package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 商城核心：下单扣库存、订单号唯一性（同毫秒并发不碰撞）。 */
class MallOrderTest extends DbTestSupport {

    @Test
    void createOrderDeductsStock() {
        int seller = register("seller");
        int buyer = register("buyer");

        long pid = db.insertProduct(seller, "保温杯", "生活", 2500L, 10, "好杯子", "");
        assertTrue(pid > 0);

        Map<String, Object> order = db.createOrder(buyer, (int) pid, 2, "张三", "13800138000", "某市某路1号");
        assertNotNull(order, "下单应成功");
        assertFalse(order.containsKey("error"), "下单失败: " + order);

        Map<String, Object> p = db.getProduct((int) pid);
        assertEquals(8, ((Number) p.get("stock")).intValue(), "下单 2 件后库存应减 2");
    }

    @Test
    void orderNumbersUniqueUnderSameMillisecond() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "数据线", "数码", 900L, 100, "", "");

        // 同毫秒内连下两单：order_no 含随机后缀，不得因 UNIQUE 冲突失败
        Map<String, Object> o1 = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址A");
        Map<String, Object> o2 = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址A");
        assertNotNull(o1, "第一单应成功");
        assertNotNull(o2, "第二单应成功（order_no 随机后缀防碰撞）");
        assertFalse(o1.containsKey("error") || o2.containsKey("error"));
    }

    @Test
    void cannotBuyOwnProduct() {
        int seller = register("seller");
        long pid = db.insertProduct(seller, "自产自销", "生活", 100L, 5, "", "");
        Map<String, Object> order = db.createOrder(seller, (int) pid, 1, "自", "139", "家");
        assertNotNull(order);
        assertTrue(order.containsKey("error"), "不能购买自己发布的商品");
    }

    @Test
    void stockCannotGoNegative() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "限量款", "数码", 100L, 1, "", "");
        Map<String, Object> o1 = db.createOrder(buyer, (int) pid, 1, "甲", "138", "A地");
        assertNotNull(o1);
        assertFalse(o1.containsKey("error"), "第一单应成功");
        Map<String, Object> o2 = db.createOrder(buyer, (int) pid, 1, "乙", "138", "B地");
        assertNotNull(o2);
        assertTrue(o2.containsKey("error"), "库存只剩 1，第二单必须失败（防超卖）");
        Map<String, Object> p = db.getProduct((int) pid);
        assertEquals(0, ((Number) p.get("stock")).intValue(), "库存不得为负");
    }

    @Test
    void shipRequiresTrackingNumber() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "发货测试", "生活", 100L, 3, "", "");
        db.addBalance(buyer, 1000L);
        Map<String, Object> order = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址");
        int oid = ((Number) order.get("id")).intValue();
        db.payOrder(oid, buyer);

        assertNotNull(db.shipOrder(oid, seller, "顺丰", ""), "空单号必须拒绝发货");
        assertNull(db.shipOrder(oid, seller, "顺丰", "SF123456"), "填了单号应可发货");
    }

    @Test
    void refundOnShippedRestoresStockAndSales() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "退款测试", "生活", 1000L, 5, "", "");
        db.addBalance(buyer, 5000L);
        Map<String, Object> order = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址");
        int oid = ((Number) order.get("id")).intValue();
        db.payOrder(oid, buyer);
        db.shipOrder(oid, seller, "顺丰", "SF1");

        // 待收货可申请并同意退款
        assertNull(db.applyRefund(oid, buyer, "不想要了"));
        assertNull(db.approveRefund(oid, seller));
        Map<String, Object> o2 = db.getOrder(oid);
        assertEquals("refunded", o2.get("status"), "退款后订单状态应为 refunded");
        Map<String, Object> p = db.getProduct((int) pid);
        assertEquals(5, ((Number) p.get("stock")).intValue(), "退款后库存应恢复");
        assertEquals(0, ((Number) p.get("sales")).intValue(), "退款后销量应扣回");
    }

    @Test
    void refundBlockedAfterCompleted() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "完成测试", "生活", 100L, 3, "", "");
        db.addBalance(buyer, 1000L);
        Map<String, Object> order = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址");
        int oid = ((Number) order.get("id")).intValue();
        db.payOrder(oid, buyer);
        db.shipOrder(oid, seller, "顺丰", "SF1");
        db.confirmOrder(oid, buyer);

        // 已完成的订单不可再退款（双状态机互校验）
        assertNotNull(db.applyRefund(oid, buyer, "想退"), "已完成订单不可申请退款");
    }

    @Test
    void rejectedRefundCanBeReapplied() {
        int seller = register("seller");
        int buyer = register("buyer");
        long pid = db.insertProduct(seller, "重试退款", "生活", 100L, 3, "", "");
        db.addBalance(buyer, 1000L);
        Map<String, Object> order = db.createOrder(buyer, (int) pid, 1, "张三", "138", "地址");
        int oid = ((Number) order.get("id")).intValue();
        db.payOrder(oid, buyer);

        assertNull(db.applyRefund(oid, buyer, "不想要了"));
        assertNull(db.rejectRefund(oid, seller), "卖家可拒绝退款");
        // 被拒后买家应能再次申请（不永久锁死）
        assertNull(db.applyRefund(oid, buyer, "质量问题"), "被拒后可再次申请退款");
    }
}
