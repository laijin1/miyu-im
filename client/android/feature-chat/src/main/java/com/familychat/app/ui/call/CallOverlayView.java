package com.familychat.app.ui.call;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.familychat.app.api.call.CallEngine;
import com.familychat.app.api.call.CallManager;
import com.familychat.app.api.call.CallSession;
import com.familychat.app.feature_chat.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.util.Locale;

/**
 * 通话"应用内浮层"（App 内小窗）的视图。
 *
 * <p>形态照 {@code ui/voice/VoiceRecordOverlay}（同为 FrameLayout 子类 + 构造期 inflate），
 * 由 {@link com.familychat.app.api.call.CallOverlayController} 创建并挂到宿主 Activity 的
 * {@code android.R.id.content} 上。
 *
 * <h3>两套布局：视频版 / 语音版（按 videoEnabled 分流）</h3>
 * 构造期读 {@code CallManager.getCurrentCall().videoEnabled} 决定 inflate 哪一份：
 * <ul>
 *   <li><b>视频</b> → {@code R.layout.call_overlay_view}（120×160dp，三层：远端画面 +
 *       本地自视小窗 + 名字/计时 + 4 按钮）。此版本持有下面三个渲染目标。</li>
 *   <li><b>语音</b> → {@code R.layout.call_overlay_view_audio}（120×120dp，头像 + 名字 +
 *       计时 + <b>同样 4 个按钮</b>）。<b>没有视频层</b>，三个渲染目标字段全为 {@code null}，
 *       {@link #hasVideoLayer()} 返回 false —— 调用方据此跳过全部渲染目标注入。</li>
 * </ul>
 * 两套布局的按钮 id、尺寸、点击逻辑完全一致，本类对它们不做分支。
 *
 * <h3>它持有的三个渲染目标（仅视频版；与 activity_call.xml 同构）</h3>
 * <ul>
 *   <li>{@link #localPreviewView}（TextureView）—— 相机唯一的 preview surface 锚点</li>
 *   <li>{@link #localPreviewFrame}（ImageView）—— 本地预览显示层（解码 JPEG 后 centerCrop）</li>
 *   <li>{@link #remoteVideoView}（ImageView）—— 远端帧显示层</li>
 * </ul>
 * 三者通过 {@code CallEngine.setRendererViews(...)} / {@code CallEngine.setRelayRemoteView(...)}
 * 注册进进程级媒体引擎；本类只负责"提供 View + 画状态"，不直接碰 WebRTCManager。
 *
 * <h3>触摸约定（重要）</h3>
 * <p>只有<b>可见的那块 120×160dp 盒子</b>（布局根 {@code callOverlayRoot}）消费点击；
 * {@code this}（外层 FrameLayout）<b>刻意不设 OnClickListener</b>，因此它
 * {@code dispatchTouchEvent} 返回 false，盒子之外的触摸会穿透给宿主 Activity ——
 * 否则用 MATCH_PARENT 的外层 View 会把整个宿主页面的输入全吃掉（聊天列表滑不动）。
 *
 * <h3>本批不做</h3>
 * 进出动画（后续批次）。
 */
public class CallOverlayView extends FrameLayout {

    private static final String TAG = "CallOverlayView";

    private static final long TICK_MS = 1000L;

    /** 浮层交互回调，由 CallOverlayController 实现（转调通话/导航动作）。 */
    public interface Callback {
        /** 点浮层非按钮区 / 放大按钮 → 回通话页。 */
        void onExpand();

        /** 挂断按钮。 */
        void onHangup();

        /** 闭麦按钮（关自己的麦克风，对方听不到你）。本 View 已就地调用引擎，此回调仅作通知。 */
        void onMute();

        /** 静音对方按钮（本地拦下行，对方不知道）。本 View 已就地调用引擎，此回调仅作通知。 */
        void onLocalMute();
    }

    private Callback callback;

    /**
     * 本实例是否带视频层（视频通话 = true，语音通话 = false）。
     *
     * <p>构造期由 {@code CallManager.getCurrentCall().videoEnabled} 决定，之后不变。
     * false 时 {@link #localPreviewView} / {@link #localPreviewFrame} / {@link #remoteVideoView}
     * 全为 null，调用方（{@code CallOverlayController}）必须跳过渲染目标注入。
     */
    private final boolean videoLayer;

    // ---- 渲染目标（仅视频版非 null；对外只读暴露，交给 CallEngine 注册） ----
    private TextureView localPreviewView;
    private ImageView localPreviewFrame;
    private ImageView remoteVideoView;

    // ---- 状态控件 ----
    private TextView tvName;
    private TextView tvTimer;
    private ImageView ivAvatar;
    private ImageButton btnMute;
    private ImageButton btnLocalMute;
    private ImageButton btnHangup;
    private ImageButton btnExpand;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private boolean destroyed = false;

    /** 区分"点击（回通话页）"与"拖动（挪浮层）"：单击确认才 onExpand，拖过 slop 不算点击。 */
    private final android.view.GestureDetector gesture;

    public CallOverlayView(Context ctx) {
        super(ctx);
        // 外层只包住 120×160dp（音频版 120×120dp）的可见盒子：WRAP_CONTENT 让触摸footprint == 可见范围。
        // 若交给 addView 走默认参数，FrameLayout 会给 MATCH_PARENT（铺满宿主内容区）。
        final LayoutParams lp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        setLayoutParams(lp);

        // 按通话类型分流 inflate —— 语音版整份布局里没有任何视频 View，
        // 因此不可能"语音却显示视频画面"（视频版的 View 一行都不 inflate）。
        // 无通话（理论上 attach 已挡住）时按语音版处理：不注入相机/远端渲染目标最安全。
        final CallSession session = CallManager.getCurrentCall();
        videoLayer = session != null && session.videoEnabled;
        LayoutInflater.from(ctx).inflate(
                videoLayer ? R.layout.call_overlay_view : R.layout.call_overlay_view_audio, this);

        // 语音版没有这三个 id，findViewById 返回 null；字段保持 null 是**刻意**的，
        // 上层据 hasVideoLayer() 跳过注入。
        localPreviewView = findViewById(R.id.overlayLocalPreviewView);
        localPreviewFrame = findViewById(R.id.overlayLocalPreviewFrame);
        remoteVideoView = findViewById(R.id.overlayRemoteVideo);

        tvName = findViewById(R.id.overlayName);
        tvTimer = findViewById(R.id.overlayTimer);
        ivAvatar = findViewById(R.id.overlayAudioAvatar);
        btnMute = findViewById(R.id.overlayBtnMute);
        btnLocalMute = findViewById(R.id.overlayBtnLocalMute);
        btnHangup = findViewById(R.id.overlayBtnHangup);
        btnExpand = findViewById(R.id.overlayBtnExpand);

        // 点可见盒子（非按钮区）→ 放大回通话页；按住拖动 → 挪浮层。
        // 注意：是 callOverlayRoot，不是 this —— 见类注释"触摸约定"。
        final View box = findViewById(R.id.callOverlayRoot);

        gesture = new android.view.GestureDetector(getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(
                            android.view.MotionEvent e) {
                        if (callback != null) callback.onExpand();
                        return true;
                    }
                });

        if (box != null) {
            box.setOnTouchListener(new android.view.View.OnTouchListener() {
                private float downRawX, downRawY;
                private float startTx, startTy;
                private boolean dragging;

                @Override
                public boolean onTouch(android.view.View v,
                                       android.view.MotionEvent event) {
                    gesture.onTouchEvent(event);
                    switch (event.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downRawX = event.getRawX();
                            downRawY = event.getRawY();
                            startTx = getTranslationX();
                            startTy = getTranslationY();
                            dragging = false;
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            float dx = event.getRawX() - downRawX;
                            float dy = event.getRawY() - downRawY;
                            final int slop = android.view.ViewConfiguration
                                    .get(getContext()).getScaledTouchSlop();
                            if (!dragging
                                    && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                                dragging = true;
                            }
                            if (dragging) {
                                float nx = startTx + dx;
                                float ny = startTy + dy;
                                final int w = getWidth();
                                final int h = getHeight();
                                final int screenW = getResources()
                                        .getDisplayMetrics().widthPixels;
                                final int screenH = getResources()
                                        .getDisplayMetrics().heightPixels;
                                final int baseLeft = getLeft();
                                final int baseTop  = getTop();
                                final float minTx = -baseLeft - w / 2f;
                                final float maxTx = screenW - baseLeft - w / 2f;
                                final float minTy = -baseTop - h / 2f;
                                final float maxTy = screenH - baseTop - h / 2f;
                                nx = Math.max(minTx, Math.min(maxTx, nx));
                                ny = Math.max(minTy, Math.min(maxTy, ny));
                                setTranslationX(nx);
                                setTranslationY(ny);
                            }
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                        default:
                            return true;
                    }
                }
            });
        }

        if (btnMute != null) {
            btnMute.setOnClickListener(v -> {
                CallEngine.toggleMute();   // 不再维护本地镜像，图标状态由 refreshFromEngine 现取
                refreshFromEngine();
                if (callback != null) callback.onMute();
            });
        }

        if (btnLocalMute != null) {
            btnLocalMute.setOnClickListener(v -> {
                CallEngine.toggleLocalMute();
                refreshFromEngine();
                if (callback != null) callback.onLocalMute();
            });
        }

        if (btnHangup != null) {
            btnHangup.setOnClickListener(v -> {
                if (callback != null) callback.onHangup();
            });
        }

        if (btnExpand != null) {
            btnExpand.setOnClickListener(v -> {
                if (callback != null) callback.onExpand();
            });
        }

        refreshFromEngine();
    }

    // =================================================================
    //  对外接口
    // =================================================================

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /** 设置对端昵称（空值不覆盖占位文案）。两套布局都认领，无分支。 */
    public void setPeerInfo(String name) {
        if (tvName == null) return;
        if (name != null && !name.isEmpty()) tvName.setText(name);
    }

    /**
     * 设置对端头像（仅语音版有这个控件；视频版没有头像位，静默无操作）。
     *
     * <p>{@code avatar} 可以是服务端相对路径（{@code /media/...}）。空值时保持 XML 里的
     * 占位头像。加载方式与 {@code CallFloatingWindow.bindContent} 一致（Glide + circleCrop），
     * 走 applicationContext 以免持有 Activity。
     */
    public void setPeerAvatar(String avatar) {
        if (ivAvatar == null) return;
        final String url = avatarUrl(avatar);
        if (url == null) {
            ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
            return;
        }
        try {
            Glide.with(getContext().getApplicationContext())
                    .load(url).circleCrop().into(ivAvatar);
        } catch (Exception e) {
            AppLog.e(TAG, "setPeerAvatar failed", e);
        }
    }

    /**
     * 本实例是否有视频层（视频通话 true / 语音通话 false）。
     *
     * <p>构造后不变。调用方必须先看这个再碰 {@link #getLocalPreviewView()} /
     * {@link #getLocalPreviewFrame()} / {@link #getRemoteVideoView()} ——
     * 语音版这三个恒为 null，把它们注入 {@code CallEngine} 会清掉正在出帧的相机锚点。
     */
    public boolean hasVideoLayer() {
        return videoLayer;
    }

    /** 相机 preview surface 锚点。<b>仅视频版非 null</b>；语音版返回 null，调用方必须先判 {@link #hasVideoLayer()}。 */
    public TextureView getLocalPreviewView() {
        return localPreviewView;
    }

    /** 本地预览显示层。<b>仅视频版非 null</b>；语音版返回 null。 */
    public ImageView getLocalPreviewFrame() {
        return localPreviewFrame;
    }

    /** 远端帧显示层。<b>仅视频版非 null</b>；语音版返回 null。 */
    public ImageView getRemoteVideoView() {
        return remoteVideoView;
    }

    /**
     * 从引擎/状态机现取状态刷 UI：闭麦、静音对方、通话计时。
     *
     * <p>秒级计时由内部 ticker（1s）驱动，本方法只做一次即时刷新 ——
     * 按钮点击后调用它可以立刻看到图标变化，不必等下一个 tick。
     */
    public void refreshFromEngine() {
        if (destroyed) return;

        // ---- 闭麦（现取。CallEngine.isMuted() → WebRTCManager.isMuted()，与通话页/系统浮窗同一份状态） ----
        if (btnMute != null) {
            final boolean muted = CallEngine.isMuted();
            btnMute.setImageResource(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setColorFilter(muted ? 0xFFF15A24 : 0xFFFFFFFF);
        }

        // ---- 静音对方（可从引擎现取：P1 新增的 CallEngine.isLocalMuted()） ----
        if (btnLocalMute != null) {
            final boolean localMuted = CallEngine.isLocalMuted();
            btnLocalMute.setColorFilter(localMuted ? 0xFFF15A24 : 0xFFFFFFFF);
        }

        // ---- 计时 ----
        refreshTimer();
        ensureTicker();
    }

    /**
     * 释放：停 ticker、断回调引用。
     *
     * <p><b>刻意不反注册渲染目标</b>：{@code setRelayLocalPreviewView(null)} 会摘掉相机
     * preview surface，而 {@code WebRTCManager.rearmRelayPreview()} 在 anchor == null 时
     * 直接早返回 —— 相机被摘掉后没有任何东西会重新挂上，本地采集会永久停（对方看不到你）。
     * 渲染目标的切换交给"下一个宿主 attach 时重新 setRendererViews"。
     */
    public void destroy() {
        destroyed = true;
        stopTicker();
        handler.removeCallbacksAndMessages(null);
        callback = null;
    }

    // =================================================================
    //  计时
    // =================================================================

    private void refreshTimer() {
        if (tvTimer == null) return;
        final CallSession s = CallManager.getCurrentCall();
        tvTimer.setText(formatSec(s == null ? 0 : s.activeElapsedSec()));
    }

    private static String formatSec(int sec) {
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private void ensureTicker() {
        if (ticker != null) return;      // 已在跑
        ticker = () -> {
            if (destroyed) return;
            if (CallManager.getCurrentCall() == null) {
                // 通话已收尾：停表，不再自我排队（收尾清理由 CallOverlayController 负责）
                ticker = null;
                return;
            }
            refreshTimer();
            handler.postDelayed(ticker, TICK_MS);
        };
        handler.postDelayed(ticker, TICK_MS);
    }

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    // =================================================================
    //  工具
    // =================================================================

    /**
     * 把服务端相对头像路径拼成带 token 的绝对 URL；无头像/失败返回 null。
     *
     * <p>与 {@code CallFloatingWindow.avatarUrl}（CallFloatingWindow.java:437-449）逐行同款，
     * 两处保持同一套拼接规则；本类不引用那个类是为了不把"系统浮窗"依赖引进 View 层。
     */
    private String avatarUrl(String avatar) {
        if (avatar == null || avatar.isEmpty()) return null;
        try {
            if (!avatar.startsWith("/")) return avatar;   // 已经是绝对 URL
            final PreferencesManager prefs = PreferencesManager.getInstance(getContext());
            return ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                    prefs.getToken());
        } catch (Exception e) {
            AppLog.e(TAG, "avatarUrl failed", e);
            return null;
        }
    }
}
