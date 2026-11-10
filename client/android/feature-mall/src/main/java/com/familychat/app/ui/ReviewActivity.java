package com.familychat.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.utils.PreferencesManager;

/**
 * 发表评价：星级 + 内容，提交到订单。
 */
public class ReviewActivity extends AppCompatActivity {

    public static final String EXTRA_ORDER_ID = "order_id";
    public static final String EXTRA_PRODUCT_NAME = "product_name";

    private PreferencesManager prefs;
    private ApiClient api;

    private int orderId;
    private int rating = 5;
    private EditText etContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        orderId = getIntent().getIntExtra(EXTRA_ORDER_ID, 0);
        String productName = getIntent().getStringExtra(EXTRA_PRODUCT_NAME);

        TextView tvProduct = findViewById(R.id.tvReviewProduct);
        tvProduct.setText(productName != null && !productName.isEmpty() ? productName : "商品");
        etContent = findViewById(R.id.etReviewContent);

        findViewById(R.id.btnReviewBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnReviewSubmit).setOnClickListener(v -> submit());

        buildStars();
    }

    /** 5 个 ★，点击选择星级。 */
    private void buildStars() {
        LinearLayout row = findViewById(R.id.rowStars);
        for (int i = 1; i <= 5; i++) {
            TextView star = new TextView(this);
            star.setText("★");
            star.setTextSize(34);
            star.setTextColor(getResources().getColor(R.color.text_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(44), dp(44));
            star.setLayoutParams(lp);
            star.setGravity(android.view.Gravity.CENTER);
            final int s = i;
            star.setOnClickListener(v -> {
                rating = s;
                for (int j = 0; j < row.getChildCount(); j++) {
                    TextView t = (TextView) row.getChildAt(j);
                    t.setTextColor(getResources().getColor(
                            j < rating ? R.color.taobao_orange : R.color.text_secondary));
                }
            });
            row.addView(star);
        }
        // 默认 5 星高亮
        for (int j = 0; j < row.getChildCount(); j++) {
            ((TextView) row.getChildAt(j)).setTextColor(
                    getResources().getColor(R.color.taobao_orange));
        }
    }

    private void submit() {
        String content = etContent.getText().toString().trim();
        new Thread(() -> {
            try {
                api.addReview(orderId, rating, content);
                runOnUiThread(() -> {
                    Toast.makeText(this, "评价成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
