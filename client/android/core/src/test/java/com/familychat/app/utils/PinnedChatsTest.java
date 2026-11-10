package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * pinned_chats 解析 / 序列化的纯函数单测（批 1：好友置顶格式修复）。
 *
 * <p><b>只调 {@link PreferencesManager#parsePinned} / {@link PreferencesManager#formatPinned} 两个
 * static 方法，绝不 {@code new PreferencesManager(...)}</b>：单元测试跑在纯 JVM 上，android.jar 是 stub，
 * 一旦走到构造函数里的 {@code EncryptedSharedPreferences / MasterKeys} 就会抛 "Stub!" RuntimeException。
 * 这两个方法不碰 prefs（不经字段、不读盘），所以静态调用安全。
 */
public class PinnedChatsTest {

    /** 旧实现写入的 "5,"（首置顶）、规范 ",5,"、正常多项、重复累积 "5,5," 都必须解析正确。 */
    @Test
    public void parse_toleratesLegacyFormats() {
        assertEquals(set(5), PreferencesManager.parsePinned("5,"));
        assertEquals(set(5), PreferencesManager.parsePinned(",5,"));
        assertEquals(set(3, 5), PreferencesManager.parsePinned("3,5,"));
        assertEquals(set(5), PreferencesManager.parsePinned("5,5,"));
        assertEquals(set(5), PreferencesManager.parsePinned(" ,5, ,"));
    }

    @Test
    public void parse_emptyAndGarbage() {
        assertEquals(set(), PreferencesManager.parsePinned(""));
        assertEquals(set(), PreferencesManager.parsePinned(null));
        assertEquals(set(7), PreferencesManager.parsePinned("abc,7,,"));   // 非数字段跳过，不抛异常
    }

    @Test
    public void format_isCanonical() {
        assertEquals(",5,3,", PreferencesManager.formatPinned(set(5, 3)));
        assertEquals("", PreferencesManager.formatPinned(set()));
        assertEquals("", PreferencesManager.formatPinned(null));
    }

    /**
     * 验收第 1 条的核心不变量：任何遗留格式经一轮「解析 → 序列化」都收敛成规范串且幂等；
     * "置顶 → 取消 → 再置顶"三步后不出现重复 id（旧实现会累积成 "5,5,"）。
     */
    @Test
    public void roundTrip_collapsesDuplicatesAndIsIdempotent() {
        for (String legacy : new String[]{"5,", ",5,", "5,5,", "3,5,", ",3,5,5,"}) {
            String once = PreferencesManager.formatPinned(PreferencesManager.parsePinned(legacy));
            assertEquals("规范化一次后再走一轮结果必须不变（幂等）", once,
                    PreferencesManager.formatPinned(PreferencesManager.parsePinned(once)));
        }

        LinkedHashSet<Integer> ids = PreferencesManager.parsePinned("");
        ids.add(5);
        String pinned = PreferencesManager.formatPinned(ids);
        ids = PreferencesManager.parsePinned(pinned);
        ids.remove(5);
        String unpinned = PreferencesManager.formatPinned(ids);
        ids = PreferencesManager.parsePinned(unpinned);
        ids.add(5);
        String repinned = PreferencesManager.formatPinned(ids);

        assertEquals(",5,", pinned);
        assertEquals("", unpinned);
        assertEquals(",5,", repinned);      // 不累积
    }

    private static LinkedHashSet<Integer> set(Integer... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }
}
