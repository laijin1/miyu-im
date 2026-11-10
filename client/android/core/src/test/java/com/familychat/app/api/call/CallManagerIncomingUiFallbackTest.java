package com.familychat.app.api.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 契约测试：来电通知兜底钩子（{@link CallManager.IncomingUiFallback}）。
 *
 * <h3>为什么必须锁住这条契约</h3>
 * 后台/锁屏来电时，{@code launchIncomingUi} 的 {@code startActivity} 在 Android 10+
 * 会被系统<b>静默</b>拦截（不抛异常）。这条通知钩子是那时唯一的用户可感知信号，
 * 而它的调用点在一段以 try/catch 结尾的代码里 —— 很容易在后续重构中被顺手挪进
 * {@code catch} 块（那样静默拦截就完全失效），或在整个方法提前 return 的分支里被漏掉。
 * 本测试把"只要走到拉 UI 这一段就必须回调一次"变成可执行断言。
 *
 * <h3>为什么直接测 {@code dispatchIncomingUiFallback} 而不是走 {@code onOffer}</h3>
 * {@code launchIncomingUi}（private）需要两样纯 JVM 单测里没有的东西：
 * <ol>
 *   <li>非 null 的 {@code appCtx} —— {@code CallManager.install(Context)} 在单测里
 *       {@code getApplicationContext()} 返回 null（{@code returnDefaultValues=true}），
 *       方法会在 {@code if (ctx == null) return;} 处提前退出，钩子<b>到不了</b>；</li>
 *   <li>能解析的 {@code Routes.CALL_ACTIVITY} —— {@code startActivity} 需要一个真实组件。</li>
 * </ol>
 * 因此把钩子的调用收敛成一个包级方法（{@code dispatchIncomingUiFallback}），
 * 由 {@code launchIncomingUi} 在"真正拉 UI"分支末尾无条件调用。本测试覆盖该方法的契约：
 * 已安装 → 恰好回调一次且参数原样；未安装（null）→ 静默不抛；实现方抛异常 → 被吞掉。
 */
public class CallManagerIncomingUiFallbackTest {

    private static CallSession incomingSession(String callId, boolean video) {
        // new CallSession(callId, callerId, calleeId, videoEnabled, incoming=true)
        return new CallSession(callId, 7001, 8002, video, true);
    }

    @After
    public void tearDown() {
        // 静态钩子会跨测试方法残留：必须清干净，否则污染同 JVM 的其它测试。
        CallManager.setIncomingUiFallback(null);
    }

    @Test
    public void fallbackReceivesCallerInfoExactlyOnce() {
        final int[] calls = {0};
        final String[] seen = new String[4];

        CallManager.setIncomingUiFallback((callId, callerId, video, name) -> {
            calls[0]++;
            seen[0] = callId;
            seen[1] = String.valueOf(callerId);
            seen[2] = String.valueOf(video);
            seen[3] = name;
        });

        CallManager.dispatchIncomingUiFallback(incomingSession("call-abc", true), "张三");

        assertEquals("钩子必须恰好被调用一次", 1, calls[0]);
        assertEquals("callId 必须原样透传", "call-abc", seen[0]);
        assertEquals("callerId 必须原样透传", "7001", seen[1]);
        assertEquals("videoEnabled 必须原样透传", "true", seen[2]);
        assertEquals("friendName 必须原样透传", "张三", seen[3]);
    }

    @Test
    public void noFallbackMeansSilentNoOp() {
        // 未安装（null）时必须是静默 no-op —— 这是纯 JVM 单测/未安装 app 模块时的行为，
        // 也是"改造前行为"的基线：不能因为没装钩子就把来电路径抛崩。
        CallManager.setIncomingUiFallback(null);
        CallManager.dispatchIncomingUiFallback(incomingSession("call-none", false), "未知用户");
        // 走到这里没抛异常即为通过；再断言一下状态确实没被钩子污染。
        assertNull("未安装时不应产生任何副作用", CallManager.getCurrentCall());
    }

    @Test
    public void throwingFallbackIsSwallowed() {
        CallManager.setIncomingUiFallback((callId, callerId, video, name) -> {
            throw new IllegalStateException("notification infra broken");
        });

        // 通知设施炸了绝不能影响状态机/来电信令：必须被吞掉。
        CallManager.dispatchIncomingUiFallback(incomingSession("call-boom", false), "李四");
        assertTrue("异常必须被吞掉（走到这里说明没抛）", true);
    }

    @Test
    public void clearingFallbackStopsCallbacks() {
        final int[] calls = {0};
        CallManager.setIncomingUiFallback((callId, callerId, video, name) -> calls[0]++);
        CallManager.setIncomingUiFallback(null);   // 卸载（排障路径）
        CallManager.dispatchIncomingUiFallback(incomingSession("call-cleared", false), "王五");
        assertEquals("卸载后不应再回调", 0, calls[0]);
    }

    // =================================================================
    //  批 2 / c：onCallUiShown 转发（UI 已显示 → 撤通知）
    // =================================================================

    @Test
    public void callUiShownIsForwardedWithCallId() {
        final int[] calls = {0};
        final String[] seen = new String[1];

        CallManager.setIncomingUiFallback(new CallManager.IncomingUiFallback() {
            @Override
            public void onIncomingUiFallback(String callId, int callerId,
                                             boolean videoEnabled, String friendName) {
                // 本用例只关心 onCallUiShown；留空实现证明 default 之外的方法不影响本路径。
            }

            @Override
            public void onCallUiShown(String callId) {
                calls[0]++;
                seen[0] = callId;
            }
        });

        CallManager.notifyCallUiShown("call-ui");

        assertEquals("onCallUiShown 必须被转发一次", 1, calls[0]);
        assertEquals("callId 必须原样透传", "call-ui", seen[0]);
    }

    @Test
    public void callUiShownNeedsNoOverride() {
        // onCallUiShown 是 interface 的 default 方法：只实现 onIncomingUiFallback 的
        // 老式实现者（以及不关心撤通知的实现者）不该被迫改动 —— 这条锁定该兼容性。
        final boolean[] fallbackCalled = {false};
        CallManager.setIncomingUiFallback((callId, callerId, video, name) -> fallbackCalled[0] = true);

        CallManager.notifyCallUiShown("call-default");   // default 空实现，必须不抛
        assertEquals("default 实现下不应触达 onIncomingUiFallback", false, fallbackCalled[0]);

        CallManager.dispatchIncomingUiFallback(incomingSession("call-default2", false), "赵六");
        assertTrue("原有的 onIncomingUiFallback 通道必须仍然工作", fallbackCalled[0]);
    }

    @Test
    public void callUiShownThrowingIsSwallowed() {
        CallManager.setIncomingUiFallback(new CallManager.IncomingUiFallback() {
            @Override
            public void onIncomingUiFallback(String callId, int callerId,
                                             boolean videoEnabled, String friendName) { }

            @Override
            public void onCallUiShown(String callId) {
                throw new IllegalStateException("cancel infra broken");
            }
        });

        // 撤通知失败绝不能影响通话页的 onStart：必须被吞掉。
        CallManager.notifyCallUiShown("call-boom-ui");
        assertTrue("异常必须被吞掉（走到这里说明没抛）", true);
    }
}
