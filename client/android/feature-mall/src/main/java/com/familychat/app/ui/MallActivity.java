package com.familychat.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
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
import com.familychat.app.utils.Sha;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 商城单页（淘宝式）：首页 | 购物车 | 我的订单 三个视图原位切换，底部导航与主界面一致。
 * 切换 tab 不新开 Activity —— 返回栈干净：任何 tab 上按返回都回到上一个页面。
 */
public class MallActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;

    // ═══════════ 首页（商品双列） ═══════════
    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private ProductAdapter adapter;
    private EditText etMallSearch;
    private TextView tvSection;

    /** 服务端原始顺序（综合）。 */
    private final List<Models.Product> base = new ArrayList<>();
    /** 展示列表（按当前排序模式排列）。 */
    private final List<Models.Product> products = new ArrayList<>();

    private String keyword = "";   // 当前搜索关键词（空=显示全部）
    private String category = "";  // 当前分类过滤（空=全部）
    private int sortMode = 0;      // 0=综合 1=销量 2=价格升 3=价格降

    // 分页
    private int page = 1;
    private boolean hasMore = true;
    private boolean loading = false;
    private static final int PAGE_SIZE = 20;

    private static final int TYPE_EMPTY = 0, TYPE_PRODUCT = 1, TYPE_LOADING = 2;

    // ═══════════ 购物车 ═══════════
    private RecyclerView rvCart;
    private LinearLayout layoutBar;
    private CheckBox ckAll;
    private TextView tvTotal;
    private TextView btnCheckout;
    private TextView btnEdit;
    private final List<Models.CartItem> cartItems = new ArrayList<>();
    private final Set<Integer> checked = new HashSet<>(); // cart_id
    private CartAdapter cartAdapter;
    private boolean editMode = false;
    private boolean cartLoaded = false;

    // ═══════════ 我的订单 ═══════════
    private static final String[][] TABS = {
            {"全部", ""}, {"待付款", "pending"}, {"待发货", "paid"},
            {"待收货", "shipped"}, {"已完成", "completed"},
            {"退款中", "refund"}, {"已退款", "refunded"}
    };
    private static final String[] REFUND_REASONS = {
            "不想要了", "拍错了", "商品与描述不符", "商品质量问题", "卖家发错货", "其他"
    };
    private SwipeRefreshLayout swipeOrders;
    private RecyclerView rvOrders;
    /** 全量订单（服务端返回）。 */
    private final List<Models.MallOrder> orders = new ArrayList<>();
    /** 当前 tab 筛选后的展示列表。 */
    private final List<Models.MallOrder> shownOrders = new ArrayList<>();
    private OrderAdapter ordersAdapter;
    private String selectedTab = "";
    private int pendingPayOrder;
    private boolean ordersLoaded = false;

    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6 && pendingPayOrder > 0) {
                        doPay(pendingPayOrder, pin);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mall);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        // ── 首页 ──
        swipe = findViewById(R.id.swipeMall);
        rv = findViewById(R.id.rvMallProducts);
        etMallSearch = findViewById(R.id.etMallSearch);
        tvSection = findViewById(R.id.tvMallSection);
        swipe.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipe.setOnRefreshListener(this::refreshAll);

        findViewById(R.id.btnMallBack).setOnClickListener(v -> finish());
        etMallSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnMallSearchAction).setOnClickListener(v -> doSearch());

        // 排序栏：综合 / 销量 / 价格（价格点击在"升序↔降序"间切换），并高亮当前选中项
        findViewById(R.id.tabSortDefault).setOnClickListener(v -> { setSortMode(0); updateSortTabUI(); });
        findViewById(R.id.tabSortSales).setOnClickListener(v -> { setSortMode(1); updateSortTabUI(); });
        findViewById(R.id.tabSortPrice).setOnClickListener(v -> {
            setSortMode(sortMode == 2 ? 3 : 2);
            updateSortTabUI();
        });
        updateSortTabUI();

        adapter = new ProductAdapter();
        rv.setAdapter(adapter);
        GridLayoutManager glm = new GridLayoutManager(this, 2);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                int t = adapter.getItemViewType(position);
                return (t == TYPE_PRODUCT) ? 1 : 2;
            }
        });
        rv.setLayoutManager(glm);
        // 触底加载更多
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                if (hasMore && !loading && last >= adapter.getItemCount() - 3) {
                    loadMore();
                }
            }
        });

        // ── 购物车 ──
        rvCart = findViewById(R.id.rvCart);
        layoutBar = findViewById(R.id.layoutCartBar);
        ckAll = findViewById(R.id.ckCartAll);
        tvTotal = findViewById(R.id.tvCartTotal);
        btnCheckout = findViewById(R.id.btnCartCheckout);
        btnEdit = findViewById(R.id.btnMallEdit);
        cartAdapter = new CartAdapter();
        rvCart.setAdapter(cartAdapter);
        rvCart.setLayoutManager(new LinearLayoutManager(this));
        ckAll.setOnCheckedChangeListener((b, isChecked) -> {
            checked.clear();
            if (isChecked) {
                for (Models.CartItem it : cartItems) {
                    if (it.on_sale == 1 && it.stock > 0) checked.add(it.cart_id);
                }
            }
            cartAdapter.notifyDataSetChanged();
            updateCartTotal();
        });
        btnCheckout.setOnClickListener(v -> checkout());
        btnEdit.setOnClickListener(v -> {
            editMode = !editMode;
            btnEdit.setText(editMode ? "完成" : "管理");
            cartAdapter.notifyDataSetChanged();
        });

        // ── 我的订单 ──
        swipeOrders = findViewById(R.id.swipeOrders);
        swipeOrders.setColorSchemeResources(R.color.green, R.color.taobao_orange);
        swipeOrders.setOnRefreshListener(this::loadOrders);
        int[] tabIds = {R.id.tabOrdersAll, R.id.tabOrdersPending, R.id.tabOrdersPaid,
                R.id.tabOrdersShipped, R.id.tabOrdersCompleted,
                R.id.tabOrdersRefunding, R.id.tabOrdersRefunded};
        for (int i = 0; i < tabIds.length; i++) {
            int idx = i;
            findViewById(tabIds[i]).setOnClickListener(v -> {
                selectedTab = TABS[idx][1];
                applyOrdersTabStyle();
                filterOrders();
            });
        }
        applyOrdersTabStyle();
        rvOrders = findViewById(R.id.rvOrders);
        ordersAdapter = new OrderAdapter();
        rvOrders.setAdapter(ordersAdapter);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));

        // 底部导航（与主界面一致的原位切换）
        showMallTab(0);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && !base.isEmpty()) refreshAll(); // 返回时刷新（库存/销量可能变化）
        if (cartLoaded) loadCart();      // 结算/下单返回后刷新购物车
        if (ordersLoaded) loadOrders();  // 支付成功后返回刷新订单状态
    }

    // ═══════════════════ Tab 切换（与主界面导航一致） ═══════════════════

    /** 切换底部 tab：0=首页 1=购物车 2=我的订单（原位切换视图，不新开 Activity）。 */
    void showMallTab(int tab) {
        findViewById(R.id.viewMallHome).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.viewMallCart).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.viewMallOrders).setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.mallSortBar).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.etMallSearch).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnMallSearchAction).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.tvMallTitle).setVisibility(tab == 0 ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tvMallTitle)).setText(tab == 1 ? "购物车" : "我的订单");
        findViewById(R.id.btnMallEdit).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        MallNav.bind(this, tab, this::showMallTab);
        if (tab == 1 && !cartLoaded) { cartLoaded = true; loadCart(); }
        if (tab == 2 && !ordersLoaded) { ordersLoaded = true; loadOrders(); }
    }

    // ═══════════════════ 首页：搜索 / 排序 ═══════════════════

    private void doSearch() {
        keyword = etMallSearch.getText().toString().trim();
        refreshAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etMallSearch.getWindowToken(), 0);
        etMallSearch.clearFocus();
    }

    /** 切换排序模式（0 综合 / 1 销量 / 2 价格升 / 3 价格降）。 */
    private void setSortMode(int mode) {
        sortMode = mode;
        applySort();
    }

    /** 更新排序栏高亮（当前选中项橙色，其余灰色）。 */
    private void updateSortTabUI() {
        int active = sortMode == 1 ? R.id.tabSortSales
                : sortMode >= 2 ? R.id.tabSortPrice : R.id.tabSortDefault;
        setTabColor(R.id.tabSortDefault, active == R.id.tabSortDefault);
        setTabColor(R.id.tabSortSales, active == R.id.tabSortSales);
        setTabColor(R.id.tabSortPrice, active == R.id.tabSortPrice);
    }

    private void setTabColor(int id, boolean active) {
        TextView tv = findViewById(id);
        tv.setTextColor(getColor(active ? R.color.taobao_orange : R.color.text_secondary));
    }

    private void applySort() {
        products.clear();
        products.addAll(base);
        switch (sortMode) {
            case 1:
                Collections.sort(products, (a, b) -> Long.compare(b.sales, a.sales));
                break;
            case 2:
                Collections.sort(products, Comparator.comparingLong(a -> a.price));
                break;
            case 3:
                Collections.sort(products, (a, b) -> Long.compare(b.price, a.price));
                break;
            default:
                break;
        }
        adapter.notifyDataSetChanged();
    }

    // ═══════════════════ 首页：数据加载（分页） ═══════════════════

    private void refreshAll() {
        page = 1;
        hasMore = true;
        swipe.setRefreshing(true);
        fetchPage(true);
    }

    private void loadMore() {
        if (loading) return;
        fetchPage(false);
    }

    private void fetchPage(boolean isRefresh) {
        if (loading) return;
        loading = true;
        adapter.notifyDataSetChanged();
        final int targetPage = page;
        new Thread(() -> {
            try {
                Models.ProductsResponse pr = api.getProductsPage(category, keyword, targetPage, PAGE_SIZE);
                List<Models.Product> p = pr != null && pr.products != null ? pr.products : new ArrayList<>();
                boolean more = pr == null || pr.has_more;
                runOnUiThread(() -> {
                    if (isRefresh) {
                        base.clear();
                        page = 1;
                    } else {
                        page = targetPage + 1;
                    }
                    if (isRefresh || targetPage == 1) {
                        base.clear();
                        base.addAll(p);
                    } else {
                        base.addAll(p);
                    }
                    hasMore = more && !p.isEmpty();
                    loading = false;
                    applySort();
                    updateSection();
                    swipe.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading = false;
                    swipe.setRefreshing(false);
                    if (base.isEmpty()) {
                        Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** 「为你推荐」小节标题：空关键词且非空时显示。 */
    private void updateSection() {
        boolean show = keyword.isEmpty() && category.isEmpty() && !products.isEmpty();
        tvSection.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ═══════════════════ 购物车 ═══════════════════

    private void loadCart() {
        new Thread(() -> {
            try {
                Models.CartResponse r = api.getCart();
                List<Models.CartItem> list = r != null && r.items != null ? r.items : new ArrayList<>();
                runOnUiThread(() -> {
                    cartItems.clear();
                    cartItems.addAll(list);
                    checked.retainAll(cartValidIds());
                    cartAdapter.notifyDataSetChanged();
                    updateCartTotal();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "购物车加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private Set<Integer> cartValidIds() {
        Set<Integer> s = new HashSet<>();
        for (Models.CartItem it : cartItems) s.add(it.cart_id);
        return s;
    }

    private long cartTotalAmount() {
        long total = 0;
        for (Models.CartItem it : cartItems) {
            if (checked.contains(it.cart_id)) total += it.price * it.quantity;
        }
        return total;
    }

    private void updateCartTotal() {
        tvTotal.setText(String.format(Locale.CHINA, "¥%.2f", cartTotalAmount() / 100.0));
        boolean any = !cartItems.isEmpty();
        layoutBar.setVisibility(any ? View.VISIBLE : View.GONE);
        if (any) {
            boolean all = true;
            for (Models.CartItem it : cartItems) {
                if (it.on_sale != 1 || it.stock <= 0) continue;
                if (!checked.contains(it.cart_id)) { all = false; break; }
            }
            ckAll.setOnCheckedChangeListener(null);
            ckAll.setChecked(all);
            ckAll.setOnCheckedChangeListener((b, isChecked) -> {
                checked.clear();
                if (isChecked) {
                    for (Models.CartItem it : cartItems) {
                        if (it.on_sale == 1 && it.stock > 0) checked.add(it.cart_id);
                    }
                }
                cartAdapter.notifyDataSetChanged();
                updateCartTotal();
            });
        }
        btnCheckout.setEnabled(!checked.isEmpty());
    }

    /** 结算：选中的项以 items 模式进入确认订单页。 */
    private void checkout() {
        if (checked.isEmpty()) {
            Toast.makeText(this, "请先勾选要结算的商品", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Models.CartItem it : cartItems) {
            if (checked.contains(it.cart_id)) {
                Map<String, Object> m = new HashMap<>();
                m.put("product_id", it.product_id);
                m.put("quantity", it.quantity);
                m.put("name", it.name);
                m.put("price", it.price);
                m.put("image_url", it.image_url);
                m.put("cart_id", it.cart_id);
                list.add(m);
            }
        }
        Intent it = new Intent(this, CheckoutActivity.class);
        it.putExtra("items_json", new com.google.gson.Gson().toJson(list));
        startActivityForResult(it, 1001);
    }

    private void changeQty(Models.CartItem it, int qty) {
        new Thread(() -> {
            try {
                api.updateCartQuantity(it.cart_id, qty);
                runOnUiThread(this::loadCart);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void deleteCartItem(Models.CartItem it) {
        new Thread(() -> {
            try {
                api.deleteCartItem(it.cart_id);
                runOnUiThread(this::loadCart);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    class CartAdapter extends RecyclerView.Adapter<CartAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(MallActivity.this)
                    .inflate(R.layout.item_cart, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Models.CartItem it = cartItems.get(pos);
            boolean off = it.on_sale != 1 || it.stock <= 0;
            h.ck.setEnabled(!off);
            h.ck.setChecked(!off && checked.contains(it.cart_id));
            h.ck.setOnClickListener(v -> {
                if (checked.contains(it.cart_id)) checked.remove(it.cart_id);
                else checked.add(it.cart_id);
                updateCartTotal();
                cartAdapter.notifyDataSetChanged();
            });
            h.tvName.setText(it.name + (off ? "（已下架/无货）" : ""));
            h.tvPrice.setText(String.format(Locale.CHINA, "¥%.2f", it.price / 100.0));
            h.tvQty.setText(String.valueOf(it.quantity));
            h.tvStock.setText("库存 " + it.stock);
            if (it.image_url != null && !it.image_url.isEmpty()) {
                h.iv.setVisibility(View.VISIBLE);
                h.tvFallback.setVisibility(View.GONE);
                Glide.with(MallActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(it.image_url), prefs.getToken()))
                        .transform(new RoundedCorners(dp(6)))
                        .centerCrop()
                        .into(h.iv);
            } else {
                h.iv.setVisibility(View.GONE);
                h.tvFallback.setVisibility(View.VISIBLE);
                h.tvFallback.setText(it.name != null && !it.name.isEmpty() ? it.name.substring(0, 1) : "商");
            }
            h.btnMinus.setEnabled(!off && it.quantity > 1);
            h.btnPlus.setEnabled(!off && it.quantity < it.stock);
            h.btnMinus.setOnClickListener(v -> changeQty(it, it.quantity - 1));
            h.btnPlus.setOnClickListener(v -> changeQty(it, it.quantity + 1));
            h.btnDelete.setOnClickListener(v -> deleteCartItem(it));
            h.itemView.setOnClickListener(v -> {
                Intent d = new Intent(MallActivity.this, ProductDetailActivity.class);
                d.putExtra("product_id", it.product_id);
                startActivity(d);
            });
        }

        @Override public int getItemCount() {
            return cartItems.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final CheckBox ck;
            final ImageView iv;
            final TextView tvFallback;
            final TextView tvName;
            final TextView tvPrice;
            final TextView tvStock;
            final TextView tvQty;
            final TextView btnMinus;
            final TextView btnPlus;
            final TextView btnDelete;
            VH(View v) {
                super(v);
                ck = v.findViewById(R.id.ckCartItem);
                iv = v.findViewById(R.id.ivCartImage);
                tvFallback = v.findViewById(R.id.tvCartFallback);
                tvName = v.findViewById(R.id.tvCartName);
                tvPrice = v.findViewById(R.id.tvCartPrice);
                tvStock = v.findViewById(R.id.tvCartStock);
                tvQty = v.findViewById(R.id.tvCartQty);
                btnMinus = v.findViewById(R.id.btnCartMinus);
                btnPlus = v.findViewById(R.id.btnCartPlus);
                btnDelete = v.findViewById(R.id.btnCartDelete);
            }
        }
    }

    // ═══════════════════ 我的订单 ═══════════════════

    /** tab 高亮：选中橙粗 + 下划线，未选灰。 */
    private void applyOrdersTabStyle() {
        int[] tabIds = {R.id.tabOrdersAll, R.id.tabOrdersPending, R.id.tabOrdersPaid,
                R.id.tabOrdersShipped, R.id.tabOrdersCompleted,
                R.id.tabOrdersRefunding, R.id.tabOrdersRefunded};
        int[] indIds = {R.id.indOrdersAll, R.id.indOrdersPending, R.id.indOrdersPaid,
                R.id.indOrdersShipped, R.id.indOrdersCompleted,
                R.id.indOrdersRefunding, R.id.indOrdersRefunded};
        for (int i = 0; i < tabIds.length; i++) {
            boolean sel = selectedTab.equals(TABS[i][1]);
            TextView title = (TextView) ((ViewGroup) findViewById(tabIds[i])).getChildAt(0);
            title.setTextColor(sel ? getColor(R.color.taobao_orange) : getColor(R.color.text_secondary));
            title.getPaint().setFakeBoldText(sel);
            findViewById(indIds[i]).setVisibility(sel ? View.VISIBLE : View.GONE);
        }
    }

    /** 在全量 orders 上按当前 tab 过滤到 shownOrders。 */
    private void filterOrders() {
        shownOrders.clear();
        for (Models.MallOrder o : orders) {
            if (selectedTab.isEmpty()) {
                shownOrders.add(o);
            } else if ("refund".equals(selectedTab)) {
                if ("pending".equals(o.refund_status)) shownOrders.add(o);
            } else if ("refunded".equals(selectedTab)) {
                if ("refunded".equals(o.status)) shownOrders.add(o);
            } else if (selectedTab.equals(o.status)) {
                shownOrders.add(o);
            }
        }
        ordersAdapter.notifyDataSetChanged();
    }

    private void loadOrders() {
        swipeOrders.setRefreshing(true);
        new Thread(() -> {
            try {
                Models.MallOrderListResponse r = api.getMyOrders();
                List<Models.MallOrder> list = r != null && r.orders != null ? r.orders : new ArrayList<>();
                runOnUiThread(() -> {
                    orders.clear();
                    orders.addAll(list);
                    filterOrders();
                    swipeOrders.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    swipeOrders.setRefreshing(false);
                    Toast.makeText(this, "订单加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void doPay(int orderId, String pin) {
        new Thread(() -> {
            try {
                api.payOrder(orderId, Sha.sha256Hex(pin));
                runOnUiThread(this::loadOrders);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "支付失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void cancelOrder(Models.MallOrder o) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("取消订单")
                .setMessage("确定取消这笔订单吗？库存将自动恢复。")
                .setPositiveButton("确定", (d, w) -> new Thread(() -> {
                    try {
                        api.cancelOrder(o.id);
                        runOnUiThread(this::loadOrders);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "取消失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("再想想", null)
                .show();
    }

    private void confirmOrder(Models.MallOrder o) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("确认收货")
                .setMessage("确认已收到货吗？确认后订单完成。")
                .setPositiveButton("确认", (d, w) -> new Thread(() -> {
                    try {
                        api.confirmOrder(o.id);
                        runOnUiThread(this::loadOrders);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 申请退款：原因单选 + 说明输入。 */
    private void applyRefund(Models.MallOrder o) {
        final int[] selected = {0};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final android.widget.RadioButton[] radios = new android.widget.RadioButton[REFUND_REASONS.length];
        for (int i = 0; i < REFUND_REASONS.length; i++) {
            final int idx = i;
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(REFUND_REASONS[i]);
            rb.setTextSize(14);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.taobao_orange)));
            rb.setOnClickListener(v -> {
                selected[0] = idx;
                for (int j = 0; j < radios.length; j++) radios[j].setChecked(j == idx);
            });
            radios[i] = rb;
            box.addView(rb);
        }
        radios[0].setChecked(true);
        final EditText etReason = new EditText(this);
        etReason.setHint("补充说明（选填）");
        etReason.setTextSize(13);
        etReason.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.addView(etReason);

        new MaterialAlertDialogBuilder(this)
                .setTitle("申请退款")
                .setMessage("退款金额 " + String.format(Locale.CHINA, "¥%.2f", o.amount / 100.0)
                        + " 将原路退回零钱，请选择退款原因：")
                .setView(box)
                .setPositiveButton("提交申请", (d, w) -> new Thread(() -> {
                    try {
                        String reason = REFUND_REASONS[selected[0]];
                        String extra = etReason.getText().toString().trim();
                        if (!extra.isEmpty()) reason = reason + "：" + extra;
                        api.applyRefund(o.id, reason);
                        runOnUiThread(this::loadOrders);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "申请失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ═══════════════════ 订单状态（静态工具，商家后台复用） ═══════════════════

    static String statusText(String s) {
        if (s == null) return "";
        switch (s) {
            case "pending": return "待付款";
            case "paid": return "待发货";
            case "shipped": return "待收货";
            case "completed": return "已完成";
            case "cancelled": return "已关闭";
            case "refunded": return "已退款";
            default: return s;
        }
    }

    /** 订单主状态 + 退款流程状态组合展示。 */
    static String displayStatus(Models.MallOrder o) {
        if (o == null) return "";
        if ("pending".equals(o.refund_status)) return "退款中";
        if ("approved".equals(o.refund_status)) return "已退款";
        return statusText(o.status);
    }

    static int statusColor(String s) {
        if (s == null) return 0xFF999999;
        switch (s) {
            case "pending": return 0xFF2196F3;
            case "paid": return 0xFF2196F3;
            case "shipped": return 0xFF2196F3;
            case "completed": return 0xFF999999;
            case "cancelled": return 0xFF999999;
            case "refunded": return 0xFF999999;
            default: return 0xFF999999;
        }
    }

    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    static String formatTime(long ms) {
        if (ms <= 0) return "";
        try {
            return SDF.format(new Date(ms));
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════ 订单 Adapter ═══════════════════

    class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int pos) {
            return shownOrders.isEmpty() ? 0 : 1;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                TextView tv = new TextView(MallActivity.this);
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, dp(200)));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(14);
                tv.setTextColor(getColor(R.color.text_secondary));
                tv.setText(selectedTab.isEmpty() ? "还没有订单，去商城逛逛吧"
                        : "没有「" + tabsText(selectedTab) + "」状态的订单");
                return new EmptyVH(tv);
            }
            return new VH(LayoutInflater.from(MallActivity.this)
                    .inflate(R.layout.item_order, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof EmptyVH) return;
            VH h = (VH) holder;
            Models.MallOrder o = shownOrders.get(pos);

            h.tvName.setText(o.product_name != null ? o.product_name : "商品已删除");
            h.tvQty.setText("共" + o.quantity + "件");
            h.tvAmount.setText(String.format(Locale.CHINA, "¥%.2f", o.amount / 100.0));
            h.tvTime.setText(formatTime(o.created_at));

            String status = o.status;
            h.tvStatus.setText(displayStatus(o));
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(dp(12));
            badge.setColor("pending".equals(o.refund_status) ? 0xFFFA5151 : statusColor(status));
            h.tvStatus.setBackground(badge);
            h.tvStatus.setTextColor(Color.WHITE);

            // 物流信息（发货后）
            h.tvLogistics.setVisibility(View.GONE);
            if ("shipped".equals(status) && (o.tracking_no != null && !o.tracking_no.isEmpty())) {
                h.tvLogistics.setVisibility(View.VISIBLE);
                String company = o.shipping_company != null && !o.shipping_company.isEmpty()
                        ? o.shipping_company : "快递";
                h.tvLogistics.setText("📦 " + company + "：" + o.tracking_no
                        + "（" + formatTime(o.shipped_at) + "发货）");
            }

            // 退款信息
            h.tvRefundInfo.setVisibility(View.GONE);
            if ("pending".equals(o.refund_status)) {
                h.tvRefundInfo.setVisibility(View.VISIBLE);
                h.tvRefundInfo.setText("退款申请处理中，等待卖家处理");
            } else if ("rejected".equals(o.refund_status)) {
                h.tvRefundInfo.setVisibility(View.VISIBLE);
                h.tvRefundInfo.setText("退款申请被拒绝：" + (o.refund_reason != null ? o.refund_reason : ""));
            } else if ("refunded".equals(status)) {
                h.tvRefundInfo.setVisibility(View.VISIBLE);
                h.tvRefundInfo.setText("已退款（" + formatTime(o.refund_time) + "）");
            }

            // 图片（圆角）
            if (o.product_image != null && !o.product_image.isEmpty()) {
                h.ivImage.setVisibility(View.VISIBLE);
                h.tvFallback.setVisibility(View.GONE);
                Glide.with(MallActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(o.product_image), prefs.getToken()))
                        .transform(new RoundedCorners(dp(8)))
                        .centerCrop()
                        .into(h.ivImage);
            } else {
                h.ivImage.setVisibility(View.GONE);
                h.tvFallback.setVisibility(View.VISIBLE);
                String n = o.product_name;
                h.tvFallback.setText(n != null && !n.isEmpty() ? n.substring(0, 1) : "商");
            }

            // 操作按钮
            h.rowActions.setVisibility(View.VISIBLE);
            h.btnPay.setVisibility(View.GONE);
            h.btnCancel.setVisibility(View.GONE);
            h.btnConfirm.setVisibility(View.GONE);
            h.btnRefund.setVisibility(View.GONE);
            h.btnReview.setVisibility(View.GONE);
            boolean refunding = "pending".equals(o.refund_status);
            if ("pending".equals(status)) {
                h.btnPay.setVisibility(View.VISIBLE);
                h.btnCancel.setVisibility(View.VISIBLE);
                h.btnPay.setOnClickListener(v -> {
                    pendingPayOrder = o.id;
                    Intent it = new Intent(MallActivity.this, PasswordInputActivity.class);
                    it.putExtra(PasswordInputActivity.EXTRA_CONTEXT,
                            "商城购物 共 " + String.format(Locale.CHINA, "¥%.2f", o.amount / 100.0));
                    pwdLauncher.launch(it);
                });
                h.btnCancel.setOnClickListener(v -> cancelOrder(o));
            } else if (("paid".equals(status) || "shipped".equals(status)) && !refunding) {
                h.btnRefund.setVisibility(View.VISIBLE);
                h.btnRefund.setOnClickListener(v -> applyRefund(o));
                if ("shipped".equals(status)) {
                    h.btnConfirm.setVisibility(View.VISIBLE);
                    h.btnConfirm.setOnClickListener(v -> confirmOrder(o));
                }
            } else if ("shipped".equals(status) && !refunding) {
                h.btnConfirm.setVisibility(View.VISIBLE);
                h.btnConfirm.setOnClickListener(v -> confirmOrder(o));
            } else if ("completed".equals(status)) {
                h.btnReview.setVisibility(View.VISIBLE);
                h.btnReview.setOnClickListener(v -> {
                    Intent it = new Intent(MallActivity.this, ReviewActivity.class);
                    it.putExtra(ReviewActivity.EXTRA_ORDER_ID, o.id);
                    it.putExtra(ReviewActivity.EXTRA_PRODUCT_NAME,
                            o.product_name != null ? o.product_name : "商品");
                    startActivityForResult(it, 2001);
                });
            } else {
                h.rowActions.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() {
            return shownOrders.isEmpty() ? 1 : shownOrders.size();
        }

        class EmptyVH extends RecyclerView.ViewHolder {
            EmptyVH(View v) { super(v); }
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivImage;
            final TextView tvFallback;
            final TextView tvName;
            final TextView tvQty;
            final TextView tvStatus;
            final TextView tvTime;
            final TextView tvAmount;
            final TextView tvLogistics;
            final TextView tvRefundInfo;
            final LinearLayout rowActions;
            final TextView btnPay;
            final TextView btnCancel;
            final TextView btnConfirm;
            final TextView btnRefund;
            final TextView btnReview;
            VH(View v) {
                super(v);
                ivImage = v.findViewById(R.id.ivOrderImage);
                tvFallback = v.findViewById(R.id.tvOrderFallback);
                tvName = v.findViewById(R.id.tvOrderName);
                tvQty = v.findViewById(R.id.tvOrderQty);
                tvStatus = v.findViewById(R.id.tvOrderStatus);
                tvTime = v.findViewById(R.id.tvOrderTime);
                tvAmount = v.findViewById(R.id.tvOrderAmount);
                tvLogistics = v.findViewById(R.id.tvOrderLogistics);
                tvRefundInfo = v.findViewById(R.id.tvOrderRefundInfo);
                rowActions = v.findViewById(R.id.rowOrderActions);
                btnPay = v.findViewById(R.id.btnOrderPay);
                btnCancel = v.findViewById(R.id.btnOrderCancel);
                btnConfirm = v.findViewById(R.id.btnOrderConfirm);
                btnRefund = v.findViewById(R.id.btnOrderRefund);
                btnReview = v.findViewById(R.id.btnOrderReview);
            }
        }
    }

    private static String tabsText(String status) {
        if (status == null) return "";
        switch (status) {
            case "pending": return "待付款";
            case "paid": return "待发货";
            case "shipped": return "待收货";
            case "completed": return "已完成";
            default: return status;
        }
    }

    // ═══════════════════ 首页主列表 ═══════════════════

    class ProductAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int pos) {
            if (products.isEmpty()) return TYPE_EMPTY;
            if (pos >= products.size()) return TYPE_LOADING;
            return TYPE_PRODUCT;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater li = LayoutInflater.from(MallActivity.this);
            switch (viewType) {
                case TYPE_EMPTY:
                    return new EmptyVH(li.inflate(R.layout.item_mall_empty, parent, false));
                case TYPE_LOADING:
                    return new LoadingVH(li.inflate(R.layout.item_mall_loading, parent, false));
                default:
                    return new ProductVH(li.inflate(R.layout.item_product, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof EmptyVH) {
                EmptyVH h = (EmptyVH) holder;
                h.tvTitle.setText(emptyTitle());
                h.tvSub.setText(emptySub());
            } else if (holder instanceof ProductVH) {
                ((ProductVH) holder).bind(products.get(pos));
            }
        }

        @Override public int getItemCount() {
            return products.size() + (products.isEmpty() ? 1 : (hasMore ? 1 : 0));
        }
    }

    private String emptyTitle() {
        return keyword.isEmpty() && category.isEmpty()
                ? "商城空空如也" : "没有找到相关商品";
    }

    private String emptySub() {
        return keyword.isEmpty() && category.isEmpty()
                ? "商城空空如也" : "换个关键词试试吧";
    }

    class EmptyVH extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvSub;
        EmptyVH(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvEmptyTitle);
            tvSub = v.findViewById(R.id.tvEmptySub);
        }
    }

    class LoadingVH extends RecyclerView.ViewHolder {
        LoadingVH(View v) {
            super(v);
        }
    }

    class ProductVH extends RecyclerView.ViewHolder {
        final ImageView ivImage;
        final TextView tvFallback;
        final TextView tvName;
        final TextView tvPrice;
        final TextView tvSales;
        ProductVH(View v) {
            super(v);
            ivImage = v.findViewById(R.id.ivProductImage);
            tvFallback = v.findViewById(R.id.tvProductFallback);
            tvName = v.findViewById(R.id.tvProductName);
            tvPrice = v.findViewById(R.id.tvProductPrice);
            tvSales = v.findViewById(R.id.tvProductSales);
        }

        void bind(Models.Product p) {
            tvName.setText(p.name);
            tvPrice.setText(String.format(Locale.CHINA, "%.2f", p.price / 100.0));
            tvSales.setText(formatSales(p.sales));
            if (p.image_url != null && !p.image_url.isEmpty()) {
                tvFallback.setVisibility(View.GONE);
                ivImage.setVisibility(View.VISIBLE);
                Glide.with(MallActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(p.image_url), prefs.getToken()))
                        .transform(new RoundedCorners(dp(8)))
                        .centerCrop()
                        .into(ivImage);
            } else {
                ivImage.setVisibility(View.GONE);
                tvFallback.setVisibility(View.VISIBLE);
                tvFallback.setText(p.name != null && !p.name.isEmpty()
                        ? p.name.substring(0, 1) : "商");
            }
            itemView.setOnClickListener(v -> {
                Intent it = new Intent(MallActivity.this, ProductDetailActivity.class);
                it.putExtra("product_id", p.id);
                startActivity(it);
            });
        }
    }

    // ═══════════════════ 工具 ═══════════════════

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            loadCart();   // 结算完成：服务端已清理对应购物车项
        } else if (requestCode == 2001) {
            loadOrders(); // 评价成功返回后刷新
        }
    }

    private String formatSales(long sales) {
        if (sales >= 10000) {
            return String.format(Locale.CHINA, "已售%.1f万", sales / 10000.0);
        }
        return "已售" + sales;
    }

    /** 相对路径 → 完整媒体 URL（带鉴权 token，Glide 加载用）。 */
    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return ServerProtocol.baseUrl(prefs.getServer()) + path;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
