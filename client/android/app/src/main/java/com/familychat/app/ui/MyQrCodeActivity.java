package com.familychat.app.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.PreferencesManager;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;

public class MyQrCodeActivity extends AppCompatActivity {

    private PreferencesManager prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_qr);

        prefs = PreferencesManager.getInstance(this);

        findViewById(R.id.btnQrBack).setOnClickListener(v -> finish());

        ImageView ivQr = findViewById(R.id.ivQrCode);
        TextView tvNick = findViewById(R.id.tvQrNick);
        TextView tvId = findViewById(R.id.tvQrId);
        MaterialButton btnSave = findViewById(R.id.btnQrSave);

        int userId = prefs.getUserId();
        String nickname = prefs.getMyNickname();
        if (nickname == null || nickname.isEmpty()) nickname = "用户" + userId;

        tvNick.setText(nickname);
        // 界面统一显示 @账号名（唯一身份号）；后台密语号不再展示
        String accountName = prefs.getString("username", "");
        final String displayId = (accountName != null && !accountName.isEmpty())
                ? "@" + accountName : "ID: " + userId;
        final String copyVal = (accountName != null && !accountName.isEmpty())
                ? "@" + accountName : String.valueOf(userId);
        tvId.setText(displayId);
        // 长按复制 @账号名
        tvId.setOnLongClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("账号名", copyVal));
                FlashText.flash(tvId, "已复制", displayId);
            } catch (Exception e) {
                FlashText.flash(tvId, "复制失败", displayId);
            }
            return true;
        });

        // Generate QR code bitmap
        String qrContent = "familychat://user?id=" + userId;
        int size = dp(250);
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ivQr.setImageBitmap(bmp);
        } catch (Exception e) {
            android.util.Log.e("MyQrCode", "QR generate error", e);
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
        }

        btnSave.setOnClickListener(v -> {
            ivQr.setDrawingCacheEnabled(true);
            Bitmap qrBmp = ivQr.getDrawingCache();
            if (qrBmp != null) {
                try {
                    String fileName = "QR_" + userId + ".png";
                    File dir = new File(getCacheDir(), "qr");
                    dir.mkdirs();
                    File file = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    qrBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    // Insert to gallery
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/密语QR");
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        var uri = getContentResolver().insert(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (uri != null) {
                            try (var os = getContentResolver().openOutputStream(uri)) {
                                if (os != null) {
                                    qrBmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                                }
                            }
                        }
                    }
                    FlashText.flash(btnSave, "已保存", "保存到相册");
                } catch (Exception e) {
                    android.util.Log.e("MyQrCode", "save error", e);
                    FlashText.flash(btnSave, "保存失败", "保存到相册");
                }
            }
            ivQr.setDrawingCacheEnabled(false);
        });
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
