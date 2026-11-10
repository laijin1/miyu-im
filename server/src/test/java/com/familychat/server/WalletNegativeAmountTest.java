package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 负数金额安全：转账/扫码支付/扣款/充值都不得造币（LOGIC-AUDIT 第 2 条）。 */
class WalletNegativeAmountTest extends DbTestSupport {

    /** 余额；钱包行不存在时视为 0（未开户==没钱），而不是 -1。 */
    private long bal(int uid) {
        Map<String, Object> w = db.getWallet(uid);
        return w == null ? 0L : ((Number) w.get("balance")).longValue();
    }

    /** 负数转账：修复前扣款 SQL 变加钱（造币）；修复后必须直接拒绝。 */
    @Test
    void negativeTransferMintsMoney() {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 500L);
        long before = bal(a);

        Map<String, Object> tx = db.createTransfer(a, b, -100L, "负");
        System.out.println("[NEG] tx=" + tx);
        System.out.println("[NEG] a before=" + before + " after=" + bal(a));

        // 修复后：负数转账被拒，且余额一分不动
        assertNull(tx, "负数转账必须被拒绝");
        assertEquals(before, bal(a), "余额不得变动");
        assertEquals(0L, bal(b), "接收方不得收到钱");
        System.out.println("[NEG][OK] 负数转账已拒绝，未造币");
    }

    /** 反复负数转账：修复后余额应始终为 0（造不出钱）。 */
    @Test
    void negativeTransferRepeatable() {
        int a = register("alice");
        int b = register("bob");
        for (int i = 0; i < 10; i++) {
            db.createTransfer(a, b, -10_000L, "负");
        }
        System.out.println("[NEG-REPEAT] a=" + bal(a) + " b=" + bal(b));
        assertEquals(0L, bal(a), "反复负数转账不得造出余额");
        assertEquals(0L, bal(b), "接收方不得有钱");
    }

    /** directPay 负数：修复后应拒绝，双方余额不变。 */
    @Test
    void negativeDirectPay() {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 100L);
        Map<String, Object> r = db.directPay(a, b, -500L, "负付");
        System.out.println("[NEG-PAY] r=" + r + " a=" + bal(a) + " b=" + bal(b));
        assertNull(r, "负数扫码支付必须被拒绝");
        assertEquals(100L, bal(a), "付款方余额不变");
        assertEquals(0L, bal(b), "收款方余额不得为负");
    }

    /** 扣款 deductBalance 对负数修复前会反向加钱；修复后必须拒绝。 */
    @Test
    void negativeDeduct() {
        int a = register("alice");
        db.addBalance(a, 100L);
        boolean r = db.deductBalance(a, -300L);
        System.out.println("[DEDUCT] deduct(-300)=" + r + " bal=" + bal(a));
        assertFalse(r, "负数扣款必须被拒绝");
        assertEquals(100L, bal(a), "余额不得反向增加");
        assertFalse(db.deductBalance(a, 0L), "零元扣款必须被拒绝");
        assertEquals(100L, bal(a), "余额不得变动");
    }
}
