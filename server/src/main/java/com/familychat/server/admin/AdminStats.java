package com.familychat.server.admin;

import com.familychat.server.model.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端聚合查询与高风险的批量数据操作（删号及其连带清理）。
 *
 * <p>与 {@link AdminDao} 的分工：本类只管"读统计"和"删号"这两件与业务表强耦合的事，
 * 管理员账号/审计日志仍归 AdminDao。
 */
public final class AdminStats {

    private AdminStats() {
    }

    // ═══════════════════════════ 概览统计 ═══════════════════════════

    /** 首页统计数字。 */
    public static Map<String, Object> collect(Database db) {
        Map<String, Object> s = new LinkedHashMap<>();
        try (Connection c = db.getConn()) {
            s.put("users", AdminSupport.scalar(c, "SELECT COUNT(*) FROM users WHERE id<>0"));
            s.put("users_today", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM users WHERE id<>0 AND date(created_at)=date('now','localtime')"));
            s.put("disabled", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM users WHERE is_disabled=1"));
            s.put("messages", AdminSupport.scalar(c, "SELECT COUNT(*) FROM messages"));
            s.put("group_messages", AdminSupport.scalar(c, "SELECT COUNT(*) FROM group_messages"));
            s.put("groups", AdminSupport.scalar(c, "SELECT COUNT(*) FROM groups"));
            s.put("balance_sum", AdminSupport.scalar(c,
                    "SELECT COALESCE(SUM(balance),0) FROM wallets"));
            s.put("transactions", AdminSupport.scalar(c, "SELECT COUNT(*) FROM transactions"));
            s.put("orders_pending", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM mall_orders WHERE status='pending'"));
            s.put("refunds_pending", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM mall_orders WHERE refund_status='pending'"));
            s.put("recharges_pending", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM admin_audit WHERE action='recharge_request' AND target LIKE '%:pending'"));
            s.put("charges_pending", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM charge_requests WHERE status='pending'"));
            s.put("audit_rows", AdminSupport.scalar(c, "SELECT COUNT(*) FROM admin_audit"));
            // ── 商城托管（审计第 4 条）：池内余额与明细条数必须一致，不一致就是账坏了 ──
            s.put("escrow_pool", AdminSupport.scalar(c,
                    "SELECT COALESCE(SUM(balance),0) FROM wallets WHERE user_id=0"));
            s.put("escrow_orders", AdminSupport.scalar(c, "SELECT COUNT(*) FROM mall_escrow"));
            s.put("escrow_detail_sum", AdminSupport.scalar(c,
                    "SELECT COALESCE(SUM(amount),0) FROM mall_escrow"));
            s.put("escrow_orders_holding", AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM mall_orders WHERE escrow=1 AND status NOT IN ('cancelled')"));
        } catch (SQLException e) {
            System.err.println("[ADMIN] collect 失败: " + AdminSupport.detail(e));
        }
        return s;
    }

    // ═══════════════════════════ 待审批：退款 ═══════════════════════════

    /** 待审批退款（refund_status=pending）分页列表，带买卖双方名称。 */
    public static Map<String, Object> pendingRefunds(Database db, int page, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        long total = 0;
        try (Connection c = db.getConn()) {
            total = AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM mall_orders WHERE refund_status='pending'");
            String sql = "SELECT o.id,o.order_no,o.buyer_id,o.seller_id,o.amount,o.quantity," +
                    "o.status,o.refund_status,o.refund_reason,o.created_at," +
                    "ub.nickname AS buyer_name, us.nickname AS seller_name, p.name AS product_name " +
                    "FROM mall_orders o " +
                    "LEFT JOIN users ub ON ub.id=o.buyer_id " +
                    "LEFT JOIN users us ON us.id=o.seller_id " +
                    "LEFT JOIN products p ON p.id=o.product_id " +
                    "WHERE o.refund_status='pending' ORDER BY o.id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, size);
                ps.setInt(2, Math.max(0, page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("id", rs.getInt("id"));
                        o.put("order_no", rs.getString("order_no"));
                        o.put("buyer_id", rs.getInt("buyer_id"));
                        o.put("seller_id", rs.getInt("seller_id"));
                        o.put("buyer_name", rs.getString("buyer_name"));
                        o.put("seller_name", rs.getString("seller_name"));
                        o.put("product_name", rs.getString("product_name"));
                        o.put("amount", rs.getLong("amount"));
                        o.put("quantity", rs.getInt("quantity"));
                        o.put("status", rs.getString("status"));
                        o.put("refund_status", rs.getString("refund_status"));
                        o.put("refund_reason", rs.getString("refund_reason"));
                        o.put("created_at", rs.getLong("created_at"));
                        list.add(o);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] pendingRefunds 失败: " + AdminSupport.detail(e));
        }
        long pages = total == 0 ? 1 : (total + size - 1) / size;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("pages", pages);
        out.put("orders", list);
        return out;
    }

    // ═══════════════════════════ 待审批：充值 ═══════════════════════════

    /**
     * 充值审批页数据。
     *
     * <p>现状说明（阶段审计结论）：App 侧充值没有"申请→审批"状态机 ——
     * {@code /api/wallet/recharge} 是白名单账号自助直充，钱立刻到账；
     * 因此这里没有"待审批充值"队列，只展示最近的 recharge 流水，
     * 并提供<b>管理员手动入账</b>（POST /admin/api/recharges）作为平台代充入口。
     * 若将来要真正的"充值申请单"，需要新增 recharge_requests 表 —— 已在报告里列为待决策项。
     */
    public static Map<String, Object> recharges(Database db, int page, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        long total = 0;
        try (Connection c = db.getConn()) {
            total = AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM transactions WHERE type='recharge'");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.id,t.to_user,t.amount,t.memo,t.status,t.created_at,u.nickname " +
                            "FROM transactions t LEFT JOIN users u ON u.id=t.to_user " +
                            "WHERE t.type='recharge' ORDER BY t.id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, size);
                ps.setInt(2, Math.max(0, page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", rs.getInt("id"));
                        r.put("to_user", rs.getInt("to_user"));
                        r.put("nickname", rs.getString("nickname"));
                        r.put("amount", rs.getLong("amount"));
                        r.put("memo", rs.getString("memo"));
                        r.put("status", rs.getString("status"));
                        r.put("created_at", rs.getLong("created_at"));
                        list.add(r);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] recharges 失败: " + AdminSupport.detail(e));
        }
        long pages = total == 0 ? 1 : (total + size - 1) / size;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("pages", pages);
        out.put("pending", 0);
        out.put("recent", list);
        return out;
    }

    // ═══════════════════════════ 待审批：收款请求 ═══════════════════════════

    public static Map<String, Object> charges(Database db, int page, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        long total = 0;
        try (Connection c = db.getConn()) {
            total = AdminSupport.scalar(c,
                    "SELECT COUNT(*) FROM charge_requests WHERE status='pending'");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.*,uf.nickname AS from_name,ut.nickname AS to_name " +
                            "FROM charge_requests r " +
                            "LEFT JOIN users uf ON uf.id=r.from_user " +
                            "LEFT JOIN users ut ON ut.id=r.to_user " +
                            "WHERE r.status='pending' ORDER BY r.id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, size);
                ps.setInt(2, Math.max(0, page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", rs.getInt("id"));
                        r.put("from_user", rs.getInt("from_user"));
                        r.put("from_name", rs.getString("from_name"));
                        r.put("to_user", rs.getInt("to_user"));
                        r.put("to_name", rs.getString("to_name"));
                        r.put("amount", rs.getLong("amount"));
                        r.put("memo", rs.getString("memo"));
                        r.put("status", rs.getString("status"));
                        r.put("created_at", rs.getLong("created_at"));
                        list.add(r);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] charges 失败: " + AdminSupport.detail(e));
        }
        long pages = total == 0 ? 1 : (total + size - 1) / size;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("pages", pages);
        out.put("requests", list);
        return out;
    }

    // ═══════════════════════════ 用户分页计数 ═══════════════════════════

    /** 带筛选条件的用户总数（与 AdminHandler.users 的 WHERE 子句保持同一份字符串）。 */
    public static long countUsers(Connection c, String where, List<Object> params) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users u" + where;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * 把 {@code users.created_at}（SQLite CURRENT_TIMESTAMP，UTC 的 "YYYY-MM-DD HH:MM:SS"）
     * 转成 epoch 毫秒给前端格式化；已经是纯数字的则按毫秒直接透传。
     */
    public static long parseCreatedAt(String s) {
        if (s == null || s.isBlank()) return 0L;
        String t = s.trim();
        if (t.matches("\\d+")) {
            long v = Long.parseLong(t);
            return v < 100_000_000_000L ? v * 1000 : v;
        }
        try {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(
                    t.replace(' ', 'T').substring(0, Math.min(19, t.replace(' ', 'T').length())));
            return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ═══════════════════════════ 删号 + 连带清理 ═══════════════════════════

    /**
     * 删除用户及其全部关联数据（单事务）。
     *
     * <p><b>为什么在一个事务里做：</b>删号跨越 20+ 张表，任何中途失败都会留下
     * "用户没了但消息还挂在别人会话里"这种半残状态。要么全成功，要么整体回滚。
     *
     * <p><b>外键策略：</b>事务内先 {@code PRAGMA foreign_keys=OFF}，提交前恢复。
     * 原因是 SQLite 的外键 CASCADE 在这里帮不上忙 —— 表定义里根本没有 ON DELETE
     * 子句，而逐个表手写 DELETE 的顺序又不能保证父子序（groups → group_messages
     * → group_message_reads 中间还夹着别的引用）。关掉约束后由本方法自己列出所有
     * 关联表并逐一清理，覆盖面是显式的、可 review 的，比隐式依赖级联更可靠。
     *
     * <p>返回各表清理行数，供审计与人工核对。
     */
    public static Map<String, Object> deleteUser(Database db, int uid) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection c = db.getConn()) {
            // PRAGMA foreign_keys 在事务内是 no-op（SQLite 规定），必须在 BEGIN 之前设置，
            // 否则约束照旧生效、删号会撞 FOREIGN KEY constraint failed。
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=OFF");
            }
            c.setAutoCommit(false);
            try {
                Map<String, Integer> counts = new LinkedHashMap<>();
                // 1) 该用户发的群消息：连同它的已读回执一起删（否则回执变孤儿，
                //    正是审计第 5 条要消灭的问题）
                counts.put("group_message_reads_for_own_msgs", exec(c,
                        "DELETE FROM group_message_reads WHERE message_id IN " +
                                "(SELECT id FROM group_messages WHERE sender_id=?)", uid));
                counts.put("group_message_reads_by_user", exec(c,
                        "DELETE FROM group_message_reads WHERE user_id=?", uid));
                // 2) 点对点消息
                counts.put("messages", exec(c,
                        "DELETE FROM messages WHERE sender_id=? OR receiver_id=?", uid, uid));
                // 3) 好友关系（双向）
                counts.put("friendships", exec(c,
                        "DELETE FROM friendships WHERE user_id=? OR friend_id=?", uid, uid));
                // 4) 群主身份：必须在删 group_members 之前判断，否则识别不出他建的群
                //    （groups.owner_id 指向一个已经不存在的成员，群将无人能管理）
                counts.put("groups_owned_deleted", exec(c,
                        "DELETE FROM groups WHERE owner_id=?", uid));
                // 5) 群成员身份 / 群会话设置 / 公告已读
                counts.put("group_members", exec(c, "DELETE FROM group_members WHERE user_id=?", uid));
                counts.put("group_settings", exec(c, "DELETE FROM group_settings WHERE user_id=?", uid));
                counts.put("group_announcement_reads", exec(c,
                        "DELETE FROM group_announcement_reads WHERE user_id=?", uid));
                // 6) 随群一起消失的内容（群已删，这些行必须跟着走，否则就是孤儿数据）
                counts.put("group_messages_orphan", exec(c,
                        "DELETE FROM group_messages WHERE group_id NOT IN (SELECT id FROM groups)"));
                counts.put("group_files_orphan", exec(c,
                        "DELETE FROM group_files WHERE group_id NOT IN (SELECT id FROM groups)"));
                counts.put("group_albums_orphan", exec(c,
                        "DELETE FROM group_albums WHERE group_id NOT IN (SELECT id FROM groups)"));
                counts.put("group_album_photos_orphan", exec(c,
                        "DELETE FROM group_album_photos WHERE group_id NOT IN (SELECT id FROM groups)"));
                counts.put("group_announcements_orphan", exec(c,
                        "DELETE FROM group_announcements WHERE group_id NOT IN (SELECT id FROM groups)"));
                counts.put("group_polls_orphan", exec(c,
                        "DELETE FROM group_polls WHERE group_id NOT IN (SELECT id FROM groups)"));
                // 该用户上传的其它群内容（群还在时）
                counts.put("group_files", exec(c, "DELETE FROM group_files WHERE uploader_id=?", uid));
                counts.put("group_album_photos", exec(c,
                        "DELETE FROM group_album_photos WHERE uploader_id=?", uid));
                counts.put("group_albums", exec(c, "DELETE FROM group_albums WHERE created_by=?", uid));
                counts.put("group_announcements", exec(c,
                        "DELETE FROM group_announcements WHERE publisher_id=?", uid));
                counts.put("group_polls", exec(c, "DELETE FROM group_polls WHERE creator_id=?", uid));
                counts.put("group_poll_votes", exec(c, "DELETE FROM group_poll_votes WHERE user_id=?", uid));
                counts.put("group_messages", exec(c, "DELETE FROM group_messages WHERE sender_id=?", uid));
                // 6) 钱包与资金
                counts.put("wallets", exec(c, "DELETE FROM wallets WHERE user_id=?", uid));
                counts.put("transactions", exec(c,
                        "DELETE FROM transactions WHERE from_user=? OR to_user=?", uid, uid));
                counts.put("charge_requests", exec(c,
                        "DELETE FROM charge_requests WHERE from_user=? OR to_user=?", uid, uid));
                // 7) 商城
                counts.put("mall_cart", exec(c, "DELETE FROM mall_cart WHERE user_id=?", uid));
                counts.put("mall_favorites", exec(c, "DELETE FROM mall_favorites WHERE user_id=?", uid));
                counts.put("mall_addresses", exec(c, "DELETE FROM mall_addresses WHERE user_id=?", uid));
                counts.put("mall_reviews", exec(c, "DELETE FROM mall_reviews WHERE user_id=?", uid));
                counts.put("mall_orders", exec(c,
                        "DELETE FROM mall_orders WHERE buyer_id=? OR seller_id=?", uid, uid));
                // 8) 密钥
                counts.put("public_keys", exec(c, "DELETE FROM public_keys WHERE user_id=?", uid));
                counts.put("key_backups", exec(c, "DELETE FROM key_backups WHERE user_id=?", uid));
                // 9) 最后删用户本体
                counts.put("users", exec(c, "DELETE FROM users WHERE id=? AND id<>0", uid));

                // 回填群公告横幅指针：删掉的公告可能正是 latest_announcement_id
                exec(c, "UPDATE groups SET latest_announcement_id=0 WHERE latest_announcement_id NOT IN " +
                        "(SELECT id FROM group_announcements)");
                // 清理商品收藏/购物车里指向已删商品的孤儿行（删号可能带走商品）
                exec(c, "DELETE FROM mall_cart WHERE product_id NOT IN (SELECT id FROM products)");
                exec(c, "DELETE FROM mall_favorites WHERE product_id NOT IN (SELECT id FROM products)");

                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA foreign_keys=ON");
                }
                c.commit();
                result.putAll(counts);
                result.put("ok", true);
            } catch (SQLException e) {
                c.rollback();
                // 回滚后务必把外键约束恢复，否则这条连接（可能被池化复用）会一直无约束
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA foreign_keys=ON");
                } catch (SQLException ignored) {
                    // 恢复失败只能记日志；连接已回滚，数据是一致的
                }
                System.err.println("[ADMIN] deleteUser 回滚: " + AdminSupport.detail(e));
                result.clear();
                result.put("error", "删除失败已回滚: " + e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            result.clear();
            result.put("error", "数据库错误: " + e.getMessage());
        }
        return result;
    }

    /** 执行带可变参数的 DELETE/UPDATE，返回受影响行数；失败抛 SQLException 让事务回滚。 */
    private static int exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }
}
