package com.familychat.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.BuildConfig;
import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.PushConnection;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 服务器信息：地址（可复制）、连接状态（实时）、服务器版本、当前用户、修改服务器。
 */
public class ServerInfoActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private TextView tvServerAddr, tvWsStatus, tvHttpStatus, tvServerVersion, tvServerUid, tvServerNick;
    private View vWsDot, vHttpDot;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_info);
        prefs = PreferencesManager.getInstance(this);

        ImageButton btnBack = findViewById(R.id.btnServerInfoBack);
        btnBack.setOnClickListener(v -> finish());

        tvServerAddr = findViewById(R.id.tvServerAddr);
        tvWsStatus = findViewById(R.id.tvWsStatus);
        tvHttpStatus = findViewById(R.id.tvHttpStatus);
        tvServerVersion = findViewById(R.id.tvServerVersion);
        tvServerUid = findViewById(R.id.tvServerUid);
        tvServerNick = findViewById(R.id.tvServerNick);
        vWsDot = findViewById(R.id.vWsDot);
        vHttpDot = findViewById(R.id.vHttpDot);

        String addr = prefs.getServer();
        // 展示给人看的地址：刻意保留 httpOf（而不是 baseUrl）。
        // baseUrl 在假域名模式下会显示 "https://miyu.lan:5000" —— 那是 App 内部用的
        // 名字，用户既看不懂也 ping 不通，反而会以为地址配错了。这里要显示用户
        // 实际填的 "http://192.168.x.x:5000"。
        // addrText 同时是点击复制后闪字复原的目标：必须存一份稳定字符串，
        // 不能在点击回调里取 tvServerAddr.getText()——上次闪的"已复制"还没复原时再点，
        // 会把"已复制"当成规范文字。
        final String addrText = ServerProtocol.httpOf(addr) + addr;
        tvServerAddr.setText(addrText);
        tvServerUid.setText(String.valueOf(prefs.getUserId()));
        tvServerNick.setText(prefs.getMyNickname());
        tvServerVersion.setText("v" + BuildConfig.VERSION_NAME);

        // 点击复制地址
        LinearLayout rowAddr = findViewById(R.id.rowServerAddr);
        rowAddr.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("server", addrText));
            FlashText.flash(tvServerAddr, "已复制", addrText);
        });

        // 修改服务器地址
        Button btnChange = findViewById(R.id.btnChangeServer);
        btnChange.setOnClickListener(v -> changeServerAddress());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.postDelayed(pollRunnable, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollRunnable);
    }

    /** 刷新 WS / HTTP 状态。 */
    private void refreshStatus() {
        // WebSocket 状态：读连接宿主（全进程唯一连接 PushConnection），不再读
        // WebSocketManager 的 static 标志 —— 那个值会被登录页的临时连接污染，
        // 且 disconnect() 不清理它，只靠 onClosed 异步兜底，不可信。
        if (PushConnection.isConnected()) {
            setDot(vWsDot, 0xFF2196F3);
            tvWsStatus.setText("WebSocket 已连接");
        } else if (PushConnection.isConnecting()) {
            setDot(vWsDot, 0xFFFFC107);
            tvWsStatus.setText("WebSocket 连接中");
        } else {
            setDot(vWsDot, 0xFFF15A24);
            tvWsStatus.setText("WebSocket 未连接");
        }
        // HTTP 状态（异步探测）
        new Thread(() -> {
            boolean ok;
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(ServerProtocol.baseUrl(prefs.getServer()) + "/version.json")
                        .build();
                // 走进程级共享 client：过去这里每次轮询都 new OkHttpClient()，每 3 秒泄漏
                // 一套 Dispatcher 线程池 + ConnectionPool（空闲连接 60s 才回收），停留在本页
                // 时会无界增长。共享 client 自身是 15s/30s 超时，直接拿来做探活会让线程最长
                // 挂 30s、3s 一次轮询反而堆得更多，所以用单次调用超时覆盖回原来的 4s。
                okhttp3.Call call = ApiClient.getSharedClient().newCall(req);
                call.timeout().timeout(4, java.util.concurrent.TimeUnit.SECONDS);
                okhttp3.Response resp = call.execute();
                ok = resp.isSuccessful();
                final String version = extractVersion(resp);
                resp.close();
                final boolean fOk = ok;
                final String fVer = version;
                runOnUiThread(() -> {
                    setDot(vHttpDot, fOk ? 0xFF2196F3 : 0xFFF15A24);
                    tvHttpStatus.setText("HTTP 服务" + (fOk ? "正常" : "异常"));
                    if (fVer != null) tvServerVersion.setText(fVer);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setDot(vHttpDot, 0xFFF15A24);
                    tvHttpStatus.setText("HTTP 服务异常");
                });
            }
        }).start();
    }

    private String extractVersion(okhttp3.Response resp) {
        try {
            String body = resp.body() != null ? resp.body().string() : "";
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            return "v" + json.get("versionName").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private void setDot(View dot, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
    }

    /** 修改服务器地址：保存后重启主界面以应用新地址。 */
    private void changeServerAddress() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        androidx.appcompat.widget.AppCompatEditText etServer = new androidx.appcompat.widget.AppCompatEditText(this);
        etServer.setHint("服务器地址 (如 192.168.1.1:5000)");
        etServer.setText(prefs.getServer());
        layout.addView(etServer);
        new MaterialAlertDialogBuilder(this).setTitle("修改服务器地址").setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String addr = etServer.getText().toString().trim();
                    if (addr.isEmpty()) { Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show(); return; }
                    prefs.setServer(addr);
                    // 地址变了 → 丢弃缓存的 OkHttpClient，否则新地址不会生效：
                    // 旧 client 里装的是"旧 IP 的 Dns"，之后所有请求仍打到旧服务器。
                    // 必须在下一次发起请求之前 refresh（下面重启 MainActivity，
                    // 新界面第一件事就是发请求）。
                    com.familychat.app.utils.HttpClientProvider.refresh();
                    // 重启主界面以应用新服务器地址
                    Intent it = new Intent(this, MainActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(it);
                    finish();
                }).setNegativeButton("取消", null).show();
    }
}
