package com.familychat.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 收货地址簿：列表 + 新增/编辑/删除/设默认。
 * select_mode 下点击地址返回给调用方（结算页选地址）。
 */
public class AddressBookActivity extends AppCompatActivity {

    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_ADDR_ID = "addr_id";
    public static final String EXTRA_ADDR_NAME = "addr_name";
    public static final String EXTRA_ADDR_PHONE = "addr_phone";
    public static final String EXTRA_ADDR_ADDRESS = "addr_address";

    private PreferencesManager prefs;
    private ApiClient api;
    private boolean selectMode;

    private RecyclerView rv;
    private final List<Models.Address> list = new ArrayList<>();
    private AddrAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_address_book);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);

        rv = findViewById(R.id.rvAddresses);
        findViewById(R.id.btnAddrBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddrAdd).setOnClickListener(v ->
                startActivity(new Intent(this, AddressEditActivity.class)));

        adapter = new AddrAdapter();
        rv.setAdapter(adapter);
        rv.setLayoutManager(new LinearLayoutManager(this));

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                Models.AddressListResponse r = api.getAddresses();
                List<Models.Address> l = r != null && r.addresses != null ? r.addresses : new ArrayList<>();
                runOnUiThread(() -> {
                    list.clear();
                    list.addAll(l);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void setDefault(Models.Address a) {
        new Thread(() -> {
            try {
                api.setDefaultAddress(a.id);
                runOnUiThread(this::load);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void delete(Models.Address a) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除地址")
                .setMessage("确定删除该收货地址吗？")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    try {
                        api.deleteAddress(a.id);
                        runOnUiThread(this::load);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    class AddrAdapter extends RecyclerView.Adapter<AddrAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(AddressBookActivity.this)
                    .inflate(R.layout.item_address, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Models.Address a = list.get(pos);
            h.tvName.setText(a.name);
            h.tvPhone.setText(a.phone);
            h.tvDetail.setText(a.address);
            h.tvDefault.setVisibility(a.is_default == 1 ? View.VISIBLE : View.GONE);
            h.btnSetDefault.setVisibility(a.is_default == 1 ? View.GONE : View.VISIBLE);
            h.btnSetDefault.setOnClickListener(v -> setDefault(a));
            h.btnEdit.setOnClickListener(v -> {
                Intent it = new Intent(AddressBookActivity.this, AddressEditActivity.class);
                it.putExtra(AddressEditActivity.EXTRA_ID, a.id);
                it.putExtra(AddressEditActivity.EXTRA_NAME, a.name);
                it.putExtra(AddressEditActivity.EXTRA_PHONE, a.phone);
                it.putExtra(AddressEditActivity.EXTRA_ADDRESS, a.address);
                it.putExtra(AddressEditActivity.EXTRA_IS_DEFAULT, a.is_default == 1);
                startActivity(it);
            });
            h.btnDelete.setOnClickListener(v -> delete(a));
            h.itemView.setOnClickListener(v -> {
                if (selectMode) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_ADDR_ID, a.id);
                    data.putExtra(EXTRA_ADDR_NAME, a.name);
                    data.putExtra(EXTRA_ADDR_PHONE, a.phone);
                    data.putExtra(EXTRA_ADDR_ADDRESS, a.address);
                    setResult(Activity.RESULT_OK, data);
                    finish();
                }
            });
        }

        @Override public int getItemCount() {
            return list.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvPhone;
            final TextView tvDetail;
            final TextView tvDefault;
            final TextView btnSetDefault;
            final TextView btnEdit;
            final TextView btnDelete;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvAddrName);
                tvPhone = v.findViewById(R.id.tvAddrPhone);
                tvDetail = v.findViewById(R.id.tvAddrDetail);
                tvDefault = v.findViewById(R.id.tvAddrDefault);
                btnSetDefault = v.findViewById(R.id.btnAddrSetDefault);
                btnEdit = v.findViewById(R.id.btnAddrEdit);
                btnDelete = v.findViewById(R.id.btnAddrDelete);
            }
        }
    }
}
