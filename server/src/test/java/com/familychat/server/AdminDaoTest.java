package com.familychat.server;

import com.familychat.server.admin.AdminDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端数据访问：账号表、审计日志（写入 / 分页 / 筛选）、以及被禁用的 App 账号行为。
 *
 * <p>重点锁"禁用"的语义：不能登录、数据保留、可逆。
 */
class AdminDaoTest extends DbTestSupport {

    @Test
    void auditWriteAndReadBack() {
        AdminDao dao = new AdminDao(db);
        dao.writeAudit(7, "ops", "user_disable", "users:42",
                "{\"reason\":\"测试\"}", "10.0.0.1");

        List<Map<String, Object>> logs = dao.listAudit(null, null, 1, 10);
        assertEquals(1, logs.size());
        Map<String, Object> row = logs.get(0);
        assertEquals(7L, ((Number) row.get("admin_id")).longValue());
        assertEquals("ops", row.get("admin_name"));
        assertEquals("user_disable", row.get("action"));
        assertEquals("users:42", row.get("target"));
        assertEquals("10.0.0.1", row.get("ip"));
        assertTrue(((Number) row.get("created_at")).longValue() > 0);
        assertEquals(1, dao.countAudit(null, null));
        assertEquals(List.of("user_disable"), dao.distinctActions());
    }

    @Test
    void auditFilterByActionAndKeyword() {
        AdminDao dao = new AdminDao(db);
        dao.writeAudit(1, "ops", "user_disable", "users:1", "{\"reason\":\"刷屏\"}", "10.0.0.1");
        dao.writeAudit(1, "ops", "user_disable", "users:2", "{\"reason\":\"刷屏\",\"n\":2}", "10.0.0.1");
        dao.writeAudit(1, "ops", "refund_approve", "mall_orders:9", "{\"amount\":100}", "10.0.0.2");
        dao.writeAudit(1, "ops", "user_enable", "users:1", "{}", "10.0.0.1");

        assertEquals(1, dao.countAudit("refund_approve", ""), "按 action 精确筛选");
        assertEquals(2, dao.countAudit(null, "users:1"), "关键字命中 target");
        assertEquals(2, dao.countAudit("user_disable", "刷屏"), "action + 关键字必须同时生效");
        assertEquals(1, dao.countAudit("user_disable", "\"n\":2"), "关键字命中 detail");
        assertEquals(0, dao.countAudit("user_disable", "不存在的关键字"));
        assertEquals(3, dao.distinctActions().size(), "三个动作都应出现在筛选列表里");
    }

    @Test
    void auditPagingIsStableNewestFirst() {
        AdminDao dao = new AdminDao(db);
        for (int i = 1; i <= 25; i++) {
            dao.writeAudit(1, "ops", "probe", "t:" + i, "{}", "ip");
        }
        List<Map<String, Object>> p1 = dao.listAudit("probe", null, 1, 10);
        List<Map<String, Object>> p2 = dao.listAudit("probe", null, 2, 10);
        List<Map<String, Object>> p3 = dao.listAudit("probe", null, 3, 10);
        assertEquals(10, p1.size());
        assertEquals(10, p2.size());
        assertEquals(5, p3.size());
        // 新→旧：第 1 页第一条是最后写入的 t:25，第 3 页最后一条是最早的 t:1
        assertEquals("t:25", p1.get(0).get("target"));
        assertEquals("t:16", p1.get(p1.size() - 1).get("target"));
        assertEquals("t:15", p2.get(0).get("target"));
        assertEquals("t:6", p2.get(p2.size() - 1).get("target"));
        assertEquals("t:5", p3.get(0).get("target"));
        assertEquals("t:1", p3.get(p3.size() - 1).get("target"));
        // 分页之间不得重叠、不得漏
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<Map<String, Object>> page : List.of(p1, p2, p3)) {
            for (Map<String, Object> r : page) assertTrue(seen.add(String.valueOf(r.get("target"))),
                    "分页结果重叠: " + r.get("target"));
        }
        assertEquals(25, seen.size(), "三页合计必须恰好覆盖全部 25 条");
    }

    @Test
    void disableBlocksLoginButKeepsData() {
        int uid = register("blocked_user");
        assertNotNull(db.loginUser("blocked_user", "pass1234"), "禁用前可登录");

        setDisabled(uid, true);

        Map<String, Object> u = db.getUserById(uid);
        assertNotNull(u, "禁用不是删除：用户行必须还在");
        assertEquals(1, ((Number) u.get("is_disabled")).intValue());
        assertNull(db.loginUser("blocked_user", "pass1234"), "禁用后不能登录");

        setDisabled(uid, false);
        assertNotNull(db.loginUser("blocked_user", "pass1234"), "启用后恢复可登录");
        assertEquals(0, ((Number) db.getUserById(uid).get("is_disabled")).intValue());
    }

    @Test
    void disabledFlagIsReturnedByGetUserById() {
        int uid = register("flag_user");
        // 关键：AdminHandler 依赖 getUserById 返回 is_disabled，漏了就会永远显示"正常"
        assertNotNull(db.getUserById(uid).get("is_disabled"));
    }

    @Test
    void adminsListNeverExposesPasswordHash() {
        AdminDao dao = new AdminDao(db);
        dao.createAdmin("a1", com.familychat.server.admin.AdminAuth.hashPassword("pw12345678"), false);
        for (Map<String, Object> a : dao.listAdmins()) {
            assertFalse(a.containsKey("password_hash"), "列表接口绝不能带出密码哈希");
        }
    }

    @Test
    void duplicateAdminNameRejected() {
        AdminDao dao = new AdminDao(db);
        String h = com.familychat.server.admin.AdminAuth.hashPassword("pw12345678");
        assertTrue(dao.createAdmin("dup", h, false) > 0);
        assertEquals(-1L, dao.createAdmin("dup", h, false), "重复用户名必须明确返回 -1");
    }

    private void setDisabled(int uid, boolean disabled) {
        // 与 AdminHandler.setDisabled 相同的落库方式，避免测试里另写一套 SQL 而测不到真实行为
        try (java.sql.Connection c = db.getConn();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET is_disabled=? WHERE id=?")) {
            ps.setInt(1, disabled ? 1 : 0);
            ps.setInt(2, uid);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            fail("setDisabled 失败: " + e.getMessage());
        }
    }
}
