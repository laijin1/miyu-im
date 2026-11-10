package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Sha;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 确认订单（淘宝式）：填收货人/手机号/地址（可从地址簿选择/回填）→ 提交订单 → 支付密码付款。
 * 支持两种模式：
 *  - 单商品：product_id/quantity/price/product_name
 *  - 多商品（购物车结算）：items_json = [{product_id,quantity,name,price,image_url,cart_id},...]
 * 支付：单订单 payOrder / 多订单 payOrders（一次密码批量支付）。
 */
public class CheckoutActivity extends AppCompatActivity {

    private static final String PREF_NAME = "mall_receiver_name";
    private static final String PREF_PHONE = "mall_receiver_phone";
    private static final String PREF_ADDR = "mall_receiver_address";

    private PreferencesManager prefs;
    private ApiClient api;

    // 单商品模式
    private int productId;
    private int quantity;
    private long price;      // 分
    private String productName;
    // 多商品模式
    private final List<Map<String, Object>> items = new ArrayList<>();
    private boolean multiMode = false;

    private EditText etName;
    private EditText etPhone;
    private EditText etAddress;
    private LinearLayout llItems;

    private long pendingAmount = 0;
    private List<Integer> pendingOrderIds = new ArrayList<>();
    private String pendingAddressName;
    private String pendingAddressPhone;
    private String pendingAddressAddr;

    /** 地址簿选择结果。 */
    private final ActivityResultLauncher<Intent> addrLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    etName.setText(result.getData().getStringExtra(AddressBookActivity.EXTRA_ADDR_NAME));
                    etPhone.setText(result.getData().getStringExtra(AddressBookActivity.EXTRA_ADDR_PHONE));
                    etAddress.setText(result.getData().getStringExtra(AddressBookActivity.EXTRA_ADDR_ADDRESS));
                }
            });

    /** 支付密码页结果：拿到 6 位 PIN 后支付。 */
    private final ActivityResultLauncher<Intent> pwdLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String pin = result.getData().getStringExtra(PasswordInputActivity.EXTRA_PIN);
                    if (pin != null && pin.length() == 6) doPay(pin);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkout);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        etName = findViewById(R.id.etCheckoutName);
        etPhone = findViewById(R.id.etCheckoutPhone);
        etAddress = findViewById(R.id.etCheckoutAddress);
        llItems = findViewById(R.id.llCheckoutItems);

        // 解析模式
        String itemsJson = getIntent().getStringExtra("items_json");
        if (itemsJson != null && !itemsJson.isEmpty()) {
            multiMode = true;
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            try {
                List<Map<String, Object>> parsed = new Gson().fromJson(itemsJson, type);
                if (parsed != null) items.addAll(parsed);
            } catch (Exception ignored) {
            }
            if (items.isEmpty()) { finish(); return; }
            renderItems();
        } else {
            productId = getIntent().getIntExtra("product_id", 0);
            productName = getIntent().getStringExtra("product_name");
            price = getIntent().getLongExtra("price", 0);
            quantity = getIntent().getIntExtra("quantity", 1);
            if (productId <= 0) { finish(); return; }
            if (productName == null) productName = "";
            if (quantity < 1) quantity = 1;
            TextView tvName = findViewById(R.id.tvCheckoutName);
            TextView tvUnit = findViewById(R.id.tvCheckoutUnitPrice);
            TextView tvQty = findViewById(R.id.tvCheckoutQty);
            tvName.setText(productName);
            tvUnit.setText(String.format(Locale.CHINA, "单价 ¥%.2f", price / 100.0));
            tvQty.setText("x" + quantity);
        }
        updateAmounts();

        findViewById(R.id.btnCheckoutBack).setOnClickListener(v -> finish());

        // 上次下单成功回填收货地址
        etName.setText(prefs.getString(PREF_NAME, ""));
        etPhone.setText(prefs.getString(PREF_PHONE, ""));
        etAddress.setText(prefs.getString(PREF_ADDR, ""));

        findViewById(R.id.btnCheckoutPickAddr).setOnClickListener(v -> {
            Intent it = new Intent(this, AddressBookActivity.class);
            it.putExtra(AddressBookActivity.EXTRA_SELECT_MODE, true);
            addrLauncher.launch(it);
        });
        findViewById(R.id.btnCheckoutManageAddr).setOnClickListener(v ->
                startActivity(new Intent(this, AddressBookActivity.class)));

        findViewById(R.id.btnCheckoutSubmit).setOnClickListener(v -> submit());
    }

    /** 渲染多商品行（名称 x数量 单价）。 */
    private void renderItems() {
        llItems.setVisibility(View.VISIBLE);
        findViewById(R.id.tvCheckoutName).setVisibility(View.GONE);
        findViewById(R.id.tvCheckoutUnitPrice).setVisibility(View.GONE);
        findViewById(R.id.tvCheckoutQty).setVisibility(View.GONE);
        llItems.removeAllViews();
        for (Map<String, Object> it : items) {
            String name = it.get("name") instanceof String s ? s : "商品";
            long p = it.get("price") instanceof Number n ? n.longValue() : 0;
            int q = it.get("quantity") instanceof Number n ? n.intValue() : 1;
            TextView row = new TextView(this);
            row.setText(String.format(Locale.CHINA, "%s  x%d  ¥%.2f", name, q, p * q / 100.0));
            row.setTextSize(14);
            row.setTextColor(getResources().getColor(R.color.text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            llItems.addView(row);
        }
    }

    /** 汇总金额（单商品 = price*qty；多商品 = Σ price*qty）。 */
    private void updateAmounts() {
        long total = 0;
        if (multiMode) {
            for (Map<String, Object> it : items) {
                long p = it.get("price") instanceof Number n ? n.longValue() : 0;
                int q = it.get("quantity") instanceof Number n ? n.intValue() : 1;
                total += p * q;
            }
        } else {
            total = price * quantity;
        }
        String text = String.format(Locale.CHINA, "¥%.2f", total / 100.0);
        TextView tvGoods = findViewById(R.id.tvCheckoutGoodsAmount);
        TextView tvTotal = findViewById(R.id.tvCheckoutTotal);
        TextView tvDue = findViewById(R.id.tvCheckoutDue);
        tvGoods.setText(text);
        tvTotal.setText(text);
        tvDue.setText(text);
    }

    private void submit() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String addr = etAddress.getText().toString().trim();

        if (name.isEmpty() || phone.isEmpty() || addr.isEmpty()) {
            Toast.makeText(this, "请填写完整的收货人、手机号和地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            Toast.makeText(this, "手机号格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingAddressName = name;
        pendingAddressPhone = phone;
        pendingAddressAddr = addr;
        pendingAmount = 0;
        pendingOrderIds.clear();

        new Thread(() -> {
            try {
                if (multiMode) {
                    // 购物车批量下单
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (Map<String, Object> it : items) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("product_id", it.get("product_id"));
                        m.put("quantity", it.get("quantity"));
                        payload.add(m);
                    }
                    Models.CreateOrdersResponse r = api.createOrders(payload, name, phone, addr);
                    runOnUiThread(() -> {
                        if (r == null || r.orders == null || r.orders.isEmpty()) {
                            Toast.makeText(this, "下单失败，请稍后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        for (Models.MallOrder o : r.orders) {
                            pendingOrderIds.add(o.id);
                            pendingAmount += o.amount;
                        }
                        openPwdInput();
                    });
                } else {
                    Models.CreateOrdersResponse r = api.createOrders(
                            java.util.Collections.singletonList(java.util.Map.of(
                                    "product_id", productId, "quantity", quantity)),
                            name, phone, addr);
                    runOnUiThread(() -> {
                        if (r == null || r.orders == null || r.orders.isEmpty()) {
                            Toast.makeText(this, "下单失败，请稍后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        for (Models.MallOrder o : r.orders) {
                            pendingOrderIds.add(o.id);
                            pendingAmount += o.amount;
                        }
                        openPwdInput();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "下单失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void openPwdInput() {
        Intent it = new Intent(this, PasswordInputActivity.class);
        it.putExtra(PasswordInputActivity.EXTRA_CONTEXT,
                "商城购物 共 " + String.format(Locale.CHINA, "¥%.2f", pendingAmount / 100.0));
        pwdLauncher.launch(it);
    }

    private void doPay(String pin) {
        String pinHash = Sha.sha256Hex(pin);
        new Thread(() -> {
            try {
                if (pendingOrderIds.size() > 1) {
                    api.payOrders(pendingOrderIds, pinHash);
                } else {
                    api.payOrder(pendingOrderIds.get(0), pinHash);
                }
                // 支付成功：记录收货地址，下次自动回填
                prefs.putString(PREF_NAME, pendingAddressName);
                prefs.putString(PREF_PHONE, pendingAddressPhone);
                prefs.putString(PREF_ADDR, pendingAddressAddr);
                runOnUiThread(() -> {
                    Intent success = new Intent(this, PaymentSuccessActivity.class);
                    success.putExtra(PaymentSuccessActivity.EXTRA_AMOUNT, pendingAmount);
                    success.putExtra(PaymentSuccessActivity.EXTRA_PAYEE, "商城购物");
                    startActivity(success);
                    setResult(RESULT_OK); // 购物车页面据此刷新
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "支付失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
