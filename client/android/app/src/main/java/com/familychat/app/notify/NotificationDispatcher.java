package com.familychat.app.notify;

import android.content.Context;
import android.content.Intent;

import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.ui.ChatActivity;
import com.familychat.app.ui.GroupChatActivity;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.GroupEncryptHelper;
import com.familychat.app.utils.MessagePreview;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ReadOnlyCache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程级通知派发（通知派发下沉）。
 *
 * <h3>为什么存在</h3>
 * 历史形态：消息未读计数与系统通知都写在 {@code MainActivity.createWsListener()} 里，
 * Context 用 {@code MainActivity.this}。结果<b>MainActivity 不在 = 整条通知链消失</b>
 * —— 用户没打开过 App、或主界面已销毁时，后台长连接收到消息既不计未读也不弹通知。
 * 本类把这段决策搬到进程级：注册在 {@link PushConnection} 上的常驻监听器，
 * 由 {@code FamilyChatApp.onCreate()} 安装一次，与任何 Activity 的生死无关。
 *
 * <h3>为什么在 :app</h3>
 * 群通知预览必须调 {@code GroupEncryptHelper.decryptGroupMessage()}（:feature-group），
 * 跳转 Intent 必须认识 {@code ChatActivity}（:feature-chat）/ {@code GroupChatActivity}
 * （:feature-group），而 :core 看不见这两个模块，:app 是唯一同时看得见的地方。
 * 为此本类只持 {@code applicationContext}，<b>绝不持任何 Activity 引用</b>。
 *
 * <h3>双层守卫（改这里之前必读）</h3>
 * 「正在与该好友/该群聊天就不打扰」要判定<b>两次</b>：
 * <ol>
 *   <li><b>主线程层</b>（{@link Listener#onNewMessage} / {@link Listener#onGroupNewMessage}）：
 *       WS 回调在主线程，此处读 {@code ChatActivity.currentFriendId} /
 *       {@code GroupChatActivity.currentGroupId} 决定要不要计数、要不要排队派发；</li>
 *   <li><b>Executor 层</b>（{@link #dispatchFriend} / {@link #dispatchGroup}）：真正发通知前
 *       再读一次同样的静态字段。</li>
 * </ol>
 * <b>两层都不能删。</b>主线程判定到 Executor 执行之间有真实时间差（解密可能要拉公钥、走网络），
 * 用户完全可能在这期间点进该会话；只留一层的第一层 → 会把"已经在看这个会话"的消息弹出来，
 * 只留第二层 → 未读计数与去重时机错位。第二层还顺带覆盖了"排队期间退出登录 / 打开免打扰"。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>主线程：判定 + 写未读计数（prefs 写，和旧实现一致）；</li>
 *   <li>单线程 Executor：名称缓存加载、群消息解密（<b>旧实现是在主线程调
 *       {@code decryptGroupMessage}，而它会同步拉公钥 —— 主线程网络请求，异常被
 *       PushConnection 的 safeCall 吞掉，整条群通知静默失败</b>）、构建并发出通知。</li>
 * </ul>
 * 单线程保证同一会话的通知按到达顺序发出；名称缓存因此可以被该线程独占访问
 * （见 {@link #ensureNamesLoaded}），无需加锁。
 *
 * <h3>与 :feature 模块的边界</h3>
 * 本类读 {@code ChatActivity.currentFriendId} / {@code GroupChatActivity.currentGroupId}
 * 两个 static volatile 字段（onStart/onStop 维护）。本批<b>只读不写</b>，不改它们的语义。
 */
public final class NotificationDispatcher {

    private static final String TAG = "NotificationDispatch";

    /** 群通知 id 基数：群通知 id = BASE + groupId（好友通知 id = friendId），与撤群通知同一规则。 */
    private static final int GROUP_NOTIFICATION_BASE = 100000;

    /** 名称缓存有效期。好友/群改名后最长 1 分钟生效，避免每条消息都读一次盘。 */
    private static final long NAME_CACHE_TTL_MS = 60_000L;

    /** 应用级 Context。进程级单例只允许持有它，绝不允许持有 Activity。 */
    private static volatile Context appCtx;

    /** 常驻监听器：进程生命周期内不反注册（它不持 Activity，无需回收）。 */
    private static volatile WebSocketManager.Listener listener;

    /**
     * 通知派发线程。单线程 = 顺序执行，且是名称缓存的唯一访问线程。
     * daemon 线程：不阻止进程退出。
     */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notify-dispatch");
        t.setDaemon(true);
        return t;
    });

    /** 好友 id → 昵称。只在 WORKER 线程访问。 */
    private static final Map<Integer, String> friendNames = new HashMap<>();
    /** 群 id → 群名。只在 WORKER 线程访问。 */
    private static final Map<Integer, String> groupNames = new HashMap<>();
    private static long namesLoadedAt;
    private static int namesUid = -1;

    private NotificationDispatcher() {
    }

    // =================================================================
    //  安装
    // =================================================================

    /**
     * 安装进程级通知派发。<b>幂等</b>，由 {@code FamilyChatApp.onCreate()} 调用一次。
     *
     * <p>为什么在 Application 而不是某个 Activity/ForegroundService：
     * 进程由 ForegroundService 通过 START_STICKY 拉起时会先走 Application.onCreate，
     * 保证"服务活着 = 通知链活着"；同时用户从未打开过 Activity 的场景也覆盖到。
     *
     * <p>注册点刻意<b>不做反注册</b>：{@code PushConnection.stop()}（登出/401）不清空监听器，
     * 重新登录后同一条连接重启，本派发器继续有效。
     */
    public static synchronized void install(Context ctx) {
        if (ctx != null) {
            try {
                appCtx = ctx.getApplicationContext();
            } catch (Exception e) {
                AppLog.e(TAG, "install: getApplicationContext failed", e);
            }
        }
        if (listener != null) return;      // 已安装：幂等返回
        if (appCtx == null) {
            AppLog.e(TAG, "install skipped: no application context");
            return;
        }
        listener = new Listener();
        PushConnection.addListener(listener);
        AppLog.d(TAG, "NotificationDispatcher installed");
    }

    // =================================================================
    //  监听器（WS 回调，全部在主线程）
    // =================================================================

    private static final class Listener implements WebSocketManager.Listener {

        // ---- 本批只关心这两条；其余回调属 UI/DB，仍归各 Activity 自己处理 ----

        @Override
        public void onNewMessage(Models.Message msg) {
            if (msg == null) return;
            try {
                Context ctx = appCtx;
                if (ctx == null) return;
                PreferencesManager prefs = PreferencesManager.getInstance(ctx);

                final int friendId = msg.sender_id == prefs.getUserId() ? msg.receiver_id : msg.sender_id;

                // ── 第一层守卫（主线程）：不在该好友会话里才计未读 ──
                if (!NotificationPolicy.shouldCountUnread(ChatActivity.currentFriendId, friendId)) return;

                String key = "unread_" + friendId;
                prefs.putString(key, String.valueOf(
                        NotificationPolicy.nextUnread(parseIntOrZero(prefs.getString(key, "0")))));

                // 免打扰：仍计未读（红点照旧），只是不弹通知 —— 与群侧同序（计未读在前，quiet 判定在后）
                if (!NotificationPolicy.shouldNotifyFriend(
                        ChatActivity.currentFriendId, friendId, prefs.getFriendQuiet(friendId))) {
                    return;
                }

                // 类型映射统一走 MessagePreview：file/video 过去没有分支，通知栏会弹 /uploads/... 裸路径。
                // 注意：这里只换映射，不动解密（text 仍是 WS 原文，属 §9.2-2 的范围，本批不修）。
                final String preview = MessagePreview.forList(msg.content, msg.msg_type);
                WORKER.execute(() -> dispatchFriend(ctx, prefs, friendId, preview));
            } catch (Throwable t) {
                AppLog.e(TAG, "onNewMessage dispatch failed", t);
            }
        }

        @Override
        public void onGroupNewMessage(Models.GroupMessage gmsg) {
            if (gmsg == null) return;
            try {
                Context ctx = appCtx;
                if (ctx == null) return;
                PreferencesManager prefs = PreferencesManager.getInstance(ctx);

                final int groupId = gmsg.group_id;

                // ── 第一层守卫（主线程）：不在该群里才计未读 ──
                // 注意：未读计数包含 system 消息（旧行为如此），而通知不包含。
                if (!NotificationPolicy.shouldCountUnread(GroupChatActivity.currentGroupId, groupId)) return;

                prefs.setGroupUnread(groupId, NotificationPolicy.nextUnread(prefs.getGroupUnread(groupId)));

                boolean system = "system".equals(gmsg.msg_type);
                boolean quiet = prefs.getGroupQuiet(groupId);
                if (!NotificationPolicy.shouldNotifyGroup(
                        GroupChatActivity.currentGroupId, groupId, system, quiet)) {
                    return;
                }

                final String content = gmsg.content;
                final String msgType = gmsg.msg_type;
                final int senderId = gmsg.sender_id;
                WORKER.execute(() -> dispatchGroup(ctx, prefs, groupId, senderId, content, msgType));
            } catch (Throwable t) {
                AppLog.e(TAG, "onGroupNewMessage dispatch failed", t);
            }
        }

        // ---- WebSocketManager.Listener 的其余 9 个非 default 方法：本批不涉及 ----

        @Override public void onConnected() {}
        @Override public void onDisconnected() {}
        @Override public void onFriendRequest() {}
        @Override public void onFriendRequestHandled(int fsId, String action, Models.User byUser) {}
        @Override public void onUserOnline(int userId) {}
        @Override public void onUserOffline(int userId) {}
        @Override public void onCallOffer(int fromUserId, String offerSdp, boolean videoEnabled) {}
        @Override public void onCallAnswer(String answerSdp) {}
        @Override public void onCallIce(String candidateJson) {}
        @Override public void onCallHangup(int fromUserId) {}
    }

    // =================================================================
    //  派发（WORKER 线程）
    // =================================================================

    /** 好友新消息：解析标题 → 构建跳转 Intent → 发通知。 */
    private static void dispatchFriend(Context ctx, PreferencesManager prefs,
                                       int friendId, String preview) {
        try {
            // ── 第二层守卫（Executor 内，见类注释「双层守卫」）──
            // 排队期间用户可能已进该会话、也可能刚打开免打扰，因此两个条件都重新读一遍。
            if (!NotificationPolicy.shouldNotifyFriend(
                    ChatActivity.currentFriendId, friendId, prefs.getFriendQuiet(friendId))) {
                return;
            }
            if (!isSessionValid(prefs)) return;

            ensureNamesLoaded(ctx, prefs);
            String nickname = friendNames.get(friendId);

            // 显示标题用兜底文案；跳转 extra 保持原值（可能为空串），与旧行为一致。
            String title = NotificationPolicy.friendTitle(nickname);

            Intent it = new Intent(ctx, ChatActivity.class);
            it.putExtra("friend_id", friendId);
            it.putExtra("friend_name", nickname == null ? "" : nickname);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            NotificationHelper.showMessageNotification(ctx, it, title,
                    preview == null ? "" : preview, friendId);
        } catch (Throwable t) {
            AppLog.e(TAG, "friend dispatch failed friendId=" + friendId, t);
        }
    }

    /** 群新消息：解密预览 → 解析群名 → 构建跳转 Intent → 发通知。 */
    private static void dispatchGroup(Context ctx, PreferencesManager prefs, int groupId,
                                      int senderId, String content, String msgType) {
        try {
            // ── 第二层守卫（Executor 内）──
            // 排队期间用户可能已进群、也可能刚打开免打扰，因此两个条件都重新读一遍。
            boolean system = "system".equals(msgType);
            if (!NotificationPolicy.shouldNotifyGroup(
                    GroupChatActivity.currentGroupId, groupId, system, prefs.getGroupQuiet(groupId))) {
                return;
            }
            if (!isSessionValid(prefs)) return;

            // 解密必须在后台线程做：decryptGroupMessage 可能同步拉取发送者公钥（网络）。
            ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
            String plain = GroupEncryptHelper.decryptGroupMessage(
                    content, senderId, prefs.getUserId(), api, prefs, msgType);
            String preview = MessagePreview.forList(plain, msgType);

            ensureNamesLoaded(ctx, prefs);
            String groupName = groupNames.get(groupId);
            String title = NotificationPolicy.groupTitle(groupName);

            Intent it = new Intent(ctx, GroupChatActivity.class);
            it.putExtra("group_id", groupId);
            it.putExtra("group_name", groupName == null ? "" : groupName);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // 旧实现正文是 senderName + ": " + preview，而 senderName 一直是空串 → 正文即 preview。
            NotificationHelper.showGroupNotification(ctx, it, title, preview,
                    GROUP_NOTIFICATION_BASE + groupId);
        } catch (Throwable t) {
            AppLog.e(TAG, "group dispatch failed groupId=" + groupId, t);
        }
    }

    // =================================================================
    //  工具
    // =================================================================

    /**
     * 会话是否仍然有效。排队期间用户可能已登出（401 自动登出会 stopService + 清 token），
     * 此时绝不能把上一条会话的通知弹出来 —— 那是上一个账号的消息。
     */
    private static boolean isSessionValid(PreferencesManager prefs) {
        try {
            return prefs != null && prefs.isLoggedIn() && prefs.getUserId() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 加载好友昵称 / 群名（带 TTL）。<b>只在 WORKER 线程调用</b>——因此下面的 Map 无需加锁。
     *
     * <p>数据来源是 {@code ReadOnlyCache}（:core，按 uid 作用域落盘的好友/群镜像）：
     * 本类已无法再依赖 MainActivity 的内存列表（Activity 可能根本不存在）。
     * uid 变化（切号）时整体丢弃重载，杜绝跨账号串名。
     *
     * <p>读盘同样放在后台线程：EncryptedSharedPreferences 解密 + Gson 解析
     * （群列表可达 10KB 量级）放主线程会掉帧。
     */
    private static void ensureNamesLoaded(Context ctx, PreferencesManager prefs) {
        int uid;
        try {
            uid = prefs.getUserId();
        } catch (Exception e) {
            uid = -1;
        }
        long now = System.currentTimeMillis();
        if (uid == namesUid && uid > 0 && now - namesLoadedAt < NAME_CACHE_TTL_MS) return;

        friendNames.clear();
        groupNames.clear();
        try {
            Models.FriendsResponse fr = ReadOnlyCache.loadFriends(ctx, prefs);
            if (fr != null && fr.friends != null) {
                for (Models.User u : fr.friends) {
                    if (u != null && u.nickname != null && !u.nickname.isEmpty()) {
                        friendNames.put(u.id, u.nickname);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "load friend names failed", e);
        }
        try {
            Models.GroupListResponse gr = ReadOnlyCache.loadGroups(ctx, prefs);
            if (gr != null && gr.groups != null) {
                for (Models.Group g : gr.groups) {
                    if (g != null && g.name != null && !g.name.isEmpty()) {
                        groupNames.put(g.id, g.name);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "load group names failed", e);
        }
        namesUid = uid;
        namesLoadedAt = now;
    }

    /** 未读计数解析：脏数据（非数字）按 0 处理，绝不抛异常打断 WS 回调。 */
    private static int parseIntOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return 0;
        }
    }
}
