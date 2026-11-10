package com.familychat.server.admin;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 管理员侧的小工具集合：建表、密码生成、SQL 协助。
 *
 * <p>刻意<b>不</b>复用 {@link com.familychat.server.model.DbUtil} 的 rowToMap：
 * 那一份会把名为 password 的列静默剔除，管理端要读 admin_users 时容易踩坑。
 */
final class AdminSupport {

    private AdminSupport() {
    }

    /** 从 SQLException 里抽出可读原因，避免日志只留一行 "SQL error"。 */
    static String detail(SQLException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /**
     * 生成随机初始管理员密码。
     *
     * <p>为什么用 {@link java.security.SecureRandom} 而不是 Math.random：
     * 初始密码哪怕只存在到首次改密，它也是能进入管理端的凭证，必须不可预测。
     * 字符集剔除了 0/O/1/l/I 这类易混字符，因为运维要照着控制台手抄。
     */
    static String generatePassword(int len) {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 安全关闭，忽略异常（try-with-resources 里塞太多会让主流程读不下去）。 */
    static void close(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 关闭失败不影响业务结果
        }
    }

    /** 单个标量查询（COUNT 等），失败返回 0 而不是抛异常。 */
    static long scalar(java.sql.Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            System.err.println("[ADMIN] scalar 失败 " + sql + " -> " + detail(e));
            return 0L;
        }
    }
}
