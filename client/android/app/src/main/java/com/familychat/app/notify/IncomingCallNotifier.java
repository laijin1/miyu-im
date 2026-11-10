package com.familychat.app.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.familychat.app.R;
import com.familychat.app.api.Models;
import com.familychat.app.api.call.CallManager;
import com.familychat.app.api.call.CallSession;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Routes;
import com.google.gson.Gson;

/**
 * 来电通知（批 1）：后台/锁屏来电时给用户一条可感知、可全屏拉起的系统通知。
 *
 * <h3>为什么需要它</h3>
 * 改造前来电的唯一信号是 {@code RingtonePlayer} 的铃声：后台/锁屏时用户可能完全无感，
 * 而 {@code CallManager.launchIncomingUi()} 的 {@code startActivity} 在 Android 10+
 * 被后台启动限制拦截（多数机型静默失败、不抛异常）。本类是该失败的兜底。
 *
 * <h3>三个已拍板的设计约束（改动前必读）</h3>
 * <ol>
 *   <li><b>独立渠道</b> {@code familychat_call_incoming}（IMPORTANCE_HIGH）。
 *       <b>不动</b> {@code familychat_call}（LOW，CallService 的通话中通知）
 *       与 {@code familychat_msg}（HIGH，消息通知）—— 渠道参数一旦创建就由系统沿用，
 *       改 id 或改重要性会重置用户已保存的设置（见 {@link NotificationHelper} 类注释）。</li>
 *   <li><b>本渠道不声明声音</b>（{@code setSound(null)}）：铃声仍由
 *       {@code RingtonePlayer} 独占，避免"渠道音 + MediaPlayer"两路叠加
 *       —— 那正是 {@code NotificationHelper.java:30-39} 记录过的历史故障。</li>
 *   <li><b>通知不从 CallService 发</b>：CallService 只在 {@code CallEngine.start()}
 *       之后才存在，而响铃阶段根本没有 CallService。本类挂在
 *       {@link CallManager.IncomingUiFallback} 上，由 {@code FamilyChatApp.onCreate} 安装。</li>
 * </ol>
 *
 * <h3>撤销时机（不碰 CallActivity）</h3>
 * 本类自己注册为 {@link CallManager.Observer}：来电被接通（→ ACTIVE）、被拒接、
 * 被对方取消、响铃超时 —— 所有收尾路径都汇聚到 {@code CallManager.teardown()}，
 * 由它回调 {@link #onCallEnded} / {@link #onStateChanged}，在此撤通知。
 * 这样"撤通知"与"通话结束"只有一个真相来源，不需要 CallActivity 参与。
 *
 * <h3>线程</h3>
 * {@code CallManager} 的观察者回调是<b>同步</b>的（在状态机入口线程上跑），
 * 因此本类所有通知操作都 post 到主线程执行；{@code notifyIncoming} 由
 * {@code launchIncomingUi} 在主线程调用，同样统一 post，保证串行。
 */
public final class IncomingCallNotifier implements CallManager.IncomingUiFallback {

    private static final String TAG = "IncomingCallNotify";

    /** 独立的高重要性渠道（声音为空，见类注释第 2 条）。 */
    private static final String CHANNEL_ID = "familychat_call_incoming";
    private static final String CHANNEL_NAME = "来电";

    /** 避开 ForegroundService 的 1001 与 CallService 的 1002。 */
    private static final int NOTIFICATION_ID = 1003;

    /**
     * 震动模式（关-振-关-振）。之所以给震动：本渠道<b>没有</b>提示音，
     * 而 {@code RingtonePlayer} 的声音在部分机型"静音模式"下会被系统压制，
     * 震动是那时唯一的可感知信号；且振动不受"渠道无声音"影响，不会叠加。
     */
    private static final long[] VIBRATION_PATTERN = {0L, 400L, 200L, 400L};

    private static final int REQ_CONTENT = 2001;
    private static final int REQ_ANSWER = 2002;
    private static final int REQ_DECLINE = 2003;
    private static final int REQ_FULL_SCREEN = 2004;

    /** 当前已发出来电通知的 callId；撤离时据此判断"这条通知是不是这一通的"。 */
    private volatile String notifiedCallId;

    /**
     * "这一通的通话页已经显示过"的 callId（批 2 / c 的竞态守卫）。
     *
     * <p>为什么必须有它：{@code launchIncomingUi} 先 {@code startActivity}、再调 fallback，
     * 而 fallback 里的 {@code show()} 是 {@code post} 到主线程执行的。Activity 的
     * {@code onCreate/onStart} 也在主线程，完全可能先于那个 post 跑完 ——
     * 此时 {@code onCallUiShown → cancel()} 会因为"还没发过通知"而 no-op，
     * 随后那个迟到的 post 又把通知发出来。正是批 1 报告里"前台仍多发一条通知"的成因。
     *
     * <p>有了这个字段，{@code show()} 在真正发通知前再确认一次"UI 是否已经显示过"，
     * 从而把迟到的 post 变成 no-op。
     */
    private volatile String uiShownCallId;

    /** 应用级 Context（进程级单例只允许持有它，绝不持 Activity，与 NotificationDispatcher 同款）。 */
    private final Context appCtx;

    private IncomingCallNotifier(Context appCtx) {
        this.appCtx = appCtx;
    }

    /**
     * 安装：建渠道 + 注册为 CallManager 观察者（用于来电结束时撤通知）。
     * <b>幂等</b>，由 {@code FamilyChatApp.onCreate()} 调用一次。
     *
     * @return 已安装好的实例（由 {@code :app} 交给 {@link CallManager#setIncomingUiFallback}）
     */
    public static IncomingCallNotifier install(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        IncomingCallNotifier n = new IncomingCallNotifier(app);
        n.createChannel();
        // 与 CallEngine.CallStateObserver / CallActivity 同款：刻意不反注册。
        // 本对象只持 applicationContext 与一个 callId 字符串，不持 Activity。
        CallManager.addObserver(new Observer(n));
        AppLog.d(TAG, "installed (channel=" + CHANNEL_ID + ", notifId=" + NOTIFICATION_ID + ")");
        return n;
    }

    // =================================================================
    //  渠道
    // =================================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = appCtx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);   // HIGH：heads-up + 允许全屏意图
        ch.setDescription("来电提醒（铃声由应用内播铃负责，本渠道不发声）");
        ch.setShowBadge(false);
        // 明示无声音：铃声归 RingtonePlayer，见类注释第 2 条。
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setVibrationPattern(VIBRATION_PATTERN);
        nm.createNotificationChannel(ch);
    }

    // =================================================================
    //  CallManager.IncomingUiFallback
    // =================================================================

    @Override
    public void onIncomingUiFallback(final String callId, final int callerId,
                                     final boolean videoEnabled, final String friendName) {
        // 由 launchIncomingUi 在主线程调用；统一 post 只为与观察者回调串行。
        runOnMain(() -> show(callId, callerId, videoEnabled, friendName));
    }

    /**
     * 通话页真的显示出来了（批 2 / c）→ 撤掉兜底通知。
     *
     * <p>典型场景：App 在前台，{@code launchIncomingUi} 的 {@code startActivity} 成功，
     * 但批 1 的设计是"无条件发通知"（无法从代码判定系统是否静默拦截），于是会多出一条。
     * 本回调把它撤掉，让前台来电只有通话页、没有冗余通知。
     *
     * <p>{@code cancel} 自带 callId 守卫：外呼 / 已接通 / 迟到通知等情况 callId 不匹配，
     * 对端是 no-op，因此不需要在这里再判一次状态。
     */
    @Override
    public void onCallUiShown(String callId) {
        // 先记"这一通的 UI 已经显示过"（竞态守卫），再撤已发出的那条。
        // 页面 onStart 在主线程，这里直接撤（不再 post），使"通知消失"与页面可见同时发生。
        if (callId != null) uiShownCallId = callId;
        cancel(callId);
    }

    // =================================================================
    //  发通知
    // =================================================================

    private void show(String callId, int callerId, boolean videoEnabled, String friendName) {
        try {
            final Context ctx = appCtx;
            if (ctx == null) {
                AppLog.e(TAG, "show skipped: no appCtx callId=" + callId);
                return;
            }
            String name = (friendName == null || friendName.isEmpty()) ? "未知用户" : friendName;

            // 竞态守卫（批 2 / c）：onStart 可能已经跑过并撤过通知了。此时再发就是把
            // "前台来电"变成"前台来电 + 一条冗余通知"，即批 1 报告里的已知副作用。
            if (callId != null && callId.equals(uiShownCallId)) {
                AppLog.d(TAG, "show skipped: call UI already shown callId=" + callId);
                return;
            }

            notifiedCallId = callId;

            String friendJson = readFriendJson(callerId);

            PendingIntent answer = buildCallPendingIntent(
                    ctx, REQ_ANSWER, true, false, callId, callerId, name, friendJson, videoEnabled);
            PendingIntent decline = buildCallPendingIntent(
                    ctx, REQ_DECLINE, false, true, callId, callerId, name, friendJson, videoEnabled);
            PendingIntent content = buildCallPendingIntent(
                    ctx, REQ_CONTENT, false, false, callId, callerId, name, friendJson, videoEnabled);
            // 全屏意图与内容意图指向同一个"手动来电浮层"分支：锁屏时直接展开 CallActivity。
            PendingIntent fullScreen = buildCallPendingIntent(
                    ctx, REQ_FULL_SCREEN, false, false, callId, callerId, name, friendJson, videoEnabled);

            Person person = new Person.Builder()
                    .setName(name)
                    .setImportant(true)
                    .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_avatar_placeholder))
                    .build();

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
                    .setContentTitle(name)
                    .setContentText(videoEnabled ? "邀请你视频通话…" : "邀请你语音通话…")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setShowWhen(false)
                    .setContentIntent(content)
                    .setFullScreenIntent(fullScreen, true);

            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                AppLog.e(TAG, "show skipped: NotificationManager unavailable");
                return;
            }
            nm.notify(NOTIFICATION_ID, b.build());
            AppLog.d(TAG, "incoming call notification posted callId=" + callId
                    + " from=" + callerId + " name=" + name + " video=" + videoEnabled);
        } catch (Throwable t) {
            // 通知失败绝不能影响通话：信令/超时/铃声都不依赖本类。
            AppLog.e(TAG, "show failed callId=" + callId, t);
        }
    }

    /**
     * 构造"点通知去 CallActivity"的 PendingIntent。
     *
     * <p><b>三个 requestCode 必须互不相同</b>：PendingIntent 的相等性<b>不看 extras</b>，
     * 相同 requestCode + 相同 Intent 会被视为同一个 PendingIntent，后一个
     * {@code FLAG_UPDATE_CURRENT} 会覆盖前一个的 extras —— 点"接听"却走了拒接分支。
     * 这是 {@code NotificationHelper} 类注释（{@code :47-53}）记录过的历史事故。
     *
     * @param autoAccept true = 直接接听（CallActivity 的 auto_accept 分支）；
     *                   false = 打开来电浮层由用户选择（CallActivity 的 manual 分支）
     * @param autoReject true = 直接拒接（批 2：CallActivity 的 auto_reject 分支，
     *                   一步完成，不再需要用户进页面再点一次）
     */
    private PendingIntent buildCallPendingIntent(Context ctx, int requestCode,
                                                 boolean autoAccept, boolean autoReject,
                                                 String callId, int callerId, String friendName,
                                                 String friendJson, boolean videoEnabled) {
        Intent it = new Intent();
        it.setClassName(ctx, Routes.CALL_ACTIVITY);
        // 与 CallService.buildContentIntent(:223) 同款：通知发起的 PendingIntent 必须有 NEW_TASK；
        // SINGLE_TOP 配合 CallActivity 的 launchMode=singleTop（批 2），避免通话页已在栈内时
        // 再建一个实例 —— 多实例会各自跑一遍 flow 分支（接听/外呼）而互相打架。
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra("incoming", true);
        it.putExtra("call_id", callId);
        it.putExtra("from_user_id", callerId);
        it.putExtra("friend_name", friendName);
        if (friendJson != null) it.putExtra("friend", friendJson);
        it.putExtra("video", videoEnabled);
        // auto_accept=true 时跳过响铃浮层直接起媒体+发 call_answer（见 CallActivity 的
        // "已经接听过" 分支）。信令仍只从 CallManager.acceptCall() 出，本类不碰状态机。
        if (autoAccept) it.putExtra("auto_accept", true);
        // auto_reject=true 时 CallActivity 直接 CallManager.rejectCall(callId) 后 finish()
        // （批 2）。同样只调状态机方法，不自己发信令/写系统消息。
        if (autoReject) it.putExtra("auto_reject", true);

        final CallSession s = CallManager.getCurrentCall();
        if (s != null && callId != null && callId.equals(s.callId) && s.offerSdp != null) {
            it.putExtra("offer_sdp", s.offerSdp);
        }

        return PendingIntent.getActivity(ctx, requestCode, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 读 {@code friend_<id>} 缓存（与 {@code CallManager.launchIncomingUi} 同款读法）；失败返回 null。 */
    private String readFriendJson(int callerId) {
        try {
            String cached = PreferencesManager.getInstance(appCtx)
                    .getString("friend_" + callerId, null);
            if (cached == null || cached.isEmpty()) return null;
            Models.User u = new Gson().fromJson(cached, Models.User.class);
            return u == null ? null : cached;
        } catch (Exception e) {
            AppLog.e(TAG, "read friend_ cache failed callerId=" + callerId, e);
            return null;
        }
    }

    // =================================================================
    //  撤通知
    // =================================================================

    /** 撤掉来电通知。<b>幂等</b>：没有通知时 cancel 无副作用。 */
    void cancel(String callId) {
        final String mine = notifiedCallId;
        if (mine == null) return;                       // 从没发过：什么都不做
        if (callId != null && !callId.equals(mine)) return;   // 不是这一通：不动
        notifiedCallId = null;
        // 这一通彻底结束了：竞态守卫星也一并清掉，避免残留到下一通（同 callId 不会复用，
        // 但清掉才能让"没有通知 = 没有记录"这条不变量成立）。
        if (mine.equals(uiShownCallId)) uiShownCallId = null;
        final Context ctx = appCtx;
        if (ctx == null) return;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
                AppLog.d(TAG, "incoming call notification cancelled callId=" + callId);
            }
        } catch (Throwable t) {
            AppLog.e(TAG, "cancel failed callId=" + callId, t);
        }
    }

    // =================================================================
    //  内部
    // =================================================================

    private static void runOnMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    /**
     * 状态机事件的接收者。做成独立静态类而不是让 {@link IncomingCallNotifier} 自己
     * 实现 {@link CallManager.Observer}：这样 {@code install()} 的幂等语义不被
     * "观察者已注册但实例被换掉"污染（与 {@code CallEngine.CallStateObserver} 同款）。
     */
    private static final class Observer implements CallManager.Observer {

        private final IncomingCallNotifier target;

        Observer(IncomingCallNotifier target) {
            this.target = target;
        }

        /** 来电被接通（ACTIVE）/ 收尾（ENDING/IDLE）→ 通知不再需要。 */
        @Override
        public void onStateChanged(CallSession session) {
            if (session == null) {
                target.cancel(null);
                return;
            }
            if (session.state != CallSession.State.INCOMING_RINGING) {
                target.cancel(session.callId);
            }
        }

        /** 所有收尾路径（拒接/对方取消/响铃超时/挂断）都会走到这里。 */
        @Override
        public void onCallEnded(String callId, CallManager.EndReason reason) {
            target.cancel(callId);
        }
    }
}
