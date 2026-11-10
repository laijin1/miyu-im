package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 商品详情（淘宝式）：大图 + 名称/价格/销量/库存 + 描述 + 卖家（进店）+ 数量选择
 * + 收藏 + 加入购物车 + 立即购买 + 商品评价（条数 + 最近 3 条 + 点击看全部）。
 */
public class ProductDetailActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;

    private Models.Product product;
    private int quantity = 1;
    private boolean favorited = false;

    private TextView tvQty;
    private TextView tvTotal;
    private TextView tvSales;
    private TextView btnFav;
    private TextView btnBuy;
    private TextView btnAddCart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);

        final int productId = getIntent().getIntExtra("product_id", 0);
        if (productId <= 0) { finish(); return; }

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnDetailBack).setOnClickListener(v -> finish());

        tvQty = findViewById(R.id.tvDetailQty);
        tvTotal = findViewById(R.id.tvDetailTotal);
        tvSales = findViewById(R.id.tvDetailSales);
        btnFav = findViewById(R.id.btnDetailFav);
        btnBuy = findViewById(R.id.btnDetailBuy);
        btnAddCart = findViewById(R.id.btnDetailAddCart);

        findViewById(R.id.btnDetailMinus).setOnClickListener(v -> {
            if (quantity > 1) { quantity--; refreshQty(); }
        });
        findViewById(R.id.btnDetailPlus).setOnClickListener(v -> {
            if (product != null && quantity < product.stock) { quantity++; refreshQty(); }
            else Toast.makeText(this, "已达库存上限", Toast.LENGTH_SHORT).show();
        });

        btnFav.setOnClickListener(v -> toggleFav());
        btnAddCart.setOnClickListener(v -> addCart());
        btnBuy.setOnClickListener(v -> {
            if (product == null) return;
            if (product.stock <= 0) {
                Toast.makeText(this, "商品已售罄", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent it = new Intent(this, CheckoutActivity.class);
            it.putExtra("product_id", product.id);
            it.putExtra("product_name", product.name);
            it.putExtra("price", product.price);
            it.putExtra("quantity", quantity);
            startActivity(it);
        });

        load(productId);
        loadFavoriteState(productId);
        loadReviews(productId);
    }

    private void load(int productId) {
        new Thread(() -> {
            try {
                Models.ProductDetailResponse r = api.getProduct(productId);
                runOnUiThread(() -> {
                    if (r == null || r.product == null) {
                        Toast.makeText(this, "商品不存在或已下架", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    product = r.product;
                    bind();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void loadFavoriteState(int productId) {
        new Thread(() -> {
            try {
                Models.FavoriteStatusResponse r = api.isFavorite(productId);
                runOnUiThread(() -> {
                    favorited = r != null && r.favorited;
                    updateFavIcon();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void loadReviews(int productId) {
        new Thread(() -> {
            try {
                Models.ReviewListResponse r = api.getProductReviews(productId);
                List<Models.Review> reviews = r != null && r.reviews != null
                        ? r.reviews : new ArrayList<>();
                runOnUiThread(() -> renderReviews(reviews));
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** 评价区：条数 + 最近 3 条；点击看全部（Dialog）。 */
    private void renderReviews(List<Models.Review> reviews) {
        TextView tvCount = findViewById(R.id.tvDetailReviewCount);
        LinearLayout ll = findViewById(R.id.llDetailReviews);
        tvCount.setText(reviews.isEmpty() ? "暂无评价" : "共 " + reviews.size() + " 条");
        ll.removeAllViews();
        int show = Math.min(reviews.size(), 3);
        for (int i = 0; i < show; i++) {
            Models.Review rv = reviews.get(i);
            TextView row = new TextView(this);
            String stars = stars(rv.rating);
            row.setText(stars + "  " + (rv.nickname != null ? rv.nickname : "匿名用户")
                    + "\n" + (rv.content != null && !rv.content.isEmpty() ? rv.content : "此用户未填写评价内容"));
            row.setTextSize(13);
            row.setTextColor(getResources().getColor(R.color.text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            row.setLayoutParams(lp);
            ll.addView(row);
        }
        if (!reviews.isEmpty()) {
            View line = new View(this);
            line.setBackgroundColor(getResources().getColor(R.color.border));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            llp.topMargin = dp(10);
            line.setLayoutParams(llp);
            ll.addView(line);
            TextView more = new TextView(this);
            more.setText(reviews.size() > 3 ? "查看全部 " + reviews.size() + " 条评价 ›" : "查看全部评价 ›");
            more.setTextSize(13);
            more.setTextColor(getResources().getColor(R.color.taobao_orange));
            more.setPadding(0, dp(8), 0, 0);
            more.setOnClickListener(v -> showAllReviews(reviews));
            ll.addView(more);
        }
    }

    private void showAllReviews(List<Models.Review> reviews) {
        StringBuilder sb = new StringBuilder();
        for (Models.Review rv : reviews) {
            sb.append(stars(rv.rating)).append("  ")
                    .append(rv.nickname != null ? rv.nickname : "匿名用户").append("\n")
                    .append(rv.content != null && !rv.content.isEmpty() ? rv.content : "此用户未填写评价内容")
                    .append("\n\n");
        }
        if (sb.length() == 0) sb.append("暂无评价");
        new android.app.AlertDialog.Builder(this)
                .setTitle("商品评价（" + reviews.size() + "）")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show();
    }

    private String stars(int rating) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < rating ? "★" : "☆");
        return sb.toString();
    }

    private void bind() {
        Models.Product p = product;
        TextView tvName = findViewById(R.id.tvDetailName);
        TextView tvPrice = findViewById(R.id.tvDetailPrice);
        TextView tvSeller = findViewById(R.id.tvDetailSeller);
        TextView tvDesc = findViewById(R.id.tvDetailDesc);
        ImageView ivImage = findViewById(R.id.ivDetailImage);
        TextView tvFallback = findViewById(R.id.tvDetailFallback);

        tvName.setText(p.name);
        tvPrice.setText(String.format(Locale.CHINA, "%.2f", p.price / 100.0));
        tvSales.setText(formatSales(p.sales) + " · 库存 " + p.stock);
        String seller = p.seller_nickname != null && !p.seller_nickname.isEmpty()
                ? p.seller_nickname : "官方";
        tvSeller.setText(seller);
        tvDesc.setText(p.description != null && !p.description.isEmpty()
                ? p.description : "暂无描述");

        // 进店（点击店铺卡进入卖家主页）
        View shopCard = tvSeller.getParent() instanceof View ? (View) tvSeller.getParent() : null;
        if (shopCard != null) {
            shopCard.setOnClickListener(v -> {
                if (p.seller_id <= 0) return;
                Intent it = new Intent(this, ShopActivity.class);
                it.putExtra(ShopActivity.EXTRA_SELLER_ID, p.seller_id);
                it.putExtra(ShopActivity.EXTRA_SELLER_NAME, seller);
                startActivity(it);
            });
        }

        // 下架/售罄态：下架或没库存都不可购买
        if (p.stock <= 0 || p.on_sale != 1) {
            btnBuy.setEnabled(false);
            btnAddCart.setEnabled(false);
            btnBuy.setText(p.on_sale != 1 ? "已下架" : "已售罄");
        }

        if (p.image_url != null && !p.image_url.isEmpty()) {
            tvFallback.setVisibility(View.GONE);
            ivImage.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(ServerProtocol.withToken(fullUrl(p.image_url), prefs.getToken()))
                    .transform(new RoundedCorners(dp(8)))
                    .centerCrop()
                    .into(ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
            tvFallback.setVisibility(View.VISIBLE);
            tvFallback.setText(p.name != null && !p.name.isEmpty() ? p.name.substring(0, 1) : "商");
        }

        refreshQty();
    }

    private void refreshQty() {
        tvQty.setText(String.valueOf(quantity));
        if (product != null) {
            tvTotal.setText(String.format(Locale.CHINA, "合计 ¥%.2f", product.price * quantity / 100.0));
        }
    }

    private void updateFavIcon() {
        btnFav.setText(favorited ? "♥" : "♡");
        btnFav.setTextColor(getResources().getColor(
                favorited ? R.color.amount_red : R.color.text_secondary));
    }

    private void toggleFav() {
        if (product == null) return;
        new Thread(() -> {
            try {
                Models.FavoriteStatusResponse r = api.toggleFavorite(product.id);
                runOnUiThread(() -> {
                    favorited = r != null && r.favorited;
                    updateFavIcon();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void addCart() {
        if (product == null) return;
        if (product.stock <= 0) {
            Toast.makeText(this, "商品已售罄", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                api.addCart(product.id, quantity);
                runOnUiThread(() ->
                        FlashText.flash(btnAddCart, "已加入", "加入购物车"));
            } catch (Exception e) {
                runOnUiThread(() -> FlashText.flash(btnAddCart, "加入失败", "加入购物车"));
            }
        }).start();
    }

    private String formatSales(long sales) {
        if (sales >= 10000) {
            return String.format(Locale.CHINA, "已售%.1f万", sales / 10000.0);
        }
        return "已售" + sales;
    }

    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return ServerProtocol.baseUrl(prefs.getServer()) + path;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
