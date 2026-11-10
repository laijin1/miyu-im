package com.familychat.server.handler;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.familychat.server.FamilyChatServer;
import com.familychat.server.config.ServerConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthHandler {

    private static final Algorithm ALGO = Algorithm.HMAC256(ServerConfig.getJwtSecret());

    // Cache the JWTVerifier as a static final field instead of creating it every request
    private static final JWTVerifier VERIFIER = JWT.require(ALGO).build();

    // Simple in-memory rate limiter: IP -> [timestamp, count]
    private static final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();
    private static final int RATE_LIMIT_MAX = 10;       // max attempts
    private static final long RATE_LIMIT_WINDOW = 60000; // per 60 seconds
    // Memory guard: cap total entries so the map can't grow without bound
    private static final int RATE_LIMIT_MAP_MAX = 10000;

    /**
     * Check rate limit for the given IP.
     * Returns true if request is allowed, false if rate limited.
     */
    private static boolean checkRateLimit(String ip) {
        long now = System.currentTimeMillis();
        // Prune expired entries once the map grows large, keeping memory bounded.
        // removeIf / Iterator.remove are safe on ConcurrentHashMap.
        if (rateLimitMap.size() >= RATE_LIMIT_MAP_MAX) {
            rateLimitMap.entrySet().removeIf(e -> now - e.getValue()[0] > RATE_LIMIT_WINDOW);
            // Fallback: if expired entries weren't enough, force-evict half to stay bounded
            if (rateLimitMap.size() >= RATE_LIMIT_MAP_MAX) {
                int half = rateLimitMap.size() / 2;
                java.util.Iterator<java.util.Map.Entry<String, long[]>> it = rateLimitMap.entrySet().iterator();
                for (int i = 0; it.hasNext() && i < half; i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        // compute 原子自增：并发请求不会同时读到同一计数而绕过限流
        return rateLimitMap.compute(ip, (k, e) -> {
            if (e == null || now - e[0] > RATE_LIMIT_WINDOW) {
                return new long[]{now, 1};
            }
            e[1]++;
            return e;
        })[1] <= RATE_LIMIT_MAX;
    }

    public void register(Javalin app) {
        app.post("/api/register", this::registerUser);
        app.post("/api/login", this::loginUser);
        app.post("/api/user/change-password", this::changePassword);
    }

    private void changePassword(Context ctx) {
        int userId = requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("code", 400, "message", "无效的请求格式"));
            return;
        }

        String oldPw = body.get("old_password") instanceof String ? (String) body.get("old_password") : "";
        String newPw = body.get("new_password") instanceof String ? (String) body.get("new_password") : "";

        if (oldPw.isEmpty()) {
            ctx.status(400).json(Map.of("code", 400, "message", "旧密码不能为空"));
            return;
        }
        if (newPw.length() < 6) {
            ctx.status(400).json(Map.of("code", 400, "message", "新密码格式不符合要求"));
            return;
        }

        int result = FamilyChatServer.getDb().changePassword(userId, oldPw, newPw);
        if (result == 1) {
            ctx.status(400).json(Map.of("code", 400, "message", "旧密码错误"));
            return;
        }
        if (result != 0) {
            ctx.status(500).json(Map.of("code", 500, "message", "修改失败"));
            return;
        }
        // 改密同时 token_version+1，旧 JWT 全部失效；签发新 token 返回给客户端，
        // 否则客户端改密后携带旧 token 的后续请求（如重新上传密钥备份）会全部 401。
        String token = JWT.create()
                .withClaim("user_id", userId)
                .withClaim("tv", FamilyChatServer.getDb().getTokenVersion(userId))
                .withExpiresAt(new Date(System.currentTimeMillis() + 86400 * 7 * 1000))
                .sign(ALGO);
        ctx.json(Map.of("code", 200, "message", "密码修改成功", "token", token));
    }

    private void registerUser(Context ctx) {
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        // Validate and safely extract values
        Object usernameObj = body.get("username");
        Object passwordObj = body.get("password");
        Object nicknameObj = body.get("nickname");

        String username = usernameObj instanceof String ? ((String) usernameObj).trim() : "";
        String password = passwordObj instanceof String ? ((String) passwordObj).trim() : "";
        String nickname = nicknameObj instanceof String ? ((String) nicknameObj).trim() : "";
        if (nickname.isEmpty()) nickname = username;

        // Rate limiting: 按 "用户名|IP" 双重限流。经 Cloudflare tunnel 后所有请求同 IP,
        // 只按 IP 会把全家人一起锁死, 故必须带上用户名维度。
        if (!checkRateLimit(username + "|" + ctx.req().getRemoteAddr())) {
            ctx.status(429).json(Map.of("error", "请求过于频繁，请稍后再试"));
            return;
        }

        // Input validation
        if (username.isEmpty() || password.isEmpty()) {
            ctx.status(400).json(Map.of("error", "用户名和密码不能为空"));
            return;
        }
        if (username.length() < 2 || username.length() > 20) {
            ctx.status(400).json(Map.of("error", "用户名长度需在2-20字符之间"));
            return;
        }
        if (!username.matches("[a-zA-Z0-9_\\u4e00-\\u9fff]+")) {
            ctx.status(400).json(Map.of("error", "用户名只能包含字母、数字、下划线和中文"));
            return;
        }
        if (password.length() < 6) {
            ctx.status(400).json(Map.of("error", "密码至少6个字符"));
            return;
        }
        if (password.length() > 128) {
            ctx.status(400).json(Map.of("error", "密码不能超过128个字符"));
            return;
        }

        Map<String, Object> user = FamilyChatServer.getDb().registerUser(username, password, nickname);
        if (user == null) {
            ctx.status(409).json(Map.of("error", "用户名已存在"));
            return;
        }

        // Use longValue() to safely convert Number to int, avoiding ClassCastException
        int userId = ((Number) user.get("id")).intValue();
        String token = JWT.create()
                .withClaim("user_id", userId)
                .withClaim("tv", FamilyChatServer.getDb().getTokenVersion(userId)) // 吊销版本号
                .withExpiresAt(new Date(System.currentTimeMillis() + 86400 * 7 * 1000))
                .sign(ALGO);
        ctx.json(Map.of("token", token, "user", userDict(user)));
    }

    private void loginUser(Context ctx) {
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        Object usernameObj = body.get("username");
        Object passwordObj = body.get("password");

        String username = usernameObj instanceof String ? ((String) usernameObj).trim() : "";
        String password = passwordObj instanceof String ? ((String) passwordObj).trim() : "";

        // Rate limiting: 按 "用户名|IP" 双重限流(隧道后同 IP, 必须带用户名维度, 防止锁死全家人)
        String rateKey = username.isEmpty()
                ? ctx.req().getRemoteAddr()
                : username + "|" + ctx.req().getRemoteAddr();
        if (!checkRateLimit(rateKey)) {
            ctx.status(429).json(Map.of("error", "请求过于频繁，请稍后再试"));
            return;
        }

        if (username.isEmpty() || password.isEmpty()) {
            ctx.status(400).json(Map.of("error", "用户名和密码不能为空"));
            return;
        }

        Map<String, Object> user = FamilyChatServer.getDb().loginUser(username, password);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "用户名或密码错误"));
            return;
        }

        int userId = ((Number) user.get("id")).intValue();
        String token = JWT.create()
                .withClaim("user_id", userId)
                .withClaim("tv", FamilyChatServer.getDb().getTokenVersion(userId)) // 吊销版本号
                .withExpiresAt(new Date(System.currentTimeMillis() + 86400 * 7 * 1000))
                .sign(ALGO);
        ctx.json(Map.of("token", token, "user", userDict(user)));
    }

    // ===== JWT Middleware =====

    /**
     * Require authentication via JWT Bearer token.
     * Uses cached JWTVerifier for performance.
     */
    public static int requireAuth(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of("error", "缺少认证令牌"));
            return -1;
        }
        try {
            String token = auth.substring(7);
            var jwt = VERIFIER.verify(token);
            Integer userId = jwt.getClaim("user_id").asInt();
            if (userId == null) {
                ctx.status(401).json(Map.of("error", "无效令牌"));
                return -1;
            }
            // 吊销校验：tv 声明必须等于当前 token_version（改密后旧 token 失效）
            if (jwt.getClaim("tv").asInt() != FamilyChatServer.getDb().getTokenVersion(userId)) {
                ctx.status(401).json(Map.of("error", "登录已失效，请重新登录"));
                return -1;
            }
            return userId;
        } catch (Exception e) {
            ctx.status(401).json(Map.of("error", "无效令牌"));
            return -1;
        }
    }

    /**
     * 文件/媒体端点鉴权: 优先 Authorization header, 其次 ?token= 查询参数。
     * 客户端 Glide/ExoPlayer 加载图片/视频不便带 header, 与 WS 一致走 query token。
     * 返回 userId; -1 表示未通过(已写 401 响应)。
     */
    public static int requireAuthQuery(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                var jwt = VERIFIER.verify(auth.substring(7));
                Integer userId = jwt.getClaim("user_id").asInt();
                if (userId != null && jwt.getClaim("tv").asInt()
                        == FamilyChatServer.getDb().getTokenVersion(userId)) {
                    return userId;
                }
            } catch (Exception ignored) {
                // header 无效时落到 query token 再试一次
            }
        }
        String qToken = ctx.queryParam("token");
        if (qToken != null && !qToken.isEmpty()) {
            try {
                var jwt = VERIFIER.verify(qToken);
                Integer userId = jwt.getClaim("user_id").asInt();
                if (userId != null && jwt.getClaim("tv").asInt()
                        == FamilyChatServer.getDb().getTokenVersion(userId)) {
                    return userId;
                }
                ctx.status(401).json(Map.of("error", "登录已失效，请重新登录"));
                return -1;
            } catch (Exception e) {
                ctx.status(401).json(Map.of("error", "无效令牌"));
                return -1;
            }
        }
        ctx.status(401).json(Map.of("error", "缺少认证令牌"));
        return -1;
    }

    public static Map<String, Object> userDict(Map<String, Object> u) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", u.get("id"));
        d.put("username", u.get("username"));
        d.put("nickname", u.get("nickname"));
        d.put("avatar", u.get("avatar") != null ? u.get("avatar") : "😊");
        d.put("avatar_color", u.get("avatar_color") != null ? u.get("avatar_color") : "#e8f5e9");
        // is_online 可能是 Boolean(rowToMap 已归一)或 Number(旧原始值)，两者都要兼容
        Object online = u.get("is_online");
        d.put("is_online", online instanceof Boolean ? (Boolean) online
                : (online instanceof Number && ((Number) online).intValue() == 1));
        d.put("last_seen", u.get("last_seen") != null ? u.get("last_seen").toString() : "");
        d.put("wx_id", u.get("wx_id") != null ? u.get("wx_id").toString() : "");
        d.put("remark", u.get("remark") != null ? u.get("remark").toString() : "");
        return d;
    }
}
