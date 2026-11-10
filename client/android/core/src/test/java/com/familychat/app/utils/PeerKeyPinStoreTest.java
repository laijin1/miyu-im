package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * TOFU pin 状态机回归测试（纯 JVM，无需模拟器）。
 *
 * <p>为什么这段必须测：pin 是「加密聊天」名不副实这件事的唯一补救点。
 * 三态判定一旦写错，后果完全不对称 ——
 * <ul>
 *   <li>把 CHANGED 判成 MATCH/UNKNOWN → 中间人替换公钥后一切照旧，安全能力形同虚设；</li>
 *   <li>把 MATCH 判成 CHANGED → 用户每次发消息都被弹窗拦住，最后必然麻木点"信任"，
 *       等于把安全提示训练成噪音。</li>
 * </ul>
 * 所以这里既钉死三态，也钉死"check 永不覆盖已有 pin、只有 acceptChange 才覆盖"这条不变量。
 *
 * <p>指纹算法的黄金向量复用 {@link KeyTransferPayloadTest} 里那条由独立工具（.NET ECDsa）
 * 生成的真实 P-256 公钥，确保 TOFU 的指纹与「设置 → 密钥管理」页显示的是同一套算法。
 */
public class PeerKeyPinStoreTest {

    /** 与 KeyTransferPayloadTest 相同的独立工具黄金向量（真实 P-256 X509 SPKI，91 字节）。 */
    private static final String KEY_A_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6LIBZZP5lYP8mqyCOqg2MaopFmQTgumFTXrINnNGWWXGH1jBDyNgrAx1RWO8oQAL+HSsvV94KiY1VOj5HyQ==";
    /** 同一把钥匙的 SHA-256(SPKI) 全码（独立工具算出）。 */
    private static final String KEY_A_FULL_FP =
            "13489a1bdfedf680658c0491b56879fe59cfedd59e842625e3b5d15f16afad8f";
    /** 短码 = 全码前 4 字节。 */
    private static final String KEY_A_SHORT_FP = "13489a1b";

    private static final int PEER = 4242;

    private InMemoryBackend backend;

    @Before
    public void setUp() {
        backend = new InMemoryBackend();
        PeerKeyPinStore.setBackendForTest(backend);
    }

    @After
    public void tearDown() {
        PeerKeyPinStore.setBackendForTest(null);
    }

    // ===== 三态 =====

    @Test
    public void firstSight_isUnknown_andPinsKey() {
        assertEquals("首次见到某公钥必须判为 UNKNOWN（首次信任）",
                PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, KEY_A_B64));

        PeerKeyPinStore.PinRecord rec = backend.map.get(PEER);
        assertNotNull("首次信任必须落 pin（否则下次见面还是 UNKNOWN，TOFU 不存在）", rec);
        assertEquals("pin 里存的必须是 64 位全码指纹", KEY_A_FULL_FP, rec.fingerprint);
        assertTrue("firstSeenAt 必须是有效时间戳", rec.firstSeenAt > 0);
        assertFalse("首次信任不可能是「用户已确认」", rec.userConfirmed);
    }

    @Test
    public void sameKey_isMatch_andFirstSeenAtNotRefreshed() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        long firstSeen = backend.map.get(PEER).firstSeenAt;

        assertEquals(PeerKeyPinStore.PinState.MATCH, PeerKeyPinStore.check(PEER, KEY_A_B64));
        assertEquals("MATCH 不得刷新 firstSeenAt（它表示「这把钥匙何时第一次出现」）",
                firstSeen, backend.map.get(PEER).firstSeenAt);
    }

    @Test
    public void differentKey_isChanged_andPinNotOverwritten() throws Exception {
        PeerKeyPinStore.check(PEER, KEY_A_B64);

        String otherKey = newKeyBase64();
        assertNotEquals("测试自身前提：两把钥匙的指纹必须不同",
                KEY_A_FULL_FP, PeerKeyPinStore.fingerprintOf(otherKey));

        assertEquals("公钥换了必须判 CHANGED（这是中间人替换的唯一信号）",
                PeerKeyPinStore.PinState.CHANGED, PeerKeyPinStore.check(PEER, otherKey));
        assertEquals("check 绝不允许覆盖已有 pin —— 否则变化信号被立刻擦掉",
                KEY_A_FULL_FP, backend.map.get(PEER).fingerprint);
    }

    @Test
    public void acceptChange_switchesPin_andThenMatches() throws Exception {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        String otherKey = newKeyBase64();
        PeerKeyPinStore.check(PEER, otherKey); // CHANGED

        PeerKeyPinStore.acceptChange(PEER, otherKey);

        PeerKeyPinStore.PinRecord rec = backend.map.get(PEER);
        assertNotNull(rec);
        assertEquals(PeerKeyPinStore.fingerprintOf(otherKey), rec.fingerprint);
        assertTrue("用户确认过的 pin 必须留下 userConfirmed 标记", rec.userConfirmed);
        assertEquals("确认之后同一把钥匙必须判 MATCH",
                PeerKeyPinStore.PinState.MATCH, PeerKeyPinStore.check(PEER, otherKey));
        assertEquals("确认之后旧钥匙相对新 pin 就是 CHANGED（不能双向都算 MATCH）",
                PeerKeyPinStore.PinState.CHANGED, PeerKeyPinStore.check(PEER, KEY_A_B64));
    }

    @Test
    public void clear_resetsToUnknown() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        PeerKeyPinStore.clear(PEER);
        assertNull("clear 之后本地不应再有 pin", backend.map.get(PEER));
        assertEquals("clear 之后同一把钥匙重新算「首次信任」",
                PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, KEY_A_B64));
    }

    // ===== trustedOrNull（第二轮抽到 core 的公共入口）=====

    @Test
    public void trustedOrNull_returnsKeyWhenNoPinYet() {
        // UNKNOWN：首次见到 → 视同可信（pin 由 check 顺手记下），否则新装用户永远发不出消息
        assertEquals(KEY_A_B64, PeerKeyPinStore.trustedOrNull(PEER, KEY_A_B64));
        assertNotNull("trustedOrNull 在 UNKNOWN 时也应落 pin", backend.map.get(PEER));
    }

    @Test
    public void trustedOrNull_returnsKeyWhenMatchesPin() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        assertEquals(KEY_A_B64, PeerKeyPinStore.trustedOrNull(PEER, KEY_A_B64));
    }

    @Test
    public void trustedOrNull_returnsNullOrChangedKey() throws Exception {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        String contaminated = newKeyBase64();
        assertNull("缓存里的公钥与 pin 不一致时必须判为不可信（否则缓存污染即可绕过 pin）",
                PeerKeyPinStore.trustedOrNull(PEER, contaminated));
        assertEquals("trustedOrNull 不得覆盖已有 pin",
                KEY_A_FULL_FP, backend.map.get(PEER).fingerprint);
    }

    @Test
    public void trustedOrNull_handlesEmptyAndNull() {
        assertNull(PeerKeyPinStore.trustedOrNull(PEER, null));
        assertNull(PeerKeyPinStore.trustedOrNull(PEER, ""));
        assertNull("无 pin 时也算不出指纹 → 不可信，不能拿它去加解密",
                PeerKeyPinStore.trustedOrNull(PEER, "!!!not-base64!!!"));
    }

    // ===== markVerified（第三轮：带外扫码核对）=====

    @Test
    public void markVerified_matchingFingerprint_setsConfirmedWithoutTouchingPin() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        long firstSeen = backend.map.get(PEER).firstSeenAt;

        assertTrue("指纹与 pin 一致时必须置位成功",
                PeerKeyPinStore.markVerified(PEER, KEY_A_FULL_FP));

        PeerKeyPinStore.PinRecord rec = backend.map.get(PEER);
        assertTrue("核对通过后应处于「已核对」状态", rec.userConfirmed);
        assertTrue(PeerKeyPinStore.isUserConfirmed(PEER));
        assertEquals("核对不得改变 pin 的指纹", KEY_A_FULL_FP, rec.fingerprint);
        assertEquals("核对不得刷新 firstSeenAt", firstSeen, rec.firstSeenAt);
    }

    @Test
    public void markVerified_wrongFingerprint_refusesAndLeavesPinUntouched() throws Exception {
        PeerKeyPinStore.check(PEER, KEY_A_B64);

        // 扫到的码属于另一把钥匙（例如攻击者出示自己的码）
        String attackerKey = newKeyBase64();
        assertFalse("指纹不匹配绝不能置位",
                PeerKeyPinStore.markVerified(PEER, PeerKeyPinStore.fingerprintOf(attackerKey)));
        assertFalse(backend.map.get(PEER).userConfirmed);
        assertEquals(KEY_A_FULL_FP, backend.map.get(PEER).fingerprint);

        // 8 位短码不是全码，不能被当成"匹配"（否则短码碰撞即可伪造已核对）
        assertFalse("短码不得当作全码使用",
                PeerKeyPinStore.markVerified(PEER, KEY_A_SHORT_FP));
        assertFalse(backend.map.get(PEER).userConfirmed);
    }

    @Test
    public void markVerified_isIdempotent() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        assertTrue(PeerKeyPinStore.markVerified(PEER, KEY_A_FULL_FP));
        assertTrue("重复核对应幂等返回 true", PeerKeyPinStore.markVerified(PEER, KEY_A_FULL_FP));
        assertTrue(PeerKeyPinStore.isUserConfirmed(PEER));
    }

    @Test
    public void markVerified_withoutPinOrWithoutArgument_returnsFalse() {
        assertFalse("没有 pin 时不能凭空置位（还没和对方聊过）",
                PeerKeyPinStore.markVerified(PEER, KEY_A_FULL_FP));
        assertNull(backend.map.get(PEER));

        PeerKeyPinStore.check(PEER, KEY_A_B64);
        assertFalse(PeerKeyPinStore.markVerified(PEER, null));
        assertFalse(PeerKeyPinStore.markVerified(PEER, ""));
        assertFalse("后端未安装时也不能崩，只能返回 false",
                markVerifiedWithoutBackend());
    }

    /** 后端未安装（生产不可达，但本方法跑在 UI 路径上，钉死"不崩"）。 */
    private boolean markVerifiedWithoutBackend() {
        PeerKeyPinStore.setBackendForTest(null);
        boolean r = PeerKeyPinStore.markVerified(PEER, KEY_A_FULL_FP);
        PeerKeyPinStore.setBackendForTest(backend);
        return r;
    }

    // ===== 与既有指纹体系一致 =====

    @Test
    public void fingerprintOf_matchesKeyTransferPayloadAndGoldenVector() {
        assertEquals("TOFU pin 的指纹必须与独立工具(.NET SHA256)一致",
                KEY_A_FULL_FP, PeerKeyPinStore.fingerprintOf(KEY_A_B64));

        byte[] spki = Base64.getMimeDecoder().decode(KEY_A_B64);
        assertEquals("必须与 KeyTransferPayload 用完全同一套算法（否则页面显示与判定会自相矛盾）",
                KeyTransferPayload.fingerprintFullHex(spki), PeerKeyPinStore.fingerprintOf(KEY_A_B64));
        assertEquals("pin 存的指纹长度必须是 64 位 hex，不能退化成 8 位短码",
                64, PeerKeyPinStore.fingerprintOf(KEY_A_B64).length());
    }

    @Test
    public void displayCode_isGroupedShortCode_ofSameAlgorithm() {
        assertEquals("1348 9a1b", PeerKeyPinStore.displayCode(KEY_A_FULL_FP));
        assertEquals(KEY_A_SHORT_FP, KeyTransferPayload.fingerprintShortOfBase64(KEY_A_B64));
        assertTrue("displayCode 必须与短码算法同源（前 4 字节）",
                PeerKeyPinStore.displayCode(KEY_A_FULL_FP).replace(" ", "").equals(KEY_A_SHORT_FP));
    }

    // ===== 退化与异常输入 =====

    @Test
    public void undecodableKey_neverPoisonsThePin() {
        // 没有 pin：算不出指纹 → 不写记录（写空指纹会让之后每个公钥都被判"变化"）
        assertEquals(PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, "!!!not-base64!!!"));
        assertNull("无法解码的公钥绝不能落 pin", backend.map.get(PEER));

        // 有 pin：算不出指纹 → 按"变化"处理（宁可多问一次），且不动已有 pin
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        assertEquals(PeerKeyPinStore.PinState.CHANGED,
                PeerKeyPinStore.check(PEER, "!!!not-base64!!!"));
        assertEquals(KEY_A_FULL_FP, backend.map.get(PEER).fingerprint);
    }

    @Test
    public void acceptChange_refusesUndecodableKey() {
        PeerKeyPinStore.check(PEER, KEY_A_B64);
        PeerKeyPinStore.acceptChange(PEER, "!!!not-base64!!!");
        assertEquals("确认一把无法解码的公钥不得写入 pin",
                KEY_A_FULL_FP, backend.map.get(PEER).fingerprint);
    }

    @Test
    public void nullAndEmptyKeys_areHandledWithoutThrowing() {
        assertEquals(PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, null));
        assertEquals(PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, ""));
        assertEquals("", PeerKeyPinStore.fingerprintOf(null));
        assertEquals("", PeerKeyPinStore.fingerprintOf(""));
        assertEquals("", PeerKeyPinStore.displayCode(null));
        assertEquals("", PeerKeyPinStore.displayCode("abc"));
        assertNull("没有 pin 时 getPinnedFingerprint 必须返回 null（与空串区分）",
                PeerKeyPinStore.getPinnedFingerprint(PEER));
    }

    @Test
    public void withoutBackend_doesNotThrowAndDoesNotBlockUser() {
        // 生产环境由 Application.onCreate 装后端；这里钉死"没装后端也不能崩"这一点，
        // 因为本方法跑在聊天发送路径上。
        PeerKeyPinStore.setBackendForTest(null);
        assertEquals(PeerKeyPinStore.PinState.UNKNOWN, PeerKeyPinStore.check(PEER, KEY_A_B64));
        assertNull(PeerKeyPinStore.getPinnedFingerprint(PEER));
        PeerKeyPinStore.acceptChange(PEER, KEY_A_B64);
        PeerKeyPinStore.clear(PEER);
    }

    @Test
    public void pinIsScopedPerPeer() throws Exception {
        String otherKey = newKeyBase64();
        PeerKeyPinStore.check(1, KEY_A_B64);
        PeerKeyPinStore.check(2, otherKey);
        assertEquals(PeerKeyPinStore.PinState.MATCH, PeerKeyPinStore.check(1, KEY_A_B64));
        assertEquals(PeerKeyPinStore.PinState.MATCH, PeerKeyPinStore.check(2, otherKey));
        assertEquals("pin 必须按对端隔离，不能串号",
                PeerKeyPinStore.PinState.CHANGED, PeerKeyPinStore.check(1, otherKey));
    }

    // ===== 辅助 =====

    /** 运行时生成一把真实 P-256 公钥（X509 SPKI）的 Base64，避免写死第二把钥匙的字节。 */
    private static String newKeyBase64() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());
    }

    /** 内存后端：单测不想碰 EncryptedSharedPreferences / Keystore。 */
    private static final class InMemoryBackend implements PeerKeyPinStore.PinBackend {
        final Map<Integer, PeerKeyPinStore.PinRecord> map = new HashMap<>();

        @Override
        public PeerKeyPinStore.PinRecord load(int peerUserId) {
            return map.get(peerUserId);
        }

        @Override
        public void save(int peerUserId, PeerKeyPinStore.PinRecord record) {
            map.put(peerUserId, record);
        }

        @Override
        public void remove(int peerUserId) {
            map.remove(peerUserId);
        }
    }
}
