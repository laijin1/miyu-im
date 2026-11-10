package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 群公告领域 DAO（原 Database.java 群公告方法原样搬移，仅改连接获取方式
 * 与共享助手调用路径，不改变任何逻辑）。
 */
public class GroupAnnouncementDao {
    private final Database db;

    public GroupAnnouncementDao(Database db) {
        this.db = db;
    }

    /** 发公告（publisher 须为群主/管理员）。content 为 {成员id:密文} JSON，服务端只存不解。 */
    public Map<String, Object> createAnnouncement(int groupId, int publisherId, String content, int pinned) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_announcements (group_id,publisher_id,content,pinned) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, groupId); ps.setInt(2, publisherId);
            ps.setString(3, content); ps.setInt(4, pinned);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int aid = rs.getInt(1);
                // 同步最新公告位（横幅取最新）
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE groups SET latest_announcement_id=? WHERE id=?")) {
                    up.setInt(1, aid); up.setInt(2, groupId); up.executeUpdate();
                }
                return getGroupAnnouncement(aid);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] createAnnouncement: " + e.getMessage());
        }
        return null;
    }

    /** 公告列表（最新在前，置顶优先），每条带 read_count。 */
    public List<Map<String, Object>> getGroupAnnouncements(int groupId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT a.*, (SELECT COUNT(*) FROM group_announcement_reads r " +
                            "WHERE r.announcement_id=a.id) AS read_count " +
                            "FROM group_announcements a WHERE a.group_id=? " +
                            "ORDER BY a.pinned DESC, a.id DESC");
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAnnouncements: " + e.getMessage());
        }
        return list;
    }

    /** 公告详情（含 read_by 已读成员 uid 列表）。 */
    public Map<String, Object> getGroupAnnouncement(int announcementId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT a.*, (SELECT COUNT(*) FROM group_announcement_reads r " +
                            "WHERE r.announcement_id=a.id) AS read_count " +
                            "FROM group_announcements a WHERE a.id=?");
            ps.setInt(1, announcementId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                List<Integer> readBy = new ArrayList<>();
                try (PreparedStatement rp = c.prepareStatement(
                        "SELECT user_id FROM group_announcement_reads WHERE announcement_id=?")) {
                    rp.setInt(1, announcementId);
                    ResultSet rr = rp.executeQuery();
                    while (rr.next()) readBy.add(rr.getInt(1));
                }
                m.put("read_by", readBy);
                return m;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAnnouncement: " + e.getMessage());
        }
        return null;
    }

    /** 改公告：content/pinned 传 null 表示不变。返回更新后的公告详情。 */
    public Map<String, Object> updateAnnouncement(int announcementId, String content, Integer pinned) {
        try (Connection c = db.getConn()) {
            if (content != null && pinned != null) {
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE group_announcements SET content=?, pinned=?, updated_at=CURRENT_TIMESTAMP WHERE id=?");
                ps.setString(1, content); ps.setInt(2, pinned); ps.setInt(3, announcementId);
                ps.executeUpdate();
            } else if (content != null) {
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE group_announcements SET content=?, updated_at=CURRENT_TIMESTAMP WHERE id=?");
                ps.setString(1, content); ps.setInt(2, announcementId);
                ps.executeUpdate();
            } else if (pinned != null) {
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE group_announcements SET pinned=?, updated_at=CURRENT_TIMESTAMP WHERE id=?");
                ps.setInt(1, pinned); ps.setInt(2, announcementId);
                ps.executeUpdate();
            } else {
                return null;
            }
            // 更新也算最新，横幅刷新
            try (PreparedStatement g = c.prepareStatement(
                    "SELECT group_id FROM group_announcements WHERE id=?")) {
                g.setInt(1, announcementId);
                ResultSet gr = g.executeQuery();
                if (gr.next()) {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE groups SET latest_announcement_id=? WHERE id=?")) {
                        up.setInt(1, announcementId); up.setInt(2, gr.getInt(1)); up.executeUpdate();
                    }
                }
            }
            return getGroupAnnouncement(announcementId);
        } catch (SQLException e) {
            System.err.println("[ERROR] updateAnnouncement: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteAnnouncement(int announcementId) {
        try (Connection c = db.getConn()) {
            // 先取所属群，删除后回退 latest_announcement_id，避免群横幅指针指向已删除公告
            int groupId = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT group_id FROM group_announcements WHERE id=?")) {
                ps.setInt(1, announcementId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) groupId = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_announcement_reads WHERE announcement_id=?")) {
                ps.setInt(1, announcementId); ps.executeUpdate();
            }
            PreparedStatement ps = c.prepareStatement("DELETE FROM group_announcements WHERE id=?");
            ps.setInt(1, announcementId);
            boolean ok = ps.executeUpdate() > 0;
            if (ok && groupId > 0) {
                // 回退为该群现存最新公告（无则置 0）
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE groups SET latest_announcement_id = COALESCE(" +
                                "(SELECT MAX(id) FROM group_announcements WHERE group_id=?), 0) WHERE id=?")) {
                    up.setInt(1, groupId);
                    up.setInt(2, groupId);
                    up.executeUpdate();
                }
            }
            return ok;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteAnnouncement: " + e.getMessage());
        }
        return false;
    }

    /** 标记公告已读（幂等）。返回是否新标记。 */
    public boolean markAnnouncementRead(int announcementId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO group_announcement_reads (announcement_id, user_id) VALUES (?,?)");
            ps.setInt(1, announcementId); ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] markAnnouncementRead: " + e.getMessage());
        }
        return false;
    }
}
