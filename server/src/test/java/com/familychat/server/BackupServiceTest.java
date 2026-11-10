package com.familychat.server;

import com.familychat.server.model.BackupService;
import com.familychat.server.model.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库自动备份：产物必须是「能用的一致性快照」，保留策略必须有界。
 *
 * <p>为什么这两条必须被锁住：
 * <ul>
 *   <li><b>一致性</b> —— 库跑在 WAL 模式，最近一次提交可能还只在 {@code -wal} 里。
 *       裸复制主库文件会得到一个"看起来正常但少了最近写入"的副本。这里直接写一行、
 *       备份、再从备份里把那行读回来，确保走的是引擎级导出（VACUUM INTO）而不是文件拷贝。</li>
 *   <li><b>有界</b> —— 定时任务无人值守地跑，保留策略一旦失效就是磁盘被自己填满，
 *       而磁盘满会连带主库 WAL 写不进去。所以必须断言：产物数封顶 RETAIN，
 *       且策略不碰任何不是它自己写的文件。</li>
 * </ul>
 *
 * <p>测试数据目录由 gradle test 注入（familychat.data.dir=build/test-data），
 * 绝不触碰真实 data/。
 */
class BackupServiceTest extends DbTestSupport {

    private static final File BACKUP_DIR = new File(BackupService.BACKUP_DIR);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @AfterEach
    void cleanBackups() {
        BackupService.stop();
        File[] files = BACKUP_DIR.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    /** 备份产物必须存在、命名合规，并且是能打开的 SQLite 库。 */
    @Test
    void backupProducesReadableSqliteSnapshot() throws Exception {
        // 先写入一行：这行很可能只在 WAL 里（这就是裸复制会丢的东西）
        int uid = register("backup_probe");
        db.updateUser(uid, "nickname", "备份探针");

        File out = BackupService.backupOnce();
        assertNotNull(out, "备份应产出文件");
        assertTrue(out.isFile(), "备份文件应存在");
        assertTrue(out.length() > 0, "备份文件不应为空");

        // 命名：familychat.db.YYYYMMDD-HHMMSS.bak
        assertTrue(out.getName().matches("^familychat\\.db\\.\\d{8}-\\d{6}\\.bak$"),
                "备份文件名格式不合规: " + out.getName());
        assertEquals(BACKUP_DIR.getAbsolutePath(), out.getParentFile().getAbsolutePath(),
                "备份必须落在 data/backup/");

        // 文件头是 SQLite 的 magic（"SQLite format 3\0"）
        byte[] header = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(out, "r")) {
            raf.readFully(header);
        }
        assertArrayEquals("SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII), header,
                "备份产物不是合法的 SQLite 文件");

        // 真正打开它，确认 WAL 里的那行也在（一致性）
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out.getAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT nickname FROM users WHERE id = " + uid)) {
            assertTrue(rs.next(), "备份里找不到刚写入的用户 —— 备份不是一致快照");
            assertEquals("备份探针", rs.getString(1));
        }
    }

    /** 保留策略：超过 RETAIN 份时删最旧的，并且只动自己产出的文件。 */
    @Test
    void retentionKeepsNewestNOnly() throws Exception {
        assertTrue(BACKUP_DIR.isDirectory() || BACKUP_DIR.mkdirs());

        // 造 20 份历史备份（时间戳各不相同、升序）
        LocalDateTime base = LocalDateTime.now().minusDays(10);
        for (int i = 0; i < 20; i++) {
            File f = new File(BACKUP_DIR, "familychat.db."
                    + base.plusHours(i).format(STAMP) + ".bak");
            Files.write(f.toPath(), new byte[]{1});
        }
        // 干扰文件：不是本服务产出的，绝不能被保留策略删除
        File foreign = new File(BACKUP_DIR, "familychat.db.20260921-221316.pre-orphan-cleanup.bak");
        Files.write(foreign.toPath(), new byte[]{1});
        File notABackup = new File(BACKUP_DIR, "wallet-reset-20260921-214234.txt");
        Files.write(notABackup.toPath(), new byte[]{1});

        // 再跑一次真实备份 → 总数 21，触发剪枝
        File fresh = BackupService.backupOnce();
        assertNotNull(fresh, "备份应产出文件");

        List<String> kept = BackupService.listBackupNames();
        assertEquals(BackupService.RETAIN, kept.size(),
                "保留份数应为 " + BackupService.RETAIN + "，实际: " + kept);
        assertTrue(kept.contains(fresh.getName()), "最新一份必须在保留集里");
        // 最早的两份（i=0,1）应已被删
        assertFalse(new File(BACKUP_DIR, "familychat.db."
                + base.format(STAMP) + ".bak").exists(), "最旧的备份应被删除");
        // 干扰文件一个都不能少
        assertTrue(foreign.exists(), "非本服务产出的 .bak 不能被删");
        assertTrue(notABackup.exists(), "非备份文件不能被删");
    }

    /** 同一秒内连跑两次不能互相覆盖，两次的产物都要留下。 */
    @Test
    void successiveBackupsDoNotOverwriteEachOther() {
        assertTrue(BACKUP_DIR.isDirectory() || BACKUP_DIR.mkdirs());
        File a = BackupService.backupOnce();
        File b = BackupService.backupOnce();
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.getAbsolutePath(), b.getAbsolutePath(), "两次备份不得落到同一个文件");
        assertTrue(a.isFile() && b.isFile(), "两次备份的产物都应存在");
        assertEquals(2, BackupService.listBackupNames().size());
    }

    /** 调度器可以重复启动（幂等），stop 后能再次启动。 */
    @Test
    void startIsIdempotentAndRestartable() {
        assertTrue(BACKUP_DIR.isDirectory() || BACKUP_DIR.mkdirs());
        BackupService.start();
        BackupService.start();   // 重复调用不应抛异常、不应二次备份
        BackupService.stop();
        BackupService.start();   // 停机后应能再次启动
        BackupService.stop();
        // 启动即备份：至少产出了一份
        assertTrue(BackupService.listBackupNames().size() >= 1,
                "start() 应立刻备份一次（启动即备份）");
    }

    /** 库文件不存在（全新安装）时跳过，不抛异常、不产出垃圾文件。 */
    @Test
    void missingDatabaseIsSkippedQuietly() throws Exception {
        assertTrue(BACKUP_DIR.isDirectory() || BACKUP_DIR.mkdirs());
        // 删掉本用例的库文件（DbTestSupport 建的那个）
        File dbFile = new File(Database.DATA_DIR, "family_chat.db");
        assertTrue(dbFile.exists(), "前置条件：库文件应已由 db.init() 建立");
        assertTrue(dbFile.delete());

        assertNull(BackupService.backupOnce(), "无库文件时应跳过而非报错");
        assertEquals(0, BackupService.listBackupNames().size(), "跳过时不应留下文件");
    }
}
