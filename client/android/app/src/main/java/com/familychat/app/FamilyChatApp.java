package com.familychat.app;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import com.familychat.app.api.WebRTCManager;
import com.familychat.app.notify.NotificationHelper;

/**
 * Application class — performs one-time initialization of global singletons.
 */
public class FamilyChatApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化应用内日志（设置→日志 查看报错）
        try {
            com.familychat.app.utils.AppLog.init(this);
            // 每次启动把版本号写进日志头部，方便确认设备实际跑的是哪个版本
            com.familychat.app.utils.AppLog.d("APP", "应用启动，版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        } catch (Exception ignored) {}

        // 应用上次保存的黑夜/白天模式（必须在任何 Activity 创建之前）
        try {
            boolean night = com.familychat.app.utils.PreferencesManager.getInstance(this).getNightMode();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    night ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                          : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        } catch (Exception ignored) {}

        // E2EE TOFU pin：对端公钥本地固定（docs/audits/E2EE_SECURITY_AUDIT §1.1/§1.2）。
        // 必须在任何聊天页之前装好，否则 PeerKeyPinStore 没有后端，pin 校验整体失效。
        try {
            com.familychat.app.utils.PeerKeyPinStore.install(
                    com.familychat.app.utils.PreferencesManager.getInstance(this));
        } catch (Exception ignored) {}

        // Recover any crash from previous launch
        try {
            String lastCrash = getSharedPreferences("crash_prefs", MODE_PRIVATE)
                .getString("last_crash", null);
            if (lastCrash != null) {
                // Write recovered crash to visible files
                writeCrashLogSync(new java.io.File(getFilesDir(), "crash_log.txt"), lastCrash);
                java.io.File extDir = getExternalFilesDir(null);
                if (extDir != null)
                    writeCrashLogSync(new java.io.File(extDir, "crash_log.txt"), lastCrash);
                // Clear so we don't write it again
                getSharedPreferences("crash_prefs", MODE_PRIVATE).edit().remove("last_crash").commit();
            }
        } catch (Exception ignored) {}

        // Global uncaught exception handler — writes crash log to file + logcat.
        // 报错后不弹系统崩溃窗：把报错写入 设置→日志（AppLog）后直接静默结束进程。
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                pw.println("=== 密语 崩溃日志 ===");
                pw.println("时间: " + new java.util.Date().toString());
                pw.println("线程: " + thread.getName());
                pw.println("异常: " + throwable.toString());
                throwable.printStackTrace(pw);
                Throwable cause = throwable.getCause();
                while (cause != null) {
                    pw.println("Caused by: " + cause.toString());
                    cause.printStackTrace(pw);
                    cause = cause.getCause();
                }
                pw.println("=== 日志结束 ===");
                pw.close();
                String crashLog = sw.toString();

                // Write to logcat
                Log.e("FATAL_CRASH", crashLog);

                // 写入应用内日志（设置→日志 界面可见）
                try {
                    com.familychat.app.utils.AppLog.e("CRASH", crashLog);
                } catch (Exception ignored) {}

                // Save to SharedPreferences (sync commit — survives process kill)
                try {
                    getSharedPreferences("crash_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_crash", crashLog)
                        .commit();
                } catch (Exception ignored) {}

                // Write to visible locations with fsync
                writeCrashLogSync(new java.io.File(getFilesDir(), "crash_log.txt"), crashLog);

                try {
                    java.io.File extDir = getExternalFilesDir(null);
                    if (extDir != null)
                        writeCrashLogSync(new java.io.File(extDir, "crash_log.txt"), crashLog);
                } catch (Exception ignored) {}

                try {
                    java.io.File crashDir = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), "FamilyChat");
                    crashDir.mkdirs();
                    writeCrashLogSync(new java.io.File(crashDir, "crash_log.txt"), crashLog);
                } catch (Exception ignored) {}

                writeCrashLogSync(new java.io.File(getCacheDir(), "crash_log.txt"), crashLog);
            } catch (Exception ignored) {}

            // 不调用系统默认处理器 → 系统不再弹「密语 已停止运行」崩溃框。
            // 报错已写入 设置→日志（AppLog）与 crash_log.txt，重启后进日志页即可查看。
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });

        // Create notification channel
        NotificationHelper.createChannel(this);

        // 进程级通知派发：注册到 PushConnection 的常驻监听器（只持 applicationContext，不持 Activity）。
        // 必须在这里而不是 MainActivity —— MainActivity 不在时（用户没打开过 App / 主界面已销毁）
        // 通知链仍要活着；进程被 ForegroundService 按 START_STICKY 拉起时同样会重跑本方法。
        com.familychat.app.notify.NotificationDispatcher.install(this);

        // 进程级通话状态机：注册到 PushConnection 的常驻监听器（只持 applicationContext）。
        // 与 NotificationDispatcher 相互独立，注册顺序无关；同样刻意不反注册
        // （PushConnection.stop() 不清空监听器，重新登录后同一条连接重启，本类继续有效）。
        // ⚠️ Step 1 / C3 阶段内部开关 OBSERVE_ONLY=true：只更新状态 + 打日志，
        //    不启动超时、不发信令、不启动 UI、不写通话状态系统消息（新旧路径并存，必须静默）。
        com.familychat.app.api.call.CallManager.install(this);

        // 来电通知兜底（批 1）：后台/锁屏来电时 Android 10+ 的 startActivity 会被静默拦截，
        // 此时必须有一条可全屏拉起的通知 —— 由 CallManager 的可插拔钩子回调到这里。
        // 顺序要求：必须晚于 CallManager.install（内部要 addObserver），早于 CallEngine.install 无影响。
        com.familychat.app.api.call.CallManager.setIncomingUiFallback(
                com.familychat.app.notify.IncomingCallNotifier.install(this));

        // 进程级通话媒体引擎：持有 WebRTCManager 并把信令/媒体监听器注册到 PushConnection。
        // 与上面两个安装点相互独立；同样只持 applicationContext，且刻意不反注册 ——
        // 这正是"通话能活过 CallActivity"的前提（媒体信令在通话页销毁后仍须到达）。
        com.familychat.app.api.call.CallEngine.install(this);

        // Global 401 handler — registered exactly once here, never by an Activity.
        // 只注册一次，且只捕获 application 级引用（appCtx / mainHandler），不持有任何 Activity。
        // 历史 bug：MainActivity 与 CallActivity 各自注册并互相覆盖/清除，导致通话结束后
        // 其它页面（商城/钱包/群聊）收到 401 不再自动登出。任何 Activity 都不得再注册或清除它。
        try {
            final android.content.Context appCtx = getApplicationContext();
            final android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            com.familychat.app.api.ApiClient.setAuthFailureListener(() -> {
                // 去重：token 过期时会有多个并发请求同时返回 401（典型 3~5 个），
                // 只有第一个能抢到名额，其余直接返回，避免 LoginActivity 被反复创建/销毁。
                if (!com.familychat.app.auth.AuthFailureGuard.tryBegin()) return;
                try {
                    mainHandler.post(() -> {
                        try {
                            com.familychat.app.utils.PreferencesManager.getInstance(appCtx).logout();

                            // 停掉前台服务 —— 连接宿主下沉后，WebSocket 由 ForegroundService
                            // 驱动（PushConnection 持有）。若不停服务，它会拿着刚被清掉的废
                            // token 对这个地址无限重连（START_STICKY 还会把进程拉回来）。
                            // 独立 try/catch：这一句失败绝不能连带触发 AuthFailureGuard.reset()，
                            // 否则本次之后再也无法自动登出。
                            try {
                                appCtx.stopService(new Intent(appCtx,
                                        com.familychat.app.ForegroundService.class));
                            } catch (Exception se) {
                                Log.e("FamilyChatApp", "stopService(fg) failed after 401", se);
                            }

                            // 清空整个任务栈并跳转登录页：栈内 Activity 会被销毁，各自在
                            // onDestroy 里 removeListener。连接本身由上面停掉的服务负责断开。
                            Intent intent = new Intent(appCtx, com.familychat.app.ui.LoginActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                            appCtx.startActivity(intent);
                        } catch (Exception e) {
                            // 处理失败必须归还名额，否则本次之后再也无法自动登出
                            com.familychat.app.auth.AuthFailureGuard.reset();
                            Log.e("FamilyChatApp", "auth-failure handling failed", e);
                        }
                    });
                } catch (Exception e) {
                    // post 本身失败也要归还名额
                    com.familychat.app.auth.AuthFailureGuard.reset();
                    Log.e("FamilyChatApp", "auth-failure post failed", e);
                }
            });
        } catch (Exception e) {
            Log.e("FamilyChatApp", "Failed to register auth-failure listener", e);
        }

        // Initialize WebRTC globally (required before any PeerConnectionFactory is created).
        // IMPORTANT: Must run on the main thread — PeerConnectionFactory.initialize()
        // sets up native libraries and JNI bindings that require the main Looper.
        try {
            WebRTCManager.initializeGlobal(this);
            Log.i("FamilyChatApp", "WebRTC initialized successfully");
        } catch (Exception e) {
            Log.e("FamilyChatApp", "Failed to init WebRTC", e);
        }
    }

    /** Write crash log with fsync to ensure it's on disk before process dies. */
    private static void writeCrashLogSync(java.io.File file, String content) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } catch (Exception ignored) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }
}
