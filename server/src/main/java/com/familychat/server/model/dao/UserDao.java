package com.familychat.server.model.dao;

import com.familychat.server.model.Database;
import com.familychat.server.model.DbUtil;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.*;
import java.util.*;

/**
 * 用户领域 DAO（原 Database.java User / Public Keys / Key Backups / updateUser / setOnline
 * 方法原样搬移，仅改连接获取方式与共享助手调用路径，不改变任何逻辑）。
 */
public class UserDao {
    private final Database db;

    // Whitelist of allowed field names for updateUser to prevent SQL injection
    private static final Set<String> ALLOWED_UPDATE_FIELDS = Set.of("nickname", "avatar", "avatar_color");

    public UserDao(Database db) {
        this.db = db;
    }

    /**
     * Register a new user with bcrypt-hashed password.
     * Uses PreparedStatement with RETURN_GENERATED_KEYS instead of last_insert_rowid().
     */
    public Map<String, Object> registerUser(String username, String password, String nickname) {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (username, password, nickname) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, nickname);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int id = rs.getInt(1);
                // wx_id 无法在同一 INSERT 引用自增 id（SQLite 限制），先 INSERT 再回填
                String wxId = String.format("my_%06d", id);
                try (PreparedStatement up = c.prepareStatement("UPDATE users SET wx_id=? WHERE id=?")) {
                    up.setString(1, wxId);
                    up.setInt(2, id);
                    up.executeUpdate();
                }
                return getUserById(id);
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE")) return null;
            System.err.println("[ERROR] register: " + e.getMessage());
        }
        return null;
    }

    /**
     * Login user with bcrypt password verification.
     * Supports seamless migration from SHA-256 to bcrypt:
     * - First tries bcrypt (for users registered after the migration)
     * - Falls back to SHA-256 if the stored hash looks like a legacy SHA-256 hash
     * - On successful SHA-256 login, re-hashes password with bcrypt and updates DB
     */
    public Map<String, Object> loginUser(String username, String password) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM users WHERE username=?");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password");
                int userId = rs.getInt("id");
                // 管理端禁用：直接拒绝登录。禁用状态就是"此账号不得建立新会话"，
                // 与 JWT 的 token_version 吊销互补（后者管已签发的旧令牌）。
                if (rs.getInt("is_disabled") == 1) {
                    System.out.println("[AUTH] 已禁用账号登录被拒: " + username);
                    return null;
                }
                boolean passwordOk = false;

                if (storedHash != null && storedHash.startsWith("$2")) {
                    // bcrypt hash format — verify directly
                    passwordOk = BCrypt.checkpw(password, storedHash);
                } else if (storedHash != null && storedHash.matches("[0-9a-f]{64}")) {
                    // Legacy SHA-256 hash — verify and migrate to bcrypt
                    String sha256hash = DbUtil.sha256Hex(password);
                    // 恒定时间比较（见 WalletDao 同款注释）：十六进制摘要长度固定，语义等价
                    if (sha256hash != null && constantTimeEqualsHex(sha256hash, storedHash)) {
                        passwordOk = true;
                        // Migrate to bcrypt
                        String bcryptHash = BCrypt.hashpw(password, BCrypt.gensalt());
                        PreparedStatement migratePs = c.prepareStatement(
                                "UPDATE users SET password=? WHERE id=?");
                        migratePs.setString(1, bcryptHash);
                        migratePs.setInt(2, userId);
                        migratePs.executeUpdate();
                        System.out.println("[MIGRATED] User '" + username + "' password upgraded to bcrypt");
                    }
                }

                if (passwordOk) {
                    PreparedStatement updatePs = c.prepareStatement(
                            "UPDATE users SET is_online=1, last_seen=CURRENT_TIMESTAMP WHERE id=?");
                    updatePs.setInt(1, userId);
                    updatePs.executeUpdate();
                    // Re-query to return the updated is_online value
                    PreparedStatement reQuery = c.prepareStatement("SELECT * FROM users WHERE id=?");
                    reQuery.setInt(1, userId);
                    ResultSet updatedRs = reQuery.executeQuery();
                    if (updatedRs.next()) return DbUtil.rowToMap(updatedRs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] login: " + e.getMessage());
        }
        return null;
    }

    /**
     * 修改密码。返回 0=成功, 1=旧密码错误, 2=用户不存在, 3=其他错误。
     * 与 loginUser 一致支持 bcrypt 与遗留 SHA-256 校验。
     */
    public int changePassword(int userId, String oldPassword, String newPassword) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT password FROM users WHERE id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 2;
            String storedHash = rs.getString("password");
            boolean ok;
            if (storedHash != null && storedHash.startsWith("$2")) {
                ok = BCrypt.checkpw(oldPassword, storedHash);
            } else if (storedHash != null && storedHash.matches("[0-9a-f]{64}")) {
                String sha = DbUtil.sha256Hex(oldPassword);
                ok = sha != null && constantTimeEqualsHex(sha, storedHash);
            } else {
                ok = false;
            }
            if (!ok) return 1;
            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            // 改密同时自增 token_version：所有已签发 JWT 立即失效（吊销机制）
            PreparedStatement up = c.prepareStatement(
                    "UPDATE users SET password=?, token_version=token_version+1 WHERE id=?");
            up.setString(1, newHash);
            up.setInt(2, userId);
            up.executeUpdate();
            return 0;
        } catch (SQLException e) {
            System.err.println("[ERROR] changePassword: " + e.getMessage());
            return 3;
        }
    }

    /**
     * 读取用户 token_version（JWT 吊销版本号）。
     *
     * <p><b>查不到时返回 -1，不返回 0。</b>原因：{@code token_version} 的默认值就是 0
     * （新注册用户即 tv=0，见 AuthHandler 的签发逻辑），所以 0 是一个**合法版本号**。
     * 若"用户不存在 / DB 读失败"也返回 0，一个 tv=0 的旧 token 会在吊销校验
     * （{@code jwt.tv != getTokenVersion(userId)}）里被判为"版本一致"而放行 ——
     * 等于把失败伪装成合法值（审计第 6 条）。返回 -1 后，任何真实 token 都不可能
     * 与之相等，吊销校验必然拒绝。
     */
    public int getTokenVersion(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT token_version FROM users WHERE id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
            return -1;   // 用户不存在：绝不能用 0（那是合法版本号）
        } catch (SQLException e) {
            System.err.println("[ERROR] getTokenVersion: " + e.getMessage());
        }
        return -1;       // 读失败同样是"取不到"，不能伪装成版本 0
    }

    public Map<String, Object> getUserById(int id) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE id=?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return DbUtil.rowToMap(rs);
        } catch (SQLException e) {
            System.err.println("[ERROR] getById: " + e.getMessage());
        }
        return null;
    }

    /**
     * Search users with a limit to prevent unlimited enumeration.
     */
    public List<Map<String, Object>> searchUsers(String query, int currentUserId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id,username,nickname,avatar,avatar_color,is_online,wx_id FROM users " +
                            "WHERE (username LIKE ? OR nickname LIKE ? OR wx_id LIKE ?) AND id != ? LIMIT 50");
            ps.setString(1, "%" + query + "%");
            ps.setString(2, "%" + query + "%");
            ps.setString(3, "%" + query + "%");
            ps.setInt(4, currentUserId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(DbUtil.rowToMap(rs));
        } catch (SQLException e) {
            System.err.println("[ERROR] search: " + e.getMessage());
        }
        return list;
    }

    // ===== Public Keys (E2EE) =====

    public void savePublicKey(int userId, String publicKey) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO public_keys (user_id, public_key, updated_at) VALUES (?,?,CURRENT_TIMESTAMP)");
            ps.setInt(1, userId);
            ps.setString(2, publicKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] savePublicKey: " + e.getMessage());
        }
    }

    public String getPublicKey(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "SELECT public_key FROM public_keys WHERE user_id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("public_key");
        } catch (SQLException e) {
            System.err.println("[ERROR] getPublicKey: " + e.getMessage());
        }
        return null;
    }

    // ===== Key Backups (password-encrypted private key, for reinstall recovery) =====

    public void saveKeyBackup(int userId, String backupB64) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO key_backups (user_id, backup_b64, updated_at) VALUES (?,?,CURRENT_TIMESTAMP)");
            ps.setInt(1, userId);
            ps.setString(2, backupB64);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] saveKeyBackup: " + e.getMessage());
        }
    }

    public String getKeyBackup(int userId) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                "SELECT backup_b64 FROM key_backups WHERE user_id=?");
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("backup_b64");
        } catch (SQLException e) {
            System.err.println("[ERROR] getKeyBackup: " + e.getMessage());
        }
        return null;
    }

    /**
     * Update a user field with whitelisted column names to prevent SQL injection.
     */
    public void updateUser(int id, String field, String value) {
        if (!ALLOWED_UPDATE_FIELDS.contains(field)) {
            System.err.println("[ERROR] updateUser: invalid field '" + field + "'");
            return;
        }
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET " + field + " = ? WHERE id = ?");
            ps.setString(1, value);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] updateUser: " + e.getMessage());
        }
    }

    public void setOnline(int userId, boolean online) {
        try (Connection c = db.getConn()) {
            PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET is_online=?, last_seen=CURRENT_TIMESTAMP WHERE id=?");
            ps.setInt(1, online ? 1 : 0);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR] setOnline: " + e.getMessage());
        }
    }

    /**
     * 恒定时间比较两个十六进制摘要（体检报告 §7.6「非恒定时间比较」）。
     *
     * <p>为什么用它替代 {@code String.equals}：{@code equals} 在首个不同字符处就返回，
     * 比较耗时随「猜对的前缀长度」变化，理论上可被用来逐字节推断摘要。原口令哈希路径
     * （遗留 SHA-256）属于离线可爆破目标，比较环节不该再额外泄露信息。
     *
     * <p>为什么是等价替换：两侧都是固定长度的十六进制摘要（SHA-256 = 64 字符），
     * {@link java.security.MessageDigest#isEqual} 在长度不等时立即返回 false、长度相等时
     * 逐字节比较且不提前退出，语义与 {@code equals} 完全一致（只把「null 检查」留给调用方）。
     *
     * <p>bcrypt 路径（{@code BCrypt.checkpw}）不在此列：它自带恒定时间比较，无需改。
     */
    static boolean constantTimeEqualsHex(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
