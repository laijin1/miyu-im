package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MessagePreview} 单测。
 *
 * <p>锁两件事：
 * <ol>
 *   <li>转账预览 = {@code [转账] ¥金额}（amount 是分，除以 100），status 三种取值不影响金额；</li>
 *   <li><b>硬约束</b>：任何畸形输入都不得把原始 JSON / 裸路径返回给调用方
 *       （否则会话列表会重现 {@code {"transfer_id":87,"amount":100}} 那个缺陷）。</li>
 * </ol>
 *
 * <p>服务端 content 结构见 {@code server-java .../model/dao/WalletDao.java:500-503}：
 * {@code {"transfer_id":..,"amount":分,"memo":"..","status":"pending|accepted|refunded"}}。
 */
public class MessagePreviewTest {

    private static String transfer(String content) {
        return MessagePreview.forList(content, "transfer");
    }

    /** 畸形/不可解析输入的期望值：兜底标签。 */
    private static final String FALLBACK = MessagePreview.LABEL_TRANSFER;

    // ───────────────────────── 三种 status ─────────────────────────

    @Test
    public void transferPending_showsAmount() {
        assertEquals("[转账] ¥1.00", transfer(
                "{\"transfer_id\":87,\"amount\":100,\"memo\":\"\",\"status\":\"pending\"}"));
    }

    @Test
    public void transferAccepted_showsAmount() {
        assertEquals("[转账] ¥1.00", transfer(
                "{\"transfer_id\":87,\"amount\":100,\"memo\":\"\",\"status\":\"accepted\"}"));
    }

    @Test
    public void transferRefunded_showsAmount() {
        assertEquals("[转账] ¥1.00", transfer(
                "{\"transfer_id\":87,\"amount\":100,\"memo\":\"\",\"status\":\"refunded\"}"));
    }

    // ───────────────────────── 金额与 memo ─────────────────────────

    @Test
    public void transferChineseMemo_doesNotAffectAmount() {
        assertEquals("[转账] ¥1.00", transfer(
                "{\"transfer_id\":7,\"amount\":100,\"memo\":\"上次的饭钱，记得收\",\"status\":\"pending\"}"));
    }

    @Test
    public void transferAmount_isFenFormattedToYuan() {
        assertEquals("[转账] ¥1234.56", transfer(
                "{\"transfer_id\":1,\"amount\":123456,\"memo\":\"\",\"status\":\"pending\"}"));
        assertEquals("[转账] ¥0.01", transfer(
                "{\"transfer_id\":2,\"amount\":1,\"memo\":\"\",\"status\":\"accepted\"}"));
    }

    /** 服务端硬拒 amount<=0（WalletDao.createTransfer），此分支不可达；仍按规格断言「有值就格式化」。 */
    @Test
    public void transferZeroAmount_staysFormatted() {
        assertEquals("[转账] ¥0.00", transfer(
                "{\"transfer_id\":3,\"amount\":0,\"memo\":\"\",\"status\":\"pending\"}"));
    }

    // ───────────────────────── 缺字段 / 畸形 ─────────────────────────

    @Test
    public void transferMissingAmount_fallsBackToLabel() {
        assertEquals(FALLBACK, transfer(
                "{\"transfer_id\":4,\"memo\":\"\",\"status\":\"pending\"}"));
    }

    @Test
    public void transferNullAmount_fallsBackToLabel() {
        assertEquals(FALLBACK, transfer(
                "{\"transfer_id\":5,\"amount\":null,\"memo\":\"\",\"status\":\"pending\"}"));
    }

    @Test
    public void transferNonNumericAmount_fallsBackToLabel() {
        assertEquals(FALLBACK, transfer(
                "{\"transfer_id\":6,\"amount\":\"abc\",\"memo\":\"\",\"status\":\"pending\"}"));
        // amount 是个对象：getAsLong() 抛 UnsupportedOperationException，同样必须被吞掉
        assertEquals(FALLBACK, transfer(
                "{\"transfer_id\":6,\"amount\":{\"a\":1},\"status\":\"pending\"}"));
    }

    @Test
    public void transferMissingStatus_stillShowsAmount() {
        assertEquals("[转账] ¥1.00", transfer(
                "{\"transfer_id\":8,\"amount\":100,\"memo\":\"\"}"));
    }

    /** 同时锁死「列表页不会再出现转账 JSON 被截断 30 字符」那个现象。 */
    @Test
    public void transferMalformedJson_fallsBackToLabel() {
        assertEquals(FALLBACK, transfer("{\"transfer_id\":1,"));
        assertEquals(FALLBACK, transfer("{\"transfer_id\":1,\"amount\":100"));
        assertEquals(FALLBACK, transfer("not json at all"));
    }

    @Test
    public void transferNonObjectJson_fallsBackToLabel() {
        assertEquals(FALLBACK, transfer("[1,2,3]"));
        assertEquals(FALLBACK, transfer("100"));
        assertEquals(FALLBACK, transfer("\"transfer_id\""));
    }

    @Test
    public void transferNullAndBlank_fallBackToLabel() {
        assertEquals(FALLBACK, transfer(null));
        assertEquals(FALLBACK, transfer(""));
        assertEquals(FALLBACK, transfer("   "));
    }

    // ───────────────────────── 硬约束：绝不外泄原始内容 ─────────────────────────

    /**
     * 所有走兜底/异常路径的输入都不得返回原始 JSON 或裸路径。
     * 同时覆盖 image/video/voice/file 的裸路径 → 必须是方括号标签。
     */
    @Test
    public void neverLeaksRawJsonOrBarePath() {
        String[] trickyTransfers = {
                "{\"transfer_id\":1,",                                  // 非法 JSON
                "{\"transfer_id\":1,\"amount\":100",                    // 非法 JSON
                "not json at all",                                      // 非 JSON
                "[1,2,3]",                                              // JSON 数组
                "100",                                                  // 裸数字
                "\"transfer_id\"",                                      // 裸字符串
                "{\"amount\":null}",                                    // amount 为 null
                "{\"amount\":\"abc\"}",                                 // amount 非数字
                "{\"amount\":{\"a\":1}}",                               // amount 是对象
                "{\"transfer_id\":1,\"memo\":\"x\",\"status\":\"pending\"}", // 缺 amount
                "{",                                                    // 半个括号
                "{\"amount\":}",                                        // 语法错误
                "  {\"transfer_id\":1,  ",                              // 前后空白 + 非法
                null,
                "",
                "   ",
        };
        for (String in : trickyTransfers) {
            String out = transfer(in);
            assertNotNull("转账预览不得为 null，输入=" + in, out);
            assertNoRawLeak(out, in);
            assertTrue("输出必须是 [转账] 开头，输入=" + in + " 输出=" + out,
                    out.startsWith(MessagePreview.LABEL_TRANSFER));
        }

        // 非文本类型的裸 URL / 相对路径一律变成标签
        assertEquals("[图片]", MessagePreview.forList("/uploads/2026/01/a.jpg", "image"));
        assertEquals("[视频]", MessagePreview.forList("/uploads/2026/01/a.mp4", "video"));
        assertEquals("[语音]", MessagePreview.forList("/uploads/2026/01/a.amr", "voice"));
        assertEquals("[文件]", MessagePreview.forList("/uploads/2026/01/a.zip", "file"));
        assertNoRawLeak(MessagePreview.forList("/uploads/2026/01/a.zip", "file"), "/uploads/...");

        // 文本分支行为保持不变（trim + 30 字符截断；null → 空串）
        assertEquals("hello", MessagePreview.forList("hello", "text"));
        assertEquals("hello", MessagePreview.forList("  hello  ", null));
        assertEquals("", MessagePreview.forList(null, "text"));
        assertEquals("", MessagePreview.forList("   ", "text"));
        String longText = "这是一条刚好超过三十个字符的普通文本消息用来验证截断行为保持不变";
        assertEquals(30, MessagePreview.forList(longText, "text").length());
        assertEquals(longText.substring(0, 30), MessagePreview.forList(longText, "text"));
        // 两参重载 == 三参传 MAX_LEN（签名兼容，列表页/通知栏行为不变）
        assertEquals(MessagePreview.forList(longText, "text", MessagePreview.MAX_LEN),
                MessagePreview.forList(longText, "text"));
        assertEquals(30, MessagePreview.forList(longText, "text", MessagePreview.MAX_LEN).length());
        // 三参重载：搜索页传 Integer.MAX_VALUE 表示不截断（保住关键词高亮）
        assertEquals(longText, MessagePreview.forList(longText, "text", Integer.MAX_VALUE));
        assertEquals(longText, MessagePreview.forList(longText, "text", longText.length()));
        assertEquals(longText.substring(0, 10), MessagePreview.forList(longText, "text", 10));
        assertEquals("", MessagePreview.forList(longText, "text", 0));
        // 负值防御：按不截断处理，不抛异常
        assertEquals(longText, MessagePreview.forList(longText, "text", -1));
        // 非文本类型不受 maxLen 影响
        assertEquals("[视频]", MessagePreview.forList("/uploads/2026/01/a.mp4", "video", Integer.MAX_VALUE));
        assertEquals("[转账] ¥1.00", MessagePreview.forList(
                "{\"transfer_id\":87,\"amount\":100,\"status\":\"pending\"}", "transfer", Integer.MAX_VALUE));
    }

    private static void assertNoRawLeak(String out, String input) {
        assertFalse("不得含 JSON 起始符，输入=" + input + " 输出=" + out, out.contains("{"));
        assertFalse("不得含 transfer_id，输入=" + input + " 输出=" + out, out.contains("transfer_id"));
        assertFalse("不得含 amount，输入=" + input + " 输出=" + out, out.contains("amount"));
        assertFalse("不得含裸上传路径，输入=" + input + " 输出=" + out, out.contains("/uploads"));
        assertFalse("不得含裸 ip 地址，输入=" + input + " 输出=" + out, out.contains("http://"));
    }
}
