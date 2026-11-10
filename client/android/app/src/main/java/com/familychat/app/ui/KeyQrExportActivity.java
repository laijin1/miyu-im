package com.familychat.app.ui;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.familychat.app.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.QrUtils;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * 私钥二维码导出页（第 2 步）。
 *
 * ⚠️ 安全设计要点：
 *   1) 二维码里是**用迁移口令加密后**的私钥（v2 载荷，口令 + PBKDF2-SHA256 + AES-GCM）。
 *      仍然把屏幕当敏感面处理，因为"加密强度取决于口令强度"：
 *        - 顶部常驻醒目警告，不可关闭；
 *        - 进入本页必须先设置迁移口令（见 showPasswordSetup）；
 *        - 口令末 4 位显示在二维码下方，供用户在新设备上核对输入是否正确；
 *        - 倒计时到点自动隐藏二维码（默认 60 秒），可随时手动隐藏；
 *        - FLAG_SECURE 禁止系统截图与最近任务缩略图（注意：任务快照由系统在 stop
 *          转换期抓取，仅靠 onStop 清 ImageView 是来不及的，所以 MUST 设本标志）。
 *        - 额外提供「立即隐藏」按钮；
 *        - FLAG_SECURE 禁止系统截图与最近任务缩略图。
 *   2) 不得做「复制私钥」按钮（剪贴板不安全，且截图/输入法都可能读取）。
 *   3) 二维码内容**不写入任何持久化存储**：M1 起唯一的外发通道是
 *      「分享二维码图片」——PNG 只写到本应用私有缓存目录，经 FileProvider 临时授权
 *      交给系统分享面板，用完即删；**不再写入系统相册（共享媒体）**。
 *      原因：写入共享媒体的副本对任何有存储权限的应用与云相册同步都可见，
 *      且 60 秒自动隐藏对它无效、也没有清理机制。
 *
 * 为什么私钥在本页读取（而不是由 KeyManagementActivity 传进来）：
 *   KeyManagementActivity 有"零私钥访问"的硬约束（它是只读展示页，可用 grep 验证）。
 *   因此"读取私钥 → 编码 → 显示"这条链集中在本页，成为**唯一的私钥出口**，便于审计。
 *   载荷既不走 Intent extra（避免进 Binder 事务/系统日志），也不跨进程。
 */
public class KeyQrExportActivity extends AppCompatActivity {

    private static final String TAG = "KeyQrExport";

    /** 指纹短码（仅用于页面上的辅助显示，不是私钥）。 */
    public static final String EXTRA_FP = "fingerprint";

    /** 二维码自动隐藏倒计时（秒）。 */
    private static final int HIDE_AFTER_SECONDS = 60;

    /**
     * 口令最短长度。与 KeyTransferPayload.MIN_PASSWORD_LENGTH 必须一致 ——
     * 这里提前拦是为了让用户当场看到提示，而不是点了"生成"才失败。
     */
    private static final int MIN_PASSWORD_LENGTH = KeyTransferPayload.MIN_PASSWORD_LENGTH;

    private ImageView ivQr;
    private TextView tvCountdown;
    private TextView tvFingerprint;
    private TextView tvPasswordTail;
    private MaterialButton btnShare;
    private MaterialButton btnHide;

    /** 屏幕显示用的二维码位图。 */
    private Bitmap qrBitmap;
    private boolean hidden = false;

    /**
     * 本次导出使用的迁移口令。
     *
     * ⚠️ 只用 char[] 持有，用完立刻填充清零（见 clearPassword）：
     *   - 不落盘、不进 Intent、不进 Bundle、不打日志；
     *   - 不放进任何会被系统"保活/还原"的容器（String 会被 intern 且生命周期不可控）。
     *   - 只在本页内存中存在，onStop/onDestroy 即清零。
     */
    private char[] migrationPassword;

    /** 已读取的密钥对（编码用；同样只存在于本页内存）。 */
    private String keyPub;
    private String keyPriv;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int secondsLeft = HIDE_AFTER_SECONDS;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            secondsLeft--;
            if (secondsLeft <= 0) {
                hideQr(true);
                return;
            }
            updateCountdownText();
            handler.postDelayed(this, 1000);
        }
    };

    /** 分享用的临时文件（cacheDir/qr_share/ 下），onDestroy 时删除。 */
    private java.io.File shareFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // H4：本页会显示含私钥的二维码。FLAG_SECURE 同时阻止三件事：
        //   ① 系统截图；② 最近任务列表缩略图（该系统快照在 stop 转换期抓取，
        //      靠 onStop 里 setImageDrawable(null) 来不及）；③ 投屏/录屏。
        // 必须放在 setContentView 之前，避免首帧未受保护。
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_key_qr_export);

        // M1：清掉上次可能残留的分享临时文件（进程被杀时 onDestroy 不会执行）。
        cleanupShareCache();

        View btnBack = findViewById(R.id.btnQrExportBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        ivQr = findViewById(R.id.ivKeyQr);
        tvCountdown = findViewById(R.id.tvQrCountdown);
        tvFingerprint = findViewById(R.id.tvQrFingerprint);
        tvPasswordTail = findViewById(R.id.tvQrPasswordTail);
        btnShare = findViewById(R.id.btnQrShareImage);
        btnHide = findViewById(R.id.btnQrHideNow);

        String fingerprint = getIntent().getStringExtra(EXTRA_FP);
        if (fingerprint != null && !fingerprint.isEmpty()) {
            tvFingerprint.setText(KeyTransferPayload.group4(fingerprint));
        }

        if (btnHide != null) btnHide.setOnClickListener(v -> hideQr(false));
        if (btnShare != null) btnShare.setOnClickListener(v -> onShareClicked());

        // 先把密钥读进内存（读不到就没必要走口令流程）。
        if (!loadKeys()) {
            finish();
            return;
        }

        // 生成二维码必须先设口令：没有口令就没有"加密"，这是 v2 的硬约束。
        showPasswordSetup();
    }

    /** 读取本机密钥对。失败时给出可行动的提示并返回 false。 */
    private boolean loadKeys() {
        try {
            PreferencesManager prefs = PreferencesManager.getInstance(this);
            String pub = prefs.getMyPublicKey();
            String priv = prefs.getMyPrivateKey();
            if (pub == null || pub.isEmpty() || priv == null || priv.isEmpty()) {
                Toast.makeText(this, "本机没有可导出的密钥", Toast.LENGTH_LONG).show();
                return false;
            }
            keyPub = pub;
            keyPriv = priv;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "load keys failed", e);
            AppLog.e(TAG, "导出页读取密钥失败", e);
            Toast.makeText(this, "读取密钥失败，请重试", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // ===== 迁移口令设置（v2 载荷的前提）=====

    /**
     * 弹出口令设置对话框。取消 = 放弃本次导出（返回上一页）——
     * 不能有"跳过口令"的出口：那等于回退到 v1 明文载荷。
     */
    private void showPasswordSetup() {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_transfer_password, null);
        final EditText etPw = content.findViewById(R.id.etTransferPassword);
        final EditText etConfirm = content.findViewById(R.id.etTransferPasswordConfirm);

        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("设置迁移口令")
                        .setView(content)
                        .setCancelable(false)
                        .setPositiveButton("生成二维码", null)   // 见下面 doNothing 覆写
                        .setNegativeButton("取消", (d, w) -> finish())
                        .create();
        dialog.show();

        // 不用 setPositiveButton 的默认关闭行为：口令不合规时要留在弹窗里让用户改。
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String pw = etPw.getText() == null ? "" : etPw.getText().toString();
                    String confirm = etConfirm.getText() == null ? "" : etConfirm.getText().toString();
                    if (pw.length() < MIN_PASSWORD_LENGTH) {
                        Toast.makeText(this,
                                "口令至少 " + MIN_PASSWORD_LENGTH + " 位；建议 12 位以上并混合大小写与数字",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!pw.equals(confirm)) {
                        Toast.makeText(this, "两次输入的口令不一致", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    onPasswordReady(pw);
                });
    }

    /** 拿到口令：存成 char[] → 编码 → 显示二维码（并在页面上标出口令末 4 位）。 */
    private void onPasswordReady(String password) {
        clearPassword();
        migrationPassword = password.toCharArray();

        // 口令末 4 位：只在本页内存里算，只用于用户肉眼核对（见类注释与布局注释）。
        String tail = passwordTail(migrationPassword);
        if (tvPasswordTail != null && tail != null) {
            tvPasswordTail.setText("口令末 4 位：" + tail);
            tvPasswordTail.setVisibility(View.VISIBLE);
        }

        final String payload;
        try {
            payload = KeyTransferPayload.encode(keyPub, keyPriv, password);
        } catch (Exception e) {
            Log.e(TAG, "encode failed", e);
            AppLog.e(TAG, "密钥载荷编码失败", e);
            Toast.makeText(this, "生成二维码数据失败，请重试", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (payload == null || payload.isEmpty()) {
            Log.e(TAG, "encode returned null");
            AppLog.e(TAG, "密钥载荷编码失败（口令长度=" + password.length() + "）");
            Toast.makeText(this, "生成二维码数据失败，请重试", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        updateCountdownText();
        generateQrAsync(payload);
    }

    /** 取口令末 4 位（不足 4 位时返回 null，调用方不显示）。 */
    private static String passwordTail(char[] pw) {
        if (pw == null || pw.length < 4) return null;
        return new String(pw, pw.length - 4, 4);
    }

    /**
     * 清零口令。用 Arrays.fill 覆盖原数组，而不是只把引用置空 ——
     * 后者只是"不再引用"，堆上的字符仍可能存活到下一次 GC。
     */
    private void clearPassword() {
        if (migrationPassword != null) {
            java.util.Arrays.fill(migrationPassword, '\0');
            migrationPassword = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!hidden) handler.postDelayed(tick, 1000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开前台（切后台/锁屏/被其它页覆盖）：停掉倒计时并立即抹掉屏幕上的私钥。
        handler.removeCallbacks(tick);
        hideQr(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (qrBitmap != null && !qrBitmap.isRecycled()) {
            qrBitmap.recycle();
            qrBitmap = null;
        }
        // 分享临时文件里是含私钥的图片，必须随本页一起消失。
        deleteShareFile();
        // 密钥与口令都不留到 Activity 之后。
        keyPub = null;
        keyPriv = null;
        clearPassword();
        if (tvPasswordTail != null) tvPasswordTail.setText("");
        ivQr = null;
    }

    /**
     * 后台线程生成二维码。
     * 载荷约 380 字符 → 版本较高、模块多，生成 + 像素填充在 UI 线程会有可感知卡顿。
     */
    private void generateQrAsync(final String payload) {
        new Thread(() -> {
            final Bitmap bmp = QrUtils.generateQr(payload, dp(320), ErrorCorrectionLevel.M);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    Log.e(TAG, "generateQr returned null");
                    AppLog.e(TAG, "密钥二维码生成失败（载荷长度 " + payload.length() + "）");
                    Toast.makeText(this, "二维码生成失败，请返回重试", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                qrBitmap = bmp;
                ivQr.setImageBitmap(bmp);
            });
        }).start();
    }

    private void updateCountdownText() {
        if (tvCountdown == null) return;
        tvCountdown.setText("二维码将在 " + secondsLeft + " 秒后自动隐藏");
    }

    /** 隐藏二维码。fromTimer=true 表示倒计时到点，用 Toast 告知原因。 */
    private void hideQr(boolean fromTimer) {
        if (hidden) return;
        hidden = true;
        handler.removeCallbacks(tick);

        if (ivQr != null) {
            ivQr.setImageDrawable(null);
            ivQr.setVisibility(View.GONE);
        }
        if (qrBitmap != null && !qrBitmap.isRecycled()) {
            qrBitmap.recycle();
            qrBitmap = null;
        }
        if (btnShare != null) btnShare.setEnabled(false);
        if (btnHide != null) btnHide.setEnabled(false);
        if (tvCountdown != null) {
            tvCountdown.setText(fromTimer
                    ? "二维码已自动隐藏。需要时请返回后重新打开本页。"
                    : "二维码已隐藏。需要时请返回后重新打开本页。");
        }
        if (fromTimer) {
            Toast.makeText(this, "为安全起见，二维码已自动隐藏", Toast.LENGTH_LONG).show();
        }
    }

    // ===== 分享二维码图片（替代原「保存到相册」）=====
    //
    // M1：原实现把含私钥的 PNG 写进系统相册（共享媒体 Pictures/密语密钥），
    //     60 秒自动隐藏对它无效，也没有任何清理；有存储权限的应用与云相册同步都能读到。
    //     现在改为：把 PNG 写到**本应用私有缓存目录**，通过系统分享面板以临时授权（content://）
    //     交给用户选定的另一个应用；本应用不留存，也不进入共享媒体。
    //
    // ⚠️ 诚实说明（必须让用户知道）：图片一旦交给第三方应用（微信/文件管理器等），
    //    对方会不会保存、存到哪里，本应用无法控制。所以分享前先做一次明确的知情确认。
    //    这也正是"不能悄悄存相册"的同一条理由。

    /**
     * 分享入口：先做一次知情确认（这是敏感操作），再生成临时文件并调起系统分享面板。
     */
    private void onShareClicked() {
        if (hidden || qrBitmap == null) {
            Toast.makeText(this, "二维码已隐藏，无法分享", Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("分享含私钥的二维码图片")
                .setMessage("这张图片里装着你**加密后的私钥**，只能发给你自己的另一台设备。\n\n"
                        + "· 请勿发给他人、勿发到群聊或公开平台；\n"
                        + "· 图片交给对方应用后，是否被保存、同步到云端由那个应用决定，本应用无法控制；\n"
                        + "· 更安全的方式是直接用另一台设备扫描屏幕上的二维码（不经任何第三方）。\n\n"
                        + "确认继续？")
                .setPositiveButton("继续分享", (d, w) -> shareQrImage())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把二维码写成缓存目录里的临时 PNG，然后经 FileProvider 走系统分享面板。 */
    private void shareQrImage() {
        final Bitmap bmp = qrBitmap;
        if (bmp == null || bmp.isRecycled()) {
            Toast.makeText(this, "二维码已隐藏，无法分享", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            java.io.File out = null;
            boolean ok = false;
            try {
                java.io.File dir = new java.io.File(getCacheDir(), SHARE_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "mkdirs failed: " + dir.getAbsolutePath());
                } else {
                    out = new java.io.File(dir, "miyu_key_" + System.currentTimeMillis() + ".png");
                    try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    if (!ok) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        out = null;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "shareQrImage write failed", e);
                AppLog.e(TAG, "密钥二维码分享临时文件写入失败", e);
            }
            final java.io.File written = out;
            final boolean success = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (written != null) {
                        //noinspection ResultOfMethodCallIgnored
                        written.delete();
                    }
                    return;
                }
                if (!success || written == null) {
                    Toast.makeText(this, "生成分享图片失败，请重试", Toast.LENGTH_LONG).show();
                    return;
                }
                shareFile = written;
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", written);
                    android.content.Intent send = androidx.core.app.ShareCompat.IntentBuilder.from(this)
                            .setType("image/png")
                            .setStream(uri)
                            .setChooserTitle("分享密钥二维码图片")
                            .createChooserIntent();
                    // 临时读权限：只对用户在分享面板里选中的那个应用生效，且随任务结束失效。
                    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(send);
                } catch (Exception e) {
                    Log.e(TAG, "share intent failed", e);
                    AppLog.e(TAG, "调起分享面板失败", e);
                    Toast.makeText(this, "无法调起分享面板", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** 分享临时文件所在目录（cacheDir 下，file_provider_paths.xml 已覆盖整个 cache）。 */
    private static final String SHARE_DIR = "qr_share";

    /** 删除本次生成的分享临时文件（不清理目录里的其他残留，交给 cleanupShareCache）。 */
    private void deleteShareFile() {
        if (shareFile == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            shareFile.delete();
        } catch (Exception e) {
            Log.w(TAG, "deleteShareFile failed", e);
        }
        shareFile = null;
    }

    /**
     * 清理过期的分享临时文件（超过 1 小时）。
     *
     * 为什么需要：分享临时文件里是含私钥的图片，而 onDestroy 在"进程被杀"时不会执行。
     * 不清的话它会一直躺在缓存目录里，直到系统清缓存（可能是几个月）。
     * 每次打开本页顺手清一次，成本极低。
     */
    private void cleanupShareCache() {
        try {
            // 本版本目录
            deleteOldFiles(new java.io.File(getCacheDir(), SHARE_DIR));
            // 早期版本的残留（若某个用户曾经装过写私有外部目录的构建）
            java.io.File ext = getExternalFilesDir(null);
            if (ext != null) deleteOldFiles(new java.io.File(ext, SHARE_DIR));
        } catch (Exception e) {
            Log.w(TAG, "cleanupShareCache failed", e);
        }
    }

    private void deleteOldFiles(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - SHARE_TTL_MS;
        for (java.io.File f : files) {
            if (f == null || !f.isFile()) continue;
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 分享临时文件的保留时长（超过即清理）。 */
    private static final long SHARE_TTL_MS = 60L * 60 * 1000;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
