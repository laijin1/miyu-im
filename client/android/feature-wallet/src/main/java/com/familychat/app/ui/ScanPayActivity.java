package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Sha;

/**
 * 扫「收款码」→ 向我付款：给收款人（可为非好友）扫码支付，钱直接到账（非待收款）。
 * 密语同款数字键盘输金额，scan_pay=true 跳过好友校验并直接扣款入账。
 */
public class ScanPayActivity extends AppCompatActivity {

    private ApiClient api;
    private int toUserId;
    private int paySet = 0;
    private AmountKeypadView keypad;
    private TextView btnPay;
    private String payeeName;
    private long pendingFen;
    private String pendingMemo;

    /** 全屏支付密码页结果：拿到 6 位 PIN 后直接支付。 */
    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6) doPay(pendingFen, pendingMemo, pin);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_pay);

        toUserId = getIntent().getIntExtra("user_id", 0);
        PreferencesManager prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnScanPayBack).setOnClickListener(v -> finish());

        final TextView tvAvatar = findViewById(R.id.tvScanPayAvatar);
        final TextView tvName = findViewById(R.id.tvScanPayName);
        final TextView tvBalance = findViewById(R.id.tvScanPayBalance);
        final EditText etMemo = findViewById(R.id.etScanPayMemo);
        final TextView tvAmount = findViewById(R.id.tvScanPayAmount);

        // 密语同款数字键盘
        keypad = findViewById(R.id.amountKeypadScanPay);
        btnPay = findViewById(R.id.btnScanPayPay);
        keypad.setAmountView(tvAmount, this::updatePayBtn);
        updatePayBtn();
        findViewById(R.id.rowScanPayAmount).setOnClickListener(v -> toggleKeypad());
        etMemo.setOnFocusChangeListener((v, has) -> { if (has) keypad.setVisibility(View.GONE); });

        btnPay.setOnClickListener(v -> {
            long fen = keypad.fen();
            if (fen <= 0) return;
            if (paySet != 1) {
                Toast.makeText(this, "请先在「钱包」设置支付密码", Toast.LENGTH_SHORT).show();
                return;
            }
            // 直接进全屏密码页（无弹窗），密码页顶部显示「向 XX 付款 ¥xx.xx」
            pendingFen = fen;
            pendingMemo = etMemo.getText().toString().trim();
            Intent it = new Intent(this, PasswordInputActivity.class);
            it.putExtra(PasswordInputActivity.EXTRA_CONTEXT,
                    "向 " + (payeeName != null && !payeeName.isEmpty() ? payeeName : "收款人")
                            + " 付款 " + formatYuan(fen));
            pwdLauncher.launch(it);
        });

        // 拉取收款人资料 + 我的零钱
        new Thread(() -> {
            try {
                Models.ProfileResponse p = api.getUserById(toUserId);
                runOnUiThread(() -> {
                    if (p != null && p.user != null) {
                        String name = p.user.nickname;
                        if (name == null || name.isEmpty()) name = "收款人";
                        payeeName = name;
                        tvName.setText(name);
                        tvAvatar.setText(name.substring(0, 1));
                        try {
                            String c = p.user.avatar_color;
                            ((GradientDrawable) tvAvatar.getBackground())
                                    .setColor(Color.parseColor(c != null && !c.isEmpty() ? c : "#e8f5e9"));
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
            try {
                Models.WalletInfo w = api.getWallet();
                runOnUiThread(() -> {
                    paySet = w.pay_set;
                    tvBalance.setText("我的零钱 " + formatYuan(w.balance));
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void updatePayBtn() {
        boolean ok = keypad.hasValue();
        btnPay.setEnabled(ok);
        btnPay.setTextColor(ok ? Color.WHITE : 0xFFB2B2B2);
    }

    private void toggleKeypad() {
        if (keypad.getVisibility() == View.VISIBLE) {
            keypad.setVisibility(View.GONE);
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            keypad.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (keypad.getVisibility() == View.VISIBLE) {
            keypad.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    private void doPay(final long fen, final String memo, final String pin) {
        String hash = Sha.sha256Hex(pin);
        if (hash.isEmpty()) {
            Toast.makeText(this, "安全组件不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                Models.TransferSendResponse r = api.sendTransfer(toUserId, fen, memo, hash, true);
                runOnUiThread(() -> {
                    if (r != null && r.success) {
                        // 支付成功 → 密语同款成功结果页（含金额/收款方/完成）
                        Intent it = new Intent(ScanPayActivity.this, PaymentSuccessActivity.class);
                        it.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT, fen);
                        it.putExtra(PaymentSuccessActivity.EXTRA_PAYEE,
                                payeeName != null && !payeeName.isEmpty() ? payeeName : "收款人");
                        startActivity(it);
                        finish();
                    } else {
                        Toast.makeText(this, r != null && r.error != null ? r.error : "支付失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "转账失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String formatYuan(long fen) {
        return String.format(java.util.Locale.CHINA, "¥%.2f", fen / 100.0);
    }
}
