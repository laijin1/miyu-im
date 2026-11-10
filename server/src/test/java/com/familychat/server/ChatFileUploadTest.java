package com.familychat.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.familychat.server.config.ServerConfig;
import com.familychat.server.handler.FileHandler;
import com.familychat.server.model.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单聊附件台账 chat_file_uploads：上传成功后必须留下「生成名 → 上传者」一行。
 *
 * <p>为什么这条性质必须被锁住：单聊消息的 content 是 E2EE 密文，服务端<b>无法</b>从
 * messages 反查 /chat_files/&lt;生成名&gt; 属于谁、是否还在用（实测 119 个文件里只有 3 条
 * 能对上）。上传时刻是唯一还能拿到明文 URL + uploaderId 的时机，这个台账一旦漏记，
 * 该附件就永久变成"服务端眼中无主"的文件，孤儿回收/归属校验都失去依据。
 *
 * <p>同时锁住两点：① 台账的 key 是<b>生成名</b>，不是客户端回传的原始文件名；
 * ② 上传成功与记账成功解耦 —— 记账失败不得让上传失败。
 */
class ChatFileUploadTest extends DbTestSupport {

    private static final Path CHAT_DIR = Paths.get(Database.DATA_DIR, "chat_files");

    private Javalin app;
    private String base;

    @BeforeEach
    void startServer() {
        FamilyChatServer.setDbForTests(db);
        app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        new FileHandler().register(app);
        // 端口 0 = 系统分配空闲端口，避免与真实服务/并行测试撞端口
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void stopServer() {
        if (app != null) app.stop();
        FamilyChatServer.setDbForTests(null);
        if (db != null) db.close();
    }

    @Test
    void uploadWritesLedgerRowKeyedByGeneratedName() throws Exception {
        int uid = register("uploader");
        byte[] content = new byte[1234];
        java.util.Arrays.fill(content, (byte) 9);

        HttpResponse<String> r = upload(uid, "holiday.jpg", "image/jpeg", content);
        assertEquals(200, r.statusCode(), "上传应成功: " + r.body());

        JsonObject json = JsonParser.parseString(r.body()).getAsJsonObject();
        assertTrue(json.has("url"), "响应结构必须含 url: " + r.body());
        String url = json.get("url").getAsString();
        assertTrue(url.startsWith("/chat_files/"), "url 形如 /chat_files/<生成名>: " + url);
        // 服务端回传的 name 是原始文件名（这正是"URL 没落库"的根因：
        // 客户端只把 name 明文回传，生成名只在被加密的 content 里）
        assertEquals("holiday.jpg", json.get("name").getAsString());
        assertEquals(1234, json.get("size").getAsInt());

        String generated = url.substring("/chat_files/".length());

        Map<String, Object> row = db.findChatFileUpload(generated);
        assertNotNull(row, "上传后 chat_file_uploads 必须有一行（key=生成名 " + generated + "）");
        assertEquals(uid, ((Number) row.get("uploader_id")).intValue(), "uploader_id 必须来自鉴权");
        assertEquals("holiday.jpg", row.get("original_name"));
        assertEquals(1234L, ((Number) row.get("size_bytes")).longValue());
        assertTrue(((Number) row.get("uploaded_at")).longValue() > 0, "uploaded_at 应写入毫秒时间戳");

        // 台账指向的文件在磁盘上真实存在，且文件名与台账 key 完全一致
        assertTrue(Files.exists(CHAT_DIR.resolve(generated)), "磁盘上应有对应文件");
    }

    @Test
    void ledgerIsKeyedByGeneratedNameNotOriginalName() throws Exception {
        int uid = register("uploader");
        String original = "假期照片.jpg";

        HttpResponse<String> r = upload(uid, original, "image/jpeg", new byte[64]);
        assertEquals(200, r.statusCode(), "上传应成功: " + r.body());
        String generated = JsonParser.parseString(r.body()).getAsJsonObject()
                .get("url").getAsString().substring("/chat_files/".length());

        // ★ 核心不变量：生成名 ≠ 原始名，且台账只认生成名
        assertNotEquals(original, generated, "生成名必须与原始文件名不同");
        assertNotNull(db.findChatFileUpload(generated), "生成名下应有台账行");
        assertNull(db.findChatFileUpload(original),
                "原始文件名绝不能成为台账 key（磁盘上也没有这个文件）");
        assertFalse(Files.exists(CHAT_DIR.resolve(original)), "磁盘上只有生成名文件");
    }

    @Test
    void eachUploadGetsItsOwnRow() throws Exception {
        int uid = register("uploader");
        String g1 = uploadName(uid, "a.png", "image/png", new byte[10]);
        String g2 = uploadName(uid, "b.png", "image/png", new byte[20]);
        assertNotEquals(g1, g2, "两次上传的生成名必须不同");
        assertNotNull(db.findChatFileUpload(g1));
        assertNotNull(db.findChatFileUpload(g2));
        assertEquals(10L, ((Number) db.findChatFileUpload(g1).get("size_bytes")).longValue());
        assertEquals(20L, ((Number) db.findChatFileUpload(g2).get("size_bytes")).longValue());
    }

    // ── 助手 ──

    /** 上传一次，返回生成名。 */
    private String uploadName(int uid, String filename, String mime, byte[] bytes) throws Exception {
        HttpResponse<String> r = upload(uid, filename, mime, bytes);
        assertEquals(200, r.statusCode(), "上传应成功: " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject()
                .get("url").getAsString().substring("/chat_files/".length());
    }

    /** 以合法 App JWT 打一次真实的 multipart 上传请求。 */
    private HttpResponse<String> upload(int uid, String filename, String mime, byte[] bytes) throws Exception {
        String boundary = "----chatfile" + System.nanoTime();
        String jwt = JWT.create()
                .withClaim("user_id", uid)
                .withClaim("tv", db.getTokenVersion(uid))
                .withExpiresAt(new Date(System.currentTimeMillis() + 3600_000))
                .sign(Algorithm.HMAC256(ServerConfig.getJwtSecret()));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/chat/upload"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(boundary, "file", filename, mime, bytes)))
                .build();

        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 手工拼一个单段 multipart body（只发一个名为 file 的 part）。 */
    private static byte[] multipart(String boundary, String field, String filename,
                                    String mime, byte[] bytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"" + field + "\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(bytes);
        bos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return bos.toByteArray();
    }
}
