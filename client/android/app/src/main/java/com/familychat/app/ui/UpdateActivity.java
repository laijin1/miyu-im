package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.familychat.app.BuildConfig;
import com.familychat.app.R;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 检查更新页：打开时拉取服务器 version.json 显示最新版本与更新日志；
 * 点击【开始更新】用 OkHttp 在界面内实时下载 APK（进度条显示百分比，不依赖通知栏）；
 * 下载完成后做三重校验（真实版本号 / SHA-256 / 签名证书），全部通过才唤起
 * **系统安装页**（FileProvider content:// + ACTION_VIEW），由用户在系统界面上确认安装。
 * 退出页面即终止下载，不做后台下载。
 *
 * ⚠️ 合规红线（2026-09 整改，勿回退）：
 * 1) 只允许从**本服务同源**的 version.json → downloadUrl 取包，禁止任意外部 URL；
 * 2) **不得**再使用 PackageInstaller 会话直装/静默安装，也不得把包复制进公共下载目录再弹装——
 *    这两种"应用代用户装包"的路径正是 OPPO 安装器判定「自动捆绑安装」的依据。
 *    安装动作必须由用户在系统安装界面上亲手确认。
 *    详见 docs/audits/apk-av/报毒原因分析报告-补充-OPPO实测.md。
 */
public class UpdateActivity extends AppCompatActivity {

    private static final String APK_NAME = "update.apk";
    private static final int MAX_DOWNLOAD_ATTEMPTS = 5; // 断线续传最大重试次数

    private TextView tvLocalVersion;
    private TextView tvLatestVersion;
    private TextView tvChangelog;
    private TextView tvStatus;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private Button btnUpdate;

    private PreferencesManager prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Call downloadCall;
    private volatile boolean destroyed = false;
    private boolean downloading = false;
    private boolean apkReady = false;        // APK 已下载完成、还没唤起安装页
    private boolean pendingDownload = false; // 去系统"安装未知应用"设置后返回要继续下载

    private String downloadUrl;
    private File apkFile;
    private int remoteCode = 0; // 服务器最新版本号（fetchVersion 时获取）
    private String apkSha256 = ""; // 服务器声明的 APK SHA-256（fetchVersion 时获取，用于下载完整性校验）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        prefs = PreferencesManager.getInstance(this);
        apkFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME);
        // 每次打开先清理旧包：避免把之前下载的旧版本 APK 误装上去（旧包版本号和最新版对不上）
        if (apkFile.exists()) {
            com.familychat.app.utils.AppLog.d("UPDATE",
                    "发现旧包 update.apk 大小=" + apkFile.length() + "B，已删除");
            apkFile.delete();
        }
        com.familychat.app.utils.AppLog.d("UPDATE",
                "打开更新页, 本机版本=" + BuildConfig.VERSION_CODE + "(" + BuildConfig.VERSION_NAME
                        + "), 服务器=" + prefs.getServer());

        ImageButton btnBack = findViewById(R.id.btnUpdateBack);
        btnBack.setOnClickListener(v -> finish());

        tvLocalVersion = findViewById(R.id.tvUpdateLocalVersion);
        tvLatestVersion = findViewById(R.id.tvUpdateLatestVersion);
        tvChangelog = findViewById(R.id.tvUpdateChangelog);
        tvStatus = findViewById(R.id.tvUpdateStatus);
        tvProgress = findViewById(R.id.tvUpdateProgress);
        progressBar = findViewById(R.id.pbUpdateProgress);
        btnUpdate = findViewById(R.id.btnStartUpdate);

        tvLocalVersion.setText("v" + BuildConfig.VERSION_NAME);
        progressBar.setVisibility(View.GONE);

        btnUpdate.setOnClickListener(v -> onStartUpdateClick());

        fetchVersion();
    }

    /** 拉取服务器 version.json，读取最新版本号 / 更新日志 / APK 下载地址。 */
    private void fetchVersion() {
        tvStatus.setText("正在检查更新...");
        btnUpdate.setEnabled(false);
        btnUpdate.setText("正在检查...");
        new Thread(() -> {
            try {
                String base = ServerProtocol.baseUrl(prefs.getServer());
                // 走进程级共享 client：它自带假域名模式的自定义 Dns（miyu.lan → 用户配置的 IP）。
                // 自建 OkHttpClient 没有这份 Dns，系统解析不了 miyu.lan，会直接
                // UnknownHostException（"Unable to resolve host miyu.lan"）。
                OkHttpClient c = HttpClientProvider.shared();
                Request req = new Request.Builder().url(base + "/version.json").build();
                Response resp = c.newCall(req).execute();
                if (!resp.isSuccessful()) throw new Exception("服务器返回 " + resp.code());
                String body = resp.body().string();
                com.google.gson.JsonObject json =
                        com.google.gson.JsonParser.parseString(body).getAsJsonObject();

                final int remoteCode = json.get("versionCode").getAsInt();
                UpdateActivity.this.remoteCode = remoteCode;
                final String remoteName = json.get("versionName").getAsString();
                final String changelog = json.has("changelog")
                        ? json.get("changelog").getAsString() : "";
                // 发布窗口保护：服务器版本号已升但新 APK 未就位时 published=false，
                // 此时不提供下载（否则会拿到与版本号不符的旧包）。
                final boolean published = !json.has("published") || json.get("published").getAsBoolean();
                String dl = json.get("downloadUrl").getAsString();
                // 只允许同源下载: 相对路径拼 base; 绝对 URL 必须与本服务同源(带路径边界), 拒绝任意外部 URL
                if (dl.startsWith("http")) {
                    if (!dl.startsWith(base) || dl.length() == base.length() || dl.charAt(base.length()) != '/') {
                        throw new Exception("下载地址不是本服务同源, 已拒绝");
                    }
                    downloadUrl = dl;
                } else {
                    downloadUrl = base + dl;
                }
                final String sha = (json.has("apkSha256") && !json.get("apkSha256").isJsonNull())
                        ? json.get("apkSha256").getAsString().trim() : "";
                apkSha256 = sha;
                com.familychat.app.utils.AppLog.d("UPDATE",
                        "检查更新: 本机=" + BuildConfig.VERSION_CODE + ", 服务器最新=" + remoteCode
                                + "(" + remoteName + "), 下载地址=" + downloadUrl);

                uiHandler.post(() -> {
                    tvLatestVersion.setText("v" + remoteName);
                    tvChangelog.setText(changelog == null || changelog.trim().isEmpty()
                            ? "暂无更新说明" : changelog);
                    if (!published) {
                        // 发布窗口：版本文件已更新但 APK 未就位，稍后再试，避免下载到旧包
                        tvStatus.setText("新版本发布中，请稍后重试");
                        btnUpdate.setEnabled(false);
                        btnUpdate.setText("稍后再试");
                    } else if (remoteCode <= BuildConfig.VERSION_CODE) {
                        tvStatus.setText("当前已是最新版本");
                        btnUpdate.setEnabled(false);
                        btnUpdate.setText("已是最新版本");
                    } else {
                        tvStatus.setText("发现新版本");
                        btnUpdate.setEnabled(true);
                        btnUpdate.setText("开始更新");
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    tvStatus.setText("检查更新失败：" + e.getMessage());
                    btnUpdate.setEnabled(true);
                    btnUpdate.setText("重新检查");
                });
            }
        }).start();
    }

    private void onStartUpdateClick() {
        if (downloading) return;
        if (downloadUrl == null) { // 版本还没拉取到（比如网络失败）→ 重新检查
            fetchVersion();
            return;
        }
        // 本次会话内已下载完成的 APK → 直接复用，不重复下载
        // （onCreate 已清理过旧包，只要文件存在，就一定是本次下载的正确版本）
        if (apkFile.exists() && apkFile.length() > 0) {
            com.familychat.app.utils.AppLog.d("UPDATE",
                    "点击更新: 复用已下载包 大小=" + apkFile.length() + "B");
            prepareInstall();
            return;
        }
        // 下载前先确认"安装未知应用"权限（Android 8.0+），未开启则引导去设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            com.familychat.app.utils.AppLog.d("UPDATE",
                    "点击更新: 未授权安装未知应用，弹窗引导去设置");
            pendingDownload = true;
            showInstallPermissionDialog();
            return;
        }
        startDownload();
    }

    /** 子线程用 OkHttp 下载 APK，进度经 Handler 回主线程刷新，避免 ANR。
     *  支持断点续传：网络中断时保留半截临时文件，下一轮带 Range 头从断点续传，
     *  不从头重下，最多重试 MAX_DOWNLOAD_ATTEMPTS 次。 */
    private void startDownload() {
        downloading = true;
        btnUpdate.setEnabled(false);
        btnUpdate.setText("正在下载...");
        tvStatus.setText("");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvProgress.setText("0%");

        // 走进程级共享 client（自带假域名模式 Dns），不再自建实例。
        // ⚠️ 共享 client 的 readTimeout 是 30s（两次读之间的间隔上限），
        //    对 APK 下载够用；另在每次 newCall 后用 call.timeout() 给整次调用
        //    加一个宽松的 30 分钟总预算，防止极端情况下连接永久挂死。
        OkHttpClient client = HttpClientProvider.shared();
        com.familychat.app.utils.AppLog.d("UPDATE", "开始下载: " + downloadUrl);
        // 先下到临时文件，全部写完再改名，避免把半截文件误当"已下载"
        File tmp = new File(apkFile.getParentFile(), APK_NAME + ".tmp");

        new Thread(() -> {
            File dir = apkFile.getParentFile();
            if (dir != null) dir.mkdirs();
            long fullSize = 0; // 完整 APK 大小（首次响应或 Content-Range 得到）
            long done = 0;     // 已写进磁盘的累计字节数（断点续传起点）
            long lastThrottle = 0;
            int lastPct = -1;

            for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    // 有已下字节就带 Range 头续传
                    Request.Builder rb = new Request.Builder().url(downloadUrl);
                    if (done > 0) rb.header("Range", "bytes=" + done + "-");
                    downloadCall = client.newCall(rb.build());
                    // 每次 attempt 都是新的 call，必须各自覆盖：call.timeout() 是
                    // 整次调用（DNS/connect/write/read）的总预算，不是 read 超时。
                    downloadCall.timeout().timeout(30, TimeUnit.MINUTES);
                    Response resp = downloadCall.execute();
                    // 416 = Range 从文件末尾开始，说明文件其实已下完 → 直接完成，防止续传死循环
                    if (resp.code() == 416) {
                        resp.close();
                        if (fullSize > 0 && done >= fullSize) {
                            finalizeDownload();
                        } else {
                            failDownload(new Exception("服务器返回 416"));
                        }
                        return;
                    }
                    if (!resp.isSuccessful()) throw new Exception("服务器返回 " + resp.code());

                    // 服务端不支持 Range 却要求续传 → 删掉半截文件，从头下
                    if (done > 0 && resp.code() != 206) {
                        resp.close();
                        if (tmp.exists()) tmp.delete();
                        done = 0;
                        fullSize = 0;
                        uiHandler.post(() -> tvStatus.setText("服务器不支持续传，从头下载..."));
                        continue;
                    }

                    // 完整大小：优先从 Content-Range 解析，否则按首次响应的 Content-Length
                    if (fullSize == 0) {
                        String cr = resp.header("Content-Range");
                        if (cr != null && cr.contains("/")) {
                            fullSize = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                        } else {
                            fullSize = done + resp.body().contentLength();
                        }
                    }

                    boolean append = done > 0;
                    try (InputStream is = resp.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(tmp, append)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            if (destroyed) return;
                            fos.write(buf, 0, len);
                            done += len;
                            if (fullSize > 0) {
                                int pct = (int) (done * 100 / fullSize);
                                if (pct > 100) pct = 100;
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    final int fpct = pct;
                                    uiHandler.post(() -> {
                                        progressBar.setProgress(fpct);
                                        tvProgress.setText(fpct + "%");
                                    });
                                }
                            } else if (done - lastThrottle >= 512 * 1024) {
                                // 服务器没给长度时：进度条切成不确定模式(滚动动画)，文字按已下载字节数显示
                                lastThrottle = done;
                                final long fdone = done;
                                uiHandler.post(() -> {
                                    if (!progressBar.isIndeterminate()) progressBar.setIndeterminate(true);
                                    tvProgress.setText((fdone / 1024 / 1024) + " MB");
                                });
                            }
                        }
                    }
                    if (destroyed) return;

                    // 流读完了但字节不足 → 视为中断，下一轮续传
                    if (fullSize > 0 && done < fullSize) {
                        final int fa = attempt;
                        uiHandler.post(() -> tvStatus.setText(
                                "下载中断，正在续传(" + (fa + 1) + "/" + MAX_DOWNLOAD_ATTEMPTS + ")..."));
                        continue;
                    }

                    // 下载完成
                    finalizeDownload();
                    return;
                } catch (Exception e) {
                    if (destroyed) return;
                    // 字节其实已下满（比如服务器响应格式问题在最后抛错）→ 文件就是完整的，直接完成
                    if (fullSize > 0 && done >= fullSize) {
                        finalizeDownload();
                        return;
                    }
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                        failDownload(e);
                        return;
                    }
                    // 网络中断（unexpected end of stream 等）：保留半截 tmp，下一轮带 Range 续传
                    com.familychat.app.utils.AppLog.d("UPDATE",
                            "下载中断(第" + attempt + "次): " + e.getMessage() + ", 已下 " + done + "B，准备续传");
                    final int fa = attempt;
                    uiHandler.post(() -> tvStatus.setText(
                            "网络中断，正在续传(" + (fa + 1) + "/" + MAX_DOWNLOAD_ATTEMPTS + ")..."));
                }
            }
            failDownload(new Exception("重试次数用完"));
        }).start();
    }

    /** 下载完成收尾：把临时文件改名成最终 APK，唤起系统安装页。 */
    private void finalizeDownload() {
        if (apkFile.exists()) apkFile.delete();
        File tmp = new File(apkFile.getParentFile(), APK_NAME + ".tmp");
        if (!tmp.renameTo(apkFile)) {
            failDownload(new Exception("保存 APK 文件失败"));
            return;
        }
        apkReady = true;
        downloading = false;
        com.familychat.app.utils.AppLog.d("UPDATE",
                "下载完成, 文件大小=" + apkFile.length() + "B, SHA-256=" + sha256(apkFile)
                        + ", 服务器版本=" + remoteCode);
        uiHandler.post(() -> {
            progressBar.setIndeterminate(false); // 先复位，否则不确定模式下 setProgress 无效
            progressBar.setProgress(100);
            tvProgress.setText("100%");
            tvStatus.setText("下载完成，准备安装...");
            prepareInstall();
        });
    }

    /** 下载彻底失败：清掉半截文件，恢复按钮，显示错误。 */
    private void failDownload(Exception cause) {
        downloading = false;
        if (apkFile.getParentFile() != null) {
            File tmp = new File(apkFile.getParentFile(), APK_NAME + ".tmp");
            if (tmp.exists()) tmp.delete();
        }
        com.familychat.app.utils.AppLog.e("UPDATE", "下载失败: " + cause.getMessage(), cause);
        uiHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            progressBar.setIndeterminate(false);
            tvProgress.setText("");
            tvStatus.setText("下载失败：" + cause.getMessage());
            btnUpdate.setEnabled(true);
            btnUpdate.setText("重试下载");
        });
    }

    /** 读取一个 APK 文件的真实版本号（不安装）。返回 -1 表示读不到/文件损坏。 */
    private int getApkVersionCode(File apk) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            // Android 13+ 部分机型 getPackageArchiveInfo(flags=0) 返回 null，带标志位再试一次
            if (info == null && Build.VERSION.SDK_INT >= 33) {
                info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        android.content.pm.PackageManager.GET_ACTIVITIES
                                | android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES);
            }
            return info == null ? -1 : info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 唤起系统安装页前：Android 8.0+ 先校验"安装未知应用"权限。 */
    private void prepareInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionDialog();
            return;
        }
        if (!apkFile.exists()) return;

        // 安装前校验：读取下载文件的真实版本号，必须等于服务器版本，否则删掉重下
        int apkVer = getApkVersionCode(apkFile);
        com.familychat.app.utils.AppLog.d("UPDATE",
                "准备安装: " + apkFile.getAbsolutePath() + " 大小=" + apkFile.length()
                        + "B 文件真实版本=" + apkVer + " 应装版本=" + remoteCode
                        + " 本机=" + BuildConfig.VERSION_CODE);
        if (apkVer > 0 && apkVer != remoteCode) {
            com.familychat.app.utils.AppLog.e("UPDATE",
                    "下载的文件版本不对(实际" + apkVer + " 应为" + remoteCode + ")，删除并重新下载");
            apkFile.delete();
            tvStatus.setText("下载的文件版本不对，正在重新下载...");
            startDownload();
            return;
        }

        // 完整性校验: SHA-256 必须等于服务器声明的 apkSha256。
        // 服务器旧版未提供该字段时跳过; 不匹配说明文件被篡改/损坏, 删除并要求重试(不自动循环)。
        if (!apkSha256.isEmpty()) {
            String actual = sha256(apkFile);
            if (!apkSha256.equalsIgnoreCase(actual)) {
                com.familychat.app.utils.AppLog.e("UPDATE",
                        "SHA-256 校验失败: 期望=" + apkSha256 + " 实际=" + actual + ", 已删除, 请重试");
                apkFile.delete();
                downloading = false;
                tvStatus.setText("下载校验失败(文件被篡改或损坏)，已删除，请重试");
                tvProgress.setText("");
                progressBar.setProgress(0);
                btnUpdate.setEnabled(true);
                btnUpdate.setText("重新更新");
                return;
            }
        }

        // 签名校验: 新包的签名证书必须等于"当前安装包的证书"或"本 App 的发布证书"。
        // 防的是更新包在传输/落地环节被换成第三方改包 —— 这是"捆绑/静默替换"类恶意行为的
        // 主要入口，也是安全软件判定风险时最看重的一环。SHA-256 只能证明"没被改坏",
        // 证明不了"是谁签的", 两者都要。
        if (!verifyApkSignature(apkFile)) {
            com.familychat.app.utils.AppLog.e("UPDATE", "更新包签名校验失败，已删除并中止安装");
            apkFile.delete();
            downloading = false;
            tvStatus.setText("更新包签名校验失败（可能被替换或篡改），已删除，请联系管理员");
            tvProgress.setText("");
            progressBar.setProgress(0);
            btnUpdate.setEnabled(true);
            btnUpdate.setText("重新更新");
            return;
        }

        apkReady = false; // 已唤起安装页，防止 onResume 重复触发
        openApkForInstall(apkFile);
    }

    /**
     * 校验下载到的更新包签名。返回 true 表示可安全安装。
     *
     * 允许的证书集合 = 当前已安装包的证书 + 本 App 的正式发布证书（由 build.gradle 注入
     * BuildConfig.RELEASE_CERT_SHA256）。
     *
     * 之所以把"当前证书"也算作合法，是为了让早期用调试签名发布的老用户能平滑升到正式签名包
     * （覆盖安装仍会被系统拒绝，需卸载重装一次，但至少不会因为校验过不去而下不动包）。
     * 一旦全部用户升级完毕，可把这一项去掉、只留发布证书，校验会更严。
     */
    private boolean verifyApkSignature(File apk) {
        Set<String> allowed = allowedSignerSha256();
        if (allowed.isEmpty()) { // 发布证书未注入（如旧构建产物）→ 不阻断，仅记录
            com.familychat.app.utils.AppLog.e("UPDATE", "未能获取发布证书指纹，跳过签名校验");
            return true;
        }
        String actual = apkSignerSha256(apk);
        if (actual == null || !allowed.contains(actual)) {
            com.familychat.app.utils.AppLog.e("UPDATE",
                    "签名不匹配: 实际=" + actual + ", 允许=" + allowed);
            return false;
        }
        com.familychat.app.utils.AppLog.d("UPDATE", "更新包签名校验通过: " + actual);
        return true;
    }

    /**
     * 允许的签名证书 SHA-256 集合（大写十六进制，无冒号）。
     *
     * = 当前已安装包的证书 + 本 App 的正式发布证书（build.gradle 注入
     * BuildConfig.RELEASE_CERT_SHA256）。
     *
     * ⚠️ 2026-09 整改：历史上这里还会从 `assets/legacy_debug_cert.pem` 读入那张
     * Android 调试证书（全世界公开的固定密钥）并放进白名单。该资源已从包内删除，
     * 原因有二：
     *   1) 包里带一张证书文件本身就是安全引擎的"改包/签名替换"特征；
     *   2) 把公开调试证书当合法更新来源，等于给"任意第三方改包"留了后门。
     * 老调试签名用户仍能正常下载（下载校验只看上面两项——当前包证书就是调试证书），
     * 只是最后一步覆盖安装仍需先卸载旧版，这是系统行为，与白名单无关。
     */
    private Set<String> allowedSignerSha256() {
        Set<String> out = new HashSet<>();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), packageCertFlags());
            if (pi.signatures != null) {
                for (Signature s : pi.signatures) {
                    out.add(sha256Hex(s.toByteArray()));
                }
            }
        } catch (Exception e) {
            com.familychat.app.utils.AppLog.e("UPDATE", "读取当前包签名失败: " + e.getMessage());
        }
        if (BuildConfig.RELEASE_CERT_SHA256 != null && !BuildConfig.RELEASE_CERT_SHA256.isEmpty()) {
            out.add(BuildConfig.RELEASE_CERT_SHA256.toUpperCase());
        }
        return out;
    }

    /** 读取一个尚未安装的 APK 文件的签名证书 SHA-256（大写十六进制）。 */
    private String apkSignerSha256(File apk) {
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), packageCertFlags());
            if (info == null || info.signatures == null || info.signatures.length == 0) return null;
            return sha256Hex(info.signatures[0].toByteArray());
        } catch (Exception e) {
            com.familychat.app.utils.AppLog.e("UPDATE", "读取更新包签名失败: " + e.getMessage());
            return null;
        }
    }

    /** Android 13+ 用 GET_SIGNING_CERTIFICATES，低版本回退 GET_SIGNATURES（两者都能读到签名证书）。 */
    private int packageCertFlags() {
        return Build.VERSION.SDK_INT >= 33
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从系统"安装未知应用"设置页返回：已下载的文件直接安装，不重复下载。 */
    @Override
    protected void onResume() {
        super.onResume();
        com.familychat.app.utils.AppLog.d("UPDATE",
                "onResume: apkReady=" + apkReady + " pendingDownload=" + pendingDownload
                        + " 文件存在=" + apkFile.exists() + " 大小=" + (apkFile.exists() ? apkFile.length() : 0));
        if (apkReady && apkFile.exists()) {
            prepareInstall();
        } else if (pendingDownload) {
            pendingDownload = false;
            startDownload();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (downloadCall != null) downloadCall.cancel();
        super.onDestroy();
    }

    private void showInstallPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("需要安装权限")
                .setMessage("安装更新 APK 需要允许本应用“安装未知应用”。\n\n开启权限返回后，将直接使用已下载好的安装包，无需重新下载。")
                .setPositiveButton("去开启", (d, w) -> openInstallSourceSettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openInstallSourceSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请在系统设置→应用→本应用中允许“安装未知应用”", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 唤起**系统安装页**，由用户在系统界面上确认安装。
     *
     * ⚠️ 合规红线：这里**只保留** FileProvider + ACTION_VIEW 这一条路径。
     * 已删除的两条路径（勿恢复）：
     *   - `installViaPackageInstaller()`：PackageInstaller 会话直装 = 应用代装包，风控重点；
     *   - `installViaPublicDownloads()`：把包复制进公共下载目录再弹装，同样属"代装包"，
     *     且会在用户下载目录里留下重复的安装包。
     */
    private void openApkForInstall(File apk) {
        installViaFileProvider(apk);
    }

    /** FileProvider content:// 唤起系统原生安装页（禁止 file:// Uri）。 */
    private void installViaFileProvider(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            com.familychat.app.utils.AppLog.d("UPDATE",
                    "唤起系统安装页 " + uri + " 文件大小=" + apk.length() + "B");
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            com.familychat.app.utils.AppLog.e("UPDATE", "无法打开安装界面", e);
            Toast.makeText(this, "无法打开安装界面，请到文件管理中手动安装", Toast.LENGTH_LONG).show();
        }
    }

    /** 计算文件 SHA-256（用于确认下载的 APK 与服务器文件字节级一致）。 */
    private String sha256(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }
}
