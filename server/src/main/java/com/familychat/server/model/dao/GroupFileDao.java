package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;

import java.sql.*;
import java.util.*;

/**
 * 群内搜索 + 群文件库 + 群相册 DAO（原 Database.java 对应方法原样搬移，
 * 仅改连接获取方式与共享助手调用路径，不改变任何逻辑）。
 */
public class GroupFileDao {
    private final Database db;

    public GroupFileDao(Database db) {
        this.db = db;
    }

    // ===== 群内搜索 =====

    /** 群内搜索：只搜明文字段（文件名/系统消息），E2EE 密文不可服务端搜索。 */
    public List<Map<String, Object>> searchGroupMessages(int groupId, String keyword, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM group_messages WHERE group_id=? AND (filename LIKE ? OR " +
                            "(msg_type='system' AND content LIKE ?)) ORDER BY id DESC LIMIT ?");
            String like = "%" + keyword + "%";
            ps.setInt(1, groupId); ps.setString(2, like); ps.setString(3, like); ps.setInt(4, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
            Collections.reverse(list);
        } catch (SQLException e) {
            System.err.println("[ERROR] searchGroupMessages: " + e.getMessage());
        }
        return list;
    }

    // ═══════════════ 群文件库（阶段4，明文 URL，仅群成员可看）═══════════════

    /** 索引进群文件库，返回新文件 id（失败 0）。聊天发文件/图片/视频/语音时调用。 */
    public int indexGroupFile(int groupId, int uploaderId, String filename, String fileUrl,
                              String thumbnail, long sizeBytes, String folder, String msgType, int fileMsgId) {
        String sql = "INSERT INTO group_files (group_id,uploader_id,filename,file_url,thumbnail,size_bytes,folder,msg_type,file_msg_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, groupId); ps.setInt(2, uploaderId);
            ps.setString(3, filename); ps.setString(4, fileUrl);
            ps.setString(5, thumbnail == null ? "" : thumbnail); ps.setLong(6, sizeBytes);
            ps.setString(7, folder == null || folder.isEmpty() ? "群文件" : folder);
            ps.setString(8, msgType == null || msgType.isEmpty() ? "file" : msgType);
            ps.setInt(9, fileMsgId);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] indexGroupFile: " + e.getMessage());
            return 0;
        }
    }

    /** 文件列表。folder="全部" 或空 = 不筛选；page 从 1 起。 */
    public List<Map<String, Object>> getGroupFiles(int groupId, String folder, int page, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        int off = Math.max(0, (page - 1)) * limit;
        String sql = (folder == null || folder.isEmpty() || "全部".equals(folder))
                ? "SELECT * FROM group_files WHERE group_id=? ORDER BY id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM group_files WHERE group_id=? AND folder=? ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            if (folder != null && !folder.isEmpty() && !"全部".equals(folder)) ps.setString(2, folder);
            ps.setInt(folder != null && !folder.isEmpty() && !"全部".equals(folder) ? 3 : 2, limit);
            ps.setInt(folder != null && !folder.isEmpty() && !"全部".equals(folder) ? 4 : 3, off);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                m.put("uploader_name", DbUtil.displayName(db, rs.getInt("uploader_id")));
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupFiles: " + e.getMessage());
        }
        return list;
    }

    /** 文件夹分类计数（"全部" 总条数 + 各 folder 计数），chips 展示用。 */
    public List<Map<String, Object>> getGroupFileFolders(int groupId) {
        List<Map<String, Object>> list = new ArrayList<>();
        int total = 0;
        String sql = "SELECT folder, COUNT(*) AS cnt FROM group_files WHERE group_id=? GROUP BY folder ORDER BY cnt DESC";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("folder", rs.getString("folder"));
                m.put("count", rs.getInt("cnt"));
                total += rs.getInt("cnt");
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupFileFolders: " + e.getMessage());
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("folder", "全部");
        all.put("count", total);
        list.add(0, all);
        return list;
    }

    public Map<String, Object> getGroupFile(int fileId) {
        String sql = "SELECT * FROM group_files WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                m.put("uploader_name", DbUtil.displayName(db, rs.getInt("uploader_id")));
                return m;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupFile: " + e.getMessage());
        }
        return null;
    }

    /**
     * 按文件 URL 查归属群：若该 URL 属于群文件库 / 群相册 / 群消息附件，返回其 group_id；
     * 不属于任何群内容返回 null（即为普通单聊文件）。
     * 用于 /chat_files 下载时校验调用者是否为该群成员，防越权下载群资料。
     */
    public Integer findGroupIdByFileUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String sql = "SELECT group_id FROM group_files WHERE file_url=? " +
                "UNION SELECT group_id FROM group_album_photos WHERE photo_url=? OR thumbnail=? " +
                "UNION SELECT group_id FROM group_messages WHERE filename=? OR thumbnail=? LIMIT 1";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setString(2, url);
            ps.setString(3, url);
            ps.setString(4, url);
            ps.setString(5, url);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[ERROR] findGroupIdByFileUrl: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteGroupFile(int fileId) {
        String sql = "DELETE FROM group_files WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fileId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteGroupFile: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════ 群相册（阶段4）═══════════════

    public int createGroupAlbum(int groupId, String name, String coverUrl, int creatorId) {
        String sql = "INSERT INTO group_albums (group_id,name,cover_url,created_by) VALUES (?,?,?,?)";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, groupId);
            ps.setString(2, name == null || name.isEmpty() ? "默认相册" : name);
            ps.setString(3, coverUrl == null ? "" : coverUrl);
            ps.setInt(4, creatorId);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] createGroupAlbum: " + e.getMessage());
            return 0;
        }
    }

    /** 相册列表：带 photo_count 与封面（最新一张照片，否则用相册 cover_url）。 */
    public List<Map<String, Object>> getGroupAlbums(int groupId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT a.*, " +
                "(SELECT COUNT(*) FROM group_album_photos p WHERE p.album_id=a.id) AS photo_count, " +
                "(SELECT p.photo_url FROM group_album_photos p WHERE p.album_id=a.id ORDER BY p.id DESC LIMIT 1) AS last_photo " +
                "FROM group_albums a WHERE a.group_id=? ORDER BY a.id DESC";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                String last = rs.getString("last_photo");
                if (last != null && !last.isEmpty()) m.put("cover_url", last);
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAlbums: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> getGroupAlbum(int albumId) {
        String sql = "SELECT * FROM group_albums WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, albumId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAlbum: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> getGroupAlbumPhotos(int albumId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM group_album_photos WHERE album_id=? ORDER BY id DESC";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, albumId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = DbUtil.rowToMap(rs);
                m.put("uploader_name", DbUtil.displayName(db, rs.getInt("uploader_id")));
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAlbumPhotos: " + e.getMessage());
        }
        return list;
    }

    public int addAlbumPhoto(int albumId, int groupId, int uploaderId, String photoUrl, String thumbnail) {
        String sql = "INSERT INTO group_album_photos (album_id,group_id,uploader_id,photo_url,thumbnail) VALUES (?,?,?,?,?)";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, albumId); ps.setInt(2, groupId); ps.setInt(3, uploaderId);
            ps.setString(4, photoUrl);
            ps.setString(5, thumbnail == null ? "" : thumbnail);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] addAlbumPhoto: " + e.getMessage());
            return 0;
        }
    }

    public Map<String, Object> getGroupAlbumPhoto(int photoId) {
        String sql = "SELECT * FROM group_album_photos WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, photoId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getGroupAlbumPhoto: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteGroupAlbum(int albumId) {
        try (Connection c = db.getConn()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_album_photos WHERE album_id=?")) {
                ps.setInt(1, albumId); ps.executeUpdate();
            }
            boolean ok;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_albums WHERE id=?")) {
                ps.setInt(1, albumId);
                ok = ps.executeUpdate() > 0;
            }
            c.commit();
            return ok;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteGroupAlbum: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteAlbumPhoto(int photoId) {
        String sql = "DELETE FROM group_album_photos WHERE id=?";
        try (Connection c = db.getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, photoId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] deleteAlbumPhoto: " + e.getMessage());
            return false;
        }
    }
}
