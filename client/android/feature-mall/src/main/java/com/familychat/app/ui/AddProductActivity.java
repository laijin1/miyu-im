package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * 发布/编辑商品（淘宝卖家）：选图（系统相册→压缩→上传 /api/chat/upload）+ 名称/分类/价格/库存/描述。
 * 传 product_id 进入编辑模式（预填字段），保存走 createProduct 或 updateProduct。
 */
public class AddProductActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;

    private int editProductId = 0;   // 0=发布，>0=编辑
    private byte[] imageBytes;       // 待上传的图片字节（选了图还没上传/重选时）
    private String imageUrl = "";    // 已上传的服务端相对路径

    private EditText etName;
    private EditText etCategory;
    private EditText etPrice;
    private EditText etStock;
    private EditText etDesc;
    private ImageView ivImage;
    private View frmImageWrap;
    private View llImageHint;
    private TextView tvChange;

    private final ActivityResultLauncher<Intent> imageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) loadImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_product);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        editProductId = getIntent().getIntExtra("product_id", 0);
        if (editProductId > 0) {
            ((TextView) findViewById(R.id.tvAddTitle)).setText("编辑商品");
        }

        findViewById(R.id.btnAddBack).setOnClickListener(v -> finish());
        findViewById(R.id.frmAddImage).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnAddSave).setOnClickListener(v -> save());

        etName = findViewById(R.id.etAddName);
        etCategory = findViewById(R.id.etAddCategory);
        etPrice = findViewById(R.id.etAddPrice);
        etStock = findViewById(R.id.etAddStock);
        etDesc = findViewById(R.id.etAddDesc);
        ivImage = findViewById(R.id.ivAddImage);
        frmImageWrap = findViewById(R.id.frmAddImageWrap);
        llImageHint = findViewById(R.id.llAddImageHint);
        tvChange = findViewById(R.id.tvAddChange);

        if (editProductId > 0) loadProduct(editProductId);
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        imageLauncher.launch(intent);
    }

    /** 读取选中图片（降采样到≤1200px），本地预览 + 存字节等待上传。 */
    private void loadImage(Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            int sample = 1;
            int maxDim = 1200;
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2;
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            is = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            if (bmp == null) { Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show(); return; }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            imageBytes = baos.toByteArray();

            ivImage.setImageBitmap(bmp);
            showImageChosen();
        } catch (Exception e) {
            Toast.makeText(this, "读取图片失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 已选图/已加载图：显示圆角预览 + 「更换」角标，隐藏虚线空态。 */
    private void showImageChosen() {
        frmImageWrap.setVisibility(View.VISIBLE);
        llImageHint.setVisibility(View.GONE);
        tvChange.setVisibility(View.VISIBLE);
    }

    private void loadProduct(int productId) {
        new Thread(() -> {
            try {
                Models.ProductDetailResponse r = api.getProduct(productId);
                runOnUiThread(() -> {
                    if (r == null || r.product == null) { finish(); return; }
                    Models.Product p = r.product;
                    etName.setText(p.name);
                    etCategory.setText(p.category != null ? p.category : "");
                    etPrice.setText(String.format(Locale.CHINA, "%.2f", p.price / 100.0));
                    etStock.setText(String.valueOf(p.stock));
                    etDesc.setText(p.description != null ? p.description : "");
                    imageUrl = p.image_url != null ? p.image_url : "";
                    if (!imageUrl.isEmpty()) {
                        showImageChosen();
                        Glide.with(this)
                                .load(ServerProtocol.withToken(fullUrl(imageUrl), prefs.getToken()))
                                .centerCrop()
                                .into(ivImage);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show(); finish(); });
            }
        }).start();
    }

    /** 上传图片（如选了新图），完成后保存商品。 */
    private void save() {
        final String name = etName.getText().toString().trim();
        final String category = etCategory.getText().toString().trim();
        final String desc = etDesc.getText().toString().trim();
        final long priceFen;
        final int stock;
        try {
            priceFen = new BigDecimal(etPrice.getText().toString().trim())
                    .multiply(BigDecimal.valueOf(100)).longValue();
        } catch (Exception e) {
            Toast.makeText(this, "请输入正确的价格", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            stock = Integer.parseInt(etStock.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "请输入正确的库存", Toast.LENGTH_SHORT).show();
            return;
        }
        if (name.isEmpty()) { Toast.makeText(this, "商品名称不能为空", Toast.LENGTH_SHORT).show(); return; }
        if (priceFen <= 0) { Toast.makeText(this, "商品价格必须大于 0", Toast.LENGTH_SHORT).show(); return; }
        if (stock < 0) { Toast.makeText(this, "库存不能为负数", Toast.LENGTH_SHORT).show(); return; }

        if (imageBytes != null) {
            final byte[] bytes = imageBytes;
            Toast.makeText(this, "正在上传图片…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    String url = api.uploadFile(bytes, "product_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
                    runOnUiThread(() -> doSave(name, category, priceFen, stock, desc, url));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "上传失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        } else {
            doSave(name, category, priceFen, stock, desc, imageUrl);
        }
    }

    private void doSave(String name, String category, long priceFen, int stock, String desc, String url) {
        new Thread(() -> {
            try {
                if (editProductId > 0) {
                    api.updateProduct(editProductId, name, category, priceFen, stock, desc, url);
                } else {
                    api.createProduct(name, category, priceFen, stock, desc, url);
                }
                runOnUiThread(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return ServerProtocol.baseUrl(prefs.getServer()) + path;
    }
}
