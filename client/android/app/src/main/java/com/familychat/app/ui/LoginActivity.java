package com.familychat.app.ui;

import android.content.Intent;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.PreferencesManager;
import com.google.android.material.tabs.TabLayout;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoginActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private WebSocketManager ws;

    private TabLayout tabLayout;
    private EditText etLoginUser, etLoginPass, etLoginAddr;
    private EditText etRegUser, etRegNick, etRegPass, etRegConfirm, etRegAddr;
    private Button btnLogin, btnRegister;
    private TextView tvRegError;

    // Set when the Activity is destroyed, so background subnet-scan threads exit promptly
    // instead of holding this Activity (and its Views) for up to 60s.
    private volatile boolean destroyed = false;
    // 账号管理"添加账号"进入标记：登录成功后返回账号管理页
    private boolean fromAccountManage = false;

    @Override
    protected void onDestroy() {
        destroyed = true;
        // 断开登录页创建的 WebSocket（自动登录 / 登录注册成功后都会 finish 本页）。
        // 若不主动断开，这个 WS 会泄漏——带旧 token 对旧服务器地址无限重连，
        // 即使退出登录也停不下来（表现为"退出功能坏了"）。
        if (ws != null) ws.disconnect();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferencesManager.getInstance(this);
        // 账号管理"添加账号"进入时：登录成功后返回账号管理页而非主界面
        fromAccountManage = getIntent().getBooleanExtra("from_account_manage", false);

        // Auto-login if token exists（添加账号时跳过自动登录，展示登录表单）
        if (prefs.isLoggedIn() && !fromAccountManage) {
            // 这里不要建 WebSocket：MainActivity 会自己创建并连接。
            // 登录页建一个 WS 却不主动断开，就会泄漏成"退出后仍无限重连"的僵尸连接。
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_login);

        api = new ApiClient(prefs.getServer());
        ws = new WebSocketManager(prefs.getServer(), "");

        tabLayout = findViewById(R.id.tabLayout);
        tabLayout.addTab(tabLayout.newTab().setText("登录"));
        tabLayout.addTab(tabLayout.newTab().setText("注册"));

        // Login form
        etLoginUser = findViewById(R.id.etLoginUser);
        etLoginPass = findViewById(R.id.etLoginPass);
        etLoginAddr = findViewById(R.id.etLoginAddr);
        // Use saved server address; only overwrite if no server has been explicitly set
        String saved = prefs.getServer();
        etLoginAddr.setText(saved);
        btnLogin = findViewById(R.id.btnLogin);

        // Register form
        etRegUser = findViewById(R.id.etRegUser);
        etRegNick = findViewById(R.id.etRegNick);
        etRegPass = findViewById(R.id.etRegPass);
        etRegConfirm = findViewById(R.id.etRegConfirm);
        etRegAddr = findViewById(R.id.etRegAddr);
        etRegAddr.setText(prefs.getServer());
        btnRegister = findViewById(R.id.btnRegister);
        tvRegError = findViewById(R.id.tvRegError);

        // Tab switching
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                findViewById(R.id.formLogin).setVisibility(tab.getPosition() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
                findViewById(R.id.formRegister).setVisibility(tab.getPosition() == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Show login form by default
        findViewById(R.id.formLogin).setVisibility(android.view.View.VISIBLE);
        findViewById(R.id.formRegister).setVisibility(android.view.View.GONE);

        // 双向同步地址：登录/注册任一 Tab 修改都会同步到另一个（带相等保护防回环），
        // 修复"注册页改服务器地址无效（被登录页旧值覆盖）"的问题。
        etLoginAddr.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String cur = etRegAddr.getText().toString();
                if (!cur.equals(s.toString())) etRegAddr.setText(s.toString());
            }
        });
        etRegAddr.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String cur = etLoginAddr.getText().toString();
                if (!cur.equals(s.toString())) etLoginAddr.setText(s.toString());
            }
        });

        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> doRegister());

        // Auto-detect server on WiFi
        autoDetectServer();
    }

    private void autoDetectServer() {
        // Strategy 1: Use WifiManager to get gateway subnet and scan 1-254
        String gatewaySubnet = getGatewaySubnet();
        if (gatewaySubnet != null) {
            scanSubnet(gatewaySubnet, 1, 254);
            return;
        }

        // Strategy 2 (fallback): Get subnet from phone's WiFi IP
        String subnet = getPhoneSubnet();
        if (subnet == null) return;

        // Fallback: scan common host IPs (might catch other subnets)
        scanSubnetFallback(subnet);
    }

    /**
     * Get the subnet prefix from the WiFi gateway IP (works on any router subnet).
     * Example: gateway=192.168.31.1 → returns "192.168.31."
     */
    private String getGatewaySubnet() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null) {
                    int gateway = dhcp.gateway;
                    // gateway int is in host byte order on Android
                    String subnet = String.format("%d.%d.%d.",
                            (gateway >> 24) & 0xFF,
                            (gateway >> 16) & 0xFF,
                            (gateway >> 8) & 0xFF);
                    runOnUiThread(() ->
                            Toast.makeText(LoginActivity.this, "检测中: " + subnet + "x...", Toast.LENGTH_SHORT).show());
                    return subnet;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Scan a subnet range (1-254) using a thread pool.
     * Picks the first responding host on port 5000.
     */
    private void scanSubnet(String subnet, int start, int end) {
        final String targetSubnet = subnet;
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            final boolean[] found = {false};
            try {
                for (int host = start; host <= end && !found[0] && !destroyed; host++) {
                    final int h = host;
                    pool.submit(() -> {
                        if (found[0] || destroyed) return;
                        String addr = targetSubnet + h;
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(addr, 5000), 300);
                            s.close();
                            synchronized (found) {
                                if (!found[0]) {
                                    found[0] = true;
                                    final String foundAddr = addr + ":5000";
                                    runOnUiThread(() -> {
                                        if (destroyed) return; // Activity is gone — stop updating UI
                                        etLoginAddr.setText(foundAddr);
                                        etRegAddr.setText(foundAddr);
                                        prefs.setServer(foundAddr);
                                    });
                                }
                            }
                        } catch (Exception ignored) {}
                    });
                }
            } finally {
                pool.shutdown();
                if (destroyed) {
                    pool.shutdownNow(); // interrupt in-flight tasks as fast as possible
                }
                try {
                    pool.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    /**
     * Fallback scan (uses hardcoded common IPs).
     */
    private void scanSubnetFallback(String subnet) {
        new Thread(() -> {
            int[] tries = {
                1, 2, 3, 10, 50, 71, 73, 88, 99, 100, 101, 102, 103, 104, 105,
                106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120,
                130, 140, 150, 200, 250, 254
            };
            for (int host : tries) {
                if (destroyed) return;
                String addr = subnet + host;
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(addr, 5000), 300);
                    s.close();
                    final String found = addr + ":5000";
                    runOnUiThread(() -> {
                        if (destroyed) return; // Activity is gone — stop updating UI
                        etLoginAddr.setText(found);
                        etRegAddr.setText(found);
                        prefs.setServer(found);
                    });
                    return;
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private String getPhoneSubnet() {
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getName().toLowerCase();
                String display = iface.getDisplayName() != null ? iface.getDisplayName().toLowerCase() : "";
                if (name.contains("tun") || name.contains("tap") || name.contains("vpn") ||
                    name.contains("ppp") || name.contains("radmin") || name.contains("vethernet") ||
                    display.contains("virtual") || display.contains("docker"))
                    continue;
                java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && ip.contains(".")) {
                            return ip.substring(0, ip.lastIndexOf('.') + 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getServerAddr() {
        // 读取当前可见 Tab 的地址输入框（登录/注册各自独立编辑，注册页输入不再被登录页旧值覆盖）
        String addr = "";
        if (tabLayout != null && tabLayout.getSelectedTabPosition() == 1) {
            addr = etRegAddr.getText().toString().trim();
        } else {
            addr = etLoginAddr.getText().toString().trim();
        }
        if (TextUtils.isEmpty(addr)) addr = prefs.getServer();
        if (addr.startsWith("https://")) addr = addr.substring(8);
        else if (addr.startsWith("http://")) addr = addr.substring(7);
        if (addr.endsWith("/")) addr = addr.substring(0, addr.length() - 1);
        if (TextUtils.isEmpty(addr)) {
            addr = "127.0.0.1:5000";
        }
        prefs.setServer(addr);
        // 地址变了 → 丢弃缓存的 OkHttpClient（旧 client 里装着旧 IP 的 Dns）。
        // 顺序很重要：必须在 setServer 之后、下一次请求之前 —— doLogin() 紧接着就
        // 调 api.login()，那一步必须已经用上新地址。
        com.familychat.app.utils.HttpClientProvider.refresh();
        return addr;
    }

    /** Quick single-IP check for server presence. */
    private String tryQuickDetect(String subnet) {
        // Try common IPs quickly
        int[] hosts = {1, 73, 71, 100, 101, 102, 103, 104, 105, 110};
        for (int host : hosts) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(subnet + host, 5000), 200);
                s.close();
                return subnet + host + ":5000";
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void doLogin() {
        String user = etLoginUser.getText().toString().trim();
        String pass = etLoginPass.getText().toString().trim();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show();
            return;
        }

        String addr = getServerAddr();
        api.setServer(addr);
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");

        new Thread(() -> {
            try {
                Models.AuthResponse res = api.login(user, pass);
                runOnUiThread(() -> {
                    if (res != null && res.token != null && res.user != null) {
                        prefs.setToken(res.token);
                        prefs.setUserId(res.user.id);
                        api.setToken(res.token);
                        prefs.saveLoginAccount(res.user.id, res.user.username, res.user.nickname,
                                res.user.avatar, res.user.avatar_color, res.token, res.user.wx_id);
                        final int e2eeUid2 = res.user.id;
                        final String e2eePass2 = pass;
                        new Thread(() -> ensureLoginKeys(e2eeUid2, e2eePass2)).start();
                        ws = new WebSocketManager(addr, res.token);
                        ws.connect();
                        finishOrMain();
                    } else {
                        btnLogin.setEnabled(true);
                        btnLogin.setText("登录");
                        Toast.makeText(LoginActivity.this, "登录失败: 服务器返回数据无效", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    btnLogin.setText("登录");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 确保当前账号有 E2EE 密钥：
     * 1) 本地已有 → 直接用；
     * 2) 本地没有 → 先尝试本地账号快照恢复（重新登录不换密钥，避免服务器公钥被反复覆盖）；
     * 3) 快照也没有 → 从服务端拉「密码加密的密钥备份」用密码解密恢复（重装/清数据场景）；
     * 4) 服务器没有备份 → 生成新钥匙（新用户）；
     * 5) 备份存在但解密失败 → 弹窗让用户知情后再决定是否换新钥（绝不静默覆盖旧公钥）。
     * 最后幂等上传当前公钥，并上传「密码加密的密钥备份」到服务端。
     */
    private void ensureLoginKeys(final int userId, final String password) {
        boolean keysMissing = TextUtils.isEmpty(prefs.getMyPrivateKey()) || TextUtils.isEmpty(prefs.getMyPublicKey());
        if (keysMissing) {
            PreferencesManager.Account snap = prefs.findAccount(userId);
            if (snap != null && !TextUtils.isEmpty(snap.e2eePriv) && !TextUtils.isEmpty(snap.e2eePub)) {
                prefs.saveKeyPair(snap.e2eePub, snap.e2eePriv);
                android.util.Log.d("E2EE_LOG", "login: keys restored from account snapshot uid=" + userId);
                uploadKeyPairAndBackup(userId, password);
                return;
            }

            // 无密码时无法从备份恢复（理论上不会走到：ensureLoginKeys 仅登录/注册时调用）
            if (password == null || password.isEmpty()) {
                generateNewKeys(userId, password);
                return;
            }

            // 重装/清数据：从服务端取「密码加密的密钥备份」，用密码解回旧钥匙
            //
            // ⚠️ H2 修复：必须用 fetchKeyBackupState() 明确区分
            //   「服务端确认没有备份」（可生成新钥）与「没能问到」（绝不能生成新钥）。
            //   旧代码靠 `catch` 判失败，但 fetchKeyBackup() 从不抛异常、只返回 null，
            //   于是下面的 fetchError 分支实际是死代码 —— 网络一抖就会把服务器上的旧公钥覆盖掉。
            String backup = null;
            boolean backupKnownMissing = false;
            try {
                com.familychat.app.utils.KeyBackupResponse.State st = api.fetchKeyBackupState();
                if (st.exists) {
                    backup = st.backupB64;
                } else {
                    backupKnownMissing = true;
                }
            } catch (Exception e) {
                // 无法区分「没有备份」与「网络失败」：绝不能在此状态下生成并上传新公钥，
                // 否则一次网络抖动就会覆盖服务器上的旧公钥、永久作废历史消息（P2-⑤）。
                android.util.Log.e("E2EE_LOG", "login: fetch key backup failed uid=" + userId, e);
                runOnUiThread(() -> Toast.makeText(LoginActivity.this,
                        "无法连接服务器检查密钥备份，未生成新密钥。请检查网络后重新登录", Toast.LENGTH_LONG).show());
                return;
            }
            if (backupKnownMissing || backup == null || backup.isEmpty()) {
                // 服务端明确回答"没有备份"：新用户，生成新钥匙
                generateNewKeys(userId, password);
                return;
            }

            String combined = com.familychat.app.utils.KeyBackup.decryptBackup(backup, password, userId);
            if (combined != null) {
                String[] parts = combined.split("\n", 2);
                if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                    prefs.saveKeyPair(parts[0].trim(), parts[1].trim());
                    android.util.Log.d("E2EE_LOG", "login: keys restored from server backup uid=" + userId);
                    uploadKeyPairAndBackup(userId, password);
                    return;
                }
            }
            // 备份存在但解密失败（旧密码/密码输错/备份损坏）：让用户知情后再决定（P2-⑤）
            android.util.Log.w("E2EE_LOG", "login: server backup decrypt failed (password? uid=" + userId + ")");
            showRestoreFailedConfirm(userId, password);
            return;
        }
        uploadKeyPairAndBackup(userId, password);
    }

    /** 生成新钥匙并保存本地，然后上传公钥与备份。 */
    private void generateNewKeys(int userId, String password) {
        String[] keys = com.familychat.app.utils.MessageEncryptor.generateKeyPair();
        if (keys != null) {
            prefs.saveKeyPair(keys[0], keys[1]);
            android.util.Log.d("E2EE_LOG", "login: keys generated uid=" + userId);
            uploadKeyPairAndBackup(userId, password);
        } else {
            android.util.Log.e("E2EE_LOG", "login: generateKeyPair failed uid=" + userId);
        }
    }

    /** 备份存在但用当前密码解不开：弹窗让用户确认，绝不静默生成新钥覆盖旧公钥。 */
    private void showRestoreFailedConfirm(final int userId, final String password) {
        runOnUiThread(() -> new android.app.AlertDialog.Builder(LoginActivity.this)
                .setTitle("无法恢复加密密钥")
                .setMessage("服务器上存在该账号的密钥备份，但用当前密码解密失败。\n\n" +
                        "如果继续，将生成一把全新的密钥并覆盖服务器上的旧公钥：\n" +
                        "• 用旧密钥加密的历史消息将永久无法解密；\n" +
                        "• 其他成员此后发给你的新消息也可能无法解密。\n\n" +
                        "建议先确认输入的是最后一次修改后的密码，再重新登录。")
                .setPositiveButton("仍要生成新密钥", (d, w) ->
                        new Thread(() -> generateNewKeys(userId, password)).start())
                .setNegativeButton("取消", null)
                .show());
    }

    /** 幂等上传当前公钥；有密码时同时上传「密码加密的密钥备份」。 */
    private void uploadKeyPairAndBackup(int userId, String password) {
        String pub = prefs.getMyPublicKey();
        if (pub != null && !pub.isEmpty()) {
            boolean ok = api.uploadPublicKey(pub);
            android.util.Log.d("E2EE_LOG", "login: upload my pub (" + pub.length() + " chars) → " + (ok ? "OK" : "FAILED") + " uid=" + userId);
        } else {
            android.util.Log.e("E2EE_LOG", "login: no public key to upload uid=" + userId);
        }
        // 上传「密码加密的密钥备份」（自动登录无密码则跳过；备份在首次登录/注册时已上传过）
        if (password != null && !password.isEmpty()) {
            uploadKeyBackup(userId, password);
        }
    }

    /** 用当前密钥 + 登录密码生成备份密文并上传服务端。 */
    private void uploadKeyBackup(int userId, String password) {
        try {
            String pub = prefs.getMyPublicKey();
            String priv = prefs.getMyPrivateKey();
            if (pub == null || priv == null || pub.isEmpty() || priv.isEmpty()) return;
            String combined = pub + "\n" + priv;
            String backup = com.familychat.app.utils.KeyBackup.encryptBackup(combined, password, userId);
            if (backup != null) {
                boolean ok = api.uploadKeyBackup(backup);
                android.util.Log.d("E2EE_LOG", "login: upload key backup → " + (ok ? "OK" : "FAILED") + " uid=" + userId);
            }
        } catch (Exception e) {
            android.util.Log.e("E2EE_LOG", "login: upload key backup failed uid=" + userId, e);
        }
    }

    private void doRegister() {
        String user = etRegUser.getText().toString().trim();
        String nick = etRegNick.getText().toString().trim();
        String pass = etRegPass.getText().toString().trim();
        String confirm = etRegConfirm.getText().toString().trim();

        if (TextUtils.isEmpty(user) || user.length() < 2) {
            tvRegError.setText("用户名至少2个字符"); return;
        }
        if (TextUtils.isEmpty(pass) || pass.length() < 6) {
            tvRegError.setText("密码至少6个字符"); return;
        }
        if (!pass.equals(confirm)) {
            tvRegError.setText("两次密码不一致"); return;
        }
        if (TextUtils.isEmpty(nick)) nick = user;
        final String finalNick = nick;

        tvRegError.setText("");
        String addr = getServerAddr();
        api.setServer(addr);
        btnRegister.setEnabled(false);
        btnRegister.setText("注册中...");

        new Thread(() -> {
            try {
                Models.AuthResponse res = api.register(user, pass, finalNick);
                runOnUiThread(() -> {
                    if (res != null && res.token != null && res.user != null) {
                        prefs.setToken(res.token);
                        prefs.setUserId(res.user.id);
                        api.setToken(res.token);
                        prefs.saveLoginAccount(res.user.id, res.user.username, res.user.nickname,
                                res.user.avatar, res.user.avatar_color, res.token, res.user.wx_id);
                        final int e2eeUid2 = res.user.id;
                        final String e2eePass2 = pass;
                        new Thread(() -> ensureLoginKeys(e2eeUid2, e2eePass2)).start();
                        ws = new WebSocketManager(addr, res.token);
                        ws.connect();
                        finishOrMain();
                    } else {
                        btnRegister.setEnabled(true);
                        btnRegister.setText("注册");
                        tvRegError.setText("注册失败: 服务器返回数据无效");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnRegister.setEnabled(true);
                    btnRegister.setText("注册");
                    tvRegError.setText(e.getMessage());
                });
            }
        }).start();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("server", prefs.getServer());
        intent.putExtra("token", prefs.getToken());
        startActivity(intent);
        finish();
    }

    /** 账号管理"添加账号"成功后返回账号管理页；否则进入主界面。 */
    private void finishOrMain() {
        if (fromAccountManage) {
            setResult(RESULT_OK);
            finish();
        } else {
            startMainActivity();
        }
    }
}
