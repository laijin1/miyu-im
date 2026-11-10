package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 商家后台（淘宝千牛式）：橙渐变店铺头部（我的商品/待发货/累计收入）+ 商品管理/订单管理双 tab（橙色下划线）。
 * 商品管理：列表 + 编辑/上架下架按钮 + 长按菜单 + 右下「发布商品」。
 * 订单管理=购买账单：买家昵称/手机/收货地址 + 状态筛选（全部/待发货/待收货/已完成），待发货可一键发货。
 */
public class MerchantBackendActivity extends AppCompatActivity {

    /** 订单筛选 tab 对应状态："" = 全部；"refund" = 退款中。 */
    private static final String[][] OF_TABS = {
            {"全部", ""}, {"待发货", "paid"}, {"待收货", "shipped"}, {"已完成", "completed"}, {"退款中", "refund"}
    };

    private PreferencesManager prefs;
    private ApiClient api;

    private final List<Models.Product> products = new ArrayList<>();
    private final List<Models.MallOrder> orders = new ArrayList<>();
    /** 订单筛选后展示列表。 */
    private final List<Models.MallOrder> shownOrders = new ArrayList<>();
    private ProductAdapter productAdapter;
    private OrderAdapter orderAdapter;

    private SwipeRefreshLayout swipeProducts;
    private SwipeRefreshLayout swipeOrders;
    private boolean showOrders = false;
    private String selectedOrderTab = "";

    private final ActivityResultLauncher<Intent> addLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) loadProducts();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_merchant);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnMerchantBack).setOnClickListener(v -> finish());

        swipeProducts = findViewById(R.id.swipeMerchantProducts);
        swipeOrders = findViewById(R.id.swipeMerchantOrders);
        swipeProducts.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipeOrders.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipeProducts.setOnRefreshListener(this::loadAll);
        swipeOrders.setOnRefreshListener(this::loadAll);

        RecyclerView rvProducts = findViewById(R.id.rvMerchantProducts);
        RecyclerView rvOrders = findViewById(R.id.rvMerchantOrders);
        productAdapter = new ProductAdapter();
        orderAdapter = new OrderAdapter();
        rvProducts.setAdapter(productAdapter);
        rvProducts.setLayoutManager(new LinearLayoutManager(this));
        rvOrders.setAdapter(orderAdapter);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.tabMerchantProducts).setOnClickListener(v -> switchTab(false));
        findViewById(R.id.tabMerchantOrders).setOnClickListener(v -> switchTab(true));

        // 订单状态筛选
        int[] ofTabIds = {R.id.tabMofAll, R.id.tabMofPaid, R.id.tabMofShipped, R.id.tabMofCompleted, R.id.tabMofRefund};
        int[] ofIndIds = {R.id.indMofAll, R.id.indMofPaid, R.id.indMofShipped, R.id.indMofCompleted, R.id.indMofRefund};
        for (int i = 0; i < ofTabIds.length; i++) {
            int idx = i;
            findViewById(ofTabIds[i]).setOnClickListener(v -> {
                selectedOrderTab = OF_TABS[idx][1];
                applyOrderFilterStyle();
                filterOrders();
            });
        }

        findViewById(R.id.btnMerchantAdd).setOnClickListener(v ->
                addLauncher.launch(new Intent(this, AddProductActivity.class)));

        switchTab(false);
        applyOrderFilterStyle();
        loadAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAll();   // 从编辑/发布页返回刷新
    }

    private void switchTab(boolean toOrders) {
        showOrders = toOrders;
        swipeProducts.setVisibility(toOrders ? View.GONE : View.VISIBLE);
        swipeOrders.setVisibility(toOrders ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnMerchantAdd).setVisibility(toOrders ? View.GONE : View.VISIBLE);
        findViewById(R.id.rowMerchantOrderFilter).setVisibility(toOrders ? View.VISIBLE : View.GONE);
        applyTabStyle(toOrders);
    }

    /** 主 tab（商品管理/订单管理）高亮：选中橙粗 + 下划线。 */
    private void applyTabStyle(boolean toOrders) {
        TextView pTitle = (TextView) ((ViewGroup) findViewById(R.id.tabMerchantProducts)).getChildAt(0);
        TextView oTitle = (TextView) ((ViewGroup) findViewById(R.id.tabMerchantOrders)).getChildAt(0);
        pTitle.setTextColor(getColorCompat(toOrders ? R.color.text_secondary : R.color.taobao_orange));
        pTitle.getPaint().setFakeBoldText(!toOrders);
        oTitle.setTextColor(getColorCompat(toOrders ? R.color.taobao_orange : R.color.text_secondary));
        oTitle.getPaint().setFakeBoldText(toOrders);
        findViewById(R.id.indMerchantProducts).setVisibility(toOrders ? View.GONE : View.VISIBLE);
        findViewById(R.id.indMerchantOrders).setVisibility(toOrders ? View.VISIBLE : View.GONE);
    }

    /** 订单筛选 tab 高亮。 */
    private void applyOrderFilterStyle() {
        int[] ofTabIds = {R.id.tabMofAll, R.id.tabMofPaid, R.id.tabMofShipped, R.id.tabMofCompleted, R.id.tabMofRefund};
        int[] ofIndIds = {R.id.indMofAll, R.id.indMofPaid, R.id.indMofShipped, R.id.indMofCompleted, R.id.indMofRefund};
        for (int i = 0; i < ofTabIds.length; i++) {
            boolean sel = selectedOrderTab.equals(OF_TABS[i][1]);
            TextView title = (TextView) ((ViewGroup) findViewById(ofTabIds[i])).getChildAt(0);
            title.setTextColor(sel ? getColorCompat(R.color.taobao_orange) : getColorCompat(R.color.text_secondary));
            title.getPaint().setFakeBoldText(sel);
            findViewById(ofIndIds[i]).setVisibility(sel ? View.VISIBLE : View.GONE);
        }
    }

    /** 在订单全量上按筛选 tab 过滤到 shownOrders。 */
    private void filterOrders() {
        shownOrders.clear();
        for (Models.MallOrder o : orders) {
            if (selectedOrderTab.isEmpty()) {
                shownOrders.add(o);
            } else if ("refund".equals(selectedOrderTab)) {
                if ("pending".equals(o.refund_status)) shownOrders.add(o);
            } else if (selectedOrderTab.equals(o.status)) {
                shownOrders.add(o);
            }
        }
        orderAdapter.notifyDataSetChanged();
    }

    /** 并发拉商品 + 订单，更新摘要与两个列表。 */
    private void loadAll() {
        swipeProducts.setRefreshing(true);
        swipeOrders.setRefreshing(true);
        new Thread(() -> {
            try {
                Models.ProductsResponse pr = api.getSellerProducts();
                Models.MallOrderListResponse or = api.getSellerOrders();
                List<Models.Product> p = pr != null && pr.products != null ? pr.products : new ArrayList<>();
                List<Models.MallOrder> o = or != null && or.orders != null ? or.orders : new ArrayList<>();
                runOnUiThread(() -> {
                    products.clear();
                    products.addAll(p);
                    orders.clear();
                    orders.addAll(o);
                    filterOrders();
                    updateSummary();
                    productAdapter.notifyDataSetChanged();
                    swipeProducts.setRefreshing(false);
                    swipeOrders.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    swipeProducts.setRefreshing(false);
                    swipeOrders.setRefreshing(false);
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void loadProducts() {
        new Thread(() -> {
            try {
                Models.ProductsResponse pr = api.getSellerProducts();
                List<Models.Product> p = pr != null && pr.products != null ? pr.products : new ArrayList<>();
                runOnUiThread(() -> {
                    products.clear();
                    products.addAll(p);
                    updateSummary();
                    productAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateSummary() {
        ((TextView) findViewById(R.id.tvSummaryProducts)).setText(String.valueOf(products.size()));
        int pending = 0;
        long income = 0;
        for (Models.MallOrder o : orders) {
            if ("paid".equals(o.status)) pending++;
            if ("paid".equals(o.status) || "shipped".equals(o.status) || "completed".equals(o.status)) {
                income += o.amount;
            }
        }
        ((TextView) findViewById(R.id.tvSummaryPending)).setText(String.valueOf(pending));
        ((TextView) findViewById(R.id.tvSummaryIncome))
                .setText(String.format(Locale.CHINA, "¥%.2f", income / 100.0));
    }

    // ═══════════════════ 商品管理 ═══════════════════

    class ProductAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int pos) {
            return products.isEmpty() ? 0 : 1;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                TextView tv = new TextView(MerchantBackendActivity.this);
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, dp(200)));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(14);
                tv.setTextColor(getColorCompat(R.color.text_secondary));
                tv.setText("还没有发布商品\n点击右下角「发布商品」上架");
                return new EmptyVH(tv);
            }
            return new VH(LayoutInflater.from(MerchantBackendActivity.this)
                    .inflate(R.layout.item_merchant_product, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof EmptyVH) return;
            VH h = (VH) holder;
            Models.Product p = products.get(pos);
            h.tvName.setText(p.name);
            h.tvPrice.setText(String.format(Locale.CHINA, "¥%.2f", p.price / 100.0));
            h.tvStock.setText("库存 " + p.stock + " · 已售 " + p.sales);

            boolean onSale = p.on_sale == 1;
            h.tvStatus.setText(onSale ? "上架中" : "已下架");
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(dp(12));
            badge.setColor(onSale ? 0xFF4CAF50 : 0xFF999999);
            h.tvStatus.setBackground(badge);
            h.tvStatus.setTextColor(Color.WHITE);

            // 上架/下架按钮：上架中显示「下架」描边灰，已下架显示「上架」橙实心
            h.btnToggle.setText(onSale ? "下架" : "上架");
            h.btnToggle.setTextColor(onSale ? getColorCompat(R.color.text_secondary) : Color.WHITE);
            h.btnToggle.setBackgroundResource(onSale ? R.drawable.bg_outline_btn : R.drawable.bg_taobao_btn);

            if (p.image_url != null && !p.image_url.isEmpty()) {
                h.ivImage.setVisibility(View.VISIBLE);
                h.tvFallback.setVisibility(View.GONE);
                Glide.with(MerchantBackendActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(p.image_url), prefs.getToken()))
                        .transform(new RoundedCorners(dp(8)))
                        .centerCrop()
                        .into(h.ivImage);
            } else {
                h.ivImage.setVisibility(View.GONE);
                h.tvFallback.setVisibility(View.VISIBLE);
                h.tvFallback.setText(p.name != null && !p.name.isEmpty() ? p.name.substring(0, 1) : "商");
            }

            h.btnEdit.setOnClickListener(v -> edit(p));
            h.btnToggle.setOnClickListener(v -> toggle(p.id));
            h.itemView.setOnClickListener(v -> showProductMenu(p));
            h.itemView.setOnLongClickListener(v -> { showProductMenu(p); return true; });
        }

        @Override public int getItemCount() {
            return products.isEmpty() ? 1 : products.size();
        }

        class EmptyVH extends RecyclerView.ViewHolder {
            EmptyVH(View v) { super(v); }
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivImage;
            final TextView tvFallback;
            final TextView tvName;
            final TextView tvStatus;
            final TextView tvPrice;
            final TextView tvStock;
            final TextView btnEdit;
            final TextView btnToggle;
            VH(View v) {
                super(v);
                ivImage = v.findViewById(R.id.ivMpImage);
                tvFallback = v.findViewById(R.id.tvMpFallback);
                tvName = v.findViewById(R.id.tvMpName);
                tvStatus = v.findViewById(R.id.tvMpStatus);
                tvPrice = v.findViewById(R.id.tvMpPrice);
                tvStock = v.findViewById(R.id.tvMpStock);
                btnEdit = v.findViewById(R.id.btnMpEdit);
                btnToggle = v.findViewById(R.id.btnMpToggle);
            }
        }
    }

    private void edit(Models.Product p) {
        Intent it = new Intent(this, AddProductActivity.class);
        it.putExtra("product_id", p.id);
        addLauncher.launch(it);
    }

    private void showProductMenu(Models.Product p) {
        String[] items = {"编辑", p.on_sale == 1 ? "下架" : "上架", "删除"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(p.name)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        edit(p);
                    } else if (w == 1) {
                        toggle(p.id);
                    } else {
                        delete(p);
                    }
                })
                .show();
    }

    private void toggle(int productId) {
        new Thread(() -> {
            try {
                api.toggleProduct(productId);
                runOnUiThread(this::loadProducts);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void delete(Models.Product p) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除商品")
                .setMessage("确定删除「" + p.name + "」吗？删除后不可恢复。")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    try {
                        api.deleteProduct(p.id);
                        runOnUiThread(this::loadProducts);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ═══════════════════ 订单管理（购买账单） ═══════════════════

    class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int pos) {
            return shownOrders.isEmpty() ? 0 : 1;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                TextView tv = new TextView(MerchantBackendActivity.this);
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, dp(200)));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(14);
                tv.setTextColor(getColorCompat(R.color.text_secondary));
                tv.setText(selectedOrderTab.isEmpty() ? "还没有订单" : "没有「" + ofTabText(selectedOrderTab) + "」状态的订单");
                return new EmptyVH(tv);
            }
            return new VH(LayoutInflater.from(MerchantBackendActivity.this)
                    .inflate(R.layout.item_merchant_order, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof EmptyVH) return;
            VH h = (VH) holder;
            Models.MallOrder o = shownOrders.get(pos);
            String buyer = o.buyer_nickname != null && !o.buyer_nickname.isEmpty()
                    ? o.buyer_nickname : "买家";
            h.tvBuyer.setText(buyer + "  " + o.receiver_phone);
            h.tvAddress.setText(o.receiver_address);
            h.tvProduct.setText(o.product_name + "  x" + o.quantity);
            h.tvAmount.setText(String.format(Locale.CHINA, "¥%.2f", o.amount / 100.0));
            h.tvTime.setText(MallActivity.formatTime(o.created_at));

            // 状态徽标（退款中显示红色）
            String status = o.status;
            h.tvStatus.setText(MallActivity.displayStatus(o));
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(dp(12));
            badge.setColor("pending".equals(o.refund_status) ? 0xFFFA5151 : MallActivity.statusColor(status));
            h.tvStatus.setBackground(badge);
            h.tvStatus.setTextColor(Color.WHITE);

            // 退款申请信息 + 处理按钮
            boolean refunding = "pending".equals(o.refund_status);
            h.tvRefundInfo.setVisibility(refunding ? View.VISIBLE : View.GONE);
            if (refunding) {
                h.tvRefundInfo.setText("退款申请：" + (o.refund_reason != null ? o.refund_reason : ""));
            }
            h.btnApproveRefund.setVisibility(refunding ? View.VISIBLE : View.GONE);
            h.btnRejectRefund.setVisibility(refunding ? View.VISIBLE : View.GONE);
            h.btnApproveRefund.setOnClickListener(v -> approveRefund(o));
            h.btnRejectRefund.setOnClickListener(v -> rejectRefund(o));

            h.btnShip.setVisibility("paid".equals(status) && !refunding ? View.VISIBLE : View.GONE);
            h.btnShip.setOnClickListener(v -> ship(o));
        }

        @Override public int getItemCount() {
            return shownOrders.isEmpty() ? 1 : shownOrders.size();
        }

        class EmptyVH extends RecyclerView.ViewHolder {
            EmptyVH(View v) { super(v); }
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvBuyer;
            final TextView tvStatus;
            final TextView tvAddress;
            final TextView tvProduct;
            final TextView tvAmount;
            final TextView tvTime;
            final TextView tvRefundInfo;
            final TextView btnShip;
            final TextView btnApproveRefund;
            final TextView btnRejectRefund;
            VH(View v) {
                super(v);
                tvBuyer = v.findViewById(R.id.tvMoBuyer);
                tvStatus = v.findViewById(R.id.tvMoStatus);
                tvAddress = v.findViewById(R.id.tvMoAddress);
                tvProduct = v.findViewById(R.id.tvMoProduct);
                tvAmount = v.findViewById(R.id.tvMoAmount);
                tvTime = v.findViewById(R.id.tvMoTime);
                tvRefundInfo = v.findViewById(R.id.tvMoRefundInfo);
                btnShip = v.findViewById(R.id.btnMoShip);
                btnApproveRefund = v.findViewById(R.id.btnMoApproveRefund);
                btnRejectRefund = v.findViewById(R.id.btnMoRejectRefund);
            }
        }
    }

    private static String ofTabText(String status) {
        for (String[] t : OF_TABS) if (t[1].equals(status)) return t[0];
        return status;
    }

    /** 发货：填快递公司 + 单号。 */
    private void ship(Models.MallOrder o) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText etCompany = new EditText(this);
        etCompany.setHint("快递公司，如：顺丰速运");
        etCompany.setTextSize(14);
        etCompany.setSingleLine(true);
        etCompany.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.addView(etCompany);
        final EditText etNo = new EditText(this);
        etNo.setHint("快递单号");
        etNo.setTextSize(14);
        etNo.setSingleLine(true);
        etNo.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.addView(etNo);

        new MaterialAlertDialogBuilder(this)
                .setTitle("发货")
                .setMessage("确认已发货？买家将收到发货通知。")
                .setView(box)
                .setPositiveButton("确认发货", (d, w) -> {
                    String no = etNo.getText().toString().trim();
                    if (no.isEmpty()) {
                        Toast.makeText(this, "请填写快递单号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            api.shipOrder(o.id,
                                    etCompany.getText().toString().trim(), no);
                            runOnUiThread(this::loadAll);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "发货失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 同意退款：资金原路退回买家。 */
    private void approveRefund(Models.MallOrder o) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("同意退款")
                .setMessage("将向买家退回 " + String.format(Locale.CHINA, "¥%.2f", o.amount / 100.0)
                        + "（从你的零钱余额扣除），确认同意？")
                .setPositiveButton("同意退款", (d, w) -> new Thread(() -> {
                    try {
                        api.approveRefund(o.id);
                        runOnUiThread(this::loadAll);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "退款失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 拒绝退款。 */
    private void rejectRefund(Models.MallOrder o) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("拒绝退款")
                .setMessage("确定拒绝这笔退款申请吗？")
                .setPositiveButton("拒绝", (d, w) -> new Thread(() -> {
                    try {
                        api.rejectRefund(o.id);
                        runOnUiThread(this::loadAll);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ═══════════════════ 工具 ═══════════════════

    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return ServerProtocol.baseUrl(prefs.getServer()) + path;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }
}
