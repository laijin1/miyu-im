package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 钱包并发安全：并发收款只成功一次、并发扣款不得透支（LOGIC-AUDIT 第 3 条）。 */
class WalletConcurrencyTest extends DbTestSupport {

    private long bal(int uid) {
        Map<String, Object> w = db.getWallet(uid);
        return w == null ? -1 : ((Number) w.get("balance")).longValue();
    }

    /** 并发收款同一笔转账：只能成功一次（防重复入账）。 */
    @Test
    void concurrentAcceptOnlyOnce() throws Exception {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 1000L);
        Map<String, Object> tx = db.createTransfer(a, b, 500L, "并发");
        int txId = ((Number) tx.get("transfer_id")).intValue();

        int N = 16;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Map<String, Object> r = db.acceptTransfer(txId, b);
                    if (r != null) ok.incrementAndGet(); else fail.incrementAndGet();
                } catch (Exception e) {
                    System.out.println("[RACE] exception: " + e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        System.out.println("[RACE] accept ok=" + ok.get() + " fail=" + fail.get());
        System.out.println("[RACE] a=" + bal(a) + " b=" + bal(b) + " (期望 a=500 b=500)");
        assertEquals(1, ok.get(), "并发收款只应成功一次");
        assertEquals(500L, bal(b), "接收方只能入账一次");
    }

    /** 并发扣款：余额不得为负。 */
    @Test
    void concurrentDeductNoOverdraft() throws Exception {
        int a = register("alice");
        db.addBalance(a, 1000L);

        int N = 20;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (db.deductBalance(a, 100L)) ok.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        System.out.println("[RACE-DEDUCT] ok=" + ok.get() + " bal=" + bal(a) + " (期望 ok=10 bal=0)");
        assertTrue(bal(a) >= 0, "余额不得为负");
        assertEquals(10, ok.get(), "1000/100 应恰好成功 10 次");
        assertEquals(0L, bal(a), "扣完应为 0");
    }

    /** 并发转账总额不得超过余额。 */
    @Test
    void concurrentTransferNoOverdraft() throws Exception {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 1000L);

        int N = 20;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (db.createTransfer(a, b, 100L, "并发转") != null) ok.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        System.out.println("[RACE-TX] ok=" + ok.get() + " a=" + bal(a) + " (期望 ok=10 a=0)");
        assertTrue(bal(a) >= 0, "余额不得为负");
        assertEquals(10, ok.get(), "1000/100 应恰好成功 10 次");
        assertEquals(0L, bal(a), "扣完应为 0");
    }
}
