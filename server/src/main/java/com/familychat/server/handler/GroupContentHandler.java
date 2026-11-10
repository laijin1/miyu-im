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
 * 群文件库 + 群相册（全仿 QQ，阶段4）。
 * 文件/照片为明文字节（沿 /chat_files 上传），仅群成员可访问与查看；
 * 聊天里发的文件/图片/视频/语音自动索引进文件库（GroupHandler.sendMessage 内做）。
 * 删除权限：上传者本人或群主/管理员。
 */
public class GroupContentHandler {

    public void register(Javalin app) {
        // 群文件
        app.get("/api/groups/{id}/files", this::listFiles);
        app.get("/api/groups/{id}/files/folders", this::fileFolders);
        app.post("/api/groups/{id}/files", this::addFile);
        app.delete("/api/groups/{id}/files/{fid}", this::deleteFile);
        // 群相册
        app.get("/api/groups/{id}/albums", this::listAlbums);
        app.post("/api/groups/{id}/albums", this::createAlbum);
        app.get("/api/groups/{id}/albums/{aid}/photos", this::albumPhotos);
        app.post("/api/groups/{id}/albums/{aid}/photos", this::addPhotos);
        app.delete("/api/groups/{id}/albums/{aid}", this::deleteAlbum);
        app.delete("/api/groups/{id}/albums/{aid}/photos/{pid}", this::deletePhoto);
    }

    // ═══════════════ 群文件 ═══════════════

    /** 文件列表：?folder=分类（"全部"=不限）&page=1 */
    private void listFiles(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        String folder = ctx.queryParam("folder");
        int page = 1;
        String pageStr = ctx.queryParam("page");
        try { page = Math.max(1, Integer.parseInt(pageStr != null ? pageStr : "1")); }
        catch (Exception ignored) {}
        List<Map<String, Object>> files = FamilyChatServer.getDb().getGroupFiles(groupId, folder, page, 50);
        ctx.json(Map.of("files", files));
    }

    /** 文件夹分类计数（chips）。 */
    private void fileFolders(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        ctx.json(Map.of("folders", FamilyChatServer.getDb().getGroupFileFolders(groupId)));
    }

    /** 手动索引进文件库（文件库直接上传场景）。 */
    private void addFile(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String filename = body.get("filename") instanceof String s ? s : "";
        String fileUrl = body.get("file_url") instanceof String s2 ? s2 : "";
        if (filename.isEmpty() || fileUrl.isEmpty()) {
            ctx.status(400).json(Map.of("error", "缺少文件名或文件地址")); return;
        }
        long size = 0;
        try { size = ((Number) body.getOrDefault("file_size", 0)).longValue(); }
        catch (Exception ignored) {}
        String thumbnail = body.get("thumbnail") instanceof String ts ? ts : "";
        String folder = body.get("folder") instanceof String fs2 ? fs2 : "群文件";
        int fileId = FamilyChatServer.getDb().indexGroupFile(
                groupId, userId, filename, fileUrl, thumbnail, size,
                folder.isEmpty() ? "群文件" : folder, "file", 0);
        if (fileId == 0) { ctx.status(500).json(Map.of("error", "入库失败")); return; }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_file", "action", "create",
                "group_id", groupId, "file_id", fileId, "filename", filename));
        ctx.json(Map.of("success", true, "file_id", fileId,
                "file", FamilyChatServer.getDb().getGroupFile(fileId)));
    }

    /** 删文件：上传者或群主/管理员。 */
    private void deleteFile(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int fid;
        try { fid = Integer.parseInt(ctx.pathParam("fid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的文件ID")); return; }
        Map<String, Object> f = FamilyChatServer.getDb().getGroupFile(fid);
        if (f == null) { ctx.status(404).json(Map.of("error", "文件不存在")); return; }
        if (((Number) f.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        boolean canDelete = ((Number) f.get("uploader_id")).intValue() == userId
                || GroupHandler.isAdmin(groupId, userId, group);
        if (!canDelete) {
            ctx.status(403).json(Map.of("error", "只有上传者或群主/管理员可删除")); return;
        }
        // 审计第 5 条：先取 URL 再删记录，最后回收磁盘 —— 顺序反了就拿不到文件名了
        java.util.List<String> urls = FamilyChatServer.getDb().fileUrlsOfGroupFile(fid);
        FamilyChatServer.getDb().deleteGroupFile(fid);
        Map<String, Object> reclaimed = FamilyChatServer.getDb().reclaimFiles(urls, false);
        System.out.println("[GROUP] 删除群文件 " + fid + " 磁盘回收: " + reclaimed);
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_file", "action", "delete",
                "group_id", groupId, "file_id", fid));
        ctx.json(Map.of("success", true));
    }

    // ═══════════════ 群相册 ═══════════════

    private void listAlbums(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        ctx.json(Map.of("albums", FamilyChatServer.getDb().getGroupAlbums(groupId)));
    }

    /** 建相册（任意成员）。 */
    private void createAlbum(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String name = body.get("name") instanceof String s ? s : "";
        if (name.isEmpty()) name = "默认相册";
        int albumId = FamilyChatServer.getDb().createGroupAlbum(groupId, name, "", userId);
        if (albumId == 0) { ctx.status(500).json(Map.of("error", "创建失败")); return; }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_album", "action", "create",
                "group_id", groupId, "album_id", albumId, "name", name));
        ctx.json(Map.of("success", true, "album_id", albumId,
                "album", FamilyChatServer.getDb().getGroupAlbum(albumId)));
    }

    /** 相册照片列表。 */
    private void albumPhotos(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的相册ID")); return; }
        Map<String, Object> album = FamilyChatServer.getDb().getGroupAlbum(aid);
        if (album == null || ((Number) album.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        ctx.json(Map.of("photos", FamilyChatServer.getDb().getGroupAlbumPhotos(aid)));
    }

    /** 批量传照片：{photo_urls: ["/chat_files/..", ...]}（任意成员，相册内每人可传）。 */
    private void addPhotos(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的相册ID")); return; }
        Map<String, Object> album = FamilyChatServer.getDb().getGroupAlbum(aid);
        if (album == null || ((Number) album.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        List<String> urls = new ArrayList<>();
        if (body.get("photo_urls") instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s && !s.isEmpty()) urls.add(s);
        }
        if (urls.isEmpty()) { ctx.status(400).json(Map.of("error", "没有照片")); return; }
        List<Integer> ids = new ArrayList<>();
        for (String url : urls) {
            int pid = FamilyChatServer.getDb().addAlbumPhoto(aid, groupId, userId, url, "");
            if (pid > 0) ids.add(pid);
        }
        if (ids.isEmpty()) { ctx.status(500).json(Map.of("error", "上传失败")); return; }
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_album", "action", "add_photo",
                "group_id", groupId, "album_id", aid, "count", ids.size()));
        ctx.json(Map.of("success", true, "photo_ids", ids));
    }

    /** 删相册：创建者或群主/管理员。 */
    private void deleteAlbum(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int aid;
        try { aid = Integer.parseInt(ctx.pathParam("aid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的相册ID")); return; }
        Map<String, Object> album = FamilyChatServer.getDb().getGroupAlbum(aid);
        if (album == null || ((Number) album.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        boolean canDelete = ((Number) album.get("created_by")).intValue() == userId
                || GroupHandler.isAdmin(groupId, userId, group);
        if (!canDelete) {
            ctx.status(403).json(Map.of("error", "只有创建者或群主/管理员可删除")); return;
        }
        java.util.List<String> urls = FamilyChatServer.getDb().fileUrlsOfAlbum(aid);
        FamilyChatServer.getDb().deleteGroupAlbum(aid);
        Map<String, Object> reclaimed = FamilyChatServer.getDb().reclaimFiles(urls, false);
        System.out.println("[GROUP] 删除相册 " + aid + " 磁盘回收: " + reclaimed);
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_album", "action", "delete",
                "group_id", groupId, "album_id", aid));
        ctx.json(Map.of("success", true));
    }

    /** 删照片：上传者或群主/管理员。 */
    private void deletePhoto(Context ctx) {
        int groupId = GroupHandler.requireGroupMember(ctx);
        if (groupId < 0) return;
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int pid;
        try { pid = Integer.parseInt(ctx.pathParam("pid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的照片ID")); return; }
        Map<String, Object> photo = FamilyChatServer.getDb().getGroupAlbumPhoto(pid);
        if (photo == null) { ctx.status(404).json(Map.of("error", "照片不存在")); return; }
        int aid = ((Number) photo.get("album_id")).intValue();
        Map<String, Object> album = FamilyChatServer.getDb().getGroupAlbum(aid);
        if (album == null || ((Number) album.get("group_id")).intValue() != groupId) {
            ctx.status(403).json(Map.of("error", "越权访问")); return;
        }
        Map<String, Object> group = FamilyChatServer.getDb().getGroupById(groupId);
        boolean canDelete = ((Number) photo.get("uploader_id")).intValue() == userId
                || GroupHandler.isAdmin(groupId, userId, group);
        if (!canDelete) {
            ctx.status(403).json(Map.of("error", "只有上传者或群主/管理员可删除")); return;
        }
        java.util.List<String> urls = FamilyChatServer.getDb().fileUrlsOfAlbumPhoto(pid);
        FamilyChatServer.getDb().deleteAlbumPhoto(pid);
        Map<String, Object> reclaimed = FamilyChatServer.getDb().reclaimFiles(urls, false);
        System.out.println("[GROUP] 删除相册照片 " + pid + " 磁盘回收: " + reclaimed);
        WsHandler.sendToUsers(GroupHandler.allMemberIds(groupId), Map.of(
                "type", "group_album", "action", "delete_photo",
                "group_id", groupId, "album_id", aid, "photo_id", pid));
        ctx.json(Map.of("success", true));
    }
}
