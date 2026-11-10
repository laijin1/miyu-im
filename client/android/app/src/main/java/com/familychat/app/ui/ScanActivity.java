package com.familychat.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.familychat.app.R;
import com.familychat.app.utils.QrUtils;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.InputStream;

/**
 * 谷歌扫一扫风格扫码页（竖屏）。
 * 自建布局直接驱动 DecoratedBarcodeView：全屏相机 + 圆角取景框 + 绿色扫描线 + 手电筒。
 * 扫码结果以 zxing 标准 intent 返回，MainActivity 用 IntentIntegrator.parseActivityResult 解析。
 */
public class ScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;

    private DecoratedBarcodeView barcodeView;
    private ImageView btnTorch;
    private boolean permOk = false;
    private boolean started = false;
    private boolean torchOn = false;

    /** 相册选图扫码：ACTION_PICK 直接打开系统相册（与换头像同一套，低版本可靠）。 */
    private final ActivityResultLauncher<Intent> albumLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) decodeFromImage(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Exception ignored) {}
        setContentView(R.layout.activity_scan);

        barcodeView = findViewById(R.id.zxing_barcode_scanner);
        barcodeView.setStatusText("");               // 去掉库自带提示文字
        View vf = barcodeView.getViewFinder();       // 隐藏库自带取景框，用我们的遮罩
        if (vf != null) vf.setVisibility(View.GONE);

        findViewById(R.id.btnScanBack).setOnClickListener(v -> finish());
        btnTorch = findViewById(R.id.btnScanTorch);
        btnTorch.setOnClickListener(v -> toggleTorch());
        findViewById(R.id.btnScanAlbum).setOnClickListener(v -> pickFromAlbum());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            permOk = true;
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permOk) startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) barcodeView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (barcodeView != null) barcodeView.pauseAndWait();
    }

    private void startCamera() {
        if (started) return;
        started = true;
        barcodeView.resume();
        barcodeView.decodeSingle(callback);
    }

    /** 手电筒开关：亮时图标变绿。设备无闪光灯时提示。 */
    private void toggleTorch() {
        if (barcodeView == null) return;
        try {
            if (torchOn) {
                barcodeView.setTorchOff();
                torchOn = false;
                btnTorch.setColorFilter(android.graphics.Color.WHITE);
            } else {
                barcodeView.setTorchOn();
                torchOn = true;
                btnTorch.setColorFilter(android.graphics.Color.rgb(0, 200, 83));
            }
        } catch (Exception e) {
            Toast.makeText(this, "当前设备不支持闪光灯", Toast.LENGTH_SHORT).show();
            torchOn = false;
            btnTorch.setColorFilter(android.graphics.Color.WHITE);
        }
    }

    /** 打开系统相册选图。 */
    private void pickFromAlbum() {
        Intent pick = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (pick.resolveActivity(getPackageManager()) != null) {
            albumLauncher.launch(pick);
        } else {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    /** 后台线程识别图片里的二维码，识别到按相机扫码同款结果返回，否则提示。 */
    private void decodeFromImage(Uri uri) {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                bmp = loadScaledBitmap(uri, 1600);
                final String text = QrUtils.decodeBitmap(bmp);
                if (bmp != null) bmp.recycle();
                if (text == null || text.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "图片中没有识别到二维码", Toast.LENGTH_SHORT).show());
                    return;
                }
                runOnUiThread(() -> finishWithResult(text));
            } catch (Exception e) {
                if (bmp != null) bmp.recycle();
                runOnUiThread(() -> Toast.makeText(this, "读取图片失败，请重试", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 按最大边长采样缩小加载，避免大图 OOM 且加快识别。 */
    private Bitmap loadScaledBitmap(Uri uri, int maxSize) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int w = bounds.outWidth, h = bounds.outHeight;
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= maxSize) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, opt);
        }
    }

    /** 按 zxing 标准结果格式返回，供 MainActivity parseActivityResult 解析。 */
    private void finishWithResult(String text) {
        Intent data = new Intent();
        data.putExtra("SCAN_RESULT", text);
        data.putExtra("SCAN_RESULT_FORMAT", BarcodeFormat.QR_CODE.toString());
        data.putExtra("SCAN_RESULT_ORIENTATION", "0");
        setResult(RESULT_OK, data);
        finish();
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null) return;
            finishWithResult(result.getText());
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permOk = true;
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
