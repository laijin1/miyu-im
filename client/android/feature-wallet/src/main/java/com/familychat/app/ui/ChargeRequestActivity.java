package com.familychat.app.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;

/**
 * 扫「付款码」→ 向商家收款：商家输金额后发起收款请求，服务端 WS 推给付款码主人确认。
 * 密语同款数字键盘输金额。
 */
public class ChargeRequestActivity extends AppCompatActivity {

    private ApiClient api;
    private int toUserId;
    private AmountKeypadView keypad;
    private TextView btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charge_request);

        toUserId = getIntent().getIntExtra("user_id", 0);
        PreferencesManager prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnChargeReqBack).setOnClickListener(v -> finish());

        final TextView tvAvatar = findViewById(R.id.tvChargeReqAvatar);
        final TextView tvName = findViewById(R.id.tvChargeReqName);
        final EditText etMemo = findViewById(R.id.etChargeReqMemo);
        final TextView tvAmount = findViewById(R.id.tvChargeReqAmount);

        // 密语同款数字键盘
        keypad = findViewById(R.id.amountKeypadChargeReq);
        btnSend = findViewById(R.id.btnChargeReqSend);
        keypad.setAmountView(tvAmount, this::updateSendBtn);
        updateSendBtn();
        findViewById(R.id.rowChargeReqAmount).setOnClickListener(v -> toggleKeypad());
        etMemo.setOnFocusChangeListener((v, has) -> { if (has) keypad.setVisibility(View.GONE); });

        btnSend.setOnClickListener(v -> {
            long fen = keypad.fen();
            if (fen <= 0) return;
            doSend(fen, etMemo.getText().toString().trim(), tvName.getText().toString());
        });

        // 拉取付款方（付款码主人）资料
        new Thread(() -> {
            try {
                Models.ProfileResponse p = api.getUserById(toUserId);
                runOnUiThread(() -> {
                    if (p != null && p.user != null) {
                        String name = p.user.nickname;
                        if (name == null || name.isEmpty()) name = "付款人";
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

    private void doSend(final long fen, final String memo, final String peerName) {
        new Thread(() -> {
            try {
                Models.ChargeResponse r = api.createCharge(toUserId, fen, memo);
                runOnUiThread(() -> {
                    if (r != null && r.success) {
                        Toast.makeText(this, "已向 " + peerName + " 发送收款请求，等待对方确认",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, r != null && r.error != null ? r.error : "发起收款失败",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "发起收款失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
