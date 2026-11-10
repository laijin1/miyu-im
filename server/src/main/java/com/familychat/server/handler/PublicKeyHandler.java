package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.util.E2eeValidator;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;

public class PublicKeyHandler {

    public void register(Javalin app) {
        app.post("/api/user/publickey", this::uploadPublicKey);
        app.get("/api/user/publickey", this::getPublicKey);
    }

    private void uploadPublicKey(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效的请求格式"));
            return;
        }

        Object pk = body.get("public_key");
        if (pk == null || pk.toString().isEmpty()) {
            ctx.status(400).json(Map.of("error", "缺少 public_key"));
            return;
        }
        // P2-④: 长度 + X509 EC 公钥格式校验，拒绝垃圾数据撑爆数据库
        if (!E2eeValidator.isValidPublicKey(pk.toString())) {
            ctx.status(400).json(Map.of("error", "public_key 格式无效（需为 X509 EC 公钥的 Base64）"));
            return;
        }

        FamilyChatServer.getDb().savePublicKey(userId, pk.toString());
        System.out.println("[E2EE] User " + userId + " uploaded public key (" + pk.toString().length() + " bytes)");
        ctx.json(Map.of("success", true));
    }

    private void getPublicKey(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        String targetIdStr = ctx.queryParam("userId");
        if (targetIdStr == null) {
            ctx.status(400).json(Map.of("error", "缺少 userId 参数"));
            return;
        }

        int targetId;
        try {
            targetId = Integer.parseInt(targetIdStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "无效的 userId"));
            return;
        }

        String publicKey = FamilyChatServer.getDb().getPublicKey(targetId);
        if (publicKey == null) {
            ctx.status(404).json(Map.of("error", "未找到该用户的公钥"));
            return;
        }

        ctx.json(Map.of("user_id", targetId, "public_key", publicKey));
    }
}
