package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 钱包/支付领域 DAO（原 Database.java 支付系统各阶段方法原样搬移，仅改连接获取方式
 * 与共享助手调用路径，不改变任何逻辑）。
 */
public class WalletDao {
    private final Database db;

    /**
     * 单次入账上限（分）= 100 元。handler 层用它返回明确 400；
     * addBalance 的 SQL 同时用它做等值守卫，保证「负/零/超上限」在 SQL 语句层面
     * 就不可能改动 balance（不依赖调用方是否校验）。
     */
    public static final long MAX_SINGLE_CREDIT = 10_000L;

    public WalletDao(Database db) {
        this.db = db;
    }

    /** 取钱包行（无则返回 null）。只返回业务字段，绝不外泄 pay_hash/pay_salt 凭证材料。 */
    public Map<String, Object> getWallet(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT user_id,balance,pay_set FROM wallets WHERE user_id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getWallet: " + e.getMessage());
        }
        return null;
    }

    /** 确保钱包行存在（首次访问钱包时建 0 余额默认行）。 */
    public void ensureWallet(int userId) {
        try (Connection c = db.getConn()) {
            ensureWallet(c, userId);
        } catch (SQLException e) {
            System.err.println("[ERROR] ensureWallet: " + e.getMessage());
        }
    }

    /** 事务内确保钱包行存在（转账/红包在同一连接里建行）。跨 DAO 共享（MallDao 支付事务也调用）。 */
    static void ensureWallet(Connection c, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO wallets (user_id, updated_at) VALUES (?,?)")) {
            ps.setInt(1, userId);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * 设置/修改支付密码。pinHash = 客户端 SHA-256(6位数字PIN)。
     * 服务端加随机盐后做 PBKDF2 慢哈希存储（防离线暴力破解），格式：
     * pay_hash = "pbkdf2$迭代次数$盐$哈希"，绝不落明文。
     */
    public boolean setPayPassword(int userId, String pinHash) {
        String salt = Long.toHexString(new java.security.SecureRandom().nextLong())
                + Long.toHexString(System.nanoTime());
        String digest = pbkdf2Hex(pinHash, salt, PAY_PBKDF2_ITERATIONS);
        if (digest == null) return false;
        String stored = "pbkdf2$" + PAY_PBKDF2_ITERATIONS + "$" + salt + "$" + digest;
        try (Connection c = db.getConn()) {
            ensureWallet(userId);
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET pay_hash=?, pay_salt=?, pay_set=1, updated_at=? WHERE user_id=?");
            ps.setString(1, stored);
            ps.setString(2, salt);
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] setPayPassword: " + e.getMessage());
        }
        return false;
    }

    /** 校验支付密码。返回 0=通过, 1=密码错误/未设置, 2=已被临时锁定。 */
    public int verifyPayPassword(int userId, String pinHash) {
        if (pinHash == null) return 1;
        if (payLocked(userId)) return 2;
        String salt = null, stored = null;
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT pay_hash,pay_salt,pay_set FROM wallets WHERE user_id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int paySet = rs.getInt("pay_set");
                if (paySet == 0) return 1;
                stored = rs.getString("pay_hash");
                salt = rs.getString("pay_salt");
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] verifyPayPassword: " + e.getMessage());
            return 1;
        }
        if (salt == null || stored == null || stored.isEmpty()) return 1;

        boolean ok;
        if (stored.startsWith("pbkdf2$")) {
            // 新格式：PBKDF2 慢哈希
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return 1;
            int iter;
            try { iter = Integer.parseInt(parts[1]); } catch (Exception e) { return 1; }
            String calc = pbkdf2Hex(pinHash, parts[2], iter);
            ok = calc != null && constantTimeEqualsHex(calc, parts[3]);
        } else {
            // 旧格式：SHA-256(pinHash || salt)（快速哈希，仅作兼容）——验证通过后自动升级为 PBKDF2
            String calc = DbUtil.sha256Hex(pinHash + salt);
            ok = calc != null && constantTimeEqualsHex(calc, stored);
            if (ok) setPayPassword(userId, pinHash);
        }

        if (ok) {
            payRecordSuccess(userId);
            return 0;
        }
        payRecordFail(userId);
        return payLocked(userId) ? 2 : 1;
    }

    // ===== 支付密码防暴力破解：PBKDF2 慢哈希 + 失败锁定 =====

    private static final int PAY_PBKDF2_ITERATIONS = 120_000;   // 每次校验 ~50-100ms，暴力破解代价极高
    private static final int PAY_MAX_FAILS = 5;                  // 连续失败次数
    private static final long PAY_LOCK_MS = 5 * 60 * 1000L;      // 锁定 5 分钟
    // userId -> [failCount, lockUntilMillis]
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> payLock =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 恒定时间比较两个十六进制摘要（体检报告 §7.6「非恒定时间比较」）。
     *
     * <p>为什么用它替代 {@code String.equals}：{@code equals} 在首个不同字符处就返回，
     * 比较耗时随「猜对的前缀长度」变化。支付密码只有 6 位数字（空间仅 10^6），
     * 校验路径上任何可观测的时间差都值得消除。
     *
     * <p>为什么是等价替换：两侧都是固定长度的十六进制摘要（PBKDF2/SHA-256 = 64 字符），
     * {@link java.security.MessageDigest#isEqual} 在长度不等时立即返回 false、长度相等时
     * 逐字节比较且不提前退出，语义与 {@code equals} 完全一致（null 检查留给调用方）。
     */
    static boolean constantTimeEqualsHex(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** PBKDF2-HMAC-SHA256 慢哈希，输出小写 hex。 */
    private static String pbkdf2Hex(String input, String salt, int iterations) {        try {
            javax.crypto.SecretKeyFactory skf =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    input.toCharArray(), salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    iterations, 256);
            byte[] dk = skf.generateSecret(spec).getEncoded();
            StringBuilder sb = new StringBuilder();
            for (byte b : dk) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[ERROR] pbkdf2Hex: " + e.getMessage());
            return null;
        }
    }

    private static boolean payLocked(int userId) {
        long[] e = payLock.get(userId);
        return e != null && e[1] > System.currentTimeMillis();
    }

    /** 记录一次校验失败；连续失败达阈值即触发锁定（重置计数并设置锁定期）。 */
    private static void payRecordFail(int userId) {
        long now = System.currentTimeMillis();
        payLock.compute(userId, (k, e) -> {
            if (e == null || e[1] > 0) e = new long[]{0, 0};  // 未锁定或锁定期已过，重新计数
            e[0]++;
            if (e[0] >= PAY_MAX_FAILS) {
                e[0] = 0;
                e[1] = now + PAY_LOCK_MS;
            }
            return e;
        });
    }

    private static void payRecordSuccess(int userId) {
        payLock.remove(userId);
    }

    /** 加余额（充值/收款入账用，amount 为正），默认套用单次上限 {@link #MAX_SINGLE_CREDIT}。 */
    public boolean addBalance(int userId, long amount) {
        return addBalance(userId, amount, MAX_SINGLE_CREDIT);
    }

    /**
     * 加余额，并在 SQL 层强制 amount 为「正数且不超过 maxSingle」。
     * WHERE 里的 {@code ? > 0 AND ? <= ?} 使负数/零/超上限的调用直接命中 0 行，
     * 余额不可能被非法改动 —— 这是授权边界，不依赖上层 handler 的校验。
     */
    public boolean addBalance(int userId, long amount, long maxSingle) {
        try (Connection c = db.getConn()) {
            ensureWallet(userId);
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance = balance + ?, updated_at=? " +
                    "WHERE user_id=? AND ? > 0 AND ? <= ?");
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, userId);
            ps.setLong(4, amount);
            ps.setLong(5, amount);
            ps.setLong(6, maxSingle);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] addBalance: " + e.getMessage());
        }
        return false;
    }

    /**
     * 扣余额（转账/发红包/付款用，amount 必须为正）。余额不足或金额非法返回 false。
     *
     * <p>为什么必须挡负数：SQL 是 {@code balance = balance - ?}，传 {@code -100} 时
     * 会变成 {@code balance + 100} —— 扣款反转为加钱（造币）。与 {@link #addBalance}
     * 对齐，这里做两道防线：DAO 入口显式校验 + SQL 的 {@code ? > 0} 守卫。
     */
    public boolean deductBalance(int userId, long amount) {
        if (amount <= 0) return false;   // 防线一：入口校验
        try (Connection c = db.getConn()) {
            ensureWallet(userId);
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance = balance - ?, updated_at=? " +
                            "WHERE user_id=? AND balance >= ? AND ? > 0");   // 防线二：SQL 守卫
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, userId);
            ps.setLong(4, amount);
            ps.setLong(5, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deductBalance: " + e.getMessage());
        }
        return false;
    }

    /** 记账（所有资金流水统一入口）。返回流水 id；失败返回 0。 */
    public long addTransaction(int fromUserId, int toUserId, String type, long amount,
                               String memo, String status, int refId) {
        try (Connection c = db.getConn()) {
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
        } catch (SQLException e) {
            System.err.println("[ERROR] addTransaction: " + e.getMessage());
        }
        return 0L;
    }

    /** 按 id 查流水（accept/refund 用）。 */
    public Map<String, Object> getTransaction(int txId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM transactions WHERE id=?");
            ps.setInt(1, txId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getTransaction: " + e.getMessage());
        }
        return null;
    }

    // ===== 支付系统（阶段5）：钱包账单 =====

    /** 我的全部流水（我参与的交易），新→旧，最多 200 条。 */
    public List<Map<String, Object>> getBills(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM transactions WHERE from_user=? OR to_user=? ORDER BY id DESC LIMIT 200");
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getBills: " + e.getMessage());
        }
        return list;
    }

    // ===== 支付系统（阶段4）：收款请求（付款码被扫 → 商家发起 → 主人确认） =====

    /** 按 id 查收款请求。 */
    public Map<String, Object> getChargeRequest(int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM charge_requests WHERE id=?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getChargeRequest: " + e.getMessage());
        }
        return null;
    }

    /** 商家发起收款请求（fromUser=商家，toUser=付款码主人）。返回自增 id，失败返回 0。 */
    public long createChargeRequest(int fromUser, int toUser, long amount, String memo) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO charge_requests (from_user,to_user,amount,memo,status,created_at) " +
                            "VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, fromUser);
            ps.setInt(2, toUser);
            ps.setLong(3, amount);
            ps.setString(4, memo != null ? memo : "");
            ps.setString(5, "pending");
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            System.err.println("[ERROR] createChargeRequest: " + e.getMessage());
        }
        return 0L;
    }

    /** 主人拒绝收款请求：仅 to_user 且 pending 可拒。 */
    public boolean declineCharge(int id, int toUser) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE charge_requests SET status='declined' WHERE id=? AND to_user=? AND status='pending'");
            ps.setInt(1, id);
            ps.setInt(2, toUser);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] declineCharge: " + e.getMessage());
        }
        return false;
    }

    /**
     * 主人确认付款（事务）：pending→accepted（条件 UPDATE 防并发重复入账）→ 扣主人余额
     * → 商家加余额 → 记 completed 流水（type=transfer，from=主人 to=商家）。
     * 返回 {from_user, to_user, amount, status}；余额不足/非法状态返回 null。
     */
    public Map<String, Object> approveCharge(int id, int toUser) {
        return approveChargeInternal(id, toUser, true);
    }

    /**
     * 代确认的核心实现（唯一一份动钱代码）。
     *
     * @param id           收款请求 id
     * @param toUser       付款人；{@code requireOwner=true} 时必须与请求的 to_user 一致
     * @param requireOwner false = 管理员代确认（不校验归属，但仍走同一状态机与同一资金路径）
     */
    private Map<String, Object> approveChargeInternal(int id, Integer toUser, boolean requireOwner) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            // 先用同一连接读出请求详情：管理端不预先知道 to_user，只有拿到真实付款人
            // 才能给他建钱包行、扣他的余额
            int payer = 0;
            int fromUser = 0;
            long amount = 0;
            String memo = "";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM charge_requests WHERE id=?")) {
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    fromUser = rs.getInt("from_user");
                    payer = rs.getInt("to_user");
                    amount = rs.getLong("amount");
                    memo = rs.getString("memo") != null ? rs.getString("memo") : "";
                }
            }
            if (requireOwner) {
                if (toUser == null || payer != toUser) {
                    c.rollback();
                    return null;
                }
            }
            if (payer <= 0 || fromUser <= 0 || amount <= 0) {
                c.rollback();
                return null;
            }
            // 保证双方钱包行存在（同一连接内，避免新连接写库撞 SQLite 写锁）
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO wallets (user_id) VALUES (?)")) {
                ps.setInt(1, payer);
                ps.executeUpdate();
                ps.setInt(1, fromUser);
                ps.executeUpdate();
            }
            // 条件 UPDATE：只有 status='pending' 才会被处理，杜绝重复入账
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE charge_requests SET status='accepted' WHERE id=? AND status='pending'")) {
                ps.setInt(1, id);
                updated = ps.executeUpdate();
            }
            if (updated <= 0) {
                c.rollback();
                return null;
            }
            // 扣付款人余额（余额不足回滚）
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance-?, updated_at=? WHERE user_id=? AND balance>=?")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, payer);
                ps.setLong(4, amount);
                if (ps.executeUpdate() <= 0) {
                    c.rollback();
                    return null; // 余额不足
                }
            }
            // 商家加余额
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance+?, updated_at=? WHERE user_id=?")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, fromUser);
                ps.executeUpdate();
            }
            // 记账：付款人→商家（completed），同连接插入，避免新连接撞写锁
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions (from_user,to_user,type,amount,memo,status,ref_id,created_at) " +
                            "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setInt(1, payer);
                ps.setInt(2, fromUser);
                ps.setString(3, "transfer");
                ps.setLong(4, amount);
                ps.setString(5, memo.isEmpty() ? "向商家付款" : "向商家付款 " + memo);
                ps.setString(6, "completed");
                ps.setInt(7, 0);
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
            }
            c.commit();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from_user", fromUser);
            r.put("to_user", payer);
            r.put("amount", amount);
            r.put("status", "accepted");
            return r;
        } catch (SQLException e) {
            System.err.println("[ERROR] approveCharge: " + e.getMessage());
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
        } finally {
            // 关键：无论成功/失败/提前返回，都必须关闭连接，否则每次确认收款泄漏一个 SQLite 连接
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
        return null;
    }

    // ===== 管理后台：代用户处理收款请求（阶段：管理后台） =====

    /**
     * 管理员代付款人确认收款请求：与 {@link #approveCharge} 完全同一条资金路径
     * （扣付款人 → 加商家 → 记 completed 流水），唯一区别是不校验 to_user 归属。
     *
     * <p><b>为什么必须复用同一段 SQL 而不是另写一份：</b>这部分是动钱的核心，
     * 两份实现早晚会分叉（比如一边补了上限校验、另一边忘了）。所以这里把归属校验
     * 作为参数传给同一个私有实现，钱的部分只有一份代码。
     *
     * <p>返回同 {@link #approveCharge}；状态已变更或余额不足返回 null。
     */
    public Map<String, Object> approveChargeAsAdmin(int id) {
        // requireOwner=false：管理员代表付款人确认，不能要求管理员就是付款人本人
        return approveChargeInternal(id, null, false);
    }

    /**
     * 管理员拒绝收款请求：与主人拒绝同一状态机，但不校验 to_user。
     */
    public boolean declineChargeAsAdmin(int id) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE charge_requests SET status='declined' WHERE id=? AND status='pending'")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] declineChargeAsAdmin: " + e.getMessage());
        }
        return false;
    }

    /**
     * 代确认后把对应的 pending 转账流水一起落成 completed。
     *
     * <p>收款请求在 {@link #createChargeRequest} 时不写流水，确认时才写一条 completed；
     * 但历史数据里可能存在与 charge 关联的 pending 行，这里统一收口，避免留下
     * "请求已 accepted、流水还 pending"的不一致。
     */
    public int settleChargeTransactions(int requestId) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE transactions SET status='completed' " +
                             "WHERE type='charge' AND ref_id=? AND status='pending'")) {
            ps.setInt(1, requestId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] settleChargeTransactions: " + e.getMessage());
        }
        return 0;
    }

    // ===== 支付系统（阶段2）：转账（密语式状态机） =====
    /**
     * 发起转账（事务）：扣发起方余额 → 记 pending 流水 → 存 transfer 消息 → 流水 ref_id 关联消息。
     * 返回消息字典（含 id/content/msg_type）；余额不足或异常返回 null。
     * 注意：入账/撤回是 acceptTransfer/refundTransfer（pending→accepted/refunded）。
     */
    public Map<String, Object> createTransfer(int fromUser, int toUser, long amount, String memo) {
        // 防线一：入口拒绝非法金额。SQL 是 balance = balance - ?，负数会把扣款变成加钱。
        if (amount <= 0) return null;
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            ensureWallet(c, fromUser);
            ensureWallet(c, toUser);
            // 扣款（余额不足则更新行数为 0）；AND ? > 0 为防线二
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance-?, updated_at=? " +
                            "WHERE user_id=? AND balance>=? AND ? > 0")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, fromUser);
                ps.setLong(4, amount);
                ps.setLong(5, amount);
                if (ps.executeUpdate() <= 0) {
                    c.rollback();
                    return null;
                }
            }
            long txId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions (from_user,to_user,type,amount,memo,status,created_at) " +
                            "VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, fromUser);
                ps.setInt(2, toUser);
                ps.setString(3, "transfer");
                ps.setLong(4, amount);
                ps.setString(5, memo != null ? memo : "");
                ps.setString(6, "pending");
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (!rs.next()) {
                    c.rollback();
                    return null;
                }
                txId = rs.getLong(1);
            }
            String content = "{\"transfer_id\":" + txId
                    + ",\"amount\":" + amount
                    + ",\"memo\":\"" + DbUtil.jsonEscape(memo) + "\""
                    + ",\"status\":\"pending\"}";
            long msgId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO messages (sender_id,receiver_id,content,msg_type) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, fromUser);
                ps.setInt(2, toUser);
                ps.setString(3, content);
                ps.setString(4, "transfer");
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (!rs.next()) {
                    c.rollback();
                    return null;
                }
                msgId = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET ref_id=? WHERE id=?")) {
                ps.setLong(1, msgId);
                ps.setLong(2, txId);
                ps.executeUpdate();
            }
            c.commit();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", msgId);
            m.put("sender_id", fromUser);
            m.put("receiver_id", toUser);
            m.put("content", content);
            m.put("msg_type", "transfer");
            m.put("transfer_id", txId);   // 转账流水 id（客户端定位气泡用）
            return m;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] createTransfer: " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * 扫码支付直接到账（事务）：扣发起方余额 → 直接入账接收方 → 记 completed 流水。
     * 纯钱包到账，不产生任何聊天消息（密语收款码支付行为：只出现在双方钱包/账单，聊天框无转账卡片）。
     * 区别于 createTransfer（pending 待收款 + 聊天消息，需接收方确认）。
     * 返回 {transfer_id, status}；余额不足或异常返回 null。
     */
    public Map<String, Object> directPay(int fromUser, int toUser, long amount, String memo) {
        // 防线一：入口拒绝非法金额（负数会把扣款反转成加钱）
        if (amount <= 0) return null;
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            ensureWallet(c, fromUser);
            ensureWallet(c, toUser);
            // 扣款（余额不足则更新行数为 0）；AND ? > 0 为防线二
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance-?, updated_at=? " +
                            "WHERE user_id=? AND balance>=? AND ? > 0")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, fromUser);
                ps.setLong(4, amount);
                ps.setLong(5, amount);
                if (ps.executeUpdate() <= 0) {
                    c.rollback();
                    return null;
                }
            }
            // 直接入账接收方
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance+?, updated_at=? WHERE user_id=?")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, toUser);
                ps.executeUpdate();
            }
            long txId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions (from_user,to_user,type,amount,memo,status,created_at) " +
                            "VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, fromUser);
                ps.setInt(2, toUser);
                ps.setString(3, "paycode");    // 扫码支付（账单显示「扫码付款」），区别于聊天转账 transfer
                ps.setLong(4, amount);
                ps.setString(5, memo != null ? memo : "");
                ps.setString(6, "completed");
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (!rs.next()) {
                    c.rollback();
                    return null;
                }
                txId = rs.getLong(1);
            }
            c.commit();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transfer_id", txId);
            m.put("status", "completed");   // 直接到账，非待收款
            return m;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] directPay: " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * 收款（事务）：仅接收方可调。pending→accepted，入账接收方，更新 transfer 消息 content status。
     * 返回 {transfer_id, from_user, amount, status, msg_id, content}；非法状态返回 null。
     */
    public Map<String, Object> acceptTransfer(int txId, int toUser) {
        return resolveTransfer(txId, toUser, true);
    }

    /**
     * 撤回/退还转账（事务）：pending→refunded，钱退回发起方，并更新转账消息。
     *
     * <p><b>两种调用者都合法</b>（见 {@link #resolveTransfer} 的权限分支）：
     * <ul>
     *   <li>发起方 {@code from_user} 调用 = <b>撤回</b>（反悔，钱退回自己）；</li>
     *   <li>接收方 {@code to_user} 调用 = <b>立即退还</b>（拒收，钱同样退回发起方）。</li>
     * </ul>
     * 注意两者钱都退给<b>发起方</b>（{@code moneyTo = fromUser}），不是退给操作者 ——
     * 所以接收方调用不会把钱打给自己。其他用户调用一律返回 null。
     *
     * <p>（原 javadoc 写"仅发起方可调"，与实际行为不符，已按实现更正。）
     */
    public Map<String, Object> refundTransfer(int txId, int fromUser) {
        return resolveTransfer(txId, fromUser, false);
    }

    /** 收款/撤回共用事务逻辑。 */
    private Map<String, Object> resolveTransfer(int txId, int operator, boolean accept) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            // SQLite 不支持 SELECT ... FOR UPDATE，改用"条件 UPDATE pending→终态"作为并发互斥：
            // 只有 status='pending' 的行才会被更新，连点两次收款/撤回只有一次成功，杜绝重复入账。
            Map<String, Object> tx;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM transactions WHERE id=?")) {
                ps.setInt(1, txId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    c.rollback();
                    return null;
                }
                tx = DbUtil.rowToMap(rs);
            }
            String status = tx.get("status") instanceof String s ? s : "";
            if (!"pending".equals(status)) {
                c.rollback();
                return null; // 已处理过（并发防重复入账）
            }
            int fromUser = ((Number) tx.get("from_user")).intValue();
            int toUser = ((Number) tx.get("to_user")).intValue();
            long amount = ((Number) tx.get("amount")).longValue();
            // 权限：accept 必须是 to_user；refund 可以是 from_user（撤回）或 to_user（立即退还）
            if (accept && operator != toUser) {
                c.rollback();
                return null;
            }
            if (!accept && operator != fromUser && operator != toUser) {
                c.rollback();
                return null;
            }
            String newStatus = accept ? "accepted" : "refunded";
            int moneyTo = accept ? toUser : fromUser; // 收款入账接收方；撤回退回发起方
            // 先做状态转移（pending→终态），失败说明已被并发处理
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET status=? WHERE id=? AND status='pending'")) {
                ps.setString(1, newStatus);
                ps.setInt(2, txId);
                if (ps.executeUpdate() <= 0) {
                    c.rollback();
                    return null; // 已被别人处理过
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wallets SET balance=balance+?, updated_at=? WHERE user_id=?")) {
                ps.setLong(1, amount);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, moneyTo);
                ps.executeUpdate();
            }
            long msgId = tx.get("ref_id") instanceof Number n ? n.longValue() : 0L;
            String newContent = null;
            if (msgId > 0) {
                String old = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT content FROM messages WHERE id=?")) {
                    ps.setLong(1, msgId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) old = rs.getString("content");
                }
                if (old != null) {
                    newContent = old.replace("\"status\":\"pending\"", "\"status\":\"" + newStatus + "\"");
                    if (!newContent.equals(old)) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE messages SET content=? WHERE id=?")) {
                            ps.setString(1, newContent);
                            ps.setLong(2, msgId);
                            ps.executeUpdate();
                        }
                    }
                }
            }
            c.commit();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("transfer_id", txId);
            r.put("from_user", fromUser);
            r.put("to_user", toUser);
            r.put("amount", amount);
            r.put("status", newStatus);
            r.put("msg_id", msgId);
            r.put("content", newContent);
            return r;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] resolveTransfer: " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
