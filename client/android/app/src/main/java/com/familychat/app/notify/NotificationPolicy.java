package com.familychat.app.notify;

/**
 * 通知派发的<b>纯决策</b>：不碰 Android API、不读盘、不发通知，因此可在纯 JVM 单测里全覆盖。
 *
 * <h3>为什么单独拆出来</h3>
 * 这些规则原来散在 {@code MainActivity.createWsListener()} 的 if 里，和 UI 刷新、解密、
 * 发通知糊在一起，既测不了也看不清。抽成纯函数后，「正在聊天不打扰」「免打扰群不弹」
 * 「系统消息不弹」「未读怎么加」都能被 {@code NotificationPolicyTest} 钉死；
 * {@code NotificationDispatcher} 只负责把判定结果落到真实副作用上。
 *
 * <h3>与旧行为的逐条对应（改动这些方法＝改产品行为，必须同时改测试）</h3>
 * <ul>
 *   <li>好友消息：{@code currentFriendId != friendId} 时<b>同时</b>累计未读 + 弹通知
 *       （旧代码就在同一个 if 里，保持一致）。</li>
 *   <li>群消息：{@code currentGroupId != groupId} 时累计未读（<b>包含 system 消息</b>，
 *       旧行为如此）；其中仅「非 system 且非免打扰」才弹通知。</li>
 * </ul>
 */
public final class NotificationPolicy {

    /** 查不到昵称时的兜底标题（旧实现会弹一个空标题，用户看到空白通知）。 */
    public static final String FALLBACK_FRIEND_TITLE = "好友";
    /** 查不到群名时的兜底标题。 */
    public static final String FALLBACK_GROUP_TITLE = "群消息";

    private NotificationPolicy() {
    }

    /**
     * 该会话的消息是否要计入未读：只有"当前不在这个会话里"才算。
     *
     * @param currentChatId 当前打开的会话 id（好友 id 或群 id）；没有打开任何会话时为 -1
     * @param conversationId 这条消息所属会话 id
     */
    public static boolean shouldCountUnread(int currentChatId, int conversationId) {
        if (conversationId <= 0) return false;   // 异常/缺字段的推送不污染未读
        return currentChatId != conversationId;
    }

    /**
     * 好友新消息是否要弹通知。
     *
     * <p>与群侧 {@link #shouldNotifyGroup} 同序：先免打扰，再交给 {@link #shouldCountUnread}
     * —— 后者同时负责"正在该好友会话里不弹"与"id&lt;=0 的脏推送不弹"两个条件。
     *
     * @param quiet 该好友是否免打扰
     */
    public static boolean shouldNotifyFriend(int currentFriendId, int friendId, boolean quiet) {
        if (quiet) return false;
        return shouldCountUnread(currentFriendId, friendId);
    }

    /**
     * 群新消息是否要弹通知。
     *
     * @param currentGroupId 当前打开的群 id；没有打开任何群时为 -1
     * @param system         msg_type == "system"（入群/退群/禁言等，由群聊页内展示）
     * @param quiet          该群是否免打扰
     */
    public static boolean shouldNotifyGroup(int currentGroupId, int groupId, boolean system, boolean quiet) {
        if (system) return false;
        if (quiet) return false;
        return shouldCountUnread(currentGroupId, groupId);
    }

    /**
     * 未读 +1。负值（脏数据/历史遗留）按 0 处理，避免未读越加越小。
     */
    public static int nextUnread(int current) {
        return current < 0 ? 1 : current + 1;
    }

    /** 好友通知标题：昵称为空 → 兜底文案（不弹空标题通知）。 */
    public static String friendTitle(String nickname) {
        return (nickname == null || nickname.trim().isEmpty()) ? FALLBACK_FRIEND_TITLE : nickname;
    }

    /** 群通知标题：群名为空 → 兜底文案。 */
    public static String groupTitle(String groupName) {
        return (groupName == null || groupName.trim().isEmpty()) ? FALLBACK_GROUP_TITLE : groupName;
    }
}
