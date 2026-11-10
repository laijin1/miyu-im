package com.familychat.server.util;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * E2EE 上传数据（公钥 / 密码加密的密钥备份）的服务端校验。
 *
 * 此前上传接口只做"非空"检查：任意长度、任意内容的字符串都会被存进 SQLite，
 * 恶意客户端可借此撑爆数据库。这里加两层防护：
 *  1) 长度上限：拒绝巨型字符串；
 *  2) 结构校验：公钥必须是能解析的 X509 EC 公钥（256 位曲线，与客户端 secp256r1 一致），
 *     备份必须是合法 Base64 且带可识别的版本头与最小结构长度。
 */
public final class E2eeValidator {

    /** X509 EC 公钥 Base64 长度范围（P-256 未压缩点约 91 字符，留足余量）。 */
    public static final int PUBKEY_MIN_LEN = 44;
    public static final int PUBKEY_MAX_LEN = 1024;

    /** 密钥备份密文 Base64 长度范围。 */
    public static final int BACKUP_MIN_LEN = 60;
    public static final int BACKUP_MAX_LEN = 200_000;

    private static final int BACKUP_V1 = 0x01;
    private static final int BACKUP_V2 = 0x02;
    private static final int IV_LEN = 12;      // AES-GCM 推荐 IV 长度
    private static final int GCM_TAG_LEN = 16; // 128 位 GCM tag
    private static final int SALT_MIN_LEN = 8;
    private static final int SALT_MAX_LEN = 64;

    private E2eeValidator() {}

    /**
     * 是否为合法的 X509 EC 公钥（Base64，256 位曲线）。
     * 注意：X509EncodedKeySpec 解析会拒绝任意垃圾字节，等价于完整格式校验。
     */
    public static boolean isValidPublicKey(String b64) {
        if (b64 == null || b64.length() < PUBKEY_MIN_LEN || b64.length() > PUBKEY_MAX_LEN) {
            return false;
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(der));
            if (!(pub instanceof ECPublicKey)) return false;
            ECPublicKey ec = (ECPublicKey) pub;
            return ec.getParams().getCurve().getField().getFieldSize() == 256;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否为合法的"密码加密的密钥备份"（Base64，版本头 + IV + GCM 密文结构）。
     * v1: 0x01 | IV(12) | 密文+tag
     * v2: 0x02 | saltLen(1) | salt | IV(12) | 密文+tag
     */
    public static boolean isValidKeyBackup(String b64) {
        if (b64 == null || b64.length() < BACKUP_MIN_LEN || b64.length() > BACKUP_MAX_LEN) {
            return false;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (data.length < 1 + IV_LEN + GCM_TAG_LEN) return false;
        int version = data[0] & 0xFF;
        if (version == BACKUP_V1) {
            return data.length >= 1 + IV_LEN + GCM_TAG_LEN;
        }
        if (version == BACKUP_V2) {
            if (data.length < 3) return false;
            int saltLen = data[1] & 0xFF;
            if (saltLen < SALT_MIN_LEN || saltLen > SALT_MAX_LEN) return false;
            return data.length >= 2 + saltLen + IV_LEN + GCM_TAG_LEN;
        }
        return false;
    }
}
