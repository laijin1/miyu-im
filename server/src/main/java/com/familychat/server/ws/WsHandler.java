package com.familychat.server.ws;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.familychat.server.FamilyChatServer;
import com.familychat.server.config.ServerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WsHandler {
    private static final Algorithm ALGO = Algorithm.HMAC256(ServerConfig.getJwtSecret());
    private static final JWTVerifier VERIFIER = JWT.require(ALGO).build();
    private static final Gson gson = new Gson();

    // userId -> Set of WebSocket connections (supporting multiple connections per user)
    private static final ConcurrentHashMap<Integer, Set<WsContext>> connections = new ConcurrentHashMap<>();

    // Buffered call offers for targets that were momentarily disconnected when the offer
    // arrived (e.g. mid-reconnect). Flushed when the target's next connection is established.
    private static final ConcurrentHashMap<Integer, PendingOffer> pendingOffers = new ConcurrentHashMap<>();
    private static final long PENDING_OFFER_TTL_MS = 20_000; // 20s — call_offer is only meaningful briefly

    /**
     * 通话信令白名单（Step 1 / C2.1）。
     *
     * <p>抽成常量而不是散在 {@code if (… || …)} 里，是为了能被单测断言"恰好这 11 条"。
     * <b>漏一条的后果是静默失效</b>：客户端发得出，服务端在这里不匹配就什么都不做 ——
     * 表现为"对方忙 / 已拒绝 / 已取消"永远不生效，且没有任何报错可查。
     * 守卫见 {@code WsHandlerContractTest}。
     *
     * <p>{@code Set.of} 对重复元素会在类初始化时直接抛异常，顺带挡住复制粘贴写重。
     *
     * <p>⚠️ <b>注意：本集合里的 {@code "call_video"} 与
     * {@code MessageHandler} 里 {@code messages.msg_type} 的 {@code "call_video"}
     * 同名但不同用途</b> —— 这里是 <b>WS 信令信封的 type</b>（RTP 视频轨控制），
     * 那里是 <b>消息类型</b>（聊天里那条通话记录是视频通话）。
     * 两者不走同一条路径：信令走 {@link #isCallSignaling(String)} 转发，
     * 消息走 {@code new_message} 报文的 {@code msg_type} 字段。
     */
    static final Set<String> CALL_SIGNALING_TYPES = Set.of(
            "call_offer",
            "call_answer",
            "call_ice",
            "call_hangup",
            "call_busy",        // Step 1 新增：对端占线
            "call_reject",      // Step 1 新增：被叫拒接
            "call_cancel",      // Step 1 新增：主叫取消（含呼叫超时）
            "call_media",
            "call_video",       // WS 信令 type：视频轨控制。勿与 messages.msg_type 的 call_video 混淆
            "call_video_state", // 视频开关状态：对端关/开摄像头（enabled=true/false），非帧数据
            "call_media_stop");

    /** 是否为需要转发给目标用户的通话信令。 */
    static boolean isCallSignaling(String type) {
        return type != null && CALL_SIGNALING_TYPES.contains(type);
    }

    private static final class PendingOffer {
        final Map<String, Object> payload;
        final long createdAt = System.currentTimeMillis();
        PendingOffer(Map<String, Object> payload) { this.payload = payload; }
    }

    /**
     * Send a message to ALL connections of a user.
     * Removes dead connections after iteration to avoid skipping healthy ones.
     */
    public static void sendToUser(int userId, Object message) {
        Set<WsContext> conns = connections.get(userId);
        if (conns != null && !conns.isEmpty()) {
            java.util.List<WsContext> deadConns = null;
            // Snapshot and send to avoid ConcurrentModification during iteration
            WsContext[] snapshot = conns.toArray(new WsContext[0]);
            for (WsContext ctx : snapshot) {
                try {
                    ctx.send(message);
                } catch (Exception e) {
                    System.err.println("[WsHandler] Failed to send to user " + userId + ": " + e.getMessage()
                            + " — marking dead connection for removal");
                    if (deadConns == null) deadConns = new java.util.ArrayList<>();
                    deadConns.add(ctx);
                }
            }
            if (deadConns != null) {
                conns.removeAll(deadConns);
                if (conns.isEmpty()) connections.remove(userId, conns);
            }
        }
    }

    /**
     * Check if a user has any active connections.
     */
    public static boolean isUserConnected(int userId) {
        Set<WsContext> conns = connections.get(userId);
        return conns != null && !conns.isEmpty();
    }

    /**
     * 强制断开某用户的全部 WebSocket 连接（管理端禁用/删除账号时调用）。
     *
     * <p>为什么必须踢：JWT 默认 7 天有效，只改数据库标记的话，已经登录的客户端
     * 还能继续收发消息最长 7 天，"禁用"就成了摆设。这里主动关连接，
     * 客户端会立刻掉线并重新登录，而重新登录会走 is_disabled 检查被拒。
     *
     * <p>返回被断开的连接数；顺带清掉未投递的来电 offer，避免给已禁用账号留补投。
     */
    public static int kickUser(int userId) {
        pendingOffers.remove(userId);
        Set<WsContext> conns = connections.remove(userId);
        if (conns == null || conns.isEmpty()) return 0;
        int n = 0;
        for (WsContext ctx : conns.toArray(new WsContext[0])) {
            try {
                ctx.session.close(1008, "account disabled");  // 1008 = policy violation
                n++;
            } catch (Exception e) {
                System.err.println("[WsHandler] kickUser 关闭连接失败 uid=" + userId
                        + ": " + e.getMessage());
            }
        }
        FamilyChatServer.getDb().setOnline(userId, false);
        System.out.println("[WsHandler] 已踢下线 uid=" + userId + "（断开 " + n + " 个连接）");
        return n;
    }

    /**
     * Broadcast a message to ALL connections of a set of users (群聊成员用).
     * Skips users with no active connections (message is already persisted to DB).
     */
    public static void sendToUsers(java.util.Collection<Integer> userIds, Object message) {
        if (userIds == null) return;
        for (int uid : userIds) {
            if (isUserConnected(uid)) sendToUser(uid, message);
        }
    }

    public void register(Javalin app) {
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (token == null) {
                    ctx.session.close();
                    return;
                }
                try {
                    var jwt = VERIFIER.verify(token);
                    Integer userId = jwt.getClaim("user_id").asInt();
                    // 吊销校验：tv 声明必须等于当前 token_version（改密后旧 token 连接被拒）
                    if (userId == null || jwt.getClaim("tv").asInt()
                            != FamilyChatServer.getDb().getTokenVersion(userId)) {
                        ctx.session.close();
                        return;
                    }
                    // 管理端禁用：禁用后连已有 token 也不许建 WS 连接（token 最多 7 天有效，
                    // 不挡这里的话"禁用"要等令牌自然过期才生效）
                    Map<String, Object> u = FamilyChatServer.getDb().getUserById(userId);
                    if (u != null && u.get("is_disabled") instanceof Number n && n.intValue() == 1) {
                        System.err.println("[WsHandler] 已禁用账号连接被拒 uid=" + userId);
                        ctx.session.close();
                        return;
                    }

                    // Add this connection to the user's connection set
                    Set<WsContext> userConns = connections.computeIfAbsent(userId,
                            k -> ConcurrentHashMap.newKeySet());
                    userConns.add(ctx);

                    ctx.attribute("userId", userId);
                    FamilyChatServer.getDb().setOnline(userId, true);

                    // Only broadcast user_online on the FIRST connection
                    if (userConns.size() == 1) {
                        broadcast(Map.of("type", "user_online", "user_id", userId));
                    }

                    // Flush any call offer that arrived while this user was reconnecting
                    PendingOffer pending = pendingOffers.remove(userId);
                    if (pending != null) {
                        long age = System.currentTimeMillis() - pending.createdAt;
                        if (age <= PENDING_OFFER_TTL_MS) {
                            try {
                                ctx.send(pending.payload);
                                // Step 0 观察点：补投成功 = 客户端会弹出一个"可能已经作废"的来电。
                                // 主叫是否仍在呼叫，这里无从判断（pendingOffers 不记录主叫生命周期）。
                                System.err.println("[WsHandler] FLUSHED pending call_offer to user " + userId
                                        + " (age " + age + "ms, from " + pending.payload.get("from")
                                        + ", video " + pending.payload.get("video")
                                        + ", payload " + pending.payload.get("offer") + ")");
                            } catch (Exception e) {
                                System.err.println("[WsHandler] Failed to flush pending offer: " + e.getMessage());
                            }
                        } else {
                            System.err.println("[WsHandler] Dropped stale pending call_offer for user " + userId
                                    + " (age " + age + "ms, from " + pending.payload.get("from") + ")");
                        }
                    }

                    ctx.send(Map.of("type", "online_confirmed", "user_id", userId));
                } catch (Exception e) {
                    System.err.println("[WsHandler] Connect auth failed: " + e.getMessage());
                    ctx.session.close();
                }
            });

            ws.onError(ctx -> {
                // Log the error, then remove the connection defensively.
                // Javalin normally also fires onClose after onError; removeConnection is
                // idempotent so calling it twice (error + close) is safe.
                Throwable err = ctx.error();
                System.err.println("[WsHandler] WebSocket error, userId=" + ctx.attribute("userId")
                        + ": " + (err != null ? err.getMessage() : "unknown"));
                removeConnection(ctx);
            });

            ws.onClose(ctx -> {
                removeConnection(ctx);
            });

            ws.onMessage(ctx -> {
                try {
                    // Verify message is valid JSON
                    JsonObject msg;
                    try {
                        msg = JsonParser.parseString(ctx.message()).getAsJsonObject();
                    } catch (Exception e) {
                        System.err.println("[WsHandler] Invalid JSON message: " + e.getMessage());
                        return;
                    }

                    // Get authenticated sender ID from connection (set during onConnect JWT verification)
                    Integer senderId = ctx.attribute("userId");
                    if (senderId == null) {
                        System.err.println("[WsHandler] Message from unauthenticated connection, closing");
                        ctx.session.close();
                        return;
                    }

                    String type = msg.has("type") ? msg.get("type").getAsString() : "";
                    if (type.isEmpty()) return;

                    if ("private_message".equals(type)) {
                        handlePrivateMessage(ctx, msg, senderId);
                    } else if (isCallSignaling(type)) {
                        // 白名单见 CALL_SIGNALING_TYPES（漏一条 = 该信令静默丢弃）
                        handleSignalingMessage(ctx, msg, senderId, type);
                    }
                } catch (Exception e) {
                    System.err.println("[WsHandler] Error processing message: " + e.getMessage());
                }
            });
        });
    }

    /**
     * Idempotent removal of a WebSocket connection: shared by onClose and onError.
     * Safe to call more than once for the same context — the second call is a no-op.
     */
    private void removeConnection(WsContext ctx) {
        Integer userId = ctx.attribute("userId");
        if (userId != null) {
            Set<WsContext> userConns = connections.get(userId);
            if (userConns != null) {
                userConns.remove(ctx);
                // Only broadcast user_offline when ALL connections are gone
                if (userConns.isEmpty()) {
                    connections.remove(userId);
                    FamilyChatServer.getDb().setOnline(userId, false);
                    broadcast(Map.of("type", "user_offline", "user_id", userId));
                }
            }
        }
    }

    private void handlePrivateMessage(WsContext ctx, JsonObject msg, int senderId) {
        if (!msg.has("receiver_id") || !msg.has("content")) {
            ctx.send(Map.of("type", "error", "message", "缺少必要字段"));
            return;
        }

        int receiverId;
        try {
            receiverId = msg.get("receiver_id").getAsInt();
        } catch (Exception e) {
            ctx.send(Map.of("type", "error", "message", "无效的接收者ID"));
            return;
        }

        // Verify sender and receiver are friends before allowing message
        if (senderId == receiverId) {
            ctx.send(Map.of("type", "error", "message", "不能给自己发消息"));
            return;
        }
        if (!FamilyChatServer.getDb().areFriends(senderId, receiverId)) {
            ctx.send(Map.of("type", "error", "message", "你们还不是好友"));
            return;
        }

        String content = msg.get("content").getAsString();
        String msgType = msg.has("msg_type") ? msg.get("msg_type").getAsString() : "text";

        Map<String, Object> saved = FamilyChatServer.getDb().saveMessage(senderId, receiverId, content, msgType);
        if (saved == null) {
            ctx.send(Map.of("type", "error", "message", "消息保存失败"));
            return;
        }

        Map<String, Object> payload = Map.of(
                "type", "new_message",
                "id", saved.get("id"),
                "sender_id", senderId,
                "receiver_id", receiverId,
                "content", content,
                "msg_type", msgType,
                "created_at", saved.getOrDefault("created_at", "").toString()
        );

        // Send to ALL connections of the receiver
        sendToUser(receiverId, payload);

        // Send confirmation to the sender
        ctx.send(Map.of("type", "message_sent", "id", saved.get("id")));

        // Log warning if receiver was not connected
        if (!isUserConnected(receiverId)) {
            System.err.println("[WsHandler] private_message: receiver " + receiverId + " not connected (message saved to DB)");
        }
    }

    private void handleSignalingMessage(WsContext ctx, JsonObject msg, int senderId, String type) {
        if (!msg.has("to")) {
            ctx.send(Map.of("type", "error", "message", "缺少目标用户ID"));
            return;
        }

        int targetId;
        try {
            targetId = msg.get("to").getAsInt();
        } catch (Exception e) {
            ctx.send(Map.of("type", "error", "message", "无效的目标用户ID"));
            return;
        }

        // 通话信令前先校验双方是好友(与 handlePrivateMessage 一致),防止对任意用户轰炸信令
        if (senderId == targetId) {
            ctx.send(Map.of("type", "error", "message", "不能给自己发送通话信令"));
            return;
        }
        if (!FamilyChatServer.getDb().areFriends(senderId, targetId)) {
            ctx.send(Map.of("type", "error", "message", "你们还不是好友,无法通话"));
            return;
        }

        Map<String, Object> relay = new HashMap<>();
        relay.put("type", type);
        relay.put("from", senderId);
        // Step 1：call_id 对所有通话信令一律透传（客户端按它去重/匹配迟到信令）。
        // 旧版客户端（≤1.1.6）不发这个字段，此时原样不补 —— 由客户端兜底生成。
        if (msg.has("call_id") && !msg.get("call_id").isJsonNull()) {
            relay.put("call_id", msg.get("call_id").getAsString());
        }

        switch (type) {
            case "call_offer":
                if (!msg.has("offer")) {
                    ctx.send(Map.of("type", "error", "message", "缺少 offer"));
                    return;
                }
                relay.put("offer", msg.get("offer").getAsString());
                if (msg.has("video")) {
                    relay.put("video", msg.get("video").getAsBoolean());
                }
                break;

            case "call_answer":
                if (!msg.has("answer")) {
                    ctx.send(Map.of("type", "error", "message", "缺少 answer"));
                    return;
                }
                relay.put("answer", msg.get("answer").getAsString());
                break;

            case "call_ice":
                if (msg.has("candidate") && !msg.get("candidate").isJsonNull()) {
                    // Convert JsonElement to String first (Jackson can't serialize Gson types)
                    relay.put("candidate", msg.get("candidate").toString());
                } else {
                    relay.put("candidate", "{}");
                }
                break;

            case "call_hangup":
                // No additional payload needed
                break;

            // ---- Step 1：三条通话状态信令，负载只有 type + from + to + call_id ----
            case "call_busy":      // 对端占线（被叫在非空闲状态收到 offer 时回给主叫）
            case "call_reject":    // 被叫拒接（区别于挂断）
            case "call_cancel":    // 主叫取消未接通的呼叫（含 30s 呼叫超时）
                break;

            case "call_media":
                if (!msg.has("data") || !msg.has("media_type")) {
                    ctx.send(Map.of("type", "error", "message", "缺少媒体数据"));
                    return;
                }
                relay.put("data", msg.get("data").getAsString());
                relay.put("media_type", msg.get("media_type").getAsString());
                if (msg.has("seq")) {
                    relay.put("seq", msg.get("seq").getAsInt());
                }
                // Enforce 60KB per relayed message to prevent memory abuse
                if (relay.get("data") != null &&
                        ((String) relay.get("data")).length() > 60_000) {
                    return; // silently drop oversized media
                }
                break;

            case "call_video":
                if (!msg.has("data")) {
                    ctx.send(Map.of("type", "error", "message", "缺少视频数据"));
                    return;
                }
                relay.put("data", msg.get("data").getAsString());
                // Enforce a size cap on relayed video frames to prevent memory abuse
                if (((String) relay.get("data")).length() > 200_000) {
                    return; // silently drop oversized video frame
                }
                break;

            case "call_media_stop":
                // No additional payload needed
                break;

            // 视频开关状态：对端"关了/开了摄像头"。与 call_video（帧数据）语义不同 ——
            // 这条只表达状态，负载是 type + from + to + enabled（+ 透传的 call_id），没有 data。
            // enabled 缺失/为 null 时不补字段，接收端按默认值兜底。
            case "call_video_state":
                if (msg.has("enabled") && !msg.get("enabled").isJsonNull()) {
                    relay.put("enabled", msg.get("enabled").getAsBoolean());
                }
                break;
        }

        // If the target is momentarily disconnected (e.g. reconnecting), buffer the
        // call_offer so the call still goes through once they come back online.
        // This is what previously made calls intermittently fail to connect at all.
        if (!isUserConnected(targetId)) {
            if ("call_offer".equals(type)) {
                // 写入时即淘汰过期条目：防止对长期离线用户反复呼叫导致 Map 无限膨胀
                if (pendingOffers.size() > 256) {
                    pendingOffers.entrySet().removeIf(e ->
                            System.currentTimeMillis() - e.getValue().createdAt > PENDING_OFFER_TTL_MS);
                }
                pendingOffers.put(targetId, new PendingOffer(new HashMap<>(relay)));
                System.err.println("[WsHandler] BUFFERED call_offer for offline user " + targetId
                        + " from " + senderId + " (video " + relay.get("video")
                        + ", offer " + relay.get("offer")
                        + ", callId=" + relay.get("call_id")
                        + ", queue=" + pendingOffers.size() + ")");
            } else {
                // Step 0 观察点：非 offer 信令对离线目标直接丢弃 —— 挂断/取消/拒接都走这里，
                // 因此"主叫已挂断"不会取消上面对 targetId 的 pendingOffer（幽灵来电根因）。
                boolean terminal = "call_hangup".equals(type) || "call_reject".equals(type)
                        || "call_cancel".equals(type);
                System.err.println("[WsHandler] DROPPED signaling for offline target " + targetId
                        + " (type=" + type + ", from=" + senderId
                        + ", callId=" + relay.get("call_id") + ")"
                        + (terminal
                            ? " — pendingOffer still scheduled=" + pendingOffers.containsKey(targetId)
                            : ""));
            }
        } else {
            // Step 0 观察点：正常转发路径打点（只打信令，媒体/视频帧每秒几十条不打）。
            boolean terminal = "call_hangup".equals(type) || "call_reject".equals(type)
                    || "call_cancel".equals(type) || "call_busy".equals(type);
            if ("call_offer".equals(type) || "call_answer".equals(type) || terminal) {
                System.err.println("[WsHandler] RELAY " + type + " from " + senderId
                        + " to " + targetId + " (callId=" + relay.get("call_id") + ")"
                        + ("call_offer".equals(type)
                            ? " (video " + relay.get("video") + ", offer " + relay.get("offer") + ")" : "")
                        + (terminal && pendingOffers.containsKey(targetId)
                            ? " — WARNING: target still has a pendingOffer, NOT cancelled" : ""));
            }
        }

        sendToUser(targetId, relay);
    }

    private void broadcast(Map<String, Object> msg) {
        connections.values().forEach(conns -> {
            conns.removeIf(ctx -> {
                try {
                    ctx.send(msg);
                    return false; // keep alive
                } catch (Exception e) {
                    System.err.println("[WsHandler] Broadcast send error: " + e.getMessage()
                            + " — removing dead connection");
                    return true; // remove dead
                }
            });
        });
        // Clean up empty user entries
        connections.values().removeIf(Set::isEmpty);
    }
}
