package com.familychat.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.familychat.server.config.ServerConfig;
import com.familychat.server.handler.WalletHandler;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT token version 吊销：改密递增、缺失用户返回 -1（LOGIC-AUDIT 第 4 条）。
 *
 * <p>修复前 {@code getTokenVersion} 在「用户不存在 / DB 读失败」时返回 0，
 * 而 0 是**合法版本号**（新用户即 tv=0），于是一个 tv=0 的旧 token
 * 会在吊销校验里被判为"版本一致"。修复后改为返回 -1，任何真实 token 都不匹配。
 */
class TokenVersionTest extends DbTestSupport {

    @Test
    void tvClaimSemantics() {
        int uid = register("alice");
        int fresh = db.getTokenVersion(uid);
        System.out.println("[TV] fresh user tokenVersion=" + fresh);
        db.changePassword(uid, "pass1234", "newpass123");
        int after = db.getTokenVersion(uid);
        System.out.println("[TV] after changePassword tokenVersion=" + after);

        int missing = db.getTokenVersion(424242);
        System.out.println("[TV] nonexistent user tokenVersion=" + missing);

        // 新用户 tv=0（合法值），改密后递增
        assertEquals(0, fresh, "新用户 token_version 应为 0");
        assertEquals(1, after, "改密后应为 1");
        // 关键断言：查不到必须是 -1，不能是合法版本号 0
        assertEquals(-1, missing, "用户不存在必须返回 -1（0 是合法版本号）");
        assertNotEquals(0, missing, "绝不能把「查不到」当成版本 0");
        System.out.println("[TV][OK] 不存在用户返回 -1，不再伪装成合法版本 0");
    }

    /** 改密后旧会话应失效：tv 从 0 变 1。 */
    @Test
    void changePasswordIncrementsTv() {
        int uid = register("bob");
        int v0 = db.getTokenVersion(uid);
        db.changePassword(uid, "pass1234", "another123");
        int v1 = db.getTokenVersion(uid);
        System.out.println("[TV] v0=" + v0 + " v1=" + v1);
        assertTrue(v1 > v0, "改密必须递增 token_version");
        assertEquals(0, v0, "改密前应为 0");
        assertEquals(1, v1, "改密后应为 1");
    }

    /**
     * 吊销语义端到端：真起 Javalin + 真 {@link WalletHandler}，由 {@code AuthHandler.requireAuth}
     * 自己判定（不再在测试里复刻它的比较表达式）。
     *
     * <p>① 签名合法、但 user_id 指向不存在用户、tv=0 的 token → 必须 401；
     * ② 对照：真实用户的合法 token → 必须 200（证明路由确实挂上了，401 不是"路由没注册"）。
     */
    @Test
    void revokedCheckForMissingUserRejectsTvZeroToken() throws Exception {
        FamilyChatServer.setDbForTests(db);
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        new WalletHandler().register(app);
        app.start(0);                                  // 端口 0 = 系统分配，避免与服务端/并行测试撞端口
        String base = "http://127.0.0.1:" + app.port();
        try {
            // ① 424242 不存在：getTokenVersion → -1，与 token 里的 tv=0 不等 → 401
            HttpResponse<String> ghost = getWallet(base, signToken(424242, 0));
            System.out.println("[TV] missing-user token -> HTTP " + ghost.statusCode() + " " + ghost.body());
            assertEquals(401, ghost.statusCode(), "不存在的用户不得放行 tv=0 的 token");

            // ② 对照组：真实用户 + 真实 tv
            int uid = register("ghostcheck");
            HttpResponse<String> real = getWallet(base, signToken(uid, db.getTokenVersion(uid)));
            System.out.println("[TV] real-user token -> HTTP " + real.statusCode());
            assertEquals(200, real.statusCode(), "合法 token 必须放行（证明 401 不是路由未注册）");
        } finally {
            app.stop();
            FamilyChatServer.setDbForTests(null);
        }
    }

    /** 用测试密钥签一个含 user_id / tv 的 App JWT（与客户端同格式）。 */
    private static String signToken(int userId, int tv) {
        return JWT.create()
                .withClaim("user_id", userId)
                .withClaim("tv", tv)
                .withExpiresAt(new Date(System.currentTimeMillis() + 3600_000))
                .sign(Algorithm.HMAC256(ServerConfig.getJwtSecret()));
    }

    private static HttpResponse<String> getWallet(String base, String jwt) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/wallet"))
                .header("Authorization", "Bearer " + jwt)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }
}
