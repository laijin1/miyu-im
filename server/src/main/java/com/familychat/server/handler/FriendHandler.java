package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.*;

public class FriendHandler {
    public void register(Javalin app) {
        app.get("/api/users/search", this::searchUsers);
        app.get("/api/users/{id}", this::getUserById);
        app.post("/api/friends/request", this::sendRequest);
        app.get("/api/friends/requests", this::getRequests);
        app.get("/api/friends/requests/sent", this::getSentRequests);
        app.post("/api/friends/request/{id}/{action}", this::handleRequest);
        app.get("/api/friends", this::getFriends);
        app.delete("/api/friends/{friendId}", this::deleteFriend);
        app.post("/api/friend/remark", this::setFriendRemark);
    }

    private void setFriendRemark(Context ctx) {
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
            ctx.status(400).json(Map.of("error", "缺少好友ID"));
            return;
        }
        int friendId;
        try {
            friendId = ((Number) fid).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的好友ID"));
            return;
        }
        String remark = body.get("remark") != null ? body.get("remark").toString() : "";

        boolean ok = FamilyChatServer.getDb().setFriendRemark(userId, friendId, remark);
        if (!ok) {
            ctx.status(400).json(Map.of("error", "更新备注失败，请确认已是好友"));
            return;
        }
        ctx.json(Map.of("success", true, "remark", remark));
    }

    /** 按 id 查询用户（扫码/资料页用），返回 userDict。 */
    private void getUserById(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int targetId;
        try {
            targetId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的用户ID"));
            return;
        }
        var u = FamilyChatServer.getDb().getUserById(targetId);
        if (u == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        ctx.json(Map.of("user", AuthHandler.userDict(u)));
    }

    private void searchUsers(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        String q = ctx.queryParam("q");
        if (q == null || q.trim().isEmpty()) {
            ctx.json(Map.of("users", List.of()));
            return;
        }
        var users = FamilyChatServer.getDb().searchUsers(q.trim(), userId);
        ctx.json(Map.of("users", users.stream().map(AuthHandler::userDict).toList()));
    }

    private void sendRequest(Context ctx) {
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
            ctx.status(400).json(Map.of("error", "缺少好友ID"));
            return;
        }
        int friendId;
        try {
            friendId = ((Number) fid).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的好友ID"));
            return;
        }

        var result = FamilyChatServer.getDb().sendFriendRequest(userId, friendId);
        if (result.containsKey("error")) {
            ctx.status(400).json(result);
            return;
        }

        // Notify receiver via WebSocket (broadcast to all connections)
        var user = FamilyChatServer.getDb().getUserById(userId);
        if (user != null) {
            WsHandler.sendToUser(friendId, Map.of(
                    "type", "friend_request",
                    "from_user", AuthHandler.userDict(user)
            ));
        }

        ctx.json(result);
    }

    private void getRequests(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        var reqs = FamilyChatServer.getDb().getPendingRequests(userId);
        ctx.json(Map.of("requests", reqs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("friendship_id", r.get("friendship_id"));
            m.put("user", AuthHandler.userDict(r));
            m.put("created_at", r.getOrDefault("created_at", "").toString());
            return m;
        }).toList()));
    }

    private void getSentRequests(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        var reqs = FamilyChatServer.getDb().getSentRequests(userId);
        ctx.json(Map.of("requests", reqs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("friendship_id", r.get("friendship_id"));
            m.put("user", AuthHandler.userDict(r));
            m.put("status", r.get("status"));
            m.put("created_at", r.getOrDefault("created_at", "").toString());
            return m;
        }).toList()));
    }

    private void handleRequest(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        int fsId;
        try {
            fsId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的请求ID"));
            return;
        }

        String action = ctx.pathParam("action");
        if (!"accept".equals(action) && !"reject".equals(action)) {
            ctx.status(400).json(Map.of("error", "无效操作"));
            return;
        }

        // Look up the friendship BEFORE changing status to verify ownership
        // and get the requester ID for auto-message notification.
        Integer requesterId = null;
        int friendId = -1;
        try (var c = FamilyChatServer.getDb().getConn()) {
            var ps = c.prepareStatement("SELECT user_id, friend_id FROM friendships WHERE id=?");
            ps.setInt(1, fsId);
            var rs = ps.executeQuery();
            if (!rs.next()) {
                ctx.status(404).json(Map.of("error", "好友请求不存在"));
                return;
            }
            requesterId = rs.getInt("user_id");
            friendId = rs.getInt("friend_id");
        } catch (Exception e) {
            System.err.println("[ERROR] handleRequest lookup: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "查询失败"));
            return;
        }

        // CRITICAL: Verify the authenticated user is the recipient (friend_id)
        if (friendId != userId) {
            ctx.status(403).json(Map.of("error", "无权操作此好友请求"));
            return;
        }

        if (!FamilyChatServer.getDb().handleFriendRequest(fsId, userId, action)) {
            ctx.status(400).json(Map.of("error", "操作失败"));
            return;
        }

        if ("accept".equals(action)) {
            if (requesterId != null && requesterId > 0) {
                // Save auto-message "我已同意好友请求"
                String msg = "我已同意好友请求";
                FamilyChatServer.getDb().saveMessage(userId, requesterId, msg, "text");

                // Notify requester via WebSocket
                Map<String, Object> payload = Map.of(
                        "type", "new_message",
                        "sender_id", userId,
                        "receiver_id", requesterId,
                        "content", msg,
                        "msg_type", "text"
                );
                WsHandler.sendToUser(requesterId, payload);
            }

            // Notify the accepter's other devices
            WsHandler.sendToUser(userId, Map.of(
                    "type", "friend_request_handled",
                    "action", "accept"
            ));
        }

        ctx.json(Map.of("success", true));
    }

    private void getFriends(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        var friends = FamilyChatServer.getDb().getFriends(userId);
        ctx.json(Map.of("friends", friends.stream().map(AuthHandler::userDict).toList()));
    }

    private void deleteFriend(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int friendId;
        try { friendId = Integer.parseInt(ctx.pathParam("friendId")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的好友ID")); return; }
        // Clear messages + delete friendship
        FamilyChatServer.getDb().clearConversation(userId, friendId);
        boolean ok = FamilyChatServer.getDb().deleteFriendship(userId, friendId);
        ctx.json(Map.of("success", ok));
    }
}
