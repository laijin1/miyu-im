package com.familychat.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.QrUtils;

/**
 * 收付款页（密语同款）：二维码收款 | 向商家付款 分段切换。
 * 收款码 = familychat://collect?id=X；付款码 = familychat://pay?id=X（扫码方据此发起收款请求）。
 */
public class PayCodeActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private TextView tvAvatar, tvName;
    private ImageView ivCollectQr, ivPayQr, ivBarcode;
    /** 「保存收款码」按钮：保存反馈直接闪在它自己的文字上。 */
    private TextView btnSaveCollectQr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paycode);

        prefs = PreferencesManager.getInstance(this);
        final int myId = prefs.getUserId();
        final String myName = prefs.getMyNickname();
        final String myColor = prefs.getMyAvatarColor();
        final String myAvatar = prefs.getMyAvatar();

        findViewById(R.id.btnPayCodeBack).setOnClickListener(v -> finish());
        tvAvatar = findViewById(R.id.tvCollectAvatar);
        tvName = findViewById(R.id.tvCollectName);
        ivCollectQr = findViewById(R.id.ivCollectQr);
        ivPayQr = findViewById(R.id.ivPayQr);
        ivBarcode = findViewById(R.id.ivBarcode);

        tvName.setText(myName.isEmpty() ? "我" : myName);
        try {
            GradientDrawable g = (GradientDrawable) tvAvatar.getBackground();
            g.setColor(Color.parseColor(myColor != null && !myColor.isEmpty() ? myColor : "#e8f5e9"));
        } catch (Exception ignored) {}
        tvAvatar.setText(myName.isEmpty() ? "我" : myName.substring(0, 1));

        // 分段切换
        TextView tabCollect = findViewById(R.id.tabCollect);
        TextView tabPay = findViewById(R.id.tabPay);
        tabCollect.setOnClickListener(v -> switchTab(true));
        tabPay.setOnClickListener(v -> switchTab(false));

        // 付款码绿虚线卡片
        GradientDrawable dashed = new GradientDrawable();
        dashed.setShape(GradientDrawable.RECTANGLE);
        dashed.setCornerRadius(dp(12));
        dashed.setColor(Color.WHITE);
        dashed.setStroke(dp(2), Color.parseColor("#2196F3"), dp(4), dp(4));
        findViewById(R.id.payCard).setBackground(dashed);

        // 付款方式（仅支持零钱）
        findViewById(R.id.rowPayMethod).setOnClickListener(v ->
                Toast.makeText(this, "当前仅支持零钱付款", Toast.LENGTH_SHORT).show());

        btnSaveCollectQr = findViewById(R.id.btnSaveCollectQr);
        btnSaveCollectQr.setOnClickListener(v -> saveCollectCard(myId, myName, myColor, myAvatar));

        // 后台生成两张二维码 + 条形码
        new Thread(() -> {
            final Bitmap collectQr = QrUtils.generateQr("familychat://collect?id=" + myId, dp(240));
            final Bitmap payQr = QrUtils.generateQr("familychat://pay?id=" + myId, dp(220));
            final Bitmap barcode = QrUtils.generateBarcode128("familychat://pay?id=" + myId, dp(280), dp(56));
            runOnUiThread(() -> {
                if (collectQr != null) ivCollectQr.setImageBitmap(collectQr);
                if (payQr != null) ivPayQr.setImageBitmap(payQr);
                if (barcode != null) ivBarcode.setImageBitmap(barcode);
            });
        }).start();
    }

    /** 分段切换：高亮选中项，切换内容面板。 */
    private void switchTab(boolean collect) {
        TextView tabCollect = findViewById(R.id.tabCollect);
        TextView tabPay = findViewById(R.id.tabPay);
        tabCollect.setTextColor(collect ? getColorCompat(R.color.wallet_green) : 0xCCFFFFFF);
        tabPay.setTextColor(collect ? 0xCCFFFFFF : getColorCompat(R.color.wallet_green));
        findViewById(R.id.collectPanel).setVisibility(collect ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.payPanel).setVisibility(collect ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    /** 合成「头像+昵称+收款码」卡片位图，存入相册（照 MyQrCodeActivity 的 MediaStore 写法）。 */
    private void saveCollectCard(final int myId, final String name, final String color, final String avatar) {
        final String displayName = name.isEmpty() ? "我" : name;
        new Thread(() -> {
            try {
                Bitmap qr = QrUtils.generateQr("familychat://collect?id=" + myId, 420);
                if (qr == null) {
                    runOnUiThread(() -> FlashText.flash(btnSaveCollectQr, "保存失败", "保存收款码"));
                    return;
                }
                Bitmap card = Bitmap.createBitmap(620, 900, Bitmap.Config.RGB_565);
                Canvas cv = new Canvas(card);
                cv.drawColor(Color.WHITE);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                // 头像圆底
                int cx = 310, cy = 190, r = 80;
                p.setColor(Color.parseColor(color != null && !color.isEmpty() ? color : "#e8f5e9"));
                cv.drawCircle(cx, cy, r, p);
                // 昵称首字
                p.setColor(Color.WHITE);
                p.setTextSize(64);
                p.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = p.getFontMetrics();
                float baseline = cy - (fm.ascent + fm.descent) / 2f;
                cv.drawText(displayName.substring(0, 1), cx, baseline, p);
                // 昵称
                p.setColor(0xFF1A1A1A);
                p.setTextSize(40);
                cv.drawText(displayName, cx, cy + r + 60, p);
                // 收款码
                cv.drawBitmap(qr, (620 - qr.getWidth()) / 2f, cy + r + 90, p);
                saveCardToGallery(card);
                runOnUiThread(() -> FlashText.flash(btnSaveCollectQr, "已保存", "保存收款码"));
            } catch (Exception e) {
                android.util.Log.e("PayCode", "save card error", e);
                runOnUiThread(() -> FlashText.flash(btnSaveCollectQr, "保存失败", "保存收款码"));
            }
        }).start();
    }

    private void saveCardToGallery(Bitmap bmp) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // Android 9-：旧式写入（需 WRITE_EXTERNAL_STORAGE，应用已声明 maxSdk 28）
            String fileName = "paycode_" + System.currentTimeMillis() + ".png";
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(android.provider.MediaStore.Images.Media.DATA,
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES) + "/" + fileName);
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
        } else {
            String fileName = "paycode_" + System.currentTimeMillis() + ".png";
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/密语收款码");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }
}
