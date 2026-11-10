package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 商城领域 DAO（原 Database.java 从 getProducts 起至文件末尾的全部商城方法原样搬移，
 * 仅改连接获取方式与共享助手调用路径，不改变任何逻辑）。
 */
public class MallDao {
    private final Database db;

    public MallDao(Database db) {
        this.db = db;
    }

    // ===== Mall =====

    /** 商城商品列表（仅上架商品；category 过滤、"全部"=不过滤；keyword 匹配名称/描述）。 */
    public List<Map<String, Object>> getProducts(String category, String keyword) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT p.id,p.name,p.category,p.price,p.description,p.image_url,p.sales,p.stock,p.seller_id," +
                        "COALESCE(u.nickname,'官方') AS seller_nickname " +
                        "FROM products p LEFT JOIN users u ON u.id=p.seller_id WHERE p.on_sale=1 AND p.stock>0");
        if (category != null && !category.isEmpty() && !"全部".equals(category)) {
            sql.append(" AND category=?");
            params.add(category);
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (name LIKE ? OR description LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }
        sql.append(" ORDER BY p.sort_order ASC, p.id ASC");
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getProducts: " + e.getMessage());
        }
        return list;
    }

    /** 商城分类（首页金刚区宫格，按 sort_order/id 排序）。 */
    public List<Map<String, Object>> getCategories() {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,color,sort_order FROM categories ORDER BY sort_order ASC, id ASC");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getCategories: " + e.getMessage());
        }
        return list;
    }

    // ===== 商城（阶段3）：商品详情 / 商家后台 / 订单闭环 =====

    /** 单商品详情（含卖家昵称）。 */
    public Map<String, Object> getProduct(int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT p.id,p.name,p.category,p.price,p.description,p.image_url,p.sales,p.stock,p.on_sale,p.seller_id," +
                            "COALESCE(u.nickname,'官方') AS seller_nickname " +
                            "FROM products p LEFT JOIN users u ON u.id=p.seller_id WHERE p.id=?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getProduct: " + e.getMessage());
        }
        return null;
    }

    /** 发布商品（卖家）。返回新商品 id；失败返回 0。 */
    public long insertProduct(int sellerId, String name, String category, long price, int stock,
                              String description, String imageUrl) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO products (name,category,price,description,image_url,stock,on_sale,seller_id,sort_order,created_at) " +
                            "VALUES (?,?,?,?,?,?,1,?,0,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, category != null && !category.isEmpty() ? category : "生活");
            ps.setLong(3, price);
            ps.setString(4, description != null ? description : "");
            ps.setString(5, imageUrl != null ? imageUrl : "");
            ps.setInt(6, Math.max(0, stock));
            ps.setInt(7, sellerId);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            System.err.println("[ERROR] insertProduct: " + e.getMessage());
        }
        return 0L;
    }

    /** 编辑商品（仅本人）。 */
    public boolean updateProduct(int sellerId, int id, String name, String category, long price, int stock,
                                 String description, String imageUrl) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE products SET name=?,category=?,price=?,description=?,image_url=?,stock=? WHERE id=? AND seller_id=?");
            ps.setString(1, name);
            ps.setString(2, category != null && !category.isEmpty() ? category : "生活");
            ps.setLong(3, price);
            ps.setString(4, description != null ? description : "");
            ps.setString(5, imageUrl != null ? imageUrl : "");
            ps.setInt(6, Math.max(0, stock));
            ps.setInt(7, id);
            ps.setInt(8, sellerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] updateProduct: " + e.getMessage());
        }
        return false;
    }

    /** 删除商品（仅本人）。 */
    public boolean deleteProduct(int sellerId, int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("DELETE FROM products WHERE id=? AND seller_id=?");
            ps.setInt(1, id);
            ps.setInt(2, sellerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteProduct: " + e.getMessage());
        }
        return false;
    }

    /** 上架/下架切换（仅本人）。返回新状态（1=上架，0=下架）；失败返回 -1。 */
    public int toggleProduct(int sellerId, int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE products SET on_sale = 1 - on_sale WHERE id=? AND seller_id=?");
            ps.setInt(1, id);
            ps.setInt(2, sellerId);
            if (ps.executeUpdate() == 0) return -1;
            PreparedStatement q = c.prepareStatement("SELECT on_sale FROM products WHERE id=?");
            q.setInt(1, id);
            ResultSet rs = q.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[ERROR] toggleProduct: " + e.getMessage());
        }
        return -1;
    }

    /** 我的商品（含下架），新→旧。 */
    public List<Map<String, Object>> getSellerProducts(int sellerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,category,price,description,image_url,stock,on_sale,sales FROM products WHERE seller_id=? ORDER BY id DESC");
            ps.setInt(1, sellerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getSellerProducts: " + e.getMessage());
        }
        return list;
    }

    /**
     * 下单（事务）：查商品 on_sale=1 AND stock>=qty → 扣库存 → 插订单。
     * 成功返回订单 map（含 error 之外的完整行）；失败返回 Map.of("error", 中文原因)。
     */
    public Map<String, Object> createOrder(int buyerId, int productId, int quantity,
                                           String receiverName, String receiverPhone, String receiverAddress) {
        if (quantity <= 0) quantity = 1;
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement gp = c.prepareStatement(
                        "SELECT price,seller_id,stock FROM products WHERE id=? AND on_sale=1");
                gp.setInt(1, productId);
                ResultSet rs = gp.executeQuery();
                if (!rs.next()) { c.rollback(); return Map.of("error", "商品不存在或已下架"); }
                long price = rs.getLong("price");
                int sellerId = rs.getInt("seller_id");
                int stock = rs.getInt("stock");
                if (sellerId == buyerId) { c.rollback(); return Map.of("error", "不能购买自己发布的商品"); }
                if (stock < quantity) { c.rollback(); return Map.of("error", "库存不足"); }
                long amount = price * quantity;
                String orderNo = "MO" + System.currentTimeMillis() + buyerId
                        + "-" + UUID.randomUUID().toString().substring(0, 6); // 随机后缀防同毫秒 UNIQUE 冲突
                // 条件更新防超卖（TOCTOU）：SELECT 检查只是快速失败，这里才是权威守卫
                PreparedStatement up = c.prepareStatement(
                        "UPDATE products SET stock=stock-? WHERE id=? AND stock>=? AND on_sale=1");
                up.setInt(1, quantity);
                up.setInt(2, productId);
                up.setInt(3, quantity);
                if (up.executeUpdate() == 0) { c.rollback(); return Map.of("error", "库存不足或商品已下架"); }
                PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO mall_orders (order_no,product_id,seller_id,buyer_id,quantity,amount,status," +
                                "receiver_name,receiver_phone,receiver_address,created_at) " +
                                "VALUES (?,?,?,?,?,?,'pending',?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ins.setString(1, orderNo);
                ins.setInt(2, productId);
                ins.setInt(3, sellerId);
                ins.setInt(4, buyerId);
                ins.setInt(5, quantity);
                ins.setLong(6, amount);
                ins.setString(7, receiverName);
                ins.setString(8, receiverPhone);
                ins.setString(9, receiverAddress);
                ins.setLong(10, System.currentTimeMillis());
                ins.executeUpdate();
                long orderId;
                ResultSet keys = ins.getGeneratedKeys();
                if (!keys.next()) { c.rollback(); return Map.of("error", "下单失败，请稍后重试"); }
                orderId = keys.getLong(1);
                c.commit();
                return getOrder((int) orderId);
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] createOrder: " + e.getMessage());
                return Map.of("error", "下单失败，请稍后重试");
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] createOrder: " + e.getMessage());
            return Map.of("error", "下单失败，请稍后重试");
        }
    }

    /**
     * 批量下单（购物车结算，单事务）：items=[{product_id,quantity},...]。
     * 逐项校验（上架/非本人/库存）并扣库存建单，任一失败整体回滚。
     * 成功返回 {orders:[...]}；失败返回 {error: 中文原因}。
     */
    public Map<String, Object> createOrders(int buyerId, List<Map<String, Object>> items,
                                            String receiverName, String receiverPhone, String receiverAddress) {
        if (items == null || items.isEmpty()) return DbUtil.err("购物车为空");
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                List<Map<String, Object>> orders = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    int productId = item.get("product_id") instanceof Number n ? n.intValue() : 0;
                    int quantity = item.get("quantity") instanceof Number qn ? Math.max(1, qn.intValue()) : 1;
                    PreparedStatement gp = c.prepareStatement(
                            "SELECT price,seller_id,stock FROM products WHERE id=? AND on_sale=1");
                    gp.setInt(1, productId);
                    ResultSet rs = gp.executeQuery();
                    if (!rs.next()) { c.rollback(); return DbUtil.err("部分商品不存在或已下架"); }
                    long price = rs.getLong("price");
                    int sellerId = rs.getInt("seller_id");
                    int stock = rs.getInt("stock");
                    if (sellerId == buyerId) { c.rollback(); return DbUtil.err("不能购买自己发布的商品"); }
                    if (stock < quantity) { c.rollback(); return DbUtil.err("部分商品库存不足"); }
                    long amount = price * quantity;
                    String orderNo = "MO" + System.currentTimeMillis() + buyerId + productId
                            + "-" + UUID.randomUUID().toString().substring(0, 6); // 随机后缀防同毫秒 UNIQUE 冲突
                    // 条件更新防超卖（TOCTOU）：SELECT 检查只是快速失败，这里才是权威守卫
                    PreparedStatement up = c.prepareStatement(
                            "UPDATE products SET stock=stock-? WHERE id=? AND stock>=? AND on_sale=1");
                    up.setInt(1, quantity);
                    up.setInt(2, productId);
                    up.setInt(3, quantity);
                    if (up.executeUpdate() == 0) { c.rollback(); return DbUtil.err("部分商品库存不足或已下架"); }
                    PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO mall_orders (order_no,product_id,seller_id,buyer_id,quantity,amount,status," +
                                    "receiver_name,receiver_phone,receiver_address,created_at) " +
                                    "VALUES (?,?,?,?,?,?,'pending',?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ins.setString(1, orderNo);
                    ins.setInt(2, productId);
                    ins.setInt(3, sellerId);
                    ins.setInt(4, buyerId);
                    ins.setInt(5, quantity);
                    ins.setLong(6, amount);
                    ins.setString(7, receiverName);
                    ins.setString(8, receiverPhone);
                    ins.setString(9, receiverAddress);
                    ins.setLong(10, System.currentTimeMillis());
                    ins.executeUpdate();
                    ResultSet keys = ins.getGeneratedKeys();
                    if (!keys.next()) { c.rollback(); return DbUtil.err("下单失败，请稍后重试"); }
                    orders.add(orderRowInTx(c, (int) keys.getLong(1)));
                }
                c.commit();
                return Map.of("orders", orders);
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] createOrders: " + e.getMessage());
                return DbUtil.err("下单失败，请稍后重试");
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] createOrders: " + e.getMessage());
            return DbUtil.err("下单失败，请稍后重试");
        }
    }

    /** 事务内按 id 查订单行（供 createOrders 在未提交事务中读取）。 */
    private Map<String, Object> orderRowInTx(Connection c, int orderId) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT o.*, p.name AS product_name, p.image_url AS product_image, u.nickname AS buyer_nickname " +
                        "FROM mall_orders o LEFT JOIN products p ON p.id=o.product_id " +
                        "LEFT JOIN users u ON u.id=o.buyer_id WHERE o.id=?");
        ps.setInt(1, orderId);
        ResultSet rs = ps.executeQuery();
        return rs.next() ? DbUtil.rowToMap(rs) : new HashMap<>();
    }

    /** 我的订单（买家），带商品名/图，新→旧。 */
    public List<Map<String, Object>> getMyOrders(int buyerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT o.*, p.name AS product_name, p.image_url AS product_image " +
                            "FROM mall_orders o LEFT JOIN products p ON p.id=o.product_id WHERE o.buyer_id=? ORDER BY o.id DESC");
            ps.setInt(1, buyerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getMyOrders: " + e.getMessage());
        }
        return list;
    }

    /** 店铺订单（卖家=购买账单），带商品名/图、买家昵称，新→旧。 */
    public List<Map<String, Object>> getSellerOrders(int sellerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT o.*, p.name AS product_name, p.image_url AS product_image, u.nickname AS buyer_nickname " +
                            "FROM mall_orders o LEFT JOIN products p ON p.id=o.product_id " +
                            "LEFT JOIN users u ON u.id=o.buyer_id WHERE o.seller_id=? ORDER BY o.id DESC");
            ps.setInt(1, sellerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getSellerOrders: " + e.getMessage());
        }
        return list;
    }

    /** 按 id 查订单。 */
    public Map<String, Object> getOrder(int orderId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT o.*, p.name AS product_name, p.image_url AS product_image, u.nickname AS buyer_nickname " +
                            "FROM mall_orders o LEFT JOIN products p ON p.id=o.product_id " +
                            "LEFT JOIN users u ON u.id=o.buyer_id WHERE o.id=?");
            ps.setInt(1, orderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getOrder: " + e.getMessage());
        }
        return null;
    }

    /** 事务内业务失败（携带用户可见中文原因）。 */
    private static class TxFail extends RuntimeException {
        TxFail(String msg) { super(msg); }
    }

    /**
     * 支付（事务）：pending→paid → 扣买家余额 → <b>钱进托管池</b> → 买家侧记账 → 累加销量。
     *
     * <p>审计第 4 条：卖家此时<b>不入账</b>，要等买家确认收货（{@link #confirmOrder}）
     * 或超时自动确认（{@link #autoCompleteOrders}）才放款。退款则原路退回买家，
     * 全程不需要卖家余额配合。
     *
     * <p>成功返回 null；失败返回中文错误。
     */
    public String payOrder(int orderId, int buyerId) {
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                String err = payOrderInTx(c, orderId, buyerId);
                if (err != null) { c.rollback(); return err; }
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] payOrder: " + e.getMessage());
                return "支付失败，请稍后重试";
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] payOrder: " + e.getMessage());
            return "支付失败，请稍后重试";
        }
    }

    /**
     * 批量支付（购物车结算）：事务内逐单支付，任一失败整体回滚。
     * 成功返回 null；失败返回中文错误。
     */
    public String payOrders(List<Integer> orderIds, int buyerId) {
        if (orderIds == null || orderIds.isEmpty()) return "没有待支付的订单";
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                for (int orderId : orderIds) {
                    String err = payOrderInTx(c, orderId, buyerId);
                    if (err != null) { c.rollback(); return err; }
                }
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] payOrders: " + e.getMessage());
                return "支付失败，请稍后重试";
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] payOrders: " + e.getMessage());
            return "支付失败，请稍后重试";
        }
    }

    /** 事务内支付单笔订单（复用：单笔支付/批量支付）。返回 null=成功；否则中文错误。 */
    private String payOrderInTx(Connection c, int orderId, int buyerId) throws SQLException {
        PreparedStatement q = c.prepareStatement(
                "SELECT amount,seller_id,buyer_id,product_id,quantity,status FROM mall_orders WHERE id=?");
        q.setInt(1, orderId);
        ResultSet rs = q.executeQuery();
        if (!rs.next()) return "订单不存在";
        if (!"pending".equals(rs.getString("status"))) return "订单状态已变更，无法支付";
        if (rs.getInt("buyer_id") != buyerId) return "无权操作该订单";
        long amount = rs.getLong("amount");
        int sellerId = rs.getInt("seller_id");
        int productId = rs.getInt("product_id");
        int quantity = rs.getInt("quantity");
        // 条件更新防并发重复支付
        PreparedStatement up = c.prepareStatement(
                "UPDATE mall_orders SET status='paid',paid_at=? WHERE id=? AND status='pending'");
        up.setLong(1, System.currentTimeMillis());
        up.setInt(2, orderId);
        if (up.executeUpdate() == 0) return "订单状态已变更，无法支付";
        // ★ 审计第 4 条：钱先托管，不直接进卖家余额 —— 卖家此时还没发货，
        //   直接入账意味着"钱货两空"风险全由买家承担（卖家跑路/不发货）。
        //   注意：买家扣款在 holdEscrow 内部完成，这里绝不能再扣一次（否则重复扣款）。
        WalletDao.ensureWallet(c, buyerId);
        WalletDao.ensureWallet(c, sellerId);
        // 余额不足要给出精确文案（客户端会直接展示给用户），所以这里先探一次；
        // holdEscrow 内部仍有权威的条件扣款守卫，重复检查不会造成"查了却扣不到"的漏洞。
        if (!hasEnoughBalance(c, buyerId, amount)) return "余额不足";
        holdEscrow(c, orderId, buyerId, sellerId, amount);
        // 买家侧账单（支出）
        addTransaction(c, buyerId, sellerId, "mall", amount, "商城购物(托管)", "completed", orderId);
        // 累加销量
        PreparedStatement s = c.prepareStatement("UPDATE products SET sales=sales+? WHERE id=?");
        s.setInt(1, quantity);
        s.setInt(2, productId);
        s.executeUpdate();
        return null;
    }

    /** 买家取消待付款订单（事务）：pending→cancelled，恢复库存。成功返回 null。 */
    public String cancelOrder(int orderId, int buyerId) {
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement q = c.prepareStatement(
                        "SELECT product_id,quantity,status,buyer_id FROM mall_orders WHERE id=?");
                q.setInt(1, orderId);
                ResultSet rs = q.executeQuery();
                if (!rs.next()) { c.rollback(); return "订单不存在"; }
                if (rs.getInt("buyer_id") != buyerId) { c.rollback(); return "无权操作该订单"; }
                if (!"pending".equals(rs.getString("status"))) { c.rollback(); return "订单状态已变更，无法取消"; }
                int productId = rs.getInt("product_id");
                int quantity = rs.getInt("quantity");
                PreparedStatement up = c.prepareStatement(
                        "UPDATE mall_orders SET status='cancelled',cancel_time=? WHERE id=? AND status='pending'");
                up.setLong(1, System.currentTimeMillis());
                up.setInt(2, orderId);
                if (up.executeUpdate() == 0) { c.rollback(); return "订单状态已变更，无法取消"; }
                PreparedStatement st = c.prepareStatement("UPDATE products SET stock=stock+? WHERE id=?");
                st.setInt(1, quantity);
                st.setInt(2, productId);
                st.executeUpdate();
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] cancelOrder: " + e.getMessage());
                return "取消失败，请稍后重试";
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] cancelOrder: " + e.getMessage());
            return "取消失败，请稍后重试";
        }
    }

    /** 卖家发货：paid→shipped（记录快递公司/单号，单号必填）。成功返回 null。 */
    public String shipOrder(int orderId, int sellerId, String company, String trackingNo) {
        try (Connection c = db.getConn()) {
            PreparedStatement q = c.prepareStatement(
                    "SELECT status,refund_status FROM mall_orders WHERE id=? AND seller_id=?");
            q.setInt(1, orderId);
            q.setInt(2, sellerId);
            ResultSet rs = q.executeQuery();
            if (!rs.next()) return "订单不存在";
            if (!"paid".equals(rs.getString("status"))) return "订单状态已变更，无法发货";
            if (!rs.getString("refund_status").isEmpty()) return "该订单有退款申请，暂不能发货";
            if (trackingNo == null || trackingNo.trim().isEmpty()) return "请填写快递单号";
            PreparedStatement up = c.prepareStatement(
                    "UPDATE mall_orders SET status='shipped',shipped_at=?,shipping_company=?,tracking_no=? " +
                            "WHERE id=? AND seller_id=? AND status='paid' AND refund_status=''");
            up.setLong(1, System.currentTimeMillis());
            up.setString(2, company != null ? company : "");
            up.setString(3, trackingNo.trim());
            up.setInt(4, orderId);
            up.setInt(5, sellerId);
            return up.executeUpdate() > 0 ? null : "订单状态已变更，无法发货";
        } catch (SQLException e) {
            System.err.println("[ERROR] shipOrder: " + e.getMessage());
            return "发货失败，请稍后重试";
        }
    }

    /**
     * 买家确认收货：shipped→completed（退款处理中禁止确认）。
     *
     * <p>审计第 4 条后这里多了一步<b>放款</b>：状态更新与"托管→卖家余额"必须同一事务，
     * 否则可能出现"订单已确认收货但卖家没拿到钱"（买家已失去退款依据，钱卡在托管池）。
     * 成功返回 null。
     */
    public String confirmOrder(int orderId, int buyerId) {
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement q = c.prepareStatement(
                        "SELECT status,refund_status FROM mall_orders WHERE id=? AND buyer_id=?");
                q.setInt(1, orderId);
                q.setInt(2, buyerId);
                ResultSet rs = q.executeQuery();
                if (!rs.next()) { c.rollback(); return "订单不存在"; }
                if (!"shipped".equals(rs.getString("status"))) { c.rollback(); return "订单状态已变更，无法确认收货"; }
                if (!rs.getString("refund_status").isEmpty()) { c.rollback(); return "退款处理中，暂不能确认收货"; }
                PreparedStatement up = c.prepareStatement(
                        "UPDATE mall_orders SET status='completed',completed_at=? " +
                                "WHERE id=? AND buyer_id=? AND status='shipped' AND refund_status=''");
                up.setLong(1, System.currentTimeMillis());
                up.setInt(2, orderId);
                up.setInt(3, buyerId);
                if (up.executeUpdate() <= 0) { c.rollback(); return "订单状态已变更，无法确认收货"; }
                // 条件更新成功才放款，且同一事务：不会重复放款（escrow 标记已置 0）
                releaseEscrowToSeller(c, orderId);
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] confirmOrder: " + e.getMessage());
                return "操作失败，请稍后重试";
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] confirmOrder: " + e.getMessage());
            return "操作失败，请稍后重试";
        }
    }

    // ===== Mall（阶段4完善）：分页 / 购物车 / 收藏 / 地址簿 / 评价 / 退款 / 热搜 / 店铺 =====

    /**
     * 商品列表（分页版）。仅上架且有库存；category 过滤；keyword 匹配名称/描述。
     * 返回 {products, has_more, total}。
     */
    public Map<String, Object> getProductsPage(String category, String keyword, int page, int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        StringBuilder where = new StringBuilder(" WHERE p.on_sale=1 AND p.stock>0");
        List<Object> params = new ArrayList<>();
        if (category != null && !category.isEmpty() && !"全部".equals(category)) {
            where.append(" AND p.category=?");
            params.add(category);
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            where.append(" AND (p.name LIKE ? OR p.description LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }
        Map<String, Object> out = new HashMap<>();
        try (Connection c = db.getConn()) {
            PreparedStatement cnt = c.prepareStatement(
                    "SELECT COUNT(*) FROM products p" + where);
            for (int i = 0; i < params.size(); i++) cnt.setObject(i + 1, params.get(i));
            ResultSet crs = cnt.executeQuery();
            int total = crs.next() ? crs.getInt(1) : 0;

            PreparedStatement ps = c.prepareStatement(
                    "SELECT p.id,p.name,p.category,p.price,p.description,p.image_url,p.sales,p.stock,p.seller_id," +
                            "COALESCE(u.nickname,'官方') AS seller_nickname " +
                            "FROM products p LEFT JOIN users u ON u.id=p.seller_id" + where +
                            " ORDER BY p.sort_order ASC, p.id ASC LIMIT ? OFFSET ?");
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ps.setInt(params.size() + 1, pageSize);
            ps.setInt(params.size() + 2, (page - 1) * pageSize);
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
            out.put("products", list);
            out.put("total", total);
            out.put("has_more", (long) page * pageSize < total);
        } catch (SQLException e) {
            System.err.println("[ERROR] getProductsPage: " + e.getMessage());
            out.put("products", new ArrayList<>());
            out.put("total", 0);
            out.put("has_more", false);
        }
        return out;
    }

    /** 卖家店铺主页：该卖家上架且有库存的商品（不含本人隐私字段）。 */
    public List<Map<String, Object>> getSellerPublicProducts(int sellerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,category,price,description,image_url,sales,stock,seller_id " +
                            "FROM products WHERE seller_id=? AND on_sale=1 AND stock>0 ORDER BY sort_order ASC, id ASC");
            ps.setInt(1, sellerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getSellerPublicProducts: " + e.getMessage());
        }
        return list;
    }

    // ── 购物车 ──

    /**
     * 我的购物车（带商品信息，新加入在前）。
     * 查询失败返回 {@code null}（调用方映射为 5xx），与「购物车为空」的空列表严格区分：
     * 原先 SQLException 被吞掉后返回空列表，handler 会回 200 + items:[]，
     * 用户在客户端看到的是「购物车空了」而不是「服务端出错了」，属于静默错误。
     */
    public List<Map<String, Object>> getCart(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT c.id AS cart_id,c.product_id,c.quantity,c.created_at," +
                            "p.name,p.price,p.image_url,p.stock,p.on_sale,p.sales " +
                            "FROM mall_cart c LEFT JOIN products p ON p.id=c.product_id " +
                            "WHERE c.user_id=? ORDER BY c.id DESC");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getCart: " + e.getMessage());
            return null;   // 查询失败 ≠ 无数据
        }
        return list;
    }

    /**
     * 加购（幂等合并数量）。校验商品存在且上架。
     * 返回 null=成功；否则返回中文错误。
     */
    public String addCart(int userId, int productId, int quantity) {
        if (quantity < 1) quantity = 1;
        try (Connection c = db.getConn()) {
            PreparedStatement gp = c.prepareStatement(
                    "SELECT id,on_sale,stock,seller_id FROM products WHERE id=?");
            gp.setInt(1, productId);
            ResultSet rs = gp.executeQuery();
            if (!rs.next()) return "商品不存在";
            if (rs.getInt("on_sale") != 1) return "商品已下架";
            if (rs.getInt("seller_id") == userId) return "不能购买自己发布的商品";
            if (rs.getInt("stock") < 1) return "库存不足";
            PreparedStatement chk = c.prepareStatement(
                    "SELECT id,quantity FROM mall_cart WHERE user_id=? AND product_id=?");
            chk.setInt(1, userId);
            chk.setInt(2, productId);
            ResultSet crs = chk.executeQuery();
            if (crs.next()) {
                PreparedStatement up = c.prepareStatement(
                        "UPDATE mall_cart SET quantity=quantity+? WHERE id=?");
                up.setInt(1, quantity);
                up.setInt(2, crs.getInt("id"));
                up.executeUpdate();
            } else {
                PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO mall_cart (user_id,product_id,quantity,created_at) VALUES (?,?,?,?)");
                ins.setInt(1, userId);
                ins.setInt(2, productId);
                ins.setInt(3, quantity);
                ins.setLong(4, System.currentTimeMillis());
                ins.executeUpdate();
            }
            return null;
        } catch (SQLException e) {
            System.err.println("[ERROR] addCart: " + e.getMessage());
            return "加购失败，请稍后重试";
        }
    }

    /** 改购物车数量（仅本人）。失败返回中文错误。 */
    public String updateCartQuantity(int userId, int cartId, int quantity) {
        if (quantity < 1) return "数量至少为 1";
        try (Connection c = db.getConn()) {
            PreparedStatement q = c.prepareStatement(
                    "SELECT p.stock FROM mall_cart c LEFT JOIN products p ON p.id=c.product_id WHERE c.id=? AND c.user_id=?");
            q.setInt(1, cartId);
            q.setInt(2, userId);
            ResultSet rs = q.executeQuery();
            if (!rs.next()) return "购物车项不存在";
            int stock = rs.getInt("stock");
            if (quantity > stock) return "库存不足，最多 " + stock + " 件";
            PreparedStatement up = c.prepareStatement("UPDATE mall_cart SET quantity=? WHERE id=? AND user_id=?");
            up.setInt(1, quantity);
            up.setInt(2, cartId);
            up.setInt(3, userId);
            up.executeUpdate();
            return null;
        } catch (SQLException e) {
            System.err.println("[ERROR] updateCartQuantity: " + e.getMessage());
            return "操作失败，请稍后重试";
        }
    }

    /** 删除购物车项（仅本人）。 */
    public boolean deleteCartItem(int userId, int cartId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("DELETE FROM mall_cart WHERE id=? AND user_id=?");
            ps.setInt(1, cartId);
            ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteCartItem: " + e.getMessage());
            return false;
        }
    }

    /** 按商品删除购物车项（结算成功后服务端兜底清理）。 */
    public boolean deleteCartByProduct(int userId, int productId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mall_cart WHERE user_id=? AND product_id=?");
            ps.setInt(1, userId);
            ps.setInt(2, productId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteCartByProduct: " + e.getMessage());
            return false;
        }
    }

    /** 清空购物车（下单成功后用）。 */
    public void clearCart(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("DELETE FROM mall_cart WHERE user_id=?");
            ps.setInt(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] clearCart: " + e.getMessage());
        }
    }

    // ── 商品收藏 ──

    /** 是否已收藏。 */
    public boolean isFavorite(int userId, int productId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM mall_favorites WHERE user_id=? AND product_id=?");
            ps.setInt(1, userId);
            ps.setInt(2, productId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("[ERROR] isFavorite: " + e.getMessage());
            return false;
        }
    }

    /** 收藏/取消收藏。返回新状态 true=已收藏。 */
    public boolean toggleFavorite(int userId, int productId) {
        try (Connection c = db.getConn()) {
            if (isFavorite(userId, productId)) {
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM mall_favorites WHERE user_id=? AND product_id=?");
                ps.setInt(1, userId);
                ps.setInt(2, productId);
                ps.executeUpdate();
                return false;
            }
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO mall_favorites (user_id,product_id,created_at) VALUES (?,?,?)");
            ps.setInt(1, userId);
            ps.setInt(2, productId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] toggleFavorite: " + e.getMessage());
            return isFavorite(userId, productId);
        }
    }

    /**
     * 我的收藏（带商品信息，新→旧）。
     * 查询失败返回 {@code null}（调用方映射为 5xx），与「没有收藏」的空列表严格区分。
     */
    public List<Map<String, Object>> getFavorites(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT f.id AS fav_id,f.product_id,f.created_at," +
                            "p.name,p.price,p.image_url,p.stock,p.on_sale,p.sales,p.seller_id," +
                            "COALESCE(u.nickname,'官方') AS seller_nickname " +
                            "FROM mall_favorites f LEFT JOIN products p ON p.id=f.product_id " +
                            "LEFT JOIN users u ON u.id=p.seller_id " +
                            "WHERE f.user_id=? ORDER BY f.id DESC");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getFavorites: " + e.getMessage());
            return null;   // 查询失败 ≠ 无数据
        }
        return list;
    }

    // ── 收货地址簿 ──

    /** 我的地址（默认在前）。 */
    public List<Map<String, Object>> getAddresses(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,phone,address,is_default FROM mall_addresses " +
                            "WHERE user_id=? ORDER BY is_default DESC, id DESC");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getAddresses: " + e.getMessage());
        }
        return list;
    }

    /** 新增地址；第一个地址自动设为默认。返回新地址 id。 */
    public long addAddress(int userId, String name, String phone, String address) {
        try (Connection c = db.getConn()) {
            PreparedStatement cnt = c.prepareStatement("SELECT COUNT(*) FROM mall_addresses WHERE user_id=?");
            cnt.setInt(1, userId);
            ResultSet crs = cnt.executeQuery();
            int isDefault = (!crs.next() || crs.getInt(1) == 0) ? 1 : 0;
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mall_addresses (user_id,name,phone,address,is_default,created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, userId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, address);
            ps.setInt(5, isDefault);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getLong(1);
        } catch (SQLException e) {
            System.err.println("[ERROR] addAddress: " + e.getMessage());
        }
        return 0L;
    }

    /** 编辑地址（仅本人）。 */
    public boolean updateAddress(int userId, int id, String name, String phone, String address) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE mall_addresses SET name=?,phone=?,address=? WHERE id=? AND user_id=?");
            ps.setString(1, name);
            ps.setString(2, phone);
            ps.setString(3, address);
            ps.setInt(4, id);
            ps.setInt(5, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] updateAddress: " + e.getMessage());
            return false;
        }
    }

    /** 删除地址（仅本人）。删除默认地址后自动指定最新一条为默认。 */
    public boolean deleteAddress(int userId, int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("DELETE FROM mall_addresses WHERE id=? AND user_id=?");
            ps.setInt(1, id);
            ps.setInt(2, userId);
            if (ps.executeUpdate() == 0) return false;
            PreparedStatement cnt = c.prepareStatement(
                    "SELECT COUNT(*) FROM mall_addresses WHERE user_id=? AND is_default=1");
            cnt.setInt(1, userId);
            ResultSet crs = cnt.executeQuery();
            if (crs.next() && crs.getInt(1) == 0) {
                PreparedStatement first = c.prepareStatement(
                        "SELECT id FROM mall_addresses WHERE user_id=? ORDER BY id DESC LIMIT 1");
                first.setInt(1, userId);
                ResultSet frs = first.executeQuery();
                if (frs.next()) {
                    PreparedStatement up = c.prepareStatement(
                            "UPDATE mall_addresses SET is_default=1 WHERE id=?");
                    up.setInt(1, frs.getInt("id"));
                    up.executeUpdate();
                }
            }
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteAddress: " + e.getMessage());
            return false;
        }
    }

    /** 设为默认地址（仅本人；先清默认再设）。 */
    public boolean setDefaultAddress(int userId, int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement chk = c.prepareStatement("SELECT id FROM mall_addresses WHERE id=? AND user_id=?");
            chk.setInt(1, id);
            chk.setInt(2, userId);
            if (!chk.executeQuery().next()) return false;
            PreparedStatement clear = c.prepareStatement(
                    "UPDATE mall_addresses SET is_default=0 WHERE user_id=?");
            clear.setInt(1, userId);
            clear.executeUpdate();
            PreparedStatement set = c.prepareStatement("UPDATE mall_addresses SET is_default=1 WHERE id=?");
            set.setInt(1, id);
            set.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] setDefaultAddress: " + e.getMessage());
            return false;
        }
    }

    /** 取默认地址（无则取最新一条）。 */
    public Map<String, Object> getDefaultAddress(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,phone,address,is_default FROM mall_addresses " +
                            "WHERE user_id=? ORDER BY is_default DESC, id DESC LIMIT 1");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getDefaultAddress: " + e.getMessage());
        }
        return null;
    }

    // ── 商品评价 ──

    /** 评价：仅 completed 订单、仅买家本人、一单一评。成功返回 null。 */
    public String addReview(int userId, int orderId, int rating, String content) {
        if (rating < 1) rating = 1;
        if (rating > 5) rating = 5;
        if (content != null && content.length() > 500) content = content.substring(0, 500);
        try (Connection c = db.getConn()) {
            PreparedStatement q = c.prepareStatement(
                    "SELECT status,buyer_id,product_id FROM mall_orders WHERE id=?");
            q.setInt(1, orderId);
            ResultSet rs = q.executeQuery();
            if (!rs.next()) return "订单不存在";
            if (rs.getInt("buyer_id") != userId) return "无权评价该订单";
            if (!"completed".equals(rs.getString("status"))) return "订单完成后才能评价";
            PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO mall_reviews (product_id,order_id,user_id,rating,content,created_at) VALUES (?,?,?,?,?,?)");
            ins.setInt(1, rs.getInt("product_id"));
            ins.setInt(2, orderId);
            ins.setInt(3, userId);
            ins.setInt(4, rating);
            ins.setString(5, content != null ? content : "");
            ins.setLong(6, System.currentTimeMillis());
            if (ins.executeUpdate() == 0) return "该订单已评价过";
            return null;
        } catch (SQLException e) {
            System.err.println("[ERROR] addReview: " + e.getMessage());
            return "评价失败，请稍后重试";
        }
    }

    /**
     * 商品评价列表（新→旧），附评价人昵称。
     * 查询失败返回 {@code null}（调用方映射为 5xx），与「暂无评价」的空列表严格区分。
     */
    public List<Map<String, Object>> getProductReviews(int productId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT r.id,r.rating,r.content,r.created_at,r.user_id," +
                            "COALESCE(u.nickname,'匿名用户') AS nickname " +
                            "FROM mall_reviews r LEFT JOIN users u ON u.id=r.user_id " +
                            "WHERE r.product_id=? ORDER BY r.id DESC LIMIT 100");
            ps.setInt(1, productId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getProductReviews: " + e.getMessage());
            return null;   // 查询失败 ≠ 无数据
        }
        return list;
    }

    /** 订单是否已评价（买家订单页按钮态用）。 */
    public boolean orderReviewed(int orderId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT id FROM mall_reviews WHERE order_id=?");
            ps.setInt(1, orderId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("[ERROR] orderReviewed: " + e.getMessage());
            return false;
        }
    }

    // ── 退款 / 售后 ──

    /**
     * 买家申请退款：仅 paid/shipped 且未在退款流程中的订单。
     * status 保持原状态，refund_status 置 pending。
     */
    public String applyRefund(int orderId, int buyerId, String reason) {
        try (Connection c = db.getConn()) {
            PreparedStatement q = c.prepareStatement(
                    "SELECT status,refund_status,buyer_id FROM mall_orders WHERE id=?");
            q.setInt(1, orderId);
            ResultSet rs = q.executeQuery();
            if (!rs.next()) return "订单不存在";
            if (rs.getInt("buyer_id") != buyerId) return "无权操作该订单";
            String status = rs.getString("status");
            if (!"paid".equals(status) && !"shipped".equals(status)) return "当前订单状态不可申请退款";
            // 仅处理中的/已退款的申请会阻止再次申请；被拒绝后可重新申请
            String refundStatus = rs.getString("refund_status");
            if ("pending".equals(refundStatus) || "approved".equals(refundStatus)) {
                return "已有退款申请在处理中";
            }
            PreparedStatement up = c.prepareStatement(
                    "UPDATE mall_orders SET refund_status='pending',refund_reason=? WHERE id=?");
            up.setString(1, reason != null && !reason.isEmpty() ? reason : "不想要了");
            up.setInt(2, orderId);
            up.executeUpdate();
            return null;
        } catch (SQLException e) {
            System.err.println("[ERROR] applyRefund: " + e.getMessage());
            return "申请失败，请稍后重试";
        }
    }

    /**
     * 卖家同意退款（事务）：refund_status pending→approved，订单 status→refunded。
     * 卖家余额扣回金额→买家余额退回，双方记账，恢复库存。
     * 成功返回 null；失败返回中文错误。
     */
    public String approveRefund(int orderId, int sellerId) {
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement q = c.prepareStatement(
                        "SELECT status,refund_status,amount,buyer_id,product_id,quantity,seller_id FROM mall_orders WHERE id=?");
                q.setInt(1, orderId);
                ResultSet rs = q.executeQuery();
                if (!rs.next()) { c.rollback(); return "订单不存在"; }
                if (rs.getInt("seller_id") != sellerId) { c.rollback(); return "无权操作该订单"; }
                // 双状态机互校验：只有待发货/待收货（且非已完成）订单可退款，杜绝"钱退+货在"
                String ostatus = rs.getString("status");
                if (!"paid".equals(ostatus) && !"shipped".equals(ostatus)) {
                    c.rollback(); return "当前订单状态不可退款";
                }
                if (!"pending".equals(rs.getString("refund_status"))) { c.rollback(); return "没有待处理的退款申请"; }
                long amount = rs.getLong("amount");
                int buyerId = rs.getInt("buyer_id");
                int productId = rs.getInt("product_id");
                int quantity = rs.getInt("quantity");
                // 条件更新防并发
                PreparedStatement up = c.prepareStatement(
                        "UPDATE mall_orders SET status='refunded',refund_status='approved',refund_time=? " +
                                "WHERE id=? AND refund_status='pending'");
                up.setLong(1, System.currentTimeMillis());
                up.setInt(2, orderId);
                if (up.executeUpdate() == 0) { c.rollback(); return "退款申请状态已变更"; }
                WalletDao.ensureWallet(c, sellerId);
                WalletDao.ensureWallet(c, buyerId);
                // ★ 审计第 4 条：托管中的订单退款不再碰卖家余额。
                //   旧模型要从卖家余额扣回，卖家把钱提走/花掉就会退款失败
                //   （表现为"卖家余额不足，退款失败，请联系卖家线下处理"）。
                //   托管模型下钱一直在托管池，退款必然成功。
                long escrowRefunded = refundEscrowToBuyer(c, orderId);
                if (escrowRefunded <= 0) {
                    // 老订单（付款即入卖家余额，escrow=0）走原路径：从卖家余额扣回。
                    // 一刀切策略：新订单全程托管，老订单沿用旧规则。
                    if (!debitWallet(c, sellerId, amount)) {
                        c.rollback();
                        return "卖家余额不足，退款失败，请联系卖家线下处理";
                    }
                    creditWallet(c, buyerId, amount);
                    addTransaction(c, sellerId, buyerId, "mall_refund_out", amount,
                            "商城退款(卖家扣回)", "completed", orderId);
                }
                // 恢复库存 + 扣回销量（退款商品不再计入已售）
                PreparedStatement st = c.prepareStatement(
                        "UPDATE products SET stock=stock+?, sales=CASE WHEN sales>=? THEN sales-? ELSE 0 END WHERE id=?");
                st.setInt(1, quantity);
                st.setInt(2, quantity);
                st.setInt(3, quantity);
                st.setInt(4, productId);
                st.executeUpdate();
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] approveRefund: " + e.getMessage());
                return "退款失败，请稍后重试";
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] approveRefund: " + e.getMessage());
            return "退款失败，请稍后重试";
        }
    }

    /** 卖家拒绝退款：refund_status pending→rejected，订单恢复原状态。 */
    public String rejectRefund(int orderId, int sellerId) {
        try (Connection c = db.getConn()) {
            PreparedStatement q = c.prepareStatement(
                    "SELECT refund_status,seller_id FROM mall_orders WHERE id=?");
            q.setInt(1, orderId);
            ResultSet rs = q.executeQuery();
            if (!rs.next()) return "订单不存在";
            if (rs.getInt("seller_id") != sellerId) return "无权操作该订单";
            if (!"pending".equals(rs.getString("refund_status"))) return "没有待处理的退款申请";
            PreparedStatement up = c.prepareStatement(
                    "UPDATE mall_orders SET refund_status='rejected' WHERE id=? AND refund_status='pending'");
            up.setInt(1, orderId);
            return up.executeUpdate() > 0 ? null : "退款申请状态已变更";
        } catch (SQLException e) {
            System.err.println("[ERROR] rejectRefund: " + e.getMessage());
            return "操作失败，请稍后重试";
        }
    }

    // ── 搜索热词 ──

    /** 记录一次搜索（关键词非空时调用，幂等累加计数）。 */
    public void recordSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        String kw = keyword.trim();
        if (kw.length() > 20) kw = kw.substring(0, 20);
        try (Connection c = db.getConn()) {
            PreparedStatement up = c.prepareStatement(
                    "UPDATE hot_searches SET count=count+1,updated_at=? WHERE keyword=?");
            up.setLong(1, System.currentTimeMillis());
            up.setString(2, kw);
            if (up.executeUpdate() == 0) {
                PreparedStatement ins = c.prepareStatement(
                        "INSERT OR IGNORE INTO hot_searches (keyword,count,updated_at) VALUES (?,1,?)");
                ins.setString(1, kw);
                ins.setLong(2, System.currentTimeMillis());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] recordSearch: " + e.getMessage());
        }
    }

    /** 搜索热词榜（按热度倒序，返回 keyword+count）。count 列名与聚合函数同名，ORDER BY 需加引号避免 SQLite 解析歧义。 */
    public List<Map<String, Object>> getHotSearches(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            try (Connection c = db.getConn()) {
                PreparedStatement ps = c.prepareStatement(
                        "SELECT keyword,\"count\" FROM hot_searches ORDER BY \"count\" DESC, updated_at DESC LIMIT ?");
                ps.setInt(1, Math.min(Math.max(limit, 1), 20));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) list.add(DbUtil.rowToMap(rs));
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getHotSearches: " + e.getMessage());
        }
        return list;
    }

    // ── 定时任务：超时取消 / 自动确认收货 ──

    /**
     * 取消超时未付款订单（事务逐个）：pending 超过 expireMs → cancelled，恢复库存。
     * 返回被取消订单的 id+买/卖家，供 WS 推送。
     */
    public List<Map<String, Object>> cancelExpiredOrders(long expireMs) {
        List<Map<String, Object>> cancelled = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - expireMs;
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement q = c.prepareStatement(
                        "SELECT id,product_id,quantity,buyer_id,seller_id FROM mall_orders " +
                                "WHERE status='pending' AND created_at<?");
                q.setLong(1, cutoff);
                ResultSet rs = q.executeQuery();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) rows.add(DbUtil.rowToMap(rs));
                for (Map<String, Object> row : rows) {
                    int orderId = ((Number) row.get("id")).intValue();
                    PreparedStatement up = c.prepareStatement(
                            "UPDATE mall_orders SET status='cancelled',cancel_time=? " +
                                    "WHERE id=? AND status='pending'");
                    up.setLong(1, System.currentTimeMillis());
                    up.setInt(2, orderId);
                    if (up.executeUpdate() > 0) {
                        PreparedStatement st = c.prepareStatement("UPDATE products SET stock=stock+? WHERE id=?");
                        st.setInt(1, ((Number) row.get("quantity")).intValue());
                        st.setInt(2, ((Number) row.get("product_id")).intValue());
                        st.executeUpdate();
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("order_id", orderId);
                        evt.put("buyer_id", row.get("buyer_id"));
                        evt.put("seller_id", row.get("seller_id"));
                        cancelled.add(evt);
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] cancelExpiredOrders: " + e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] cancelExpiredOrders: " + e.getMessage());
        }
        return cancelled;
    }

    /**
     * 自动确认收货：shipped 超过 expireMs → completed。
     *
     * <p>与手动确认一样要放款（审计第 4 条）：定时任务漏放一次，
     * 卖家就会永久收不到这笔钱，而买家已经无法退款。
     */
    public List<Map<String, Object>> autoCompleteOrders(long expireMs) {
        List<Map<String, Object>> done = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - expireMs;
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement q = c.prepareStatement(
                        "SELECT id,buyer_id,seller_id FROM mall_orders WHERE status='shipped' AND shipped_at<? AND refund_status=''");
                q.setLong(1, cutoff);
                ResultSet rs = q.executeQuery();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) rows.add(DbUtil.rowToMap(rs));
                for (Map<String, Object> row : rows) {
                    int orderId = ((Number) row.get("id")).intValue();
                    PreparedStatement up = c.prepareStatement(
                            "UPDATE mall_orders SET status='completed',completed_at=? " +
                                    "WHERE id=? AND status='shipped' AND refund_status=''");
                    up.setLong(1, System.currentTimeMillis());
                    up.setInt(2, orderId);
                    if (up.executeUpdate() > 0) {
                        releaseEscrowToSeller(c, orderId);   // 同一事务放款
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("order_id", orderId);
                        evt.put("buyer_id", row.get("buyer_id"));
                        evt.put("seller_id", row.get("seller_id"));
                        done.add(evt);
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.err.println("[ERROR] autoCompleteOrders: " + e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] autoCompleteOrders: " + e.getMessage());
        }
        return done;
    }

    // ═══════════════════════ 商城托管账户（审计第 4 条） ═══════════════════════

    /**
     * 托管账户在 users 表里的保留 id。
     *
     * <p>为什么不新开一张 {@code escrow} 表来存余额：钱守恒要靠"同一事务内一扣一加"来保证，
     * 而全项目所有资金变动的权威账本都是 {@code wallets}。把托管余额也放进 wallets，
     * 意味着<b>任何时刻</b>都能用一句
     * {@code SELECT SUM(balance) FROM wallets} 核对总量是否守恒 —— 不需要跨表对账，
     * 也不会出现"wallets 改了但 escrow 表没改"这类半更新。
     *
     * <p>user_id=0 是建库时就存在的系统账户（充值 credit 的 from_user=0 也用它），
     * 复用它比新造一个负数 id 更不容易踩外键。
     */
    static final int ESCROW_USER_ID = 0;

    /**
     * 记账里的"托管方"标识。
     *
     * <p>用 0（系统账户）而不是 -1：{@code transactions.from_user/to_user} 都有指向 users
     * 的外键，-1 会直接撞 FOREIGN KEY 约束、把整个支付事务回滚（实测踩过这个坑）。
     * 托管与充值的区分交给 type 字段（mall_escrow_in / mall_income / mall_refund），
     * 不靠 id 编码。
     */
    private static final int ESCROW_LEDGER_ID = 0;

    /** 事务内查询余额是否够（仅用于给出精确错误文案；权威判定在 UPDATE 的 WHERE 上）。 */
    private static boolean hasEnoughBalance(Connection c, int userId, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM wallets WHERE user_id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) >= amount;
            }
        }
    }

    /** 扣余额（同一事务内）。余额不足返回 false，调用方负责回滚。 */    private static boolean debitWallet(Connection c, int userId, long amount) throws SQLException {
        if (amount <= 0) return false;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE wallets SET balance=balance-?, updated_at=? WHERE user_id=? AND balance>=?")) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, userId);
            ps.setLong(4, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /** 加余额（同一事务内）。 */
    private static void creditWallet(Connection c, int userId, long amount) throws SQLException {
        if (amount <= 0) return;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE wallets SET balance=balance+?, updated_at=? WHERE user_id=?")) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    /**
     * 把买家的钱转入托管：扣买家余额 + 加托管账户余额 + 落托管明细 + 订单打标记。
     *
     * <p>四步必须在调用方的同一个事务里：任何一步失败都要整体回滚，
     * 否则会出现"钱扣了但没进托管"（凭空消失）或"托管加了但订单没标记"（钱放不出去）。
     */
    private void holdEscrow(Connection c, int orderId, int buyerId, int sellerId, long amount)
            throws SQLException {
        WalletDao.ensureWallet(c, ESCROW_USER_ID);
        if (!debitWallet(c, buyerId, amount)) {
            throw new SQLException("托管扣款失败：买家余额不足");
        }
        creditWallet(c, ESCROW_USER_ID, amount);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO mall_escrow (order_id,buyer_id,seller_id,amount,created_at) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT(order_id) DO NOTHING")) {
            ps.setInt(1, orderId);
            ps.setInt(2, buyerId);
            ps.setInt(3, sellerId);
            ps.setLong(4, amount);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE mall_orders SET escrow=1, escrow_released_at=0 WHERE id=?")) {
            ps.setInt(1, orderId);
            ps.executeUpdate();
        }
        // 托管流水：from=买家 to=托管方（用 -1 与真实用户区分）
        addTransaction(c, buyerId, ESCROW_LEDGER_ID, "mall_escrow_in", amount,
                "商城托管(买家付款)", "completed", orderId);
    }

    /**
     * 释放托管给卖家（确认收货/自动确认收货）。
     *
     * <p>幂等：以订单行的 {@code escrow} 标记为准做条件更新，重复调用只会放一次钱。
     * 若订单没有被托管（历史订单 escrow=0），直接返回 0，不做任何资金变动。
     *
     * @return 实际释放的金额（分）；0 表示无需释放
     */
    private long releaseEscrowToSeller(Connection c, int orderId) throws SQLException {
        long amount = takeEscrow(c, orderId);
        if (amount <= 0) return 0L;
        int sellerId = escrowSellerOf(c, orderId);
        if (sellerId <= 0) return 0L;
        WalletDao.ensureWallet(c, sellerId);
        creditWallet(c, sellerId, amount);
        addTransaction(c, ESCROW_LEDGER_ID, sellerId, "mall_income", amount,
                "商城收入(托管释放)", "completed", orderId);
        return amount;
    }

    /**
     * 释放托管退给买家（退款）。
     *
     * <p>不碰卖家余额 —— 钱从头到尾都在托管账户里，根本还没进过卖家口袋。
     * 这正是托管模型的意义：卖家余额为 0 也能正常退款（旧模型此时会失败）。
     *
     * @return 实际退回的金额（分）；0 表示该订单无托管
     */
    private long refundEscrowToBuyer(Connection c, int orderId) throws SQLException {
        long amount = takeEscrow(c, orderId);
        if (amount <= 0) return 0L;
        int buyerId = escrowBuyerOf(c, orderId);
        if (buyerId <= 0) return 0L;
        WalletDao.ensureWallet(c, buyerId);
        creditWallet(c, buyerId, amount);
        addTransaction(c, ESCROW_LEDGER_ID, buyerId, "mall_refund", amount,
                "商城退款(托管退回)", "completed", orderId);
        return amount;
    }

    /**
     * 取出托管明细并扣减托管账户余额（钱离开托管池的唯一出口）。
     *
     * <p>先删明细再扣池：删除是幂等的（第二次 affected=0 直接返回 0），
     * 所以在"同一事务内被调用两次"的极端情况下，第二次拿不到明细、也就不会重复放钱。
     */
    private long takeEscrow(Connection c, int orderId) throws SQLException {
        long amount;
        int sellerId;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT amount,seller_id FROM mall_escrow WHERE order_id=?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0L;
                amount = rs.getLong("amount");
                sellerId = rs.getInt("seller_id");
            }
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM mall_escrow WHERE order_id=?")) {
            ps.setInt(1, orderId);
            if (ps.executeUpdate() <= 0) return 0L;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE wallets SET balance=balance-?, updated_at=? WHERE user_id=? AND balance>=?")) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, ESCROW_USER_ID);
            ps.setLong(4, amount);
            if (ps.executeUpdate() <= 0) {
                // 托管池余额不足以支付这笔明细 = 账目已损坏（有人手工改库）。
                // 抛异常回滚，绝不"少放一点"糊过去 —— 那会把资金缺口隐藏起来。
                throw new SQLException("托管账户余额不足，拒绝释放 order=" + orderId
                        + " amount=" + amount + " seller=" + sellerId);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE mall_orders SET escrow=0, escrow_released_at=? WHERE id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
        return amount;
    }

    private int escrowSellerOf(Connection c, int orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT seller_id FROM mall_orders WHERE id=?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int escrowBuyerOf(Connection c, int orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT buyer_id FROM mall_orders WHERE id=?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 托管池当前总额（分），运维对账用。 */
    public long getEscrowPoolTotal() {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(balance),0) FROM wallets WHERE user_id=?")) {
            ps.setInt(1, ESCROW_USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getEscrowPoolTotal: " + e.getMessage());
            return 0L;
        }
    }

    /** 托管明细条数与金额合计（管理端统计用）。 */
    public Map<String, Object> getEscrowStats() {
        Map<String, Object> m = new HashMap<>();
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM mall_escrow");
             ResultSet rs = ps.executeQuery()) {
            m.put("orders", rs.next() ? rs.getInt(1) : 0);
            m.put("amount", rs.next() ? rs.getLong(2) : 0L);
        } catch (SQLException e) {
            System.err.println("[ERROR] getEscrowStats: " + e.getMessage());
        }
        m.put("pool", getEscrowPoolTotal());
        return m;
    }

    /** 事务内记账（支付用，同一连接）。 */
    private long addTransaction(Connection c, int fromUserId, int toUserId, String type, long amount,
                                String memo, String status, int refId) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (from_user,to_user,type,amount,memo,status,ref_id,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
        ps.setInt(1, fromUserId);
        ps.setInt(2, toUserId);
        ps.setString(3, type);
        ps.setLong(4, amount);
        ps.setString(5, memo != null ? memo : "");
        ps.setString(6, status != null ? status : "completed");
        ps.setInt(7, refId);
        ps.setLong(8, System.currentTimeMillis());
        ps.executeUpdate();
        ResultSet rs = ps.getGeneratedKeys();
        if (rs.next()) return rs.getLong(1);
        return 0L;
    }
}
