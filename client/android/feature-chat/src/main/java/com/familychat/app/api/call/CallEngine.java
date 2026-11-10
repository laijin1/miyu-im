package com.familychat.app.api.call;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.widget.ImageView;

import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebRTCManager;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.Routes;

import org.webrtc.SurfaceViewRenderer;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程级通话媒体引擎（静态门面）。
 *
 * <h3>为什么存在</h3>
 * 改造前 {@code WebRTCManager} 是 {@code CallActivity} 的实例字段（CallActivity.java:86，
 * new 在 :524），媒体资源（AudioRecord / AudioTrack / relayCamera / 采集线程）全部挂在
 * Activity 实例上：Activity 一销毁 → {@code onDestroy → cleanup()} → 媒体即断。
 * 本类把媒体引擎的所有权从 Activity 提到进程级，使通话能够活过通话页
 * （悬浮窗 / 只挂通知的场景）。
 *
 * <h3>与 {@link CallManager} 的分工</h3>
 * {@code CallManager} 管<b>信令与状态机</b>（:core，纯 static，见 CallManager.java:71,177）；
 * 本类管<b>媒体</b>（WebRTCManager 的持有、relay 视图接线、媒体信令的分发）。
 * 二者都把状态收敛到进程级，但本类多一层复杂度：它持有 {@code WebRTCManager} 这个
 * 需要 View 才能构造的对象。
 *
 * <h3>线程约定（与 CallManager 的关键差异）</h3>
 * {@code CallManager} 的观察者回调是<b>同步</b>的（CallManager.java:817-837 无 post），
 * 因为它的所有入口都在主线程。本类<b>不能</b>照抄这一点：
 * {@code WebRTCManager} 的事件可能来自后台线程（relay 采集线程 {@code RelayCapture}、
 * 解码线程 {@code RelayVideoDecode}），因此本类的所有观察者通知都用
 * {@link #MAIN}.post 切回主线程。
 *
 * <h3>注册与生命周期</h3>
 * {@link #install(Context)} 由 {@code FamilyChatApp.onCreate()} 调用一次，
 * 并且<b>刻意不反注册</b>（与 {@code CallManager} 同款，见 CallManager.java:36-38）——
 * 媒体信令必须在通话页销毁之后仍然到达，否则"通话活过 Activity"无从谈起。
 */
public final class CallEngine {

    private static final String TAG = "CallEngine";

    /** 引擎事件回调。所有方法都会在主线程被调用（本类统一 MAIN.post）。 */
    public interface Observer {
        /** 媒体通路已就绪（relay 对端应答 / WebRTC 连接建立）。 */
        void onEngineReady();

        /** 引擎已收尾（本端 end()、对端挂断、或 WebRTC 报连接结束）。 */
        void onEngineEnded();

        /** 引擎错误（WebRTCManager 的 onError / 本类自身的失败）。 */
        void onError(String error);

        /**
         * 对端关/开摄像头（{@code call_video_state}）。
         * 设 default：既有实现者（CallActivity）无需改动即可编译。
         */
        default void onRemoteVideoStateChanged(boolean enabled) {}

        /**
         * 本地视频起不来（前后摄都打不开）。注意：这是"没有画面"，不是"通话失败"，
         * 实现者不应据此结束通话。设 default，同上。
         */
        default void onVideoStartFailed() {}
    }

    // =================================================================
    //  内部状态
    // =================================================================

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();

    private static volatile Context appCtx;
    private static volatile boolean installed;

    /** 媒体引擎。null = 没有通话。 */
    private static WebRTCManager engine;

    /**
     * 本引擎所属通话的 callId（M6）。
     *
     * <p>在 {@link #start} 成功安装引擎时从 {@code CallManager.getCurrentCall()} 快照，
     * 供 {@link #end} 反向通知 CallManager 时做严格匹配 —— end() 执行时点可能已经
     * 不属于同一通电话，那时再去取就晚了。
     */
    private static String currentCallId;

    /** 一次性通知标记（每通电话复位）。 */
    private static boolean readyNotified;
    private static boolean endedNotified;

    /**
     * 渲染目标缓存。
     *
     * <p>注入顺序是被固定的（CallActivity.onCreate）：先 {@link #setRendererViews}，
     * 后 {@link #startOutgoing}/{@link #acceptIncoming}。因此 start 时这些字段必然已就绪。
     * Activity 重建时会再次 {@code setRendererViews}，此时 engine != null → 走重绑分支。
     */
    private static SurfaceViewRenderer cachedLocalRenderer;
    private static SurfaceViewRenderer cachedRemoteRenderer;
    private static TextureView cachedLocalPreviewView;
    private static ImageView cachedLocalPreviewFrame;
    private static ImageView cachedRemoteVideoView;

    private CallEngine() {
    }

    // =================================================================
    //  生命周期
    // =================================================================

    /** 幂等安装。由 {@code FamilyChatApp.onCreate()} 调用（与 CallManager.install 并列）。 */
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
        // 那条路径会回调进来取 LOCK —— 持锁注册会自我死锁（与 CallManager.install 同款）。
        // 刻意不反注册：媒体信令必须在通话页销毁后仍然到达。
        PushConnection.addListener(new SignalingListener());
        // 响铃：订阅状态机。同样刻意不反注册（与上面一致）—— 来电可能在
        // 通话页存在之前就到达（CallManager.onOffer 先 transition 再 launchIncomingUi），
        // 反注册会让"没有 UI 时也不响铃"。
        CallManager.addObserver(new CallStateObserver());
        AppLog.d(TAG, "installed");
    }

    // =================================================================
    //  响铃（订阅 CallManager 状态机）
    // =================================================================

    /**
     * 把"通话状态"翻译成"响不响铃"。
     *
     * <p>规则只有两条：
     * <ul>
     *   <li>进 {@code OUTGOING_RINGING} / {@code INCOMING_RINGING} → 起铃；</li>
     *   <li>其它任何状态（{@code ACTIVE} / {@code ENDING} / {@code IDLE} / null）→ 停铃。</li>
     * </ul>
     *
     * <p>为什么用"非响铃即停"而不是"出响铃状态才停"：{@code CallManager} 的收尾路径
     * 有 12 条（{@code onHangup/onBusy/onReject/onCancel/armRingTimeout/mediaWatchdog/
     * rejectCall/endCall/cancelCall/…}），但它们<b>全部汇聚到 {@code teardown()}</b>，
     * 由它发 {@link CallManager.Observer#onCallEnded} 与一次 IDLE 的
     * {@code onStateChanged}。白名单式判断（只认两个 ringing 状态）天然覆盖全部结束点，
     * 不需要在每条路径上单独停铃 —— 漏一条就是"铃声永远停不下来"。
     *
     * <p>两个回调都调 {@code stop()} 是刻意的双保险：{@code onCallEnded} 覆盖
     * "状态没经过非响铃态的收尾"，{@code onStateChanged} 覆盖"电话接通"这类
     * 不触发 onCallEnded 的正常转场。{@link RingtonePlayer#stop()} 幂等，重复调用无副作用。
     */
    static final class CallStateObserver implements CallManager.Observer {

        @Override
        public void onStateChanged(CallSession s) {
            if (s == null) {
                RingtonePlayer.stop();
                return;
            }
            final boolean ringing = (s.state == CallSession.State.OUTGOING_RINGING
                    || s.state == CallSession.State.INCOMING_RINGING);
            if (ringing) {
                RingtonePlayer.start(appCtx);
            } else {
                RingtonePlayer.stop();
            }
        }

        @Override
        public void onCallEnded(String callId, CallManager.EndReason reason) {
            // 与 onStateChanged 的停铃重复，但收尾必须无条件停：
            // 任何"漏停"都会变成挂断后铃声一直响，代价远大于一次幂等调用的开销。
            AppLog.d(TAG, "onCallEnded callId=" + callId + " reason=" + reason
                    + " — stop ringtone + release engine");
            RingtonePlayer.stop();
            // H3 修复：CallManager.teardown 是全部 12+ 条收尾路径的唯一汇聚点。
            // 引擎若只靠"对端 call_hangup"释放，会漏掉 busy/reject/cancel/30s/45s/10s
            // 六条路径（CallEngine.SignalingListener 没有这几个回调），导致
            // relay 采集/播放线程 + MODE_IN_COMMUNICATION + 麦克风残留 —— 而前台
            // 通知已随 CallService ticker 消失，用户看不到"仍在录音"。
            // end() 幂等（engine==null 早返回），不会与 CallFloatingWindow /
            // CallActivity 的既有"先 CallManager 后 CallEngine"顺序重复释放。
            end();
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
    //  "App 内浮层已接管"标志（S2 抑制方案）
    // =================================================================

    /** "App 内浮层已接管"标志（S2 抑制方案）。true 时 CallActivity.onStop
     *  不显示系统浮窗 —— 因为应用内浮层已经在 App 里展示了通话。
     *  由 CallOverlayController.attach/detach 置位/清除。 */
    private static volatile boolean inAppHostActive;

    public static void setInAppHostActive(boolean active) {
        inAppHostActive = active;
        AppLog.d(TAG, "setInAppHostActive: " + active);
    }

    public static boolean isInAppHostActive() {
        return inAppHostActive;
    }

    // =================================================================
    //  渲染目标注入
    // =================================================================

    /**
     * 注入四个渲染目标。必须先于 {@link #startOutgoing}/{@link #acceptIncoming} 调用。
     *
     * <p>engine == null → 只缓存，等 start 时用来构造 WebRTCManager。
     * <p>engine != null（Activity 重建）→ 调 {@code setRenderers} 重绑并 re-arm relay 预览。
     */
    public static void setRendererViews(SurfaceViewRenderer local,
                                        SurfaceViewRenderer remote,
                                        TextureView localPreview,
                                        ImageView localPreviewFrame) {
        final WebRTCManager e;
        final ImageView remoteVideo;
        synchronized (LOCK) {
            cachedLocalRenderer = local;
            cachedRemoteRenderer = remote;
            cachedLocalPreviewView = localPreview;
            cachedLocalPreviewFrame = localPreviewFrame;
            remoteVideo = cachedRemoteVideoView;
            e = engine;
        }
        if (e == null) {
            AppLog.d(TAG, "setRendererViews: engine==null — views cached for next start");
            return;
        }
        AppLog.d(TAG, "setRendererViews: engine!=null — rebinding (Activity recreated)");
        e.setRenderers(local, remote);
        e.setRelayLocalPreviewView(localPreview);
        e.setRelayLocalPreviewFrame(localPreviewFrame);
        e.setRelayRemoteView(remoteVideo);
    }

    /**
     * 注入 relay 远端帧显示层（remoteVideoView）。
     *
     * <p>单独一个 setter 而不是塞进 {@link #setRendererViews}：与 WebRTCManager 的
     * {@code setRelayRemoteView} 一一对应（WebRTCManager.java 的 relay 视图接线共有三个：
     * 本地预览锚点 TextureView / 本地预览显示层 ImageView / 远端帧显示层 ImageView）。
     */
    public static void setRelayRemoteView(ImageView view) {
        final WebRTCManager e;
        synchronized (LOCK) {
            cachedRemoteVideoView = view;
            e = engine;
        }
        if (e != null) e.setRelayRemoteView(view);
    }

    // =================================================================
    //  通话入口
    // =================================================================

    /** 外呼：构造引擎并启动 relay 采集（主叫侧）。 */
    public static boolean startOutgoing(int userId, boolean video) {
        return start(userId, video, true, null);
    }

    /** 来电接听：构造引擎并启动 relay 采集（被叫侧）。{@code offerSdp} 在 relay 模式下是字面量 "relay"。 */
    public static boolean acceptIncoming(int fromUserId, String offerSdp, boolean video) {
        return start(fromUserId, video, false, offerSdp);
    }

    private static boolean start(int userId, boolean video, boolean outgoing, String offerSdp) {
        final Context ctx = appCtx;
        if (ctx == null) {
            // 刻意不 notifyError：构造失败时旧行为是"UI 留在通话页 + 文案提示"
            // （CallActivity 的 initFailed），由调用方根据返回值决定 UI，而不是直接 finish。
            AppLog.e(TAG, "start ignored: appCtx==null (CallEngine.install 未调用?)");
            return false;
        }

        final SurfaceViewRenderer local;
        final SurfaceViewRenderer remote;
        final TextureView previewView;
        final ImageView previewFrame;
        final ImageView remoteVideo;
        // M6：锁外取会话快照。CallManager 与 CallEngine 各有一把锁，既有加锁方向是
        // CallManager.LOCK → CallEngine.LOCK（CallManager.teardown → 本类 CallStateObserver
        // → end()），在 CallEngine 的锁内反调 CallManager 会造成反向加锁。
        final CallSession session = CallManager.getCurrentCall();
        synchronized (LOCK) {
            if (engine != null) {
                AppLog.d(TAG, "start ignored: engine already exists (outgoing=" + outgoing + ")");
                return false;
            }
            local = cachedLocalRenderer;
            remote = cachedRemoteRenderer;
            previewView = cachedLocalPreviewView;
            previewFrame = cachedLocalPreviewFrame;
            remoteVideo = cachedRemoteVideoView;
        }

        // 慢操作放锁外：WebRTCManager 构造会 EglBase.create / PeerConnectionFactory /
        // 相机 startCapture，全部是重操作（见 WebRTCManager 构造函数）。
        // 传 Application Context：WebRTCManager 内部也只保留 getApplicationContext()，
        // 但传 appCtx 可以避免构造函数期间短暂持有 Activity。
        final WebRTCManager created;
        try {
            created = new WebRTCManager(ctx, local, remote, video);
        } catch (Exception e) {
            // 同 appCtx==null 分支：不 notifyError，交给调用方渲染"初始化失败"文案
            AppLog.e(TAG, "WebRTCManager construction failed", e);
            return false;
        }

        synchronized (LOCK) {
            if (engine != null) {
                // 竞争失败：把刚建出来的实例丢弃（endCall 内部会调 stopRelayCall，幂等）
                AppLog.e(TAG, "start race lost: another engine was installed concurrently");
                try { created.endCall(); } catch (Exception ignored) {}
                return false;
            }
            engine = created;
            readyNotified = false;
            endedNotified = false;
            currentCallId = (session != null) ? session.callId : null;
        }

        // 信令出口与回调：改造前在 CallActivity.initWebRTC（:541-590），现随引擎所有权一起下沉。
        created.setSignalingSender((type, targetUserId, data) -> {
            if ("call_media".equals(type)) {
                PushConnection.sendCallMedia(targetUserId, data, 0, "audio");
            } else if ("call_video".equals(type)) {
                // Dedicated sender keeps the base64 frame in the "data" field
                PushConnection.sendCallVideo(targetUserId, data);
            } else if ("call_video_state".equals(type)) {
                // 状态信令：SignalingSender 只有扁平 String 通道，这里把布尔解回来
                // （约定 "1"/"true" 为开，其余为关）。专用发送方法负责写成 enabled 字段。
                PushConnection.sendCallVideoState(targetUserId,
                        "1".equals(data) || Boolean.parseBoolean(data));
            } else if ("call_media_stop".equals(type)) {
                PushConnection.sendCallMediaStop(targetUserId);
            } else {
                PushConnection.sendCallSignal(type, targetUserId, data);
            }
        });
        created.setListener(new EngineListener());

        // relay 视图接线（必须在 startRelayCall/acceptRelayCall 之前 —— 采集线程与
        // relay 相机预览会立刻开始找这些 View）
        created.setRelayLocalPreviewView(previewView);
        created.setRelayLocalPreviewFrame(previewFrame);
        created.setRelayRemoteView(remoteVideo);

        if (outgoing) {
            created.startRelayCall(userId, video);
        } else {
            created.acceptRelayCall(userId, offerSdp != null ? offerSdp : "", video);
        }
        // 通话已起：拉起前台服务保活（进程级，与引擎生命周期同拍）。
        startCallService();
        // 音频路由：进入 MODE_IN_COMMUNICATION + 默认扬声器
        // （接管改造前 CallActivity 在进入通话页时无条件执行的 setSpeakerOn(true)）。
        AudioRouter.init(ctx);
        AppLog.d(TAG, "start ok: outgoing=" + outgoing + " user=" + userId + " video=" + video
                + " relay=" + created.isRelayMode());
        return true;
    }

    /** 收尾：释放媒体资源。幂等（engine 已为 null 时直接返回）。 */
    public static void end() {
        final WebRTCManager e;
        synchronized (LOCK) {
            e = engine;
            engine = null;
        }
        if (e == null) {
            AppLog.d(TAG, "end: engine already null");
            return;
        }
        AppLog.d(TAG, "end: releasing engine");
        // endCall() 内部已调 stopRelayCall()；这里再调一次是为了让"释放 relay 资源"
        // 不依赖 endCall 的实现细节（stopRelayCall 幂等，见 WebRTCManager.endCall 注释）。
        try {
            e.endCall();
        } catch (Exception ex) {
            AppLog.e(TAG, "end: endCall failed", ex);
        }
        try {
            e.stopRelayCall();
        } catch (Exception ex) {
            AppLog.e(TAG, "end: stopRelayCall failed", ex);
        }
        // 通话已收尾：停掉前台服务（通知随 stopForeground 一并移除）。
        stopCallService();
        // 恢复系统音频模式（接管改造前 CallActivity.cleanup 的音频恢复）。
        AudioRouter.release();
        // 通话结束 → 悬浮窗必须消失（不能留一个点了没反应的浮窗）
        CallFloatingWindow.hide();
        notifyEnded();
        // M6 修复：反向通知 CallManager 清 current（引擎已收尾，但状态机可能
        // 仍停在 ACTIVE —— 见 CallManager.onEngineEnded 的说明）。
        // 先传快照再清空：传 null 会被 CallManager 当作"匹配任意通话"。
        CallManager.onEngineEnded(currentCallId);
        currentCallId = null;
    }

    // =================================================================
    //  前台服务挂钩（CallService 在 :app，本类在 :feature-chat → 只能走类名字符串）
    // =================================================================

    /**
     * 拉起通话前台服务。失败只打日志 —— 服务只是"保活 + 通知"，
     * 启动失败绝不能影响通话本身（媒体与信令都不依赖它）。
     *
     * <p>调用时机：{@link #start} 在通话已起之后。此时通常有已启动的 Activity 在栈顶，
     * 因此不触发 API 31+ 的"后台启动前台服务"限制；若平台仍然拒绝，
     * 会被下面的 catch 记录，通话继续（只是少了保活）。
     */
    private static void startCallService() {
        final Context ctx = appCtx;
        if (ctx == null) {
            AppLog.e(TAG, "startCallService skipped: appCtx==null");
            return;
        }
        try {
            Intent it = new Intent();
            it.setClassName(ctx, Routes.CALL_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
            AppLog.d(TAG, "startCallService ok");
        } catch (Exception e) {
            AppLog.e(TAG, "startCallService failed (call continues without keep-alive)", e);
        }
    }

    /** 停掉通话前台服务（幂等：服务不存在时 stopService 无副作用）。 */
    private static void stopCallService() {
        final Context ctx = appCtx;
        if (ctx == null) {
            AppLog.e(TAG, "stopCallService skipped: appCtx==null");
            return;
        }
        try {
            Intent it = new Intent();
            it.setClassName(ctx, Routes.CALL_SERVICE);
            ctx.stopService(it);
            AppLog.d(TAG, "stopCallService ok");
        } catch (Exception e) {
            AppLog.e(TAG, "stopCallService failed", e);
        }
    }

    // =================================================================
    //  悬浮窗挂钩（实现全在 CallFloatingWindow；这里只做转发 + 可读性命名）
    // =================================================================

    /**
     * 显示通话悬浮窗（通话页 onStop 时调用）。
     *
     * <p>无 {@code SYSTEM_ALERT_WINDOW} 授权时返回 false —— 调用方无需自己判权限，
     * {@link CallFloatingWindow#show} 内部已检查。
     */
    public static boolean showFloatingWindow(Context ctx) {
        return CallFloatingWindow.show(ctx);
    }

    /** 隐藏通话悬浮窗（通话页 onStart 时调用；幂等）。 */
    public static void hideFloatingWindow() {
        CallFloatingWindow.hide();
    }

    // =================================================================
    //  查询 / 转发（供通话页使用）
    // =================================================================

    public static boolean hasEngine() {
        return current() != null;
    }

    public static boolean isRelayMode() {
        final WebRTCManager e = current();
        return e != null && e.isRelayMode();
    }

    public static void setStreamingEnabled(boolean enabled) {
        final WebRTCManager e = current();
        if (e != null) e.setRelayStreamingEnabled(enabled);
    }

    public static void toggleMute() {
        final WebRTCManager e = current();
        if (e != null) e.toggleMute();
    }

    /** 当前是否已闭麦。引擎不存在时返回 false。 */
    public static boolean isMuted() {
        final WebRTCManager e = current();
        return e != null && e.isMuted();
    }

    /**
     * 本地静音对方（丙方案）：纯本地拦掉 relay 下行播放，对方不知道。
     *
     * <p>与 {@link #toggleMute()}（闭麦：拦上行发送）方向相反 —— 那条是"对方听不到我"，
     * 这条是"我听不到对方"。两者互不影响，可同时成立。
     * 引擎不存在时静默返回（与 toggleMute 同款）。
     */
    public static void toggleLocalMute() {
        final WebRTCManager e = current();
        if (e != null) e.setRelayLocalMuted(!e.isRelayLocalMuted());
    }

    /** 当前是否已"静音对方"。引擎不存在时返回 false。 */
    public static boolean isLocalMuted() {
        final WebRTCManager e = current();
        return e != null && e.isRelayLocalMuted();
    }

    /** 本地视频上行是否开着（"关闭摄像头"的开关状态）。引擎不存在时返回 false。 */
    public static boolean isVideoSendEnabled() {
        final WebRTCManager e = current();
        return e != null && e.isVideoSendEnabled();
    }

    /**
     * 关/开本地摄像头（视频上行总开关）。引擎不存在时静默返回（与 toggleMute 同款）。
     * 只对 relay 模式生效，见 {@link WebRTCManager#setVideoSendEnabled(boolean)}。
     */
    public static void setVideoSendEnabled(boolean enabled) {
        final WebRTCManager e = current();
        if (e != null) e.setVideoSendEnabled(enabled);
    }

    /** 对端视频是否开着（最近一次 call_video_state 的状态）。引擎不存在时返回 true。 */
    public static boolean isRemoteVideoEnabled() {
        final WebRTCManager e = current();
        return e == null || e.isRemoteVideoEnabled();
    }

    public static void switchCamera() {
        final WebRTCManager e = current();
        if (e != null) e.switchCamera();
    }

    private static WebRTCManager current() {
        synchronized (LOCK) {
            return engine;
        }
    }

    // =================================================================
    //  事件通知（一律 MAIN.post —— 本类的事件源含后台线程）
    // =================================================================

    private static void notifyReady() {
        synchronized (LOCK) {
            if (readyNotified) return;
            readyNotified = true;
        }
        AppLog.d(TAG, "notifyReady");
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) {
                try {
                    o.onEngineReady();
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onEngineReady failed", t);
                }
            }
        });
    }

    private static void notifyEnded() {
        synchronized (LOCK) {
            if (endedNotified) return;
            endedNotified = true;
        }
        AppLog.d(TAG, "notifyEnded");
        MAIN.post(() -> {
            // 快照遍历：观察者在回调里 removeObserver 不能破坏遍历
            for (Observer o : new CopyOnWriteArrayList<>(OBSERVERS)) {
                try {
                    o.onEngineEnded();
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onEngineEnded failed", t);
                }
            }
        });
    }

    private static void notifyError(String error) {
        AppLog.e(TAG, "notifyError: " + error);
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) {
                try {
                    o.onError(error);
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onError failed", t);
                }
            }
        });
    }

    /**
     * 对端视频开关状态变化。
     *
     * <p>与 {@link #notifyReady()} / {@link #notifyEnded()} 不同，这里<b>不做</b>"只通知一次"的
     * 去重：开关可以反复切换，每次都必须送达 UI。
     */
    private static void notifyRemoteVideoStateChanged(boolean enabled) {
        AppLog.d(TAG, "notifyRemoteVideoStateChanged enabled=" + enabled);
        MAIN.post(() -> {
            // 快照遍历：观察者在回调里 removeObserver 不能破坏遍历
            for (Observer o : new CopyOnWriteArrayList<>(OBSERVERS)) {
                try {
                    o.onRemoteVideoStateChanged(enabled);
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onRemoteVideoStateChanged failed", t);
                }
            }
        });
    }

    /** 本地视频起不来（前后摄都打不开）。属"无画面"而非"通话失败"，观察者不应据此结束通话。 */
    private static void notifyVideoStartFailed() {
        AppLog.e(TAG, "notifyVideoStartFailed");
        MAIN.post(() -> {
            for (Observer o : new CopyOnWriteArrayList<>(OBSERVERS)) {
                try {
                    o.onVideoStartFailed();
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onVideoStartFailed failed", t);
                }
            }
        });
    }

    // =================================================================
    //  WebRTCManager.Listener
    // =================================================================

    private static final class EngineListener implements WebRTCManager.Listener {

        @Override
        public void onCallConnected() {
            notifyReady();
        }

        /**
         * relay 模式不产生本事件：它只在 {@code PeerConnectionObserver.onAddStream} 里触发，
         * 而 relay 模式不建 PeerConnection（WebRTCManager.startRelayCall/acceptRelayCall）。
         * 因此 CallEngine.Observer 没有对应回调，此处留空。
         */
        @Override
        public void onRemoteVideoStarted() {
            AppLog.d(TAG, "onRemoteVideoStarted (P2P-only, no observer callback by design)");
        }

        /** 对端关/开摄像头 → 透传给 UI 观察者。 */
        @Override
        public void onRemoteVideoStateChanged(boolean enabled) {
            notifyRemoteVideoStateChanged(enabled);
        }

        /** 前后摄都打不开 → 透传给 UI 观察者（不是 onError：通话本身仍然正常）。 */
        @Override
        public void onVideoStartFailed() {
            notifyVideoStartFailed();
        }

        @Override
        public void onCallEnded() {
            notifyEnded();
        }

        @Override
        public void onError(String error) {
            notifyError(error);
        }
    }

    // =================================================================
    //  媒体信令监听（改造前是 CallActivity 的匿名 wsListener，:300-387）
    //
    //  刻意不反注册：见 install() 注释。所有转发都先取快照并判空 —— 通话页销毁后
    //  引擎可能已被 end()，此时到达的媒体帧直接丢弃，不抛异常。
    // =================================================================

    private static final class SignalingListener implements WebSocketManager.Listener {

        @Override
        public void onCallAnswer(String answerSdp) {
            AppLog.d(TAG, "sig recv call_answer relay=" + "relay".equals(answerSdp));
            if ("relay".equals(answerSdp)) {
                // relay 模式：对端已接听，媒体通路可以建立
                notifyReady();
            } else {
                final WebRTCManager e = current();
                if (e != null) e.onRemoteSignaling("call_answer", answerSdp);
            }
        }

        @Override
        public void onCallIce(String candidateJson) {
            final WebRTCManager e = current();
            if (e != null) e.onRemoteSignaling("call_ice", candidateJson);
        }

        @Override
        public void onCallMedia(int fromUserId, String base64Data, int seq, String mediaType) {
            final WebRTCManager e = current();
            if (e != null) e.onRelayMediaChunk(base64Data, seq, mediaType);
        }

        @Override
        public void onCallVideo(int fromUserId, String base64Data) {
            final WebRTCManager e = current();
            if (e != null) e.onRelayVideoFrame(base64Data);
        }

        /**
         * 对端关/开摄像头（{@code call_video_state}）。与 {@link #onCallVideo}（帧数据）不同：
         * 这条只表达状态、无 data，因此只更新引擎状态并通知 UI，不碰渲染。
         * 引擎已 end() 时静默丢弃（与其它转发一致）。
         */
        @Override
        public void onCallVideoState(int fromUserId, boolean enabled) {
            AppLog.d(TAG, "sig recv call_video_state enabled=" + enabled + " from=" + fromUserId);
            final WebRTCManager e = current();
            if (e != null) e.setRemoteVideoEnabled(enabled);
        }

        @Override
        public void onCallMediaStop(int fromUserId) {
            AppLog.d(TAG, "sig recv call_media_stop from=" + fromUserId);
            final WebRTCManager e = current();
            if (e != null) e.onRelayMediaStop();
        }

        @Override
        public void onCallHangup(int fromUserId) {
            // 老通道（报文无 call_id）：只能按对端 userId 认领，保持原行为
            AppLog.d(TAG, "sig recv call_hangup from=" + fromUserId + " — ending engine");
            end();
        }

        /**
         * 对端挂断（带 callId，A4 起 {@code WebSocketManager} 实际派发的是本方法）。
         *
         * <p>有 callId 时<b>严格匹配</b>：与 {@code CallSession.matches()} 不同，这里不回退到
         * "按对端 userId 认领" —— 那正是"同一对端上一通电话的迟到 call_hangup 打死当前通话"
         * 的成因。callId 缺失（老客户端）或本地无通话时才退回 {@link #onCallHangup(int)} 的语义。
         */
        @Override
        public void onCallHangupWithId(int fromUserId, String callId) {
            AppLog.d(TAG, "sig recv call_hangup from=" + fromUserId
                    + " callId=" + callId + " — ending engine");
            final CallSession s = CallManager.getCurrentCall();
            // 有 callId 时严格匹配：避免"旧通话的迟到挂断"打死当前通话
            if (s != null && callId != null && !callId.isEmpty()
                    && !callId.equals(s.callId)) {
                AppLog.d(TAG, "call_hangup ignored (callId mismatch) localCallId=" + s.callId);
                return;
            }
            end();
        }

        // ---- 接口抽象方法（WebSocketManager.Listener:61-67），必须实现；本类刻意留空 ----
        // 媒体引擎不关心连接状态/消息/好友/在线状态；这些归 NotificationDispatcher /
        // MainActivity / ChatActivity（与 CallManager.SignalingListener 同样的处理，
        // 见 CallManager.java:896-928）。

        @Override
        public void onConnected() {
            // 连接建立本身不影响媒体引擎（信令出口是 PushConnection 静态方法）
        }

        @Override
        public void onDisconnected() {
            // 刻意留空：一次网络抖动不结束通话，与 CallManager 的媒体看门狗策略一致
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
