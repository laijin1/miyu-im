package com.familychat.app.api;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 契约测试：{@code PushConnection.Fanout} 必须覆写 {@link WebSocketManager.Listener} 的<b>全部</b>方法。
 *
 * <h3>为什么必须有这条测试</h3>
 * {@code PushConnection} 是全进程唯一的 WebSocket 宿主，它用一个内部类 {@code Fanout}
 * 把每条回调扇出给所有注册的监听器（各 Activity / NotificationDispatcher / CallManager）。
 * 对 {@code Listener} 的 default 方法而言，<b>「不覆写」不会编译报错，但该事件会对所有
 * 监听器静默失效</b> —— 这个坑在本仓库已经踩过多次（新增通话信令时尤其容易漏）。
 *
 * <p>本测试用反射让"漏覆写"变成<b>测试失败</b>，而不是线上某条推送莫名收不到。
 *
 * <h3>实现注意</h3>
 * 用 {@code Class.forName(name, false, loader)} 加载，<b>刻意不初始化</b> {@code PushConnection}
 * —— 它的静态字段 {@code Handler MAIN} 会触碰 android.os.Handler/Looper，
 * 在纯 JVM 单测里没有必要触发（虽然 returnDefaultValues=true 能兜住，但能不依赖就不依赖）。
 * 反射取方法签名不需要初始化类。
 */
public class ListenerFanoutContractTest {

    private static final String FANOUT_CLASS = "com.familychat.app.api.PushConnection$Fanout";

    @Test
    public void fanoutOverridesEveryListenerMethod() throws Exception {
        Class<?> fanout = Class.forName(FANOUT_CLASS, false,
                ListenerFanoutContractTest.class.getClassLoader());

        assertTrue("Fanout 必须实现 WebSocketManager.Listener",
                WebSocketManager.Listener.class.isAssignableFrom(fanout));

        List<String> missing = new ArrayList<>();
        for (Method m : WebSocketManager.Listener.class.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            try {
                fanout.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName() + "(" + paramList(m) + ")");
            }
        }
        Collections.sort(missing);

        if (!missing.isEmpty()) {
            fail("PushConnection.Fanout 漏覆写 WebSocketManager.Listener 的 "
                    + missing.size() + " 个方法：" + missing
                    + "。\n漏覆写不会编译报错，但该事件会对所有监听器【静默失效】——"
                    + "请在 PushConnection.Fanout 中补上对应覆写。");
        }
    }

    /** 反向校验：Fanout 里不该出现 Listener 上已不存在的"陈旧覆写"（改名/删除后的残留）。 */
    @Test
    public void fanoutHasNoStaleOverrides() throws Exception {
        Class<?> fanout = Class.forName(FANOUT_CLASS, false,
                ListenerFanoutContractTest.class.getClassLoader());

        List<Method> declared = new ArrayList<>(Arrays.asList(fanout.getDeclaredMethods()));
        List<String> stale = new ArrayList<>();
        for (Method m : declared) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if ("<init>".equals(m.getName())) continue;
            try {
                WebSocketManager.Listener.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                stale.add(m.getName() + "(" + paramList(m) + ")");
            }
        }
        Collections.sort(stale);

        assertTrue("PushConnection.Fanout 存在 Listener 上已不存在的覆写（接口改名/删方法的残留）："
                + stale, stale.isEmpty());
    }

    /** 保证"带 callId 的来电"这条新通道不会退化回窄接口（CallManager 的去重键依赖它）。 */
    @Test
    public void listenerExposesCallIdChannels() throws Exception {
        Class<?> l = WebSocketManager.Listener.class;
        // 必须存在，且必须是 default（否则 7 个历史实现者会全部编译失败）
        Method withId = l.getMethod("onCallOfferWithId", int.class, String.class, boolean.class, String.class);
        assertTrue("onCallOfferWithId 必须是 default 方法（老实现者不应被迫改动）",
                withId.isDefault());

        for (String name : new String[]{"onCallBusy", "onCallReject", "onCallCancel"}) {
            Method m = l.getMethod(name, int.class, String.class);
            assertTrue(name + " 必须是 default 方法", m.isDefault());
        }
    }

    private static String paramList(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getSimpleName());
        }
        return sb.toString();
    }
}
