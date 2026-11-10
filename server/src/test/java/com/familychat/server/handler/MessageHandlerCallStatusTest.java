package com.familychat.server.handler;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约测试：{@code MessageHandler.reportCallStatus} 的 event 白名单、四态文案、msg_type 二分。
 *
 * <h3>为什么必须有这条测试</h3>
 * 通话状态消息的文案与 msg_type 都由服务端生成，客户端<b>只负责渲染</b>：
 * <ul>
 *   <li>白名单漏一条 → 客户端发得出、服务端 400，聊天里<b>静默什么都没有</b>；</li>
 *   <li>文案表漏一条 → 那条通话记录显示成别的状态（或 500），<b>没有任何日志</b>；</li>
 *   <li>msg_type 二分漏一条 → 客户端图标与回拨类型全错（语音显示摄像头 / 点开变视频）。</li>
 * </ul>
 * 这三类错误都不会抛异常、不会打错误日志，只能靠用户反馈发现。服务端是<b>唯一</b>能拦住它们的地方。
 *
 * <p>本测试断言白名单<b>恰好</b>是这 4 条：多一条（误收别的 event）或少一条都会红。
 * 将来新增 {@code call_hold} 之类，必须同时改白名单、文案表（{@code callStatusContent}）
 * 和本测试 —— 这是刻意的摩擦。
 *
 * <p>纯静态调用，不启动 Javalin、不碰数据库（{@code MessageHandler} 没有静态字段），
 * 因此不继承 {@code DbTestSupport}。
 */
class MessageHandlerCallStatusTest {

    // ─────────────────────────────────────────────────────────────
    //  白名单
    // ─────────────────────────────────────────────────────────────

    @Test
    void eventWhitelist_isExactlyTheFourCallStatusEvents() {
        assertEquals(
                Set.of("rejected", "ended", "missed", "canceled"),
                MessageHandler.CALL_STATUS_EVENTS,
                "白名单变更必须同步本测试 + callStatusContent 的文案表");
    }

    @Test
    void eventWhitelist_rejectsOldAndUnknownEvents() {
        // "canceled" 是本次新增；下面这些从来不是合法 event，必须继续被拒。
        for (String bogus : new String[]{
                "", "cancel", "CANCELED", "ended ", "call_cancel",
                "busy", "timeout", "rejected ", "cancelled", "call_video"}) {
            assertFalse(MessageHandler.CALL_STATUS_EVENTS.contains(bogus),
                    "不该被放行的 event: " + bogus);
        }
        // 刻意不测 null：Set.of() 的 contains(null) 会抛 NPE（不可变集合的既定行为）。
        // 生产路径也不会传 null —— reportCallStatus 里
        // `eventObj instanceof String ? (String) eventObj : ""` 把非字符串一律归一成 ""。
    }

    // ─────────────────────────────────────────────────────────────
    //  四态文案
    // ─────────────────────────────────────────────────────────────

    @Test
    void content_isExactCopyForEachEvent() {
        assertEquals("已拒绝", MessageHandler.callStatusContent("rejected", 0));
        assertEquals("无人接听", MessageHandler.callStatusContent("missed", 0));
        assertEquals("已取消", MessageHandler.callStatusContent("canceled", 0));
        assertEquals("通话时长 00:00", MessageHandler.callStatusContent("ended", 0));
    }

    @Test
    void content_endedCarriesFormattedDuration() {
        assertEquals("通话时长 00:30", MessageHandler.callStatusContent("ended", 30));
        assertEquals("通话时长 01:00", MessageHandler.callStatusContent("ended", 60));
        assertEquals("通话时长 02:05", MessageHandler.callStatusContent("ended", 125));
        // 超过一小时仍按 mm:ss（与 formatCallDuration 既有行为一致，本次不改）
        assertEquals("通话时长 61:01", MessageHandler.callStatusContent("ended", 3661));
    }

    @Test
    void content_clampsNegativeDurationToZero() {
        assertEquals("通话时长 00:00", MessageHandler.callStatusContent("ended", -5));
        assertEquals("通话时长 00:00", MessageHandler.callStatusContent("ended", Integer.MIN_VALUE));
    }

    @Test
    void content_ignoresDurationForNonEndedEvents() {
        // 非 ended 的 event 不该把时长混进文案（客户端会在这些事件上一并传 duration_sec=0）
        assertEquals("已拒绝", MessageHandler.callStatusContent("rejected", 42));
        assertEquals("无人接听", MessageHandler.callStatusContent("missed", 42));
        assertEquals("已取消", MessageHandler.callStatusContent("canceled", 42));
    }

    @Test
    void content_throwsOnWhitelistedButUnregisteredEvent() {
        // 白名单里有、文案表里没有 = 新增 event 时漏了文案。必须炸，不能静默发错文案。
        for (String unknown : new String[]{"call_hold", "bogus", "", null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MessageHandler.callStatusContent(unknown, 0),
                    "未登记文案的 event 必须抛异常: " + unknown);
        }
    }

    /**
     * 交叉校验：白名单里的每一条都<b>必须</b>能取到文案。
     *
     * <p>这条是真正的"漏一条就红"守卫 —— 将来有人只往 {@code CALL_STATUS_EVENTS} 里加了一条、
     * 忘了往 {@code callStatusContent} 加分支，本测试立刻失败，而不是等线上发出错文案。
     */
    @Test
    void everyWhitelistedEvent_hasRegisteredCopy() {
        for (String event : MessageHandler.CALL_STATUS_EVENTS) {
            // 直接调用：缺文案会抛 IllegalArgumentException，本测试即失败（无需 assertDoesNotThrow，
            // 那个重载在这里还会与 ThrowingSupplier 版本产生歧义）。
            String content = MessageHandler.callStatusContent(event, 0);
            assertFalse(content.isEmpty(), "文案不能为空: " + event);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  msg_type 二分
    // ─────────────────────────────────────────────────────────────

    @Test
    void msgType_isVideoOrVoice() {
        assertEquals("call_video", MessageHandler.callMsgType(true));
        assertEquals("call_voice", MessageHandler.callMsgType(false));
    }

    @Test
    void msgType_neverCollidesWithLegacyCallOrSignalingNames() {
        // "call" 是 1.1.8 及更早的历史 msg_type，服务端不再生成，但客户端仍要认它（legacy）。
        // "call_media"/"call_media_stop" 等是 WS 信令 type，永远不能出现在 messages.msg_type 里。
        for (boolean video : new boolean[]{true, false}) {
            String t = MessageHandler.callMsgType(video);
            assertNotEquals("call", t, "服务端不再写历史类型 call");
            assertTrue(t.startsWith("call_"), "应为 call_ 前缀: " + t);
            assertFalse(t.startsWith("call_media"), "不能与 WS 信令 type 撞名: " + t);
        }
    }
}
