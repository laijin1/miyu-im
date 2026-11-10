package com.familychat.app.utils;

import android.util.Log;

import com.familychat.app.api.ApiClient;

/**
 * 会话列表「预览文字」用的一次性解密（单聊）。
 *
 * <p>背景：会话列表（MainActivity.previewFor）历史上直接展示本地库里 {@code MessageDatabase}
 * 的原始 content。而本地库存的是密文（收到的消息先入库后解密；服务端历史同步也会用
 * CONFLICT_REPLACE 覆盖成本地密文），于是列表预览会显示 Base64 乱码。
 *
 * <p>本类只做纯逻辑解密，<b>必须运行在后台线程</b>：缓存公钥未命中时会同步请求服务端拉取
 * 最新公钥，Android 主线程发起网络请求会抛 NetworkOnMainThreadException，导致解密静默失败。
 *
 * <p>与 {@code ChatActivity.maybeDecrypt} 同策略（缓存公钥 → 服务端最新公钥两级重试），
 * 且两级都必须过 TOFU pin（{@link PeerKeyPinStore}）：与 pin 指纹不一致时返回
 * {@link #PLACEHOLDER_KEY_CHANGED} 且绝不写缓存。
 * 额外保证：解码失败时绝不把 Base64 密文当预览展示，统一返回 {@link #PLACEHOLDER_FAILED}。
 *
 * <p>非 text 类型（image/voice/video/file 的 content 是 URL，transfer 是服务端 JSON）
 * 一律原样透传，由调用方按 msg_type 映射成 [图片]/[语音] 等。
 */
public final class PreviewDecryptor {

    private static final String TAG = "E2EE_PREVIEW";

    /** 解密失败时的占位符（绝不显示 Base64 密文）。 */
    public static final String PLACEHOLDER_FAILED = "[无法解密]";

    /**
     * 对方公钥指纹与本地 TOFU pin 不一致时（疑似中间人替换）的占位符。
     *
     * <p>刻意与 {@link #PLACEHOLDER_FAILED} 区分：解密失败是"可能只是技术故障"，
     * 密钥变更是"可能有人在中间替换了公钥"——后者必须把用户引导到聊天页里的告警/安全码，
     * 不能混在普通失败里被忽略。
     */
    public static final String PLACEHOLDER_KEY_CHANGED = "⚠️ 密钥已变更，点击查看";

    private PreviewDecryptor() {}

    /**
     * 解密一条单聊消息的 content 用于预览展示。
     *
     * @param content    本地库里的原始 content（可能是密文 / 明文 / URL / JSON）
     * @param msgType    消息类型；仅 text（或 null）走解密
     * @param peerUserId 聊天对方 id（注意是好友 id，不是发送者 id）
     * @return 明文；非文本类型或本就是明文时原样返回；解不开时返回 {@link #PLACEHOLDER_FAILED}
     */
    public static String decryptForPreview(String content, String msgType,
                                           int peerUserId, ApiClient api, PreferencesManager prefs) {
        if (content == null || content.isEmpty()) return content;
        // 只有文本是 E2EE 密文；图片/语音/视频/文件存的是 URL
        if (msgType != null && !"text".equals(msgType)) return content;

        String plain = content;
        boolean looksEncrypted = looksLikeCiphertext(content);
        if (!looksEncrypted) return content; // 明文（旧数据 / [已撤回] / 系统消息）直通

        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            Log.w(TAG, "own private key missing, peer=" + peerUserId);
            return PLACEHOLDER_FAILED;
        }

        // 尝试 1：缓存的对方公钥（先过 pin —— 缓存可能被其它未收口路径覆盖过）
        String peerPub = PeerKeyPinStore.trustedOrNull(peerUserId, prefs.getPeerPublicKey(peerUserId));
        if (peerPub != null) {
            plain = MessageEncryptor.decryptMessage(content, myPriv, peerPub);
            if (plain != null) return plain;
        }

        // 尝试 2：服务端拉取对方最新公钥重试一次；同样必须先过 pin（TOFU，审计 §1.1/§1.2）
        String freshPub = null;
        try {
            freshPub = api.getPublicKey(peerUserId);
        } catch (Exception e) {
            Log.w(TAG, "fetch pubkey failed, peer=" + peerUserId, e);
        }
        if (freshPub != null) {
            if (PeerKeyPinStore.check(peerUserId, freshPub) == PeerKeyPinStore.PinState.CHANGED) {
                // 指纹与本地 pin 不一致：绝不写缓存、也绝不静默降级成"解密失败"
                //（那会把一次可能的中间人替换显示成普通故障，用户不会去看一眼）。
                Log.w(TAG, "peer key CHANGED — preview refused, peer=" + peerUserId);
                return PLACEHOLDER_KEY_CHANGED;
            }
            prefs.savePeerPublicKey(peerUserId, freshPub);
            if (!freshPub.equals(peerPub)) {
                plain = MessageEncryptor.decryptMessage(content, myPriv, freshPub);
                if (plain != null) return plain;
            }
        }

        Log.w(TAG, "preview decrypt failed, peer=" + peerUserId);
        return PLACEHOLDER_FAILED;
    }

    /**
     * 内容是否像 E2EE 密文：Base64（含 +/=）且长度 >= 40。
     * （12 字节 IV + GCM 密文+tag，Base64 后必然远超 40 字符；明文几乎不可能命中。）
     */
    public static boolean looksLikeCiphertext(String content) {
        if (content == null || content.length() < 40) return false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }
}
