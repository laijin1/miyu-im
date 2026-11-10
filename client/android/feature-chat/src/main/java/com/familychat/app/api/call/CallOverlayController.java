package com.familychat.app.api.call;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import com.familychat.app.api.Models;
import com.familychat.app.ui.call.CallOverlayView;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Routes;
import com.google.gson.Gson;

/**
 * 通话"应用内浮层"的进程级静态门面（仿 {@link CallManager} / {@link CallEngine} 风格）。
 *
 * <h3>职责</h3>
 * 把 {@link CallOverlayView} 挂到宿主 Activity 的 {@code android.R.id.content} 上、并把三个
 * relay 渲染目标注册进 {@link CallEngine}。宿主只需要在 <b>onStart 调 attach(this)、
 * onStop 调 detach()</b>。
 *
 * <p><b>系统浮窗的收放也由本类统一负责</b>（宿主不必自己管）：
 * {@link #attach(Activity)} 里调 {@code CallEngine.hideFloatingWindow()} 收掉系统浮窗，
 * {@link #onHostStopped(Activity)} 负责在真的切走时把它弹出来。两者必须成对，否则会出现
 * "应用内浮层 + 系统浮窗"两个浮窗并存。
 *
 * <h3>为什么挂在 android.R.id.content</h3>
 * 与 {@code VoiceRecordOverlay} 同款（见 ChatActivity.java:343
 * {@code ((ViewGroup) findViewById(android.R.id.content)).addView(voiceOverlay)}）：
 * 这一层是框架提供的 FrameLayout，浮层作为<b>后加的子 View</b> 天然叠在宿主内容之上，
 * 宿主布局文件一行都不用改。
 *
 * <h3>调用时机必须是 onStart / onStop，不是 onCreate / onDestroy</h3>
 * 通话音视频要在"宿主可见/不可见"这一对回调上迁移：
 * <ul>
 *   <li>onStart/onStop 与可见性对齐（同 {@code ChatActivity.currentFriendId} 的做法，
 *       见 ChatActivity.java:1848-1858 的注释）；</li>
 *   <li>用 onCreate/onDestroy 会让"切到别的页面再回来"这种常见路径错过迁移时机
 *       （Activity 实例还在，onCreate 不会再跑）。</li>
 * </ul>
 *
 * <h3>attach / detach 的线程约定</h3>
 * 都只在主线程调用（宿主生命周期回调天然在主线程）。本类不做线程切换。
 *
 * <h3>已知的宿主切换次序问题（本类已处理，见 attach 注释）</h3>
 * Android 切换 Activity 的标准次序是 {@code host1.onPause → host2.onStart/onResume →
 * host1.onStop}。若 attach 对"已 attach"一律早返回，就会出现"host2.onStart 被拒 →
 * host1.onStop 把浮层拆掉 → 两个页面都没有浮层"的断档。因此 {@link #attach(Activity)}
 * 只在<b>同一个宿主</b>时幂等，换宿主时先迁移。
 * 同理，宿主的 onStop 必须调宿主作用域的 {@link #detach(Activity)}，不能调无参
 * {@link #detach()} —— 否则会把刚迁到新宿主的浮层摘掉。
 */
public final class CallOverlayController {

    private static final String TAG = "CallOverlayController";

    private static final Object LOCK = new Object();

    /** 宿主 onStop 后等待多久再判定"是否需要系统浮窗"：给"App 内切页"的接管留出时间。 */
    private static final long SYSTEM_OVERLAY_DELAY_MS = 200L;

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static CallOverlayView currentView;
    private static Activity currentHost;

    /**
     * 通话页（CallActivity）当前是否可见。
     *
     * <p>由 CallActivity.onStart / onStop 置位/清除。用于抑制"通话页已经全屏显示通话，
     * 却还弹出系统浮窗"的重复 UI：宿主 onStop 的延迟判定若只看 {@link #isAttached()}，
     * 会在"宿主 → 通话页"这条路径上误判 —— 通话页不是应用内宿主（它不挂浮层），
     * 于是判定为"切到别的 App"并弹出系统浮窗。
     */
    private static volatile boolean callPageVisible;

    private CallOverlayController() {
    }

    // =================================================================
    //  挂载 / 卸载
    // =================================================================

    /**
     * 把浮层挂到 {@code host} 上（幂等）。
     *
     * <p>前置条件：当前有一通通话（{@link CallManager#getCurrentCall()} 非 null）。
     * 没有通话时不挂 —— 浮层没有可显示的内容。
     *
     * <p>幂等语义：<b>同一个宿主</b>重复调用直接返回；<b>不同宿主</b>则先把旧宿主上的
     * 浮层摘掉再挂到新宿主（迁移）。后者是必需的 —— 宿主切换的标准次序是
     * host2.onStart 早于 host1.onStop，若换宿主也早返回，最后会被 host1.onStop 拆成空白。
     */
    public static void attach(Activity host) {
        if (host == null) return;

        synchronized (LOCK) {
            if (currentView != null && currentHost == host) {
                AppLog.d(TAG, "attach: already attached to this host — skip");
                return;
            }
        }

        final CallSession s = CallManager.getCurrentCall();
        if (s == null) {
            AppLog.d(TAG, "attach: no current call — skip");
            return;
        }

        // 换宿主：先把旧宿主上的浮层摘掉（内部会清 currentView/currentHost）
        if (isAttached()) {
            AppLog.d(TAG, "attach: migrating from another host");
            detach();
        }

        final ViewGroup content;
        try {
            content = (ViewGroup) host.findViewById(android.R.id.content);
        } catch (Exception e) {
            AppLog.e(TAG, "attach: findViewById(android.R.id.content) failed", e);
            return;
        }
        if (content == null) {
            AppLog.e(TAG, "attach: content root not found");
            return;
        }

        final CallOverlayView view;
        try {
            view = new CallOverlayView(host);
        } catch (Exception e) {
            AppLog.e(TAG, "attach: CallOverlayView construction failed", e);
            return;
        }

        try {
            content.addView(view);
        } catch (Exception e) {
            AppLog.e(TAG, "attach: addView failed", e);
            return;
        }

        // S2 抑制标志：置位后 CallActivity.onStop 不再显示系统浮窗（App 内浮层已接管）
        CallEngine.setInAppHostActive(true);

        // ★ 回到 App 必须收掉系统浮窗（修复"两个浮窗同时存在"）。
        //
        // 位置刻意选在这里 —— addView 成功之后、渲染目标注入之前：
        //   - 必须**在 addView 成功之后**：addView 失败时上面已经 return，不会误关系统浮窗
        //     （那会变成"App 内挂不上 + 系统浮窗也没了"= 通话 UI 全丢，比双浮窗更糟）。
        //   - 必须**在渲染目标注入之前**：hide() 只做 removeView + 停 ticker，不碰 View 引用，
        //     但它会把计时 ticker 停掉；放在注入前，语义上是"先清干净旧 UI，再接新渲染目标"，
        //     万一 hide() 抛异常也不会带着半接好的渲染目标留在新宿主上。
        //   - 与 currentView 赋值无关：那个赋值在最后（192 行附近）只是登记"已挂载"，
        //     hide 不依赖它；反过来若放在它之后，中间那段窗口里 isAttached() 已是 true
        //     而系统浮窗还在 —— 正是要消灭的状态。
        //
        // 为什么必须在这里做、而不是让每个宿主自己调（CallActivity.onStart:954 就是自己调的）：
        // 宿主有 MainActivity（MainActivity.java:236）和 ChatActivity（ChatActivity.java:1849）
        // 两个，各自 onStart 都只调 attach()。写在这里一处覆盖两个宿主，且与
        // detach()/onHostStopped() 的"控制器统一负责系统浮窗"分工一致。
        //
        // 复现的原始路径：通话中按 Home → onHostStopped 延迟 200ms 判定为"切到别的 App"
        // → CallFloatingWindow.show()（301-319 行）；此时点系统浮窗回 App（CallFloatingWindow
        // .openCallActivity:334 只 startActivity，不 hide 自己）→ 宿主 onStart → attach()
        // 挂上应用内浮层，而系统浮窗仍在 → 两个浮窗并存。这行即补上缺失的 hide。
        CallEngine.hideFloatingWindow();

        // 渲染目标接线 —— **仅视频版浮层**。
        //
        // 语音版浮层（!view.hasVideoLayer()）刻意整体跳过，依据：
        //   1) 它的三个渲染目标字段全是 null（call_overlay_view_audio.xml 里没有这些 View）。
        //   2) 若照旧注入 null，{@code setRendererViews(null, null, null, null)} 会走到
        //      {@code WebRTCManager.setRelayLocalPreviewView(null)}，而它内部是
        //      {@code previous = this.relayLocalPreviewView; this.xxx = null; if (previous == view) return;}
        //      （WebRTCManager.java:884-891）→ 从"非 null 锚点"置 null 时 previous != null != view，
        //      **会执行** {@code detachRelayPreviewSurface()}：置 relayAttachedView = null、
        //      relayVideoRunning = false，并在 relayCamera != null 时 setPreviewTexture(null)（894-904 行）。
        //      之后的 {@code rearmRelayPreview()} 因为 relayLocalPreviewView == null 直接早返回（913 行），
        //      再没有东西会把相机挂回去 —— 这正是 CallOverlayView.destroy 与 CallOverlayController.detach
        //      注释里反复警告的"本地采集永久停、对方看不到你"。
        //   3) 语音通话下 relayCamera 本来就是 null（{@code startRelayVideo()} 只在
        //      {@code startRelayCall/acceptRelayCall} 里 videoEnabled==true 时才调用，
        //      WebRTCManager.java:801 / 812），所以"语音时 setRelayLocalPreviewView(null) 是安全的"
        //      这个判断**成立**。但成立的前提是"当前通话确实是语音"，而 attach 读的
        //      {@code CallOverlayView.videoLayer} 正是同一个 videoEnabled —— 与其依赖这个前提，
        //      不如不调用，等价且不依赖任何前提。
        //      ⚠️ 反向风险也已确认：**视频通话不受影响**，因为视频版走的是老逻辑（原样注入真 View）。
        //   4) 远端帧无需显式解绑：{@code WebRTCManager.setRelayRemoteView} 只是赋值（944-946 行），
        //      而帧注入受 {@code isRelayMode} 门控（1320 行），语音通话下 isRelayMode 恒为 false
        //      （只由 startRelayCall/acceptRelayCall 置位），即便旧锚点仍是通话页的 View 也不会出帧。
        if (view.hasVideoLayer()) {
            //   - SurfaceViewRenderer 两个参数传 null —— 浮层没有这两个 View，relay 模式也不用它们
            //     （relay 模式下 CallActivity.onCallEstablished 已把它们置 GONE）。
            //     WebRTCManager.setRenderers(null, null) 会早返回（不 release、不 re-init），
            //     因此不会影响 relay 通路。
            //   - 本地预览锚点 TextureView / 显示层 ImageView 通过 setRendererViews 一次性注入，
            //     内部对锚点走 setRelayLocalPreviewView（摘旧 surface → 挂新锚点）。
            //     ⚠️ 此刻新 TextureView 刚 addView、尚未走到下一帧 layout，
            //     isAvailable() 很可能还是 false —— 那样会退化为挂 SurfaceTextureListener
            //     等 onSurfaceTextureAvailable，属正常路径（WebRTCManager.attachOrStartPreview:1133+）。
            //   - 远端帧显示层单独注入（setRendererViews 的签名不含它）。
            try {
                CallEngine.setRendererViews(null, null,
                        view.getLocalPreviewView(), view.getLocalPreviewFrame());
                CallEngine.setRelayRemoteView(view.getRemoteVideoView());
            } catch (Exception e) {
                AppLog.e(TAG, "attach: renderer binding failed", e);
            }
        } else {
            AppLog.d(TAG, "attach: audio overlay — skip renderer binding (no video layer)");
        }

        final Models.User peer = readPeer(host, s);
        view.setPeerInfo(peer != null ? peer.nickname : null);
        view.setPeerAvatar(peer != null ? peer.avatar : null);
        view.setCallback(new CallOverlayView.Callback() {
            @Override
            public void onExpand() {
                openCallActivity(host);
            }

            @Override
            public void onHangup() {
                hangup();
            }

            @Override
            public void onMute() {
                // 闭麦已由 CallOverlayView 就地调用 CallEngine.toggleMute()，这里只记日志
                AppLog.d(TAG, "overlay: mute toggled");
            }

            @Override
            public void onLocalMute() {
                // 静音对方已由 CallOverlayView 就地调用 CallEngine.toggleLocalMute()
                AppLog.d(TAG, "overlay: localMute toggled -> " + CallEngine.isLocalMuted());
            }
        });

        synchronized (LOCK) {
            currentView = view;
            currentHost = host;
        }
        AppLog.d(TAG, "attach ok: host=" + host.getClass().getSimpleName()
                + " callId=" + s.callId);
    }

    /**
     * 摘掉浮层（幂等）。
     *
     * <p><b>⚠️ 刻意不反注册渲染目标</b>：不调 {@code setRendererViews} /
     * {@code setRelayRemoteView}，也<b>绝不</b>调 {@code setRelayLocalPreviewView(null)}。
     * 原因（上一轮调查已取证）：{@code setRelayLocalPreviewView(null)} 会走
     * {@code detachRelayPreviewSurface()} 把相机 preview surface 摘掉，而
     * {@code WebRTCManager.rearmRelayPreview()} 在 {@code relayLocalPreviewView == null} 时
     * <b>直接早返回</b>（WebRTCManager.java:913）→ 摘掉之后没有任何东西会重新挂上 →
     * 本地采集永久停 → <b>对方看不到你</b>。
     *
     * <p>渲染目标的切换一律交给"下一个宿主 attach 时的 setRendererViews"。
     */
    public static void detach() {
        final CallOverlayView view;
        final Activity host;
        synchronized (LOCK) {
            view = currentView;
            host = currentHost;
            currentView = null;
            currentHost = null;
        }
        if (view == null) {
            AppLog.d(TAG, "detach: not attached");
            return;
        }

        // 用 view 自己当前的 parent 来摘，而不是缓存的 host 的 content ——
        // 宿主可能已经被系统回收，缓存的 Activity 上再 findViewById 不可靠。
        try {
            final android.view.ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(view);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "detach: removeView failed", e);
        }

        try {
            view.destroy();
        } catch (Exception e) {
            AppLog.e(TAG, "detach: view.destroy failed", e);
        }

        CallEngine.setInAppHostActive(false);
        AppLog.d(TAG, "detach ok: host="
                + (host != null ? host.getClass().getSimpleName() : "?"));
    }

    public static boolean isAttached() {
        synchronized (LOCK) {
            return currentView != null;
        }
    }

    /**
     * 只摘掉<b>属于 {@code host} 的</b>浮层（宿主生命周期专用，与无参 {@link #detach()} 的区别见下）。
     *
     * <p><b>为什么宿主必须用这个而不是无参 detach()</b>：Activity 切换的标准次序是
     * {@code host1.onPause → host2.onStart（此时 attach 已把浮层迁到 host2）→ host1.onStop}。
     * 若 host1.onStop 调无参 {@code detach()}，会把<b>刚刚迁到 host2 的浮层</b>摘掉 ——
     * 结果是"App 内切页后浮层消失"。
     */
    public static void detach(Activity host) {
        if (host == null) return;
        synchronized (LOCK) {
            if (currentView == null || currentHost != host) {
                AppLog.d(TAG, "detach(host): current host is not this one — skip");
                return;
            }
        }
        detach();
    }

    /**
     * 宿主 onStop 时调用：延迟判断是"App 内切页"还是"切到别的 App"，后者才显示系统浮窗。
     *
     * <p>为什么需要"延迟"：Activity 切换时新宿主的 onStart 早于旧宿主的 onStop，
     * 但两者之间没有同步点；用一个短延迟（{@value #SYSTEM_OVERLAY_DELAY_MS} ms）等接管完成，
     * 再看 {@link #isAttached()} 是否已为 true。
     *
     * <p>三个跳过条件（任一成立都不显示系统浮窗）：
     * <ol>
     *   <li>{@link #isAttached()} —— 有新宿主接管（App 内切页）；</li>
     *   <li>{@link #callPageVisible} —— 通话页正全屏显示通话，再弹浮窗是重复 UI
     *       （这条是必需的：通话页不是应用内宿主，光看 isAttached() 会误判成"切到别的 App"）；</li>
     *   <li>引擎已不存在 —— 通话已结束。</li>
     * </ol>
     *
     * <p>对端资料在这里预置（从 {@code friend_<peerId>} 缓存现读），因为
     * {@code CallFloatingWindow} 拿不到 {@code CallActivity.remoteUser}。
     */
    public static void onHostStopped(Activity host) {
        if (host == null) return;
        final android.content.Context appCtx = host.getApplicationContext();

        // 预置系统浮窗的对端资料（从 prefs 现读，不依赖 CallActivity 字段）
        final CallSession s = CallManager.getCurrentCall();
        if (s != null) {
            final Models.User peer = readPeer(host, s);
            CallFloatingWindow.setPeerInfo(
                    peer != null ? peer.nickname : null,
                    peer != null ? peer.avatar : null);
        }

        MAIN.postDelayed(() -> {
            if (isAttached()) {
                // 有新宿主接管（App 内切页）：不显示系统浮窗
                AppLog.d(TAG, "onHostStopped: new host attached — skip system overlay");
                return;
            }
            if (callPageVisible) {
                // 通话页正全屏显示通话：不显示系统浮窗（否则是重复 UI）
                AppLog.d(TAG, "onHostStopped: call page visible — skip system overlay");
                return;
            }
            if (!CallEngine.hasEngine()) {
                // 通话已结束
                return;
            }
            // 切到别的 App：显示系统浮窗
            AppLog.d(TAG, "onHostStopped: no in-app host — show system overlay");
            CallFloatingWindow.show(appCtx);
        }, SYSTEM_OVERLAY_DELAY_MS);
    }

    /** 通话页可见性置位（由 CallActivity.onStart/onStop 调用），见 {@link #callPageVisible}。 */
    public static void setCallPageVisible(boolean visible) {
        callPageVisible = visible;
        AppLog.d(TAG, "setCallPageVisible: " + visible);
    }

    // =================================================================
    //  回调实现
    // =================================================================

    /**
     * 放大回通话页。extras 与 {@code CallFloatingWindow.openCallActivity}
     * （CallFloatingWindow.java:334-356）和 {@code CallService.buildContentIntent}
     * （CallService.java:199-222）同款 —— 目的是让 CallActivity.onCreate 能认领
     * CallManager 里已存在的会话（adopt existing session 分支），而不是重开一通。
     *
     * <p>本方法<b>不</b>主动 detach：CallActivity 起来之后宿主会 onStop，由宿主调 detach()。
     */
    private static void openCallActivity(Activity host) {
        try {
            final Intent it = new Intent();
            it.setClassName(host, Routes.CALL_ACTIVITY);
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
            host.startActivity(it);
            AppLog.d(TAG, "openCallActivity ok");
        } catch (Exception e) {
            AppLog.e(TAG, "openCallActivity failed", e);
        }
    }

    /**
     * 从浮层挂断。与 {@code CallFloatingWindow} 的挂断按钮同款
     * （CallFloatingWindow.java:236-246）：信令+系统消息归 {@code CallManager.endCall}，
     * 媒体+前台服务+系统浮窗归 {@code CallEngine.end}，最后自己收摊。
     */
    private static void hangup() {
        AppLog.d(TAG, "hangup tapped from in-app overlay");
        final CallSession s = CallManager.getCurrentCall();
        if (s != null) {
            CallManager.endCall(s.callId);
        } else {
            AppLog.e(TAG, "hangup: no current call (CallManager already idle)");
        }
        CallEngine.end();
        detach();
    }

    // =================================================================
    //  工具
    // =================================================================

    /**
     * 取对端资料（昵称 + 头像）。
     *
     * <p>{@code CallSession} 不带昵称/头像，这里读 {@code friend_<peerId>} 缓存 ——
     * 与 {@code CallManager.launchIncomingUi}（CallManager.java:674-688）用的是同一份缓存、
     * 同一套 Gson 解析。缓存里没有（从没聊过的好友）时返回 null，调用方各自保留占位文案。
     *
     * <p>Context 直接用传入的宿主：{@code PreferencesManager.getInstance} 内部会取
     * applicationContext，不会因此泄漏 Activity。
     */
    private static Models.User readPeer(Activity host, CallSession s) {
        try {
            final PreferencesManager prefs = PreferencesManager.getInstance(host);
            if (prefs == null) return null;
            final String cached = prefs.getString("friend_" + s.peerId(), null);
            if (cached == null || cached.isEmpty()) return null;
            return new Gson().fromJson(cached, Models.User.class);
        } catch (Exception e) {
            AppLog.e(TAG, "readPeer failed", e);
            return null;
        }
    }
}
