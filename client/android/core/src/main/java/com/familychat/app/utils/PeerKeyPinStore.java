package com.familychat.app.utils;

import android.util.Log;

/**
 * E2EE TOFU（Trust On First Use）对端公钥 pin 库 —— 第一层：本地 pin + 变化告警。
 *
 * <p>要解决的问题（见 {@code docs/audits/E2EE_SECURITY_AUDIT.md} §1.1 Critical / §1.2 High）：
 * 服务端 {@code GET /api/user/publickey} 把任意用户的公钥原样下发，客户端历史上
 * "拿到就用"——既不算指纹也不比对。于是控制服务端（或局域网、或拿到任一有效 token）
 * 的人把对方公钥换成自己的，双方看到的仍是"加密成功"，而他全看得到。
 *
 * <p>本类给对端公钥加一层本地记忆：首次见到即信任并记下 {@code SHA-256(SPKI)} 指纹，
 * 之后指纹只要变化就必须由用户显式确认，否则由调用方阻断发送（fail-closed）。
 *
 * <p><b>三层能力（按轮次落地）</b>：
 * <ol>
 *   <li>第一轮：pin 的三态判定 + 存取；变化时由调用方阻断发送、弹告警，
 *       用户确认后调 {@link #acceptChange}（单聊）；</li>
 *   <li>第二轮：{@link #trustedOrNull} 抽为公共读出口（预览 / 搜索 / 群聊共用），
 *       群聊逐成员 pin；</li>
 *   <li>第三轮：{@link #markVerified} —— 用户当面互扫安全码二维码后置位"已核对"
 *       （{@link SafetyNumberPayload}）。</li>
 * </ol>
 * <b>仍不做</b>：服务端 {@code public_key_history}（审计 §1.1 的第二条 Fix，需改服务端）。
 *
 * <p><b>存储契约</b>（与 {@link PreferencesManager} 的分工）：
 * <ul>
 *   <li>持久化用前缀 {@code peer_pin_<uid>}，<b>刻意避开</b> {@code peer_pub_*}：
 *       {@code PreferencesManager.logout()} 会清空 {@code peer_pub_*}，而 pin 必须
 *       活过退出登录——否则重新登录后所有对端都会被当成"首次见到"，TOFU 的威胁模型
 *       被静默摧毁。</li>
 *   <li>pin 不按"我的账号"分区（决策 3）：与现有 {@code peer_pub_*} 对端缓存一致，
 *       同设备多账号共用一份对端公钥记忆。</li>
 *   <li>同一份记录里同时保存"用户是否显式确认过"：首次信任（TOFU）为 {@code false}，
 *       用户点过"信任新密钥"为 {@code true}。本批不区分两者，字段留给第二轮
 *       （密钥变化后要能区分"从未人工核对"与"用户已确认过变更"）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：{@link #LOCK} 保护全部状态；方法内部只做 prefs 读写
 * （{@code EncryptedSharedPreferences}，极快），不联网、不碰 UI，
 * 可从任意线程调用。
 */
public final class PeerKeyPinStore {

    private static final String TAG = "TOFU_PIN";

    /** pin 判定结果。 */
    public enum PinState {
        /** 本地没有该对端的 pin —— 本次为其"首次信任"，调用方视同正常。 */
        UNKNOWN,
        /** 有 pin 且与本次看到的公钥指纹一致。 */
        MATCH,
        /** 有 pin 但与本次看到的公钥指纹不一致 —— 调用方必须阻断并让用户确认。 */
        CHANGED
    }

    /** 一条 pin 记录。不可变。 */
    public static final class PinRecord {
        /** 完整指纹：{@code SHA-256(SPKI)} 全部 32 字节的小写 hex（64 字符）。 */
        public final String fingerprint;
        /** 该指纹首次被记下的时间（epoch millis）。 */
        public final long firstSeenAt;
        /** 用户是否显式确认过这把钥匙（首次信任为 false）。 */
        public final boolean userConfirmed;

        public PinRecord(String fingerprint, long firstSeenAt, boolean userConfirmed) {
            this.fingerprint = fingerprint;
            this.firstSeenAt = firstSeenAt;
            this.userConfirmed = userConfirmed;
        }

        @Override
        public String toString() {
            return "PinRecord{fp=" + displayCode(fingerprint)
                    + ", firstSeenAt=" + firstSeenAt + ", userConfirmed=" + userConfirmed + "}";
        }
    }

    /**
     * 持久化后端。生产实现走 {@link PreferencesManager}；
     * 单测注入内存实现（纯 JVM，不需要 Android 运行时）。
     */
    public interface PinBackend {
        /** @return 该对端的 pin；没有则 null。 */
        PinRecord load(int peerUserId);

        /** 写入/覆盖该对端的 pin。 */
        void save(int peerUserId, PinRecord record);

        /** 删除该对端的 pin。 */
        void remove(int peerUserId);
    }

    private static final Object LOCK = new Object();

    /** 生产后端（{@link #install} 时建立），仅用于 install 幂等判断。 */
    private static volatile PinBackend prefsBackend;
    /** 当前生效的后端；null = 尚未 install（单测用 setBackendForTest 注入）。 */
    private static volatile PinBackend backend;

    private PeerKeyPinStore() {
    }

    /**
     * 安装生产后端（使用 {@link PreferencesManager} 的加密存储）。
     * 幂等，可在 {@code Application.onCreate} 里调用一次（与 {@code AppLog.init} 同款）。
     */
    public static void install(PreferencesManager prefs) {
        if (prefs == null) return;
        synchronized (LOCK) {
            if (prefsBackend == null) prefsBackend = new PrefsPinBackend(prefs);
            backend = prefsBackend;
            Log.d(TAG, "installed (EncryptedSharedPreferences backend)");
        }
    }

    /**
     * 单测专用：注入内存后端。传 null 等价于恢复"未安装"状态。
     * 生产代码不得调用（本方法仅为了让纯 JVM 单测覆盖三态状态机）。
     */
    public static void setBackendForTest(PinBackend testBackend) {
        synchronized (LOCK) {
            backend = testBackend;
        }
    }

    /**
     * TOFU 校验：拿本次看到的公钥与本地 pin 比对。
     *
     * <ul>
     *   <li>{@code UNKNOWN}：本地没有 pin → 记下指纹（首次信任）后返回。调用方视同正常。</li>
     *   <li>{@code MATCH}：指纹一致 → 正常。 <b>不刷新 firstSeenAt</b>（它表示"这把钥匙何时
     *       第一次出现"，不是"最近一次见到"）。</li>
     *   <li>{@code CHANGED}：指纹不一致 → <b>不更新 pin</b>，返回 CHANGED 由调用方阻断；
     *       只有用户显式确认后调用 {@link #acceptChange} 才会覆盖。</li>
     * </ul>
     *
     * @param newPublicKey X509 SPKI 的 Base64 文本（与 {@code peer_pub_<uid>} 里存的是同一种值）
     */
    public static PinState check(int peerUserId, String newPublicKey) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) {
                // 生产路径不可达（Application.onCreate 已 install）。真发生时不阻断用户：
                // 阻断会造成"完全发不出消息"且无从排查，而日志与 UI 都无从提示。
                Log.e(TAG, "backend not installed — TOFU pin skipped, peer=" + peerUserId);
                return PinState.UNKNOWN;
            }

            String fp = fingerprintOf(newPublicKey);
            if (fp.isEmpty()) {
                // 公钥无法解码/长度非法：算不出指纹就绝不能写入（空指纹会把后续所有公钥
                // 都判成"变化"）。已有 pin 时按"变化"处理——宁可多问一次。
                PinRecord existing = safeLoad(b, peerUserId);
                Log.w(TAG, "cannot fingerprint peer key, peer=" + peerUserId
                        + ", hasPin=" + (existing != null));
                return existing != null ? PinState.CHANGED : PinState.UNKNOWN;
            }

            PinRecord rec = safeLoad(b, peerUserId);
            if (rec == null) {
                b.save(peerUserId, new PinRecord(fp, System.currentTimeMillis(), false));
                Log.d(TAG, "TOFU first use: pinned peer=" + peerUserId + " fp=" + displayCode(fp));
                return PinState.UNKNOWN;
            }
            if (fp.equals(rec.fingerprint)) return PinState.MATCH;

            Log.w(TAG, "peer key CHANGED: peer=" + peerUserId
                    + " pinned=" + displayCode(rec.fingerprint)
                    + " new=" + displayCode(fp));
            return PinState.CHANGED;
        }
    }

    /**
     * 用户显式确认"信任新密钥"后调用：把 pin 更新为新公钥的指纹。
     *
     * <p>只有本方法会覆盖已存在的 pin，{@link #check} 永远不会。
     * 覆盖算不出指纹的公钥会被拒绝（避免写入空指纹）。
     */
    public static void acceptChange(int peerUserId, String newPublicKey) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) {
                Log.e(TAG, "backend not installed — acceptChange skipped, peer=" + peerUserId);
                return;
            }
            String fp = fingerprintOf(newPublicKey);
            if (fp.isEmpty()) {
                Log.e(TAG, "acceptChange refused: cannot fingerprint new key, peer=" + peerUserId);
                return;
            }
            b.save(peerUserId, new PinRecord(fp, System.currentTimeMillis(), true));
            Log.w(TAG, "user accepted CHANGED key: peer=" + peerUserId + " fp=" + displayCode(fp));
        }
    }

    /**
     * 该对端已 pin 的指纹（64 位全码）。
     *
     * @return 无 pin 时返回 {@code null}（注意与"指纹算不出来"的空串区分）
     */
    public static String getPinnedFingerprint(int peerUserId) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) return null;
            PinRecord rec = safeLoad(b, peerUserId);
            return (rec == null || rec.fingerprint == null || rec.fingerprint.isEmpty())
                    ? null : rec.fingerprint;
        }
    }

    /** 该对端是否已经有人工确认过的 pin（安全码页"已核对"标识、群聊告警文案都用它）。 */
    public static boolean isUserConfirmed(int peerUserId) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) return false;
            PinRecord rec = safeLoad(b, peerUserId);
            return rec != null && rec.userConfirmed;
        }
    }

    /**
     * 用户通过**带外方式**（当面扫安全码二维码）核对过该对端公钥后调用。
     *
     * <p>与 {@link #acceptChange} 的分工：{@code acceptChange} 是"密钥变了，我确认换成新的"；
     * 本方法是"密钥没变，我核对过它确实是对的"。两者都置位 {@link PinRecord#userConfirmed}，
     * 因为对用户而言都是"我已人工确认过这把钥匙"。
     *
     * <p><b>为什么必须传入指纹而不是无参调用</b>：只有
     * {@code expectedFullFingerprint} 与本地 pin 的指纹**完全相等**时才置位。
     * 这样即便调用方传错参数、或将来某条路径被绕过，也不可能把一把<b>不匹配</b>的钥匙
     * 标成"已核对"——那正是本方法要防的事。
     *
     * <p><b>不修改</b> {@code fingerprint} 与 {@code firstSeenAt}：核对不改变"这把钥匙是什么、
     * 何时第一次出现"。已置位时幂等返回 {@code true}。
     *
     * @param expectedFullFingerprint 扫码得到并已与 pin 比对过的 64 位全码指纹
     * @return 是否处于"已核对"状态；无 pin / 指纹不匹配 / 参数为空 / 后端未安装时返回 {@code false}
     */
    public static boolean markVerified(int peerUserId, String expectedFullFingerprint) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) {
                Log.e(TAG, "backend not installed — markVerified skipped, peer=" + peerUserId);
                return false;
            }
            if (expectedFullFingerprint == null || expectedFullFingerprint.isEmpty()) return false;

            PinRecord rec = safeLoad(b, peerUserId);
            if (rec == null) {
                Log.w(TAG, "markVerified refused: no pin for peer=" + peerUserId);
                return false;
            }
            if (!expectedFullFingerprint.equals(rec.fingerprint)) {
                Log.w(TAG, "markVerified refused: fingerprint mismatch, peer=" + peerUserId);
                return false;
            }
            if (rec.userConfirmed) return true; // 幂等
            try {
                b.save(peerUserId, new PinRecord(rec.fingerprint, rec.firstSeenAt, true));
            } catch (Throwable t) {
                Log.e(TAG, "markVerified save failed, peer=" + peerUserId, t);
                return false;
            }
            Log.d(TAG, "peer marked verified: peer=" + peerUserId + " fp=" + displayCode(rec.fingerprint));
            return true;
        }
    }

    /**
     * 从"可能被污染的缓存值"里筛出一把可信公钥。
     *
     * <p>为什么需要它：{@code peer_pub_<uid>} 只是网络缓存，历史上（以及至今仍在的群聊 /
     * 预览路径）存在多处"拿到服务端回复就无条件覆盖缓存"的写法。只要缓存被替换一次，
     * 任何"只信缓存"的解密/加密入口都会被绕过。所以<b>凡是要用缓存里的公钥，都必须先过这道门</b>。
     *
     * <ul>
     *   <li>空值 → {@code null}</li>
     *   <li>无 pin（UNKNOWN）→ 返回缓存值（首次信任；pin 由 {@link #check} 顺手记下）</li>
     *   <li>有 pin 且指纹一致（MATCH）→ 返回缓存值</li>
     *   <li>有 pin 且指纹不同（CHANGED）→ {@code null}，调用方必须走"重拉 + 告警/占位"分支，
     *       不得用这把钥匙加密或解密</li>
     *   <li>算不出指纹（无法解码）→ {@code null}：这类值既 pin 不住也检测不到替换</li>
     * </ul>
     *
     * <p>与第一轮 {@code ChatActivity.trustedOrNull} 的语义完全一致，本轮抽到 core 供
     * 单聊 / 会话列表预览 / 本地搜索 / 群聊四条路径共用同一套判定。
     *
     * @return 可信公钥；不可信、为空或算不出指纹时返回 {@code null}
     */
    public static String trustedOrNull(int peerUserId, String cachedKey) {
        if (cachedKey == null || cachedKey.isEmpty()) return null;
        // 算不出指纹的键既无法 pin、也无法检测后续被替换 —— 一律不可信（宁可走重拉）
        if (fingerprintOf(cachedKey).isEmpty()) {
            Log.w(TAG, "cached key is not decodable — treated as untrusted, peer=" + peerUserId);
            return null;
        }
        if (check(peerUserId, cachedKey) == PinState.CHANGED) {
            Log.w(TAG, "cached key no longer matches pin, peer=" + peerUserId);
            return null;
        }
        return cachedKey;
    }

    /** 清除该对端的 pin（下一次见面视为"首次信任"）。 */
    public static void clear(int peerUserId) {
        synchronized (LOCK) {
            PinBackend b = backend;
            if (b == null) return;
            try {
                b.remove(peerUserId);
            } catch (Throwable t) {
                Log.e(TAG, "clear failed, peer=" + peerUserId, t);
            }
        }
    }

    /**
     * 公钥指纹：{@code SHA-256(X509 SPKI DER)} 全部 32 字节的小写 hex（64 字符）。
     *
     * <p>算法与「设置 → 密钥管理」页显示的指纹完全同一套（都走
     * {@link KeyTransferPayload}），否则会出现"页面显示不一致但判定一致"的自相矛盾。
     *
     * <p>为什么 pin 用全码而不是页面上那 8 位短码：短码只有 32 bit，
     * 攻击者试算约 2^32 次就能造出一把短码相同的钥匙，不足以固定密钥。
     *
     * @return 64 位小写 hex；入参为空或无法解码时返回空串（不抛异常）
     */
    public static String fingerprintOf(String base64PublicKey) {
        byte[] spki = decodeSpki(base64PublicKey);
        if (spki == null || spki.length == 0) return "";
        return KeyTransferPayload.fingerprintFullHex(spki);
    }

    /**
     * UI 显示的短码：全码前 4 字节（8 位 hex）按 4 位分组，例如 {@code "1348 9a1b"}。
     *
     * <p>⚠️ 仅供人眼近似核对；pin 判定一律用 64 位全码（见 {@link #fingerprintOf}）。
     *
     * @return 分组后的短码；入参不足 8 位时返回空串
     */
    public static String displayCode(String fullFingerprint) {
        if (fullFingerprint == null || fullFingerprint.length() < 8) return "";
        return KeyTransferPayload.group4(fullFingerprint.substring(0, 8));
    }

    // ===== 内部 =====

    /** load 不得让异常冒泡到聊天发送路径。 */
    private static PinRecord safeLoad(PinBackend b, int peerUserId) {
        try {
            return b.load(peerUserId);
        } catch (Throwable t) {
            Log.e(TAG, "load failed, peer=" + peerUserId, t);
            return null;
        }
    }

    /**
     * 把 Base64 公钥解码成 SPKI DER 字节。
     *
     * <p>优先复用 {@link KeyTransferPayload} 的宽松解码器（纯 JVM，单测走这条，
     * 保证与既有指纹体系逐字节一致）。它在 API 24/25 上会因 {@code java.util.Base64}
     * 不存在而抛 {@link NoClassDefFoundError}（{@code Error}，不是 {@code Exception}），
     * 因此这里兜一层平台解码——本类不能因为"算个指纹"把发送路径搞崩。
     */
    private static byte[] decodeSpki(String base64PublicKey) {
        if (base64PublicKey == null || base64PublicKey.isEmpty()) return null;
        try {
            byte[] der = KeyTransferPayload.decodeBase64Lenient(base64PublicKey);
            if (der != null && der.length > 0) return der;
        } catch (Throwable ignored) {
            // 落到下面的平台解码
        }
        try {
            return android.util.Base64.decode(base64PublicKey, android.util.Base64.DEFAULT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 生产后端：{@link PreferencesManager} 的 {@code peer_pin_<uid>} 存储。 */
    private static final class PrefsPinBackend implements PinBackend {
        private final PreferencesManager prefs;

        PrefsPinBackend(PreferencesManager prefs) {
            this.prefs = prefs;
        }

        @Override
        public PinRecord load(int peerUserId) {
            return prefs.getPeerPin(peerUserId);
        }

        @Override
        public void save(int peerUserId, PinRecord record) {
            if (record == null) return;
            prefs.savePeerPin(peerUserId, record.fingerprint, record.firstSeenAt, record.userConfirmed);
        }

        @Override
        public void remove(int peerUserId) {
            prefs.removePeerPin(peerUserId);
        }
    }
}
