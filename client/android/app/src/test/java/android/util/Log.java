package android.util;

/**
 * 单元测试用的 android.util.Log 替身（不是生产代码）。
 *
 * android.jar 的桩实现里 Log.d/i/w/e 是空方法，开启 returnDefaultValues 后返回 0，
 * 本身不致命；但用真实替身可以把日志打到 stdout，测试失败时更容易定位。
 * 同时避免个别情况下 "not mocked" 异常。
 */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    /** 设为 true 可让测试输出完整日志（排查加密失败时很有用）。 */
    public static boolean VERBOSE_LOGGING = false;

    private Log() {
    }

    public static int v(String tag, String msg) { return log("V", tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return log("V", tag, msg, t); }
    public static int d(String tag, String msg) { return log("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable t) { return log("D", tag, msg, t); }
    public static int i(String tag, String msg) { return log("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return log("I", tag, msg, t); }
    public static int w(String tag, String msg) { return log("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return log("W", tag, msg, t); }
    public static int e(String tag, String msg) { return log("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return log("E", tag, msg, t); }

    public static boolean isLoggable(String tag, int level) {
        return level >= DEBUG || VERBOSE_LOGGING;
    }

    public static String getStackTraceString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static int log(String level, String tag, String msg, Throwable t) {
        if (VERBOSE_LOGGING || "E".equals(level) || "W".equals(level)) {
            System.out.println(level + "/" + tag + ": " + msg);
            if (t != null) t.printStackTrace(System.out);
        }
        return 0;
    }
}
