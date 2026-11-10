package com.familychat.server.admin;

import com.familychat.server.model.Database;
import io.javalin.http.Context;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员会话与登录鉴权。
 *
 * <p><b>与 App 鉴权的隔离方式（硬约束）：</b>管理端只认自己签发的 HttpOnly Cookie，
 * 而 Cookie 里放的是随机 256bit 不透明令牌，<b>不是 JWT</b>。因此哪怕有人把 App 的
 * {@code Authorization: Bearer <JWT>} 直接怼到 /admin/* 上，这里也永远解析不出会话 ——
 * 两条链路没有共享任何解析器。App 侧 {@code AuthHandler.requireAuth} 同样不认识本 Cookie。
 *
 * <p>令牌只以 SHA-256 摘要驻留内存：即便进程内存转储泄漏，也无法反推出可用 Cookie 值。
 */
public final class AdminAuth {

    /** Cookie 名：App 端从不使用 Cookie，前缀独立便于在日志里一眼区分。 */
    public static final String COOKIE = "miyu_admin_session";

    private static final long SESSION_TTL_MS = 12 * 60 * 60 * 1000L;   // 12 小时
    private static final int SESSION_MAP_MAX = 2000;                   // 内存上界

    /** 登录失败限流：5 次失败锁 15 分钟（同一 IP）。 */
    private static final int LOGIN_MAX_FAILS = 5;
    private static final long LOGIN_LOCK_MS = 15 * 60 * 1000L;

    private static final SecureRandom RNG = new SecureRandom();

    /** tokenHash -> 会话。 */
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** ip -> [failCount, firstFailAt, lockedUntil]。 */
    private static final ConcurrentHashMap<String, long[]> LOGIN_FAILS = new ConcurrentHashMap<>();

    public static final class Session {
        public final long adminId;
        public final String username;
        /**
         * 签发该会话时库里的 {@code admin_users.session_epoch} 快照。
         *
         * <p>每次请求都会拿它与库里的当前值比对（见 {@link #current(Context, AdminDao)}）：
         * 运维在 CLI 里重置口令会 bump 这个计数器，于是<b>用旧口令签发的全部会话</b>
         * 在下次请求时就失效 —— 即使重置发生在另一个 JVM，也无需任何跨进程通知。
         */
        public final long epoch;
        public final long expiresAt;

        Session(long adminId, String username, long epoch, long expiresAt) {
            this.adminId = adminId;
            this.username = username;
            this.epoch = epoch;
            this.expiresAt = expiresAt;
        }

        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private AdminAuth() {
    }

    // ═══════════════════════════ 会话 ═══════════════════════════

    /** 新建会话并把令牌写进 HttpOnly Cookie。返回令牌摘要对应的会话。 */
    public static Session startSession(Context ctx, long adminId, String username, long epoch) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long exp = System.currentTimeMillis() + SESSION_TTL_MS;
        Session s = new Session(adminId, username, epoch, exp);
        evictIfNeeded();
        SESSIONS.put(hash(token), s);

        // HttpOnly：JS 读不到，XSS 无法窃取
        // SameSite=Strict：跨站请求根本不带 Cookie，管理接口天然免受 CSRF
        // 不加 Secure：局域网部署常为 http://，加了浏览器会直接丢弃 Cookie
        ctx.cookie(sessionCookie(token, (int) (SESSION_TTL_MS / 1000)));
        return s;
    }

    /**
     * 当前请求的管理员会话；未登录/已过期/已被代次失效返回 null（并清掉无效 Cookie）。
     *
     * <p><b>为什么每次请求都查库、不做缓存：</b>管理端是低频运维界面（QPS 极低），
     * 而 SQLite 就在同一台机器的本地文件上，一次主键查询的成本远小于"缓存没失效好"
     * 带来的安全风险。缓存必须维护失效点（本 JVM 的 bump 能清，别的 JVM / CLI 清的清不了），
     * 任何漏掉的一处都会让"重置口令 = 踢掉旧会话"这条承诺悄悄失效。
     *
     * <p><b>失败语义是 fail-closed：</b>库里读不到代次（返回 -1）或与快照不相等时，
     * 一律删除该令牌、清 Cookie、返回 null。DB 抖动只会把管理员送回登录页，绝不会
     * 放行一个可能已作废的会话。
     */
    public static Session current(Context ctx, AdminDao dao) {
        String token = ctx.cookie(COOKIE);
        if (token == null || token.isEmpty()) return null;
        String key = hash(token);
        Session s = SESSIONS.get(key);
        if (s == null || s.expired()) {
            SESSIONS.remove(key);
            return null;
        }
        long dbEpoch = dao.getSessionEpoch(s.adminId);
        if (dbEpoch < 0 || dbEpoch != s.epoch) {
            SESSIONS.remove(key);
            ctx.cookie(sessionCookie("", 0));
            return null;
        }
        return s;
    }

    /** 注销：删会话 + 清 Cookie（Max-Age=0）。 */
    public static void endSession(Context ctx) {
        String token = ctx.cookie(COOKIE);
        if (token != null && !token.isEmpty()) SESSIONS.remove(hash(token));
        ctx.cookie(sessionCookie("", 0));
    }

    /**
     * 就地刷新当前会话里的用户名（管理员改了自己的登录名后调用），会话本身继续有效。
     *
     * <p><b>为什么必须刷新：</b>{@link Session#username} 是登录那一刻的快照，而页面头部
     * （{@code AdminPages.shell}）与审计的 {@code admin_name} 都读它。不刷新的话，改完名
     * 页面上还挂着旧名字；更危险的是任何"拿会话名去查库"的代码路径都会查不到人。
     *
     * <p><b>为什么整体替换实例、而不是把 username 改成非 final：</b>后者会引入可变共享
     * 状态（多个请求线程可能读到字段被改到一半的 Session，需要额外同步）；整体替换在
     * {@link ConcurrentHashMap} 上是原子的，且让 Session 继续保持不可变、无需加锁。
     * 令牌摘要（map 的 key）与 {@code expiresAt} 都原样保留，因此不会顺带续期或踢下线。
     *
     * @return 刷新成功 true；会话不存在/已过期 false（调用方不必视为致命错误 —— 库里的
     *         改名已经生效，管理员下次请求会因会话失效被送回登录页）
     */
    public static boolean updateSessionUsername(Context ctx, String newUsername) {
        String token = ctx.cookie(COOKIE);
        if (token == null || token.isEmpty()) return false;
        String key = hash(token);
        Session old = SESSIONS.get(key);
        if (old == null || old.expired()) return false;
        // epoch 必须原样带走：它是"这个会话是哪一代口令签发的"快照，
        // 替换实例时漏掉会静默变成 0，下一次请求就被代次校验判成失效（改完名把自己踢下线）。
        SESSIONS.put(key, new Session(old.adminId, newUsername, old.epoch, old.expiresAt));
        return true;
    }

    /**
     * 构造会话 Cookie。
     *
     * <p>参数顺序（Javalin 6 的 Kotlin data class，构造器有多个重载）：
     * name, value, path, maxAge(秒), secure, version, httpOnly, comment, domain, sameSite。
     * 用最长的那个构造器显式写出 SameSite，避免依赖默认值 —— 默认是 NONE，
     * 那等于放弃 SameSite 这层 CSRF 防护。
     */
    private static io.javalin.http.Cookie sessionCookie(String value, int maxAgeSeconds) {
        return new io.javalin.http.Cookie(
                COOKIE, value, "/", maxAgeSeconds,
                false /* secure：局域网 http 下必须为 false */,
                0     /* version：0 = 不带 Cookie: 版本前缀 */,
                true  /* httpOnly */,
                null  /* comment */,
                null  /* domain：不写 Domain，锁定当前主机 */,
                io.javalin.http.SameSite.STRICT);
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 防止会话表无界增长：先清过期的，仍超限就再挤掉一批最旧的。 */
    private static void evictIfNeeded() {
        if (SESSIONS.size() < SESSION_MAP_MAX) return;
        SESSIONS.entrySet().removeIf(e -> e.getValue().expired());
        int toDrop = SESSIONS.size() - SESSION_MAP_MAX + 1;
        if (toDrop <= 0) return;
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        for (int i = 0; it.hasNext() && i < toDrop; i++) {
            it.next();
            it.remove();
        }
    }

    // ═══════════════════════════ 登录限流 ═══════════════════════════

    /** 剩余锁定秒数；未锁返回 0。 */
    public static long lockRemainingSeconds(String ip) {
        long[] st = LOGIN_FAILS.get(ip);
        if (st == null) return 0;
        long left = st[2] - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /** 记一次失败；达到阈值即锁定。 */
    public static void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        LOGIN_FAILS.compute(ip, (k, st) -> {
            if (st == null || now - st[1] > LOGIN_LOCK_MS) {
                return new long[]{1, now, 0};
            }
            st[0]++;
            if (st[0] >= LOGIN_MAX_FAILS) {
                st[2] = now + LOGIN_LOCK_MS;   // 锁 15 分钟
            }
            return st;
        });
        // 内存上界保护：IP 维度可被伪造，不能让 map 无限长
        if (LOGIN_FAILS.size() > 5000) {
            LOGIN_FAILS.entrySet().removeIf(e -> now - e.getValue()[1] > LOGIN_LOCK_MS);
        }
    }

    /** 登录成功后清掉该 IP 的失败计数。 */
    public static void clearFailures(String ip) {
        LOGIN_FAILS.remove(ip);
    }

    /**
     * 仅供测试：清空全部登录限流状态。
     *
     * <p>限流表是 JVM 级静态状态，同一测试进程里前一个用例故意攒够 5 次失败后，
     * 后面的用例会被连带锁定（表现为莫名其妙的 429）。测试需要能隔离这份状态。
     */
    public static void resetRateLimiterForTests() {
        LOGIN_FAILS.clear();
    }

    // ═══════════════════════════ 口令校验 ═══════════════════════════

    /**
     * 校验管理员口令。
     *
     * <p>刻意在"用户不存在"分支上也跑一次 BCrypt：否则存在用户名枚举的时序侧信道
     * （存在的账号要 ~60ms，不存在的账号 0ms，扫一遍就能筛出真实用户名）。
     */
    public static boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            BCrypt.checkpw(password, DUMMY_HASH);
            return false;
        }
        try {
            return BCrypt.checkpw(password, storedHash);
        } catch (IllegalArgumentException e) {
            // 库里的哈希不是合法 BCrypt（手工改库导致），拒绝登录而不是崩 500
            System.err.println("[ADMIN] 非法 BCrypt 哈希: " + e.getMessage());
            return false;
        }
    }

    /** 一个固定成本的假哈希，专供时序对齐使用。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 生成管理员口令哈希。 */
    public static String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(10));
    }

    /** 首次启动时确保存在管理员：没有就建一个并返回初始密码，已有则返回 null。 */
    public static String bootstrapIfEmpty(AdminDao dao) {
        if (dao.countAdmins() > 0) {
            noteExistingAdmin(dao);
            return null;
        }
        String username = "admin";
        String initial = AdminSupport.generatePassword(14);
        long id = dao.createAdmin(username, hashPassword(initial), true);
        if (id <= 0) {
            System.err.println("[ADMIN] 初始管理员创建失败（id=" + id + "）");
            return null;
        }
        // 审计 admin_id=0 表示"系统/启动期动作"：这条必须留痕，它是管理端第一次写入
        dao.writeAudit(0, "system", "admin_bootstrap", "admin_users:" + id,
                "{\"username\":\"" + username + "\",\"must_change_pw\":true}", "local");
        printBootstrapBanner(username, initial);
        return initial;
    }

    /**
     * 运维重置某管理员的口令（忘记密码时的唯一出路：库里只有 BCrypt 哈希，反推不出来）。
     *
     * <p><b>旧会话会被立刻踢下线：</b>先 {@code bumpSessionEpoch(id)} 把会话代次 +1，
     * 再用新哈希覆盖口令。该管理员此前签发的所有会话（含当前页面上的那个）会在下次请求时
     * 被 {@link #current(Context, AdminDao)} 判为失效 —— 即使重置动作发生在另一个 JVM
     * （例如 {@code gradle dbadmin} 的 CLI 进程），也无需任何跨进程通知。
     *
     * <p><b>顺序是先 bump、再写哈希</b>：bump 失败则整个重置失败，库里的口令<b>完全没动</b>；
     * 而最坏情况也只是"会话被踢下线、口令没换成功"，安全方向是正确的。反过来先写哈希再
     * bump，一旦 bump 失败就会留下"旧会话仍有效、但没人知道新口令是否生效"的状态。
     *
     * <p><b>审计采用"动作成功后再写成功审计"：</b>避免旧实现"先写一条成功审计、再写库"
     * 在写库失败时留下一条实际上没发生的成功记录。任何失败写一条
     * {@code admin_password_reset_failed}，detail 里带 username 与失败原因。
     *
     * <p>交付方式与 {@link #bootstrapIfEmpty} 完全一致：明文落
     * {@code data/admin_initial_password.txt}，服务每次启动只要还有 must_change_pw=1
     * 的账号就会再提示一次该文件。
     *
     * @param actor 审计里的 {@code admin_name}：管理端操作记管理员名，CLI 记 {@code cli:DbAdminTool}
     * @return 新口令明文（已交付）；账号不存在或写库失败返回 null
     */
    public static String resetPassword(AdminDao dao, String username, String actor) {
        Map<String, Object> admin = dao.findByName(username);
        if (admin == null) {
            // 查无此人也是"一次失败的重置尝试"，留痕但不写成功审计
            dao.writeAudit(0, actor, "admin_password_reset_failed", "admin_users:?",
                    AdminAudit.json("username", username, "reason", "not_found"), "cli");
            return null;
        }
        long id = ((Number) admin.get("id")).longValue();
        String fresh = AdminSupport.generatePassword(14);
        // 先 bump：失败则口令不变，旧会话也不受影响（最坏只是没重置成功）
        if (!dao.bumpSessionEpoch(id)) {
            dao.writeAudit(0, actor, "admin_password_reset_failed", "admin_users:" + id,
                    AdminAudit.json("username", username,
                            "reason", "bump_session_epoch_failed"), "cli");
            return null;
        }
        if (!dao.resetPassword(id, hashPassword(fresh))) {
            // epoch 已经 +1：旧会话已被踢下线，但口令没换成 —— 让运维重试即可
            dao.writeAudit(0, actor, "admin_password_reset_failed", "admin_users:" + id,
                    AdminAudit.json("username", username,
                            "reason", "update_hash_failed"), "cli");
            return null;
        }
        dao.writeAudit(0, actor, "admin_password_reset", "admin_users:" + id,
                AdminAudit.json("username", username, "must_change_pw", true,
                        "epoch_bumped", true), "cli");
        deliverInitialPassword(username, fresh);
        return fresh;
    }

    /**
     * 已存在管理员时的启动提示。
     *
     * <p>要解决的尴尬场景：初始密码是随机生成的，只在首次启动打印一次。
     * 如果运维没抄下来、服务又重启了，控制台就再也拿不到密码了。
     * 所以只要 {@code admin_initial_password.txt} 还在，就在每次启动时重新提示一次 ——
     * 而文件会在管理员改密成功后被删除，改密之后就不再有任何明文留档。
     */
    private static void noteExistingAdmin(AdminDao dao) {
        java.nio.file.Path p = initialPasswordFile();
        if (!java.nio.file.Files.exists(p)) return;
        boolean stillInitial = dao.listAdmins().stream()
                .anyMatch(a -> a.get("must_change_pw") instanceof Number n && n.intValue() == 1);
        if (!stillInitial) {
            // 已改密但明文文件还在：删掉，避免遗留可用凭证
            try {
                java.nio.file.Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // 删不掉不影响安全（文件里是已经失效的旧密码）
            }
            return;
        }
        System.out.println();
        System.out.println("[ADMIN] 管理后台仍在使用初始密码，提示文件：" + p.toAbsolutePath());
        System.out.println("[ADMIN] 登录地址 /admin/login（登录后请立即改密，文件会自动删除）");
        System.out.println();
    }

    /**
     * 口令明文的一次性交付方式（用户 2g 让我判断）。首次初始化与运维重置
     * （{@code gradle dbadmin -Pcmd="admin-reset ... --apply"}）共用这一条链路，
     * 免得两条路径各写一份文件格式。
     *
     * <p>选择「随机生成 + 控制台打印 + 落一份 data/admin_initial_password.txt」，
     * 而不是写死常量。理由：
     * <ul>
     *   <li>写死密码 = 任何读到源码的人都持有管理端钥匙，且部署后几乎没人会改；</li>
     *   <li>随机密码若只打印在控制台，服务一重启控制台就滚没了，运维晚了就锁在门外；</li>
     *   <li>落盘到 data/（已被 .gitignore 的 {@code miyu-app/server-java/data/} 覆盖）
     *       让运维随时可查，同时不会进版本库。</li>
     * </ul>
     * 文件里同时写明"改密后本文件即失效"，并标记 must_change_pw=1：服务端对该账号
     * 强制改密 —— 未改密前只放行首页、改密接口与登出，其余管理功能一律 403
     * （见 {@code AdminHandler.adminFilter}）。
     */
    public static void deliverInitialPassword(String username, String password) {
        String content = String.join("\n",
                "# FamilyChat 管理后台 待改密口令（首次初始化或运维重置时生成）",
                "# 改密成功后请删除本文件；文件位于 data/，不会进版本库。",
                "username=" + username,
                "password=" + password,
                "") ;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(Database.DATA_DIR, "admin_initial_password.txt");
            java.nio.file.Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println("[ADMIN] 初始密码已写入 " + p.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ADMIN] 初始密码落盘失败（控制台仍可见）: " + e.getMessage());
        }
    }

    /** 提示文件路径（初始密码明文只存在于此，改密后即删除）。 */
    public static java.nio.file.Path initialPasswordFile() {
        return java.nio.file.Paths.get(Database.DATA_DIR, "admin_initial_password.txt");
    }

    /** 管理员改密成功后调用：删除残留的初始密码明文。 */
    public static void dropInitialPasswordFile() {
        try {
            java.nio.file.Files.deleteIfExists(initialPasswordFile());
        } catch (Exception e) {
            System.err.println("[ADMIN] 初始密码文件删除失败（请手工删除）: " + e.getMessage());
        }
    }

    private static void printBootstrapBanner(String username, String password) {
        deliverInitialPassword(username, password);
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  [ADMIN] 管理后台首次初始化，已生成管理员账号");
        System.out.println("================================================================");
        System.out.println("    用户名 : " + username);
        System.out.println("    初始密码 : " + password);
        System.out.println("    登录地址 : /admin/login");
        System.out.println("    提示文件 : data/admin_initial_password.txt");
        System.out.println("  首次登录后请立刻修改密码（未改密前只放行首页/改密/登出，其它功能会被拒绝）。");
        System.out.println("================================================================");
        System.out.println();
    }
}
