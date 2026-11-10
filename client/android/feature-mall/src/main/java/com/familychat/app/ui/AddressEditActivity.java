package com.familychat.app.ui;

import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_mall.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.utils.PreferencesManager;

/**
 * 新增/编辑收货地址。
 */
public class AddressEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "addr_id";
    public static final String EXTRA_NAME = "addr_name";
    public static final String EXTRA_PHONE = "addr_phone";
    public static final String EXTRA_ADDRESS = "addr_address";
    public static final String EXTRA_IS_DEFAULT = "addr_default";

    private PreferencesManager prefs;
    private ApiClient api;

    private EditText etName, etPhone, etDetail;
    private CheckBox ckDefault;
    private int editId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_address_edit);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        etName = findViewById(R.id.etAddrName);
        etPhone = findViewById(R.id.etAddrPhone);
        etDetail = findViewById(R.id.etAddrDetail);
        ckDefault = findViewById(R.id.ckAddrDefault);
        TextView tvTitle = findViewById(R.id.tvAddrEditTitle);
        TextView btnSave = findViewById(R.id.btnAddrEditSave);

        editId = getIntent().getIntExtra(EXTRA_ID, 0);
        if (editId > 0) {
            tvTitle.setText("编辑地址");
            etName.setText(getIntent().getStringExtra(EXTRA_NAME));
            etPhone.setText(getIntent().getStringExtra(EXTRA_PHONE));
            etDetail.setText(getIntent().getStringExtra(EXTRA_ADDRESS));
            ckDefault.setChecked(getIntent().getBooleanExtra(EXTRA_IS_DEFAULT, false));
        }

        findViewById(R.id.btnAddrEditBack).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String address = etDetail.getText().toString().trim();
        if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
            Toast.makeText(this, "请填写完整的收货人、手机号和地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            Toast.makeText(this, "手机号格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                if (editId > 0) {
                    api.updateAddress(editId, name, phone, address);
                } else {
                    api.addAddress(name, phone, address);
                }
                if (ckDefault.isChecked()) {
                    // 保存后设为默认：编辑时直接设；新增时找 id 最大（最新）的地址
                    if (editId > 0) {
                        api.setDefaultAddress(editId);
                    } else {
                        com.familychat.app.api.Models.AddressListResponse r = api.getAddresses();
                        if (r != null && r.addresses != null && !r.addresses.isEmpty()) {
                            com.familychat.app.api.Models.Address newest = r.addresses.get(0);
                            for (com.familychat.app.api.Models.Address a : r.addresses) {
                                if (a.id > newest.id) newest = a;
                            }
                            api.setDefaultAddress(newest.id);
                        }
                    }
                }
                runOnUiThread(this::finish);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
