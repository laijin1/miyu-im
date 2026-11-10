package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * 安全码载荷（{@code miyu-safety:v1}）回归测试（纯 JVM，无需模拟器）。
 *
 * <p>为什么这段必须测：安全码是"带外核对公钥"的唯一入口，也是整个 TOFU 体系里
 * <b>唯一不依赖服务端</b>的一环。它一旦判错，后果不对称：
 * <ul>
 *   <li>把不匹配的码判成匹配 → 用户看到"已核对"，而中间人照旧存在（安全能力归零）；</li>
 *   <li>把合法的码判成非法 → 用户永远核对不成功，最后放弃这个功能（同样归零）。</li>
 * </ul>
 * 所以这里既钉死"往返后指纹必须与独立工具算出的黄金向量一致"，
 * 也钉死每一类坏输入都被明确拒绝。
 *
 * <p>黄金向量与 {@link KeyTransferPayloadTest} 共用同一条由独立工具（.NET ECDsa P-256）
 * 生成的真实 P-256 公钥，确保"安全码用的指纹"与"设置→密钥管理页显示的指纹"是同一套算法。
 */
public class SafetyNumberPayloadTest {

    /** 由独立工具（.NET ECDsa P-256）导出的真实 X509 SPKI 公钥（91 字节）。 */
    private static final String SPKI_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6LIBZZP5lYP8mqyCOqg2MaopFmQTgumFTXrINnNGWWXGH1jBDyNgrAx1RWO8oQAL+HSsvV94KiY1VOj5HyQ==";

    /** 独立工具算出的 SHA-256(SPKI) 全码。 */
    private static final String EXPECTED_FULL =
            "13489a1bdfedf680658c0491b56879fe59cfedd59e842625e3b5d15f16afad8f";

    // ===== 往返 =====

    @Test
    public void roundTrip_fingerprintMatchesGoldenVectorAndPinStore() {
        String payload = SafetyNumberPayload.encode(SPKI_B64);
        assertNotNull("合法 P-256 公钥必须能编码", payload);

        SafetyNumberPayload.ParseResult r = SafetyNumberPayload.parse(payload);
        assertTrue("自己编出来的码必须能解回来", r.isSuccess());
        assertEquals("解析出的指纹必须等于独立工具(.NET SHA256)的黄金向量",
                EXPECTED_FULL, r.fullFingerprint);
        assertEquals("安全码的指纹必须与 PeerKeyPinStore 的判定用同一套算法",
                PeerKeyPinStore.fingerprintOf(SPKI_B64), r.fullFingerprint);
    }

    @Test
    public void roundTrip_withRuntimeGeneratedKey() throws Exception {
        String keyB64 = newP256KeyBase64();
        SafetyNumberPayload.ParseResult r =
                SafetyNumberPayload.parse(SafetyNumberPayload.encode(keyB64));
        assertTrue(r.isSuccess());
        assertEquals(PeerKeyPinStore.fingerprintOf(keyB64), r.fullFingerprint);
    }

    @Test
    public void payloadFormat_isThreePartsWithExpectedMagicAndVersion() {
        String payload = SafetyNumberPayload.encode(SPKI_B64);
        String[] parts = payload.split(":", -1);
        assertEquals(3, parts.length);
        assertEquals("miyu-safety", parts[0]);
        assertEquals("1", parts[1]);
        assertEquals("公钥段必须是 Base64url（无填充）",
                KeyTransferPayload.encodeBase64Url(Base64.getMimeDecoder().decode(SPKI_B64)),
                parts[2]);
    }

    // ===== 前缀判定 =====

    @Test
    public void looksLikeSafetyPayload_acceptsOwnPayloadOnly() {
        assertTrue(SafetyNumberPayload.looksLikeSafetyPayload(SafetyNumberPayload.encode(SPKI_B64)));
        assertFalse("空/null 必须拒绝", SafetyNumberPayload.looksLikeSafetyPayload(null));
        assertFalse(SafetyNumberPayload.looksLikeSafetyPayload(""));
        assertFalse("只有 magic、没有分隔符时必须拒绝",
                SafetyNumberPayload.looksLikeSafetyPayload("miyu-safety"));
        assertFalse("magic 后面不是冒号时必须拒绝",
                SafetyNumberPayload.looksLikeSafetyPayload("miyu-safetyX:1:AAAA"));
    }

    @Test
    public void keyBackupPayloadAndSafetyPayload_neverCrossMatch() {
        // 两种码必须互不误认：否则用户拿"私钥迁移码"来核对身份，或反过来。
        String safety = SafetyNumberPayload.encode(SPKI_B64);
        assertFalse("安全码不得被当成密钥迁移码",
                KeyTransferPayload.looksLikeKeyPayload(safety));
        assertFalse("密钥迁移码不得被当成安全码",
                SafetyNumberPayload.looksLikeSafetyPayload("miyu-keybackup:2:c2FsdA:aXY:Y3Q"));
        assertEquals(SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("miyu-keybackup:2:c2FsdA:aXY:Y3Q").error);
    }

    // ===== 坏输入：结构性 =====

    @Test
    public void parse_rejectsNullEmptyAndWrongShape() {
        assertEquals(SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse(null).error);
        assertEquals(SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("").error);
        assertEquals(SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("   ").error);
        assertEquals("段数不足", SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("miyu-safety:1").error);
        assertEquals("段数过多", SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("miyu-safety:1:AAAA:BBBB").error);
        assertEquals("别的应用/别的用途的码", SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("other-app:1:AAAA").error);
    }

    @Test
    public void parse_distinguishesVersionTooNewFromMalformed() {
        String pub = KeyTransferPayload.encodeBase64Url(Base64.getMimeDecoder().decode(SPKI_B64));

        assertEquals("码比 App 新 → 应提示升级 App，而不是'坏码'",
                SafetyNumberPayload.Error.UNSUPPORTED_VERSION,
                SafetyNumberPayload.parse("miyu-safety:2:" + pub).error);
        assertEquals(2, SafetyNumberPayload.parse("miyu-safety:2:" + pub).version);

        assertEquals("版本字段不是数字 → 这压根不像本应用的码",
                SafetyNumberPayload.Error.NOT_SAFETY_PAYLOAD,
                SafetyNumberPayload.parse("miyu-safety:x:" + pub).error);
        assertEquals(SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:0:" + pub).error);
    }

    @Test
    public void parse_rejectsMalformedKeyMaterial() {
        assertEquals("非法 Base64url", SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:1:!!!not-base64url!!!").error);
        assertEquals("截断（空公钥段）", SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:1:").error);
        assertEquals("合法 Base64 但不是密钥", SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:1:QUJD").error);
    }

    // ===== 坏输入：密钥类型 =====

    @Test
    public void parse_rejectsNonEcKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        String rsaB64 = Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());

        assertNull("RSA 公钥不得被当成安全码公钥", SafetyNumberPayload.encode(rsaB64));
        assertEquals(SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:1:" +
                        KeyTransferPayload.encodeBase64Url(
                                kpg.generateKeyPair().getPublic().getEncoded())).error);
    }

    @Test
    public void parse_rejectsNonP256Curve() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        byte[] p384 = kpg.generateKeyPair().getPublic().getEncoded();

        assertNull("P-384 公钥不得被当成安全码公钥（曲线必须与消息加密一致）",
                SafetyNumberPayload.encode(Base64.getEncoder().encodeToString(p384)));
        assertEquals(SafetyNumberPayload.Error.MALFORMED_KEY,
                SafetyNumberPayload.parse("miyu-safety:1:" +
                        KeyTransferPayload.encodeBase64Url(p384)).error);
    }

    // ===== 编码侧退化 =====

    @Test
    public void encode_returnsNullForUnusableInput() {
        assertNull(SafetyNumberPayload.encode(null));
        assertNull(SafetyNumberPayload.encode(""));
        assertNull(SafetyNumberPayload.encode("!!!not-base64!!!"));
        assertNull(SafetyNumberPayload.encode("QUJD"));
    }

    private static String newP256KeyBase64() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());
    }
}
