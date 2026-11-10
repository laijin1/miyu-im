package com.familychat.app.ui;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.PreferencesManager;

/**
 * 密钥管理页（第 1 步：只读指纹显示）。
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 本页是「只读」页面，严禁出现以下四类调用（本文件不得出现这些方法名）：
 *      私钥读取 / 密钥对生成 / 密钥对保存 / 公钥上报
 *
 *    理由：如果为了显示指纹而顺手在本地无密钥时生成一把新钥并上传，
 *    会静默覆盖服务端上的旧公钥、永久作废该账号的历史消息 ——
 *    这恰好是本功能要解决的问题的镜像。指纹是只读信息，
 *    用户点进来只是想看一眼，不应产生任何副作用。
 *    "本地无密钥"时的正确做法是显示引导文案，让用户去聊天页/重新登录触发生成。
 *
 *    校验方式（必须零匹配）：对本文件 grep 上述四类方法名（私钥读取 / 密钥对生成 /
 *    密钥对保存 / 公钥上报）。本文件只允许调用 PreferencesManager 的公钥读取方法。
 * ─────────────────────────────────────────────────────────────
 *
 * 后续步骤（本步均未实现，也不留死按钮）：
 *   第 2 步：导出二维码 / 保存到相册
 *   第 3 步：扫码导入（相机权限 + 本地已有密钥的处理策略）
 *   第 4 步：重置密钥（两次确认 + 公钥上报）
 *
 * 文案说明：本项目界面文案统一硬编码（见 activity_settings.xml），
 * 未走 strings.xml，故此处与布局保持同一约定。
 */
public class KeyManagementActivity extends AppCompatActivity {

    private static final String TAG = "KeyMgmt";

    private static final String HINT_NORMAL =
            "在两台设备上分别打开本页，指纹完全相同即表示两把私钥是同一把。";
    private static final String HINT_NONE =
            "打开任意一个聊天或重新登录后，应用会自动生成加密密钥，之后回到本页即可看到指纹。";
    private static final String TEXT_NONE = "密钥尚未生成";
    private static final String TEXT_BROKEN = "密钥数据异常，无法计算指纹";

    /** 指纹卡片中的三个 TextView（短码 / 全码 / 说明）。 */
    private TextView tvFpShort;
    private TextView tvFpFull;
    private TextView tvFpHint;

    /** 导出二维码入口（无密钥时置灰，避免点了给出含糊错误）。 */
    private View rowExportQr;

    /** 扫码导入入口。导入会写密钥，故逻辑放在独立的 KeyQrImportActivity。 */
    private View rowImportQr;

    /** 当前是否已有可导出的密钥。由 renderFingerprint 更新。 */
    private boolean keysUsable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 安全存储是 fail-closed 的：初始化失败会抛 IllegalStateException。
        // 这里必须兜住并在报错后 finish()，绝不能让"看一眼指纹"把页面搞崩。
        final PreferencesManager prefs;
        try {
            prefs = PreferencesManager.getInstance(this);
        } catch (Exception e) {
            Log.e(TAG, "PreferencesManager init failed", e);
            Toast.makeText(this,
                    "安全存储初始化失败，无法读取密钥指纹。请检查系统锁屏/Keystore 后重试。",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_key_management);

        View btnBack = findViewById(R.id.btnKeyMgmtBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvFpShort = findViewById(R.id.tvKeyFpShort);
        tvFpFull = findViewById(R.id.tvKeyFpFull);
        tvFpHint = findViewById(R.id.tvKeyFpHint);
        rowExportQr = findViewById(R.id.rowExportQr);
        if (rowExportQr != null) rowExportQr.setOnClickListener(v -> onExportClicked());
        rowImportQr = findViewById(R.id.rowImportQr);
        if (rowImportQr != null) rowImportQr.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, KeyQrImportActivity.class)));
        View rowResetKey = findViewById(R.id.rowResetKey);
        if (rowResetKey != null) rowResetKey.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, KeyResetActivity.class)));

        renderFingerprint(prefs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onCreate 中已渲染过一次；此处覆盖"从导出页返回"等 Activity 复用场景。
        try {
            renderFingerprint(PreferencesManager.getInstance(this));
        } catch (Exception e) {
            Log.e(TAG, "onResume render failed", e);
        }
    }

    /**
     * 三态渲染：
     *   1) 公钥为空          → "密钥尚未生成" + 引导文案
     *   2) 公钥非空但解码失败 → "密钥数据异常，无法计算指纹"（写 AppLog.e）
     *   3) 正常              → 短码 / 全码（4 位一组）
     *
     * 注意：指纹的"未生成"与"解码失败"必须在调用方区分 ——
     * KeyTransferPayload 只保证对 null/空数组安全返回空串，不承载状态语义。
     */
    private void renderFingerprint(PreferencesManager prefs) {
        if (tvFpShort == null || tvFpFull == null || tvFpHint == null) return;

        final String pub = prefs.getMyPublicKey();
        // 只读公钥用于判断"是否有可导出的密钥"。
        // ⚠️ 刻意不读私钥：本文件必须保持零私钥访问（可用 grep 验证零匹配）。
        // "有公钥但解不开/格式坏"由下面的解码失败分支覆盖；
        // 私钥确实缺失的极端情况由导出页在打开时报错并返回。
        keysUsable = pub != null && !pub.isEmpty();

        // 状态 1：本地尚未生成密钥
        if (pub == null || pub.isEmpty()) {
            showState(TEXT_NONE, HINT_NONE);
            return;
        }

        // 状态 2：有公钥但解码失败（数据损坏）
        byte[] spki;
        try {
            spki = Base64.decode(pub, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "public key base64 decode failed", e);
            // 只记录长度，绝不把公钥原文写进日志
            AppLog.e(TAG, "公钥 Base64 解码失败, len=" + pub.length());
            keysUsable = false;
            showState(TEXT_BROKEN, HINT_NORMAL);
            return;
        }
        if (spki == null || spki.length == 0) {
            AppLog.e(TAG, "公钥解码结果为空, len=" + pub.length());
            keysUsable = false;
            showState(TEXT_BROKEN, HINT_NORMAL);
            return;
        }

        // 状态 3：正常
        final String shortCode = KeyTransferPayload.fingerprintShort(spki);
        final String fullCode = KeyTransferPayload.fingerprintFullHex(spki);
        if (shortCode.isEmpty() || fullCode.isEmpty()) {
            // 理论上不可达（SHA-256 在 API 24+ 必定可用）；兜底避免误显示成正常态
            Log.e(TAG, "fingerprint compute returned empty");
            AppLog.e(TAG, "指纹计算返回空值, spkiLen=" + spki.length);
            keysUsable = false;
            showState(TEXT_BROKEN, HINT_NORMAL);
            return;
        }

        tvFpShort.setText(KeyTransferPayload.group4(shortCode));
        tvFpFull.setText(KeyTransferPayload.group4(fullCode));
        tvFpFull.setVisibility(View.VISIBLE);
        tvFpHint.setText(HINT_NORMAL);
        updateExportRow();
    }

    /** 统一处理"无指纹可显示"的两种状态：短码换成提示语、隐藏全码、更新说明。 */
    private void showState(String shortText, String hint) {
        tvFpShort.setText(shortText);
        tvFpFull.setText("");
        tvFpFull.setVisibility(View.GONE);
        tvFpHint.setText(hint);
        updateExportRow();
    }

    /** 无可用密钥时把"导出二维码"入口置灰，避免用户点了只得到含糊错误。 */
    private void updateExportRow() {
        if (rowExportQr == null) return;
        rowExportQr.setEnabled(keysUsable);
        rowExportQr.setAlpha(keysUsable ? 1f : 0.4f);
    }

    // ===== 导出二维码（第 2 步）=====

    /**
     * 导出入口：先做一次明确的安全确认，再进导出页。
     *
     * 这里不直接用 AlertDialog 承载"要不要导出"，而是把确认与后续的
     * 屏幕警告、倒计时隐藏串成一条链：用户在这里先知道"私钥会显示在屏幕上"，
     * 进页后顶部还有常驻警告，60 秒后自动隐藏。
     */
    private void onExportClicked() {
        if (!keysUsable) {
            Toast.makeText(this, "当前没有可导出的密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("导出私钥二维码")
                .setMessage("接下来屏幕上会显示一个包含你私钥的二维码。\n\n"
                        + "· 请只在没有旁人的环境下操作\n"
                        + "· 不要截图、不要发给他\n"
                        + "· 二维码会在 60 秒后自动隐藏\n\n"
                        + "确认继续？")
                .setPositiveButton("继续", (d, w) -> startExport())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 生成载荷并交给导出页显示。载荷经静态一次性交接传递，不走 Intent extra。 */
    private void startExport() {
        final String pub;
        try {
            // 只读公钥用于算指纹；私钥由导出页自己读取（见下）。
            pub = PreferencesManager.getInstance(this).getMyPublicKey();
        } catch (Exception e) {
            Log.e(TAG, "startExport: prefs failed", e);
            Toast.makeText(this, "读取密钥失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }

        String fp = "";
        try {
            byte[] spki = Base64.decode(pub, Base64.NO_WRAP);
            fp = KeyTransferPayload.fingerprintShort(spki);
        } catch (Exception ignored) {
            // 指纹只用于导出页上的辅助显示，算不出来不影响导出本身
        }

        try {
            // 注意：这里刻意不读私钥、也不生成载荷 —— 私钥原文由
            // KeyQrExportActivity 自己从 PreferencesManager 读出并编码。
            // 目的是维持本文件「零私钥访问」的硬约束（可用 grep 验证零匹配），
            // 让"私钥出口"只存在于导出页这一处，便于审计。
            android.content.Intent it = new android.content.Intent(this, KeyQrExportActivity.class);
            if (fp != null && !fp.isEmpty()) it.putExtra(KeyQrExportActivity.EXTRA_FP, fp);
            startActivity(it);
        } catch (Exception e) {
            Log.e(TAG, "startExport: startActivity failed", e);
            Toast.makeText(this, "打开导出页失败", Toast.LENGTH_SHORT).show();
        }
    }
}
