package com.familychat.server;

import com.familychat.server.admin.AdminAuth;
import com.familychat.server.admin.AdminDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端鉴权：口令哈希、口令校验、登录限流。
 *
 * <p>这些是不依赖 HTTP 层的安全不变量，必须能在单元测试里锁住：
 * 顺序写错（比如先比对长度再比对哈希）就会退化成一个可被计时的 oracle。
 */
class AdminAuthTest extends DbTestSupport {

    // bootstrap 会往数据目录写初始密码文件，测试结束必须回收，
    // 否则会污染同目录下别的测试（以及开发机上真实的 data/ 若曾指向那里）。
    @AfterEach
    void cleanInitialPasswordFile() {
        AdminAuth.dropInitialPasswordFile();
    }

    @Test
    void passwordHashIsBcryptAndVerifies() {
        String hash = AdminAuth.hashPassword("S3cret-Passw0rd");
        assertTrue(hash.startsWith("$2"), "必须是 BCrypt 哈希，实际: " + hash);
        assertTrue(AdminAuth.verifyPassword("S3cret-Passw0rd", hash));
        assertFalse(AdminAuth.verifyPassword("wrong", hash));
    }

    @Test
    void samePasswordProducesDifferentHashes() {
        // 每次加盐：两个相同口令的哈希必须不同，否则等于明文存储的等价物
        String a = AdminAuth.hashPassword("same-password");
        String b = AdminAuth.hashPassword("same-password");
        assertNotEquals(a, b);
    }

    @Test
    void verifyRejectsNullAndMalformedHash() {
        assertFalse(AdminAuth.verifyPassword("x", null), "空哈希绝不能通过");
        assertFalse(AdminAuth.verifyPassword("x", ""), "空串哈希绝不能通过");
        assertFalse(AdminAuth.verifyPassword("x", "not-a-bcrypt-hash"), "非法哈希不能抛异常，只能拒绝");
    }

    @Test
    void loginLockAfterFiveFailures() {
        String ip = "10.9.9.1";
        AdminAuth.clearFailures(ip);
        assertEquals(0, AdminAuth.lockRemainingSeconds(ip), "初始未锁定");

        for (int i = 1; i <= 4; i++) {
            AdminAuth.recordFailure(ip);
            assertEquals(0, AdminAuth.lockRemainingSeconds(ip),
                    "第 " + i + " 次失败还不应锁定");
        }
        AdminAuth.recordFailure(ip);
        long left = AdminAuth.lockRemainingSeconds(ip);
        assertTrue(left > 0, "第 5 次失败后必须锁定");
        assertTrue(left <= 15 * 60, "锁定时长不超过 15 分钟，实际 " + left + "s");

        // 成功登录清空计数
        AdminAuth.clearFailures(ip);
        assertEquals(0, AdminAuth.lockRemainingSeconds(ip));
    }

    @Test
    void lockIsPerIpIsolated() {
        AdminAuth.clearFailures("10.1.1.1");
        AdminAuth.clearFailures("10.1.1.2");
        for (int i = 0; i < 5; i++) AdminAuth.recordFailure("10.1.1.1");
        assertTrue(AdminAuth.lockRemainingSeconds("10.1.1.1") > 0);
        assertEquals(0, AdminAuth.lockRemainingSeconds("10.1.1.2"), "别的 IP 不受影响");
    }

    @Test
    void bootstrapCreatesAdminOnceAndStaysUnchanged() {
        AdminDao dao = new AdminDao(db);
        assertEquals(0, dao.countAdmins());

        String pw1 = AdminAuth.bootstrapIfEmpty(dao);
        assertNotNull(pw1, "首次启动应生成初始密码");
        assertEquals(1, dao.countAdmins());

        // 再次启动不得覆盖已有口令（否则重启 = 密码被重置）
        String pw2 = AdminAuth.bootstrapIfEmpty(dao);
        assertNull(pw2, "已存在管理员时不再生成");
        Map<String, Object> admin = dao.findByName("admin");
        assertNotNull(admin);
        assertTrue(AdminAuth.verifyPassword(pw1, String.valueOf(admin.get("password_hash"))),
                "初始密码必须仍然有效");
        assertEquals(1, ((Number) admin.get("must_change_pw")).intValue(), "初始密码必须标记待改");

        // 初始化动作本身要留审计
        assertEquals(1, dao.countAudit("admin_bootstrap", ""));
    }

    @Test
    void updatedPasswordInvalidatesInitialOne() {
        AdminDao dao = new AdminDao(db);
        AdminAuth.bootstrapIfEmpty(dao);
        Map<String, Object> admin = dao.findByName("admin");
        long id = ((Number) admin.get("id")).longValue();

        assertTrue(dao.updatePassword(id, AdminAuth.hashPassword("new-password-123")));
        Map<String, Object> after = dao.findByName("admin");
        assertTrue(AdminAuth.verifyPassword("new-password-123",
                String.valueOf(after.get("password_hash"))));
        assertEquals(0, ((Number) after.get("must_change_pw")).intValue(), "改密后清掉待改标记");
    }

    /**
     * 运维重置（忘记密码的补救路径）必须同时满足三件事：新口令能登、旧口令立刻失效、
     * must_change_pw 置 1（它既是"登录后强制改密"的开关，也是 data/admin_initial_password.txt
     * 的存活条件 —— 不置 1 的话那份明文会在下次重启时被当作过期残留删掉）。
     */
    @Test
    void resetPasswordReplacesHashForcesChangeAndLogsAudit() {
        AdminDao dao = new AdminDao(db);
        String oldPw = AdminAuth.bootstrapIfEmpty(dao);

        String fresh = AdminAuth.resetPassword(dao, "admin", "cli:DbAdminTool");
        assertNotNull(fresh, "重置必须交付一串可用的新口令");

        Map<String, Object> after = dao.findByName("admin");
        String hash = String.valueOf(after.get("password_hash"));
        assertTrue(AdminAuth.verifyPassword(fresh, hash), "新口令必须能通过校验");
        assertFalse(AdminAuth.verifyPassword(oldPw, hash), "旧口令必须立即失效");
        assertEquals(1, ((Number) after.get("must_change_pw")).intValue(), "重置后必须强制改密");
        assertEquals(1, dao.countAudit("admin_password_reset", ""), "重置必须留审计");

        // 用户名写错是最常见的输入错误：必须报"查无此人"，绝不能顺手新建一个账号
        assertNull(AdminAuth.resetPassword(dao, "no-such-admin", "cli:DbAdminTool"));
        assertEquals(1, dao.countAdmins(), "查无此人时不得新增管理员");
    }
}
