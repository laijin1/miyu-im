package com.familychat.server.model;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 跨 DAO 共享的静态助手：原 Database.java 中被多个领域共用的私有方法原样搬移，
 * 仅改可见性（private → public static），不改变任何逻辑。
 * （"static package-private 放在 Database" 的方案不可行：model.dao 是不同 Java 包，
 * 包私有成员对子包不可见，会编译失败，故集中到本工具类。）
 */
public final class DbUtil {
    private DbUtil() {
    }

    // Columns to exclude from rowToMap for safety
    private static final Set<String> EXCLUDED_COLUMNS = Set.of("password");

    /**
     * JDBC 数值类型集合（SQLite JDBC 实测：INTEGER=4, REAL=7）。
     * 其他常见数值类型一并列出，避免换驱动后失效。
     */
    private static boolean isNumericType(int sqlType) {
        switch (sqlType) {
            case Types.TINYINT:      // -6
            case Types.SMALLINT:     // 5
            case Types.INTEGER:      // 4
            case Types.BIGINT:       // -5
            case Types.FLOAT:        // 6
            case Types.REAL:         // 7
            case Types.DOUBLE:       // 8
            case Types.NUMERIC:      // 2
            case Types.DECIMAL:      // 3
                return true;
            default:
                return false;
        }
    }

    /**
     * 把一行 {@link ResultSet} 转成 Map（列名小写）。
     *
     * <p><b>NULL 处理（审计第 7 条）：</b>只有文本类列才把 NULL 兜底成空串，
     * 数值列保持 {@code null}。原实现对**所有**列一律 {@code val = ""}，
     * 于是 {@code balance} 这类数值列一旦为 NULL 就变成字符串 {@code ""}，
     * 下游 {@code ((Number) m.get("balance")).longValue()} 会在离根因很远的地方
     * 抛 ClassCastException / 客户端 Gson 抛 NumberFormatException。
     *
     * <p>为什么用列类型而不是列名白名单：白名单要随每张表的新列手工维护，
     * 漏一个就重新引入同一个 bug；列类型由驱动直接给出，新列自动正确。
     */
    public static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String colName = md.getColumnName(i).toLowerCase();
            if (EXCLUDED_COLUMNS.contains(colName)) continue; // skip sensitive fields
            Object val = rs.getObject(i);
            // is_online 是 DB 的 INTEGER(0/1)，统一序列化为 boolean，与客户端 User.is_online 一致（否则 Gson 解析抛异常）
            if (colName.equals("is_online")) {
                val = val != null && ((Number) val).intValue() == 1;
            } else if (val == null && !isNumericType(md.getColumnType(i))) {
                // 仅文本/其他非数值列做空串兜底；数值列保持 null，让调用方显式处理
                val = "";
            }
            m.put(colName, val);
        }
        return m;
    }

    /** JSON 字符串转义（转账备注写进消息 content 用）。 */
    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Legacy SHA-256 hashing for backward compatibility during password migration.
     */
    public static String sha256Hex(String pwd) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(pwd.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> err(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", msg);
        return m;
    }

    /** 群内显示名（文件/相册上传者展示用）。 */
    public static String displayName(Database db, int uid) {
        Map<String, Object> u = db.getUserById(uid);
        if (u == null) return "用户" + uid;
        String n = u.get("nickname") instanceof String s && !s.isEmpty() ? s : "";
        if (n.isEmpty()) n = u.get("username") instanceof String us ? us : "";
        return n.isEmpty() ? "用户" + uid : n;
    }
}
