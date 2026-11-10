package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.admin.AdminAudit;
import com.familychat.server.admin.AdminAuth;
import com.familychat.server.admin.AdminDao;
import com.familychat.server.admin.AdminPages;
import com.familychat.server.admin.AdminStats;
import com.familychat.server.model.Database;
import com.familychat.server.model.dao.WalletDao;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 HTTP 入口。
 *
 * <p><b>鉴权模型（硬约束"绝不接受 App 的 JWT"）：</b>
 * <ul>
 *   <li>登录只发 HttpOnly Cookie（{@link AdminAuth}），是随机不透明令牌，不是 JWT；</li>
 *   <li>{@link #register} 里挂的 before 过滤器先于 {@code FamilyChatServer} 的 CORS
 *       before 执行（Javalin 按注册顺序跑 before），因此未登录的 /admin/* 请求根本
 *       到不了业务代码，也就没有"漏挂鉴权"的可能；</li>
 *   <li>本类全程不读 {@code Authorization} 头 —— 即使攻击者把 App 的 JWT 怼进来，
 *       也没有任何代码路径会去解析它。</li>
 * </ul>
 *
 * <p>写操作顺序固定为<b>先审计、后执行</b>：审计失败会打 ERROR 但仍继续（不能让审计库
 * 故障把管理员挡在门外），而动作成功与否都会在响应里如实返回。
 */
public class AdminHandler {

    private final AdminDao dao = new AdminDao(FamilyChatServer.getDb());
    private final Database db = FamilyChatServer.getDb();

    /** 管理接口默认分页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 管理员登录名允许的字符集：3-32 位的 ASCII 字母/数字/点/下划线/连字符。
     *
     * <p>刻意只收 ASCII：登录名会出现在 Cookie 会话、审计日志、控制台横幅里，收窄字符集
     * 可以避免同形字（西里尔字母 а vs 拉丁 a）冒充既有账号，也不需要为它设计 Unicode
     * 归一化规则。中文显示名属于 App 用户侧的 {@code nickname}，与这里无关。
     */
    private static final java.util.regex.Pattern ADMIN_NAME_OK =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{3,32}");

    /**
     * 注册路由。必须在 {@code FamilyChatServer.main} 的最早期调用，使鉴权过滤器
     * 注册在其它 before 之前。
     */
    public void register(Javalin app) {
        // ⚠️ 关键：Javalin 的 before 过滤器【不会】自动终止后续 handler —— 它只是"先跑一段代码"。
        // 实测（AdminFilterProbeTest）：before 里写 401 + return 之后，路由 handler 照样执行，
        // 于是响应状态是 401、响应体却是 /admin/api/stats 的真实数据（鉴权形同虚设）。
        // 所以这里必须主动抛异常来短路，并且下面的异常处理器负责把响应发出去。
        app.exception(ShortCircuit.class, (e, ctx) -> {
            // 只处理"需要写 JSON 错误体"的短路。重定向的短路在抛出前已经设好
            // Location 与 302，这里不能再碰 response，否则会把重定向改写成 401。
            if (e.status == 302) return;
            if (!ctx.res().isCommitted()) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
            }
        });

        // ── 鉴权过滤器：除登录接口外，/admin 一律先过会话 ──
        // 必须注册【两条】路径，少一条都会漏保护：
        //   "/admin"   的正则是 ^/admin/?$  → 覆盖 /admin 与 /admin/，但【不含】任何子路径；
        //   "/admin/*" 的正则要求 admin 后必须跟 "/" → 覆盖 /admin/ 及其下所有子路径，
        //              但【不】覆盖无斜杠的 /admin 本身。
        // 历史坑：以前 /admin 的 handler 是"重定向到 /admin/"（见下方注释），
        // 裸 /admin 靠这一跳间接落进 "/admin/*" 的保护范围；删掉那次重定向后，
        // 裸 /admin 会绕过鉴权直接进 page handler，ctx.attribute("admin") 为 null → 500。
        app.before("/admin", this::adminFilter);
        app.before("/admin/*", this::adminFilter);

        // ⚠️ 不要在这里再注册 app.get("/admin", ctx -> ctx.redirect("/admin/"))：
        // Javalin 6 默认 ignoreTrailingSlashes=true，"/admin" 与 "/admin/" 会被编译成
        // 同一条正则 ^/admin/?$，而路由解析取【首个】匹配项 —— 那条重定向会遮蔽下面的
        // page handler，把 /admin/ 重定向到它自己，浏览器直接 ERR_TOO_MANY_REDIRECTS。
        // 只留 "/admin/" 一条即可：它的正则本身就同时匹配 "/admin" 与 "/admin/"，
        // 裸 /admin 由上面刚补的 before("/admin") 负责鉴权。
        app.get("/admin/", this::page);
        app.get("/admin/login", this::loginPage);
        app.post("/admin/login", this::login);
        app.post("/admin/logout", this::logout);

        app.get("/admin/api/stats", this::stats);
        app.get("/admin/api/users", this::users);
        app.post("/admin/api/users/{id}/disable", this::disableUser);
        app.post("/admin/api/users/{id}/enable", this::enableUser);
        app.post("/admin/api/users/{id}/rename", this::renameUser);
        app.post("/admin/api/users/{id}/delete", this::deleteUser);
        app.post("/admin/api/password", this::changePassword);
        app.post("/admin/api/username", this::changeUsername);

        app.get("/admin/api/audit", this::audit);
        app.get("/admin/api/refunds", this::refunds);
        app.post("/admin/api/refunds/{id}/approve", this::approveRefund);
        app.post("/admin/api/refunds/{id}/reject", this::rejectRefund);
        app.get("/admin/api/recharges", this::recharges);
        app.post("/admin/api/recharges", this::doRecharge);
        app.get("/admin/api/charges", this::charges);
        app.post("/admin/api/charges/{id}/approve", this::approveCharge);
        app.post("/admin/api/charges/{id}/decline", this::declineCharge);
        // ── 审计第 5 条：孤儿数据与磁盘回收 ──
        app.post("/admin/api/maintenance/scan", this::orphanScan);
        app.post("/admin/api/maintenance/cleanup", this::orphanCleanup);
    }

    // ═══════════════════════════ 页面 ═══════════════════════════

    /**
     * 管理端鉴权过滤器本体，注册在 {@code "/admin"} 与 {@code "/admin/*"} 两条路径上
     * （为什么必须两条，见 {@link #register} 里的注释）。
     *
     * <p>放行时把会话塞进 {@code ctx.attribute("admin")}，后续 handler 直接取用；
     * 拦截时抛 {@link ShortCircuit} —— Javalin 6 的 before 过滤器没有"返回即停止"的语义，
     * 不抛异常的话路由 handler 照样执行。
     */
    private void adminFilter(Context ctx) {
        String p = ctx.path();
        if (p.equals("/admin/login") || p.equals("/admin/logout")) return;   // 这两个自己处理
        AdminAuth.Session s = AdminAuth.current(ctx, dao);
        if (s == null) {
            // 浏览器访问页面 → 跳登录页；XHR/接口 → 401 交给前端跳转
            if (isApi(p) || !wantsHtml(ctx)) {
                throw new ShortCircuit(401, "未登录或会话已过期");
            }
            ctx.redirect("/admin/login");
            throw new ShortCircuit(302, "");
        }
        ctx.attribute("admin", s);
        // 服务端强制"初始密码必须先改"：只放行改密所需的三类路径，其余一律 403。
        // 为什么放行首页与登出：首页本身承载"初始密码未改"的提示且没有数据操作，
        // 登出是任何时候都必须可用的退路；其余 /admin/api/* 在改密前都不该被调用。
        if (AdminAudit.mustChangePassword(dao, s.adminId) && !isBeforePasswordChange(p)) {
            throw new ShortCircuit(403, "必须先修改密码后才能使用该功能");
        }
    }

    /** 初始密码未改时仍放行的路径：首页（/admin 与 /admin/）、改密接口、登出。 */
    private static boolean isBeforePasswordChange(String p) {
        return p.equals("/admin") || p.equals("/admin/") || p.equals("/admin/logout")
                || p.equals("/admin/api/password");
    }

    private void page(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        boolean must = AdminAudit.mustChangePassword(dao, s.adminId);
        ctx.contentType("text/html; charset=UTF-8").result(AdminPages.shell(s.username, must));
    }

    private void loginPage(Context ctx) {
        if (AdminAuth.current(ctx, dao) != null) {
            ctx.redirect("/admin/");
            return;
        }
        String ip = ip(ctx);
        long lock = AdminAuth.lockRemainingSeconds(ip);
        ctx.contentType("text/html; charset=UTF-8")
                .result(AdminPages.loginPage(null, lock > 0, lock));
    }

    // ═══════════════════════════ 登录 / 登出 ═══════════════════════════

    private void login(Context ctx) {
        String ip = ip(ctx);
        long lock = AdminAuth.lockRemainingSeconds(ip);
        if (lock > 0) {
            // HTML 表单提交：回登录页展示锁定倒计时，而不是给一串 JSON 让人看不懂
            ctx.status(429).contentType("text/html; charset=UTF-8")
                    .result(AdminPages.loginPage(null, true, lock));
            return;
        }
        String username = trim(ctx.formParam("username"));
        String password = ctx.formParam("password") == null ? "" : ctx.formParam("password");
        if (username.isEmpty() || password.isEmpty()) {
            ctx.status(400).contentType("text/html; charset=UTF-8")
                    .result(AdminPages.loginPage("用户名和密码不能为空", false, 0));
            return;
        }

        Map<String, Object> admin = dao.findByName(username);
        // verifyPassword 在账号不存在时也会跑一次 BCrypt，抹掉用户名枚举的时序差
        String hash = admin == null ? null : String.valueOf(admin.get("password_hash"));
        boolean ok = AdminAuth.verifyPassword(password, hash);
        boolean disabled = admin != null && num(admin.get("disabled")) == 1;
        if (!ok || disabled) {
            AdminAuth.recordFailure(ip);
            long left = AdminAuth.lockRemainingSeconds(ip);
            // 审计：失败也记，暴力破解尝试必须留痕（admin_id=0 表示未认证）
            dao.writeAudit(0, username.isEmpty() ? "?" : username, "admin_login_failed",
                    "admin_users:" + (admin == null ? "-" : admin.get("id")),
                    "{\"reason\":\"" + (disabled ? "disabled" : "bad_credentials")
                            + "\",\"lock_remaining_s\":" + left + "}", ip);
            String msg = disabled ? "该管理员账号已被禁用" : "用户名或密码错误";
            ctx.status(401).contentType("text/html; charset=UTF-8")
                    .result(AdminPages.loginPage(msg, left > 0, left));
            return;
        }

        long adminId = num(admin.get("id"));
        // 新会话必须绑定"当前口令代次"。读不到（-1）说明库层异常：fail-closed 拒绝登录，
        // 绝不用 -1 建一个永远对不上号的会话（否则登录看似成功、下一个请求就被送回登录页）。
        long epoch = dao.getSessionEpoch(adminId);
        if (epoch < 0) {
            System.err.println("[ADMIN] 登录拒绝：读取 session_epoch 失败 adminId=" + adminId);
            ctx.status(500).contentType("text/html; charset=UTF-8")
                    .result(AdminPages.loginPage("服务端会话状态异常，请稍后重试", false, 0));
            return;
        }
        AdminAuth.clearFailures(ip);
        AdminAuth.startSession(ctx, adminId, username, epoch);
        dao.touchLogin(adminId);
        dao.writeAudit(adminId, username, "admin_login", "admin_users:" + adminId,
                "{\"result\":\"ok\"}", ip);
        ctx.redirect("/admin/");
    }

    private void logout(Context ctx) {
        AdminAuth.Session s = AdminAuth.current(ctx, dao);
        if (s != null) {
            dao.writeAudit(s.adminId, s.username, "admin_logout", "admin_users:" + s.adminId, "", ip(ctx));
        }
        AdminAuth.endSession(ctx);
        ctx.redirect("/admin/login");
    }

    private void changePassword(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        Map<String, Object> body = body(ctx);
        String oldPw = str(body.get("old_password"));
        String newPw = str(body.get("new_password"));
        if (newPw.length() < 8) {
            ctx.status(400).json(Map.of("error", "新密码至少 8 位"));
            return;
        }
        // 纯数字密码在 8 位长度下只有 1e8 种可能，离线爆破几秒即出，必须单独挡掉
        if (newPw.matches("\\d+")) {
            ctx.status(400).json(Map.of("error", "新密码不能是纯数字"));
            return;
        }
        if (newPw.equals(oldPw)) {
            ctx.status(400).json(Map.of("error", "新密码不能与当前密码相同"));
            return;
        }
        // 按 id 取哈希，而不是 findByName(s.username)：会话里的用户名是登录时的快照，
        // 一旦管理员改过名就会查不到人，表现为"密码明明正确却报当前密码不正确"
        Map<String, Object> me = dao.findByIdWithHash(s.adminId);
        String hash = me == null ? null : str(me.get("password_hash"));
        if (hash == null || !AdminAuth.verifyPassword(oldPw, hash)) {
            dao.writeAudit(s.adminId, s.username, "admin_password_change_failed",
                    "admin_users:" + s.adminId, "{\"reason\":\"bad_old_password\"}", ip(ctx));
            ctx.status(400).json(Map.of("error", "当前密码不正确"));
            return;
        }
        // 先审计后落库：改密是不可逆的管理动作，必须留痕
        dao.writeAudit(s.adminId, s.username, "admin_password_change",
                "admin_users:" + s.adminId, "{\"result\":\"pending\"}", ip(ctx));
        if (!dao.updatePassword(s.adminId, AdminAuth.hashPassword(newPw))) {
            ctx.status(500).json(Map.of("error", "修改失败"));
            return;
        }
        // 改密成功即刻回收初始密码明文（data/admin_initial_password.txt）
        AdminAuth.dropInitialPasswordFile();
        System.out.println("[ADMIN] 管理员 " + s.username + " 已修改密码");
        ctx.json(Map.of("success", true));
    }

    /**
     * 改当前管理员的登录名（username）。
     *
     * <p>校验顺序是刻意的：格式 → 是否同名 → <b>当前密码</b> → 落库。
     * 把密码校验放在格式校验之后，是为了让"用户名不合法"这种纯输入问题不必先跑一次
     * 昂贵的 BCrypt（cost=10 约 60ms）就能返回；但密码校验一定在落库之前，改名等同于
     * 换一把登录钥匙，必须由本人凭当前口令发起，不能只靠一个可能被盗用的会话。
     *
     * <p>失败也写审计，但<b>只记有安全意义的失败</b>（口令错 / 重名 / 写库失败）；
     * 纯格式错误不记 —— 那只是用户在输入框里打错字，记进去只会把审计日志淹掉，
     * 与 changePassword 对"密码太短"不写审计的处理保持一致。
     *
     * <p>审计的 {@code admin_name} 是冗余快照：改名前的历史记录仍显示旧名，这是设计意图
     * （见 {@code admin_audit.admin_name} 的建表注释），不要试图回填。
     */
    private void changeUsername(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        Map<String, Object> body = body(ctx);
        String newName = trim(str(body.get("new_username")));
        String password = str(body.get("password"));

        // 1) 格式
        if (!ADMIN_NAME_OK.matcher(newName).matches()) {
            ctx.status(400).json(Map.of("error",
                    "用户名需为 3-32 位字母、数字、点、下划线或连字符"));
            return;
        }
        // 2) 与当前名相同：明确报错，不静默成功 —— 静默成功会让前端提示"已改名"而其实没动
        if (newName.equals(s.username)) {
            ctx.status(400).json(Map.of("error", "新用户名与当前用户名相同"));
            return;
        }
        // 3) 当前密码
        Map<String, Object> me = dao.findByIdWithHash(s.adminId);
        String hash = me == null ? null : str(me.get("password_hash"));
        if (hash == null || !AdminAuth.verifyPassword(password, hash)) {
            dao.writeAudit(s.adminId, s.username, "admin_username_change_failed",
                    "admin_users:" + s.adminId,
                    AdminAudit.json("reason", "bad_password"), ip(ctx));
            ctx.status(400).json(Map.of("error", "当前密码不正确"));
            return;
        }
        String oldName = s.username;
        // 4) 重名先做一次读检查：这样常见路径只留下一条 _failed 审计，
        //    而不是先写一条"疑似成功"再补一条失败。真正的并发防线仍是 UNIQUE 约束。
        if (dao.findByName(newName) != null) {
            dao.writeAudit(s.adminId, oldName, "admin_username_change_failed",
                    "admin_users:" + s.adminId,
                    AdminAudit.json("reason", "duplicate", "new", newName), ip(ctx));
            ctx.status(400).json(Map.of("error", "用户名已存在"));
            return;
        }

        // 先审计后落库（与 changePassword 同一约定）：进程若在写库途中被打断，
        // 日志里也留下了"谁试图改成什么"
        dao.writeAudit(s.adminId, oldName, "admin_username_change",
                "admin_users:" + s.adminId,
                AdminAudit.json("old", oldName, "new", newName, "result", "pending"), ip(ctx));

        AdminDao.RenameResult r = dao.updateUsername(s.adminId, newName);
        if (r != AdminDao.RenameResult.OK) {
            boolean dup = r == AdminDao.RenameResult.DUPLICATE;   // 上面查过后仍撞上 = 并发改名
            dao.writeAudit(s.adminId, oldName, "admin_username_change_failed",
                    "admin_users:" + s.adminId,
                    AdminAudit.json("reason", dup ? "duplicate" : "write_failed", "new", newName),
                    ip(ctx));
            if (dup) {
                ctx.status(400).json(Map.of("error", "用户名已存在"));
            } else {
                ctx.status(500).json(Map.of("error", "修改失败"));
            }
            return;
        }

        // 会话里的用户名是登录快照，必须同步刷新，否则头部一直显示旧名
        if (!AdminAuth.updateSessionUsername(ctx, newName)) {
            System.err.println("[ADMIN] 会话用户名刷新失败（会话可能已过期）: "
                    + oldName + " -> " + newName);
        }
        System.out.println("[ADMIN] 管理员 " + oldName + " 已改名为 " + newName);
        ctx.json(Map.of("success", true, "username", newName));
    }

    // ═══════════════════════════ 概览 ═══════════════════════════

    private void stats(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", AdminStats.collect(db));
        Map<String, Object> me = dao.findById(s.adminId);
        out.put("admin", Map.of(
                "id", s.adminId,
                "username", s.username,
                "last_login_at", me == null || me.get("last_login_at") == null
                        ? 0L : num(me.get("last_login_at")),
                "must_change_pw", AdminAudit.mustChangePassword(dao, s.adminId)));
        out.put("data_dir", Database.DATA_DIR);
        ctx.json(out);
    }

    // ═══════════════════════════ 用户管理 ═══════════════════════════

    private void users(Context ctx) {
        int page = Math.max(1, intParam(ctx, "page", 1));
        int size = clamp(intParam(ctx, "size", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        String q = trim(ctx.queryParam("q"));

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!q.isEmpty()) {
            where.append(" AND (u.username LIKE ? OR u.nickname LIKE ? OR CAST(u.id AS TEXT)=?)");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
            params.add(q);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        long total = 0;
        try (java.sql.Connection c = db.getConn()) {
            total = AdminStats.countUsers(c, where.toString(), params);
            String sql = "SELECT u.id,u.username,u.nickname,u.avatar,u.is_online," +
                    "u.created_at,u.last_seen,u.is_disabled,w.balance " +
                    "FROM users u LEFT JOIN wallets w ON w.user_id=u.id" + where +
                    " ORDER BY u.id DESC LIMIT ? OFFSET ?";
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (Object p : params) ps.setObject(i++, p);
                ps.setInt(i++, size);
                ps.setInt(i, (page - 1) * size);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("id", rs.getInt("id"));
                        u.put("username", rs.getString("username"));
                        u.put("nickname", rs.getString("nickname"));
                        u.put("avatar", rs.getString("avatar"));
                        u.put("is_online", rs.getInt("is_online") == 1);
                        u.put("is_disabled", rs.getInt("is_disabled") == 1);
                        // balance 可能为 NULL（从没建过钱包）—— 保持 null，前端显示"无钱包"
                        Object bal = rs.getObject("balance");
                        u.put("balance", bal == null ? null : ((Number) bal).longValue());
                        u.put("last_seen", rs.getString("last_seen"));
                        u.put("created_at_ms", AdminStats.parseCreatedAt(rs.getString("created_at")));
                        list.add(u);
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            ctx.status(500).json(Map.of("error", "查询失败: " + e.getMessage()));
            return;
        }
        long pages = total == 0 ? 1 : (total + size - 1) / size;
        ctx.json(Map.of("total", total, "page", page, "pages", pages, "users", list));
    }

    private void disableUser(Context ctx) {
        setDisabled(ctx, true);
    }

    private void enableUser(Context ctx) {
        setDisabled(ctx, false);
    }

    private void setDisabled(Context ctx, boolean disabled) {
        AdminAuth.Session s = ctx.attribute("admin");
        int uid = intPath(ctx, "id");
        Map<String, Object> target = db.getUserById(uid);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        String reason = str(body(ctx).get("reason"));
        if (disabled && reason.isEmpty()) {
            ctx.status(400).json(Map.of("error", "禁用必须填写原因（会写入审计）"));
            return;
        }
        // 先审计后执行
        dao.writeAudit(s.adminId, s.username, disabled ? "user_disable" : "user_enable",
                "users:" + uid, AdminAudit.json("username", str(target.get("username")),
                        "reason", reason), ip(ctx));

        try (java.sql.Connection c = db.getConn();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET is_disabled=? WHERE id=?")) {
            ps.setInt(1, disabled ? 1 : 0);
            ps.setInt(2, uid);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            ctx.status(500).json(Map.of("error", "操作失败: " + e.getMessage()));
            return;
        }
        if (disabled) {
            // 禁用后立刻把在线会话踢下线：否则已登录的客户端还能继续用旧 JWT 收发消息，
            // 直到 token 自然过期（最长 7 天），"禁用"就形同虚设。
            db.setOnline(uid, false);
            WsHandler.sendToUser(uid, Map.of("type", "account_disabled"));
            WsHandler.kickUser(uid);
        }
        ctx.json(Map.of("success", true, "is_disabled", disabled));
    }

    private void renameUser(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int uid = intPath(ctx, "id");
        String nickname = str(body(ctx).get("nickname")).trim();
        if (nickname.isEmpty() || nickname.length() > 24) {
            ctx.status(400).json(Map.of("error", "昵称需为 1-24 个字符"));
            return;
        }
        Map<String, Object> target = db.getUserById(uid);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        dao.writeAudit(s.adminId, s.username, "user_rename", "users:" + uid,
                AdminAudit.json("from", str(target.get("nickname")), "to", nickname), ip(ctx));
        db.updateUser(uid, "nickname", nickname);
        ctx.json(Map.of("success", true));
    }

    /**
     * 删除用户（连带清理见 {@link AdminStats#deleteUser}）。
     *
     * <p>二次确认采用"请求体里回填用户名"的方式：前端弹框让人手打用户名，
     * 服务端再比对一次，防止误点/脚本误触把唯一账号删掉。
     */
    private void deleteUser(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int uid = intPath(ctx, "id");
        String confirm = str(body(ctx).get("confirm_username")).trim();
        Map<String, Object> target = db.getUserById(uid);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        String username = str(target.get("username"));
        if (!username.equals(confirm)) {
            ctx.status(400).json(Map.of("error", "用户名不匹配，删除已取消"));
            return;
        }
        if (uid == 0) {
            ctx.status(400).json(Map.of("error", "系统账户不可删除"));
            return;
        }
        // 先审计后执行：删号不可逆，审计里要留下"谁、何时、依据哪个用户名确认的"
        dao.writeAudit(s.adminId, s.username, "user_delete", "users:" + uid,
                AdminAudit.json("username", username, "nickname", str(target.get("nickname"))),
                ip(ctx));
        Map<String, Object> res = AdminStats.deleteUser(db, uid);
        if (res.containsKey("error")) {
            ctx.status(500).json(res);
            return;
        }
        WsHandler.kickUser(uid);
        ctx.json(res);
    }

    // ═══════════════════════════ 审计日志 ═══════════════════════════

    private void audit(Context ctx) {
        int page = Math.max(1, intParam(ctx, "page", 1));
        int size = clamp(intParam(ctx, "size", 50), 1, MAX_PAGE_SIZE);
        String action = trim(ctx.queryParam("action"));
        String q = trim(ctx.queryParam("q"));
        List<Map<String, Object>> logs = dao.listAudit(action, q, page, size);
        long total = dao.countAudit(action, q);
        long pages = total == 0 ? 1 : (total + size - 1) / size;
        ctx.json(Map.of("total", total, "page", page, "pages", pages,
                "logs", logs, "actions", dao.distinctActions()));
    }

    // ═══════════════════════════ 退款审批 ═══════════════════════════

    private void refunds(Context ctx) {
        int page = Math.max(1, intParam(ctx, "page", 1));
        int size = clamp(intParam(ctx, "size", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        ctx.json(AdminStats.pendingRefunds(db, page, size));
    }

    private void approveRefund(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int orderId = intPath(ctx, "id");
        String reason = str(body(ctx).get("reason"));
        Map<String, Object> order = db.getOrder(orderId);
        if (order == null) {
            ctx.status(404).json(Map.of("error", "订单不存在"));
            return;
        }
        int sellerId = num(order.get("seller_id"));
        int buyerId = num(order.get("buyer_id"));
        // 防重复审批：状态不满足直接拒绝，且不写审计（这不是一次有效的管理动作）
        String rs = str(order.get("refund_status"));
        String st = str(order.get("status"));
        if (!"pending".equals(rs) || (!"paid".equals(st) && !"shipped".equals(st))) {
            ctx.status(409).json(Map.of("error", "当前订单状态不允许审批（可能已被处理）"));
            return;
        }
        dao.writeAudit(s.adminId, s.username, "refund_approve", "mall_orders:" + orderId,
                AdminAudit.json("order_no", str(order.get("order_no")),
                        "amount", num(order.get("amount")),
                        "buyer_id", buyerId, "seller_id", sellerId, "reason", reason), ip(ctx));
        // 复用卖家退款流程：事务内扣卖家余额→退买家→双向记账→恢复库存
        String err = db.approveRefund(orderId, sellerId);
        if (err != null) {
            dao.writeAudit(s.adminId, s.username, "refund_approve_failed",
                    "mall_orders:" + orderId, AdminAudit.json("error", err), ip(ctx));
            ctx.status(409).json(Map.of("error", err));
            return;
        }
        WsHandler.sendToUser(buyerId, Map.of("type", "mall_refund_result",
                "order_id", orderId, "approved", true, "by", "admin"));
        if (sellerId > 0 && sellerId != buyerId) {
            WsHandler.sendToUser(sellerId, Map.of("type", "mall_refund_result",
                    "order_id", orderId, "approved", true, "by", "admin"));
        }
        ctx.json(Map.of("success", true));
    }

    private void rejectRefund(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int orderId = intPath(ctx, "id");
        String reason = str(body(ctx).get("reason"));
        if (reason.isEmpty()) {
            ctx.status(400).json(Map.of("error", "驳回必须填写理由"));
            return;
        }
        Map<String, Object> order = db.getOrder(orderId);
        if (order == null) {
            ctx.status(404).json(Map.of("error", "订单不存在"));
            return;
        }
        if (!"pending".equals(str(order.get("refund_status")))) {
            ctx.status(409).json(Map.of("error", "当前没有待处理的退款申请"));
            return;
        }
        dao.writeAudit(s.adminId, s.username, "refund_reject", "mall_orders:" + orderId,
                AdminAudit.json("order_no", str(order.get("order_no")),
                        "buyer_id", num(order.get("buyer_id")), "reason", reason), ip(ctx));
        String err = db.rejectRefund(orderId, num(order.get("seller_id")));
        if (err != null) {
            ctx.status(409).json(Map.of("error", err));
            return;
        }
        WsHandler.sendToUser(num(order.get("buyer_id")), Map.of("type", "mall_refund_result",
                "order_id", orderId, "approved", false, "by", "admin"));
        ctx.json(Map.of("success", true));
    }

    // ═══════════════════════════ 充值审批 / 手动入账 ═══════════════════════════

    private void recharges(Context ctx) {
        int page = Math.max(1, intParam(ctx, "page", 1));
        int size = clamp(intParam(ctx, "size", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        ctx.json(AdminStats.recharges(db, page, size));
    }

    /**
     * 手动入账（平台代充值）。
     *
     * <p>与 App 侧 {@code /api/wallet/recharge} 的区别：
     * <ul>
     *   <li>不受 ADMIN_USER_IDS 白名单限制 —— 管理员本身就是授权来源；</li>
     *   <li>可以给别人充值（App 侧只能给自己）；</li>
     *   <li>单次上限放宽到 {@link #ADMIN_MAX_CREDIT}，且必须写审计。</li>
     * </ul>
     */
    private void doRecharge(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        Map<String, Object> b = body(ctx);
        int uid;
        long amount;
        try {
            uid = ((Number) b.get("user_id")).intValue();
            amount = ((Number) b.get("amount")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 user_id 或 amount"));
            return;
        }
        amount = amount * 100;   // 前端填元，内部存分
        if (amount <= 0 || amount > ADMIN_MAX_CREDIT) {
            ctx.status(400).json(Map.of("error",
                    "单次入账需在 0.01 ~ " + (ADMIN_MAX_CREDIT / 100) + " 元之间"));
            return;
        }
        Map<String, Object> target = db.getUserById(uid);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        String memo = str(b.get("memo"));
        if (memo.isEmpty()) memo = "管理员手动入账";
        dao.writeAudit(s.adminId, s.username, "recharge_manual", "users:" + uid,
                AdminAudit.json("username", str(target.get("username")),
                        "amount_fen", amount, "memo", memo), ip(ctx));
        if (!db.addBalance(uid, amount, ADMIN_MAX_CREDIT)) {
            ctx.status(500).json(Map.of("error", "入账失败（金额非法或超上限）"));
            return;
        }
        db.addTransaction(0, uid, "recharge", amount, memo, "completed", 0);
        Map<String, Object> w = db.getWallet(uid);
        WsHandler.sendToUser(uid, Map.of("type", "wallet_updated",
                "balance", w != null && w.get("balance") != null ? w.get("balance") : 0));
        ctx.json(Map.of("success", true,
                "balance", w != null ? w.get("balance") : 0));
    }

    /** 管理员单次入账上限（分）：10000 元。比 App 自助充值的 100 元宽，但仍设上限。 */
    private static final long ADMIN_MAX_CREDIT = 1_000_000L;

    // ═══════════════════════════ 收款请求（付款码）═══════════════════════════

    private void charges(Context ctx) {
        int page = Math.max(1, intParam(ctx, "page", 1));
        int size = clamp(intParam(ctx, "size", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        ctx.json(AdminStats.charges(db, page, size));
    }

    private void approveCharge(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int id = intPath(ctx, "id");
        Map<String, Object> req = db.getChargeRequest(id);
        if (req == null) {
            ctx.status(404).json(Map.of("error", "收款请求不存在"));
            return;
        }
        if (!"pending".equals(str(req.get("status")))) {
            ctx.status(409).json(Map.of("error", "该请求已处理过"));
            return;
        }
        int toUser = num(req.get("to_user"));
        dao.writeAudit(s.adminId, s.username, "charge_approve", "charge_requests:" + id,
                AdminAudit.json("amount", num(req.get("amount")),
                        "payer_id", toUser, "merchant_id", num(req.get("from_user"))), ip(ctx));
        // 管理端入口：不校验付款人归属（管理员代表平台处理），资金路径与主人确认共用
        Map<String, Object> r = db.approveChargeAsAdmin(id);
        if (r == null) {
            dao.writeAudit(s.adminId, s.username, "charge_approve_failed",
                    "charge_requests:" + id,
                    AdminAudit.json("error", "余额不足或状态已变更"), ip(ctx));
            ctx.status(409).json(Map.of("error", "代确认失败：付款人余额不足或状态已变更"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    private void declineCharge(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        int id = intPath(ctx, "id");
        String reason = str(body(ctx).get("reason"));
        Map<String, Object> req = db.getChargeRequest(id);
        if (req == null) {
            ctx.status(404).json(Map.of("error", "收款请求不存在"));
            return;
        }
        if (!"pending".equals(str(req.get("status")))) {
            ctx.status(409).json(Map.of("error", "该请求已处理过"));
            return;
        }
        dao.writeAudit(s.adminId, s.username, "charge_decline", "charge_requests:" + id,
                AdminAudit.json("reason", reason), ip(ctx));
        if (!db.declineChargeAsAdmin(id)) {
            ctx.status(409).json(Map.of("error", "拒绝失败：状态已变更"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    // ═══════════════════════════ 孤儿数据 / 磁盘回收（审计第 5 条）═══════════════════════════

    /** 只扫描不修改：把库内孤儿与磁盘未引用文件的清单和体量报出来。 */
    private void orphanScan(Context ctx) {
        ctx.json(Map.of("report", db.sweepOrphans(true, 100)));
    }

    /**
     * 执行清理。
     *
     * <p><b>默认只清数据库孤儿，不删磁盘文件</b> —— 因为"数据库没引用"在这套 schema 下
     * 不足以证明文件没被使用：App 上传的附件 URL 从未落库（只存了原始文件名 + E2EE 密文），
     * 实测 119 个 chat_files 文件里只有 3 个能在库里匹配到 URL。
     * 按"未引用"批量删会清掉用户仍然能看到的聊天附件。
     *
     * <p>要连磁盘一起删，必须显式传 {@code delete_files=true} 并再次确认，
     * 且删除前会做一次数据库备份 —— 让"我知道这可能删掉在用的附件"成为一个
     * 由人做出的、留痕的决定，而不是后台点一下按钮的副作用。
     */
    private void orphanCleanup(Context ctx) {
        AdminAuth.Session s = ctx.attribute("admin");
        Map<String, Object> body = body(ctx);
        if (!"CONFIRM".equals(str(body.get("confirm")).trim())) {
            ctx.status(400).json(Map.of("error", "危险操作：请在确认框输入 CONFIRM"));
            return;
        }
        boolean deleteFiles = Boolean.TRUE.equals(body.get("delete_files"));
        // 先备份再删（硬约束："不删任何数据前必须先备份"）
        String backup = backupDatabase();
        dao.writeAudit(s.adminId, s.username, "maintenance_cleanup", "orphan_rows"
                        + (deleteFiles ? "+chat_files" : ""),
                AdminAudit.json("backup", backup, "delete_files", deleteFiles), ip(ctx));
        Map<String, Object> report = db.sweepOrphans(false, 50);
        if (deleteFiles) {
            Map<String, Object> disk = db.reclaimFiles(allChatFileNames(), false);
            report.put("diskReclaim", disk);
        } else {
            report.put("diskReclaimSkipped", "未删除任何磁盘文件（默认安全模式）；"
                    + "如确认要回收，请带 delete_files=true 再次执行");
        }
        System.out.println("[ADMIN] 孤儿清理完成 deleteFiles=" + deleteFiles
                + " backup=" + backup + " report=" + report);
        ctx.json(Map.of("report", report, "backup", backup, "deleted_files", deleteFiles));
    }

    /** chat_files 下的全部文件名（回收判定由 reclaimFiles/isFileReferenced 负责）。 */
    private List<String> allChatFileNames() {
        List<String> names = new ArrayList<>();
        java.io.File dir = new java.io.File(Database.DATA_DIR, "chat_files");
        java.io.File[] files = dir.listFiles();
        if (files == null) return names;
        for (java.io.File f : files) {
            if (f.isFile()) names.add(f.getName());
        }
        return names;
    }

    /**
     * 用 SQLite 的 {@code VACUUM INTO} 做一致性快照备份。
     *
     * <p>为什么不是简单复制文件：库开着 WAL，直接拷 .db 可能漏掉尚未 checkpoint 的数据。
     * VACUUM INTO 由 SQLite 自己保证是完整一致快照，且不需要服务停写。
     */
    private String backupDatabase() {
        java.nio.file.Path dir = java.nio.file.Paths.get(Database.DATA_DIR, "backup");
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        java.nio.file.Path out = dir.resolve("familychat.db." + stamp + ".bak");
        try {
            java.nio.file.Files.createDirectories(dir);
            try (java.sql.Connection c = db.getConn();
                 java.sql.Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + out.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            System.out.println("[ADMIN] 备份完成 -> " + out);
            return out.toAbsolutePath().toString();
        } catch (Exception e) {
            System.err.println("[ADMIN] 备份失败: " + e.getMessage());
            return "BACKUP_FAILED: " + e.getMessage();
        }
    }

    // ═══════════════════════════ 助手 ═══════════════════════════

    /**
     * 用于在 before 过滤器里<b>真正</b>终止请求的内部异常。
     *
     * <p>存在的唯一理由：Javalin 6 的 before 过滤器没有"返回即停止"的语义，
     * 必须靠抛异常打断路由链，再由上面注册的 exception handler 写响应。
     * 用私有异常类型而不是 HttpResponseException，是为了完全掌控响应体格式
     * （管理端前端只认 JSON {@code {error}}，不接受 Javalin 默认错误页）。
     */
    private static final class ShortCircuit extends RuntimeException {
        final int status;

        ShortCircuit(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static boolean isApi(String path) {
        return path.startsWith("/admin/api/") || path.equals("/admin/logout");
    }

    /** 浏览器导航请求带 Accept: text/html；XHR 一般都带 application/json。 */
    private static boolean wantsHtml(Context ctx) {
        String a = ctx.header("Accept");
        return a != null && a.contains("text/html");
    }

    private static String ip(Context ctx) {
        // 经 Cloudflare tunnel 时真实来源在 CF-Connecting-IP / X-Forwarded-For，
        // 仅用于审计展示，不用于鉴权决策（可伪造，不能作为安全依据）。
        String cf = ctx.header("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return ctx.req().getRemoteAddr();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Context ctx) {
        try {
            Map<String, Object> m = ctx.bodyAsClass(Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int intPath(Context ctx, String name) {
        try {
            return Integer.parseInt(ctx.pathParam(name));
        } catch (Exception e) {
            return -1;
        }
    }

    private static int intParam(Context ctx, String name, int def) {
        try {
            String v = ctx.queryParam(name);
            return v == null ? def : Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
