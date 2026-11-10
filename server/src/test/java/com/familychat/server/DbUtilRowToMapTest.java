package com.familychat.server;

import com.familychat.server.model.DbUtil;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbUtil.rowToMap 的 NULL 列类型语义（LOGIC-AUDIT 第 8 条）。
 *
 * <p>修复前：**所有**列的 NULL 都被兜底成字符串 {@code ""}，于是数值列
 * （如 balance）一旦为 NULL 就变成 {@code ""}，下游 {@code ((Number) v).longValue()}
 * 会在离根因很远的地方抛 ClassCastException。
 *
 * <p>修复后：只有非数值列兜底成 {@code ""}，数值列保持 {@code null}。
 *
 * <p>桩现在带上 SQL 类型，才能驱动真实的分支逻辑。
 */
class DbUtilRowToMapTest {

    /** 极简 ResultSet 桩：支持 rowToMap 用到的 getMetaData/getObject。 */
    static class StubRs implements java.lang.reflect.InvocationHandler {
        final String[] cols;
        final int[] types;
        final Object[] vals;

        StubRs(String[] cols, int[] types, Object[] vals) {
            this.cols = cols; this.types = types; this.vals = vals;
        }

        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
            switch (m.getName()) {
                case "getMetaData":
                    return java.lang.reflect.Proxy.newProxyInstance(
                            ResultSetMetaData.class.getClassLoader(),
                            new Class<?>[]{ResultSetMetaData.class},
                            (p, mm, aa) -> {
                                int idx = (aa != null && aa.length > 0 && aa[0] instanceof Integer i) ? i - 1 : 0;
                                switch (mm.getName()) {
                                    case "getColumnCount": return cols.length;
                                    case "getColumnName": return cols[idx];
                                    case "getColumnType": return types[idx];
                                    default: return null;
                                }
                            });
                case "getObject": return vals[(Integer) args[0] - 1];
                default: return null;
            }
        }
    }

    private ResultSet rs(String[] cols, int[] types, Object[] vals) {
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new StubRs(cols, types, vals));
    }

    @Test
    void numericNullStaysNull() throws Exception {
        Object b = DbUtil.rowToMap(rs(new String[]{"balance"}, new int[]{Types.INTEGER}, new Object[]{null}))
                .get("balance");
        System.out.println("[ROW] balance(INTEGER)=NULL -> "
                + (b == null ? "null" : "'" + b + "' (" + b.getClass().getSimpleName() + ")"));
        assertNull(b, "数值列的 NULL 必须保持 null，不能变成字符串 \"\"");
    }

    @Test
    void textNullBecomesEmptyString() throws Exception {
        Object t = DbUtil.rowToMap(rs(new String[]{"memo"}, new int[]{Types.VARCHAR}, new Object[]{null}))
                .get("memo");
        System.out.println("[ROW] memo(VARCHAR)=NULL -> '" + t + "'");
        assertEquals("", t, "文本列的 NULL 仍兜底成空串（保持既有行为，客户端不用改）");
    }

    @Test
    void isOnlineStillBoolean() throws Exception {
        Object n = DbUtil.rowToMap(rs(new String[]{"is_online"}, new int[]{Types.INTEGER}, new Object[]{null}))
                .get("is_online");
        Object one = DbUtil.rowToMap(rs(new String[]{"is_online"}, new int[]{Types.INTEGER}, new Object[]{1}))
                .get("is_online");
        Object zero = DbUtil.rowToMap(rs(new String[]{"is_online"}, new int[]{Types.INTEGER}, new Object[]{0}))
                .get("is_online");
        System.out.println("[ROW] is_online NULL=" + n + " 1=" + one + " 0=" + zero);
        assertEquals(Boolean.FALSE, n, "is_online 的 NULL 视为 false");
        assertEquals(Boolean.TRUE, one);
        assertEquals(Boolean.FALSE, zero);
    }

    @Test
    void passwordStillExcluded() throws Exception {
        boolean present = DbUtil.rowToMap(rs(new String[]{"password"}, new int[]{Types.VARCHAR},
                new Object[]{"hash"})).containsKey("password");
        System.out.println("[ROW] password 列存在? " + present);
        assertFalse(present, "password 列必须被排除");
    }

    /** 回归重点：数值列 NULL 之后，下游按 Number 取值不再炸。 */
    @Test
    void numericNullDoesNotBreakNumberCast() throws Exception {
        Object b = DbUtil.rowToMap(rs(new String[]{"refund_time"}, new int[]{Types.INTEGER}, new Object[]{null}))
                .get("refund_time");
        // 修复前这里是 "" -> ClassCastException
        assertNull(b);
        long fallback = (b instanceof Number num) ? num.longValue() : 0L;
        assertEquals(0L, fallback, "下游可用 instanceof 安全兜底");
        System.out.println("[ROW][OK] 数值列 NULL 不再污染下游 Number 转换");
    }
}
