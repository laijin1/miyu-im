package com.familychat.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_chat.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.call.AudioRouter;
import com.familychat.app.api.call.CallEngine;
import com.familychat.app.api.call.CallFloatingWindow;
import com.familychat.app.api.call.CallManager;
import com.familychat.app.api.call.CallSession;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.webrtc.SurfaceViewRenderer;

/**
 * Full WebRTC voice/video call activity.
 *
 * Modes:
 *   - Outgoing call (isIncoming=false): creates offer immediately
 *   - Incoming call (isIncoming=true): shows accept/reject, creates answer on accept
 *
 * Handles:
 *   - Local/remote video rendering via SurfaceViewRenderer
 *   - Audio routing (speaker vs earpiece)
 *   - Camera switching
 *   - Mute/unmute
 *   - Call duration timer
 *   - Clean hangup + resource release
 */
public class CallActivity extends AppCompatActivity
        implements CallManager.Observer, CallEngine.Observer, AudioRouter.Observer {

    private static final String TAG = "CallActivity";
    private static final int PERM_CALL_REQUEST = 200;

    // ---- 通话状态：全部来自 :core 的 CallManager（Step 1 / C5）----
    // 这里曾经有 4 个静态字段（isActive / lastIncomingOfferAt / lastIncomingFrom /
    // shouldHandleIncoming），用于在 MainActivity 与 ChatActivity 两条来电路径之间
    // 靠"发起人 id + 4 秒时间窗"抢名额。它们已被整体删除：
    //   1) 4 秒窗口会把同一人 4 秒内的重拨静默吞掉；
    //   2) isActive 在 onPause 就置 false，通话页被暂停时第二通电话照样能起；
    //   3) "谁先注册谁生效"让来电 UI 落到不可见的那一侧（在聊天框里来电、退出才弹）。
    // 去重、占线、超时、以及"谁弹 UI"现在都由 CallManager 按 callId 决定。

    /** 本页这通电话的 id。来电由 CallManager 经 intent 传入；外呼由 startCall() 返回。 */
    private String callId;

    private PreferencesManager prefs;
    private ApiClient api;
    /**
     * 通话媒体引擎（{@code :feature-chat} 的 {@link CallEngine}，进程级静态门面）。
     *
     * <p>媒体所有权已从本页移出：CallEngine 持有 {@code WebRTCManager}、信令监听器与
     * PushConnection 注册，因此通话能活过本 Activity。本页的职责收敛为
     * "注入渲染目标 + 渲染 + 收尾"。
     */
    private Models.User remoteUser;

    /** 本端主动挂断标记：区分"本端挂断"与"对端挂断"，决定是否提示"通话已结束"。 */
    private boolean hangupInitiated;

    /** 是否已最小化到应用内浮层（onDestroy 时据此跳过 cleanup）。 */
    private boolean minimized;

    // Config
    private boolean isVideoEnabled;
    private boolean isIncoming;
    private boolean autoAccept;  // skip incoming overlay (user already accepted via dialog)
    private String offerSdp;    // incoming offer (callee only)
    private int fromUserId;
    private boolean callConnected = false;
    private boolean isCleaningUp = false;
    private boolean callEstablished = false;
    private boolean initFailed = false; // WebRTC init failed, UI still shown

    // UI
    private FrameLayout remoteVideoContainer;
    private FrameLayout localVideoContainer;
    private SurfaceViewRenderer remoteRenderer;
    private SurfaceViewRenderer localRenderer;
    private android.view.TextureView localPreviewView;   // relay local-camera frame anchor (TextureView)
    private android.widget.ImageView localPreviewFrame;  // relay local preview 实际显示层（centerCrop 填满窗口）
    private android.widget.ImageView remoteVideoView;     // relay remote-video frames
    private ImageView remoteResizeHandle;                 // 小窗口右下角缩放把手（远程）
    private ImageView localResizeHandle;                  // 小窗口右下角缩放把手（本地）
    private LinearLayout audioOnlyLayout;
    private View incomingOverlay;
    private ImageView ivCallAvatar;
    private TextView tvCallName, tvCallStatus, tvCallDuration;
    private ImageButton btnMute, btnSwitchCamera, btnCallSpeaker, btnHangup;
    private ImageButton btnCallMinimize;
    private ImageButton btnCallVideoToggle;
    private ImageButton btnAccept, btnReject;

    // ---- 大小窗口交换 / 拖动 / 缩放（密语视频通话风格）----
    private boolean isSwapped = false;   // false = 远程大/本地小；true = 本地大/远程小
    private float density;
    private int screenW, screenH;
    private int smallX, smallY, smallW, smallH; // 小窗口当前矩形（px）
    private int minSmallW, minSmallH, maxSmallW, maxSmallH;
    private boolean windowReady = false; // 通话建立、窗口已就位后可交互
    private GestureDetector smallWindowGesture, resizeGesture;
    private ValueAnimator swapAnim;
    private float dragLastX, dragLastY;     // 拖动上一帧手指位置（增量跟踪，紧密跟随）
    private float resizeLastX, resizeLastY; // 缩放上一帧手指位置（增量跟踪，紧密跟随）

    // Connection mode detection
    private boolean isLanMode = true;

    // Audio
    // ⚠️ AudioManager 已移出本页：路由（含 MODE_IN_COMMUNICATION 的进入/恢复、扬声器/听筒/
    //    蓝牙切换）统一由 CallEngine → AudioRouter 承担（Step C）。
    private boolean isMuted = false;
    private boolean isSpeakerOn = true;

    // Timer
    private Handler timerHandler = new Handler();
    private long callStartTime;
    private Runnable timerRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏/灭屏来电可见性（批 2 / a）：manifest 的 showWhenLocked / turnScreenOn 是
        // API 27 才有的属性，minSdk 24 需要 window flags 兜底。放在 setContentView 之前：
        // flags 在窗口 attach 之前设置，第一次 attach 就带上，避免"先不可见再闪一下"。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {   // < 27
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                  | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        try {
            setContentView(R.layout.activity_call);
        } catch (Exception e) {
            // Layout inflation failed — likely a missing class or resource
            Toast.makeText(this, "布局加载失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Layout inflation failed", e);
            finish();
            return;
        }

        try {
            // ---- Init ----
            prefs = PreferencesManager.getInstance(this);
            // Redirect to login if no valid token
            if (prefs.getToken() == null) {
                Intent loginIntent = new Intent()
                        .setClassName(this, com.familychat.app.utils.Routes.LOGIN_ACTIVITY);
                loginIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(loginIntent);
                finish();
                return;
            }
            api = new ApiClient(prefs.getServer(), prefs.getToken());
            // 401 自动登出由 FamilyChatApp 全局注册且只注册一次；任何 Activity 都不再
            // 注册或清除它（否则会覆盖全局监听器，导致其它页面 401 不再自动登出）。
            // AudioManager 获取已随音频路由一起移到 AudioRouter（Step C）。

            // ---- Get intent data ----
            String friendJson = getIntent().getStringExtra("friend");
            isVideoEnabled = getIntent().getBooleanExtra("video", false);
            isIncoming = getIntent().getBooleanExtra("incoming", false);
            autoAccept = getIntent().getBooleanExtra("auto_accept", false);
            fromUserId = getIntent().getIntExtra("from_user_id", 0);
            offerSdp = getIntent().getStringExtra("offer_sdp");
            // 来电：callId 由 CallManager.launchIncomingUi() 放进 intent；
            // 外呼：本页 onCreate 末尾调 CallManager.startCall() 生成（见下方 flow 分支）。
            callId = getIntent().getStringExtra("call_id");

            // auto_reject（批 2 / b）：通知上的"拒接"按钮一步到位。
            // 必须在这里、且早于下面的 adopt / auto_accept 分支：
            //   · adopt 分支要求会话是 ACTIVE，而我们要拒的会话还在 INCOMING_RINGING；
            //   · 早于 CallEngine.acceptIncoming（auto_accept 分支）→ 不会为一个即将被拒的
            //     通话建媒体引擎，省掉一次起停。
            // 信令与"已拒绝"系统消息仍只由 CallManager.rejectCall() 写（唯一落点，见 CallManager
            // 的归属不变量注释）；本页不自己发 call_reject、不自己写系统消息，故不会双报。
            if (getIntent().getBooleanExtra("auto_reject", false)) {
                AppLog.d(TAG, "flow: auto_reject callId=" + callId + " from=" + fromUserId);
                if (callId != null) {
                    CallManager.rejectCall(callId);
                } else {
                    AppLog.e(TAG, "auto_reject: callId 为空，无法通知 CallManager（对端将等超时）");
                }
                finish();
                return;
            }

            if (friendJson != null) {
                remoteUser = new com.google.gson.Gson().fromJson(friendJson, Models.User.class);
            } else {
                remoteUser = new Models.User();
                remoteUser.id = fromUserId;
                remoteUser.nickname = getIntent().getStringExtra("friend_name");
            }

            // ---- Step 0 观察点：进入通话页时的入参快照 + 实例标识 ----
            // instance 用于识别"多实例叠加"（同一时刻应有且只有一个 CallActivity）。
            AppLog.d(TAG, "onCreate instance=" + System.identityHashCode(this)
                    + " incoming=" + isIncoming + " autoAccept=" + autoAccept
                    + " video=" + isVideoEnabled + " from=" + fromUserId
                    + " callId=" + callId
                    + " offer=" + sdpSummary(offerSdp));

            // ---- Bind views ----
            remoteVideoContainer = findViewById(R.id.remoteVideoContainer);
            localVideoContainer = findViewById(R.id.localVideoContainer);
            audioOnlyLayout = findViewById(R.id.audioOnlyLayout);
            localPreviewView = findViewById(R.id.localPreviewView);
            localPreviewFrame = findViewById(R.id.localPreviewFrame);
            remoteVideoView = findViewById(R.id.remoteVideoView);
            incomingOverlay = findViewById(R.id.incomingOverlay);
            ivCallAvatar = findViewById(R.id.ivCallAvatar);
            tvCallName = findViewById(R.id.tvCallName);
            tvCallStatus = findViewById(R.id.tvCallStatus);
            tvCallDuration = findViewById(R.id.tvCallDuration);
            btnMute = findViewById(R.id.btnCallMute);
            btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
            btnCallMinimize = findViewById(R.id.btnCallMinimize);
            btnCallVideoToggle = findViewById(R.id.btnCallVideoToggle);
            btnCallSpeaker = findViewById(R.id.btnCallSpeaker);
            btnHangup = findViewById(R.id.btnCallHangup);
            btnAccept = findViewById(R.id.btnCallAccept);
            btnReject = findViewById(R.id.btnCallReject);
            remoteResizeHandle = findViewById(R.id.remoteResizeHandle);
            localResizeHandle = findViewById(R.id.localResizeHandle);

            // ---- Create SurfaceViewRenderers programmatically ----
            remoteRenderer = new SurfaceViewRenderer(this);
            remoteRenderer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            remoteVideoContainer.addView(remoteRenderer);

            localRenderer = new SurfaceViewRenderer(this);
            localRenderer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            localVideoContainer.addView(localRenderer);

            // ---- Detect connection mode (LAN = WebRTC P2P, WAN = relay via Tunnel) ----
            String serverHost = prefs.getServer().split(":")[0];
            isLanMode = isLanAddress(serverHost);

            // ---- Set user info ----
        String name = remoteUser != null ? remoteUser.nickname : "联系人";
        tvCallName.setText(name);
        loadAvatar(ivCallAvatar, remoteUser != null ? remoteUser.avatar : null, 120);
        // 兜底：进来的数据没头像/账号名时，从服务器拉一次好友资料补全（后台线程）
        if (remoteUser != null && (remoteUser.avatar == null || remoteUser.avatar.isEmpty())) {
            final int targetId = remoteUser.id > 0 ? remoteUser.id : fromUserId;
            if (targetId > 0) {
                new Thread(() -> {
                    try {
                        Models.FriendsResponse fres = api.getFriends();
                        if (fres != null && fres.friends != null) {
                            for (Models.User f : fres.friends) {
                                if (f.id == targetId) {
                                    final Models.User fresh = f;
                                    runOnUiThread(() -> {
                                        remoteUser.avatar = fresh.avatar;
                                        if (fresh.nickname != null && !fresh.nickname.isEmpty()) {
                                            tvCallName.setText(fresh.nickname);
                                        }
                                        loadAvatar(ivCallAvatar, fresh.avatar, 120);
                                    });
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e("CallActivity", "fetch friend avatar", e);
                    }
                }).start();
            }
        }

        // ---- Check permissions (CAMERA for video, RECORD_AUDIO for all) ----
        if (!checkCallPermissions()) {
            AppLog.d(TAG, "permissions missing, requesting (audio+video=" + isVideoEnabled + ")");
            requestCallPermissions();
            return;
        }
        AppLog.d(TAG, "permissions ok (audio+video=" + isVideoEnabled + ")");

        // ---- 注入渲染目标 + 订阅引擎事件 ----
        // ⚠️ 顺序固定（先注入 View，后 startOutgoing/acceptIncoming）：
        //   engine == null 时 CallEngine 只缓存这四个 View，等 start 时用来构造 WebRTCManager；
        //   engine != null（Activity 重建）时重绑 renderer 并 re-arm relay 本地预览。
        //   若顺序颠倒，WebRTCManager 会拿到 null renderer（构造失败）。
        CallEngine.setRendererViews(localRenderer, remoteRenderer,
                localPreviewView, localPreviewFrame);
        CallEngine.setRelayRemoteView(remoteVideoView);
        CallEngine.addObserver(this);
        // 音频路由观察者：路由变化时刷新 btnCallSpeaker 的图标
        AudioRouter.addObserver(this);
        AppLog.d(TAG, "renderer views injected + engine/audio observer registered");

        // 信令监听器（含媒体帧转发）已随引擎所有权下沉到 CallEngine.SignalingListener，
        // 并仿 CallManager 在 install() 时注册一次、刻意不反注册 —— 这样本页销毁后
        // 媒体信令仍然到达，通话页才可能"活过 Activity"。

        // ---- Setup buttons ----
        setupButtons(name);

        // ---- 密语式大小窗口：初始化手势与初始布局 ----
        // ⚠️ 必须早于下面的 Handle call flow：adopt 分支会同步调用 onCallEstablished()，
        //    而视频分支里的 setupVideoWindows() 依赖本方法赋值的 screenW/screenH/smallX/…
        //    （新实例里这些字段初值为 0，晚调会导致视频窗口 0×0）。
        initCallWindowSystem();

        // ---- Handle call flow ----
        // E1（U1 修复）：先认领"已接通的同一通通话"。
        // 通知 / 悬浮窗点击回到通话页时，CallManager 里已经有一通 ACTIVE 会话：
        // 此时既不能再 startCall（会撞占线），也不该显示"来电"浮层。
        // ⚠️ 必须放在 if (isIncoming) 分派<b>之前</b>：被叫场景的 extras 带的是
        //    incoming == true，若只在 else（外呼）分支里判断，该分支永远不可达
        //    —— Step D 的 D6 就是因此空转。
        CallSession existing = CallManager.getCurrentCall();
        final boolean adoptable = existing != null
                && existing.state == CallSession.State.ACTIVE
                && ((!existing.incoming && existing.calleeId == remoteUser.id)
                    || (existing.incoming && existing.callerId == remoteUser.id));

        if (adoptable) {
            callId = existing.callId;
            AppLog.d(TAG, "flow: adopt active session callId=" + callId
                    + " incoming=" + existing.incoming
                    + " engineAlive=" + CallEngine.hasEngine());
            if (CallEngine.hasEngine()) {
                // 引擎还在（通话一直活着）：媒体无需重启，只把 UI 摆到"通话中"
                onCallEstablished();
            } else {
                // 会话是 ACTIVE 但引擎没了 —— 无法恢复媒体，只能收尾这通"孤儿会话"
                AppLog.e(TAG, "adopt: engine missing while ACTIVE — ending stale session");
                CallManager.endCall(existing.callId);
                Toast.makeText(this, "通话已中断，请重新拨打", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else if (isIncoming) {
            if (autoAccept) {
                // 已经接听过（用于 Step 2 的独立响铃页）：跳过浮层直接接通。
                // ⚠️ 目前没有生产者 —— MainActivity 的来电对话框已在 C4 删除，
                //    CallManager.launchIncomingUi() 只传 incoming=true（保留响铃浮层由用户点"接听"）。
                AppLog.d(TAG, "flow: incoming autoAccept -> accept relay + answer");
                incomingOverlay.setVisibility(View.GONE);
                tvCallStatus.setText("连接中...");
                if (!CallEngine.acceptIncoming(fromUserId,
                        offerSdp != null ? offerSdp : "", isVideoEnabled)) {
                    // 与改造前 WebRTCManager 构造失败一致：UI 留在通话页，不 finish
                    initFailed = true;
                    tvCallStatus.setText("初始化失败，请挂断重试");
                }
                onCallEstablished();
                // call_answer 由 CallManager.acceptCall() 发出（信令唯一出口）
                if (callId != null) CallManager.acceptCall(callId);
            } else {
                // Show incoming overlay with accept/reject buttons
                AppLog.d(TAG, "flow: incoming manual -> show incomingOverlay");
                incomingOverlay.setVisibility(View.VISIBLE);
                tvCallStatus.setText("来电...");
                if (isVideoEnabled) tvCallStatus.append(" (视频)");
                if (offerSdp != null && offerSdp.equals("relay")) {
                    tvCallStatus.setText("来电... (中继)");
                }
            }
        } else {
            // 外呼：信令（call_offer）由 CallManager.startCall 负责；
            // 媒体（中继采集/播放）仍由本页启动 —— 它需要上面已接线的 relay 视图。
            // 认领"已存在的通话"已整体前移到 if (isIncoming) 分派之前（E1），
            // 这里只剩"真正的发起新呼叫"。
            callId = CallManager.startCall(prefs.getUserId(), remoteUser.id, isVideoEnabled);
            if (callId == null) {
                AppLog.e(TAG, "flow: outgoing rejected (CallManager busy) -> finish");
                Toast.makeText(this, "正在通话中", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            AppLog.d(TAG, "flow: outgoing callId=" + callId + " video=" + isVideoEnabled);
            tvCallStatus.setText("呼叫中...");
            // Always use relay mode (avoids WebRTC native library crashes)
            if (!CallEngine.startOutgoing(remoteUser.id, isVideoEnabled)) {
                // 与改造前 WebRTCManager 构造失败一致：UI 留在通话页，不 finish
                initFailed = true;
                tvCallStatus.setText("初始化失败，请挂断重试");
            }
        }

        // 扬声器/MODE_IN_COMMUNICATION 已由 CallEngine.startOutgoing/acceptIncoming →
        // AudioRouter.init 承担（Step C），本页不再设置音频。

        // 订阅 CallManager 事件（必须在最后：此前启动的流程若已同步结束，不应再回调本页）。
        CallManager.addObserver(this);
        AppLog.d(TAG, "observer registered instance=" + System.identityHashCode(this)
                + " callId=" + callId);

        // ---- 悬浮窗权限引导（所有通话都要用悬浮窗，故不区分音视频） ----
        // 用户跳过 = 不显示悬浮窗：CallEngine.showFloatingWindow → CallFloatingWindow.show
        // 内部会再检查 canDrawOverlays 并返回 false，不会崩。
        maybeRequestOverlayPermission();
        } catch (Exception e) {
            Log.e(TAG, "CallActivity init failed", e);
            finish();
        }
    }

    // ──────────────────────────────────────────────
    //  Permissions
    // ──────────────────────────────────────────────

    private boolean checkCallPermissions() {
        boolean audioOk = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!isVideoEnabled) return audioOk;
        boolean cameraOk = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        return audioOk && cameraOk;
    }

    private void requestCallPermissions() {
        String[] perms;
        if (isVideoEnabled) {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
            };
        } else {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO
            };
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(perms, PERM_CALL_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CALL_REQUEST) {
            boolean allGranted = true;
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                // Step 0 观察点：授权后 recreate() 会销毁旧实例（旧 WebRTCManager 释放相机）
                // 并新建实例重新抢相机 —— 这是"被叫首次授权后摄像头打不开"的候选路径。
                AppLog.d(TAG, "permissions granted -> recreate() instance="
                        + System.identityHashCode(this));
                recreate(); // restart with permissions
            } else {
                AppLog.e(TAG, "permissions denied -> finish()");
                Toast.makeText(this, "需要麦克风权限才能通话", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /** Check if a host is a local LAN address (P2P WebRTC works) vs public (need relay). */
    private static boolean isLanAddress(String host) {
        if (host == null || host.isEmpty()) return true;
        return host.equals("localhost") || host.equals("127.0.0.1")
            || host.startsWith("192.168.") || host.startsWith("10.")
            || host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")
            || host.endsWith(".local") || host.endsWith(".lan");
    }

    // ──────────────────────────────────────────────
    //  Button setup
    // ──────────────────────────────────────────────

    private void setupButtons(String name) {
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            CallEngine.toggleMute();
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setColorFilter(isMuted ? 0xFFF15A24 : 0xFFFFFFFF);
        });

        btnSwitchCamera.setOnClickListener(v -> {
            if (isVideoEnabled) CallEngine.switchCamera();
        });

        if (!isVideoEnabled) {
            btnSwitchCamera.setVisibility(View.GONE);
        }

        // 关闭/打开摄像头：图标语义 = "点下去会发生什么"。
        // ⚠️ 时序（关键）：关 → 先改 UI 再调引擎；开 → **先让 localVideoContainer VISIBLE**，
        //    让 TextureView 的 SurfaceTexture 先重建，再调引擎重开相机 attach。
        //    顺序颠倒会导致 attachOrStartPreview 找不到可用 surface → 重开后本地预览不亮。
        btnCallVideoToggle.setOnClickListener(v -> {
            final boolean nowOn = CallEngine.isVideoSendEnabled();
            if (nowOn) {
                // 关：先改 UI，再调引擎
                localVideoContainer.setVisibility(View.GONE);
                btnCallVideoToggle.setImageResource(R.drawable.ic_video);
                CallEngine.setVideoSendEnabled(false);
            } else {
                // 开：先设 VISIBLE（SurfaceTexture 重建），再让引擎重开相机
                localVideoContainer.setVisibility(View.VISIBLE);
                btnCallVideoToggle.setImageResource(R.drawable.ic_video_off);
                CallEngine.setVideoSendEnabled(true);
            }
        });

        // 语音通话没有摄像头可控：与 btnSwitchCamera 同款隐藏。
        if (!isVideoEnabled) {
            btnCallVideoToggle.setVisibility(View.GONE);
        }

        // 音频设备选择：弹底部面板，图标随当前路由变（AudioRouter.Observer.onRouteChanged）
        btnCallSpeaker.setOnClickListener(v -> new AudioRouteDialog(this).show());
        updateSpeakerIcon();

        btnHangup.setOnClickListener(v -> hangup());

        // 左上角"缩小"：与返回键同一个入口（最小化到应用内浮层，通话继续）
        if (btnCallMinimize != null) {
            btnCallMinimize.setOnClickListener(v -> minimizeToOverlay());
        }

        // Incoming call buttons
        btnAccept.setOnClickListener(v -> {
            // Accept incoming call — always use relay mode
            AppLog.d(TAG, "user tapped 接听 (manual) from=" + fromUserId
                    + " video=" + isVideoEnabled + " callId=" + callId);
            incomingOverlay.setVisibility(View.GONE);
            tvCallStatus.setText("通话中");
            // 先起媒体、再通知状态机：call_answer 由 CallManager.acceptCall() 发出，
            // 放在最后是为了让对端收到 answer 时本端中继通路已经就绪（与改造前的先后一致）。
            if (!CallEngine.acceptIncoming(fromUserId,
                    offerSdp != null ? offerSdp : "", isVideoEnabled)) {
                initFailed = true;
                tvCallStatus.setText("初始化失败，请挂断重试");
            }
            onCallEstablished();
            if (callId != null) {
                CallManager.acceptCall(callId);   // → ACTIVE + 发 call_answer + 取消 45s 响铃超时
            } else {
                AppLog.e(TAG, "btnAccept: callId 为空，无法通知 CallManager（对端将等超时）");
            }
        });

        btnReject.setOnClickListener(v -> {
            AppLog.d(TAG, "user tapped 拒绝 from=" + fromUserId + " callId=" + callId);
            // 拒接：信令（call_reject）与"已拒绝"系统消息统一由
            // CallManager.rejectCall() 负责 —— 本页不再自己发 call_hangup、也不再自己写系统消息，
            // 否则 CallManager 里那条也会发，聊天里就会出现两条"已拒绝"。
            if (callId != null) {
                CallManager.rejectCall(callId);
            } else {
                AppLog.e(TAG, "btnReject: callId 为空，无法通知 CallManager（对端将等超时）");
            }
            finish();
        });
    }

    // ──────────────────────────────────────────────
    //  Call lifecycle
    // ──────────────────────────────────────────────

    private void onCallEstablished() {
        if (callEstablished) {
            AppLog.d(TAG, "onCallEstablished skipped (already established)"
                    + " instance=" + System.identityHashCode(this));
            return;
        }
        callEstablished = true;
        callConnected = true;
        AppLog.d(TAG, "STATE -> ESTABLISHED instance=" + System.identityHashCode(this)
                + " incoming=" + isIncoming + " video=" + isVideoEnabled
                + " relay=" + CallEngine.isRelayMode());

        // Now that both sides have agreed, allow media to stream.
        CallEngine.setStreamingEnabled(true);
        // MODE_IN_COMMUNICATION 由 AudioRouter 在通话开始时设置并保持（Step C 搬走）。

        tvCallStatus.setText("通话中");
        incomingOverlay.setVisibility(View.GONE);
        // Cancel any existing timer before starting a new one
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        // Start duration timer
        // 用会话的真实接通时刻起算，避免 adopt（通知/悬浮窗回到通话页）后
        // 计时从 00:00 重来；activeAt 尚未置位（被叫接听路径在 acceptCall
        // 之前就调到这里）时退回当前时间。
        final CallSession timingSession = CallManager.getCurrentCall();
        callStartTime = (timingSession != null && timingSession.activeAt > 0)
                ? timingSession.activeAt
                : System.currentTimeMillis();
        tvCallDuration.setVisibility(View.VISIBLE);
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - callStartTime;
                int secs = (int) (elapsed / 1000);
                int mins = secs / 60;
                secs = secs % 60;
                tvCallDuration.setText(String.format("%02d:%02d", mins, secs));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);

        // Video: show local preview, hide audio-only layout
        if (isVideoEnabled) {
            localVideoContainer.setVisibility(View.VISIBLE);
            audioOnlyLayout.setVisibility(View.GONE);
            // 密语式大小窗口：远程大窗口全屏，本地小窗口右下角
            setupVideoWindows();
            // Relay mode: the WebRTC renderers are released in favour of the legacy-camera
            // preview (ImageView) and the remote-frame ImageView.
            if (CallEngine.isRelayMode()) {
                remoteRenderer.setVisibility(View.GONE);
                localRenderer.setVisibility(View.GONE);
                if (localPreviewView != null) localPreviewView.setVisibility(View.VISIBLE);
                if (localPreviewFrame != null) localPreviewFrame.setVisibility(View.VISIBLE);
            }
        } else {
            localRenderer.setVisibility(View.GONE);
            remoteRenderer.setVisibility(View.GONE);
        }

        // ---- 用引擎状态初始化 UI（Activity 重建 / adopt 场景的关键点）----
        // 媒体所有权在 CallEngine（进程级），它持有 WebRTCManager 跨 Activity 存活，
        // 因此"摄像头关着 / 对端关着"这个状态可能是在本页创建<b>之前</b>就发生的：
        // 上面的 video 分支只看 isVideoEnabled，会把窗口摆成"视频中"，与引擎真实状态不符。
        // 这里按引擎状态纠正一次（幂等；状态正常时是 no-op）。
        if (isVideoEnabled) {
            if (!CallEngine.isVideoSendEnabled()) {
                localVideoContainer.setVisibility(View.GONE);
                if (btnCallVideoToggle != null) {
                    btnCallVideoToggle.setImageResource(R.drawable.ic_video);
                }
            }
            if (!CallEngine.isRemoteVideoEnabled()) {
                remoteVideoContainer.setVisibility(View.GONE);
                audioOnlyLayout.setVisibility(View.VISIBLE);
                // audioOnlyLayout 在 XML 中声明于 localVideoContainer 之后，会盖住本地小窗；
                // 本地还开着时把它提到最前，保留自己的画面。
                if (localVideoContainer.getVisibility() == View.VISIBLE) {
                    localVideoContainer.bringToFront();
                }
            }
        }
    }

    /**
     * 向服务器写入通话状态系统消息的能力已整体下移到 {@code CallManager}
     * （{@code reportCallStatus} 是 private，全仓唯一落点）。
     *
     * <p>本页曾经有两个调用点：{@code btnReject} 的 "rejected" 与 {@code hangup()} /
     * {@code onCallHangup()} 的 "ended"。C5 全部删除并交给 CallManager ——
     * 因为 CallManager 也会为同样的原因上报一次，两处都留就是聊天里的**两条系统消息**。
     * 本页的职责收敛为"渲染 + 媒体 + 清理"。
     */

    private void hangup() {
        AppLog.d(TAG, "user hangup instance=" + System.identityHashCode(this)
                + " incoming=" + isIncoming + " established=" + callEstablished
                + " callId=" + callId
                + " remote=" + (remoteUser != null ? remoteUser.id : -1));
        // 标记本端主动挂断：onEngineEnded 据此决定是否提示"通话已结束"
        // （与改造前 WebRTCManager.Listener.onCallEnded 只在本端 end() 时触发一致）。
        hangupInitiated = true;
        // 本端挂断：信令（call_hangup / call_cancel / call_reject，按当前状态自动选）
        // 与"通话时长 MM:SS"系统消息，统一由 CallManager.endCall() 负责 —— 唯一落点，防双报。
        if (callId != null) {
            CallManager.endCall(callId);
        } else {
            AppLog.e(TAG, "hangup: callId 为空，无法通知 CallManager（对端将等超时）");
        }
        // 中继媒体停止仍由本页发（属媒体通道，不是通话信令）
        if (remoteUser != null && CallEngine.isRelayMode()) {
            PushConnection.sendCallMediaStop(remoteUser.id);
        }
        // Small delay to let the signal be sent before releasing resources
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            runOnUiThread(() -> {
                cleanup();
                finish();
            });
        }).start();
    }

    private void cleanup() {
        if (isCleaningUp) {
            AppLog.d(TAG, "cleanup skipped (already cleaning) instance="
                    + System.identityHashCode(this));
            return;
        }
        isCleaningUp = true;
        AppLog.d(TAG, "cleanup instance=" + System.identityHashCode(this)
                + " established=" + callEstablished);

        if (timerHandler != null) {
            if (timerRunnable != null) {
                timerHandler.removeCallbacks(timerRunnable);
                timerRunnable = null;
            }
            timerHandler = null;
        }
        // 释放媒体引擎（含 relay 采集/播放与 relay 相机）。
        // 信令监听器现由 CallEngine 持有且刻意不反注册（与 CallManager 同款）——
        // 本页不再 removeListener：那正是"通话活不过 Activity"的旧根因。
        CallEngine.end();
        // 音频模式恢复（setMode(MODE_NORMAL) / 释放通信设备）已由 CallEngine.end → AudioRouter.release 承担。
    }

    // ──────────────────────────────────────────────
    //  密语式大小窗口：交换 / 拖动 / 缩放
    // ──────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * density); }

    private FrameLayout getSmallWindow() {
        return isSwapped ? remoteVideoContainer : localVideoContainer;
    }
    private FrameLayout getBigWindow() {
        return isSwapped ? localVideoContainer : remoteVideoContainer;
    }

    private void initCallWindowSystem() {
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;

        minSmallW = dp(80);
        minSmallH = dp(120);
        maxSmallW = (int) (screenW * 0.4f);
        maxSmallH = maxSmallW * 4 / 3;

        // 初始小窗口：右下角，避开底部按钮
        smallW = dp(120);
        smallH = dp(160);
        smallX = screenW - smallW - dp(16);
        smallY = screenH - smallH - dp(150);

        // 小窗口拖动 + 点击交换。
        // onScroll 的 dx/dy 是"从按下起的累计位移"，不能直接累加（会越拖越快跑偏）；
        // 这里记录上一帧手指位置，每帧算增量，窗口严格贴着手指走。
        smallWindowGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                dragLastX = e.getRawX();
                dragLastY = e.getRawY();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!windowReady) return false;
                int dx = (int) (e2.getRawX() - dragLastX);
                int dy = (int) (e2.getRawY() - dragLastY);
                dragLastX = e2.getRawX();
                dragLastY = e2.getRawY();
                moveSmallWindow(dx, dy);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (windowReady) animateSwap();
                return true;
            }
        });

        // 小窗口右下角缩放：同样用增量，缩放把手（右下角）贴着手指走
        resizeGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                resizeLastX = e.getRawX();
                resizeLastY = e.getRawY();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!windowReady) return false;
                int dx = (int) (e2.getRawX() - resizeLastX);
                int dy = (int) (e2.getRawY() - resizeLastY);
                resizeLastX = e2.getRawX();
                resizeLastY = e2.getRawY();
                resizeSmallWindow(dx, dy);
                return true;
            }
        });

        View.OnTouchListener smallWindowTouch = (v, e) -> {
            if (v != getSmallWindow()) return false;
            return smallWindowGesture.onTouchEvent(e);
        };
        remoteVideoContainer.setOnTouchListener(smallWindowTouch);
        localVideoContainer.setOnTouchListener(smallWindowTouch);

        // 缩放把手：只响应“本容器是小窗口”时
        remoteResizeHandle.setOnTouchListener((v, e) -> {
            if (getSmallWindow() != remoteVideoContainer) return false;
            return resizeGesture.onTouchEvent(e);
        });
        localResizeHandle.setOnTouchListener((v, e) -> {
            if (getSmallWindow() != localVideoContainer) return false;
            return resizeGesture.onTouchEvent(e);
        });
    }

    /** 视频通话建立后调用：远程大窗口全屏，本地小窗口右下角。 */
    private void setupVideoWindows() {
        windowReady = true;
        isSwapped = false;
        applyFrame(remoteVideoContainer, 0, 0, screenW, screenH);
        applyFrame(localVideoContainer, smallX, smallY, smallW, smallH);
        updateWindowBorders();
    }

    private void applyFrame(FrameLayout v, int l, int t, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = l;
        lp.topMargin = t;
        v.setLayoutParams(lp);
    }

    private void moveSmallWindow(int dx, int dy) {
        smallX += dx;
        smallY += dy;
        smallX = Math.max(0, Math.min(smallX, screenW - smallW));
        smallY = Math.max(0, Math.min(smallY, screenH - smallH));
        applyFrame(getSmallWindow(), smallX, smallY, smallW, smallH);
    }

    /** 右下角锚定缩放：左上角不动，等比例 3:4，最小 80x120dp，最大 40% 屏宽。 */
    private void resizeSmallWindow(int dx, int dy) {
        int w = smallW + dx;
        int h = w * 4 / 3;
        if (w < minSmallW) { w = minSmallW; h = w * 4 / 3; }
        if (h < minSmallH) { h = minSmallH; w = h * 3 / 4; }
        if (w > maxSmallW) { w = maxSmallW; h = w * 4 / 3; }
        if (h > maxSmallH) { h = maxSmallH; w = h * 3 / 4; }
        int maxW = screenW - smallX;
        int maxH = screenH - smallY;
        if (w > maxW) { w = maxW; h = w * 4 / 3; }
        if (h > maxH) { h = maxH; w = h * 3 / 4; }
        smallW = w;
        smallH = h;
        applyFrame(getSmallWindow(), smallX, smallY, smallW, smallH);
    }

    /** 点击小窗口：300ms 缩放动画交换大小窗口，可无限循环。 */
    private void animateSwap() {
        if (swapAnim != null && swapAnim.isRunning()) return;
        final FrameLayout small = getSmallWindow();
        final FrameLayout big = getBigWindow();
        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) small.getLayoutParams();
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) big.getLayoutParams();
        final int sL = slp.leftMargin, sT = slp.topMargin, sW0 = slp.width, sH0 = slp.height;
        final int bL = blp.leftMargin, bT = blp.topMargin, bW0 = blp.width, bH0 = blp.height;

        swapAnim = ValueAnimator.ofFloat(0f, 1f);
        swapAnim.setDuration(300);
        swapAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            applyFrame(small,
                    (int) (sL + (bL - sL) * t), (int) (sT + (bT - sT) * t),
                    (int) (sW0 + (bW0 - sW0) * t), (int) (sH0 + (bH0 - sH0) * t));
            applyFrame(big,
                    (int) (bL + (sL - bL) * t), (int) (bT + (sT - bT) * t),
                    (int) (bW0 + (sW0 - bW0) * t), (int) (bH0 + (sH0 - bH0) * t));
        });
        swapAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                isSwapped = !isSwapped;
                applyFrame(small, bL, bT, bW0, bH0);
                applyFrame(big, sL, sT, sW0, sH0);
                updateWindowBorders();
            }
        });
        swapAnim.start();
    }

    /** 小窗口白色圆角边框 + 置顶 elevation；大窗口无边框。 */
    private void updateWindowBorders() {
        FrameLayout small = getSmallWindow();
        FrameLayout big = getBigWindow();
        small.setBackgroundResource(R.drawable.bg_call_small_window);
        small.setElevation(dp(12));
        small.setPadding(dp(2), dp(2), dp(2), dp(2));
        big.setBackgroundResource(0);
        big.setElevation(0);
        big.setPadding(0, 0, 0, 0);
        // 缩放把手只在小窗口显示
        remoteResizeHandle.setVisibility(isSwapped ? View.VISIBLE : View.GONE);
        localResizeHandle.setVisibility(isSwapped ? View.GONE : View.VISIBLE);
    }

    // ──────────────────────────────────────────────
    //  Overrides
    // ──────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // 返回键 = 最小化到应用内浮层（通话继续），不再挂断。
        // 挂断只剩两个入口：底部红色挂断按钮、应用内浮层/系统浮窗的挂断按钮。
        minimizeToOverlay();
    }

    /**
     * 最小化到应用内浮层：本页退场，通话由进程级 CallEngine 继续，画面交给宿主 Activity 的浮层。
     *
     * <p>关键点：
     * <ul>
     *   <li>{@code minimized = true} —— onDestroy 据此<b>跳过 cleanup()</b>，否则
     *       {@code cleanup() → CallEngine.end()} 会把通话和媒体一起收掉；</li>
     *   <li>本页是 task root 时（从通知/系统浮窗拉起、且 App 任务已不存在），
     *       {@code finish()} 会把任务清空并掉到桌面 —— 因此显式拉 MainActivity。
     *       flags 与 {@code NotificationDispatcher} 的消息通知同款
     *       （NEW_TASK | CLEAR_TOP | SINGLE_TOP）。</li>
     * </ul>
     */
    private void minimizeToOverlay() {
        if (!CallEngine.hasEngine()) {
            // 没有通话：走普通返回
            super.onBackPressed();
            return;
        }
        AppLog.d(TAG, "minimizeToOverlay: instance=" + System.identityHashCode(this)
                + " taskRoot=" + isTaskRoot());
        minimized = true;
        // 若 CallActivity 是 task root（从通知/系统浮窗拉起），
        // finish() 会掉到桌面 → 显式拉 MainActivity
        if (isTaskRoot()) {
            try {
                Intent it = new Intent();
                it.setClassName(this, "com.familychat.app.ui.MainActivity");
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
            } catch (Exception e) {
                AppLog.e(TAG, "minimizeToOverlay: startActivity failed", e);
            }
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 占线判定已完全交给 CallManager（Step 1 / C5）：本页不再有 isActive 静态字段。
        AppLog.d(TAG, "onResume instance=" + System.identityHashCode(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.d(TAG, "onPause instance=" + System.identityHashCode(this)
                + " finishing=" + isFinishing());
    }

    /**
     * 通话页退到后台（Home / 切到别的 App / 被最小化）→ 交给 CallOverlayController 判定
     * 是否需要系统级悬浮窗。通话继续。
     *
     * <p>本页<b>不做任何释放</b>：媒体所有权已在 CallEngine（Step A），
     * 因此退到后台不等于结束通话。
     *
     * <p>这里不再直接 {@code showFloatingWindow} —— 改成 200ms 延迟判定（见
     * {@code CallOverlayController.onHostStopped}）：
     * 宿主 App 内切页时应用内浮层已接管，不需要系统浮窗；真的切到别的 App 才显示。
     */
    @Override
    protected void onStop() {
        super.onStop();
        AppLog.d(TAG, "onStop instance=" + System.identityHashCode(this)
                + " finishing=" + isFinishing() + " engineAlive=" + CallEngine.hasEngine());
        // 本页不可见了：清掉"通话页可见"标志，并让控制器做延迟判定
        com.familychat.app.api.call.CallOverlayController.setCallPageVisible(false);
        if (CallEngine.hasEngine()) {
            com.familychat.app.api.call.CallOverlayController.onHostStopped(this);
        }
    }

    /**
     * 回到前台 → 隐藏悬浮窗（通话页本身就是全屏通话 UI，不需要两套同时存在）。
     *
     * <p>注意与 {@link #onResume()} 的分工：{@code onStart} 只管"可见性"，
     * {@code onResume} 只管"占线判定已下沉"的日志。
     */
    @Override
    protected void onStart() {
        super.onStart();
        AppLog.d(TAG, "onStart instance=" + System.identityHashCode(this)
                + " engineAlive=" + CallEngine.hasEngine());
        // 通话页可见：抑制系统浮窗的延迟判定（否则"宿主 → 通话页"会误弹浮窗）
        com.familychat.app.api.call.CallOverlayController.setCallPageVisible(true);
        CallEngine.hideFloatingWindow();
        // 本页真的可见了 → 撤掉"来电通知"（批 2 / c）。
        // 走 CallManager 转发而不是直接 import IncomingCallNotifier：后者在 :app，
        // :feature-chat 看不见它（依赖方向不可逆）。callId 不匹配时对端是 no-op，
        // 因此外呼 / 接通后重入本页时调用同样安全。
        CallManager.notifyCallUiShown(callId);
    }

    /**
     * {@code launchMode=singleTop}（批 2 / a）后，已在栈顶的本页会被复用而不是新建实例，
     * 再次拉起的 Intent 从 {@code onCreate} 改走这里。通话页被通知 / 悬浮窗反复拉起
     * 是常态（每次 ticker 刷新都可能被点），所以必须能安全重入。
     *
     * <h3>为什么只做"替换 Intent + 撤通知 + 拒接"，不重跑 onCreate 的 flow</h3>
     * {@code onCreate} 里的 flow 分支是<b>有副作用</b>的：{@code auto_accept} 会
     * {@code CallEngine.acceptIncoming} + {@code CallManager.acceptCall}，
     * 外呼分支会 {@code CallManager.startCall}。在已有实例上重跑这些的后果是
     * "同一通电话被接听两次 / 撞占线 / 重复发信令"，而正确行为恰恰是<b>什么都不做</b>
     * —— 通话状态机（{@code CallManager.current}）已经在维护这一通的状态，页面只是视图。
     *
     * <p>唯一需要在这里处理的 intent 是 {@code auto_reject}：用户可能在浮层已打开时
     * 又去点通知上的"拒接"。此时本页还停在 {@code incomingOverlay}，需要真的拒掉。
     * {@code CallManager.rejectCall} 自带状态校验（非 INCOMING_RINGING 即忽略），
     * 所以重复调用无副作用。
     *
     * <p>{@code adopt} 分支（会话已 ACTIVE → 直接摆到"通话中"）属于 onCreate 的初始化职责，
     * 这里不需要：本页若已存在且会话是 ACTIVE，说明本页本来就是被 adopt 或已接通的实例，
     * 不需要重新 adopt。若本页已 destroyed（不是 singleTop 命中的情形），系统会走 onCreate。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent instance=" + System.identityHashCode(this)
                + " myCallId=" + callId + " engineAlive=" + CallEngine.hasEngine());

        CallManager.notifyCallUiShown(callId);

        if (intent == null) return;
        final boolean reject = intent.getBooleanExtra("auto_reject", false);
        final String newCallId = intent.getStringExtra("call_id");
        if (reject) {
            // 只拒"当前这通"：intent 里的 callId 若与本页不一致，说明是上一通的迟到通知。
            if (callId != null && callId.equals(newCallId)) {
                AppLog.d(TAG, "onNewIntent: auto_reject callId=" + callId);
                CallManager.rejectCall(callId);
                finish();
            } else {
                AppLog.d(TAG, "onNewIntent: auto_reject ignored (callId mismatch) new=" + newCallId);
            }
        }
    }

    @Override
    protected void onDestroy() {
        AppLog.d(TAG, "onDestroy instance=" + System.identityHashCode(this)
                + " established=" + callEstablished + " callId=" + callId);
        // 先退订：cleanup() 之后本页不该再收到 CallManager / CallEngine / AudioRouter 的事件
        CallManager.removeObserver(this);
        CallEngine.removeObserver(this);
        AudioRouter.removeObserver(this);
        if (!minimized) {
            cleanup();
        } else {
            AppLog.d(TAG, "onDestroy: minimized — skip cleanup (call survives)");
        }
        super.onDestroy();
    }

    // ──────────────────────────────────────────────
    //  CallEngine.Observer
    // ──────────────────────────────────────────────

    /** 媒体通路就绪（relay 对端应答 / WebRTC 连接建立）→ 走原 onCallEstablished 流程。 */
    @Override
    public void onEngineReady() {
        AppLog.d(TAG, "onEngineReady instance=" + System.identityHashCode(this)
                + " established=" + callEstablished + " finishing=" + isFinishing());
        if (isFinishing()) return;
        tvCallStatus.setText("通话中 (中继)");
        onCallEstablished();
    }

    /**
     * 引擎收尾（本端 end() / 对端挂断 / WebRTC 报连接结束）→ 关闭通话页。
     *
     * <p>只在本端主动挂断时提示"通话已结束"：改造前该提示来自
     * {@code WebRTCManager.Listener.onCallEnded}，它只在 {@code endCall()} 被调用时触发
     * （即本端挂断，以及 P2P 的 ICE 断开）；对端挂断走 {@code wsListener.onCallHangup}，
     * 当时是静默 {@code finish()}。这里用 {@link #hangupInitiated} 保留同样的语义，
     * 避免与 CallManager 的原因提示（"对方已拒绝"等）叠加成两条 Toast。
     */
    @Override
    public void onEngineEnded() {
        AppLog.d(TAG, "onEngineEnded instance=" + System.identityHashCode(this)
                + " finishing=" + isFinishing() + " localHangup=" + hangupInitiated);
        if (isFinishing()) return;
        if (hangupInitiated) {
            Toast.makeText(this, "通话已结束", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /**
     * 对端关/开摄像头（{@code call_video_state}）→ 用对端头像占位层替换远端的（已定格）画面。
     *
     * <p>为什么必须由信令驱动而不是"无帧判断"：对端停发帧后，本端 {@code remoteVideoView}
     * 会保持在最后一帧（WebRTCManager 的 relay 路径从不清空它），画面只是定格，不会变黑，
     * 所以只能靠这条状态信令切 UI。
     *
     * <p>本方法由 {@code CallEngine} 经 {@code MAIN.post} 调用，已在主线程。
     */
    @Override
    public void onRemoteVideoStateChanged(boolean enabled) {
        AppLog.d(TAG, "onRemoteVideoStateChanged enabled=" + enabled
                + " isVideoEnabled=" + isVideoEnabled + " finishing=" + isFinishing());
        if (isFinishing()) return;
        // 防御：语音通话（isVideoEnabled == false）根本没有视频 UI，本页只有头像占位层。
        // 此时若照 enabled=true 去显示 remoteVideoContainer / 隐藏 audioOnlyLayout，
        // 会把头像与名字换成一片空白。对端（或旧版客户端）误发这条信令时直接忽略。
        if (!isVideoEnabled) {
            AppLog.d(TAG, "onRemoteVideoStateChanged ignored (voice call has no video UI)");
            return;
        }
        if (enabled) {
            remoteVideoContainer.setVisibility(View.VISIBLE);
            audioOnlyLayout.setVisibility(View.GONE);
        } else {
            remoteVideoContainer.setVisibility(View.GONE);
            audioOnlyLayout.setVisibility(View.VISIBLE);
            // audioOnlyLayout 在 XML 中声明于 localVideoContainer 之后，会盖住本地小窗；
            // 本地还开着时把它提到最前，保留自己的画面。
            if (localVideoContainer.getVisibility() == View.VISIBLE) {
                localVideoContainer.bringToFront();
            }
        }
    }

    /**
     * 本地视频起不来（前后摄都打不开）→ 只提示，<b>绝不 finish()</b>。
     * 通话与音频都正常，只是因为拿不到摄像头而没有画面。
     */
    @Override
    public void onVideoStartFailed() {
        AppLog.e(TAG, "onVideoStartFailed instance=" + System.identityHashCode(this)
                + " finishing=" + isFinishing());
        if (isFinishing()) return;
        Toast.makeText(this, "摄像头打开失败", Toast.LENGTH_SHORT).show();
    }

    /** 引擎错误（与改造前 WebRTCManager.Listener.onError 一致：提示后关闭通话页）。 */
    @Override
    public void onError(String error) {
        AppLog.e(TAG, "onError instance=" + System.identityHashCode(this) + " msg=" + error);
        if (isFinishing()) return;
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ──────────────────────────────────────────────
    //  AudioRouter.Observer
    // ──────────────────────────────────────────────

    /** 路由变化（含蓝牙 SCO 接通/断开）→ 刷新音频设备按钮的图标。 */
    @Override
    public void onRouteChanged(AudioRouter.Route route) {
        AppLog.d(TAG, "onRouteChanged -> " + route + " finishing=" + isFinishing());
        if (isFinishing()) return;
        updateSpeakerIcon();
    }

    /** 把当前路由映射到按钮图标（扬声器图标是从 :app 复制过来的位图）。 */
    private void updateSpeakerIcon() {
        if (btnCallSpeaker == null) return;
        final AudioRouter.Route route = AudioRouter.getCurrentRoute();
        final int icon;
        switch (route) {
            case EARPIECE:
                icon = R.drawable.ic_earpiece;
                break;
            case BLUETOOTH:
                icon = R.drawable.ic_bluetooth;
                break;
            case WIRED_HEADSET:
                icon = R.drawable.ic_headset;
                break;
            case SPEAKER:
            default:
                icon = R.drawable.ic_speaker;
                break;
        }
        btnCallSpeaker.setImageResource(icon);
        btnCallSpeaker.setColorFilter(0xFFFFFFFF);
    }

    // ──────────────────────────────────────────────
    //  悬浮窗权限引导
    // ──────────────────────────────────────────────

    /**
     * 悬浮窗权限引导。
     *
     * <p>{@code SYSTEM_ALERT_WINDOW} 是<b>特殊权限</b>，不在运行时请求列表里，也无法用
     * {@code requestPermissions} 申请 —— 只能跳到 {@code ACTION_MANAGE_OVERLAY_PERMISSION}
     * 让用户手动开。
     *
     * <p>用户点"跳过"或从设置页返回时未授权都不做任何补救：{@code CallFloatingWindow.show}
     * 每次显示前都会再判一次 {@code canDrawOverlays}，未授权就返回 false（悬浮窗不显示，
     * 通话本身不受影响）。因此本页<b>不需要</b> {@code onActivityResult} 复查。
     */
    private void maybeRequestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            AppLog.d(TAG, "overlay permission already granted");
            return;
        }
        AppLog.d(TAG, "overlay permission missing -> show guidance dialog");
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("悬浮窗权限")
                    .setMessage("通话缩到后台时需要悬浮窗才能继续。请在设置里授权。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            AppLog.e(TAG, "open overlay settings failed", e);
                        }
                    })
                    .setNegativeButton("跳过", null)
                    .show();
        } catch (Exception e) {
            AppLog.e(TAG, "overlay guidance dialog failed", e);
        }
    }

    // ──────────────────────────────────────────────
    //  CallManager.Observer
    // ──────────────────────────────────────────────

    /**
     * 通话被状态机收尾（对方挂断 / 占线 / 拒接 / 取消 / 各种超时 / 媒体未建立）。
     *
     * <p>这是"来电浮层永远不会自己关掉"的修复点之一：改造前来电 Dialog 挂在 MainActivity 上、
     * 引用被丢弃，对端取消后无人关闭它。现在收尾事件有明确接收者。
     */
    @Override
    public void onCallEnded(String endedCallId, CallManager.EndReason reason) {
        AppLog.d(TAG, "onCallEnded callId=" + endedCallId + " reason=" + reason
                + " instance=" + System.identityHashCode(this) + " myCallId=" + callId);
        final String reasonText = endReasonText(reason);
        runOnUiThread(() -> {
            // 不是本页这通电话的收尾事件：忽略（多实例/迟到事件防御）
            if (callId != null && !callId.equals(endedCallId)) {
                AppLog.d(TAG, "onCallEnded ignored (not my call)");
                return;
            }
            if (incomingOverlay != null) incomingOverlay.setVisibility(View.GONE);
            if (reasonText != null && !isFinishing()) {
                Toast.makeText(CallActivity.this, reasonText, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }

    @Override
    public void onStateChanged(CallSession session) {
        // 先留空：本页的通话页 UI 目前由自身流程驱动（onCallEstablished / hangup），
        // 只有"收尾"需要状态机通知（见 onCallEnded）。将来把响铃页拆出去时再补。
    }

    /** 收尾原因 → 用户可读提示。null 表示不需要提示（对方/本端正常挂断）。 */
    private static String endReasonText(CallManager.EndReason reason) {
        switch (reason) {
            case BUSY:           return "对方正在通话中";
            case REJECTED:       return "对方已拒绝";
            case CANCELLED:      return "对方已取消";
            case TIMEOUT_RING:   return "无人接听";
            case TIMEOUT_ACCEPT: return "无人接听";
            case MEDIA_FAIL:     return "通话连接失败";
            case HANGUP:         return null;   // 正常挂断不提示（与改造前一致）
            case LOCAL_END:      return null;   // 本端主动挂断自己清楚
            default:             return null;
        }
    }

    // ──────────────────────────────────────────────
    //  Utility
    // ──────────────────────────────────────────────

    /**
     * Step 0 专用：把 offerSdp 压成一行可读摘要。
     * relay 模式下它是字面量 "relay"；P2P 模式下是一大段 SDP，只记长度避免日志被刷爆。
     */
    private static String sdpSummary(String sdp) {
        if (sdp == null) return "null";
        if (sdp.length() <= 32) return sdp;
        return "<sdp len=" + sdp.length() + ">";
    }

    private void loadAvatar(ImageView iv, String avatar, int size) {
        try {
            if (avatar != null && avatar.startsWith("/")) {
                Glide.with(this)
                        .load(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                                prefs.getToken()))
                        .circleCrop()
                        .into(iv);
            } else {
                iv.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        } catch (Exception e) {
            iv.setImageResource(R.drawable.ic_avatar_placeholder);
        }
    }
}
