package com.familychat.server;

import com.familychat.server.model.dao.WalletDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 充值安全：单次入账上限必须在 SQL 层强制（负数/零/超上限都不得改动 balance）。
 * 对应体检报告 §1.1 与「充值金额无上限、可为负」修复。
 */
class WalletRechargeTest extends DbTestSupport {

    @Test
    void addBalanceEnforcesSingleCreditLimitInSql() {
        int uid = register("recharger");
        long cap = WalletDao.MAX_SINGLE_CREDIT;
        assertEquals(10_000L, cap, "上限应为 10000 分（100 元）");

        assertFalse(db.addBalance(uid, -100L, cap), "负数入账必须被拒");
        assertFalse(db.addBalance(uid, 0L, cap), "零入账必须被拒");
        assertFalse(db.addBalance(uid, cap + 1, cap), "超上限入账必须被拒");
        assertFalse(db.addBalance(uid, 999_999L, cap), "远超上限入账必须被拒");
        assertEquals(0L, ((Number) db.getWallet(uid).get("balance")).longValue(),
                "非法入账后余额必须仍为 0");

        // 边界：恰好等于上限应放行
        assertTrue(db.addBalance(uid, cap, cap), "恰好等于上限应成功");
        assertEquals(cap, ((Number) db.getWallet(uid).get("balance")).longValue());

        // 被拒后余额不得变化
        assertFalse(db.addBalance(uid, cap + 1, cap), "再次超限仍应被拒");
        assertFalse(db.addBalance(uid, 1L, 0L), "上限为 0 时任何入账都应被拒");
        assertEquals(cap, ((Number) db.getWallet(uid).get("balance")).longValue(),
                "被拒后余额不得变化");
    }

    /** 2 参重载（转账/收款入账路径）默认也套用上限，且既有测试用的小额不受影响。 */
    @Test
    void twoArgOverloadStillWorksForLegitimateAmounts() {
        int uid = register("smallcredit");
        assertTrue(db.addBalance(uid, 1000L), "1000 分应成功");
        assertEquals(1000L, ((Number) db.getWallet(uid).get("balance")).longValue());
        assertFalse(db.addBalance(uid, 999_999L), "默认重载也必须挡下超上限");
        assertEquals(1000L, ((Number) db.getWallet(uid).get("balance")).longValue());
    }
}
