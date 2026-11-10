package com.familychat.server;

import com.familychat.server.model.Database;
import com.familychat.server.model.dao.GroupMaintenance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计第 5 条：孤儿回执 + 磁盘回收。
 *
 * <p>三条必须锁住的性质：
 * <ol>
 *   <li>成员离开群后，他的已读回执/群设置/公告已读不能再留在库里；</li>
 *   <li>删除群文件/解散群后，磁盘上"确实没人引用"的附件要被回收；</li>
 *   <li><b>仍被引用的文件一个都不能删</b> —— 同一个 /chat_files/x 可能同时被
 *       单聊消息、群消息、群文件库引用，误删就是用户附件永久丢失。</li>
 * </ol>
 */
class GroupOrphanTest extends DbTestSupport {

    private static final Path CHAT_DIR = Paths.get(Database.DATA_DIR, "chat_files");

    @Test
    void leavingGroupRemovesReadReceiptsAndSettings() {
        int owner = register("owner");
        int member = register("member");
        int gid = db.createGroup(owner, "退群测试群", List.of(member));
        assertTrue(gid > 0);
        long msgId = saveGroupMsg(gid, owner, "hi");

        // 造出：已读回执 + 群设置 + 公告已读（走真实 DAO，不手写 SQL）
        insertRead(msgId, member);
        db.setGroupSetting(gid, member, 1, 1);
        Map<String, Object> ann = db.createAnnouncement(gid, owner, "{\"1\":\"公告\"}", 0);
        int annId = ((Number) ann.get("id")).intValue();
        db.markAnnouncementRead(annId, member);

        assertEquals(1, count("group_message_reads", "user_id=" + member));
        assertEquals(1, count("group_settings", "user_id=" + member));
        assertEquals(1, count("group_announcement_reads", "user_id=" + member));

        // 真退群
        assertTrue(db.removeGroupMember(gid, member));
        Map<String, Integer> cleaned = db.cleanupAfterGroupMemberRemoved(gid, member);

        assertEquals(1, (int) cleaned.get("group_message_reads"), "应清掉 1 条已读回执");
        assertEquals(1, (int) cleaned.get("group_settings"), "应清掉 1 条群设置");
        assertEquals(1, (int) cleaned.get("group_announcement_reads"), "应清掉 1 条公告已读");

        assertEquals(0, count("group_message_reads", "user_id=" + member), "退群后回执必须清零");
        assertEquals(0, count("group_settings", "user_id=" + member), "退群后群设置必须清零");
        assertEquals(0, count("group_announcement_reads", "user_id=" + member), "退群后公告已读必须清零");
        // 别人的数据不受影响
        assertEquals(0, count("group_members", "group_id=" + gid + " AND user_id=" + member));
        assertTrue(count("group_members", "group_id=" + gid + " AND user_id=" + owner) > 0, "群主仍在群里");
        // 群消息本体保留（别人聊天记录的一部分）
        assertTrue(count("group_messages", "group_id=" + gid) > 0, "群消息不应被连带删除");
    }

    @Test
    void deletingGroupFileReclaimsUnreferencedDiskFile() throws Exception {
        int owner = register("owner");
        int gid = db.createGroup(owner, "文件群", List.of());
        String name = uniqueName("file");
        Path f = writeChatFile(name, 2048);

        int fileId = db.indexGroupFile(gid, owner, "报告.pdf", "/chat_files/" + name,
                "", 2048, "全部", "file", 0);
        assertTrue(fileId > 0);
        assertEquals(1, count("group_files", "id=" + fileId));

        List<String> urls = db.fileUrlsOfGroupFile(fileId);
        assertEquals(1, urls.size());
        db.deleteGroupFile(fileId);
        Map<String, Object> res = db.reclaimFiles(urls, false);

        assertEquals(1, (int) res.get("deleted"), "无引用的文件应被删除: " + res);
        assertFalse(Files.exists(f), "磁盘文件应已消失");
        assertTrue((long) res.get("bytesFreed") >= 2048);
    }

    @Test
    void sharedFileIsNotDeletedWhileStillReferenced() throws Exception {
        int owner = register("owner");
        int other = register("other");
        int gid = db.createGroup(owner, "共享群", List.of());
        String name = uniqueName("shared");
        Path f = writeChatFile(name, 1024);
        String url = "/chat_files/" + name;

        // 同一个 URL 同时被：群文件库 + 单聊消息 引用
        int fileId = db.indexGroupFile(gid, owner, "共享.jpg", url, "", 1024, "全部", "image", 0);
        db.saveMessage(owner, other, url, "image");
        assertTrue(db.isChatFileReferenced(name), "两处引用，判定应为被引用");

        // 删掉群文件库那条记录：文件仍被单聊消息引用 → 绝不能删
        List<String> urls = db.fileUrlsOfGroupFile(fileId);
        db.deleteGroupFile(fileId);
        Map<String, Object> res = db.reclaimFiles(urls, false);

        assertEquals(0, (int) res.get("deleted"), "仍有引用时不得删除");
        assertEquals(1, (int) res.get("skippedReferenced"));
        assertTrue(Files.exists(f), "★ 共享文件必须保留");

        // 再删掉最后一条引用 → 这次可以删
        deleteMessagesMentioning(url);
        assertFalse(db.isChatFileReferenced(name));
        Map<String, Object> res2 = db.reclaimFiles(urls, false);
        assertEquals(1, (int) res2.get("deleted"));
        assertFalse(Files.exists(f), "引用清空后才可回收");
    }

    @Test
    void dryRunNeverDeletesAnything() throws Exception {
        String name = uniqueName("dry");
        Path f = writeChatFile(name, 512);
        Map<String, Object> res = db.reclaimFiles(List.of("/chat_files/" + name), true);

        assertEquals(1, (int) res.get("deleted"), "dry-run 应报告'将会删除'的数量");
        assertEquals(Boolean.TRUE, res.get("dryRun"));
        assertTrue(Files.exists(f), "★ dry-run 绝不能真的删文件");
    }

    @Test
    void pathTraversalUrlsAreRejected() {
        assertNull(GroupMaintenance.chatFileName("/chat_files/../../etc/passwd"));
        assertNull(GroupMaintenance.chatFileName("/chat_files/a/b.png"));
        assertNull(GroupMaintenance.chatFileName("http://evil.com/x.png"));
        assertNull(GroupMaintenance.chatFileName(null));
        assertEquals("abc_1.png", GroupMaintenance.chatFileName("/chat_files/abc_1.png"));
        assertEquals("abc_1.png", GroupMaintenance.chatFileName("/chat_files/abc_1.png?t=1"));
    }

    @Test
    void unknownFileIsTreatedAsReferenced() {
        // 判定不了/查询失败一律保守：宁可留着也不误删
        assertTrue(db.isChatFileReferenced(null));
        assertTrue(db.isChatFileReferenced(""));
        assertFalse(db.isChatFileReferenced("definitely_no_such_file_xyz.png"));
    }

    @Test
    void sweepFindsAndCleansOrphans() throws Exception {
        int owner = register("owner");
        int member = register("member");
        int gid = db.createGroup(owner, "孤儿群", List.of(member));
        long msgId = saveGroupMsg(gid, owner, "x");
        insertRead(msgId, member);
        db.setGroupSetting(gid, member, 1, 0);
        // 故意造两条"引用已断"的孤儿（真实线上怎么产生孤儿：先有引用，父行被别处删掉）。
        // 外键默认开启，所以这里显式关掉约束再插入 —— 这正是"手工改库/历史遗留"会留下的脏数据。
        execFkOff("INSERT INTO group_message_reads (message_id,user_id) VALUES (?,?)", 999999, member);
        execFkOff("INSERT INTO group_settings (group_id,user_id,pinned,muted) VALUES (?,?,0,0)",
                gid + 5000, member);
        // 磁盘孤儿
        String orphanName = uniqueName("orphan");
        Path orphan = writeChatFile(orphanName, 4096);

        // 只读扫描：不得修改任何东西
        Map<String, Object> scan = db.sweepOrphans(true, 50);
        assertNull(scan.get("error"), "扫描不应报错: " + scan.get("error"));
        assertTrue((int) scan.get("dbOrphanTotal") > 0, "应发现库内孤儿: " + scan.get("dbOrphanCounts"));
        assertTrue((int) scan.get("unreferencedFileCount") >= 1, "应发现磁盘孤儿");
        assertNull(scan.get("dbOrphanCleaned"), "只读扫描不得返回已清理结果");
        assertTrue(count("group_message_reads", "message_id=999999") > 0, "扫描不得删数据");
        assertTrue(Files.exists(orphan), "扫描不得删文件");

        // 执行清理
        Map<String, Object> clean = db.sweepOrphans(false, 50);
        assertNotNull(clean.get("dbOrphanCleaned"), "应返回清理明细");
        assertEquals(0, count("group_message_reads", "message_id=999999"), "孤儿回执应被清掉");
        assertEquals(0, count("group_settings", "group_id=" + (gid + 5000)), "孤儿群设置应被清掉");
        // 正常成员的数据不能被误清
        assertEquals(1, count("group_settings", "group_id=" + gid + " AND user_id=" + member),
                "★ 正常成员的群设置不得被误删");

        // 磁盘：sweep 本身只报告，reclaimFiles 才删
        Map<String, Object> res = db.reclaimFiles(List.of("/chat_files/" + orphanName), false);
        assertEquals(1, (int) res.get("deleted"));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void dissolvingGroupCleansAllChildRows() {
        int owner = register("owner");
        int member = register("member");
        int gid = db.createGroup(owner, "解散测试群", List.of(member));
        long msgId = saveGroupMsg(gid, owner, "x");
        insertRead(msgId, member);
        db.setGroupSetting(gid, member, 1, 0);

        assertTrue(db.dissolveGroup(gid));
        assertEquals(0, count("group_members", "group_id=" + gid));
        assertEquals(0, count("group_messages", "group_id=" + gid));
        assertEquals(0, count("group_settings", "group_id=" + gid));
        assertEquals(0, count("group_message_reads", "message_id=" + msgId), "解散后回执不得残留");
        assertEquals(0, count("groups", "id=" + gid));
    }

    // ── 助手 ──

    /** 发一条群消息并返回其 id（保存失败会直接 fail，避免后续断言拿 0 去误导）。 */
    private long saveGroupMsg(int gid, int sender, String text) {
        Map<String, Object> m = db.saveGroupMessage(gid, sender, text, "text", "", "", 0);
        assertNotNull(m, "群消息保存失败（发送者不是群成员？）");
        Object id = m.get("id");
        assertNotNull(id, "群消息返回值缺少 id: " + m);
        return ((Number) id).longValue();
    }

    private Path writeChatFile(String name, int bytes) throws IOException {
        Files.createDirectories(CHAT_DIR);
        Path p = CHAT_DIR.resolve(name);
        byte[] buf = new byte[bytes];
        java.util.Arrays.fill(buf, (byte) 7);
        Files.write(p, buf);
        return p;
    }

    private static String uniqueName(String tag) {
        return System.nanoTime() + "_" + tag + ".bin";
    }

    private void insertRead(long messageId, int userId) {
        exec("INSERT OR IGNORE INTO group_message_reads (message_id,user_id) VALUES (?,?)",
                messageId, userId);
    }

    private void deleteMessagesMentioning(String url) {
        exec("DELETE FROM messages WHERE content LIKE ?", "%" + url + "%");
    }

    private void exec(String sql, Object... params) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            fail("SQL 失败 " + sql + ": " + e.getMessage());
        }
    }

    /** 关闭外键约束后执行：用于刻意制造"父行已不存在"的孤儿数据。 */
    private void execFkOff(String sql, Object... params) {
        try (Connection c = db.getConn()) {
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=OFF");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            fail("SQL 失败 " + sql + ": " + e.getMessage());
        }
    }

    private long count(String table, String where) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            fail("count 失败 " + table + ": " + e.getMessage());
            return -1;
        }
    }
}
