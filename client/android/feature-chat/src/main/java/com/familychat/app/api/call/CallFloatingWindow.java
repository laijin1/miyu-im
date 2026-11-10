package com.familychat.app.api.call;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_chat.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Routes;
import com.familychat.app.utils.ServerProtocol;

import java.util.Locale;

/**
 * 通话系统级悬浮窗（可任意拖动，浮在所有 App 之上）。
 *
 * <h3>窗口类型与权限</h3>
 * {@code TYPE_APPLICATION_OVERLAY}（API 26+）；API 24-25 没有该常量，退回同样只需
 * {@code SYSTEM_ALERT_WINDOW} 的 {@code TYPE_PHONE}。两者都要求
 * {@code Settings.canDrawOverlays()} 为 true —— 否则 {@link #show} 直接返回 false，
 * 绝不尝试 {@code addView}（会抛 {@code WindowManager.BadTokenException}）。
 *
 * <h3>触摸</h3>
 * 拖动用 {@code OnTouchListener} 的 ACTION_MOVE 增量更新
 * {@link WindowManager.LayoutParams#x}/{@link WindowManager.LayoutParams#y}；
 * 单击（非拖动）用 {@link GestureDetector#onSingleTapConfirmed} 判定，然后拉回通话页。
 * 静音/挂断按钮是子 View，会先消费自己的点击，不会触发拖动。
 *
 * <h3>存活条件</h3>
 * 由 {@code CallEngine} 驱动：通话结束时 {@code CallEngine.end()} 会调 {@link #hide()}；
 * 计时线程发现 {@code CallManager.getCurrentCall() == null} 时也会自行 {@link #hide()}。
 *
 * <p><b>不在本类范围内</b>：悬浮窗内的视频（多目标分发 + 位图回收重写）—— 见 Step D7。
 */
public final class CallFloatingWindow {

    private static final String TAG = "CallFloatingWindow";

    /** 与 floating_call_window.xml 的固定尺寸保持一致，仅用于首次定位。 */
    private static final int WINDOW_W_DP = 140;
    private static final int WINDOW_H_DP = 90;
    private static final int MARGIN_DP = 16;

    private static final long TICK_MS = 1000L;

    private static final Object LOCK = new Object();

    private static WindowManager windowManager;
    private static View rootView;
    private static WindowManager.LayoutParams params;

    private static Handler handler;
    private static Runnable ticker;

    private static String peerName;
    private static String peerAvatar;
    private static boolean muted;

    private CallFloatingWindow() {
    }

    // =================================================================
    //  对外接口
    // =================================================================

    /**
     * 对端资料（可选）。在 {@link #show} 之前调用；{@code avatar} 可以是服务端相对路径。
     * 不设置时显示 XML 占位文案与占位头像。
     */
    public static void setPeerInfo(String name, String avatar) {
        synchronized (LOCK) {
            peerName = name;
            peerAvatar = avatar;
        }
    }

    /**
     * 显示悬浮窗。
     *
     * @return true = 已显示；false = 无权限 / 无通话 / 平台拒绝（都只打日志）
     */
    public static boolean show(Context ctx) {
        if (ctx == null) return false;

        synchronized (LOCK) {
            if (rootView != null) {
                AppLog.d(TAG, "show: already showing");
                return true;
            }
        }
        if (!Settings.canDrawOverlays(ctx)) {
            AppLog.e(TAG, "show: SYSTEM_ALERT_WINDOW not granted — skip");
            return false;
        }
        if (CallManager.getCurrentCall() == null) {
            AppLog.e(TAG, "show: no current call — skip");
            return false;
        }

        final WindowManager wm = (WindowManager) ctx.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            AppLog.e(TAG, "show: WINDOW_SERVICE unavailable");
            return false;
        }

        final View view;
        try {
            view = LayoutInflater.from(ctx).inflate(R.layout.floating_call_window, null);
        } catch (Exception e) {
            AppLog.e(TAG, "show: inflate failed", e);
            return false;
        }

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        final int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        lp.x = screenW - dp(ctx, WINDOW_W_DP) - dp(ctx, MARGIN_DP);
        lp.y = statusBarHeight(ctx) + dp(ctx, MARGIN_DP);

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            AppLog.e(TAG, "show: addView failed", e);
            return false;
        }

        synchronized (LOCK) {
            windowManager = wm;
            rootView = view;
            params = lp;
        }

        bindContent(ctx, view, wm, lp);
        startTicker(ctx);
        AppLog.d(TAG, "show ok: x=" + lp.x + " y=" + lp.y);
        return true;
    }

    /** 隐藏悬浮窗（幂等）。 */
    public static void hide() {
        final WindowManager wm;
        final View view;
        synchronized (LOCK) {
            wm = windowManager;
            view = rootView;
            windowManager = null;
            rootView = null;
            params = null;
        }
        stopTicker();
        if (wm == null || view == null) {
            AppLog.d(TAG, "hide: not showing");
            return;
        }
        try {
            wm.removeView(view);
            AppLog.d(TAG, "hide ok");
        } catch (Exception e) {
            AppLog.e(TAG, "hide: removeView failed", e);
        }
    }

    public static boolean isShowing() {
        synchronized (LOCK) {
            return rootView != null;
        }
    }

    // =================================================================
    //  内容绑定
    // =================================================================

    private static void bindContent(final Context ctx, final View view,
                                    final WindowManager wm, final WindowManager.LayoutParams lp) {
        final TextView tvName = view.findViewById(R.id.tvFloatName);
        final TextView tvTimer = view.findViewById(R.id.tvFloatTimer);
        final ImageView ivAvatar = view.findViewById(R.id.ivFloatAvatar);
        final ImageButton btnMute = view.findViewById(R.id.btnFloatMute);
        final ImageButton btnHangup = view.findViewById(R.id.btnFloatHangup);

        // ---- 对端资料 ----
        final String name;
        final String avatar;
        synchronized (LOCK) {
            name = peerName;
            avatar = peerAvatar;
        }
        if (tvName != null && name != null && !name.isEmpty()) {
            tvName.setText(name);
        }
        if (ivAvatar != null) {
            final String url = avatarUrl(ctx, avatar);
            if (url != null) {
                Glide.with(ctx).load(url).circleCrop().into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        }

        // ---- 静音 ----
        if (btnMute != null) {
            updateMuteIcon(btnMute);
            btnMute.setOnClickListener(v -> {
                CallEngine.toggleMute();
                synchronized (LOCK) {
                    muted = !muted;
                }
                updateMuteIcon(btnMute);
            });
        }

        // ---- 挂断 ----
        if (btnHangup != null) {
            btnHangup.setOnClickListener(v -> {
                AppLog.d(TAG, "hangup tapped from floating window");
                final CallSession s = CallManager.getCurrentCall();
                if (s != null) {
                    CallManager.endCall(s.callId);
                } else {
                    AppLog.e(TAG, "hangup: no current call (CallManager already idle)");
                }
                CallEngine.end();
                hide();
            });
        }

        // ---- 拖动 + 单击回到通话页 ----
        final GestureDetector gesture = new GestureDetector(ctx,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        openCallActivity(ctx);
                        return true;
                    }
                });

        view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gesture.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        lp.x = startX + (int) (event.getRawX() - downRawX);
                        lp.y = startY + (int) (event.getRawY() - downRawY);
                        // D-U2：边界钳制 —— 允许稍微出界，但保证至少有一半可见，
                        // 否则用户能把窗口拖到完全看不见且找不回来的地方
                        // （FLAG_LAYOUT_NO_LIMITS 不为窗口做任何边界约束）。
                        final int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
                        final int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
                        final int viewW = v.getWidth();
                        final int viewH = v.getHeight();
                        final int minX = -viewW / 2;
                        final int maxX = screenW - viewW / 2;
                        final int minY = -viewH / 2;
                        final int maxY = screenH - viewH / 2;
                        lp.x = Math.max(minX, Math.min(maxX, lp.x));
                        lp.y = Math.max(minY, Math.min(maxY, lp.y));
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (Exception e) {
                            AppLog.e(TAG, "drag: updateViewLayout failed", e);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                    default:
                        return true;
                }
            }
        });

        // ---- 首次布局后用真实宽度校正初始位置（布局宽度由 XML 决定，避免硬编码漂移） ----
        view.post(() -> {
            final int w = view.getWidth();
            if (w > 0) {
                final int sw = ctx.getResources().getDisplayMetrics().widthPixels;
                lp.x = sw - w - dp(ctx, MARGIN_DP);
                try {
                    wm.updateViewLayout(view, lp);
                } catch (Exception e) {
                    AppLog.e(TAG, "post-layout reposition failed", e);
                }
            }
        });
    }

    private static void updateMuteIcon(ImageButton btnMute) {
        final boolean m;
        synchronized (LOCK) {
            m = muted;
        }
        btnMute.setImageResource(m ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        btnMute.setColorFilter(m ? 0xFFF15A24 : 0xFFFFFFFF);
    }

    /**
     * 点击悬浮窗 → 拉回通话页。extras 与 {@code CallService.buildContentIntent()} 同款，
     * 目的是让 {@code CallActivity} 能认领 CallManager 里已存在的会话。
     */
    private static void openCallActivity(Context ctx) {
        try {
            Intent it = new Intent();
            it.setClassName(ctx, Routes.CALL_ACTIVITY);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            final CallSession s = CallManager.getCurrentCall();
            if (s != null) {
                it.putExtra("call_id", s.callId);
                it.putExtra("incoming", s.incoming);
                it.putExtra("video", s.videoEnabled);
                if (!s.incoming) {
                    it.putExtra("from_user_id", s.calleeId);
                } else {
                    it.putExtra("from_user_id", s.callerId);
                    if (s.offerSdp != null) it.putExtra("offer_sdp", s.offerSdp);
                }
            }
            ctx.startActivity(it);
            AppLog.d(TAG, "openCallActivity ok");
        } catch (Exception e) {
            AppLog.e(TAG, "openCallActivity failed (后台启动 Activity 可能被系统拦截)", e);
        }
    }

    // =================================================================
    //  计时
    // =================================================================

    private static void startTicker(final Context ctx) {
        stopTicker();
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                final View v;
                synchronized (LOCK) {
                    v = rootView;
                }
                if (v == null) return;   // 已被 hide()：停止，不再自我排队
                final CallSession s = CallManager.getCurrentCall();
                if (s == null) {
                    AppLog.d(TAG, "ticker: no current call -> hide");
                    hide();
                    return;
                }
                final TextView tv = v.findViewById(R.id.tvFloatTimer);
                if (tv != null) {
                    final int sec = s.activeElapsedSec();
                    tv.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
                }
                h.postDelayed(this, TICK_MS);
            }
        };
        synchronized (LOCK) {
            handler = h;
            ticker = r;
        }
        h.postDelayed(r, TICK_MS);
    }

    private static void stopTicker() {
        final Handler h;
        final Runnable r;
        synchronized (LOCK) {
            h = handler;
            r = ticker;
            handler = null;
            ticker = null;
        }
        if (h != null && r != null) {
            h.removeCallbacks(r);
        }
    }

    // =================================================================
    //  工具
    // =================================================================

    /** API 26+ 用 TYPE_APPLICATION_OVERLAY；24-25 无该常量，退回 TYPE_PHONE（同为系统级覆盖窗）。 */
    @SuppressWarnings("deprecation")
    private static int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static int statusBarHeight(Context ctx) {
        try {
            final int id = ctx.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Exception e) {
            AppLog.e(TAG, "statusBarHeight lookup failed", e);
        }
        return dp(ctx, 24);
    }

    /** 把服务端相对头像路径拼成带 token 的绝对 URL；无头像返回 null。 */
    private static String avatarUrl(Context ctx, String avatar) {
        if (avatar == null || avatar.isEmpty()) return null;
        try {
            if (!avatar.startsWith("/")) return avatar;   // 已经是绝对 URL
            final PreferencesManager prefs = PreferencesManager.getInstance(ctx);
            return ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                    prefs.getToken());
        } catch (Exception e) {
            AppLog.e(TAG, "avatarUrl failed", e);
            return null;
        }
    }
}
