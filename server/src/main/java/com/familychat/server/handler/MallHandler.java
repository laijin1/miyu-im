package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/**
 * 商城接口：淘宝式完整闭环。
 * 阶段2：商品/分类/轮播。阶段3：商品详情、下单（填地址手机号）、钱包支付、我的订单、
 * 确认收货、商家后台（发布/编辑/上下架/删除商品、店铺订单管理、发货）。
 * 阶段4完善：首页轮播/分类宫格数据、购物车、商品收藏、收货地址簿、商品评价、
 * 退款/售后（申请/同意/拒绝）、物流信息（快递公司+单号）、搜索热词、商品分页、
 * 卖家店铺主页、订单状态 WS 通知（下单/付款/发货/收货/取消/退款/超时）。
 * 商品价格单位:分(整数)，与钱包体系一致；支付走支付密码，资金流与钱包账单贯通。
 */
public class MallHandler {

    public void register(Javalin app) {
        app.get("/api/mall/products", this::getProducts);            // 商品列表（分类/关键词/分页）
        app.get("/api/mall/categories", this::getCategories);        // 分类宫格
        app.get("/api/mall/hot-searches", this::getHotSearches);     // 搜索热词榜
        app.get("/api/mall/sellers/{id}/products", this::getSellerShop); // 卖家店铺主页
        // 买家
        app.get("/api/mall/products/{id}", this::getProduct);        // 商品详情
        app.get("/api/mall/products/{id}/reviews", this::getReviews);// 商品评价
        app.get("/api/mall/orders", this::getMyOrders);              // 我的订单
        app.get("/api/mall/orders/{id}", this::getOrder);            // 订单详情
        app.post("/api/mall/orders", this::createOrder);             // 下单（单商品或 items 批量）
        app.post("/api/mall/orders/pay-batch", this::payOrders);     // 批量支付（购物车结算）
        app.post("/api/mall/orders/{id}/pay", this::payOrder);       // 支付密码付款
        app.post("/api/mall/orders/{id}/cancel", this::cancelOrder); // 取消待付款
        app.post("/api/mall/orders/{id}/confirm", this::confirmOrder);// 确认收货
        app.post("/api/mall/orders/{id}/review", this::addReview);   // 订单评价（完成后）
        app.post("/api/mall/orders/{id}/refund", this::applyRefund); // 买家申请退款
        // 购物车
        app.get("/api/mall/cart", this::getCart);                    // 我的购物车
        app.post("/api/mall/cart", this::addCart);                   // 加购
        app.put("/api/mall/cart/{id}", this::updateCart);            // 改数量
        app.delete("/api/mall/cart/{id}", this::deleteCart);         // 删项
        // 收藏（toggle 必须注册在 {productId} 之前，避免被通配）
        app.post("/api/mall/favorites/toggle", this::toggleFavorite);// 收藏/取消
        app.get("/api/mall/favorites", this::getFavorites);          // 我的收藏
        app.get("/api/mall/favorites/{productId}", this::isFavorite);// 是否已收藏
        // 收货地址簿
        app.get("/api/mall/addresses", this::getAddresses);          // 地址列表
        app.post("/api/mall/addresses", this::addAddress);           // 新增
        app.put("/api/mall/addresses/{id}", this::updateAddress);    // 编辑
        app.delete("/api/mall/addresses/{id}", this::deleteAddress); // 删除
        app.post("/api/mall/addresses/{id}/default", this::setDefaultAddress); // 设默认
        // 商家（都是本人，seller_id=userId）
        app.get("/api/mall/merchant/products", this::getSellerProducts); // 我的商品
        app.get("/api/mall/merchant/orders", this::getSellerOrders);     // 店铺订单（购买账单）
        app.post("/api/mall/products", this::createProduct);              // 发布商品
        app.put("/api/mall/products/{id}", this::updateProduct);          // 编辑商品
        app.post("/api/mall/products/{id}/toggle", this::toggleProduct);  // 上架/下架
        app.delete("/api/mall/products/{id}", this::deleteProduct);       // 删除商品
        app.post("/api/mall/orders/{id}/ship", this::shipOrder);          // 卖家发货（含快递信息）
        app.post("/api/mall/orders/{id}/refund/approve", this::approveRefund); // 卖家同意退款
        app.post("/api/mall/orders/{id}/refund/reject", this::rejectRefund);   // 卖家拒绝退款
    }

    /** GET /api/mall/products?category=数码&keyword=水杯&page=1&pageSize=20 → {products,has_more,total}。 */
    private void getProducts(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        String category = ctx.queryParam("category");
        String keyword = ctx.queryParam("keyword");
        int page = parsePositive(ctx.queryParam("page"), 1);
        int pageSize = parsePositive(ctx.queryParam("pageSize"), 20);
        // 记录搜索热词（非空关键词，下钻一次）
        if (keyword != null && !keyword.trim().isEmpty()) {
            FamilyChatServer.getDb().recordSearch(keyword);
        }
        ctx.json(FamilyChatServer.getDb().getProductsPage(category, keyword, page, pageSize));
    }

    /** GET /api/mall/categories → {categories:[{id,name,color,sort_order}, ...]}。 */
    private void getCategories(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        List<Map<String, Object>> list = FamilyChatServer.getDb().getCategories();
        ctx.json(Map.of("categories", list));
    }

    /** GET /api/mall/hot-searches → {keywords:[{keyword,count}, ...]}。 */
    private void getHotSearches(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        try {
            ctx.json(Map.of("keywords", FamilyChatServer.getDb().getHotSearches(10)));
        } catch (Exception e) {
            // 兜底：DB 异常时返回空榜而非 500（客户端会回退默认热词）
            System.err.println("[ERROR] getHotSearches: " + e.getMessage());
            ctx.json(Map.of("keywords", new java.util.ArrayList<>()));
        }
    }

    /** GET /api/mall/sellers/{id}/products → 卖家店铺主页商品（含店铺名）。 */
    private void getSellerShop(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int sellerId;
        try { sellerId = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的卖家ID")); return; }
        List<Map<String, Object>> products = FamilyChatServer.getDb().getSellerPublicProducts(sellerId);
        Map<String, Object> seller = FamilyChatServer.getDb().getUserById(sellerId);
        String shopName = seller != null
                ? (seller.get("nickname") instanceof String s && !s.isEmpty() ? s : "家庭小店")
                : "家庭小店";
        ctx.json(Map.of("products", products,
                "seller_id", sellerId,
                "shop_name", shopName != null && !shopName.isEmpty() ? shopName : "家庭小店"));
    }

    /** GET /api/mall/products/{id} → 商品详情（含 seller_nickname / stock / sales）。 */
    private void getProduct(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        Map<String, Object> p = FamilyChatServer.getDb().getProduct(id);
        if (p == null) { ctx.status(404).json(Map.of("error", "商品不存在")); return; }
        ctx.json(Map.of("product", p));
    }

    /** GET /api/mall/products/{id}/reviews → 商品评价列表。查询失败回 503，不伪装成「暂无评价」。 */
    private void getReviews(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        java.util.List<Map<String, Object>> reviews = FamilyChatServer.getDb().getProductReviews(id);
        if (reviews == null) {
            ctx.status(503).json(Map.of("error", "评价加载失败，请稍后重试"));
            return;
        }
        ctx.json(Map.of("reviews", reviews));
    }

    /** GET /api/mall/orders → 我的订单（买家，新→旧）。 */
    private void getMyOrders(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        ctx.json(Map.of("orders", FamilyChatServer.getDb().getMyOrders(userId)));
    }

    /** GET /api/mall/orders/{id} → 订单详情。 */
    private void getOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> o = FamilyChatServer.getDb().getOrder(id);
        if (o == null) { ctx.status(404).json(Map.of("error", "订单不存在")); return; }
        int buyerId = o.get("buyer_id") instanceof Number n ? n.intValue() : -1;
        int sellerId = o.get("seller_id") instanceof Number n2 ? n2.intValue() : -1;
        if (buyerId != userId && sellerId != userId) {
            ctx.status(403).json(Map.of("error", "无权查看该订单"));
            return;
        }
        ctx.json(Map.of("order", o));
    }

    /**
     * POST /api/mall/orders  body {product_id, quantity, receiver_*} 或 {items:[{product_id,quantity}], receiver_*}
     * 下单（淘宝式：先填收货信息再支付）。单商品或购物车批量（单事务）。
     * 成功后逐个通知卖家「新订单」。
     */
    private void createOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        String name = body.get("receiver_name") instanceof String s ? s.trim() : "";
        String phone = body.get("receiver_phone") instanceof String s ? s.trim() : "";
        String address = body.get("receiver_address") instanceof String s ? s.trim() : "";

        if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
            ctx.status(400).json(Map.of("error", "请填写完整的收货人、手机号和地址"));
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            ctx.status(400).json(Map.of("error", "手机号格式不正确"));
            return;
        }

        Object itemsObj = body.get("items");
        List<Map<String, Object>> orders;
        if (itemsObj instanceof List<?> items && !items.isEmpty()) {
            // 批量下单（购物车结算）
            List<Map<String, Object>> itemList = new java.util.ArrayList<>();
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) {
                    java.util.Map<String, Object> it = new java.util.HashMap<>();
                    it.put("product_id", m.get("product_id") instanceof Number n ? n.intValue() : 0);
                    it.put("quantity", m.get("quantity") instanceof Number qn ? Math.max(1, qn.intValue()) : 1);
                    itemList.add(it);
                }
            }
            Map<String, Object> r = FamilyChatServer.getDb().createOrders(
                    userId, itemList, name, phone, address);
            if (r.containsKey("error")) {
                ctx.status(400).json(Map.of("error", r.get("error")));
                return;
            }
            orders = (List<Map<String, Object>>) r.get("orders");
            // 清空已结算的购物车项
            for (Map<String, Object> o : orders) {
                int productId = o.get("product_id") instanceof Number pn ? pn.intValue() : 0;
                if (productId > 0) {
                    for (Map<String, Object> item : itemList) {
                        if (((Number) item.get("product_id")).intValue() == productId) {
                            // 找到购物车项并删除（由客户端在成功后清理，服务端兜底按商品删）
                            FamilyChatServer.getDb().deleteCartByProduct(userId, productId);
                            break;
                        }
                    }
                }
            }
        } else {
            // 单商品下单（老格式）
            int productId;
            int quantity = 1;
            try { productId = ((Number) body.get("product_id")).intValue(); }
            catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 product_id")); return; }
            Object qObj = body.get("quantity");
            if (qObj instanceof Number qn) quantity = Math.max(1, qn.intValue());
            Map<String, Object> order = FamilyChatServer.getDb().createOrder(
                    userId, productId, quantity, name, phone, address);
            if (order == null || order.containsKey("error")) {
                ctx.status(400).json(Map.of("error", order != null ? order.get("error") : "下单失败"));
                return;
            }
            orders = new java.util.ArrayList<>();
            orders.add(order);
        }

        // 通知各卖家有新订单
        for (Map<String, Object> o : orders) {
            int sellerId = o.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of(
                        "type", "mall_order_new",
                        "order_id", o.get("id"),
                        "product_name", o.getOrDefault("product_name", ""),
                        "amount", o.get("amount")));
            }
        }
        System.out.println("[MALL] uid=" + userId + " create " + orders.size() + " order(s)");
        // 兼容旧客户端：单订单同时返回 order 字段；新客户端统一读 orders 数组
        if (orders.size() == 1) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("success", true);
            out.put("order", orders.get(0));
            out.put("orders", orders);
            ctx.json(out);
        } else {
            ctx.json(Map.of("success", true, "orders", orders));
        }
    }

    /**
     * POST /api/mall/orders/pay-batch  body {order_ids:[...], pin_hash}
     * 批量支付（购物车结算）：事务内逐单扣款/入账/记账，任一失败整体回滚。
     * 成功后刷新买家与相关卖家余额并通知卖家已付款。
     */
    private void payOrders(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        Object idsObj = body.get("order_ids");
        if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
            ctx.status(400).json(Map.of("error", "缺少 order_ids"));
            return;
        }
        List<Integer> orderIds = new java.util.ArrayList<>();
        for (Object o : ids) {
            if (o instanceof Number n) orderIds.add(n.intValue());
        }
        if (orderIds.isEmpty()) { ctx.status(400).json(Map.of("error", "缺少 order_ids")); return; }
        String pinHash = body.get("pin_hash") instanceof String s ? s.trim() : "";

        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        boolean paySet = w != null && (w.get("pay_set") instanceof Number n ? n.intValue() : 0) == 1;
        if (!paySet) {
            ctx.status(403).json(Map.of("error", "请先设置支付密码"));
            return;
        }
        int pinR = FamilyChatServer.getDb().verifyPayPassword(userId, pinHash);
        if (pinR == 2) {
            ctx.status(429).json(Map.of("error", "支付密码错误次数过多，请 5 分钟后再试"));
            return;
        }
        if (pinR != 0) {
            ctx.status(403).json(Map.of("error", "支付密码错误"));
            return;
        }

        String err = FamilyChatServer.getDb().payOrders(orderIds, userId);
        if (err != null) {
            ctx.status(400).json(Map.of("error", err));
            return;
        }
        System.out.println("[MALL] uid=" + userId + " pay-batch " + orderIds.size() + " orders");
        pushWallet(userId);
        for (int orderId : orderIds) {
            Map<String, Object> order = FamilyChatServer.getDb().getOrder(orderId);
            if (order == null) continue;
            int sellerId = order.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            if (sellerId > 0) pushWallet(sellerId);
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of(
                        "type", "mall_order_paid",
                        "order_id", orderId,
                        "product_name", order.getOrDefault("product_name", ""),
                        "amount", order.get("amount")));
            }
        }
        ctx.json(Map.of("success", true, "order_ids", orderIds));
    }

    /**
     * POST /api/mall/orders/{id}/pay  body {pin_hash}
     * 支付密码校验 → 事务扣买家/入卖家/记账/累加销量。成功后 WS 双端刷新余额 + 通知卖家已付款。
     */
    private void payOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String pinHash = body.get("pin_hash") instanceof String s ? s.trim() : "";

        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        boolean paySet = w != null && (w.get("pay_set") instanceof Number n ? n.intValue() : 0) == 1;
        if (!paySet) {
            ctx.status(403).json(Map.of("error", "请先设置支付密码"));
            return;
        }
        int pinR = FamilyChatServer.getDb().verifyPayPassword(userId, pinHash);
        if (pinR == 2) {
            ctx.status(429).json(Map.of("error", "支付密码错误次数过多，请 5 分钟后再试"));
            return;
        }
        if (pinR != 0) {
            ctx.status(403).json(Map.of("error", "支付密码错误"));
            return;
        }

        String err = FamilyChatServer.getDb().payOrder(id, userId);
        if (err != null) {
            ctx.status(400).json(Map.of("error", err));
            return;
        }
        System.out.println("[MALL] uid=" + userId + " pay order " + id);
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        if (order != null) {
            int sellerId = order.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            pushWallet(userId);
            if (sellerId > 0) pushWallet(sellerId);
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of(
                        "type", "mall_order_paid",
                        "order_id", id,
                        "product_name", order.getOrDefault("product_name", ""),
                        "amount", order.get("amount")));
            }
        }
        ctx.json(Map.of("success", true, "order_id", id));
    }

    /** POST /api/mall/orders/{id}/cancel  买家取消待付款订单（恢复库存）。通知卖家。 */
    private void cancelOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().cancelOrder(id, userId);
        if (err != null) {
            ctx.status(400).json(Map.of("error", err));
            return;
        }
        if (order != null) {
            int sellerId = order.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of("type", "mall_order_cancelled", "order_id", id));
            }
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/orders/{id}/confirm  买家确认收货（shipped→completed）。通知卖家。 */
    private void confirmOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().confirmOrder(id, userId);
        if (err != null) {
            ctx.status(400).json(Map.of("error", err));
            return;
        }
        if (order != null) {
            int sellerId = order.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of("type", "mall_order_completed", "order_id", id));
            }
        }
        ctx.json(Map.of("success", true));
    }

    // ===== 购物车 =====

    /** GET /api/mall/cart → 我的购物车（含商品信息）。查询失败回 503，不伪装成空购物车。 */
    private void getCart(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        java.util.List<Map<String, Object>> items = FamilyChatServer.getDb().getCart(userId);
        if (items == null) {
            ctx.status(503).json(Map.of("error", "购物车加载失败，请稍后重试"));
            return;
        }
        ctx.json(Map.of("items", items));
    }

    /** POST /api/mall/cart  body {product_id, quantity}。 */
    private void addCart(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int productId;
        int quantity = 1;
        try { productId = ((Number) body.get("product_id")).intValue(); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 product_id")); return; }
        Object qObj = body.get("quantity");
        if (qObj instanceof Number qn) quantity = Math.max(1, qn.intValue());
        String err = FamilyChatServer.getDb().addCart(userId, productId, quantity);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        ctx.json(Map.of("success", true));
    }

    /** PUT /api/mall/cart/{id}  body {quantity}。 */
    private void updateCart(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的购物车项ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int quantity = body.get("quantity") instanceof Number qn ? Math.max(1, qn.intValue()) : 1;
        String err = FamilyChatServer.getDb().updateCartQuantity(userId, id, quantity);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        ctx.json(Map.of("success", true));
    }

    /** DELETE /api/mall/cart/{id}。 */
    private void deleteCart(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的购物车项ID")); return; }
        if (!FamilyChatServer.getDb().deleteCartItem(userId, id)) {
            ctx.status(404).json(Map.of("error", "购物车项不存在"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    // ===== 收藏 =====

    /** POST /api/mall/favorites/toggle  body {product_id} → {favorited: true/false}。 */
    private void toggleFavorite(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int productId;
        try { productId = ((Number) body.get("product_id")).intValue(); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "缺少 product_id")); return; }
        boolean fav = FamilyChatServer.getDb().toggleFavorite(userId, productId);
        ctx.json(Map.of("success", true, "favorited", fav));
    }

    /** GET /api/mall/favorites → 我的收藏（带商品信息）。查询失败回 503，不伪装成「没有收藏」。 */
    private void getFavorites(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        java.util.List<Map<String, Object>> favorites = FamilyChatServer.getDb().getFavorites(userId);
        if (favorites == null) {
            ctx.status(503).json(Map.of("error", "收藏加载失败，请稍后重试"));
            return;
        }
        ctx.json(Map.of("favorites", favorites));
    }

    /** GET /api/mall/favorites/{productId} → {favorited: bool}。 */
    private void isFavorite(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int productId;
        try { productId = Integer.parseInt(ctx.pathParam("productId")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        ctx.json(Map.of("favorited", FamilyChatServer.getDb().isFavorite(userId, productId)));
    }

    // ===== 收货地址簿 =====

    /** GET /api/mall/addresses → 我的地址列表（默认在前）。 */
    private void getAddresses(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        ctx.json(Map.of("addresses", FamilyChatServer.getDb().getAddresses(userId)));
    }

    /** POST /api/mall/addresses  body {name, phone, address}。 */
    private void addAddress(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String name = body.get("name") instanceof String s ? s.trim() : "";
        String phone = body.get("phone") instanceof String s ? s.trim() : "";
        String address = body.get("address") instanceof String s ? s.trim() : "";
        if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
            ctx.status(400).json(Map.of("error", "请填写完整的收货人、手机号和地址"));
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            ctx.status(400).json(Map.of("error", "手机号格式不正确"));
            return;
        }
        long addrId = FamilyChatServer.getDb().addAddress(userId, name, phone, address);
        if (addrId <= 0) { ctx.status(500).json(Map.of("error", "保存失败，请稍后重试")); return; }
        ctx.json(Map.of("success", true, "address_id", addrId));
    }

    /** PUT /api/mall/addresses/{id}  body {name, phone, address}。 */
    private void updateAddress(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的地址ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String name = body.get("name") instanceof String s ? s.trim() : "";
        String phone = body.get("phone") instanceof String s ? s.trim() : "";
        String address = body.get("address") instanceof String s ? s.trim() : "";
        if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
            ctx.status(400).json(Map.of("error", "请填写完整的收货人、手机号和地址"));
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            ctx.status(400).json(Map.of("error", "手机号格式不正确"));
            return;
        }
        if (!FamilyChatServer.getDb().updateAddress(userId, id, name, phone, address)) {
            ctx.status(404).json(Map.of("error", "地址不存在或无权修改"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    /** DELETE /api/mall/addresses/{id}。 */
    private void deleteAddress(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的地址ID")); return; }
        if (!FamilyChatServer.getDb().deleteAddress(userId, id)) {
            ctx.status(404).json(Map.of("error", "地址不存在或无权删除"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/addresses/{id}/default → 设为默认地址。 */
    private void setDefaultAddress(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的地址ID")); return; }
        if (!FamilyChatServer.getDb().setDefaultAddress(userId, id)) {
            ctx.status(404).json(Map.of("error", "地址不存在或无权操作"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    // ===== 评价 =====

    /** POST /api/mall/orders/{id}/review  body {rating, content}。仅 completed 订单可评。 */
    private void addReview(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        int rating = body.get("rating") instanceof Number rn ? rn.intValue() : 5;
        String content = body.get("content") instanceof String s ? s.trim() : "";
        String err = FamilyChatServer.getDb().addReview(userId, id, rating, content);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        ctx.json(Map.of("success", true));
    }

    // ===== 退款 / 售后 =====

    /** POST /api/mall/orders/{id}/refund  body {reason}。买家申请，通知卖家。 */
    private void applyRefund(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { body = Map.of(); }
        String reason = body.get("reason") instanceof String s ? s.trim() : "";
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().applyRefund(id, userId, reason);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        if (order != null) {
            int sellerId = order.get("seller_id") instanceof Number sn ? sn.intValue() : 0;
            if (sellerId > 0 && sellerId != userId) {
                WsHandler.sendToUser(sellerId, Map.of(
                        "type", "mall_refund_apply",
                        "order_id", id,
                        "reason", reason,
                        "product_name", order.getOrDefault("product_name", "")));
            }
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/orders/{id}/refund/approve  卖家同意退款（资金原路退回）。 */
    private void approveRefund(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().approveRefund(id, userId);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        System.out.println("[MALL] uid=" + userId + " approve refund order " + id);
        if (order != null) {
            int buyerId = order.get("buyer_id") instanceof Number bn ? bn.intValue() : 0;
            pushWallet(userId);
            if (buyerId > 0) pushWallet(buyerId);
            if (buyerId > 0 && buyerId != userId) {
                WsHandler.sendToUser(buyerId, Map.of(
                        "type", "mall_refund_result",
                        "order_id", id,
                        "approved", true,
                        "product_name", order.getOrDefault("product_name", "")));
            }
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/orders/{id}/refund/reject  卖家拒绝退款。 */
    private void rejectRefund(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().rejectRefund(id, userId);
        if (err != null) { ctx.status(400).json(Map.of("error", err)); return; }
        if (order != null) {
            int buyerId = order.get("buyer_id") instanceof Number bn ? bn.intValue() : 0;
            if (buyerId > 0 && buyerId != userId) {
                WsHandler.sendToUser(buyerId, Map.of(
                        "type", "mall_refund_result",
                        "order_id", id,
                        "approved", false,
                        "product_name", order.getOrDefault("product_name", "")));
            }
        }
        ctx.json(Map.of("success", true));
    }

    // ===== 商家后台 =====

    /** GET /api/mall/merchant/products → 我的商品（含下架）。 */
    private void getSellerProducts(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        ctx.json(Map.of("products", FamilyChatServer.getDb().getSellerProducts(userId)));
    }

    /** GET /api/mall/merchant/orders → 店铺订单（购买账单，含买家昵称/手机/地址）。 */
    private void getSellerOrders(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        ctx.json(Map.of("orders", FamilyChatServer.getDb().getSellerOrders(userId)));
    }

    /**
     * POST /api/mall/products  body {name, category, price(分), stock, description, image_url}
     * 发布商品。价格校验：>0。返回新商品 id。
     */
    private void createProduct(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        String name = body.get("name") instanceof String s ? s.trim() : "";
        String category = body.get("category") instanceof String s ? s.trim() : "";
        String description = body.get("description") instanceof String s ? s.trim() : "";
        String imageUrl = body.get("image_url") instanceof String s ? s.trim() : "";
        long price;
        int stock = 0;
        try {
            price = ((Number) body.get("price")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 price")); return;
        }
        if (name.isEmpty()) { ctx.status(400).json(Map.of("error", "商品名称不能为空")); return; }
        if (price <= 0) { ctx.status(400).json(Map.of("error", "商品价格必须大于 0")); return; }
        Object stObj = body.get("stock");
        if (stObj instanceof Number sn) stock = Math.max(0, sn.intValue());

        long productId = FamilyChatServer.getDb().insertProduct(
                userId, name, category, price, stock, description, imageUrl);
        if (productId <= 0) {
            ctx.status(500).json(Map.of("error", "发布失败，请稍后重试"));
            return;
        }
        System.out.println("[MALL] uid=" + userId + " publish product " + productId);
        ctx.json(Map.of("success", true, "product_id", productId));
    }

    /** PUT /api/mall/products/{id}  编辑自己的商品。 */
    private void updateProduct(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        String name = body.get("name") instanceof String s ? s.trim() : "";
        String category = body.get("category") instanceof String s ? s.trim() : "";
        String description = body.get("description") instanceof String s ? s.trim() : "";
        String imageUrl = body.get("image_url") instanceof String s ? s.trim() : "";
        long price;
        int stock = 0;
        try {
            price = ((Number) body.get("price")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 price")); return;
        }
        if (name.isEmpty()) { ctx.status(400).json(Map.of("error", "商品名称不能为空")); return; }
        if (price <= 0) { ctx.status(400).json(Map.of("error", "商品价格必须大于 0")); return; }
        Object stObj = body.get("stock");
        if (stObj instanceof Number sn) stock = Math.max(0, sn.intValue());

        if (!FamilyChatServer.getDb().updateProduct(
                userId, id, name, category, price, stock, description, imageUrl)) {
            ctx.status(404).json(Map.of("error", "商品不存在或无权修改"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/products/{id}/toggle  上架/下架切换。 */
    private void toggleProduct(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        int state = FamilyChatServer.getDb().toggleProduct(userId, id);
        if (state < 0) {
            ctx.status(404).json(Map.of("error", "商品不存在或无权操作"));
            return;
        }
        ctx.json(Map.of("success", true, "on_sale", state));
    }

    /** DELETE /api/mall/products/{id}  删除自己的商品。 */
    private void deleteProduct(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的商品ID")); return; }
        if (!FamilyChatServer.getDb().deleteProduct(userId, id)) {
            ctx.status(404).json(Map.of("error", "商品不存在或无权删除"));
            return;
        }
        ctx.json(Map.of("success", true));
    }

    /** POST /api/mall/orders/{id}/ship  body {shipping_company, tracking_no} 卖家发货。通知买家。 */
    private void shipOrder(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int id;
        try { id = Integer.parseInt(ctx.pathParam("id")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的订单ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { body = Map.of(); }
        String company = body.get("shipping_company") instanceof String s ? s.trim() : "";
        String trackingNo = body.get("tracking_no") instanceof String s ? s.trim() : "";
        Map<String, Object> order = FamilyChatServer.getDb().getOrder(id);
        String err = FamilyChatServer.getDb().shipOrder(id, userId, company, trackingNo);
        if (err != null) {
            ctx.status(400).json(Map.of("error", err));
            return;
        }
        System.out.println("[MALL] uid=" + userId + " ship order " + id);
        if (order != null) {
            int buyerId = order.get("buyer_id") instanceof Number bn ? bn.intValue() : 0;
            if (buyerId > 0 && buyerId != userId) {
                WsHandler.sendToUser(buyerId, Map.of(
                        "type", "mall_order_shipped",
                        "order_id", id,
                        "product_name", order.getOrDefault("product_name", ""),
                        "shipping_company", company,
                        "tracking_no", trackingNo));
            }
        }
        ctx.json(Map.of("success", true));
    }

    // ===== 工具 =====

    private int parsePositive(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 向用户推送钱包余额变化（wallet_updated），支付后双端刷新。 */
    private void pushWallet(int userId) {
        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        WsHandler.sendToUser(userId, Map.of("type", "wallet_updated",
                "balance", w != null ? w.get("balance") : 0));
    }
}
