package com.familychat.app.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.feature_wallet.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 钱包账单（密语同款）：收入绿 +¥ / 支出黑 -¥，方向 + 类型 + 状态 + 时间。
 * 数据源：GET /api/wallet/bills。
 */
public class BillsActivity extends AppCompatActivity {

    private ApiClient api;
    private List<Models.BillItem> bills = new ArrayList<>();
    private BillAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bills);

        PreferencesManager prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnBillsBack).setOnClickListener(v -> finish());
        tvEmpty = findViewById(R.id.tvBillsEmpty);

        RecyclerView rv = findViewById(R.id.rvBills);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BillAdapter();
        rv.setAdapter(adapter);

        loadBills();
    }

    private void loadBills() {
        new Thread(() -> {
            try {
                Models.BillsResponse res = api.getBills();
                bills = res.bills != null ? res.bills : new ArrayList<>();
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                android.util.Log.e("Bills", "load error", e);
            }
        }).start();
    }

    /** 状态文案（与转账气泡一致）。 */
    static String statusText(String status) {
        if (status == null) return "";
        switch (status) {
            case "pending": return "待收款";
            case "accepted": return "已收款";
            case "refunded": return "已退款";
            case "completed": return "已完成";
            default: return "";
        }
    }

    static String typeText(String type) {
        if (type == null) return "交易";
        switch (type) {
            case "transfer": return "转账";
            case "red_packet": return "红包";
            case "recharge": return "充值";
            case "paycode": return "扫码付款";
            default: return "交易";
        }
    }

    class BillAdapter extends RecyclerView.Adapter<BillAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bill, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Models.BillItem b = bills.get(i);
            boolean income = "income".equals(b.direction);

            // 方向图标：收入绿↑ / 支出浅灰↓
            h.ivDir.setText(income ? "↑" : "↓");
            h.ivDir.setTextColor(Color.WHITE);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(19f);
            g.setColor(income ? Color.parseColor("#2196F3") : Color.parseColor("#B2B2B2"));
            h.ivDir.setBackground(g);

            // 对端
            String peer = b.peer_nickname;
            if (peer == null || peer.isEmpty()) peer = "管理员";
            h.tvPeer.setText(peer);

            // 类型 + 时间
            String time = b.created_at > 0
                    ? new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(b.created_at))
                    : "";
            h.tvType.setText(typeText(b.type) + " · " + time);

            // 金额 + 状态
            h.tvAmount.setText((income ? "+" : "-") + String.format(Locale.CHINA, "¥%.2f", b.amount / 100.0));
            h.tvAmount.setTextColor(income ? Color.parseColor("#2196F3") : getResources().getColor(R.color.text));
            h.tvStatus.setText(statusText(b.status));
            h.tvStatus.setVisibility(b.status == null || b.status.isEmpty() ? View.GONE : View.VISIBLE);
        }

        @Override public int getItemCount() { return bills.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView ivDir, tvPeer, tvType, tvAmount, tvStatus;
            VH(View v) {
                super(v);
                ivDir = v.findViewById(R.id.ivBillDir);
                tvPeer = v.findViewById(R.id.tvBillPeer);
                tvType = v.findViewById(R.id.tvBillType);
                tvAmount = v.findViewById(R.id.tvBillAmount);
                tvStatus = v.findViewById(R.id.tvBillStatus);
            }
        }
    }
}
