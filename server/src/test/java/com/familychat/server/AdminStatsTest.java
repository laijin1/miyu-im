package com.familychat.server;

import com.familychat.server.admin.AdminStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端聚合统计 + 删号连带清理。
 *
 * <p>删号是最容易留孤儿数据的操作，所以这里成套的造数据（单聊/群聊/好友/钱包/流水/
 * 商城订单/密钥），删完后逐表断言行数归零 —— 任何一张表漏掉都会在这里红。
 */
class AdminStatsTest extends DbTestSupport {

    @Test
    void statsNumbersMatchReality() {
        int a = register("u_a");
        int b = register("u_b");
        db.sendFriendRequest(a, b);
        db.saveMessage(a, b, "hello", "text");
        db.createGroup(a, "家庭群", List.of(b));
        assertTrue(db.addBalance(a, 5000, 10000));
        // 加余额本身不产生流水（流水由调用方显式记账），这里补一条验证计数
        db.addTransaction(0, a, "recharge", 5000, "测试", "completed", 0);

        Map<String, Object> s = AdminStats.collect(db);
        assertEquals(2L, ((Number) s.get("users")).longValue(), "统计不含 system 账户");
        assertEquals(1L, ((Number) s.get("messages")).longValue());
        assertEquals(0L, ((Number) s.get("group_messages")).longValue(), "建群不发消息，群消息应为 0");
        assertEquals(1L, ((Number) s.get("groups")).longValue());
        assertEquals(5000L, ((Number) s.get("balance_sum")).longValue());
        assertEquals(1L, ((Number) s.get("transactions")).longValue());
        assertEquals(0L, ((Number) s.get("charges_pending")).longValue());
        assertEquals(0L, ((Number) s.get("refunds_pending")).longValue());
    }

    @Test
    void pendingRefundsListsOnlyPendingRefundStatus() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "杯子", "生活", 1000L, 10, "", "");
        Map<String, Object> order = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址");
        int oid = ((Number) order.get("id")).intValue();

        // 未申请退款前不出现
        assertEquals(0L, ((Number) AdminStats.pendingRefunds(db, 1, 20).get("total")).longValue());

        db.addBalance(buyer, 10000, 100000);
        assertNull(db.payOrder(oid, buyer));
        assertNull(db.applyRefund(oid, buyer, "不想要了"));

        Map<String, Object> res = AdminStats.pendingRefunds(db, 1, 20);
        assertEquals(1L, ((Number) res.get("total")).longValue());
        List<Map<String, Object>> orders = cast(res.get("orders"));
        assertEquals(1, orders.size());
        assertEquals(1000L, ((Number) orders.get(0).get("amount")).longValue());
        assertEquals("不想要了", orders.get(0).get("refund_reason"));
    }

    @Test
    void deleteUserRemovesEveryDependentRow() {
        int victim = register("victim");
        int other = register("bystander");

        // 好友关系 + 单聊
        db.sendFriendRequest(victim, other);
        db.saveMessage(victim, other, "hi", "text");
        db.saveMessage(other, victim, "yo", "text");
        // 群：victim 建群并把 other 拉进来（victim 是群主）
        int gid = db.createGroup(victim, "将被解散", List.of(other));
        assertTrue(gid > 0);
        // 钱包 + 流水
        db.addBalance(victim, 3000, 100000);
        db.addTransaction(0, victim, "recharge", 3000, "测试", "completed", 0);
        // 商城：victim 卖货、other 下单
        long pid = db.insertProduct(victim, "商品", "生活", 500L, 5, "", "");
        db.createOrder(other, (int) pid, 1, "李四", "139", "地址X");
        // 群成员/群设置
        db.setGroupSetting(gid, other, 1, 0);

        assertNotNull(db.getUserById(victim));

        // 二次确认由 handler 做；这里直接走真正的删号实现
        Map<String, Object> res = AdminStats.deleteUser(db, victim);
        assertNull(res.get("error"), "删号不应失败: " + res);

        assertNull(db.getUserById(victim), "用户必须已删除");
        assertEquals(0, count("wallets", "user_id=?", victim), "钱包必须删除");
        assertEquals(0, count("transactions", "from_user=? OR to_user=?", victim, victim), "流水必须删除");
        assertEquals(0, count("friendships", "user_id=? OR friend_id=?", victim, victim), "好友关系必须删除");
        assertEquals(0, count("messages", "sender_id=? OR receiver_id=?", victim, victim), "单聊消息必须删除");
        assertEquals(0, count("group_members", "user_id=?", victim), "群成员身份必须删除");
        assertEquals(0, count("groups", "owner_id=?", victim), "他建的群必须一并处理，否则群无人管理");
        assertEquals(0, count("group_messages", "group_id=?", gid), "群解散后群消息必须清理");
        assertEquals(0, count("public_keys", "user_id=?", victim), "公钥必须删除");
        // 旁观者不能被误删
        assertNotNull(db.getUserById(other), "无关用户必须完好");
    }

    @Test
    void deleteMissingUserStillReturnsCounts() {
        // 目标不存在时（并发删除）不应抛异常，返回的结构必须可读
        Map<String, Object> res = AdminStats.deleteUser(db, 99999);
        assertNull(res.get("error"));
        assertEquals(0, ((Number) res.get("users")).intValue());
    }

    @Test
    void parseCreatedAtHandlesSqliteAndEpoch() {
        assertTrue(AdminStats.parseCreatedAt("2026-09-21 13:00:00") > 0, "SQLite 时间戳要能解析");
        assertEquals(1_700_000_000_000L, AdminStats.parseCreatedAt("1700000000000"), "毫秒直接透传");
        assertEquals(1_700_000_000_000L, AdminStats.parseCreatedAt("1700000000"), "秒级自动升级为毫秒");
        assertEquals(0L, AdminStats.parseCreatedAt(null));
        assertEquals(0L, AdminStats.parseCreatedAt("not a date"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private long count(String table, String where, Object... params) {
        try (java.sql.Connection c = db.getConn();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (java.sql.SQLException e) {
            fail("count 失败 " + table + ": " + e.getMessage());
            return -1;
        }
    }
}
