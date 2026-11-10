package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计第 4 条：商城托管模型。
 *
 * <p>核心不变量是<b>钱守恒</b>：{@code 所有用户余额 + 托管池} 在
 * 下单/支付/确认收货/退款 全过程中只会在"买家扣款→托管"这种内部搬运上变化，
 * 净额必须恒定（本用例不含平台抽成，故应完全不变）。
 *
 * <p>只断言"订单状态对了"是不够的：旧代码就会把状态改对，同时把钱直接塞进卖家口袋。
 * 所以每个用例都同时断言买卖双方余额 + 托管池 + 总额。
 */
class MallEscrowTest extends DbTestSupport {

    @Test
    void paymentHoldsMoneyInEscrowInsteadOfPayingSeller() {
        int seller = register("seller");
        int buyer = register("buyer");
        long base = totalMoney();          // 建库时的存量（含系统账户）= 守恒基准
        long before = credit(buyer, 100_000);
        long pid = db.insertProduct(seller, "托管测试商品", "生活", 10_000L, 10, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 2, "张三", "138", "地址"));

        assertEquals(0, balance(seller), "支付前卖家余额为 0");
        assertNull(db.payOrder(oid, buyer), "支付应成功");

        assertEquals(before - 20_000, balance(buyer), "买家应被扣 200 元");
        assertEquals(0, balance(seller), "★ 支付后卖家不得入账（钱在托管池）");
        assertEquals(20_000, db.getEscrowPoolTotal(), "托管池应有 200 元");
        assertEquals(base + 100_000, totalMoney(), "钱守恒：充值后总额 = 基准 + 充值额");

        Map<String, Object> order = db.getOrder(oid);
        assertEquals(1, ((Number) order.get("escrow")).intValue(), "订单应标记为托管中");
    }

    @Test
    void confirmReceiptReleasesEscrowToSeller() {
        int seller = register("seller");
        int buyer = register("buyer");
        long base = totalMoney();
        credit(buyer, 100_000);
        long pid = db.insertProduct(seller, "商品", "生活", 10_000L, 10, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        assertNull(db.shipOrder(oid, seller, "顺丰", "SF123"));
        assertEquals(10_000, db.getEscrowPoolTotal(), "发货后钱仍在托管池");

        assertNull(db.confirmOrder(oid, buyer), "确认收货应成功");

        assertEquals(10_000, balance(seller), "★ 确认收货后托管才放给卖家");
        assertEquals(0, db.getEscrowPoolTotal(), "托管池应清空");
        assertEquals(base + 100_000, totalMoney(), "钱守恒：放款只是池内搬运，总额不变");
        assertEquals(90_000, balance(buyer), "买家余额 = 充值 - 货款");
        Map<String, Object> order = db.getOrder(oid);
        assertEquals(0, ((Number) order.get("escrow")).intValue(), "托管标记应清除");
    }

    @Test
    void confirmReceiptIsNotRepeatable() {
        int seller = register("seller");
        int buyer = register("buyer");
        credit(buyer, 100_000);
        long pid = db.insertProduct(seller, "商品", "生活", 5_000L, 10, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        assertNull(db.shipOrder(oid, seller, "顺丰", "SF123"));
        assertNull(db.confirmOrder(oid, buyer));

        // 重复确认：状态机挡住，钱不能被放第二次
        assertNotNull(db.confirmOrder(oid, buyer), "重复确认必须失败");
        assertEquals(5_000, balance(seller), "卖家不得二次入账");
        assertEquals(0, db.getEscrowPoolTotal());
    }

    @Test
    void refundFromEscrowSucceedsEvenWhenSellerHasNoBalance() {
        int seller = register("seller");
        int buyer = register("buyer");
        long before = credit(buyer, 50_000);
        long pid = db.insertProduct(seller, "商品", "生活", 30_000L, 5, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        assertEquals(0, balance(seller), "卖家没有余额（钱在托管池）");
        assertNull(db.applyRefund(oid, buyer, "不想要了"));

        // ★ 旧模型在这里必然失败（"卖家余额不足，退款失败"）；托管模型必须成功
        assertNull(db.approveRefund(oid, seller), "托管中的订单退款不应受卖家余额影响");

        assertEquals(before, balance(buyer), "买家应全额退回");
        assertEquals(0, balance(seller), "卖家余额不受影响");
        assertEquals(0, db.getEscrowPoolTotal(), "托管池应清空");
        assertEquals(before, totalMoney(), "钱守恒：总额回到原值");
        assertEquals("refunded", db.getOrder(oid).get("status"));
    }

    @Test
    void adminApprovedRefundAlsoUsesEscrow() {
        int seller = register("seller");
        int buyer = register("buyer");
        long before = credit(buyer, 50_000);
        long pid = db.insertProduct(seller, "商品", "生活", 12_345L, 5, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        assertNull(db.applyRefund(oid, buyer, "平台介入"));

        // 管理后台代批（AdminHandler 走的也是这条 DAO 路径）
        assertNull(db.approveRefund(oid, ((Number) db.getOrder(oid).get("seller_id")).intValue()));
        assertEquals(before, balance(buyer));
        assertEquals(0, db.getEscrowPoolTotal());
        assertEquals(before, totalMoney());
    }

    @Test
    void unconfirmedOrderKeepsMoneyInEscrowAndStockDeducted() {
        int seller = register("seller");
        int buyer = register("buyer");
        long base = totalMoney();
        credit(buyer, 100_000);
        long pid = db.insertProduct(seller, "商品", "生活", 7_000L, 3, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 2, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        assertNull(db.shipOrder(oid, seller, "顺丰", "SF1"));

        // 7 天自动确认之前：钱在托管、卖家拿不到、库存已扣
        assertEquals(0, balance(seller));
        assertEquals(14_000, db.getEscrowPoolTotal());
        assertEquals(1, ((Number) db.getProduct((int) pid).get("stock")).intValue());
        assertEquals(base + 100_000, totalMoney(), "钱守恒");
    }

    @Test
    void legacyOrderWithoutEscrowStillRefundsFromSellerBalance() {
        // 一刀切策略的另一半：escrow=0 的老订单必须仍能按旧规则退款
        int seller = register("seller");
        int buyer = register("buyer");
        long base = totalMoney();
        long buyerBefore = credit(buyer, 10_000);
        credit(seller, 0);
        long pid = db.insertProduct(seller, "老商品", "生活", 4_000L, 5, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));
        assertNull(db.payOrder(oid, buyer));
        // 模拟旧模型：把托管里的钱结算给卖家，并把 escrow 标志清掉
        assertEquals(4_000, db.getEscrowPoolTotal());
        simulateLegacySettle(seller, 4_000);
        assertEquals(0, db.getEscrowPoolTotal());
        assertEquals(4_000, balance(seller), "老订单的钱在卖家余额里");
        assertNull(db.applyRefund(oid, buyer, "老的"));

        assertNull(db.approveRefund(oid, seller), "老订单按旧规则从卖家余额退");
        assertEquals(0, balance(seller), "卖家余额被扣回");
        assertEquals(buyerBefore, balance(buyer), "买家退回");
        assertEquals(0, db.getEscrowPoolTotal(), "老订单不产生托管");
        assertEquals(base + 10_000, totalMoney(), "钱守恒：退款只是把卖家的钱还给买家");
    }

    @Test
    void oversellingAndInsufficientBalanceStillBlocked() {
        int seller = register("seller");
        int buyer = register("buyer");
        credit(buyer, 100);   // 只有 1 元
        long pid = db.insertProduct(seller, "贵的", "生活", 999_999L, 5, "", "");
        int oid = orderId(db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址"));

        assertEquals("余额不足", db.payOrder(oid, buyer), "余额不足必须明确失败");
        assertEquals(0, db.getEscrowPoolTotal(), "失败时不得进托管");
        assertEquals("pending", db.getOrder(oid).get("status"));
        assertEquals(100, totalMoney(), "失败时钱一分不动");
    }

    // ── 助手 ──

    private int orderId(Map<String, Object> order) {
        assertNotNull(order, "下单应成功");
        assertFalse(order.containsKey("error"), "下单失败: " + order);
        return ((Number) order.get("id")).intValue();
    }

    private long credit(int uid, long fen) {
        db.ensureWallet(uid);
        if (fen > 0) assertTrue(db.addBalance(uid, fen, 100_000_000L), "预置余额失败");
        return balance(uid);
    }

    private long balance(int uid) {
        Map<String, Object> w = db.getWallet(uid);
        return w == null ? 0L : ((Number) w.get("balance")).longValue();
    }

    /** 所有用户余额 + 托管池：这个数必须恒定（钱守恒）。 */
    private long totalMoney() {
        long sum = 0;
        try (java.sql.Connection c = db.getConn();
             java.sql.Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                     "SELECT COALESCE(SUM(balance),0) FROM wallets")) {
            if (rs.next()) sum = rs.getLong(1);
        } catch (java.sql.SQLException e) {
            fail("totalMoney 失败: " + e.getMessage());
        }
        return sum;
    }

    /** 把某个订单的托管手工结算成"老模型"状态（钱已在卖家余额、订单 escrow=0）。 */
    private void simulateLegacySettle(int sellerId, long amount) {
        try (java.sql.Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "UPDATE wallets SET balance=balance-? WHERE user_id=0")) {
                    ps.setLong(1, amount);
                    ps.executeUpdate();
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "UPDATE wallets SET balance=balance+? WHERE user_id=?")) {
                    ps.setLong(1, amount);
                    ps.setInt(2, sellerId);
                    ps.executeUpdate();
                }
                try (java.sql.Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM mall_escrow");
                    s.executeUpdate("UPDATE mall_orders SET escrow=0");
                }
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                fail("simulateLegacySettle 失败: " + e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (java.sql.SQLException e) {
            fail("simulateLegacySettle 连接失败: " + e.getMessage());
        }
    }
}
