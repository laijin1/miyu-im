package com.familychat.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.familychat.server.admin.AdminAuth;
import com.familychat.server.admin.AdminDao;
import com.familychat.server.config.ServerConfig;
import com.familychat.server.handler.AdminHandler;
import com.familychat.server.handler.AuthHandler;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理后台 HTTP 层集成测试（真起一个 Javalin 实例，端口 0 由系统分配）。
 *
 * <p>锁住的是"结构性隔离"这几条，它们是本轮最核心的安全约束：
 * <ul>
 *   <li>未登录访问 /admin/* → 页面跳登录、接口 401；</li>
 *   <li>App 的 JWT（Authorization: Bearer）对管理接口<b>完全无效</b>；</li>
 *   <li>登录后拿到的是 HttpOnly + SameSite=Strict 的不透明 Cookie，且 Cookie 里不是 JWT；</li>
 *   <li>错误口令连续 5 次后按 IP 锁定。</li>
 * </ul>
 */
class AdminHttpTest extends DbTestSupport {

    private Javalin app;
    private String base;
    private AdminDao dao;

    @BeforeEach
    void startServer() throws Exception {
        FamilyChatServer.setDbForTests(db);
        // 限流是 JVM 级静态状态：不清掉的话，"锁定"用例失败 5 次会连带锁死其它用例
        AdminAuth.resetRateLimiterForTests();
        dao = new AdminDao(db);
        AdminAuth.bootstrapIfEmpty(dao);
        // #2 之后未改密账号只能访问首页/改密/登出；本测试类验证的是其它管理功能，
        // 故夹具里预先清掉标记。必须改密语义由独立探针验证。
        try (java.sql.Connection c = db.getConn();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE admin_users SET must_change_pw=0");
        }
        app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        new AdminHandler().register(app);
        new AuthHandler().register(app);
        // 端口 0 = 让系统分配空闲端口，避免与真实服务/并行测试撞端口
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void stopServer() {
        if (app != null) app.stop();
        AdminAuth.dropInitialPasswordFile();
        FamilyChatServer.setDbForTests(null);
        // 收尾做 WAL checkpoint：否则测试结束会留下 family_chat.db-wal/-shm，
        // 下一个用例的 resetDb 只删主库文件，残留的 -wal 会在 open 时把旧库内容重放回来，
        // 于是"数据库已重置"变成一句空话（现象是随执行顺序飘移的诡异断言失败）。
        if (db != null) db.close();
    }

    @Test
    void unauthenticatedPageRedirectsToLogin() throws Exception {
        // 显式带上浏览器的 Accept（Java HttpClient 默认不发 Accept，会被服务端判成 API 请求）
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> redirect = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/"))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, redirect.statusCode(), "浏览器请求未登录应 302");
        assertEquals("/admin/login", redirect.headers().firstValue("Location").orElse(""),
                "必须跳到登录页");

        HttpResponse<String> loginPage = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginPage.statusCode());
        assertTrue(loginPage.body().contains("管理后台登录"), "登录页应可直接打开");

        // 不带 Accept（等价于 XHR/脚本请求）时必须 401，页面不能被直接吐出来
        HttpResponse<String> apiLike = get("/admin/", null);
        assertEquals(401, apiLike.statusCode(), "非浏览器请求不得拿到页面");
    }

    @Test
    void unauthenticatedApiReturns401() throws Exception {
        HttpResponse<String> r = get("/admin/api/stats", null);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("未登录") || r.body().contains("error"));
    }

    @Test
    void appJwtIsRejectedByAdminApi() throws Exception {
        // 造一个合法的 App JWT（与 App 登录签发的完全同构）
        int uid = register("app_user");
        String jwt = JWT.create()
                .withClaim("user_id", uid)
                .withClaim("tv", db.getTokenVersion(uid))
                .withExpiresAt(new Date(System.currentTimeMillis() + 3600_000))
                .sign(Algorithm.HMAC256(ServerConfig.getJwtSecret()));

        HttpResponse<String> r = getWithAuth("/admin/api/stats", jwt);
        assertEquals(401, r.statusCode(),
                "App 的 JWT 绝不能通过管理端鉴权：管理端只认自己的 Cookie 会话");

        // 对照：同一个 JWT 在 App 接口上能通过鉴权（证明令牌本身有效，
        // 是管理端主动拒绝它，而不是令牌坏了）。用 AuthHandler 自己注册的接口，
        // 空 body 应得到 400（参数问题）而不是 401（鉴权问题）。
        HttpResponse<String> appResp = postWithAuth("/api/user/change-password", jwt, "");
        assertEquals(400, appResp.statusCode(), "该 JWT 在 App 侧应通过鉴权（仅参数不合法）");
    }

    @Test
    void loginSetsHttpOnlyCookieAndGrantsAccess() throws Exception {
        CookieManager jar = new CookieManager();
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(jar)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=" + initialPassword()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), "登录成功应跳转，而不是回显登录页");

        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains(AdminAuth.COOKIE), "必须种下管理会话 Cookie: " + setCookie);
        assertTrue(setCookie.toLowerCase().contains("httponly"), "Cookie 必须 HttpOnly: " + setCookie);
        assertTrue(setCookie.toLowerCase().contains("samesite=strict"), "Cookie 必须 SameSite=Strict: " + setCookie);
        // 令牌不能是 JWT（JWT 形如 xxx.yyy.zzz）；这是与 App 鉴权隔离的关键
        String tokenValue = setCookie.split(";")[0].replace(AdminAuth.COOKIE + "=", "");
        assertFalse(tokenValue.contains("."), "管理会话令牌不能是 JWT: " + tokenValue);

        HttpResponse<String> stats = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/api/stats")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stats.statusCode());
        assertTrue(stats.body().contains("\"stats\""));
    }

    /**
     * 回归：已登录访问 <code>/admin/</code> 必须直接返回页面，不能 302。
     *
     * <p>曾经的 bug：{@code AdminHandler} 同时注册了 {@code "/admin"}（handler 是
     * {@code ctx.redirect("/admin/")}）与 {@code "/admin/"}（handler 是 page）。Javalin 6
     * 默认 {@code ignoreTrailingSlashes=true}，两条路由被编译成同一条正则
     * {@code ^/admin/?$}，而路由解析取<b>首个</b>匹配项 —— 于是 {@code /admin/} 的请求被
     * 前者接管，重定向到它自己，浏览器报 ERR_TOO_MANY_REDIRECTS。
     *
     * <p>这个 bug 能长期存活，正是因为原有用例只覆盖了"未登录"路径
     * （见 {@link #unauthenticatedPageRedirectsToLogin}），已登录路径是测试盲区。
     */
    @Test
    void authenticatedAdminRootReturns200() throws Exception {
        CookieManager jar = new CookieManager();
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(jar)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // 登录用与 loginSetsHttpOnlyCookieAndGrantsAccess 相同的表单提交方式
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=" + initialPassword()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), "前置条件：登录应成功并 302 跳转");

        // 带浏览器的 Accept 访问管理首页（此时 Cookie 由 jar 自动携带）
        HttpResponse<String> root = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/"))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, root.statusCode(),
                "已登录访问 /admin/ 必须 200；拿到 302 说明又在跳回自己（死循环回归）");
        assertTrue(root.headers().firstValue("Location").isEmpty(),
                "200 响应不得带 Location，否则浏览器会继续跳转");
        assertTrue(root.body().contains("密语 管理后台"),
                "响应体应是管理后台页面 shell，而不是登录页或错误页");

        // 附带验证修复依据：删掉 "/admin" 那条路由后，无斜杠的 "/admin" 也必须被
        // "/admin/" 的正则 ^/admin/?$ 接住，而不是变成 404/302。
        HttpResponse<String> noSlash = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin"))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, noSlash.statusCode(),
                "无斜杠的 /admin 也应由同一个 page handler 直接返回 200");
        assertTrue(noSlash.body().contains("密语 管理后台"), "/admin 也应吐管理后台页面");

        // 关键补丁：裸 /admin 必须在【鉴权过滤器】覆盖范围内。
        // "/admin/*" 的正则要求 admin 后必须跟 "/"，所以它【不】匹配裸 /admin；
        // 去掉"重定向到 /admin/"那一跳之后，必须显式再注册一条 before("/admin")，
        // 否则未登录的 /admin 会绕过过滤器直接进 page →
        // ctx.attribute("admin") 为 null → NPE → 500（既不是登录页也不是 401）。
        HttpClient anonymous = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> anonNoSlash = anonymous.send(
                HttpRequest.newBuilder(URI.create(base + "/admin"))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, anonNoSlash.statusCode(),
                "未登录的裸 /admin 必须被过滤器拦下跳登录页；500 说明过滤器没覆盖到它");
        assertEquals("/admin/login", anonNoSlash.headers().firstValue("Location").orElse(""),
                "未登录的裸 /admin 应跳登录页");
    }

    @Test
    void wrongPasswordLocksAfterFiveAttempts() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
        int lastStatus = 0;
        for (int i = 1; i <= 5; i++) {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=wrong"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            lastStatus = r.statusCode();
        }
        assertEquals(401, lastStatus, "第 5 次失败仍应是 401");
        // 第 6 次：已锁定，必须是 429
        HttpResponse<String> locked = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=" + initialPassword()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(429, locked.statusCode(), "锁定后即使密码正确也必须拒绝");
    }

    // ══════════════ 改密 / 改账号名（本轮新增，HTTP 级端到端） ══════════════
    //
    // 每个 @Test 都会重置数据库并重新 bootstrap，所以改掉 admin 的名字/密码
    // 不会污染下一个用例，无需手工还原；SESSIONS 是 JVM 级静态状态，但每个用例
    // 都用全新的 CookieManager，互不影响。

    @Test
    void changePasswordRejectsWrongOldPassword() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"wrong-password\",\"new_password\":\"BrandNew123\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("当前密码不正确"),
                "错误必须指明是旧密码问题，而不是含糊的失败: " + r.body());
        assertEquals(1, dao.countAudit("admin_password_change_failed", ""), "失败必须写审计");
        // 口令必须真的没变：初始密码仍能登录，新密码不能
        assertEquals(302, loginStatus("admin", initialPassword()), "初始密码必须仍然有效");
        assertEquals(401, loginStatus("admin", "BrandNew123"), "失败的改密不得让新密码生效");
    }

    @Test
    void changePasswordRejectsTooShort() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"" + initialPassword() + "\",\"new_password\":\"Ab1\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("至少 8 位"), "应提示长度下限: " + r.body());
    }

    @Test
    void changePasswordRejectsAllDigits() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"" + initialPassword() + "\",\"new_password\":\"12345678\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("纯数字"), "8 位纯数字必须被拒绝: " + r.body());
    }

    @Test
    void changePasswordSucceedsAndDropsInitialPasswordFile() throws Exception {
        String initial = initialPassword();          // 必须先读：改密成功后文件就没了
        assertTrue(java.nio.file.Files.exists(AdminAuth.initialPasswordFile()),
                "前置条件：初始密码提示文件应存在");
        HttpClient client = loginAsAdmin();

        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"" + initial + "\",\"new_password\":\"BrandNew123\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("success"), r.body());

        assertFalse(java.nio.file.Files.exists(AdminAuth.initialPasswordFile()),
                "改密成功后必须删除初始密码明文文件");
        assertEquals(302, loginStatus("admin", "BrandNew123"), "新密码必须可登录");
        assertEquals(401, loginStatus("admin", initial), "初始密码必须立即失效");
        assertEquals(1, dao.countAudit("admin_password_change", ""), "成功必须写审计");
    }

    @Test
    void changeUsernameRejectsDuplicate() throws Exception {
        // 先造一个占用 "taken" 的第二个管理员
        dao.createAdmin("taken", AdminAuth.hashPassword("whatever-123"), false);
        HttpClient client = loginAsAdmin();

        HttpResponse<String> r = postJson(client, "/admin/api/username",
                "{\"new_username\":\"taken\",\"password\":\"" + initialPassword() + "\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("已存在"), "重名必须是明确的「已存在」错误: " + r.body());
        assertNotNull(dao.findByName("admin"), "重名时原账号名必须原封不动");
        assertEquals(1, dao.countAudit("admin_username_change_failed", ""), "重名必须写失败审计");
    }

    @Test
    void changeUsernameRejectsWrongPassword() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/username",
                "{\"new_username\":\"renamed\",\"password\":\"wrong-password\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("当前密码不正确"), r.body());
        assertNotNull(dao.findByName("admin"), "口令错时绝不能改名");
        assertNull(dao.findByName("renamed"), "口令错时不得留下新名");
        assertEquals(1, dao.countAudit("admin_username_change_failed", ""));
    }

    @Test
    void changeUsernameKeepsCurrentSession() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/username",
                "{\"new_username\":\"renamed\",\"password\":\"" + initialPassword() + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("renamed"), r.body());

        // 关键硬约束：改名后当前会话必须继续有效，不能把管理员自己踢下线
        HttpResponse<String> root = getAdmin(client, "/admin/");
        assertEquals(200, root.statusCode(), "改名后当前会话必须仍然有效（不能 302 回登录页）");
        assertTrue(root.body().contains("<span id=\"who\">renamed</span>"),
                "页面头部必须显示新账号名（会话用户名未刷新时这里还是旧的）");

        assertNotNull(dao.findByName("renamed"), "库里应已是新名");
        assertNull(dao.findByName("admin"), "库里不应再有旧名");
    }

    @Test
    void changePasswordWorksAfterRename() throws Exception {
        String initial = initialPassword();
        HttpClient client = loginAsAdmin();
        HttpResponse<String> rename = postJson(client, "/admin/api/username",
                "{\"new_username\":\"renamed\",\"password\":\"" + initial + "\"}");
        assertEquals(200, rename.statusCode(), rename.body());

        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"" + initial + "\",\"new_password\":\"BrandNew123\"}");
        assertEquals(200, r.statusCode(), "改名后改密必须仍然可用: " + r.body());
    }

    /**
     * 解耦回归：改密的旧密码校验必须按<b>会话里的 adminId</b> 查哈希。
     *
     * <p>这里直接用 DAO 改名（绕过 handler），故意让会话里的 username 快照过期 ——
     * 这正是旧实现 {@code dao.findByName(s.username)} 会踩的坑：改名后查不到人，
     * 表现为"密码明明正确却报当前密码不正确"。
     */
    @Test
    void changePasswordSurvivesOutOfBandRename() throws Exception {
        String initial = initialPassword();
        HttpClient client = loginAsAdmin();
        long id = ((Number) dao.findByName("admin").get("id")).longValue();
        assertEquals(AdminDao.RenameResult.OK, dao.updateUsername(id, "outsider"));

        HttpResponse<String> r = postJson(client, "/admin/api/password",
                "{\"old_password\":\"" + initial + "\",\"new_password\":\"BrandNew123\"}");
        assertEquals(200, r.statusCode(),
                "改密必须按 adminId 查哈希；退回 findByName(s.username) 时这里会是 400: " + r.body());
    }

    @Test
    void renameWritesAuditWithOldAndNewName() throws Exception {
        HttpClient client = loginAsAdmin();
        HttpResponse<String> r = postJson(client, "/admin/api/username",
                "{\"new_username\":\"renamed\",\"password\":\"" + initialPassword() + "\"}");
        assertEquals(200, r.statusCode(), r.body());

        java.util.List<java.util.Map<String, Object>> rows =
                dao.listAudit("admin_username_change", "", 1, 10);
        assertEquals(1, rows.size(), "应恰好一条改名成功审计");
        String detail = String.valueOf(rows.get(0).get("detail"));
        assertTrue(detail.contains("admin") && detail.contains("renamed"),
                "审计 detail 必须同时留下旧名与新名: " + detail);
        assertEquals("admin", String.valueOf(rows.get(0).get("admin_name")),
                "admin_name 是改名前的冗余快照 —— 这是设计意图，不回填");
    }

    // ── 助手 ──

    /** 用初始密码登录，返回带着会话 Cookie 的客户端（不跟随跳转）。 */
    private HttpClient loginAsAdmin() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=admin&password=" + initialPassword()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), "前置条件：管理员登录应成功并 302 跳转");
        return client;
    }

    /** 用指定口令走一次登录，返回状态码（供"口令到底改没改"的断言使用）。 */
    private int loginStatus(String user, String pw) throws Exception {
        HttpResponse<String> r = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "username=" + user + "&password=" + pw))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    /** POST 一段 JSON 到管理接口，复用客户端 Cookie。 */
    private HttpResponse<String> postJson(HttpClient client, String path, String json)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 带浏览器 Accept 的 GET（页面请求，命中"跳登录页"而非 401 的分支）。 */
    private HttpResponse<String> getAdmin(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 首次 bootstrap 生成的初始密码（从落盘提示文件读取，避免测试里再猜）。 */
    private String initialPassword() throws Exception {
        String content = java.nio.file.Files.readString(AdminAuth.initialPasswordFile());
        for (String line : content.split("\\R")) {
            if (line.startsWith("password=")) return line.substring("password=".length()).trim();
        }
        throw new IllegalStateException("初始密码文件里没有 password= 行");
    }

    private HttpResponse<String> get(String path, String ignored) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithAuth(String path, String jwt) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + jwt).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithAuth(String path, String jwt, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + jwt)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
