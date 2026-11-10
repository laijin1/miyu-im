package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 好友领域 DAO（原 Database.java Friends 方法原样搬移，仅改连接获取方式
 * 与共享助手调用路径，不改变任何逻辑）。
 */
public class FriendDao {
    private final Database db;

    public FriendDao(Database db) {
        this.db = db;
    }

    public Map<String, Object> sendFriendRequest(int userId, int friendId) {
        if (userId == friendId) return DbUtil.err("不能添加自己为好友");
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM friendships WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)");
            ps.setInt(1, userId);
            ps.setInt(2, friendId);
            ps.setInt(3, friendId);
            ps.setInt(4, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String status = rs.getString("status");
                int friendshipId = rs.getInt("id");
                if ("accepted".equals(status)) return DbUtil.err("已经是好友了");
                if ("pending".equals(status) && rs.getInt("friend_id") == userId) {
                    // auto-accept: update status and reset created_at timestamp
                    PreparedStatement updatePs = c.prepareStatement(
                            "UPDATE friendships SET status='accepted', created_at=CURRENT_TIMESTAMP WHERE id=?");
                    updatePs.setInt(1, friendshipId);
                    updatePs.executeUpdate();
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "accepted");
                    return r;
                }
                if ("pending".equals(status)) return DbUtil.err("已发送过请求");
                if ("rejected".equals(status)) {
                    PreparedStatement updatePs = c.prepareStatement(
                            "UPDATE friendships SET status='pending', created_at=CURRENT_TIMESTAMP WHERE id=?");
                    updatePs.setInt(1, friendshipId);
                    updatePs.executeUpdate();
                    Map<String, Object> r = new HashMap<>();
                    r.put("status", "pending");
                    return r;
                }
            }
            PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO friendships (user_id, friend_id, status) VALUES (?,?,'pending')");
            ins.setInt(1, userId);
            ins.setInt(2, friendId);
            ins.executeUpdate();
            Map<String, Object> r = new HashMap<>();
            r.put("status", "pending");
            return r;
        } catch (SQLException e) {
            System.err.println("[ERROR] sendFriendRequest: " + e.getMessage());
            return DbUtil.err("请求失败");
        }
    }

    public boolean handleFriendRequest(int friendshipId, int friendId, String action) {
        String status = "accept".equals(action) ? "accepted" : "rejected";
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE friendships SET status=? WHERE id=? AND friend_id=?");
            ps.setString(1, status);
            ps.setInt(2, friendshipId);
            ps.setInt(3, friendId);
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] handleFriendRequest: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> getFriends(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            // 备注是单向的：只返回「当前用户」给对方设置的备注。
            // 行的 user_id 一侧存 remark_user，friend_id 一侧存 remark_friend。
            PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id,u.username,u.nickname,u.avatar,u.avatar_color,u.is_online,u.last_seen,u.wx_id," +
                            "CASE WHEN f.user_id=? THEN f.remark_user ELSE f.remark_friend END AS remark " +
                            "FROM friendships f JOIN users u ON " +
                            "CASE WHEN f.user_id=? THEN f.friend_id ELSE f.user_id END = u.id " +
                            "WHERE (f.user_id=? OR f.friend_id=?) AND f.status='accepted'");
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setInt(4, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getFriends: " + e.getMessage());
        }
        return list;
    }

    /**
     * Check if two users are friends (accepted friendship in either direction).
     */
    public boolean deleteFriendship(int userId, int friendId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "DELETE FROM friendships WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)");
            ps.setInt(1, userId); ps.setInt(2, friendId);
            ps.setInt(3, friendId); ps.setInt(4, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteFriendship: " + e.getMessage());
            return false;
        }
    }

    public boolean areFriends(int userId1, int userId2) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM friendships WHERE " +
                    "((user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)) " +
                    "AND status='accepted' LIMIT 1");
            ps.setInt(1, userId1); ps.setInt(2, userId2);
            ps.setInt(3, userId2); ps.setInt(4, userId1);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.err.println("[ERROR] areFriends: " + e.getMessage());
            return false;
        }
    }

    /**
     * 更新好友备注（单向，仅已接受的好友关系）。
     * 当前用户给 friendId 设置备注：若行的 user_id 是当前用户 → 写 remark_user；
     * 若行的 friend_id 是当前用户 → 写 remark_friend。绝不覆盖对方设的备注。
     */
    public boolean setFriendRemark(int userId, int friendId, String remark) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE friendships SET " +
                            "remark_user = CASE WHEN user_id=? THEN ? ELSE remark_user END, " +
                            "remark_friend = CASE WHEN friend_id=? THEN ? ELSE remark_friend END " +
                            "WHERE ((user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)) AND status='accepted'");
            ps.setInt(1, userId);   ps.setString(2, remark);
            ps.setInt(3, userId);   ps.setString(4, remark);
            ps.setInt(5, userId);   ps.setInt(6, friendId);
            ps.setInt(7, friendId); ps.setInt(8, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] setFriendRemark: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> getPendingRequests(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT f.id as friendship_id, u.id, u.username, u.nickname, u.avatar, u.avatar_color, u.wx_id, f.created_at " +
                            "FROM friendships f JOIN users u ON f.user_id=u.id " +
                            "WHERE f.friend_id=? AND f.status='pending'");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getPendingRequests: " + e.getMessage());
        }
        return list;
    }

    public List<Map<String, Object>> getSentRequests(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT f.id as friendship_id, f.status, u.id, u.username, u.nickname, u.avatar, u.avatar_color, u.wx_id, f.created_at " +
                            "FROM friendships f JOIN users u ON f.friend_id=u.id " +
                            "WHERE f.user_id=? AND f.status='pending'");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getSentRequests: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> getFriendById(int friendId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,username,nickname,avatar,avatar_color,is_online,wx_id FROM users WHERE id=?");
            ps.setInt(1, friendId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getFriendById: " + e.getMessage());
        }
        return null;
    }
}
