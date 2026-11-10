package com.familychat.server;

import com.familychat.server.config.ServerConfig;
import com.familychat.server.handler.*;
import com.familychat.server.model.Database;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.OptionalSslConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class FamilyChatServer {
    public static final int PORT = Integer.parseInt(
            System.getProperty("server.port", "5000"));
    private static Database db;
    private static Javalin app;

    // Configurable max request size (default 100MB — 发视频/大文件用，手机短视频常超 10MB)
    private static final long MAX_REQUEST_SIZE = Long.parseLong(
            System.getProperty("max.request.size", String.valueOf(100 * 1024 * 1024)));

    // ---- HTTPS / TLS ----
    // 数据目录复用 Database.DATA_DIR（基于 jar 位置解析，与 start-server.bat 从哪个目录
    // 启动无关），keystore 与 admin_initial_password.txt 同目录、同样被 .gitignore 忽略。
    private static final String SSL_KEYSTORE = System.getProperty(
            "ssl.keystore",
            Paths.get(Database.DATA_DIR).resolve("ssl_keystore.p12").toString());
    private static final String SSL_KEYSTORE_PASSWORD_FILE = System.getProperty(
            "ssl.keystore.password.file",
            Paths.get(Database.DATA_DIR).resolve("ssl_keystore_password.txt").toString());

    /**
     * 明文（非 TLS）连接只允许访问的路径集合。
     *
     * <p>端口同时接受 TLS 与明文之后，浏览器用 {@code http://IP:5000/} 直接打开下载页不需要
     * 添加自签证书例外，cloudflared 的明文回源也依赖这条路。但明文信道没有任何机密性，
     * 所以只放行"下载页 + 版本接口"这 5 个本来就是公开的路径；
     * {@code /admin}、{@code /api/*}、WebSocket 等一律必须走 https。
     */
    private static final Set<String> PLAINTEXT_DOWNLOAD_PATHS = Set.of(
            "/", "/index.html", "/download", "/apk", "/version.json");

    /**
     * 非 null 表示本次以 HTTPS 启动；null 表示未检测到可用 keystore，退回 HTTP。
     *
     * <p><b>为什么退回而不是拒绝启动：</b>证书脚本是运维手动执行的一步，本地开发、
     * 跑测试、或者运维忘了生成证书时，若直接 FATAL 就完全没法启动，代价远大于收益。
     * 这里选择"打醒目的 WARN + 照常以明文启动"，让可用性优先，由横幅明确告知当前协议。
     */
    private static SslContextFactory.Server tlsFactory;

    /**
     * 加载 PKCS12 keystore 并构造 Jetty 的 SslContextFactory。
     *
     * <p>成功返回可用的 factory；任何一步失败都返回 {@code null}（调用方据此退回 HTTP），
     * 并且**只打日志、不抛异常**。keystore 缺失属于"还没跑证书脚本"的正常中间状态，
     * 不该让服务端起不来。
     *
     * <p>这里刻意先用 {@link KeyStore} 自己 load 一次做校验，而不是直接把路径交给
     * Jetty：Jetty 的 keystore 是在连接器 start() 时才惰性加载的，一旦口令错/文件损坏，
     * 报错会出现在很靠后的启动阶段、且信息晦涩。提前加载可以把"口令错"这类最常见的
     * 运维错误在启动最初就变成一句能看懂的中文 WARN。
     */
    private static SslContextFactory.Server initTls() {
        Path keystorePath = Paths.get(SSL_KEYSTORE).toAbsolutePath().normalize();

        if (!Files.isRegularFile(keystorePath)) {
            System.out.println("[WARN] TLS keystore not found: " + keystorePath);
            System.out.println("[WARN]   -> running plain HTTP (port " + PORT + ").");
            System.out.println("[WARN]   -> to enable HTTPS, run: pwsh -File scripts/gen-cert.ps1");
            return null;
        }

        Path passwordFile = Paths.get(SSL_KEYSTORE_PASSWORD_FILE).toAbsolutePath().normalize();
        if (!Files.isRegularFile(passwordFile)) {
            System.out.println("[WARN] TLS keystore password file not found: " + passwordFile);
            System.out.println("[WARN]   -> running plain HTTP (port " + PORT + ").");
            System.out.println("[WARN]   -> re-run: pwsh -File scripts/gen-cert.ps1");
            return null;
        }

        String password;
        try {
            // trim()：脚本用 Set-Content -NoNewline 写，但仍可能混入 CRLF / BOM（有人手动编辑过）
            password = Files.readString(passwordFile).trim();
        } catch (IOException e) {
            System.out.println("[WARN] Failed to read TLS keystore password file: " + e.getMessage());
            System.out.println("[WARN]   -> running plain HTTP (port " + PORT + ").");
            return null;
        }
        if (password.isEmpty()) {
            System.out.println("[WARN] TLS keystore password file is empty: " + passwordFile);
            System.out.println("[WARN]   -> running plain HTTP (port " + PORT + ").");
            return null;
        }

        try {
            // 提前加载一次：口令错 / 文件损坏会在这里暴露，而不是拖到连接器 start()。
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(keystorePath)) {
                keyStore.load(in, password.toCharArray());
            }

            SslContextFactory.Server factory = new SslContextFactory.Server();
            factory.setKeyStorePath(keystorePath.toString());
            factory.setKeyStoreType("PKCS12");
            factory.setKeyStorePassword(password);
            System.out.println("[OK] TLS enabled: keystore=" + keystorePath);
            return factory;
        } catch (Exception e) {
            System.out.println("[WARN] Failed to load TLS keystore: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            System.out.println("[WARN]   -> running plain HTTP (port " + PORT + ").");
            System.out.println("[WARN]   -> if the password file was hand-edited, re-run:");
            System.out.println("[WARN]      pwsh -File scripts/gen-cert.ps1 -Force");
            return null;
        }
    }

    public static Database getDb() {
        return db;
    }

    /**
     * 仅供测试：注入 Database 实例。
     *
     * <p>生产路径上 {@code db} 由 main() 赋值；测试里需要在不启动完整服务的前提下
     * 构造依赖 {@code getDb()} 的 handler（如 AdminHandler）。包私有，避免被外部误用。
     */
    static void setDbForTests(Database testDb) {
        db = testDb;
    }

    public static void main(String[] args) {
        // 必须在 new Database()/db.init() 之前：db.init() 会创建 family_chat.db，
        // 而 ServerConfig 用「数据库是否已存在」判断是否首次启动；先建库会让全新
        // 安装被误判成"密钥丢失的老库"而拒绝启动。校验失败时这里直接终止进程。
        ServerConfig.validateAtStartup();

        db = new Database();
        db.init();

        // 数据库自动备份（VACUUM INTO + 定时 + 保留 14 份）。
        // 放在 init 之后：表结构建好才是值得留存的快照；启动即备份一次，把
        // "进程重启到第一次定时点之间" 这段无备份窗口压到 0。
        // 备份失败只打日志，不影响启动（见 BackupService 的失败语义）。
        com.familychat.server.model.BackupService.start();

        // 管理后台初始化：首次启动（admin_users 为空）生成随机初始密码并打印到控制台，
        // 同时落一份 data/admin_initial_password.txt（已 gitignore）供运维复查；
        // 管理员改密成功后该文件会被删除。详见 AdminAuth.bootstrapIfEmpty 的注释。
        com.familychat.server.admin.AdminAuth.bootstrapIfEmpty(
                new com.familychat.server.admin.AdminDao(db));

        // 尝试加载 TLS 证书。失败一律只 WARN + 退回 HTTP，绝不阻塞启动（见 tlsFactory 注释）。
        tlsFactory = initTls();

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = MAX_REQUEST_SIZE;
            // 关闭 gzip/brotli 压缩：压缩会把响应体变小，但 Javalin 不会同步改掉我们手动
            // 设置的 Content-Length（下载进度需要它），导致客户端按未压缩长度去读、最后报
            // "unexpected end of stream"。且 APK/视频本身是压缩过的，再压缩毫无收益。
            config.pvt.compressionStrategy = io.javalin.compression.CompressionStrategy.NONE;
            // Don't kill idle WebSocket connections after Jetty's default 5 minutes —
            // Android clients ping every 20s, but a slow/temporarily-suspended client
            // shouldn't lose its connection (which made incoming calls intermittently fail).
            config.jetty.modifyWebSocketServletFactory(factory ->
                    factory.setIdleTimeout(java.time.Duration.ofMinutes(60)));
            // HTTPS：Javalin 6.3.0 的 JettyConfig 没有 sslContext 入口，也没有
            // start(sslConfig, port) 重载，必须走 Jetty 层的 addConnector。
            // 注意 Javalin 的启动逻辑：connectors 列表为空时才创建默认明文连接器，
            // 非空则只 addConnector 列表内的连接器 —— 所以这里注册了 SSL 连接器，
            // 就不会再另外监听一个明文端口，端口仍由 app.start(PORT) 的 PORT 决定。
            if (tlsFactory != null) {
                config.jetty.addConnector((server, httpConfig) -> {
                    httpConfig.setSecureScheme("https");
                    httpConfig.setSecurePort(PORT);
                    // SNI 校验保持默认开启：客户端用 miyu.lan 连接时证书必须匹配该名字，
                    // 这正是我们想要的（也避免自签证书被泛化滥用）。
                    httpConfig.addCustomizer(new SecureRequestCustomizer());
                    // 同一端口同时接受 TLS 与明文：OptionalSslConnectionFactory 嗅探首字节
                    // （b0∈{0x16,0x15} 且 b1==0x03 → TLS，否则按明文处理），TLS 走 sslCf，
                    // 明文按协议名 "http/1.1" 升级到 httpCf。
                    // ⚠️ 顺序不可颠倒：连接器的 _defaultProtocol 取"第一个被加入的工厂"
                    //    （AbstractConnector.addConnectionFactory 末尾），只有 optSsl 排第 1
                    //    才会成为默认入口并触发嗅探；httpCf 排第 2 是为了让 optSsl 的
                    //    nextProtocol 能按 "http/1.1" 查到它，把明文连接升级过去。
                    // ⚠️ nextProtocol 必须非 null：传 null 会掉进 otherProtocol() 的
                    //    "HTTP request to HTTPS port" 400 分支，明文照样打不开。
                    SslConnectionFactory sslCf = new SslConnectionFactory(tlsFactory, "http/1.1");
                    OptionalSslConnectionFactory optSsl = new OptionalSslConnectionFactory(sslCf, "http/1.1");
                    HttpConnectionFactory httpCf = new HttpConnectionFactory(httpConfig);
                    ServerConnector connector = new ServerConnector(server, optSsl, httpCf);
                    connector.setPort(PORT);
                    return connector;
                });
            }
        });

        // ---- 明文访问闸门（必须最先注册：before 按注册顺序执行）----
        // 端口 5000 现在同时接受 TLS 与明文。这条闸门把明文信道收窄到"下载页 + 本机/隧道"：
        //   A. TLS 请求 → 放行
        //   B. 回环地址 → 放行（cloudflared 以 http://localhost:5000 回源；本机调试同理）
        //   C. 路径在白名单内（/ /index.html /download /apk /version.json）→ 放行
        //   其余明文一律 403，避免 /admin、/api/* 暴露在无加密信道上。
        // ⚠️ 判据只能用 isSecure()+remoteAddr()。绝不能用 X-Forwarded-Proto / CF-Connecting-IP：
        //    这些头客户端可伪造，项目内已有约定（见 AdminHandler.ip 的注释）只用于审计展示，
        //    不得作为鉴权/放行依据。
        installPlaintextGate(app);

        // Add CORS headers with dynamic origin validation
        app.before(ctx -> {
            String origin = ctx.req().getHeader("Origin");
            if (origin == null || origin.isEmpty()) {
                // Same-origin request (browser doesn't send Origin for same-origin GET/POST)
                ctx.header("Access-Control-Allow-Origin", "*");
            } else {
                // Validate origin against trusted list
                String host = extractHost(origin);
                if (host != null && isTrustedOrigin(host)) {
                    ctx.header("Access-Control-Allow-Origin", origin);
                } else {
                    // Reject untrusted origins — browser will block the response
                    ctx.header("Access-Control-Allow-Origin", "null");
                }
            }
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });

        // Register API routes
        // 管理后台必须最先注册： AdminHandler.register 里挂的 before 鉴权过滤器按注册顺序
        // 执行，先于下面的 CORS before —— 未登录的 /admin/* 到不了任何业务代码。
        AdminHandler adminHandler = new AdminHandler();
        adminHandler.register(app);

        new AuthHandler().register(app);
        new ProfileHandler().register(app);
        new FriendHandler().register(app);
        new GroupHandler().register(app);
        new GroupContentHandler().register(app);   // 群文件库 + 群相册（阶段4）
        new GroupPollHandler().register(app);      // 群投票（阶段5）
        new MessageHandler().register(app);
        new WalletHandler().register(app);       // 支付系统（钱包/转账/红包/收付款码/账单）
        new MallHandler().register(app);         // 商城（商品浏览，阶段1）
        new FileHandler().register(app);
        new WsHandler().register(app);
        new PublicKeyHandler().register(app);
        new KeyBackupHandler().register(app);
        new AiHandler().register(app);

        // Ensure all JSON responses explicitly declare UTF-8 charset
        // (ctx.json() defaults to "application/json" without charset)
        app.after(ctx -> {
            String ct = ctx.res().getContentType();
            if (ct != null && ct.startsWith("application/json") && !ct.contains("charset")) {
                ctx.res().setContentType(ct + "; charset=UTF-8");
            }
            // 访问日志：记录每个请求的方法/路径/状态码/账号（排查客户端加载失败）
            // 注意：query string 里的 ?token=<JWT> 必须脱敏，否则身份凭证明文落盘
            String qs = ctx.queryString();
            if (qs != null && qs.contains("token=")) {
                qs = qs.replaceAll("(?i)(token=)[^&]*", "$1***");
            }
            int uid = extractUidFromToken(ctx.req().getHeader("Authorization"));
            System.out.println("[HTTP] " + ctx.method() + " " + ctx.path()
                    + (qs != null && !qs.isEmpty() ? "?" + qs : "")
                    + " -> " + ctx.status() + " (uid=" + uid + ")");
        });

        // 网页聊天客户端已移除，不再静态伺服 client/ 目录
        // APK 下载页 /download 与版本接口 /version.json 由 FileHandler 提供

        String ip = getLocalIp();
        // 横幅里印的每个地址都必须"照着点就能打开"。这里**不能**再像以前那样统一套一个
        // scheme：TLS 打开后 `https://<LAN-IP>` / `https://127.0.0.1` 全是死链。
        //   * https 只认证书 SAN 里的 miyu.lan：SecureRequestCustomizer 默认开启 SNI/主机名
        //     校验，客户端用 localhost / 127.0.0.1 / 局域网 IP 走 https，Jetty 直接回
        //     400 Invalid SNI（gen-cert.ps1 的 SAN 只签了 DNS:miyu.lan，2026-10-02 实测）。
        //   * 明文信道被 installPlaintextGate 收窄：回环地址全放行，其它来源只有下载页
        //     白名单（/ /index.html /download /apk /version.json）放行，其余 403。
        // 因此真正能打开的只有三种：本机明文、局域网下载页明文、App 的假域名 https。
        System.out.println("===============================");
        System.out.println("  Family Chat Server (Java)");
        System.out.println("===============================");
        System.out.println("  ADMIN: http://127.0.0.1:" + PORT + "/admin/login");
        System.out.println("  Local: http://127.0.0.1:" + PORT + "/");
        System.out.println("  APK:   http://" + ip + ":" + PORT + "/download   (手机装包，免证书)");
        if (tlsFactory != null) {
            System.out.println("  App:   https://miyu.lan:" + PORT
                    + "   (App 假域名模式；https 用 IP 会被 SNI 校验挡成 400)");
        } else {
            System.out.println("  App:   http://" + ip + ":" + PORT);
        }
        System.out.println("===============================");
        if (tlsFactory != null) {
            System.out.println("  TLS: enabled (self-signed) — 客户端需信任 data/ca.crt");
        } else {
            System.out.println("  TLS: NOT enabled — running plain HTTP");
        }
        System.out.println("===============================");
        System.out.println("  Max request size: " + (MAX_REQUEST_SIZE / (1024 * 1024)) + "MB");
        System.out.println("===============================");

        // 订单超时定时任务：每 60s 扫描一次
        //  - 待付款超过 30 分钟 → 自动取消并恢复库存（通知买/卖家）
        //  - 已发货超过 7 天未确认 → 自动确认收货（通知双方）
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "mall-order-scheduler");
                    t.setDaemon(true);
                    return t;
                });
        final long ORDER_PAY_TIMEOUT_MS = 30L * 60 * 1000;   // 30 分钟未付款自动取消
        final long ORDER_AUTO_CONFIRM_MS = 7L * 24 * 3600 * 1000; // 7 天自动确认收货
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // 超时未付款 → 自动取消
                java.util.List<Map<String, Object>> cancelled =
                        db.cancelExpiredOrders(ORDER_PAY_TIMEOUT_MS);
                for (Map<String, Object> evt : cancelled) {
                    int orderId = ((Number) evt.get("order_id")).intValue();
                    int buyerId = ((Number) evt.get("buyer_id")).intValue();
                    int sellerId = ((Number) evt.get("seller_id")).intValue();
                    System.out.println("[MALL] auto-cancel expired order " + orderId);
                    WsHandler.sendToUser(buyerId, Map.of(
                            "type", "mall_order_timeout", "order_id", orderId));
                    if (sellerId > 0 && sellerId != buyerId) {
                        WsHandler.sendToUser(sellerId, Map.of(
                                "type", "mall_order_cancelled", "order_id", orderId));
                    }
                }
                // 发货超 7 天未确认 → 自动确认收货
                java.util.List<Map<String, Object>> completed =
                        db.autoCompleteOrders(ORDER_AUTO_CONFIRM_MS);
                for (Map<String, Object> evt : completed) {
                    int orderId = ((Number) evt.get("order_id")).intValue();
                    int buyerId = ((Number) evt.get("buyer_id")).intValue();
                    int sellerId = ((Number) evt.get("seller_id")).intValue();
                    System.out.println("[MALL] auto-complete order " + orderId);
                    WsHandler.sendToUser(buyerId, Map.of(
                            "type", "mall_order_completed", "order_id", orderId));
                    if (sellerId > 0 && sellerId != buyerId) {
                        WsHandler.sendToUser(sellerId, Map.of(
                                "type", "mall_order_completed", "order_id", orderId));
                    }
                }
            } catch (Exception e) {
                System.err.println("[MALL] scheduler error: " + e.getMessage());
            }
        }, 30, 60, java.util.concurrent.TimeUnit.SECONDS);

        // 文件/缓存维护定时任务：复用上面同一个调度线程，不新起线程池。
        //  - chat_files：输出 TTL / 容量报告（只报告不删除，见 FileHandler.pruneChatFiles）
        //  - video_cache：按总大小 + 文件数双条件回收（可再生产物，允许真删）
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                FileHandler.pruneChatFiles();
                FileHandler.pruneVideoCacheNow();
            } catch (Exception e) {
                System.err.println("[CACHE] scheduler error: " + e.getMessage());
            }
        }, 45, 60, java.util.concurrent.TimeUnit.SECONDS);

        // 孤儿数据巡检（审计第 5 条）：每 6 小时只读扫描一次并打日志。
        // 关键决策：定期任务【只报告不删除】。
        //   理由与 chat_files 的 TTL 策略一致 —— 服务端无法 100% 确信某个附件真的无人使用
        //   （E2EE 密文没法反查内容），自动删除一旦误判就是用户数据永久丢失，
        //   而且发生在无人值守的时刻。真正的删除留给管理后台/运维工具，由人确认。
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                java.util.Map<String, Object> rep = db.sweepOrphans(true, 20);
                Object total = rep.get("dbOrphanTotal");
                Object files = rep.get("unreferencedFileCount");
                Object bytes = rep.get("unreferencedBytes");
                System.out.println("[ORPHAN] 巡检（只读）dbOrphans=" + total
                        + " unreferencedFiles=" + files + " bytes=" + bytes);
                if (total instanceof Number n && n.intValue() > 0) {
                    System.out.println("[ORPHAN] 明细: " + rep.get("dbOrphanCounts"));
                    System.out.println("[ORPHAN] 如需清理：管理后台「数据卫生」页，或 "
                            + "gradle dbadmin -Pcmd=\"orphan-cleanup --apply\"");
                }
            } catch (Exception e) {
                System.err.println("[ORPHAN] scheduler error: " + e.getMessage());
            }
        }, 120, 6 * 3600, java.util.concurrent.TimeUnit.SECONDS);

        // Register JVM shutdown hook for graceful database cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[OK] Shutting down gracefully...");
            try {
                scheduler.shutdownNow();
            } catch (Exception ignored) {
            }
            try {
                if (app != null) {
                    app.stop();
                    System.out.println("[OK] HTTP server stopped");
                }
            } catch (Exception e) {
                System.err.println("[WARN] Server stop error: " + e.getMessage());
            }
            if (db != null) {
                db.close();  // Ensure WAL checkpoint before exit
            }
            System.out.println("[OK] Shutdown complete");
        }));

        app.start(PORT);
    }

    /**
     * 明文访问闸门（H1 修复）。必须在所有其它 before 之前注册。
     *
     * <p>package-private（而非 private）是为了让同包的 {@code PlaintextGateTest}
     * 能直接注册这条闸门做真 HTTP 验证；生产调用点只有 {@code main} 一处。
     */
    static void installPlaintextGate(Javalin app) {
        app.before(ctx -> {
            if (ctx.req().isSecure()) return;      // TLS 分支：真机验证覆盖
            String remoteAddr = ctx.req().getRemoteAddr();
            if (isLoopbackAddress(remoteAddr)) {
                return;                             // 回环例外（cloudflared + 本机）
            }
            if (PLAINTEXT_DOWNLOAD_PATHS.contains(ctx.path())) return;
            System.out.println("[GATE] plaintext blocked: " + ctx.method()
                    + " " + ctx.path() + " from " + remoteAddr + " -> 403");
            ctx.status(403).result("HTTPS required");
            ctx.skipRemainingHandlers();            // H1 修复：真短路，端点 handler 不执行
        });
    }

    /**
     * 判断来源地址是否为回环地址（IPv4 127.0.0.0/8 或 IPv6 ::1）。
     *
     * <p><b>为什么不能只做字符串比较：</b>Jetty 在 IPv6 连接上返回的
     * {@code getRemoteAddr()} 可能带方括号 —— 实测 cloudflared 回源
     * {@code localhost} 走 IPv6 时为 {@code [0:0:0:0:0:0:0:1]}，旧实现只比较
     * 不带括号的 {@code 0:0:0:0:0:0:0:1}，于是隧道下的 {@code /admin} 被明文
     * 闸门误判成 403。这里先剥掉方括号，再交给 {@link InetAddress} 做语义判断，
     * IPv4/IPv6 以及多种等价写法都能覆盖。
     */
    private static boolean isLoopbackAddress(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) return false;
        String addr = remoteAddr;
        if (addr.length() > 2 && addr.startsWith("[") && addr.endsWith("]")) {
            addr = addr.substring(1, addr.length() - 1);
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            // remoteAddr 理论上一定是 IP 字面量；解析失败时退回 IPv4 回环的保守判断
            return addr.startsWith("127.");
        }
    }

    /**
     * Extract hostname from an Origin header value.
     * Returns null if the origin is malformed.
     */
    private static String extractHost(String origin) {
        try {
            URI uri = new URI(origin);
            String host = uri.getHost();
            return (host != null) ? host.toLowerCase() : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Trusted origin patterns for CORS validation. */
    private static final List<Pattern> TRUSTED_ORIGINS = Arrays.asList(
        // Local development
        Pattern.compile("^localhost$"),
        Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
        // Private IPv4 ranges
        Pattern.compile("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
        Pattern.compile("^172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3}$"),
        Pattern.compile("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$"),
        // Cloudflare Tunnel temporary domains
        Pattern.compile("^.*\\.trycloudflare\\.com$")
        // Add your custom domain here if needed
    );

    /** Check if a hostname is in the trusted CORS whitelist. */
    private static boolean isTrustedOrigin(String host) {
        if (host == null) return false;
        return TRUSTED_ORIGINS.stream().anyMatch(p -> p.matcher(host).matches());
    }

    /**
     * Detect the local LAN IP address.
     */
    /** 从 Authorization: Bearer <jwt> 里解码出 user_id（失败返回 -1）。仅用于访问日志。 */
    private static int extractUidFromToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) return -1;
            String token = authHeader.substring(7).trim();
            String[] parts = token.split("\\.");
            if (parts.length < 2) return -1;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = Pattern.compile("\"user_id\"\\s*:\\s*(\\d+)").matcher(payload);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String getLocalIp() {
        String dnsHost = System.getProperty("lan.detect.host", "8.8.8.8");
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(java.net.InetAddress.getByName(dnsHost), 9);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.")) {
                return ip;
            }
        } catch (Exception e) {
            System.err.println("[WARN] UDP IP detection failed: " + e.getMessage());
        }

        try {
            java.util.List<String> skipNames = java.util.List.of(
                    "vethernet", "virtual", "vmware", "docker", "hyper-v",
                    "bluetooth", "pseudo", "teredo", "loopback",
                    "radmin", "vpn", "tap", "tun", "zerotier"
            );

            Optional<String> ip = java.net.NetworkInterface.networkInterfaces()
                    .filter(iface -> {
                        try { return !iface.isLoopback() && iface.isUp(); }
                        catch (Exception e) { return false; }
                    })
                    .filter(iface -> {
                        String name = iface.getName().toLowerCase();
                        String display = iface.getDisplayName().toLowerCase();
                        return skipNames.stream().noneMatch(
                                s -> name.contains(s) || display.contains(s));
                    })
                    .flatMap(java.net.NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof java.net.Inet4Address)
                    .map(java.net.InetAddress::getHostAddress)
                    .filter(addr -> !addr.startsWith("127."))
                    .findFirst();

            if (ip.isPresent()) return ip.get();
        } catch (Exception e) {
            System.err.println("[WARN] Network interface scan failed: " + e.getMessage());
        }

        return "127.0.0.1";
    }

    public static void stop() {
        if (app != null) app.stop();
    }
}
