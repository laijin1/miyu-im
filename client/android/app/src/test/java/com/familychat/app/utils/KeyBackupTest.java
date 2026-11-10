package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Base64;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 私钥「密码加密备份」回归测试。
 *
 * 这段代码决定了「换手机/重装后能不能找回历史聊天记录」：
 *   - 加密逻辑坏了 → 备份上传了但永远解不开 → 历史消息永久丢失
 *   - 解密兼容坏了 → 老用户升级后解不开旧备份
 *   - 密码错时返回非 null → 会用垃圾数据覆盖本地密钥，比丢失更糟
 *
 * 全是纯 Java（PBKDF2 + AES-GCM），无需模拟器。
 *
 * 注意：v2 派生 21 万次迭代，单次约 100~300ms，本测试有意控制调用次数。
 */
public class KeyBackupTest {

    private static final String PRIVATE_KEY_SAMPLE =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5";
    private static final String PASSWORD = "myLoginPassword123";
    private static final int USER_ID = 42;

    // ===== 正常往返 =====

    @Test
    public void encryptThenDecrypt_restoresPrivateKey() {
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull("加密应成功", backup);

        String restored = KeyBackup.decryptBackup(backup, PASSWORD, USER_ID);
        assertEquals("密码正确时必须还原出完全相同的私钥", PRIVATE_KEY_SAMPLE, restored);
    }

    @Test
    public void sameInput_producesDifferentBackups() {
        // v2 引入随机盐：同账号多次备份的密文必须不同
        // （否则攻击者能看出"密码没改、密钥没变"，且彩虹表更易命中）
        String b1 = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        String b2 = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotEquals("随机盐必须让两次备份密文不同", b1, b2);
        // 但都能解开
        assertEquals(PRIVATE_KEY_SAMPLE, KeyBackup.decryptBackup(b1, PASSWORD, USER_ID));
        assertEquals(PRIVATE_KEY_SAMPLE, KeyBackup.decryptBackup(b2, PASSWORD, USER_ID));
    }

    @Test
    public void backupFormat_v2HeaderIsCorrect() {
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull(backup);
        byte[] data = Base64.decode(backup, Base64.NO_WRAP);

        assertEquals("版本头必须是 0x02（v2 随机盐格式）", 0x02, data[0] & 0xFF);
        int saltLen = data[1] & 0xFF;
        assertEquals("v2 随机盐长度应为 16 字节", 16, saltLen);
        // 2(头) + 16(盐) + 12(IV) + 密文 + 16(GCM tag)
        assertTrue("长度异常: " + data.length, data.length > 2 + saltLen + 12 + 16);
    }

    // ===== 密码错误 / 数据损坏必须失败 =====

    @Test
    public void wrongPassword_returnsNull() {
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull(backup);
        assertNull("密码错误必须返回 null，绝不能返回垃圾数据（否则会覆盖本地密钥）",
                KeyBackup.decryptBackup(backup, "wrongPassword", USER_ID));
    }

    @Test
    public void wrongUserId_returnsNull() {
        // 盐包含 userId，跨账号必须解不开
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull(backup);
        assertNull("用别的 userId 解密必须失败（盐含账号隔离）",
                KeyBackup.decryptBackup(backup, PASSWORD, USER_ID + 1));
    }

    @Test
    public void tamperedBackup_isRejected() {
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);
        assertNotNull(backup);

        byte[] data = Base64.decode(backup, Base64.NO_WRAP);
        data[data.length - 1] ^= 0x01; // 翻转密文最后一位（GCM tag 区域）
        String tampered = Base64.encodeToString(data, Base64.NO_WRAP);

        assertNull("密文被篡改必须被 GCM 检出并返回 null",
                KeyBackup.decryptBackup(tampered, PASSWORD, USER_ID));
    }

    @Test
    public void malformedInputs_returnNullWithoutThrowing() {
        assertNull(KeyBackup.decryptBackup(null, PASSWORD, USER_ID));
        assertNull(KeyBackup.decryptBackup("", PASSWORD, USER_ID));
        assertNull(KeyBackup.decryptBackup("!!!not base64!!!", PASSWORD, USER_ID));
        assertNull(KeyBackup.decryptBackup("QUJD", PASSWORD, USER_ID));       // 太短
        assertNull(KeyBackup.decryptBackup(validBase64(new byte[]{0x03, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4}),
                PASSWORD, USER_ID));                                          // 未知版本号
    }

    @Test
    public void badSaltLength_isRejected() {
        // 构造 v2 头但盐长非法（0 或过大），必须安全拒绝而不是数组越界
        for (int badLen : new int[]{0, 1, 7, 65, 255}) {
            byte[] payload = new byte[2 + 12 + 32];
            payload[0] = 0x02;
            payload[1] = (byte) badLen;
            String b64 = validBase64(payload);
            assertNull("非法盐长 " + badLen + " 应返回 null",
                    KeyBackup.decryptBackup(b64, PASSWORD, USER_ID));
        }
    }

    // ===== v1 旧格式兼容（老用户升级后必须还能解开历史备份）=====

    /**
     * 手工按 v1 格式构造一个备份（固定盐 + 5 万次迭代），验证新代码仍能解开。
     * 这条测试保护的是"向前兼容"：v1 备份是真实存在于服务端的老数据。
     */
    @Test
    public void v1LegacyBackup_stillDecryptable() throws Exception {
        String v1Backup = buildV1Backup(PRIVATE_KEY_SAMPLE, PASSWORD, USER_ID);

        String restored = KeyBackup.decryptBackup(v1Backup, PASSWORD, USER_ID);

        assertEquals("v1 旧格式备份必须仍可解密（否则老用户历史消息全丢）",
                PRIVATE_KEY_SAMPLE, restored);
        assertNull("v1 备份用错密码也应失败",
                KeyBackup.decryptBackup(v1Backup, "wrong", USER_ID));
    }

    /** 按 KeyBackup 内部约定的 v1 格式生成密文，作为兼容性测试的"老数据"。 */
    private static String buildV1Backup(String privateKey, String password, int userId) throws Exception {
        final int V1_ITERATIONS = 50000;
        final String SALT_PREFIX = "familychat/keybackup/v1/";
        final int IV_LENGTH = 12;

        byte[] salt = (SALT_PREFIX + userId).getBytes(StandardCharsets.UTF_8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        SecretKey key = new SecretKeySpec(
                factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, V1_ITERATIONS, 256))
                        .getEncoded(), "AES");

        byte[] iv = new byte[IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(privateKey.getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[1 + IV_LENGTH + ct.length];
        out[0] = 0x01;
        System.arraycopy(iv, 0, out, 1, IV_LENGTH);
        System.arraycopy(ct, 0, out, 1 + IV_LENGTH, ct.length);
        return validBase64(out);
    }

    // ===== 边界内容 =====

    @Test
    public void boundaryPrivateKeyValues_roundTrip() {
        String[] cases = {
                "A",
                "含中文的私钥串（理论上不该出现，但必须能安全往返）",
                repeat("A", 5000),
        };
        for (String value : cases) {
            String backup = KeyBackup.encryptBackup(value, PASSWORD, USER_ID);
            assertNotNull(backup);
            assertEquals("往返不一致（长度 " + value.length() + "）",
                    value, KeyBackup.decryptBackup(backup, PASSWORD, USER_ID));
        }
    }

    @Test
    public void unicodePassword_roundTrips() {
        String cnPassword = "我的密码🔐中文123";
        String backup = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, cnPassword, USER_ID);
        assertNotNull(backup);
        assertEquals(PRIVATE_KEY_SAMPLE, KeyBackup.decryptBackup(backup, cnPassword, USER_ID));
        assertNull("相似但不同的中文密码不能解开",
                KeyBackup.decryptBackup(backup, "我的密码🔐中文124", USER_ID));
    }

    @Test
    public void emptyPrivateKey_roundTrips() {
        String backup = KeyBackup.encryptBackup("", PASSWORD, USER_ID);
        assertNotNull(backup);
        assertEquals("", KeyBackup.decryptBackup(backup, PASSWORD, USER_ID));
    }

    @Test
    public void differentAccountsProduceIncompatibleBackups() {
        String forUser1 = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, 1);
        String forUser2 = KeyBackup.encryptBackup(PRIVATE_KEY_SAMPLE, PASSWORD, 2);
        assertNotNull(forUser1);
        assertNotNull(forUser2);
        assertFalse(forUser1.equals(forUser2));
        assertEquals(PRIVATE_KEY_SAMPLE, KeyBackup.decryptBackup(forUser1, PASSWORD, 1));
        assertEquals(PRIVATE_KEY_SAMPLE, KeyBackup.decryptBackup(forUser2, PASSWORD, 2));
        assertNull(KeyBackup.decryptBackup(forUser1, PASSWORD, 2));
    }

    private static String validBase64(byte[] raw) {
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }
}
