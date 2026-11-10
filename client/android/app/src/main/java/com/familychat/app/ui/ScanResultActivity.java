package com.familychat.app.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.utils.FlashText;

/**
 * 万能扫码结果页：
 * 扫到既不是加好友、也不是收付款的二维码时进入。是网址 → 显示类型"网页链接"+「打开网页」；
 * 是普通文本 → 显示"文本内容"+ 可复制。复制用系统剪贴板，打开网页走系统浏览器。
 */
public class ScanResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_result);

        final String content = getIntent().getStringExtra("content") == null
                ? "" : getIntent().getStringExtra("content");

        boolean isUrl = content.startsWith("http://") || content.startsWith("https://");
        TextView tvType = findViewById(R.id.tvResultType);
        tvType.setText(isUrl ? "网页链接" : "文本内容");

        TextView tvContent = findViewById(R.id.tvResultContent);
        tvContent.setText(content);

        View btnOpenWeb = findViewById(R.id.btnResultOpenWeb);
        if (isUrl) {
            btnOpenWeb.setVisibility(View.VISIBLE);
            btnOpenWeb.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(content)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show();
                }
            });
        }

        final TextView btnResultCopy = findViewById(R.id.btnResultCopy);
        btnResultCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("scan_result", content));
                FlashText.flash(btnResultCopy, "已复制", "复制内容");
            }
        });

        findViewById(R.id.btnResultBack).setOnClickListener(v -> finish());
    }
}
