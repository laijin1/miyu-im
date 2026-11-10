package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import com.familychat.server.ws.WsHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;

import com.familychat.server.model.dao.WalletDao;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付系统接口（阶段1起）：自建虚拟余额，完整复刻密语收付。
 * 钱包 / 支付密码 / 管理员充值（阶段1）→ 转账（阶段2）→ 红包（阶段3）→ 收付款码/扫码（阶段4）→ 账单（阶段5）。
 * 余额单位:分(整数)，杜绝浮点误差。支付密码客户端 SHA-256(6位PIN) 后上传，服务端加盐再哈希存储。
 * 所有资金变化都记录 transactions 账本，供账单查询。
 */
public class WalletHandler {

    /**
     * 充值管理员白名单，唯一来源是服务端环境变量 ADMIN_USER_IDS（逗号分隔的 userId 列表）。
     * 未设置或为空 = 空集合 = 任何人都不能充值（包括第一个用户），这是安全默认值。
     * 不落库、不建白名单表，改环境变量重启即生效。
     */
    private static final Set<Integer> ADMIN_USER_IDS = parseAdminUserIds(System.getenv("ADMIN_USER_IDS"));

    /** 解析 "1,2,3" 形式的 userId 白名单；非数字项忽略，空值返回空集合。 */
    private static Set<Integer> parseAdminUserIds(String raw) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (raw == null) return ids;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                // 非法项直接忽略，不放行
            }
        }
        return ids;
    }

    // ===== /api/wallet/charge 限流：防无限骚扰通道 =====
    // 为什么必须有：createCharge 每调用一次就向「收款对象」WS 推一条 charge_requested 通知。
    // 之前该接口除鉴权外零限制，任何登录用户都能用循环请求把任意账号的通知栏刷爆
    // （实测确认无任何限制）。这里给「发起方」加窗口限流，把骚扰速率钉死在上限内。
    private static final ConcurrentHashMap<Integer, long[]> chargeRateLimit = new ConcurrentHashMap<>();
    private static final int CHARGE_RATE_MAX = intProp("wallet.charge.rate.max", 10);
    private static final long CHARGE_RATE_WINDOW =
            longProp("wallet.charge.rate.window.ms", 60_000L);
    // 内存上限：与 AuthHandler 同形，防止长期运行下 map 无界增长
    private static final int CHARGE_RATE_MAP_MAX = 10_000;

    /**
     * 读 int/long 型系统属性；缺失或非法一律回退默认值。
     * 为什么不用 {@code Integer.getInteger}：它在值非数字时返回 null，自动拆箱会抛
     * NullPointerException 直接把启动搞挂（例如 -Dwallet.charge.rate.max=abc）。
     */
    private static int intProp(String key, int def) {
        long v = longProp(key, def);
        return (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) ? def : (int) v;
    }

    private static long longProp(String key, long def) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.trim().isEmpty()) ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[WARN] 非法系统属性 " + key + "，回退默认值 " + def);
            return def;
        }
    }

    /** charge 限流，超限返回 false。compute 原子计数，防并发绕过。 */
    private static boolean checkChargeRateLimit(int userId) {
        long now = System.currentTimeMillis();
        if (chargeRateLimit.size() >= CHARGE_RATE_MAP_MAX) {
            chargeRateLimit.entrySet().removeIf(e -> now - e.getValue()[0] > CHARGE_RATE_WINDOW);
            if (chargeRateLimit.size() >= CHARGE_RATE_MAP_MAX) {
                int half = chargeRateLimit.size() / 2;
                java.util.Iterator<Map.Entry<Integer, long[]>> it = chargeRateLimit.entrySet().iterator();
                for (int i = 0; it.hasNext() && i < half; i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        return chargeRateLimit.compute(userId, (k, e) -> {
            if (e == null || now - e[0] > CHARGE_RATE_WINDOW) {
                return new long[]{now, 1};
            }
            e[1]++;
            return e;
        })[1] <= CHARGE_RATE_MAX;
    }

    public void register(Javalin app) {
        app.get("/api/wallet", this::getWallet);                 // 钱包余额/是否已设支付密码
        app.put("/api/wallet/password", this::setPassword);      // 设/改支付密码
        app.post("/api/wallet/recharge", this::recharge);        // 充值（仅 ADMIN_USER_IDS 白名单账号）
        // 转账（密语式状态机，阶段2）
        app.post("/api/wallet/transfer", this::sendTransfer);              // 发起转账（扣款冻结）
        app.post("/api/wallet/transfer/{tid}/accept", this::acceptTransfer); // 接收方收款
        app.post("/api/wallet/transfer/{tid}/refund", this::refundTransfer); // 发起方撤回/接收方立即退还
        // 转账详情 / 账单（阶段5）
        app.get("/api/wallet/transfer/{tid}", this::transferDetail);       // 转账详情
        app.get("/api/wallet/bills", this::bills);                          // 钱包账单
        // 收付款码（阶段4）：付款码被扫 → 商家发起收款请求 → 主人确认/拒绝
        app.post("/api/wallet/charge", this::createCharge);                 // 商家发起收款请求
        app.post("/api/wallet/charge/{cid}/approve", this::approveCharge);  // 付款码主人确认付款
        app.post("/api/wallet/charge/{cid}/decline", this::declineCharge);  // 主人拒绝
    }

    /** 取余额字典（无钱包行时自动补建 0 余额）。 */
    private Map<String, Object> walletDict(int userId) {
        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        if (w == null) {
            FamilyChatServer.getDb().ensureWallet(userId);
            w = FamilyChatServer.getDb().getWallet(userId);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("balance", w.get("balance"));
        d.put("pay_set", w.get("pay_set"));
        return d;
    }

    /** GET /api/wallet → {balance, pay_set, nickname, avatar}。 */
    private void getWallet(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> u = FamilyChatServer.getDb().getUserById(userId);
        Map<String, Object> d = walletDict(userId);
        d.put("nickname", u != null ? u.get("nickname") : "");
        d.put("avatar", u != null ? u.get("avatar") : "");
        ctx.json(d);
    }

    /** 校验支付密码并处理错误/锁定响应；返回 true 表示通过。 */
    private boolean verifyPin(Context ctx, int userId, String pinHash) {
        int r = FamilyChatServer.getDb().verifyPayPassword(userId, pinHash);
        if (r == 2) {
            ctx.status(429).json(Map.of("error", "支付密码错误次数过多，请 5 分钟后再试"));
            return false;
        }
        if (r != 0) {
            ctx.status(403).json(Map.of("error", "支付密码错误"));
            return false;
        }
        return true;
    }

    /**
     * PUT /api/wallet/password  body {pin_hash, old_hash?}
     * pin_hash = 客户端 SHA-256(6位数字PIN)。已设密码时需 old_hash 校验（改密码）。
     */
    private void setPassword(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String pinHash = body.get("pin_hash") instanceof String s ? s.trim() : "";
        if (!pinHash.matches("[0-9a-f]{64}")) {
            ctx.status(400).json(Map.of("error", "无效的支付密码"));
            return;
        }
        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        boolean paySet = w != null
                && (w.get("pay_set") instanceof Number n ? n.intValue() : 0) == 1;
        if (paySet) {
            String oldHash = body.get("old_hash") instanceof String s ? s.trim() : "";
            if (!verifyPin(ctx, userId, oldHash)) return;
        }
        if (!FamilyChatServer.getDb().setPayPassword(userId, pinHash)) {
            ctx.status(500).json(Map.of("error", "设置失败"));
            return;
        }
        ctx.json(Map.of("success", true, "pay_set", 1));
    }

    /**
     * POST /api/wallet/recharge  body {amount}
     * 仅 ADMIN_USER_IDS 白名单内的账号可给自己充值；白名单未配置 = 对所有人关闭。
     * 金额必须为正数且不超过 WalletDao.MAX_SINGLE_CREDIT（分）；上限同时在 SQL 层再校验一次。
     * 事务内：加余额 + 记账(type=recharge, from_user=0 系统) + WS 推 wallet_updated 给本人。
     * 未来接入银行：此接口将对接真实支付渠道，当前为直接加余额。
     */
    private void recharge(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        // 管理员校验：非白名单账号一律 403，且不透露任何金额/内部细节
        if (!ADMIN_USER_IDS.contains(userId)) {
            ctx.status(403).json(Map.of("error", "充值功能未开放"));
            return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        long amount;
        try {
            amount = ((Number) body.get("amount")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 amount"));
            return;
        }
        if (amount <= 0) {
            ctx.status(400).json(Map.of("error", "充值金额必须大于 0"));
            return;
        }
        // 单次上限（分）。此处给用户明确 400；SQL 层还有一道等值守卫，双重校验。
        if (amount > WalletDao.MAX_SINGLE_CREDIT) {
            ctx.status(400).json(Map.of("error",
                    "单次充值金额不能超过 " + (WalletDao.MAX_SINGLE_CREDIT / 100) + " 元"));
            return;
        }
        // 只能给自己充值，防止任意加他人余额
        int toUser = userId;
        if (!FamilyChatServer.getDb().addBalance(toUser, amount, WalletDao.MAX_SINGLE_CREDIT)) {
            ctx.status(500).json(Map.of("error", "充值失败"));
            return;
        }
        FamilyChatServer.getDb().addTransaction(0, toUser, "recharge", amount, "零钱充值", "completed", 0);
        System.out.println("[WALLET] uid=" + userId + " recharge +" + amount + "分");
        // WS 通知本人刷新余额
        Map<String, Object> w = FamilyChatServer.getDb().getWallet(toUser);
        WsHandler.sendToUser(toUser, Map.of("type", "wallet_updated",
                "balance", w != null ? w.get("balance") : 0));
        ctx.json(Map.of("success", true, "balance", w != null ? w.get("balance") : 0));
    }

    // ===== 转账（阶段2） =====

    /**
     * POST /api/wallet/transfer  body {to_user, amount, memo, pin_hash}
     * 密语式：扣发起方余额冻结 → 记 pending 流水 → 存 transfer 消息 → WS 推给接收方。
     * 接收方「收款」或发起方「撤回」后资金才真正入账/退回。
     */
    private void sendTransfer(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        int toUser;
        long amount;
        try {
            toUser = ((Number) body.get("to_user")).intValue();
            amount = ((Number) body.get("amount")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 to_user 或 amount"));
            return;
        }
        String memo = body.get("memo") instanceof String s ? s.trim() : "";
        String pinHash = body.get("pin_hash") instanceof String s ? s.trim() : "";
        boolean scanPay = Boolean.TRUE.equals(body.get("scan_pay")); // 收款码扫码付款：跳过好友校验（密语行为）

        if (toUser == userId) {
            ctx.status(400).json(Map.of("error", "不能给自己转账"));
            return;
        }
        if (amount <= 0) {
            ctx.status(400).json(Map.of("error", "转账金额必须大于 0"));
            return;
        }
        if (FamilyChatServer.getDb().getUserById(toUser) == null) {
            ctx.status(404).json(Map.of("error", "对方不存在"));
            return;
        }
        if (!scanPay && !FamilyChatServer.getDb().areFriends(userId, toUser)) {
            ctx.status(403).json(Map.of("error", "只能给好友转账"));
            return;
        }
        if (!verifyPin(ctx, userId, pinHash)) return;

        // 扫码支付 = 直接到账（扣发起方 → 入账接收方，无需确认）；普通转账 = 待收款冻结
        Map<String, Object> msg = scanPay
                ? FamilyChatServer.getDb().directPay(userId, toUser, amount, memo)
                : FamilyChatServer.getDb().createTransfer(userId, toUser, amount, memo);
        if (msg == null) {
            // 余额不足与异常都返回 400（中文提示）
            Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
            long bal = w != null && w.get("balance") instanceof Number n ? n.longValue() : 0L;
            ctx.status(400).json(Map.of("error", bal < amount ? "余额不足" : "转账失败"));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + (scanPay ? " scanPay -> " : " transfer -> ")
                + "uid=" + toUser + " " + amount + "分 memo=" + memo);
        long transferId = msg.get("transfer_id") instanceof Number n ? n.longValue() : 0L;
        if (scanPay) {
            // 扫码支付=纯钱包到账（无聊天消息）：双方钱包余额都实际变动，都要刷新
            pushWallet(userId);
            pushWallet(toUser);
        } else {
            // 普通聊天转账：推待收款事件给接收方，聊天页即时刷新转账卡片
            WsHandler.sendToUser(toUser, Map.of(
                    "type", "transfer_received",
                    "transfer_id", transferId,
                    "amount", amount,
                    "from_user", userId,
                    "memo", memo));
        }
        ctx.json(Map.of("success", true, "message", msg));
    }

    /** POST /api/wallet/transfer/{tid}/accept  仅接收方可收款。 */
    private void acceptTransfer(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int txId;
        try { txId = Integer.parseInt(ctx.pathParam("tid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的转账ID")); return; }

        Map<String, Object> r = FamilyChatServer.getDb().acceptTransfer(txId, userId);
        if (r == null) {
            Map<String, Object> tx = FamilyChatServer.getDb().getTransaction(txId);
            if (tx == null) { ctx.status(404).json(Map.of("error", "转账不存在")); return; }
            ctx.status(403).json(Map.of("error", "无法收款"));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + " accept transfer " + txId);
        WsHandler.sendToUser(((Number) r.get("from_user")).intValue(), Map.of(
                "type", "transfer_accepted",
                "transfer_id", txId,
                "amount", r.get("amount")));
        ctx.json(Map.of("success", true, "transfer_id", txId,
                "amount", r.get("amount"), "status", r.get("status")));
    }

    /**
     * POST /api/wallet/transfer/{tid}/refund
     *
     * <p>两种语义共用本接口（与 DAO 层 resolveTransfer 的权限分支一致）：
     * <ul>
     *   <li>发起方调用 = <b>撤回</b>转账；</li>
     *   <li>接收方调用 = <b>立即退还</b>（拒收），钱同样退回发起方。</li>
     * </ul>
     * 失败提示按调用者的实际角色区分，避免接收方退还失败时看到"无法撤回"这种
     * 与自身操作不符的文案（审计第 8 条）。
     */
    private void refundTransfer(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int txId;
        try { txId = Integer.parseInt(ctx.pathParam("tid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的转账ID")); return; }

        Map<String, Object> r = FamilyChatServer.getDb().refundTransfer(txId, userId);
        if (r == null) {
            Map<String, Object> tx = FamilyChatServer.getDb().getTransaction(txId);
            if (tx == null) { ctx.status(404).json(Map.of("error", "转账不存在")); return; }
            // 按调用者角色给出对应文案：发起方=撤回，接收方=退还，其他人=无权
            String msg;
            if (userId == ((Number) tx.get("from_user")).intValue()) {
                msg = "无法撤回（对方已收款或该转账已处理）";
            } else if (userId == ((Number) tx.get("to_user")).intValue()) {
                msg = "无法退还（该转账已处理）";
            } else {
                msg = "无权操作该转账";
            }
            ctx.status(403).json(Map.of("error", msg));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + " refund transfer " + txId);
        // 撤回/退还双端都通知：发起方撤回时接收方要收到，接收方立即退还时发起方要收到
        int fromUser = ((Number) r.get("from_user")).intValue();
        int toUser = ((Number) r.get("to_user")).intValue();
        Map<String, Object> refundEvt = Map.of(
                "type", "transfer_refunded",
                "transfer_id", txId,
                "amount", r.get("amount"));
        WsHandler.sendToUser(fromUser, refundEvt);
        WsHandler.sendToUser(toUser, refundEvt);
        ctx.json(Map.of("success", true, "transfer_id", txId,
                "amount", r.get("amount"), "status", r.get("status")));
    }

    // ===== 转账详情（阶段5） =====

    /** GET /api/wallet/transfer/{tid} → 转账详情（含双方昵称/头像）。 */
    private void transferDetail(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int txId;
        try { txId = Integer.parseInt(ctx.pathParam("tid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的转账ID")); return; }
        Map<String, Object> tx = FamilyChatServer.getDb().getTransaction(txId);
        if (tx == null) {
            ctx.status(404).json(Map.of("error", "转账不存在"));
            return;
        }
        int fromUser = ((Number) tx.get("from_user")).intValue();
        int toUser = ((Number) tx.get("to_user")).intValue();
        // 越权防护：仅转账双方可查看详情（防止遍历 tid 读取全服务器转账记录）
        if (userId != fromUser && userId != toUser) {
            ctx.status(403).json(Map.of("error", "无权查看该转账"));
            return;
        }
        Map<String, Object> from = FamilyChatServer.getDb().getUserById(fromUser);
        Map<String, Object> to = FamilyChatServer.getDb().getUserById(toUser);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("transfer_id", txId);
        d.put("from_user", fromUser);
        d.put("to_user", toUser);
        d.put("amount", tx.get("amount"));
        d.put("memo", tx.get("memo"));
        d.put("status", tx.get("status"));
        d.put("created_at", tx.get("created_at"));
        d.put("from_nickname", from != null ? from.get("nickname") : "");
        d.put("from_avatar", from != null ? from.get("avatar") : "");
        d.put("to_nickname", to != null ? to.get("nickname") : "");
        d.put("to_avatar", to != null ? to.get("avatar") : "");
        ctx.json(Map.of("transfer", d));
    }

    // ===== 钱包账单（阶段5） =====

    /** GET /api/wallet/bills → 我的流水列表（方向/类型/金额/状态/对端/备注/时间）。 */
    private void bills(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        java.util.List<Map<String, Object>> rows = FamilyChatServer.getDb().getBills(userId);
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            int fromUser = ((Number) row.get("from_user")).intValue();
            int toUser = ((Number) row.get("to_user")).intValue();
            boolean income = toUser == userId;
            int peerId = income ? fromUser : toUser;
            Map<String, Object> u = peerId == 0 ? null : FamilyChatServer.getDb().getUserById(peerId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("type", row.get("type"));
            item.put("amount", row.get("amount"));
            item.put("status", row.get("status"));
            item.put("direction", income ? "income" : "expense");
            item.put("peer_id", peerId);
            // 系统账目(from_user=0)：对端名用备注（如「零钱充值」），否则兜底「系统」
            item.put("peer_nickname", peerId == 0
                    ? (row.get("memo") != null && !String.valueOf(row.get("memo")).isEmpty()
                        ? String.valueOf(row.get("memo")) : "系统")
                    : (u != null ? u.get("nickname") : ""));
            item.put("peer_avatar", u != null ? u.get("avatar") : "");
            item.put("memo", row.get("memo"));
            item.put("created_at", row.get("created_at"));
            items.add(item);
        }
        ctx.json(Map.of("bills", items));
    }

    // ===== 收款请求（阶段4）：付款码被扫 → 商家发起 → 主人确认/拒绝 =====

    /**
     * POST /api/wallet/charge  body {to_user, amount, memo}
     * 商家（扫码方）向付款码主人发起收款请求；服务端存 pending，WS 推给主人确认。
     * 限流：每账号每窗口最多 CHARGE_RATE_MAX 次（默认 10 次/分钟），超限 429。
     * 计数放在参数校验之前 —— 无论请求是否合法，只要打到这个接口就消耗发起方配额，
     * 避免「用非法参数刷通知」绕过限流。
     */
    private void createCharge(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        if (!checkChargeRateLimit(userId)) {
            ctx.header("Retry-After", String.valueOf(CHARGE_RATE_WINDOW / 1000));
            ctx.status(429).json(Map.of("error", "发起收款过于频繁，请稍后再试"));
            return;
        }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }

        int toUser;
        long amount;
        try {
            toUser = ((Number) body.get("to_user")).intValue();
            amount = ((Number) body.get("amount")).longValue();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "缺少 to_user 或 amount"));
            return;
        }
        String memo = body.get("memo") instanceof String s ? s.trim() : "";
        if (toUser == userId) {
            ctx.status(400).json(Map.of("error", "不能向自己发起收款"));
            return;
        }
        if (amount <= 0) {
            ctx.status(400).json(Map.of("error", "收款金额必须大于 0"));
            return;
        }
        if (FamilyChatServer.getDb().getUserById(toUser) == null) {
            ctx.status(404).json(Map.of("error", "对方不存在"));
            return;
        }
        long chargeId = FamilyChatServer.getDb().createChargeRequest(userId, toUser, amount, memo);
        if (chargeId <= 0) {
            ctx.status(500).json(Map.of("error", "发起收款失败"));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + " charge request -> uid=" + toUser + " " + amount + "分 memo=" + memo);
        Map<String, Object> from = FamilyChatServer.getDb().getUserById(userId);
        WsHandler.sendToUser(toUser, Map.of(
                "type", "charge_requested",
                "charge_id", chargeId,
                "from_user", userId,
                "from_nickname", from != null ? from.get("nickname") : "",
                "amount", amount,
                "memo", memo));
        ctx.json(Map.of("success", true, "charge_id", chargeId));
    }

    /** POST /api/wallet/charge/{cid}/approve  body {pin_hash}  仅付款码主人可确认付款。 */
    private void approveCharge(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int cid;
        try { cid = Integer.parseInt(ctx.pathParam("cid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的收款请求ID")); return; }
        Map<String, Object> body;
        try { body = ctx.bodyAsClass(Map.class); }
        catch (Exception e) { ctx.status(400).json(Map.of("error", "无效的请求格式")); return; }
        String pinHash = body.get("pin_hash") instanceof String s ? s.trim() : "";
        if (!verifyPin(ctx, userId, pinHash)) return;
        Map<String, Object> req = FamilyChatServer.getDb().getChargeRequest(cid);
        if (req == null) {
            ctx.status(404).json(Map.of("error", "收款请求不存在"));
            return;
        }
        int toUser = ((Number) req.get("to_user")).intValue();
        if (toUser != userId) {
            ctx.status(403).json(Map.of("error", "无权处理该请求"));
            return;
        }
        Map<String, Object> r = FamilyChatServer.getDb().approveCharge(cid, userId);
        if (r == null) {
            ctx.status(400).json(Map.of("error", "无法收款（余额不足或已处理）"));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + " approve charge " + cid);
        int merchant = ((Number) r.get("from_user")).intValue();
        WsHandler.sendToUser(merchant, Map.of(
                "type", "charge_result",
                "charge_id", cid, "status", "accepted", "amount", r.get("amount")));
        // 双端刷新余额
        pushWallet(merchant);
        pushWallet(userId);
        ctx.json(Map.of("success", true, "status", "accepted"));
    }

    /** POST /api/wallet/charge/{cid}/decline  仅付款码主人可拒绝。 */
    private void declineCharge(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;
        int cid;
        try { cid = Integer.parseInt(ctx.pathParam("cid")); }
        catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "无效的收款请求ID")); return; }
        if (!FamilyChatServer.getDb().declineCharge(cid, userId)) {
            ctx.status(403).json(Map.of("error", "无法拒绝（请求不存在或已处理）"));
            return;
        }
        System.out.println("[WALLET] uid=" + userId + " decline charge " + cid);
        Map<String, Object> req = FamilyChatServer.getDb().getChargeRequest(cid);
        int merchant = req != null ? ((Number) req.get("from_user")).intValue() : 0;
        if (merchant > 0) {
            WsHandler.sendToUser(merchant, Map.of(
                    "type", "charge_result",
                    "charge_id", cid, "status", "declined", "amount", 0));
        }
        ctx.json(Map.of("success", true, "status", "declined"));
    }

    /** 向用户推送钱包余额变化（wallet_updated）。 */
    private void pushWallet(int userId) {
        Map<String, Object> w = FamilyChatServer.getDb().getWallet(userId);
        WsHandler.sendToUser(userId, Map.of("type", "wallet_updated",
                "balance", w != null ? w.get("balance") : 0));
    }
}
