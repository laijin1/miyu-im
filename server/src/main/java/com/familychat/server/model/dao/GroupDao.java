package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 群聊领域 DAO（原 Database.java Groups / 群聊权限/名片/群信息 方法原样搬移，
 * 仅改连接获取方式与共享助手调用路径，不改变任何逻辑）。
 */
public class GroupDao {
    private final Database db;

    public GroupDao(Database db) {
        this.db = db;
    }

    /**
     * 建群：创建 groups 行，把 owner 与 memberIds 全部写入 group_members。
     * 返回新群 id；失败返回 0。
     */
    public int createGroup(int ownerId, String name, List<Integer> memberIds) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false); // 事务:建群+写成员要么全部成功,要么回滚,避免孤儿群
            int groupId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO groups (name, owner_id) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name != null && !name.isEmpty() ? name : "群聊");
                ps.setInt(2, ownerId);
                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                if (!rs.next()) {
                    c.rollback();
                    return 0;
                }
                groupId = rs.getInt(1);
            }
            // 创建者 role=owner，其余成员默认 member
            try (PreparedStatement insOwner = c.prepareStatement(
                    "INSERT OR IGNORE INTO group_members (group_id, user_id, role) VALUES (?,?, 'owner')")) {
                insOwner.setInt(1, groupId);
                insOwner.setInt(2, ownerId);
                insOwner.executeUpdate();
            }
            Set<Integer> members = new LinkedHashSet<>();
            if (memberIds != null) members.addAll(memberIds);
            members.remove(ownerId);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO group_members (group_id, user_id) VALUES (?,?)")) {
                for (int uid : members) {
                    ins.setInt(1, groupId);
                    ins.setInt(2, uid);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
            return groupId;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] createGroup: " + e.getMessage());
            return 0;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public boolean isGroupMember(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM group_members WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("[ERROR] isGroupMember: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getGroupById(int groupId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT g.*, (SELECT COUNT(*) FROM group_members m WHERE m.group_id=g.id) AS member_count " +
                            "FROM groups g WHERE g.id=?");
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupById: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> getGroupMembers(int groupId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id,u.username,u.nickname,u.avatar,u.avatar_color,u.is_online,u.wx_id," +
                            "COALESCE(m.role,'member') AS role," +
                            "COALESCE(m.nickname,'') AS group_nickname," +
                            "COALESCE(m.mute_until,0) AS mute_until " +
                            "FROM group_members m JOIN users u ON m.user_id=u.id WHERE m.group_id=?");
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMembers: " + e.getMessage());
        }
        return list;
    }

    public boolean addGroupMember(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO group_members (group_id, user_id) VALUES (?,?)");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] addGroupMember: " + e.getMessage());
            return false;
        }
    }

    public boolean removeGroupMember(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_members WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] removeGroupMember: " + e.getMessage());
            return false;
        }
    }

    /** 我加入的群列表：群信息 + 成员数 + 最后一条消息 + 我的未读数。 */
    public List<Map<String, Object>> getMyGroups(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT g.id,g.name,g.avatar,g.owner_id,g.created_at,g.latest_announcement_id, " +
                            "(SELECT COUNT(*) FROM group_members m WHERE m.group_id=g.id) AS member_count, " +
                            "(SELECT COUNT(*) FROM group_messages gm WHERE gm.group_id=g.id) AS msg_count, " +
                            "(SELECT COUNT(*) FROM group_messages gm " +
                            "   WHERE gm.group_id=g.id AND gm.sender_id!=? AND gm.id NOT IN " +
                            "   (SELECT message_id FROM group_message_reads r WHERE r.user_id=?)) AS unread " +
                            "FROM group_members me JOIN groups g ON me.group_id=g.id WHERE me.user_id=? " +
                            "ORDER BY (SELECT MAX(gm.id) FROM group_messages gm WHERE gm.group_id=g.id) DESC, g.id DESC");
            ps.setInt(1, userId); ps.setInt(2, userId); ps.setInt(3, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                int gid = ((Number) m.get("id")).intValue();
                // 最后一条消息预览（JSON content 客户端会解密，服务端原样返回）
                try (PreparedStatement last = c.prepareStatement(
                        "SELECT id,sender_id,content,msg_type,filename,thumbnail,created_at " +
                                "FROM group_messages WHERE group_id=? ORDER BY id DESC LIMIT 1")) {
                    last.setInt(1, gid);
                    ResultSet lr = last.executeQuery();
                    if (lr.next()) {
                        m.put("last_message_id", lr.getInt("id"));
                        m.put("last_sender_id", lr.getInt("sender_id"));
                        m.put("last_content", lr.getString("content"));
                        m.put("last_msg_type", lr.getString("msg_type"));
                        m.put("last_filename", lr.getString("filename"));
                        m.put("last_thumbnail", lr.getString("thumbnail"));
                        m.put("last_created_at", lr.getString("created_at"));
                    }
                }
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getMyGroups: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> saveGroupMessage(int groupId, int senderId, String content,
                                                String msgType, String filename, String thumbnail,
                                                int durationSec) {
        return saveGroupMessage(groupId, senderId, content, msgType, filename, thumbnail, durationSec, "");
    }

    /** 发群消息（含 @提及 mentions："[uid,...]" JSON 或 "all"）。 */
    public Map<String, Object> saveGroupMessage(int groupId, int senderId, String content,
                                                String msgType, String filename, String thumbnail,
                                                int durationSec, String mentions) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_messages (group_id,sender_id,content,msg_type,filename,thumbnail,duration_sec,mentions) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, groupId); ps.setInt(2, senderId);
            ps.setString(3, content);
            ps.setString(4, msgType);
            ps.setString(5, filename != null ? filename : "");
            ps.setString(6, thumbnail != null ? thumbnail : "");
            ps.setInt(7, durationSec);
            ps.setString(8, mentions != null ? mentions : "");
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                PreparedStatement g = c.prepareStatement("SELECT * FROM group_messages WHERE id=?");
                g.setInt(1, rs.getInt(1));
                ResultSet gr = g.executeQuery();
                if (gr.next()) return DbUtil.rowToMap(gr);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] saveGroupMessage: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> getGroupMessages(int groupId, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM group_messages WHERE group_id=? ORDER BY created_at DESC, id DESC LIMIT ?");
            ps.setInt(1, groupId); ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
            Collections.reverse(list);
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMessages: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> getGroupMessageById(int msgId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM group_messages WHERE id=?");
            ps.setInt(1, msgId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMessageById: " + e.getMessage());
        }
        return null;
    }

    /** 撤回群消息：仅发送者可撤回，未撤回过才生效；必须属于指定群（防跨群按 msgId 撤回）。 */
    public boolean recallGroupMessage(int msgId, int userId, int groupId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_messages SET content='[已撤回]', msg_type='recalled' " +
                            "WHERE id=? AND sender_id=? AND group_id=? AND msg_type!='recalled'");
            ps.setInt(1, msgId); ps.setInt(2, userId); ps.setInt(3, groupId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] recallGroupMessage: " + e.getMessage());
            return false;
        }
    }

    /** 标记该用户已读到某条群消息（含之前所有）。返回标记数量。 */
    public int markGroupRead(int groupId, int userId, int upToMsgId) {
        try (Connection c = db.getConn()) {
            List<Integer> ids = new ArrayList<>();
            PreparedStatement sel = c.prepareStatement(
                    "SELECT id FROM group_messages WHERE group_id=? AND id<=? AND sender_id!=? " +
                            "AND id NOT IN (SELECT message_id FROM group_message_reads r WHERE r.user_id=?)");
            sel.setInt(1, groupId); sel.setInt(2, upToMsgId); sel.setInt(3, userId); sel.setInt(4, userId);
            ResultSet rs = sel.executeQuery();
            while (rs.next()) ids.add(rs.getInt(1));
            if (ids.isEmpty()) return 0;
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO group_message_reads (message_id, user_id) VALUES (?,?)")) {
                for (int id : ids) {
                    ins.setInt(1, id); ins.setInt(2, userId);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            return ids.size();
        } catch (SQLException e) {
            System.err.println("[ERROR] markGroupRead: " + e.getMessage());
            return 0;
        }
    }

    /** 该群某成员是否已读某条消息（读成员集合用）。 */
    public boolean hasGroupRead(int msgId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM group_message_reads WHERE message_id=? AND user_id=?");
            ps.setInt(1, msgId); ps.setInt(2, userId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("[ERROR] hasGroupRead: " + e.getMessage());
            return false;
        }
    }

    // ===== 群聊权限/名片/群信息（阶段1） =====

    /** 群成员角色（owner/admin/member；老库未回填时返回 member，owner 特判在 handler）。 */
    public String getGroupMemberRole(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT role FROM group_members WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String role = rs.getString("role");
                return role != null && !role.isEmpty() ? role : "member";
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMemberRole: " + e.getMessage());
        }
        return "member";
    }

    public boolean setGroupMemberRole(int groupId, int userId, String role) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_members SET role=? WHERE group_id=? AND user_id=?");
            ps.setString(1, role); ps.setInt(2, groupId); ps.setInt(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] setGroupMemberRole: " + e.getMessage());
            return false;
        }
    }

    /** 群名片（群内昵称），空串=用全局昵称。 */
    public String getGroupMemberNickname(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT nickname FROM group_members WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String n = rs.getString("nickname");
                return n != null ? n : "";
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMemberNickname: " + e.getMessage());
        }
        return "";
    }

    public boolean setGroupMemberNickname(int groupId, int userId, String nickname) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_members SET nickname=? WHERE group_id=? AND user_id=?");
            ps.setString(1, nickname != null ? nickname : "");
            ps.setInt(2, groupId); ps.setInt(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] setGroupMemberNickname: " + e.getMessage());
            return false;
        }
    }

    /** 禁言截止时间（毫秒）。0=未禁言，-1=永久禁言。 */
    public long getGroupMemberMuteUntil(int groupId, int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT mute_until FROM group_members WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("mute_until");
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupMemberMuteUntil: " + e.getMessage());
        }
        return 0;
    }

    public boolean setGroupMemberMuteUntil(int groupId, int userId, long untilMs) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_members SET mute_until=? WHERE group_id=? AND user_id=?");
            ps.setLong(1, untilMs); ps.setInt(2, groupId); ps.setInt(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] setGroupMemberMuteUntil: " + e.getMessage());
            return false;
        }
    }

    /** 改群名/群头像。传 null 表示不改该项。 */
    public boolean updateGroupInfo(int groupId, String name, String avatar) {
        try (Connection c = db.getConn()) {
            if (name != null && avatar != null) {
                PreparedStatement ps = c.prepareStatement("UPDATE groups SET name=?, avatar=? WHERE id=?");
                ps.setString(1, name); ps.setString(2, avatar); ps.setInt(3, groupId);
                return ps.executeUpdate() > 0;
            } else if (name != null) {
                PreparedStatement ps = c.prepareStatement("UPDATE groups SET name=? WHERE id=?");
                ps.setString(1, name); ps.setInt(2, groupId);
                return ps.executeUpdate() > 0;
            } else if (avatar != null) {
                PreparedStatement ps = c.prepareStatement("UPDATE groups SET avatar=? WHERE id=?");
                ps.setString(1, avatar); ps.setInt(2, groupId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] updateGroupInfo: " + e.getMessage());
        }
        return false;
    }

    /** 我的群会话设置。返回 Map{pinned, quiet}，无记录则默认 {0,0}。 */
    public java.util.Map<String, Object> getGroupSetting(int groupId, int userId) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("pinned", 0);
        m.put("quiet", 0);
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT pinned, muted FROM group_settings WHERE group_id=? AND user_id=?");
            ps.setInt(1, groupId); ps.setInt(2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                m.put("pinned", rs.getInt("pinned"));
                m.put("quiet", rs.getInt("muted"));
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupSetting: " + e.getMessage());
        }
        return m;
    }

    /** 更新群会话设置（置顶 pinned / 免打扰 quiet）。null 表示该项不变（局部更新）。 */
    public boolean setGroupSetting(int groupId, int userId, Integer pinned, Integer quiet) {
        try (Connection c = db.getConn()) {
            PreparedStatement sel = c.prepareStatement(
                    "SELECT pinned, muted FROM group_settings WHERE group_id=? AND user_id=?");
            sel.setInt(1, groupId); sel.setInt(2, userId);
            ResultSet rs = sel.executeQuery();
            if (rs.next()) {
                // 已存在：只更新传入的非 null 列，其余保留原值
                int curPinned = rs.getInt("pinned");
                int curQuiet = rs.getInt("muted");
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE group_settings SET pinned=?, muted=? WHERE group_id=? AND user_id=?");
                ps.setInt(1, pinned != null ? pinned : curPinned);
                ps.setInt(2, quiet != null ? quiet : curQuiet);
                ps.setInt(3, groupId); ps.setInt(4, userId);
                ps.executeUpdate();
            } else {
                // 首次设置：没传的项按 0
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO group_settings (group_id,user_id,pinned,muted) VALUES (?,?,?,?)");
                ps.setInt(1, groupId); ps.setInt(2, userId);
                ps.setInt(3, pinned != null ? pinned : 0);
                ps.setInt(4, quiet != null ? quiet : 0);
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            System.err.println("[ERROR] setGroupSetting: " + e.getMessage());
            return false;
        }
    }

    /** 转让群主：新主 role=owner，旧主降为 admin。事务保证原子。 */
    public boolean transferGroup(int groupId, int oldOwnerId, int newOwnerId) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE groups SET owner_id=? WHERE id=?")) {
                ps.setInt(1, newOwnerId); ps.setInt(2, groupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_members SET role='admin' WHERE group_id=? AND user_id=?")) {
                ps.setInt(1, groupId); ps.setInt(2, oldOwnerId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE group_members SET role='owner' WHERE group_id=? AND user_id=?")) {
                ps.setInt(1, groupId); ps.setInt(2, newOwnerId);
                ps.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] transferGroup: " + e.getMessage());
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }

    /** 解散群：事务级联删除全部子表数据（公告/文件/相册/投票/已读/成员/消息/设置）。 */
    public boolean dissolveGroup(int groupId) {
        Connection c = null;
        try {
            c = db.getConn();
            c.setAutoCommit(false);
            // 直接按 group_id 删除的表。
            // ⚠️ group_message_reads 不在其列：它的列是 (message_id,user_id)，根本没有 group_id，
            //    按 group_id 删会抛 "no such column" 而被下面 catch 静默吞掉 —— 结果回执全部残留，
            //    group_messages 的删除随后撞上外键约束，整个解散静默失败（调用方只看到 false）。
            //    必须显式按 message_id 子查询删。
            String[] directTables = {
                    "group_settings", "group_album_photos", "group_albums", "group_files",
                    "group_messages", "group_members"
            };
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_message_reads WHERE message_id IN " +
                            "(SELECT id FROM group_messages WHERE group_id=?)")) {
                ps.setInt(1, groupId);
                ps.executeUpdate();
            } catch (SQLException e) {
                // 这里不再静默：回执删不掉就意味着后面 group_messages 会撞外键，
                // 早一点把原因打出来，比让调用方看到一个没有上下文的 false 有用得多。
                System.err.println("[WARN] dissolveGroup 清理群消息回执失败 gid=" + groupId
                        + ": " + e.getMessage());
            }
            for (String t : directTables) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE group_id=?")) {
                    ps.setInt(1, groupId);
                    ps.executeUpdate();
                } catch (SQLException ignored) { /* 该表可能尚未创建 */ }
            }
            // 公告：先删已读回执，再删公告
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_announcement_reads WHERE announcement_id IN " +
                            "(SELECT id FROM group_announcements WHERE group_id=?)")) {
                ps.setInt(1, groupId); ps.executeUpdate();
            } catch (SQLException ignored) {}
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_announcements WHERE group_id=?")) {
                ps.setInt(1, groupId); ps.executeUpdate();
            } catch (SQLException ignored) {}
            // 投票：先删票、选项，再删投票
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_poll_votes WHERE poll_id IN (SELECT id FROM group_polls WHERE group_id=?)")) {
                ps.setInt(1, groupId); ps.executeUpdate();
            } catch (SQLException ignored) {}
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM group_poll_options WHERE poll_id IN (SELECT id FROM group_polls WHERE group_id=?)")) {
                ps.setInt(1, groupId); ps.executeUpdate();
            } catch (SQLException ignored) {}
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_polls WHERE group_id=?")) {
                ps.setInt(1, groupId); ps.executeUpdate();
            } catch (SQLException ignored) {}
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM groups WHERE id=?")) {
                ps.setInt(1, groupId);
                ps.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            if (c != null) {
                try { c.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[ERROR] dissolveGroup: " + e.getMessage());
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
