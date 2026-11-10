package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Sha;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 钱包首页（密语同款）：余额卡 + 收付款/账单/支付密码 + 充值。
 * 充值对所有人开放（未来接入银行，当前直接加余额）。
 */
public class WalletActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private TextView tvBalance;
    /** 「支付密码」行右侧状态文字（已设置/未设置）；设置/修改成功时也把反馈闪在这里。 */
    private TextView tvWalletPayStatus;

    /** 待接收 PIN 的回调（连按流程用：设/改密码需多次输入）。 */
    private java.util.function.Consumer<String> pendingPinCb;

    /** 全屏支付密码页结果分发：OK 且有 PIN → 交给当前待执行回调。 */
    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                java.util.function.Consumer<String> cb = pendingPinCb;
                pendingPinCb = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6 && cb != null) cb.accept(pin);
                }
            });
    private long balanceFen = 0;
    private int paySet = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        tvBalance = findViewById(R.id.tvWalletBalance);
        tvWalletPayStatus = findViewById(R.id.tvWalletPayStatus);
        TextView btnRecharge = findViewById(R.id.btnWalletRecharge);

        findViewById(R.id.btnWalletBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnWalletPayCode).setOnClickListener(v ->
                startActivity(new Intent(this, PayCodeActivity.class)));
        findViewById(R.id.rowWalletPayCode).setOnClickListener(v ->
                startActivity(new Intent(this, PayCodeActivity.class)));
        findViewById(R.id.rowWalletBills).setOnClickListener(v ->
                startActivity(new Intent(this, BillsActivity.class)));
        findViewById(R.id.rowWalletPassword).setOnClickListener(v -> onPayPasswordClick());

        // 充值对所有用户开放
        btnRecharge.setVisibility(View.VISIBLE);
        btnRecharge.setOnClickListener(v -> showRechargeDialog());

        loadWallet();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设密码/充值返回后刷新余额
        loadWallet();
    }

    /** 拉取钱包余额/是否已设支付密码。 */
    private void loadWallet() {
        new Thread(() -> {
            try {
                Models.WalletInfo w = api.getWallet();
                runOnUiThread(() -> {
                    balanceFen = w.balance;
                    paySet = w.pay_set;
                    tvBalance.setText(formatYuan(balanceFen));
                    tvWalletPayStatus.setText(paySet == 1 ? "已设置" : "未设置");
                });
            } catch (Exception e) {
                // 静默记录，不弹 toast（网络抖动时钱包页打开不闪错）
                com.familychat.app.utils.AppLog.e("Wallet", "loadWallet 失败", e);
            }
        }).start();
    }

    /** 支付密码：未设置→设置流程；已设置→修改流程（需旧密码）。 */
    private void onPayPasswordClick() {
        new Thread(() -> {
            final int set;
            try {
                set = api.getWallet().pay_set;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载支付状态失败", Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> {
                if (set == 1) changePayPasswordFlow();
                else setPayPasswordFlow();
            });
        }).start();
    }

    /** 首次设置：全屏密码页连按两次，一致后提交。 */
    private void setPayPasswordFlow() {
        promptPin("设置支付密码", pin1 ->
            promptPin("请再次输入支付密码", pin2 -> {
                if (!pin1.equals(pin2)) {
                    Toast.makeText(this, "两次输入不一致，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                String hash = Sha.sha256Hex(pin1);
                if (hash.isEmpty()) {
                    Toast.makeText(this, "安全组件不可用", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(() -> {
                    try {
                        Models.WalletResponse r = api.setPayPassword(hash);
                        runOnUiThread(() -> {
                            prefs.setPayHash(hash);
                            paySet = 1;
                            FlashText.flash(tvWalletPayStatus, "设置成功", "已设置");
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "设置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }));
    }

    /** 修改：原密码 → 新密码两次（全屏密码页连按）。 */
    private void changePayPasswordFlow() {
        promptPin("请输入原支付密码", oldPin ->
            promptPin("请输入新支付密码", newPin1 ->
                promptPin("请再次输入新支付密码", newPin2 -> {
                    if (!newPin1.equals(newPin2)) {
                        Toast.makeText(this, "两次输入不一致，请重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String oldHash = Sha.sha256Hex(oldPin);
                    String newHash = Sha.sha256Hex(newPin1);
                    new Thread(() -> {
                        try {
                            Models.WalletResponse r = api.changePayPassword(oldHash, newHash);
                            runOnUiThread(() -> {
                                prefs.setPayHash(newHash);
                                FlashText.flash(tvWalletPayStatus, "修改成功", "已设置");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "修改失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })));
    }

    /** 启动全屏支付密码页，拿到 6 位 PIN 后回调 cb（取消则静默放弃）。 */
    private void promptPin(String context, java.util.function.Consumer<String> cb) {
        pendingPinCb = cb;
        Intent it = new Intent(this, PasswordInputActivity.class);
        it.putExtra(PasswordInputActivity.EXTRA_CONTEXT, context);
        pwdLauncher.launch(it);
    }

    /** 充值：给自己充零钱（输入金额(元)，未来接入银行）。 */
    private void showRechargeDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, dp(8), pad, 0);

        EditText etAmount = new EditText(this);
        etAmount.setHint("充值金额（元）");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(etAmount);

        new MaterialAlertDialogBuilder(this)
                .setTitle("零钱充值")
                .setMessage("当前余额 " + formatYuan(balanceFen) + "\n充值会直接到账零钱")
                .setView(root)
                .setPositiveButton("充值", (d, w) -> {
                    String amtStr = etAmount.getText().toString().trim();
                    long fen;
                    try {
                        fen = Math.round(Double.parseDouble(amtStr) * 100);
                    } catch (Exception e) {
                        Toast.makeText(this, "请输入有效金额", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (fen <= 0) {
                        Toast.makeText(this, "金额必须大于 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            Models.WalletResponse r = api.rechargeWallet(fen);
                            runOnUiThread(this::loadWallet);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "充值失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 分 → "¥xx.xx"。 */
    private String formatYuan(long fen) {
        return String.format(java.util.Locale.CHINA, "¥%.2f", fen / 100.0);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
