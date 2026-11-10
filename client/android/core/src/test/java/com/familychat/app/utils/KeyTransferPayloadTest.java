package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.Base64;

/**
 * 密钥迁移载荷的指纹部分回归测试（纯 JVM，无需模拟器）。
 *
 * 这段代码决定了「两台设备上的私钥是不是同一把」这个人工核对结论：
 *   - 算法改坏 → 两台设备指纹对不上，用户会误判"密钥不是原来那把"，
 *     进而做出多余的覆盖操作，反而烧掉历史消息；
 *   - 取错字节/字节序反了 → 指纹看似正常但实际无意义，问题极难发现。
 *
 * 因此这里既有「黄金向量」钉死当前算法，也有「与独立算法交叉比对」防实现取错字节。
 *
 * ── 黄金向量的来源（独立工具生成，非本实现自产）──
 * 用 PowerShell + .NET 的 ECDsa / SHA256 独立实现生成一条真实 P-256 公钥：
 *   $ec = [System.Security.Cryptography.ECDsa]::Create(
 *             [System.Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP256'))
 *   $spki = $ec.ExportSubjectPublicKeyInfo()
 *   [Convert]::ToBase64String($spki)
 *   [System.Security.Cryptography.SHA256]::Create().ComputeHash($spki)
 *
 *   SPKI Base64 = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6LIBZZP5lYP8mqyCOqg2MaopFmQTgumFTXrINnNGWWXGH1jBDyNgrAx1RWO8oQAL+HSsvV94KiY1VOj5HyQ==
 *   解码后 91 字节（与 MessageEncryptor 产出的 P-256 SPKI 长度一致）
 *   SHA-256     = 13489a1bdfedf680658c0491b56879fe59cfedd59e842625e3b5d15f16afad8f
 *
 * 黄金向量的用途是「防止将来有人改坏算法」，不是证明算法定义正确 ——
 * 后者由真机上与独立工具交叉核对来保证。
 */
public class KeyTransferPayloadTest {

    /** 由独立工具（.NET ECDsa P-256）导出的真实 X509 SPKI 公钥。 */
    private static final String SPKI_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6LIBZZP5lYP8mqyCOqg2MaopFmQTgumFTXrINnNGWWXGH1jBDyNgrAx1RWO8oQAL+HSsvV94KiY1VOj5HyQ==";

    /** 独立工具算出的 SHA-256(SPKI) 全码。 */
    private static final String EXPECTED_FULL =
            "13489a1bdfedf680658c0491b56879fe59cfedd59e842625e3b5d15f16afad8f";
    /** 全码前 4 字节 = 短码。 */
    private static final String EXPECTED_SHORT = "13489a1b";

    private static byte[] spki() {
        return Base64.getMimeDecoder().decode(SPKI_B64);
    }

    // ===== 黄金向量 =====

    @Test
    public void goldenVector_shortMatchesIndependentTool() {
        assertEquals("短码必须与独立工具(.NET SHA256)结果一致",
                EXPECTED_SHORT, KeyTransferPayload.fingerprintShort(spki()));
    }

    @Test
    public void goldenVector_fullMatchesIndependentTool() {
        assertEquals("全码必须与独立工具(.NET SHA256)结果一致",
                EXPECTED_FULL, KeyTransferPayload.fingerprintFullHex(spki()));
    }

    @Test
    public void goldenVector_spkiLengthIsExpected() {
        // 91 字节：P-256 未压缩点的标准 SPKI 长度。
        // 长度不对说明测试向量本身写错了（比如 Base64 被截断），
        // 那前两个断言就变成了"用坏向量验证坏实现"。
        assertEquals(91, spki().length);
    }

    // ===== 长度与字符集 =====

    @Test
    public void shortCode_isEightLowerHexChars() {
        byte[] input = "some-fingerprint-input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String shortCode = KeyTransferPayload.fingerprintShort(input);
        assertEquals("短码长度必须恰为 8", 8, shortCode.length());
        assertTrue("短码必须全部落在 [0-9a-f]，实际=" + shortCode,
                shortCode.matches("[0-9a-f]{8}"));
    }

    @Test
    public void fullCode_isSixtyFourLowerHexChars() {
        byte[] input = "some-fingerprint-input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String full = KeyTransferPayload.fingerprintFullHex(input);
        assertEquals("全码长度必须恰为 64", 64, full.length());
        assertTrue("全码必须全部落在 [0-9a-f]，实际=" + full,
                full.matches("[0-9a-f]{64}"));
    }

    // ===== 稳定性与内部一致性 =====

    @Test
    public void sameInput_producesSameFingerprint() {
        byte[] input = spki();
        assertEquals(KeyTransferPayload.fingerprintShort(input),
                KeyTransferPayload.fingerprintShort(input));
        assertEquals(KeyTransferPayload.fingerprintFullHex(input),
                KeyTransferPayload.fingerprintFullHex(input));
    }

    @Test
    public void shortCode_equalsTruncatedFullCode() {
        byte[] input = spki();
        assertEquals("全码前 8 位必须等于短码（两者共用同一取字节逻辑）",
                KeyTransferPayload.fingerprintFullHex(input).substring(0, 8),
                KeyTransferPayload.fingerprintShort(input));
    }

    @Test
    public void fingerprint_matchesIndependentlyComputedSha256() throws Exception {
        // 测试内用 MessageDigest 独立算一遍再截断，防"取错 4 字节/字节序反了"。
        byte[] input = spki();
        byte[] d = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));

        assertEquals(sb.substring(0, 8), KeyTransferPayload.fingerprintShort(input));
        assertEquals(sb.toString(), KeyTransferPayload.fingerprintFullHex(input));
    }

    @Test
    public void hexEncoding_handlesHighBytesWithoutSignExtension() throws Exception {
        // 目的是钉死"取哪 4 字节 + hex 怎么编"，而不是预测 SHA-256 的输出
        // （SHA-256 输出与输入没有肉眼可见的关系，无法凭直觉写死)。
        // 所以这里拿"测试内独立算一遍的 SHA-256"做基准，专门覆盖容易出错的输入：
        //   0x00        → 若实现用了有符号扩展，会编出非 hex 字符或补错位
        //   0xFF        → 若实现漏了 & 0xFF，会编成 "ffffffff" 而不是 "ff......"
        //   全 0x80 序列 → 高位字节密集
        assertMatchesIndependentSha256(new byte[]{0x00});
        assertMatchesIndependentSha256(new byte[]{(byte) 0xFF});
        assertMatchesIndependentSha256(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80});
        assertMatchesIndependentSha256(new byte[64]);
        assertMatchesIndependentSha256(new byte[]{0x0F, (byte) 0xF0, 0x01, (byte) 0xFE, 0x7F});

        // 单字节 0x00 的 SHA-256 是公开已知值("6e340b9c...")，独立于本实现，
        // 用它做一次外部锚点，顺带证明编码路径没有引入额外偏移。
        assertEquals("6e340b9c", KeyTransferPayload.fingerprintShort(new byte[]{0x00}));
    }

    /** 用测试内独立计算的 SHA-256 校验短码与全码。 */
    private static void assertMatchesIndependentSha256(byte[] input) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        assertEquals("短码应与独立计算的 SHA-256 前 8 位一致, inputLen=" + input.length,
                sb.substring(0, 8), KeyTransferPayload.fingerprintShort(input));
        assertEquals("全码应与独立计算的 SHA-256 一致, inputLen=" + input.length,
                sb.toString(), KeyTransferPayload.fingerprintFullHex(input));
    }

    @Test
    public void differentInputs_produceDifferentFingerprints() {
        byte[] a = "input-a".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = "input-b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNotEquals("不同输入必须得到不同指纹（防常量返回）",
                KeyTransferPayload.fingerprintShort(a), KeyTransferPayload.fingerprintShort(b));
    }

    // ===== 空态安全（不抛异常）=====

    @Test
    public void nullInput_returnsEmptyString() {
        assertEquals("", KeyTransferPayload.fingerprintShort(null));
        assertEquals("", KeyTransferPayload.fingerprintFullHex(null));
    }

    @Test
    public void emptyArrayInput_returnsEmptyString() {
        assertEquals("", KeyTransferPayload.fingerprintShort(new byte[0]));
        assertEquals("", KeyTransferPayload.fingerprintFullHex(new byte[0]));
    }

    // ===== group4 分组 =====

    @Test
    public void group4_handlesNormalAndEdgeCases() {
        assertEquals("", KeyTransferPayload.group4(null));
        assertEquals("", KeyTransferPayload.group4(""));
        assertEquals("a1b2 c3d4", KeyTransferPayload.group4("a1b2c3d4"));
        // 64 位 → 16 组（15 个空格）
        String full = EXPECTED_FULL;
        String grouped = KeyTransferPayload.group4(full);
        assertEquals("分组后字符数 = 64 + 15 个空格", 64 + 15, grouped.length());
        assertEquals("分组不得改变字符顺序（去掉空格后必须还原）",
                full, grouped.replace(" ", ""));
        // 非 4 倍数长度：不丢字符、不留尾部空格
        String odd = KeyTransferPayload.group4("abcdefghij");
        assertEquals("abcd efgh ij", odd);
        assertEquals("尾部不得残留空格", odd.trim(), odd);
    }

    // =====================================================================
    // 第 2 步：二维码载荷 encode / decode（v2 = 口令 + KDF + AEAD）
    // =====================================================================
    //
    // 这组测试守的是"把私钥从 A 机搬到 B 机"的唯一入口：
    // 放过一个坏载荷 = 历史消息永久解不开，所以负例比正例更重要。
    //
    // v1（明文私钥）已作废：它连"完整性"都只有无密钥校验和（fp8），
    // 攻击者可自造一致载荷。下面既正测 v2，也负测"v1 必须被拒"。

    /** 现场生成一对真实的 EC P-256 密钥（与 MessageEncryptor 用的是同一套 JDK API）。 */
    private static java.security.KeyPair newKeyPair() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
        kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /** 测试用迁移口令（≥ 8 位）。 */
    private static final String PW = "MiYu-Test-Pw-2026";
    private static final String PREFIX_V2 = "miyu-keybackup:2:";

    /**
     * 把 KDF 迭代次数降到 1000，使口令路径的用例可以连跑上百次。
     *
     * ⚠️ 必须在 @Before 里显式设置、在 @After 里恢复默认，绝不依赖执行顺序：
     * 若某个用例改了却没恢复，后续用例（以及 100 次连跑）会在错误参数下运行，
     * 那种"测试自己把自己改了"的失败极难定位。
     */
    @org.junit.Before
    public void kdfIsFastForTests() {
        KeyTransferPayload.setKdfIterationsForTest(1000);
    }

    @org.junit.After
    public void restoreKdfDefaults() {
        KeyTransferPayload.setKdfIterationsForTest(0);
    }

    // ===== 正例 =====

    @Test
    public void encodeDecode_roundTripsKeyPair() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        String priv = b64(kp.getPrivate().getEncoded());

        String payload = KeyTransferPayload.encode(pub, priv, PW);
        assertTrue("编码应成功", payload != null && !payload.isEmpty());
        assertTrue("载荷应以 v2 魔术前缀开头", payload.startsWith(PREFIX_V2));

        // 不带口令只能知道"需要口令"，绝不能解出任何密钥材料
        KeyTransferPayload.DecodeResult locked = KeyTransferPayload.decode(payload);
        assertTrue("v2 载荷在不给口令时必须返回 NEED_PASSWORD",
                locked.isPasswordProtected());
        assertTrue("未给口令时不得返回任何载荷内容", locked.payload == null);

        KeyTransferPayload.DecodeResult r = KeyTransferPayload.decode(payload, PW);
        assertTrue("解码应成功，实际 error=" + r.error, r.isSuccess());
        // 还原出的密钥必须与原始完全一致（字节级）
        assertEquals("公钥必须原样还原", pub, r.payload.publicKeyBase64());
        assertEquals("私钥必须原样还原", priv, r.payload.privateKeyBase64());
        assertEquals("指纹应为公钥指纹",
                KeyTransferPayload.fingerprintShort(kp.getPublic().getEncoded()),
                r.payload.fingerprintShort);
        assertEquals(2, r.payload.version);
    }

    @Test
    public void encode_isNotStableButAlwaysDecryptable() throws Exception {
        // v2 每次编码都用新的随机盐与 IV，因此同一密钥对的两次载荷**必须不同**
        // （否则说明随机源坏了：相同密文会让"口令没换"这类信息泄露出去）。
        // 但两者都必须能解回同一把密钥。
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        String priv = b64(kp.getPrivate().getEncoded());

        String a = KeyTransferPayload.encode(pub, priv, PW);
        String b = KeyTransferPayload.encode(pub, priv, PW);
        assertNotEquals("同一密钥对两次编码不得相同（随机盐/IV 必须生效）", a, b);

        assertEquals("第一次载荷应可解", pub, KeyTransferPayload.decode(a, PW).payload.publicKeyBase64());
        assertEquals("第二次载荷应可解", pub, KeyTransferPayload.decode(b, PW).payload.publicKeyBase64());
    }

    @Test
    public void payload_containsNoPlaintextKeyMaterial() throws Exception {
        // H1 的核心断言：v2 载荷里不得出现明文密钥（哪怕只是片段）。
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        String priv = b64(kp.getPrivate().getEncoded());

        String payload = KeyTransferPayload.encode(pub, priv, PW);
        assertTrue("载荷不得包含公钥明文", !payload.contains(pub));
        assertTrue("载荷不得包含私钥明文", !payload.contains(priv));
        // 公钥正文与私钥正文的前 16 个字符也不得出现（防分段泄露）
        assertTrue("载荷不得包含公钥片段", !payload.contains(pub.substring(0, 16)));
        assertTrue("载荷不得包含私钥片段", !payload.contains(priv.substring(0, 16)));
        // 明文 PKCS8 的 DER 前缀（30 82 ...）以 base64 形式出现也算泄露
        String privHeadB64 = b64(java.util.Arrays.copyOf(kp.getPrivate().getEncoded(), 12));
        assertTrue("载荷不得包含私钥头部片段的 base64", !payload.contains(privHeadB64.substring(0, 12)));
    }

    @Test
    public void payloadLength_isWithinQrVersion40Capacity() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        assertTrue("载荷不应为空", payload != null);
        // 实测（真实 P-256 密钥 91/67 字节，zxing 3.5.3，生产 KDF 参数）：
        //   v1 明文 239 字符 → QR 版本 11（61 模块）
        //   v2 加密 292 字符 → QR 版本 13（69 模块），960px 下约 13.9 px/模块
        // v2 更大的原因是：salt(22) + iv(16) + GCM 标签(16 字节→22 字符) 净增约 44 字符，
        // 只被"两个 base64url 字段的填充浪费消失"抵消了一小部分。
        // 上界取 400 而不是贴着 292：这条断言的目的不是钉死当前数字，
        // 而是"将来有人把载荷撑大时立刻报警"。贴着实测值写就会变成新的间歇性红灯。
        assertTrue("载荷长度 " + payload.length() + " 超出安全余量（v2 实测 292）",
                payload.length() < 400);
        // base64url 字段不得含 ':'，否则段数会被打乱
        assertEquals("载荷应恰好 5 段", 5, payload.split(":").length);
        // base64url 不得出现标准字母表的 '+' '/' 与填充 '='，否则在 URL/二维码里易被改写
        String body = payload.substring(PREFIX_V2.length());
        assertTrue("载荷正文不应含 '+' '/' '='，实际=" + body,
                !body.matches(".*[+/=].*"));
    }

    /**
     * 用真实 zxing 编码一次，确认载荷能被生成为二维码且版本在合理范围内。
     *
     * 这是"二维码到底能不能出图"的前置保护：仅断言字符串长度是不够的，
     * 版本号与模块数才决定屏幕上的每模块像素数、进而决定扫码成功率。
     * 实测 v2：版本 13 / 69 模块（v1 曾是版本 11 / 61 模块），余量仍然充裕。
     */
    @Test
    public void payload_isEncodableAsQrAtErrorCorrectionM() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);

        com.google.zxing.qrcode.encoder.QRCode qr =
                com.google.zxing.qrcode.encoder.Encoder.encode(
                        payload, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        int version = qr.getVersion().getVersionNumber();
        int modules = qr.getMatrix().getWidth();

        // 实测 v2 为版本 13 / 69 模块；留出余量断言 <= 20 版本（97 模块）。
        assertTrue("QR 版本 " + version + " 过高，屏幕可能扫不动", version <= 20);
        assertTrue("模块数异常: " + modules, modules == version * 4 + 17);

        // 屏幕渲染尺寸下每模块像素数：320dp 在 xxhdpi(3x) 上约 960px，需 >= 3px/模块
        int px = 960;
        double perModule = (double) px / modules;
        assertTrue("960px 下每模块仅 " + perModule + " px，过小", perModule >= 3.0);
    }

    // ===== 负例：口令与篡改 =====

    @Test
    public void decode_rejectsWrongPassword() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);

        assertEquals("错误口令必须被拒（GCM 认证失败）",
                KeyTransferPayload.Error.DECRYPT_FAILED,
                KeyTransferPayload.decode(payload, PW + "-x").error);
        assertEquals("空口令必须被拒",
                KeyTransferPayload.Error.DECRYPT_FAILED,
                KeyTransferPayload.decode(payload, "").error);
        assertEquals("大小写变化也算错误口令",
                KeyTransferPayload.Error.DECRYPT_FAILED,
                KeyTransferPayload.decode(payload, PW.toUpperCase()).error);
    }

    @Test
    public void decode_rejectsTamperedCiphertext() throws Exception {
        // H1 的核心负例：认证标签必须覆盖密文。
        // 逐字节翻转密文段，每一种都必须被拒，且不得因"翻转后恰好解出可解析的密钥"而放过。
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        String[] parts = payload.split(":", -1);
        byte[] ct = Base64.getUrlDecoder().decode(parts[4]);

        for (int i = 0; i < ct.length; i += Math.max(1, ct.length / 8)) {
            byte[] mod = ct.clone();
            mod[i] ^= 0x01;
            String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                    + parts[3] + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(mod);
            KeyTransferPayload.DecodeResult r = KeyTransferPayload.decode(tampered, PW);
            assertTrue("密文第 " + i + " 字节被翻转后必须被拒，实际=" + r.error, !r.isSuccess());
            assertEquals("密文被篡改应报 DECRYPT_FAILED(byte " + i + ")",
                    KeyTransferPayload.Error.DECRYPT_FAILED, r.error);
        }
    }

    @Test
    public void decode_rejectsTamperedSaltAndIv() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        String[] parts = payload.split(":", -1);
        byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
        byte[] iv = Base64.getUrlDecoder().decode(parts[3]);

        byte[] saltMod = salt.clone();
        saltMod[0] ^= 0x01;
        String saltTampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(saltMod)
                + ":" + parts[3] + ":" + parts[4];
        assertEquals("盐被篡改必须被拒（派生出的钥匙不同）",
                KeyTransferPayload.Error.DECRYPT_FAILED,
                KeyTransferPayload.decode(saltTampered, PW).error);

        byte[] ivMod = iv.clone();
        ivMod[0] ^= 0x01;
        String ivTampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(ivMod)
                + ":" + parts[4];
        assertEquals("IV 被篡改必须被拒",
                KeyTransferPayload.Error.DECRYPT_FAILED,
                KeyTransferPayload.decode(ivTampered, PW).error);
    }

    @Test
    public void decode_rejectsLegacyV1Payload() throws Exception {
        // v1 明文码必须被**明确拒绝**，而不是静默降级接受
        // （降级兼容 = 让明文私钥这条攻击面永久存活）。
        java.security.KeyPair kp = newKeyPair();
        String legacy = KeyTransferPayload.encodeLegacyV1ForTest(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()));
        assertTrue("测试夹具本身应能构造 v1 载荷", legacy != null && legacy.startsWith("miyu-keybackup:1:"));

        assertEquals("v1 旧码应报 LEGACY_FORMAT（码旧，需重新导出）",
                KeyTransferPayload.Error.LEGACY_FORMAT, KeyTransferPayload.decode(legacy).error);
        assertEquals("带口令也不能解 v1",
                KeyTransferPayload.Error.LEGACY_FORMAT,
                KeyTransferPayload.decode(legacy, PW).error);
        assertEquals("inspect 同样应报 LEGACY_FORMAT",
                KeyTransferPayload.Error.LEGACY_FORMAT, KeyTransferPayload.inspect(legacy).error);
    }

    @Test
    public void decode_rejectsUnsupportedFutureVersion() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        // 3 = 未来版本（"码比 App 新"）：与 v1（"码比 App 旧"）必须区分开
        String bumped = payload.replaceFirst("^miyu-keybackup:2:", "miyu-keybackup:3:");
        assertEquals("未来版本应提示需要升级",
                KeyTransferPayload.Error.UNSUPPORTED_VERSION,
                KeyTransferPayload.decode(bumped).error);
        assertEquals("inspect 同样应报 UNSUPPORTED_VERSION",
                KeyTransferPayload.Error.UNSUPPORTED_VERSION,
                KeyTransferPayload.inspect(bumped).error);
    }

    @Test
    public void inspect_reportsNeedPasswordWithoutLeakingKeys() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);

        KeyTransferPayload.DecodeResult r = KeyTransferPayload.inspect(payload);
        assertTrue("inspect 对 v2 应返回 NEED_PASSWORD", r.isPasswordProtected());
        assertTrue("inspect 不得返回任何载荷内容", r.payload == null);
        assertEquals("inspect 应能报出版本号", 2, r.version);
    }

    @Test
    public void decode_rejectsForeignQrCode() {
        // 扫到加好友码/收款码/随便一个网址，必须判为"不是密钥二维码"，
        // 而不是抛异常或当成坏密钥。
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode("familychat://user?id=123").error);
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode("https://example.com").error);
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode("").error);
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode(null).error);
        // 段数不足
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode("miyu-keybackup:2:abc").error);
        // 版本号段不是数字
        assertEquals(KeyTransferPayload.Error.NOT_KEY_PAYLOAD,
                KeyTransferPayload.decode("miyu-keybackup:x:a:b:c").error);
    }

    @Test
    public void decode_rejectsCorruptedBase64() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        String[] parts = payload.split(":", -1);
        // 往密文段塞非法字符 → Base64 解码失败（外层结构检查即拦下）
        String broken = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":!!!not-base64!!!";
        assertEquals(KeyTransferPayload.Error.MALFORMED_KEY,
                KeyTransferPayload.decode(broken, PW).error);
        // 盐段非法
        String brokenSalt = parts[0] + ":" + parts[1] + ":!!!bad!!!" + ":" + parts[3] + ":" + parts[4];
        assertEquals(KeyTransferPayload.Error.MALFORMED_KEY,
                KeyTransferPayload.decode(brokenSalt, PW).error);
    }

    @Test
    public void decode_rejectsMismatchedKeyPair() throws Exception {
        // 核心负例：公钥与私钥来自**两把不同的密钥**。
        // v1 时这种载荷格式完全合法、指纹也自洽，只有配对校验能抓到；
        // v2 时它由我方加密器生成（口令正确、GCM 通过），仍然必须被配对校验拦住。
        // 若放过，导入后新消息将永远解不开。
        java.security.KeyPair a = newKeyPair();
        java.security.KeyPair b = newKeyPair();
        String payloadWithMismatch = KeyTransferPayload.encode(
                b64(a.getPublic().getEncoded()), b64(b.getPrivate().getEncoded()), PW);
        assertTrue(payloadWithMismatch != null);
        assertEquals("公私钥不同源必须拒绝",
                KeyTransferPayload.Error.KEYPAIR_MISMATCH,
                KeyTransferPayload.decode(payloadWithMismatch, PW).error);
    }

    @Test
    public void isMatchingKeyPair_acceptsRealPairAndRejectsOthers() throws Exception {
        java.security.KeyPair a = newKeyPair();
        java.security.KeyPair b = newKeyPair();
        assertTrue(KeyTransferPayload.isMatchingKeyPair(
                (java.security.interfaces.ECPublicKey) a.getPublic(),
                (java.security.interfaces.ECPrivateKey) a.getPrivate()));
        assertTrue("不同密钥对不应被判为配对", !KeyTransferPayload.isMatchingKeyPair(
                (java.security.interfaces.ECPublicKey) a.getPublic(),
                (java.security.interfaces.ECPrivateKey) b.getPrivate()));
    }

    // ===== 编码入参健壮性 =====

    @Test
    public void encode_returnsNullOnBadInput() {
        assertEquals("null 公钥应返回 null", null, KeyTransferPayload.encode(null, "AAAA", PW));
        assertEquals("空串应返回 null", null, KeyTransferPayload.encode("", "", PW));
        assertEquals("非法 Base64 应返回 null", null,
                KeyTransferPayload.encode("!!!not-base64!!!", "!!!not-base64!!!", PW));
        // 口令缺失/过短必须拒绝：口令是 v2 唯一的机密性来源，
        // 允许空口令等于把"加密"变成"公开混淆"。
        assertEquals("null 口令应返回 null", null,
                KeyTransferPayload.encode("AAAA", "AAAA", null));
        assertEquals("空口令应返回 null", null, KeyTransferPayload.encode("AAAA", "AAAA", ""));
        assertEquals("短于 8 位的口令应返回 null", null,
                KeyTransferPayload.encode("AAAA", "AAAA", "1234567"));
    }

    @Test
    public void encode_acceptsMinimumLengthPassword() throws Exception {
        // 8 位恰好是最短允许值（不是 9，也不是 12）：策略是"8 位起步、建议 12 位以上"，
        // 建议归建议，不能变成隐式强制。
        java.security.KeyPair kp = newKeyPair();
        String eight = "abcd1234";
        assertEquals(8, eight.length());
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), eight);
        assertTrue("8 位口令应能导出", payload != null);
        assertTrue("8 位口令应能解回", KeyTransferPayload.decode(payload, eight).isSuccess());
    }

    @Test
    public void encode_acceptsBase64WithLineBreaks() throws Exception {
        // PreferencesManager 存取的是 NO_WRAP 无换行形式，但手工复制/其他工具可能带换行，
        // 编码侧要能容忍，否则会出现"密钥明明有效却导不出"。
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        String pubWrapped = pub.substring(0, 40) + "\n" + pub.substring(40);
        String payload = KeyTransferPayload.encode(pubWrapped, b64(kp.getPrivate().getEncoded()), PW);
        assertTrue("带换行的公钥也应能编码", payload != null);
        assertTrue("解码应成功", KeyTransferPayload.decode(payload, PW).isSuccess());
    }

    @Test
    public void looksLikeKeyPayload_detectsOwnFormatOnly() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String payload = KeyTransferPayload.encode(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()), PW);
        assertTrue(KeyTransferPayload.looksLikeKeyPayload(payload));
        assertTrue(!KeyTransferPayload.looksLikeKeyPayload("familychat://pay?id=1"));
        assertTrue(!KeyTransferPayload.looksLikeKeyPayload(null));
        // 前缀相同但缺分隔符
        assertTrue(!KeyTransferPayload.looksLikeKeyPayload("miyu-keybackup"));
        // v1 旧码仍会被 looksLike 认出（前缀相同）；真正的版本判定交给 decode/inspect
        String legacy = KeyTransferPayload.encodeLegacyV1ForTest(
                b64(kp.getPublic().getEncoded()), b64(kp.getPrivate().getEncoded()));
        assertTrue("v1 旧码依然会被 looksLike 认出（由 decode 拒绝）",
                KeyTransferPayload.looksLikeKeyPayload(legacy));
    }

    // =====================================================================
    // 第 3 步：导入时的"本地是否已有同一把密钥"判定
    // =====================================================================
    //
    // 这两个方法是「指纹一致 → 拒绝导入」这条短路策略的依据。
    // 判错的后果是不对称的：
    //   - 判成"一致"但实际不同 → 跳过警告，用户以为没换密钥，其实已覆盖 = 数据丢失
    //   - 判成"不同"但实际一致 → 多弹一次确认框，用户可以取消，代价只是多点一下
    // 所以下面特意钉死"算不出指纹时必须判为不同"。

    @Test
    public void isSameKey_trueForIdenticalKey() throws Exception {
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        assertTrue("同一把公钥必须判为一致", KeyTransferPayload.isSameKey(pub, pub));
    }

    @Test
    public void isSameKey_falseForDifferentKeys() throws Exception {
        java.security.KeyPair a = newKeyPair();
        java.security.KeyPair b = newKeyPair();
        assertTrue("不同公钥必须判为不一致",
                !KeyTransferPayload.isSameKey(b64(a.getPublic().getEncoded()),
                        b64(b.getPublic().getEncoded())));
    }

    @Test
    public void isSameKey_treatsUncomputableAsDifferent() {
        // 关键安全性质：只要有一边算不出指纹，就必须判"不同"，
        // 从而走更谨慎的覆盖确认流程，而不是跳过警告。
        assertTrue("null 必须判为不同", !KeyTransferPayload.isSameKey(null, null));
        assertTrue("空串必须判为不同", !KeyTransferPayload.isSameKey("", ""));
        assertTrue("垃圾输入必须判为不同",
                !KeyTransferPayload.isSameKey("!!!not-base64!!!", "!!!not-base64!!!"));
        // 一边有效一边无效
        try {
            java.security.KeyPair kp = newKeyPair();
            String pub = b64(kp.getPublic().getEncoded());
            assertTrue("一边无效必须判为不同", !KeyTransferPayload.isSameKey(pub, null));
            assertTrue("一边无效必须判为不同", !KeyTransferPayload.isSameKey(null, pub));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void fingerprintShortOfBase64_agreesWithDisplayedFingerprint() throws Exception {
        // 页面显示的指纹与"判定是否同一把"用的指纹必须来自同一算法，
        // 否则会出现"显示不一致但判定一致"的自相矛盾结果。
        java.security.KeyPair kp = newKeyPair();
        String pub = b64(kp.getPublic().getEncoded());
        String fromBase64 = KeyTransferPayload.fingerprintShortOfBase64(pub);
        String fromBytes = KeyTransferPayload.fingerprintShort(kp.getPublic().getEncoded());
        assertEquals("base64 便捷方法必须与字节版结果一致", fromBytes, fromBase64);
        assertEquals(8, fromBase64.length());
    }

    @Test
    public void fingerprintShortOfBase64_handlesBadInput() {
        assertEquals("", KeyTransferPayload.fingerprintShortOfBase64(null));
        assertEquals("", KeyTransferPayload.fingerprintShortOfBase64(""));
        assertEquals("", KeyTransferPayload.fingerprintShortOfBase64("!!!bad!!!"));
    }
}
