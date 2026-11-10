package com.familychat.app.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 支付密码哈希工具：客户端只存/只传 SHA-256(PIN) 的 hex，绝不落明文 6 位数字。
 * 服务端还会再加随机盐二次哈希，故上传 hash 不影响安全性。
 */
public class Sha {

    /** SHA-256 hex（小写）。失败返回空串（上层应视为不可用并提示）。 */
    public static String sha256Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
