package com.familychat.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.familychat.app.api.call.AudioRouter;
import com.familychat.app.api.call.CallEngine;
import com.familychat.app.feature_chat.R;
import com.familychat.app.utils.AppLog;

/**
 * 音频设备选择面板（底部弹出）。
 *
 * <p>自建 {@link Dialog} + {@code Gravity.BOTTOM}（项目里没有现成的 BottomSheet 用法：
 * 全仓 {@code BottomSheetDialog} 仅有一处未使用的 import，{@code BottomSheetDialogFragment} 零命中），
 * 布局见 {@code res/layout/dialog_audio_route.xml}。
 *
 * <p>四行：扬声器 / 听筒 / 蓝牙 / 有线耳机。蓝牙与有线耳机在未连接时置灰且不可点
 * （判定来自 {@link AudioRouter#isBluetoothConnected()} / {@link AudioRouter#isWiredHeadsetConnected()}）。
 * 当前路由打对勾。点击可用行 → {@link AudioRouter#setRoute} + 关闭（本类不弹提示，
 * 反馈由调用方注册的 {@link AudioRouter.Observer} 呈现，详见 {@code bindRow}）。
 */
public class AudioRouteDialog {

    private static final String TAG = "AudioRouteDialog";

    private static final int COLOR_ENABLED = 0xFF222222;
    private static final int COLOR_DISABLED = 0xFFBBBBBB;

    private final Context context;
    private Dialog dialog;

    public AudioRouteDialog(Context context) {
        this.context = context;
    }

    public void show() {
        final View root = LayoutInflater.from(context).inflate(R.layout.dialog_audio_route, null);

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);

        final Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            final WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
        }

        final AudioRouter.Route current = AudioRouter.getCurrentRoute();
        final boolean btConnected = AudioRouter.isBluetoothConnected();
        final boolean wiredConnected = AudioRouter.isWiredHeadsetConnected();

        bindRow(root, R.id.rowRouteSpeaker, R.id.ivRouteSpeakerIcon, R.id.tvRouteSpeaker,
                R.id.ivRouteSpeakerCheck, AudioRouter.Route.SPEAKER,
                true, current == AudioRouter.Route.SPEAKER);
        bindRow(root, R.id.rowRouteEarpiece, R.id.ivRouteEarpieceIcon, R.id.tvRouteEarpiece,
                R.id.ivRouteEarpieceCheck, AudioRouter.Route.EARPIECE,
                true, current == AudioRouter.Route.EARPIECE);
        bindRow(root, R.id.rowRouteBluetooth, R.id.ivRouteBluetoothIcon, R.id.tvRouteBluetooth,
                R.id.ivRouteBluetoothCheck, AudioRouter.Route.BLUETOOTH,
                btConnected, current == AudioRouter.Route.BLUETOOTH);
        bindRow(root, R.id.rowRouteWired, R.id.ivRouteWiredIcon, R.id.tvRouteWired,
                R.id.ivRouteWiredCheck, AudioRouter.Route.WIRED_HEADSET,
                wiredConnected, current == AudioRouter.Route.WIRED_HEADSET);

        // 第 5 行：本地静音对方（开关型，不是路由）—— 单独绑定，不复用 bindRow
        bindLocalMuteRow(root);

        root.findViewById(R.id.btnRouteCancel).setOnClickListener(v -> dismiss());

        AppLog.d(TAG, "show: current=" + current + " bt=" + btConnected + " wired=" + wiredConnected);
        dialog.show();
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = null;
    }

    /**
     * 绑定一行。
     *
     * @param available 设备是否可用（不可用 → 置灰 + 取消点击）
     * @param selected  是否当前路由（显示对勾）
     */
    private void bindRow(View root, int rowId, int iconId, int textId, int checkId,
                         final AudioRouter.Route route,
                         boolean available, boolean selected) {
        final View row = root.findViewById(rowId);
        final ImageView icon = root.findViewById(iconId);
        final TextView text = root.findViewById(textId);
        final ImageView check = root.findViewById(checkId);

        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);

        final int color = available ? COLOR_ENABLED : COLOR_DISABLED;
        icon.setColorFilter(color);
        text.setTextColor(color);

        row.setClickable(available);
        if (available) {
            row.setOnClickListener(v -> {
                // 路由变化的可见反馈由调用方（CallActivity）的 Observer 呈现：它注册了
                // AudioRouter.Observer，onRouteChanged → updateSpeakerIcon() 会把底部音频设备
                // 按钮的图标切到新路由。本类刻意不弹提示。
                // ⚠️ 若将来有别的调用方，必须确保其注册了 AudioRouter.Observer，
                //    否则切换路由将没有任何反馈。
                AudioRouter.setRoute(route);
                dismiss();
            });
        } else {
            row.setOnClickListener(null);
        }
    }

    /**
     * 绑定"静音对方"行（开关型，刻意不复用 {@link #bindRow}）。
     *
     * <p><b>为什么不能复用</b>：{@code bindRow} 的第 5 参是 {@link AudioRouter.Route}，
     * 点击行为被硬编码为 {@code AudioRouter.setRoute(route) + dismiss()}（见上方 bindRow）。
     * 而"静音对方"根本不是一条音频路由（{@code AudioRouter.Route} 只有扬声器/听筒/蓝牙/
     * 有线耳机四种），且点击后<b>不能</b>关闭面板 —— 用户要能连点两次来取消静音。
     *
     * <p>语义：对勾不表示"当前路由"，而表示"本地静音开关已打开"。
     * 状态现取现读（本 Dialog 每次 {@code new}，无实例状态缓存）。
     */
    private void bindLocalMuteRow(View root) {
        final View row = root.findViewById(R.id.rowLocalMute);
        final ImageView icon = root.findViewById(R.id.ivLocalMuteIcon);
        final TextView text = root.findViewById(R.id.tvLocalMute);
        final ImageView check = root.findViewById(R.id.ivLocalMuteCheck);

        // 与 4 条路由行同款着色（面板白底）
        icon.setColorFilter(COLOR_ENABLED);
        text.setTextColor(COLOR_ENABLED);

        refreshLocalMuteCheck(check);

        row.setClickable(true);
        row.setOnClickListener(v -> {
            CallEngine.toggleLocalMute();
            refreshLocalMuteCheck(check);
            // 刻意不 dismiss：允许用户留在面板里连续切换
        });
    }

    /** 按当前引擎状态刷新对勾（引擎不存在时 {@code isLocalMuted()} 返回 false → 不显示对勾）。 */
    private void refreshLocalMuteCheck(ImageView check) {
        final boolean muted = CallEngine.isLocalMuted();
        check.setVisibility(muted ? View.VISIBLE : View.INVISIBLE);
    }
}
