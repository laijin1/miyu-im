import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * E2EE 密钥备份的独立加解密工具（**仅供测试脚本调用，不属于 App 代码**）。
 *
 * 为什么需要它：KeyBackup 用的是 AES-GCM，而 .NET 的 Aes 类只支持 CBC/ECB/OFB/CFB/CTS，
 * 不支持 GCM，PowerShell 侧无法直接实现。Java 的 Cipher 支持 GCM，且这里是纯 JDK
 * （无 BouncyCastle 等第三方依赖），所以用它来独立复刻 KeyBackup 的字节格式。
 *
 * 独立性说明：
 *   - 密钥对由 PowerShell（.NET ECDsa）生成并通过参数传入，本工具**不生成密钥**；
 *   - 本工具只实现 KeyBackup 的封装格式，不引用 App 的任何类；
 *   - 因此"脚本加密 → App 侧单测 → 服务端存储"三者互为交叉验证。
 *
 * 用法：
 *   encrypt <privateKeyAndPubCombined> <password> <userId>   -> 输出备份 Base64
 *   decrypt <backupB64> <password> <userId>                 -> 成功输出明文；失败 exit 2
 */
public final class KeyBackupTool {

    private static final String PBKDF2 = "PBKDF2WithHmacSHA1";
    private static final int V1_ITERATIONS = 50000;
    private static final int V2_ITERATIONS = 210000;
    private static final int AES_KEY_BITS = 256;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int SALT_LENGTH = 16;
    private static final int MAX_SALT_LENGTH = 64;
    private static final String SALT_PREFIX = "familychat/keybackup/v1/";

    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: encrypt|decrypt ...");
            System.exit(3);
        }
        try {
            switch (args[0]) {
                case "encrypt": {
                    if (args.length < 4) { System.err.println("encrypt needs 3 args"); System.exit(3); }
                    System.out.println(encrypt(args[1], args[2], Integer.parseInt(args[3])));
                    break;
                }
                case "decrypt": {
                    if (args.length < 4) { System.err.println("decrypt needs 3 args"); System.exit(3); }
                    String plain = decrypt(args[1], args[2], Integer.parseInt(args[3]));
                    if (plain == null) System.exit(2);
                    System.out.println(plain);
                    break;
                }
                default:
                    System.err.println("unknown command: " + args[0]);
                    System.exit(3);
            }
        } catch (Throwable t) {
            System.err.println("error: " + t);
            System.exit(4);
        }
    }

    private static SecretKey deriveKey(String password, int userId, byte[] salt, int iterations)
            throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] buildSalt(int userId, byte[] randomSalt) throws Exception {
        byte[] fixed = (SALT_PREFIX + userId).getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[fixed.length + randomSalt.length];
        System.arraycopy(fixed, 0, salt, 0, fixed.length);
        System.arraycopy(randomSalt, 0, salt, fixed.length, randomSalt.length);
        return salt;
    }

    /** v2 格式：0x02 | saltLen | salt | IV | 密文+tag，与 App 的 KeyBackup 一致。 */
    static String encrypt(String plain, String password, int userId) throws Exception {
        byte[] randomSalt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(randomSalt);
        SecretKey key = deriveKey(password, userId, buildSalt(userId, randomSalt), V2_ITERATIONS);

        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[2 + randomSalt.length + IV_LENGTH + ct.length];
        out[0] = 0x02;
        out[1] = (byte) randomSalt.length;
        System.arraycopy(randomSalt, 0, out, 2, randomSalt.length);
        System.arraycopy(iv, 0, out, 2 + randomSalt.length, IV_LENGTH);
        System.arraycopy(ct, 0, out, 2 + randomSalt.length + IV_LENGTH, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /** v1/v2 兼容解密；密码不对或数据损坏返回 null。 */
    static String decrypt(String backupB64, String password, int userId) {
        try {
            byte[] data = Base64.getDecoder().decode(backupB64);
            if (data.length < 1 + IV_LENGTH + 1) return null;
            int version = data[0] & 0xFF;

            byte[] salt;
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct;
            int iterations;

            if (version == 0x01) {
                ct = new byte[data.length - 1 - IV_LENGTH];
                System.arraycopy(data, 1, iv, 0, IV_LENGTH);
                System.arraycopy(data, 1 + IV_LENGTH, ct, 0, ct.length);
                salt = (SALT_PREFIX + userId).getBytes(StandardCharsets.UTF_8);
                iterations = V1_ITERATIONS;
            } else if (version == 0x02) {
                if (data.length < 3) return null;
                int saltLen = data[1] & 0xFF;
                if (saltLen < 8 || saltLen > MAX_SALT_LENGTH) return null;
                if (data.length < 2 + saltLen + IV_LENGTH + 1) return null;
                byte[] randomSalt = new byte[saltLen];
                System.arraycopy(data, 2, randomSalt, 0, saltLen);
                System.arraycopy(data, 2 + saltLen, iv, 0, IV_LENGTH);
                ct = new byte[data.length - 2 - saltLen - IV_LENGTH];
                System.arraycopy(data, 2 + saltLen + IV_LENGTH, ct, 0, ct.length);
                salt = buildSalt(userId, randomSalt);
                iterations = V2_ITERATIONS;
            } else {
                return null;
            }

            SecretKey key = deriveKey(password, userId, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
