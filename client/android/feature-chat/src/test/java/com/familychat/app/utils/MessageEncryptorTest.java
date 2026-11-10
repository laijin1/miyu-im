package com.familychat.app.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * E2EE 加解密回归测试。
 *
 * 这是全项目最不能出错的一段代码：一旦加解密不对称，历史聊天记录就永久解不开。
 * 而这些方法全是纯 Java（javax.crypto），不需要模拟器即可测试——所以没有理由不测。
 *
 * 覆盖点：
 *   1. 自己加密自己解密（自己给自己发消息，重进群后要能看历史）
 *   2. A 加密 → B 解密（ECDH 对称性）
 *   3. 用错误的私钥解密必须失败（而不是返回乱码）
 *   4. 密文被篡改必须被 GCM 检出
 *   5. 同样的明文两次加密结果必须不同（IV 随机，否则有明文泄露风险）
 *   6. 中文/emoji/长文本/空串等边界
 *   7. Base64 与密钥格式的往返
 */
public class MessageEncryptorTest {

    /** Alice（发送方） */
    private static String alicePub, alicePriv;
    /** Bob（接收方） */
    private static String bobPub, bobPriv;

    @BeforeClass
    public static void setUpKeys() {
        String[] alice = MessageEncryptor.generateKeyPair();
        String[] bob = MessageEncryptor.generateKeyPair();
        assertNotNull("生成 Alice 密钥对失败", alice);
        assertNotNull("生成 Bob 密钥对失败", bob);
        assertEquals("密钥对应含公钥+私钥两项", 2, alice.length);

        alicePub = alice[0];
        alicePriv = alice[1];
        bobPub = bob[0];
        bobPriv = bob[1];
    }

    @Test
    public void keyPairGeneration_producesDistinctKeys() {
        assertNotEquals("两个用户的公钥不应相同", alicePub, bobPub);
        assertNotEquals("私钥与公钥不应相同", alicePriv, alicePub);
        // X509 编码的 P-256 公钥 Base64 长度约 124 字符；PKCS8 私钥约 138
        assertTrue("公钥长度异常: " + alicePub.length(), alicePub.length() > 80);
        assertTrue("私钥长度异常: " + alicePriv.length(), alicePriv.length() > 80);
    }

    @Test
    public void twoKeyPairs_differEachTime() {
        String[] a = MessageEncryptor.generateKeyPair();
        String[] b = MessageEncryptor.generateKeyPair();
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals("每次生成的密钥必须不同（否则 SecureRandom 有问题）", a[0], b[0]);
    }

    // ===== 1. 自己给自己加解密 =====

    @Test
    public void selfRoundTrip() {
        String plain = "自己发给自己的消息";
        String cipher = MessageEncryptor.encryptMessage(plain, alicePriv, alicePub);
        assertNotNull("加密失败", cipher);
        String back = MessageEncryptor.decryptMessage(cipher, alicePriv, alicePub);
        assertEquals("自己加解密应还原原文", plain, back);
    }

    // ===== 2. A→B 正常往返（ECDH 对称性）=====

    @Test
    public void crossUserRoundTrip() {
        String plain = "Alice 发给 Bob 的消息";
        // Alice 用自己私钥 + Bob 公钥加密
        String cipher = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
        assertNotNull(cipher);
        // Bob 用自己私钥 + Alice 公钥解密
        String back = MessageEncryptor.decryptMessage(cipher, bobPriv, alicePub);
        assertEquals("跨用户加解密应还原原文", plain, back);
    }

    @Test
    public void crossUserRoundTrip_reverseDirection() {
        String plain = "Bob 回复 Alice";
        String cipher = MessageEncryptor.encryptMessage(plain, bobPriv, alicePub);
        assertNotNull(cipher);
        assertEquals(plain, MessageEncryptor.decryptMessage(cipher, alicePriv, bobPub));
    }

    // ===== 3. 错误密钥必须失败 =====

    @Test
    public void decryptWithWrongPrivateKey_returnsNull() {
        String cipher = MessageEncryptor.encryptMessage("机密", alicePriv, bobPub);
        assertNotNull(cipher);
        // 用第三方（自己）的私钥去解，必须失败而不是返回乱码
        assertNull("错误私钥解密应当返回 null（GCM 认证失败）",
                MessageEncryptor.decryptMessage(cipher, alicePriv, alicePub));
    }

    @Test
    public void decryptWithWrongPeerPublicKey_returnsNull() {
        String cipher = MessageEncryptor.encryptMessage("机密", alicePriv, bobPub);
        assertNotNull(cipher);
        assertNull("错误对端公钥解密应当返回 null",
                MessageEncryptor.decryptMessage(cipher, bobPriv, bobPub));
    }

    // ===== 4. 密文篡改必须被检出（GCM 完整性）=====

    @Test
    public void tamperedCiphertext_isRejected() {
        String cipher = MessageEncryptor.encryptMessage("不要被篡改", alicePriv, bobPub);
        assertNotNull(cipher);

        // 翻转 Base64 中间的一个字符，模拟传输中被改动
        char[] chars = cipher.toCharArray();
        int pos = chars.length / 2;
        chars[pos] = (chars[pos] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertNull("被篡改的密文必须解密失败（GCM tag 校验）",
                MessageEncryptor.decryptMessage(tampered, bobPriv, alicePub));
    }

    // ===== 5. IV 必须随机 =====

    @Test
    public void sameInput_producesDifferentCiphertext() {
        String plain = "同一条消息发两次";
        String c1 = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
        String c2 = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
        assertNotNull(c1);
        assertNotNull(c2);
        assertNotEquals("相同明文两次加密必须不同（IV 随机），否则会泄露重复内容", c1, c2);

        // 但都要能正确解密
        assertEquals(plain, MessageEncryptor.decryptMessage(c1, bobPriv, alicePub));
        assertEquals(plain, MessageEncryptor.decryptMessage(c2, bobPriv, alicePub));
    }

    // ===== 6. 边界输入 =====

    @Test
    public void boundaryContent_roundTrips() {
        String[] cases = {
                "",                                   // 空串
                "a",                                  // 单字符
                "中文测试：你好，世界！",                 // 中文
                "emoji 😀🎉🔐 混合 test",              // emoji（4 字节 UTF-8）
                "换行\n制表\t回车\r",                    // 控制字符
                "引号\"反斜杠\\尖括号<>",                 // JSON 特殊字符
                repeat("很长很长的消息", 2000),          // 长文本（约 14KB）
                repeat("x", 100000),                  // 100KB 大文本
        };
        for (String plain : cases) {
            String cipher = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
            assertNotNull("加密失败，输入长度=" + plain.length(), cipher);
            String back = MessageEncryptor.decryptMessage(cipher, bobPriv, alicePub);
            assertEquals("往返不一致，输入长度=" + plain.length(), plain, back);
        }
    }

    @Test
    public void nullPlainText_returnsNull() {
        assertNull("null 明文应返回 null 而不是抛异常",
                MessageEncryptor.encryptMessage(null, alicePriv, bobPub));
    }

    // ===== 7. 非法输入不应抛异常，只应返回 null =====

    @Test
    public void malformedInputs_returnNullWithoutThrowing() {
        assertNull(MessageEncryptor.encryptMessage("hi", "not-base64!!!", bobPub));
        assertNull(MessageEncryptor.encryptMessage("hi", alicePriv, "not-base64!!!"));
        assertNull(MessageEncryptor.encryptMessage("hi", "", bobPub));
        assertNull(MessageEncryptor.decryptMessage("not-base64!!!", bobPriv, alicePub));
        assertNull(MessageEncryptor.decryptMessage("", bobPriv, alicePub));
        assertNull(MessageEncryptor.decryptMessage("QUJD", bobPriv, alicePub)); // 合法Base64但过短
        assertNull(MessageEncryptor.decryptMessage("短", bobPriv, alicePub));
    }

    // ===== 8. 群聊场景：同一明文给每个成员各加密一份 =====

    @Test
    public void groupScenario_eachMemberCanDecryptOwnCopy() {
        // 模拟 GroupEncryptHelper：为每个成员单独加密（不用共享群密钥）
        String[] carol = MessageEncryptor.generateKeyPair();
        assertNotNull(carol);
        String carolPub = carol[0], carolPriv = carol[1];

        String plain = "群消息：今晚聚餐";

        String forBob = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
        String forCarol = MessageEncryptor.encryptMessage(plain, alicePriv, carolPub);
        String forSelf = MessageEncryptor.encryptMessage(plain, alicePriv, alicePub);

        assertNotNull(forBob);
        assertNotNull(forCarol);
        assertNotNull(forSelf);

        // 每人用自己的私钥 + 发送者公钥解开自己那一份
        assertEquals(plain, MessageEncryptor.decryptMessage(forBob, bobPriv, alicePub));
        assertEquals(plain, MessageEncryptor.decryptMessage(forCarol, carolPriv, alicePub));
        assertEquals(plain, MessageEncryptor.decryptMessage(forSelf, alicePriv, alicePub));

        // 三份密文互不相同（没有共享密钥）
        assertFalse(forBob.equals(forCarol));
        assertFalse(forBob.equals(forSelf));
        assertFalse(forCarol.equals(forSelf));
    }

    // ===== 9. 密钥 Base64 往返稳定性 =====

    @Test
    public void keysAreStableAcrossManyOperations() {
        String plain = "稳定性测试";
        for (int i = 0; i < 20; i++) {
            String cipher = MessageEncryptor.encryptMessage(plain, alicePriv, bobPub);
            assertNotNull("第 " + i + " 次加密失败", cipher);
            assertEquals("第 " + i + " 次解密不一致", plain,
                    MessageEncryptor.decryptMessage(cipher, bobPriv, alicePub));
        }
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }
}
