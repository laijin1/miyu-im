package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.QrUtils;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.InputStream;

/**
 * 私钥扫码导入页（第 3 步）。
 *
 * 为什么单独开一个 Activity，而不是把导入功能塞进 KeyManagementActivity：
 *   KeyManagementActivity 有"零私钥访问 / 零密钥写入"的硬约束（它是只读展示页，
 *   可用 grep 验证）。私钥的**写入**入口集中在本页，成为唯一的"密钥入口"，
 *   与 KeyQrExportActivity（唯一"密钥出口"）对称，便于审计。
 *
 * 二维码是 v2 载荷（口令 + PBKDF2-SHA256 + AES-GCM）：扫码后先解外层结构，
 * 需要口令时弹输入框，口令错误与"二维码被改过"在密码学上不可区分，
 * 因此提示必须同时覆盖两种可能。
 *
 * 「本地已有密钥」的处理策略（本步的核心决策）：
 *   1) 本地无密钥            → 直接导入
 *   2) 指纹与本地一致         → 提示「密钥一致，无需导入」，**不写入任何东西**
 *   3) 指纹与本地不一致       → 双指纹并排显示 + 强确认后才覆盖（可回滚）
 *
 *   为什么指纹一致要直接短路：换机场景最常见的操作是"重复扫同一张码"。
 *   若每次都弹覆盖确认，用户会养成一路点确认的习惯，等到真正需要覆盖时
 *   已经不看文案了。指纹一致时不写入，把确认框留给真正有意义的场景。
 *
 *   为什么不默认覆盖：本机有可用私钥却把它换掉，会当场烧掉全部历史记录，
 *   而收益通常为零（用户多半扫错了码或扫了旧手机的旧码）。
 *   不覆盖的代价是零，覆盖的代价是不可逆的数据丢失 —— 两者不对称。
 */
public class KeyQrImportActivity extends AppCompatActivity {

    private static final String TAG = "KeyQrImport";

    /**
     * 版本兼容提示（常驻状态行 + v1 命中时各出现一次）。
     *
     * 为什么要常驻：用户扫不动时最需要的信息就是"对方那台该升级了"，
     * 而失败提示只出现一瞬间；这条文案必须在他扫码之前就看得见。
     */
    private static final String VERSION_HINT = "如对方设备版本较旧，请先让它升级到同一版本再扫码。";

    private TextView tvStatus;
    private View btnScan;
    private View btnAlbum;

    /** 覆盖前把旧密钥只放在内存里，导入后若上报失败可同会话内撤销。 */
    private String rollbackPub;
    private String rollbackPriv;

    /**
     * 本页持有的**迁移口令**（二维码解密用）。
     *
     * ⚠️ 用 char[] 持有、用完 Arrays.fill 清零：不落盘、不进 Intent、不打日志。
     * 它与下面的 loginPassword 是两把不同的口令，必须分开保存 ——
     * 迁移口令在"要不要同步服务端备份"这一步已经没用了（那时要的是登录密码），
     * 混用一个变量会让用户在服务端备份上用到错误的口令并覆盖掉旧备份。
     */
    private char[] migrationPassword;

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchScan();
                } else {
                    Toast.makeText(this, "需要相机权限才能扫码。你也可以从相册选择二维码图片。",
                            Toast.LENGTH_LONG).show();
                    updateStatus("未获得相机权限。可点「从相册选择二维码图片」，或去系统设置开启相机权限。");
                }
            });

    /** 相册选图作为无相机权限时的兜底（复用 ScanActivity 的同款做法）。 */
    private final ActivityResultLauncher<Intent> albumLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) decodeFromImage(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // H4：本页会输入迁移口令 / 登录密码，并显示密钥指纹。
        // FLAG_SECURE 阻止系统截图、最近任务缩略图与投屏；顺带禁掉该窗口的
        // 输入法截屏/剪贴板预览。必须放在 setContentView 之前。
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_key_qr_import);

        View btnBack = findViewById(R.id.btnImportBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvStatus = findViewById(R.id.tvImportStatus);
        btnScan = findViewById(R.id.btnImportScan);
        btnAlbum = findViewById(R.id.btnImportAlbum);

        if (btnScan != null) btnScan.setOnClickListener(v -> onScanClicked());
        if (btnAlbum != null) btnAlbum.setOnClickListener(v -> pickFromAlbum());

        updateStatus("点下方按钮扫描另一台设备上显示的私钥二维码。\n\n" + VERSION_HINT);
    }

    // ===== 扫码入口（相机权限）=====

    /**
     * 相机权限在 targetSdk 34 下的处理：
     *   - 已授权 → 直接进扫码页；
     *   - 未授权 → 走运行时申请；被拒后给出明确提示，并保留相册兜底。
     * 注意不要在扫码页里再申请一次：ScanActivity 自身也会检查/申请权限，
     * 这里先申请可避免"进来先看到一句需要权限然后被弹回"的糟糕体验。
     */
    private void onScanClicked() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchScan();
            return;
        }
        // 用户此前拒绝过（勾了不再询问或已拒绝一次）时先解释再申请
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                android.Manifest.permission.CAMERA)) {
            updateStatus("扫描二维码需要相机权限，用于读取另一台设备上的私钥二维码。");
        }
        cameraPermLauncher.launch(android.Manifest.permission.CAMERA);
    }

    /** 用 zxing 的 IntentIntegrator 启动项目自带的竖屏 ScanActivity。 */
    private void launchScan() {
        try {
            new IntentIntegrator(this)
                    .setCaptureActivity(ScanActivity.class)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("扫描私钥二维码")
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
                    .initiateScan();
        } catch (Exception e) {
            Log.e(TAG, "launchScan failed", e);
            Toast.makeText(this, "无法打开扫码页", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return;
        handleScannedText(result.getContents());
    }

    // ===== 相册兜底 =====

    private void pickFromAlbum() {
        Intent pick = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (pick.resolveActivity(getPackageManager()) != null) {
            albumLauncher.launch(pick);
        } else {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    private void decodeFromImage(Uri uri) {
        updateStatus("正在识别图片中的二维码…");
        new Thread(() -> {
            Bitmap bmp = null;
            String text = null;
            try {
                bmp = loadScaledBitmap(uri, 1600);
                text = QrUtils.decodeBitmap(bmp);
            } catch (Exception e) {
                Log.e(TAG, "decodeFromImage failed", e);
            } finally {
                if (bmp != null) bmp.recycle();
            }
            final String found = text;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (found == null || found.isEmpty()) {
                    updateStatus("图片中没有识别到二维码，请换一张或改用相机扫描。");
                } else {
                    handleScannedText(found);
                }
            });
        }).start();
    }

    /** 按最大边长采样缩小加载，避免大图 OOM（与 ScanActivity 同款实现）。 */
    private Bitmap loadScaledBitmap(Uri uri, int maxSize) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int w = bounds.outWidth, h = bounds.outHeight;
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= maxSize) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, opt);
        }
    }

    // ===== 核心：校验 → 口令 → 比对 → 导入 =====

    /** 判断文本是否是本应用的密钥二维码；不是则给"扫错码"的提示。 */
    private void handleScannedText(String text) {
        if (text == null) return;
        if (!KeyTransferPayload.looksLikeKeyPayload(text)) {
            updateStatus("这不是本应用的密钥二维码，请确认扫描的是「导出私钥二维码」页上的码。\n\n"
                    + VERSION_HINT);
            return;
        }

        // 第一步只解外层结构：不需要口令，也不会拿到任何密钥材料。
        // 它负责回答两件事：① 这是哪一代格式；② 要不要弹口令框。
        KeyTransferPayload.DecodeResult header = KeyTransferPayload.inspect(text);
        if (!header.isSuccess() && !header.isPasswordProtected()) {
            updateStatus(messageForError(header.error));
            Log.w(TAG, "inspect failed: " + header.error);
            AppLog.e(TAG, "密钥二维码校验失败: " + header.error);
            return;
        }

        if (header.isPasswordProtected()) {
            askPasswordThenImport(text);
            return;
        }

        // 理论上不可达（v2 一定需要口令）；保留是为了将来出现"免口令格式"时不静默走错分支。
        importWithPassword(text, null);
    }

    /**
     * 弹出迁移口令输入框。
     *
     * 口令只用于本地解密：不落盘、不进 Intent、不打日志，用完即清零。
     * 反过来说，本页**无法**知道正确口令的末 4 位是什么（二维码里不含任何口令派生信息），
     * 所以这里不显示任何"应为 XXXX"的提示 —— 只提示用户去导出页上核对。
     */
    private void askPasswordThenImport(final String text) {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_import_password, null);
        final EditText et = content.findViewById(R.id.etImportTransferPassword);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("输入迁移口令")
                .setView(content)
                .setPositiveButton("导入", (d, w) -> {
                    String pw = et.getText() == null ? "" : et.getText().toString();
                    if (pw.isEmpty()) {
                        updateStatus("未输入口令，已取消导入。");
                        return;
                    }
                    importWithPassword(text, pw);
                })
                .setNegativeButton("取消", (d, w) -> updateStatus("已取消导入，本机密钥未改变。"))
                .show();
    }

    /** 用口令解密 → 校验 → 比对本地密钥 → 决定是否写入。 */
    private void importWithPassword(final String text, final String password) {
        if (password == null) {
            // 理论上不可达（v2 一定需要口令）
            updateStatus("该二维码需要迁移口令才能导入。");
            return;
        }
        // 口令只在本页内存里存活到导入结束；尽早清零，减少暴露窗口。
        clearPassword();
        migrationPassword = password.toCharArray();

        KeyTransferPayload.DecodeResult r = KeyTransferPayload.decode(text, password);
        if (!r.isSuccess()) {
            String msg = messageForError(r.error);
            // 口令错误时留在页面上并允许重试：这是最常见的失败，重扫一次成本很高。
            updateStatus(msg);
            Log.w(TAG, "decode failed: " + r.error);
            AppLog.e(TAG, "密钥二维码校验失败: " + r.error);
            if (r.error == KeyTransferPayload.Error.DECRYPT_FAILED) {
                askPasswordThenImport(text);
            }
            return;
        }

        final KeyTransferPayload.Payload payload = r.payload;
        final String incomingPub = payload.publicKeyBase64();
        final String incomingPriv = payload.privateKeyBase64();

        final PreferencesManager prefs;
        try {
            prefs = PreferencesManager.getInstance(this);
        } catch (Exception e) {
            Log.e(TAG, "prefs init failed", e);
            updateStatus("安全存储初始化失败，无法导入。请检查系统锁屏/Keystore 后重试。");
            return;
        }

        final String localPub = prefs.getMyPublicKey();
        final String localPriv = prefs.getMyPrivateKey();

        // 分支 1：本地无密钥 → 直接导入
        if (localPub == null || localPub.isEmpty() || localPriv == null || localPriv.isEmpty()) {
            updateStatus("正在导入…");
            applyImport(prefs, incomingPub, incomingPriv, null, null);
            return;
        }

        // 分支 2：指纹一致 → 短路，不写入任何东西
        if (KeyTransferPayload.isSameKey(localPub, incomingPub)) {
            updateStatus("密钥一致，无需导入。本机当前密钥与二维码中的密钥是同一把。");
            return;
        }

        // 分支 3：指纹不同 → 双指纹强确认
        showOverwriteConfirm(prefs, localPub, incomingPub, incomingPriv);
    }

    /** 把载荷错误码翻译成用户能行动的中文提示（不暴露内部细节）。 */
    private String messageForError(KeyTransferPayload.Error error) {
        if (error == null) return "二维码无效，请重试。";
        switch (error) {
            case NOT_KEY_PAYLOAD:
                return "这不是本应用的密钥二维码，请确认扫描的是「导出私钥二维码」页上的码。";
            case LEGACY_FORMAT:
                // 「码比 App 旧」：让用户重新导出，而不是去升级已经是最新的设备。
                return "这是旧版本 App 导出的二维码。请用当前版本重新导出一张。\n\n" + VERSION_HINT;
            case UNSUPPORTED_VERSION:
                // 「码比 App 新」：让用户升级本机 App。
                return "这是更新版本导出的二维码。请把你的 App 也升级到同一版本。";
            case NEED_PASSWORD:
                return "该二维码需要迁移口令才能导入。";
            case DECRYPT_FAILED:
                // AEAD 认证失败无法区分"口令错"与"载荷被改"，提示必须同时覆盖。
                return "口令不正确，或二维码内容已被改动。\n\n"
                        + "· 请核对导出页上显示的「口令末 4 位」；\n"
                        + "· 若口令确定无误，说明这张二维码在传递中被改过，请回到旧设备重新导出。";
            case KEYPAIR_MISMATCH:
                return "二维码内容不合法（公钥与私钥不匹配）。请回到旧设备重新导出后再扫。";
            case MALFORMED_KEY:
            default:
                return "二维码内容无效或已损坏，请重新扫描。";
        }
    }

    /** 双指纹并排显示 + 强确认。只有用户明确点「仍要导入」才会覆盖。 */
    private void showOverwriteConfirm(final PreferencesManager prefs,
                                      final String localPub,
                                      final String incomingPub,
                                      final String incomingPriv) {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_import_confirm, null);
        TextView tvCur = content.findViewById(R.id.tvCurFingerprint);
        TextView tvNew = content.findViewById(R.id.tvNewFingerprint);

        String curFp = KeyTransferPayload.fingerprintShortOfBase64(localPub);
        String newFp = KeyTransferPayload.fingerprintShortOfBase64(incomingPub);
        tvCur.setText(KeyTransferPayload.group4(curFp));
        tvNew.setText(KeyTransferPayload.group4(newFp));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("要用二维码里的密钥替换本机密钥吗？")
                .setView(content)
                .setPositiveButton("仍要导入", (d, w) -> {
                    updateStatus("正在导入…");
                    // 覆盖前把旧密钥留在内存里，便于同会话内撤销
                    applyImport(prefs, incomingPub, incomingPriv, localPub, prefs.getMyPrivateKey());
                })
                .setNegativeButton("取消", (d, w) ->
                        updateStatus("已取消导入，本机密钥未改变。"))
                .show();
    }

    /**
     * 执行导入：写本地 → 刷新账号快照 → 上报公钥 → **同步服务端备份**（H3）。
     *
     * @param rollbackPub  覆盖前的公钥（无覆盖时传 null）
     * @param rollbackPriv 覆盖前的私钥（无覆盖时传 null）
     */
    private void applyImport(final PreferencesManager prefs,
                             final String pub, final String priv,
                             final String rollbackPub, final String rollbackPriv) {
        try {
            prefs.saveKeyPair(pub, priv);
        } catch (Exception e) {
            Log.e(TAG, "saveKeyPair failed", e);
            AppLog.e(TAG, "导入密钥写入失败", e);
            updateStatus("导入失败：无法写入密钥存储。");
            return;
        }

        // 账号快照同步：PreferencesManager.saveKeyPair 只有在账号列表里**已存在**该 uid 时才会更新
        // （updateAccountE2ee 找不到就什么都不做）。若快照仍是导入前的旧密钥，
        // 一旦某个路径回到"快照恢复"，导入就会被静默撤销。这里显式补一次。
        try {
            prefs.saveCurrentAccount();
        } catch (Exception e) {
            Log.w(TAG, "saveCurrentAccount failed", e);
        }

        final boolean wasOverwrite = rollbackPub != null;
        AppLog.d(TAG, "密钥已导入, 覆盖=" + wasOverwrite
                + ", 新指纹=" + KeyTransferPayload.fingerprintShortOfBase64(pub));

        // 上报公钥（后台）。失败不影响本地导入成功，但要如实告知并提供撤销。
        new Thread(() -> {
            boolean uploaded = false;
            String err = null;
            try {
                ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
                uploaded = api.uploadPublicKey(pub);
            } catch (Exception e) {
                err = e.getMessage();
                Log.e(TAG, "uploadPublicKey failed", e);
            }
            final boolean ok = uploaded;
            final String errMsg = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    // 公钥已上报成功 → 立刻处理服务端密码备份（H3）。
                    updateStatus("公钥已同步，正在处理服务端的密钥备份…");
                    maybeRefreshServerBackup(prefs);
                } else {
                    AppLog.e(TAG, "导入后公钥上报失败: " + errMsg);
                    updateStatus("密钥已导入，但公钥上报失败，对方暂时无法给你发加密消息。");
                    clearPassword();
                    showUploadFailedDialog(prefs, rollbackPub, rollbackPriv);
                }
            });
        }).start();
    }

    // ===== H3：导入成功后刷新服务端密码备份 =====

    /** 本页持有的**登录密码**（仅 H3 刷新备份时使用；不落盘、不打日志、用完清零）。 */
    private char[] loginPassword;

    /**
     * 导入成功后检查并刷新服务端的「密码加密密钥备份」。
     *
     * ─────────────────────────────────────────────────────────────
     * 为什么导入路径也必须刷新（H3）
     * ─────────────────────────────────────────────────────────────
     * 服务端存着一份**用登录密码加密的旧密钥备份**。若导入新机后不清掉它：
     * 用户一旦「清除数据 / 换机后重新登录」，`LoginActivity.ensureLoginKeys` 会用登录密码
     * 把**旧密钥**从备份里解回来并重新上报旧公钥 —— 本次导入被静默撤销。
     * 重置路径已经闭环（第 5 步），导入路径此前没有。
     *
     * 为什么必须向用户要登录密码：备份密文的钥匙由登录密码经 PBKDF2 派生，
     * 密码只存在于用户脑中，App 任何地方都没有留存，无法本地推导。
     *
     * 三种结果分别处置（沿用 H2 的三态原则）：
     *   ① 服务端**确认没有**备份 → 无需刷新，明确告知
     *   ② 有备份 → 询问登录密码并用它重新加密「刚导入的密钥对」覆盖
     *   ③ **无法确认**（网络/HTTP 失败）→ 绝不猜测；如实警告"本次导入可能被旧备份覆盖"
     */
    private void maybeRefreshServerBackup(final PreferencesManager prefs) {
        new Thread(() -> {
            final int uid = prefs.getUserId();
            ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
            final com.familychat.app.utils.KeyBackupResponse.State state;
            try {
                state = api.fetchKeyBackupState();
            } catch (Exception e) {
                Log.e(TAG, "fetchKeyBackupState failed", e);
                AppLog.e(TAG, "导入后无法确认服务端备份状态: " + e.getMessage());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    clearPassword();
                    updateStatus("导入成功，但无法确认服务端是否存有旧密钥备份。");
                    showBackupNotRefreshedDialog("没能在导入后连上服务器确认现有的密钥备份，"
                            + "因此没有更新它。\n\n"
                            + "如果服务器上确实存着旧密钥的备份：下次你**清除数据或换机后重新登录**时，"
                            + "旧密钥会被自动恢复回来，本次导入等于白做。\n\n"
                            + "建议在网络正常时重新导入一次。");
                });
                return;
            }

            if (!state.exists) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    clearPassword();
                    String msg = "导入成功。服务器上没有旧密钥备份，无需同步。";
                    updateStatus(msg + " 请重新打开对应聊天查看历史消息。");
                    setResult(RESULT_OK);
                });
                return;
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateStatus("导入成功。服务器上还存着旧密钥的备份，需要同步更新。");
                showLoginPasswordForBackup(prefs);
            });
        }).start();
    }

    /** 询问登录密码，用于把**刚导入的**密钥对重新加密后覆盖服务端备份。 */
    private void showLoginPasswordForBackup(final PreferencesManager prefs) {
        View content = getLayoutInflater().inflate(R.layout.dialog_key_reset_password, null);
        TextView prompt = content.findViewById(R.id.tvResetPwdPrompt);
        if (prompt != null) {
            prompt.setText("请输入**登录密码**（不是刚才的迁移口令）");
        }
        final EditText et = content.findViewById(R.id.etResetPassword);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("同步服务端密钥备份")
                .setView(content)
                .setCancelable(false)
                .setPositiveButton("同步", (d, w) -> {
                    String pwd = et.getText() == null ? "" : et.getText().toString().trim();
                    if (pwd.isEmpty()) {
                        Toast.makeText(this, "未输入密码，未更新服务端备份", Toast.LENGTH_LONG).show();
                        warnBackupNotRefreshed();
                        finish();
                        return;
                    }
                    refreshServerBackup(prefs, pwd);
                })
                .setNegativeButton("暂不同步", (d, w) -> warnBackupNotRefreshed())
                .show();
    }

    /** 用登录密码加密「刚导入的密钥对」并覆盖服务端备份（后台）。 */
    private void refreshServerBackup(final PreferencesManager prefs, final String password) {
        clearLoginPassword();
        loginPassword = password.toCharArray();

        final int uid = prefs.getUserId();
        final String pub = prefs.getMyPublicKey();
        final String priv = prefs.getMyPrivateKey();

        updateStatus("正在同步服务端密钥备份…");
        new Thread(() -> {
            ApiClient api = new ApiClient(prefs.getServer(), prefs.getToken());
            final String combined = pub + "\n" + priv;
            final String backup = com.familychat.app.utils.KeyBackup.encryptBackup(combined, password, uid);
            if (backup == null) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    clearLoginPassword();
                    AppLog.e(TAG, "导入后备份加密失败");
                    warnBackupNotRefreshed();
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
                clearLoginPassword();
                if (success) {
                    AppLog.d(TAG, "导入后服务端密钥备份已更新");
                    String msg = "导入完成：本机密钥与服务端备份都已更新。";
                    updateStatus(msg + " 请重新打开对应聊天查看历史消息。");
                    setResult(RESULT_OK);
                } else {
                    AppLog.e(TAG, "导入后服务端备份更新失败: " + errMsg);
                    warnBackupNotRefreshed();
                }
            });
        }).start();
    }

    /** 服务端备份未刷新时的统一警告（可跳过，但必须讲清后果）。 */
    private void warnBackupNotRefreshed() {
        clearLoginPassword();
        showBackupNotRefreshedDialog("本地已成功导入密钥，但服务器上的旧密钥备份没有更新。\n\n"
                + "后果：下次你**清除数据或换机后重新登录**时，旧密钥会被自动恢复回来，"
                + "本次导入等于白做（需要重新扫一次二维码）。\n\n"
                + "要彻底避免，请重新进入本页导入一次，并在提示同步备份时输入登录密码。");
    }

    private void showBackupNotRefreshedDialog(String message) {
        if (isFinishing() || isDestroyed()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("服务端备份未更新")
                .setMessage(message)
                .setPositiveButton("知道了", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    /**
     * 清零登录密码。
     * 与迁移口令一样用 Arrays.fill 覆盖，而不是只把引用置空。
     */
    private void clearLoginPassword() {
        if (loginPassword != null) {
            java.util.Arrays.fill(loginPassword, '\0');
            loginPassword = null;
        }
    }

    /** 清零迁移口令（char[] 覆写；见 clearLoginPassword 的说明）。 */
    private void clearPassword() {
        if (migrationPassword != null) {
            java.util.Arrays.fill(migrationPassword, '\0');
            migrationPassword = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearPassword();
        clearLoginPassword();
        rollbackPub = null;
        rollbackPriv = null;
    }

    /** 上报失败：如实说明后果，并（仅在本次是覆盖时）提供撤销回旧密钥的出口。 */
    private void showUploadFailedDialog(final PreferencesManager prefs,
                                        final String rollbackPub,
                                        final String rollbackPriv) {
        final boolean canRollback = rollbackPub != null && rollbackPriv != null;
        com.google.android.material.dialog.MaterialAlertDialogBuilder b =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("公钥上报失败")
                        .setMessage("密钥已写入本机，但没能同步到服务器。\n\n"
                                + "在你恢复网络前，对方发来的新消息你可能无法解密。\n"
                                + "稍后在聊天页发一条消息会自动重试上报。"
                                + (canRollback ? "\n\n也可以选择撤销本次导入，恢复导入前的密钥。" : ""))
                        .setPositiveButton("知道了", null);
        if (canRollback) {
            b.setNegativeButton("撤销导入", (d, w) -> {
                try {
                    prefs.saveKeyPair(rollbackPub, rollbackPriv);
                    updateStatus("已撤销导入，恢复为导入前的密钥。");
                    AppLog.d(TAG, "用户撤销导入，已恢复旧密钥");
                } catch (Exception e) {
                    Log.e(TAG, "rollback failed", e);
                    Toast.makeText(this, "撤销失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    private void updateStatus(String text) {
        if (tvStatus != null) tvStatus.setText(text);
    }
}
