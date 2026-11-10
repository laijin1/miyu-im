package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 群聊权限核心：建群/成员校验/消息撤回的群归属校验。 */
class GroupTest extends DbTestSupport {

    @Test
    void createGroupAndMembership() {
        int owner = register("owner");
        int m1 = register("m1");
        int m2 = register("m2");
        int outsider = register("outsider");

        int gid = db.createGroup(owner, "一家人", List.of(m1, m2));
        assertTrue(gid > 0);

        assertTrue(db.isGroupMember(gid, owner));
        assertTrue(db.isGroupMember(gid, m1));
        assertTrue(db.isGroupMember(gid, m2));
        assertFalse(db.isGroupMember(gid, outsider), "非成员不应是群成员");
    }

    @Test
    void recallGroupMessageRequiresSameGroup() {
        int owner = register("owner");
        int m1 = register("m1");
        int other = register("other");

        int gidA = db.createGroup(owner, "群A", List.of(m1));
        int gidB = db.createGroup(other, "群B", List.of(owner));  // owner 同时在群B

        Map<String, Object> msg = db.saveGroupMessage(gidA, owner, "秘密", "text", "", "", 0, "");
        int msgId = ((Number) msg.get("id")).intValue();

        // 通过群B的路径撤回群A的消息 → 必须失败（防跨群撤回）
        assertFalse(db.recallGroupMessage(msgId, owner, gidB), "跨群撤回必须失败");
        // 正确的群 + 发送者 → 成功
        assertTrue(db.recallGroupMessage(msgId, owner, gidA), "本群发送者可撤回");
        // 已撤回不可再撤
        assertFalse(db.recallGroupMessage(msgId, owner, gidA), "重复撤回应失败");
    }
}
