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
import com.google.gson.Gson;

/**
 * 聊天「+」→ 转账：给好友转账，密语同款数字键盘输金额。
 * 点「转账」→ 直接进全屏支付密码页（自写数字键盘，无弹窗）→ 成功后把服务端返回的
 * transfer 消息 JSON 带回 ChatActivity（setResult "json"），密语式即时上屏。
 */
public class TransferSendActivity extends AppCompatActivity {

    private ApiClient api;
    private int friendId;
    private String friendName;
    private int paySet = 0;
    private AmountKeypadView keypad;
    private TextView btnSend;
    private long pendingFen;
    private String pendingMemo;

    /** 全屏支付密码页结果：拿到 6 位 PIN 后提交转账。 */
    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6) doSend(pendingFen, pendingMemo, pin);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer_send);

        friendId = getIntent().getIntExtra("friend_id", 0);
        friendName = getIntent().getStringExtra("friend_name");
        if (friendName == null || friendName.isEmpty()) friendName = "好友";

        PreferencesManager prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnTransferSendBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvTransferSendTitle)).setText("转账给 " + friendName);
        ((TextView) findViewById(R.id.tvTransferSendName)).setText(friendName);

        TextView tvAvatar = findViewById(R.id.tvTransferSendAvatar);
        tvAvatar.setText(friendName.substring(0, 1));
        try {
            ((GradientDrawable) tvAvatar.getBackground()).setColor(Color.parseColor("#FA9D3B"));
        } catch (Exception ignored) {}

        final TextView tvBalance = findViewById(R.id.tvTransferSendBalance);
        final EditText etMemo = findViewById(R.id.etTransferSendMemo);
        final TextView tvAmount = findViewById(R.id.tvTransferSendAmount);

        // 密语同款数字键盘：点金额呼出，金额变化启用/禁用按钮
        keypad = findViewById(R.id.amountKeypadTransfer);
        btnSend = findViewById(R.id.btnTransferSendSend);
        keypad.setAmountView(tvAmount, this::updateSendBtn);
        updateSendBtn();
        findViewById(R.id.rowTransferSendAmount).setOnClickListener(v -> toggleKeypad());
        etMemo.setOnFocusChangeListener((v, has) -> { if (has) keypad.setVisibility(View.GONE); });

        btnSend.setOnClickListener(v -> {
            long fen = keypad.fen();
            if (fen <= 0) return;
            if (paySet != 1) {
                Toast.makeText(this, "请先在「钱包」设置支付密码", Toast.LENGTH_SHORT).show();
                return;
            }
            // 无确认弹窗：直接进全屏密码页，密码页顶部显示「转账给 XX ¥xx.xx」供核对
            pendingFen = fen;
            pendingMemo = etMemo.getText().toString().trim();
            Intent it = new Intent(this, PasswordInputActivity.class);
            it.putExtra(PasswordInputActivity.EXTRA_CONTEXT,
                    "转账给 " + friendName + " " + formatYuan(fen));
            pwdLauncher.launch(it);
        });

        new Thread(() -> {
            try {
                Models.WalletInfo w = api.getWallet();
                runOnUiThread(() -> {
                    paySet = w.pay_set;
                    tvBalance.setText("零钱 " + formatYuan(w.balance));
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void updateSendBtn() {
        boolean ok = keypad.hasValue();
        btnSend.setEnabled(ok);
        btnSend.setTextColor(ok ? Color.WHITE : 0xFFB2B2B2);
    }

    private void toggleKeypad() {
        if (keypad.getVisibility() == View.VISIBLE) {
            keypad.setVisibility(View.GONE);
        } else {
            // 呼出金额键盘前先收起系统输入法（备注可能还保持着焦点）
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

    private void doSend(final long fen, final String memo, final String pin) {
        String hash = Sha.sha256Hex(pin);
        if (hash.isEmpty()) {
            Toast.makeText(this, "安全组件不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                Models.TransferSendResponse r = api.sendTransfer(friendId, fen, memo, hash, false);
                runOnUiThread(() -> {
                    if (r != null && r.success && r.message != null) {
                        // 把服务端 transfer 消息带回聊天页即时上屏
                        Intent data = new Intent();
                        data.putExtra("json", new Gson().toJson(r.message));
                        setResult(RESULT_OK, data);
                        finish();
                    } else {
                        Toast.makeText(this, r != null && r.error != null ? r.error : "转账失败", Toast.LENGTH_SHORT).show();
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
