package com.familychat.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.KeyBackup;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.PreferencesManager;

/**
 * 重置密钥页（第 4 步 + 第 5 步）。
 *
 * 这是本功能里**唯一不可逆**的操作，因此确认流程刻意做重：
 *   第一次确认：完整列出后果（旧记录永久解不开、对方自动更新公钥但会短暂解不开）
 *   第二次确认：必须手动逐字输入「我确认」，确认按钮初始不可点
 *
 * ⚠️ 第 5 步是让"重置"真正生效的关键：
 *   服务端还存着一份「用登录密码加密的旧私钥备份」（KeyBackup）。
 *   只重置本地、不动服务端备份的话，用户一旦**退出登录**或**清数据后重新登录**，
 *   LoginActivity.ensureLoginKeys 会把旧私钥从备份里解回来，重置被静默撤销，
 *   同时客户端还会把旧公钥重新上传覆盖新公钥。
 *   所以重置必须用登录密码把**新**密钥重新加密后覆盖服务端备份。
 *
 * 密码正确性校验：把服务端现有备份取下来用该密码试解一次。
 *   解不开 = 密码不对（或备份损坏）；服务端没有备份说明解不开，
 *   因为无法判断，所以上传后额外提示"下次找回时可能失败"。
 */
public class KeyResetActivity extends AppCompatActivity {

    private static final String TAG = "KeyReset";

    /** 第二次确认必须逐字输入的内容。 */
    private static final String CONFIRM_PHRASE = "我确认";

    private PreferencesManager prefs;

    /** 新生成的密钥对，等待拿到密码后上传备份。 */
    private String newPub;
    private String newPriv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // H4：本页会输入登录密码并展示"重置密钥"的确认流程。
        // FLAG_SECURE 阻止系统截图、最近任务缩略图与投屏（密码输入框尤其需要）。
        // 必须放在任何界面/弹窗创建之前。
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);

        try {
            prefs = PreferencesManager.getInstance(this);
        } catch (Exception e) {
            Log.e(TAG, "PreferencesManager init failed", e);
            Toast.makeText(this, "安全存储初始化失败，无法重置密钥。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 本页没有界面，只作为确认流程的宿主：进来即开始第一次确认。
        showFirstConfirm();
    }

    // ===== 第一次确认 =====

    private void showFirstConfirm() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("重置密钥（危险）")
                .setMessage("重置后，本机将生成一把全新的加密密钥。\n\n"
                        + "· 旧聊天记录将永久无法解密，无法恢复。\n"
                        + "· 对方不需要重新加好友，会自动获取你的新公钥。\n"
                        + "· 在对方获取到新公钥之前，双方新消息都会显示「消息解密失败」。\n"
                        + "· 服务端保存的密钥备份会用你的登录密码同步更新；"
                        + "若更新失败，重置可能在下次登录时被旧备份撤销。")
                .setPositiveButton("继续（危险）", (d, w) -> showSecondConfirm())
                .setNegativeButton("取消", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // ===== 第二次确认：手动输入「我确认」 =====

    private void showSecondConfirm() {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_reset_typed, null);
        final EditText et = content.findViewById(R.id.etResetConfirm);

        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("再次确认")
                        .setView(content)
                        .setPositiveButton("确认重置", (d, w) -> onConfirmedReset())
                        .setNegativeButton("取消", (d, w) -> finish())
                        .setOnCancelListener(d -> finish())
                        .create();
        dialog.show();

        // 确认按钮初始不可点，只有逐字输入「我确认」才启用。
        // 注意：AlertDialog 的按钮在 show() 之后才存在，不能在 setPositiveButton 里取。
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                boolean ok = CONFIRM_PHRASE.equals(s == null ? "" : s.toString().trim());
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(ok);
            }
        });
    }

    // ===== 执行重置（第 4 步：生成新钥 + 上报公钥）=====

    private void onConfirmedReset() {
        final String oldPub = prefs.getMyPublicKey();

        String[] keys;
        try {
            keys = com.familychat.app.utils.MessageEncryptor.generateKeyPair();
        } catch (Throwable t) {
            Log.e(TAG, "generateKeyPair failed", t);
            AppLog.e(TAG, "重置密钥：生成新密钥对失败", t);
            Toast.makeText(this, "生成新密钥失败，已放弃重置（原密钥未改动）。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (keys == null || keys.length < 2 || keys[0] == null || keys[1] == null) {
            AppLog.e(TAG, "重置密钥：generateKeyPair 返回空");
            Toast.makeText(this, "生成新密钥失败，已放弃重置（原密钥未改动）。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 防御：极端情况下"新钥"与旧钥相同 —— 那等于用户白白烧掉历史记录。
        // 概率极低（2^-256 量级），但检查成本几乎为零，且后果不可逆。
        if (oldPub != null && !oldPub.isEmpty()
                && KeyTransferPayload.isSameKey(oldPub, keys[0])) {
            AppLog.e(TAG, "重置密钥：新密钥与旧密钥相同，已中止");
            Toast.makeText(this, "生成的新密钥与旧密钥相同，已中止重置。请重试。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        newPub = keys[0];
        newPriv = keys[1];

        try {
            prefs.saveKeyPair(newPub, newPriv);
        } catch (Exception e) {
            Log.e(TAG, "saveKeyPair failed", e);
            AppLog.e(TAG, "重置密钥：写入失败", e);
            Toast.makeText(this, "重置失败：无法写入密钥存储。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        AppLog.d(TAG, "密钥已重置, 新指纹="
                + KeyTransferPayload.fingerprintShortOfBase64(newPub));

        // 第 4 步：上报公钥；第 5 步：同步服务端密码备份。两者并行，各自独立报错。
        uploadPublicKey();
        askPasswordForBackup();
    }

    /** 第 4 步：把新公钥上报服务端（后台）。 */
    private void uploadPublicKey() {
        final String pub = newPub;
        new Thread(() -> {
            boolean ok = false;
            String err = null;
            try {
                ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
                ok = api.uploadPublicKey(pub);
            } catch (Exception e) {
                err = e.getMessage();
                Log.e(TAG, "uploadPublicKey failed", e);
            }
            final boolean success = ok;
            final String errMsg = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    AppLog.d(TAG, "重置后公钥上报成功");
                } else {
                    AppLog.e(TAG, "重置后公钥上报失败: " + errMsg);
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("公钥上报失败")
                            .setMessage("本地密钥已重置，但新公钥没能同步到服务器。\n\n"
                                    + "在你恢复网络前，对方仍会用你的旧公钥加密，你将无法解密。\n"
                                    + "稍后在任意聊天页发一条消息会自动重试上报。")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }).start();
    }

    // ===== 第 5 步：用登录密码同步服务端备份 =====

    /**
     * 询问登录密码，用于把**新**密钥重新加密后覆盖服务端备份。
     *
     * 没有输入密码的出口（"稍后再说"）：不走这条就等于本次重置随时可能被
     * 服务端旧备份静默撤销，所以必须明确告知后果，而不是让用户以为已经完事。
     */
    private void askPasswordForBackup() {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_reset_password, null);
        final EditText et = content.findViewById(R.id.etResetPassword);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("同步服务端密钥备份")
                .setView(content)
                .setCancelable(false)
                .setPositiveButton("同步", (d, w) -> {
                    String pwd = et.getText() == null ? "" : et.getText().toString().trim();
                    if (pwd.isEmpty()) {
                        Toast.makeText(this, "未输入密码，未更新服务端备份", Toast.LENGTH_LONG).show();
                        warnBackupNotUpdated();
                        finish();
                        return;
                    }
                    syncBackupWithPassword(pwd);
                })
                .setNegativeButton("稍后再说", (d, w) -> {
                    warnBackupNotUpdated();
                    finish();
                })
                .show();
    }

    /**
     * 用密码加密新密钥并覆盖服务端备份（后台，含密码正确性预检）。
     *
     * ⚠️ H2 修复要点：预检的第一步是**向服务端确认现状**。
     *   旧实现用 `fetchKeyBackup()` 返回的 null 同时表示「服务端没有备份」和「拉取失败」，
     *   于是网络一抖就跳过密码校验、把用错密码加密的新备份传上去，
     *   覆盖掉唯一可恢复的旧备份，而界面还报「已同步更新」。
     *   现在改为 `fetchKeyBackupState()`：**失败抛异常 → 直接拒绝上传**。
     */
    private void syncBackupWithPassword(final String password) {
        AppLog.d(TAG, "开始同步服务端密钥备份…");
        new Thread(() -> {
            final int uid = prefs.getUserId();
            ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());

            // 第一步：确认服务端现状。三种结果必须分开处理，绝不能混为一谈：
            //   ① 问到了且有备份 → 用该密码试解，解不开说明密码错，不上传
            //   ② 问到了但没备份 → 无需预检，直接上传（新用户/首次备份）
            //   ③ 问不到（网络/HTTP/解析失败）→ **拒绝上传**，并要求用户重试
            com.familychat.app.utils.KeyBackupResponse.State state;
            try {
                state = api.fetchKeyBackupState();
            } catch (Exception e) {
                Log.e(TAG, "fetchKeyBackupState failed → 拒绝上传", e);
                AppLog.e(TAG, "无法确认服务端备份状态，已拒绝上传（原因：" + e.getMessage() + "）");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("无法确认服务端备份状态")
                            .setMessage("没能连上服务器确认现有的密钥备份，因此**没有上传任何东西**，"
                                    + "服务端备份保持原样。\n\n"
                                    + "为什么不能直接传：服务端可能存着一份用你的登录密码加密的旧备份，"
                                    + "而本应用无法在离线状态下校验你输入的密码是否正确。"
                                    + "若用错误密码加密后覆盖它，你下次找回时才会发现解不开，那时已无可挽回。\n\n"
                                    + "本地密钥已重置。请检查网络后点「重试同步」。")
                            .setPositiveButton("重试同步", (d, w) -> syncBackupWithPassword(password))
                            .setNegativeButton("稍后再说", (d, w) -> warnBackupNotUpdated())
                            .setCancelable(false)
                            .show();
                });
                return;
            }

            if (state.exists) {
                String existing = state.backupB64;
                String plain = KeyBackup.decryptBackup(existing, password, uid);
                if (plain == null) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        AppLog.e(TAG, "密码预检失败：现有备份解不开");
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle("密码不正确")
                                .setMessage("用这个密码解不开服务器上已有的密钥备份，因此没有更新它。\n\n"
                                        + "本地密钥已重置；服务端仍是旧备份。"
                                        + "下次退出登录或清除数据后重新登录时，旧密钥可能被恢复回来，"
                                        + "使本次重置失效。\n\n"
                                        + "请重新进入本页，用正确的登录密码再重置一次并同步备份。")
                                .setPositiveButton("知道了", (d, w) -> finish())
                                .setCancelable(false)
                                .show();
                    });
                    return;
                }
            } else {
                AppLog.d(TAG, "服务端确认无备份，跳过密码预检");
            }

            // 用登录密码加密「新公钥 + 新私钥」，格式与 LoginActivity/ChangePasswordActivity 一致
            final String combined = newPub + "\n" + newPriv;
            final String backup = KeyBackup.encryptBackup(combined, password, uid);
            if (backup == null) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    AppLog.e(TAG, "备份加密失败");
                    Toast.makeText(this, "密钥备份加密失败，服务端未更新", Toast.LENGTH_LONG).show();
                    warnBackupNotUpdated();
                    finish();
                });
                return;
            }

            boolean ok = false;
            String err = null;
            try {
                ok = api.uploadKeyBackup(backup);
            } catch (Exception e) {
                err = e.getMessage();
                Log.e(TAG, "uploadKeyBackup failed", e);
            }
            final boolean success = ok;
            final String errMsg = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    AppLog.d(TAG, "服务端密钥备份已更新");
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("重置完成")
                            .setMessage("已生成新密钥，并同步更新了服务端的密钥备份。\n\n"
                                    + "· 旧聊天记录已永久无法解密。\n"
                                    + "· 对方会自动获取你的新公钥，不需要重新加好友。\n"
                                    + "· 在对方获取到新公钥前，双方新消息会显示「消息解密失败」。")
                            .setPositiveButton("知道了", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                } else {
                    AppLog.e(TAG, "服务端密钥备份更新失败: " + errMsg);
                    // 不在这里 finish()：warnBackupNotUpdated 的弹窗按钮负责收尾，
                    // 否则弹窗会被立刻关掉（旧代码就是"先 show 再 finish"，用户只能看到一闪）。
                    warnBackupNotUpdated();
                }
            });
        }).start();
    }

    /** 服务端备份未更新时的统一警告：这是"重置可能被静默撤销"的唯一提示机会。 */
    private void warnBackupNotUpdated() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("服务端备份未更新")
                .setMessage("本地密钥已重置，但服务端的密钥备份没有更新。\n\n"
                        + "这意味着：下次你退出登录，或清除数据后重新登录时，"
                        + "旧密钥可能会被自动恢复回来，本次重置等于白做。\n\n"
                        + "建议稍后在网络正常时，重新进入本页再重置一次并将备份同步成功。")
                .setPositiveButton("知道了", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
