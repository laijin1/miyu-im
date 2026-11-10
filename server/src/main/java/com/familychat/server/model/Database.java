package com.familychat.server.model;

import com.familychat.server.model.dao.*;

import java.sql.*;
import java.util.*;

/**
 * 数据库入口（瘦身版）：仅保留 数据目录/路径、getConn、init（全部建表语句）、close
 * 与全部公开方法的委托。各领域实现已按职责拆入 model.dao 子包：
 * UserDao / WalletDao / FriendDao / MessageDao / GroupDao / GroupAnnouncementDao /
 * GroupFileDao / GroupPollDao / MallDao，共享助手集中在 DbUtil。
 * 现有 handler 通过 getDb().xxx() 的调用方式与返回行为完全不变。
 */
public class Database {
    public static final String DATA_DIR;

    static {
        // 测试/嵌入式场景可显式指定数据目录（gradle test 通过 familychat.data.dir 注入，
        // 指向 build/test-data，绝不触碰真实数据）；否则基于 jar 位置解析（与工作目录无关）。
        String override = System.getProperty("familychat.data.dir");
        if (override != null && !override.trim().isEmpty()) {
            DATA_DIR = override.trim();
        } else {
            String base = "server/data";
            try {
                java.net.URL jarUrl = Database.class.getProtectionDomain()
                        .getCodeSource().getLocation();
                if (jarUrl != null) {
                    java.io.File jarFile = new java.io.File(jarUrl.toURI());
                    java.io.File projectRoot = jarFile.getParentFile()
                            .getParentFile().getParentFile().getParentFile();
                    base = new java.io.File(projectRoot, "server/data").getAbsolutePath();
                }
            } catch (Exception e) {
                System.err.println("[WARN] Could not determine data directory from jar location, using: " + base);
            }
            DATA_DIR = base;
        }
    }

    private static final String DB_PATH = DATA_DIR + "/family_chat.db";

    private final UserDao userDao = new UserDao(this);
    private final WalletDao walletDao = new WalletDao(this);
    private final FriendDao friendDao = new FriendDao(this);
    private final MessageDao messageDao = new MessageDao(this);
    private final GroupDao groupDao = new GroupDao(this);
    private final GroupAnnouncementDao groupAnnouncementDao = new GroupAnnouncementDao(this);
    private final GroupFileDao groupFileDao = new GroupFileDao(this);
    private final ChatFileUploadDao chatFileUploadDao = new ChatFileUploadDao(this);
    private final GroupPollDao groupPollDao = new GroupPollDao(this);
    private final MallDao mallDao = new MallDao(this);
    private final GroupMaintenance groupMaintenance = new GroupMaintenance(this);

    public Database() {
        new java.io.File(DATA_DIR).mkdirs();
    }

    public Connection getConn() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");           // 读写并发基础
            s.execute("PRAGMA busy_timeout=5000");          // 写锁碰撞时等待 5s 而非报错
            s.execute("PRAGMA synchronous=NORMAL");         // WAL 下安全，比 FULL 快
            s.execute("PRAGMA cache_size=-4096");           // 每连接 4MB 读缓存
            s.execute("PRAGMA journal_size_limit=16777216");// WAL 文件上限 16MB
            s.execute("PRAGMA temp_store=MEMORY");          // 临时表/排序入内存
            s.execute("PRAGMA foreign_keys=ON");            // 外键约束
        }
        return c;
    }

    public void init() {
        try (Connection c = getConn()) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "username TEXT UNIQUE NOT NULL, password TEXT NOT NULL, nickname TEXT NOT NULL, " +
                                "avatar TEXT DEFAULT '😊', avatar_color TEXT DEFAULT '#e8f5e9', " +
                                "wx_id TEXT DEFAULT '', " +
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP, is_online INTEGER DEFAULT 0)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS friendships (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "user_id INTEGER NOT NULL, friend_id INTEGER NOT NULL, " +
                                "status TEXT CHECK(status IN('pending','accepted','rejected')) DEFAULT 'pending', " +
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                                "FOREIGN KEY (user_id) REFERENCES users(id), " +
                                "FOREIGN KEY (friend_id) REFERENCES users(id), UNIQUE(user_id, friend_id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "sender_id INTEGER NOT NULL, receiver_id INTEGER NOT NULL, " +
                                "content TEXT NOT NULL, msg_type TEXT DEFAULT 'text', " +
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, is_read INTEGER DEFAULT 0, " +
                                "thumbnail TEXT DEFAULT '', " +
                                "FOREIGN KEY (sender_id) REFERENCES users(id), " +
                                "FOREIGN KEY (receiver_id) REFERENCES users(id))");
            }
            // ── 单聊附件台账（纯增量，不影响任何现有表）──
            // 上传成功后服务端返回 /chat_files/<生成名>，客户端把该明文 URL 用 E2EE 加密后
            // 写进 messages.content，服务端无法再反查 → 这里在上传时刻（URL 还是明文、
            // uploaderId 刚从鉴权拿到）记一行，把「生成名 ↔ 上传者」这个唯一可靠的关联留住。
            // generated_name 就是 /chat_files/ 后面那一段；uploaded_at 为 epoch 毫秒。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS chat_file_uploads (" +
                                "generated_name TEXT PRIMARY KEY, " +
                                "uploader_id INTEGER NOT NULL, " +
                                "original_name TEXT DEFAULT '', " +
                                "size_bytes INTEGER DEFAULT 0, " +
                                "uploaded_at INTEGER DEFAULT 0, " +
                                "FOREIGN KEY (uploader_id) REFERENCES users(id))");
            }
            // ── 群聊：4 张新表（纯增量，单聊 messages 表不动）──
            // groups: 群信息；group_members: 成员；group_messages: 群消息（content 为
            // {成员id: 密文} 的 JSON，每人一份密文）；group_message_reads: 成员已读集合。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS groups (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "name TEXT NOT NULL, avatar TEXT DEFAULT '', " +
                            "owner_id INTEGER NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (owner_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_members (" +
                            "group_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                            "joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "PRIMARY KEY (group_id, user_id), " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_messages (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "group_id INTEGER NOT NULL, sender_id INTEGER NOT NULL, " +
                            "content TEXT NOT NULL, msg_type TEXT DEFAULT 'text', " +
                            "filename TEXT DEFAULT '', thumbnail TEXT DEFAULT '', " +
                            "duration_sec INTEGER DEFAULT 0, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (sender_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_message_reads (" +
                            "message_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                            "PRIMARY KEY (message_id, user_id), " +
                            "FOREIGN KEY (message_id) REFERENCES group_messages(id), " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_members_gid " +
                    "ON group_members(group_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_messages_gid " +
                    "ON group_messages(group_id, created_at)");
            }
            // ── 群聊扩展表（阶段1建表，后续阶段启用接口）──
            // 群公告：content 为 {成员id:密文} JSON，服务端只存不解；已读回执只记 uid 列表。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_announcements (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "group_id INTEGER NOT NULL, publisher_id INTEGER NOT NULL, " +
                            "content TEXT NOT NULL, pinned INTEGER DEFAULT 0, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (publisher_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_announcement_reads (" +
                            "announcement_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                            "read_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "PRIMARY KEY (announcement_id, user_id), " +
                            "FOREIGN KEY (announcement_id) REFERENCES group_announcements(id), " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            // 群文件库：二进制沿用 /chat_files 明文，仅群成员可访问。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_files (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "group_id INTEGER NOT NULL, uploader_id INTEGER NOT NULL, " +
                            "filename TEXT NOT NULL, file_url TEXT NOT NULL, " +
                            "thumbnail TEXT DEFAULT '', size_bytes INTEGER DEFAULT 0, " +
                            "folder TEXT DEFAULT '全部', msg_type TEXT DEFAULT 'file', " +
                            "file_msg_id INTEGER DEFAULT 0, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (uploader_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_albums (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "group_id INTEGER NOT NULL, name TEXT NOT NULL, " +
                            "cover_url TEXT DEFAULT '', created_by INTEGER NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (created_by) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_album_photos (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "album_id INTEGER NOT NULL, group_id INTEGER NOT NULL, " +
                            "uploader_id INTEGER NOT NULL, " +
                            "photo_url TEXT NOT NULL, thumbnail TEXT DEFAULT '', " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (album_id) REFERENCES group_albums(id), " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (uploader_id) REFERENCES users(id))");
            }
            // 群投票：明文存储，服务端统计。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_polls (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "group_id INTEGER NOT NULL, creator_id INTEGER NOT NULL, " +
                            "title TEXT NOT NULL, description TEXT DEFAULT '', " +
                            "is_multi INTEGER DEFAULT 0, anonymous INTEGER DEFAULT 0, " +
                            "status TEXT DEFAULT 'open', end_at TIMESTAMP, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (creator_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_poll_options (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "poll_id INTEGER NOT NULL, option_text TEXT NOT NULL, " +
                            "ordinal INTEGER DEFAULT 0, " +
                            "FOREIGN KEY (poll_id) REFERENCES group_polls(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_poll_votes (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "poll_id INTEGER NOT NULL, option_id INTEGER NOT NULL, " +
                            "user_id INTEGER NOT NULL, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "UNIQUE (poll_id, user_id, option_id), " +
                            "FOREIGN KEY (poll_id) REFERENCES group_polls(id), " +
                            "FOREIGN KEY (option_id) REFERENCES group_poll_options(id), " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            // 群会话设置：每成员每群置顶/免打扰。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS group_settings (" +
                            "group_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                            "pinned INTEGER DEFAULT 0, muted INTEGER DEFAULT 0, " +
                            "PRIMARY KEY (group_id, user_id), " +
                            "FOREIGN KEY (group_id) REFERENCES groups(id), " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_announcements_gid " +
                    "ON group_announcements(group_id, created_at)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_announce_reads_aid " +
                    "ON group_announcement_reads(announcement_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_files_gid " +
                    "ON group_files(group_id, created_at)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_albums_gid " +
                    "ON group_albums(group_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_album_photos_aid " +
                    "ON group_album_photos(album_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_polls_gid " +
                    "ON group_polls(group_id, created_at)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_poll_options_pid " +
                    "ON group_poll_options(poll_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_poll_votes_pid " +
                    "ON group_poll_votes(poll_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_settings_gid " +
                    "ON group_settings(group_id)");
            }
            // Add indexes for performance
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_messages_conv " +
                    "ON messages(sender_id, receiver_id, created_at)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_friendships_user " +
                    "ON friendships(user_id, friend_id)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS public_keys (" +
                            "user_id INTEGER PRIMARY KEY, " +
                            "public_key TEXT NOT NULL, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            // ── 密钥备份：私钥经用户密码派生的钥匙 AES-GCM 加密后存这里（服务端无密码解不开）──
            // 用于重装/清数据后恢复端到端加密私钥，找回历史聊天记录。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS key_backups (" +
                            "user_id INTEGER PRIMARY KEY, " +
                            "backup_b64 TEXT NOT NULL, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            // ── 支付系统（阶段1起）：钱包/余额 + 账本 ──
            // 余额单位:分(整数)。wallets 存余额与支付密码哈希；transactions 记所有资金流水
            // (充值/转账/红包/扫码)，是服务端的权威账本。支付密码只存哈希不存明文。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS wallets (" +
                            "user_id INTEGER PRIMARY KEY, " +
                            "balance INTEGER NOT NULL DEFAULT 0, " +        // 单位:分
                            "pay_hash TEXT DEFAULT '', " +                  // SHA-256(pin_hash || salt) hex
                            "pay_salt TEXT DEFAULT '', " +                  // 随机盐 hex
                            "pay_set INTEGER NOT NULL DEFAULT 0, " +        // 是否已设支付密码
                            "updated_at INTEGER DEFAULT 0, " +
                            "FOREIGN KEY (user_id) REFERENCES users(id))");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS transactions (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "from_user INTEGER NOT NULL DEFAULT 0, " +      // 0=系统(充值)
                            "to_user INTEGER NOT NULL, " +
                            "type TEXT NOT NULL, " +                        // transfer|red_packet|recharge|paycode
                            "amount INTEGER NOT NULL, " +                   // 分
                            "memo TEXT DEFAULT '', " +
                            "status TEXT NOT NULL DEFAULT 'pending', " +    // transfer: pending|accepted|refunded; 其余: completed
                            "ref_id INTEGER DEFAULT 0, " +
                            "created_at INTEGER DEFAULT 0, " +
                            "FOREIGN KEY (from_user) REFERENCES users(id), " +
                            "FOREIGN KEY (to_user) REFERENCES users(id))");
            }
            // 系统账户(id=0)：充值等系统账目 from_user=0 需满足外键约束
            try (PreparedStatement sys = c.prepareStatement(
                    "INSERT OR IGNORE INTO users (id, username, password, nickname) VALUES (0, 'system', '', '系统')")) {
                sys.executeUpdate();
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_user ON transactions(to_user, created_at)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(from_user, created_at)");
            }
            // ── 迁移：旧库补充新增列（ALTER 已存在时静默忽略）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE friendships ADD COLUMN remark TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：备注改为单向（各自只能看到自己设置的备注）──
            // remark_user   = f.user_id  给 f.friend_id 设置的备注
            // remark_friend = f.friend_id 给 f.user_id  设置的备注
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE friendships ADD COLUMN remark_user TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE friendships ADD COLUMN remark_friend TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // 旧共享 remark 一次性回填到 user 一侧，避免已有备注丢失
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE friendships SET remark_user = remark WHERE remark_user = '' AND remark != ''");
            } catch (SQLException ignored) { /* 非关键 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE messages ADD COLUMN filename TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：视频消息缩略图 URL ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE messages ADD COLUMN thumbnail TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：密语号式专属 ID（wx_id）──
            // 新库建表时已带 wx_id 列；老库补列，再对空值幂等回填（由自增 id 派生，保证唯一）。
            // 前缀统一为 my_（早期版本为 wx_，此处一次性迁移存量值）。
            // ALTER 不支持 UNIQUE 约束（SQLite 限制），唯一性由自增 id 派生保证，可接受。
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE users ADD COLUMN wx_id TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE users SET wx_id = 'my_' || substr('000000' || CAST(id AS TEXT), -6) " +
                        "WHERE wx_id LIKE 'wx_%'");
            } catch (SQLException ignored) { /* 非关键 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE users SET wx_id = 'my_' || substr('000000' || CAST(id AS TEXT), -6) " +
                        "WHERE wx_id IS NULL OR wx_id = ''");
            } catch (SQLException ignored) { /* 非关键 */ }
            // ── 迁移：JWT 吊销支持（token_version：改密时自增，旧 token 立即失效）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE users ADD COLUMN token_version INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：群消息语音时长（duration_sec）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE group_messages ADD COLUMN duration_sec INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：群聊权限体系（角色/群名片/禁言）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE group_members ADD COLUMN role TEXT DEFAULT 'member'");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE group_members ADD COLUMN nickname TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE group_members ADD COLUMN mute_until INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // 回填群主角色（幂等，老库群主行角色补为 owner）
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE group_members SET role='owner' WHERE role!='owner' AND " +
                        "user_id IN (SELECT owner_id FROM groups g WHERE g.id=group_members.group_id)");
            } catch (SQLException ignored) { /* 非关键 */ }
            // ── 迁移：群公告位（最新公告 id，横幅用）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE groups ADD COLUMN latest_announcement_id INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 迁移：群消息 @提及（mentions，[uid,...] JSON 或 "all"）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE group_messages ADD COLUMN mentions TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 商城：products 表（商品浏览，阶段1）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS products (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "name TEXT NOT NULL, category TEXT DEFAULT '生活', " +
                                "price INTEGER NOT NULL, description TEXT DEFAULT '', " +
                                "image_url TEXT DEFAULT '', stock INTEGER DEFAULT 0, " +
                                "on_sale INTEGER DEFAULT 1, sort_order INTEGER DEFAULT 0, " +
                                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_products_onsale ON products(on_sale, sort_order)");
            }
            // ── 商城：商品销量列（幂等迁移，阶段2）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE products ADD COLUMN sales INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 商城：分类表（首页金刚区宫格）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS categories (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, " +
                                "color TEXT DEFAULT '#FF5000', sort_order INTEGER DEFAULT 0)");
            }
            // ── 商城：products 加卖家归属列（幂等，阶段3 商家后台）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE products ADD COLUMN seller_id INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 商城：订单表（阶段3 淘宝式订单闭环）──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_orders (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT NOT NULL UNIQUE, " +
                                "product_id INTEGER NOT NULL, seller_id INTEGER NOT NULL, buyer_id INTEGER NOT NULL, " +
                                "quantity INTEGER NOT NULL DEFAULT 1, amount INTEGER NOT NULL, " +
                                "status TEXT NOT NULL DEFAULT 'pending', " +  // pending|paid|shipped|completed|cancelled
                                "receiver_name TEXT NOT NULL, receiver_phone TEXT NOT NULL, receiver_address TEXT NOT NULL, " +
                                "created_at INTEGER NOT NULL, paid_at INTEGER DEFAULT 0, " +
                                "shipped_at INTEGER DEFAULT 0, completed_at INTEGER DEFAULT 0)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mall_orders_buyer ON mall_orders(buyer_id, id)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mall_orders_seller ON mall_orders(seller_id, id)");
            }
            // ── 商城（阶段4完善）：订单物流/退款列（幂等迁移）──
            // status 扩展：refund_pending(买家申请退款,等待卖家处理) | refunded(已退款)
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN shipping_company TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN tracking_no TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN refund_status TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN refund_reason TEXT DEFAULT ''");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN refund_time INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN cancel_time INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            // ── 商城（阶段4完善）：购物车表 ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_cart (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, " +
                                "product_id INTEGER NOT NULL, quantity INTEGER NOT NULL DEFAULT 1, " +
                                "created_at INTEGER NOT NULL, UNIQUE(user_id, product_id))");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cart_user ON mall_cart(user_id, id)");
            }
            // ── 商城（阶段4完善）：商品收藏表 ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_favorites (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, " +
                                "product_id INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                                "UNIQUE(user_id, product_id))");
            }
            // ── 商城（阶段4完善）：收货地址簿 ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_addresses (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, " +
                                "name TEXT NOT NULL, phone TEXT NOT NULL, address TEXT NOT NULL, " +
                                "is_default INTEGER DEFAULT 0, created_at INTEGER NOT NULL)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_addr_user ON mall_addresses(user_id, id)");
            }
            // ── 商城（阶段4完善）：商品评价表 ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_reviews (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, " +
                                "order_id INTEGER NOT NULL, user_id INTEGER NOT NULL, " +
                                "rating INTEGER NOT NULL DEFAULT 5, content TEXT DEFAULT '', " +
                                "created_at INTEGER NOT NULL, UNIQUE(order_id))");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reviews_product ON mall_reviews(product_id, id)");
            }
            // ── 商城（阶段4完善）：搜索热词表 ──
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS hot_searches (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT NOT NULL UNIQUE, " +
                                "count INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)");
            }
            // 播种分类（幂等：UNIQUE(name)，INSERT OR IGNORE）
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO categories (name,color,sort_order) VALUES (?,?,?)")) {
                Object[][] cats = {
                        {"全部", "#FF5000", 0}, {"生活", "#FF9500", 1}, {"数码", "#00B7EE", 2},
                        {"家居", "#8B7DD8", 3}, {"美妆", "#FF6B9D", 4}, {"服饰", "#3D9BFF", 5},
                        {"食品", "#FF6A00", 6}, {"母婴", "#4AC9A3", 7}, {"家电", "#5B7FFF", 8},
                        {"运动", "#22C55E", 9},
                };
                for (Object[] row : cats) {
                    ins.setString(1, (String) row[0]);
                    ins.setString(2, (String) row[1]);
                    ins.setInt(3, (Integer) row[2]);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            // 商品种子：按 name 幂等（命中则回填补 sales，未命中则插入），兼容老库 6 商品补齐到 21 条
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE products SET category=?,price=?,description=?,image_url=?,sales=?,sort_order=? WHERE name=?")) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO products (name,category,price,description,image_url,sales,sort_order) VALUES (?,?,?,?,?,?,?)")) {
                    Object[][] seeds = {
                            {"保温水杯", "生活", 3990, "316 不锈钢 500ml", "", 2300, 0},
                            {"蓝牙耳机", "数码", 19900, "真无线降噪", "", 1560, 1},
                            {"充电宝", "数码", 8900, "20000mAh 快充", "", 3200, 2},
                            {"全家福相册", "生活", 5900, "硬壳精装", "", 120, 3},
                            {"智能台灯", "家居", 12900, "护眼无频闪", "", 760, 4},
                            {"便携榨汁杯", "家居", 7990, "无线随行杯", "", 540, 5},
                            {"桌面收纳盒", "生活", 2990, "多功能分层", "", 880, 6},
                            {"智能手表", "数码", 59900, "血氧心率监测", "", 430, 7},
                            {"香薰蜡烛", "家居", 3990, "大豆蜡 70g", "", 210, 8},
                            {"保湿面膜", "美妆", 8990, "玻尿酸 5片装", "", 4100, 9},
                            {"口红礼盒", "美妆", 12900, "3支装 哑光", "", 980, 10},
                            {"纯棉T恤", "服饰", 5900, "全棉 黑白灰三色", "", 6700, 11},
                            {"运动卫衣", "服饰", 15900, "加绒连帽", "", 1200, 12},
                            {"坚果礼盒", "食品", 12900, "每日坚果 30包", "", 2800, 13},
                            {"手工曲奇", "食品", 4900, "黄油曲奇 铁盒装", "", 1500, 14},
                            {"婴儿湿巾", "母婴", 3990, "80抽*5包 无酒精", "", 8900, 15},
                            {"儿童积木", "母婴", 7900, "益智拼装 200粒", "", 2300, 16},
                            {"电热水壶", "家电", 11900, "1.5L 304不锈钢", "", 640, 17},
                            {"落地风扇", "家电", 15900, "遥控静音 7叶", "", 380, 18},
                            {"瑜伽垫", "运动", 6900, "加厚防滑 10mm", "", 1700, 19},
                            {"哑铃套装", "运动", 9900, "可调节 10kg", "", 460, 20},
                    };
                    for (Object[] row : seeds) {
                        String name = (String) row[0];
                        upd.setString(1, (String) row[1]);
                        upd.setInt(2, (Integer) row[2]);
                        upd.setString(3, (String) row[3]);
                        upd.setString(4, (String) row[4]);
                        upd.setInt(5, (Integer) row[5]);
                        upd.setInt(6, (Integer) row[6]);
                        upd.setString(7, name);
                        if (upd.executeUpdate() == 0) { // 不存在才插入
                            ins.setString(1, name);
                            ins.setString(2, (String) row[1]);
                            ins.setInt(3, (Integer) row[2]);
                            ins.setString(4, (String) row[3]);
                            ins.setString(5, (String) row[4]);
                            ins.setInt(6, (Integer) row[5]);
                            ins.setInt(7, (Integer) row[6]);
                            ins.executeUpdate();
                        }
                    }
                    System.out.println("[OK] Seeded mall data: products/sales, categories");
                }
            }
            // ── 支付：收款请求表（付款码被扫 → 商家发起 → 主人确认，阶段4）──
            // from_user=商家(扫码方) to_user=付款码主人(确认方)；status: pending|accepted|declined
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS charge_requests (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, from_user INTEGER NOT NULL, " +
                                "to_user INTEGER NOT NULL, amount INTEGER NOT NULL, memo TEXT DEFAULT '', " +
                                "status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL)");
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_charge_to ON charge_requests(to_user, status, created_at)");
            }
            // ── 管理后台：账号禁用标记（幂等迁移）──
            // 禁用语义 = 不能登录、不能建 WS 连接，但数据全部保留（可逆、可审计）。
            // 之所以不做成"删除"：测试阶段误禁一个账号是可挽回的，误删不是。
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE users ADD COLUMN is_disabled INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_disabled ON users(is_disabled)");
            } catch (SQLException ignored) { /* 索引已存在 */ }
            // ── 管理后台：独立管理员体系 + 审计日志（见 admin.AdminDao）──
            // 放在 Database.init 里而不是 main()：任何建库路径（服务/测试/运维工具）
            // 都会得到同样的表结构，避免"测试库有 admin_users、线上库没有"的分叉。
            new com.familychat.server.admin.AdminDao(this).createTables();
            // ── 商城托管（审计第 4 条）：下单扣款进托管账户，确认收货才放给卖家 ──
            // 金额记账权威在 wallets(escrow 账户, user_id=0)；本表只记"哪一单被托管了多少钱"，
            // 用于逐单核对与解冻，避免用 mall_orders.amount 推总额时把历史订单也算进去。
            // 历史订单 escrow 默认 0 —— 它们按旧模型（付款即入卖家余额）已经终结，
            // 不做数据回填（一刀切：新订单走托管，老订单不动）。
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS mall_escrow (" +
                                "order_id INTEGER PRIMARY KEY, " +
                                "buyer_id INTEGER NOT NULL, " +
                                "seller_id INTEGER NOT NULL, " +
                                "amount INTEGER NOT NULL, " +           // 单位:分，托管中
                                "created_at INTEGER NOT NULL, " +
                                "FOREIGN KEY (order_id) REFERENCES mall_orders(id))");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mall_escrow_seller " +
                        "ON mall_escrow(seller_id)");
            }
            // mall_orders 补托管标记列（幂等）：escrow=1 表示这一单的钱在托管账户里
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN escrow INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE mall_orders ADD COLUMN escrow_released_at INTEGER DEFAULT 0");
            } catch (SQLException ignored) { /* 列已存在 */ }
            System.out.println("[OK] Database initialized");
        } catch (SQLException e) {
            System.err.println("[ERROR] DB init: " + e.getMessage());
        }
    }

    /**
     * Gracefully close the database: checkpoint WAL journal and release resources.
     * Call this during JVM shutdown to prevent data loss from force-kills.
     */
    public void close() {
        System.out.println("[DB] Performing WAL checkpoint...");
        try (Connection c = getConn()) {
            try (Statement s = c.createStatement()) {
                // TRUNCATE mode merges WAL into main database and removes -wal/-shm files
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            System.out.println("[DB] WAL checkpoint complete");
        } catch (SQLException e) {
            System.err.println("[DB] WAL checkpoint failed: " + e.getMessage());
        }
    }

    // ═════════════════════════ 公开方法委托（handler 调用 getDb().xxx() 完全不变）═════════════════════════

    // ===== User =====
    public Map<String, Object> registerUser(String username, String password, String nickname) {
        return userDao.registerUser(username, password, nickname);
    }

    public Map<String, Object> loginUser(String username, String password) {
        return userDao.loginUser(username, password);
    }

    public int changePassword(int userId, String oldPassword, String newPassword) {
        return userDao.changePassword(userId, oldPassword, newPassword);
    }

    public int getTokenVersion(int userId) {
        return userDao.getTokenVersion(userId);
    }

    public Map<String, Object> getUserById(int id) {
        return userDao.getUserById(id);
    }

    public List<Map<String, Object>> searchUsers(String query, int currentUserId) {
        return userDao.searchUsers(query, currentUserId);
    }

    // ===== Public Keys (E2EE) =====
    public void savePublicKey(int userId, String publicKey) {
        userDao.savePublicKey(userId, publicKey);
    }

    public String getPublicKey(int userId) {
        return userDao.getPublicKey(userId);
    }

    // ===== Key Backups (password-encrypted private key, for reinstall recovery) =====
    public void saveKeyBackup(int userId, String backupB64) {
        userDao.saveKeyBackup(userId, backupB64);
    }

    public String getKeyBackup(int userId) {
        return userDao.getKeyBackup(userId);
    }

    // ===== 支付系统（阶段1起）：钱包/余额/支付密码 =====
    public Map<String, Object> getWallet(int userId) {
        return walletDao.getWallet(userId);
    }

    public void ensureWallet(int userId) {
        walletDao.ensureWallet(userId);
    }

    public boolean setPayPassword(int userId, String pinHash) {
        return walletDao.setPayPassword(userId, pinHash);
    }

    public int verifyPayPassword(int userId, String pinHash) {
        return walletDao.verifyPayPassword(userId, pinHash);
    }

    public boolean addBalance(int userId, long amount) {
        return walletDao.addBalance(userId, amount);
    }

    /** 加余额并显式指定单次上限（充值走这条；上限在 SQL 层强制）。 */
    public boolean addBalance(int userId, long amount, long maxSingle) {
        return walletDao.addBalance(userId, amount, maxSingle);
    }

    public boolean deductBalance(int userId, long amount) {
        return walletDao.deductBalance(userId, amount);
    }

    public long addTransaction(int fromUserId, int toUserId, String type, long amount,
                               String memo, String status, int refId) {
        return walletDao.addTransaction(fromUserId, toUserId, type, amount, memo, status, refId);
    }

    public Map<String, Object> getTransaction(int txId) {
        return walletDao.getTransaction(txId);
    }

    // ===== 支付系统（阶段5）：钱包账单 =====
    public List<Map<String, Object>> getBills(int userId) {
        return walletDao.getBills(userId);
    }

    // ===== 支付系统（阶段4）：收款请求（付款码被扫 → 商家发起 → 主人确认） =====
    public Map<String, Object> getChargeRequest(int id) {
        return walletDao.getChargeRequest(id);
    }

    public long createChargeRequest(int fromUser, int toUser, long amount, String memo) {
        return walletDao.createChargeRequest(fromUser, toUser, amount, memo);
    }

    public boolean declineCharge(int id, int toUser) {
        return walletDao.declineCharge(id, toUser);
    }

    public Map<String, Object> approveCharge(int id, int toUser) {
        return walletDao.approveCharge(id, toUser);
    }

    /** 管理后台代付款人确认收款请求（不校验归属，同一资金路径）。 */
    public Map<String, Object> approveChargeAsAdmin(int id) {
        return walletDao.approveChargeAsAdmin(id);
    }

    /** 管理后台代付款人拒绝收款请求（不校验归属）。 */
    public boolean declineChargeAsAdmin(int id) {
        return walletDao.declineChargeAsAdmin(id);
    }

    // ===== 支付系统（阶段2）：转账（密语式状态机） =====
    public Map<String, Object> createTransfer(int fromUser, int toUser, long amount, String memo) {
        return walletDao.createTransfer(fromUser, toUser, amount, memo);
    }

    public Map<String, Object> directPay(int fromUser, int toUser, long amount, String memo) {
        return walletDao.directPay(fromUser, toUser, amount, memo);
    }

    public Map<String, Object> acceptTransfer(int txId, int toUser) {
        return walletDao.acceptTransfer(txId, toUser);
    }

    public Map<String, Object> refundTransfer(int txId, int fromUser) {
        return walletDao.refundTransfer(txId, fromUser);
    }

    public void updateUser(int id, String field, String value) {
        userDao.updateUser(id, field, value);
    }

    public void setOnline(int userId, boolean online) {
        userDao.setOnline(userId, online);
    }

    // ===== Friends =====
    public Map<String, Object> sendFriendRequest(int userId, int friendId) {
        return friendDao.sendFriendRequest(userId, friendId);
    }

    public boolean handleFriendRequest(int friendshipId, int friendId, String action) {
        return friendDao.handleFriendRequest(friendshipId, friendId, action);
    }

    public List<Map<String, Object>> getFriends(int userId) {
        return friendDao.getFriends(userId);
    }

    public boolean deleteFriendship(int userId, int friendId) {
        return friendDao.deleteFriendship(userId, friendId);
    }

    public boolean areFriends(int userId1, int userId2) {
        return friendDao.areFriends(userId1, userId2);
    }

    public boolean setFriendRemark(int userId, int friendId, String remark) {
        return friendDao.setFriendRemark(userId, friendId, remark);
    }

    public List<Map<String, Object>> getPendingRequests(int userId) {
        return friendDao.getPendingRequests(userId);
    }

    public List<Map<String, Object>> getSentRequests(int userId) {
        return friendDao.getSentRequests(userId);
    }

    public Map<String, Object> getFriendById(int friendId) {
        return friendDao.getFriendById(friendId);
    }

    // ===== Messages =====
    public Map<String, Object> saveMessage(int senderId, int receiverId, String content, String msgType) {
        return messageDao.saveMessage(senderId, receiverId, content, msgType);
    }

    public Map<String, Object> saveMessageWithFilename(int senderId, int receiverId, String content, String msgType, String filename) {
        return messageDao.saveMessageWithFilename(senderId, receiverId, content, msgType, filename);
    }

    public Map<String, Object> saveMessageWithThumbnail(int senderId, int receiverId, String content, String msgType, String filename, String thumbnail) {
        return messageDao.saveMessageWithThumbnail(senderId, receiverId, content, msgType, filename, thumbnail);
    }

    public List<Map<String, Object>> getMessages(int userId, int friendId, int limit) {
        return messageDao.getMessages(userId, friendId, limit);
    }

    public List<Map<String, Object>> searchMessages(int userId, int friendId, String keyword, int limit, int offset) {
        return messageDao.searchMessages(userId, friendId, keyword, limit, offset);
    }

    public Map<String, Object> getMessageById(int msgId) {
        return messageDao.getMessageById(msgId);
    }

    public boolean deleteMessage(int msgId, int userId) {
        return messageDao.deleteMessage(msgId, userId);
    }

    public boolean recallMessage(int msgId, int userId) {
        return messageDao.recallMessage(msgId, userId);
    }

    public int clearConversation(int userId, int friendId) {
        return messageDao.clearConversation(userId, friendId);
    }

    public int markRead(int senderId, int receiverId) {
        return messageDao.markRead(senderId, receiverId);
    }

    // ===== Groups =====
    public int createGroup(int ownerId, String name, List<Integer> memberIds) {
        return groupDao.createGroup(ownerId, name, memberIds);
    }

    public boolean isGroupMember(int groupId, int userId) {
        return groupDao.isGroupMember(groupId, userId);
    }

    public Map<String, Object> getGroupById(int groupId) {
        return groupDao.getGroupById(groupId);
    }

    public List<Map<String, Object>> getGroupMembers(int groupId) {
        return groupDao.getGroupMembers(groupId);
    }

    public boolean addGroupMember(int groupId, int userId) {
        return groupDao.addGroupMember(groupId, userId);
    }

    public boolean removeGroupMember(int groupId, int userId) {
        return groupDao.removeGroupMember(groupId, userId);
    }

    public List<Map<String, Object>> getMyGroups(int userId) {
        return groupDao.getMyGroups(userId);
    }

    public Map<String, Object> saveGroupMessage(int groupId, int senderId, String content,
                                                String msgType, String filename, String thumbnail,
                                                int durationSec) {
        return groupDao.saveGroupMessage(groupId, senderId, content, msgType, filename, thumbnail, durationSec);
    }

    public Map<String, Object> saveGroupMessage(int groupId, int senderId, String content,
                                                String msgType, String filename, String thumbnail,
                                                int durationSec, String mentions) {
        return groupDao.saveGroupMessage(groupId, senderId, content, msgType, filename, thumbnail, durationSec, mentions);
    }

    public List<Map<String, Object>> getGroupMessages(int groupId, int limit) {
        return groupDao.getGroupMessages(groupId, limit);
    }

    public Map<String, Object> getGroupMessageById(int msgId) {
        return groupDao.getGroupMessageById(msgId);
    }

    public boolean recallGroupMessage(int msgId, int userId, int groupId) {
        return groupDao.recallGroupMessage(msgId, userId, groupId);
    }

    public int markGroupRead(int groupId, int userId, int upToMsgId) {
        return groupDao.markGroupRead(groupId, userId, upToMsgId);
    }

    public boolean hasGroupRead(int msgId, int userId) {
        return groupDao.hasGroupRead(msgId, userId);
    }

    // ===== 群聊权限/名片/群信息（阶段1） =====
    public String getGroupMemberRole(int groupId, int userId) {
        return groupDao.getGroupMemberRole(groupId, userId);
    }

    public boolean setGroupMemberRole(int groupId, int userId, String role) {
        return groupDao.setGroupMemberRole(groupId, userId, role);
    }

    public String getGroupMemberNickname(int groupId, int userId) {
        return groupDao.getGroupMemberNickname(groupId, userId);
    }

    public boolean setGroupMemberNickname(int groupId, int userId, String nickname) {
        return groupDao.setGroupMemberNickname(groupId, userId, nickname);
    }

    public long getGroupMemberMuteUntil(int groupId, int userId) {
        return groupDao.getGroupMemberMuteUntil(groupId, userId);
    }

    public boolean setGroupMemberMuteUntil(int groupId, int userId, long untilMs) {
        return groupDao.setGroupMemberMuteUntil(groupId, userId, untilMs);
    }

    public boolean updateGroupInfo(int groupId, String name, String avatar) {
        return groupDao.updateGroupInfo(groupId, name, avatar);
    }

    public java.util.Map<String, Object> getGroupSetting(int groupId, int userId) {
        return groupDao.getGroupSetting(groupId, userId);
    }

    public boolean setGroupSetting(int groupId, int userId, Integer pinned, Integer quiet) {
        return groupDao.setGroupSetting(groupId, userId, pinned, quiet);
    }

    public boolean transferGroup(int groupId, int oldOwnerId, int newOwnerId) {
        return groupDao.transferGroup(groupId, oldOwnerId, newOwnerId);
    }

    public boolean dissolveGroup(int groupId) {
        return groupDao.dissolveGroup(groupId);
    }

    // ===== 群公告 =====
    public Map<String, Object> createAnnouncement(int groupId, int publisherId, String content, int pinned) {
        return groupAnnouncementDao.createAnnouncement(groupId, publisherId, content, pinned);
    }

    public List<Map<String, Object>> getGroupAnnouncements(int groupId) {
        return groupAnnouncementDao.getGroupAnnouncements(groupId);
    }

    public Map<String, Object> getGroupAnnouncement(int announcementId) {
        return groupAnnouncementDao.getGroupAnnouncement(announcementId);
    }

    public Map<String, Object> updateAnnouncement(int announcementId, String content, Integer pinned) {
        return groupAnnouncementDao.updateAnnouncement(announcementId, content, pinned);
    }

    public boolean deleteAnnouncement(int announcementId) {
        return groupAnnouncementDao.deleteAnnouncement(announcementId);
    }

    public boolean markAnnouncementRead(int announcementId, int userId) {
        return groupAnnouncementDao.markAnnouncementRead(announcementId, userId);
    }

    // ===== 群内搜索 =====
    public List<Map<String, Object>> searchGroupMessages(int groupId, String keyword, int limit) {
        return groupFileDao.searchGroupMessages(groupId, keyword, limit);
    }

    // ═══════════════════ 群文件库（阶段4，明文 URL，仅群成员可看）═══════════════════
    public int indexGroupFile(int groupId, int uploaderId, String filename, String fileUrl,
                              String thumbnail, long sizeBytes, String folder, String msgType, int fileMsgId) {
        return groupFileDao.indexGroupFile(groupId, uploaderId, filename, fileUrl, thumbnail, sizeBytes, folder, msgType, fileMsgId);
    }

    public List<Map<String, Object>> getGroupFiles(int groupId, String folder, int page, int limit) {
        return groupFileDao.getGroupFiles(groupId, folder, page, limit);
    }

    public List<Map<String, Object>> getGroupFileFolders(int groupId) {
        return groupFileDao.getGroupFileFolders(groupId);
    }

    public Map<String, Object> getGroupFile(int fileId) {
        return groupFileDao.getGroupFile(fileId);
    }

    public Integer findGroupIdByFileUrl(String url) {
        return groupFileDao.findGroupIdByFileUrl(url);
    }

    public boolean deleteGroupFile(int fileId) {
        return groupFileDao.deleteGroupFile(fileId);
    }

    // ═══════════════════ 群相册（阶段4）═══════════════════
    public int createGroupAlbum(int groupId, String name, String coverUrl, int creatorId) {
        return groupFileDao.createGroupAlbum(groupId, name, coverUrl, creatorId);
    }

    public List<Map<String, Object>> getGroupAlbums(int groupId) {
        return groupFileDao.getGroupAlbums(groupId);
    }

    public Map<String, Object> getGroupAlbum(int albumId) {
        return groupFileDao.getGroupAlbum(albumId);
    }

    public List<Map<String, Object>> getGroupAlbumPhotos(int albumId) {
        return groupFileDao.getGroupAlbumPhotos(albumId);
    }

    public int addAlbumPhoto(int albumId, int groupId, int uploaderId, String photoUrl, String thumbnail) {
        return groupFileDao.addAlbumPhoto(albumId, groupId, uploaderId, photoUrl, thumbnail);
    }

    public Map<String, Object> getGroupAlbumPhoto(int photoId) {
        return groupFileDao.getGroupAlbumPhoto(photoId);
    }

    public boolean deleteGroupAlbum(int albumId) {
        return groupFileDao.deleteGroupAlbum(albumId);
    }

    public boolean deleteAlbumPhoto(int photoId) {
        return groupFileDao.deleteAlbumPhoto(photoId);
    }

    // ===== 群投票（阶段5）：明文存储，服务端统计 =====
    public int createGroupPoll(int groupId, int creatorId, String title, String description,
                               boolean isMulti, boolean anonymous, long endAt, List<String> options) {
        return groupPollDao.createGroupPoll(groupId, creatorId, title, description, isMulti, anonymous, endAt, options);
    }

    public List<Map<String, Object>> getGroupPolls(int groupId, int myUserId) {
        return groupPollDao.getGroupPolls(groupId, myUserId);
    }

    public Map<String, Object> getGroupPoll(int pollId, int myUserId) {
        return groupPollDao.getGroupPoll(pollId, myUserId);
    }

    public boolean votePoll(int pollId, int userId, List<Integer> optionIds) {
        return groupPollDao.votePoll(pollId, userId, optionIds);
    }

    public boolean closePoll(int pollId) {
        return groupPollDao.closePoll(pollId);
    }

    public boolean deletePoll(int pollId) {
        return groupPollDao.deletePoll(pollId);
    }

    // ===== Mall =====
    public List<Map<String, Object>> getProducts(String category, String keyword) {
        return mallDao.getProducts(category, keyword);
    }

    public List<Map<String, Object>> getCategories() {
        return mallDao.getCategories();
    }

    // ===== 商城（阶段3）：商品详情 / 商家后台 / 订单闭环 =====
    public Map<String, Object> getProduct(int id) {
        return mallDao.getProduct(id);
    }

    public long insertProduct(int sellerId, String name, String category, long price, int stock,
                              String description, String imageUrl) {
        return mallDao.insertProduct(sellerId, name, category, price, stock, description, imageUrl);
    }

    public boolean updateProduct(int sellerId, int id, String name, String category, long price, int stock,
                                 String description, String imageUrl) {
        return mallDao.updateProduct(sellerId, id, name, category, price, stock, description, imageUrl);
    }

    public boolean deleteProduct(int sellerId, int id) {
        return mallDao.deleteProduct(sellerId, id);
    }

    public int toggleProduct(int sellerId, int id) {
        return mallDao.toggleProduct(sellerId, id);
    }

    public List<Map<String, Object>> getSellerProducts(int sellerId) {
        return mallDao.getSellerProducts(sellerId);
    }

    public Map<String, Object> createOrder(int buyerId, int productId, int quantity,
                                           String receiverName, String receiverPhone, String receiverAddress) {
        return mallDao.createOrder(buyerId, productId, quantity, receiverName, receiverPhone, receiverAddress);
    }

    public Map<String, Object> createOrders(int buyerId, List<Map<String, Object>> items,
                                            String receiverName, String receiverPhone, String receiverAddress) {
        return mallDao.createOrders(buyerId, items, receiverName, receiverPhone, receiverAddress);
    }

    public List<Map<String, Object>> getMyOrders(int buyerId) {
        return mallDao.getMyOrders(buyerId);
    }

    public List<Map<String, Object>> getSellerOrders(int sellerId) {
        return mallDao.getSellerOrders(sellerId);
    }

    public Map<String, Object> getOrder(int orderId) {
        return mallDao.getOrder(orderId);
    }

    public String payOrder(int orderId, int buyerId) {
        return mallDao.payOrder(orderId, buyerId);
    }

    public String payOrders(List<Integer> orderIds, int buyerId) {
        return mallDao.payOrders(orderIds, buyerId);
    }

    public String cancelOrder(int orderId, int buyerId) {
        return mallDao.cancelOrder(orderId, buyerId);
    }

    public String shipOrder(int orderId, int sellerId, String company, String trackingNo) {
        return mallDao.shipOrder(orderId, sellerId, company, trackingNo);
    }

    public String confirmOrder(int orderId, int buyerId) {
        return mallDao.confirmOrder(orderId, buyerId);
    }

    // ===== 商城托管（审计第 4 条）：托管池查询 =====

    /** 托管池当前总额（分）：wallets 里托管账户(uid=0)的余额。 */
    public long getEscrowPoolTotal() {
        return mallDao.getEscrowPoolTotal();
    }

    /** 托管明细统计：{orders, amount, pool}。 */
    public Map<String, Object> getEscrowStats() {
        return mallDao.getEscrowStats();
    }

    // ===== 数据卫生（审计第 5 条）：孤儿回执 + 磁盘回收 =====

    /** 成员离开群后的联动清理（已读回执/群设置/公告已读）。 */
    public Map<String, Integer> cleanupAfterGroupMemberRemoved(int groupId, int userId) {
        return groupMaintenance.cleanupAfterMemberRemoved(groupId, userId);
    }

    public List<String> fileUrlsOfGroupFile(int fileId) {
        return groupMaintenance.fileUrlsOfGroupFile(fileId);
    }

    public List<String> fileUrlsOfAlbum(int albumId) {
        return groupMaintenance.fileUrlsOfAlbum(albumId);
    }

    public List<String> fileUrlsOfAlbumPhoto(int photoId) {
        return groupMaintenance.fileUrlsOfAlbumPhoto(photoId);
    }

    public List<String> fileUrlsOfGroup(int groupId) {
        return groupMaintenance.fileUrlsOfGroup(groupId);
    }

    /** 回收磁盘附件；只删全库无引用的文件。dryRun=true 时仅统计。 */
    public Map<String, Object> reclaimFiles(List<String> urls, boolean dryRun) {
        return groupMaintenance.reclaimFiles(urls, dryRun);
    }

    /** 全局孤儿扫描/清理（一次性脚本与定期任务共用）。 */
    public Map<String, Object> sweepOrphans(boolean dryRun, int maxList) {
        return groupMaintenance.sweep(dryRun, maxList);
    }

    /** 某个 chat_files 文件名是否仍被任何数据库行引用。 */
    public boolean isChatFileReferenced(String fileName) {
        return groupMaintenance.isFileReferenced(fileName);
    }

    // ===== Chat file uploads（单聊附件台账：生成名 → 上传者）=====
    /** 记一次成功落盘的上传；失败只打日志返回 false，绝不抛异常、绝不阻塞上传。 */
    public boolean recordChatFileUpload(String generatedName, int uploaderId, String originalName, long sizeBytes) {
        return chatFileUploadDao.recordUpload(generatedName, uploaderId, originalName, sizeBytes);
    }

    /** 按生成名查台账；不存在返回 null。 */
    public Map<String, Object> findChatFileUpload(String generatedName) {
        return chatFileUploadDao.findUpload(generatedName);
    }

    // ===== Mall（阶段4完善）：分页 / 购物车 / 收藏 / 地址簿 / 评价 / 退款 / 热搜 / 店铺 =====
    public Map<String, Object> getProductsPage(String category, String keyword, int page, int pageSize) {
        return mallDao.getProductsPage(category, keyword, page, pageSize);
    }

    public List<Map<String, Object>> getSellerPublicProducts(int sellerId) {
        return mallDao.getSellerPublicProducts(sellerId);
    }

    // ── 购物车 ──
    public List<Map<String, Object>> getCart(int userId) {
        return mallDao.getCart(userId);
    }

    public String addCart(int userId, int productId, int quantity) {
        return mallDao.addCart(userId, productId, quantity);
    }

    public String updateCartQuantity(int userId, int cartId, int quantity) {
        return mallDao.updateCartQuantity(userId, cartId, quantity);
    }

    public boolean deleteCartItem(int userId, int cartId) {
        return mallDao.deleteCartItem(userId, cartId);
    }

    public boolean deleteCartByProduct(int userId, int productId) {
        return mallDao.deleteCartByProduct(userId, productId);
    }

    public void clearCart(int userId) {
        mallDao.clearCart(userId);
    }

    // ── 商品收藏 ──
    public boolean isFavorite(int userId, int productId) {
        return mallDao.isFavorite(userId, productId);
    }

    public boolean toggleFavorite(int userId, int productId) {
        return mallDao.toggleFavorite(userId, productId);
    }

    public List<Map<String, Object>> getFavorites(int userId) {
        return mallDao.getFavorites(userId);
    }

    // ── 收货地址簿 ──
    public List<Map<String, Object>> getAddresses(int userId) {
        return mallDao.getAddresses(userId);
    }

    public long addAddress(int userId, String name, String phone, String address) {
        return mallDao.addAddress(userId, name, phone, address);
    }

    public boolean updateAddress(int userId, int id, String name, String phone, String address) {
        return mallDao.updateAddress(userId, id, name, phone, address);
    }

    public boolean deleteAddress(int userId, int id) {
        return mallDao.deleteAddress(userId, id);
    }

    public boolean setDefaultAddress(int userId, int id) {
        return mallDao.setDefaultAddress(userId, id);
    }

    public Map<String, Object> getDefaultAddress(int userId) {
        return mallDao.getDefaultAddress(userId);
    }

    // ── 商品评价 ──
    public String addReview(int userId, int orderId, int rating, String content) {
        return mallDao.addReview(userId, orderId, rating, content);
    }

    public List<Map<String, Object>> getProductReviews(int productId) {
        return mallDao.getProductReviews(productId);
    }

    public boolean orderReviewed(int orderId) {
        return mallDao.orderReviewed(orderId);
    }

    // ── 退款 / 售后 ──
    public String applyRefund(int orderId, int buyerId, String reason) {
        return mallDao.applyRefund(orderId, buyerId, reason);
    }

    public String approveRefund(int orderId, int sellerId) {
        return mallDao.approveRefund(orderId, sellerId);
    }

    public String rejectRefund(int orderId, int sellerId) {
        return mallDao.rejectRefund(orderId, sellerId);
    }

    // ── 搜索热词 ──
    public void recordSearch(String keyword) {
        mallDao.recordSearch(keyword);
    }

    public List<Map<String, Object>> getHotSearches(int limit) {
        return mallDao.getHotSearches(limit);
    }

    // ── 定时任务：超时取消 / 自动确认收货 ──
    public List<Map<String, Object>> cancelExpiredOrders(long expireMs) {
        return mallDao.cancelExpiredOrders(expireMs);
    }

    public List<Map<String, Object>> autoCompleteOrders(long expireMs) {
        return mallDao.autoCompleteOrders(expireMs);
    }
}
