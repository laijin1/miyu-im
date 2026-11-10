package com.familychat.app.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link NotificationPolicy} 单测 —— 通知派发的全部"该不该弹"规则。
 *
 * <p>锁三件事：
 * <ol>
 *   <li><b>正在聊天不打扰</b>：{@code currentFriendId/currentGroupId} 等于消息所属会话时不弹；</li>
 *   <li><b>未读与通知条件不同</b>：群未读<b>包含</b> system 消息，群通知<b>不包含</b>
 *       （旧实现就是这个不对称，是最容易被"顺手统一"改坏的地方）；</li>
 *   <li><b>不产生空标题</b>：昵称/群名缺失时给兜底文案，而不是旧实现里的空标题通知。</li>
 * </ol>
 *
 * <p>纯 JVM 测试：本类不碰 Android API，无需模拟器。
 */
public class NotificationPolicyTest {

    // ───────────────────────── 好友 ─────────────────────────

    @Test
    public void friend_inSameChat_notNotified() {
        assertFalse(NotificationPolicy.shouldNotifyFriend(11, 11, false));
    }

    @Test
    public void friend_inAnotherChat_notified() {
        assertTrue(NotificationPolicy.shouldNotifyFriend(11, 22, false));
    }

    @Test
    public void friend_noChatOpen_notified() {
        assertTrue(NotificationPolicy.shouldNotifyFriend(-1, 22, false));
    }

    @Test
    public void friend_invalidId_notNotified() {
        assertFalse(NotificationPolicy.shouldNotifyFriend(-1, 0, false));
        assertFalse(NotificationPolicy.shouldNotifyFriend(-1, -5, false));
    }

    @Test
    public void friend_quiet_neverNotified() {
        assertFalse(NotificationPolicy.shouldNotifyFriend(-1, 22, true));
    }

    /**
     * 未读与通知的条件不对称（对齐群侧 {@link #unreadCountsEvenWhenNotificationIsSuppressed}）：
     * 免打扰好友仍要计未读，只是不弹通知。
     */
    @Test
    public void friend_quietStillCountsUnread() {
        int friendId = 22;
        assertTrue("免打扰好友仍要计未读", NotificationPolicy.shouldCountUnread(-1, friendId));
        assertFalse("但免打扰好友不弹通知", NotificationPolicy.shouldNotifyFriend(-1, friendId, true));
        assertTrue("未开免打扰照常弹", NotificationPolicy.shouldNotifyFriend(-1, friendId, false));
    }

    // ───────────────────────── 群 ─────────────────────────

    @Test
    public void group_inSameChat_notNotified() {
        assertFalse(NotificationPolicy.shouldNotifyGroup(21, 21, false, false));
    }

    @Test
    public void group_normal_notNotified_none() {
        assertTrue(NotificationPolicy.shouldNotifyGroup(-1, 21, false, false));
    }

    @Test
    public void group_systemMessage_neverNotified() {
        assertFalse(NotificationPolicy.shouldNotifyGroup(-1, 21, true, false));
    }

    @Test
    public void group_quiet_neverNotified() {
        assertFalse(NotificationPolicy.shouldNotifyGroup(-1, 21, false, true));
    }

    @Test
    public void group_quietAndSystem_neverNotified() {
        assertFalse(NotificationPolicy.shouldNotifyGroup(-1, 21, true, true));
    }

    @Test
    public void group_invalidId_notNotified() {
        assertFalse(NotificationPolicy.shouldNotifyGroup(-1, 0, false, false));
    }

    /**
     * 未读与通知的条件不对称（旧行为，必须保持）：
     * 免打扰群、system 消息都要计未读，只是不弹通知。
     */
    @Test
    public void unreadCountsEvenWhenNotificationIsSuppressed() {
        int groupId = 21;
        assertTrue("免打扰群仍要计未读",
                NotificationPolicy.shouldCountUnread(-1, groupId));
        assertFalse("但免打扰群不弹通知",
                NotificationPolicy.shouldNotifyGroup(-1, groupId, false, true));
        assertFalse("system 消息不弹通知",
                NotificationPolicy.shouldNotifyGroup(-1, groupId, true, false));
    }

    // ───────────────────────── 未读计数 ─────────────────────────

    @Test
    public void nextUnread_increments() {
        assertEquals(1, NotificationPolicy.nextUnread(0));
        assertEquals(6, NotificationPolicy.nextUnread(5));
    }

    @Test
    public void nextUnread_negativeTreatedAsZero() {
        assertEquals(1, NotificationPolicy.nextUnread(-3));
    }

    // ───────────────────────── 标题兜底 ─────────────────────────

    @Test
    public void friendTitle_fallsBackWhenMissing() {
        assertEquals("好友", NotificationPolicy.friendTitle(null));
        assertEquals("好友", NotificationPolicy.friendTitle(""));
        assertEquals("好友", NotificationPolicy.friendTitle("   "));
        assertEquals("妈妈", NotificationPolicy.friendTitle("妈妈"));
    }

    @Test
    public void groupTitle_fallsBackWhenMissing() {
        assertEquals("群消息", NotificationPolicy.groupTitle(null));
        assertEquals("群消息", NotificationPolicy.groupTitle(""));
        assertEquals("家族群", NotificationPolicy.groupTitle("家族群"));
    }
}
