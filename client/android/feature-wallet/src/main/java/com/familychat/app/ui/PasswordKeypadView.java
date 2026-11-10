package com.familychat.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 自写纯数字键盘（支付密码用）：3x4 布局 「1-9 / 空·0·⌫」，白色按键 + 发丝分隔。
 * 只上报 onDigit / onBackspace 事件，不持有输入状态（由页面维护 6 位掩码）。
 */
public class PasswordKeypadView extends LinearLayout {

    public interface Listener {
        void onDigit(char c);
        void onBackspace();
    }

    private Listener listener;

    /** 代码动态创建（无入参布局） */
    public PasswordKeypadView(Context ctx) {
        this(ctx, null);
    }

    /** XML inflate 必需构造器（Activity 布局引用本类时系统调用） */
    public PasswordKeypadView(Context ctx, AttributeSet attrs) {
        this(ctx, attrs, 0);
    }

    public PasswordKeypadView(Context ctx, AttributeSet attrs, int defStyleAttr) {
        super(ctx, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.keypad_bg));
        buildKeys();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void buildKeys() {
        addRow(new String[]{"1", "2", "3"});
        addRow(new String[]{"4", "5", "6"});
        addRow(new String[]{"7", "8", "9"});
        // 密语支付密码键盘：空位 · 0 · ⌫（⌫ 靠右）
        addRow(new String[]{"", "0", "⌫"});
    }

    private void addRow(String[] keys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));
        for (String k : keys) {
            TextView key = new TextView(getContext());
            key.setTextSize(24);
            key.setGravity(Gravity.CENTER);
            key.setTextColor(ContextCompat.getColor(getContext(), com.familychat.app.feature_wallet.R.color.keypad_key_text));
            key.setBackgroundResource(com.familychat.app.feature_wallet.R.drawable.bg_keypad_key);
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            key.setLayoutParams(lp);
            if (k.isEmpty()) {
                // 空位：无内容、无点击
            } else if ("⌫".equals(k)) {
                key.setText("⌫");
                key.setOnClickListener(v -> { if (listener != null) listener.onBackspace(); });
            } else {
                key.setText(k);
                key.setOnClickListener(v -> { if (listener != null) listener.onDigit(k.charAt(0)); });
            }
            row.addView(key, lp);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
