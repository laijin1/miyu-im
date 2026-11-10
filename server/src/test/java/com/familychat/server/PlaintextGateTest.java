package com.familychat.server;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * H1 明文访问闸门（{@link FamilyChatServer#installPlaintextGate}）的真 HTTP 集成测试。
 *
 * <p><b>锁住的性质：</b>明文（非 TLS、非回环）请求打到非白名单路径时，不只是"回一个 403"，
 * 而是必须让<b>端点 handler 完全不执行</b>。修复前的写法在 before 里 {@code ctx.status(403)}
 * 后直接 return —— Javalin 6 的 before 过滤器没有"返回即停止"语义（只有 {@code
 * skipRemainingHandlers()} / 抛异常才会打断任务链），于是响应状态是 403、业务副作用却照样发生
 * （与 {@code AdminHandler} 注释里记录的 AdminFilterProbeTest 现象同源）。
 *
 * <p><b>为什么用探针 handler 而不是真实业务 handler：</b>本用例要锁的是"闸门是否掐断了端点任务"，
 * 探针计数（0 / &gt;0）就是直接证据。装 {@code AuthHandler}/{@code FileHandler} 会把
 * {@code DATA_DIR} 目录布局（{@code build/test-data} 下并没有 {@code download.html}、
 * {@code release/密语.apk}）拖进来，引入与闸门无关的失败。
 *
 * <p><b>为什么要用非回环 IPv4 发起请求：</b>闸门对 {@code 127.*} / {@code ::1} 无条件放行
 * （cloudflared 以 {@code http://localhost:5000} 回源）。若测试连 {@code 127.0.0.1}，
 * {@code getRemoteAddr()} 就是回环地址，403 分支<b>永远走不到</b>，测试会假绿。
 *
 * <p><b>TLS 分支未覆盖：</b>引入 keytool + trust-all {@code SSLContext} 属新机制，超出本批范围；
 * 该分支由真机验证覆盖。
 *
 * <p><b>不引入业务依赖：</b>本测试只调用 {@code installPlaintextGate}（package-private，
 * 便于同包测试直接调用）并挂探针，不碰 {@code Database} / {@code AuthHandler} /
 * {@code FileHandler}（因此不继承 {@code DbTestSupport}）。Javalin 起停与 HTTP 调用写法参考
 * {@code AdminHttpTest}（随机端口 + JDK 自带 HttpClient）。
 */
class PlaintextGateTest {

    // ── 探针计数器：每个受测路径一个，@BeforeEach 清零 ──
    private static final AtomicInteger HIT_LOGIN = new AtomicInteger();
    private static final AtomicInteger HIT_SEND = new AtomicInteger();
    private static final AtomicInteger HIT_ADMIN_LOGIN = new AtomicInteger();
    private static final AtomicInteger HIT_ROOT = new AtomicInteger();
    private static final AtomicInteger HIT_APK = new AtomicInteger();
    private static final AtomicInteger HIT_VERSION = new AtomicInteger();

    /** 探针响应体前缀：放行时用它证明"确实是探针在回答"，而不是别的什么占了这条路由。 */
    private static final String PROBE = "PROBE ";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 虚拟/隧道网卡的名字特征。这些网卡上的 IPv4（Radmin VPN 的 26.x、Hyper-V 的 172.17.x、
     * TAP 的 10.x 等）语义上是"隧道/虚拟网络"，不是本机 LAN 地址，按裁定排除。
     * 参考 {@code FamilyChatServer.getLocalIp()} 的同类过滤思路。
     */
    private static final List<String> VIRTUAL_HINTS = List.of(
            "virtual", "vethernet", "hyper-v", "vmware", "vbox", "virtualbox",
            "docker", "wsl", "tap", "tun", "vpn", "radmin", "zerotier",
            "bluetooth", "loopback", "teredo", "pseudo", "wi-fi direct");

    /**
     * 必须直连的 HttpClient。
     *
     * <p>若请求走了系统代理，服务端看到的 {@code remoteAddr} 会是代理地址而不是本机地址 ——
     * 非回环与回环两组用例的语义就同时被破坏了（而且可能"看起来是绿的"）。显式 NO_PROXY 消除该变量。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException e) {
                    // NO_PROXY 下不会触发
                }
            })
            .build();

    /** 进程内缓存的非回环 IPv4（与端口无关，故可跨用例复用）。 */
    private static volatile String nonLoopbackIpv4;

    private Javalin app;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        resetCounters();

        app = Javalin.create(cfg -> cfg.showJavalinBanner = false);

        // 被测对象：必须最先注册（与 main 里的注册顺序一致）
        FamilyChatServer.installPlaintextGate(app);

        // 探针 handler：每个受测路径一个，只做「记一笔 + 回一个可辨识 body」
        app.post("/api/login", probe(HIT_LOGIN, "/api/login"));
        app.post("/api/messages/send", probe(HIT_SEND, "/api/messages/send"));
        app.post("/admin/login", probe(HIT_ADMIN_LOGIN, "/admin/login"));
        app.get("/", probe(HIT_ROOT, "/"));
        app.get("/apk", probe(HIT_APK, "/apk"));
        app.get("/version.json", probe(HIT_VERSION, "/version.json"));

        // 0 = 系统分配空闲端口，避免与真实服务/并行测试撞端口；默认 host 是 0.0.0.0
        app.start(0);
        port = app.port();

        resolveNonLoopbackIpv4();
    }

    @AfterEach
    void stopServer() {
        if (app != null) app.stop();
    }

    // ═══════════════════════ 用例 1-3：非回环明文 → 403 且端点未执行 ═══════════════════════

    @Test
    void plaintextLoginIsBlockedAndHandlerDoesNotRun() throws Exception {
        HttpResponse<String> r = post(lan(), "/api/login");

        assertEquals(403, r.statusCode(), "非回环明文访问 /api/login 必须 403，实际: " + r.body());
        assertEquals("HTTPS required", r.body(),
                "响应体必须是闸门原文；若多了别的内容，说明有别的 handler 参与了响应");
        assertEquals(0, HIT_LOGIN.get(),
                "闸门必须掐断端点任务：计数 > 0 说明 before 只写了 403 却没短路（回到 H1）");
    }

    @Test
    void plaintextMessageSendIsBlockedAndHandlerDoesNotRun() throws Exception {
        HttpResponse<String> r = post(lan(), "/api/messages/send");

        assertEquals(403, r.statusCode(), "非回环明文访问 /api/messages/send 必须 403，实际: " + r.body());
        assertEquals("HTTPS required", r.body(), "响应体必须是闸门原文");
        assertEquals(0, HIT_SEND.get(), "闸门必须掐断端点任务（消息发送副作用不得发生）");
    }

    @Test
    void plaintextAdminLoginIsBlockedAndHandlerDoesNotRun() throws Exception {
        HttpResponse<String> r = post(lan(), "/admin/login");

        assertEquals(403, r.statusCode(), "非回环明文访问 /admin/login 必须 403，实际: " + r.body());
        assertEquals("HTTPS required", r.body(), "响应体必须是闸门原文");
        assertEquals(0, HIT_ADMIN_LOGIN.get(), "闸门必须掐断端点任务（管理端登录不得在明文信道发生）");
    }

    // ═══════════════════════ 用例 4-6：白名单路径 → 放行 ═══════════════════════

    @Test
    void plaintextDownloadRootIsAllowed() throws Exception {
        HttpResponse<String> r = get(lan(), "/");

        assertNotEquals(403, r.statusCode(), "白名单 '/' 不得被闸门拦下");
        assertNotEquals("HTTPS required", r.body(), "放行路径的 body 不该是闸门的 403 文案");
        assertTrue(r.body().startsWith(PROBE), "应由探针 handler 回答，实际 body: " + r.body());
        assertTrue(HIT_ROOT.get() > 0, "放行路径的端点 handler 必须真的执行");
    }

    @Test
    void plaintextApkIsAllowed() throws Exception {
        HttpResponse<String> r = get(lan(), "/apk");

        assertNotEquals(403, r.statusCode(), "白名单 '/apk' 不得被闸门拦下");
        assertTrue(r.body().startsWith(PROBE), "应由探针 handler 回答，实际 body: " + r.body());
        assertTrue(HIT_APK.get() > 0, "放行路径的端点 handler 必须真的执行");
    }

    @Test
    void plaintextVersionJsonIsAllowed() throws Exception {
        HttpResponse<String> r = get(lan(), "/version.json");

        assertEquals(200, r.statusCode(), "白名单 '/version.json' 应正常 200，实际: " + r.body());
        assertTrue(r.body().startsWith(PROBE), "应由探针 handler 回答，实际 body: " + r.body());
        assertTrue(HIT_VERSION.get() > 0, "放行路径的端点 handler 必须真的执行");
    }

    // ═══════════════════════ 用例 7：回环例外 → 放行 ═══════════════════════

    @Test
    void loopbackPlaintextLoginIsAllowed() throws Exception {
        String base = "http://127.0.0.1:" + port;
        HttpResponse<String> r = post(base, "/api/login");

        assertNotEquals(403, r.statusCode(),
                "回环例外（cloudflared 回源 / 本机调试）必须放行，实际: " + r.body());
        assertTrue(r.body().startsWith(PROBE), "应由探针 handler 回答，实际 body: " + r.body());
        assertTrue(HIT_LOGIN.get() > 0, "回环请求的端点 handler 必须真的执行");
    }

    // ═══════════════════════ 非回环 IPv4 发现 ═══════════════════════

    /** 非回环用例的目标 base URL。 */
    private String lan() {
        String ip = nonLoopbackIpv4;
        if (ip == null) {
            fail("本机无非回环 IPv4，无法构造「非回环明文被拦」用例");
        }
        return "http://" + ip + ":" + port;
    }

    /**
     * 枚举出本机的非回环、非虚拟 IPv4，并逐个用真实 HTTP 请求验证可达性后选定。
     *
     * <p>为什么要做可达性验证：仅凭网卡枚举可能选中一个"Up 但实际不可达"的地址，
     * 那样失败信息会变成莫名其妙的连接超时，掩盖真正原因。取不到/全部不可达时显式 fail，
     * <b>绝不 skip</b> —— 静默跳过会让 H1 在 CI 上重新变成无人看守的盲区。
     */
    private void resolveNonLoopbackIpv4() {
        if (nonLoopbackIpv4 != null) return;

        List<String> candidates = candidateIpv4();
        if (candidates.isEmpty()) {
            fail("本机无非回环 IPv4（已排除回环/未启用/169.254/虚拟网卡）："
                    + "闸门的 403 分支需要非回环源地址，本机无法构造该用例。"
                    + " 网卡明细: " + describeInterfaces());
        }

        List<String> tried = new ArrayList<>();
        for (String ip : candidates) {
            tried.add(ip);
            try {
                HttpResponse<String> r = CLIENT.send(
                        HttpRequest.newBuilder(URI.create("http://" + ip + ":" + port + "/version.json"))
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() > 0) {
                    nonLoopbackIpv4 = ip;
                    // 可达性探测本身打到了 /version.json 探针，清了再让用例从 0 开始
                    resetCounters();
                    return;
                }
            } catch (Exception ignored) {
                // 该地址不可达，继续试下一个
            }
        }
        fail("枚举到非回环 IPv4 但全部不可达: " + tried + " 网卡明细: " + describeInterfaces());
    }

    private static List<String> candidateIpv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            if (nifs == null) return out;
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                } catch (SocketException e) {
                    continue;
                }
                if (isVirtual(nif)) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) continue;
                    out.add(a.getHostAddress());
                }
            }
        } catch (SocketException e) {
            // 枚举失败 → 返回空列表，由调用方 fail 并打网卡明细
        }
        return out;
    }

    private static boolean isVirtual(NetworkInterface nif) {
        String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.ROOT);
        String desc = nif.getDisplayName() == null ? "" : nif.getDisplayName().toLowerCase(Locale.ROOT);
        for (String hint : VIRTUAL_HINTS) {
            if (name.contains(hint) || desc.contains(hint)) return true;
        }
        return false;
    }

    /** 失败时给出可读的网卡明细，避免"取不到 IP"变成无法排查的黑盒。 */
    private static String describeInterfaces() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                sb.append('[').append(nif.getName()).append(" up=").append(nif.isUp())
                        .append(" loopback=").append(nif.isLoopback())
                        .append(" virtual=").append(isVirtual(nif)).append(" -> ");
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    sb.append(ia.getAddress().getHostAddress()).append(' ');
                }
                sb.append("] ");
            }
        } catch (SocketException e) {
            sb.append("(枚举失败: ").append(e.getMessage()).append(')');
        }
        return sb.toString();
    }

    // ═══════════════════════ 助手 ═══════════════════════

    /** 造一个探针 handler：先计数，再回一个可辨识的 body。 */
    private static Handler probe(AtomicInteger counter, String label) {
        return ctx -> {
            counter.incrementAndGet();
            ctx.result(PROBE + label);
        };
    }

    private static void resetCounters() {
        HIT_LOGIN.set(0);
        HIT_SEND.set(0);
        HIT_ADMIN_LOGIN.set(0);
        HIT_ROOT.set(0);
        HIT_APK.set(0);
        HIT_VERSION.set(0);
    }

    private static HttpResponse<String> get(String base, String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String base, String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
