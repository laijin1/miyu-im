package com.familychat.app.utils;

import java.util.Calendar;
import java.util.Locale;

/**
 * 密语式时间文案（聊天页时间分隔符 / 系统消息共用，唯一实现）。
 *
 * <p>规则：
 * <ul>
 *   <li>今天 → {@code "上午 10:30"}</li>
 *   <li>昨天 → {@code "昨天 上午 10:30"}</li>
 *   <li>今年 → {@code "8月17日 上午 8:14"}</li>
 *   <li>往年 → {@code "2025年8月17日 上午 8:14"}</li>
 * </ul>
 *
 * <p>只用 {@link Calendar} / {@link String#format}，Java 8+ 与低版本 Android 均兼容，
 * 不引入 java.time，保持 minSdk 24 可用。
 */
public final class ChatTimeFormatter {

    private ChatTimeFormatter() {}

    /**
     * 解析服务端 {@code created_at} 为毫秒时间戳。
     *
     * <p>服务端 {@code created_at} 由 SQLite {@code CURRENT_TIMESTAMP} 生成，**固定是 UTC**
     * （{@code Database.java:97}，形如 {@code "2026-08-17 00:14:00"}）。
     * 历史实现用默认时区的 {@link java.text.SimpleDateFormat} 解析，把 UTC 当本地时间，
     * 在 UTC+8 下整体**早 8 小时**（且本地 00:00–08:00 的消息会被判成前一天）。
     * 这里显式指定 UTC 时区，得到正确的 epoch 毫秒；显示端再用本地时区渲染。
     *
     * <p>兼容 ISO 变体：{@code "2026-08-17T00:14:00"}、
     * {@code "2026-08-17T00:14:00.000Z"}（只取前 19 位，尾部的 {@code .SSS}/{@code Z} 忽略）。
     *
     * <p>解析失败（null / 空 / 空白 / 非法）返回 {@code 0}。
     * 非 {@code yyyy-} 开头的历史值按「纯毫秒数」兜底解析。
     */
    public static long parseUtc(String createdAt) {
        if (createdAt == null) return 0L;
        String s = createdAt.trim();
        if (s.isEmpty()) return 0L;

        if (s.length() >= 19 && s.charAt(4) == '-') {
            try {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); // 关键：created_at 是 UTC
                // 必须关掉宽松模式：默认 SimpleDateFormat 会把 "2026-13-45 99:99:99"
                // 静默滚成 2027 年 2 月的某天，而不是报错 → 非法输入必须返回 0
                sdf.setLenient(false);
                java.util.Date d = sdf.parse(s.substring(0, 19).replace('T', ' '));
                return d != null ? d.getTime() : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }
        // 兜底：纯毫秒数（当前库里 created_at 全为文本，此分支为防御性保留）
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 生产入口：以「当前时刻」为基准做相对格式化。 */
    public static String format(long millis) {
        return format(millis, System.currentTimeMillis());
    }

    /** 可注入「现在」的重载，便于单元测试固定今天/昨天/今年/往年四种分支。 */
    public static String format(long millis, long nowMillis) {
        if (millis <= 0) return "";

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);

        // 上午/下午 + 12 小时制（0 点显示为 12），分钟补零
        String ap = c.get(Calendar.HOUR_OF_DAY) < 12 ? "上午" : "下午";
        int h = c.get(Calendar.HOUR_OF_DAY) % 12;
        if (h == 0) h = 12;
        String time = ap + " " + h + ":" + String.format(Locale.CHINA, "%02d", c.get(Calendar.MINUTE));

        boolean sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return time;

        Calendar yest = (Calendar) now.clone();
        yest.add(Calendar.DAY_OF_YEAR, -1);
        boolean isYest = c.get(Calendar.YEAR) == yest.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == yest.get(Calendar.DAY_OF_YEAR);
        if (isYest) return "昨天 " + time;

        String md = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) return md + " " + time;
        return c.get(Calendar.YEAR) + "年" + md + " " + time;
    }

    /**
     * 完整绝对时间文案：{@code "2026年9月19日 下午 8:47"}（今年）/ {@code "2025年9月19日 下午 8:47"}。
     *
     * <p>与 {@link #format(long)} 的区别：**永远带年份与日期**，不做「今天/昨天」相对化。
     * 用于单据类页面（转账详情），那里只写「下午 8:47」会有歧义。
     *
     * <p>{@code millis <= 0} 返回 {@code ""}。
     */
    public static String formatFull(long millis) {
        if (millis <= 0) return "";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);

        String ap = c.get(Calendar.HOUR_OF_DAY) < 12 ? "上午" : "下午";
        int h = c.get(Calendar.HOUR_OF_DAY) % 12;
        if (h == 0) h = 12;
        String time = ap + " " + h + ":" + String.format(Locale.CHINA, "%02d", c.get(Calendar.MINUTE));

        return c.get(Calendar.YEAR) + "年" + (c.get(Calendar.MONTH) + 1) + "月"
                + c.get(Calendar.DAY_OF_MONTH) + "日 " + time;
    }
}
