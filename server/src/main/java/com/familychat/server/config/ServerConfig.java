package com.familychat.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.familychat.server.model.Database;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared configuration for the FamilyChat server.
 * JWT secret is loaded from environment variable JWT_SECRET, or auto-generated on first run.
 */
public class ServerConfig {

    private static final String JWT_SECRET;
    // 复用 Database.DATA_DIR（基于 jar 位置解析），保证密钥与数据库始终在同一数据目录，
    // 避免从 server-java/ 等非项目根目录启动时密钥路径错位、导致所有用户被强制登出。
    private static final Path DATA_DIR = Paths.get(Database.DATA_DIR);
    private static final String SECRET_FILE = DATA_DIR.resolve("jwt_secret.key").toString();
    private static final String DB_FILE = DATA_DIR.resolve("family_chat.db").toString();

    // 显式轮换开关：数据库已存在时若要重建密钥，必须先设这个环境变量。
    // 严格只认 "1"（"true" / "yes" 等一律视为未设置），避免误触发全员强制登出。
    private static final String ROTATE_ENV = System.getenv("JWT_SECRET_ROTATE");
    private static final boolean ROTATE_ALLOWED = ROTATE_ENV != null && "1".equals(ROTATE_ENV.trim());

    static {
        // 优先级：环境变量 JWT_SECRET > 系统属性 jwt.secret > 密钥文件 > 首次生成。
        // 加"系统属性"这一档是为了测试：gradle test 通过 -Djwt.secret=... 注入固定密钥，
        // 测试就不必依赖 build/test-data/jwt_secret.key 是否还在 ——
        // 那个文件一旦缺失而数据库还在，本类会按设计拒绝启动（防生产误轮换全员登出），
        // 于是测试会因为"上次跑测试留下的 db + 本次被 clean 掉的密钥文件"而集体挂掉。
        String propSecret = System.getProperty("jwt.secret");
        String secret = System.getenv("JWT_SECRET");
        if ((secret == null || secret.trim().isEmpty())
                && propSecret != null && !propSecret.trim().isEmpty()) {
            secret = propSecret;
        }
        if (secret != null && !secret.trim().isEmpty()) {
            JWT_SECRET = secret.trim();
            printKeyFingerprint(JWT_SECRET, propSecret != null && secret.equals(propSecret)
                    ? "[system property jwt.secret]" : "[env JWT_SECRET]");
        } else {
            JWT_SECRET = loadOrGenerateSecret();
        }
    }

    /** Load existing key file; generate a new one only on first run or on explicit rotation. */
    private static String loadOrGenerateSecret() {
        Path keyFile = Paths.get(SECRET_FILE).toAbsolutePath().normalize();
        if (Files.exists(keyFile)) {
            try {
                String secret = Files.readString(keyFile).trim();
                if (!secret.isEmpty()) {
                    printKeyFingerprint(secret, keyFile.toString());
                    return secret;
                }
            } catch (IOException e) {
                System.err.println("[WARN] Failed to read JWT secret key file: " + e.getMessage());
            }
        }

        // --- Key file missing or empty — CRITICAL SITUATION ---
        Path dbPath = Paths.get(DB_FILE).toAbsolutePath().normalize();
        boolean dbExists = Files.exists(dbPath);
        if (dbExists) {
            try { dbExists = Files.size(dbPath) > 0; }
            catch (IOException e) { dbExists = true; /* assume data exists if accessible */ }
        }

        // 数据库已存在 = 之前启动过、已经签发过 token。此时若密钥文件丢失还自动生成新密钥，
        // 会把所有在线用户强制登出且不可挽回 —— 所以必须显式授权（JWT_SECRET_ROTATE=1）才允许轮换。
        if (dbExists && !ROTATE_ALLOWED) {
            printRotationRefused(keyFile, dbPath);
            throw new IllegalStateException(
                    "Refusing to start: JWT secret file is missing but the database exists "
                    + "(key=" + keyFile + ", db=" + dbPath + "). "
                    + "Restore jwt_secret.key from backup, or set JWT_SECRET_ROTATE=1 to rotate deliberately.");
        }

        if (dbExists) {
            printRotationAuthorised(keyFile, dbPath);
        } else {
            System.out.println("[OK] First run (no database yet): generating a new JWT secret key.");
        }

        // Generate a new random 256-bit secret
        try {
            SecureRandom rng = new SecureRandom();
            byte[] keyBytes = new byte[32];
            rng.nextBytes(keyBytes);
            String generated = Base64.getEncoder().encodeToString(keyBytes);
            Path secretDir = keyFile.getParent();
            if (secretDir != null) {
                Files.createDirectories(secretDir);
                Files.writeString(keyFile, generated);
                System.out.println("[OK] Generated new JWT secret key -> " + keyFile);
            }
            if (dbExists) {
                System.out.println("[!!] DATABASE EXISTS — all existing tokens are now INVALID.");
                System.out.println("[!!] Users must re-login after this restart.");
            }
            printKeyFingerprint(generated, keyFile.toString());
            return generated;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT secret key: " + e.getMessage());
        }
    }

    /**
     * 在启动最早期强制完成 JWT 密钥校验，失败即终止启动。
     *
     * <p>必须在 {@code new Database()} / {@code db.init()} 之前调用。{@code db.init()} 会创建
     * family_chat.db，而本类的「数据库是否已存在」判断同时被用作「是否首次启动」的依据；
     * 数据库文件一旦先被建出来，全新安装就会被误判成"密钥丢失的老库"而被拒绝启动。
     *
     * <p>本方法只触发静态初始化（纯路径计算），不会创建目录或数据库文件。
     */
    public static void validateAtStartup() {
        // 真正校验发生在 static {} 里：能走到这里就说明密钥已就绪或已成功生成。
    }

    /** 明确拒绝启动：打印完整路径 + 为什么致命 + 两种恢复方式（随后由调用方抛异常终止）。 */
    private static void printRotationRefused(Path keyFile, Path dbPath) {
        System.err.println();
        System.err.println("================================================================");
        System.err.println("  [FATAL] JWT secret file is missing but the database exists.");
        System.err.println("================================================================");
        System.err.println();
        System.err.println("  Secret file : " + keyFile);
        System.err.println("  Database    : " + dbPath);
        System.err.println();
        System.err.println("  WHY THIS IS FATAL");
        System.err.println("    A previous run already created the database above, so tokens");
        System.err.println("    have already been issued. Generating a new key now would");
        System.err.println("    invalidate every existing token and force all users to log in");
        System.err.println("    again, and that cannot be undone. So the server refuses to");
        System.err.println("    start instead of silently logging everyone out.");
        System.err.println();
        System.err.println("  HOW TO RECOVER");
        System.err.println("    (a) Restore jwt_secret.key from a backup, then start again.");
        System.err.println("        Nothing is lost and no user is logged out.");
        System.err.println("    (b) If you REALLY intend to rotate the key:");
        System.err.println("          set JWT_SECRET_ROTATE=1");
        System.err.println("        then start again. A new key will be generated and ALL");
        System.err.println("        USERS WILL BE LOGGED OUT and must log in again.");
        System.err.println();
        System.err.println("  The server did NOT start, and no new key was written.");
        System.err.println("================================================================");
        System.err.println();
    }

    /** 显式授权轮换（JWT_SECRET_ROTATE=1）时的醒目警告：必须让运维看见后果。 */
    private static void printRotationAuthorised(Path keyFile, Path dbPath) {
        System.err.println();
        System.err.println("================================================================");
        System.err.println("  [!!] JWT KEY ROTATION authorised by JWT_SECRET_ROTATE=1");
        System.err.println("================================================================");
        System.err.println("  Secret file : " + keyFile + "   (missing or empty)");
        System.err.println("  Database    : " + dbPath);
        System.err.println();
        System.err.println("  A NEW key will be generated below. Consequences:");
        System.err.println("    - Every existing token becomes invalid IMMEDIATELY.");
        System.err.println("    - ALL users are logged out and must log in again.");
        System.err.println("    - This cannot be undone unless you still have the old file.");
        System.err.println();
        System.err.println("  If this was NOT intended: stop the server, restore");
        System.err.println("  jwt_secret.key from backup, and start again without this flag.");
        System.err.println("================================================================");
        System.err.println();
    }

    /** Print the SHA-256 fingerprint of the JWT secret for verification. */
    private static void printKeyFingerprint(String secret, String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            System.out.println("[OK] JWT key fingerprint (SHA-256): " + hex.substring(0, 16) + "...  <- " + source);
        } catch (Exception e) {
            // fingerprint is optional, ignore errors
        }
    }

    public static String getJwtSecret() {
        return JWT_SECRET;
    }
}
