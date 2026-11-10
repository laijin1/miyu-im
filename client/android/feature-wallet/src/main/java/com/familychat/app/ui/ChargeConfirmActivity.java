package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
 * 付款码主人确认「收款请求」（Dialog 主题）：商家扫码输金额 → WS charge_requested → 本页弹窗。
 * 确认 → 全屏支付密码页（自写键盘，无弹窗）→ 钱从本人零钱直接转入商家；拒绝则请求作废。
 */
public class ChargeConfirmActivity extends AppCompatActivity {

    private ApiClient api;
    private long chargeId;
    private String merchantName;
    private long payAmount;

    /** 全屏支付密码页结果：拿到 6 位 PIN 后确认付款。 */
    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6) doApprove(pin);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charge_confirm);

        chargeId = getIntent().getLongExtra("charge_id", 0);
        String fromNickname = getIntent().getStringExtra("from_nickname");
        long amount = getIntent().getLongExtra("amount", 0);
        payAmount = amount;
        String memo = getIntent().getStringExtra("memo");

        PreferencesManager prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        TextView tvAvatar = findViewById(R.id.tvChargeConfirmAvatar);
        TextView tvName = findViewById(R.id.tvChargeConfirmName);
        TextView tvAmount = findViewById(R.id.tvChargeConfirmAmount);
        TextView tvMemo = findViewById(R.id.tvChargeConfirmMemo);

        if (fromNickname == null || fromNickname.isEmpty()) fromNickname = "商家";
        merchantName = fromNickname;
        tvName.setText(fromNickname);
        tvAvatar.setText(fromNickname.substring(0, 1));
        try {
            ((GradientDrawable) tvAvatar.getBackground()).setColor(Color.parseColor("#2196F3"));
        } catch (Exception ignored) {}
        tvAmount.setText(formatYuan(amount));
        if (memo != null && !memo.isEmpty()) {
            tvMemo.setVisibility(android.view.View.VISIBLE);
            tvMemo.setText("备注：" + memo);
        }

        findViewById(R.id.btnChargeConfirmDecline).setOnClickListener(v -> doDecline());
        findViewById(R.id.btnChargeConfirmOk).setOnClickListener(v -> {
            // 直接进全屏密码页（无弹窗），密码页顶部显示「向 XX 付款 ¥xx.xx」
            Intent it = new Intent(this, PasswordInputActivity.class);
            it.putExtra(PasswordInputActivity.EXTRA_CONTEXT,
                    "向 " + merchantName + " 付款 " + formatYuan(amount));
            pwdLauncher.launch(it);
        });
    }

    private void doApprove(final String pin) {
        String hash = Sha.sha256Hex(pin);
        if (hash.isEmpty()) {
            Toast.makeText(this, "安全组件不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                Models.WalletResponse r = api.approveCharge((int) chargeId, hash);
                runOnUiThread(() -> {
                    if (r != null && r.success) {
                        // 付款成功 → 密语同款成功结果页
                        Intent it = new Intent(ChargeConfirmActivity.this, PaymentSuccessActivity.class);
                        it.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT, payAmount);
                        it.putExtra(PaymentSuccessActivity.EXTRA_PAYEE,
                                merchantName != null && !merchantName.isEmpty() ? merchantName : "商家");
                        startActivity(it);
                        finish();
                    } else {
                        Toast.makeText(this, r != null && r.error != null ? r.error : "付款失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "付款失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void doDecline() {
        new Thread(() -> {
            try {
                Models.WalletResponse r = api.declineCharge((int) chargeId);
                runOnUiThread(this::finish);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String formatYuan(long fen) {
        return String.format(java.util.Locale.CHINA, "¥%.2f", fen / 100.0);
    }
}
