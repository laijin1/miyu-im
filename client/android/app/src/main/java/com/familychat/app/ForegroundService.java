package com.familychat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.familychat.app.api.PushConnection;
import com.familychat.app.utils.AppLog;

/**
 * 后台保活前台服务。
 *
 * <p>职责（连接宿主下沉 · 第一步之后）：
 * <ul>
 *   <li>维持前台通知，避免进程被系统随意回收；</li>
 *   <li><b>驱动 {@link PushConnection} 的生命周期</b> —— 进程被系统拉起后恢复推送连接。</li>
 * </ul>
 * 本服务<b>不持有连接本身</b>：连接的持有者是 :core 的 {@code PushConnection}，
 * 那样 :feature-chat / :feature-group 也能访问到它（它们看不见 :app）。
 */
public class ForegroundService extends Service {

    private static final String TAG = "ForegroundService";

    private static final String CHANNEL_ID = com.familychat.app.notify.NotificationHelper.FG_CHANNEL_ID;
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();

        // ⚠️ 前台通知必须最先建立：startForegroundService() 之后若 5 秒内没有 startForeground，
        // 系统会抛 RemoteServiceException 直接崩溃。PushConnection.start() 要读加密
        // SharedPreferences（磁盘 I/O），所以绝不能排在 startForeground() 之前。
        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("密语")
                .setContentText("正在后台运行")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        startPushConnection("onCreate");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "后台服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持应用后台运行");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 系统按 START_STICKY 重启本服务时会带 null intent 重新进入这里，而 onCreate
        // 不一定重跑 —— 因此每次 onStartCommand 都补一次 start()（start 是幂等的）。
        startPushConnection("onStartCommand");
        return START_STICKY;
    }

    private void startPushConnection(String from) {
        try {
            PushConnection.start(this);
        } catch (Exception e) {
            AppLog.e(TAG, "PushConnection.start failed (" + from + ")", e);
        }
    }

    @Override
    public void onDestroy() {
        // 只在服务真正销毁时断开连接（退出登录 / 401 自动登出路径）。
        // Activity 的 onDestroy 一律只做 removeListener，绝不调用 stop()。
        PushConnection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
