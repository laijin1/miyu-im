package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * 单聊附件台账 DAO：记录「服务端生成名 → 上传者 userId」。
 *
 * <p><b>为什么需要这张表。</b>上传成功后服务端返回 {@code /chat_files/<生成名>}，客户端
 * 立刻把这个明文 URL 用 E2EE 加密后写进 {@code messages.content}（客户端
 * {@code ChatActivity.uploadAndSendFile} → {@code maybeEncrypt}）。因此服务端<b>无法</b>
 * 再用 URL 反查 messages —— {@code GroupMaintenance.isFileReferenced} 对单聊附件永远
 * 匹配不到（实测 data/chat_files 119 个文件，messages 里能匹配到 URL 的只有 3 条，
 * 且都来自群明文路径）。
 *
 * <p><b>为什么可以只改这里。</b>上传时刻 URL 还是明文，服务端既是它的制造者，也刚刚
 * 从鉴权拿到 uploaderId；生成名里本来就内嵌了 userId。所以在落盘成功处顺手记一行
 * 即可，<b>完全不触碰加密边界</b>，不需要解密 content，也不需要改客户端。
 *
 * <p>本表是<b>纯增量台账</b>，不参与任何现有读写路径；写入失败不影响上传结果。
 */
public class ChatFileUploadDao {
    private final Database db;

    public ChatFileUploadDao(Database db) {
        this.db = db;
    }

    /**
     * 记录一次成功落盘的上传。幂等：同名重复调用会覆盖同一行（PK = generated_name）。
     *
     * <p><b>绝不抛异常</b>：记账是旁路，调用方（FileHandler.uploadFile）不因此失败。
     * 失败只打日志并返回 false。
     *
     * @param generatedName 服务端生成名（{@code /chat_files/} 后面那一段），主键
     * @param uploaderId    上传者 userId（来自鉴权，<b>不是</b>从文件名解析）
     * @param originalName  客户端上传时的原始文件名（明文，仅供检索/排障）
     * @param sizeBytes     实际落盘字节数
     * @return true = 已记账；false = 参数非法或写入失败（已打日志）
     */
    public boolean recordUpload(String generatedName, int uploaderId, String originalName, long sizeBytes) {
        if (generatedName == null || generatedName.isEmpty() || uploaderId <= 0) {
            System.err.println("[WARN] recordUpload: 参数非法，跳过记账 (name=" + generatedName
                    + ", uploader=" + uploaderId + ")");
            return false;
        }
        String sql = "INSERT OR REPLACE INTO chat_file_uploads " +
                "(generated_name,uploader_id,original_name,size_bytes,uploaded_at) VALUES (?,?,?,?,?)";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, generatedName);
            ps.setInt(2, uploaderId);
            ps.setString(3, originalName == null ? "" : originalName);
            ps.setLong(4, sizeBytes);
            // uploaded_at 与生成名用同一套毫秒时钟，便于与磁盘 mtime / TTL 报告对齐
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] recordUpload(" + generatedName + "): " + e.getMessage());
            return false;
        }
    }

    /** 按生成名查一条台账；不存在或查询失败返回 null。 */
    public Map<String, Object> findUpload(String generatedName) {
        if (generatedName == null || generatedName.isEmpty()) return null;
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM chat_file_uploads WHERE generated_name=?")) {
            ps.setString(1, generatedName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return DbUtil.rowToMap(rs);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] findUpload(" + generatedName + "): " + e.getMessage());
        }
        return null;
    }
}
