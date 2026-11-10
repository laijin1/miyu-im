package com.familychat.app.ui.chat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.familychat.app.feature_chat.R;

/**
 * 详情面板「行」的共用构件：行骨架 + 开关行。
 *
 * <p>单聊（{@link ChatDetailPanel}）与群聊（{@link GroupDetailPanel}）两个面板同包，
 * 行高、内边距、图标着色（{@code R.attr.colorIcon} 解析）与无障碍标记这些细节一旦两处各写一份
 * 就会漂移 —— 本仓库已经因为"两处各写一份"踩过两次坑，故这里只留一份。
 *
 * <p><b>只有开关行共用骨架。</b>{@code createSectionButton} 的骨架仍留在各自的宿主里：
 * 群面板的右值行要 {@code setTag(subTv)}、单聊面板的右值行语义不同，
 * 强行统一会把两种尾部的差异藏进参数里。
 */
final class SectionRowHelper {

    private SectionRowHelper() {
    }

    /**
     * 行骨架：HORIZONTAL / CENTER_VERTICAL / 高 52dp / 左右内边距 16dp / ripple 底 / 可点，
     * 内容为「装饰图标 + 标题 + 撑开的 Space」。
     *
     * <p>尾部（右值 / 箭头 / 开关）由调用方追加。
     *
     * <p>⚠️ 本方法<b>不</b>设置 click listener：尾部控件不同，点击语义也不同，由调用方决定。
     */
    static LinearLayout newSectionRow(Context context, int iconRes, String title, boolean isDanger) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
        row.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_ff_row_ripple);

        // 装饰图标：纯展示，不绑定、不点击、不参与任何逻辑
        if (iconRes != 0) {
            ImageView icon = new ImageView(context);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20));
            iconLp.rightMargin = dp(context, 12);
            icon.setLayoutParams(iconLp);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 图标统一着色：白天深色、黑夜白色（沿用主题 colorIcon，与顶栏图标一致）
            try {
                android.util.TypedValue tv = new android.util.TypedValue();
                context.getTheme().resolveAttribute(R.attr.colorIcon, tv, true);
                icon.setColorFilter(tv.data);
            } catch (Exception ignored) {}
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon);
        }

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(15);
        titleTv.setTextColor(context.getColor(isDanger ? R.color.danger : R.color.text));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(titleTv);

        // 撑开中缝，把尾部（右值/箭头/开关）推到行尾
        Space spacer = new Space(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(spacer);
        return row;
    }

    /**
     * 开关行：骨架 + 尾部 {@link SwitchCompat}。
     *
     * <p><b>为什么不会重复触发</b>：行的 click 只做 {@code sw.toggle()}，真正的业务回调只挂在
     * Switch 自己的 {@code OnCheckedChangeListener} 上 —— "点整行"与"点开关"因此汇流成同一条
     * 路径，一次点击只触发一次回调。Switch 自身是 clickable 的，点它时触摸事件被它消费，
     * 行的 click 不会再触发。
     *
     * <p><b>setChecked 的顺序</b>：先 {@code setChecked(初始值)} 再挂 listener —— 否则初始同步
     * 就会触发一次"用户操作"。
     *
     * <p>Switch 通过 {@code setTag} 挂在本行上（与右值 TextView 用 {@code setTag} 的机制一致），
     * 供 {@link #setSectionChecked} 回取。
     */
    static LinearLayout createSwitchRow(Context context, int iconRes, String title, boolean checked,
                                        CompoundButton.OnCheckedChangeListener onToggle) {
        LinearLayout row = newSectionRow(context, iconRes, title, false);
        SwitchCompat sw = new SwitchCompat(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(onToggle);
        row.addView(sw);
        row.setTag(sw);
        // 整行可点 = 切换开关。回调一律由 switch 的 listener 处理，这里绝不再调 onToggle，
        // 否则点一次行会翻两次（switch.toggle() 触发一次 + 行直接回调一次）。
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    /**
     * 同步某个开关行的选中态（置顶/免打扰）。
     *
     * <p>靠 {@code row.getTag()} 取回尾部控件（开关行里挂的是 {@link SwitchCompat}）。
     * 用 {@code CompoundButton} 判断而不是 {@code SwitchCompat}，是因为能挂上去的开关类型
     * 将来可能变，而 setChecked 的契约相同。
     *
     * <p>⚠️ 这里的 {@code setChecked} 在值发生变化时<b>会</b>触发该开关的 listener；
     * 调用方（{@link #createSwitchRow} 的 onToggle）需自行用"与当前状态比对"处理这种程序性同步。
     */
    static void setSectionChecked(LinearLayout row, boolean checked) {
        if (row == null) return;
        Object tag = row.getTag();
        if (tag instanceof CompoundButton) ((CompoundButton) tag).setChecked(checked);
    }

    private static int dp(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
