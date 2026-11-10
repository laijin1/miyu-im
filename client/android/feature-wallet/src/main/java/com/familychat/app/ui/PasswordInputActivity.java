package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.familychat.app.feature_wallet.R;

/**
 * 全屏支付密码输入页（密语同款）：6 个掩码方格 + 自写数字键盘（无系统输入法、无弹窗）。
 * 第 6 位自动提交，通过 setResult 返回明文 PIN；点击 ✕ 或返回键取消。
 */
public class PasswordInputActivity extends AppCompatActivity {

    public static final String EXTRA_CONTEXT = "context";
    public static final String EXTRA_PIN = "pin";

    private final StringBuilder pin = new StringBuilder();
    private final TextView[] boxes = new TextView[6];
    private boolean completed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password_input);

        findViewById(R.id.btnPwdClose).setOnClickListener(v -> cancel());
        String ctx = getIntent().getStringExtra(EXTRA_CONTEXT);
        if (ctx != null && !ctx.isEmpty()) {
            ((TextView) findViewById(R.id.tvPwdContext)).setText(ctx);
        }

        // 6 个掩码方格（程序化创建，避免 6 段重复 XML）
        LinearLayout row = findViewById(R.id.rowPwdBoxes);
        int boxSize = dp(48);
        for (int i = 0; i < 6; i++) {
            TextView box = new TextView(this);
            box.setWidth(boxSize);
            box.setHeight(boxSize);
            box.setGravity(Gravity.CENTER);
            box.setTextSize(24);
            box.setTextColor(ContextCompat.getColor(this, R.color.text));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(6));
            g.setColor(ContextCompat.getColor(this, R.color.pwd_box_bg));
            g.setStroke(dp(1), ContextCompat.getColor(this, R.color.pwd_box_stroke));
            box.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(boxSize, boxSize);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(box, lp);
            boxes[i] = box;
        }

        PasswordKeypadView keypad = findViewById(R.id.pwdKeypad);
        keypad.setListener(new PasswordKeypadView.Listener() {
            @Override public void onDigit(char c) {
                if (pin.length() >= 6) return;
                pin.append(c);
                refresh();
                if (pin.length() == 6) complete();
            }
            @Override public void onBackspace() {
                if (pin.length() > 0) {
                    pin.deleteCharAt(pin.length() - 1);
                    refresh();
                }
            }
        });
    }

    private void refresh() {
        for (int i = 0; i < 6; i++) boxes[i].setText(i < pin.length() ? "●" : "");
    }

    private void complete() {
        if (completed) return;
        completed = true;
        Intent data = new Intent();
        data.putExtra(EXTRA_PIN, pin.toString());
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        if (completed) return;
        completed = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
