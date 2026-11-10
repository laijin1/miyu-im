package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群投票（全仿 QQ，阶段5）。
 * 投票内容/选项/票数为明文，服务端统计（决策：投票明文）；匿名投票隐藏实名，仅显示人数。
 * 权限：发起=任意成员；投票=任意成员(可改票)；关闭/删除=创建者或群主/管理员。
 * 创建时写一条系统消息「XX 发起了一个投票」（复刻 QQ），并 WS 推 group_poll。
 */
public class GroupPollHandler {

    public void register(Javalin app) {
        app.get("/api/groups/{id}/polls", this::listPolls);
        app.post("/api/groups/{id}/polls", this::createPoll);
        app.get("/api/groups/{id}/polls/{pid}", this::pollDetail);
        app.post("/api/groups/{id}/polls/{pid}/vote", this::vote);
        app.post("/api/groups/{id}/polls/{pid}/close", this::close);
        app.delete("/api/groups/{id}/polls/{pid}", this::delete);
    }

    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 投票列表（带选项计数 + my_voted + expired）。 */
    private void listPolls(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        List<Map<String, Object>> polls = FamilyChatServer.getDb().getGroupPolls(groupId, userId);
        for (Map<String, Object> p : polls) decorate(p);
        ctx.json(Map.of("polls", polls));
    }

    /** 发起投票：{title, description?, options:[...], is_multi?, anonymous?, end_at?} */
    private void createPoll(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String title = body.get("title") instanceof String s ? s.trim() : "";
        if (title.isEmpty()) { ctx.status(400).json(Map.of("error", "请填写投票标题")); return; }
        if (title.length() > 60) title = title.substring(0, 60);
        String description = body.get("description") instanceof String d ? d.trim() : "";
        boolean isMulti = Boolean.TRUE.equals(body.get("is_multi"))
                || body.get("is_multi") instanceof Number n && n.intValue() == 1;
        boolean anonymous = Boolean.TRUE.equals(body.get("anonymous"))
                || body.get("anonymous") instanceof Number n2 && n2.intValue() == 1;
        long endAt = 0;
        try { endAt = ((Number) body.getOrDefault("end_at", 0)).longValue(); } catch (Exception ignored) {}
        List<String> options = new ArrayList<>();
        if (body.get("options") instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s && !s.trim().isEmpty()) options.add(s.trim());
        }
        if (options.size() < 2) { ctx.status(400).json(Map.of("error", "至少需要 2 个选项")); return; }
        if (options.size() > 10) options = options.subList(0, 10);
        int pollId = FamilyChatServer.getDb().createGroupPoll(
                groupId, userId, title, description, isMulti, anonymous, endAt, options);
        if (pollId == 0) { ctx.status(500).json(Map.of("error", "创建失败")); return; }
        // 复刻 QQ：系统消息「XX 发起了一个投票」
        GroupHandler.addSystemMessage(groupId, userId,
                GroupHandler.displayName(userId) + " 发起了一个投票：\"" + title + "\"");
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_poll", "action", "create",
                "group_id", groupId, "poll_id", pollId, "title", title));
        Map<String, Object> detail = FamilyChatServer.getDb().getGroupPoll(pollId, userId);
        decorate(detail);
        ctx.json(Map.of("success", true, "poll", detail));
    }

    /** 单条投票详情。 */
    private void pollDetail(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int pid = parseId(ctx, "pid");
        if (pid < 0) return;
        Map<String, Object> poll = FamilyChatServer.getDb().getGroupPoll(pid, userId);
        if (poll == null || ((Number) poll.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        decorate(poll);
        ctx.json(poll);
    }

    /** 投票/改票：{option_ids:[...]}（先删旧票再插新票）。 */
    private void vote(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int pid = parseId(ctx, "pid");
        if (pid < 0) return;
        Map<String, Object> poll = FamilyChatServer.getDb().getGroupPoll(pid, userId);
        if (poll == null || ((Number) poll.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        decorate(poll);  // 补 expired 标记：原始 getGroupPoll 不带该字段，否则下面的过期判断永远不生效
        boolean closed = "closed".equals(poll.get("status"));
        if (closed) { ctx.status(400).json(Map.of("error", "投票已结束")); return; }
        if (Boolean.TRUE.equals(poll.get("expired"))) {
            ctx.status(400).json(Map.of("error", "投票已过期")); return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        List<Object> rawIds = body.get("option_ids") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        if (rawIds.isEmpty()) { ctx.status(400).json(Map.of("error", "请选择选项")); return; }
        // 校验选项归属本投票
        List<?> opts = (List<?>) poll.get("options");
        List<Integer> validIds = new ArrayList<>();
        for (Object o : opts) {
            if (o instanceof Map<?, ?> om && om.get("id") instanceof Number on) {
                validIds.add(on.intValue());
            }
        }
        List<Integer> picks = new ArrayList<>();
        for (Object r : rawIds) {
            if (!(r instanceof Number)) continue;
            int oid = ((Number) r).intValue();
            if (validIds.contains(oid) && !picks.contains(oid)) picks.add(oid);
        }
        if (picks.isEmpty()) { ctx.status(400).json(Map.of("error", "选项无效")); return; }
        boolean multi = Boolean.TRUE.equals(poll.get("is_multi"))
                || poll.get("is_multi") instanceof Number n && n.intValue() == 1;
        if (!multi && picks.size() > 1) {
            ctx.status(400).json(Map.of("error", "这是单选投票")); return;
        }
        if (multi && picks.size() > 10) picks = picks.subList(0, 10);
        if (!FamilyChatServer.getDb().votePoll(pid, userId, picks)) {
            ctx.status(500).json(Map.of("error", "投票失败")); return;
        }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_poll", "action", "vote",
                "group_id", groupId, "poll_id", pid));
        Map<String, Object> detail = FamilyChatServer.getDb().getGroupPoll(pid, userId);
        decorate(detail);
        ctx.json(Map.of("success", true, "poll", detail));
    }

    /** 结束投票：创建者或群主/管理员。 */
    private void close(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int pid = parseId(ctx, "pid");
        if (pid < 0) return;
        Map<String, Object> poll = FamilyChatServer.getDb().getGroupPoll(pid, userId);
        if (poll == null || ((Number) poll.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        boolean canClose = ((Number) poll.get("creator_id")).intValue() == userId
                || GroupHandler.isAdmin(groupId, userId, group);
        if (!canClose) {
            ctx.status(403).json(Map.of("error", "只有发起者或群主/管理员可结束投票")); return;
        }
        if (!FamilyChatServer.getDb().closePoll(pid)) {
            ctx.status(500).json(Map.of("error", "操作失败")); return;
        }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_poll", "action", "close",
                "group_id", groupId, "poll_id", pid));
        ctx.json(Map.of("success", true));
    }

    /** 删除投票：创建者或群主/管理员。 */
    private void delete(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int pid = parseId(ctx, "pid");
        if (pid < 0) return;
        Map<String, Object> poll = FamilyChatServer.getDb().getGroupPoll(pid, userId);
        if (poll == null || ((Number) poll.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        boolean canDelete = ((Number) poll.get("creator_id")).intValue() == userId
                || GroupHandler.isAdmin(groupId, userId, group);
        if (!canDelete) {
            ctx.status(403).json(Map.of("error", "只有发起者或群主/管理员可删除投票")); return;
        }
        if (!FamilyChatServer.getDb().deletePoll(pid)) {
            ctx.status(500).json(Map.of("error", "删除失败")); return;
        }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_poll", "action", "delete",
                "group_id", groupId, "poll_id", pid));
        ctx.json(Map.of("success", true));
    }

    /** 补 expired 标记（end_at 已过视为过期，客户端据此禁投）。 */
    private void decorate(Map<String, Object> poll) {
        if (poll == null) return;
        Object endAt = poll.get("end_at");
        boolean expired = false;
        if (endAt != null && !String.valueOf(endAt).isEmpty()) {
            try {
                long end = java.time.LocalDateTime.parse(String.valueOf(endAt), FMT)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                expired = System.currentTimeMillis() > end;
            } catch (Exception ignored) {}
        }
        poll.put("expired", expired);
    }

    private int parseId(Context ctx, String param) {
        try { return Integer.parseInt(ctx.pathParam(param)); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的ID")); return -1; }
    }
}
