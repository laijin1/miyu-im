package com.familychat.server.admin;

import com.familychat.server.model.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端数据访问：管理员账号表 {@code admin_users} + 审计日志表 {@code admin_audit}。
 *
 * <p><b>为什么独立建表而不是复用 users：</b>硬约束要求管理接口绝不接受 App 的 JWT。
 * 只要管理员身份存在另一张表、走另一套 Cookie 会话，App 侧的 JWT 就天然无法映射成
 * 管理员 —— 隔离是结构性的，不依赖某处 if 判断写对了。
 */
public final class AdminDao {

    private final Database db;

    public AdminDao(Database db) {
        this.db = db;
    }

    // ═══════════════════════════ 建表 ═══════════════════════════

    /**
     * 幂等建表。由 {@code Database.init()} 调用，保证任何建库路径（服务启动 / 测试 /
     * 运维工具）都得到同一份结构，不会出现"测试库有、线上库没有"的分叉。
     */
    public void createTables() {
        try (Connection c = db.getConn(); Statement s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS admin_users (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "username TEXT NOT NULL UNIQUE, " +
                            "password_hash TEXT NOT NULL, " +          // BCrypt cost=10
                            "created_at INTEGER NOT NULL DEFAULT 0, " + // epoch millis
                            "last_login_at INTEGER NOT NULL DEFAULT 0, " +
                            "must_change_pw INTEGER NOT NULL DEFAULT 0, " + // 1=仍是初始密码
                            "disabled INTEGER NOT NULL DEFAULT 0, " +
                            "session_epoch INTEGER NOT NULL DEFAULT 0)"); // 会话代次，改密/禁用时+1
            // 老库幂等迁移：新库上面已带该列，ALTER 会因"列已存在"抛 SQLException，吞掉即可。
            // 放在 createTables() 而不是 Database.init()：admin_users 的建表与它的迁移应当同源，
            // 任何调用本方法的建库路径（服务启动 / 测试 / 运维工具）都不会出现"表有了列没有"。
            try {
                s.executeUpdate("ALTER TABLE admin_users ADD COLUMN session_epoch INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS admin_audit (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "admin_id INTEGER NOT NULL DEFAULT 0, " +   // 0=系统/启动期动作
                            "admin_name TEXT NOT NULL DEFAULT '', " +   // 冗余存名字，账号删了也能追溯
                            "action TEXT NOT NULL, " +
                            "target TEXT NOT NULL DEFAULT '', " +
                            "detail TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL DEFAULT 0, " +
                            "ip TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admin_audit_time " +
                    "ON admin_audit(created_at DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admin_audit_action " +
                    "ON admin_audit(action, created_at DESC)");
        } catch (SQLException e) {
            System.err.println("[ADMIN] createTables 失败: " + AdminSupport.detail(e));
        }
    }

    // ═══════════════════════════ 管理员账号 ═══════════════════════════

    /** 管理员账号总数（>0 表示已初始化过，不再生成初始密码）。 */
    public long countAdmins() {
        try (Connection c = db.getConn()) {
            return AdminSupport.scalar(c, "SELECT COUNT(*) FROM admin_users");
        } catch (SQLException e) {
            System.err.println("[ADMIN] countAdmins 失败: " + AdminSupport.detail(e));
            return 0L;
        }
    }

    /** 建管理员。返回新 id；用户名已存在返回 -1，其他失败返回 0。 */
    public long createAdmin(String username, String passwordHash, boolean mustChangePw) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_users (username,password_hash,created_at,must_change_pw) " +
                             "VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, mustChangePw ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("UNIQUE")) return -1L;
            System.err.println("[ADMIN] createAdmin 失败: " + AdminSupport.detail(e));
            return 0L;
        }
    }

    /** 按用户名取管理员（含 password_hash，供登录校验）。找不到返回 null。 */
    public Map<String, Object> findByName(String username) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM admin_users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] findByName 失败: " + AdminSupport.detail(e));
            return null;
        }
    }

    /** 按 id 取管理员（不含密码哈希，供会话校验）。 */
    public Map<String, Object> findById(long id) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,username,created_at,last_login_at,must_change_pw,disabled " +
                             "FROM admin_users WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] findById 失败: " + AdminSupport.detail(e));
            return null;
        }
    }

    public void touchLogin(long adminId) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE admin_users SET last_login_at=? WHERE id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, adminId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ADMIN] touchLogin 失败: " + AdminSupport.detail(e));
        }
    }

    /**
     * 读会话代次。查不到该管理员、或读库失败，一律返回 {@code -1}（fail-closed）。
     *
     * <p>为什么失败也要返回 -1 而不是 0：{@code -1} 在调用方被当作"会话无效"，
     * 宁可让管理员重新登录一次，也不能因为一次读库抖动就放行一个可能已作废的会话。
     */
    public long getSessionEpoch(long adminId) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT session_epoch FROM admin_users WHERE id=?")) {
            ps.setLong(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] getSessionEpoch 失败: " + AdminSupport.detail(e));
            return -1L;
        }
    }

    /**
     * 会话代次 +1：使该管理员此前签发的所有会话立即作废（改密 / 禁用 / 删号时调用）。
     *
     * <p>返回 {@code true} 表示确实更新了行；id 不存在或写库失败返回 {@code false}。
     */
    public boolean bumpSessionEpoch(long adminId) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE admin_users SET session_epoch=session_epoch+1 WHERE id=?")) {
            ps.setLong(1, adminId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ADMIN] bumpSessionEpoch 失败: " + AdminSupport.detail(e));
            return false;
        }
    }

    /**
     * 改用户名的三态结果。
     *
     * <p>刻意不用 {@code boolean}：{@link #DUPLICATE} 是<b>用户输入问题</b>（该报 400 并提示
     * 换一个名字），{@link #FAILED} 是<b>服务端故障</b>（该报 500）。把两者压成一个
     * {@code false}，管理员会看到"用户名已存在"而实际原因是磁盘写满，排查方向直接跑偏。
     */
    public enum RenameResult {
        /** 改名成功 */
        OK,
        /** 新用户名已被占用（触到 username 上的 UNIQUE 约束） */
        DUPLICATE,
        /** 其它写库失败（连接/磁盘/权限），或目标 id 不存在 */
        FAILED
    }

    /** 改密：写新哈希并清掉 must_change_pw。 */
    public boolean updatePassword(long adminId, String passwordHash) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE admin_users SET password_hash=?, must_change_pw=0 WHERE id=?")) {
            ps.setString(1, passwordHash);
            ps.setLong(2, adminId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ADMIN] updatePassword 失败: " + AdminSupport.detail(e));
            return false;
        }
    }

    /**
     * 运维重置口令：写新哈希并<b>置上</b> must_change_pw。
     *
     * <p>与 {@link #updatePassword} 只差这一个标记位，方向却相反：那边是管理员自己改密
     * （改完就不该再被催），这边是运维替他重置（他并不记得这串临时口令，必须下次登录就换掉）。
     * 标记位同时还是 {@code data/admin_initial_password.txt} 的存活条件 —— 见
     * {@code AdminAuth.noteExistingAdmin}：没有 must_change_pw=1 的账号时会删掉那份明文，
     * 若这里不置 1，运维刚抄下来的口令会随着下次重启"看起来过期"。
     */
    public boolean resetPassword(long adminId, String passwordHash) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE admin_users SET password_hash=?, must_change_pw=1 WHERE id=?")) {
            ps.setString(1, passwordHash);
            ps.setLong(2, adminId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ADMIN] resetPassword 失败: " + AdminSupport.detail(e));
            return false;
        }
    }

    /**
     * 按 id 取管理员，<b>含 password_hash</b>，专供"改密/改名的旧密码校验"使用。
     *
     * <p>为什么不直接改 {@link #findById}：那个方法刻意不含哈希，是为了让页面渲染、
     * 列表、日志这些宽场景<i>即使被顺手复用也拿不到口令哈希</i>。这里另开一个名字里
     * 就写着 {@code WithHash} 的方法，使"本次调用会读到口令哈希"在调用点上显式可见。
     */
    public Map<String, Object> findByIdWithHash(long adminId) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM admin_users WHERE id=?")) {
            ps.setLong(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] findByIdWithHash 失败: " + AdminSupport.detail(e));
            return null;
        }
    }

    /**
     * 改管理员登录名。UNIQUE 冲突与其它失败必须能被调用方区分（见 {@link RenameResult}）。
     */
    public RenameResult updateUsername(long adminId, String newUsername) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE admin_users SET username=? WHERE id=?")) {
            ps.setString(1, newUsername);
            ps.setLong(2, adminId);
            // update 影响 0 行 = 目标 id 不存在，同样是"没改成"，归入 FAILED
            return ps.executeUpdate() > 0 ? RenameResult.OK : RenameResult.FAILED;
        } catch (SQLException e) {
            // SQLite 的重名报错形如 "UNIQUE constraint failed: admin_users.username"，
            // 判定手法与 createAdmin 保持一致
            if (String.valueOf(e.getMessage()).contains("UNIQUE")) return RenameResult.DUPLICATE;
            System.err.println("[ADMIN] updateUsername 失败: " + AdminSupport.detail(e));
            return RenameResult.FAILED;
        }
    }

    /** 所有管理员（列表页用，永不返回 password_hash）。 */
    public List<Map<String, Object>> listAdmins() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,username,created_at,last_login_at,must_change_pw,disabled " +
                             "FROM admin_users ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(row(rs));
        } catch (SQLException e) {
            System.err.println("[ADMIN] listAdmins 失败: " + AdminSupport.detail(e));
        }
        return out;
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            m.put(md.getColumnName(i).toLowerCase(), rs.getObject(i));
        }
        return m;
    }

    // ═══════════════════════════ 审计日志 ═══════════════════════════

    /**
     * 写一条审计。
     *
     * <p>调用约定：<b>先写审计、再执行动作</b>。这样即使动作抛异常导致进程中断，
     * 日志里也留下了"有人尝试过"，而不是动作生效了却查不到是谁干的。
     *
     * <p>本方法<b>不抛异常</b>：审计写失败不应该让业务动作半途而废，但会打 ERROR
     * 日志（在运维视角里这属于必须人工介入的异常）。
     */
    public void writeAudit(long adminId, String adminName, String action, String target,
                           String detail, String ip) {
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_audit " +
                             "(admin_id,admin_name,action,target,detail,created_at,ip) " +
                             "VALUES (?,?,?,?,?,?,?)")) {
            ps.setLong(1, adminId);
            ps.setString(2, adminName != null ? adminName : "");
            ps.setString(3, action);
            ps.setString(4, target != null ? target : "");
            ps.setString(5, detail != null ? detail : "");
            ps.setLong(6, System.currentTimeMillis());
            ps.setString(7, ip != null ? ip : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ADMIN][AUDIT-FAIL] action=" + action + " target=" + target
                    + " -> " + AdminSupport.detail(e));
        }
    }

    /** 分页查审计（新→旧），可按 action / 关键字筛选。 */
    public List<Map<String, Object>> listAudit(String action, String keyword, int page, int pageSize) {
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM admin_audit WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (action != null && !action.isEmpty()) {
            sql.append(" AND action=?");
            params.add(action);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (target LIKE ? OR detail LIKE ? OR admin_name LIKE ?)");
            String like = "%" + keyword + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(Math.max(0, page - 1) * pageSize);

        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] listAudit 失败: " + AdminSupport.detail(e));
        }
        return out;
    }

    /** 审计总条数（与 listAudit 同一套筛选条件，供分页用）。 */
    public long countAudit(String action, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM admin_audit WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (action != null && !action.isEmpty()) {
            sql.append(" AND action=?");
            params.add(action);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (target LIKE ? OR detail LIKE ? OR admin_name LIKE ?)");
            String like = "%" + keyword + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            System.err.println("[ADMIN] countAudit 失败: " + AdminSupport.detail(e));
            return 0L;
        }
    }

    /** 审计里出现过的 action 列表（前端筛选下拉用，只列实际有数据的）。 */
    public List<String> distinctActions() {
        List<String> out = new ArrayList<>();
        try (Connection c = db.getConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT action FROM admin_audit ORDER BY action");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            System.err.println("[ADMIN] distinctActions 失败: " + AdminSupport.detail(e));
        }
        return out;
    }
}
