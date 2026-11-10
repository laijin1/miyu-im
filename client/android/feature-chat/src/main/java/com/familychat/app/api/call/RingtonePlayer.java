package com.familychat.app.api.call;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.familychat.app.feature_chat.R;
import com.familychat.app.utils.AppLog;

/**
 * 循环来电铃声播放器（静态门面）。
 *
 * <h3>为什么存在</h3>
 * 通话在此之前<b>完全没有铃声</b>：状态机（{@link CallManager}）会进
 * {@code INCOMING_RINGING} / {@code OUTGOING_RINGING}，但没有任何声音提示。
 * 本类只负责"把铃声放出来"，不判断"该不该响" —— 后者是
 * {@link CallEngine.CallStateObserver} 的职责（它订阅 {@link CallManager.Observer}）。
 *
 * <h3>与媒体引擎的关系：刻意解耦</h3>
 * 本类<b>不持有</b> {@code WebRTCManager}，也不参与通话媒体：铃声是本地提示音，
 * 与对端音频流无关。因此通话铃响期间引擎可以还没起来（外呼刚 startCall、
 * 来电还没接），本类照样能响。
 *
 * <h3>线程约定</h3>
 * {@code MediaPlayer} <b>非线程安全</b>，且 {@code prepare()/start()/stop()/release()}
 * 都可能在主线程产生阻塞或回调。因此本类的三个公开入口
 * （{@link #start}/{@link #stop}/{@link #isPlaying}）<b>一律 MAIN.post 到主线程执行</b>，
 * 调用方在任意线程调用都是安全的（与 {@link CallEngine} 的 MAIN.post 同款）。
 *
 * <p>幂等性是这个类的核心不变量：{@code CallManager} 的观察者回调可能对同一状态
 * 重复触发（{@code transition()} 在 {@code from == to} 时提前返回，但
 * {@code teardown()} 会额外发一次 IDLE），{@link #start} 必须能承受"已经在响又要响"，
 * {@link #stop} 必须能承受"根本没响就要停"。
 *
 * <h3>与通知音的关系</h3>
 * 本类与消息提示音<b>无关</b>：消息通知音由 {@code NotificationHelper} 的
 * 渠道声明（{@code familychat_msg}）决定，本类既不读也不写它。
 * 历史上"渠道 + builder + 手动 MediaPlayer"三路叠加导致一条消息响两次
 * （见 NotificationHelper.java:30-39），因此本类<b>只服务通话响铃</b>，
 * 绝不能被消息路径调用。
 */
public final class RingtonePlayer {

    private static final String TAG = "RingtonePlayer";

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 当前铃声播放器。null = 未在响铃（或已 release）。只在主线程读写。 */
    private static MediaPlayer player;

    private RingtonePlayer() {
    }

    /**
     * 起铃（幂等：已在响则什么都不做，不重建、不叠加）。
     *
     * <p>用 {@code openRawResourceFd} + {@code setDataSource(AssetFileDescriptor)} 而不是
     * 字符串路径：{@code res/raw} 里的文件没有可用的文件系统路径，
     * 传路径字符串必然 {@code IOException}。
     *
     * @param ctx 任意 Context（内部立即取 applicationContext，不持有 Activity）
     */
    public static void start(final Context ctx) {
        if (ctx == null) {
            AppLog.e(TAG, "start skipped: ctx == null");
            return;
        }
        final Context appCtx;
        try {
            appCtx = ctx.getApplicationContext();
        } catch (Exception e) {
            AppLog.e(TAG, "start skipped: getApplicationContext failed", e);
            return;
        }
        if (appCtx == null) {
            AppLog.e(TAG, "start skipped: applicationContext == null");
            return;
        }
        MAIN.post(() -> startOnMain(appCtx));
    }

    /** 主线程实际起铃。幂等由 {@link #player} 非空短路保证。 */
    private static void startOnMain(Context ctx) {
        if (player != null) {
            AppLog.d(TAG, "start ignored: already ringing");
            return;
        }
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setLooping(true);

            try (AssetFileDescriptor afd =
                         ctx.getResources().openRawResourceFd(R.raw.call_ringtone)) {
                if (afd == null) {
                    AppLog.e(TAG, "start failed: openRawResourceFd returned null");
                    mp.release();
                    return;
                }
                mp.setDataSource(afd.getFileDescriptor(),
                        afd.getStartOffset(), afd.getLength());
            }

            mp.prepare();
            mp.start();
            player = mp;
            AppLog.d(TAG, "ringing started (looping)");
        } catch (Throwable t) {
            // 起铃失败绝不能影响通话本身：提示音是可选增强，通话是主功能。
            AppLog.e(TAG, "start failed", t);
            releaseQuietly(mp);
            player = null;
        }
    }

    /**
     * 停铃（幂等：没在响时什么都不做）。
     *
     * <p>{@code stop()} + {@code release()} 都做：只 release 不 stop 在某些设备上
     * 会让最后一小段音频继续播完（{@code release()} 不保证立即静音）。
     */
    public static void stop() {
        MAIN.post(RingtonePlayer::stopOnMain);
    }

    /** 主线程实际停铃。 */
    private static void stopOnMain() {
        final MediaPlayer mp = player;
        if (mp == null) {
            AppLog.d(TAG, "stop ignored: not ringing");
            return;
        }
        player = null;
        try {
            if (mp.isPlaying()) {
                mp.stop();
            }
        } catch (Throwable t) {
            AppLog.e(TAG, "stop: stop() failed", t);
        }
        releaseQuietly(mp);
        AppLog.d(TAG, "ringing stopped");
    }

    /**
     * 是否正在响铃。
     *
     * <p>读的是主线程 {@link #player} 字段 —— 为非主线程调用提供 volatile 语义之外的
     * 最简一致性；本方法返回的是"最近一次主线程操作后的状态"，不保证瞬时精确。
     * 只用于日志与断言，<b>不要</b>用它做业务判断。
     */
    public static boolean isPlaying() {
        synchronized (LOCK) {
            final MediaPlayer mp = player;
            if (mp == null) return false;
            try {
                return mp.isPlaying();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static void releaseQuietly(MediaPlayer mp) {
        if (mp == null) return;
        try {
            mp.release();
        } catch (Throwable t) {
            AppLog.e(TAG, "release failed", t);
        }
    }
}
