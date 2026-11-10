package com.familychat.server;

import com.familychat.server.model.Database;
import org.junit.jupiter.api.BeforeEach;

import java.security.MessageDigest;
import java.util.Map;

/**
 * 测试基类：每个测试前重置临时数据库（数据目录由 gradle test 注入的
 * familychat.data.dir 指定，见 server-java/build.gradle，绝不触碰真实 data/）。
 */
public abstract class DbTestSupport {

    protected Database db;

    @BeforeEach
    void resetDb() {
        String dir = Database.DATA_DIR;
        java.io.File d = new java.io.File(dir);
        if (d.exists() && d.isDirectory()) {
            java.io.File[] files = d.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.getName().startsWith("family_chat.db")) f.delete();
                }
            }
            // 孤儿/磁盘回收用例会往 chat_files 里写测试附件，必须一并清掉：
            // 否则上一轮用例留下的文件会被下一轮的"未引用文件"统计看见，
            // 断言随执行顺序飘移（tests must be order-independent）。
            // 注意：DATA_DIR 在测试里是 build/test-data（见 build.gradle），绝不碰真实 data/。
            java.io.File chatFiles = new java.io.File(d, "chat_files");
            if (chatFiles.isDirectory()) {
                java.io.File[] cf = chatFiles.listFiles();
                if (cf != null) {
                    for (java.io.File f : cf) f.delete();
                }
            }
        }
        db = new Database();
        db.init();
    }

    /** 注册用户，返回 userId。 */
    protected int register(String username) {
        Map<String, Object> u = db.registerUser(username, "pass1234", username);
        if (u == null) throw new IllegalStateException("注册失败: " + username);
        return ((Number) u.get("id")).intValue();
    }

    /** SHA-256 小写 hex（与客户端支付密码 pin_hash 一致）。 */
    protected static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
