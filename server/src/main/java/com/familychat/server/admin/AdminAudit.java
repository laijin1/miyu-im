package com.familychat.server.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/** 审计相关的少量助手：详情 JSON 拼装 + 初始密码标记查询。 */
public final class AdminAudit {

    private AdminAudit() {
    }

    /**
     * 把若干 key/value 拼成审计详情字符串。
     *
     * <p>刻意手写而不是引 Gson：详情是要塞进 SQLite 文本列的，字段少且都是标量，
     * 手写能顺手把引号/反斜杠/换行转义干净（否则管理员填的备注里一个双引号就能
     * 让这条"详情"变成无法解析的字符串）。数字与布尔原样输出，便于人肉阅读。
     */
    public static String json(Object... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (b.length() > 1) b.append(",");
            b.append('"').append(escape(String.valueOf(kv[i]))).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number || v instanceof Boolean) {
                b.append(v);
            } else {
                b.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return b.append("}").toString();
    }

    /** JSON 字符串转义（最小正确集：引号、反斜杠、控制字符）。 */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** 该管理员是否还在用初始密码（首页会持续提示改密）。 */
    public static boolean mustChangePassword(AdminDao dao, long adminId) {
        Map<String, Object> m = dao.findById(adminId);
        if (m == null) return false;
        Object v = m.get("must_change_pw");
        return v instanceof Number n && n.intValue() == 1;
    }

    /** 便捷构造：审计列表里把 null 显示成空串。 */
    public static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
