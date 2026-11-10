package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 会话列表预览解密的回归测试。
 *
 * <p>会话列表预览的两种坏法必须同时被挡住：
 *   1. 把密文当明文显示 → 列表出现 Base64 乱码（原 bug）；
 *   2. 把明文误判成密文 → 显示「[无法解密]」，正常消息反而看不到。
 * 第 2 种由 {@link PreviewDecryptor#looksLikeCiphertext} 的判定边界把关，是本测试的重点。
 *
 * <p>{@code decryptForPreview} 需要 Android 的 PreferencesManager/ApiClient，无法在纯 JVM 里跑；
 * 这里只覆盖可以离线验证的判定逻辑与密文形态。
 */
public class PreviewDecryptorTest {

    private static String alicePub, alicePriv, bobPub, bobPriv;

    @BeforeClass
    public static void setUpKeys() {
        String[] alice = MessageEncryptor.generateKeyPair();
        String[] bob = MessageEncryptor.generateKeyPair();
        assertNotNull("生成密钥对失败", alice);
        assertNotNull("生成密钥对失败", bob);
        alicePub = alice[0];
        alicePriv = alice[1];
        bobPub = bob[0];
        bobPriv = bob[1];
    }

    /** 真实密文必须被识别出来（否则会走明文透传，列表就会显示 Base64）。 */
    @Test
    public void realCiphertext_isDetected() {
        String cipher = MessageEncryptor.encryptMessage("晚上七点老地方见", alicePriv, bobPub);
        assertNotNull(cipher);
        assertTrue("真实密文应被判定为密文", PreviewDecryptor.looksLikeCiphertext(cipher));

        // 解密回来确认这就是列表要展示的内容
        String plain = MessageEncryptor.decryptMessage(cipher, bobPriv, alicePub);
        assertEquals("晚上七点老地方见", plain);
    }

    /** 短文本绝不能被当成密文，否则列表会把正常消息显示成「[无法解密]」。 */
    @Test
    public void shortPlaintext_isNotDetected() {
        assertFalse(PreviewDecryptor.looksLikeCiphertext("好的"));
        assertFalse(PreviewDecryptor.looksLikeCiphertext("晚上七点老地方见"));
        assertFalse(PreviewDecryptor.looksLikeCiphertext("1234567890"));
        // 判定阈值为 40：39 个 Base64 字符仍算明文（真实密文 Base64 后必然远超 40）
        assertFalse("39 字符不算密文", PreviewDecryptor.looksLikeCiphertext(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM"));
    }

    /** 阈值边界：39 个 Base64 字符算明文，40 个即达到密文长度下限（锁住阈值本身，防止被误改）。 */
    @Test
    public void threshold_isForty() {
        assertFalse("39 个 Base64 字符：不算密文", PreviewDecryptor.looksLikeCiphertext(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM"));
        assertTrue("40 个 Base64 字符：达到下限", PreviewDecryptor.looksLikeCiphertext(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN"));
        assertTrue("含 = 补齐符的 40 个字符同样算密文", PreviewDecryptor.looksLikeCiphertext(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM="));
    }

    /** 含非 Base64 字符的长文本（URL / JSON / 撤回标记）不能误判。 */
    @Test
    public void nonBase64Content_isNotDetected() {
        assertFalse("URL 不算密文", PreviewDecryptor.looksLikeCiphertext(
                "http://example.com/uploads/2026/01/abcdefghijklmnopqrstuvwxyz.jpg"));
        assertFalse("撤回标记不算密文", PreviewDecryptor.looksLikeCiphertext("[已撤回]"));
        assertFalse("中文长文本不算密文", PreviewDecryptor.looksLikeCiphertext(
                "这是一条很长的中文普通消息用来确认不会被误判成密文的测试文本内容"));
        assertFalse("转账 JSON 不算密文", PreviewDecryptor.looksLikeCiphertext(
                "{\"transfer_id\":12,\"amount\":100,\"memo\":\"note\",\"status\":\"pending\"}"));
    }

    /** 空/null 必须安全透传，不能抛异常。 */
    @Test
    public void nullAndEmpty_areSafe() {
        assertFalse(PreviewDecryptor.looksLikeCiphertext(null));
        assertFalse(PreviewDecryptor.looksLikeCiphertext(""));
    }
}
