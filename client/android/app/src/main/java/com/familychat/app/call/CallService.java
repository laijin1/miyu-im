package com.familychat.app.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.familychat.app.api.call.CallManager;
import com.familychat.app.api.call.CallSession;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.Routes;

/**
 * 通话前台服务。
 *
 * <p><b>职责</b>（三件，且仅此三件）：
 * <ol>
 *   <li>维持前台通知"通话中 MM:SS"，每秒刷新；</li>
 *   <li>让通话在 {@code CallActivity} 之外时进程不被系统随意回收；</li>
 *   <li>通知点击 → 把通话页拉回前台。</li>
 * </ol>
 *
 * <p><b>刻意不持有 {@code CallEngine}</b>：后者是 :feature-chat 的进程级静态门面
 * （见 CallEngine.java 的类注释），媒体与信令都不属于本服务的职责。本服务只读
 * {@code CallManager.getCurrentCall()} 这一份进程级状态来渲染通知。
 *
 * <p><b>为什么用 specialUse 而不是 phoneCall</b>：{@code phoneCall} 类型面向默认拨号器 /
 * Telecom 场景，其准入前置条件由平台决定；{@code specialUse} 不引入这类要求。
 * 由于 {@code android:foregroundServiceType} 是 API 29 才有的属性，本服务在 API 29+ 用
 * {@code ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST}（= 使用 manifest 声明的类型），
 * API 24-28 用两参重载（老平台无类型语义）。
 *
 * <p><b>生命周期</b>：由 {@code CallEngine} 在通话开始时启动、通话结束时停止
 * （见 CallEngine.start / CallEngine.end）。若发现当前已无通话（例如服务被
 * START_STICKY 单独拉起），本服务自行 {@code stopSelf()}，不留空壳通知。
 */
public class CallService extends Service {

    private static final String TAG = "CallService";

    /** 新渠道（与 NotificationHelper.FG_CHANNEL_ID = "familychat_fg" 区分开）。 */
    private static final String CHANNEL_ID = "familychat_call";
    private static final String CHANNEL_NAME = "通话中";
    /** 避开 ForegroundService 的 1001。 */
    private static final int NOTIFICATION_ID = 1002;

    private static final long TICK_MS = 1000L;

    private Handler handler;
    private Runnable ticker;
    /** 上次渲染的 (state, sec) 组合：任一变化才重发通知。 */
    private CallSession.State lastRenderedState;
    private int lastRenderedSec = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        // 前台通知必须最先建立：startForegroundService() 之后若 5 秒内没有 startForeground，
        // 系统会抛 RemoteServiceException（与 ForegroundService 的同类注释一致）。
        //
        // 第三参为显式位掩码（Android 14 后台掉麦修复），且 camera 位按【运行时权限】动态决定：
        // 纯语音通话不申请 CAMERA（见 CallActivity.checkCallPermissions：!isVideoEnabled 时
        // 只请求 RECORD_AUDIO），此时如果无条件把 camera 类型位传给 startForeground，
        // Android 14 会因"缺少该类型对应权限"直接抛 SecurityException 把服务打死。
        // 因此：microphone 位恒定（通话必有录音权限），camera 位仅在权限确已授予时叠加。
        //
        // 常量 API 级别（本地 SDK D:\Android\platforms\android-34\data\api-versions.xml 实测）：
        //   FOREGROUND_SERVICE_TYPE_MICROPHONE / _CAMERA  since 30
        //   FOREGROUND_SERVICE_TYPE_SPECIAL_USE           since 34
        // 三者都是编译期常量（值由 javac 内联，不产生运行时字段访问），因此低版本引用不会
        // NoSuchFieldError；但 API 29-33 的平台并不认识 SPECIAL_USE 这个位，
        // 故按版本分段，只传该平台已知的位，避免向老框架提交未知标志。
        final String initial = buildText(null);
        if (Build.VERSION.SDK_INT >= 30) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            if (Build.VERSION.SDK_INT >= 34) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            startForeground(NOTIFICATION_ID, buildNotification(initial), type);
        } else if (Build.VERSION.SDK_INT >= 29) {
            // API 29 不认识 microphone/camera/specialUse 这些位：维持原行为（用 manifest 声明的类型）
            startForeground(NOTIFICATION_ID, buildNotification(initial),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(initial));
        }
        AppLog.d(TAG, "onCreate: foreground started, text=" + initial);

        handler = new Handler(Looper.getMainLooper());
        ticker = new Runnable() {
            @Override
            public void run() {
                final CallSession s = CallManager.getCurrentCall();
                if (s == null) {
                    // 已经没有通话了（或被 START_STICKY 单独拉起）：自己收尾，不留空壳通知
                    AppLog.d(TAG, "ticker: no current call -> stopSelf");
                    stopSelf();
                    return;
                }
                final int sec = s.state == CallSession.State.ACTIVE ? s.activeElapsedSec() : 0;
                // 文案随状态变（呼叫中/来电中/通话中 MM:SS），时长只在 ACTIVE 阶段推进
                if (s.state != lastRenderedState || sec != lastRenderedSec) {
                    lastRenderedState = s.state;
                    lastRenderedSec = sec;
                    updateNotification(buildText(s));
                }
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 与 ForegroundService 一致：系统按 START_STICKY 重启时 intent 可能为 null，
        // 这里不做依赖 intent 的工作，通知内容一律从 CallManager 现取。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.d(TAG, "onDestroy");
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
        }
        ticker = null;
        handler = null;
        try {
            // API 24+：移除通知并退出前台态
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            AppLog.e(TAG, "onDestroy: stopForeground failed", e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =================================================================
    //  通知
    // =================================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);   // LOW：不响铃、不震动
        ch.setDescription("通话进行中的常驻通知");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /**
     * 按通话状态生成文案（U4 修复）：
     * {@code OUTGOING_RINGING} → "呼叫中…"，{@code INCOMING_RINGING} → "来电中…"，
     * {@code ACTIVE} → "通话中 MM:SS"。{@code null} / 其它瞬态 → "通话中"。
     */
    private String buildText(CallSession s) {
        if (s == null) return "通话中";
        switch (s.state) {
            case OUTGOING_RINGING:
                return "呼叫中…";
            case INCOMING_RINGING:
                return "来电中…";
            case ACTIVE: {
                final int sec = s.activeElapsedSec();
                final int m = sec / 60;
                return String.format(java.util.Locale.US, "通话中 %02d:%02d", m, sec % 60);
            }
            default:
                return "通话中";
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("密语")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(buildContentIntent())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    /**
     * 点击通知 → 拉回通话页。
     *
     * <p>用 {@code Routes.CALL_ACTIVITY} 类名字符串而不是直接 import：
     * 与 {@code CallManager.launchIncomingUi} 同款做法（CallActivity 属于 :feature-chat，
     * 走字符串可避免模块间编译期耦合）。
     *
     * <p>{@code FLAG_ACTIVITY_SINGLE_TOP}：通话页若已经在返回栈里（Step D 的悬浮窗场景：
     * Activity 只是 onStop，没有销毁），点击通知应把它<b>带回前台</b>而不是再建一个实例。
     * {@code FLAG_ACTIVITY_NEW_TASK}：PendingIntent 从通知发起，必须有它。
     */
    private PendingIntent buildContentIntent() {
        Intent it = new Intent();
        it.setClassName(this, Routes.CALL_ACTIVITY);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // 尽力携带通话上下文：CallActivity.onCreate 用 "from_user_id" 复原 remoteUser，
        // 从而能认领 CallManager 里已存在的外呼会话（见 CallActivity 的 adopt existing session 分支）。
        final CallSession s = CallManager.getCurrentCall();
        if (s != null) {
            it.putExtra("call_id", s.callId);
            it.putExtra("incoming", s.incoming);
            it.putExtra("video", s.videoEnabled);
            if (!s.incoming) {
                // 外呼：CallActivity 认领会话的条件是 remoteUser.id == existing.calleeId
                it.putExtra("from_user_id", s.calleeId);
            } else {
                it.putExtra("from_user_id", s.callerId);
                if (s.offerSdp != null) it.putExtra("offer_sdp", s.offerSdp);
            }
        }

        return PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
