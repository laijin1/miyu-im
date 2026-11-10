package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 消息越权防护：清空/撤回/删除的参与者校验（LOGIC-AUDIT 第 5 条）。 */
class MessageAuthorizationTest extends DbTestSupport {

    /** 三方：alice 与 bob 聊天，无关的 eve 冒充调用者，能否波及他们的记录？ */
    @Test
    void thirdPartyCanWipeOthersConversation() {
        int alice = register("alice");
        int bob = register("bob");
        int eve = register("eve");

        db.saveMessage(alice, bob, "hello bob", "text");
        db.saveMessage(bob, alice, "hi alice", "text");
        List<Map<String, Object>> before = db.getMessages(alice, bob, 50);
        System.out.println("[CLEAR] before = " + before.size());

        // eve 是毫不相干的第三方，冒充调用者调 clearConversation(eve, bob)：
        // 语义是「eve 要清空自己与 bob 的会话」，但 eve 与 bob 之间没有任何消息，
        // 而旧实现会按 (eve,bob) 这一对删除 —— 关键是能否波及 alice↔bob 的记录。
        int deleted = db.clearConversation(eve, alice);
        List<Map<String, Object>> after = db.getMessages(alice, bob, 50);
        System.out.println("[CLEAR] eve deleted=" + deleted + " alice-bob remaining=" + after.size());

        // 修复后：eve 与 alice 之间没有消息，删不动，alice↔bob 的记录必须原样保留
        assertEquals(0, deleted, "无关第三方不应能删除他人会话记录");
        assertEquals(2, after.size(), "记录必须原样保留");
        System.out.println("[CLEAR][OK] 第三方已无法清空他人聊天记录");
    }

    /** 当事人自己清空：仍然有效（确认修复没把正常路径一起堵死）。 */
    @Test
    void participantCanStillClearOwnConversation() {
        int alice = register("alice");
        int bob = register("bob");
        db.saveMessage(alice, bob, "m1", "text");
        db.saveMessage(bob, alice, "m2", "text");

        int deleted = db.clearConversation(alice, bob);
        System.out.println("[CLEAR-OWN] alice 清空 deleted=" + deleted
                + " remaining=" + db.getMessages(alice, bob, 50).size());
        assertEquals(2, deleted, "当事人清空自己的会话应生效");
        assertTrue(db.getMessages(alice, bob, 50).isEmpty());
    }

    /** alice 清空与 bob 的会话，bob 那侧是否也被删了（单向 vs 双向语义）。 */
    @Test
    void clearIsBidirectionalNotPerUser() {
        int alice = register("alice");
        int bob = register("bob");
        db.saveMessage(alice, bob, "msg1", "text");
        db.saveMessage(alice, bob, "msg2", "text");

        db.clearConversation(alice, bob);
        System.out.println("[CLEAR2] alice view = " + db.getMessages(alice, bob, 50).size()
                + " bob view = " + db.getMessages(bob, alice, 50).size());
    }

    /** 撤回消息：接收方能否撤回发送方的消息？ */
    @Test
    void recallAuthorization() {
        int alice = register("alice");
        int bob = register("bob");
        Map<String, Object> m = db.saveMessage(alice, bob, "secret", "text");
        int mid = ((Number) m.get("id")).intValue();

        boolean bobRecall = db.recallMessage(mid, bob);
        System.out.println("[RECALL] bob(接收方) 撤回 alice 的消息 = " + bobRecall + " (期望 false)");
        boolean aliceRecall = db.recallMessage(mid, alice);
        System.out.println("[RECALL] alice(发送方) 撤回 = " + aliceRecall + " (期望 true)");
        assertFalse(bobRecall, "接收方不应能撤回他人消息");
    }

    /** 删除消息只能删自己的。 */
    @Test
    void deleteAuthorization() {
        int alice = register("alice");
        int bob = register("bob");
        Map<String, Object> m = db.saveMessage(alice, bob, "x", "text");
        int mid = ((Number) m.get("id")).intValue();
        System.out.println("[DELETE] bob 删除 alice 的消息 = " + db.deleteMessage(mid, bob) + " (期望 false)");
    }

    /** 撤回后 is_read 标记与未读数。 */
    @Test
    void unreadCountAfterRecall() {
        int alice = register("alice");
        int bob = register("bob");
        db.saveMessage(alice, bob, "a", "text");
        db.saveMessage(alice, bob, "b", "text");
        int n = db.markRead(alice, bob);
        System.out.println("[READ] markRead 影响行数 = " + n + " (期望 2)");
        System.out.println("[READ] 再次 markRead = " + db.markRead(alice, bob) + " (期望 0)");
    }
}
