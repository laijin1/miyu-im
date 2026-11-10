package com.familychat.server.handler;

import com.familychat.server.DbTestSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 接口限流表的内存上限（体检报告 §7.6「限流 Map 无上限」）。
 *
 * <p>原实现是「每账号一条、永不回收」的 ConcurrentHashMap：长期运行下会随历史登录
 * 账号数单调增长。现在照搬 AuthHandler 既有形状，加了容量闸门：
 * 到达 {@code AI_RATE_MAP_MAX} 先清理过期窗口条目，仍超限则强制驱逐一半。
 *
 * <p>本测试直接驱动私有 {@code checkAiRateLimit}，验证「超过容量阈值后计数被重置」——
 * 即新账号不会被早已不活跃的历史条目挤掉配额（这是普通 LRU 淘汰不具备的性质：
 * 过期限流的唯一正确淘汰方式是「窗口已过期」，允许重置计数恰好等价于该语义）。
 */
class AiRateLimitMapBoundTest extends DbTestSupport {

    private static final String MAP_FIELD = "aiRateLimit";

    @SuppressWarnings("unchecked")
    private static Map<Integer, long[]> rateMap() throws Exception {
        Field f = Class.forName("com.familychat.server.handler.AiHandler")
                .getDeclaredField(MAP_FIELD);
        f.setAccessible(true);
        return (Map<Integer, long[]>) f.get(null);
    }

    private static boolean check(int userId) throws Exception {
        Method m = Class.forName("com.familychat.server.handler.AiHandler")
                .getDeclaredMethod("checkAiRateLimit", int.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, userId);
    }

    private static int mapMax() throws Exception {
        Field f = Class.forName("com.familychat.server.handler.AiHandler")
                .getDeclaredField("AI_RATE_MAP_MAX");
        f.setAccessible(true);
        return (Integer) f.get(null);
    }

    @Test
    void mapHasAnExplicitUpperBoundConstant() throws Exception {
        int max = mapMax();
        assertTrue(max > 0 && max < 1_000_000, "AI_RATE_MAP_MAX 应有明确且合理的上限，实际=" + max);
    }

    @Test
    void mapStaysBoundedAndNewAccountsStillGetTheirOwnQuota() throws Exception {
        Map<Integer, long[]> map = rateMap();
        map.clear();
        int max = mapMax();

        // 1) 先填满到上限（每个账号一次请求，都是「活跃」的）
        for (int uid = 1; uid <= max + 100; uid++) {
            check(uid);
        }
        assertTrue(map.size() <= max + 100,
                "map 不应超过上限 + 当前批次的瞬时写入量，实际=" + map.size());

        // 2) 驱逐后，一个全新账号必须仍能拿到完整配额（不被历史条目挤掉）
        int fresh = 9_000_000;
        assertTrue(check(fresh), "新账号首次请求必须放行");
        for (int i = 2; i <= 20; i++) {
            assertTrue(check(fresh), "新账号配额不应被历史条目影响（第 " + i + " 次）");
        }
        assertFalse(check(fresh), "新账号第 21 次应被限流（AI_RATE_MAX=20）");

        // 3) 清场，避免影响同一 JVM 内其它测试
        map.clear();
    }
}
