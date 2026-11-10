package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 群聊接口（密语式 + QQ 式扩展）。
 * 所有操作鉴权，且"发消息/拉历史/拉人/移除"都校验调用者是自己加入的群（防越权）。
 * 群消息 content 为 {成员id: 密文} 的 JSON，服务端只存不解（与单聊一致）。
 * 系统消息（msg_type='system'）为明文中文，服务端生成。
 */
public class GroupHandler {
    // 线程安全（SimpleDateFormat 非线程安全，多线程并发会错乱/抛异常）
    private static final java.time.format.DateTimeFormatter MUTE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public void register(Javalin app) {
        app.post("/api/groups", this::createGroup);
        app.get("/api/groups", this::getMyGroups);
        app.get("/api/groups/{id}", this::getGroupDetail);
        app.post("/api/groups/{id}/members", this::addMember);
        app.delete("/api/groups/{id}/members/{uid}", this::removeMember);
        app.post("/api/groups/{id}/messages", this::sendMessage);
        app.get("/api/groups/{id}/messages", this::getMessages);
        app.post("/api/groups/{id}/read", this::markRead);
        app.post("/api/groups/{id}/messages/{msgId}/recall", this::recallMessage);
        // ── 权限体系/群资料（阶段1）──
        app.put("/api/groups/{id}", this::updateGroupInfo);                    // 改名/换头像（群主）
        app.post("/api/groups/{id}/nickname", this::updateMyNickname);         // 群名片（成员自己）
        app.post("/api/groups/{id}/admin", this::setAdmin);                    // 任/免管理员（群主）
        app.put("/api/groups/{id}/members/{uid}/mute", this::setMute);         // 禁言（群主/管理员）
        app.post("/api/groups/{id}/transfer", this::transferGroup);            // 转让群主（群主）
        app.delete("/api/groups/{id}", this::dissolveGroup);                   // 解散群（群主）
        app.get("/api/groups/{id}/settings", this::getGroupSettings);          // 会话设置（置顶/免打扰）
        app.put("/api/groups/{id}/settings", this::updateGroupSettings);       // 更新会话设置
        // ── 群公告（阶段3，owner/admin 发布/改/删/置顶，成员看+已读）──
        app.post("/api/groups/{id}/announcements", this::createAnnouncement);
        app.get("/api/groups/{id}/announcements", this::listAnnouncements);
        app.get("/api/groups/{id}/announcements/{aid}", this::getAnnouncement);
        app.put("/api/groups/{id}/announcements/{aid}", this::updateAnnouncement);
        app.delete("/api/groups/{id}/announcements/{aid}", this::deleteAnnouncement);
        app.post("/api/groups/{id}/announcements/{aid}/read", this::markAnnouncementRead);
        // ── 群内搜索（阶段3，只搜明文：文件名/系统消息）──
        app.get("/api/groups/{id}/messages/search", this::searchMessages);
    }

    // ===== 鉴权助手 =====

    /** 校验调用者是群成员，返回 groupId；失败已写响应，返回 -1。 */
    /** 鉴权：必须是群成员，返回 groupId，否则 -1 并已写错误响应。包内共享（GroupContentHandler/GroupPollHandler 复用）。 */
    static int requireGroupMember(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return -1;
        int groupId;
        try { groupId = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的群ID")); return -1; }
        // 群主始终有权访问自己的群（兼容旧数据里群主成员记录缺失/被误删的情况，
        // 与 memberRole() 的 owner 兜底保持一致）
        var g = FamilyChatServer.getDb().getGroupById(groupId);
        boolean isOwner = g != null && ((Number) g.get("owner_id")).intValue() == userId;
        if (!isOwner && !FamilyChatServer.getDb().isGroupMember(groupId, userId)) {
            ctx.status(403).json(Map.of("error", "你不是该群成员"));
            return -1;
        }
        return groupId;
    }

    /** 校验调用者是群成员且为群主/管理员，返回 groupId；失败已写响应，返回 -1。 */
    /** 鉴权：必须是群主/管理员，返回 groupId，否则 -1。包内共享。 */
    static int requireGroupAdmin(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return -1;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return -1;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return -1; }
        if (!isAdmin(groupId, userId, group)) {
            ctx.status(403).json(Map.of("error", "只有群主或管理员可操作"));
            return -1;
        }
        return groupId;
    }

    /** 成员角色：owner 优先看 groups.owner_id（兼容老库未回填），否则读 role 列。包内共享。 */
    static String memberRole(int groupId, int userId, Map<String, Object> group) {
        if (group != null && ((Number) group.get("owner_id")).intValue() == userId) return "owner";
        return FamilyChatServer.getDb().getGroupMemberRole(groupId, userId);
    }

    static boolean isAdmin(int groupId, int userId, Map<String, Object> group) {
        String role = memberRole(groupId, userId, group);
        return "owner".equals(role) || "admin".equals(role);
    }

    /** 是否禁言中（群主/管理员不禁言）。 */
    private boolean isMuted(int groupId, int userId, Map<String, Object> group) {
        if ("owner".equals(memberRole(groupId, userId, group))) return false;
        long until = FamilyChatServer.getDb().getGroupMemberMuteUntil(groupId, userId);
        return until == -1 || until > System.currentTimeMillis();
    }

    static List<Integer> allMemberIds(int groupId) {
        return FamilyChatServer.getDb().getGroupMembers(groupId).stream()
                .map(m -> ((Number) m.get("id")).intValue()).toList();
    }

    /** 用户显示名：群内展示用昵称，取全局昵称否则账号。包内共享。 */
    static String displayName(int uid) {
        Map<String, Object> u = FamilyChatServer.getDb().getUserById(uid);
        if (u == null) return "用户" + uid;
        String n = u.get("nickname") instanceof String s && !s.isEmpty() ? s : "";
        if (n.isEmpty()) n = u.get("username") instanceof String us ? us : "";
        return n.isEmpty() ? "用户" + uid : n;
    }

    /** 写一条系统消息并广播给全体成员（msg_type='system'，明文）。 */
    static void addSystemMessage(int groupId, int actorId, String text) {
        Map<String, Object> saved = FamilyChatServer.getDb()
                .saveGroupMessage(groupId, actorId, text, "system", "", "", 0);
        if (saved == null) return;
        Map<String, Object> payload = Map.of(
                "type", "group_new_message",
                "id", saved.get("id"),
                "group_id", groupId,
                "sender_id", actorId,
                "content", text,
                "msg_type", "system",
                "filename", "",
                "thumbnail", "",
                "duration_sec", 0,
                "created_at", saved.getOrDefault("created_at", "").toString());
        WsHandler.sendToUsers(allMemberIds(groupId), payload);
    }

    /** 广播成员变更事件（action: add/remove/role/mute/nickname/info/transfer）。 */
    private void broadcastMembersChanged(int groupId, String action, int actorId) {
        WsHandler.sendToUsers(allMemberIds(groupId),
                Map.of("type", "group_members_changed", "group_id", groupId,
                        "action", action, "actor_id", actorId));
    }

    private List<Integer> memberIdList(Context ctx) {
        List<Integer> ids = new ArrayList<>();
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object m = body.get("member_ids");
            if (m instanceof List<?> list) {
                for (Object o : list) ids.add(((Number) o).intValue());
            }
        } catch (Exception ignored) {}
        return ids;
    }

    /** 建群：name + member_ids[]，自动把创建者加进去（role=owner）。 */
    private void createGroup(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        Object nameObj = body.get("name");
        String name = nameObj instanceof String ? (String) nameObj : "群聊";
        List<Integer> memberIds = new ArrayList<>();
        Object m = body.get("member_ids");
        if (m instanceof List<?> list) {
            for (Object o : list) {
                try { memberIds.add(((Number) o).intValue()); }
                catch (Exception ignored) {}
            }
        }
        if (memberIds.isEmpty()) {
            ctx.status(400).json(Map.of("error", "请至少选择一位好友"));
            return;
        }

        // 校验所选成员都是创建者的好友,防止把陌生人拉进群(创建者会自动加入,无需在列表里)
        for (int mid : memberIds) {
            if (mid == userId || !FamilyChatServer.getDb().areFriends(userId, mid)) {
                ctx.status(403).json(Map.of("error", "只能邀请自己的好友进群"));
                return;
            }
        }

        int groupId = FamilyChatServer.getDb().createGroup(userId, name, memberIds);
        if (groupId == 0) { ctx.status(500).json(Map.of("error", "建群失败")); return; }
        // 通知新群成员
        WsHandler.sendToUsers(allMemberIds(groupId),
                Map.of("type", "group_members_changed", "group_id", groupId,
                        "action", "add", "actor_id", userId));
        ctx.json(Map.of("success", true, "group", groupDict(FamilyChatServer.getDb().getGroupById(groupId))));
    }

    /** 我加入的群列表。 */
    private void getMyGroups(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        List<Map<String, Object>> groups = FamilyChatServer.getDb().getMyGroups(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            Map<String, Object> d = groupDict(g);
            int gid = ((Number) g.get("id")).intValue();
            d.put("my_role", memberRole(gid, userId, g));
            d.put("my_nickname", FamilyChatServer.getDb().getGroupMemberNickname(gid, userId));
            d.put("muted", isMuted(gid, userId, g) ? 1 : 0);
            var st = FamilyChatServer.getDb().getGroupSetting(gid, userId);
            d.put("pinned", st.getOrDefault("pinned", 0));
            d.put("quiet", st.getOrDefault("quiet", 0));
            result.add(d);
        }
        ctx.json(Map.of("groups", result));
    }

    private Map<String, Object> groupDict(Map<String, Object> g) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (g == null) return d;
        d.put("id", g.get("id"));
        d.put("name", g.get("name"));
        d.put("avatar", g.get("avatar") != null ? g.get("avatar") : "");
        d.put("owner_id", g.get("owner_id"));
        d.put("created_at", g.getOrDefault("created_at", "").toString());
        d.put("member_count", g.getOrDefault("member_count", 0));
        if (g.containsKey("latest_announcement_id")) d.put("latest_announcement_id", g.get("latest_announcement_id"));
        if (g.containsKey("unread")) d.put("unread", g.get("unread"));
        // 最后一条消息预览（content 是密文 JSON，客户端解密展示）
        for (String k : new String[]{"last_message_id", "last_sender_id", "last_content",
                "last_msg_type", "last_filename", "last_thumbnail", "last_created_at"}) {
            if (g.containsKey(k)) d.put(k, g.get(k));
        }
        return d;
    }

    /** 群详情：群信息 + 成员列表（成员带 role/group_nickname/mute_until）。 */
    private void getGroupDetail(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        var group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        Map<String, Object> d = groupDict(group);
        d.put("my_role", memberRole(groupId, userId, group));
        d.put("my_nickname", FamilyChatServer.getDb().getGroupMemberNickname(groupId, userId));
        d.put("muted", isMuted(groupId, userId, group) ? 1 : 0);
        var st = FamilyChatServer.getDb().getGroupSetting(groupId, userId);
        d.put("pinned", st.getOrDefault("pinned", 0));
        d.put("quiet", st.getOrDefault("quiet", 0));
        ctx.json(Map.of("group", d,
                "members", FamilyChatServer.getDb().getGroupMembers(groupId)));
    }

    /** 查询群会话设置（置顶/免打扰）。 */
    private void getGroupSettings(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        ctx.json(FamilyChatServer.getDb().getGroupSetting(groupId, userId));
    }

    /** 更新群会话设置：body {pinned?:0/1, quiet?:0/1}（置顶/免打扰）。 */
    private void updateGroupSettings(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        Integer pinned = body.get("pinned") instanceof Number n ? n.intValue() : null;
        Integer quiet = body.get("quiet") instanceof Number n ? n.intValue() : null;
        if (pinned == null && quiet == null) {
            ctx.status(400).json(Map.of("error", "没有需要修改的设置"));
            return;
        }
        boolean ok = FamilyChatServer.getDb().setGroupSetting(groupId, userId, pinned, quiet);
        if (!ok) { ctx.status(500).json(Map.of("error", "保存失败")); return; }
        ctx.json(Map.of("success", true, "settings", FamilyChatServer.getDb().getGroupSetting(groupId, userId)));
    }

    /** 改群名/群头像（仅群主）。 */
    private void updateGroupInfo(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        if (!"owner".equals(memberRole(groupId, userId, group))) {
            ctx.status(403).json(Map.of("error", "只有群主可以修改群资料"));
            return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String name = body.get("name") instanceof String s && !s.trim().isEmpty() ? s.trim() : null;
        String avatar = body.get("avatar") instanceof String a ? a : null;
        if (name == null && avatar == null) {
            ctx.status(400).json(Map.of("error", "没有需要修改的内容"));
            return;
        }
        if (name != null && name.length() > 30) {
            ctx.status(400).json(Map.of("error", "群名最长 30 字"));
            return;
        }
        if (!FamilyChatServer.getDb().updateGroupInfo(groupId, name, avatar)) {
            ctx.status(500).json(Map.of("error", "修改失败"));
            return;
        }
        broadcastMembersChanged(groupId, "info", userId);
        ctx.json(Map.of("success", true, "group", groupDict(FamilyChatServer.getDb().getGroupById(groupId))));
    }

    /** 设置我的群名片（群内昵称）。 */
    private void updateMyNickname(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String nickname = body.get("nickname") instanceof String s ? s.trim() : "";
        if (nickname.length() > 24) {
            ctx.status(400).json(Map.of("error", "群名片最长 24 字"));
            return;
        }
        if (!FamilyChatServer.getDb().setGroupMemberNickname(groupId, userId, nickname)) {
            ctx.status(500).json(Map.of("error", "设置失败"));
            return;
        }
        broadcastMembersChanged(groupId, "nickname", userId);
        ctx.json(Map.of("success", true));
    }

    /** 任/免管理员（仅群主）。 */
    private void setAdmin(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        if (!"owner".equals(memberRole(groupId, userId, group))) {
            ctx.status(403).json(Map.of("error", "只有群主可以设置管理员"));
            return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int targetUid;
        try { targetUid = ((Number) body.get("user_id")).intValue(); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 user_id")); return; }
        String action = "appoint".equals(body.getOrDefault("action", "appoint").toString()) ? "appoint" : "revoke";
        if (targetUid == userId) {
            ctx.status(400).json(Map.of("error", "不能对自己操作"));
            return;
        }
        if (!FamilyChatServer.getDb().isGroupMember(groupId, targetUid)) {
            ctx.status(400).json(Map.of("error", "该用户不是群成员"));
            return;
        }
        String newRole = "appoint".equals(action) ? "admin" : "member";
        if (!FamilyChatServer.getDb().setGroupMemberRole(groupId, targetUid, newRole)) {
            ctx.status(500).json(Map.of("error", "操作失败"));
            return;
        }
        if ("appoint".equals(action)) {
            addSystemMessage(groupId, userId, displayName(userId) + " 将 " + displayName(targetUid) + " 设为管理员");
        } else {
            addSystemMessage(groupId, userId, displayName(userId) + " 取消了 " + displayName(targetUid) + " 的管理员身份");
        }
        broadcastMembersChanged(groupId, "role", userId);
        ctx.json(Map.of("success", true));
    }

    /** 禁言/解除禁言（群主/管理员；admin 不能禁言 admin，不能禁言群主）。 */
    private void setMute(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        if (!isAdmin(groupId, userId, group)) {
            ctx.status(403).json(Map.of("error", "只有群主或管理员可操作"));
            return;
        }
        int targetUid;
        try { targetUid = Integer.parseInt(ctx.pathParam("uid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的用户ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        long untilMs;
        try { untilMs = ((Number) body.get("until_ms")).longValue(); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 until_ms")); return; }

        String myRole = memberRole(groupId, userId, group);
        String targetRole = memberRole(groupId, targetUid, group);
        if (targetUid == userId) {
            ctx.status(400).json(Map.of("error", "不能禁言自己"));
            return;
        }
        if ("owner".equals(targetRole)) {
            ctx.status(400).json(Map.of("error", "不能禁言群主"));
            return;
        }
        if ("admin".equals(myRole) && "admin".equals(targetRole)) {
            ctx.status(400).json(Map.of("error", "管理员不能禁言管理员"));
            return;
        }
        if (!FamilyChatServer.getDb().setGroupMemberMuteUntil(groupId, targetUid, untilMs)) {
            ctx.status(500).json(Map.of("error", "操作失败"));
            return;
        }
        if (untilMs == 0) {
            addSystemMessage(groupId, userId, displayName(targetUid) + " 已被解除禁言");
        } else if (untilMs == -1) {
            addSystemMessage(groupId, userId, displayName(targetUid) + " 已被永久禁言");
        } else {
            addSystemMessage(groupId, userId, displayName(targetUid) + " 已被禁言至 "
                    + MUTE_FMT.format(java.time.Instant.ofEpochMilli(untilMs)
                            .atZone(java.time.ZoneId.systemDefault())));
        }
        broadcastMembersChanged(groupId, "mute", userId);
        ctx.json(Map.of("success", true));
    }

    /** 转让群主（仅群主，目标须为群成员）。旧主降为 admin。 */
    private void transferGroup(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        if (!"owner".equals(memberRole(groupId, userId, group))) {
            ctx.status(403).json(Map.of("error", "只有群主可以转让群"));
            return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int targetUid;
        try { targetUid = ((Number) body.get("user_id")).intValue(); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 user_id")); return; }
        if (targetUid == userId) {
            ctx.status(400).json(Map.of("error", "不能转让给自己"));
            return;
        }
        if (!FamilyChatServer.getDb().isGroupMember(groupId, targetUid)) {
            ctx.status(400).json(Map.of("error", "该用户不是群成员"));
            return;
        }
        if (!FamilyChatServer.getDb().transferGroup(groupId, userId, targetUid)) {
            ctx.status(500).json(Map.of("error", "转让失败"));
            return;
        }
        addSystemMessage(groupId, userId, displayName(userId) + " 已将群主转让给 " + displayName(targetUid));
        broadcastMembersChanged(groupId, "transfer", userId);
        ctx.json(Map.of("success", true,
                "group", groupDict(FamilyChatServer.getDb().getGroupById(groupId))));
    }

    /** 解散群（仅群主）。先广播解散，再事务级联删除。 */
    private void dissolveGroup(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        if (!"owner".equals(memberRole(groupId, userId, group))) {
            ctx.status(403).json(Map.of("error", "只有群主可以解散群"));
            return;
        }
        // 先广播解散事件（群即将删除，成员端移除该群）
        WsHandler.sendToUsers(allMemberIds(groupId),
                Map.of("type", "group_dissolved", "group_id", groupId));
        // 审计第 5 条：先把该群引用的磁盘附件 URL 记下来。必须"先取 URL → 再删库 → 再删文件"，
        // 删库之后 file_url 就查不到了，磁盘上那些文件会永久变成无主孤儿。
        java.util.List<String> urls = FamilyChatServer.getDb().fileUrlsOfGroup(groupId);
        if (!FamilyChatServer.getDb().dissolveGroup(groupId)) {
            ctx.status(500).json(Map.of("error", "解散失败"));
            return;
        }
        // 解散成功后回收磁盘：只删"全库都没人引用"的文件（同 URL 可能被单聊消息共用）
        Map<String, Object> reclaim = FamilyChatServer.getDb().reclaimFiles(urls, false);
        System.out.println("[GROUP] 解散群 " + groupId + " 磁盘回收: " + reclaim);
        ctx.json(Map.of("success", true, "files_reclaimed", reclaim));
    }

    /** 拉人进群(仅群主可操作,且只能拉自己的好友)。 */
    private void addMember(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        // 只有群主可以拉人进群(与 removeMember 的权限模型对齐)
        var group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        boolean isOwner = ((Number) group.get("owner_id")).intValue() == userId;
        if (!isOwner) {
            ctx.status(403).json(Map.of("error", "只有群主可以拉人进群"));
            return;
        }

        Object body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int newUserId;
        try {
            newUserId = ((Number) ((Map<?, ?>) body).get("user_id")).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 user_id")); return;
        }
        // 只能拉自己的好友,防止把陌生人/未同意的人拉进群看到群历史
        if (!FamilyChatServer.getDb().areFriends(userId, newUserId)) {
            ctx.status(403).json(Map.of("error", "只能拉自己的好友进群"));
            return;
        }
        boolean ok = FamilyChatServer.getDb().addGroupMember(groupId, newUserId);
        if (!ok) { ctx.status(500).json(Map.of("error", "添加失败")); return; }
        addSystemMessage(groupId, userId, displayName(userId) + " 邀请 " + displayName(newUserId) + " 加入群聊");
        broadcastMembersChanged(groupId, "add", userId);
        ctx.json(Map.of("success", true));
    }

    /** 移除成员 / 退群。自己=退群；群主可踢任何人(除自己)；管理员可踢普通成员。 */
    private void removeMember(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int targetUid;
        try { targetUid = Integer.parseInt(ctx.pathParam("uid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的用户ID")); return; }

        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        var group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }

        boolean isSelf = targetUid == userId;
        String myRole = memberRole(groupId, userId, group);
        String targetRole = memberRole(groupId, targetUid, group);
        // 群主不能退群（QQ 规则：须先转让群主），防止群主成员记录被删后锁死/孤儿群
        if (isSelf && "owner".equals(myRole)) {
            ctx.status(403).json(Map.of("error", "群主请先转让群主再退群"));
            return;
        }
        boolean canKick = "owner".equals(myRole) ||
                ("admin".equals(myRole) && "member".equals(targetRole));
        if (!isSelf && !canKick) {
            ctx.status(403).json(Map.of("error", "无权移除该成员"));
            return;
        }
        boolean ok = FamilyChatServer.getDb().removeGroupMember(groupId, targetUid);
        if (!ok) { ctx.status(500).json(Map.of("error", "移除失败")); return; }
        // 审计第 5 条：成员离开后，他在这条群的已读回执/群设置/公告已读必须一起清掉，
        // 否则重新入群时旧回执会让新消息直接显示成"已读"，而且这些行指向一个非成员，是孤儿。
        Map<String, Integer> cleaned =
                FamilyChatServer.getDb().cleanupAfterGroupMemberRemoved(groupId, targetUid);
        System.out.println("[GROUP] 成员离开联动清理: uid=" + targetUid + " gid=" + groupId
                + " " + cleaned);
        if (isSelf) {
            addSystemMessage(groupId, userId, displayName(userId) + " 退出了群聊");
        } else {
            addSystemMessage(groupId, userId, displayName(userId) + " 将 " + displayName(targetUid) + " 移出群聊");
        }
        broadcastMembersChanged(groupId, "remove", userId);
        // 单独通知被移除者：从我的群列表移除该群
        WsHandler.sendToUser(targetUid, Map.of("type", "group_removed", "group_id", groupId));
        ctx.json(Map.of("success", true));
    }

    /** 发群消息。content 由客户端按"每人一份密文"加密为 JSON 后原样保存。 */
    private void sendMessage(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        var group = FamilyChatServer.getDb().getGroupById(groupId);
        if (group == null) { ctx.status(404).json(Map.of("error", "群不存在")); return; }
        // 禁言检查（群主/管理员不禁言）
        if (isMuted(groupId, userId, group)) {
            ctx.status(403).json(Map.of("error", "你已被禁言"));
            return;
        }

        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        String content = body.get("content") instanceof String s ? s : "";
        String msgType = body.getOrDefault("msg_type", "text").toString();
        String filename = body.get("filename") instanceof String fs ? fs : "";
        String thumbnail = body.get("thumbnail") instanceof String ts ? ts : "";
        int durationSec = 0;
        try { durationSec = ((Number) body.getOrDefault("duration_sec", 0)).intValue(); }
        catch (Exception ignored) {}
        // 文件库索引参数（阶段4）：file_size 字节数、file_folder 自定义文件夹
        long fileSize = 0;
        try { fileSize = ((Number) body.getOrDefault("file_size", 0)).longValue(); }
        catch (Exception ignored) {}
        String fileFolder = body.get("file_folder") instanceof String ff ? ff : "";
        // @提及：客户端传 "all"（@全员）或 List<Integer>（@指定成员）
        String mentions = "";
        Object mObj = body.get("mentions");
        if (mObj instanceof String ms) {
            mentions = "all".equals(ms.trim()) ? "all" : ms.trim();
        } else if (mObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : list) {
                if (o instanceof Number n) {
                    if (!first) sb.append(",");
                    sb.append(n.intValue());
                    first = false;
                }
            }
            sb.append("]");
            mentions = sb.toString();
        }
        if (content.isEmpty() && "text".equals(msgType)) {
            ctx.status(400).json(Map.of("error", "消息不能为空")); return;
        }

        Map<String, Object> saved = FamilyChatServer.getDb()
                .saveGroupMessage(groupId, userId, content, msgType, filename, thumbnail, durationSec, mentions);
        if (saved == null) { ctx.status(500).json(Map.of("error", "保存失败")); return; }

        // ── 阶段4：文件/图片/视频/语音消息自动索引进群文件库（明文 URL，仅群成员可看）──
        if ("image".equals(msgType) || "video".equals(msgType) || "file".equals(msgType) || "voice".equals(msgType)) {
            try {
                String defFolder = "image".equals(msgType) ? "聊天图片"
                        : "video".equals(msgType) ? "聊天视频"
                        : "voice".equals(msgType) ? "聊天语音" : "群文件";
                String folder = fileFolder.isEmpty() ? defFolder : fileFolder;
                int fileId = FamilyChatServer.getDb().indexGroupFile(
                        groupId, userId, filename.isEmpty() ? content : filename,
                        content, thumbnail, fileSize, folder, msgType, ((Number) saved.get("id")).intValue());
                if (fileId > 0) {
                    WsHandler.sendToUsers(allMemberIds(groupId), Map.of(
                            "type", "group_file", "action", "create",
                            "group_id", groupId, "file_id", fileId,
                            "filename", filename.isEmpty() ? content : filename));
                }
            } catch (Exception e) {
                System.err.println("[WARN] indexGroupFile failed: " + e.getMessage());
            }
        }

        // 广播给所有在线成员（含发送者自己，客户端自行去重/忽略自己的回显）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "group_new_message");
        payload.put("id", saved.get("id"));
        payload.put("group_id", groupId);
        payload.put("sender_id", userId);
        payload.put("content", content);
        payload.put("msg_type", msgType);
        payload.put("filename", filename);
        payload.put("thumbnail", thumbnail);
        payload.put("duration_sec", durationSec);
        payload.put("mentions", mentions);
        payload.put("created_at", saved.getOrDefault("created_at", "").toString());
        List<Integer> memberIds = allMemberIds(groupId);
        WsHandler.sendToUsers(memberIds, payload);

        // @提及提醒：推给被@成员（排除发送者自己）
        if (!mentions.isEmpty()) {
            Set<Integer> mentioned = parseMentions(mentions, groupId);
            mentioned.remove(userId);
            if (!mentioned.isEmpty()) {
                WsHandler.sendToUsers(mentioned, Map.of(
                        "type", "group_mention",
                        "message_id", saved.get("id"),
                        "group_id", groupId,
                        "sender_id", userId,
                        "sender_name", displayName(userId)));
            }
        }

        ctx.json(Map.of("success", true, "message", groupMsgDict(saved)));
    }

    /** 拉群历史（limit 默认 50，上限 200，与单聊一致）。 */
    private void getMessages(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        List<Map<String, Object>> msgs = FamilyChatServer.getDb().getGroupMessages(groupId, limit);
        ctx.json(Map.of("messages", msgs.stream().map(this::groupMsgDict).toList()));
    }

    /** 标记已读到某条。 */
    private void markRead(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        int upToMsgId;
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            upToMsgId = ((Number) body.get("last_msg_id")).intValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 last_msg_id")); return;
        }
        int count = FamilyChatServer.getDb().markGroupRead(groupId, userId, upToMsgId);
        ctx.json(Map.of("success", true, "marked_read", count));
    }

    /** 撤回群消息（仅发送者）。 */
    private void recallMessage(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        int msgId;
        try { msgId = Integer.parseInt(ctx.pathParam("msgId")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的消息ID")); return; }
        boolean ok = FamilyChatServer.getDb().recallGroupMessage(msgId, userId, groupId);
        if (ok) {
            WsHandler.sendToUsers(allMemberIds(groupId),
                    Map.of("type", "group_message_recalled", "id", msgId, "group_id", groupId));
        }
        ctx.json(Map.of("success", ok));
    }

    private Map<String, Object> groupMsgDict(Map<String, Object> m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", m.get("id"));
        d.put("group_id", m.get("group_id"));
        d.put("sender_id", m.get("sender_id"));
        d.put("content", m.get("content") != null ? m.get("content") : "");
        d.put("msg_type", m.getOrDefault("msg_type", "text"));
        d.put("filename", m.get("filename") != null ? m.get("filename") : "");
        d.put("thumbnail", m.get("thumbnail") != null ? m.get("thumbnail") : "");
        d.put("duration_sec", m.get("duration_sec") != null ? m.get("duration_sec") : 0);
        d.put("mentions", m.get("mentions") != null ? m.get("mentions") : "");
        d.put("created_at", m.getOrDefault("created_at", "").toString());
        return d;
    }

    // ===== 群公告（阶段3） =====

    private Map<String, Object> announcementDict(Map<String, Object> a) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", a.get("id"));
        d.put("group_id", a.get("group_id"));
        d.put("publisher_id", a.get("publisher_id"));
        d.put("publisher_name", displayName(((Number) a.get("publisher_id")).intValue()));
        d.put("content", a.get("content") != null ? a.get("content") : "");
        d.put("pinned", a.get("pinned") != null ? a.get("pinned") : 0);
        d.put("created_at", a.getOrDefault("created_at", "").toString());
        d.put("updated_at", a.getOrDefault("updated_at", "").toString());
        d.put("read_count", a.getOrDefault("read_count", 0));
        return d;
    }

    /** 广播公告变更（action: create/update/delete）。content 为密文，只通知刷新。 */
    private void broadcastAnnouncement(int groupId, String action, Map<String, Object> ann) {
        WsHandler.sendToUsers(allMemberIds(groupId),
                Map.of("type", "group_announcement", "action", action, "group_id", groupId,
                        "announcement_id", ann.get("id")));
    }

    /** pinned 字段兼容布尔(true/false)与数字(1/0)——Android 客户端发送 JSON 布尔。 */
    private int parsePin(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue() == 1 ? 1 : 0;
        return 0;
    }

    /** 发群公告（群主/管理员）。content 为 {成员id:密文} JSON，服务端只存不解。 */
    private void createAnnouncement(Context ctx) {
        int groupId = requireGroupAdmin(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String content = body.get("content") instanceof String s ? s : "";
        if (content.isEmpty()) { ctx.status(400).json(Map.of("error", "公告内容不能为空")); return; }
        int pinned = parsePin(body.get("pinned"));
        var ann = FamilyChatServer.getDb().createAnnouncement(groupId, userId, content, pinned);
        if (ann == null) { ctx.status(500).json(Map.of("error", "发布失败")); return; }
        broadcastAnnouncement(groupId, "create", ann);
        addSystemMessage(groupId, userId, displayName(userId) + " 发布了一条群公告");
        ctx.json(Map.of("success", true, "announcement", announcementDict(ann)));
    }

    /** 公告列表（成员可见），带 member_count 供 X/Y 已读展示。 */
    private void listAnnouncements(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        var group = FamilyChatServer.getDb().getGroupById(groupId);
        int memberCount = group != null && group.get("member_count") instanceof Number n ? n.intValue() : 0;
        List<Map<String, Object>> list = FamilyChatServer.getDb().getGroupAnnouncements(groupId);
        ctx.json(Map.of("announcements", list.stream().map(this::announcementDict).toList(),
                "member_count", memberCount));
    }

    /** 公告详情（含已读成员名单）。 */
    private void getAnnouncement(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的公告ID")); return; }
        var ann = FamilyChatServer.getDb().getGroupAnnouncement(aid);
        if (ann == null) { ctx.status(404).json(Map.of("error", "公告不存在")); return; }
        if (((Number) ann.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        var group = FamilyChatServer.getDb().getGroupById(groupId);
        int memberCount = group != null && group.get("member_count") instanceof Number n ? n.intValue() : 0;
        List<Object> readUsers = new ArrayList<>();
        List<Integer> readBy = new ArrayList<>();
        for (Object uidObj : (List<?>) ann.get("read_by")) {
            int uid = ((Number) uidObj).intValue();
            readBy.add(uid);
            readUsers.add(Map.of("id", uid, "name", displayName(uid)));
        }
        Map<String, Object> d = announcementDict(ann);
        d.put("read_by", readBy);
        d.put("read_users", readUsers);
        d.put("member_count", memberCount);
        ctx.json(Map.of("announcement", d));
    }

    /** 改公告/置顶（群主/管理员）。body {content?, pinned?}。 */
    private void updateAnnouncement(Context ctx) {
        int groupId = requireGroupAdmin(ctx);
        if (groupId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的公告ID")); return; }
        var exist = FamilyChatServer.getDb().getGroupAnnouncement(aid);
        if (exist == null) { ctx.status(404).json(Map.of("error", "公告不存在")); return; }
        if (((Number) exist.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String content = body.get("content") instanceof String s && !s.isEmpty() ? s : null;
        Integer pinned = body.get("pinned") == null ? null : parsePin(body.get("pinned"));
        if (content == null && pinned == null) {
            ctx.status(400).json(Map.of("error", "没有需要修改的内容")); return;
        }
        var updated = FamilyChatServer.getDb().updateAnnouncement(aid, content, pinned);
        if (updated == null) { ctx.status(500).json(Map.of("error", "修改失败")); return; }
        broadcastAnnouncement(groupId, "update", updated);
        ctx.json(Map.of("success", true, "announcement", announcementDict(updated)));
    }

    /** 删公告（群主/管理员）。 */
    private void deleteAnnouncement(Context ctx) {
        int groupId = requireGroupAdmin(ctx);
        if (groupId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的公告ID")); return; }
        var exist = FamilyChatServer.getDb().getGroupAnnouncement(aid);
        if (exist == null) { ctx.status(404).json(Map.of("error", "公告不存在")); return; }
        if (((Number) exist.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        FamilyChatServer.getDb().deleteAnnouncement(aid);
        broadcastAnnouncement(groupId, "delete", exist);
        ctx.json(Map.of("success", true));
    }

    /** 标记公告已读（成员）。 */
    private void markAnnouncementRead(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的公告ID")); return; }
        var ann = FamilyChatServer.getDb().getGroupAnnouncement(aid);
        if (ann == null) { ctx.status(404).json(Map.of("error", "公告不存在")); return; }
        if (((Number) ann.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        FamilyChatServer.getDb().markAnnouncementRead(aid, userId);
        ctx.json(Map.of("success", true));
    }

    // ===== 群内搜索（阶段3） =====

    /** 群内搜索：只搜文件名/系统消息（明文），E2EE 密文不可服务端搜索。 */
    private void searchMessages(Context ctx) {
        int groupId = requireGroupMember(ctx);
        if (groupId < 0) return;
        String keyword = ctx.queryParam("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "缺少 keyword")); return;
        }
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        var list = FamilyChatServer.getDb().searchGroupMessages(groupId, keyword.trim(), limit);
        ctx.json(Map.of("messages", list.stream().map(this::groupMsgDict).toList()));
    }

    // ===== 提及助手 =====

    /** 解析 mentions 为被@的成员 uid 集合。"all"=全体成员；"[1,2]"=指定 uid（过滤非成员）。 */
    private Set<Integer> parseMentions(String mentions, int groupId) {
        Set<Integer> result = new LinkedHashSet<>();
        if (mentions == null || mentions.isEmpty()) return result;
        if ("all".equals(mentions)) {
            result.addAll(allMemberIds(groupId));
            return result;
        }
        try {
            var arr = com.google.gson.JsonParser.parseString(mentions).getAsJsonArray();
            for (var el : arr) result.add(el.getAsInt());
        } catch (Exception ignored) {}
        // 只保留群成员
        result.removeIf(uid -> !FamilyChatServer.getDb().isGroupMember(groupId, uid));
        return result;
    }
}
