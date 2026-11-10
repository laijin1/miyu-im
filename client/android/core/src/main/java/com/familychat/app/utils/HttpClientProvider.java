package com.familychat.app.utils;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * 进程级 OkHttpClient 门面（假域名模式 + 统一 DNS）。
 *
 * <h3>为什么存在</h3>
 * 服务端只监听 TLS，证书 SAN = {@code DNS:miyu.lan}。局域网 IP 会随 DHCP 变化，
 * 而证书不可能写死一个会变的 IP —— 所以客户端把所有请求都写成
 * {@code https://miyu.lan:5000/...}，再由本类的自定义 {@link Dns} 把
 * {@code miyu.lan} 解析到用户配置的那个 IP。用户仍然只填裸 {@code "IP:port"}，
 * IP 变了只改地址，证书和 APK 都不用动。
 *
 * <h3>取值范围与边界</h3>
 * <ul>
 *   <li>自定义 Dns <b>只覆盖</b> {@code miyu.lan}，其余主机名原样交给
 *       {@link Dns#SYSTEM}。</li>
 *   <li>{@code miyu.lan} 直接解析成 IPv4 字面量，<b>绝不</b>回落到
 *       {@code Dns.SYSTEM} —— 否则若设备 DNS 恰好能解析该名字，就会绕开用户
 *       配置的 IP；而且委托回自身也有递归风险。</li>
 *   <li>server 是域名（非 IPv4）时不启用自定义 Dns，走系统 DNS（公网预留）。</li>
 * </ul>
 *
 * <h3>缓存与重建</h3>
 * 懒加载 + 双重检查，成功后<b>永久缓存</b>（两个 client 各自带连接池，不宜频繁重建）。
 * 地址解析本身<b>不是</b>缓存的：Dns 每次 lookup 都现取一次当前 server
 * （{@link ServerProtocol#fakeHostDns}），所以即使调用方把 client 存成了快照
 * —— Glide 的 Registry 每进程只注册一次，{@code refresh()} 换不掉它手里那份 ——
 * 服务器地址变了以后也不会再被解析到旧 IP。{@link #refresh()} 仍应在地址变更后调用，
 * 但它现在只负责丢弃旧连接池与旧超时配置，不再是"图片能不能加载"的前提。
 *
 * <h3>Glide / ExoPlayer</h3>
 * 它们不走 OkHttp，拿不到这里的 Dns，所以它们的 URL 里必须是 {@code miyu.lan}
 * （由 {@link ServerProtocol#baseUrl} 保证），并且分别通过
 * {@code OkHttpUrlLoader}（见 :app 的 MiyuGlideModule）与
 * {@code OkHttpDataSource}（见 ChatVideoPlayerActivity）复用本类的 client。
 */
public final class HttpClientProvider {

    private static final String TAG = "HttpClientProvider";

    private static final Object LOCK = new Object();

    /** 通用 client；未构建时为 null（build() 可能失败，所以不能用 final 字段）。 */
    private static volatile OkHttpClient shared;

    /** WebSocket 专用 client；与 {@link #shared} 是<b>两个不同实例</b>（超时语义不同）。 */
    private static volatile OkHttpClient webSocket;

    private HttpClientProvider() {
    }

    /**
     * 通用 client：REST / 文件上传 / 媒体加载，与改造前 {@code ApiClient} 的共享
     * client 参数完全一致（15s / 30s / 30s + 跟随重定向 + 连接失败重试）。
     */
    public static OkHttpClient shared() {
        OkHttpClient c = shared;
        if (c == null) {
            synchronized (LOCK) {
                c = shared;
                if (c == null) {
                    c = build(false);
                    shared = c;
                }
            }
        }
        return c;
    }

    /**
     * WebSocket 专用 client：connect 10s / <b>read 0（不超时）</b> / ping 20s。
     *
     * <p>read 必须是 0：WS 是长连接，服务端可能长时间没有下行数据；套用通用的
     * 30s read 超时会把空闲连接判死。ping 每 20s 一次是因为服务端 Jetty 会回收
     * 空闲连接（历史上由此导致来电偶发失败）。
     */
    public static OkHttpClient webSocket() {
        OkHttpClient c = webSocket;
        if (c == null) {
            synchronized (LOCK) {
                c = webSocket;
                if (c == null) {
                    c = build(true);
                    webSocket = c;
                }
            }
        }
        return c;
    }

    /**
     * 服务器地址变更后调用：清空缓存，下次 {@link #shared()} / {@link #webSocket()}
     * 用新地址重建。
     *
     * <p><b>调用时机</b>：{@code ServerInfoActivity} 保存新地址后、{@code LoginActivity}
     * 把输入写入 prefs 后。旧实例不做 shutdown —— 可能还有调用方正握着它发请求
     * （如已发出的上传/下载），强行 shutdown 会把它们打断。
     */
    public static void refresh() {
        synchronized (LOCK) {
            shared = null;
            webSocket = null;
        }
        AppLog.d(TAG, "refresh: client cache cleared");
    }

    // =================================================================
    //  内部
    // =================================================================

    private static OkHttpClient build(boolean forWebSocket) {
        // Dns 是"每次解析现取当前地址"的动态实现（见 ServerProtocol.fakeHostDns）：
        // 这样即使调用方把 client 存成了快照（Glide 的 Registry 就是每进程只注册一次），
        // 服务器地址变更后也不会被解析到旧 IP。refresh() 只负责丢弃连接池/超时配置缓存。
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .dns(ServerProtocol.fakeHostDns(HttpClientProvider::currentServer));
        if (forWebSocket) {
            b.connectTimeout(10, TimeUnit.SECONDS)
             .readTimeout(0, TimeUnit.MILLISECONDS)
             .pingInterval(20, TimeUnit.SECONDS);
        } else {
            b.connectTimeout(15, TimeUnit.SECONDS)
             .readTimeout(30, TimeUnit.SECONDS)
             .writeTimeout(30, TimeUnit.SECONDS)
             .followRedirects(true)
             .retryOnConnectionFailure(true);
        }
        return b.build();
    }

    /**
     * 读当前服务器地址（裸 {@code "host:port"}）。
     *
     * <p>只打印 host，不打印端口以外的内容（该值不是凭证，但也不需要在日志里重复）。
     * 取不到时返回空串 → 调用方走 {@link Dns#SYSTEM}，与改造前行为一致。
     */
    private static String currentServer() {
        Context ctx = AppLog.appContext();
        if (ctx == null) {
            AppLog.d(TAG, "currentServer: no app context yet, using system DNS");
            return "";
        }
        try {
            return PreferencesManager.getInstance(ctx).getServer();
        } catch (Throwable t) {
            // EncryptedSharedPreferences 是 fail-closed 的（Keystore 异常会抛
            // IllegalStateException）。这里绝不能把异常继续抛出去：本类在静态初始化
            // 路径上，抛出去会让整个进程启动失败。退回系统 DNS 让 App 还能起来。
            AppLog.e(TAG, "currentServer: read prefs failed, using system DNS", t);
            return "";
        }
    }
}
