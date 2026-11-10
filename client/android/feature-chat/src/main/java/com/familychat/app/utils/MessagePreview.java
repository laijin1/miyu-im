package com.familychat.app.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * 会话预览文字的<b>唯一</b>映射入口（会话列表 / 聊天记录搜索 / 通知栏共用）。
 *
 * <p>历史缺陷：类型映射过去在三处各写了一份
 * （{@code MainActivity.previewText}、{@code MainActivity.onNewMessage} 的通知预览、
 * {@code SearchChatActivity.previewText}），三份都只认 image/video/voice/file 的一部分，于是：
 * <ul>
 *   <li><b>转账</b>（{@code msg_type="transfer"}，content 是服务端明文 JSON
 *       {@code {"transfer_id":..,"amount":分,"memo":..,"status":..}}）落进 default 分支，
 *       被当成普通文本截断 30 字符 → 会话列表里显示成 {@code {"transfer_id":87,"amount":100}}；
 *       而聊天气泡里同一笔转账正常渲染成橙色卡片。</li>
 *   <li>通知栏对 file/video 没有分支，直接把 {@code /uploads/2026/xx.mp4} 这种裸路径弹给用户。</li>
 * </ul>
 *
 * <p>本类是纯函数、无 I/O、无状态，可在主线程调用。
 *
 * <p><b>硬约束：任何分支都不得把原始 JSON 或裸 URL/路径返回给调用方。</b>
 * 解析失败一律退化成方括号标签。
 */
public final class MessagePreview {

    /** 文本预览长度上限（与历史上 {@code previewText} 的 substring(0,30) 一致）。 */
    public static final int MAX_LEN = 30;

    /** 转账标签；解析不出金额时的兜底文案。 */
    public static final String LABEL_TRANSFER = "[转账]";

    private MessagePreview() {}

    /**
     * 把一条消息映射成列表/搜索/通知用的预览文字（文本上限 {@link #MAX_LEN}）。
     *
     * @param content 消息正文：文本为明文或密文，非文本为 URL 或服务端 JSON
     * @param msgType 消息类型；null 按文本处理
     * @return 绝不为 null。非文本类型（image/video/voice/file/transfer）返回固定标签，
     *         其余分支返回 trim 后截断到 {@link #MAX_LEN} 个字符的正文
     */
    public static String forList(String content, String msgType) {
        return forList(content, msgType, MAX_LEN);
    }

    /**
     * 同上，但由调用方指定<b>文本分支</b>的长度上限。
     *
     * <p>存在的理由：会话列表/通知栏需要短（{@link #MAX_LEN}），
     * 而聊天记录搜索需要长 —— 搜索的核心价值就是让用户看到关键词落在哪，
     * 硬截断到 30 字符会让命中位置在第 31 字符之后的搜索结果<b>丢失关键词高亮</b>
     * （{@code item_search_result.xml} 的 {@code srPreview} 本身是
     * {@code maxLines=2 + ellipsize=end}，靠布局做视觉截断即可）。
     *
     * @param maxLen 文本分支长度上限；传 {@link Integer#MAX_VALUE} 表示不截断。
     *               非文本类型不受影响（无论 maxLen 为何都返回固定标签）；
     *               负值按「不截断」处理（纯防御，调用方只会传 MAX_LEN / Integer.MAX_VALUE）
     */
    public static String forList(String content, String msgType, int maxLen) {
        if ("image".equals(msgType)) return "[图片]";
        if ("video".equals(msgType)) return "[视频]";
        if ("voice".equals(msgType)) return "[语音]";
        if ("file".equals(msgType)) return "[文件]";
        if ("transfer".equals(msgType)) return transferPreview(content);

        if (content == null) return "";
        String s = content.trim();
        if (maxLen < 0 || s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    /**
     * 转账预览：{@code [转账] ¥1.00}（content 里的 amount 单位是<b>分</b>，除以 100）。
     *
     * <p>列表页<b>刻意不显示 status</b>：金额不变而状态会变，且列表当前不会因
     * {@code transfer_accepted / transfer_refunded} 事件刷新
     * （{@code MainActivity} 未实现 {@code onTransferEvent}，见 HEALTH-CHECK §9.3-E），
     * 一旦在列表写死状态，必然出现「列表显示待收款、点进去已收款」的不一致。
     *
     * <p>null / 非 JSON 对象 / 非法 JSON / 缺 amount / amount 为 null 或非数字
     * → {@link #LABEL_TRANSFER}。
     *
     * <p>本方法<b>必须自行吞掉所有异常</b>：调用方
     * {@code MainActivity.resolveFriendPreview} 的 {@code catch (Exception)} 会把异常
     * 映射成 {@code [无法解密]}（{@code PreviewDecryptor.PLACEHOLDER_FAILED}），
     * 那是完全不同的语义，绝不能让转账消息被误标成解密失败。
     */
    public static String transferPreview(String content) {
        if (content == null) return LABEL_TRANSFER;
        try {
            JsonElement el = JsonParser.parseString(content.trim());
            if (el == null || !el.isJsonObject()) return LABEL_TRANSFER;
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("amount") || obj.get("amount").isJsonNull()) return LABEL_TRANSFER;
            long fen = obj.get("amount").getAsLong();
            // amount <= 0 由服务端硬拒（WalletDao.createTransfer），不可达；
            // 万一出现，「[转账] ¥0.00」也比裸标签信息更多，故不额外兜底。
            return String.format(Locale.CHINA, LABEL_TRANSFER + " ¥%.2f", fen / 100.0);
        } catch (Exception e) {
            // 非法 JSON / amount 非数字：兜底标签，绝不外泄 content
            return LABEL_TRANSFER;
        }
    }
}
