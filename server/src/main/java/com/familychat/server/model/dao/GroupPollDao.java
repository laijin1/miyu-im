package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 群投票领域 DAO（原 Database.java 群投票方法原样搬移，仅改连接获取方式
 * 与共享助手调用路径，不改变任何逻辑）。
 */
public class GroupPollDao {
    private final Database db;

    public GroupPollDao(Database db) {
        this.db = db;
    }

    /** 创建投票，返回 pollId（0=失败）。endAt 毫秒时间戳，<=0 表示不限时。 */
    public int createGroupPoll(int groupId, int creatorId, String title, String description,
                               boolean isMulti, boolean anonymous, long endAt, List<String> options) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            int pollId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_polls (group_id,creator_id,title,description,is_multi,anonymous,end_at) " +
                            "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, groupId); ps.setInt(2, creatorId);
                ps.setString(3, title);
                ps.setString(4, description == null ? "" : description);
                ps.setInt(5, isMulti ? 1 : 0);
                ps.setInt(6, anonymous ? 1 : 0);
                if (endAt > 0) {
                    ps.setString(7, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new java.util.Date(endAt)));
                } else {
                    ps.setNull(7, java.sql.Types.VARCHAR);
                }
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (!rs.next()) { c.rollback(); return 0; }
                pollId = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_poll_options (poll_id,option_text,ordinal) VALUES (?,?,?)")) {
                int ord = 0;
                for (String opt : options) {
                    if (opt == null || opt.trim().isEmpty()) continue;
                    ps.setInt(1, pollId);
                    ps.setString(2, opt.trim());
                    ps.setInt(3, ord++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            return pollId;
        } catch (SQLException e) {
            System.err.println("[ERROR] createGroupPoll: " + e.getMessage());
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
            return 0;
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    /** 群投票列表（每项带选项计数 + my_voted + total_voters + creator_name）。 */
    public List<Map<String, Object>> getGroupPolls(int groupId, int myUserId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM group_polls WHERE group_id=? ORDER BY created_at DESC, id DESC";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> p = DbUtil.rowToMap(rs);
                int pollId = rs.getInt("id");
                p.put("creator_name", DbUtil.displayName(db, rs.getInt("creator_id")));
                p.put("options", getPollOptions(pollId, myUserId));
                p.put("total_voters", countPollVoters(pollId));
                result.add(p);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupPolls: " + e.getMessage());
        }
        return result;
    }

    /** 单条投票（含选项计数 + my_voted）。 */
    public Map<String, Object> getGroupPoll(int pollId, int myUserId) {
        String sql = "SELECT * FROM group_polls WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pollId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> p = DbUtil.rowToMap(rs);
                p.put("creator_name", DbUtil.displayName(db, rs.getInt("creator_id")));
                p.put("options", getPollOptions(pollId, myUserId));
                p.put("total_voters", countPollVoters(pollId));
                return p;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupPoll: " + e.getMessage());
        }
        return null;
    }

    private List<Map<String, Object>> getPollOptions(int pollId, int myUserId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT o.id, o.option_text, o.ordinal, " +
                "(SELECT COUNT(*) FROM group_poll_votes v WHERE v.option_id=o.id) AS vote_count, " +
                "(SELECT COUNT(*) FROM group_poll_votes v WHERE v.option_id=o.id AND v.user_id=?) AS my_voted " +
                "FROM group_poll_options o WHERE o.poll_id=? ORDER BY o.ordinal, o.id";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, myUserId);
            ps.setInt(2, pollId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", rs.getInt("id"));
                o.put("option_text", rs.getString("option_text"));
                o.put("ordinal", rs.getInt("ordinal"));
                o.put("vote_count", rs.getInt("vote_count"));
                o.put("my_voted", rs.getInt("my_voted") > 0);
                list.add(o);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getPollOptions: " + e.getMessage());
        }
        return list;
    }

    /** 已投人数（去重用户）。 */
    private int countPollVoters(int pollId) {
        String sql = "SELECT COUNT(DISTINCT user_id) AS n FROM group_poll_votes WHERE poll_id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pollId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("n") : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 投票/改票：先删该用户旧票再插新票（事务）。optionIds 已由 handler 校验归属与单多选约束。 */
    public boolean votePoll(int pollId, int userId, List<Integer> optionIds) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_poll_votes WHERE poll_id=? AND user_id=?")) {
                ps.setInt(1, pollId); ps.setInt(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_poll_votes (poll_id,option_id,user_id) VALUES (?,?,?)")) {
                for (int oid : optionIds) {
                    ps.setInt(1, pollId); ps.setInt(2, oid); ps.setInt(3, userId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] votePoll: " + e.getMessage());
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
            return false;
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    /** 关闭投票（status -> closed）。 */
    public boolean closePoll(int pollId) {
        String sql = "UPDATE group_polls SET status='closed' WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pollId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] closePoll: " + e.getMessage());
            return false;
        }
    }

    /** 删除投票（级联：票 -> 选项 -> 投票）。 */
    public boolean deletePoll(int pollId) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_poll_votes WHERE poll_id=?")) {
                ps.setInt(1, pollId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_poll_options WHERE poll_id=?")) {
                ps.setInt(1, pollId); ps.executeUpdate();
            }
            boolean ok;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_polls WHERE id=?")) {
                ps.setInt(1, pollId);
                ok = ps.executeUpdate() > 0;
            }
            c.commit();
            return ok;
        } catch (SQLException e) {
            System.err.println("[ERROR] deletePoll: " + e.getMessage());
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
            return false;
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }
}
