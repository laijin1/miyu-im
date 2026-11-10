package com.familychat.app.api.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.familychat.app.utils.AppLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程级通话音频路由（静态门面）。
 *
 * <h3>为什么存在</h3>
 * 改造前音频路由散在 {@code CallActivity} 的三处（进入通话页的 {@code setSpeakerOn(true)}、
 * {@code onCallEstablished} 的 {@code setMode}、{@code cleanup} 的恢复），
 * 且<b>只有"强制扬声器"一条路径</b>：全仓没有任何设备枚举、没有蓝牙 SCO、没有通信设备选择，
 * 用户无法切换，蓝牙耳机也无从接入。本类把路由收敛到一处，并提供设备选择能力。
 *
 * <h3>两套 API（minSdk 24 / targetSdk 34）</h3>
 * <ul>
 *   <li><b>API 31+</b>：{@code getAvailableCommunicationDevices()} +
 *       {@code setCommunicationDevice()} / {@code getCommunicationDevice()} /
 *       {@code clearCommunicationDevice()}。</li>
 *   <li><b>API 24-30</b>：{@code startBluetoothSco()} / {@code setBluetoothScoOn()} /
 *       {@code setSpeakerphoneOn()} / {@code isBluetoothScoOn()} /
 *       {@code isWiredHeadsetOn()}。</li>
 * </ul>
 * 这些成员的最低 API 级别取自本机 android-34 SDK 的 {@code api-versions.xml}
 * （{@code setCommunicationDevice} / {@code getAvailableCommunicationDevices} /
 * {@code clearCommunicationDevice} 均为 {@code since="31"}）。
 *
 * <h3>线程与注册约定</h3>
 * 仿 {@code CallManager} / {@code CallEngine}：全静态、单把 {@link #LOCK}、
 * 观察者通知一律 {@link #MAIN}.post。SCO 状态广播在 {@link #init} 时注册、
 * {@link #release} 时反注册；{@code Android 13+（API 33）} 起 {@code registerReceiver}
 * 必须显式给出导出标志，这里用 {@code RECEIVER_NOT_EXPORTED}。
 */
public final class AudioRouter {

    private static final String TAG = "AudioRouter";

    /** 通话音频出口。 */
    public enum Route {
        EARPIECE,
        SPEAKER,
        BLUETOOTH,
        WIRED_HEADSET
    }

    /** 路由变化回调（主线程）。 */
    public interface Observer {
        void onRouteChanged(Route route);
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();

    private static volatile Context appCtx;
    private static AudioManager audioManager;
    private static boolean scoReceiverRegistered;
    /** 最近一次已知路由。init 前为 SPEAKER（与改造前"进通话即扬声器"一致）。 */
    private static Route currentRoute = Route.SPEAKER;

    /**
     * 蓝牙 SCO 状态广播接收器。
     *
     * <p>API 24-30 下 SCO 链路是<b>异步</b>建立的：{@code startBluetoothSco()} 只是发起，
     * 真正接通要等这条广播。因此路由变化必须由广播驱动重算，不能只信 {@code setRoute()} 的入参。
     */
    private static final BroadcastReceiver SCO_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            final int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR);
            AppLog.d(TAG, "SCO state changed -> " + state);
            refreshFromSystem();
        }
    };

    private AudioRouter() {
    }

    // =================================================================
    //  生命周期
    // =================================================================

    /**
     * 通话开始：取 AudioManager、注册 SCO 广播、进入 MODE_IN_COMMUNICATION 并默认扬声器。
     *
     * <p>默认扬声器是<b>刻意保留改造前的行为</b>（原 {@code CallActivity} 在进入通话页时
     * 无条件 {@code setSpeakerOn(true)}）；用户随后可通过设备选择器改路由。
     */
    public static void init(Context ctx) {
        final AudioManager am;
        synchronized (LOCK) {
            if (ctx != null && appCtx == null) {
                try {
                    appCtx = ctx.getApplicationContext();
                } catch (Exception e) {
                    AppLog.e(TAG, "init: getApplicationContext failed", e);
                }
            }
            if (appCtx == null) {
                AppLog.e(TAG, "init skipped: no context");
                return;
            }
            am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            audioManager = am;
        }
        if (am == null) {
            AppLog.e(TAG, "init: AudioManager unavailable");
            return;
        }
        registerScoReceiver();
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            AppLog.e(TAG, "init: setMode(MODE_IN_COMMUNICATION) failed", e);
        }
        setRoute(Route.SPEAKER);
        AppLog.d(TAG, "init done, route=" + getCurrentRoute());
    }

    /** 通话结束：反注册广播并恢复系统音频模式（与改造前 cleanup 的恢复一致）。 */
    public static void release() {
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        unregisterScoReceiver();
        if (am != null) {
            try {
                // API 31+：把通信设备释放掉，否则会一直钉在通话设备上
                if (Build.VERSION.SDK_INT >= 31) {
                    am.clearCommunicationDevice();
                }
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception e) {
                AppLog.e(TAG, "release: restore audio mode failed", e);
            }
        }
        synchronized (LOCK) {
            audioManager = null;
            currentRoute = Route.SPEAKER;
        }
        AppLog.d(TAG, "release done");
    }

    // =================================================================
    //  查询
    // =================================================================

    /** 当前实际路由（每次现算，不信任缓存）。 */
    public static Route getCurrentRoute() {
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        if (am == null) {
            synchronized (LOCK) {
                return currentRoute;
            }
        }
        final Route detected = detectRoute(am);
        synchronized (LOCK) {
            currentRoute = detected;
        }
        return detected;
    }

    /** 有线耳机是否已连接（用 {@code getDevices(GET_DEVICES_OUTPUTS)}，API 23+ 通用）。 */
    public static boolean isWiredHeadsetConnected() {
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        if (am == null) return false;
        return hasOutputDeviceOfType(am,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
    }

    /**
     * 蓝牙音频设备是否已连接。
     *
     * <p>同时接受 {@code TYPE_BLUETOOTH_SCO}（通话链路已激活）与
     * {@code TYPE_BLUETOOTH_A2DP}（蓝牙音频设备已连接但通话链路未起）。
     * 两者任一都说明"用户有蓝牙耳机可用"，因此都算可选。
     */
    public static boolean isBluetoothConnected() {
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        if (am == null) return false;
        return hasOutputDeviceOfType(am,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
    }

    // =================================================================
    //  设置
    // =================================================================

    /** 切换路由。失败（设备不可用/平台拒绝）只打日志并保持原路由，不抛异常。 */
    public static void setRoute(Route route) {
        if (route == null) return;
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        if (am == null) {
            AppLog.e(TAG, "setRoute(" + route + ") ignored: AudioRouter not initialized");
            return;
        }
        final boolean applied = Build.VERSION.SDK_INT >= 31
                ? applyRouteApi31(am, route)
                : applyRouteLegacy(am, route);
        AppLog.d(TAG, "setRoute(" + route + ") applied=" + applied);
        if (!applied) return;
        synchronized (LOCK) {
            currentRoute = route;
        }
        notifyRouteChanged(route);
    }

    // =================================================================
    //  订阅
    // =================================================================

    public static void addObserver(Observer o) {
        if (o != null) OBSERVERS.addIfAbsent(o);
    }

    public static void removeObserver(Observer o) {
        if (o != null) OBSERVERS.remove(o);
    }

    // =================================================================
    //  内部：平台分支
    // =================================================================

    /** API 31+：在可用通信设备里挑一个类型匹配的，交给 {@code setCommunicationDevice}。 */
    private static boolean applyRouteApi31(AudioManager am, Route route) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            final int[] wanted = deviceTypesOf(route);
            final List<AudioDeviceInfo> devices = am.getAvailableCommunicationDevices();
            if (devices == null || devices.isEmpty()) {
                AppLog.e(TAG, "applyRouteApi31(" + route + "): no available communication devices");
                return false;
            }
            for (AudioDeviceInfo d : devices) {
                for (int t : wanted) {
                    if (d.getType() == t) {
                        final boolean ok = am.setCommunicationDevice(d);
                        AppLog.d(TAG, "setCommunicationDevice(type=" + d.getType() + ") -> " + ok);
                        return ok;
                    }
                }
            }
            AppLog.e(TAG, "applyRouteApi31(" + route + "): no matching device among "
                    + devices.size() + " available");
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "applyRouteApi31(" + route + ") failed", e);
            return false;
        }
    }

    /** API 24-30：SCO + speakerphone 老路径。 */
    private static boolean applyRouteLegacy(AudioManager am, Route route) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            switch (route) {
                case BLUETOOTH:
                    // 先起 SCO 再置位；真正的接通由 ACTION_SCO_AUDIO_STATE_CHANGED 通知
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                    am.setSpeakerphoneOn(false);
                    return true;
                case SPEAKER:
                    stopScoQuietly(am);
                    am.setSpeakerphoneOn(true);
                    return true;
                case WIRED_HEADSET:
                    // 有线耳机由平台自动优先路由，这里只需把扬声器/SCO 让开
                    stopScoQuietly(am);
                    am.setSpeakerphoneOn(false);
                    return true;
                case EARPIECE:
                default:
                    stopScoQuietly(am);
                    am.setSpeakerphoneOn(false);
                    return true;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "applyRouteLegacy(" + route + ") failed", e);
            return false;
        }
    }

    private static void stopScoQuietly(AudioManager am) {
        try {
            am.setBluetoothScoOn(false);
        } catch (Exception e) {
            AppLog.e(TAG, "setBluetoothScoOn(false) failed", e);
        }
        try {
            am.stopBluetoothSco();
        } catch (Exception e) {
            AppLog.e(TAG, "stopBluetoothSco() failed", e);
        }
    }

    /** 路由 → 候选设备类型（API 31+ 用；顺序即优先级）。 */
    private static int[] deviceTypesOf(Route route) {
        switch (route) {
            case EARPIECE:
                return new int[]{AudioDeviceInfo.TYPE_BUILTIN_EARPIECE};
            case SPEAKER:
                return new int[]{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};
            case BLUETOOTH:
                return new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_SCO};
            case WIRED_HEADSET:
                return new int[]{
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES};
            default:
                return new int[]{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};
        }
    }

    /** 设备类型 → 路由；未知类型返回 null。 */
    private static Route routeOfDeviceType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return Route.BLUETOOTH;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return Route.WIRED_HEADSET;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return Route.EARPIECE;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return Route.SPEAKER;
            default:
                return null;
        }
    }

    /** 现算当前路由。 */
    private static Route detectRoute(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                final AudioDeviceInfo d = am.getCommunicationDevice();
                if (d != null) {
                    final Route r = routeOfDeviceType(d.getType());
                    if (r != null) return r;
                }
                // 未设置通信设备时，MODE_IN_COMMUNICATION 下平台默认走扬声器
                return Route.SPEAKER;
            }
            if (am.isBluetoothScoOn()) return Route.BLUETOOTH;
            if (am.isWiredHeadsetOn()) return Route.WIRED_HEADSET;
            if (am.isSpeakerphoneOn()) return Route.SPEAKER;
            return Route.EARPIECE;
        } catch (Exception e) {
            AppLog.e(TAG, "detectRoute failed", e);
            return Route.SPEAKER;
        }
    }

    private static boolean hasOutputDeviceOfType(AudioManager am, int... types) {
        try {
            final AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null) return false;
            for (AudioDeviceInfo d : devices) {
                for (int t : types) {
                    if (d.getType() == t) return true;
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "hasOutputDeviceOfType failed", e);
        }
        return false;
    }

    // =================================================================
    //  内部：广播 / 通知
    // =================================================================

    private static void registerScoReceiver() {
        final Context ctx = appCtx;
        if (ctx == null) return;
        synchronized (LOCK) {
            if (scoReceiverRegistered) return;
            scoReceiverRegistered = true;
        }
        try {
            final IntentFilter filter =
                    new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ 起必须显式声明导出标志
                ctx.registerReceiver(SCO_RECEIVER, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(SCO_RECEIVER, filter);
            }
            AppLog.d(TAG, "SCO receiver registered");
        } catch (Exception e) {
            synchronized (LOCK) {
                scoReceiverRegistered = false;
            }
            AppLog.e(TAG, "registerScoReceiver failed", e);
        }
    }

    private static void unregisterScoReceiver() {
        final Context ctx = appCtx;
        synchronized (LOCK) {
            if (!scoReceiverRegistered) return;
            scoReceiverRegistered = false;
        }
        if (ctx == null) return;
        try {
            ctx.unregisterReceiver(SCO_RECEIVER);
            AppLog.d(TAG, "SCO receiver unregistered");
        } catch (Exception e) {
            AppLog.e(TAG, "unregisterScoReceiver failed", e);
        }
    }

    /** 系统侧状态变化（SCO 广播）：重算路由，变了才通知。 */
    private static void refreshFromSystem() {
        final AudioManager am;
        synchronized (LOCK) {
            am = audioManager;
        }
        if (am == null) return;
        final Route detected = detectRoute(am);
        final Route previous;
        synchronized (LOCK) {
            previous = currentRoute;
            currentRoute = detected;
        }
        AppLog.d(TAG, "refreshFromSystem: " + previous + " -> " + detected);
        if (detected != previous) notifyRouteChanged(detected);
    }

    private static void notifyRouteChanged(Route route) {
        MAIN.post(() -> {
            // 快照遍历：观察者在回调里 removeObserver 不能破坏遍历
            for (Observer o : new CopyOnWriteArrayList<>(OBSERVERS)) {
                try {
                    o.onRouteChanged(route);
                } catch (Throwable t) {
                    AppLog.e(TAG, "observer.onRouteChanged failed", t);
                }
            }
        });
    }
}
