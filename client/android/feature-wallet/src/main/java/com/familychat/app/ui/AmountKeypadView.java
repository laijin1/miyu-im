package com.familychat.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 密语同款转账金额数字键盘：底部滑出的 App 内置键盘（不走系统输入法）。
 * 布局：浅灰底 + 白色按键（发丝分隔），4 行 「1-9 / . 0 ⌫」。
 * 金额逻辑：最多两位小数、去前导零、空态显示灰色 0.00；fen() 返回可用金额（>0 才有值）。
 */
public class AmountKeypadView extends LinearLayout {

    private final StringBuilder value = new StringBuilder();
    private TextView amountView;
    private Runnable onChange;

    /** 代码动态创建（无入参布局） */
    public AmountKeypadView(Context ctx) {
        this(ctx, null);
    }

    /** XML inflate 必需构造器（Activity 布局引用本类时系统调用） */
    public AmountKeypadView(Context ctx, AttributeSet attrs) {
        this(ctx, attrs, 0);
    }

    public AmountKeypadView(Context ctx, AttributeSet attrs, int defStyleAttr) {
        super(ctx, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.keypad_bg));
        buildKeys();
    }

    /** 绑定金额显示 TextView + 金额变化回调（用于启用/禁用按钮）。 */
    public void setAmountView(TextView tv, Runnable onChange) {
        this.amountView = tv;
        this.onChange = onChange;
        refresh();
    }

    /** 当前是否有可用金额（> 0）。 */
    public boolean hasValue() {
        return fen() > 0;
    }

    /** 当前金额（分），非法/为空返回 0。 */
    public long fen() {
        try {
            if (value.length() == 0) return 0;
            double d = Double.parseDouble(value.toString());
            return d <= 0 ? 0 : Math.round(d * 100);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 回填金额（分），用于已选择金额的场景。 */
    public void setValue(long fen) {
        value.setLength(0);
        if (fen > 0) {
            long yuan = fen / 100;
            long cents = fen % 100;
            value.append(yuan);
            if (cents > 0) value.append('.').append(cents < 10 ? "0" + cents : cents);
        }
        refresh();
    }

    private void buildKeys() {
        addRow(new String[]{"1", "2", "3"});
        addRow(new String[]{"4", "5", "6"});
        addRow(new String[]{"7", "8", "9"});
        addRow(new String[]{".", "0", "⌫"});
    }

    private void addRow(String[] keys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(50)));
        for (String k : keys) {
            TextView key = new TextView(getContext());
            key.setText(k);
            key.setTextSize(22);
            key.setGravity(Gravity.CENTER);
            key.setTextColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.keypad_key_text));
            key.setBackgroundResource(com.familychat.app.feature_wallet.R.drawable.bg_keypad_key);
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            key.setLayoutParams(lp);
            if ("⌫".equals(k)) {
                key.setOnClickListener(v -> backspace());
                key.setOnLongClickListener(v -> { value.setLength(0); refresh(); return true; });
            } else {
                key.setOnClickListener(v -> typeKey(k));
            }
            row.addView(key, lp);
        }
    }

    private void typeKey(String k) {
        if (".".equals(k)) {
            if (value.indexOf(".") >= 0) return;          // 已有小数点
            if (value.length() == 0) value.append("0.");
            else value.append('.');
        } else {
            int dot = value.indexOf(".");
            if (dot >= 0 && value.length() - dot - 1 >= 2) return; // 两位小数上限
            if (dot < 0 && value.length() == 1 && value.charAt(0) == '0') {
                value.setCharAt(0, k.charAt(0));           // 去前导零：05 → 5
            } else if (value.length() < 10) {
                value.append(k);
            }
        }
        refresh();
    }

    private void backspace() {
        if (value.length() > 0) value.deleteCharAt(value.length() - 1);
        refresh();
    }

    private void refresh() {
        if (amountView == null) return;
        String s = value.toString();
        if (s.isEmpty()) {
            amountView.setText("0.00");
            amountView.setTextColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.amount_empty));
        } else {
            amountView.setText(s);
            amountView.setTextColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.amount_value));
        }
        if (onChange != null) onChange.run();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
