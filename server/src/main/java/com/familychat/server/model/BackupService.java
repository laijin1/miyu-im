package com.familychat.server.model;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 数据库自动备份：把单个 SQLite 文件定期复制成带时间戳的 {@code .bak} 快照。
 *
 * <p><b>为什么不直接文件复制</b>：库跑在 {@code journal_mode=WAL} 下（见
 * {@link Database#getConn}），主库文件之外还有 {@code -wal} / {@code -shm} 两个伴生文件，
 * 其中可能含着尚未 checkpoint 的已提交事务。任何对 {@code family_chat.db} 的裸复制
 * （Copy-Item / Files.copy）都可能拿到「少了最近写入」甚至「页撕裂」的副本，
 * 而且失败是静默的 —— 备份文件看起来一切正常，直到真拿它恢复才发现数据不对。
 *
 * <p>因此这里用 SQLite 自己的 {@code VACUUM INTO}：它在一个读事务里从数据库引擎内部
 * 导出，产出的是<b>单文件、已整理、事务一致</b>的完整库（WAL 内容一并合入），
 * 不需要停机、不影响在线读写（只是持有一个读快照）。
 *
 * <p><b>失败语义</b>：备份是旁路能力，任何异常都只能打日志，绝不能影响主进程 ——
 * 磁盘满、目标目录只读、库文件被搬走，都只意味着这一轮没有备份，服务照常跑。
 * 所有入口方法都自行吞掉异常，不向调用方抛出。
 *
 * <p><b>启动即备份</b>：定时任务的第一轮不是「6 小时后」而是「立刻」（见 {@link #start}），
 * 因为真正会丢数据的场景是机器/进程反复重启之间没有快照，重启即补一份能把这个窗口压到 0。
 */
public final class BackupService {

    private static final String TAG = "[BACKUP]";

    /** 备份目录：与库文件同盘同目录树下，便于整体搬迁/打包。 */
    public static final String BACKUP_DIR = Database.DATA_DIR + "/backup";

    /** 时间戳格式：YYYYMMDD-HHMMSS。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 保留份数：14 份 × 6 小时间隔 ≈ 3.5 天回溯窗口。 */
    public static final int RETAIN = 14;

    /** 备份间隔：6 小时。 */
    public static final long INTERVAL_HOURS = 6;

    /**
     * 剩余空间硬底线：低于这个值就不备份。
     *
     * <p>理由：VACUUM INTO 写的是<b>一整份</b>新库，写满磁盘会波及的不只是备份本身 ——
     * 主库的 WAL 也写不进去，那才是真正的服务不可用。宁可不备份，也不要把盘写爆。
     * 32MB 足够容纳本项目的库体量，同时留出 WAL 与系统临时文件的空间。
     */
    private static final long MIN_FREE_BYTES = 32L * 1024 * 1024;

    /** 同秒冲突时的文件名退避上限：同一秒内最多往前挪 5 秒。 */
    private static final int NAME_RETRY = 5;

    private static final Object LOCK = new Object();

    private static ScheduledExecutorService scheduler;

    private BackupService() {
    }

    // =================================================================
    //  定时调度
    // =================================================================

    /**
     * 启动自动备份：立刻备份一次，之后每 6 小时一次。
     *
     * <p>必须在 {@link Database#init()} 之后调用（库表已建好才有意义的快照）。
     * 重复调用是幂等的（只保留一个调度线程），线程为 daemon，不阻塞 JVM 退出。
     */
    public static void start() {
        synchronized (LOCK) {
            if (scheduler != null && !scheduler.isShutdown()) {
                System.out.println(TAG + " 已在运行，忽略重复启动");
                return;
            }
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "db-backup-scheduler");
                t.setDaemon(true);
                return t;
            };
            scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        }
        System.out.println(TAG + " 已启动：启动即备份一次，此后每 " + INTERVAL_HOURS + " 小时一次，"
                + "保留最近 " + RETAIN + " 份（目录 " + BACKUP_DIR + "）");
        // 启动即备份：同步先跑一次，让"进程刚起来还没到定时点"这段无备份窗口归零。
        // 同步而不是丢给调度线程，是因为这段时间正是最需要快照的时刻（崩溃/重启循环），
        // 不能又变成"取决于调度何时被唤醒"。备份失败只打日志，不抛异常。
        backupOnce();
        // 之后每 INTERVAL_HOURS 小时一次；单线程 executor + scheduleWithFixedDelay
        // 天然串行，不会出现两次备份并发争抢同名文件。
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                backupOnce();
            } catch (Throwable t) {
                // 双保险：backupOnce 内部已吞异常，这里防的是 Error 级别的意外
                System.err.println(TAG + " 未预期错误: " + t);
            }
        }, INTERVAL_HOURS, INTERVAL_HOURS, TimeUnit.HOURS);
    }

    /** 停止调度（供测试/优雅停机使用）。不抛异常。 */
    public static void stop() {
        synchronized (LOCK) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    // =================================================================
    //  备份实现
    // =================================================================

    /**
     * 用 {@code VACUUM INTO} 做一次一致性备份，并执行保留策略。
     *
     * <p>线程安全：同一时刻只允许一次备份（不同秒的时间戳虽然不同名，但并发备份会争抢
     * 磁盘与读快照，没有意义）。
     *
     * @return 备份文件；本轮未产出（无库文件 / 空间不足 / 出错）时返回 {@code null}
     */
    public static File backupOnce() {
        synchronized (LOCK) {
            try {
                File dir = new File(BACKUP_DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    System.err.println(TAG + " 无法创建备份目录: " + BACKUP_DIR);
                    return null;
                }

                File dbFile = new File(Database.DATA_DIR, "family_chat.db");
                if (!dbFile.isFile()) {
                    // 全新安装、库还没建出来：不是错误，跳过
                    System.out.println(TAG + " 跳过：数据库文件不存在 " + dbFile.getAbsolutePath());
                    return null;
                }

                if (!hasEnoughSpace(dir, dbFile.length())) {
                    return null;
                }

                File target = allocateTarget(dir);
                if (target == null) {
                    System.err.println(TAG + " 跳过：无法分配不冲突的备份文件名");
                    return null;
                }

                // VACUUM INTO 的目标文件必须不存在（存在会直接报错），这也是为什么上面要退避
                String sql = "VACUUM INTO '" + target.getAbsolutePath().replace('\\', '/') + "'";
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                     Statement s = c.createStatement()) {
                    s.execute(sql);
                }

                if (!target.isFile() || target.length() == 0) {
                    System.err.println(TAG + " 失败：备份产物为空 " + target.getName());
                    target.delete();
                    return null;
                }

                System.out.println(TAG + " 完成 " + target.getName()
                        + " (" + target.length() + " bytes, 源库 " + dbFile.length() + " bytes)");
                pruneOld();
                return target;
            } catch (Throwable t) {
                // 备份失败绝不影响主进程：只记日志
                System.err.println(TAG + " 失败: " + t);
                return null;
            }
        }
    }

    /**
     * 保留策略：按文件名时间戳排序，只留最新 {@link #RETAIN} 份，多余的最旧优先删除。
     *
     * <p>只删本服务自己产出的 {@code familychat.db.*.bak}，目录里别的东西
     * （例如运维手工放的 {@code .pre-orphan-cleanup.bak}、钱包对账导出）一律不动 ——
     * 保留策略没有资格处置不是它写的文件。
     *
     * @return 被删除的份数
     */
    static int pruneOld() {
        File dir = new File(BACKUP_DIR);
        List<File> backups = listBackups(dir);
        if (backups.size() <= RETAIN) {
            return 0;
        }
        int deleted = 0;
        // listBackups 已按时间戳升序（最旧在前）
        for (int i = 0; i < backups.size() - RETAIN; i++) {
            File old = backups.get(i);
            if (old.delete()) {
                deleted++;
                System.out.println(TAG + " 保留策略：删除最旧 " + old.getName());
            } else {
                System.err.println(TAG + " 保留策略：删除失败 " + old.getName());
            }
        }
        return deleted;
    }

    /**
     * 本服务产出的备份文件名（严格匹配）。用正则而不是 startsWith/endsWith：
     * 手工备份（{@code familychat.db.20260921-221316.pre-orphan-cleanup.bak}）与之同前缀同后缀，
     * 但多了一段后缀，长度/形态都不符 —— 它不该被保留策略当作可删对象。
     */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^familychat\\.db\\.\\d{8}-\\d{6}\\.bak$");

    /** 列出本服务产出的备份文件，按文件名时间戳升序（最旧在前）。 */
    static List<File> listBackups(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            // 时间戳格式定长固定，字典序即时间序，无需解析日期即可排序。
            // 只认自己写的名字：保留策略没有资格处置别的文件。
            if (f.isFile() && NAME_PATTERN.matcher(f.getName()).matches()) {
                out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** 备份前检查磁盘空间：VACUUM INTO 要写出完整一份新库。 */
    private static boolean hasEnoughSpace(File dir, long sourceBytes) {
        try {
            long usable = dir.getUsableSpace();
            // 需要放下整份新库，再留 MIN_FREE_BYTES 的余量
            if (usable > 0 && usable < sourceBytes + MIN_FREE_BYTES) {
                System.err.println(TAG + " 跳过：磁盘空间不足 usable=" + usable
                        + " required=" + (sourceBytes + MIN_FREE_BYTES));
                return false;
            }
            return true;
        } catch (Throwable t) {
            // 查询失败不该阻止备份，放行
            System.err.println(TAG + " 磁盘空间检查失败（放行）: " + t);
            return true;
        }
    }

    /** 生成不冲突的备份目标文件名；同一秒内多次调用则退避 1 秒重试。 */
    private static File allocateTarget(File dir) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i <= NAME_RETRY; i++) {
            File candidate = new File(dir,
                    "familychat.db." + now.plusSeconds(i).format(STAMP) + ".bak");
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    /** 供运维/测试观察当前备份清单（升序，最旧在前）。 */
    public static List<String> listBackupNames() {
        List<String> names = new ArrayList<>();
        for (File f : listBackups(new File(BACKUP_DIR))) {
            names.add(f.getName());
        }
        return names;
    }

    // =================================================================
    //  手动入口（供 harness / 运维工具调用，不经 HTTP）
    // =================================================================

    /**
     * 命令行手动备份入口，<b>不注册任何 HTTP 路由、不进管理后台</b>。
     * 直接跑（需要把 sqlite-jdbc / slf4j 放在 classpath 上）：
     * <pre>
     * java -Dfamilychat.data.dir=&lt;数据目录&gt; -cp &lt;runtimeClasspath&gt; \
     *      com.familychat.server.model.BackupService
     * </pre>
     * 冒烟测试/运维排障用；日常备份走 {@link #start()} 的定时任务，不需要本入口。
     *
     * <p>退出码：0 = 备份成功；1 = 未产出备份（原因已打日志）。
     */
    public static void main(String[] args) {
        System.out.println(TAG + " 手动备份，数据目录 " + Database.DATA_DIR);
        File out = backupOnce();
        if (out == null) {
            System.err.println(TAG + " 手动备份未产出文件");
            System.exit(1);
        }
        System.out.println(TAG + " 手动备份完成: " + out.getAbsolutePath());
        List<String> all = listBackupNames();
        System.out.println(TAG + " 当前备份清单（" + all.size() + " 份）:");
        for (String n : all) {
            System.out.println("  " + n);
        }
        System.exit(0);
    }

}
