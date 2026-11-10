package com.familychat.server.handler;

import com.familychat.server.model.Database;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 聊天代理：把 App 的请求转发到 DeepSeek，避免把 API Key 放进 APK（可被反编译提取）。
 * API Key 从环境变量 DEEPSEEK_KEY 或 data/deepseek.key（已被 .gitignore 忽略）读取。
 */
public class AiHandler {

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";

    // 单次最多转发 20 条历史消息，防止有人把代理当无限转发接口
    private static final int MAX_MESSAGES = 20;
    // 单条消息最长 4000 字
    private static final int MAX_CONTENT_LEN = 4000;

    // AI 接口 per-user 限流: userId -> [windowStart, count]
    private static final ConcurrentHashMap<Integer, long[]> aiRateLimit = new ConcurrentHashMap<>();
    private static final int AI_RATE_MAX = 20;        // 每 60 秒最多 20 次
    private static final long AI_RATE_WINDOW = 60_000;
    // 内存上限（体检报告 §7.6「限流 Map 无上限」）：原实现「每账号一条、永不回收」，
    // 长期运行下 map 随历史登录账号数单调增长。这里照抄 AuthHandler 既有形状加两道闸：
    //   1) 到达上限先清理已过期的窗口条目；
    //   2) 仍超限则强制驱逐一半，保证任何情况下容量有界。
    // 为什么不引入 Caffeine：本机 Gradle 缓存里没有该依赖且 Maven Central 连不上
    // （实测 SSL 失败），加依赖将无法离线构建与验证；而 AuthHandler 已有
    // 等价且经生产运行的实现，保持三处形状一致更稳。
    private static final int AI_RATE_MAP_MAX = 10_000;

    /** AI 接口 per-user 限流, 超限返回 false。compute 原子计数，防并发绕过。 */
    private static boolean checkAiRateLimit(int userId) {
        long now = System.currentTimeMillis();
        // 清理过期条目，保证 map 有界（removeIf / Iterator.remove 对 ConcurrentHashMap 安全）
        if (aiRateLimit.size() >= AI_RATE_MAP_MAX) {
            aiRateLimit.entrySet().removeIf(e -> now - e.getValue()[0] > AI_RATE_WINDOW);
            // 兜底：过期条目不足以降到阈值以下时，强制驱逐一半
            if (aiRateLimit.size() >= AI_RATE_MAP_MAX) {
                int half = aiRateLimit.size() / 2;
                java.util.Iterator<Map.Entry<Integer, long[]>> it = aiRateLimit.entrySet().iterator();
                for (int i = 0; it.hasNext() && i < half; i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        return aiRateLimit.compute(userId, (k, e) -> {
            if (e == null || now - e[0] > AI_RATE_WINDOW) {
                return new long[]{now, 1};
            }
            e[1]++;
            return e;
        })[1] <= AI_RATE_MAX;
    }

    private static volatile String cachedKey = null;

    /** 读取 DeepSeek API Key：优先环境变量，其次 data/deepseek.key。 */
    private static String getDeepSeekKey() {
        if (cachedKey != null) return cachedKey;
        String key = System.getenv("DEEPSEEK_KEY");
        if (key != null && !key.trim().isEmpty()) {
            cachedKey = key.trim();
            return cachedKey;
        }
        try {
            Path keyFile = Paths.get(Database.DATA_DIR, "deepseek.key").toAbsolutePath().normalize();
            if (Files.exists(keyFile)) {
                String s = Files.readString(keyFile).trim();
                if (!s.isEmpty()) {
                    cachedKey = s;
                    return s;
                }
            }
        } catch (Exception e) {
            System.err.println("[WARN] 读取 deepseek.key 失败: " + e.getMessage());
        }
        return null;
    }

    public void register(Javalin app) {
        app.post("/api/ai/chat", this::chat);
    }

    private void chat(Context ctx) {
        // 需要登录：防止服务器被当成免费 AI 代理刷接口
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        // per-user 限流, 防止账号被拿来回刷 DeepSeek 额度
        if (!checkAiRateLimit(userId)) {
            ctx.status(429).json(Map.of("code", 429, "message", "AI 请求过于频繁，请稍后再试"));
            return;
        }

        String key = getDeepSeekKey();
        if (key == null) {
            ctx.status(500).json(Map.of("code", 500, "message", "服务端未配置 DeepSeek API Key"));
            return;
        }

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("code", 400, "message", "无效的请求格式"));
            return;
        }

        Object msgsObj = body.get("messages");
        if (!(msgsObj instanceof List)) {
            ctx.status(400).json(Map.of("code", 400, "message", "缺少 messages"));
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) msgsObj;
        if (msgs.isEmpty() || msgs.size() > MAX_MESSAGES) {
            ctx.status(400).json(Map.of("code", 400, "message", "消息数量需在 1-" + MAX_MESSAGES + " 条之间"));
            return;
        }

        // 只保留 role/content 字段，防止传乱七八糟的东西进 DeepSeek
        List<Map<String, String>> clean = new java.util.ArrayList<>();
        try {
            for (Map<String, Object> m : msgs) {
                String role = m.get("role") instanceof String ? (String) m.get("role") : "user";
                if (!role.equals("system") && !role.equals("user") && !role.equals("assistant")) {
                    ctx.status(400).json(Map.of("code", 400, "message", "非法 role: " + role));
                    return;
                }
                Object contentObj = m.get("content");
                String content = contentObj == null ? "" : contentObj.toString();
                if (content.length() > MAX_CONTENT_LEN) {
                    content = content.substring(0, MAX_CONTENT_LEN);
                }
                clean.add(Map.of("role", role, "content", content));
            }
        } catch (Exception e) {
            ctx.status(400).json(Map.of("code", 400, "message", "消息内容无法序列化"));
            return;
        }

        String payload;
        try {
            Map<String, Object> reqBody = Map.of(
                    "model", MODEL,
                    "messages", clean,
                    "stream", false);
            payload = new com.google.gson.Gson().toJson(reqBody);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("code", 400, "message", "消息内容无法序列化"));
            return;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DEEPSEEK_URL))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                String reply = extractReply(resp.body());
                ctx.json(Map.of("code", 200, "reply", reply));
            } else {
                // 只记状态码, 不打完整响应体(可能包含用户聊天内容)
                System.err.println("[AI] DeepSeek 返回 " + resp.statusCode());
                ctx.status(502).json(Map.of("code", 502, "message", "AI 服务暂时不可用（" + resp.statusCode() + "）"));
            }
        } catch (java.net.http.HttpTimeoutException e) {
            ctx.status(504).json(Map.of("code", 504, "message", "AI 响应超时，请重试"));
        } catch (Exception e) {
            System.err.println("[AI] 请求 DeepSeek 失败: " + e.getMessage());
            ctx.status(502).json(Map.of("code", 502, "message", "AI 服务连接失败"));
        }
    }

    /** 从 DeepSeek 返回体里取 choices[0].message.content。 */
    private static String extractReply(String json) {
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String content = root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return content == null ? "" : content;
        } catch (Exception e) {
            // 不打完整响应体, 避免把用户聊天内容写进日志
            System.err.println("[AI] 解析 DeepSeek 返回失败");
            return "（AI 返回格式异常）";
        }
    }
}
