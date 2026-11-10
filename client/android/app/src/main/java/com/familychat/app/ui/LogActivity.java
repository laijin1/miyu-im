package com.familychat.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.FlashText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 设置→日志：查看应用内报错/崩溃日志（AppLog 写入的 app.log）。
 */
public class LogActivity extends AppCompatActivity {

    private TextView tvLog;
    private ScrollView svLog;
    /** 「复制」按钮：复制反馈直接闪在它自己的文字上。 */
    private TextView btnLogCopy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        tvLog = findViewById(R.id.tvLogContent);
        svLog = findViewById(R.id.svLog);

        ImageButton btnBack = findViewById(R.id.btnLogBack);
        btnBack.setOnClickListener(v -> finish());

        findViewById(R.id.btnLogRefresh).setOnClickListener(v -> refresh());
        btnLogCopy = findViewById(R.id.btnLogCopy);
        btnLogCopy.setOnClickListener(v -> copyLog());
        findViewById(R.id.btnLogClear).setOnClickListener(v -> confirmClear());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // 每次进来都刷新，保证看到最新日志
    }

    private void refresh() {
        String log = AppLog.read();
        if (log == null || log.trim().isEmpty()) {
            tvLog.setText("暂无日志\n\n报错和崩溃信息会记录在这里，方便排查问题。");
        } else {
            tvLog.setText(log);
            // 滚动到最底部（最新）
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyLog() {
        String log = AppLog.read();
        if (log == null || log.trim().isEmpty()) {
            FlashText.flash(btnLogCopy, "暂无日志", "复制");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("app_log", log));
        FlashText.flash(btnLogCopy, "已复制", "复制");
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空日志")
                .setMessage("确定要清空全部日志吗？")
                .setPositiveButton("清空", (d, w) -> {
                    AppLog.clear();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
