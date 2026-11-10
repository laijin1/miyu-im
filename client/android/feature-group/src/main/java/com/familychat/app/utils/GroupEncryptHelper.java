package com.familychat.app.utils;

import android.util.Log;

import com.familychat.app.api.ApiClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群消息"每人一份密文"的加解密助手。
 *
 * 群消息 content 存一个 JSON：{ "成员id": "用该成员公钥加密的密文", ... }。
 * - 发送：对每个成员用各自公钥各加密一份（复用 {@link MessageEncryptor}，一行不改）。
 * - 接收：取自己 uid 那一份，用发送者公钥解密。
 * - 非 JSON 的 content（如系统消息 / [已撤回] / 明文）直接透传。
 *
 * 公钥获取策略（第二轮起）：缓存优先 → 服务端拉取，<b>每一级都必须过 TOFU pin</b>
 * （{@link PeerKeyPinStore}）。与 pin 指纹不一致的成员既不能用它的公钥加密/解密，
 * 也不能覆盖本地缓存 —— 该成员被跳过并记入 {@link EncryptResult#changedMembers}，
 * 由调用方汇总告警；解密方向上返回 {@link #PLACEHOLDER_KEY_CHANGED}。
 *
 * P1-②/P2-⑥ 加固：本地私钥缺失时绝不回退明文（返回 json=null，调用方必须取消发送）；
 * 某成员公钥拉不到时记录到 {@link EncryptResult#skippedMembers}，由调用方提示发送者，
 * 避免"发出去才发现有人收不到"。
 */
public class GroupEncryptHelper {
    private static final String TAG = "GroupE2EE";

    /** content 里没有「我」那一份密文（发送方没为我校密，或群成员变动）。 */
    public static final String PLACEHOLDER_NO_SELF = "[发送者未将你列入加密名单]";
    /** 有「我」那一份，但解不开（私钥缺失 / 发送者公钥对不上）。 */
    public static final String PLACEHOLDER_FAILED = "[消息解密失败，密钥可能已变更]";
    /** 本该是「每人一份密文」JSON，但内容不是合法 envelope（疑似未加密/格式异常）。 */
    public static final String PLACEHOLDER_UNENCRYPTED = "[消息未加密或格式异常]";
    /**
     * 发送者公钥与本地 TOFU pin 不一致（疑似中间人替换）。
     *
     * <p>刻意与 {@link #PLACEHOLDER_FAILED} 区分：普通失败可能只是技术故障，
     * 密钥变更则必须把用户引导去核对安全码，不能混在失败里被忽略。
     */
    public static final String PLACEHOLDER_KEY_CHANGED = "[发送者密钥已变更]";

    private GroupEncryptHelper() {}

    /** 群消息加密结果。 */
    public static class EncryptResult {
        /** {成员id: 密文} 的 JSON；为 null 表示完全无法加密（本地私钥缺失），调用方必须取消发送。 */
        public String json;
        /** 因拉不到公钥 / 加密失败 / 密钥已变更而被跳过的成员 id（这些成员将无法查看这条消息）。 */
        public List<Integer> skippedMembers = new ArrayList<>();
        /** 本地私钥缺失（整条消息都无法加密）。 */
        public boolean ownKeyMissing = false;
        /**
         * {@link #skippedMembers} 的子集：因「公钥与本地 TOFU pin 不一致」被跳过的成员。
         * 与"对方还没设置公钥"是两件事 —— 前者可能是中间人替换，必须单独告警。
         */
        public List<Integer> changedMembers = new ArrayList<>();
        /** {@link #changedMembers} 对应的服务端新公钥（用户选「信任新密钥」后用它 acceptChange + 重新加密）。 */
        public Map<Integer, String> changedMemberKeys = new LinkedHashMap<>();

        /** 是否有成员因为密钥变更而收不到这条消息。 */
        public boolean hasChangedMembers() {
            return !changedMembers.isEmpty();
        }
    }

    /**
     * 为群里每个成员各加密一份明文。
     *
     * @return 加密结果；{@link EncryptResult#json} 为 null 时表示无法加密（绝不回退明文）。
     */
    public static EncryptResult encryptForGroup(String plain, int myUserId,
                                                List<Integer> memberIds,
                                                ApiClient api, PreferencesManager prefs) {
        EncryptResult result = new EncryptResult();
        if (plain == null) {
            result.json = "";
            return result;
        }
        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            Log.e(TAG, "encrypt blocked: own private key missing");
            result.ownKeyMissing = true;
            result.json = null; // 绝不回退明文：调用方应取消发送
            return result;
        }
        Map<String, String> out = new LinkedHashMap<>();
        // 自己那一份：用自己的公钥加密。否则重进群后自己的历史消息里没有自己的条目，
        // 会一直显示 [此消息无法解密]（ECDH 对称，私钥×自己公钥 与 私钥×自己公钥 共享密钥一致）。
        String myPub = prefs.getMyPublicKey();
        if (myPub != null && !myPub.isEmpty()) {
            String selfCipher = MessageEncryptor.encryptMessage(plain, myPriv, myPub);
            if (selfCipher != null) out.put(String.valueOf(myUserId), selfCipher);
        }
        for (int uid : memberIds) {
            if (uid == myUserId) continue;
            // 决策 7：群聊没有"群密钥"，pin 是**逐成员**的。公钥与 pin 不一致的成员
            // 既不能加密给他（密钥可能是攻击者的），也不能覆盖缓存（那会擦掉唯一的信号），
            // 直接跳过 + 记入 changedMembers，由调用方汇总告警（审计 §1.1/§1.2）。
            MemberKey mk = resolveMemberKey(uid, api, prefs);
            if (mk.changedKey != null) {
                Log.w(TAG, "member " + uid + " key CHANGED — skipped, awaiting user confirmation");
                result.skippedMembers.add(uid);
                result.changedMembers.add(uid);
                result.changedMemberKeys.put(uid, mk.changedKey);
                continue;
            }
            String peerPub = mk.key;
            if (peerPub == null) {
                Log.w(TAG, "skip member " + uid + ": no public key");
                result.skippedMembers.add(uid);
                continue;
            }
            String cipher = MessageEncryptor.encryptMessage(plain, myPriv, peerPub);
            if (cipher != null) {
                out.put(String.valueOf(uid), cipher);
            } else {
                Log.w(TAG, "skip member " + uid + ": encrypt failed");
                result.skippedMembers.add(uid);
            }
        }
        try {
            result.json = new com.google.gson.Gson().toJson(out);
        } catch (Exception e) {
            Log.e(TAG, "toJson failed", e);
            result.json = null;
            result.ownKeyMissing = true;
        }
        return result;
    }

    /**
     * 解密一条群消息。content 为 {成员id: 密文} JSON。
     * 取自己那份，用发送者公钥解密；非 JSON / 明文 / [已撤回] 直接透传。
     *
     * <p>发送者是自己时必须用 {@code getMyPublicKey()}：发送时自己那份是用「自己公钥」加密的
     * （见 {@link #encryptForGroup}），自己的 id 永远不会出现在 peer 公钥缓存里，
     * 走 peer 缓存查自己必然未命中。会话列表预览里「最后一条是自己发的」是最常见场景。
     *
     * <p>本方法可能在后台线程做一次公钥拉取，<b>请勿在主线程调用</b>。
     */
    public static String decryptGroupMessage(String content, int senderId,
                                             int myUserId,
                                             ApiClient api, PreferencesManager prefs) {
        return decryptGroupMessage(content, senderId, myUserId, api, prefs, null);
    }

    /**
     * 带消息类型的解密入口。
     *
     * <p>msgType 用于区分“已知明文”（system / recalled）与“本该加密却没有 envelope”的异常内容：
     * 后者返回 {@link #PLACEHOLDER_UNENCRYPTED}，避免把未加密消息静默当正常文本展示。
     * 5 参数重载保留给未传类型的旧调用方（预览/通知/搜索），行为不变。
     */
    public static String decryptGroupMessage(String content, int senderId,
                                             int myUserId,
                                             ApiClient api, PreferencesManager prefs,
                                             String msgType) {
        if (content == null || content.isEmpty()) return content;
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            // 已知明文类型直接透传；旧调用方没传类型，保持兼容；其余明确提示未加密
            if ("system".equals(msgType) || "recalled".equals(msgType)) return content;
            if (msgType == null) return content;
            Log.w(TAG, "content is not an E2EE envelope, msgType=" + msgType + " sender=" + senderId);
            return PLACEHOLDER_UNENCRYPTED;
        }

        JsonObject obj;
        try {
            obj = JsonParser.parseString(trimmed).getAsJsonObject();
        } catch (Exception e) {
            Log.w(TAG, "invalid E2EE envelope, sender=" + senderId);
            return PLACEHOLDER_FAILED;
        }
        if (!obj.has(String.valueOf(myUserId))) {
            Log.w(TAG, "no self entry, sender=" + senderId);
            return PLACEHOLDER_NO_SELF;
        }

        String myCipher = obj.get(String.valueOf(myUserId)).getAsString();
        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            Log.w(TAG, "own private key missing, sender=" + senderId);
            return PLACEHOLDER_FAILED;
        }
        if (!looksLikeCiphertext(myCipher)) return myCipher;

        // 尝试 1：发送者公钥（自己发的消息用自己公钥；ECDH 对称，私钥×自己公钥自洽）
        // 非自己发的必须先过 pin —— peer_pub_ 缓存可能被"拿到服务端回复就覆盖"的路径写过。
        boolean isSelf = (senderId == myUserId);
        String peerPub = isSelf
                ? prefs.getMyPublicKey()
                : PeerKeyPinStore.trustedOrNull(senderId, prefs.getPeerPublicKey(senderId));
        String plain = (peerPub != null)
                ? MessageEncryptor.decryptMessage(myCipher, myPriv, peerPub) : null;
        if (plain != null) return plain;

        // 尝试 2：服务端拉取发送者最新公钥重试一次（自己发的无需拉取，本地公钥即权威）
        if (!isSelf) {
            MemberKey mk = resolveMemberKey(senderId, api, prefs);
            if (mk.changedKey != null) {
                // 密钥与本地 pin 不一致：绝不用它解密、绝不写缓存，也不能静默当成普通失败
                Log.w(TAG, "sender key CHANGED, decryption refused, sender=" + senderId);
                return PLACEHOLDER_KEY_CHANGED;
            }
            String freshPub = mk.key;
            if (freshPub != null && !freshPub.equals(peerPub)) {
                plain = MessageEncryptor.decryptMessage(myCipher, myPriv, freshPub);
                if (plain != null) return plain;
            }
        }
        Log.w(TAG, "decrypt failed, sender=" + senderId);
        return PLACEHOLDER_FAILED;
    }

    /** 成员的「公钥 + pin 判定」结果。 */
    private static final class MemberKey {
        /** 通过 pin 校验、可用于加解密的公钥；不可信或拿不到时为 null。 */
        final String key;
        /** 与本地 pin 不一致的候选公钥（服务端当前值）；仅"密钥已变更"时非 null。 */
        final String changedKey;

        MemberKey(String key, String changedKey) {
            this.key = key;
            this.changedKey = changedKey;
        }
    }

    /**
     * 取某成员的群公钥并过 TOFU pin（审计 §1.1/§1.2）。
     *
     * <p>与历史实现的关键区别：
     * <ul>
     *   <li>缓存值先过 {@link PeerKeyPinStore#trustedOrNull} —— 缓存可能被其它路径覆盖过；</li>
     *   <li>服务端拉到的新键再过 {@link PeerKeyPinStore#check}，CHANGED 时
     *       <b>既不写缓存、也不返回可用公钥</b>，只把候选键交回调用方去告警。</li>
     * </ul>
     *
     * @return 永远不会返回"与 pin 不一致但可以用"的公钥
     */
    private static MemberKey resolveMemberKey(int userId, ApiClient api, PreferencesManager prefs) {
        String cached = PeerKeyPinStore.trustedOrNull(userId, prefs.getPeerPublicKey(userId));
        if (cached != null) return new MemberKey(cached, null);

        String fetched = null;
        try {
            fetched = api.getPublicKey(userId);
        } catch (Exception e) {
            Log.e(TAG, "fetch pubkey failed for user " + userId, e);
        }
        if (fetched == null) return new MemberKey(null, null);

        if (PeerKeyPinStore.check(userId, fetched) == PeerKeyPinStore.PinState.CHANGED) {
            Log.w(TAG, "member key CHANGED, user=" + userId);
            return new MemberKey(null, fetched);
        }
        prefs.savePeerPublicKey(userId, fetched);
        return new MemberKey(fetched, null);
    }

    /** True if content looks like a Base64 E2EE ciphertext. */
    private static boolean looksLikeCiphertext(String content) {
        if (content == null || content.length() < 40) return false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    /** 从成员列表里过滤出除自己外的成员 id。 */
    public static List<Integer> othersOf(List<Integer> memberIds, int myUserId) {
        List<Integer> out = new ArrayList<>();
        if (memberIds == null) return out;
        for (int id : memberIds) if (id != myUserId) out.add(id);
        return out;
    }
}
