package com.familychat.app.utils;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * 安全码二维码载荷（{@code miyu-safety:v1}）。
 *
 * <p>用途：TOFU（{@code docs/audits/E2EE_SECURITY_AUDIT.md} §1.1）的第二层 —— 用户当面（或其它可信渠道）
 * 互扫二维码，把"屏幕上看到的指纹"与"本地 pin 住的那把钥匙"做**带外比对**，
 * 从而不依赖服务端也能确认中间没有人替换过公钥。
 *
 * <p><b>与 {@link KeyTransferPayload}（{@code miyu-keybackup}）的关系</b>：
 * 那个载荷装的是"口令加密的私钥备份"，绝不能拿来核对身份；本类只装**公钥**。
 * 两者用不同的 magic 前缀，因此
 * {@code KeyQrImportActivity} 的 {@code looksLikeKeyPayload} 与本类的
 * {@link #looksLikeSafetyPayload} 天然互不误认。
 *
 * <p><b>格式</b>：
 * <pre>
 *   miyu-safety:1:&lt;pubB64url&gt;
 * </pre>
 * <ul>
 *   <li>{@code miyu-safety} 固定前缀（用途标识）</li>
 *   <li>{@code 1} 格式版本号</li>
 *   <li>{@code pubB64url} = X509 SubjectPublicKeyInfo(DER) 的 Base64url（无填充）。
 *       P-256 下 91 字节 → 122 字符，整条载荷约 137 字符（远短于密钥迁移码的 ~380 字符）。</li>
 * </ul>
 *
 * <p><b>刻意不放进载荷的字段</b>：
 * <ul>
 *   <li><b>指纹</b>：从公钥可现算，冗余字段只会带来"两处不一致时信谁"的问题；</li>
 *   <li><b>昵称</b>：未认证的自称字段。显示出来会诱导用户"看见名字对了就放心"，
 *       反而削弱验证本身；</li>
 *   <li><b>时间戳</b>：对验证没有意义，且会助长"码越新越可信"的错觉；</li>
 *   <li><b>签名</b>：身份密钥自签没有信任锚，属于另一套设计（transparency log 级别），不属本轮；</li>
 *   <li><b>口令加密</b>：公钥本就是公开信息。加口令会把"当面扫一下"变成"还要交换口令"，
 *       把简单事搞复杂，且不会增加任何安全性。</li>
 * </ul>
 *
 * <p>⚠️ 本类必须保持「纯 JVM」：不得 import 任何 android.* 类（与 {@link KeyTransferPayload} 同规矩，
 * 单测无需模拟器即可覆盖）。
 */
public final class SafetyNumberPayload {

    /** 载荷前缀（用途标识；必须与 {@code miyu-keybackup} 区分开）。 */
    public static final String MAGIC = "miyu-safety";
    /** 当前载荷格式版本。 */
    public static final int VERSION = 1;

    private static final char SEP = ':';
    /** 3 段：magic / ver / pub。 */
    private static final int PAYLOAD_PARTS = 3;
    /** 公钥 DER 长度上限（与 {@code KeyTransferPayload.PUB_MAX_LEN}、服务端 {@code E2eeValidator} 对齐）。 */
    private static final int PUB_MAX_LEN = 1024;

    private SafetyNumberPayload() {
    }

    /** 解析失败原因（供 UI 给确切提示，不暴露内部细节）。 */
    public enum Error {
        /** 不是安全码二维码（前缀/段数不对）—— 扫到了别的码。 */
        NOT_SAFETY_PAYLOAD,
        /** 二维码版本高于本 App 支持的版本 —— 需要升级 App 后再扫。 */
        UNSUPPORTED_VERSION,
        /** Base64url 解不开、长度非法，或不是 X509 的 EC P-256 公钥。 */
        MALFORMED_KEY
    }

    /** 解析结果：成功时 {@link #fullFingerprint} 非空，失败时 {@link #error} 非空。 */
    public static final class ParseResult {
        /** 成功时：{@code SHA-256(SPKI)} 的 64 位小写 hex。 */
        public final String fullFingerprint;
        /** 外层解析出的版本号；无法解析时为 0。 */
        public final int version;
        /** 失败原因；成功时为 null。 */
        public final Error error;

        private ParseResult(String fullFingerprint, int version, Error error) {
            this.fullFingerprint = fullFingerprint;
            this.version = version;
            this.error = error;
        }

        public boolean isSuccess() {
            return fullFingerprint != null && !fullFingerprint.isEmpty();
        }
    }

    /**
     * 把本机公钥编码成安全码载荷。
     *
     * @param publicKeyB64 X509 公钥的 Base64（可含换行、标准或 URL-safe 字母表，内部会归一化）
     * @return 载荷字符串；入参为空、Base64 非法、或不是 EC P-256 公钥时返回 {@code null}
     */
    public static String encode(String publicKeyB64) {
        byte[] spki = KeyTransferPayload.decodeBase64Lenient(publicKeyB64);
        if (spki == null || spki.length == 0 || spki.length > PUB_MAX_LEN) return null;
        if (!isEcP256(spki)) return null;
        return MAGIC + SEP + VERSION + SEP + KeyTransferPayload.encodeBase64Url(spki);
    }

    /**
     * 是否是本应用的"安全码"二维码（只判前缀，不做完整校验）。
     *
     * <p>与 {@link KeyTransferPayload#looksLikeKeyPayload(String)} 同款做法：
     * 先看前缀再决定要不要走后续昂贵的解析，也给用户"扫错码了"的准确提示。
     */
    public static boolean looksLikeSafetyPayload(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        return text.length() > MAGIC.length()
                && text.charAt(MAGIC.length()) == SEP
                && text.startsWith(MAGIC);
    }

    /**
     * 解析安全码载荷并算出公钥指纹。
     *
     * <p>校验顺序（与 {@link KeyTransferPayload} 的"故意做得严"一致）：
     * 段数/前缀/版本 → Base64url 可解且长度合理 → 真的是 X509 的 EC P-256 公钥 → 指纹可算。
     * 任何一步不过都返回失败，绝不返回"半个结果"。
     *
     * @param raw 扫码得到的原始文本
     * @return 永不返回 {@code null}；用 {@link ParseResult#isSuccess()} 判断
     */
    public static ParseResult parse(String raw) {
        if (raw == null) return new ParseResult(null, 0, Error.NOT_SAFETY_PAYLOAD);
        String text = raw.trim();
        if (text.isEmpty()) return new ParseResult(null, 0, Error.NOT_SAFETY_PAYLOAD);

        String[] parts = text.split(String.valueOf(SEP), -1);
        if (parts.length != PAYLOAD_PARTS || !MAGIC.equals(parts[0])) {
            return new ParseResult(null, 0, Error.NOT_SAFETY_PAYLOAD);
        }

        int version;
        try {
            version = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            // 版本字段都不是数字 → 这压根不像本应用的码
            return new ParseResult(null, 0, Error.NOT_SAFETY_PAYLOAD);
        }
        if (version > VERSION) return new ParseResult(null, version, Error.UNSUPPORTED_VERSION);
        if (version < 1) return new ParseResult(null, version, Error.MALFORMED_KEY);

        byte[] spki = KeyTransferPayload.decodeBase64Url(parts[2]);
        if (spki == null || spki.length == 0 || spki.length > PUB_MAX_LEN) {
            return new ParseResult(null, version, Error.MALFORMED_KEY);
        }
        if (!isEcP256(spki)) return new ParseResult(null, version, Error.MALFORMED_KEY);

        String fp = KeyTransferPayload.fingerprintFullHex(spki);
        if (fp.isEmpty()) return new ParseResult(null, version, Error.MALFORMED_KEY);
        return new ParseResult(fp, version, null);
    }

    /**
     * 公钥字节是否是 X509 的 EC P-256（secp256r1）公钥。
     *
     * <p>与 {@code E2eeValidator.isValidPublicKey}（服务端）和
     * {@link KeyTransferPayload} 的密钥解析同一套判据：光看长度不够，必须真的解析出来。
     */
    private static boolean isEcP256(byte[] spki) {
        try {
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            if (!(pub instanceof ECPublicKey)) return false;
            return ((ECPublicKey) pub).getParams().getCurve().getField().getFieldSize() == 256;
        } catch (Exception e) {
            return false;
        }
    }
}
