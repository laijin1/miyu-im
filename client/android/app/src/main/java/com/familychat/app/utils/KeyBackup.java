package com.familychat.app.utils;

import android.util.Base64;
import android.util.Log;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 私钥「密码加密备份」：把端到端加密私钥用登录密码派生的钥匙加密成密文，
 * 上传到服务端。重装/清数据后登录时用密码解回旧私钥，找回历史聊天记录。
 *
 * 安全性：服务端只存密文，没有密码派生钥匙就解不开，端到端加密保证不破坏。
 *
 * 备份格式（带版本号，可向前迁移）：
 *   v1（旧，仅解密兼容）: 0x01 | 12字节IV | GCM密文+tag
 *       派生：PBKDF2-HMAC-SHA1, 50,000 次迭代，盐 = 固定前缀+userId（无随机）。
 *   v2（新，P2-③加固）: 0x02 | saltLen(1) | 随机盐 | 12字节IV | GCM密文+tag
 *       派生：PBKDF2-HMAC-SHA1, 210,000 次迭代，盐 = 固定前缀+userId + 随机盐。
 *       随机盐使同账号多次备份的密文互不相同，且显著提高离线爆破成本。
 * 密文整体 Base64 后上传。
 */
public class KeyBackup {

    private static final String TAG = "E2EE_BACKUP";
    // PBKDF2WithHmacSHA256 在 Android API 26 以下不可用（minSdk 24）。
    // 备份必须跨设备/系统版本可解密，故用全版本都支持的 PBKDF2WithHmacSHA1（安全余量足够）。
    private static final String PBKDF2 = "PBKDF2WithHmacSHA1";
    /** v1 旧格式迭代次数：仅用于解密历史备份。 */
    private static final int V1_ITERATIONS = 50000;
    /** v2 新格式迭代次数：P2-③ 由 5 万提升到 21 万（约 4 倍，兼顾旧手机耗时）。 */
    private static final int V2_ITERATIONS = 210000;
    private static final int AES_KEY_BITS = 256;
    private static final int IV_LENGTH = 12;
    private static final String SALT_PREFIX = "familychat/keybackup/v1/";
    /** v2 随机盐长度（字节）。 */
    private static final int SALT_LENGTH = 16;
    private static final int MAX_SALT_LENGTH = 64;

    private static final SecureRandom secureRandom = new SecureRandom();

    /** 由密码 + userId + 盐派生 256 位加密钥匙。 */
    private static SecretKey deriveKey(String password, int userId, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    /** 盐 = 固定前缀+userId（跨账号隔离） ++ 随机盐（防彩虹表/提升爆破成本）。 */
    private static byte[] buildSalt(int userId, byte[] randomSalt) throws Exception {
        byte[] fixed = (SALT_PREFIX + userId).getBytes("UTF-8");
        byte[] salt = new byte[fixed.length + randomSalt.length];
        System.arraycopy(fixed, 0, salt, 0, fixed.length);
        System.arraycopy(randomSalt, 0, salt, fixed.length, randomSalt.length);
        return salt;
    }

    /**
     * 用密码加密私钥（v2 格式：随机盐 + 高迭代）。
     *
     * @param privateKeyB64 PKCS8 私钥的 Base64 字符串
     * @param password      登录密码
     * @param userId        当前账号 id（用于派生盐）
     * @return 备份密文 Base64；失败返回 null
     */
    public static String encryptBackup(String privateKeyB64, String password, int userId) {
        try {
            byte[] randomSalt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(randomSalt);
            byte[] salt = buildSalt(userId, randomSalt);
            SecretKey key = deriveKey(password, userId, salt, V2_ITERATIONS);

            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(privateKeyB64.getBytes("UTF-8"));

            // 版本头 + 盐长 + 随机盐 + IV + 密文
            byte[] out = new byte[2 + randomSalt.length + IV_LENGTH + ciphertext.length];
            out[0] = 0x02;
            out[1] = (byte) randomSalt.length;
            System.arraycopy(randomSalt, 0, out, 2, randomSalt.length);
            System.arraycopy(iv, 0, out, 2 + randomSalt.length, IV_LENGTH);
            System.arraycopy(ciphertext, 0, out, 2 + randomSalt.length + IV_LENGTH, ciphertext.length);

            Log.d(TAG, "encryptBackup(v2) ok: " + out.length + " bytes");
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encryptBackup failed", e);
            return null;
        }
    }

    /**
     * 用密码解密备份（v1/v2 兼容）。密码不对（或密文损坏）时返回 null。
     *
     * @return 私钥 Base64 字符串；失败返回 null
     */
    public static String decryptBackup(String backupB64, String password, int userId) {
        try {
            byte[] data = Base64.decode(backupB64, Base64.NO_WRAP);
            if (data.length < 1 + IV_LENGTH + 1) return null;
            int version = data[0] & 0xFF;

            if (version == 0x01) {
                // 旧格式：固定盐 + 5 万次迭代（只读兼容，不再生成）
                if (data.length < 1 + IV_LENGTH + 1) return null;
                byte[] iv = new byte[IV_LENGTH];
                byte[] ciphertext = new byte[data.length - 1 - IV_LENGTH];
                System.arraycopy(data, 1, iv, 0, IV_LENGTH);
                System.arraycopy(data, 1 + IV_LENGTH, ciphertext, 0, ciphertext.length);

                byte[] salt = (SALT_PREFIX + userId).getBytes("UTF-8");
                SecretKey key = deriveKey(password, userId, salt, V1_ITERATIONS);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(ciphertext);
                String priv = new String(plain, "UTF-8");
                Log.d(TAG, "decryptBackup(v1) ok: private key restored (" + priv.length() + " chars)");
                return priv;
            }

            if (version == 0x02) {
                // 新格式：随机盐 + 21 万次迭代
                if (data.length < 3) return null;
                int saltLen = data[1] & 0xFF;
                if (saltLen < 8 || saltLen > MAX_SALT_LENGTH) {
                    Log.w(TAG, "decryptBackup(v2): bad salt length " + saltLen);
                    return null;
                }
                if (data.length < 2 + saltLen + IV_LENGTH + 1) return null;
                byte[] randomSalt = new byte[saltLen];
                byte[] iv = new byte[IV_LENGTH];
                byte[] ciphertext = new byte[data.length - 2 - saltLen - IV_LENGTH];
                System.arraycopy(data, 2, randomSalt, 0, saltLen);
                System.arraycopy(data, 2 + saltLen, iv, 0, IV_LENGTH);
                System.arraycopy(data, 2 + saltLen + IV_LENGTH, ciphertext, 0, ciphertext.length);

                byte[] salt = buildSalt(userId, randomSalt);
                SecretKey key = deriveKey(password, userId, salt, V2_ITERATIONS);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(ciphertext);
                String priv = new String(plain, "UTF-8");
                Log.d(TAG, "decryptBackup(v2) ok: private key restored (" + priv.length() + " chars)");
                return priv;
            }

            Log.w(TAG, "decryptBackup: unknown version " + version);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "decryptBackup failed (wrong password or bad data): " + e.getMessage());
            return null;
        }
    }
}
