package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 卖家店铺主页：店铺名 + 全部在售商品（双列）。
 */
public class ShopActivity extends AppCompatActivity {

    public static final String EXTRA_SELLER_ID = "seller_id";
    public static final String EXTRA_SELLER_NAME = "seller_name";

    private PreferencesManager prefs;
    private ApiClient api;

    private SwipeRefreshLayout swipe;
    private TextView tvShopName;
    private TextView tvShopTitle;
    private TextView tvShopCount;
    private final List<Models.Product> products = new ArrayList<>();
    private ShopAdapter adapter;

    private int sellerId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        sellerId = getIntent().getIntExtra(EXTRA_SELLER_ID, 0);
        String sellerName = getIntent().getStringExtra(EXTRA_SELLER_NAME);

        tvShopName = findViewById(R.id.tvShopName);
        tvShopTitle = findViewById(R.id.tvShopTitle);
        tvShopCount = findViewById(R.id.tvShopCount);
        swipe = findViewById(R.id.swipeShop);

        tvShopName.setText(sellerName != null && !sellerName.isEmpty() ? sellerName + " 的小店" : "店铺");
        findViewById(R.id.btnShopBack).setOnClickListener(v -> finish());
        swipe.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipe.setOnRefreshListener(this::load);

        adapter = new ShopAdapter();
        RecyclerView rv = findViewById(R.id.rvShopProducts);
        rv.setAdapter(adapter);
        rv.setLayoutManager(new GridLayoutManager(this, 2));

        load();
    }

    private void load() {
        swipe.setRefreshing(true);
        new Thread(() -> {
            try {
                Models.ShopResponse r = api.getSellerShop(sellerId);
                List<Models.Product> list = r != null && r.products != null ? r.products : new ArrayList<>();
                runOnUiThread(() -> {
                    products.clear();
                    products.addAll(list);
                    adapter.notifyDataSetChanged();
                    if (r != null && r.shop_name != null && !r.shop_name.isEmpty()) {
                        tvShopTitle.setText(r.shop_name + " 的小店");
                    } else {
                        tvShopTitle.setText(tvShopName.getText());
                    }
                    tvShopCount.setText("共 " + list.size() + " 件商品");
                    swipe.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    swipe.setRefreshing(false);
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    class ShopAdapter extends RecyclerView.Adapter<ShopAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(ShopActivity.this)
                    .inflate(R.layout.item_product, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Models.Product p = products.get(pos);
            h.tvName.setText(p.name);
            h.tvPrice.setText(String.format(Locale.CHINA, "%.2f", p.price / 100.0));
            h.tvSales.setText(formatSales(p.sales));
            if (p.image_url != null && !p.image_url.isEmpty()) {
                h.iv.setVisibility(View.VISIBLE);
                h.tvFallback.setVisibility(View.GONE);
                Glide.with(ShopActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(p.image_url), prefs.getToken()))
                        .transform(new RoundedCorners(dp(8)))
                        .centerCrop()
                        .into(h.iv);
            } else {
                h.iv.setVisibility(View.GONE);
                h.tvFallback.setVisibility(View.VISIBLE);
                h.tvFallback.setText(p.name != null && !p.name.isEmpty() ? p.name.substring(0, 1) : "商");
            }
            h.itemView.setOnClickListener(v -> {
                Intent it = new Intent(ShopActivity.this, ProductDetailActivity.class);
                it.putExtra("product_id", p.id);
                startActivity(it);
            });
        }

        @Override public int getItemCount() {
            return products.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            final TextView tvFallback;
            final TextView tvName;
            final TextView tvPrice;
            final TextView tvSales;
            VH(View v) {
                super(v);
                iv = v.findViewById(R.id.ivProductImage);
                tvFallback = v.findViewById(R.id.tvProductFallback);
                tvName = v.findViewById(R.id.tvProductName);
                tvPrice = v.findViewById(R.id.tvProductPrice);
                tvSales = v.findViewById(R.id.tvProductSales);
            }
        }
    }

    private String formatSales(long sales) {
        if (sales >= 10000) return String.format(Locale.CHINA, "已售%.1f万", sales / 10000.0);
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
