package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.familychat.app.R;
import com.familychat.app.utils.AppLog;
import com.familychat.app.utils.PeerKeyPinStore;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.QrUtils;
import com.familychat.app.utils.SafetyNumberPayload;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * 安全码核对页（E2EE TOFU 第二层：带外比对公钥指纹）。
 *
 * <p>背景（{@code docs/audits/E2EE_SECURITY_AUDIT.md} §1.1）：TOFU pin 只保证"对方的公钥没变过"，
 * 但首次见到的公钥是不是<b>真的是他的</b>，本地无法自证。本页提供唯一不依赖服务端的核对方式：
 * 双方当面各出示自己的安全码二维码，互扫，把扫到的指纹与本地 pin 的指纹对比。
 *
 * <p><b>为什么这个页面在 :app 而不是 :feature-chat</b>：扫码（journeyapps）与二维码生成
 * 所需的 zxing 都已声明在壳层；:feature-chat 只通过
 * {@link com.familychat.app.utils.Routes#SAFETY_NUMBER_ACTIVITY} + setClassName 拉起本页，
 * 因此<b>不需要为它新增任何依赖</b>。调用方（{@code ChatDetailPanel}）通过
 * {@link #EXTRA_PEER_USER_ID} / {@link #EXTRA_PEER_NAME} 传入核对对象。
 *
 * <p>本页只处理<b>公钥</b>（展示 + 解析），不读取、不写出任何私钥材料，因此不设 FLAG_SECURE。
 */
public class SafetyNumberActivity extends AppCompatActivity {

    private static final String TAG = "SafetyNumber";

    /** 要核对的对方 userId（int）。 */
    public static final String EXTRA_PEER_USER_ID = "peer_user_id";
    /** 要核对的对方显示名（String，可空，仅用于文案）。 */
    public static final String EXTRA_PEER_NAME = "peer_name";

    private PreferencesManager prefs;
    private int peerUserId;
    private String peerName = "对方";

    private ImageView ivMyQr;
    private TextView tvMyCode;
    private TextView tvStatus;

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchScan();
                } else {
                    Toast.makeText(this, "需要相机权限才能扫码。", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safety_number);

        prefs = PreferencesManager.getInstance(this);
        peerUserId = getIntent().getIntExtra(EXTRA_PEER_USER_ID, 0);
        String name = getIntent().getStringExtra(EXTRA_PEER_NAME);
        if (name != null && !name.trim().isEmpty()) peerName = name.trim();

        ImageButton btnBack = findViewById(R.id.btnSafetyBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        ivMyQr = findViewById(R.id.ivSafetyMyQr);
        tvMyCode = findViewById(R.id.tvSafetyMyCode);
        tvStatus = findViewById(R.id.tvSafetyStatus);
        findViewById(R.id.btnSafetyScan).setOnClickListener(v -> onScanClicked());

        renderMyCode();
        renderPeerState();
    }

    // ===== ① 我的安全码 =====

    /** 生成并展示自己的安全码二维码 + 8 位短码（短码供无法扫码时人工念读）。 */
    private void renderMyCode() {
        String myPub = prefs.getMyPublicKey();
        if (myPub == null || myPub.isEmpty()) {
            tvMyCode.setText("本机还没有加密密钥：打开任意聊天后会自动生成，再回到本页。");
            return;
        }
        String myFp = PeerKeyPinStore.fingerprintOf(myPub);
        tvMyCode.setText("我的安全码：" + PeerKeyPinStore.displayCode(myFp));

        final String payload = SafetyNumberPayload.encode(myPub);
        if (payload == null) {
            tvMyCode.setText("安全码生成失败：本机公钥格式异常，请尝试重新登录。");
            AppLog.e(TAG, "SafetyNumberPayload.encode 返回 null（本机公钥不可用）");
            return;
        }
        // 二维码渲染放进后台线程：240dp 的位图在低端机上会有可见卡顿
        final int size = (int) (getResources().getDisplayMetrics().density * 240);
        new Thread(() -> {
            final Bitmap bmp = QrUtils.generateQr(payload, size, ErrorCorrectionLevel.M);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (bmp == null) {
                    tvMyCode.setText("二维码生成失败，请改用对方手机上的数字人工核对。");
                } else {
                    ivMyQr.setImageBitmap(bmp);
                }
            });
        }).start();
    }

    // ===== ③ 比对结果（也是打开页面时的初始状态）=====

    private void renderPeerState() {
        if (peerUserId <= 0) {
            tvStatus.setText("未指定核对对象，只能展示自己的安全码。");
            return;
        }
        String peerFp = PeerKeyPinStore.getPinnedFingerprint(peerUserId);
        if (peerFp == null) {
            tvStatus.setText("还没有 " + peerName + " 的密钥记录。\n\n"
                    + "先与他进行一次加密聊天，应用会自动记下他的公钥，再回到本页核对。");
            return;
        }
        String state = PeerKeyPinStore.isUserConfirmed(peerUserId) ? "已核对 ✅" : "尚未核对";
        tvStatus.setText(peerName + " 的安全码：" + PeerKeyPinStore.displayCode(peerFp)
                + "\n当前状态：" + state
                + "\n\n核对方式：让他也打开本页出示二维码，然后点上面的「扫对方的码」。");
    }

    // ===== ② 扫对方的码 =====

    private void onScanClicked() {
        if (peerUserId <= 0) {
            Toast.makeText(this, "未指定核对对象，无法比对", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchScan();
            return;
        }
        // 与 KeyQrImportActivity 同款：先申请，避免"进了扫码页才被弹权限"的糟糕体验
        cameraPermLauncher.launch(android.Manifest.permission.CAMERA);
    }

    /** 复用项目自带的竖屏 ScanActivity（:app 内，同类可直接引用）。 */
    private void launchScan() {
        try {
            new IntentIntegrator(this)
                    .setCaptureActivity(ScanActivity.class)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("扫描对方的安全码二维码")
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
        handleScanned(result.getContents());
    }

    /**
     * 解析扫到的安全码并与本地 pin 比对。
     *
     * <p>三种结局都要说清楚：一致 → 置位"已核对"；不一致 → 明确警告（中间人 或 对方重装）；
     * 本地还没 pin → 告诉用户先去聊一句。<b>绝不把"无法比对"混进"一致"</b>。
     */
    private void handleScanned(String text) {
        if (!SafetyNumberPayload.looksLikeSafetyPayload(text)) {
            tvStatus.setText("这不是「安全码」二维码。\n请确认对方打开的是「安全码核对」页，而不是别的码。");
            return;
        }
        SafetyNumberPayload.ParseResult r = SafetyNumberPayload.parse(text);
        if (!r.isSuccess()) {
            tvStatus.setText(messageForError(r));
            AppLog.e(TAG, "安全码解析失败: " + r.error);
            return;
        }
        if (peerUserId <= 0) {
            tvStatus.setText("未指定核对对象，无法比对。");
            return;
        }
        String peerFp = PeerKeyPinStore.getPinnedFingerprint(peerUserId);
        if (peerFp == null) {
            tvStatus.setText("还没有 " + peerName + " 的密钥记录，无法比对。\n\n"
                    + "先与他进行一次加密聊天，再回到本页核对。");
            return;
        }

        if (peerFp.equals(r.fullFingerprint)) {
            boolean ok = PeerKeyPinStore.markVerified(peerUserId, r.fullFingerprint);
            tvStatus.setText(ok
                    ? "✅ 已核对：扫到的安全码与本地记录完全一致。"
                    : "⚠️ 安全码一致，但本地记录写入失败，请稍后重试。");
            AppLog.d(TAG, "安全码核对通过, peerUserId=" + peerUserId + ", marked=" + ok);
        } else {
            tvStatus.setText("⚠️ 密钥不匹配！\n\n"
                    + "扫到的：" + PeerKeyPinStore.displayCode(r.fullFingerprint)
                    + "\n本地记录：" + PeerKeyPinStore.displayCode(peerFp)
                    + "\n\n这可能是有人在中间替换了他的公钥，也可能是对方重装了应用 / 换了设备。"
                    + "在弄清之前，请不要在这条会话里发送敏感内容，"
                    + "并改用其它渠道（当面 / 电话）与他确认真实情况。");
            AppLog.e(TAG, "安全码不匹配, peerUserId=" + peerUserId);
        }
    }

    private static String messageForError(SafetyNumberPayload.ParseResult r) {
        if (r.error == SafetyNumberPayload.Error.UNSUPPORTED_VERSION) {
            return "对方的二维码版本比本 App 新，请先升级 App 再扫。";
        }
        if (r.error == SafetyNumberPayload.Error.MALFORMED_KEY) {
            return "二维码内容损坏或不完整，请让对方重新打开页面后再扫。";
        }
        return "这不是「安全码」二维码。\n请确认对方打开的是「安全码核对」页，而不是别的码。";
    }
}
