package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.*;

public class MessageHandler {
    public void register(Javalin app) {
        app.get("/api/messages/search", this::searchMessages);
        app.get("/api/messages/{friendId}", this::getMessages);
        app.post("/api/messages/read/{senderId}", this::markRead);
        app.post("/api/messages/send", this::sendMessage);
        app.post("/api/messages/call-status", this::reportCallStatus);
        app.delete("/api/messages/{id}", this::deleteMessage);
        app.post("/api/messages/{id}/recall", this::recallMessage);
        app.delete("/api/messages/clear/{friendId}", this::clearConversation);
    }

    private void getMessages(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        int friendId;
        try {
            friendId = Integer.parseInt(ctx.pathParam("friendId"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的好友ID"));
            return;
        }

        // Clamp limit to [1, 200], default 50
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        var msgs = FamilyChatServer.getDb().getMessages(userId, friendId, limit);
        ctx.json(Map.of("messages", msgs.stream().map(m -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", m.get("id"));
            d.put("sender_id", m.get("sender_id"));
            d.put("receiver_id", m.get("receiver_id"));
            d.put("content", m.get("content") != null ? m.get("content") : "");
            d.put("msg_type", m.getOrDefault("msg_type", "text"));
            d.put("filename", m.get("filename") != null ? m.get("filename") : "");
            d.put("thumbnail", m.get("thumbnail") != null ? m.get("thumbnail") : "");
            d.put("created_at", m.getOrDefault("created_at", "").toString());
            // Consistent null-safe handling for boolean conversion
            Object isRead = m.get("is_read");
            d.put("is_read", isRead != null && ((Number) isRead).intValue() == 1);
            return d;
        }).toList()));
    }

    private void markRead(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        int senderId;
        try {
            senderId = Integer.parseInt(ctx.pathParam("senderId"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的发送者ID"));
            return;
        }

        int count = FamilyChatServer.getDb().markRead(senderId, userId);
        ctx.json(Map.of("marked_read", count));
    }

    private void sendMessage(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        Object fid = body.get("receiver_id");
        if (fid == null) {
            ctx.status(400).json(Map.of("error", "缺少 receiver_id"));
            return;
        }
        int receiverId;
        try {
            receiverId = ((Number) fid).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的 receiver_id"));
            return;
        }

        // Verify the receiver is a friend
        var friends = FamilyChatServer.getDb().getFriends(userId);
        boolean isFriend = friends.stream().anyMatch(f ->
                ((Number) f.get("id")).intValue() == receiverId);
        if (!isFriend) {
            ctx.status(403).json(Map.of("error", "只能给好友发送消息"));
            return;
        }

        Object contentObj = body.get("content");
        String content = contentObj instanceof String ? (String) contentObj : "";
        String msgType = body.getOrDefault("msg_type", "text").toString();
        Object filenameObj = body.get("filename");
        String filename = filenameObj instanceof String ? (String) filenameObj : "";
        // 视频消息缩略图 URL（明文预览图，与 content 的加密互不干扰）
        Object thumbObj = body.get("thumbnail");
        String thumbnail = thumbObj instanceof String ? (String) thumbObj : "";

        if (content.isEmpty() && "text".equals(msgType)) {
            ctx.status(400).json(Map.of("error", "消息不能为空"));
            return;
        }

        Map<String, Object> saved = FamilyChatServer.getDb().saveMessageWithThumbnail(userId, receiverId, content, msgType, filename, thumbnail);
        if (saved == null) {
            ctx.status(500).json(Map.of("error", "保存失败"));
            return;
        }

        // Notify receiver via WebSocket (broadcast to all connections)
        Map<String, Object> payload = Map.of(
                "type", "new_message",
                "id", saved.get("id"),
                "sender_id", userId,
                "receiver_id", receiverId,
                "content", content,
                "msg_type", msgType,
                "filename", filename,
                "thumbnail", thumbnail,
                "created_at", saved.getOrDefault("created_at", "").toString()
        );
        WsHandler.sendToUser(receiverId, payload);

        ctx.json(Map.of("success", true, "message", saved));
    }

    /**
     * 通话状态事件白名单。
     *
     * <p>抽成常量而不是散在 {@code if (… || …)} 里，是为了能被单测断言"恰好这 4 条"
     * （与 {@code WsHandler.CALL_SIGNALING_TYPES} 同款模式）。漏一条的后果是客户端的功能
     * <b>静默失效</b>：客户端发得出，服务端在这里不匹配就返回 400，聊天里什么都没有。
     */
    static final Set<String> CALL_STATUS_EVENTS =
            Set.of("rejected", "ended", "missed", "canceled");

    /**
     * 通话状态系统消息（密语一比一）：已拒绝 / 通话时长 MM:SS / 无人接听 / 已取消。
     * <pre>
     * event = "rejected" （被叫方上报，拒接 / 响铃中挂断）    →"已拒绝"
     * event = "ended"    （主叫方上报，带 duration_sec）      →"通话时长 MM:SS"
     * event = "missed"   （主叫方上报，响铃 30s 无人接听）    →"无人接听"
     * event = "canceled" （主叫方上报，对方接听前主动取消）   →"已取消"
     * </pre>
     *
     * <p><b>msg_type 由 body 的 {@code video} 决定</b>：true → {@code "call_video"}，
     * 否则 {@code "call_voice"}。客户端据此渲染图标并决定回拨发语音还是视频。
     * ⚠️ 与 {@code WsHandler.CALL_SIGNALING_TYPES} 里同名的 {@code "call_video"}
     * <b>不是同一个命名空间</b>：那个是 WS 信令信封的 type，这个是 {@code messages.msg_type}。
     *
     * <p><b>sender_id 恒为上报者</b>（见下方 {@code saveMessageWithFilename(userId, ...)}）：
     * 本接口不接受任何 sender 字段，客户端也无法指定 sender。四种 event 的归属因此完全由
     * "谁上报"决定 —— rejected → 被叫；ended / missed / canceled → 主叫。要改归属，改客户端的
     * 上报点，不要在这里加 sender 入参（那等于允许任意用户伪造他人发消息）。
     */
    private void reportCallStatus(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        Object fid = body.get("friend_id");
        if (fid == null) {
            ctx.status(400).json(Map.of("error", "缺少 friend_id"));
            return;
        }
        int friendId;
        try {
            friendId = ((Number) fid).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的 friend_id"));
            return;
        }

        // 通话状态写入前校验双方是好友,防止向任意用户注入"通话系统消息"
        if (userId == friendId) {
            ctx.status(400).json(Map.of("error", "friend_id 不能是自己"));
            return;
        }
        if (!FamilyChatServer.getDb().areFriends(userId, friendId)) {
            ctx.status(403).json(Map.of("error", "你们还不是好友"));
            return;
        }

        Object eventObj = body.get("event");
        String event = eventObj instanceof String ? (String) eventObj : "";
        if (!CALL_STATUS_EVENTS.contains(event)) {
            ctx.status(400).json(Map.of("error", "无效的 event"));
            return;
        }

        int durSec = 0;
        Object d = body.get("duration_sec");
        if (d instanceof Number) durSec = ((Number) d).intValue();

        String content = callStatusContent(event, durSec);

        // 语音/视频区分。缺省（老客户端不传 video）按语音处理，见方法 javadoc。
        // bodyAsClass(Map) 对 JSON boolean 给 Boolean；若客户端把 "true" 当字符串发则给 String；
        // 缺失为 null。三态都在这里收敛，不做裸强转。
        Object v = body.get("video");
        boolean video = (v instanceof Boolean) ? (Boolean) v
                : (v instanceof String) && Boolean.parseBoolean((String) v);
        String msgType = callMsgType(video);

        Map<String, Object> saved = FamilyChatServer.getDb()
                .saveMessageWithFilename(userId, friendId, content, msgType, "");
        if (saved == null) {
            ctx.status(500).json(Map.of("error", "保存失败"));
            return;
        }

        Map<String, Object> payload = Map.of(
                "type", "new_message",
                "id", saved.get("id"),
                "sender_id", userId,
                "receiver_id", friendId,
                "content", content,
                "msg_type", msgType,
                "filename", "",
                "created_at", saved.getOrDefault("created_at", "").toString()
        );
        WsHandler.sendToUser(friendId, payload);

        ctx.json(Map.of("success", true, "message", saved));
    }

    /**
     * event → 聊天里显示的文案（纯函数，便于单测）。
     *
     * <p>只接受 {@link #CALL_STATUS_EVENTS} 里的 4 种；其余（含 {@code null}）<b>抛异常</b>：
     * 白名单里有、文案表里没有 = 新增 event 时漏了文案。宁可让请求 500，也不要静默发一条
     * 文案错误的通话消息 —— 那种错误没有任何日志，只能靠用户反馈发现。
     */
    static String callStatusContent(String event, int durationSec) {
        if ("rejected".equals(event)) return "已拒绝";
        if ("missed".equals(event)) return "无人接听";
        if ("canceled".equals(event)) return "已取消";
        if ("ended".equals(event)) {
            return "通话时长 " + formatCallDuration(Math.max(0, durationSec));
        }
        throw new IllegalArgumentException("未登记文案的 call-status event: " + event);
    }

    /**
     * video → {@code messages.msg_type}（纯函数，便于单测）。
     *
     * <p>注意命名空间：这里的 {@code "call_video"} 是<b>消息类型</b>，
     * 与 {@code WsHandler.CALL_SIGNALING_TYPES} 里同名的 WS <b>信令 type</b> 无关。
     */
    static String callMsgType(boolean video) {
        return video ? "call_video" : "call_voice";
    }

    private static String formatCallDuration(int secs) {
        int m = secs / 60;
        int s = secs % 60;
        return String.format("%02d:%02d", m, s);
    }

    private void searchMessages(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        String fidStr = ctx.queryParam("friend_id");
        if (fidStr == null) {
            ctx.status(400).json(Map.of("error", "缺少 friend_id"));
            return;
        }
        int friendId;
        try {
            friendId = Integer.parseInt(fidStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的好友ID"));
            return;
        }

        String keyword = ctx.queryParam("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            ctx.json(Map.of("messages", List.of(), "has_more", false));
            return;
        }

        int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
        int pageSize = ctx.queryParamAsClass("page_size", Integer.class).getOrDefault(20);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 50) pageSize = 50;

        // 多取一条判断是否还有更多
        List<Map<String, Object>> msgs = FamilyChatServer.getDb().searchMessages(
                userId, friendId, keyword.trim(), pageSize + 1, (page - 1) * pageSize);
        boolean hasMore = msgs.size() > pageSize;
        List<Map<String, Object>> pageMsgs = hasMore ? msgs.subList(0, pageSize) : msgs;

        ctx.json(Map.of("messages", pageMsgs.stream().map(m -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", m.get("id"));
            d.put("sender_id", m.get("sender_id"));
            d.put("receiver_id", m.get("receiver_id"));
            d.put("content", m.get("content") != null ? m.get("content") : "");
            d.put("msg_type", m.getOrDefault("msg_type", "text"));
            d.put("filename", m.get("filename") != null ? m.get("filename") : "");
            d.put("thumbnail", m.get("thumbnail") != null ? m.get("thumbnail") : "");
            d.put("created_at", m.getOrDefault("created_at", "").toString());
            Object isRead = m.get("is_read");
            d.put("is_read", isRead != null && ((Number) isRead).intValue() == 1);
            return d;
        }).toList(), "has_more", hasMore));
    }

    private void deleteMessage(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int msgId;
        try { msgId = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的消息ID")); return; }
        boolean ok = FamilyChatServer.getDb().deleteMessage(msgId, userId);
        ctx.json(Map.of("success", ok));
    }

    private void recallMessage(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int msgId;
        try { msgId = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的消息ID")); return; }
        boolean ok = FamilyChatServer.getDb().recallMessage(msgId, userId);
        System.out.println("[RECALL_LOG] Server: recall msgId=" + msgId + " by userId=" + userId + " ok=" + ok);
        if (ok) {
            // Notify BOTH parties via WebSocket
            WsHandler.sendToUser(userId, Map.of("type","message_recalled","id",msgId));
            System.out.println("[RECALL_LOG] Server: WS notify sender uid=" + userId);
            Map<String, Object> msg = FamilyChatServer.getDb().getMessageById(msgId);
            if (msg != null) {
                int otherId = ((Number)msg.get("receiver_id")).intValue() == userId ?
                    ((Number)msg.get("sender_id")).intValue() : ((Number)msg.get("receiver_id")).intValue();
                WsHandler.sendToUser(otherId, Map.of("type","message_recalled","id",msgId));
                System.out.println("[RECALL_LOG] Server: WS notify receiver uid=" + otherId);
            }
        }
        ctx.json(Map.of("success", ok));
    }

    private void clearConversation(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int friendId;
        try { friendId = Integer.parseInt(ctx.pathParam("friendId")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的好友ID")); return; }
        int deleted = FamilyChatServer.getDb().clearConversation(userId, friendId);
        ctx.json(Map.of("success", true, "deleted", deleted));
    }
}
