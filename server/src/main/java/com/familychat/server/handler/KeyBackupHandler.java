package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.util.E2eeValidator;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;

/**
 * 密钥备份存储（用于重装/清数据后恢复端到端加密私钥）。
 *
 * 客户端把私钥用「登录密码派生钥匙」加密成密文后上传，服务端只存密文、无密码解不开，
 * 因此不破坏端到端加密保证。改密码时客户端会用新密码重新加密覆盖。
 */
public class KeyBackupHandler {

    public void register(Javalin app) {
        app.put("/api/user/keybackup", this::uploadKeyBackup);
        app.get("/api/user/keybackup", this::getKeyBackup);
    }

    private void uploadKeyBackup(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        Object backup = body.get("backup_b64");
        if (backup == null || backup.toString().isEmpty()) {
            ctx.status(400).json(Map.of("error", "缺少 backup_b64"));
            return;
        }
        // P2-④: 长度 + 版本头/结构校验，拒绝垃圾数据撑爆数据库
        if (!E2eeValidator.isValidKeyBackup(backup.toString())) {
            ctx.status(400).json(Map.of("error", "backup_b64 格式无效（需为带版本头的加密备份 Base64）"));
            return;
        }

        FamilyChatServer.getDb().saveKeyBackup(userId, backup.toString());
        System.out.println("[E2EE-BACKUP] User " + userId + " uploaded encrypted key backup ("
                + backup.toString().length() + " bytes)");
        ctx.json(Map.of("success", true));
    }

    private void getKeyBackup(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        String backup = FamilyChatServer.getDb().getKeyBackup(userId);
        if (backup == null) {
            ctx.json(Map.of("backup_b64", ""));
            return;
        }
        ctx.json(Map.of("backup_b64", backup));
    }
}
