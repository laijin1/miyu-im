package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 认证与密码核心逻辑：注册/登录/改密/Token 吊销版本号。 */
class AuthTest extends DbTestSupport {

    @Test
    void registerAndLoginRoundtrip() {
        Map<String, Object> u = db.registerUser("alice", "secret123", "爱丽丝");
        assertNotNull(u, "注册应成功");
        int uid = ((Number) u.get("id")).intValue();

        Map<String, Object> login = db.loginUser("alice", "secret123");
        assertNotNull(login, "正确密码应能登录");
        assertEquals(uid, ((Number) login.get("id")).intValue());

        assertNull(db.loginUser("alice", "wrong-pass"), "错误密码应登录失败");
        assertNull(db.registerUser("alice", "other123", "x"), "重复用户名应注册失败");
    }

    @Test
    void changePasswordInvalidatesOldPasswordAndBumpsTokenVersion() {
        int uid = register("bob");
        int tv0 = db.getTokenVersion(uid);

        // 旧密码错误 → 返回 1
        assertEquals(1, db.changePassword(uid, "wrong", "newpass99"));

        // 正确旧密码 → 返回 0
        assertEquals(0, db.changePassword(uid, "pass1234", "newpass99"));
        assertNull(db.loginUser("bob", "pass1234"), "改密后旧密码应失效");
        assertNotNull(db.loginUser("bob", "newpass99"), "新密码应能登录");

        // Token 吊销版本号应递增（改密后旧 JWT 失效）
        assertTrue(db.getTokenVersion(uid) > tv0, "改密应递增 token_version");
    }

    @Test
    void sha256HexUsedForPayPinIsStable() {
        // 与客户端约定一致：pin_hash = sha256("123456")，供钱包测试复用
        assertEquals(64, sha256Hex("123456").length());
        assertEquals(sha256Hex("123456"), sha256Hex("123456"));
    }
}
