package com.familychat.app.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;

/**
 * 修改密码：旧密码 + 新密码 + 确认新密码，客户端校验后调用服务端接口。
 */
public class ChangePasswordActivity extends AppCompatActivity {

    private EditText etOld, etNew, etConfirm;
    private TextView tvOldErr, tvNewErr, tvConfirmErr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        etOld = findViewById(R.id.etOldPassword);
        etNew = findViewById(R.id.etNewPassword);
        etConfirm = findViewById(R.id.etConfirmPassword);
        tvOldErr = findViewById(R.id.tvOldError);
        tvNewErr = findViewById(R.id.tvNewError);
        tvConfirmErr = findViewById(R.id.tvConfirmError);
        Button btnConfirm = findViewById(R.id.btnConfirmChange);

        ImageButton btnBack = findViewById(R.id.btnChangePwdBack);
        btnBack.setOnClickListener(v -> finish());

        btnConfirm.setOnClickListener(v -> doChange());
    }

    private void doChange() {
        String oldPw = etOld.getText().toString().trim();
        String newPw = etNew.getText().toString().trim();
        String confirmPw = etConfirm.getText().toString().trim();

        tvOldErr.setVisibility(TextView.GONE);
        tvNewErr.setVisibility(TextView.GONE);
        tvConfirmErr.setVisibility(TextView.GONE);

        boolean ok = true;
        if (TextUtils.isEmpty(oldPw)) {
            tvOldErr.setText("旧密码不能为空"); tvOldErr.setVisibility(TextView.VISIBLE); ok = false;
        }
        if (TextUtils.isEmpty(newPw) || newPw.length() < 6) {
            tvNewErr.setText("新密码不能为空，最少6位"); tvNewErr.setVisibility(TextView.VISIBLE); ok = false;
        }
        if (newPw.equals(oldPw)) {
            tvNewErr.setText("新密码不能与旧密码相同"); tvNewErr.setVisibility(TextView.VISIBLE); ok = false;
        }
        if (!newPw.equals(confirmPw)) {
            tvConfirmErr.setText("两次输入的新密码不一致"); tvConfirmErr.setVisibility(TextView.VISIBLE); ok = false;
        }
        if (!ok) return;

        final PreferencesManager prefs = PreferencesManager.getInstance(this);
        final ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
        final Button btn = findViewById(R.id.btnConfirmChange);
        btn.setEnabled(false);
        btn.setText("提交中...");

        new Thread(() -> {
            try {
                Models.AuthResponse resp = api.changePassword(oldPw, newPw);
                // 服务端改密会自增 token_version 使旧 JWT 全部失效，必须换用返回的新 token，
                // 否则下面重新上传密钥备份的请求会全部 401。
                if (resp != null && resp.token != null && !resp.token.isEmpty()) {
                    prefs.setToken(resp.token);
                    api.setToken(resp.token);
                }
                // 密码已改：用新密码重新加密密钥备份并上传（最多重试 3 次），
                // 避免服务器上仍是旧密码加密的备份——重装后新密码解不开就永久丢历史记录。
                final boolean backupOk = reEncryptBackupAndUpload(prefs, api, newPw);
                runOnUiThread(() -> {
                    if (!backupOk) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("密码已修改，但密钥备份更新失败")
                                .setMessage("登录密码已更新，但「密码加密的密钥备份」上传失败。\n\n" +
                                        "若之后卸载重装或清除数据，将无法用新密码恢复历史聊天记录。\n" +
                                        "建议稍后重新登录一次，应用会自动重新上传备份。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("确认修改");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 用新密码重新加密密钥备份并上传（最多重试 3 次）。返回是否成功；本地无密钥视为成功。 */
    private boolean reEncryptBackupAndUpload(PreferencesManager prefs, ApiClient api, String newPw) {
        String pub = prefs.getMyPublicKey();
        String priv = prefs.getMyPrivateKey();
        int uid = prefs.getUserId();
        if (pub == null || priv == null || pub.isEmpty() || priv.isEmpty()) {
            // 本地从未生成过密钥：无需备份
            return true;
        }
        String combined = pub + "\n" + priv;
        String backup = com.familychat.app.utils.KeyBackup.encryptBackup(combined, newPw, uid);
        if (backup == null) {
            android.util.Log.e("E2EE_BACKUP", "change password: encryptBackup failed");
            return false;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (api.uploadKeyBackup(backup)) {
                    android.util.Log.d("E2EE_BACKUP", "change password: backup uploaded (attempt " + attempt + ")");
                    return true;
                }
            } catch (Exception e) {
                android.util.Log.w("E2EE_BACKUP", "change password: upload attempt " + attempt + " failed", e);
            }
            if (attempt < 3) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        }
        android.util.Log.e("E2EE_BACKUP", "change password: backup upload failed after retries");
        return false;
    }
}
