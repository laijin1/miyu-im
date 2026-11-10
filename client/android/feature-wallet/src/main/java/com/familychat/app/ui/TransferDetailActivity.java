package com.familychat.app.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.PreferencesManager;

import java.util.Locale;

/**
 * 转账详情（密语同款）：金额 + 状态 + 付款方/收款方 + 说明/时间。
 * 待收款时：接收方可「确认收款」/「立即退还」，发起方可「撤回转账」。
 */
public class TransferDetailActivity extends AppCompatActivity {

    private ApiClient api;
    private int tid;
    private int myId;
    private int pending = 0;
    private boolean isReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer_detail);

        tid = getIntent().getIntExtra("transfer_id", 0);
        PreferencesManager prefs = PreferencesManager.getInstance(this);
        myId = prefs.getUserId();
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnTransferDetailBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDetailAccept).setOnClickListener(v -> act("accept"));
        findViewById(R.id.btnDetailRefund).setOnClickListener(v -> act("refund"));

        reload();
    }

    private void reload() {
        new Thread(() -> {
            try {
                Models.TransferDetailResponse r = api.getTransferDetail(tid);
                runOnUiThread(() -> {
                    if (r == null || r.transfer == null) {
                        Toast.makeText(this, "转账记录不存在", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    render(r.transfer);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void render(Models.TransferDetail d) {
        ((TextView) findViewById(R.id.tvDetailAmount))
                .setText(String.format(Locale.CHINA, "¥%.2f", d.amount / 100.0));

        TextView tvStatus = findViewById(R.id.tvDetailStatus);
        pending = "pending".equals(d.status) ? 1 : 0;
        isReceiver = d.to_user == myId;
        String statusText;
        int statusColor = getResources().getColor(R.color.text_secondary);
        if ("pending".equals(d.status)) {
            statusText = isReceiver ? "待收款" : "待收款";
            statusColor = getResources().getColor(R.color.paycode_orange);
        } else if ("accepted".equals(d.status)) {
            statusText = "已收款";
            statusColor = Color.parseColor("#2196F3");
        } else {
            statusText = "已退款";
        }
        tvStatus.setText(statusText);
        tvStatus.setTextColor(statusColor);

        // 付款方 / 收款方
        ((TextView) findViewById(R.id.tvDetailFromName)).setText(nonEmpty(d.from_nickname, "付款人"));
        ((TextView) findViewById(R.id.tvDetailToName)).setText(nonEmpty(d.to_nickname, "收款人"));
        setAvatar(R.id.tvDetailFromAvatar, d.from_nickname, Color.parseColor("#2196F3"));
        setAvatar(R.id.tvDetailToAvatar, d.to_nickname, Color.parseColor("#2196F3"));

        // 说明 / 时间（created_at 是 UTC 文本 → 解析成毫秒后按本地时区完整显示）
        ((TextView) findViewById(R.id.tvDetailMemo)).setText(d.memo == null || d.memo.isEmpty() ? "无" : d.memo);
        String createdText = ChatTimeFormatter.formatFull(ChatTimeFormatter.parseUtc(d.created_at));
        ((TextView) findViewById(R.id.tvDetailTime)).setText(createdText.isEmpty() ? "-" : createdText);

        // 按钮区
        boolean canAccept = pending == 1 && isReceiver;
        boolean canRefund = pending == 1 && (isReceiver || d.from_user == myId);
        findViewById(R.id.btnDetailAccept).setVisibility(canAccept ? View.VISIBLE : View.GONE);
        TextView btnRefund = findViewById(R.id.btnDetailRefund);
        if (canRefund) {
            btnRefund.setVisibility(View.VISIBLE);
            btnRefund.setText(isReceiver ? "立即退还" : "撤回转账");
        } else {
            btnRefund.setVisibility(View.GONE);
        }
    }

    private void act(final String action) {
        new Thread(() -> {
            try {
                Models.WalletResponse r = "accept".equals(action) ? api.acceptTransfer(tid) : api.refundTransfer(tid);
                runOnUiThread(() -> {
                    if (r != null && r.success) {
                        reload();
                    } else {
                        Toast.makeText(this, r != null && r.error != null ? r.error : "操作失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void setAvatar(int resId, String name, int color) {
        TextView tv = findViewById(resId);
        String n = nonEmpty(name, "?");
        tv.setText(n.substring(0, 1));
        try {
            ((GradientDrawable) tv.getBackground()).setColor(color);
        } catch (Exception ignored) {}
    }

    private String nonEmpty(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }
}
