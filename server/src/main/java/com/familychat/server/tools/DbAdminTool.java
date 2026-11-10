package com.familychat.server.tools;

import com.familychat.server.admin.AdminAuth;
import com.familychat.server.admin.AdminDao;
import com.familychat.server.model.Database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维用命令行工具（不进 HTTP 路由，不随服务启动）。
 *
 * <p>用途：把"改数据/清数据"这类一次性运维动作从手工拼 SQL 变成可复跑、
 * 可审计的代码，避免每次手敲 sqlite3 时漏表或写错 WHERE。
 *
 * <p>连接方式与线上服务完全一致（同一个 {@link Database#getConn()}，
 * 同一份 PRAGMA），所以行为不会因工具/服务的差异而分叉。
 *
 * <p>用法（在 server-java 目录下）：
 * <pre>
 *   gradle dbadmin -Pcmd="wallet-reset"                 # 只打印将要执行的动作，不写库
 *   gradle dbadmin -Pcmd="wallet-reset --apply"         # 真正执行（事务内）
 *   gradle dbadmin -Pcmd="schema"                       # 打印全部表与行数
 *   gradle dbadmin -Pcmd="admin-reset admin --apply" # 忘记管理后台密码时重置（见 adminReset）
 *   gradle dbadmin -Pcmd="admin-reset --username admin --apply" # 显式用户名；用户名以 -- 开头时必须用这种写法
 * </pre>
 *
 * <p>破坏性动作一律默认 dry-run：必须显式带 --apply 才会落盘。
 */
public final class DbAdminTool {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 备份目录：与 sqlite3 .backup 产物放在一起，便于人工对账。 */
    private static final Path BACKUP_DIR = Paths.get(Database.DATA_DIR, "backup");

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "" : args[0];
        boolean apply = false;
        for (String a : args) {
            if ("--apply".equals(a)) apply = true;
        }
        System.out.println("[DBADMIN] data_dir = " + Database.DATA_DIR);
        System.out.println("[DBADMIN] cmd = " + cmd + (apply ? " (APPLY)" : " (dry-run)"));

        Database db = new Database();
        // 与线上服务一致地收尾：退出前做 WAL checkpoint（TRUNCATE），把 -wal/-shm 合并回主库。
        // 不做的话，工具跑完会在数据目录留下 family_chat.db-wal/-shm；后续任何"删主库文件"
        // 的清理动作都会因为这两个残留文件而失效（下次 open 时它们会把旧库内容重放回来），
        // 测试环境里表现为 Database 已重置却还能读到上一个用例的数据。
        Runtime.getRuntime().addShutdownHook(new Thread(db::close, "dbadmin-close"));
        // 这些命令先跑一次幂等 init：保证新表/新列（admin_users、mall_escrow、users.is_disabled…）
        // 已经存在，否则老库上对账/统计会因为 "no such table" 直接失败；admin-reset 也需要
        // admin_users 存在，否则 findByName 会把 "no such table" 吞成"查无此人"。
        // init 的主体是 CREATE TABLE IF NOT EXISTS / ALTER ADD COLUMN，另有幂等种子与回填 UPDATE；
        // 其中不含任何 DELETE/DROP，且与服务每次启动（FamilyChatServer 调 db.init()）是同一段代码。
        switch (cmd) {
            case "schema", "counts", "escrow-audit", "admin-reset" -> {
                System.out.println("[DBADMIN] 先应用幂等 schema 初始化（建表/补列 + 幂等种子，无删除）");
                db.init();
            }
            default -> { /* 破坏性命令不需要先建表 */ }
        }
        switch (cmd) {
            case "schema" -> schema(db);
            case "wallet-reset" -> new WalletReset(db, apply).run();
            case "counts" -> counts(db);
            case "admin-reset" -> adminReset(db, args, apply);
            case "escrow-audit" -> escrowAudit(db);
            case "orphan-scan" -> orphan(db, true, args);
            case "orphan-cleanup" -> orphan(db, !apply, args);            case "backfill-wallet-reset-audit" ->
                    backfillWalletResetAudit(db, java.util.Arrays.asList(args).contains("--dry-run"));
            case "page-dump" -> pageDump();
            default -> {
                System.err.println("[DBADMIN] unknown cmd: '" + cmd + "'");
                System.err.println("  available: schema | counts | admin-reset | wallet-reset | escrow-audit " +
                        "| orphan-scan | orphan-cleanup | backfill-wallet-reset-audit | page-dump");
                System.exit(2);
            }
        }
        System.out.println("[DBADMIN] done");
    }

    // ═══════════════════════════ 子命令 ═══════════════════════════

    /** 打印所有用户表的行数，用于清空/迁移前后对账。 */
    static void schema(Database db) throws SQLException {
        List<String> tables = listTables(db);
        System.out.printf("%-28s %s%n", "TABLE", "ROWS");
        for (String t : tables) {
            System.out.printf("%-28s %d%n", t, count(db, t));
        }
        System.out.println("total tables = " + tables.size());
    }

    static void counts(Database db) throws SQLException {
        System.out.println("--- 钱相关 ---");
        System.out.println("wallets 行数        = " + count(db, "wallets"));
        System.out.println("wallets 非零余额行数 = " + scalar(db,
                "SELECT COUNT(*) FROM wallets WHERE balance<>0"));
        System.out.println("wallets 余额合计(分) = " + scalar(db,
                "SELECT COALESCE(SUM(balance),0) FROM wallets"));
        System.out.println("transactions 行数   = " + count(db, "transactions"));
        System.out.println("charge_requests 行数 = " + count(db, "charge_requests"));
        System.out.println("--- 订单 ---");
        for (String s : List.of("pending", "paid", "shipped", "completed", "cancelled", "refunded")) {
            System.out.printf("mall_orders status=%-10s = %d%n", s,
                    scalar(db, "SELECT COUNT(*) FROM mall_orders WHERE status='" + s + "'"));
        }
    }

    // ═══════════════════════════ 管理员口令重置 ═══════════════════════════

    /**
     * 忘记管理后台密码时用：换一串随机新口令，并保证"抄得下来、下次必须改"。
     *
     * <p>真实动作全在 {@link AdminAuth#resetPassword}（写审计 → 换哈希 → 置 must_change_pw
     * → 落 data/admin_initial_password.txt），这里只负责选人、确认和把结果打在控制台上。
     *
     * <p>用法（两种写法等价；<b>用户名本身以 {@code --} 开头时只能用 --username</b>，
     * 否则会被参数扫描当成未知开关吞掉）：
     * <pre>
     *   gradle dbadmin -Pcmd="admin-reset admin --apply"
     *   gradle dbadmin -Pcmd="admin-reset --username admin --apply"
     * </pre>
     * 两种写法同时出现、同一写法给多个用户名、或 --username 后面缺值，一律打印用法并 exit 2。
     * 与其它破坏性命令一样默认 dry-run —— 重置会让当前管理员立刻掉线，
     * 用户名先看清楚（忘密码的人常常连用户名也记岔）。
     */
    static void adminReset(Database db, String[] args, boolean apply) {
        AdminDao dao = new AdminDao(db);
        String byFlag = null;
        String positional = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--username".equals(a)) {
                // --username 后紧跟的值即用户名（允许以 -- 开头，供特殊用户名使用）；
                // 但下一个 token 若就是已知开关 --apply，说明用户名漏写了。
                if (i + 1 >= args.length || "--apply".equals(args[i + 1])) {
                    System.err.println("[DBADMIN] --username 后面缺少用户名");
                    adminResetUsage(dao);
                }
                if (byFlag != null) {
                    System.err.println("[DBADMIN] --username 只能出现一次");
                    adminResetUsage(dao);
                }
                byFlag = args[++i];
            } else if (!a.startsWith("--")) {
                if (positional != null) {
                    System.err.println("[DBADMIN] 一次只能重置一个用户名: '"
                            + positional + "' / '" + a + "'");
                    adminResetUsage(dao);
                }
                positional = a;
            }
        }
        if (byFlag != null && positional != null) {
            System.err.println("[DBADMIN] 位置参数写法与 --username 不能同时使用");
            adminResetUsage(dao);
        }
        // --username 优先级最高；两种写法都没给才算缺参。
        String username = byFlag != null ? byFlag : positional;
        if (username == null) {
            adminResetUsage(dao);
        }
        Map<String, Object> admin = dao.findByName(username);
        if (admin == null) {
            System.err.println("[DBADMIN] 没有这个管理员: '" + username + "'");
            System.err.println("[DBADMIN] 现有管理员: " + adminNames(dao));
            if (dao.countAdmins() == 0) {
                System.err.println("[DBADMIN] 库里没有任何管理员：启动一次服务会自动生成一个（bootstrap）");
            }
            System.exit(3);
        }
        long id = ((Number) admin.get("id")).longValue();
        if (!apply) {
            System.out.println("[DBADMIN] 将重置 id=" + id + " username=" + username
                    + " disabled=" + admin.get("disabled")
                    + " must_change_pw=" + admin.get("must_change_pw"));
            System.out.println("[DBADMIN] dry-run：账号无误再加 --apply 真正执行");
            return;
        }
        // 禁用标记必须在重置前读：resetPassword 只换哈希、不动 disabled 列。
        boolean disabled = admin.get("disabled") instanceof Number n && n.intValue() == 1;
        String fresh = AdminAuth.resetPassword(dao, username, "cli:DbAdminTool");
        if (fresh == null) {
            System.err.println("[DBADMIN] 重置失败（原因见上面的 [ADMIN] 日志）");
            System.exit(4);
        }
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  管理后台口令已重置（原密码立即失效）");
        System.out.println("================================================================");
        if (disabled) {
            System.out.println("  ⚠️ 该管理员已被禁用，重置后仍无法登录（需先在管理端启用）。");
        }
        System.out.println("    用户名   : " + username);
        System.out.println("    新密码   : " + fresh);
        System.out.println("    登录地址 : /admin/login");
        System.out.println("    提示文件 : data/admin_initial_password.txt");
        System.out.println("  登录后会强制改密，改密成功后该文件自动删除。");
        // 登录失败锁定是 AdminAuth.LOGIN_FAILS（static，按来源 IP 计数，仅存在于服务端 JVM 内存）。
        // CLI 是另一个 JVM，既读不到也清不掉它；重置口令同样不解锁，只能等过期或重启服务。
        System.out.println("  提示：若因多次登录失败被锁定，重置不会解除锁定（失败计数在服务端内存，");
        System.out.println("        等锁自然过期（最长 15 分钟）或重启服务即可恢复）。");
        System.out.println("================================================================");
        System.out.println();
    }

    /** 打印 admin-reset 用法与现有管理员，然后 exit 2（所有参数歧义共用这一条出口）。 */
    private static void adminResetUsage(AdminDao dao) {
        System.err.println("[DBADMIN] 用法: gradle dbadmin -Pcmd=\"admin-reset <用户名> --apply\"");
        System.err.println("[DBADMIN]       gradle dbadmin -Pcmd=\"admin-reset --username <用户名> --apply\"");
        System.err.println("[DBADMIN] 现有管理员: " + adminNames(dao));
        System.exit(2);
    }

    /** 现有管理员用户名，供 dry-run 与输错用户名时对照。 */
    private static String adminNames(AdminDao dao) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> a : dao.listAdmins()) names.add(String.valueOf(a.get("username")));
        return names.isEmpty() ? "(无)" : String.join(", ", names);
    }

    // ═══════════════════════════ 第 1 块：清空钱包 + 流水 ═══════════════════════════

    /**
     * 清空钱包余额与全部资金流水。
     *
     * <p>范围（由用户 2026-09 决策确认）：
     * <ul>
     *   <li>{@code wallets.balance} → 0，<b>保留行</b>与 pay_hash/pay_salt/pay_set。
     *       支付密码不是钱，清掉会让所有用户被迫重设，故不动。</li>
     *   <li>{@code transactions} 全表 DELETE（76 行资金流水）。</li>
     *   <li>{@code mall_orders} 脏金额归位：paid→pending、refunded→cancelled、
     *       shipped+refund_status=rejected→pending。理由：订单本金在 payOrder 时
     *       已从买家余额扣走；钱包归零后这些"已扣款"订单无法再退款（余额 0），
     *       必须把 status 退回可操作态，否则用户的 881,570 分悬空。
     *       同时清空 paid_at/shipped_at/refund_time/refund_status。</li>
     *   <li>不动 {@code charge_requests}（用户明确未选）。</li>
     * </ul>
     */
    static final class WalletReset {
        private final Database db;
        private final boolean apply;

        WalletReset(Database db, boolean apply) {
            this.db = db;
            this.apply = apply;
        }

        void run() throws SQLException, IOException {
            long walletsBefore = count(db, "wallets");
            long balanceSumBefore = scalar(db, "SELECT COALESCE(SUM(balance),0) FROM wallets");
            long nonZeroBefore = scalar(db, "SELECT COUNT(*) FROM wallets WHERE balance<>0");
            long txBefore = count(db, "transactions");
            String orderDistBefore = orderDistribution(db);

            System.out.println("=== 清空前 ===");
            System.out.println("wallets 行数          = " + walletsBefore);
            System.out.println("wallets 非零余额行数   = " + nonZeroBefore);
            System.out.println("wallets 余额合计(分)   = " + balanceSumBefore);
            System.out.println("transactions 行数     = " + txBefore);
            System.out.println("mall_orders 分布      = " + orderDistBefore);

            String[] steps = {
                "UPDATE wallets SET balance=0, updated_at=?",
                "DELETE FROM transactions",
                "UPDATE mall_orders SET status='pending', paid_at=0, shipped_at=0, " +
                        "shipping_company='', tracking_no='', cancel_time=0 " +
                        "WHERE status IN ('paid','shipped') AND COALESCE(refund_status,'')<>'approved'",
                "UPDATE mall_orders SET status='cancelled', refund_time=? " +
                        "WHERE status='refunded' OR refund_status='approved'",
                "UPDATE mall_orders SET refund_status='', refund_reason='', refund_time=0 " +
                        "WHERE refund_status<>''",
            };
            System.out.println("=== 将执行 ===");
            for (String s : steps) System.out.println("  " + s);

            if (!apply) {
                System.out.println("=== dry-run：未写入任何数据（加 --apply 才执行）===");
                return;
            }

            long now = System.currentTimeMillis();
            try (Connection c = db.getConn()) {
                c.setAutoCommit(false);
                try {
                    int u1;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE wallets SET balance=0, updated_at=?")) {
                        ps.setLong(1, now);
                        u1 = ps.executeUpdate();
                    }
                    int u2;
                    try (Statement s = c.createStatement()) {
                        u2 = s.executeUpdate("DELETE FROM transactions");
                    }
                    int u3;
                    try (Statement s = c.createStatement()) {
                        u3 = s.executeUpdate(
                                "UPDATE mall_orders SET status='pending', paid_at=0, shipped_at=0, " +
                                        "shipping_company='', tracking_no='', cancel_time=0 " +
                                        "WHERE status IN ('paid','shipped') " +
                                        "AND COALESCE(refund_status,'')<>'approved'");
                    }
                    int u4;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE mall_orders SET status='cancelled', refund_time=? " +
                                    "WHERE status='refunded' OR refund_status='approved'")) {
                        ps.setLong(1, now);
                        u4 = ps.executeUpdate();
                    }
                    int u5;
                    try (Statement s = c.createStatement()) {
                        u5 = s.executeUpdate(
                                "UPDATE mall_orders SET refund_status='', refund_reason='', refund_time=0 " +
                                        "WHERE refund_status<>'' AND status<>'refunded'");
                    }
                    c.commit();
                    System.out.println("=== 已提交 ===");
                    System.out.printf("wallets 归零行数=%d, transactions 删除=%d, 订单回退pending=%d, " +
                            "订单置cancelled=%d, 退款标记清理=%d%n", u1, u2, u3, u4, u5);
                } catch (SQLException e) {
                    c.rollback();
                    System.err.println("[DBADMIN] 回滚：" + e.getMessage());
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }

            String orderDistAfter = orderDistribution(db);
            long walletsAfter = count(db, "wallets");
            long balanceSumAfter = scalar(db, "SELECT COALESCE(SUM(balance),0) FROM wallets");
            long nonZeroAfter = scalar(db, "SELECT COUNT(*) FROM wallets WHERE balance<>0");
            long txAfter = count(db, "transactions");

            System.out.println("=== 清空后 ===");
            System.out.println("wallets 行数          = " + walletsAfter);
            System.out.println("wallets 非零余额行数   = " + nonZeroAfter);
            System.out.println("wallets 余额合计(分)   = " + balanceSumAfter);
            System.out.println("transactions 行数     = " + txAfter);
            System.out.println("mall_orders 分布      = " + orderDistAfter);

            boolean ok = nonZeroAfter == 0 && balanceSumAfter == 0 && txAfter == 0;
            System.out.println("[DBADMIN] 验证：" + (ok ? "PASS（非零余额=0 且流水=0）"
                    : "FAIL"));

            Files.createDirectories(BACKUP_DIR);
            String stamp = LocalDateTime.now().format(TS);
            String report = "wallet-reset " + stamp + "\n"
                    + "wallets_rows_before=" + walletsBefore + "\n"
                    + "wallets_nonzero_before=" + nonZeroBefore + "\n"
                    + "wallets_balance_sum_before=" + balanceSumBefore + "\n"
                    + "transactions_before=" + txBefore + "\n"
                    + "mall_orders_before=" + orderDistBefore + "\n"
                    + "wallets_rows_after=" + walletsAfter + "\n"
                    + "wallets_nonzero_after=" + nonZeroAfter + "\n"
                    + "wallets_balance_sum_after=" + balanceSumAfter + "\n"
                    + "transactions_after=" + txAfter + "\n"
                    + "mall_orders_after=" + orderDistAfter + "\n"
                    + "result=" + (ok ? "PASS" : "FAIL") + "\n";
            Path out = BACKUP_DIR.resolve("wallet-reset-" + stamp + ".txt");
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println("[DBADMIN] 报告写入 " + out);

            if (!ok) System.exit(3);
        }
    }

    // ═══════════════════════════ 孤儿数据（审计第 5 条） ═══════════════════════════

    /**
     * 存量孤儿数据扫描 / 一次性清理。
     *
     * <p>{@code orphan-scan} 永远只读；{@code orphan-cleanup} 默认也是 dry-run，
     * 必须显式 {@code --apply} 才会删。
     *
     * <p>清理由 {@link com.familychat.server.model.dao.GroupMaintenance} 实现，
     * 与线上删群文件/退群联动用的是同一套判定，避免"CLI 能删、线上删不掉"的分叉。
     */
    static void orphan(Database db, boolean dryRun, String... argsOf) throws SQLException {
        System.out.println("=== 孤儿数据" + (dryRun ? "扫描（只读）" : "清理（写库）") + " ===");
        com.familychat.server.model.dao.GroupMaintenance gm =
                new com.familychat.server.model.dao.GroupMaintenance(db);
        java.util.Map<String, Object> report = gm.sweep(dryRun, 200);
        System.out.println("库内孤儿统计: " + report.get("dbOrphanCounts"));
        System.out.println("库内孤儿合计: " + report.get("dbOrphanTotal"));
        if (!dryRun) System.out.println("已清理行数: " + report.get("dbOrphanCleaned"));
        System.out.println("磁盘未引用文件数: " + report.get("unreferencedFileCount")
                + "，体积(字节): " + report.get("unreferencedBytes"));
        System.out.println("chat_files 目录: " + report.get("chatDir"));
        Object listed = report.get("unreferencedFilesListed");
        if (listed instanceof java.util.List<?> l && !l.isEmpty()) {
            System.out.println("未引用文件（最多 200 个）:");
            for (Object n : l) System.out.println("  " + n);
        }
        if (report.containsKey("error")) {
            System.err.println("[DBADMIN] 失败: " + report.get("error"));
            System.exit(5);
        }
        if (!dryRun) {
            // 库内孤儿清完后，再回收磁盘上仍然无引用的文件。
            // ⚠️ 这一步是"危险动作"：App 上传附件的真实 URL 从未落库（见 isFileReferenced 的
            //    注释），所以"库里查不到引用"不足以证明文件没在用。默认不删，必须 --delete-files。
            boolean deleteFiles = false;
            for (String a : argsOf) if ("--delete-files".equals(a)) deleteFiles = true;
            if (!deleteFiles) {
                System.out.println("[DBADMIN] 磁盘文件未删除（安全默认）。");
                System.out.println("[DBADMIN] 已知局限：App 附件 URL 不入库，"
                        + "\"未引用\"≠\"未使用\"；只有在你确知这些文件是垃圾时才加 --delete-files");
                return;
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            java.io.File dir = new java.io.File(Database.DATA_DIR, "chat_files");
            java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File f : files) if (f.isFile()) names.add(f.getName());
            java.util.Map<String, Object> disk = gm.reclaimFiles(names, false);
            System.out.println("磁盘实际删除: " + disk.get("deleted")
                    + "，跳过(仍被引用): " + disk.get("skippedReferenced")
                    + "，释放字节: " + disk.get("bytesFreed"));
        } else {
            System.out.println("（未删除任何文件；加 --apply 才真正清理库内孤儿行）");
        }
    }

    // ═══════════════════════════ 托管对账（审计第 4 条） ═══════════════════════════

    /**
     * 托管账目自检（只读）。
     *
     * <p>托管模型里钱守恒的判据是：<b>托管池余额 == 托管明细金额合计</b>。
     * 池子在 {@code wallets(user_id=0)}，明细在 {@code mall_escrow}；
     * 两者由同一事务维护，一旦不等就说明有人绕过 DAO 直接改库，
     * 或者历史上有失败的事务留下了半成品 —— 必须人工介入，不能自动"抹平"。
     *
     * <p>同时检查两类结构性不一致：
     * <ul>
     *   <li>有明细但订单已不是托管状态（钱放不出去/放重了）；</li>
     *   <li>订单标记 escrow=1 但没有明细（放款时会拿不到金额）。</li>
     * </ul>
     */
    static void escrowAudit(Database db) throws SQLException {
        long pool = scalar(db, "SELECT COALESCE(SUM(balance),0) FROM wallets WHERE user_id=0");
        long detailSum = scalar(db, "SELECT COALESCE(SUM(amount),0) FROM mall_escrow");
        long detailCount = count(db, "mall_escrow");
        long flagged = scalar(db, "SELECT COUNT(*) FROM mall_orders WHERE escrow=1");
        long danglingOrders = scalar(db,
                "SELECT COUNT(*) FROM mall_orders o WHERE o.escrow=1 AND NOT EXISTS " +
                        "(SELECT 1 FROM mall_escrow e WHERE e.order_id=o.id)");
        long orphanDetail = scalar(db,
                "SELECT COUNT(*) FROM mall_escrow e WHERE NOT EXISTS " +
                        "(SELECT 1 FROM mall_orders o WHERE o.id=e.order_id)");
        long allWallets = scalar(db, "SELECT COALESCE(SUM(balance),0) FROM wallets");

        System.out.println("=== 托管账目自检 ===");
        System.out.println("托管池余额(分)        = " + pool);
        System.out.println("托管明细合计(分)      = " + detailSum);
        System.out.println("托管明细条数          = " + detailCount);
        System.out.println("订单 escrow=1 条数    = " + flagged);
        System.out.println("有标记但无明细的订单  = " + danglingOrders);
        System.out.println("有明细但无订单的孤儿  = " + orphanDetail);
        System.out.println("wallets 余额总额(分)  = " + allWallets + "（含托管池与系统账户）");

        boolean ok = pool == detailSum && danglingOrders == 0 && orphanDetail == 0;
        if (pool != detailSum) {
            System.err.println("[DBADMIN] ✗ 托管池与明细不一致，差额 = " + (pool - detailSum)
                    + " 分（池 > 明细 = 有笔钱不知属于哪单；池 < 明细 = 可能重复放款）");
        }
        if (danglingOrders > 0) {
            System.err.println("[DBADMIN] ✗ " + danglingOrders + " 个订单标记托管但无明细，"
                    + "确认收货时会拿不到金额（releaseEscrowToSeller 会静默跳过）");
        }
        if (orphanDetail > 0) {
            System.err.println("[DBADMIN] ✗ " + orphanDetail + " 条托管明细指向不存在的订单");
        }
        System.out.println("[DBADMIN] 结论：" + (ok ? "PASS" : "FAIL（需人工排查，工具不会自动改账）"));
        if (!ok) System.exit(4);
    }

    // ═══════════════════════════ 审计补录 ═══════════════════════════

    /**
     * 补录第 1 块"清空钱包/流水"的审计记录。
     *
     * <p>背景：那次清空发生在管理后台上线<b>之前</b>，admin_audit 表当时还不存在，
     * 所以没有审计行。硬约束要求所有写操作进审计，这里把已经发生的事实补登进去。
     *
     * <p>写成固定子命令而不是通用 {@code audit-log <action> <target> <detail>}：
     * 详情里含 JSON 的引号和大括号，经 gradle -> PowerShell -> 进程参数多层传递会被
     * 引号规则吃掉（实测两次都变成 dry-run 或参数丢失）。固定文案反而更可靠也更可审计：
     * 这条记录描述的就是一次具体的历史事件，不需要通用性。
     *
     * <p>--apply 默认开启（补录本身只是写一条日志）；带 --dry-run 可只打印。
     */
    static void backfillWalletResetAudit(Database db, boolean dryRun) throws SQLException {
        String detail = "{\"scope\":\"wallets+transactions+mall_orders\","
                + "\"wallets_rows\":19,\"wallets_nonzero_before\":1,\"wallets_nonzero_after\":0,"
                + "\"balance_sum_fen_before\":2400,\"balance_sum_fen_after\":0,"
                + "\"transactions_before\":76,\"transactions_after\":0,"
                + "\"mall_orders_paid_to_pending\":5,\"mall_orders_shipped_to_pending\":1,"
                + "\"mall_orders_refunded_to_cancelled\":3,"
                + "\"backup\":\"data/backup/familychat.db.20260921-213902.bak\","
                + "\"note\":\"管理后台上线前执行，故补录；clients must be restarted to pick up new schema\"}";
        System.out.println("=== 补录 wallet-reset 审计 ===");
        System.out.println("detail = " + detail);
        if (dryRun) {
            System.out.println("（--dry-run，未写入）");
            return;
        }
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_audit (admin_id,admin_name,action,target,detail,created_at,ip) " +
                             "VALUES (0,'cli-backfill','wallet_reset_by_cli','wallets+transactions',?,?,?)")) {
            ps.setString(1, detail);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, "cli:DbAdminTool");
            ps.executeUpdate();
        }
        System.out.println("[DBADMIN] 已补录，admin_audit 行数 = " + count(db, "admin_audit"));
    }

    // ═══════════════════════════ 页面自检 ═══════════════════════════

    /**
     * 把内嵌的管理页 HTML 落盘，便于在不起服务的前提下做语法/结构自检
     * （例如用 node --check 验证内联 JS，或在浏览器里直接打开看样式）。
     */
    static void pageDump() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(Database.DATA_DIR, "..", "build", "page-dump")
                .normalize();
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path login = dir.resolve("login.html");
        java.nio.file.Path shell = dir.resolve("shell.html");
        java.nio.file.Files.writeString(login,
                com.familychat.server.admin.AdminPages.loginPage(null, false, 0),
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(shell,
                com.familychat.server.admin.AdminPages.shell("admin", false),
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("[DBADMIN] 已导出 " + login + " 和 " + shell);
    }

    // ═══════════════════════════ 通用助手 ═══════════════════════════

    private static List<String> listTables(Database db) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = db.getConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' " +
                             "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static long count(Database db, String table) throws SQLException {
        return scalar(db, "SELECT COUNT(*) FROM \"" + table + "\"");
    }

    private static long scalar(Database db, String sql) throws SQLException {
        try (Connection c = db.getConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static String orderDistribution(Database db) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection c = db.getConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT status,refund_status,COUNT(*),COALESCE(SUM(amount),0) FROM mall_orders " +
                             "GROUP BY status,refund_status ORDER BY status")) {
            while (rs.next()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(rs.getString(1)).append('/').append(rs.getString(2))
                        .append(":n=").append(rs.getInt(3))
                        .append(",amt=").append(rs.getLong(4));
            }
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    private DbAdminTool() {
    }
}
