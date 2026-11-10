package android.util;

import java.nio.charset.StandardCharsets;

/**
 * 单元测试用的 android.util.Base64 替身（不是生产代码）。
 *
 * 为什么需要它：
 *   Android 单元测试默认使用 android.jar 的"桩实现"，所有框架方法要么抛
 *   "not mocked"，要么（开了 returnDefaultValues 时）返回 0/null。
 *   Base64.encodeToString 返回 null 会让加密/备份逻辑一路失败，
 *   于是测试报的是"加密失败"，根本测不到真实逻辑。
 *
 * 做法：测试源码目录里的同名类会排在 android.jar 之前被加载（test classpath 优先），
 *   因此这里用 JDK 自带的 java.util.Base64 提供真实实现即可。
 *
 * 注意：参数 flags 在真实 Android 里控制换行/URL-safe/补位等，
 *   被测代码只用 NO_WRAP 与 DEFAULT，二者在 JDK 实现下都无需特殊处理。
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    private static boolean urlSafe(int flags) {
        return (flags & URL_SAFE) == URL_SAFE;
    }

    private static boolean noPadding(int flags) {
        return (flags & NO_PADDING) == NO_PADDING;
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) return null;
        String s = str.trim();
        if (s.isEmpty()) return new byte[0];
        java.util.Base64.Decoder decoder = urlSafe(flags)
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getMimeDecoder();
        return decoder.decode(s);
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) return null;
        return decode(new String(input, StandardCharsets.US_ASCII), flags);
    }

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return null;
        java.util.Base64.Encoder encoder;
        if (urlSafe(flags)) {
            encoder = noPadding(flags)
                    ? java.util.Base64.getUrlEncoder().withoutPadding()
                    : java.util.Base64.getUrlEncoder();
        } else {
            encoder = noPadding(flags)
                    ? java.util.Base64.getEncoder().withoutPadding()
                    : java.util.Base64.getEncoder();
        }
        return encoder.encodeToString(input); // JDK 实现默认不换行 = NO_WRAP 语义
    }

    public static byte[] encode(byte[] input, int flags) {
        String s = encodeToString(input, flags);
        return s == null ? null : s.getBytes(StandardCharsets.US_ASCII);
    }

    public static int encode(byte[] input, int offset, int len, int flags) {
        byte[] all = encode(input, flags);
        if (all == null) return -1;
        System.arraycopy(all, 0, input, offset, all.length);
        return all.length;
    }
}
