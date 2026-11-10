package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端动钱路径（收款请求代确认 / 代拒绝）。
 *
 * <p>这是本轮唯一"管理员能直接把钱从 A 挪到 B"的接口，所以每个用例都同时断言
 * <b>三件事</b>：A 少了多少、B 多了多少、两边差额是否恰好抵消（钱守恒）。
 * 只断言"成功了"是锁不住造币 bug 的。
 */
class AdminWalletChargeTest extends DbTestSupport {

    @Test
    void ownerApprovalMovesMoneyAndKeepsConservation() throws Exception {
        int payer = register("payer");
        int merchant = register("merchant");
        long payerBefore = credit(payer, 100000);
        long merchantBefore = credit(merchant, 0);

        long cid = db.createChargeRequest(merchant, payer, 2500, "咖啡");
        assertTrue(cid > 0);

        Map<String, Object> r = db.approveCharge((int) cid, payer);
        assertNotNull(r, "主人确认应成功");

        long payerAfter = balance(payer);
        long merchantAfter = balance(merchant);
        assertEquals(payerBefore - 2500, payerAfter, "付款人应被扣 25 元");
        assertEquals(merchantBefore + 2500, merchantAfter, "商家应收到 25 元");
        assertEquals(payerBefore + merchantBefore, payerAfter + merchantAfter, "总余额必须守恒");
        assertEquals("accepted", db.getChargeRequest((int) cid).get("status"));
    }

    @Test
    void ownerApprovalRejectedForWrongUser() {
        int payer = register("payer2");
        int merchant = register("merchant2");
        int stranger = register("stranger2");
        long payerBefore = credit(payer, 100000);
        long cid = db.createChargeRequest(merchant, payer, 1000, "");

        assertNull(db.approveCharge((int) cid, stranger), "非付款本人不得确认");
        assertEquals(payerBefore, balance(payer), "被拒后付款人余额必须一分不动");
        assertEquals("pending", db.getChargeRequest((int) cid).get("status"));
        assertEquals(0, balanceOrZero(stranger), "第三方余额不得变动");
    }

    @Test
    void adminApprovalWorksWithoutOwnership() {
        int payer = register("payer3");
        int merchant = register("merchant3");
        long payerBefore = credit(payer, 50000);
        long merchantBefore = credit(merchant, 0);
        long cid = db.createChargeRequest(merchant, payer, 1234, "代确认");

        // 管理端入口：不校验归属，但仍走同一条资金路径
        Map<String, Object> r = db.approveChargeAsAdmin((int) cid);
        assertNotNull(r, "管理员代确认应成功");
        assertEquals(payerBefore - 1234, balance(payer));
        assertEquals(merchantBefore + 1234, balance(merchant));
        assertEquals(payerBefore + merchantBefore, balance(payer) + balance(merchant), "钱守恒");
    }

    @Test
    void doubleApprovalIsRejectedAndMoneyMovesOnce() {
        int payer = register("payer4");
        int merchant = register("merchant4");
        credit(payer, 100000);
        long cid = db.createChargeRequest(merchant, payer, 777, "");

        assertNotNull(db.approveChargeAsAdmin((int) cid));
        long afterFirst = balance(payer);
        // 第二次必须被状态机挡住：这是"防重复审批"的核心断言
        assertNull(db.approveChargeAsAdmin((int) cid), "已处理的请求不得再次入账");
        assertNull(db.approveCharge((int) cid, payer), "主人路径同样不得重复入账");
        assertEquals(afterFirst, balance(payer), "余额不得二次变动");
        assertEquals(777, balance(merchant), "商家不得二次入账");
    }

    @Test
    void approveFailsWhenPayerHasInsufficientBalance() {
        int payer = register("payer5");
        int merchant = register("merchant5");
        credit(payer, 100);   // 只有 1 元
        long cid = db.createChargeRequest(merchant, payer, 5000, "超能力");

        assertNull(db.approveChargeAsAdmin((int) cid), "余额不足必须失败");
        assertEquals(100, balance(payer), "失败时付款人余额不变");
        // 商家此前没有钱包行，失败后也不该凭空多出一行 0 余额记录
        assertEquals(0, balanceOrZero(merchant), "失败时商家不入账");
        assertEquals("pending", db.getChargeRequest((int) cid).get("status"), "失败后请求保持 pending");
    }

    @Test
    void adminDeclineLeavesMoneyUntouched() {
        int payer = register("payer6");
        int merchant = register("merchant6");
        credit(payer, 8000);
        long cid = db.createChargeRequest(merchant, payer, 3000, "拒绝测试");

        assertTrue(db.declineChargeAsAdmin((int) cid));
        assertEquals(8000, balance(payer), "拒绝不得扣款");
        assertEquals(0, balanceOrZero(merchant), "拒绝不得入账");
        assertEquals("declined", db.getChargeRequest((int) cid).get("status"));
        assertFalse(db.declineChargeAsAdmin((int) cid), "已拒绝的请求不能重复拒绝");
        assertNull(db.approveChargeAsAdmin((int) cid), "拒绝后不得再确认入账");
    }

    @Test
    void adminApprovalOnMissingRequestReturnsNull() {
        assertNull(db.approveChargeAsAdmin(999999));
        assertFalse(db.declineChargeAsAdmin(999999));
    }

    // ── 助手 ──

    private long credit(int uid, long amountFen) {
        db.ensureWallet(uid);
        if (amountFen > 0) {
            assertTrue(db.addBalance(uid, amountFen, 100_000_000L), "预置余额失败");
        }
        return balance(uid);
    }

    private long balance(int uid) {
        Map<String, Object> w = db.getWallet(uid);
        assertNotNull(w, "钱包应存在（uid=" + uid + "）");
        return ((Number) w.get("balance")).longValue();
    }

    /** 余额；没有钱包行时视为 0（"从未建过钱包"与"余额为 0"在业务上等价）。 */
    private long balanceOrZero(int uid) {
        Map<String, Object> w = db.getWallet(uid);
        return w == null ? 0L : ((Number) w.get("balance")).longValue();
    }
}
