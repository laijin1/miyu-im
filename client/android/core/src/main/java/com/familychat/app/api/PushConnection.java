package com.familychat.app.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程级推送连接宿主（连接宿主下沉 · 第一步）。
 *
 * <h3>为什么存在</h3>
 * 历史形态是「每个 Activity 各持一条 WebSocket」：MainActivity / ChatActivity / CallActivity /
 * GroupChatActivity / GroupDetailActivity 各建各的，同一账号最多 5 条并发连接，
 * 同一条推送被投递 5 次，App 只能靠 {@code CallActivity.shouldHandleIncoming} 和
 * {@code currentFriendId/currentGroupId} 这类静态字段打补丁去重。
 * 本类把所有连接收归一处：<b>全进程只有一条 WebSocket</b>，各 Activity 改为「注册监听」。
 *
 * <h3>为什么在 :core 而不是 :app</h3>
 * {@code ForegroundService} 位于 :app，而 :feature-chat / :feature-group 看不见 :app。
 * 连接持有者必须放在所有模块都可见的 :core，Service 只负责驱动它的生命周期。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>{@link #start(Context)} —— 由 {@code ForegroundService} 与 MainActivity 调用，幂等。
 *       server/token 变化时拆旧建新（否则重新登录后会拿着废 token 永不重建）。</li>
 *   <li>{@link #stop()} —— 只在退出登录 / 401 自动登出时调用。**Activity 不得调用**，
 *       否则退出主界面就会断掉后台推送。</li>
 *   <li>{@link #addListener}/{@link #removeListener} —— Activity 在 onCreate / onDestroy 成对调用。
 *       监听器为强引用：漏写 removeListener 会泄漏整个 Activity，靠 onDestroy 纪律 + 验收 grep 保障。</li>
 * </ul>
 *
 * <h3>线程约定</h3>
 * {@link WebSocketManager} 的所有回调都已 post 到主线程，本类原样转发，不改变线程语义。
 * {@link #addListener} 的补发 {@code onConnected()} 亦在主线程执行（见该方法注释）。
 */
public final class PushConnection {

    private static final String TAG = "PushConnection";

    /** 进程内唯一连接。 */
    private static volatile WebSocketManager ws;

    /** 监听器列表。CopyOnWriteArrayList：回调期间监听器增删不会破坏遍历。 */
    private static final CopyOnWriteArrayList<WebSocketManager.Listener> listeners =
            new CopyOnWriteArrayList<>();

    /** 建立当前连接时使用的 server/token，用于 start() 的幂等判定。 */
    private static volatile String startedServer;
    private static volatile String startedToken;

    /** 应用级 Context，仅用于兜底读取 PreferencesManager。 */
    private static volatile Context appCtx;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PushConnection() {
    }

    // =================================================================
    //  生命周期
    // =================================================================

    /**
     * 确保连接存在并已开始连接。幂等。
     *
     * <p>由 ForegroundService.onCreate / onStartCommand 调用（进程被系统拉起后恢复连接），
     * 也由 MainActivity.onCreate 显式调用一次 —— 因为 {@code startForegroundService()} 是
     * 异步的，Service.onCreate 不保证在 MainActivity.onCreate 之前执行；若只依赖 Service
     * 启动，MainActivity 注册监听时可能连接尚未建立，补发 onConnected 就不会发生。
     *
     * @param ctx 任意 Context；内部只保留 getApplicationContext()
     */
    public static synchronized void start(Context ctx) {
        if (ctx != null) {
            try {
                appCtx = ctx.getApplicationContext();
            } catch (Exception e) {
                AppLog.e(TAG, "start: getApplicationContext failed", e);
            }
        }
        if (appCtx == null) return;

        String server;
        String token;
        try {
            PreferencesManager prefs = PreferencesManager.getInstance(appCtx);
            server = prefs.getServer();
            token = prefs.getToken();
        } catch (Exception e) {
            AppLog.e(TAG, "start: read prefs failed", e);
            return;
        }

        // 无 token 不建连（登录页会短时间处于这个状态）
        if (token == null || token.isEmpty()) return;

        WebSocketManager cur = ws;
        if (cur != null
                && server != null && server.equals(startedServer)
                && token.equals(startedToken)) {
            return; // 已经是同一条连接，幂等返回
        }

        // server 或 token 变了（重新登录 / 切号）：拆掉旧连接再建新的
        if (cur != null) {
            try {
                cur.disconnect();
            } catch (Exception e) {
                AppLog.e(TAG, "start: disconnect stale connection failed", e);
            }
        }

        startedServer = server;
        startedToken = token;

        WebSocketManager fresh = new WebSocketManager(server, token);
        fresh.setListener(new Fanout());
        // 先发布再连接：连接回调在主线程异步投递，届时 ws 一定已可见
        ws = fresh;
        fresh.connect();
        AppLog.d(TAG, "PushConnection started: " + server);
    }

    /**
     * 断开并释放连接。
     *
     * <p><b>只应在退出登录 / 401 自动登出时调用。</b>Activity 的 onDestroy 一律用
     * {@link #removeListener} —— 调 stop() 会在用户离开主界面时切断后台推送。
     *
     * <p>刻意<b>不清空</b>监听器列表：Service 被系统重启时会重新 start()，
     * 此时已注册的 Activity 仍应继续收到推送。监听器的回收由各 Activity 的
     * onDestroy → removeListener 负责。
     */
    public static synchronized void stop() {
        WebSocketManager cur = ws;
        ws = null;
        startedServer = null;
        startedToken = null;
        if (cur != null) {
            try {
                cur.disconnect();
            } catch (Exception e) {
                AppLog.e(TAG, "stop: disconnect failed", e);
            }
        }
        AppLog.d(TAG, "PushConnection stopped");
    }

    // =================================================================
    //  监听器注册
    // =================================================================

    /**
     * 注册监听器。若连接已就绪，立即补发一次 {@code onConnected()}。
     *
     * <p>补发的必要性：Activity 可能晚于连接建立才注册（本步之后，连接由 MainActivity /
     * ForegroundService 建立，ChatActivity / CallActivity 后注册）。不补发的话，
     * <b>CallActivity 的外呼逻辑（在 onConnected 里发起 call_offer）就永远不会触发</b>。
     *
     * <p>补发在主线程同步执行（已在主线程则直接调用）：与 WebSocketManager 其余回调的
     * 线程语义一致，且 addListener 全部在 onCreate 主线程调用，等价于"立即"。
     * 调用方因此必须保证注册点的所有依赖都已初始化完毕 —— 例如 CallActivity 必须把
     * addListener 放在 initWebRTC() 返回、且 relay 视图接线（setRelayXxxView）之后。
     */
    public static void addListener(WebSocketManager.Listener l) {
        if (l == null) return;
        listeners.addIfAbsent(l);
        WebSocketManager cur = ws;
        if (cur != null && cur.isConnected()) {
            runOnMain(() -> safeCall(l, WebSocketManager.Listener::onConnected));
        }
    }

    /** 按引用移除监听器。Activity 必须在 onDestroy 调用，否则泄漏自身。 */
    public static void removeListener(WebSocketManager.Listener l) {
        if (l == null) return;
        listeners.remove(l);
    }

    /**
     * 连接是否已就绪（供状态展示）。这是"全进程连接状态"的唯一来源 ——
     * 不要再从 {@link WebSocketManager} 读静态镜像（已随本批删除：那个值会被登录页
     * 临时连接污染，且 disconnect() 不清理它，只靠 onClosed 异步兜底）。
     */
    public static boolean isConnected() {
        WebSocketManager cur = ws;
        return cur != null && cur.isConnected();
    }

    /** 是否正在建连/重连（含退避期）。语义边界见 {@link WebSocketManager#isConnecting()}。 */
    public static boolean isConnecting() {
        WebSocketManager cur = ws;
        return cur != null && cur.isConnecting();
    }

    // =================================================================
    //  发送代理（签名与 WebSocketManager 完全一致）
    // =================================================================

    public static void sendCallSignal(String type, int targetUserId, String data) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallSignal(type, targetUserId, data);
    }

    public static void sendCallSignal(String type, int targetUserId, String data, boolean videoEnabled) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallSignal(type, targetUserId, data, videoEnabled);
    }

    // ---- Step 1：带 callId 的通话信令（与 WebSocketManager 同名方法签名一一对应）----
    //      ⚠️ 参数顺序必须与 WebSocketManager 完全一致（offer, videoEnabled, callId）。

    /** 主叫发起呼叫（带 callId）。 */
    public static void sendCallOffer(int targetUserId, String offer, boolean videoEnabled, String callId) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallOffer(targetUserId, offer, videoEnabled, callId);
    }

    /** 对端占线。 */
    public static void sendCallBusy(int targetUserId, String callId) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallBusy(targetUserId, callId);
    }

    /** 被叫拒接。 */
    public static void sendCallReject(int targetUserId, String callId) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallReject(targetUserId, callId);
    }

    /** 主叫取消未接通的呼叫（含呼叫超时）。 */
    public static void sendCallCancel(int targetUserId, String callId) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallCancel(targetUserId, callId);
    }

    public static void sendCallMedia(int targetUserId, String base64Data, int seq, String mediaType) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallMedia(targetUserId, base64Data, seq, mediaType);
    }

    public static void sendCallVideo(int targetUserId, String base64Data) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallVideo(targetUserId, base64Data);
    }

    /** 对端关/开摄像头的状态信令（enabled=true 为视频开着）。 */
    public static void sendCallVideoState(int targetUserId, boolean enabled) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallVideoState(targetUserId, enabled);
    }

    public static void sendCallMediaStop(int targetUserId) {
        WebSocketManager cur = ws;
        if (cur != null) cur.sendCallMediaStop(targetUserId);
    }

    // =================================================================
    //  内部：转发 / 线程 / 异常隔离
    // =================================================================

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    /** 单个监听器的回调异常不得影响其它监听器，也不得打断 WebSocketManager 的投递。 */
    private static void safeCall(WebSocketManager.Listener l, Consumer<WebSocketManager.Listener> action) {
        try {
            action.accept(l);
        } catch (Throwable t) {
            AppLog.e(TAG, "listener callback failed", t);
        }
    }

    private static void fanout(Consumer<WebSocketManager.Listener> action) {
        for (WebSocketManager.Listener l : listeners) {
            safeCall(l, action);
        }
    }

    /**
     * WebSocketManager 的单一监听器，负责把每条回调扇出给全部已注册监听器。
     *
     * <p><b>必须逐一覆写接口的全部方法（11 个非 default + 19 个 default）。</b>
     * 对 default 方法而言，「不覆写」不会编译报错，但会使该事件对所有监听器静默失效
     * —— 这是本类最隐蔽的回归源。新增 WebSocketManager.Listener 方法时必须同步加到这里。
     */
    private static final class Fanout implements WebSocketManager.Listener {

        // ---- 非 default 方法（不覆写会编译失败）----

        @Override public void onConnected() {
            fanout(l -> l.onConnected());
        }

        @Override public void onDisconnected() {
            fanout(l -> l.onDisconnected());
        }

        @Override public void onNewMessage(Models.Message msg) {
            fanout(l -> l.onNewMessage(msg));
        }

        @Override public void onFriendRequest() {
            fanout(l -> l.onFriendRequest());
        }

        @Override public void onFriendRequestHandled(int fsId, String action, Models.User byUser) {
            fanout(l -> l.onFriendRequestHandled(fsId, action, byUser));
        }

        @Override public void onUserOnline(int userId) {
            fanout(l -> l.onUserOnline(userId));
        }

        @Override public void onUserOffline(int userId) {
            fanout(l -> l.onUserOffline(userId));
        }

        @Override public void onCallOffer(int fromUserId, String offerSdp, boolean videoEnabled) {
            fanout(l -> l.onCallOffer(fromUserId, offerSdp, videoEnabled));
        }

        @Override public void onCallAnswer(String answerSdp) {
            fanout(l -> l.onCallAnswer(answerSdp));
        }

        @Override public void onCallIce(String candidateJson) {
            fanout(l -> l.onCallIce(candidateJson));
        }

        @Override public void onCallHangup(int fromUserId) {
            fanout(l -> l.onCallHangup(fromUserId));
        }

        @Override public void onCallHangupWithId(int fromUserId, String callId) {
            fanout(l -> l.onCallHangupWithId(fromUserId, callId));
        }

        // ---- Step 1 新增通话信令：不覆写 = 该事件对所有监听器静默失效 ----
        //      由 ListenerFanoutContractTest 反射校验（每次漏覆写都是这个姿势踩的）。

        @Override
        public void onCallOfferWithId(int fromUserId, String offerSdp,
                                      boolean videoEnabled, String callId) {
            fanout(l -> l.onCallOfferWithId(fromUserId, offerSdp, videoEnabled, callId));
        }

        @Override
        public void onCallBusy(int fromUserId, String callId) {
            fanout(l -> l.onCallBusy(fromUserId, callId));
        }

        @Override
        public void onCallReject(int fromUserId, String callId) {
            fanout(l -> l.onCallReject(fromUserId, callId));
        }

        @Override
        public void onCallCancel(int fromUserId, String callId) {
            fanout(l -> l.onCallCancel(fromUserId, callId));
        }

        // ---- default 方法（不覆写不会报错，但事件会静默丢失）----

        @Override public void onCallMedia(int fromUserId, String base64Data, int seq, String mediaType) {
            fanout(l -> l.onCallMedia(fromUserId, base64Data, seq, mediaType));
        }

        @Override public void onCallVideo(int fromUserId, String base64Data) {
            fanout(l -> l.onCallVideo(fromUserId, base64Data));
        }

        @Override public void onMessageRecalled(int msgId) {
            fanout(l -> l.onMessageRecalled(msgId));
        }

        @Override public void onCallMediaStop(int fromUserId) {
            fanout(l -> l.onCallMediaStop(fromUserId));
        }

        @Override public void onCallVideoState(int fromUserId, boolean enabled) {
            fanout(l -> l.onCallVideoState(fromUserId, enabled));
        }

        @Override public void onGroupNewMessage(Models.GroupMessage msg) {
            fanout(l -> l.onGroupNewMessage(msg));
        }

        @Override public void onGroupMessageRecalled(int groupId, int msgId) {
            fanout(l -> l.onGroupMessageRecalled(groupId, msgId));
        }

        @Override public void onGroupMembersChanged(int groupId) {
            fanout(l -> l.onGroupMembersChanged(groupId));
        }

        @Override public void onGroupDissolved(int groupId) {
            fanout(l -> l.onGroupDissolved(groupId));
        }

        @Override public void onGroupRemoved(int groupId) {
            fanout(l -> l.onGroupRemoved(groupId));
        }

        @Override public void onGroupAnnouncement(int groupId, String action, int announcementId) {
            fanout(l -> l.onGroupAnnouncement(groupId, action, announcementId));
        }

        @Override public void onGroupMention(int groupId, int messageId, int senderId, String senderName) {
            fanout(l -> l.onGroupMention(groupId, messageId, senderId, senderName));
        }

        @Override public void onGroupFile(int groupId, String action, int fileId, String filename) {
            fanout(l -> l.onGroupFile(groupId, action, fileId, filename));
        }

        @Override public void onGroupAlbum(int groupId, String action, int albumId) {
            fanout(l -> l.onGroupAlbum(groupId, action, albumId));
        }

        @Override public void onGroupPoll(int groupId, String action, int pollId) {
            fanout(l -> l.onGroupPoll(groupId, action, pollId));
        }

        @Override public void onWalletUpdated(long balance) {
            fanout(l -> l.onWalletUpdated(balance));
        }

        @Override public void onTransferEvent(String kind, int tid, long amount) {
            fanout(l -> l.onTransferEvent(kind, tid, amount));
        }

        @Override public void onChargeRequested(int cid, int fromUser, String fromNickname, long amount, String memo) {
            fanout(l -> l.onChargeRequested(cid, fromUser, fromNickname, amount, memo));
        }

        @Override public void onChargeResult(int cid, String status, long amount) {
            fanout(l -> l.onChargeResult(cid, status, amount));
        }

        @Override public void onMallEvent(String type, int orderId, String productName, long amount, boolean approved) {
            fanout(l -> l.onMallEvent(type, orderId, productName, amount, approved));
        }
    }
}
