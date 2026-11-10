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
 * 我的收藏：双列商品卡片 + 右上角取消收藏。
 */
public class FavoritesActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;

    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private final List<Models.FavoriteItem> items = new ArrayList<>();
    private FavAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorites);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        swipe = findViewById(R.id.swipeFav);
        rv = findViewById(R.id.rvFavorites);
        findViewById(R.id.btnFavBack).setOnClickListener(v -> finish());
        swipe.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipe.setOnRefreshListener(this::load);

        adapter = new FavAdapter();
        rv.setAdapter(adapter);
        rv.setLayoutManager(new GridLayoutManager(this, 2));

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        swipe.setRefreshing(true);
        new Thread(() -> {
            try {
                Models.FavoritesResponse r = api.getFavorites();
                List<Models.FavoriteItem> list = r != null && r.favorites != null
                        ? r.favorites : new ArrayList<>();
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(list);
                    adapter.notifyDataSetChanged();
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

    private void removeFavorite(Models.FavoriteItem f) {
        new Thread(() -> {
            try {
                api.toggleFavorite(f.product_id);
                runOnUiThread(() -> {
                    items.remove(f);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    class FavAdapter extends RecyclerView.Adapter<FavAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(FavoritesActivity.this)
                    .inflate(R.layout.item_favorite, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Models.FavoriteItem f = items.get(pos);
            h.tvName.setText(f.name);
            h.tvPrice.setText(String.format(Locale.CHINA, "%.2f", f.price / 100.0));
            h.tvSales.setText(formatSales(f.sales));
            if (f.image_url != null && !f.image_url.isEmpty()) {
                h.iv.setVisibility(View.VISIBLE);
                h.tvFallback.setVisibility(View.GONE);
                Glide.with(FavoritesActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(f.image_url), prefs.getToken()))
                        .transform(new RoundedCorners(dp(8)))
                        .centerCrop()
                        .into(h.iv);
            } else {
                h.iv.setVisibility(View.GONE);
                h.tvFallback.setVisibility(View.VISIBLE);
                h.tvFallback.setText(f.name != null && !f.name.isEmpty() ? f.name.substring(0, 1) : "商");
            }
            h.btnRemove.setOnClickListener(v -> removeFavorite(f));
            h.itemView.setOnClickListener(v -> {
                Intent it = new Intent(FavoritesActivity.this, ProductDetailActivity.class);
                it.putExtra("product_id", f.product_id);
                startActivity(it);
            });
        }

        @Override public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            final TextView tvFallback;
            final TextView tvName;
            final TextView tvPrice;
            final TextView tvSales;
            final TextView btnRemove;
            VH(View v) {
                super(v);
                iv = v.findViewById(R.id.ivFavImage);
                tvFallback = v.findViewById(R.id.tvFavFallback);
                tvName = v.findViewById(R.id.tvFavName);
                tvPrice = v.findViewById(R.id.tvFavPrice);
                tvSales = v.findViewById(R.id.tvFavSales);
                btnRemove = v.findViewById(R.id.btnFavRemove);
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
