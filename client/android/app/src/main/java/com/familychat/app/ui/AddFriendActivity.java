package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;

/** 添加朋友页（密语式）：顶部搜索框 + 扫一扫 + 我的二维码。 */
public class AddFriendActivity extends AppCompatActivity {

    private static final String TAG = "AddFriendActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_friend);

        findViewById(R.id.btnAddFriendBack).setOnClickListener(v -> finish());

        EditText etSearch = findViewById(R.id.etAddFriendSearch);
        TextView btnSearch = findViewById(R.id.btnAddFriendSearch);
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(etSearch);
                return true;
            }
            return false;
        });
        btnSearch.setOnClickListener(v -> doSearch(etSearch));

        findViewById(R.id.rowAddFriendScan).setOnClickListener(v -> startScan());
        findViewById(R.id.rowAddFriendQr).setOnClickListener(v ->
                startActivity(new Intent(this, MyQrCodeActivity.class)));
    }

    /** 搜索：跳到搜索结果页。 */
    private void doSearch(EditText etSearch) {
        String q = etSearch.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "请输入密语号或账号名", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, SearchUsersActivity.class);
        it.putExtra("query", q);
        startActivity(it);
    }

    private void startScan() {
        new com.google.zxing.integration.android.IntentIntegrator(this)
                .setCaptureActivity(ScanActivity.class)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("扫描二维码")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        com.google.zxing.integration.android.IntentResult result =
                com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            handleScanResult(result.getContents());
        }
    }

    /** 扫到好友码 → 进用户资料页。 */
    private void handleScanResult(String content) {
        if (content != null && content.startsWith("familychat://user?id=")) {
            try {
                int userId = Integer.parseInt(content.substring("familychat://user?id=".length()));
                Intent it = new Intent(this, UserProfileActivity.class);
                it.putExtra("user_id", userId);
                startActivity(it);
                return;
            } catch (Exception e) {
                android.util.Log.e(TAG, "scan parse error", e);
            }
        }
        Toast.makeText(this, "无效的二维码", Toast.LENGTH_SHORT).show();
    }
}
