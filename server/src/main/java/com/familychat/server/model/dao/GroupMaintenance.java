package com.familychat.server.model.dao;

import com.familychat.server.model.Database;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据卫生：孤儿回执清理 + 磁盘文件回收（审计第 5 条）。
 *
 * <p>本类只处理"引用已经断了、但数据/文件还在"的东西。两条铁律：
 * <ol>
 *   <li><b>先删引用它的数据库行，再删文件</b>；删除前一定确认没有别的行还在引用该文件
 *       （同一个 {@code /chat_files/x} URL 可能同时出现在单聊消息、群消息、群文件库里）；</li>
 *   <li><b>默认只报告不删除</b>。真正开删必须显式传 {@code dryRun=false}，
 *       因为"这个文件没被引用"一旦判断错，删掉的是用户的不可恢复附件。</li>
 * </ol>
 */
public final class GroupMaintenance {

    /** 聊天附件目录（与 FileHandler.CHAT_DIR 保持一致）。 */
    private static final Path CHAT_DIR = Paths.get(Database.DATA_DIR, "chat_files");

    private final Database db;

    public GroupMaintenance(Database db) {
        this.db = db;
    }

    // ═══════════════════════ 退群/踢人：连带清理回执 ═══════════════════════

    /**
     * 成员离开群（主动退群或被踢）时的连带清理。
     *
     * <p>清理对象：
     * <ul>
     *   <li>{@code group_message_reads}：他在这条群里的已读回执。<b>必须删</b> ——
     *       成员已经不在群里，还留着回执就是审计第 5 条说的孤儿行；不删的话
     *       重新入群时旧的"已读"会让新消息直接显示成已读；</li>
     *   <li>{@code group_settings}：置顶/免打扰设置。同理，退群后留着没有意义，
     *       重新入群时还会继承一个莫名其妙的免打扰状态；</li>
     *   <li>{@code group_announcement_reads}：该群公告的已读回执。</li>
     * </ul>
     *
     * <p>不删的：群消息本体（他发过的消息仍是其他成员聊天记录的一部分，
     * 删掉会让别人的对话出现空洞，而消息内容本身是密文、无隐私泄露面）。
     *
     * <p>返回各表清理行数。调用方应在成功删除 {@code group_members} 行之后调用。
     */
    public Map<String, Integer> cleanupAfterMemberRemoved(int groupId, int userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                // 该群的全部消息 id（回执按 message_id+user_id 存）
                counts.put("group_message_reads", exec(c,
                        "DELETE FROM group_message_reads WHERE user_id=? AND message_id IN " +
                                "(SELECT id FROM group_messages WHERE group_id=?)", userId, groupId));
                counts.put("group_settings", exec(c,
                        "DELETE FROM group_settings WHERE group_id=? AND user_id=?", groupId, userId));
                counts.put("group_announcement_reads", exec(c,
                        "DELETE FROM group_announcement_reads WHERE user_id=? AND announcement_id IN " +
                                "(SELECT id FROM group_announcements WHERE group_id=?)", userId, groupId));
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] cleanupAfterMemberRemoved: " + e.getMessage());
                counts.clear();
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] cleanupAfterMemberRemoved: " + e.getMessage());
        }
        return counts;
    }

    // ═══════════════════════ 删群文件：连带删磁盘文件 ═══════════════════════

    /**
     * 删除群文件记录<b>之前</b>，先取出它引用的磁盘文件。
     *
     * <p>调用顺序必须是"先记下 URL → 删数据库行 → 再删文件"：
     * 反过来的话，万一删库失败就成了"文件没了但记录还在"（下载 404），
     * 比"记录没了但文件还在"（占点磁盘）严重得多。
     */
    public List<String> fileUrlsOfGroupFile(int fileId) {
        return urlsOf("SELECT file_url FROM group_files WHERE id=?", fileId);
    }

    /** 删群相册前取出该相册所有照片/缩略图 URL。 */
    public List<String> fileUrlsOfAlbum(int albumId) {
        List<String> urls = new ArrayList<>();
        urls.addAll(urlsOf("SELECT photo_url FROM group_album_photos WHERE album_id=?", albumId));
        urls.addAll(urlsOf("SELECT thumbnail FROM group_album_photos WHERE album_id=?", albumId));
        return urls;
    }

    /** 删照片前取出其 URL 与缩略图。 */
    public List<String> fileUrlsOfAlbumPhoto(int photoId) {
        List<String> urls = new ArrayList<>();
        urls.addAll(urlsOf("SELECT photo_url FROM group_album_photos WHERE id=?", photoId));
        urls.addAll(urlsOf("SELECT thumbnail FROM group_album_photos WHERE id=?", photoId));
        return urls;
    }

    /** 解散群前取出该群全部文件/相册照片 URL。 */
    public List<String> fileUrlsOfGroup(int groupId) {
        List<String> urls = new ArrayList<>();
        urls.addAll(urlsOf("SELECT file_url FROM group_files WHERE group_id=?", groupId));
        urls.addAll(urlsOf("SELECT photo_url FROM group_album_photos WHERE group_id=?", groupId));
        urls.addAll(urlsOf("SELECT thumbnail FROM group_album_photos WHERE group_id=?", groupId));
        return urls;
    }

    /**
     * 删除磁盘上已无人引用的聊天附件。
     *
     * <p>对每个候选 URL：先转成 {@code chat_files} 下的相对文件名（拒绝路径穿越），
     * 再查 {@link #isFileReferenced}；只有确定无引用才删。
     *
     * <p>{@code dryRun=true} 时只统计不删。
     *
     * @return {candidates, deleted, skippedReferenced, skippedMissing, bytesFreed}
     */
    public Map<String, Object> reclaimFiles(List<String> urls, boolean dryRun) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (String u : urls) {
            String n = chatFileName(u);
            if (n != null) names.add(n);
        }
        int deleted = 0, referenced = 0, missing = 0;
        long freed = 0;
        for (String name : names) {
            Path p = CHAT_DIR.resolve(name).normalize();
            if (!p.startsWith(CHAT_DIR.normalize())) {           // 双保险：路径穿越直接跳过
                System.err.println("[ORPHAN] 可疑路径已跳过: " + name);
                continue;
            }
            if (!Files.exists(p)) {
                missing++;
                continue;
            }
            if (isFileReferenced(name)) {
                referenced++;
                continue;
            }
            if (dryRun) {
                deleted++;
                freed += sizeOf(p);
                continue;
            }
            try {
                freed += sizeOf(p);
                Files.deleteIfExists(p);
                deleted++;
            } catch (IOException e) {
                System.err.println("[ORPHAN] 删除失败 " + p + ": " + e.getMessage());
            }
        }
        out.put("candidates", names.size());
        out.put("deleted", deleted);
        out.put("skippedReferenced", referenced);
        out.put("skippedMissing", missing);
        out.put("bytesFreed", freed);
        out.put("dryRun", dryRun);
        return out;
    }

    /**
     * 该文件名是否仍被任何数据库行引用。
     *
     * <p>四张表都要查：单聊消息(content/thumbnail/filename)、群消息、群文件库、群相册。
     * 只查一张就删 = 误删别人还在用的附件。
     *
     * <p>用 LIKE 做后缀匹配（URL 以 {@code /chat_files/名字} 结尾）。
     *
     * <p><b>⚠️ 已知局限（2026-09 实测，务必先读）：</b>App 上传附件时，服务端返回
     * {@code {url: "/chat_files/<生成名>", name: "<原始文件名>"}}，而客户端随后保存消息时
     * 只回传了<b>原始文件名</b>、content 里是 E2EE 密文 —— 也就是说
     * <b>{@code /chat_files/<生成名>} 这个 URL 从来没有落过库</b>。
     * 实测：data/chat_files 有 119 个文件，messages 里能匹配到 URL 的只有 3 条。
     *
     * <p>因此本方法返回 false <b>不等于</b>"这个文件没人在用"。它只适用于
     * "刚刚删掉了最后一条引用该 URL 的库记录"这种<b>已知上下文</b>（删群文件/删相册/解散群），
     * 那种场景下我们手里就攥着 URL，判断是可靠的。
     * 绝不能拿它去批量清理"看起来没人引用"的历史文件。
     */
    public boolean isFileReferenced(String fileName) {
        if (fileName == null || fileName.isEmpty()) return true;   // 判断不了就当作"被引用"，宁可不删
        String suffix = "/chat_files/" + fileName;
        String[] queries = {
            "SELECT 1 FROM messages WHERE content LIKE ? OR thumbnail LIKE ? OR filename LIKE ? LIMIT 1",
            "SELECT 1 FROM group_messages WHERE content LIKE ? OR thumbnail LIKE ? OR filename LIKE ? LIMIT 1",
            "SELECT 1 FROM group_files WHERE file_url LIKE ? OR thumbnail LIKE ? LIMIT 1",
            "SELECT 1 FROM group_album_photos WHERE photo_url LIKE ? OR thumbnail LIKE ? LIMIT 1",
        };
        try (Connection c = db.getConn()) {
            for (String q : queries) {
                int slots = q.split("\\?", -1).length - 1;
                try (PreparedStatement ps = c.prepareStatement(q)) {
                    for (int i = 1; i <= slots; i++) ps.setString(i, "%" + suffix);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return true;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ORPHAN] 引用检查失败，按'已引用'处理: " + e.getMessage());
            return true;   // 查询失败一律保守：不删
        }
        return false;
    }

    // ═══════════════════════ 存量孤儿扫描（一次性脚本 + 定期任务共用） ═══════════════════════

    /**
     * 全局扫描并回收孤儿数据。
     *
     * <p>三类孤儿：
     * <ol>
     *   <li><b>数据库孤儿</b>：指向已不存在的群/消息/用户的回执与子表行 —— 直接删；</li>
     *   <li><b>磁盘孤儿</b>：chat_files 里已无任何数据库行引用的文件 —— 默认只报告；</li>
     *   <li><b>引用完整性检查</b>：统计一遍，便于和人工判断对照。</li>
     * </ol>
     *
     * @param dryRun true = 只统计并列出，不动任何数据（默认调用方式）
     * @param maxList 报告里最多列几条明细（避免刷屏，也给调用方控制体量）
     */
    public Map<String, Object> sweep(boolean dryRun, int maxList) {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Integer> dbOrphans = new LinkedHashMap<>();
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                // 1) 回执指向不存在的消息
                dbOrphans.put("reads_of_missing_group_messages", countOrphan(c,
                        "SELECT COUNT(*) FROM group_message_reads r WHERE NOT EXISTS " +
                                "(SELECT 1 FROM group_messages m WHERE m.id=r.message_id)"));
                // 2) 回执属于已不在群里的成员 —— 审计第 5 条的主目标
                dbOrphans.put("reads_of_non_members", countOrphan(c,
                        "SELECT COUNT(*) FROM group_message_reads r WHERE NOT EXISTS " +
                                "(SELECT 1 FROM group_messages m JOIN group_members gm " +
                                " ON gm.group_id=m.group_id AND gm.user_id=r.user_id " +
                                " WHERE m.id=r.message_id)"));
                // 3) 回执属于不存在的用户
                dbOrphans.put("reads_of_missing_users", countOrphan(c,
                        "SELECT COUNT(*) FROM group_message_reads r WHERE NOT EXISTS " +
                                "(SELECT 1 FROM users u WHERE u.id=r.user_id)"));
                // 4) 群设置指向不存在的群/用户/成员
                dbOrphans.put("settings_of_missing_groups", countOrphan(c,
                        "SELECT COUNT(*) FROM group_settings s WHERE NOT EXISTS " +
                                "(SELECT 1 FROM groups g WHERE g.id=s.group_id)"));
                dbOrphans.put("settings_of_non_members", countOrphan(c,
                        "SELECT COUNT(*) FROM group_settings s WHERE NOT EXISTS " +
                                "(SELECT 1 FROM group_members m WHERE m.group_id=s.group_id " +
                                " AND m.user_id=s.user_id)"));
                // 5) 公告已读指向不存在的公告
                dbOrphans.put("announce_reads_of_missing", countOrphan(c,
                        "SELECT COUNT(*) FROM group_announcement_reads r WHERE NOT EXISTS " +
                                "(SELECT 1 FROM group_announcements a WHERE a.id=r.announcement_id)"));
                // 6) 群消息指向不存在的群
                dbOrphans.put("group_messages_of_missing_groups", countOrphan(c,
                        "SELECT COUNT(*) FROM group_messages m WHERE NOT EXISTS " +
                                "(SELECT 1 FROM groups g WHERE g.id=m.group_id)"));
                // 7) 群成员指向不存在的群
                dbOrphans.put("members_of_missing_groups", countOrphan(c,
                        "SELECT COUNT(*) FROM group_members m WHERE NOT EXISTS " +
                                "(SELECT 1 FROM groups g WHERE g.id=m.group_id)"));
                // 8) 群文件/相册指向不存在的群
                dbOrphans.put("group_files_of_missing_groups", countOrphan(c,
                        "SELECT COUNT(*) FROM group_files f WHERE NOT EXISTS " +
                                "(SELECT 1 FROM groups g WHERE g.id=f.group_id)"));
                dbOrphans.put("album_photos_of_missing_groups", countOrphan(c,
                        "SELECT COUNT(*) FROM group_album_photos p WHERE NOT EXISTS " +
                                "(SELECT 1 FROM groups g WHERE g.id=p.group_id)"));
                // 9) 公告横幅指针指向不存在的公告
                dbOrphans.put("groups_with_dangling_latest_announcement", countOrphan(c,
                        "SELECT COUNT(*) FROM groups g WHERE g.latest_announcement_id<>0 AND NOT EXISTS " +
                                "(SELECT 1 FROM group_announcements a WHERE a.id=g.latest_announcement_id)"));
                // 10) 托管明细指向不存在的订单（审计第 4 条）
                dbOrphans.put("escrow_of_missing_orders", countOrphan(c,
                        "SELECT COUNT(*) FROM mall_escrow e WHERE NOT EXISTS " +
                                "(SELECT 1 FROM mall_orders o WHERE o.id=e.order_id)"));

                int totalOrphans = dbOrphans.values().stream().mapToInt(Integer::intValue).sum();
                report.put("dbOrphanCounts", dbOrphans);
                report.put("dbOrphanTotal", totalOrphans);

                if (!dryRun && totalOrphans > 0) {
                    Map<String, Integer> cleaned = new LinkedHashMap<>();
                    cleaned.put("reads_of_missing_group_messages", exec(c,
                            "DELETE FROM group_message_reads WHERE message_id NOT IN " +
                                    "(SELECT id FROM group_messages)"));
                    cleaned.put("reads_of_non_members", exec(c,
                            "DELETE FROM group_message_reads WHERE message_id IN " +
                                    "(SELECT m.id FROM group_messages m WHERE NOT EXISTS (" +
                                    " SELECT 1 FROM group_members gm WHERE gm.group_id=m.group_id " +
                                    " AND gm.user_id=group_message_reads.user_id))"));
                    cleaned.put("reads_of_missing_users", exec(c,
                            "DELETE FROM group_message_reads WHERE user_id NOT IN (SELECT id FROM users)"));
                    cleaned.put("settings_of_missing_groups", exec(c,
                            "DELETE FROM group_settings WHERE group_id NOT IN (SELECT id FROM groups)"));
                    cleaned.put("settings_of_non_members", exec(c,
                            "DELETE FROM group_settings WHERE NOT EXISTS (SELECT 1 FROM group_members m " +
                                    " WHERE m.group_id=group_settings.group_id " +
                                    " AND m.user_id=group_settings.user_id)"));
                    cleaned.put("announce_reads_of_missing", exec(c,
                            "DELETE FROM group_announcement_reads WHERE announcement_id NOT IN " +
                                    "(SELECT id FROM group_announcements)"));
                    cleaned.put("group_messages_of_missing_groups", exec(c,
                            "DELETE FROM group_messages WHERE group_id NOT IN (SELECT id FROM groups)"));
                    cleaned.put("members_of_missing_groups", exec(c,
                            "DELETE FROM group_members WHERE group_id NOT IN (SELECT id FROM groups)"));
                    cleaned.put("group_files_of_missing_groups", exec(c,
                            "DELETE FROM group_files WHERE group_id NOT IN (SELECT id FROM groups)"));
                    cleaned.put("album_photos_of_missing_groups", exec(c,
                            "DELETE FROM group_album_photos WHERE group_id NOT IN (SELECT id FROM groups)"));
                    cleaned.put("groups_with_dangling_latest_announcement", exec(c,
                            "UPDATE groups SET latest_announcement_id=0 WHERE latest_announcement_id<>0 " +
                                    "AND latest_announcement_id NOT IN (SELECT id FROM group_announcements)"));
                    cleaned.put("escrow_of_missing_orders", exec(c,
                            "DELETE FROM mall_escrow WHERE order_id NOT IN (SELECT id FROM mall_orders)"));
                    c.commit();
                    report.put("dbOrphanCleaned", cleaned);
                } else {
                    c.rollback();   // dry-run 什么都不留
                }
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ORPHAN] 库内孤儿处理失败: " + e.getMessage());
                report.put("error", e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            report.put("error", e.getMessage());
        }

        // 磁盘孤儿：列出未被引用的 chat_files
        List<String> unreferenced = new ArrayList<>();
        long bytes = 0;
        if (Files.isDirectory(CHAT_DIR)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(CHAT_DIR)) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    if (isFileReferenced(p.getFileName().toString())) continue;
                    bytes += sizeOf(p);
                    if (unreferenced.size() < maxList) unreferenced.add(p.getFileName().toString());
                }
            } catch (IOException e) {
                System.err.println("[ORPHAN] 扫描 chat_files 失败: " + e.getMessage());
            }
        }
        report.put("unreferencedFilesListed", unreferenced);
        report.put("unreferencedFileCount", unreferenced.size());
        report.put("unreferencedBytes", bytes);
        report.put("chatDir", CHAT_DIR.toAbsolutePath().toString());
        report.put("dryRun", dryRun);
        return report;
    }

    // ═══════════════════════ 助手 ═══════════════════════

    /**
     * {@code /chat_files/xxx} → {@code xxx}；不是本站聊天附件则返回 null。
     *
     * <p>只接受纯文件名（不含路径分隔符与 {@code ..}）：防止用构造出来的 URL
     * 把删除动作引到 chat_files 之外。
     */
    public static String chatFileName(String url) {
        if (url == null) return null;
        String prefix = "/chat_files/";
        int idx = url.indexOf(prefix);
        if (idx < 0) return null;
        String name = url.substring(idx + prefix.length());
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        return name;
    }

    private List<String> urlsOf(String sql, int id) {
        List<String> out = new ArrayList<>();
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isEmpty()) out.add(v);
                }
            }
        } catch (SQLException e) {
            System.err.println("[ORPHAN] url 查询失败: " + e.getMessage());
        }
        return out;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static int countOrphan(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int exec(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    /** 带绑定参数的 DELETE/UPDATE，返回受影响行数。 */
    private static int exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }
}
