package com.familychat.app.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.familychat.app.BuildConfig;
import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.MessageDatabase;
import com.familychat.app.utils.PreferencesManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 设置页面：黑夜模式 / 隐私与安全 / 服务器信息 / 检测更新 / 退出登录。
 */
public class SettingsActivity extends AppCompatActivity {

    private PreferencesManager prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = PreferencesManager.getInstance(this);

        ImageButton btnBack = findViewById(R.id.btnSettingsBack);
        btnBack.setOnClickListener(v -> finish());

        // 服务器信息入口
        TextView tvServer = findViewById(R.id.tvSettingsServer);
        tvServer.setText(prefs.getServer());
        findViewById(R.id.rowServerInfo).setOnClickListener(v ->
                startActivity(new Intent(this, ServerInfoActivity.class)));

        // 隐私与安全
        findViewById(R.id.rowPrivacySecurity).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacySecurityActivity.class)));

        // 检测更新入口 + 当前版本
        TextView tvVersion = findViewById(R.id.tvSettingsVersion);
        tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        findViewById(R.id.rowCheckUpdate).setOnClickListener(v ->
                startActivity(new Intent(this, UpdateActivity.class)));

        // 日志（查看报错）
        findViewById(R.id.rowLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));

        // 恢复聊天记录（E2EE 密钥恢复：从服务器备份找回旧密钥）
        findViewById(R.id.rowRestoreKeys).setOnClickListener(v -> showRestoreDialog());

        // 密钥管理（E2EE 私钥指纹核对 / 换机迁移）
        findViewById(R.id.rowKeyManagement).setOnClickListener(v ->
                startActivity(new Intent(this, KeyManagementActivity.class)));

        // 退出登录
        findViewById(R.id.rowLogout).setOnClickListener(v -> showLogoutDialog());

        Switch swNight = findViewById(R.id.swNightMode);
        swNight.setChecked(isNightMode());
        swNight.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setNightMode(isChecked);
            // 触发当前 Activity 重建并切换主题（AppCompat 会自动 recreate）
            AppCompatDelegate.setDefaultNightMode(
                    isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ===== 密钥管理（指纹短码预览）=====

    /**
     * 刷新「密钥管理」右侧的指纹短码。
     *
     * 放在 onResume 而非 onCreate：
     *   1) 从密钥管理页返回时应当刷新；
     *   2) 聊天页可能刚生成密钥（ChatActivity.ensureOwnPublicKeyUploaded），
     *      回到设置页需要看到最新值。
     *
     * 只读操作：绝不生成密钥、不写存储、不联网（见 KeyManagementActivity 的同类约束）。
     * 整体 try/catch：PreferencesManager 是 fail-closed 的，设置页不应因新增读取而崩溃。
     */
    private void renderKeyFingerprint() {
        try {
            TextView tv = findViewById(R.id.tvSettingsKeyFingerprint);
            if (tv == null) return;

            final String pub = prefs.getMyPublicKey();
            if (pub == null || pub.isEmpty()) {
                tv.setText("未生成");
                return;
            }

            byte[] spki;
            try {
                spki = android.util.Base64.decode(pub, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                android.util.Log.e("Settings", "key fingerprint base64 decode failed", e);
                tv.setText("数据异常");
                return;
            }
            if (spki == null || spki.length == 0) {
                tv.setText("数据异常");
                return;
            }

            String shortCode = KeyTransferPayload.fingerprintShort(spki);
            tv.setText(shortCode.isEmpty() ? "数据异常" : KeyTransferPayload.group4(shortCode));
        } catch (Exception e) {
            android.util.Log.e("Settings", "renderKeyFingerprint failed", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderKeyFingerprint();
    }

    // ===== 恢复聊天记录（E2EE 密钥恢复）=====

    private void showRestoreDialog() {
        final EditText et = new EditText(this);
        et.setHint("输入登录密码");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new MaterialAlertDialogBuilder(this)
                .setTitle("恢复聊天记录")
                .setMessage("将从服务器备份恢复旧密钥，找回显示为「消息解密失败」的历史聊天记录。\n需要输入当前账号的登录密码。")
                .setView(et)
                .setPositiveButton("恢复", (d, w) -> restoreFromBackup(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从服务器「密码加密备份」解密恢复旧密钥对，并同步回服务器公钥。后台线程执行。 */
    private void restoreFromBackup(final String password) {
        if (password.isEmpty()) {
            Toast.makeText(this, "请输入登录密码", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在从服务器恢复密钥...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final int uid = prefs.getUserId();
                ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
                // ⚠️ H2：区分「服务端确认没有备份」与「没能问到」。
                // 旧代码用 fetchKeyBackup() 的 null，于是把网络失败也报成"服务器上没有密钥备份"，
                // 用户会以为备份丢了（或以为没必要恢复），而不是去检查网络。
                String backup;
                try {
                    com.familychat.app.utils.KeyBackupResponse.State st = api.fetchKeyBackupState();
                    if (!st.exists) {
                        runOnUiThread(() -> Toast.makeText(this, "服务器上没有密钥备份", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    backup = st.backupB64;
                } catch (Exception e) {
                    android.util.Log.e("Settings", "fetchKeyBackupState failed", e);
                    runOnUiThread(() -> Toast.makeText(this,
                            "无法连接服务器查询密钥备份，请检查网络后重试", Toast.LENGTH_LONG).show());
                    return;
                }
                String combined = com.familychat.app.utils.KeyBackup.decryptBackup(backup, password, uid);
                if (combined == null) {
                    runOnUiThread(() -> Toast.makeText(this, "密码错误或备份损坏，无法恢复", Toast.LENGTH_SHORT).show());
                    return;
                }
                String[] parts = combined.split("\n", 2);
                if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "备份数据异常，无法恢复", Toast.LENGTH_SHORT).show());
                    return;
                }
                final String pub = parts[0].trim();
                final String priv = parts[1].trim();
                prefs.saveKeyPair(pub, priv);
                // 把恢复的公钥同步回服务器（覆盖误生成的新公钥）
                final boolean uploaded = api.uploadPublicKey(pub);
                com.familychat.app.utils.AppLog.d("Settings",
                        "恢复密钥完成 pub_len=" + pub.length() + " 上传公钥=" + uploaded + " uid=" + uid);
                runOnUiThread(() -> Toast.makeText(this,
                        "恢复成功！历史记录已找回，请重新打开对应聊天查看", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                android.util.Log.e("Settings", "restoreFromBackup failed", e);
                com.familychat.app.utils.AppLog.e("Settings", "恢复密钥失败", e);
                runOnUiThread(() -> Toast.makeText(this, "恢复失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ===== 退出登录 =====

    private void showLogoutDialog() {
        try {
            new MaterialAlertDialogBuilder(this).setTitle("退出登录").setMessage("确定退出当前账号？")
                    .setPositiveButton("退出", (dialog, which) -> performLogout())
                    .setNegativeButton("取消", null).show();
        } catch (Exception e) {
            performLogout();
        }
    }

    private void performLogout() {
        // 清空登录态（保留服务器地址与账号列表）。
        // 这里不能静默吞掉 prefs.logout() 的异常：若 token 没清掉，
        // 登录页的自动登录路径会立刻把用户弹回主界面（表现为"退不出去"）。
        try {
            prefs.logout();
        } catch (Exception e) {
            android.util.Log.e("Settings", "prefs.logout() failed", e);
            com.familychat.app.utils.AppLog.e("Settings", "prefs.logout() 失败，强制清 token", e);
            // 兜底：无论如何都要清掉 token 与 uid，避免退出后自动登录
            try { prefs.clearToken(); } catch (Exception ignored) {}
            try { prefs.setUserId(0); } catch (Exception ignored) {}
        }
        // 校验 + 留档：退出后 token 必须为空
        com.familychat.app.utils.AppLog.d("Settings",
                "退出后 token=" + (prefs.getToken() == null ? "null(正确)" : "仍残留!") + " uid=" + prefs.getUserId());

        // 停止前台保活服务
        try {
            stopService(new Intent(this, com.familychat.app.ForegroundService.class));
        } catch (Exception ignored) {}

        // 清空本地消息缓存
        try { MessageDatabase.getInstance(this).clearAll(); } catch (Exception ignored) {}

        // 清空任务栈，回到登录页（会一并销毁上层 MainActivity 并断开其 WebSocket）
        try {
            Intent it = new Intent(this, LoginActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(it);
        } catch (Exception e) {
            android.util.Log.e("Settings", "start LoginActivity failed", e);
        }
        finish();
    }
}
