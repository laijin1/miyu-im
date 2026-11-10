package com.familychat.app.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;

import okhttp3.Dns;

/**
 * Utility for detecting whether the server is on a LAN (requires HTTP/WS)
 * or a public/WAN address (requires HTTPS/WSS for Cloudflare Tunnel, etc.).
 *
 * Matches the same logic as the web client's connMode detection.
 */
public class ServerProtocol {

    /**
     * Returns true if the server address looks like a WAN/public address
     * that should use HTTPS/WSS.
     */
    public static boolean isSecure(String server) {
        if (server == null || server.isEmpty()) return false;
        String host = server.split(":")[0].trim().toLowerCase();
        // 空白串（如服务器地址只输了空格）不能落到最后一行被判成 WAN：
        // 否则 httpOf() 会拼出 "https://   " 这种无意义地址，报错信息也会误导排查方向。
        // 判为 false 表示"不是已知的公网 WAN 主机"，与局域网默认行为一致。
        if (host.isEmpty()) return false;
        // Localhost
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0")) return false;
        // Private IPv4 ranges
        if (host.startsWith("192.168.")) return false;
        if (host.startsWith("10.")) return false;
        if (host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return false;
        // Anything else (public domain, public IP, Cloudflare Tunnel) → WAN
        return true;
    }

    /** Returns "https://" for WAN servers, "http://" for LAN. */
    public static String httpOf(String server) {
        return isSecure(server) ? "https://" : "http://";
    }

    /** Returns "wss://" for WAN servers, "ws://" for LAN. */
    public static String wsOf(String server) {
        return isSecure(server) ? "wss://" : "ws://";
    }

    // =================================================================
    //  假域名模式（miyu.lan + 自签 HTTPS）
    //
    //  背景：服务端只监听 TLS，证书 SAN = DNS:miyu.lan。局域网 IP 会随 DHCP
    //  变化，而证书里不可能写死一个会变的 IP —— 所以客户端统一把请求写成
    //  https://miyu.lan:5000/...，再由 HttpClientProvider 的自定义 Dns 把
    //  miyu.lan 解析到用户配置的那个 IP。用户仍然只填裸 "IP:port"。
    //
    //  ⚠️ 为什么媒体 URL 也必须走 baseUrl()：Glide / ExoPlayer 走系统 DNS，
    //  拿不到 OkHttp 的自定义 Dns，所以它们的 URL 里**必须**是 miyu.lan，
    //  不能是 IP —— 否则既解析不到、证书也对不上。
    // =================================================================

    /** 内部假域名。用户看不到它，只出现在 App 内部拼出的 URL 里。 */
    public static final String FAKE_HOST = "miyu.lan";

    /** server 未写端口时的默认端口（与服务端 FamilyChatServer.PORT 一致）。 */
    public static final int DEFAULT_PORT = 5000;

    /**
     * server 的 host 是否 IPv4 字面量。
     *
     * <p>IPv4 字面量 → 启用假域名模式（IP 会变，证书只认 miyu.lan）；
     * 域名 → 不启用（公网域名走系统 DNS，用真证书，原样拼 URL）。
     *
     * <p>纯数字点分、每段 0-255；`localhost` / IPv6 字面量都**不算** IPv4 →
     * 走"域名"分支（localhost 本来就解析得到，IPv6 需要方括号语法，本类不支持）。
     */
    public static boolean isIpServer(String server) {
        if (server == null || server.isEmpty()) return false;
        String host = hostOf(server);
        if (host.isEmpty()) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            int v;
            try {
                v = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return false;   // 全数字但溢出（如 "99999"）
            }
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    /**
     * server 的 host 部分（不含端口），小写、去空白。
     *
     * <p>格式约定：{@code "host:port"} 或 {@code "host"}。**不支持带方括号的
     * IPv6 字面量**（{@code [::1]:5000} 会被截成 {@code "["}）—— 本项目的
     * server 值由客户端自己写入，只可能是 IPv4 或域名。
     */
    public static String hostOf(String server) {
        if (server == null || server.isEmpty()) return "";
        String s = server.trim();
        int colon = s.indexOf(':');
        String host = (colon >= 0) ? s.substring(0, colon) : s;
        return host.trim().toLowerCase();
    }

    /** server 的端口（不含 host）；缺失或非法时返回 {@link #DEFAULT_PORT}。 */
    public static int portOf(String server) {
        if (server == null || server.isEmpty()) return DEFAULT_PORT;
        String s = server.trim();
        int colon = s.indexOf(':');
        if (colon < 0) return DEFAULT_PORT;
        String portStr = s.substring(colon + 1).trim();
        if (portStr.isEmpty()) return DEFAULT_PORT;
        try {
            int p = Integer.parseInt(portStr);
            return (p > 0 && p <= 65535) ? p : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /**
     * 统一 base URL（不含 path）—— API 与媒体 URL 都从这里拼。
     *
     * <ul>
     *   <li>IPv4 字面量（假域名模式）→ {@code "https://miyu.lan:<port>"}
     *       （协议强制 https，主机名换成 miyu.lan）</li>
     *   <li>域名 → 原逻辑 {@code httpOf(server) + server}
     *       （公网域名走系统 DNS + 真证书，为上线预留）</li>
     * </ul>
     *
     * <p>返回值**不带尾部斜杠**，调用方直接拼 {@code "/api/..."}。
     */
    public static String baseUrl(String server) {
        if (isIpServer(server)) {
            return "https://" + FAKE_HOST + ":" + portOf(server);
        }
        return httpOf(server) + server;
    }

    /**
     * WebSocket base URL（不含 path / query）。
     *
     * <ul>
     *   <li>IPv4 字面量 → {@code "wss://miyu.lan:<port>"}</li>
     *   <li>域名 → 原逻辑 {@code wsOf(server) + server}</li>
     * </ul>
     */
    public static String wsBaseUrl(String server) {
        if (isIpServer(server)) {
            return "wss://" + FAKE_HOST + ":" + portOf(server);
        }
        return wsOf(server) + server;
    }

    /**
     * 为媒体 URL 追加鉴权 token(?token=)。Glide/ExoPlayer 等媒体加载无法携带
     * Authorization header, 与 WebSocket 的 ?token= 模式保持一致。
     * URL 已有 query 时用 & 追加; 已含 token 或 token 为空则原样返回(幂等)。
     */
    public static String withToken(String url, String token) {
        if (url == null || token == null || token.isEmpty()) return url;
        if (url.contains("token=")) return url;
        return url.contains("?") ? url + "&token=" + token : url + "?token=" + token;
    }

    /**
     * 假域名的 DNS 解析器：把 {@link #FAKE_HOST} 解析成<b>当前</b> server 的 IPv4。
     *
     * <p><b>为什么每次 lookup 都要重新问 server，而不是建 client 时把 IP 固化下来</b>：
     * Glide 的 {@code OkHttpUrlLoader} 在进程内首次加载图片时把当时的 client 注册进
     * Registry，而 {@code registerComponents} 每进程只跑一次 —— {@code refresh()} 能换掉
     * {@code HttpClientProvider} 自己的缓存，却换不掉 Glide（以及任何把 client 存成字段/
     * 快照的调用方）手里那份旧 client。服务器 IP 一变（DHCP 换网段、用户在设置里改地址），
     * 图片/头像就会被一直解析到旧 IP：表现为 API 一切正常、所有图片都加载不出来，
     * 且服务端日志里连一条图片请求都看不到（请求根本没发出去）。
     * 把"取当前地址"推迟到 lookup 这一刻，任何快照都自动跟着新地址走。
     *
     * <p>只接管 {@link #FAKE_HOST}：其余主机名原样交回系统解析；server 不是 IPv4 字面量
     * （公网域名模式）时也交回系统解析 —— 那时自定义解析没有意义。
     */
    public static Dns fakeHostDns(final Supplier<String> server) {
        return new Dns() {
            @Override
            public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                if (hostname == null || !FAKE_HOST.equalsIgnoreCase(hostname)) {
                    return Dns.SYSTEM.lookup(hostname);
                }
                String s = (server != null) ? server.get() : null;
                if (!isIpServer(s)) {
                    return Dns.SYSTEM.lookup(hostname);
                }
                // 直接给出 IP 字面量，绝不回落到 Dns.SYSTEM（见类注释：回落会绕开用户配置的地址）。
                return java.util.Collections.singletonList(InetAddress.getByName(hostOf(s)));
            }
        };
    }
}
