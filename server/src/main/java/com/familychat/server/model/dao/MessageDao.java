package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 单聊消息领域 DAO（原 Database.java Messages 方法原样搬移，仅改连接获取方式
 * 与共享助手调用路径，不改变任何逻辑）。
 */
public class MessageDao {
    private final Database db;

    public MessageDao(Database db) {
        this.db = db;
    }

    /**
     * Save a message using RETURN_GENERATED_KEYS instead of last_insert_rowid().
     */
    public Map<String, Object> saveMessage(int senderId, int receiverId, String content, String msgType) {
        return saveMessageWithFilename(senderId, receiverId, content, msgType, "");
    }

    /** 保存消息，附带原始文件名（用于搜索图片/文件消息）。 */
    public Map<String, Object> saveMessageWithFilename(int senderId, int receiverId, String content, String msgType, String filename) {
        return saveMessageWithThumbnail(senderId, receiverId, content, msgType, filename, "");
    }

    /** 保存消息，附带原始文件名与视频缩略图 URL。 */
    public Map<String, Object> saveMessageWithThumbnail(int senderId, int receiverId, String content, String msgType, String filename, String thumbnail) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO messages (sender_id,receiver_id,content,msg_type,filename,thumbnail) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, senderId);
            ps.setInt(2, receiverId);
            ps.setString(3, content);
            ps.setString(4, msgType);
            ps.setString(5, filename != null ? filename : "");
            ps.setString(6, thumbnail != null ? thumbnail : "");
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                PreparedStatement g = c.prepareStatement("SELECT * FROM messages WHERE id=?");
                g.setInt(1, rs.getInt(1));
                ResultSet gr = g.executeQuery();
                if (gr.next()) return DbUtil.rowToMap(gr);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] saveMsg: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> getMessages(int userId, int friendId, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM messages WHERE (sender_id=? AND receiver_id=?) " +
                            "OR (sender_id=? AND receiver_id=?) ORDER BY id DESC LIMIT ?");
            ps.setInt(1, userId);
            ps.setInt(2, friendId);
            ps.setInt(3, friendId);
            ps.setInt(4, userId);
            ps.setInt(5, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
            Collections.reverse(list);
        } catch (SQLException e) {
            System.err.println("[ERROR] getMessages: " + e.getMessage());
        }
        return list;
    }

    /** 按关键词模糊搜索会话消息（content + filename），按 id 倒序分页。 */
    public List<Map<String, Object>> searchMessages(int userId, int friendId, String keyword, int limit, int offset) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            String like = "%" + keyword + "%";
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM messages WHERE " +
                            "((sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)) " +
                            "AND msg_type!='recalled' AND (content LIKE ? OR filename LIKE ?) " +
                            "ORDER BY id DESC LIMIT ? OFFSET ?");
            ps.setInt(1, userId); ps.setInt(2, friendId);
            ps.setInt(3, friendId); ps.setInt(4, userId);
            ps.setString(5, like);
            ps.setString(6, like);
            ps.setInt(7, limit);
            ps.setInt(8, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] searchMessages: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> getMessageById(int msgId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM messages WHERE id=?");
            ps.setInt(1, msgId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getMessageById: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteMessage(int msgId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "DELETE FROM messages WHERE id=? AND sender_id=?");
            ps.setInt(1, msgId); ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteMessage: " + e.getMessage());
            return false;
        }
    }

    public boolean recallMessage(int msgId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "UPDATE messages SET content='[已撤回]', msg_type='recalled' WHERE id=? AND sender_id=? AND msg_type!='recalled'");
            ps.setInt(1, msgId); ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] recallMessage: " + e.getMessage());
            return false;
        }
    }

    /**
     * 清空 {@code userId} 与 {@code friendId} 之间的会话记录。
     *
     * <p>参与者校验（安全边界）：SQL 末尾的 {@code AND (sender_id=? OR receiver_id=?)}
     * 用调用者 userId 兜底，保证「调用者必须是这段会话的当事人之一」。
     * 不能只靠 handler 层判断——{@code friendId} 直接来自路径参数，
     * 少了这道守卫，任何登录用户都能删掉两个陌生人的全部私聊记录
     * （见 docs/audits/LOGIC-AUDIT-2026-09-21.md 第 1 条）。
     *
     * <p>注意语义：本方法是**双向**删除（双方视图都会变空）。
     * 「只清我这一侧」属产品决策，本次未改，见审计报告第 1 条的修法建议。
     */
    public int clearConversation(int userId, int friendId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "DELETE FROM messages WHERE ((sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)) " +
                        "AND (sender_id=? OR receiver_id=?)");
            ps.setInt(1, userId); ps.setInt(2, friendId);
            ps.setInt(3, friendId); ps.setInt(4, userId);
            // 参与者校验：调用者必须是发送方或接收方，否则 0 行受影响
            ps.setInt(5, userId); ps.setInt(6, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] clearConversation: " + e.getMessage());
            return 0;
        }
    }

    public int markRead(int senderId, int receiverId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET is_read=1 WHERE sender_id=? AND receiver_id=? AND is_read=0");
            ps.setInt(1, senderId);
            ps.setInt(2, receiverId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] markRead: " + e.getMessage());
            return 0;
        }
    }
}
