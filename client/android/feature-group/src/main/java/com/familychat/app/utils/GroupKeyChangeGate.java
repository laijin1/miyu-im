package com.familychat.app.utils;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 群消息 / 群公告发送前的 TOFU「成员密钥已变更」汇总闸门。
 *
 * <p>背景（{@code docs/audits/E2EE_SECURITY_AUDIT.md} §1.1 Critical / §1.2 High）：群消息是"每人一份密文"，
 * 每个成员的公钥各自被 {@link PeerKeyPinStore} pin 住。某个成员的公钥与本地 pin 不一致时，
 * 既不能用它加密（那把钥匙可能是攻击者的），也不能覆盖本地缓存（那会擦掉唯一的检测信号）。
 * {@link GroupEncryptHelper} 会把这些成员跳过并记进
 * {@link GroupEncryptHelper.EncryptResult#changedMembers}，本类负责把这件事**告诉用户**，
 * 并让用户在"照发（这些人收不到）/ 信任新密钥（重加密后所有人收到）/ 取消"之间做明确选择。
 *
 * <p>本类从 {@code GroupChatActivity} 内联实现原样抽出（第三轮 A），供群聊与群公告共用，
 * 避免同一套告警在多处各写一遍、再各自漂移。
 *
 * <p><b>线程模型</b>：必须在<b>后台线程</b>调用。内部用
 * {@link Handler}{@code (Looper.getMainLooper())} 切主线程弹框，并用 {@link CountDownLatch}
 * 阻塞等待（上限 120 秒）。这样"确认信任"之后，调用方能在<b>同一次发送</b>里完成重新加密 ——
 * 用户不会觉得"点了信任还得再把话打一遍"。取消 / 弹框失败 / 超时一律按"取消发送"处理（fail-closed）。
 */
public final class GroupKeyChangeGate {

    private static final String TAG = "GroupKeyChangeGate";

    /** 用户选择：照常发送（变更成员收不到这条）。 */
    public static final int KEY_CHANGE_CONTINUE = 0;
    /** 用户选择：信任新密钥（逐个 acceptChange 后重新加密）。 */
    public static final int KEY_CHANGE_TRUST = 1;
    /** 用户选择：取消发送（默认值；取消/超时/弹框失败都落在这里）。 */
    public static final int KEY_CHANGE_CANCEL = 2;

    /** 加密函数。必须可以<b>重复调用</b> —— "信任新密钥"之后要用它重新加密一次。 */
    public interface EncryptFn {
        GroupEncryptHelper.EncryptResult encrypt(String plain);
    }

    /** 成员显示名解析。返回空/null 时回退为 {@code "用户"+uid}。 */
    public interface NameResolver {
        String nameOf(int uid);
    }

    private GroupKeyChangeGate() {
    }

    /**
     * 群发送前的 TOFU 汇总闸门。
     *
     * <ol>
     *   <li>调 {@code encryptFn} 生成密文；没有成员密钥变更时直接返回（绝大多数情况，零 UI）；</li>
     *   <li>有成员密钥变更时弹框让用户选择；</li>
     *   <li>「取消」→ 返回 {@code null}（调用方必须取消发送）；</li>
     *   <li>「信任新密钥」→ 逐个 {@link PeerKeyPinStore#acceptChange} + 对齐
     *       {@code peer_pub_} 缓存，然后<b>重新加密</b>并返回新结果；</li>
     *   <li>「继续发送」→ 原样返回（变更成员已在 {@code skippedMembers} 里，收不到这条）。</li>
     * </ol>
     *
     * @param activity     用于弹框与生命周期判断（{@code isFinishing/isDestroyed}）
     * @param prefs        "信任新密钥"时用来把网络缓存与 pin 重新对齐
     * @param encryptFn    加密函数（见 {@link EncryptFn}）
     * @param nameResolver 成员显示名解析（见 {@link NameResolver}）
     * @param plain        待加密明文
     * @return 可以继续发送的加密结果；用户取消（或弹框失败）时返回 {@code null}
     */
    public static GroupEncryptHelper.EncryptResult encryptWithGate(
            Activity activity, PreferencesManager prefs,
            EncryptFn encryptFn, NameResolver nameResolver, String plain) {
        GroupEncryptHelper.EncryptResult er = encryptFn.encrypt(plain);
        if (er == null || er.json == null || !er.hasChangedMembers()) return er;

        int choice = askAboutChangedMembers(activity, er, nameResolver);
        if (choice == KEY_CHANGE_CANCEL) {
            Log.w(TAG, "send cancelled: " + er.changedMembers.size() + " member key(s) changed");
            return null;
        }
        if (choice == KEY_CHANGE_TRUST) {
            for (Map.Entry<Integer, String> e : er.changedMemberKeys.entrySet()) {
                PeerKeyPinStore.acceptChange(e.getKey(), e.getValue());
                prefs.savePeerPublicKey(e.getKey(), e.getValue()); // 缓存与 pin 重新对齐
            }
            Log.w(TAG, "user trusted " + er.changedMemberKeys.size() + " changed member key(s), re-encrypting");
            er = encryptFn.encrypt(plain);
        }
        return er;
    }

    /**
     * 成员密钥变更告警对话框。与单聊 {@code ChatActivity.askUserToTrustChangedKey} 同款交互
     * （等宽指纹 + 强确认），差别是这里一次列出多个成员。
     *
     * <p>必须在后台线程调用：内部切主线程弹框，用 {@link CountDownLatch} 等用户选择；
     * 取消 / 超时一律按"取消发送"处理（fail-closed）。
     *
     * @return {@link #KEY_CHANGE_CONTINUE} / {@link #KEY_CHANGE_TRUST} / {@link #KEY_CHANGE_CANCEL}
     */
    private static int askAboutChangedMembers(Activity activity,
                                              GroupEncryptHelper.EncryptResult er,
                                              NameResolver nameResolver) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return KEY_CHANGE_CANCEL;
        }
        final String detail = changedMemberDetail(er, nameResolver);
        final int n = er.changedMembers.size();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger choice = new AtomicInteger(KEY_CHANGE_CANCEL);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // post 到主线程期间页面可能已经关了（用户返回/切换），别把对话框泄漏到已销毁的窗口上
                if (activity.isFinishing() || activity.isDestroyed()) {
                    latch.countDown();
                    return;
                }
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                final float density = activity.getResources().getDisplayMetrics().density;
                int pad = (int) (density * 20);
                box.setPadding(pad, pad / 2, pad, 0);

                TextView body = new TextView(activity);
                body.setText("这些成员的加密密钥与本地记录不一致：");
                body.setTextSize(14);
                body.setTextColor(0xFF757575);
                box.addView(body);

                TextView list = new TextView(activity);
                list.setText(detail);
                list.setTextSize(14);
                list.setTypeface(android.graphics.Typeface.MONOSPACE);
                list.setTextIsSelectable(true);
                list.setPadding(0, (int) (density * 6), 0, (int) (density * 12));
                box.addView(list);

                TextView warn = new TextView(activity);
                warn.setText("选「继续发送」时这 " + n + " 人将无法解密这条内容。"
                        + "如果对方没有重装应用、也没换过设备，这次变化很可能是有人在中间替换了他的公钥。");
                warn.setTextSize(12);
                warn.setTextColor(0xFFD32F2F);
                box.addView(warn);

                new MaterialAlertDialogBuilder(activity)
                        .setTitle("⚠️ 有成员的加密密钥已变更")
                        .setView(box)
                        .setPositiveButton("继续发送", (d, w) -> {
                            choice.set(KEY_CHANGE_CONTINUE);
                            latch.countDown();
                        })
                        .setNeutralButton("信任新密钥", (d, w) -> {
                            choice.set(KEY_CHANGE_TRUST);
                            latch.countDown();
                        })
                        .setNegativeButton("取消", (d, w) -> latch.countDown())
                        .setOnCancelListener(d -> latch.countDown())
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "show member-key-changed dialog failed", e);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                Log.w(TAG, "member key-changed confirm timed out — treat as cancel");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return choice.get();
    }

    /**
     * 逐成员列出「昵称：旧短码 → 新短码」。
     *
     * <p>短码只用于人眼近似核对，真正的判定用的是 {@link PeerKeyPinStore} 里的 64 位全码指纹。
     */
    private static String changedMemberDetail(GroupEncryptHelper.EncryptResult er, NameResolver nameResolver) {
        StringBuilder sb = new StringBuilder();
        for (int id : er.changedMembers) {
            String name = (nameResolver != null) ? nameResolver.nameOf(id) : null;
            if (name == null || name.isEmpty()) name = "用户" + id;
            String oldCode = PeerKeyPinStore.displayCode(PeerKeyPinStore.getPinnedFingerprint(id));
            String newCode = PeerKeyPinStore.displayCode(er.changedMemberKeys.get(id));
            sb.append("• ").append(name).append("：")
                    .append(oldCode.isEmpty() ? "(未记录)" : oldCode)
                    .append(" → ")
                    .append(newCode.isEmpty() ? "(无法识别)" : newCode)
                    .append('\n');
        }
        return sb.toString();
    }
}
