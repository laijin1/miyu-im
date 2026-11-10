package com.familychat.server.handler;

import com.familychat.server.DbTestSupport;
import com.familychat.server.model.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * video_cache 回收策略（体检报告 §3.3「只按文件个数限制」）。
 *
 * <p>原实现只有 {@code MAX_CACHE_FILES=200} 这一个条件，单文件大小不设防：
 * 实测生产目录 2 个文件就占 912.3MB（440.8MB + 471.5MB），按 200 个文件推算
 * 量级会滑到几十 GB。现在改为「总大小为主、文件个数为辅」的双条件回收。
 *
 * <p>放在 handler 包内，直接调用包内可见的 {@code FileHandler.pruneVideoCache}
 * （不为了测试把它提升为 public）。
 *
 * <p>本测试跑在 build/test-data/video_cache 下（由 gradle 注入的 familychat.data.dir
 * 决定），绝不触碰生产 server-java/data/video_cache。
 */
class VideoCachePruneTest extends DbTestSupport {

    private static final Path CACHE_DIR = Paths.get(Database.DATA_DIR, "video_cache");

    @BeforeEach
    void cleanCacheDir() throws IOException {
        new FileHandler();   // 触发静态初始化并建好目录（只建目录，不启动服务）
        if (Files.isDirectory(CACHE_DIR)) {
            try (var s = Files.list(CACHE_DIR)) {
                for (Path p : s.toList()) Files.deleteIfExists(p);
            }
        } else {
            Files.createDirectories(CACHE_DIR);
        }
    }

    private static void makeCacheFile(String name, int bytes, long mtimeMillis) throws IOException {
        Path p = CACHE_DIR.resolve(name);
        Files.write(p, new byte[bytes]);
        assertTrue(p.toFile().setLastModified(mtimeMillis), "设置 mtime 失败: " + name);
    }

    private static long hoursAgo(int h) {
        return System.currentTimeMillis() - h * 3600_000L;
    }

    @Test
    void auditOnlyReportsButDeletesNothing() throws IOException {
        makeCacheFile("old.mp4", 3_000, hoursAgo(3));
        makeCacheFile("new.mp4", 3_000, hoursAgo(1));

        // 阈值远小于实际占用 → 按理「该删」，但 auditOnly=true 必须一个都不删
        String report = FileHandler.pruneVideoCache(1_000L, 200, true);

        assertTrue(Files.exists(CACHE_DIR.resolve("old.mp4")), "audit 模式不得删除任何文件");
        assertTrue(Files.exists(CACHE_DIR.resolve("new.mp4")), "audit 模式不得删除任何文件");
        assertTrue(report.contains("[audit-only]"), "报告应标明 audit 模式；实际=" + report);
        assertTrue(report.contains("本次未删除任何文件"), "报告应说明未删除；实际=" + report);
    }

    @Test
    void totalSizeCapDeletesOldestFirstEvenWhenFileCountIsLow() throws IOException {
        // 关键回归：只有 3 个文件（远低于 200 的个数上限），旧的实现一个都不会删
        makeCacheFile("oldest.mp4", 4_000, hoursAgo(3));
        makeCacheFile("middle.mp4", 4_000, hoursAgo(2));
        makeCacheFile("newest.mp4", 4_000, hoursAgo(1));

        // 总大小上限 5_000B < 12_000B，必须回收最旧的，直到剩余 ≤ 上限
        String report = FileHandler.pruneVideoCache(5_000L, 200, false);

        assertFalse(Files.exists(CACHE_DIR.resolve("oldest.mp4")), "应优先删除最旧的文件");
        assertFalse(Files.exists(CACHE_DIR.resolve("middle.mp4")), "删到总量达标前应继续删次旧的");
        assertTrue(Files.exists(CACHE_DIR.resolve("newest.mp4")), "最新的文件应保留");
        assertTrue(report.contains("[delete]"), "报告应标明执行了删除；实际=" + report);

        long remaining;
        try (var s = Files.list(CACHE_DIR)) {
            remaining = s.mapToLong(p -> p.toFile().length()).sum();
        }
        assertTrue(remaining <= 5_000L, "回收后总大小必须 ≤ 上限，实际=" + remaining);
    }

    @Test
    void fileCountCapStillAppliesAsSecondaryCondition() throws IOException {
        for (int i = 5; i >= 1; i--) {
            makeCacheFile("f" + i + ".mp4", 100, hoursAgo(i));
        }
        FileHandler.pruneVideoCache(1_000_000L, 2, false);   // 总大小给足，只让个数上限生效
        try (var s = Files.list(CACHE_DIR)) {
            assertEquals(2, s.count(), "个数上限应把文件数压到 2");
        }
    }

    @Test
    void underLimitKeepsEverything() throws IOException {
        makeCacheFile("a.mp4", 100, hoursAgo(2));
        makeCacheFile("b.mp4", 100, hoursAgo(1));
        FileHandler.pruneVideoCache(1_000_000L, 200, false);
        try (var s = Files.list(CACHE_DIR)) {
            assertEquals(2, s.count(), "未超限时不得删除任何文件");
        }
    }
}
