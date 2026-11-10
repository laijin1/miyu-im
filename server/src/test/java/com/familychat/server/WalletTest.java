package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 钱包/支付密码/转账/收款确认：资金安全关键路径。 */
class WalletTest extends DbTestSupport {

    private static final String PIN = "123456";
    private static final String PIN_HASH = sha256Hex(PIN);

    @Test
    void payPasswordSetVerifyAndLockout() {
        int uid = register("carol");
        // 未设置 → 视为密码错误(1)
        assertEquals(1, db.verifyPayPassword(uid, PIN_HASH));

        assertTrue(db.setPayPassword(uid, PIN_HASH), "设置支付密码应成功");
        assertEquals(0, db.verifyPayPassword(uid, PIN_HASH), "正确密码应通过");
        assertEquals(1, db.verifyPayPassword(uid, sha256Hex("000000")), "错误密码应拒绝");

        // 连续错 5 次 → 锁定（返回 2），锁定期间正确密码也被拒
        for (int i = 0; i < 5; i++) {
            int r = db.verifyPayPassword(uid, sha256Hex("000000"));
            assertTrue(r == 1 || r == 2, "失败过程应返回 1 或 2，实际 " + r);
        }
        assertEquals(2, db.verifyPayPassword(uid, sha256Hex("000000")), "第 6 次应被锁定");
        assertEquals(2, db.verifyPayPassword(uid, PIN_HASH), "锁定期间正确密码也应被拒");
    }

    @Test
    void walletDoesNotLeakCredentials() {
        int uid = register("dave");
        db.setPayPassword(uid, PIN_HASH);
        Map<String, Object> w = db.getWallet(uid);
        assertNotNull(w);
        assertFalse(w.containsKey("pay_hash"), "getWallet 不得返回 pay_hash");
        assertFalse(w.containsKey("pay_salt"), "getWallet 不得返回 pay_salt");
        assertEquals(0L, ((Number) w.get("balance")).longValue());
    }

    @Test
    void transferFlowWithDoubleAcceptBlocked() {
        int a = register("alice");
        int b = register("bob");

        assertTrue(db.addBalance(a, 1000L), "充值应成功");
        Map<String, Object> tx = db.createTransfer(a, b, 300L, "午饭钱");
        assertNotNull(tx, "转账应成功");
        assertEquals(700L, ((Number) db.getWallet(a).get("balance")).longValue(), "发起方扣款");
        assertEquals(0L, ((Number) db.getWallet(b).get("balance")).longValue(), "接收方未入账");

        long txId = ((Number) tx.get("transfer_id")).longValue();
        Map<String, Object> accepted = db.acceptTransfer((int) txId, b);
        assertNotNull(accepted, "接收方收款应成功");
        assertEquals(300L, ((Number) db.getWallet(b).get("balance")).longValue(), "收款后入账");

        // 重复收款必须被拒绝（防重复入账）
        assertNull(db.acceptTransfer((int) txId, b), "已收款项重复收款应失败");
        // 已收款后发起方不能撤回
        assertNull(db.refundTransfer((int) txId, a), "已收款项不可撤回");
    }

    @Test
    void refundTransferRestoresBalance() {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 500L);

        Map<String, Object> tx = db.createTransfer(a, b, 200L, "test");
        long txId = ((Number) tx.get("transfer_id")).longValue();

        Map<String, Object> refunded = db.refundTransfer((int) txId, a);
        assertNotNull(refunded, "待收款转账发起方可撤回");
        assertEquals(500L, ((Number) db.getWallet(a).get("balance")).longValue(), "撤回后退回余额");
        assertNull(db.refundTransfer((int) txId, a), "重复撤回应失败");
    }

    /**
     * 接收方"立即退还"：钱必须退回**发起方**，不能退给操作者自己。
     * （审计第 8 条：DAO javadoc 原写"仅发起方可调"，与实现不符，此处锁住真实语义。）
     */
    @Test
    void receiverRefundReturnsMoneyToSenderNotToSelf() {
        int a = register("alice");
        int b = register("bob");
        db.addBalance(a, 500L);

        Map<String, Object> tx = db.createTransfer(a, b, 200L, "拒收");
        long txId = ((Number) tx.get("transfer_id")).longValue();
        assertEquals(300L, ((Number) db.getWallet(a).get("balance")).longValue(), "发起方已扣款");

        // 接收方 b 调用 refund = 立即退还
        Map<String, Object> r = db.refundTransfer((int) txId, b);
        assertNotNull(r, "接收方应可立即退还");
        assertEquals("refunded", r.get("status"));

        long balA = ((Number) db.getWallet(a).get("balance")).longValue();
        long balB = ((Number) db.getWallet(b).get("balance")).longValue();
        assertEquals(500L, balA, "钱必须退回发起方");
        assertEquals(0L, balB, "接收方不得把钱退给自己");
    }

    /** 毫不相干的第三方不能撤回/退还他人的转账。 */
    @Test
    void thirdPartyCannotRefund() {
        int a = register("alice");
        int b = register("bob");
        int eve = register("eve");
        db.addBalance(a, 500L);

        Map<String, Object> tx = db.createTransfer(a, b, 200L, "第三方");
        long txId = ((Number) tx.get("transfer_id")).longValue();

        assertNull(db.refundTransfer((int) txId, eve), "第三方不得撤回他人转账");
        assertEquals(300L, ((Number) db.getWallet(a).get("balance")).longValue(), "余额不得变动");
    }

    @Test
    void insufficientBalanceRejected() {
        int a = register("alice");
        int b = register("bob");
        assertNull(db.createTransfer(a, b, 100L, "没钱也转"), "余额不足应失败");
    }

    @Test
    void approveChargeIsIdempotent() {
        int payer = register("payer");
        int merchant = register("merchant");
        db.addBalance(payer, 1000L);

        // 商家发起收款：from=商家(收款方), to=付款码主人(付款方)
        long reqId = db.createChargeRequest(merchant, payer, 400L, "向商家付款");
        assertTrue(reqId > 0);

        Map<String, Object> r1 = db.approveCharge((int) reqId, payer);
        assertNotNull(r1, "首次确认收款应成功");
        assertEquals(400L, ((Number) db.getWallet(merchant).get("balance")).longValue(), "商家入账");
        assertEquals(600L, ((Number) db.getWallet(payer).get("balance")).longValue(), "付款方扣款");

        // 重复确认必须被拒绝（防重复扣款入账）
        assertNull(db.approveCharge((int) reqId, payer), "重复确认收款应失败");
        assertEquals(600L, ((Number) db.getWallet(payer).get("balance")).longValue(), "金额不得二次变动");
    }
}
