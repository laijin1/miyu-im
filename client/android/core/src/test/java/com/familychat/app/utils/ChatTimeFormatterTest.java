package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link ChatTimeFormatter} 的单元测试：相对格式各分支 + UTC 解析 + 完整格式。
 *
 * <p>相对格式依赖 JVM 默认时区，所以固定钉死 {@code Asia/Shanghai}（UTC+8），
 * 既让断言与测试机时区无关，也正是本次时区 bug 的实际场景（差 8 小时）。
 * {@code parseUtc} 内部显式用 UTC，不受此设置影响。
 */
public class ChatTimeFormatterTest {

    /** 以当前（被钉死的）默认时区构造一个确定的时间点，month 为 1-12。 */
    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 「现在」在测试方法内求值（不是 static 常量）——否则会在 {@link #pinZone()} 之前
     * 用测试机原始时区算出来，与钉死后的时区不一致。
     */
    private static long now() {
        return at(2026, 8, 20, 15, 30);
    }

    private TimeZone originalZone;

    @Before
    public void pinZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @After
    public void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    // ═══════════ format(long, long)：相对格式 ═══════════

    @Test
    public void todayMorning_usesPeriodPrefix() {
        assertEquals("上午 10:30", ChatTimeFormatter.format(at(2026, 8, 20, 10, 30), now()));
    }

    @Test
    public void todayAfternoon_usesAfternoonPeriod() {
        assertEquals("下午 3:05", ChatTimeFormatter.format(at(2026, 8, 20, 15, 5), now()));
    }

    @Test
    public void midnight_rendersTwelveAm() {
        assertEquals("上午 12:00", ChatTimeFormatter.format(at(2026, 8, 20, 0, 0), now()));
    }

    @Test
    public void yesterday_prefixedWithYesterday() {
        assertEquals("昨天 上午 8:14", ChatTimeFormatter.format(at(2026, 8, 19, 8, 14), now()));
    }

    @Test
    public void thisYear_usesChineseMonthDay() {
        assertEquals("8月17日 上午 8:14", ChatTimeFormatter.format(at(2026, 8, 17, 8, 14), now()));
    }

    @Test
    public void previousYear_includesYear() {
        assertEquals("2025年8月17日 上午 8:14", ChatTimeFormatter.format(at(2025, 8, 17, 8, 14), now()));
    }

    @Test
    public void nonPositiveMillis_returnsEmpty() {
        assertEquals("", ChatTimeFormatter.format(0L, now()));
    }

    // ═══════════ parseUtc(String)：UTC 文本 → 毫秒 ═══════════

    @Test
    public void parseUtc_producesCorrectUtcInstant() {
        // 独立于运行时时区的硬断言：与用 UTC Calendar 构造的同一 instant 相等
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(2026, Calendar.AUGUST, 17, 0, 14, 0);
        assertEquals(u.getTimeInMillis(), ChatTimeFormatter.parseUtc("2026-08-17 00:14:00"));
    }

    @Test
    public void parseUtc_acceptsIsoTFormat() {
        assertEquals(ChatTimeFormatter.parseUtc("2026-08-17 00:14:00"),
                ChatTimeFormatter.parseUtc("2026-08-17T00:14:00"));
    }

    @Test
    public void parseUtc_acceptsMillisAndZSuffix() {
        assertEquals(ChatTimeFormatter.parseUtc("2026-08-17 00:14:00"),
                ChatTimeFormatter.parseUtc("2026-08-17T00:14:00.000Z"));
    }

    @Test
    public void parseUtc_invalidOrEmpty_returnsZero() {
        assertEquals(0L, ChatTimeFormatter.parseUtc(null));
        assertEquals(0L, ChatTimeFormatter.parseUtc(""));
        assertEquals(0L, ChatTimeFormatter.parseUtc("   "));
        assertEquals(0L, ChatTimeFormatter.parseUtc("abc"));
        assertEquals(0L, ChatTimeFormatter.parseUtc("2026-13-45 99:99:99"));
    }

    @Test
    public void parseUtc_epochMillisFallback() {
        // 防御性兜底：非 yyyy- 开头的历史值按纯毫秒数解析
        assertEquals(1755000000000L, ChatTimeFormatter.parseUtc("1755000000000"));
    }

    // ═══════════ parseUtc → format：端到端（本次时区 bug 的回归）═══════════

    @Test
    public void parseUtc_thenFormat_showsLocalTime() {
        // 服务端存 UTC 00:14 → 上海(UTC+8) 应为 08:14。
        // 修复前用默认时区解析 UTC 串，这里会得到「上午 12:14」（早 8 小时）。
        long ms = ChatTimeFormatter.parseUtc("2026-08-17 00:14:00");
        assertEquals("8月17日 上午 8:14", ChatTimeFormatter.format(ms, now()));
    }

    // ═══════════ formatFull(long)：始终带年月日 ═══════════

    @Test
    public void formatFull_afternoon_matchesDocumentedExample() {
        // 2025-09-19 12:47:05 UTC → 上海 20:47
        long ms = ChatTimeFormatter.parseUtc("2025-09-19 12:47:05");
        assertEquals("2025年9月19日 下午 8:47", ChatTimeFormatter.formatFull(ms));
    }

    @Test
    public void formatFull_ignoresTodayYesterdayRelativization() {
        // 与 format 不同：即使是「今天」，也必须带完整年月日
        long ms = ChatTimeFormatter.parseUtc("2026-08-20 02:14:00"); // 上海 10:14
        assertEquals("2026年8月20日 上午 10:14", ChatTimeFormatter.formatFull(ms));
    }

    @Test
    public void formatFull_nonPositive_returnsEmpty() {
        assertEquals("", ChatTimeFormatter.formatFull(0L));
    }
}
