package com.familychat.app.utils;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用内日志工具：把报错/崩溃写到应用私有目录 logs/app.log，
 * 供 设置→日志 界面查看。线程安全、单文件超 256KB 自动轮转到 app.log.old。
 */
public class AppLog {

    private static final String DIR = "logs";
    private static final String FILE = "app.log";
    private static final String OLD = "app.log.old";
    private static final long MAX_BYTES = 256 * 1024; // 单文件上限 256KB

    private static Context appContext;
    private static final Object LOCK = new Object();

    /** 在 Application.onCreate 里调用一次。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * 返回 {@link #init} 时保存的 Application Context；尚未初始化（如纯 JVM 单测）返回 null。
     *
     * <p>存在的理由：{@link PreferencesManager#getInstance} 需要一个 Context，而
     * {@link HttpClientProvider} 是静态门面、拿不到调用方的 Context。AppLog 已经在
     * {@code FamilyChatApp.onCreate()} 最早期被 init，是 {@code :core} 里现成的
     * Application 级 Context 持有者，复用它比新增反射或改 PreferencesManager 签名更稳。
     */
    public static Context appContext() {
        return appContext;
    }

    public static void d(String tag, String msg) {
        write("D", tag, msg, null);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg, t);
    }

    private static File logDir() {
        return new File(appContext.getFilesDir(), DIR);
    }

    private static File logFile() {
        return new File(logDir(), FILE);
    }

    private static void write(String level, String tag, String msg, Throwable t) {
        synchronized (LOCK) {
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                File f = logFile();
                rollIfNeeded(f);

                StringBuilder sb = new StringBuilder();
                sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                sb.append(" [").append(tag).append("] ").append(level).append(' ').append(msg);
                if (t != null) {
                    sb.append('\n');
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    sb.append(sw);
                }
                sb.append('\n');

                try (FileOutputStream fos = new FileOutputStream(f, true);
                     OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    w.write(sb.toString());
                    w.flush();
                }
            } catch (Exception ignored) {}
        }
    }

    /** 当前文件超过上限就轮转到 app.log.old（旧的丢弃），避免无限膨胀。 */
    private static void rollIfNeeded(File f) {
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                File old = new File(logDir(), OLD);
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
        } catch (Exception ignored) {}
    }

    /** 读取全部日志（旧文件在前、当前文件在后），无日志返回空串。 */
    public static String read() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            File old = new File(logDir(), OLD);
            File cur = logFile();
            if (old.exists()) readInto(old, sb);
            if (cur.exists()) readInto(cur, sb);
            return sb.toString();
        }
    }

    private static void readInto(File f, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {}
    }

    public static void clear() {
        synchronized (LOCK) {
            File old = new File(logDir(), OLD);
            File cur = logFile();
            if (old.exists()) old.delete();
            if (cur.exists()) cur.delete();
        }
    }
}
