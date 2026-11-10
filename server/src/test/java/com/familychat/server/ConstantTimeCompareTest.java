package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 敏感摘要比较必须走恒定时间（体检报告 §7.6「非恒定时间比较」）。
 *
 * <p>原实现用 {@code String.equals} 比较口令/支付密码摘要：{@code equals} 在首个不同
 * 字符处即返回，比较耗时随「猜对的前缀长度」变化。这里锁住两层保证：
 * <ol>
 *   <li>替换后语义不变（相等判 true、不等判 false、null 判 false）；</li>
 *   <li>helper 确实存在且为静态（防止日后被改回 equals 或签名漂移而测试静默失效）。</li>
 * </ol>
 * bcrypt 路径（{@code BCrypt.checkpw}）不在此列，它自带恒定时间比较。
 *
 * <p>两个 helper 分属 {@code handler} 与 {@code model.dao} 两个包，测试无法同时处于
 * 两者包内，故与既有 {@code FileAccessTest} 对 {@code uploaderIdOf} 的做法一致：用反射
 * 调用包内可见的 helper，而不为了测试把它提升为 public。
 */
class ConstantTimeCompareTest extends DbTestSupport {

    private static final String HEX_A = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String HEX_B = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b856"; // 仅末位不同
    private static final String HEX_C = "0000000000000000000000000000000000000000000000000000000000000000"; // 首位不同

    private static boolean userEq(String a, String b) throws Exception {
        return call("com.familychat.server.model.dao.UserDao", a, b);
    }

    private static boolean walletEq(String a, String b) throws Exception {
        return call("com.familychat.server.model.dao.WalletDao", a, b);
    }

    private static boolean call(String className, String a, String b) throws Exception {
        Method m = Class.forName(className).getDeclaredMethod("constantTimeEqualsHex", String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, a, b);
    }

    @Test
    void equalDigestsCompareTrueInBothDaos() throws Exception {
        assertTrue(userEq(HEX_A, HEX_A), "UserDao: 相同摘要应判等");
        assertTrue(walletEq(HEX_A, HEX_A), "WalletDao: 相同摘要应判等");
    }

    @Test
    void differingDigestsCompareFalseRegardlessOfPosition() throws Exception {
        assertFalse(userEq(HEX_A, HEX_B), "UserDao: 仅末位不同应判不等");
        assertFalse(userEq(HEX_A, HEX_C), "UserDao: 首位不同应判不等");
        assertFalse(walletEq(HEX_A, HEX_B), "WalletDao: 仅末位不同应判不等");
        assertFalse(walletEq(HEX_A, HEX_C), "WalletDao: 首位不同应判不等");
    }

    @Test
    void nullAndLengthMismatchAreHandledWithoutThrowing() throws Exception {
        assertFalse(userEq(null, HEX_A), "UserDao: null 应判不等且不抛异常");
        assertFalse(userEq(HEX_A, null), "UserDao: null 应判不等且不抛异常");
        assertFalse(userEq(null, null), "UserDao: 双 null 应判不等且不抛异常");
        assertFalse(walletEq(null, HEX_A), "WalletDao: null 应判不等且不抛异常");
        assertFalse(walletEq(HEX_A, null), "WalletDao: null 应判不等且不抛异常");
        assertFalse(walletEq(HEX_A, HEX_A.substring(0, 32)), "长度不等应判不等");
    }

    @Test
    void semanticsMatchStringEqualsForAllCases() throws Exception {
        // 行为等价性：isEqual 与 equals 在「相等/不等」上结论必须一致（替换不能改变判定）
        for (String[] pair : new String[][]{{HEX_A, HEX_A}, {HEX_A, HEX_B}, {HEX_C, HEX_A}}) {
            boolean expected = pair[0].equals(pair[1]);
            assertEquals(expected, userEq(pair[0], pair[1]),
                    "UserDao 判定应与 equals 一致: " + pair[0] + " vs " + pair[1]);
            assertEquals(expected, walletEq(pair[0], pair[1]),
                    "WalletDao 判定应与 equals 一致: " + pair[0] + " vs " + pair[1]);
        }
    }

    /** helper 必须存在且为静态（防止被搬走/改签名后，上面的测试静默失效）。 */
    @Test
    void helperExistsAsStaticMethodInBothDaos() throws Exception {
        for (String cn : new String[]{"com.familychat.server.model.dao.UserDao",
                                      "com.familychat.server.model.dao.WalletDao"}) {
            Method m = Class.forName(cn).getDeclaredMethod("constantTimeEqualsHex", String.class, String.class);
            assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()),
                    cn + ".constantTimeEqualsHex 应为静态方法");
        }
    }
}
