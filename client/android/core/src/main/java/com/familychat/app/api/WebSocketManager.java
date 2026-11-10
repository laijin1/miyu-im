package com.familychat.app.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.familychat.app.api.Models.Message;
import com.familychat.app.api.Models.User;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.ServerProtocol;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketManager {
    private static final String TAG = "WSManager";
    private final Gson gson = new Gson();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;

    private WebSocket webSocket;
    private String server;
    private String token;
    private Listener listener;
    /**
     * 连接是否已就绪。OkHttp 读线程写、主线程读，故 volatile。
     *
     * <p>本类只维护<b>实例</b>状态，没有 static 镜像。跨页面展示连接状态请走
     * {@link PushConnection#isConnected()}（全进程唯一连接的持有者）。
     */
    private volatile boolean connected = false;

    /**
     * 是否"正在连接"（含重连退避期）。OkHttp 读线程写、主线程读，故 volatile。
     *
     * <p><b>语义边界（J2）</b>：{@code onClosed} / {@code onFailure} 中取
     * {@code connecting = shouldReconnect}，因此
     * <ul>
     *   <li><b>正在重连</b> → true → 排障页显示"连接中"🟡。
     *       退避最长 30s，其间若显示"未连接"🟠 会把自愈过程误判为故障。</li>
     *   <li><b>主动断开</b> → {@link #disconnect()} 已把 shouldReconnect 置 false，
     *       且自身立即清零 connecting → false → 显示"未连接"🟠，
     *       不再依赖 onClosed 的异步回调兜底。</li>
     * </ul>
     * 与 {@link #isConnected()} 互斥：{@code onOpen} 会清零本标志。
     */
    private volatile boolean connecting = false;

    private boolean shouldReconnect = false;
    private int reconnectAttempt = 0;
    private static final int MAX_RECONNECT_INTERVAL_MS = 30000;

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onNewMessage(Message msg);
        void onFriendRequest();
        void onFriendRequestHandled(int fsId, String action, User byUser);
        void onUserOnline(int userId);
        void onUserOffline(int userId);

        // ---- Call signaling (WebRTC relayed via server) ----
        // ⚠️ 本组方法自 Step 1 起全部为 default：通话状态机已下沉到 :core 的 CallManager
        //    （PushConnection 的常驻监听器），各 Activity 不再需要实现它们。
        //    「不覆写不会编译报错、但事件会静默丢失」这个陷阱现在由
        //    ListenerFanoutContractTest 用反射兜底（PushConnection.Fanout 必须覆写全部方法）。

        /** 来电（窄接口，不带 callId；保留给历史实现者） */
        default void onCallOffer(int fromUserId, String offerSdp, boolean videoEnabled) {}
        /**
         * 来电（带 callId）。Step 1 起 {@link WebSocketManager} 实际派发的是本方法；
         * 默认实现回退到 {@link #onCallOffer} —— 老实现者行为完全不变。
         *
         * @param callId 对端生成的通话 id；null 表示对端是旧版客户端（报文无 call_id）
         */
        default void onCallOfferWithId(int fromUserId, String offerSdp,
                                       boolean videoEnabled, String callId) {
            onCallOffer(fromUserId, offerSdp, videoEnabled);
        }
        /** Answer to our outgoing call */
        default void onCallAnswer(String answerSdp) {}
        /** ICE candidate from remote peer */
        default void onCallIce(String candidateJson) {}
        /** Remote peer hung up */
        default void onCallHangup(int fromUserId) {}
        /**
         * 对端挂断（带 callId）。Step 4 起 {@link WebSocketManager} 实际派发的是本方法；
         * 默认实现回退到 {@link #onCallHangup} —— 老实现者行为完全不变
         * （与 {@link #onCallOfferWithId} 同构）。
         *
         * @param callId 对端生成的通话 id；null 表示对端是旧版客户端（报文无 call_id）
         */
        default void onCallHangupWithId(int fromUserId, String callId) {
            onCallHangup(fromUserId);
        }
        /** 对端占线（call_busy）：被叫在非空闲状态收到 call_offer 时回给主叫 */
        default void onCallBusy(int fromUserId, String callId) {}
        /** 被叫拒接（call_reject）：区别于"挂断"，主叫据此提示"对方已拒绝" */
        default void onCallReject(int fromUserId, String callId) {}
        /** 主叫取消未接通的呼叫（call_cancel，含呼叫超时） */
        default void onCallCancel(int fromUserId, String callId) {}
        /** Media chunk for relay mode (WAN via Cloudflare Tunnel) */
        default void onCallMedia(int fromUserId, String base64Data, int seq, String mediaType) {}
        /** Video frame for relay mode */
        default void onCallVideo(int fromUserId, String base64Data) {}
        /** Message was recalled by sender */
        default void onMessageRecalled(int msgId) {}
        /** Remote peer stopped sending relay media */
        default void onCallMediaStop(int fromUserId) {}
        /**
         * 对端视频开关状态变化（关/开摄像头）。
         * 与 onCallVideo（帧数据）语义不同：只表达状态，无 data。
         */
        default void onCallVideoState(int fromUserId, boolean enabled) {}

        // ---- 群聊推送 ----
        /** 群里有新消息（广播给所有在线成员，含发送者自己） */
        default void onGroupNewMessage(Models.GroupMessage msg) {}
        /** 群消息被撤回 */
        default void onGroupMessageRecalled(int groupId, int msgId) {}
        /** 群成员变化（拉人/移除/退群/角色/禁言/名片/转让） */
        default void onGroupMembersChanged(int groupId) {}
        /** 群被解散（群主解散群，全体成员收到） */
        default void onGroupDissolved(int groupId) {}
        /** 我被移出群聊（群主/管理员把我踢了） */
        default void onGroupRemoved(int groupId) {}
        /** 群公告变更（action: create/update/delete） */
        default void onGroupAnnouncement(int groupId, String action, int announcementId) {}
        /** 我在群里被 @ 提及（group_mention） */
        default void onGroupMention(int groupId, int messageId, int senderId, String senderName) {}
        /** 群文件库变更（group_file：create/delete） */
        default void onGroupFile(int groupId, String action, int fileId, String filename) {}
        /** 群相册变更（group_album：create/add_photo/delete/delete_photo） */
        default void onGroupAlbum(int groupId, String action, int albumId) {}
        /** 群投票变更（group_poll：create/vote/close/delete） */
        default void onGroupPoll(int groupId, String action, int pollId) {}

        // ---- 支付系统推送 ----
        /** 钱包余额变化（wallet_updated：管理员充值/扫码入账等），balance 单位:分 */
        default void onWalletUpdated(long balance) {}
        /** 转账事件（transfer_received/accepted/refunded），kind 区分，供聊天即时刷新气泡状态 */
        default void onTransferEvent(String kind, int tid, long amount) {}
        /** 收款请求（charge_requested）：付款码被扫，主人确认付款 */
        default void onChargeRequested(int cid, int fromUser, String fromNickname, long amount, String memo) {}
        /** 收款请求结果（charge_result：accepted/declined） */
        default void onChargeResult(int cid, String status, long amount) {}

        // ---- 商城订单事件推送 ----
        /** 商城事件：type=mall_order_new/paid/shipped/completed/cancelled/timeout/refund_apply/refund_result */
        default void onMallEvent(String type, int orderId, String productName, long amount, boolean approved) {}
    }

    public WebSocketManager(String server, String token) {
        this.server = server;
        this.token = token;
        // client 由 HttpClientProvider.webSocket() 提供，超时语义原样保留：
        // connect 10s / read 0（长连接不设读超时）/ ping 20s（服务端 Jetty 会回收
        // 空闲连接，历史上由此导致来电偶发失败 "target not connected"）。
        // 该 client 同时带假域名模式的自定义 Dns，wss://miyu.lan 才能解析。
        this.client = HttpClientProvider.webSocket();
    }

    public void setListener(Listener l) { this.listener = l; }
    public boolean isConnected() { return connected; }
    /** 是否正在建连/重连（含退避期）。边界见 {@link #connecting} 字段注释。 */
    public boolean isConnecting() { return connecting; }
    public void setToken(String token) { this.token = token; }
    public void setServer(String server) { this.server = server; }

    public void connect() {
        shouldReconnect = true;
        doConnect();
    }

    private void doConnect() {
        if (webSocket != null) {
            webSocket.close(1000, "reconnect");
            webSocket = null;
        }
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "Cannot connect: token is null or empty");
            return;
        }

        // SECURITY NOTE: Token is passed as a query parameter for compatibility with
        // the current server implementation. For production, consider sending the
        // token as the first WebSocket message instead to avoid URL logging exposure.
        String url = ServerProtocol.wsBaseUrl(server) + "/ws?token=" + token;
        Request req = new Request.Builder().url(url).build();
        connecting = true;
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                connecting = false;
                reconnectAttempt = 0;
                // Flush any pending signals that were queued before connection
                synchronized (pendingSignals) {
                    java.util.ArrayList<Runnable> flush = new java.util.ArrayList<>(pendingSignals);
                    pendingSignals.clear();
                    for (Runnable r : flush) {
                        r.run();
                    }
                }
                handler.post(() -> { if (listener != null) listener.onConnected(); });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                    if (!obj.has("type")) {
                        Log.w(TAG, "Received message without type field");
                        return;
                    }
                    String type = obj.get("type").getAsString();

                    switch (type) {
                        case "user_online": {
                            int uid = obj.get("user_id").getAsInt();
                            handler.post(() -> { if (listener != null) listener.onUserOnline(uid); });
                            break;
                        }
                        case "user_offline": {
                            int uid = obj.get("user_id").getAsInt();
                            handler.post(() -> { if (listener != null) listener.onUserOffline(uid); });
                            break;
                        }
                        case "new_message": {
                            Message msg = gson.fromJson(text, Message.class);
                            handler.post(() -> { if (listener != null) listener.onNewMessage(msg); });
                            break;
                        }
                        case "friend_request": {
                            handler.post(() -> { if (listener != null) listener.onFriendRequest(); });
                            break;
                        }
                        case "friend_request_handled": {
                            int fsId = obj.get("friendship_id").getAsInt();
                            String action = obj.get("action").getAsString();
                            User byUser = obj.has("by_user") ? gson.fromJson(obj.get("by_user"), User.class) : null;
                            handler.post(() -> { if (listener != null) listener.onFriendRequestHandled(fsId, action, byUser); });
                            break;
                        }

                        // ---- WebRTC call signaling (relayed via server) ----
                        case "call_offer": {
                            int fromUserId = obj.get("from").getAsInt();
                            String offerSdp = obj.get("offer").getAsString();
                            boolean hasVideo = obj.has("video") && obj.get("video").getAsBoolean();
                            String callId = readCallId(obj, fromUserId);
                            handler.post(() -> { if (listener != null)
                                listener.onCallOfferWithId(fromUserId, offerSdp, hasVideo, callId); });
                            break;
                        }
                        case "call_answer": {
                            String answerSdp = obj.get("answer").getAsString();
                            handler.post(() -> { if (listener != null) listener.onCallAnswer(answerSdp); });
                            break;
                        }
                        case "call_ice": {
                            String candidateJson = obj.has("candidate") ? 
                                (obj.get("candidate").isJsonObject() ? obj.get("candidate").toString() : obj.get("candidate").getAsString()) : "{}";
                            handler.post(() -> { if (listener != null) listener.onCallIce(candidateJson); });
                            break;
                        }
                        case "call_hangup": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String callId = readCallId(obj, fromUserId);
                            handler.post(() -> { if (listener != null) listener.onCallHangupWithId(fromUserId, callId); });
                            break;
                        }
                        // ---- Step 1 新增的三条通话状态信令 ----
                        case "call_busy": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String callId = readCallId(obj, fromUserId);
                            handler.post(() -> { if (listener != null) listener.onCallBusy(fromUserId, callId); });
                            break;
                        }
                        case "call_reject": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String callId = readCallId(obj, fromUserId);
                            handler.post(() -> { if (listener != null) listener.onCallReject(fromUserId, callId); });
                            break;
                        }
                        case "call_cancel": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String callId = readCallId(obj, fromUserId);
                            handler.post(() -> { if (listener != null) listener.onCallCancel(fromUserId, callId); });
                            break;
                        }
                        case "call_media": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String base64data = obj.has("data") ? obj.get("data").getAsString() : "";
                            int seq = obj.has("seq") ? obj.get("seq").getAsInt() : 0;
                            String mediaType = obj.has("media_type") ? obj.get("media_type").getAsString() : "audio";
                            if (!base64data.isEmpty()) {
                                final String fData = base64data;
                                final int fSeq = seq;
                                final String fType = mediaType;
                                handler.post(() -> { if (listener != null) listener.onCallMedia(fromUserId, fData, fSeq, fType); });
                            }
                            break;
                        }
                        case "call_video": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            String videoData = obj.has("data") ? obj.get("data").getAsString() : "";
                            handler.post(() -> { if (listener != null) listener.onCallVideo(fromUserId, videoData); });
                            break;
                        }
                        case "message_recalled": {
                            int msgId = obj.has("id") ? obj.get("id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onMessageRecalled(msgId); });
                            break;
                        }
                        case "call_media_stop": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onCallMediaStop(fromUserId); });
                            break;
                        }
                        case "call_video_state": {
                            int fromUserId = obj.has("from") ? obj.get("from").getAsInt() : 0;
                            // enabled 缺失 → 默认 true（与"视频默认开着"语义一致）；
                            // 显式 false 才是"对端关了摄像头"。
                            boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                            handler.post(() -> {
                                if (listener != null) listener.onCallVideoState(fromUserId, enabled);
                            });
                            break;
                        }

                        // ---- 群聊推送 ----
                        case "group_new_message": {
                            Models.GroupMessage gmsg = gson.fromJson(text, Models.GroupMessage.class);
                            handler.post(() -> { if (listener != null) listener.onGroupNewMessage(gmsg); });
                            break;
                        }
                        case "group_message_recalled": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            int gmid = obj.has("id") ? obj.get("id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupMessageRecalled(gid, gmid); });
                            break;
                        }
                        case "group_members_changed": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupMembersChanged(gid); });
                            break;
                        }
                        case "group_dissolved": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupDissolved(gid); });
                            break;
                        }
                        case "group_removed": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupRemoved(gid); });
                            break;
                        }
                        case "group_announcement": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            String action = obj.has("action") ? obj.get("action").getAsString() : "update";
                            int aid = obj.has("announcement_id") ? obj.get("announcement_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupAnnouncement(gid, action, aid); });
                            break;
                        }
                        case "group_mention": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            int gmid = obj.has("message_id") ? obj.get("message_id").getAsInt() : 0;
                            int senderId = obj.has("sender_id") ? obj.get("sender_id").getAsInt() : 0;
                            String senderName = obj.has("sender_name") ? obj.get("sender_name").getAsString() : "";
                            handler.post(() -> { if (listener != null) listener.onGroupMention(gid, gmid, senderId, senderName); });
                            break;
                        }
                        case "group_file": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            String action = obj.has("action") ? obj.get("action").getAsString() : "update";
                            int fileId = obj.has("file_id") ? obj.get("file_id").getAsInt() : 0;
                            String fname = obj.has("filename") ? obj.get("filename").getAsString() : "";
                            handler.post(() -> { if (listener != null) listener.onGroupFile(gid, action, fileId, fname); });
                            break;
                        }
                        case "group_album": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            String action = obj.has("action") ? obj.get("action").getAsString() : "update";
                            int albumId = obj.has("album_id") ? obj.get("album_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupAlbum(gid, action, albumId); });
                            break;
                        }
                        case "group_poll": {
                            int gid = obj.has("group_id") ? obj.get("group_id").getAsInt() : 0;
                            String action = obj.has("action") ? obj.get("action").getAsString() : "update";
                            int pollId = obj.has("poll_id") ? obj.get("poll_id").getAsInt() : 0;
                            handler.post(() -> { if (listener != null) listener.onGroupPoll(gid, action, pollId); });
                            break;
                        }

                        // ---- 支付系统推送 ----
                        case "wallet_updated": {
                            long balance = obj.has("balance") ? obj.get("balance").getAsLong() : 0L;
                            handler.post(() -> { if (listener != null) listener.onWalletUpdated(balance); });
                            break;
                        }
                        case "transfer_received":
                        case "transfer_accepted":
                        case "transfer_refunded": {
                            String kind = obj.get("type").getAsString();
                            int tid = obj.has("transfer_id") ? obj.get("transfer_id").getAsInt() : 0;
                            long amt = obj.has("amount") ? obj.get("amount").getAsLong() : 0L;
                            handler.post(() -> { if (listener != null) listener.onTransferEvent(kind, tid, amt); });
                            break;
                        }
                        case "charge_requested": {
                            int cid = obj.has("charge_id") ? obj.get("charge_id").getAsInt() : 0;
                            int fromUser = obj.has("from_user") ? obj.get("from_user").getAsInt() : 0;
                            String fromName = obj.has("from_nickname") ? obj.get("from_nickname").getAsString() : "";
                            long amt = obj.has("amount") ? obj.get("amount").getAsLong() : 0L;
                            String memo = obj.has("memo") ? obj.get("memo").getAsString() : "";
                            handler.post(() -> {
                                if (listener != null) listener.onChargeRequested(cid, fromUser, fromName, amt, memo);
                            });
                            break;
                        }
                        case "charge_result": {
                            int cid = obj.has("charge_id") ? obj.get("charge_id").getAsInt() : 0;
                            String status = obj.has("status") ? obj.get("status").getAsString() : "";
                            long amt = obj.has("amount") ? obj.get("amount").getAsLong() : 0L;
                            handler.post(() -> {
                                if (listener != null) listener.onChargeResult(cid, status, amt);
                            });
                            break;
                        }

                        // ---- 商城订单事件（买家/卖家实时通知） ----
                        case "mall_order_new":
                        case "mall_order_paid":
                        case "mall_order_shipped":
                        case "mall_order_completed":
                        case "mall_order_cancelled":
                        case "mall_order_timeout":
                        case "mall_refund_apply":
                        case "mall_refund_result": {
                            String evtType = obj.get("type").getAsString();
                            int orderId = obj.has("order_id") ? obj.get("order_id").getAsInt() : 0;
                            String pname = obj.has("product_name") ? obj.get("product_name").getAsString() : "";
                            long amt = obj.has("amount") ? obj.get("amount").getAsLong() : 0L;
                            boolean approved = obj.has("approved") && obj.get("approved").getAsBoolean();
                            handler.post(() -> {
                                if (listener != null) listener.onMallEvent(evtType, orderId, pname, amt, approved);
                            });
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "parse msg", e);
                    com.familychat.app.utils.AppLog.e(TAG, "解析消息失败", e);
                }
            }

@Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                // J2：还会重连就保持"连接中"（退避期也算），主动 disconnect()/登出时
                // shouldReconnect 已为 false，这里自然回到"未连接"。
                connecting = shouldReconnect;
                handler.post(() -> { if (listener != null) listener.onDisconnected(); });
                com.familychat.app.utils.AppLog.e(TAG, "WebSocket 断开 code=" + code + " reason=" + reason);
                if (shouldReconnect) scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                connecting = shouldReconnect;   // J2：同 onClosed
                handler.post(() -> { if (listener != null) listener.onDisconnected(); });
                com.familychat.app.utils.AppLog.e(TAG, "WebSocket 连接失败", t);
                if (shouldReconnect) scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        // Exponential backoff: starts at 1s, multiplies by 1.5 each retry, max 30s
        int delay = (int) Math.min(1000 * Math.pow(1.5, reconnectAttempt), MAX_RECONNECT_INTERVAL_MS);
        reconnectAttempt++;
        Log.d(TAG, "Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempt + ")");
        handler.postDelayed(() -> {
            if (shouldReconnect) doConnect();
        }, delay);
    }

    public void sendMessage(int receiverId, String content) {
        if (webSocket == null || !connected) return;
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "private_message");
        jo.addProperty("token", token);
        jo.addProperty("receiver_id", receiverId);
        jo.addProperty("content", content);
        webSocket.send(jo.toString());
    }

    /**
     * Send WebRTC signaling message through the WebSocket.
     * Server relays it to the target user.
     */
    public void sendCallSignal(String type, int targetUserId, String data) {
        sendCallSignal(type, targetUserId, data, false);
    }

    // Pending signals queue for messages sent before WebSocket connects
    private final java.util.ArrayList<Runnable> pendingSignals = new java.util.ArrayList<>();
    private static final int MAX_PENDING_SIGNALS = 100;

    /**
     * Send WebRTC signaling message with video flag (for call_offer).
     * Queues messages if WebSocket is not yet connected — sends when connected.
     */
    public void sendCallSignal(String type, int targetUserId, String data, boolean videoEnabled) {
        if (webSocket == null) return;
        if (!connected) {
            synchronized (pendingSignals) {
                if (pendingSignals.size() < MAX_PENDING_SIGNALS) pendingSignals.add(() -> sendCallSignal(type, targetUserId, data, videoEnabled));
            }
            return;
        }
        JsonObject jo = new JsonObject();
        jo.addProperty("type", type);
        jo.addProperty("to", targetUserId);
        if ("call_offer".equals(type)) {
            jo.addProperty("token", token);
            jo.addProperty("offer", data);
            jo.addProperty("video", videoEnabled);
        } else if ("call_answer".equals(type)) {
            jo.addProperty("answer", data);
        } else if ("call_ice".equals(type)) {
            try {
                com.google.gson.JsonElement elem = JsonParser.parseString(data);
                jo.add("candidate", elem);
            } catch (Exception e) {
                jo.addProperty("candidate", data);
            }
        } else if ("call_hangup".equals(type)) {
            // Step 4（部分）：call_hangup 现在带 call_id，供对端按 callId 精确匹配。
            // 本重载没有独立参数承载它，约定用 data 承载 callId（由 CallManager.sendTo 传入）。
            if (data != null && !data.isEmpty()) jo.addProperty("call_id", data);
        } else if ("call_media".equals(type)) {
            // Media relay chunk — data is base64 PCM audio
            jo.addProperty("data", data);
            jo.addProperty("seq", 0);
            jo.addProperty("media_type", "audio");
        } else if ("call_video".equals(type)) {
            // Video frame (relay mode) — MUST include data or the server drops it
            jo.addProperty("data", data);
        }
        if (!webSocket.send(jo.toString())) {
            // Send failed — retry via pending queue
            synchronized (pendingSignals) {
                pendingSignals.add(() -> sendCallSignal(type, targetUserId, data, videoEnabled));
            }
        }
    }

    // =================================================================
    //  Step 1：带 callId 的通话信令发送
    //  与 PushConnection 的同名静态代理**签名一一对应**（参数顺序不可错位）。
    // =================================================================

    /**
     * 读取报文里的 call_id；旧版客户端（≤1.1.6）不带该字段，
     * 用 "legacy-<from>-<ts>-<seq>" 兜底生成，保证 CallManager 的去重键始终非空。
     * 跨端 callId 语义会退化，但不影响接听。
     *
     * <p>兜底 id 里带进程内自增序号：只用毫秒时间戳时，同一毫秒内的两通来电
     * （快速重拨 / 服务端补投与实时投递叠在一起）会撞成同一个 key，去重就会误吞。
     */
    private static final java.util.concurrent.atomic.AtomicLong LEGACY_CALL_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static String readCallId(JsonObject obj, int fromUserId) {
        try {
            if (obj.has("call_id") && !obj.get("call_id").isJsonNull()) {
                String id = obj.get("call_id").getAsString();
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (Exception ignored) {}
        return "legacy-" + fromUserId + "-" + System.currentTimeMillis()
                + "-" + LEGACY_CALL_SEQ.incrementAndGet();
    }

    /**
     * 主叫发起呼叫（带 callId）。offer 在中继模式下是字面量 "relay"。
     *
     * <p>与既有 {@link #sendCallSignal} 一致：未连接时进补偿队列，
     * 连接建立后由 {@code onOpen} 统一补发。
     */
    public void sendCallOffer(int targetUserId, String offer, boolean videoEnabled, String callId) {
        if (webSocket == null) return;
        if (!connected) {
            synchronized (pendingSignals) {
                if (pendingSignals.size() < MAX_PENDING_SIGNALS) {
                    pendingSignals.add(() -> sendCallOffer(targetUserId, offer, videoEnabled, callId));
                }
            }
            return;
        }
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "call_offer");
        jo.addProperty("to", targetUserId);
        jo.addProperty("token", token);
        jo.addProperty("offer", offer);
        jo.addProperty("video", videoEnabled);
        if (callId != null) jo.addProperty("call_id", callId);
        if (!webSocket.send(jo.toString())) {
            synchronized (pendingSignals) {
                if (pendingSignals.size() < MAX_PENDING_SIGNALS) {
                    pendingSignals.add(() -> sendCallOffer(targetUserId, offer, videoEnabled, callId));
                }
            }
        }
    }

    /** 对端占线：本端非空闲时收到 call_offer，回给主叫。 */
    public void sendCallBusy(int targetUserId, String callId) {
        sendCallIdOnly("call_busy", targetUserId, callId);
    }

    /** 被叫拒接：区别于 call_hangup，主叫据此提示"对方已拒绝"。 */
    public void sendCallReject(int targetUserId, String callId) {
        sendCallIdOnly("call_reject", targetUserId, callId);
    }

    /** 主叫取消未接通的呼叫（含呼叫 30s 超时）。 */
    public void sendCallCancel(int targetUserId, String callId) {
        sendCallIdOnly("call_cancel", targetUserId, callId);
    }

    /**
     * call_busy / call_reject / call_cancel 的公共实现：报文只有 type + to + call_id。
     *
     * <p><b>刻意不进 pendingSignals 补偿队列</b>（与 call_offer 不同）：这三条都是
     * <b>终结类</b>信令，如果断线时入队、重连后补发，会把对端的<b>下一通</b>电话打死
     * —— 这正是"幽灵挂断"的成因。宁可丢弃（由 30s/45s 超时兜底），也不延时引爆。
     * Step 4 会把 call_hangup 也统一到这条规则。
     */
    private void sendCallIdOnly(String type, int targetUserId, String callId) {
        if (webSocket == null || !connected) return;
        try {
            JsonObject jo = new JsonObject();
            jo.addProperty("type", type);
            jo.addProperty("to", targetUserId);
            if (callId != null) jo.addProperty("call_id", callId);
            webSocket.send(jo.toString());
        } catch (Exception e) {
            Log.w(TAG, "sendCallIdOnly failed: " + type + " -> " + targetUserId, e);
        }
    }

    /**
     * Send a media chunk through the relay (WAN mode via Cloudflare Tunnel).
     * Server relays it to the target user's WebSocket.
     */
    public void sendCallMedia(int targetUserId, String base64Data, int seq, String mediaType) {
        if (webSocket == null) return;
        if (!isConnectionHealthy()) {
            synchronized (pendingSignals) {
                if (pendingSignals.size() < MAX_PENDING_SIGNALS) pendingSignals.add(() -> sendCallMedia(targetUserId, base64Data, seq, mediaType));
            }
            return;
        }
        try {
            JsonObject jo = new JsonObject();
            jo.addProperty("type", "call_media");
            jo.addProperty("to", targetUserId);
            jo.addProperty("data", base64Data);
            jo.addProperty("seq", seq);
            jo.addProperty("media_type", mediaType);
            if (!webSocket.send(jo.toString())) {
                synchronized (pendingSignals) {
                    if (pendingSignals.size() < MAX_PENDING_SIGNALS) pendingSignals.add(() -> sendCallMedia(targetUserId, base64Data, seq, mediaType));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "sendCallMedia error", e);
        }
    }

    /**
     * Signal that the relay media stream has ended.
     */
    public void sendCallVideo(int targetUserId, String base64Data) {
        if (webSocket == null || !connected) return;
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "call_video");
        jo.addProperty("to", targetUserId);
        jo.addProperty("data", base64Data);
        webSocket.send(jo.toString());
    }

    /**
     * 对端关/开摄像头的状态信令（relay 模式）。
     *
     * <p>与 {@link #sendCallVideo} 的区别：这条只表达状态、无帧数据，报文即
     * {@code {type, to, enabled}}；服务端 {@code case "call_video_state"} 直接透传 enabled。
     *
     * <p>与视频帧同款走"即发即弃"（<b>刻意不进</b> pendingSignals 补偿队列）：状态是有时效性的，
     * 断线期间积压、重连后补发会让对端套用一条过期的开关状态（与 V 帧同理，
     * 与 call_busy/reject/cancel 的"宁可丢弃"一致）。
     * 参照 {@link #sendCallVideo}：不传 call_id（现有 call_media/call_video 也都不传）。
     */
    public void sendCallVideoState(int targetUserId, boolean enabled) {
        if (webSocket == null || !connected) return;
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "call_video_state");
        jo.addProperty("to", targetUserId);
        jo.addProperty("enabled", enabled);
        webSocket.send(jo.toString());
    }

    public void sendCallMediaStop(int targetUserId) {
        if (webSocket == null || !connected) return;
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "call_media_stop");
        jo.addProperty("to", targetUserId);
        webSocket.send(jo.toString());
    }

    public boolean isConnectionHealthy() {
        return webSocket != null && connected;
    }

    public void disconnect() {
        shouldReconnect = false;
        // Remove all pending callbacks (reconnect timer, posted messages) from the main thread
        // Handler, so no Runnable keeps holding this manager (and thereby the listener -> Activity).
        handler.removeCallbacksAndMessages(null);

        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        connected = false;
        // 主动断开：立即清零，不再等 onClosed 的异步回调兜底（旧 static 版的漏洞就在这里）。
        connecting = false;

        // Clear queued signals — their Runnables would otherwise retain this manager's reference chain
        synchronized (pendingSignals) {
            pendingSignals.clear();
        }

        // Critical: drop the listener so anonymous Listener implementations no longer pin the Activity.
        // All handler.post callbacks already null-check `listener`, so this is safe.
        listener = null;
    }
}
