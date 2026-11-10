package com.familychat.app.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 通知栏的「原语」层：建渠道、发一条通知、撤一条通知。
 *
 * <h3>为什么在 app/notify/ 而不是 utils/</h3>
 * 本类原来躺在 app/utils/ 里，却 import 了 :feature-chat 的 {@code ChatActivity}
 * 与 :feature-group 的 {@code GroupChatActivity}——只为构造「点击通知跳哪个页面」的
 * {@link PendingIntent}。那是 UI 知识，不该长在通用工具里。
 * 现在本类<b>不认识任何 Activity</b>：跳转目标由调用方传 {@link Intent} 进来
 * （构造者是 :app 的 {@code NotificationDispatcher}，它本就允许认识 UI）。
 *
 * <h3>职责边界</h3>
 * 本类<b>只负责"怎么发"</b>，不负责"该不该发"：未读计数、免打扰、正在聊天不打扰、
 * 系统消息不弹、进程级监听注册等等，全部在 {@code NotificationDispatcher}。
 * 任何"在这里加判断"的改动都是放错了地方。
 *
 * <h3>提示音：单路，不叠加（改这里之前必读）</h3>
 * 历史形态是三路同时生效，一条消息响两次：
 * <ol>
 *   <li>渠道 {@code familychat_msg}（IMPORTANCE_HIGH + setSound）—— Android 8.0+ 的<b>唯一</b>生效路径；</li>
 *   <li>builder {@code setSound + setDefaults} —— 8.0 以下（无渠道）的生效路径；</li>
 *   <li>手动 {@code MediaPlayer} / {@code Ringtone} / {@code Vibrator} —— 与上面两路叠加，就是那第二声。</li>
 * </ol>
 * 现在<b>只剩下 ①（API 26+）与 ②（API 24/25）二选一</b>，第 ③ 路已整体删除。
 * 振动同理：26+ 由渠道 {@code enableVibration} 负责，24/25 由 {@code DEFAULT_VIBRATE} 负责，
 * 不再手动振一次。
 *
 * <p><b>渠道参数不可改（系统限制）</b>：渠道一旦创建，其重要性/声音的代码声明就不再影响
 * 已安装设备——Android 会沿用用户设备上已保存的设置。因此本类<b>不动渠道 id</b>
 * （仍是 {@code familychat_msg}）、<b>不动</b>渠道的声音声明；若某台设备"没声音"是因为
 * 用户自己关掉了该渠道声音，代码层面修不了，只能引导用户去系统设置（见 MainActivity 的引导弹窗）。
 * 往 {@code createChannel} 里加"删了重建同名渠道"之类的动作会重置用户设置，属禁止项。
 *
 * <h3>通知 id 与 PendingIntent requestCode</h3>
 * <ul>
 *   <li>好友通知 id = friendId；群通知 id = 100000 + groupId（由调用方算好传入）。</li>
 *   <li>requestCode <b>必须等于 notificationId</b>：PendingIntent 的相等性不看 extras，
 *       历史上两个 show* 都硬编码 requestCode=0，导致两个不同好友的通知共用同一个
 *       PendingIntent，后一条 FLAG_UPDATE_CURRENT 覆盖 extras——点第 1 条会进第 2 个会话。</li>
 * </ul>
 *
 * <h3>线程</h3>
 * 无状态静态方法，任意线程可调（调用方在后台线程调用，避免主线程做解密/prefs 读）。
 */
public class NotificationHelper {

    // Message notification channel — MUST be IMPORTANCE_HIGH for sound
    private static final String CHANNEL_ID = "familychat_msg";
    // Foreground service channel — IMPORTANCE_LOW (no sound)
    public static final String FG_CHANNEL_ID = "familychat_fg";

    /** 无渠道（API 24/25）时 builder 侧的提示音/振动开关阈值。 */
    private static final int NO_CHANNEL_MAX_SDK = 25;

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;

            // Delete old channels with wrong importance
            try { nm.deleteNotificationChannel("familychat_messages"); } catch (Exception ignored) {}
            try { nm.deleteNotificationChannel("familychat_messages_v2"); } catch (Exception ignored) {}

            // ── 消息渠道：声音在这里声明。id 与参数一律不得改动（见类注释「渠道参数不可改」）──
            NotificationChannel msgCh = new NotificationChannel(
                    CHANNEL_ID, "消息通知", NotificationManager.IMPORTANCE_HIGH);
            msgCh.setDescription("新消息通知（含提示音）");
            msgCh.enableVibration(true);
            msgCh.enableLights(true);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            msgCh.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs);
            nm.createNotificationChannel(msgCh);
        }
    }

    /**
     * 好友新消息通知。
     *
     * @param contentIntent 点击后跳转的 Intent（由调用方构造：目标 Activity + friend_id/friend_name；
     *                      本类不认识任何 UI 类，故必须外部传入）
     * @param title         标题（好友昵称；查不到时由调用方给兜底文案）
     * @param preview       正文预览
     * @param notificationId 通知 id，同时用作 PendingIntent 的 requestCode（防多条通知互相覆盖）
     */
    public static void showMessageNotification(Context context, Intent contentIntent,
                                                String title, String preview, int notificationId) {
        PendingIntent pi = PendingIntent.getActivity(context, notificationId, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(preview)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);

        applySoundForLegacyBuilder(builder);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId, builder.build());
    }

    /**
     * 群新消息通知（免打扰群不调用本方法 —— 是否免打扰由调用方判断）。
     *
     * @param contentIntent  点击后跳转的 Intent（目标 Activity + group_id/group_name）
     * @param title          标题（群名；查不到时由调用方给兜底文案）
     * @param text           正文（发送者名 + 预览由调用方拼好）
     * @param notificationId 通知 id = 100000 + groupId，同时用作 requestCode
     */
    public static void showGroupNotification(Context context, Intent contentIntent,
                                             String title, String text, int notificationId) {
        try {
            PendingIntent pi = PendingIntent.getActivity(context, notificationId, contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi);

            applySoundForLegacyBuilder(builder);

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(notificationId, builder.build());
        } catch (Exception ignored) {}
    }

    /**
     * 提示音/振动的<b>唯一</b> builder 侧落点：只在 API 24/25（没有渠道）时生效。
     *
     * <p>API 26+ 一律由渠道 {@code familychat_msg} 决定声音与振动，builder 上的
     * {@code setSound/setDefaults} 会被系统忽略——但为了不留下"看起来还在设置声音"的
     * 误导代码，这里用版本判断把它们真正排除掉（<b>不能改成无条件设置</b>：那正是
     * 历史上"渠道 + builder + MediaPlayer"三路叠加、一条消息响两次的成因之一）。
     */
    private static void applySoundForLegacyBuilder(NotificationCompat.Builder builder) {
        if (Build.VERSION.SDK_INT > NO_CHANNEL_MAX_SDK) return;
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(soundUri)
               .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
    }

    public static void cancelGroupNotification(Context context, int groupId) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(100000 + groupId);
    }

    public static void cancelNotification(Context context, int friendId) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(friendId);
    }
}
