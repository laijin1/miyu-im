package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileHandler.parseRange 的 HTTP Range 解析语义（LOGIC-AUDIT 第 6 条）。
 *
 * <p>修复后改为**反射调用生产代码里的真实解析器** {@code FileHandler.parseRange}。
 * 此前是逐字复刻一份解析逻辑，复刻版不会随生产代码修复而变红，起不到回归作用。
 */
class FileHandlerRangeTest {

    /** 反射调用 parseRange，返回 [start, end, isRange, invalid]。 */
    private static long[] parse(String range, long size) throws Exception {
        Class<?> fh = Class.forName("com.familychat.server.handler.FileHandler");
        Method m = fh.getDeclaredMethod("parseRange", String.class, long.class);
        m.setAccessible(true);
        Object rs = m.invoke(null, range, size);
        return new long[]{
                num(rs, "start"), num(rs, "end"),
                bool(rs, "isRange"), bool(rs, "invalid")
        };
    }

    private static long num(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((Number) f.get(o)).longValue();
    }

    private static long bool(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(o) ? 1 : 0;
    }

    private static String describe(long[] r) {
        boolean invalid = r[3] == 1, isRange = r[2] == 1;
        return "start=" + r[0] + " end=" + r[1] + " isRange=" + isRange
                + " -> " + (invalid ? "416" : (isRange ? "206" : "200"));
    }

    /**
     * 12 个 Range 用例的解析结果逐个断言（不再只打印）。
     *
     * <p>期望值来自 HTTP Range 语义 + {@code FileHandler.parseRange} 的契约：不可满足/语法非法的
     * 三条只断言 {@code invalid=1}——此时 start/end/isRange 对调用方没有意义，不做过度断言。
     */
    @Test
    void rangeSemantics() throws Exception {
        long size = 1000;
        // {Range 头, 期望 start, 期望 end, 期望 isRange, 期望 invalid}；null = 该项不断言
        Object[][] cases = {
                {"bytes=0-499",     0L,   499L,   1L, 0L},   // 正常闭区间
                {"bytes=500-",      500L, 999L,   1L, 0L},   // 开放结尾
                {"bytes=500-999",   500L, 999L,   1L, 0L},   // 到尾
                {"bytes=500-5000",  500L, 999L,   1L, 0L},   // 超范围 -> 截断到 size-1
                {"bytes=-500",      500L, 999L,   1L, 0L},   // 后缀区间：最后 500 字节
                {"bytes=-5000",       0L, 999L,   1L, 0L},   // 后缀超长 -> 整个文件，但仍是 206
                {"bytes=0-0",         0L,   0L,   1L, 0L},   // 单字节
                {"bytes=999-999",   999L, 999L,   1L, 0L},   // 最后一字节
                {"bytes=1000-",     null, null, null, 1L},   // 起点越界 -> 416
                {"bytes=abc-def",   null, null, null, 1L},   // 非法语法 -> 416
                {"bytes=500-100",   null, null, null, 1L},   // 反向区间 -> 416
                {null,                0L, 999L,   0L, 0L},   // 无 Range -> 200 整段
        };
        for (Object[] c : cases) {
            String range = (String) c[0];
            long[] r = parse(range, size);
            System.out.printf("[RANGE] %-16s -> %s%n", String.valueOf(range), describe(r));
            if (c[1] != null) assertEquals(((Number) c[1]).longValue(), r[0], range + " 的 start");
            if (c[2] != null) assertEquals(((Number) c[2]).longValue(), r[1], range + " 的 end");
            if (c[3] != null) assertEquals(((Number) c[3]).longValue(), r[2], range + " 的 isRange");
            assertEquals(((Number) c[4]).longValue(), r[3], range + " 的 invalid");
        }
    }

    /** 后缀区间：bytes=-500 必须是一个真正的 206 片段。 */
    @Test
    void suffixRangeIsHonored() throws Exception {
        long[] r = parse("bytes=-500", 1000);
        System.out.println("[RANGE-SUFFIX] bytes=-500 -> " + describe(r));
        assertEquals(1, r[2], "后缀区间必须被识别为 Range（206 而非 200）");
        assertEquals(0, r[3], "不应被视为非法");
        assertEquals(500, r[0], "start 应为 size-500");
        assertEquals(999, r[1], "end 应为 size-1");
    }

    /** 非法语法必须 416，不得静默回退成 200。 */
    @Test
    void malformedRangeIs416() throws Exception {
        for (String bad : new String[]{"bytes=abc-def", "bytes=500-100", "bytes=1000-", "bytes=-"}) {
            long[] r = parse(bad, 1000);
            System.out.println("[RANGE-BAD] " + bad + " -> " + describe(r));
            assertEquals(1, r[3], bad + " 应判定为非法(416)");
        }
    }

    /** 正常区间与无 Range 的行为不能被改动破坏。 */
    @Test
    void normalRangesUnaffected() throws Exception {
        long[] a = parse("bytes=0-499", 1000);
        assertEquals(0, a[0]); assertEquals(499, a[1]); assertEquals(1, a[2]); assertEquals(0, a[3]);
        long[] b = parse("bytes=500-", 1000);
        assertEquals(500, b[0]); assertEquals(999, b[1]); assertEquals(1, b[2]);
        long[] c = parse(null, 1000);
        assertEquals(0, c[0]); assertEquals(999, c[1]); assertEquals(0, c[2]);
        System.out.println("[RANGE-NORMAL] 正常区间/无 Range 行为未变");
    }

    /** 空文件：任何 Range 都不可满足 -> 416。 */
    @Test
    void emptyFileIs416() throws Exception {
        long[] r = parse("bytes=0-", 0);
        System.out.println("[RANGE-EMPTY] bytes=0- size=0 -> " + describe(r));
        assertEquals(1, r[3], "空文件的 Range 应 416");
    }
}
