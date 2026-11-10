package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProfileHandler {
    private static final String AVATAR_DIR = com.familychat.server.model.Database.DATA_DIR + "/avatars";
    private static final String UPLOAD_AVATAR_DIR = com.familychat.server.model.Database.DATA_DIR + "/uploads/avatars";

    // Max avatar file size (5MB)
    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;

    // Whitelist of allowed image extensions
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    // Extension to MIME type mapping
    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    public ProfileHandler() {
        new java.io.File(AVATAR_DIR).mkdirs();
        new java.io.File(UPLOAD_AVATAR_DIR).mkdirs();
    }

    public void register(Javalin app) {
        app.get("/api/user/profile", this::getProfile);
        app.put("/api/user/profile", this::updateProfile);
        app.post("/api/user/avatar", this::uploadAvatar);
        app.get("/avatars/{filename}", this::serveAvatar);
        app.get("/uploads/avatars/{filename}", this::serveUploadedAvatar);
    }

    private void getProfile(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> user = FamilyChatServer.getDb().getUserById(userId);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        ctx.json(Map.of("user", AuthHandler.userDict(user)));
    }

    private void updateProfile(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        // Check that values are not null even when key exists
        if (body.containsKey("nickname")) {
            Object nickname = body.get("nickname");
            if (nickname != null) {
                FamilyChatServer.getDb().updateUser(userId, "nickname", nickname.toString());
            }
        }
        if (body.containsKey("avatar")) {
            Object avatar = body.get("avatar");
            if (avatar != null) {
                FamilyChatServer.getDb().updateUser(userId, "avatar", avatar.toString());
            }
        }
        if (body.containsKey("avatar_color")) {
            Object avatarColor = body.get("avatar_color");
            if (avatarColor != null) {
                FamilyChatServer.getDb().updateUser(userId, "avatar_color", avatarColor.toString());
            }
        }

        Map<String, Object> user = FamilyChatServer.getDb().getUserById(userId);
        ctx.json(Map.of("user", AuthHandler.userDict(user)));
    }

    private void uploadAvatar(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        try {
            var file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).json(Map.of("error", "未选择文件"));
                return;
            }

            // Validate file extension
            String originalFilename = file.filename();
            if (originalFilename == null || !originalFilename.contains(".")) {
                ctx.status(400).json(Map.of("error", "文件格式不支持"));
                return;
            }
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                ctx.status(400).json(Map.of("error", "仅支持 JPG/PNG/WebP 格式的图片"));
                return;
            }

            // Capture old avatar BEFORE updateUser overwrites it
            String oldAvatar = null;
            Map<String, Object> pre = FamilyChatServer.getDb().getUserById(userId);
            if (pre != null && pre.get("avatar") instanceof String) {
                oldAvatar = (String) pre.get("avatar");
            }

            // 流式读取并限制大小：超限立即中止（不再整文件 readAllBytes 先进内存，防 100MB 大文件打爆堆内存）
            byte[] data = readBounded(file.content(), (int) MAX_AVATAR_SIZE);
            if (data == null) {
                ctx.status(413).json(Map.of("error", "图片大小不能超过5MB"));
                return;
            }
            if (!isValidImage(data, ext)) {
                ctx.status(400).json(Map.of("error", "文件内容不是有效的图片格式"));
                return;
            }

            // Use UUID to prevent filename collision
            String name = "user_" + userId + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            Path target = Paths.get(UPLOAD_AVATAR_DIR, name).normalize();

            // Ensure the resolved path is within UPLOAD_AVATAR_DIR (defense in depth)
            if (!target.startsWith(Paths.get(UPLOAD_AVATAR_DIR).normalize())) {
                ctx.status(400).json(Map.of("error", "无效的文件名"));
                return;
            }

            Files.write(target, data);
            String url = "/uploads/avatars/" + name;
            FamilyChatServer.getDb().updateUser(userId, "avatar", url);

            // Delete old avatar file AFTER a successful save (no crash if already gone)
            if (oldAvatar != null) deleteAvatarFile(oldAvatar);

            Map<String, Object> user = FamilyChatServer.getDb().getUserById(userId);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("code", 200);
            body.put("data", Map.of("avatar_url", url));
            // user kept for backward compat with the web client and older Android builds
            body.put("user", AuthHandler.userDict(user));
            ctx.json(body);
        } catch (Exception e) {
            System.err.println("[ERROR] uploadAvatar: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "上传失败"));
        }
    }

    /**
     * Delete an old avatar file referenced by a DB avatar value.
     * No-op for emoji or any non-file value. Safe against path traversal.
     */
    private void deleteAvatarFile(String avatar) {
        try {
            Path base;
            String prefix;
            if (avatar.startsWith("/uploads/avatars/")) {
                base = Paths.get(UPLOAD_AVATAR_DIR).normalize();
                prefix = "/uploads/avatars/";
            } else if (avatar.startsWith("/avatars/")) {
                base = Paths.get(AVATAR_DIR).normalize();
                prefix = "/avatars/";
            } else {
                return; // emoji or non-file avatar
            }
            String name = avatar.substring(prefix.length());
            if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) return;
            Path file = base.resolve(name).normalize();
            if (file.startsWith(base) && Files.exists(file)) {
                Files.deleteIfExists(file);
                System.out.println("[OK] Deleted old avatar file: " + file);
            }
        } catch (Exception e) {
            System.err.println("[WARN] deleteAvatarFile: " + e.getMessage());
        }
    }

    private void serveAvatar(Context ctx) {
        serveAvatarFrom(ctx, Paths.get(AVATAR_DIR).normalize());
    }

    private void serveUploadedAvatar(Context ctx) {
        serveAvatarFrom(ctx, Paths.get(UPLOAD_AVATAR_DIR).normalize());
    }

    private void serveAvatarFrom(Context ctx, Path baseDir) {
        // 头像需登录才能加载(header 或 ?token=, 兼容 Glide)
        if (AuthHandler.requireAuthQuery(ctx) < 0) return;

        String filename = ctx.pathParam("filename");

        // --- PATH TRAVERSAL PREVENTION ---
        // Reject any path containing ".." or directory separators
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }

        Path file = baseDir.resolve(filename).normalize();
        // Verify the normalized path is within baseDir
        if (!file.startsWith(baseDir)) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }

        if (Files.exists(file) && Files.isRegularFile(file)) {
            try {
                // Detect content type from file extension
                String ext = getExtension(filename);
                String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");

                // Stream the file instead of loading all bytes into memory
                InputStream in = Files.newInputStream(file);
                ctx.result(in).contentType(contentType);
            } catch (Exception e) {
                System.err.println("[ERROR] serveAvatarFrom: " + e.getMessage());
                ctx.status(404);
            }
        } else {
            ctx.status(404);
        }
    }

    /**
     * Validate image magic bytes to ensure the file is actually an image.
     */
    private static boolean isValidImage(byte[] data, String ext) {
        if (data == null || data.length < 4) return false;
        switch (ext) {
            case "jpg":
            case "jpeg":
                // JPEG starts with FF D8 FF
                return data.length >= 3 &&
                        (data[0] & 0xFF) == 0xFF &&
                        (data[1] & 0xFF) == 0xD8 &&
                        (data[2] & 0xFF) == 0xFF;
            case "png":
                // PNG starts with 89 50 4E 47
                return (data[0] & 0xFF) == 0x89 &&
                        data[1] == 0x50 &&
                        data[2] == 0x4E &&
                        data[3] == 0x47;
            case "gif":
                // GIF starts with 47 49 46
                return data[0] == 0x47 &&
                        data[1] == 0x49 &&
                        data[2] == 0x46;
            case "webp":
                // WebP starts with RIFF (52 49 46 46) + size + WEBP (57 45 42 50)
                return data.length >= 12 &&
                        data[0] == 0x52 && data[1] == 0x49 &&
                        data[2] == 0x46 && data[3] == 0x46 &&
                        data[8] == 0x57 && data[9] == 0x45 &&
                        data[10] == 0x42 && data[11] == 0x50;
            default:
                return false;
        }
    }

    /**
     * Extract file extension from filename, lowercased.
     */
    private static String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /** 流式读取最多 maxBytes；一旦超出立即返回 null（调用方按"文件过大"处理）。 */
    private static byte[] readBounded(java.io.InputStream in, int maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) return null;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
