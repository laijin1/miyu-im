package com.familychat.app.api;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.familychat.app.api.Models.User;
import com.familychat.app.utils.AppLog;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * Full WebRTC manager for LAN peer-to-peer audio/video calls.
 *
 * Flow:
 *   Caller:  createOffer() -> send offer via WebSocket -> wait for answer
 *   Callee:  receive offer -> createAnswer() -> send answer via WebSocket
 *   Both:    exchange ICE candidates via WebSocket
 *
 * Server relays all signaling messages (offer/answer/ICE) via WebSocket.
 */
public class WebRTCManager {

    public static final String TAG = "WebRTCManager";

    private final Context appContext;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ---- PeerConnectionFactory ----
    private PeerConnectionFactory factory;
    private EglBase eglBase;

    // ---- PeerConnection ----
    @Nullable
    private PeerConnection peerConnection;
    private boolean isInitiator;

    // ---- Media ----
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;

    // ---- Rendering ----
    // 非 final：CallEngine 跨 Activity 存活时，Activity 重建会用新的 View 调 setRenderers() 重绑。
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;

    // ---- State ----
    private boolean isVideoEnabled;
    private boolean isMuted;
    private boolean isSpeakerOn = true;
    private boolean isFrontCamera = true;
    private boolean isEnding = false;

    // ---- Step 0 观察点：只用于日志的一次性标记（首帧 / 首个音频块），不影响任何行为 ----
    private volatile boolean relayFirstFrameLogged = false;
    private volatile boolean relayFirstRemoteFrameLogged = false;
    private volatile boolean relayFirstMediaChunkLogged = false;

    // ---- Remote party ----
    private int remoteUserId;
    /** 对端视频开关状态（true=对端摄像头开着）。由 call_video_state 信令更新，供本端 UI 查询。 */
    private volatile boolean remoteVideoEnabled = true;

    // ---- Callbacks ----
    private Listener listener;
    private SignalingSender signalingSender;

    // ──────────────────────────────────────────────
    //  Interfaces
    // ──────────────────────────────────────────────

    public interface Listener {
        void onCallConnected();
        void onRemoteVideoStarted();
        void onCallEnded();
        void onError(String error);

        /**
         * 对端视频开关状态变化（对端关了/开了摄像头）。
         *
         * <p>与 {@link #onRemoteVideoStarted()} 不同：那个是 P2P 的"收到远端视频轨"，
         * relay 模式不产生；本回调由 {@code call_video_state} 信令驱动，两种模式都可到达。
         * 设为 default 以免破坏既有实现者。
         */
        default void onRemoteVideoStateChanged(boolean enabled) {}

        /**
         * 视频无法开始出帧：前后摄都打不开（首次通话建立时，或"开摄像头"重开时）。
         *
         * <p>刻意<b>不复用 {@link #onError}</b>：{@code onError} 在 CallActivity 里会
         * {@code finish()} 掉整个通话页（见 CallActivity.onError），而这里只是视频起不来，
         * 通话与音频都正常，绝不能因此挂断。
         */
        default void onVideoStartFailed() {}
    }

    public interface SignalingSender {
        void send(String type, int targetUserId, String data);
    }

    // ──────────────────────────────────────────────
    //  Static initializer (call once in Application)
    // ──────────────────────────────────────────────

    public static void initializeGlobal(Context ctx) {
        PeerConnectionFactory.InitializationOptions opts =
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(opts);
    }

    // ──────────────────────────────────────────────
    //  Constructor
    // ──────────────────────────────────────────────

    public WebRTCManager(Context context,
                         SurfaceViewRenderer localRenderer,
                         SurfaceViewRenderer remoteRenderer,
                         boolean videoEnabled) {
        this.appContext = context.getApplicationContext();
        this.localRenderer = localRenderer;
        this.remoteRenderer = remoteRenderer;
        this.isVideoEnabled = videoEnabled;

        // WebRTC init may fail on some devices (native library issues).
        // Relay mode works without it, so we catch and continue.
        try {
            initFactoryAndEgl();
            initLocalMedia();
            AppLog.d(TAG, "ctor ok: factory=" + (factory != null)
                    + " egl=" + (eglBase != null)
                    + " videoCapturer=" + (videoCapturer != null)
                    + " audioSource=" + (audioSource != null));
        } catch (Exception e) {
            Log.w(TAG, "WebRTC init failed, relay mode will still work: " + e.getMessage());
            AppLog.e(TAG, "ctor failed, relay mode continues: " + e.getMessage(), e);
            // factory, audioSource etc remain null — only relay calls will work
        }
    }

    // ──────────────────────────────────────────────
    //  Factory + EGL
    // ──────────────────────────────────────────────

    private void initFactoryAndEgl() {
        eglBase = EglBase.create();

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(),
                true,   // enableIntelVp8Encoder
                true    // enableH264HighProfile
        );
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        // Remote renderer — shows the other person
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remoteRenderer.setEnableHardwareScaler(true);
        remoteRenderer.setMirror(false);

        // Local renderer — shows self preview (mirrored)
        localRenderer.init(eglBase.getEglBaseContext(), null);
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        localRenderer.setEnableHardwareScaler(true);
        localRenderer.setMirror(true);
    }

    // ──────────────────────────────────────────────
    //  Local Media (camera + mic)
    // ──────────────────────────────────────────────

    private void initLocalMedia() {
        if (factory == null) {
            Log.w(TAG, "initLocalMedia: factory is null, skipping");
            return;
        }
        // ---- Audio ----
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "googNoiseSuppression", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
        audioTrack.setEnabled(true);

        // ---- Video (if enabled) ----
        if (isVideoEnabled) {
            videoCapturer = createCameraCapturer(true);
            if (videoCapturer != null) {
                SurfaceTextureHelper helper = SurfaceTextureHelper.create(
                        "CaptureThread", eglBase.getEglBaseContext());
                videoSource = factory.createVideoSource(videoCapturer.isScreencast());
                videoCapturer.initialize(helper, appContext, videoSource.getCapturerObserver());

                localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
                localVideoTrack.setEnabled(true);
                localVideoTrack.addSink(localRenderer);
                localRenderer.setVisibility(View.VISIBLE);

                // Step 0 观察点：WebRTC 相机在构造阶段就抢占了设备，
                // 中继模式随后必须把它释放掉才能用 Camera1 打开（交接竞态源头）。
                videoCapturer.startCapture(1280, 720, 30);
                AppLog.d(TAG, "initLocalMedia: WebRTC camera startCapture(1280x720@30) requested");
            } else {
                Log.w(TAG, "No camera available, falling back to audio-only");
                AppLog.e(TAG, "initLocalMedia: no WebRTC camera, falling back to audio-only");
                isVideoEnabled = false;
            }
        }
    }

    @Nullable
    private VideoCapturer createCameraCapturer(boolean front) {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(appContext)) {
            enumerator = new Camera2Enumerator(appContext);
        } else {
            enumerator = new Camera1Enumerator(true);
        }

        String[] deviceNames = enumerator.getDeviceNames();
        // First try the requested facing direction
        for (String name : deviceNames) {
            boolean isFront = enumerator.isFrontFacing(name);
            boolean isBack = enumerator.isBackFacing(name);
            if ((front && isFront) || (!front && isBack)) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        // Fallback: first available
        for (String name : deviceNames) {
            VideoCapturer c = enumerator.createCapturer(name, null);
            if (c != null) return c;
        }
        return null;
    }

    // ──────────────────────────────────────────────
    //  PeerConnection
    // ──────────────────────────────────────────────

    private PeerConnection createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // STUN for LAN: not strictly needed (host candidates work directly),
        // but some Android WebRTC builds require at least one server entry.
        iceServers.add(PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.keyType = PeerConnection.KeyType.ECDSA;
        config.iceCandidatePoolSize = 0;

        MediaConstraints pcConstraints = new MediaConstraints();
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
                "DtlsSrtpKeyAgreement", "true"));

        return factory.createPeerConnection(config, pcConstraints,
                new PeerConnectionObserver());
    }

    // ──────────────────────────────────────────────
    //  Call Flow — Caller
    // ──────────────────────────────────────────────

    /**
     * Caller: initiate a call to remoteUserId.
     *
     * <p><b>当前生产不可达</b>：CallActivity 只走 relay 模式（startRelayCall / acceptRelayCall），
     * 而 relay 模式不建 PeerConnection，因此本方法全仓无调用点。保留以备回到 P2P 路径。
     */
    public void startCall(int userId, boolean videoEnabled) {
        this.remoteUserId = userId;
        this.isVideoEnabled = videoEnabled;
        this.isInitiator = true;

        peerConnection = createPeerConnection();
        if (peerConnection == null) {
            notifyError("创建通话失败");
            return;
        }

        // Add our local media as a stream
        org.webrtc.MediaStream stream = factory.createLocalMediaStream("ARDAMS");
        stream.addTrack(audioTrack);
        if (localVideoTrack != null) {
            stream.addTrack(localVideoTrack);
        }
        peerConnection.addStream(stream);

        // Create SDP offer
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", isVideoEnabled ? "true" : "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (signalingSender != null) {
                            signalingSender.send("call_offer",
                                    remoteUserId, sdp.description);
                        }
                    }

                    @Override
                    public void onSetFailure(String error) {
                        notifyError("设置本地SDP失败: " + error);
                    }
                }, sdp);
            }

            @Override
            public void onCreateFailure(String error) {
                notifyError("创建Offer失败: " + error);
            }
        }, constraints);
    }

    // ──────────────────────────────────────────────
    //  Call Flow — Callee
    // ──────────────────────────────────────────────

    /**
     * Callee: accept an incoming call by creating an answer.
     *
     * <p><b>当前生产不可达</b>：同 {@link #startCall(int, boolean)} —— relay 模式不建 PeerConnection，
     * 本方法全仓无调用点。
     */
    public void acceptCall(int fromUserId, String offerSdp, boolean videoEnabled) {
        this.remoteUserId = fromUserId;
        this.isVideoEnabled = videoEnabled;
        this.isInitiator = false;

        peerConnection = createPeerConnection();
        if (peerConnection == null) {
            notifyError("接受通话失败");
            return;
        }

        MediaStream stream = factory.createLocalMediaStream("ARDAMS");
        stream.addTrack(audioTrack);
        if (localVideoTrack != null) {
            stream.addTrack(localVideoTrack);
        }
        peerConnection.addStream(stream);

        // Set remote offer
        SessionDescription remoteSdp = new SessionDescription(
                SessionDescription.Type.OFFER, offerSdp);
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                MediaConstraints constraints = new MediaConstraints();
                constraints.mandatory.add(new MediaConstraints.KeyValuePair(
                        "OfferToReceiveAudio", "true"));
                constraints.mandatory.add(new MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo",
                        isVideoEnabled ? "true" : "false"));

                peerConnection.createAnswer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SimpleSdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                if (signalingSender != null) {
                                    signalingSender.send("call_answer",
                                            remoteUserId, sdp.description);
                                }
                            }

                            @Override
                            public void onSetFailure(String error) {
                                notifyError("设置本地SDP失败: " + error);
                            }
                        }, sdp);
                    }

                    @Override
                    public void onCreateFailure(String error) {
                        notifyError("创建Answer失败: " + error);
                    }
                }, constraints);
            }

            @Override
            public void onSetFailure(String error) {
                notifyError("设置远端SDP失败: " + error);
            }
        }, remoteSdp);
    }

    // ──────────────────────────────────────────────
    //  Signaling receiver
    // ──────────────────────────────────────────────

    /**
     * Feed signaling messages from WebSocket into WebRTC.
     *
     * <p><b>当前生产不可达</b>（对 P2P 生效的分支）：relay 模式下 {@code peerConnection == null}，
     * 调用方（CallEngine 的信令监听）进来后会在开头的 null 检查处 {@code Log.w} 返回。
     */
    public void onRemoteSignaling(String type, String data) {
        if (peerConnection == null) {
            Log.w(TAG, "No peer connection for signaling: " + type);
            return;
        }

        switch (type) {
            case "call_answer": {
                // Caller receives answer
                if (isInitiator) {
                    SessionDescription answer = new SessionDescription(
                            SessionDescription.Type.ANSWER, data);
                    peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Remote answer set — connection established");
                        }

                        @Override
                        public void onSetFailure(String error) {
                            notifyError("设置Answer失败: " + error);
                        }
                    }, answer);
                }
                break;
            }
            case "call_ice": {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(data);
                    IceCandidate candidate = new IceCandidate(
                            json.getString("sdpMid"),
                            json.getInt("sdpMLineIndex"),
                            json.getString("candidate"));
                    peerConnection.addIceCandidate(candidate);
                    Log.d(TAG, "Added ICE candidate: " + candidate.sdpMid);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse ICE candidate", e);
                }
                break;
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Controls
    // ──────────────────────────────────────────────

    public void toggleMute() {
        isMuted = !isMuted;
        AppLog.d(TAG, "toggleMute: caller=UI(btnCallMute) newValue=" + isMuted
                + " thread=" + Thread.currentThread().getName());
        if (isRelayMode) {
            setRelayMuted(isMuted);
        } else if (audioTrack != null) {
            audioTrack.setEnabled(!isMuted);
        }
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        // AudioManager routing handled at the Activity level
    }

    public boolean isSpeakerOn() {
        return isSpeakerOn;
    }

    public void switchCamera() {
        if (isRelayMode && relayCamera != null) {
            switchRelayCamera();
            return;
        }
        if (videoCapturer != null) {
            isFrontCamera = !isFrontCamera;
            if (videoCapturer instanceof org.webrtc.CameraVideoCapturer) {
                try {
                    ((org.webrtc.CameraVideoCapturer) videoCapturer).switchCamera(null);
                } catch (Exception e) {
                    Log.w(TAG, "Camera switch failed", e);
                }
            }
        }
    }

    /**
     * 中继模式切摄像头：后台线程停旧相机 → 按新朝向重开 → 回 UI 线程挂载预览。
     * 开/关相机是慢操作，放后台线程避免点按钮时界面卡一下。
     */
    private void switchRelayCamera() {
        new Thread(() -> {
            try {
                AppLog.d(TAG, "switchRelayCamera: from front=" + relayCameraIsFront);
                relayCameraConfigured = false;
                relayVideoRunning = false;
                try { relayCamera.stopPreview(); } catch (Exception ignored) {}
                try { relayCamera.release(); } catch (Exception ignored) {}
                relayCamera = null;

                boolean front = !relayCameraIsFront;
                startRelayCamera(front);
                if (relayCamera == null) {
                    // 新朝向打不开（如旧机型无前摄）：退回原朝向
                    Log.w(TAG, "Relay switch to front=" + front + " failed, reverting");
                    startRelayCamera(!front);
                }
                if (relayCamera != null) {
                    if (!isRelayMode) {
                        // 切换中途对方挂断了：把刚打开的相机释放掉，别漏
                        try { relayCamera.stopPreview(); } catch (Exception ignored) {}
                        try { relayCamera.release(); } catch (Exception ignored) {}
                        relayCamera = null;
                        relayCameraConfigured = false;
                        return;
                    }
                    uiHandler.post(() -> {
                        if (relayCameraConfigured) attachOrStartPreview();
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Relay switch camera failed", e);
            }
        }, "RelaySwitchCamera").start();
    }

    public void endCall() {
        if (isEnding) return;
        isEnding = true;
        AppLog.d(TAG, "endCall: relayMode=" + isRelayMode + " camera=" + (relayCamera != null));

        // Defensive: release relay-mode resources (Camera / AudioRecord / AudioTrack) too.
        // Normally CallActivity.cleanup() calls both endCall() and stopRelayCall(), but making
        // endCall() self-contained guarantees no future call path leaks relay resources.
        // stopRelayCall() is idempotent, so double-release is safe.
        stopRelayCall();

        // Stop capturer
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping camera capture", e);
            }
            try {
                videoCapturer.dispose();
            } catch (Exception e) {
                Log.w(TAG, "Error disposing camera", e);
            }
            videoCapturer = null;
        }

        // Close peer connection
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        // Dispose sources
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        localVideoTrack = null;
        audioTrack = null;

        // Release renderers
        try {
            if (localRenderer != null) localRenderer.release();
            if (remoteRenderer != null) remoteRenderer.release();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing renderers", e);
        }

        // Dispose factory + EGL
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }

        if (listener != null) {
            listener.onCallEnded();
        }
    }

    public boolean isVideoEnabled() {
        return isVideoEnabled;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSignalingSender(SignalingSender sender) {
        this.signalingSender = sender;
    }

    public int getRemoteUserId() {
        return remoteUserId;
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private void notifyError(String message) {
        Log.e(TAG, message);
        uiHandler.post(() -> {
            if (listener != null) listener.onError(message);
        });
    }

    // ──────────────────────────────────────────────
    //  PeerConnection.Observer
    // ──────────────────────────────────────────────

    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
            Log.d(TAG, "Signaling state: " + state);
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            try {
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("sdpMid", candidate.sdpMid);
                json.put("sdpMLineIndex", candidate.sdpMLineIndex);
                json.put("candidate", candidate.sdp);
                if (signalingSender != null) {
                    signalingSender.send("call_ice", remoteUserId, json.toString());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to serialize ICE", e);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            // Stream WebRTC handles removal internally
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d(TAG, "ICE state: " + state);
            uiHandler.post(() -> {
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    if (listener != null) { try { listener.onCallConnected(); } catch (Exception ignored) {} }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED
                        || state == PeerConnection.IceConnectionState.FAILED
                        || state == PeerConnection.IceConnectionState.CLOSED) {
                    if (listener != null) listener.onCallEnded();
                }
            });
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {}

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

        @Override
        public void onAddStream(MediaStream stream) {
            if (stream.videoTracks != null && !stream.videoTracks.isEmpty()) {
                VideoTrack remoteTrack = stream.videoTracks.get(0);
                remoteTrack.addSink(remoteRenderer);
                remoteRenderer.setVisibility(View.VISIBLE);
                uiHandler.post(() -> {
                    if (listener != null) listener.onRemoteVideoStarted();
                    Log.d(TAG, "Remote video track received");
                });
            }
        }

        @Override
        public void onRemoveStream(MediaStream stream) {}

        @Override
        public void onRenegotiationNeeded() {}

        @Override
        public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] streams) {}

        @Override
        public void onDataChannel(org.webrtc.DataChannel channel) {}
    }

    // ──────────────────────────────────────────────
    //  Simplified SdpObserver base
    // ──────────────────────────────────────────────

    private static abstract class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onSetSuccess() {}
        @Override public void onSetFailure(String error) {}
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onCreateFailure(String error) {}
    }

    // ──────────────────────────────────────────────
    //  Relay Mode (WAN via Cloudflare Tunnel)
    //  Uses AudioRecord + AudioTrack for WebSocket-based audio relay
    // ──────────────────────────────────────────────

    private static final int RELAY_SAMPLE_RATE = 8000;        // 8kHz (widely supported)
    private static final int RELAY_BUFFER_MS = 100;            // 100ms per chunk
    private static final int RELAY_BUFFER_SIZE = RELAY_SAMPLE_RATE * 2 * RELAY_BUFFER_MS / 1000; // 1600 bytes

    private boolean isRelayMode = false;
    private android.media.AudioRecord relayRecorder = null;
    private android.media.AudioTrack relayPlayer = null;
    private Thread relayCaptureThread = null;
    private boolean isRelayCapturing = false;
    private volatile boolean relayMuted = false;
    /** 本地静音对方：纯本地拦截 relay 播放，不发信令。 */
    private volatile boolean relayLocalMuted = false;
    // Streams are only pushed once the call is established (both sides agreed),
    // so we don't pump the callee's mic into a ringing caller or vice-versa.
    private boolean relayStreamingEnabled = false;
    private int relayRemoteUserId = 0;
    private final Object relayAudioLock = new Object();
    private java.util.LinkedList<byte[]> relayPlaybackQueue = new java.util.LinkedList<>();

    // ---- Relay video UI surfaces (wired by CallActivity) ----
    // 相机帧锚点：TextureView 持有真实 SurfaceTexture，保证相机回调稳定出帧。
    // 实际显示用 ImageView（relayLocalPreviewFrame）：解码 JPEG 后 centerCrop 填满窗口，原比例不变形。
    private android.view.TextureView relayLocalPreviewView;
    private android.widget.ImageView relayLocalPreviewFrame;
    private android.widget.ImageView relayRemoteView;        // remote video frames
    private android.graphics.Bitmap lastRemoteBitmap;        // previous remote frame (recycled on replace)
    private android.graphics.Bitmap lastLocalPreviewBitmap;  // previous local preview frame (recycled)
    private final Object videoRenderLock = new Object();
    private boolean videoFrameBusy = false;                  // latest-wins: drop frames while busy

    // ---- 后台线程：把音频播放和视频解码移出主线程，避免主线程被 AudioTrack.write /
    //      BitmapFactory 阻塞导致画面卡顿/延迟（开着麦克风就"延长"的根因）。 ----
    private HandlerThread videoDecodeThread;
    private Handler videoDecodeHandler;
    private HandlerThread audioPlaybackThread;
    private Handler audioPlaybackHandler;

    // 发送端限帧：最多 ~20fps，防止 WS 队列无限堆积（慢网下画面越拖越久）
    private long lastVideoSendAt = 0;
    private static final long VIDEO_SEND_INTERVAL_MS = 50;
    private int localPreviewFrameSkip = 0;                   // 本地预览隔帧刷新（约 15fps，足够顺）
    private volatile boolean localPreviewDecodeBusy = false; // 本地预览 latest-wins：解码忙则丢帧，防止队列堆积

    /** Start a relay call (outgoing) — capture microphone and stream via WebSocket. */
    public void startRelayCall(int userId, boolean videoEnabled) {
        AppLog.d(TAG, "startRelayCall to=" + userId + " video=" + videoEnabled);
        this.relayRemoteUserId = userId;
        this.isRelayMode = true;
        this.isVideoEnabled = videoEnabled;
        resetRelayLogFlags();
        initRelayAudio();
        if (videoEnabled) startRelayVideo();
    }

    /** Accept a relay call (incoming) — start capturing and playing. */
    public void acceptRelayCall(int fromUserId, String offerSdp, boolean videoEnabled) {
        AppLog.d(TAG, "acceptRelayCall from=" + fromUserId + " video=" + videoEnabled);
        this.relayRemoteUserId = fromUserId;
        this.isRelayMode = true;
        this.isVideoEnabled = videoEnabled;
        resetRelayLogFlags();
        initRelayAudio();
        if (videoEnabled) startRelayVideo();
    }

    /** Step 0 专用：每次通话开始时重置"首帧"日志标记。 */
    private void resetRelayLogFlags() {
        relayFirstFrameLogged = false;
        relayFirstRemoteFrameLogged = false;
        relayFirstMediaChunkLogged = false;
    }

    // ── 渲染目标重绑（CallEngine 跨 Activity 存活时，Activity 重建会重新注入 View） ──

    /**
     * 重绑两个 {@link SurfaceViewRenderer}（构造时注入的那对在 Activity 重建后已随窗口销毁）。
     *
     * <p>旧 renderer 先 release，新 renderer 立即用当前 {@code eglBase} init（与构造期
     * {@link #initFactoryAndEgl()} 的行为一致）；若本地视频轨还在，重新挂回新 renderer。
     *
     * <p><b>调用线程</b>：{@code SurfaceViewRenderer.init()} 必须在主线程调用 —— 调用方
     * （CallActivity.onCreate → CallEngine.setRendererViews）满足该前提。
     *
     * <p>{@code eglBase == null}（构造函数 initFactoryAndEgl 失败）时只换引用，不做 init。
     */
    public void setRenderers(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        if (local == null && remote == null) return;
        if (local == this.localRenderer && remote == this.remoteRenderer) return;

        if (local != null && local != this.localRenderer) {
            releaseRendererQuietly(this.localRenderer);
            this.localRenderer = local;
        }
        if (remote != null && remote != this.remoteRenderer) {
            releaseRendererQuietly(this.remoteRenderer);
            this.remoteRenderer = remote;
        }

        if (eglBase == null) {
            AppLog.e(TAG, "setRenderers: eglBase==null (ctor failed) — renderers rebound without init");
            return;
        }
        try {
            this.remoteRenderer.init(eglBase.getEglBaseContext(), null);
            this.remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            this.remoteRenderer.setEnableHardwareScaler(true);
            this.remoteRenderer.setMirror(false);
            this.localRenderer.init(eglBase.getEglBaseContext(), null);
            this.localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            this.localRenderer.setEnableHardwareScaler(true);
            this.localRenderer.setMirror(true);
            if (localVideoTrack != null) localVideoTrack.addSink(this.localRenderer);
            AppLog.d(TAG, "setRenderers: rebound and re-inited local+remote renderers");
        } catch (Exception e) {
            AppLog.e(TAG, "setRenderers: re-init failed", e);
        }
    }

    private static void releaseRendererQuietly(SurfaceViewRenderer renderer) {
        if (renderer == null) return;
        try {
            renderer.release();
        } catch (Exception e) {
            AppLog.e(TAG, "releaseRendererQuietly failed", e);
        }
    }

    /**
     * Wire the local camera-preview TextureView (relay mode uses the legacy Camera API).
     *
     * <p>允许重复调用（Activity 重建）：换锚点时会先把相机的 preview surface 摘掉
     * （{@code setPreviewTexture(null)}），再在新锚点上重新 attach —— 否则相机会继续往
     * 已销毁的旧 SurfaceTexture 出帧。
     */
    public void setRelayLocalPreviewView(android.view.TextureView view) {
        final android.view.TextureView previous = this.relayLocalPreviewView;
        this.relayLocalPreviewView = view;
        if (previous == view) return;   // 同一个锚点：不做任何事
        // 换锚点（含置 null）：先摘掉旧 surface，避免继续往旧 SurfaceTexture 出帧
        detachRelayPreviewSurface();
        rearmRelayPreview();
    }

    /** 把相机的 preview surface 摘掉并清掉"正在出帧"标记，使后续 attach 可以重走。 */
    private void detachRelayPreviewSurface() {
        relayAttachedView = null;
        relayVideoRunning = false;
        if (relayCamera != null) {
            try {
                relayCamera.setPreviewTexture(null);
            } catch (Exception e) {
                AppLog.e(TAG, "detachRelayPreviewSurface: setPreviewTexture(null) failed", e);
            }
        }
    }

    /**
     * 在"当前锚点"上重新走 attach：相机已配置好且 texture 已就绪 → 直接 attach；
     * 否则挂 {@link #relayTextureListener} 等 onSurfaceTextureAvailable。
     * 相机尚未打开（relayCamera == null / !relayCameraConfigured）时什么都不做 ——
     * 由后续 {@link #startRelayVideo()} 走正常启动流程。
     */
    private void rearmRelayPreview() {
        if (relayLocalPreviewView == null) return;
        if (relayCamera == null || !relayCameraConfigured) {
            AppLog.d(TAG, "rearmRelayPreview: camera not configured yet (camera="
                    + (relayCamera != null) + " configured=" + relayCameraConfigured + ")");
            return;
        }
        attachOrStartPreview();
    }

    /** Wire the ImageView that actually displays the local camera preview (decoded JPEG). */
    public void setRelayLocalPreviewFrame(android.widget.ImageView view) {
        this.relayLocalPreviewFrame = view;
    }

    private void ensureVideoDecodeThread() {
        if (videoDecodeThread == null) {
            videoDecodeThread = new HandlerThread("RelayVideoDecode");
            videoDecodeThread.start();
            videoDecodeHandler = new Handler(videoDecodeThread.getLooper());
        }
    }

    private void ensureAudioPlaybackThread() {
        if (audioPlaybackThread == null) {
            audioPlaybackThread = new HandlerThread("RelayAudioPlay");
            audioPlaybackThread.start();
            audioPlaybackHandler = new Handler(audioPlaybackThread.getLooper());
        }
    }

    /** Wire the remote ImageView that renders incoming relay video frames. */
    public void setRelayRemoteView(android.widget.ImageView view) {
        this.relayRemoteView = view;
    }

    /** Allow media capture to be streamed (call has been established on both sides). */
    public void setRelayStreamingEnabled(boolean enabled) {
        AppLog.d(TAG, "setRelayStreamingEnabled -> " + enabled
                + " (relayCamera=" + (relayCamera != null)
                + " relayVideoRunning=" + relayVideoRunning + ")");
        this.relayStreamingEnabled = enabled;
    }

    /** 本地视频上行是否开着（"关闭摄像头"的开关状态）。 */
    public boolean isVideoSendEnabled() {
        return relayVideoSendEnabled;
    }

    /**
     * 关/开本地摄像头（视频上行总开关）。关闭后通话与音频继续，可随时重开。
     *
     * <p>只作用于 relay 模式：非 relay（P2P）或无信令出口时直接忽略 —— 既不改状态也不发信令
     * （P2P 的视频开关不归本方法管，发空信令只会让对端收到一条无意义的"关了"）。
     *
     * <p>调用线程：会同步 stop/start 相机（与 switchRelayCamera 不同，那里放后台线程），
     * 因此应由 UI 线程调用；相机开合是几十毫秒级操作，暂不上后台线程以免与
     * TextureView 的 surface 回调竞态（见 startRelayVideo 的 attach 流程）。
     */
    public void setVideoSendEnabled(boolean enabled) {
        if (relayVideoSendEnabled == enabled) return;   // 幂等：重复点同一状态无副作用
        if (!isRelayMode || signalingSender == null) {
            AppLog.d(TAG, "setVideoSendEnabled ignored: relayMode=" + isRelayMode
                    + " sender=" + (signalingSender != null) + " want=" + enabled);
            return;
        }
        relayVideoSendEnabled = enabled;
        AppLog.d(TAG, "setVideoSendEnabled -> " + enabled
                + " camera=" + (relayCamera != null) + " front=" + relayCameraIsFront);

        if (!enabled) {
            // 关：停相机（stopRelayVideo 幂等，且刻意不重置 relayCameraIsFront → 重开沿用朝向）
            stopRelayVideo();
            signalingSender.send("call_video_state", relayRemoteUserId, "0");
        } else {
            // 开：相机真的打开了才发 enabled:true，失败则把开关退回 false 并通知 UI，
            // 保证"本地状态 == 对端看到的状态"。
            if (startRelayVideo()) {
                signalingSender.send("call_video_state", relayRemoteUserId, "1");
            } else {
                relayVideoSendEnabled = false;
                AppLog.e(TAG, "setVideoSendEnabled(true) failed: camera unavailable, "
                        + "state reverted to false (no signaling sent)");
            }
        }
    }

    /** 对端视频是否开着（最近一次 call_video_state 的状态）。 */
    public boolean isRemoteVideoEnabled() {
        return remoteVideoEnabled;
    }

    /**
     * 应用对端 {@code call_video_state}：缓存状态并通过主线程通知 UI。
     *
     * <p>只做"状态 + 通知"，不碰渲染：具体显示成头像还是继续显示（可能已定格）的
     * 最后一帧，由 UI 层（CallActivity）决定。回调一律 post 到主线程，因为
     * 本方法可能被 WebSocket 派发线程调用。
     */
    public void setRemoteVideoEnabled(boolean enabled) {
        this.remoteVideoEnabled = enabled;
        AppLog.d(TAG, "setRemoteVideoEnabled -> " + enabled
                + " (isRelayMode=" + isRelayMode + ")");
        uiHandler.post(() -> {
            if (listener != null) {
                try {
                    listener.onRemoteVideoStateChanged(enabled);
                } catch (Exception e) {
                    AppLog.e(TAG, "onRemoteVideoStateChanged failed", e);
                }
            }
        });
    }

    // ── Relay Video ──
    // 480x360: LAN 上帧率优先——画面更小，编码/解码/旋转/JSON 开销大幅下降，
    // 摄像头在 30fps 也能轻松喂饱，整体延迟明显变小（比 640x480 更顺）。
    // 480x360 target: LAN 上帧率优先——画面更小，编码/解码/旋转/JSON 开销大幅下降。
    // 实际预览尺寸取设备支持的最接近值（relayVideoW/H），缓冲区必须与之严格一致。
    private static final int RELAY_VIDEO_W = 480;
    private static final int RELAY_VIDEO_H = 360;
    private int relayVideoW = RELAY_VIDEO_W;
    private int relayVideoH = RELAY_VIDEO_H;
    private android.hardware.Camera relayCamera;
    // 初值 true：保证"新通话前摄优先"（与改造前 startRelayCamera(true) 的硬编码一致）。
    // stopRelayVideo() 刻意不重置它，因此"关摄像头 → 开摄像头"能沿用上次使用的朝向。
    private boolean relayCameraIsFront = true;   // which camera we opened (drives JPEG rotation)
    // volatile：UI 线程会在"关/开摄像头"时写它，相机回调线程（relayFrameCallback）读它。
    private volatile boolean relayVideoRunning;
    /**
     * 视频上行总开关（"关闭摄像头"）。与 {@code relayStreamingEnabled} 严格分工：
     * 后者是"整条 relay 通路是否可发"（同时门控音频上行），本字段<b>只</b>门控视频帧上行。
     * 关闭摄像头时通话与音频不受影响，因此不能复用 relayStreamingEnabled。
     */
    private volatile boolean relayVideoSendEnabled = true;
    private boolean relayCameraConfigured = false; // 相机已打开并配置好，等 TextureView 就绪后 attach
    // 相机当前实际挂载的 TextureView 锚点。setRelayLocalPreviewView() 换锚点时据此判断是否需要重新 attach。
    private android.view.TextureView relayAttachedView;
    private final android.view.TextureView.SurfaceTextureListener relayTextureListener =
            new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(
                        android.graphics.SurfaceTexture st, int width, int height) {
                    AppLog.d(TAG, "onSurfaceTextureAvailable " + width + "x" + height
                            + " configured=" + relayCameraConfigured);
                    // TextureView 有了真实 SurfaceTexture：把相机挂上去并启动预览，
                    // 帧才会在回调里稳定出现（无 surface 时部分设备一帧都不给）。
                    attachAndStartPreview();
                }
                @Override public void onSurfaceTextureSizeChanged(
                        android.graphics.SurfaceTexture st, int width, int height) {
                    // 窗口拖动/缩放/交换动画中尺寸变化——TextureView 自动缩放纹理，无需处理
                }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    return false; // 交给框架释放；相机已在 stopRelayVideo 中停止
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
            };

    /**
     * The WebRTC capturer (started in initLocalMedia) already owns the camera device.
     * Release it before opening the legacy Camera so the relay video path gets the device.
     * The WebRTC local-preview renderer then goes dark; the relay path previews instead
     * via relayLocalPreviewView (an ImageView wired by CallActivity).
     */
    private void releaseWebRtcCamera() {
        AppLog.d(TAG, "releaseWebRtcCamera: videoCapturer=" + (videoCapturer != null)
                + " videoSource=" + (videoSource != null)
                + " (release is asynchronous — Camera.open may still fail right after)");
        try { if (videoCapturer != null) videoCapturer.stopCapture(); } catch (Exception e) {
            AppLog.e(TAG, "releaseWebRtcCamera stopCapture failed", e);
        }
        try { if (videoCapturer != null) videoCapturer.dispose(); } catch (Exception e) {
            AppLog.e(TAG, "releaseWebRtcCamera dispose failed", e);
        }
        videoCapturer = null;
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) {}
        videoSource = null;
        localVideoTrack = null;
        try { localRenderer.setVisibility(View.GONE); } catch (Exception ignored) {}
    }

    /**
     * 打开 relay 相机并（尽量）开始出帧。
     *
     * @return 相机是否成功打开。false 只表示"前后摄都打不开"，此时已通过
     *         {@link Listener#onVideoStartFailed()} 通知 UI；调用方据此决定是否发送
     *         {@code call_video_state{enabled:true}}（失败时绝不能发，否则状态与事实不符）。
     */
    private boolean startRelayVideo() {
        AppLog.d(TAG, "startRelayVideo: enter running=" + relayVideoRunning
                + " previewView=" + (relayLocalPreviewView != null));
        // 已在本锚点上出帧才跳过；锚点被 setRelayLocalPreviewView() 换掉后 relayAttachedView
        // 会被清空（detachRelayPreviewSurface），这里允许重走 attach 流程。
        if (relayVideoRunning && relayAttachedView == relayLocalPreviewView) return true;
        releaseWebRtcCamera();
        ensureVideoDecodeThread();

        // Open front camera first; fall back to back. The WebRTC capturer we just released
        // frees the device asynchronously, so a busy device can fail on the first try —
        // retry briefly before giving up.
        // 朝向取 relayCameraIsFront（而非硬编码前摄）：关闭摄像头后重开时沿用上次朝向。
        startRelayCamera(relayCameraIsFront);
        if (relayCamera == null) {
            AppLog.d(TAG, "startRelayVideo: camera failed, trying the other facing");
            startRelayCamera(!relayCameraIsFront);
        }
        if (relayCamera == null) {
            // 前后摄都打不开。改造前这条路径**没有任何 UI 提示**（用户点"开摄像头"毫无反应），
            // 现在通过 listener 回调通知 UI；返回 false 让调用方不要发 enabled:true。
            AppLog.e(TAG, "startRelayVideo GIVE UP: no camera available (both facings failed)");
            Log.e(TAG, "Relay video: no camera available (both facings failed)");
            uiHandler.post(() -> {
                if (listener != null) {
                    try {
                        listener.onVideoStartFailed();
                    } catch (Exception e) {
                        AppLog.e(TAG, "onVideoStartFailed failed", e);
                    }
                }
            });
            return false;
        }
        AppLog.d(TAG, "startRelayVideo: camera prepared front=" + relayCameraIsFront
                + " " + relayVideoW + "x" + relayVideoH
                + " configured=" + relayCameraConfigured);
        attachOrStartPreview();
        return true;
    }

    /**
     * 打开指定朝向（front=true 前摄）的 legacy Camera 并完成配置。
     * 选设备真实支持的预览尺寸（≤640x480、4:3 优先），缓冲区严格匹配实际尺寸；
     * 帧率尽量提到最高档（局网帧率优先）。配置完先不启动预览——
     * 等 TextureView 的 surface 就绪再由 attachOrStartPreview() 挂载，
     * 否则部分设备回调一帧都不给（无声无画）。
     * 切摄像头也走这里：先 release 旧相机再按新朝向重新打开。
     */
    private void startRelayCamera(boolean front) {
        try {
            relayCamera = openCameraWithRetry(front ? 1 : 0);
            if (relayCamera == null) {
                Log.w(TAG, "Relay video: camera facing=" + front + " unavailable");
                return;
            }
            relayCameraIsFront = front;

            android.hardware.Camera.Parameters params = relayCamera.getParameters();

            // CRITICAL: pick a preview size the device ACTUALLY supports, closest to our
            // target. setPreviewCallbackWithBuffer requires the buffer size to match the
            // real preview size exactly — if they differ, the camera silently stops
            // delivering frames (audio works, video never appears). Fixed-size buffers
            // (480x360) broke video on devices that don't support that size.
            // PREFER 4:3 aspect: the frame is captured in sensor orientation (landscape) and
            // rotated 90° for display, so 4:3 landscape becomes a natural 3:4 portrait that
            // matches the small window exactly. Picking 16:9 (e.g. 640x360) instead made the
            // rotated picture an unnatural tall 9:16 and stretched-looking on both ends.
            // 限制面积 ≤ 640x480：保证编码/解码够快、JPEG 体积不超帧体上限（LAN 帧率优先）。
            int w = RELAY_VIDEO_W, h = RELAY_VIDEO_H;
            try {
                java.util.List<android.hardware.Camera.Size> sizes = params.getSupportedPreviewSizes();
                if (sizes != null && !sizes.isEmpty()) {
                    final long MAX_AREA = 640L * 480L;
                    android.hardware.Camera.Size best = null;
                    android.hardware.Camera.Size smallest = null;
                    long smallestArea = Long.MAX_VALUE;
                    long bestScore = Long.MAX_VALUE;
                    for (android.hardware.Camera.Size s : sizes) {
                        long area = (long) s.width * s.height;
                        if (smallest == null || area < smallestArea) { smallest = s; smallestArea = area; }
                        if (area > MAX_AREA) continue; // 太大不选，防止丢帧/卡顿
                        long aspect = Math.abs(s.width * 3L - s.height * 4L); // |3w-4h|：4:3 时接近 0
                        // 4:3 优先（aspect 权重极大），同比例内按分辨率接近 480x360 择优
                        long score = aspect * 100000L
                                + (Math.abs(s.width - RELAY_VIDEO_W) + Math.abs(s.height - RELAY_VIDEO_H));
                        if (score < bestScore) { bestScore = score; best = s; }
                    }
                    if (best == null) best = smallest; // 全比 640x480 大：退而选最小
                    if (best != null) { w = best.width; h = best.height; }
                }
            } catch (Exception ignored) {}
            params.setPreviewSize(w, h);

            // 尽量把预览帧率提到摄像头支持的最高档（局网不在乎流量，要的是帧率）
            try {
                java.util.List<int[]> ranges = params.getSupportedPreviewFpsRange();
                if (ranges != null && !ranges.isEmpty()) {
                    int[] best = ranges.get(0);
                    for (int[] r : ranges) { if (r[1] > best[1]) best = r; }
                    params.setPreviewFpsRange(best[0], best[1]);
                }
            } catch (Exception ignored) {}
            relayCamera.setParameters(params);

            // Confirm the ACTUAL preview size after setParameters (device may adjust),
            // then allocate buffers to match it exactly.
            try {
                android.hardware.Camera.Size actual = relayCamera.getParameters().getPreviewSize();
                if (actual != null) { w = actual.width; h = actual.height; }
            } catch (Exception ignored) {}
            relayVideoW = w;
            relayVideoH = h;
            relayCamera.setDisplayOrientation(90); // portrait orientation

            relayCameraConfigured = true;
            AppLog.d(TAG, "startRelayCamera prepared " + w + "x" + h + " front=" + front
                    + " configured=true");
            Log.d(TAG, "Relay video camera prepared (" + w + "x" + h + ", front=" + front + ")");
        } catch (Exception e) {
            AppLog.e(TAG, "startRelayCamera failed front=" + front + ": " + e.getMessage(), e);
            Log.w(TAG, "Relay video preview start failed: " + e.getMessage());
            try { relayCamera.stopPreview(); } catch (Exception ignored) {}
            try { relayCamera.release(); } catch (Exception ignored) {}
            relayCamera = null;
            relayCameraConfigured = false;
        }
    }

    /** 本地预览 TextureView 的 texture 已就绪则立即 attach，否则挂 listener 等它出现。 */
    private void attachOrStartPreview() {
        if (!relayCameraConfigured || relayCamera == null) {
            AppLog.d(TAG, "attachOrStartPreview: skip (configured=" + relayCameraConfigured
                    + " camera=" + (relayCamera != null) + ")");
            return;
        }
        if (relayLocalPreviewView == null) {
            Log.e(TAG, "Relay video: no local preview view wired");
            AppLog.e(TAG, "attachOrStartPreview: no local preview view wired (relay video will never start)");
            return;
        }
        boolean available = relayLocalPreviewView.isAvailable();
        AppLog.d(TAG, "attachOrStartPreview: TextureView isAvailable=" + available);
        if (available) {
            attachAndStartPreview();
        } else {
            AppLog.d(TAG, "attachOrStartPreview: waiting for onSurfaceTextureAvailable"
                    + " (container may still be GONE)");
            relayLocalPreviewView.setSurfaceTextureListener(relayTextureListener);
        }
    }

    /** 把已配置好的相机挂到 TextureView 上并启动预览（回调从此稳定出帧）。 */
    private void attachAndStartPreview() {
        if (relayCamera == null || relayLocalPreviewView == null) return;
        try {
            android.graphics.SurfaceTexture st = relayLocalPreviewView.getSurfaceTexture();
            if (st == null) { // 理论上不会：listener 已保证 texture 就绪
                AppLog.d(TAG, "attachAndStartPreview: getSurfaceTexture()==null, re-arm");
                attachOrStartPreview();
                return;
            }
            relayCamera.setPreviewTexture(st);
            relayCamera.setPreviewCallbackWithBuffer(relayFrameCallback);
            relayCamera.addCallbackBuffer(new byte[relayVideoW * relayVideoH * 3 / 2]);
            relayCamera.addCallbackBuffer(new byte[relayVideoW * relayVideoH * 3 / 2]);
            relayCamera.startPreview();
            relayVideoRunning = true;
            relayAttachedView = relayLocalPreviewView;
            AppLog.d(TAG, "attachAndStartPreview OK: preview started "
                    + relayVideoW + "x" + relayVideoH);
            Log.d(TAG, "Relay video preview started (" + relayVideoW + "x" + relayVideoH + ")");
        } catch (Exception e) {
            AppLog.e(TAG, "attachAndStartPreview FAILED: " + e.getMessage(), e);
            Log.w(TAG, "Relay video attach/preview failed: " + e.getMessage());
            try { relayCamera.stopPreview(); } catch (Exception ignored) {}
            try { relayCamera.release(); } catch (Exception ignored) {}
            relayCamera = null;
            relayCameraConfigured = false;
        }
    }

    /** Open a camera by index, retrying briefly (the released WebRTC capturer may still hold it). */
    private android.hardware.Camera openCameraWithRetry(int index) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                android.hardware.Camera cam = android.hardware.Camera.open(index);
                AppLog.d(TAG, "Camera.open(" + index + ") OK on attempt " + attempt
                        + " thread=" + Thread.currentThread().getName());
                return cam;
            } catch (Exception e) {
                // Step 0 观察点：交接竞态的直接证据（设备仍被 WebRTC capturer 占用时就是这里失败）。
                AppLog.e(TAG, "Camera.open(" + index + ") attempt " + attempt + " failed: "
                        + e.getMessage(), e);
                Log.w(TAG, "Camera.open(" + index + ") attempt " + attempt + " failed: " + e.getMessage());
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
        }
        AppLog.e(TAG, "Camera.open(" + index + ") exhausted 4 attempts -> null");
        return null;
    }

    private void stopRelayVideo() {
        relayVideoRunning = false;
        relayCameraConfigured = false;
        relayAttachedView = null;
        if (relayCamera != null) {
            try { relayCamera.stopPreview(); } catch (Exception ignored) {}
            try { relayCamera.release(); } catch (Exception ignored) {}
            relayCamera = null;
        }
        // 先断开 ImageView 对这张 Bitmap 的引用，再回收。
        // 否则 ImageView 的 Drawable 仍持有它，下次重绘（重开摄像头时
        // localVideoContainer 从 GONE 变 VISIBLE 触发）会画到已回收的
        // Bitmap —— 可能抛 "Canvas: trying to use a recycled bitmap"。
        if (relayLocalPreviewFrame != null) {
            try { relayLocalPreviewFrame.setImageBitmap(null); } catch (Exception ignored) {}
        }
        // 回收本地预览最后一帧
        if (lastLocalPreviewBitmap != null) {
            try { lastLocalPreviewBitmap.recycle(); } catch (Exception ignored) {}
            lastLocalPreviewBitmap = null;
        }
        // 结束解码线程
        if (videoDecodeThread != null) {
            try { videoDecodeThread.quitSafely(); } catch (Exception ignored) {}
            videoDecodeThread = null;
            videoDecodeHandler = null;
        }
        // 复位"上一帧还在处理"标志。两个 flag 平时由各自路径的 finally 复位，但 stop 时
        // 已投递/在途的解码任务可能随 looper 一起消失，finally 不再执行 → 永久卡 true，
        // 表现为"重开摄像头后本地预览/远端画面再也不刷新"。这里显式复位，代价为零。
        localPreviewDecodeBusy = false;
        synchronized (videoRenderLock) { videoFrameBusy = false; }
    }

    /**
     * Preview-frame callback (runs on the camera thread).
     * Encodes each frame to a sensor-orientation JPEG, then:
     *  1) 发送限帧：~20fps，不到间隔就跳过发送（防止 WS 队列在慢网下无限堆积导致越来越延迟）
     *  2) 本地预览：隔帧把同一张 JPEG 丢给后台解码线程，旋转/镜像后填满小窗（约 15fps 足够顺）
     * 前缀 F/B 告诉接收端该帧用前摄(270°)/后摄(90°)旋转，避免整帧重新编码。
     * 用过的 buffer 编码完再还给摄像头，避免一边写一边读导致撕裂/花屏。
     */
    private final android.hardware.Camera.PreviewCallback relayFrameCallback = new android.hardware.Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
            if (data == null) return;
            boolean streaming = relayVideoRunning && signalingSender != null && relayStreamingEnabled
                    && relayVideoSendEnabled;
            // Step 0 观察点：只打第一帧，证明"相机是否真的出帧"以及当时的发送门状态。
            if (!relayFirstFrameLogged) {
                relayFirstFrameLogged = true;
                AppLog.d(TAG, "relay camera first frame: " + relayVideoW + "x" + relayVideoH
                        + " streaming=" + streaming
                        + " [running=" + relayVideoRunning
                        + " sender=" + (signalingSender != null)
                        + " streamingEnabled=" + relayStreamingEnabled + "]"
                        + " front=" + relayCameraIsFront);
            }
            try {
                android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                        data, android.graphics.ImageFormat.NV21, relayVideoW, relayVideoH, null);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuv.compressToJpeg(new android.graphics.Rect(0, 0, relayVideoW, relayVideoH), 50, out);
                byte[] jpeg = out.toByteArray();
                if (jpeg.length > 0 && jpeg.length <= 150_000) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (streaming && (now - lastVideoSendAt >= VIDEO_SEND_INTERVAL_MS)) {
                        lastVideoSendAt = now;
                        String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
                        signalingSender.send("call_video", relayRemoteUserId,
                                (relayCameraIsFront ? "F" : "B") + b64);
                    }
                    // 本地预览：隔帧刷新，后台线程解码+旋转+镜像，centerCrop 填满小窗
                    if (relayLocalPreviewFrame != null && isRelayMode) {
                        if (++localPreviewFrameSkip % 2 == 0) {
                            postLocalPreviewDecode(jpeg, relayCameraIsFront);
                        }
                    }
                }
            } catch (Exception ignored) {}
            // 编码完再把 buffer 还给摄像头，避免一边写一边读导致撕裂/花屏
            try { camera.addCallbackBuffer(data); } catch (Exception ignored) {}
        }
    };

    /** 后台解码本地预览 JPEG：旋转（前摄 270° / 后摄 90°）+ 前摄镜像，然后 UI 线程 setImageBitmap。 */
    private void postLocalPreviewDecode(final byte[] jpeg, final boolean front) {
        if (localPreviewDecodeBusy) return; // 上一帧还在解码，这帧直接丢，保证队列不堆积
        localPreviewDecodeBusy = true;
        ensureVideoDecodeThread();
        videoDecodeHandler.post(() -> {
            try {
                android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (raw == null) return;
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(front ? 270 : 90);
                if (front) m.postScale(-1, 1); // 前摄预览像照镜子
                final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (raw != bmp) raw.recycle();
                if (bmp == null) return;
                uiHandler.post(() -> {
                    try {
                        if (relayLocalPreviewFrame != null && isRelayMode) {
                            relayLocalPreviewFrame.setImageBitmap(bmp);
                            if (lastLocalPreviewBitmap != null && lastLocalPreviewBitmap != bmp) {
                                lastLocalPreviewBitmap.recycle();
                            }
                            lastLocalPreviewBitmap = bmp;
                        } else {
                            bmp.recycle();
                        }
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {
            } finally {
                localPreviewDecodeBusy = false;
            }
        });
    }

    /**
     * Display an incoming relay video frame. Payload is "F/B + base64 JPEG"; the prefix
     * carries the sender's camera facing so we rotate the sensor frame to upright portrait.
     * Latest-wins: if a previous frame is still being decoded/rendered we drop this one,
     * so the frame queue can never grow and latency stays bounded.
     */
    public void onRelayVideoFrame(String data) {
        if (!isRelayMode || relayRemoteView == null || data == null) return;
        // Step 0 观察点：只打第一帧，证明"对端画面是否到达本机"。
        if (!relayFirstRemoteFrameLogged) {
            relayFirstRemoteFrameLogged = true;
            AppLog.d(TAG, "first remote video frame received: len=" + data.length()
                    + " isRelayMode=" + isRelayMode);
        }
        synchronized (videoRenderLock) {
            if (videoFrameBusy) return; // 落后了，丢掉旧帧，只留最新一帧
            videoFrameBusy = true;
        }
        boolean front = true;
        String payload = data;
        if (data.length() > 1 && (data.charAt(0) == 'F' || data.charAt(0) == 'B')) {
            front = data.charAt(0) == 'F';
            payload = data.substring(1);
        }
        final boolean isFront = front;
        final String b64 = payload;
        ensureVideoDecodeThread();
        videoDecodeHandler.post(() -> {
            try {
                byte[] jpeg = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (raw == null) {
                    synchronized (videoRenderLock) { videoFrameBusy = false; }
                    return;
                }
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(isFront ? 270 : 90);
                final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (raw != bmp) raw.recycle();
                if (bmp == null) {
                    synchronized (videoRenderLock) { videoFrameBusy = false; }
                    return;
                }
                uiHandler.post(() -> {
                    try {
                        if (relayRemoteView != null && isRelayMode) {
                            relayRemoteView.setImageBitmap(bmp);
                            relayRemoteView.setVisibility(View.VISIBLE);
                            // 回收上一帧的位图，避免高频帧率下内存暴涨
                            if (lastRemoteBitmap != null && lastRemoteBitmap != bmp) {
                                lastRemoteBitmap.recycle();
                            }
                            lastRemoteBitmap = bmp;
                        } else {
                            bmp.recycle();
                        }
                    } finally {
                        synchronized (videoRenderLock) { videoFrameBusy = false; }
                    }
                });
            } catch (Exception e) {
                synchronized (videoRenderLock) { videoFrameBusy = false; }
            }
        });
    }

    /** Initialize relay audio capture + playback. */
    private void initRelayAudio() {
        final int minBufSize = android.media.AudioRecord.getMinBufferSize(RELAY_SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        final int bufSize = Math.max(minBufSize, RELAY_BUFFER_SIZE * 2);

        try {
            relayRecorder = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RELAY_SAMPLE_RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize);

            if (relayRecorder.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                AppLog.e(TAG, "initRelayAudio: AudioRecord not initialized");
                notifyError("麦克风初始化失败");
                return;
            }
            AppLog.d(TAG, "initRelayAudio OK: " + RELAY_SAMPLE_RATE + "Hz mono, buf=" + bufSize);

            isRelayCapturing = true;
            relayCaptureThread = new Thread(this::relayCaptureLoop, "RelayCapture");
            relayCaptureThread.setDaemon(true);
            relayCaptureThread.start();
        } catch (Exception e) {
            Log.e(TAG, "initRelayAudio failed", e);
            notifyError("音频初始化失败: " + e.getMessage());
        }
    }

    /** Capture loop: reads PCM chunks and sends via SignalingSender. */
    private void relayCaptureLoop() {
        // THREAD_PRIORITY_URGENT_AUDIO (-19) requires system privileges —
        // regular apps can't set it, so we try and ignore failure
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {
            // Priority is a hint, not required for audio to work
        }
        byte[] buffer = new byte[RELAY_BUFFER_SIZE];
        // 节流用：只在"发送/跳过发送"状态翻转时打一条，避免每 100ms 刷屏
        boolean muteSkipLogged = false;

        try {
            relayRecorder.startRecording();
            while (isRelayCapturing && !Thread.interrupted()) {
                int bytesRead = relayRecorder.read(buffer, 0, buffer.length);
                if (signalingSender != null && relayStreamingEnabled) {
                    byte[] sendBuf;
                    if (bytesRead > 0) {
                        sendBuf = java.util.Arrays.copyOf(buffer, bytesRead);
                    } else {
                        // Mic underrun — send silence to keep the stream continuous
                        sendBuf = new byte[RELAY_BUFFER_SIZE];
                    }
                    boolean muteSkip = relayMuted;
                    if (muteSkip != muteSkipLogged) {
                        muteSkipLogged = muteSkip;
                        AppLog.d(TAG, "relayCapture: " + (muteSkip ? "闭麦生效，跳过发送" : "取消闭麦，恢复发送")
                                + " thread=" + Thread.currentThread().getName());
                    }
                    if (!relayMuted) {
                        String b64 = android.util.Base64.encodeToString(sendBuf, android.util.Base64.NO_WRAP);
                        signalingSender.send("call_media", relayRemoteUserId, b64);
                    }
                }
            }
        } catch (Exception e) {
            if (isRelayCapturing) {
                Log.e(TAG, "relayCaptureLoop error", e);
            }
        } finally {
            try {
                if (relayRecorder != null && relayRecorder.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    relayRecorder.stop();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Process an incoming relay media chunk.
     * Called from CallActivity on WebSocket onCallMedia.
     * 解码与 AudioTrack.write（阻塞式）都在后台音频线程执行，绝不占用主线程，
     * 否则开着麦克风时视频渲染会被音频阻塞拖慢（画面"延长"）。
     */
    public void onRelayMediaChunk(String base64Data, int seq, String mediaType) {
        if (!isRelayMode || !"audio".equals(mediaType)) return;
        // Step 0 观察点：只打第一块，证明音频通路是否建立。
        if (!relayFirstMediaChunkLogged) {
            relayFirstMediaChunkLogged = true;
            AppLog.d(TAG, "first relay audio chunk received: len=" + base64Data.length());
        }
        try {
            final byte[] pcmData = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
            ensureAudioPlaybackThread();
            audioPlaybackHandler.post(() -> {
                try {
                    synchronized (relayAudioLock) {
                        if (relayPlayer == null) {
                            // Lazy-init AudioTrack on first chunk
                            int bufSize = java.lang.Math.max(
                                    android.media.AudioTrack.getMinBufferSize(RELAY_SAMPLE_RATE,
                                            android.media.AudioFormat.CHANNEL_OUT_MONO,
                                            android.media.AudioFormat.ENCODING_PCM_16BIT),
                                    RELAY_BUFFER_SIZE * 4);
                            relayPlayer = new android.media.AudioTrack(
                                    android.media.AudioManager.STREAM_VOICE_CALL,
                                    RELAY_SAMPLE_RATE,
                                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                                    bufSize,
                                    android.media.AudioTrack.MODE_STREAM);
                            relayPlayer.play();
                        }
                    }
                    // 本地静音对方：在 write 前拦截下行播放。
                    // 与 relayMuted 的"跳过上行发送"镜像对称，一处改动即可；
                    // relayPlayer 保持 PLAYING 不重建；MODE_STREAM 下长期不写无副作用，
                    // 取消静音后继续写即可恢复。
                    if (!relayLocalMuted
                            && relayPlayer != null
                            && relayPlayer.getPlayState() == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                        relayPlayer.write(pcmData, 0, pcmData.length);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "audioPlayback error", e);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "onRelayMediaChunk error", e);
        }
    }

    /** Remote peer stopped sending relay media. */
    public void onRelayMediaStop() {
        Log.d(TAG, "onRelayMediaStop: remote relay stream ended");
    }

    /** Stop relay capture + playback. */
    public void stopRelayCall() {
        AppLog.d(TAG, "stopRelayCall: wasRelayMode=" + isRelayMode
                + " videoRunning=" + relayVideoRunning
                + " camera=" + (relayCamera != null));
        isRelayCapturing = false;
        relayStreamingEnabled = false;
        isRelayMode = false;
        stopRelayVideo();

        if (relayCaptureThread != null) {
            relayCaptureThread.interrupt();
            relayCaptureThread = null;
        }

        try {
            if (relayRecorder != null) {
                try {
                    if (relayRecorder.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        relayRecorder.stop();
                    }
                } catch (Exception ignored) {}
                relayRecorder.release();
                relayRecorder = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "stopRelayCall recorder error", e);
        }

        // 在音频线程上释放 AudioTrack（避免与 write 竞争），quitSafely 会先跑完已投递的任务
        if (audioPlaybackThread != null) {
            try {
                if (audioPlaybackHandler != null) {
                    audioPlaybackHandler.post(() -> {
                        synchronized (relayAudioLock) {
                            if (relayPlayer != null) {
                                try {
                                    if (relayPlayer.getPlayState() == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                                        relayPlayer.stop();
                                    }
                                } catch (Exception ignored) {}
                                relayPlayer.release();
                                relayPlayer = null;
                            }
                        }
                    });
                }
                audioPlaybackThread.quitSafely();
            } catch (Exception e) {
                Log.w(TAG, "stopRelayCall player error", e);
            }
            audioPlaybackThread = null;
            audioPlaybackHandler = null;
        }

        synchronized (relayAudioLock) {
            relayPlaybackQueue.clear();
        }
    }

    /** Whether this manager is in relay mode. */
    public boolean isRelayMuted() { return relayMuted; }
    public void setRelayMuted(boolean muted) {
        this.relayMuted = muted;
        AppLog.d(TAG, "setRelayMuted: caller=toggleMute newValue=" + muted
                + " thread=" + Thread.currentThread().getName());
    }
    /** 本地静音对方（拦下行播放，纯本地，不发信令）。 */
    public boolean isRelayLocalMuted() { return relayLocalMuted; }
    public void setRelayLocalMuted(boolean muted) {
        this.relayLocalMuted = muted;
        AppLog.d(TAG, "setRelayLocalMuted: newValue=" + muted
                + " thread=" + Thread.currentThread().getName());
    }
    public boolean isRelayMode() {
        return isRelayMode;
    }
}
