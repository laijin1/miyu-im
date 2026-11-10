package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传配额「检查—记账」的原子性（LOGIC-AUDIT 第 10 条）。
 *
 * <p>修复后不再复刻逻辑，而是通过反射直接驱动**生产代码里的真实机制**：
 * {@code FileHandler.quotaLockOf(userId)} 提供的每用户锁，
 * 配合 {@code uploadBytesOf} / {@code recordUploadBytes} 记账。
 *
 * <p>模拟的是 uploadFile 落盘后那段的真实形状：
 * <pre>
 *   synchronized (quotaLockOf(uid)) {
 *       usedNow = uploadBytesOf(uid);      // 锁内重读
 *       if (usedNow + written > QUOTA) reject;
 *       recordUploadBytes(uid, written);   // 检查与记账无窗口
 *   }
 * </pre>
 */
class UploadQuotaConcurrencyTest {

    private static Class<?> fh() throws Exception {
        return Class.forName("com.familychat.server.handler.FileHandler");
    }

    private static Object quotaLockOf(int uid) throws Exception {
        Method m = fh().getDeclaredMethod("quotaLockOf", int.class);
        m.setAccessible(true);
        return m.invoke(null, uid);
    }

    private static long uploadBytesOf(int uid) throws Exception {
        Method m = fh().getDeclaredMethod("uploadBytesOf", int.class);
        m.setAccessible(true);
        return ((Number) m.invoke(null, uid)).longValue();
    }

    private static void recordUploadBytes(int uid, long bytes) throws Exception {
        Method m = fh().getDeclaredMethod("recordUploadBytes", int.class, long.class);
        m.setAccessible(true);
        m.invoke(null, uid, bytes);
    }

    /** 清空 usagePerUser，让每次测试从 0 开始（静态状态跨测试共享）。 */
    private static void resetUsage() throws Exception {
        Field f = fh().getDeclaredField("usagePerUser");
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).clear();
        Field t = fh().getDeclaredField("usageTotalBytes");
        t.setAccessible(true);
        t.setLong(null, 0L);
    }

    private static final int UID = 424242;

    /**
     * 并发走生产锁：配额 1000 / 每文件 100 -> 只应接受 10 个。
     * 修复前该形状接受 40 个、占用 4000（超额 4 倍）。
     */
    @Test
    void concurrentUploadsRespectQuota() throws Exception {
        final long quota = 1000L, file = 100L;
        final int n = 40;
        resetUsage();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Thread.sleep(0, 1);                   // 模拟落盘耗时，放大竞态窗口
                    synchronized (quotaLockOf(UID)) {      // ← 生产代码的每用户锁
                        long usedNow = uploadBytesOf(UID); // ← 锁内重读
                        if (usedNow + file > quota) return;
                        recordUploadBytes(UID, file);
                        accepted.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long finalUsage = uploadBytesOf(UID);
        System.out.println("[QUOTA-FIXED] 接受=" + accepted.get() + " 最终占用=" + finalUsage + " 上限=" + quota);
        assertEquals(10, accepted.get(), "并发下只应接受 10 个（1000/100）");
        assertTrue(finalUsage <= quota, "占用不得超过配额，实际 " + finalUsage);
        System.out.println("[QUOTA-FIXED][OK] 并发未突破配额");
    }

    /**
     * 对照组：不加锁的「读—判—写」在并发下会突破配额 —— 证明上一条测试有区分力。
     *
     * <p>这里**强制交错**：40 个线程先全部完成"读"，主线程确认后才放行"写"，因此每个线程
     * 手里都是同一个旧快照，结果**确定**（40×100=4000 &gt; 上限 1000），不依赖线程调度、不再 flaky。
     * 它演示的是"读—判—写不是原子操作"这一事实，而不是"自然竞态偶尔发生"。
     */
    @Test
    void withoutLockStillOvershoots() throws Exception {
        final long quota = 1000L, file = 100L;
        final int n = 40;
        resetUsage();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch readDone = new CountDownLatch(n);   // 40 个线程都读完
        CountDownLatch writeGo  = new CountDownLatch(1);   // 主线程放行写
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                long used = 0L;
                try {
                    start.await();
                    try { used = uploadBytesOf(UID); }      // 无锁：读（同一旧快照）
                    finally { readDone.countDown(); }       // 读抛异常也要放行，避免主线程死等
                    writeGo.await();
                    if (used + file > quota) return;        // 无锁：判
                    recordUploadBytes(UID, file);           // 无锁：写
                    accepted.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        start.countDown();
        assertTrue(readDone.await(30, TimeUnit.SECONDS), "40 个线程都应完成读");
        writeGo.countDown();                                // 放行：此时 40 个线程都拿的是旧快照
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long finalUsage = uploadBytesOf(UID);
        System.out.println("[QUOTA-NOLOCK] 接受=" + accepted.get() + " 最终占用=" + finalUsage + " 上限=" + quota);
        assertEquals(n, accepted.get(), "强制交错下 40 个线程都应判通过（读到的都是同一个旧快照）");
        assertEquals(n * file, finalUsage, "40 × 100 = 4000，必然突破上限");
        assertTrue(finalUsage > quota,
                "无锁版本应当超额（对照组，证明测试有区分力），实际 " + finalUsage);
    }

    /** 同一 userId 必须拿到同一把锁，不同 userId 必须不同锁（否则退化成全局锁）。 */
    @Test
    void lockIsPerUser() throws Exception {
        Object a1 = quotaLockOf(1), a2 = quotaLockOf(1), b = quotaLockOf(2);
        assertSame(a1, a2, "同一用户必须复用同一把锁");
        assertNotSame(a1, b, "不同用户必须用不同的锁（不能退化成全局锁）");
        System.out.println("[QUOTA-LOCK] 同用户同锁、异用户异锁 ✓");
    }
}
