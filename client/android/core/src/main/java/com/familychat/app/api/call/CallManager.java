package com.familychat.app.api.call;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Routes;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程级通话状态机（Step 1）。
 *
 * <h3>为什么存在</h3>
 * 改造前通话状态散落在 {@code CallActivity} 的实例字段 +
 * 两个静态字段（{@code isActive} / {@code shouldHandleIncoming} 的 4 秒窗口）里，
 * 由此产生四个叠加故障：同一人 4 秒内重拨被吞、占线时对主叫无任何回信、
 * 两端都没有超时（界面永久卡在"呼叫中"）、以及"谁先注册谁弹窗"的注册顺序依赖。
 * 本类把状态收敛到进程级唯一实例，用显式状态机 + 超时 + callId 去重接管。
 *
 * <h3>注册与生命周期</h3>
 * 与 {@code NotificationDispatcher} 同款：{@link #install(Context)} 由
 * {@code FamilyChatApp.onCreate()} 调用一次，并且<b>刻意不反注册</b> ——
 * {@code PushConnection.stop()}（登出/401）不清空监听器，重新登录后同一条连接重启，
 * 本类继续有效。它与 {@code NotificationDispatcher} 相互独立，注册顺序无关。
 *
 * <h3>本类实现 {@code WebSocketManager.Listener} 时"故意只处理 9 个事件"</h3>
 *
 * <p><b>(1) 覆写并真正处理的 9 个</b>：
 * <ul>
 *   <li>{@code onCallOfferWithId} —— 来电（带 callId，去重键）</li>
 *   <li>{@code onCallAnswer} / {@code onCallBusy} / {@code onCallReject} / {@code onCallCancel}
 *       / {@code onCallHangup} —— 五条改变状态的信令</li>
 *   <li>{@code onCallMedia} / {@code onCallVideo} —— <b>只为取消"接通后无媒体"看门狗</b>，
 *       不看帧数据（帧的加解码仍归通话页的 WebRTCManager）</li>
 *   <li>{@code onConnected} —— 外呼时 WebSocket 就绪后补发 call_offer</li>
 * </ul>
 *
 * <p><b>(2) 必须实现、但刻意留空的 6 个</b>（它们是接口的<b>抽象</b>方法，不实现编译不过；
 * 留空是设计决定，不是漏）：{@code onDisconnected} / {@code onNewMessage} /
 * {@code onFriendRequest} / {@code onFriendRequestHandled} / {@code onUserOnline} / {@code onUserOffline}。
 * 其中 {@code onDisconnected} <b>刻意不结束通话</b>：一次 1 秒的网络抖动不该掐断好电话，
 * 由 10s 媒体看门狗兜底。其余 5 个是消息/好友/在线状态，属通知与各页面职责
 * （{@code NotificationDispatcher} / MainActivity / ChatActivity）。
 *
 * <p><b>(3) 保持接口 default、本类完全不出现的</b>：{@code onCallOffer}（窄接口）、
 * {@code onCallIce}、{@code onCallMediaStop}（媒体职责，在 CallActivity）、
 * 群聊 10 条、钱包 5 条、商城 1 条（通知/各页面职责）。
 *
 * <p>注意：{@code PushConnection.Fanout} 那边的"漏覆写 = 事件静默失效"由
 * {@code ListenerFanoutContractTest} 兜底；那条约束不适用本类（本类是监听器，不是扇出者）。
 *
 * <h3>线程约定</h3>
 * 所有入口都在主线程（{@code WebSocketManager} 的回调已 post 到主线程，
 * {@code PushConnection} 原样转发；UI 也从主线程调用）。
 * 状态读写统一用 {@link #LOCK} 保护，仅为防御非主线程误用与回调重入。
 */
public final class CallManager {

    private static final String TAG = "CallManager";

    // =================================================================
    //  Step 1 影子开关（C3 ← → C4 的切换点）
    // =================================================================

    /**
     * <b>只观察不干预</b>（C3 = true，<b>C4 起 = false</b>）。
     *
     * <p>置 true 时本类彻底静默：只更新状态 + 打日志，
     * <b>不启动超时、不发任何信令、不启动 UI、不写通话状态系统消息</b>。
     *
     * <p>保留这个开关而不是删掉，是因为它记录了一次真实的排查结论：C3 阶段新旧两条路径并存，
     * 会<b>同时</b>收到同一批信令；若那时本类启动超时，就会出现
     * "B 已通过旧路径接听、45s 后 CallManager 却发出 call_reject"这类<b>互相打架</b>的故障。
     * 将来若还需要"影子运行状态机排障"（例如大改信令后先只观察一轮），把它置回 true 即可。
     *
     * <p><b>⚠️ 与 {@link #LAUNCH_UI} 的组合语义</b>：C4 之后旧来电入口已删除，
     * 若有人只把 {@link #LAUNCH_UI} 设回 false 而不动本开关，来电将无处可弹（静默丢来电）。
     */
    private static final boolean OBSERVE_ONLY = false;

    /**
     * 是否允许拉起来电 UI（C3 = false，<b>C4 起 = true</b>）。
     *
     * <p>与 {@link #OBSERVE_ONLY} 独立存在：两个开关分别表达"改不改变世界"和"要不要弹界面"。
     * 关掉它等于"来电只更新状态、不弹任何界面" —— 仅用于排障，不要长期留在 false。
     */
    private static final boolean LAUNCH_UI = true;

    // =================================================================
    //  超时参数
    // =================================================================

    /** 呼叫超时：主叫等待 30s 无人接听 → 发 call_cancel 收尾，并由主叫写一条「无人接听」。 */
    private static final long OUTGOING_TIMEOUT_MS = 30_000;
    /** 响铃超时：被叫响铃 45s 无人接听 → 发 call_reject 收尾（仅兜底，不写系统消息）。 */
    private static final long INCOMING_TIMEOUT_MS = 45_000;
    /** 接通后媒体看门狗：10s 内没收到任何 call_media/call_video → 认为媒体没建立，挂断收尾。 */
    private static final long MEDIA_WATCHDOG_MS = 10_000;
    /** 已结束 callId 的吞重窗口：吸收在途重复信令（对端重发、服务端补投）。 */
    private static final long DEDUP_TTL_MS = 10_000;
    /** 吞重 LRU 上限。 */
    private static final int DEDUP_MAX = 32;

    /** 中继模式 offer 字面量（与 CallActivity 现状一致：不走 P2P SDP）。 */
    private static final String RELAY_OFFER = "relay";

    // =================================================================
    //  对外事件
    // =================================================================

    /** 通话结束原因。<b>从本端视角</b>描述"为什么结束"。 */
    public enum EndReason {
        /** 对端挂断（已接通）。 */
        HANGUP,
        /** 对端占线（收到 call_busy）。 */
        BUSY,
        /** 对端拒接（收到 call_reject）。 */
        REJECTED,
        /** 主叫取消了未接通的呼叫（收到 call_cancel）。 */
        CANCELLED,
        /** 呼叫超时：本端主叫，30s 无人接。 */
        TIMEOUT_RING,
        /** 响铃超时：本端被叫，45s 无人接。 */
        TIMEOUT_ACCEPT,
        /** 接通后 10s 无媒体。 */
        MEDIA_FAIL,
        /** 本端主动挂断/取消。 */
        LOCAL_END,
        /** 媒体引擎自行收尾（WebRTCManager 内部错误 / ICE 断开 / relay 麦克风初始化失败等）。 */
        ENGINE_ENDED
    }

    /** 通话页订阅本类事件（C5 起 CallActivity 注册；C3 无订阅者）。 */
    public interface Observer {
        /** 通话已收尾，UI 应关闭通话页/响铃浮层，并按 reason 提示原因。 */
        void onCallEnded(String callId, EndReason reason);

        /** 状态已变化（可选覆写）。 */
        default void onStateChanged(CallSession session) {}
    }

    /**
     * 来电 UI 的<b>可插拔兜底</b>：负责在"后台拉不起来电界面"时发系统通知。
     *
     * <h3>为什么需要它</h3>
     * {@link #launchIncomingUi} 唯一的动作是 {@code startActivity}，而 Android 10+ 禁止
     * 后台启动 Activity；被拦截时<b>多数机型不抛异常</b>，只表现为"屏幕毫无反应"。
     * 本类在 {@code :core}，看不见 {@code :app} 的通知设施（{@code NotificationHelper} 那一层），
     * 所以只定义接口、由 {@code :app} 在 {@code FamilyChatApp.onCreate} 里安装实现 ——
     * 与 {@link #install(Context)} 的"进程级、只安装一次、不反注册"模式一致。
     *
     * <p><b>调用语义（批 1 决定：无条件调用）</b>：只要走到 {@link #launchIncomingUi} 的
     * 真正拉 UI 分支（非 OBSERVE_ONLY、非 LAUNCH_UI=false）就会调用一次，<b>不</b>依赖
     * {@code startActivity} 是否抛异常。理由：静默拦截无法从代码判定，只靠 catch 会漏掉
     * 最主要的失败形态。代价是"UI 已经起来时也有一条通知"，由实现方自行收敛
     * （通知可撤、且同时最多只有一通响铃来电）。
     */
    public interface IncomingUiFallback {
        /**
         * 后台拉不起来电 UI 时调用；实现方负责发通知（可全屏）。
         *
         * @param callId       本通通话 id，点通知回到 CallActivity 时靠它认领会话
         * @param callerId     主叫 userId（用于读 {@code friend_<id>} 缓存展示昵称/头像）
         * @param videoEnabled 是否视频通话（决定通知文案）
         * @param friendName   来电人昵称（缓存未命中时为"未知用户"）
         */
        void onIncomingUiFallback(String callId, int callerId, boolean videoEnabled, String friendName);

        /**
         * 来电 UI <b>已经显示</b>时调用（批 2 / c）：实现方据此撤掉兜底通知。
         *
         * <p>方向是反的：{@link #onIncomingUiFallback} 是"拉不起来 → 发通知"，
         * 本方法是"UI 起来了 → 撤通知"。之所以仍放在同一个接口上，是为了不新增第二套
         * 注册机制 —— 实现方本来就是同一个对象（{@code :app} 的来电通知器），
         * 拆成两个 setter 只会多一份"装了 A 忘了装 B"的失败模式。
         *
         * <p>默认空实现：这样做是为了不强迫既有/将来的实现者都必须关心撤通知
         * （与 {@link Observer#onStateChanged} 用 default 的理由一致）。
         *
         * @param callId 页面上当前这通通话 id；实现方应只撤"与自己记录的那一通相同"的通知
         */
        default void onCallUiShown(String callId) {}
    }

    // =================================================================
    //  内部状态
    // =================================================================

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();

    /** 已结束 callId → 结束时刻（LRU，见 {@link #DEDUP_MAX}）。用于吞掉迟到的重复信令。 */
    private static final LinkedHashMap<String, Long> ENDED_CALL_IDS = new LinkedHashMap<>();

    private static volatile Context appCtx;
    private static volatile boolean installed;

    /**
     * 后台拉不起来电 UI 时的通知兜底；由 {@code :app} 安装（见 {@link IncomingUiFallback}）。
     * null = 未安装 = 不发通知（改造前的行为，也是纯 JVM 单测下的行为）。
     */
    private static volatile IncomingUiFallback incomingUiFallback;

    /** 当前通话；null 表示 IDLE。 */
    private static CallSession current;
    private static Runnable ringTimeout;
    private static Runnable mediaTimeout;
    /** 外呼时 call_offer 是否已发出（避免 onConnected 补发重复）。 */
    private static boolean offerSent;
    /** 接通后是否已收到过媒体（媒体看门狗据此取消）。 */
    private static boolean firstMediaSeen;

    private CallManager() {
    }

    // =================================================================
    //  生命周期
    // =================================================================

    /** 幂等安装。由 {@code FamilyChatApp.onCreate()} 调用（与 NotificationDispatcher.install 并列）。 */
    public static void install(Context ctx) {
        if (ctx != null) {
            try {
                appCtx = ctx.getApplicationContext();
            } catch (Exception e) {
                AppLog.e(TAG, "install: getApplicationContext failed", e);
            }
        }
        synchronized (LOCK) {
            if (installed) return;
            installed = true;
        }
        // 锁外注册：addListener 在连接已就绪时会【同步补发 onConnected】，
        // 那条路径会进 onConnected() 并取 LOCK —— 持锁注册会自我死锁（可重入则掩盖问题）。
        PushConnection.addListener(new SignalingListener());
        AppLog.d(TAG, "installed (OBSERVE_ONLY=" + OBSERVE_ONLY + ", LAUNCH_UI=" + LAUNCH_UI + ")");
    }

    /** 清空状态与定时器（登出/401 时调用；<b>不</b>反注册监听器，与 PushConnection 语义一致）。 */
    public static void reset() {
        synchronized (LOCK) {
            clearTimers();
            current = null;
            offerSent = false;
            firstMediaSeen = false;
            ENDED_CALL_IDS.clear();
        }
        AppLog.d(TAG, "reset");
    }

    /**
     * 引擎侧主动上报"已收尾"（M6 修复）。
     *
     * <p>背景：CallEngine 在某些路径下会自行结束（WebRTCManager 内部错误、
     * ICE 断开、relay 麦克风初始化失败等），此时 CallManager.current 仍停在
     * ACTIVE，导致之后每次发起通话被 isBusy() 挡回、对端来电被回 call_busy，
     * 直到进程重启。
     *
     * <p>语义：仅清状态。**不发任何信令、不写系统消息** —— 因为引擎结束
     * 不是"本端主动挂断"，不能向对端重发 call_hangup。
     *
     * <p>幂等：current == null 或 callId 不匹配时 no-op。
     */
    public static void onEngineEnded(String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null) return;
            if (callId != null && !callId.isEmpty() && !callId.equals(s.callId)) return;
            teardown(EndReason.ENGINE_ENDED);
        }
    }

    /**
     * 安装"后台拉不起来电 UI 时发通知"的兜底实现。<b>幂等</b>，由
     * {@code FamilyChatApp.onCreate()} 调用一次（与 {@link #install(Context)} 并列）。
     *
     * <p>传 null 可卸载（排障/单测用）。刻意不提供自动反注册：进程级实现不持 Activity，
     * 无需回收（与 {@link #addObserver} 的注册约定一致）。
     */
    public static void setIncomingUiFallback(IncomingUiFallback f) {
        incomingUiFallback = f;
        AppLog.d(TAG, "setIncomingUiFallback: " + (f == null ? "cleared" : "installed"));
    }

    /**
     * 通话页真正可见时由页面调用（批 2 / c），把"UI 已显示"转发给兜底实现去撤通知。
     *
     * <p><b>为什么由本类转发而不是页面直接撤</b>：通话页在 {@code :feature-chat}，
     * 通知器在 {@code :app}，依赖方向是 {@code :app → :feature-chat}，页面看不见通知器。
     * 而本类在 {@code :core}，两边都看得见它，是天然的转发点 —— 与
     * {@link #launchIncomingUi} 用 {@code Routes} 字符串跨模块同一个思路：不新增机制，
     * 复用已经存在的可插拔钩子。
     *
     * <p>未安装兜底实现（null）时是静默 no-op；实现方抛异常同样被吞掉 ——
     * 撤通知失败绝不能影响通话页的 {@code onStart} 流程。
     */
    public static void notifyCallUiShown(String callId) {
        final IncomingUiFallback fallback = incomingUiFallback;
        if (fallback == null) return;
        try {
            fallback.onCallUiShown(callId);
        } catch (Throwable t) {
            AppLog.e(TAG, "onCallUiShown failed callId=" + callId, t);
        }
    }

    // =================================================================
    //  查询
    // =================================================================

    /** 当前通话；无通话返回 null。 */
    public static CallSession getCurrentCall() {
        synchronized (LOCK) {
            return current;
        }
    }

    /** 当前状态；无通话返回 {@link CallSession.State#IDLE}。 */
    public static CallSession.State getCurrentState() {
        synchronized (LOCK) {
            return current == null ? CallSession.State.IDLE : current.state;
        }
    }

    public static boolean isIdle() {
        return getCurrentState() == CallSession.State.IDLE;
    }

    /** 是否正在通话/响铃（决定新来电要不要回 call_busy、新外呼要不要拒绝）。 */
    public static boolean isBusy() {
        return !isIdle();
    }

    // =================================================================
    //  主叫
    // =================================================================

    /**
     * 发起呼叫。
     *
     * @return 新通话的 callId；已有通话进行中返回 null（调用方应提示"正在通话中"）
     */
    public static String startCall(int callerId, int calleeId, boolean videoEnabled) {
        synchronized (LOCK) {
            if (current != null) {
                AppLog.d(TAG, "startCall REJECTED (busy) state=" + current.state
                        + " callId=" + current.callId);
                return null;
            }
            CallSession s = new CallSession(newCallId(), callerId, calleeId, videoEnabled, false);
            s.ringingAt = System.currentTimeMillis();
            current = s;
            offerSent = false;
            firstMediaSeen = false;
            transition(s, CallSession.State.OUTGOING_RINGING, null);
            armRingTimeout(OUTGOING_TIMEOUT_MS, EndReason.TIMEOUT_RING);
            maybeSendOffer(s);
            return s.callId;
        }
    }

    // =================================================================
    //  被叫 / 通用
    // =================================================================

    /** 被叫接听：发 call_answer，进入 ACTIVE，启动媒体看门狗。 */
    public static void acceptCall(String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || s.state != CallSession.State.INCOMING_RINGING
                    || !s.callId.equals(callId)) {
                AppLog.d(TAG, "acceptCall ignored (state/callId mismatch) callId=" + callId
                        + " state=" + getCurrentState());
                return;
            }
            clearRingTimeout();
            s.activeAt = System.currentTimeMillis();
            transition(s, CallSession.State.ACTIVE, null);
            sendTo(s.callerId, "call_answer", s.callId);
            armMediaWatchdog();
        }
    }

    /** 被叫拒接：发 call_reject + 写"对方已拒绝"系统消息，随即收尾。 */
    public static void rejectCall(String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || s.state != CallSession.State.INCOMING_RINGING
                    || !s.callId.equals(callId)) {
                AppLog.d(TAG, "rejectCall ignored (state/callId mismatch) callId=" + callId
                        + " state=" + getCurrentState());
                return;
            }
            sendTo(s.callerId, "call_reject", s.callId);
            reportCallStatus(s.callerId, "rejected", 0, s.videoEnabled);
            // 收尾原因用 LOCAL_END 而不是 REJECTED：REJECTED 是**主叫方**的视角
            // （Observer 会提示"对方已拒绝"）。若这里也用 REJECTED，拒接的人自己会看到
            // "对方已拒绝"这种自相矛盾的提示。与 cancelCall/endCall 保持一致：本端动作 → LOCAL_END。
            teardown(EndReason.LOCAL_END);
        }
    }

    /** 主叫取消未接通的呼叫（用户主动挂断响铃中的外呼）。 */
    public static boolean cancelCall(String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || s.state != CallSession.State.OUTGOING_RINGING
                    || !s.callId.equals(callId)) {
                AppLog.d(TAG, "cancelCall ignored (state/callId mismatch) callId=" + callId
                        + " state=" + getCurrentState());
                return false;
            }
            sendTo(s.calleeId, "call_cancel", s.callId);
            // 主叫在对方接听前取消：写一条「已取消」。上报者=主叫 → sender=主叫
            // （主叫侧显示在右、被叫侧显示在左），与 ended/missed 同族（见 reportCallStatus
            // 的归属不变量）。
            reportCallStatus(s.calleeId, "canceled", 0, s.videoEnabled);
            teardown(EndReason.LOCAL_END);
            return true;
        }
    }

    /** 本端挂断（已接通发 call_hangup；仍在响铃则退化为 cancel/reject 语义）。 */
    public static void endCall(String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || !s.callId.equals(callId)) {
                AppLog.d(TAG, "endCall ignored (no such call) callId=" + callId
                        + " current=" + (current == null ? "null" : current.callId));
                return;
            }
            if (s.state == CallSession.State.ACTIVE) {
                sendTo(s.peerId(), "call_hangup", s.callId);
                // 与改造前一致：只有主叫为"已结束"写系统消息（带时长），被叫不写
                if (!s.incoming) reportCallStatus(s.calleeId, "ended", s.activeElapsedSec(), s.videoEnabled);
            } else if (s.state == CallSession.State.OUTGOING_RINGING) {
                sendTo(s.calleeId, "call_cancel", s.callId);
                // 挂断响铃中的外呼 == 取消，与 cancelCall() 同一语义，同样由主叫留一条「已取消」。
                // 这是**实际生效**的那条路径：CallActivity 挂断按钮走的是 endCall()
                // （cancelCall() 目前全仓无调用点，两处都写是为了语义一致）。
                // 两处都在写完后 teardown，而 teardown 会清 current，所以即便将来同时触发
                // 也不会双报（后到的那次会命中 s == null 的忽略分支）。
                reportCallStatus(s.calleeId, "canceled", 0, s.videoEnabled);
            } else if (s.state == CallSession.State.INCOMING_RINGING) {
                sendTo(s.callerId, "call_reject", s.callId);
                reportCallStatus(s.callerId, "rejected", 0, s.videoEnabled);
            }
            teardown(EndReason.LOCAL_END);
        }
    }

    // =================================================================
    //  UI 订阅
    // =================================================================

    public static void addObserver(Observer o) {
        if (o != null) OBSERVERS.addIfAbsent(o);
    }

    public static void removeObserver(Observer o) {
        if (o != null) OBSERVERS.remove(o);
    }

    // =================================================================
    //  信令入口（全部由 SignalingListener 在主线程调用）
    // =================================================================

    private static void onConnected() {
        synchronized (LOCK) {
            if (current != null && current.state == CallSession.State.OUTGOING_RINGING) {
                maybeSendOffer(current);
            }
        }
    }

    private static void onOffer(int fromUserId, String offerSdp, boolean videoEnabled, String callId) {
        CallSession toLaunch = null;
        synchronized (LOCK) {
            String id = (callId == null || callId.isEmpty()) ? ("unknown-" + fromUserId) : callId;

            if (isRecentlyEnded(id)) {
                AppLog.d(TAG, "call_offer ignored (recently ended) callId=" + id + " from=" + fromUserId);
                return;
            }
            if (current != null) {
                if (id.equals(current.callId)) {
                    AppLog.d(TAG, "call_offer ignored (duplicate) callId=" + id + " from=" + fromUserId);
                    return;
                }
                // 占线：明确回 call_busy，而不是改造前那样静默丢弃（现象 3-④）
                AppLog.d(TAG, "call_offer while busy -> call_busy callId=" + id
                        + " from=" + fromUserId + " state=" + current.state);
                sendTo(fromUserId, "call_busy", id);
                return;
            }

            int selfId = myUserId();
            if (selfId <= 0) {
                AppLog.e(TAG, "call_offer ignored: local userId unavailable (not logged in?)");
                return;
            }
            CallSession s = new CallSession(id, fromUserId, selfId, videoEnabled, true);
            s.offerSdp = offerSdp;
            s.ringingAt = System.currentTimeMillis();
            current = s;
            firstMediaSeen = false;
            transition(s, CallSession.State.INCOMING_RINGING, null);
            armRingTimeout(INCOMING_TIMEOUT_MS, EndReason.TIMEOUT_ACCEPT);
            toLaunch = s;
        }
        // 锁外启动 UI：startActivity 是慢操作，不该占着状态锁
        if (toLaunch != null) launchIncomingUi(toLaunch);
    }

    private static void onAnswer() {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || s.state != CallSession.State.OUTGOING_RINGING) {
                AppLog.d(TAG, "call_answer ignored (state mismatch) state=" + getCurrentState());
                return;
            }
            clearRingTimeout();
            s.activeAt = System.currentTimeMillis();
            transition(s, CallSession.State.ACTIVE, null);
            armMediaWatchdog();
        }
    }

    private static void onHangup(int fromUserId, String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || !s.matches(fromUserId, callId)) {
                AppLog.d(TAG, "call_hangup ignored from=" + fromUserId
                        + " callId=" + callId + " (no matching call)");
                return;
            }
            // 对端先挂断：与改造前 CallActivity.onCallHangup 一致 —— 由**主叫方**补一条
            // 「通话时长 MM:SS」系统消息，被叫方不写（同一条消息只能写一次）。
            //
            // 只在 ACTIVE 时补：响铃阶段对端"挂断"等于取消/拒接，写"已结束"是错的
            // （等价于改造前的 callEstablished 判断）。
            //
            // 【不双报的论证】本分支只处理"对端挂断"；本端主动挂断走 endCall()，
            // 而 endCall() 在进入时会先把 current 清掉（teardown），所以随后到达的
            // 对端 call_hangup 会命中上面 s==null 的忽略分支。四种组合各报一次：
            //   A(主叫)挂断 → endCall 报 1 次；B 收到 hangup → incoming=true 不报
            //   B(被叫)挂断 → endCall 的 !incoming 为假不报；A 收到 hangup → 这里报 1 次
            //   双方同时挂断 → 各自 endCall，随后互收 hangup 均因 current==null 被忽略
            //   响铃阶段挂断 → 都不是 ACTIVE，不报（只发 cancel/reject）
            if (!s.incoming && s.state == CallSession.State.ACTIVE) {
                reportCallStatus(s.calleeId, "ended", s.activeElapsedSec(), s.videoEnabled);
            }
            teardown(EndReason.HANGUP);
        }
    }

    private static void onBusy(int fromUserId, String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || !s.matches(fromUserId, callId)
                    || s.state != CallSession.State.OUTGOING_RINGING) {
                AppLog.d(TAG, "call_busy ignored callId=" + callId + " from=" + fromUserId
                        + " state=" + getCurrentState());
                return;
            }
            teardown(EndReason.BUSY);
        }
    }

    private static void onReject(int fromUserId, String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || !s.matches(fromUserId, callId)
                    || s.state != CallSession.State.OUTGOING_RINGING) {
                AppLog.d(TAG, "call_reject ignored callId=" + callId + " from=" + fromUserId
                        + " state=" + getCurrentState());
                return;
            }
            teardown(EndReason.REJECTED);
        }
    }

    private static void onCancel(int fromUserId, String callId) {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || !s.matches(fromUserId, callId)
                    || s.state != CallSession.State.INCOMING_RINGING) {
                AppLog.d(TAG, "call_cancel ignored callId=" + callId + " from=" + fromUserId
                        + " state=" + getCurrentState());
                return;
            }
            teardown(EndReason.CANCELLED);
        }
    }

    /** 首帧媒体到达 → 取消"接通后无媒体"看门狗。每通电话只在第一次生效（后面被 firstMediaSeen 短路）。 */
    private static void onMediaSeen() {
        synchronized (LOCK) {
            CallSession s = current;
            if (s == null || s.state != CallSession.State.ACTIVE || firstMediaSeen) return;
            firstMediaSeen = true;
            clearMediaWatchdog();
            AppLog.d(TAG, "first media seen, media watchdog cancelled callId=" + s.callId);
        }
    }

    // =================================================================
    //  状态机内部
    // =================================================================

    /** 打一条状态迁移日志并通知观察者。 */
    private static void transition(CallSession s, CallSession.State to, EndReason why) {
        CallSession.State from = s.state;
        s.state = to;
        if (from == to) return;

        StringBuilder sb = new StringBuilder("state ").append(from).append(" -> ").append(to);
        if (why != null) sb.append(" reason=").append(why);
        sb.append(" callId=").append(s.callId);
        if (to == CallSession.State.INCOMING_RINGING) {
            sb.append(" from=").append(s.callerId).append(" video=").append(s.videoEnabled);
        }
        AppLog.d(TAG, sb.toString());

        notifyStateChanged(s);
    }

    /**
     * 收尾：清定时器 → 置 ENDING（通知期间的状态）→ 清 current → 记忆 callId → 通知观察者。
     *
     * <p>日志刻意只打<b>一行</b> {@code state <原状态> -> IDLE reason=<原因>}：
     * ENDING 是瞬态，分两行打（{@code ACTIVE -> ENDING} + {@code ENDING -> IDLE}）
     * 只会让人误以为中间还有一次真实迁移。
     */
    private static void teardown(EndReason why) {
        CallSession s = current;
        if (s == null) return;
        clearTimers();

        CallSession.State from = s.state;
        s.state = CallSession.State.ENDING;
        AppLog.d(TAG, "state " + from + " -> " + CallSession.State.IDLE
                + " reason=" + why + " callId=" + s.callId);

        current = null;
        offerSent = false;
        rememberEnded(s.callId);

        notifyEnded(s.callId, why);

        s.state = CallSession.State.IDLE;
        notifyStateChanged(s);
    }

    /** 主叫侧：确保 call_offer 只发一次（onConnected 补发走同一入口）。 */
    private static void maybeSendOffer(CallSession s) {
        if (offerSent) return;
        if (!PushConnection.isConnected()) {
            // 未连接：不发，等 onConnected 补发（PushConnection.addListener 在已连接时会立即补发）
            AppLog.d(TAG, "call_offer deferred until onConnected callId=" + s.callId);
            return;
        }
        offerSent = true;
        if (OBSERVE_ONLY) {
            AppLog.d(TAG, "[OBSERVE_ONLY] would send call_offer callId=" + s.callId
                    + " to=" + s.calleeId + " video=" + s.videoEnabled);
            return;
        }
        PushConnection.sendCallOffer(s.calleeId, RELAY_OFFER, s.videoEnabled, s.callId);
    }

    /** 统一信令出口。OBSERVE_ONLY 下只打日志（C3 的影子模式核心）。 */
    private static void sendTo(int peerId, String type, String callId) {
        if (OBSERVE_ONLY) {
            AppLog.d(TAG, "[OBSERVE_ONLY] would send " + type
                    + " callId=" + callId + " to=" + peerId);
            return;
        }
        switch (type) {
            case "call_busy":
                PushConnection.sendCallBusy(peerId, callId);
                break;
            case "call_reject":
                PushConnection.sendCallReject(peerId, callId);
                break;
            case "call_cancel":
                PushConnection.sendCallCancel(peerId, callId);
                break;
            case "call_answer":
                PushConnection.sendCallSignal("call_answer", peerId, RELAY_OFFER);
                break;
            case "call_hangup":
                // Step 4（部分）：call_hangup 现在带 call_id（由 WebSocketManager.sendCallSignal
                // 的 call_hangup 分支写进报文），对端据此按 callId 优先匹配。
                PushConnection.sendCallSignal("call_hangup", peerId, callId != null ? callId : "");
                break;
            default:
                AppLog.e(TAG, "sendTo: unknown signaling type=" + type);
        }
    }

    /**
     * 写一条通话状态系统消息（"已拒绝" / "通话时长 MM:SS" / "无人接听" / "已取消"）。
     *
     * <p>改造前这段逻辑散在 {@code CallActivity}（rejected/ended）与
     * {@code MainActivity} 的来电对话框（rejected）两处，是"两条系统消息"的隐患来源。
     * 现在唯一落点在本类。
     *
     * <p><b>归属不变量</b>：服务端以<b>上报者</b>为 {@code sender_id}（客户端无权指定 sender），
     * 所以"谁上报"就等于"这条消息归谁"。四种 event 必须各自只有一个可能的上报者：
     * <ul>
     *   <li>{@code rejected} → 被叫（拒接 / 响铃中挂断）</li>
     *   <li>{@code ended}    → 主叫（挂断已接通的通话，含对端先挂断时的补报）</li>
     *   <li>{@code missed}   → 主叫（30s 无人接听）</li>
     *   <li>{@code canceled} → 主叫（对方接听前主动取消）</li>
     * </ul>
     * 改动归属前请先确认这条规则，否则会出现"同一条系统消息在两台手机上归不同人"。
     *
     * @param video 通话是否带视频。服务端据此把 {@code msg_type} 落成
     *              {@code call_video} / {@code call_voice}，客户端据此渲染摄像头/电话图标，
     *              并决定点这条记录是回拨视频还是语音。取自 {@code CallSession.videoEnabled}。
     */
    private static void reportCallStatus(int friendId, String event, int durationSec, boolean video) {
        if (OBSERVE_ONLY) {
            AppLog.d(TAG, "[OBSERVE_ONLY] would reportCallStatus event=" + event
                    + " friend=" + friendId + " dur=" + durationSec + "s video=" + video);
            return;
        }
        final Context ctx = appCtx;
        if (ctx == null) return;
        final int dur = Math.max(0, durationSec);
        new Thread(() -> {
            try {
                PreferencesManager prefs = PreferencesManager.getInstance(ctx);
                ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
                api.reportCallStatus(friendId, event, dur, video);
                AppLog.d(TAG, "reportCallStatus ok: " + event + " friend=" + friendId
                        + " dur=" + dur + "s video=" + video);
            } catch (Exception e) {
                AppLog.e(TAG, "reportCallStatus failed: " + event + " friend=" + friendId, e);
            }
        }, "CallStatusReport").start();
    }

    /** 拉起来电界面。OBSERVE_ONLY 时只打"would launch"（那是影子模式下唯一的验证手段）。 */
    private static void launchIncomingUi(CallSession s) {
        if (OBSERVE_ONLY) {
            AppLog.d(TAG, "[OBSERVE_ONLY] would launch callId=" + s.callId
                    + " incoming=" + s.incoming + " from=" + s.callerId
                    + " video=" + s.videoEnabled);
            return;
        }
        if (!LAUNCH_UI) {
            AppLog.d(TAG, "LAUNCH_UI=false, skip launching incoming UI callId=" + s.callId);
            return;
        }
        final Context ctx = appCtx;
        if (ctx == null) {
            AppLog.e(TAG, "launchIncomingUi: no appCtx");
            return;
        }

        // 来电人昵称/头像：旧路径由 ChatActivity 读 friend_<id> 缓存、MainActivity 查内存好友列表补全，
        // 那两条路径已在 C4 删除，这里等价接管 —— 读同一个 friend_<id> 缓存（PreferencesManager 在 :core）。
        // 缓存里没有的（从没聊过的好友）交由 CallActivity 自己的 api.getFriends() 兜底补全。
        String friendJson = null;
        String friendName = null;
        try {
            String cached = PreferencesManager.getInstance(ctx).getString("friend_" + s.callerId, null);
            if (cached != null && !cached.isEmpty()) {
                Models.User u = new Gson().fromJson(cached, Models.User.class);
                if (u != null) {
                    friendJson = cached;
                    friendName = u.nickname;
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "launchIncomingUi: read friend_ cache failed", e);
        }
        if (friendName == null || friendName.isEmpty()) friendName = "未知用户";

        try {
            // :core 看不见 :feature-chat，只能走类名字符串（见 Routes）
            Intent it = new Intent();
            it.setClassName(ctx, Routes.CALL_ACTIVITY);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            it.putExtra("incoming", true);
            it.putExtra("call_id", s.callId);
            it.putExtra("from_user_id", s.callerId);
            it.putExtra("friend_name", friendName);
            if (friendJson != null) it.putExtra("friend", friendJson);
            it.putExtra("video", s.videoEnabled);
            it.putExtra("offer_sdp", s.offerSdp);
            ctx.startActivity(it);
            AppLog.d(TAG, "launched CallActivity incoming callId=" + s.callId
                    + " from=" + s.callerId + " name=" + friendName);
        } catch (Exception e) {
            // Android 10+ 后台启动 Activity 会被系统拦截（不抛异常时也起不来）——兜底见下方通知钩子
            AppLog.e(TAG, "launchIncomingUi failed (后台启动 Activity 可能被系统拦截)", e);
        }

        // ── 通知兜底（批 1）──
        // 刻意放在 try/catch【之外】且无条件调用，不看 startActivity 的成败：
        //   · 被系统静默拦截时 **不抛异常**，只靠 catch 会漏掉最主要的失败形态（见上方 catch 注释）；
        //   · startActivity 成功时多出一条通知，代价可控 —— 同一时刻最多一通响铃来电，
        //     通知 id 固定、来电结束由实现方在 CallManager.Observer 里撤掉；
        //   · 通知本身带 call_id，点它走 CallActivity 的 adopt/incoming 分支，与 UI 路径同源。
        dispatchIncomingUiFallback(s, friendName);
    }

    /**
     * 调用来电通知兜底。抽成独立方法（而不是内联在 {@link #launchIncomingUi} 里）是为了让
     * 钩子契约<b>可被纯 JVM 单测直接覆盖</b>：{@code launchIncomingUi} 需要真实 Context
     * （{@code appCtx}）与能解析的 {@code Routes.CALL_ACTIVITY}，单测里两样都没有。
     *
     * <p>本方法只在主线程被调用（{@code onOffer} → 锁外）。实现方抛任何异常都被吞掉：
     * 来电通知是可选增强，信令/超时/铃声都不能因它受损。
     */
    static void dispatchIncomingUiFallback(CallSession s, String friendName) {
        final IncomingUiFallback fallback = incomingUiFallback;
        if (fallback == null) return;
        try {
            fallback.onIncomingUiFallback(s.callId, s.callerId, s.videoEnabled, friendName);
        } catch (Throwable t) {
            AppLog.e(TAG, "incomingUiFallback failed callId=" + s.callId, t);
        }
    }

    // =================================================================
    //  定时器
    // =================================================================

    private static void armRingTimeout(final long ms, final EndReason why) {
        if (OBSERVE_ONLY) return;   // 影子模式绝不启动超时（否则会打断旧路径已接通的通话）
        clearRingTimeout();
        ringTimeout = () -> {
            synchronized (LOCK) {
                CallSession s = current;
                if (s == null || !s.isRinging()) return;
                AppLog.d(TAG, "ring timeout fired after " + ms + "ms callId=" + s.callId
                        + " state=" + s.state);
                if (s.state == CallSession.State.OUTGOING_RINGING) {
                    // 主叫 30s 无人接听：由**主叫**写一条「无人接听」（上报者=主叫 →
                    // sender=主叫，主叫侧显示在右、被叫侧显示在左）。这是「未接」唯一的写入口，
                    // 保证该场景归属恒为主叫。
                    sendTo(s.calleeId, "call_cancel", s.callId);
                    reportCallStatus(s.calleeId, "missed", 0, s.videoEnabled);
                } else {
                    // 被叫 45s 兜底：只在主叫客户端已死/离线、call_cancel 到不了时才会走到这里
                    // （正常情况下主叫的 30s 先触发，本端的定时器已被 clearTimers() 撤掉）。
                    // 刻意**只发信令、不写系统消息**：上报者是被叫，写出来会是 sender=被叫的
                    // 「已拒绝」——归属与文案双错，且会破坏「未接恒为 sender=主叫」这条不变量。
                    sendTo(s.callerId, "call_reject", s.callId);
                }
                teardown(why);
            }
        };
        MAIN.postDelayed(ringTimeout, ms);
    }

    private static void armMediaWatchdog() {
        if (OBSERVE_ONLY) return;   // 同上：影子模式不启动看门狗
        clearMediaWatchdog();
        mediaTimeout = () -> {
            synchronized (LOCK) {
                CallSession s = current;
                if (s == null || s.state != CallSession.State.ACTIVE || firstMediaSeen) return;
                AppLog.e(TAG, "media watchdog fired: no media in " + MEDIA_WATCHDOG_MS
                        + "ms callId=" + s.callId);
                sendTo(s.peerId(), "call_hangup", s.callId);
                teardown(EndReason.MEDIA_FAIL);
            }
        };
        MAIN.postDelayed(mediaTimeout, MEDIA_WATCHDOG_MS);
    }

    private static void clearRingTimeout() {
        if (ringTimeout != null) {
            MAIN.removeCallbacks(ringTimeout);
            ringTimeout = null;
        }
    }

    private static void clearMediaWatchdog() {
        if (mediaTimeout != null) {
            MAIN.removeCallbacks(mediaTimeout);
            mediaTimeout = null;
        }
    }

    private static void clearTimers() {
        clearRingTimeout();
        clearMediaWatchdog();
    }

    // =================================================================
    //  去重 / 工具
    // =================================================================

    private static String newCallId() {
        return UUID.randomUUID().toString();
    }

    /** 记录已结束的 callId（LRU 上限 {@link #DEDUP_MAX}），用于吞掉在途重复信令。 */
    private static void rememberEnded(String callId) {
        ENDED_CALL_IDS.put(callId, System.currentTimeMillis());
        while (ENDED_CALL_IDS.size() > DEDUP_MAX) {
            Iterator<String> it = ENDED_CALL_IDS.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    private static boolean isRecentlyEnded(String callId) {
        Long at = ENDED_CALL_IDS.get(callId);
        if (at == null) return false;
        if (System.currentTimeMillis() - at > DEDUP_TTL_MS) {
            ENDED_CALL_IDS.remove(callId);
            return false;
        }
        return true;
    }

    private static int myUserId() {
        Context ctx = appCtx;
        if (ctx == null) return -1;
        try {
            return PreferencesManager.getInstance(ctx).getUserId();
        } catch (Exception e) {
            AppLog.e(TAG, "myUserId failed", e);
            return -1;
        }
    }

    private static void notifyStateChanged(CallSession s) {
        for (Observer o : OBSERVERS) {
            try {
                o.onStateChanged(s);
            } catch (Throwable t) {
                AppLog.e(TAG, "observer.onStateChanged failed", t);
            }
        }
    }

    private static void notifyEnded(String callId, EndReason why) {
        // 快照遍历：观察者在回调里 removeObserver 不能破坏遍历
        List<Observer> snapshot = new ArrayList<>(OBSERVERS);
        for (Observer o : snapshot) {
            try {
                o.onCallEnded(callId, why);
            } catch (Throwable t) {
                AppLog.e(TAG, "observer.onCallEnded failed", t);
            }
        }
    }

    // =================================================================
    //  PushConnection 监听器
    //  (1) 覆写并处理的 9 个；(2) 必须实现但刻意留空的 6 个；
    //  (3) 保持接口 default、本类完全不出现的：onCallOffer / onCallIce /
    //      onCallMediaStop / 群聊 10 条 / 钱包 5 条 / 商城 1 条。原因见类注释。
    // =================================================================

    private static final class SignalingListener implements WebSocketManager.Listener {

        // ---- (1) 真正处理 ----

        @Override
        public void onConnected() {
            CallManager.onConnected();
        }

        @Override
        public void onCallOfferWithId(int fromUserId, String offerSdp,
                                      boolean videoEnabled, String callId) {
            CallManager.onOffer(fromUserId, offerSdp, videoEnabled, callId);
        }

        @Override
        public void onCallAnswer(String answerSdp) {
            CallManager.onAnswer();
        }

        @Override
        public void onCallHangup(int fromUserId) {
            // 旧通道（报文无 call_id）：callId 传 null，matches() 退化为"按对端 userId 认领"
            CallManager.onHangup(fromUserId, null);
        }

        @Override
        public void onCallHangupWithId(int fromUserId, String callId) {
            CallManager.onHangup(fromUserId, callId);
        }

        @Override
        public void onCallBusy(int fromUserId, String callId) {
            CallManager.onBusy(fromUserId, callId);
        }

        @Override
        public void onCallReject(int fromUserId, String callId) {
            CallManager.onReject(fromUserId, callId);
        }

        @Override
        public void onCallCancel(int fromUserId, String callId) {
            CallManager.onCancel(fromUserId, callId);
        }

        @Override
        public void onCallMedia(int fromUserId, String base64Data, int seq, String mediaType) {
            CallManager.onMediaSeen();
        }

        @Override
        public void onCallVideo(int fromUserId, String base64Data) {
            CallManager.onMediaSeen();
        }

        // ---- (2) 接口抽象方法，必须实现；刻意留空（不是漏，原因见类注释）----
        //      onDisconnected 尤其重要：网络抖动不结束通话，交给 10s 媒体看门狗。

        @Override
        public void onDisconnected() {
            // 刻意留空。语义见类注释第 (2) 条。
        }

        @Override
        public void onNewMessage(com.familychat.app.api.Models.Message msg) {
            // 消息属 NotificationDispatcher / ChatActivity 职责
        }

        @Override
        public void onFriendRequest() {
            // 好友请求属 NotificationDispatcher / MainActivity 职责
        }

        @Override
        public void onFriendRequestHandled(int fsId, String action,
                                           com.familychat.app.api.Models.User byUser) {
            // 同上
        }

        @Override
        public void onUserOnline(int userId) {
            // 在线状态属 MainActivity 好友列表职责
        }

        @Override
        public void onUserOffline(int userId) {
            // 同上
        }
    }
}
