package com.familychat.app.utils;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * E2EE 私钥「换机迁移」载荷工具。
 *
 * 本类分两个阶段实现：
 *   第 1 步（已完成）：指纹计算 —— fingerprintShort / fingerprintFullHex / group4
 *   第 2 步（已完成）：二维码载荷 encode / decode（decode 供第 3 步扫码导入使用）
 *   第 3 步（待实现）：扫码导入的 UI 与"本地已有密钥"策略
 *
 * ⚠️ 本类必须保持「纯 JVM」：不得 import 任何 android.* 类。
 *    原因有两个：
 *      1) Base64 编解码用 JDK 的 java.util.Base64（Url 编码器），单测无需
 *         android.jar 桩/替身，纯 JVM 即可覆盖；
 *      2) AppLog 内部依赖真实 Context.getFilesDir()，在单测里会静默失败，
 *         所以日志一律由调用方（Activity）负责，本类不打日志。
 *
 * ─────────────────────────────────────────────────────────────
 * 指纹协议（对外契约，破坏性变更需慎重）
 * ─────────────────────────────────────────────────────────────
 *   输入 = X509 SubjectPublicKeyInfo 的 DER 原始字节
 *          （即 prefs.getMyPublicKey() 经 Base64.NO_WRAP 解码后的结果）
 *   摘要 = SHA-256
 *   短码 = 摘要前 4 字节，小写 hex，共 8 字符
 *   全码 = 摘要全部 32 字节，小写 hex，共 64 字符
 *
 *   注意：哈希对象是 DER 字节，不是 Base64 文本。
 *   若改为对 Base64 字符串做哈希，同一把密钥在不同换行/补位策略下会得到
 *   不同指纹，"两台设备人工核对"这一唯一用途会静默失效。
 *
 * ─────────────────────────────────────────────────────────────
 * 二维码载荷协议（对外契约，破坏性变更需慎重）
 * ─────────────────────────────────────────────────────────────
 *   ⚠️ 当前版本 = 2（口令保护）。v1（明文私钥）**已作废、不可导入**。
 *
 *   v2 格式（5 段，以 ':' 分隔）：
 *       miyu-keybackup:2:<saltB64url>:<ivB64url>:<ctB64url>
 *   字段：
 *       miyu-keybackup  固定前缀（用途标识，必须与 familychat:// 业务码区分）
 *       ver             格式版本号，当前为 2
 *       saltB64url      16 字节随机盐（PBKDF2 用）
 *       ivB64url        12 字节随机 GCM IV
 *       ctB64url        AES-256-GCM 密文（含 16 字节认证标签）
 *
 *   明文结构（加密前，自有 TLV 头；长度上限由 PUB_MAX_LEN 兜底）：
 *       0x01 | pubLen(1) | pub(X509 SPKI) | priv(PKCS8)
 *       实测 P-256：1 + 1 + 91 + 67 = 160 字节 → GCM 后 176 字节 → base64url 235 字符。
 *
 *   容量实测（zxing 3.5.3 + 真实 P-256 密钥，纠错级别 M）：
 *       v1 明文 239 字符 → 版本 11（61 模块）
 *       v2 加密 292 字符 → 版本 13（69 模块），960px 渲染下约 13.9 px/模块
 *   v2 反而更大：salt(22) + iv(16) + 认证标签(16 字节→22 字符) 净增约 44 字符，
 *   只被"v1 两个 base64url 字段各自的填充浪费消失"抵消一小部分。
 *   这仍远低于版本 40 容量上限，由单测 payload_isEncodableAsQrAtErrorCorrectionM 钉住。
 *
 *   密钥派生：PBKDF2-HMAC-SHA256(password, salt, 210000, 256)
 *       迭代数与 KeyBackup v2 对齐（21 万）。SHA-256 而非 SHA-1：
 *       minSdk 24 下 API 26+ 原生支持，24~25 由 Conscrypt 提供（已实测两端均可用）。
 *   机密性/完整性：AES/GCM/NoPadding，128 位标签。
 *       任何一位被改动（salt/iv/ct 或版本段）都会在 GCM 验签时失败 → DECRYPT_FAILED。
 *
 *   口令策略：最短 8 位，**不强制**更长；弹窗建议 12 位以上混合大小写数字。
 *       为什么长度是唯一真实的防线：二维码一旦被拍照，攻击者可离线无限次尝试，
 *       KDF 只能抬高每次尝试的成本，无法弥补口令本身的低熵。
 *       口令**不落盘、不进 Intent、不打日志**：只存在于导出页/导入页的输入框内存中。
 *
 *   为什么不再需要 fp8 字段：认证标签已覆盖明文中的公钥，篡改必被拒；
 *       保留一个无密钥校验和反而会让人误以为"校验和 = 完整性"。
 *       指纹算法本身未变，仍用于页面显示与「两台设备是不是同一把」判定。
 *
 *   为什么 v1 不降级兼容：降级兼容 = 让「明文私钥二维码」这条攻击面永久存活，
 *       而且会让"已加密"变成看运气的假象。v1 码一律 UNSUPPORTED_VERSION，
 *       提示用户用当前版本重新导出。
 *
 *   为什么载荷里带公钥（而不是只用本地公钥）：
 *       服务端 uploadPublicKey 只校验公钥格式合法，不校验它与私钥配对。
 *       若只传私钥、公钥沿用本地现存的，重置场景会出现"公私钥不同源"，
 *       导致新消息解不开。带上公钥并在导入时做配对校验可从根上避免。
 *
 *   解码必须做四重校验（见 decode）：
 *       ① 前缀 / 版本 / 段数（v1 与未来版本一律拒绝）
 *       ② Base64url 可解、salt/iv 长度合理
 *       ③ GCM 认证通过（口令错误或载荷损坏都在此被拒）
 *       ④ 明文内的密钥可解析为 X509 / PKCS8 EC P-256，且确实是同一对
 */
public final class KeyTransferPayload {

    /** 十六进制查表：避免 String.format("%02x") 依赖默认 Locale 的问题。 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final String DIGEST_ALGO = "SHA-256";
    /** 短码取摘要前 4 字节。 */
    private static final int SHORT_BYTES = 4;
    /** 全码取完整摘要 32 字节。 */
    private static final int FULL_BYTES = 32;

    // ===== 二维码载荷协议常量 =====

    /** 载荷前缀（用途标识）。 */
    public static final String PAYLOAD_MAGIC = "miyu-keybackup";
    /** 当前载荷格式版本（v2 = 口令保护；v1 明文已作废）。 */
    public static final int PAYLOAD_VERSION = 2;
    /** 唯一被接受的版本（历史版本一律拒绝，见类注释）。 */
    private static final int MIN_SUPPORTED_VERSION = 2;
    private static final char SEP = ':';
    /** 5 段：magic / ver / salt / iv / ct。 */
    private static final int PAYLOAD_PARTS = 5;
    /** 公钥长度上限（与服务端 E2eeValidator.PUBKEY_MAX_LEN 对齐）。 */
    private static final int PUB_MAX_LEN = 1024;

    // ===== v2 口令保护参数 =====

    /** 口令最短长度。低于此值 encode 返回 null（导出页据此拦下）。 */
    public static final int MIN_PASSWORD_LENGTH = 8;
    /**
     * KDF 迭代次数。与 KeyBackup v2 的 21 万对齐。
     * 实测（JDK 24，单次派生）≈ 90~180 ms；中端真机约 0.5~1 s，一次性可接受。
     */
    private static final int DEFAULT_KDF_ITERATIONS = 210000;
    private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
    private static final int AES_KEY_BITS = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MIN_SALT_LENGTH = 8;
    private static final int MAX_SALT_LENGTH = 64;
    /** 明文 TLV 头：0x01 = 明文版本。 */
    private static final byte PLAINTEXT_VERSION = 0x01;
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";

    /** 测试可下调的迭代次数（生产恒为 DEFAULT_KDF_ITERATIONS）。 */
    private static volatile int kdfIterations = DEFAULT_KDF_ITERATIONS;

    private static final SecureRandom secureRandom = new SecureRandom();

    private KeyTransferPayload() {
    }

    /**
     * 仅供单元测试下调 KDF 迭代次数，让「口令路径」的用例可以连跑上百次而不必每次等 0.1~1 s。
     * 传 0 或负数恢复默认值。生产代码不得调用。
     */
    static void setKdfIterationsForTest(int iterations) {
        kdfIterations = (iterations <= 0) ? DEFAULT_KDF_ITERATIONS : iterations;
    }

    /**
     * 指纹短码：SHA-256(SPKI) 前 4 字节 → 8 位小写 hex。
     *
     * @param spki X509 SubjectPublicKeyInfo 的 DER 原始字节（不是 Base64 文本）
     * @return 8 位小写 hex；输入为 null / 空数组 / 无法计算时返回空串（不抛异常）
     */
    public static String fingerprintShort(byte[] spki) {
        return hex(digest(spki), SHORT_BYTES);
    }

    /**
     * 指纹全码：SHA-256(SPKI) 全部 32 字节 → 64 位小写 hex。用于两台设备并排人眼比对。
     *
     * @param spki X509 SubjectPublicKeyInfo 的 DER 原始字节（不是 Base64 文本）
     * @return 64 位小写 hex；输入为 null / 空数组 / 无法计算时返回空串（不抛异常）
     */
    public static String fingerprintFullHex(byte[] spki) {
        return hex(digest(spki), FULL_BYTES);
    }

    /**
     * 每 4 个字符插入一个空格，便于人眼分组核对。
     * 例："a1b2c3d4" → "a1b2 c3d4"；64 位 → 16 组。
     * 长度不是 4 的倍数时不丢字符、不留尾部空格。
     *
     * @return 分组后的字符串；输入为 null / 空串时返回空串
     */
    public static String group4(String hex) {
        if (hex == null || hex.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(hex.length() + hex.length() / 4);
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(hex.charAt(i));
        }
        return sb.toString();
    }

    /** 计算 SHA-256；输入不可用时返回 null（由调用方转成空串）。 */
    private static byte[] digest(byte[] spki) {
        if (spki == null || spki.length == 0) return null;
        try {
            return MessageDigest.getInstance(DIGEST_ALGO).digest(spki);
        } catch (Exception e) {
            // SHA-256 在 API 24+ 必定可用；真出现异常时返回空串而非抛出，
            // 避免"看一眼指纹"这种只读操作把页面搞崩。
            return null;
        }
    }

    /**
     * 取摘要前 byteCount 字节做小写 hex。不依赖 Locale。
     *
     * @return 2*byteCount 位小写 hex；digest 为 null 或长度不足时返回空串
     */
    private static String hex(byte[] digest, int byteCount) {
        if (digest == null || digest.length < byteCount || byteCount <= 0) return "";
        char[] out = new char[byteCount * 2];
        for (int i = 0; i < byteCount; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    // =====================================================================
    // 二维码载荷：编码 / 解码
    // =====================================================================

    /**
     * 把密钥对用**迁移口令**加密后编码成二维码载荷（v2）。
     *
     * @param publicKeyB64  X509 公钥的 Base64（可含换行/标准或 URL-safe，内部会归一化）
     * @param privateKeyB64 PKCS8 私钥的 Base64（同上）
     * @param password      迁移口令（≥ {@link #MIN_PASSWORD_LENGTH} 位；只在本方法内存中使用）
     * @return 载荷字符串；任一入参为空、口令过短、Base64 非法时返回 null
     */
    public static String encode(String publicKeyB64, String privateKeyB64, String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) return null;
        byte[] pub = decodeBase64Lenient(publicKeyB64);
        byte[] priv = decodeBase64Lenient(privateKeyB64);
        if (pub == null || pub.length == 0 || priv == null || priv.length == 0) return null;
        if (pub.length > PUB_MAX_LEN || pub.length > 0xFF) return null;

        try {
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            SecretKey key = deriveKey(password, salt, kdfIterations);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(buildPlaintext(pub, priv));

            return PAYLOAD_MAGIC + SEP + PAYLOAD_VERSION + SEP
                    + encodeBase64Url(salt) + SEP
                    + encodeBase64Url(iv) + SEP
                    + encodeBase64Url(ct);
        } catch (Exception e) {
            // 不打印任何口令/密钥材料，仅返回 null 由调用方提示。
            return null;
        }
    }

    /**
     * ⚠️ 仅用于测试构造 **v1 旧格式**（明文私钥），用来验证「旧码必须被拒」。
     * 生产代码不得调用：v1 载荷等于把私钥明文放进二维码。
     *
     * 格式：miyu-keybackup:1:<pubB64url>:<privB64url>:<fp8>
     */
    static String encodeLegacyV1ForTest(String publicKeyB64, String privateKeyB64) {
        byte[] pub = decodeBase64Lenient(publicKeyB64);
        byte[] priv = decodeBase64Lenient(privateKeyB64);
        if (pub == null || pub.length == 0 || priv == null || priv.length == 0) return null;
        String fp = fingerprintShort(pub);
        if (fp.isEmpty()) return null;
        return PAYLOAD_MAGIC + SEP + 1 + SEP
                + encodeBase64Url(pub) + SEP
                + encodeBase64Url(priv) + SEP
                + fp;
    }

    /** 明文 TLV：0x01 | pubLen(1) | pub | priv。 */
    private static byte[] buildPlaintext(byte[] pub, byte[] priv) {
        byte[] out = new byte[2 + pub.length + priv.length];
        out[0] = PLAINTEXT_VERSION;
        out[1] = (byte) pub.length;
        System.arraycopy(pub, 0, out, 2, pub.length);
        System.arraycopy(priv, 0, out, 2 + pub.length, priv.length);
        return out;
    }

    /** PBKDF2-HMAC-SHA256 派生 256 位 AES 钥匙。 */
    private static SecretKey deriveKey(String password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGO);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    /** 解码结果：成功时 payload 非空，失败时 error 非空。 */
    public static final class DecodeResult {
        /** 成功时的载荷内容。 */
        public final Payload payload;
        /** 失败原因。 */
        public final Error error;
        /** 外层解析出的版本号；无法解析时为 0。 */
        public final int version;

        private DecodeResult(Payload payload, Error error) {
            this(payload, error, payload == null ? 0 : payload.version);
        }

        private DecodeResult(Payload payload, Error error, int version) {
            this.payload = payload;
            this.error = error;
            this.version = version;
        }

        public boolean isSuccess() {
            return payload != null;
        }

        /**
         * 是否还需要口令（error == NEED_PASSWORD）。
         * 调用方据此弹口令框，而不是把 NEED_PASSWORD 当成"坏码"提示。
         */
        public boolean isPasswordProtected() {
            return error == Error.NEED_PASSWORD;
        }
    }

    /** 解码失败原因（供 UI 给出确切提示，不暴露内部细节）。 */
    public enum Error {
        /** 不是本应用的密钥二维码（前缀/段数/版本号字段不对）—— 扫码扫到了别的码。 */
        NOT_KEY_PAYLOAD,
        /**
         * 二维码版本高于本 App 支持的版本 —— 需要用**更新版本**的 App 导入。
         * 「码比 App 新」：用户要升级自己的 App。
         */
        UNSUPPORTED_VERSION,
        /**
         * 二维码是旧版（v1 明文格式）导出的，本版本已不再接受。
         * 「码比 App 旧」：用户要**用当前版本重新导出**一张，而不是升级设备。
         * 这两种情况必须分开提示，否则用户会去升级已经是最新的设备却依然扫不动。
         */
        LEGACY_FORMAT,
        /** 需要口令才能继续（调用方据此弹口令输入框）。 */
        NEED_PASSWORD,
        /** 口令错误**或**载荷被篡改（AEAD 认证失败，二者在密码学上不可区分）。 */
        DECRYPT_FAILED,
        /** Base64 解码失败、salt/iv 长度非法、或密钥格式非法（非 X509/PKCS8 的 EC P-256）。 */
        MALFORMED_KEY,
        /** 公钥与私钥不是同一对 —— 导入会导致消息解不开。 */
        KEYPAIR_MISMATCH
    }

    /** 解码成功后的载荷内容。 */
    public static final class Payload {
        /** X509 公钥的 DER 原始字节。 */
        public final byte[] publicKeySpki;
        /** PKCS8 私钥的 DER 原始字节。 */
        public final byte[] privateKeyPkcs8;
        /** 公钥指纹短码（8 位小写 hex）。 */
        public final String fingerprintShort;
        /** 载荷格式版本。 */
        public final int version;

        Payload(byte[] publicKeySpki, byte[] privateKeyPkcs8, String fingerprintShort, int version) {
            this.publicKeySpki = publicKeySpki;
            this.privateKeyPkcs8 = privateKeyPkcs8;
            this.fingerprintShort = fingerprintShort;
            this.version = version;
        }

        /** 公钥的 Base64（NO_WRAP 风格，标准字母表）——可直接喂给 uploadPublicKey。 */
        public String publicKeyBase64() {
            return Base64.getEncoder().encodeToString(publicKeySpki);
        }

        /** 私钥的 Base64（NO_WRAP 风格，标准字母表）——与 PreferencesManager 存取格式一致。 */
        public String privateKeyBase64() {
            return Base64.getEncoder().encodeToString(privateKeyPkcs8);
        }
    }

    /**
     * 只解析二维码的**外层结构**，不需要口令，也不返回任何密钥材料。
     *
     * 用途：导入页拿到二维码后先判断「这是不是本应用的码」「要不要弹口令框」。
     * 刻意不返回私钥，避免调用方在没解出口令前就拿到可用于误判的部分数据。
     *
     * @return 永不返回 null。
     *         · 继续解需要口令 → error = NEED_PASSWORD（payload 为 null）
     *         · 旧格式 v1       → error = LEGACY_FORMAT
     *         · 版本更新        → error = UNSUPPORTED_VERSION
     *         · 非本应用的码     → error = NOT_KEY_PAYLOAD
     *         · 结构/Base64 非法 → error = MALFORMED_KEY
     */
    public static DecodeResult inspect(String raw) {
        return classifyHeader(raw);
    }

    /**
     * 解码二维码载荷（v2，需要口令），并做四重校验：
     * 段数/前缀/版本 → salt/iv/ct 可解 → GCM 认证 → 密钥格式与配对。
     *
     * 校验故意做得严：这是"把私钥从 A 设备搬到 B 设备"的唯一入口，
     * 放过一个坏载荷的后果是历史消息永久解不开，而不是一次失败的操作。
     *
     * @param raw      扫码得到的原始文本（已 trim）
     * @param password 迁移口令（导出时由用户设置）
     * @return 永不返回 null；用 isSuccess()/error 判断
     */
    public static DecodeResult decode(String raw, String password) {
        DecodeResult header = classifyHeader(raw);
        if (header.error == Error.NEED_PASSWORD && password == null) {
            return header;
        }
        if (!header.isPasswordProtected()) {
            // v1 / 未来版本 / 非本应用的码 / 结构非法：原样返回对应错误
            return header;
        }
        String[] parts = raw.trim().split(String.valueOf(SEP), -1);

        byte[] salt = decodeBase64Url(parts[2]);
        byte[] iv = decodeBase64Url(parts[3]);
        byte[] ct = decodeBase64Url(parts[4]);
        if (salt == null || salt.length < MIN_SALT_LENGTH || salt.length > MAX_SALT_LENGTH) {
            return new DecodeResult(null, Error.MALFORMED_KEY);
        }
        if (iv == null || iv.length != IV_LENGTH) return new DecodeResult(null, Error.MALFORMED_KEY);
        if (ct == null || ct.length == 0) return new DecodeResult(null, Error.MALFORMED_KEY);

        // ③ GCM 认证：口令错误与载荷被篡改都会在这里失败（不可区分，也不该区分）
        byte[] plain;
        try {
            SecretKey key = deriveKey(password, salt, kdfIterations);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            plain = cipher.doFinal(ct);
        } catch (Exception e) {
            return new DecodeResult(null, Error.DECRYPT_FAILED);
        }

        // ④a 明文结构：0x01 | pubLen(1) | pub | priv
        if (plain.length < 3 || plain[0] != PLAINTEXT_VERSION) {
            return new DecodeResult(null, Error.MALFORMED_KEY);
        }
        int pubLen = plain[1] & 0xFF;
        if (pubLen == 0 || pubLen > PUB_MAX_LEN || plain.length < 2 + pubLen + 1) {
            return new DecodeResult(null, Error.MALFORMED_KEY);
        }
        byte[] pub = Arrays.copyOfRange(plain, 2, 2 + pubLen);
        byte[] priv = Arrays.copyOfRange(plain, 2 + pubLen, plain.length);

        return finishDecode(pub, priv, header.version);
    }

    /**
     * 兼容入口：不带口令调用 decode。
     *
     * 保留它是因为调用方可能先 `decode(raw)` 探一下 —— 但**不要**用它来解 v2 载荷：
     * 遇到 v2 一定返回 NEED_PASSWORD，调用方必须改为 {@link #decode(String, String)}。
     */
    public static DecodeResult decode(String raw) {
        return classifyHeader(raw);
    }

    /** 解出密钥后的最后两步校验：格式/曲线 → 配对。 */
    private static DecodeResult finishDecode(byte[] pub, byte[] priv, int version) {
        // 密钥格式：必须是 EC P-256 的 X509 公钥 / PKCS8 私钥
        ECPublicKey ecPub = parseEcPublicKey(pub);
        if (ecPub == null) return new DecodeResult(null, Error.MALFORMED_KEY);
        ECPrivateKey ecPriv = parseEcPrivateKey(priv);
        if (ecPriv == null) return new DecodeResult(null, Error.MALFORMED_KEY);

        // 公钥/私钥配对校验（防"格式合法但不同源"）
        if (!isMatchingKeyPair(ecPub, ecPriv)) {
            return new DecodeResult(null, Error.KEYPAIR_MISMATCH);
        }

        String fp = fingerprintShort(pub);
        if (fp.isEmpty()) return new DecodeResult(null, Error.MALFORMED_KEY);
        return new DecodeResult(new Payload(pub, priv, fp, version), null);
    }

    /** 外层分类：不接触任何密钥材料，只决定「能不能解、该怎么提示」。 */
    private static DecodeResult classifyHeader(String raw) {
        if (raw == null) return new DecodeResult(null, Error.NOT_KEY_PAYLOAD);
        String text = raw.trim();
        if (text.isEmpty()) return new DecodeResult(null, Error.NOT_KEY_PAYLOAD);

        String[] parts = text.split(String.valueOf(SEP), -1);
        if (parts.length != PAYLOAD_PARTS) return new DecodeResult(null, Error.NOT_KEY_PAYLOAD);
        if (!PAYLOAD_MAGIC.equals(parts[0])) return new DecodeResult(null, Error.NOT_KEY_PAYLOAD);

        int version;
        try {
            version = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return new DecodeResult(null, Error.NOT_KEY_PAYLOAD);
        }
        // 「码比 App 旧」与「码比 App 新」必须分开提示：两者的处置动作相反。
        if (version < MIN_SUPPORTED_VERSION) return new DecodeResult(null, Error.LEGACY_FORMAT);
        if (version > PAYLOAD_VERSION) return new DecodeResult(null, Error.UNSUPPORTED_VERSION);

        // 结构粗检（真正的验签在 decode(raw, password) 里）
        if (decodeBase64Url(parts[2]) == null || decodeBase64Url(parts[3]) == null
                || decodeBase64Url(parts[4]) == null) {
            return new DecodeResult(null, Error.MALFORMED_KEY);
        }
        return new DecodeResult(null, Error.NEED_PASSWORD, version);
    }

    /** 载荷是否为本应用生成的密钥二维码（只判前缀，不做完整校验）。 */
    public static boolean looksLikeKeyPayload(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        return text.length() > PAYLOAD_MAGIC.length()
                && text.charAt(PAYLOAD_MAGIC.length()) == SEP
                && text.startsWith(PAYLOAD_MAGIC);
    }

    /**
     * 对外的指纹便捷方法（入参是 base64 文本，不是 DER 字节）。
     *
     * 存在的意义：Activity 里判断"本地已有密钥与扫码得到的密钥是否同一把"。
     * 这个判断必须与页面上显示的指纹用**同一套算法**，否则会出现
     * "显示不一致但判定一致"这种自相矛盾的结果，用户会彻底失去信任。
     *
     * @return 8 位小写 hex；入参为空或无法解码时返回空串
     */
    public static String fingerprintShortOfBase64(String base64Key) {
        byte[] raw = decodeBase64Lenient(base64Key);
        if (raw == null || raw.length == 0) return "";
        return fingerprintShort(raw);
    }

    /**
     * 两把密钥（公钥 base64 文本）是否同一把。
     * 任一指纹算不出来时返回 false —— 判"不同"会走更谨慎的覆盖确认流程，
     * 宁可多问一次，也不能把不同密钥误判成相同而跳过警告。
     */
    public static boolean isSameKey(String pubBase64A, String pubBase64B) {
        String a = fingerprintShortOfBase64(pubBase64A);
        String b = fingerprintShortOfBase64(pubBase64B);
        return !a.isEmpty() && a.equals(b);
    }

    /**
     * 公钥与私钥是否属于同一密钥对。
     *
     * 做法：用私钥对一段固定数据做 ECDSA 签名，再用待验证的公钥验签，
     * 验签通过即说明两者配对。
     *
     * 为什么不直接用 ECDH 协商：ECDH 用"任意私钥 + 任意公钥"都能算出一个共享密钥，
     * 不配对时只是结果不同而**不会报错**，因此无法用来判定配对
     * （只有低阶点那种极端输入才会被拒绝）。
     *
     * 为什么不用 JDK 的 EC 点乘：java.security.spec.ECPoint 是纯数据类，
     * 没有 multiply 方法（那是 BouncyCastle 的 API），JDK 未暴露点乘。
     *
     * 安全性说明：签名只针对下面这个固定常量，不含任何用户数据或随机输入；
     * 私钥材料不出本方法、也不会被记录。该判定只在"用户主动扫码导入"时执行一次。
     */
    static boolean isMatchingKeyPair(ECPublicKey pub, ECPrivateKey priv) {
        if (pub == null || priv == null) return false;
        try {
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(priv);
            signer.update(PAIR_CHECK_MESSAGE);
            byte[] sig = signer.sign();

            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(pub);
            verifier.update(PAIR_CHECK_MESSAGE);
            return verifier.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** 配对自检用的固定数据（常量，不含用户内容）。 */
    private static final byte[] PAIR_CHECK_MESSAGE =
            "miyu-e2ee-keypair-selfcheck".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** 解析 X509 EC 公钥；要求 P-256（与客户端 secp256r1 一致）。失败返回 null。 */
    static ECPublicKey parseEcPublicKey(byte[] spki) {
        try {
            PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            if (!(k instanceof ECPublicKey)) return null;
            ECPublicKey ec = (ECPublicKey) k;
            if (ec.getParams().getCurve().getField().getFieldSize() != 256) return null;
            return ec;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 PKCS8 EC 私钥；要求 P-256。失败返回 null。 */
    static ECPrivateKey parseEcPrivateKey(byte[] pkcs8) {
        try {
            java.security.PrivateKey k =
                    KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (!(k instanceof ECPrivateKey)) return null;
            ECPrivateKey ec = (ECPrivateKey) k;
            if (ec.getParams().getCurve().getField().getFieldSize() != 256) return null;
            return ec;
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Base64 工具（URL-safe 无填充；解码宽松以兼容标准字母表） =====

    /** 无填充 URL-safe Base64 编码（载荷里用它，避免 ':' 之外还要处理 '+' '/' '='）。 */
    static String encodeBase64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * 解码载荷字段。
     *
     * 只用 java.util.Base64 的解码器：它们本身就会拒绝非法字符
     * （不像 android.util.Base64 / MIME 解码器那样静默丢弃），
     * 且 URL 解码器同时接受 '+' '/' 与 '-' '_' 两种字母表。
     * 这里刻意不再加"MIME 兜底"——那会把垃圾输入解成看似正常的字节。
     */
    static byte[] decodeBase64Url(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 宽松解码：容忍换行、标准或 URL-safe 字母表、有无填充。非法返回 null。
     *
     * ⚠️ 必须先做字符集校验，不能直接把输入丢给 MIME 解码器：
     *    getMimeDecoder 会**静默丢弃**所有非 Base64 字符，于是 "!!!bad!!!" 会被
     *    当作合法 Base64 解出几个字节，进而算出一个看似正常的指纹 ——
     *    这会让"密钥数据损坏"被误判成"密钥正常"，也会污染指纹比对结果。
     */
    static byte[] decodeBase64Lenient(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;

        // 去掉容忍的空白后，剩余字符必须全部属于 Base64 字母表（含填充符 '='）
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '-' || c == '_'
                    || c == '=';
            if (!ok) return null;
            sb.append(c);
        }
        // 去掉填充符后按标准字母表解；URL-safe 的 '-' '_' 先归一化
        String normalized = sb.toString().replace('-', '+').replace('_', '/');
        while (normalized.endsWith("=")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) return null;
        // 去掉填充后长度 %4 == 1 不可能是合法 Base64
        if (normalized.length() % 4 == 1) return null;
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /** 便捷判断：两个字节数组是否相等（null 安全）。 */
    static boolean bytesEqual(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
