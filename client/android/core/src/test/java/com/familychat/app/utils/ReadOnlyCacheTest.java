package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.familychat.app.api.Models;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 只读缓存的读写契约测试（纯 JVM，无需模拟器）。
 *
 * <p>存在的理由：本类上线时信封是手拼 JSON（{@code "...,\"payload\":" + json}），
 * 把 JSON <b>对象</b>裸内联进一个 {@code String} 字段，Gson 反序列化时抛
 * {@code Expected a string but was BEGIN_OBJECT} → load 恒返回 null →
 * "断网杀进程重进空白"，而写盘看起来完全正常。一条往返单测就能拦住它。
 *
 * <p>{@link ReadOnlyCache.Store} 接缝让这里能绕开 {@link PreferencesManager}
 * （私有构造 + Keystore 单例，JVM 里构造不出来）。
 */
public class ReadOnlyCacheTest {

    private static final String K_FRIENDS = "ro_cache_7_friends";
    private static final String K_GROUPS = "ro_cache_7_groups";

    private static final Gson GSON = new Gson();

    private FakeStore store;

    @Before
    public void setUp() {
        store = new FakeStore();
    }

    // =================================================================
    //  测试替身
    // =================================================================

    /** 只实现只读缓存真正依赖的三件事：uid + 字符串读写。 */
    private static final class FakeStore implements ReadOnlyCache.Store {
        int uid = 7;
        boolean uidThrows = false;
        final Map<String, String> map = new HashMap<>();

        @Override public int uid() {
            if (uidThrows) throw new IllegalStateException("uid unavailable (simulated)");
            return uid;
        }

        @Override public String get(String key) { return map.get(key); }

        @Override public void put(String key, String value) {
            if (value == null) map.remove(key); else map.put(key, value);
        }
    }

    // =================================================================
    //  夹具
    // =================================================================

    private static Models.User user(int id, String nickname) {
        Models.User u = new Models.User();
        u.id = id;
        u.nickname = nickname;
        u.username = "u" + id;
        // 含 token 的头像 URL：与登录凭据同级敏感，且必须逐字符保真
        u.avatar = "/uploads/a" + id + ".png?token=SECRET" + id;
        u.wx_id = "miyu-" + id;
        u.remark = "备注" + id;
        u.is_online = true;
        return u;
    }

    private static Models.FriendsResponse friends(Models.User... users) {
        Models.FriendsResponse r = new Models.FriendsResponse();
        r.friends = new ArrayList<>();
        for (Models.User u : users) r.friends.add(u);
        return r;
    }

    private static Models.Group group(int id, String name) {
        Models.Group g = new Models.Group();
        g.id = id;
        g.name = name;
        g.avatar = "/uploads/g" + id + ".png?token=GSECRET" + id;
        g.owner_id = 7;
        g.member_count = 3;
        g.last_content = "{\"c\":\"hello\"}";
        g.last_msg_type = "text";
        g.pinned = 1;
        g.quiet = 0;
        return g;
    }

    private static Models.GroupListResponse groups(Models.Group... gs) {
        Models.GroupListResponse r = new Models.GroupListResponse();
        r.groups = new ArrayList<>();
        for (Models.Group g : gs) r.groups.add(g);
        return r;
    }

    // =================================================================
    //  1. 往返（本次 bug 的直接回归）
    // =================================================================

    @Test
    public void friends_roundTrip_preservesAllFields() {
        Models.FriendsResponse saved = friends(user(11, "妈妈"), user(12, "爸爸😀"));
        ReadOnlyCache.saveFriends(store, saved);

        Models.FriendsResponse back = ReadOnlyCache.loadFriends(store);
        assertNotNull("存进去必须能读回来（原 bug：load 恒为 null）", back);
        assertNotNull(back.friends);
        assertEquals(2, back.friends.size());

        // Models.User 没有 equals()，逐字段比对
        Models.User a = back.friends.get(0);
        assertEquals(11, a.id);
        assertEquals("妈妈", a.nickname);
        assertEquals("u11", a.username);
        assertEquals("/uploads/a11.png?token=SECRET11", a.avatar);
        assertEquals("miyu-11", a.wx_id);
        assertEquals("备注11", a.remark);
        assertTrue(a.is_online);

        // 非 BMP 字符（emoji 代理对）也不能坏
        assertEquals("爸爸😀", back.friends.get(1).nickname);
    }

    @Test
    public void groups_roundTrip_preservesAllFields() {
        ReadOnlyCache.saveGroups(store, groups(group(21, "家族群"), group(22, "亲戚群")));

        Models.GroupListResponse back = ReadOnlyCache.loadGroups(store);
        assertNotNull("存进去必须能读回来（原 bug：load 恒为 null）", back);
        assertEquals(2, back.groups.size());

        Models.Group g = back.groups.get(0);
        assertEquals(21, g.id);
        assertEquals("家族群", g.name);
        assertEquals("/uploads/g21.png?token=GSECRET21", g.avatar);
        assertEquals(7, g.owner_id);
        assertEquals(3, g.member_count);
        assertEquals("{\"c\":\"hello\"}", g.last_content);
        assertEquals("text", g.last_msg_type);
        assertEquals(1, g.pinned);
    }

    @Test
    public void payloadIsStoredAsJsonString_notInlineObject() {
        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));

        String raw = store.map.get(K_FRIENDS);
        assertNotNull("save 必须真的落盘", raw);

        // 手拼 JSON 的签名：payload 后面直接跟 '{' 而不是 '"'
        assertTrue("payload 必须是 JSON 字符串（已转义），不能内联对象。raw=" + raw,
                raw.contains("\"payload\":\""));

        // 信封本身必须能被 Gson 解析（原 bug 在这里抛 BEGIN_OBJECT）
        ReadOnlyCache.Envelope env = GSON.fromJson(raw, ReadOnlyCache.Envelope.class);
        assertNotNull("信封必须可反序列化", env);
        assertNotNull(env.payload);

        Models.FriendsResponse back = GSON.fromJson(env.payload, Models.FriendsResponse.class);
        assertEquals("妈妈", back.friends.get(0).nickname);
        assertTrue("len 应与 payload 一致", env.len == env.payload.length());
    }

    // =================================================================
    //  2. 坏值 / 旧格式（自愈，不迁移）
    // =================================================================

    /**
     * 线上存量数据就是这个形状（payload 是内联对象）。
     * 契约：当成"没有缓存"而不是崩溃；下一次联网成功的新格式写入会把它覆盖掉（自愈）。
     */
    @Test
    public void legacyInlineObjectPayload_selfHealsAfterNextSave() {
        store.map.put(K_FRIENDS, "{\"ts\":1,\"len\":10,\"payload\":{\"friends\":[]}}");

        assertNull("旧格式坏值必须当作无缓存，且不得抛异常",
                ReadOnlyCache.loadFriends(store));

        // 下一次联网成功 → 覆盖 → 自愈
        ReadOnlyCache.saveFriends(store, friends(user(12, "爸爸")));
        Models.FriendsResponse back = ReadOnlyCache.loadFriends(store);
        assertNotNull("新格式写入后必须能读出来（自愈）", back);
        assertEquals(1, back.friends.size());
        assertEquals("爸爸", back.friends.get(0).nickname);
    }

    @Test
    public void corruptValue_isTreatedAsNoCache() {
        store.map.put(K_FRIENDS, "not json at all");
        store.map.put(K_GROUPS, "{\"ts\":\"not-a-number\"}");

        assertNull(ReadOnlyCache.loadFriends(store));
        assertNull(ReadOnlyCache.loadGroups(store));
        assertEquals(0L, ReadOnlyCache.friendsCachedAt(store));
        assertEquals(0L, ReadOnlyCache.groupsCachedAt(store));
    }

    // =================================================================
    //  3. 空列表 / 不写空覆盖
    // =================================================================

    @Test
    public void emptyFriendsList_isCached_notNullAfterLoad() {
        ReadOnlyCache.saveFriends(store, friends());   // 空列表（非 null 列表）

        Models.FriendsResponse back = ReadOnlyCache.loadFriends(store);
        assertNotNull("空列表是有效服务端响应，应可读回", back);
        assertNotNull(back.friends);
        assertEquals(0, back.friends.size());
    }

    @Test
    public void nullResponse_orNullList_doesNotOverwriteExistingCache() {
        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));
        Models.FriendsResponse before = ReadOnlyCache.loadFriends(store);
        assertNotNull(before);

        ReadOnlyCache.saveFriends(store, null);                 // 网络异常：调用方传 null
        Models.FriendsResponse nullList = new Models.FriendsResponse();
        nullList.friends = null;                                // 服务端明确回了空字段
        ReadOnlyCache.saveFriends(store, nullList);

        Models.FriendsResponse after = ReadOnlyCache.loadFriends(store);
        assertNotNull("失败时绝不能写空覆盖，旧缓存必须原样保留", after);
        assertEquals(1, after.friends.size());
        assertEquals("妈妈", after.friends.get(0).nickname);
    }

    // =================================================================
    //  4. 容量护栏
    // =================================================================

    @Test
    public void oversizedPayload_isRejected_previousCacheSurvives() {
        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));
        String before = store.map.get(K_FRIENDS);
        assertNotNull(before);

        // 撑过 512 KiB（MAX_JSON_CHARS）上限
        Models.User huge = user(12, "x");
        huge.nickname = "x".repeat(600 * 1024);
        ReadOnlyCache.saveFriends(store, friends(huge));

        // 只看落盘原文：护栏与信封格式正交，这条不依赖 load 路径
        assertEquals("超限必须整条跳过，旧缓存原样保留", before, store.map.get(K_FRIENDS));
    }

    // =================================================================
    //  5. uid 作用域隔离
    // =================================================================

    @Test
    public void differentUid_readsDifferentKey_noLeak() {
        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));
        assertNotNull(ReadOnlyCache.loadFriends(store));

        store.uid = 8;
        assertNull("切号后绝不能读到上一个账号的缓存", ReadOnlyCache.loadFriends(store));
        assertTrue("uid=8 不应存在任何 key", !store.map.containsKey("ro_cache_8_friends"));

        store.uid = 7;
        Models.FriendsResponse back = ReadOnlyCache.loadFriends(store);
        assertNotNull("切回原账号应能读到自己的缓存", back);
        assertEquals("妈妈", back.friends.get(0).nickname);
    }

    @Test
    public void uidZero_isRefused_notSharedBucket() {
        store.uid = 0;

        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));

        assertTrue("uid<=0 绝不能写入任何 key（尤其不能退化成共享的 ro_cache_0_*）。实际=" + store.map.keySet(),
                store.map.isEmpty());
        assertNull(ReadOnlyCache.loadFriends(store));
        assertEquals(0L, ReadOnlyCache.friendsCachedAt(store));
    }

    @Test
    public void uidThrows_degradesWithoutCache_noThrow() {
        store.uidThrows = true;

        assertNull("uid 读不到时不得组装出 key", ReadOnlyCache.scopeKey(store, "_friends"));

        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));
        assertTrue("uid 不可信时不得落盘任何东西", store.map.isEmpty());

        assertNull(ReadOnlyCache.loadFriends(store));
        assertNull(ReadOnlyCache.loadGroups(store));
        assertEquals(0L, ReadOnlyCache.friendsCachedAt(store));
    }

    // =================================================================
    //  6. 时间戳
    // =================================================================

    @Test
    public void cachedAt_zeroWhenAbsent_positiveAfterSave() {
        assertEquals(0L, ReadOnlyCache.friendsCachedAt(store));
        assertEquals(0L, ReadOnlyCache.groupsCachedAt(store));

        long before = System.currentTimeMillis();
        ReadOnlyCache.saveFriends(store, friends(user(11, "妈妈")));
        ReadOnlyCache.saveGroups(store, groups(group(21, "家族群")));

        long fTs = ReadOnlyCache.friendsCachedAt(store);
        long gTs = ReadOnlyCache.groupsCachedAt(store);
        assertTrue("写入后好友时间戳应有效，实际=" + fTs, fTs >= before);
        assertTrue("写入后群时间戳应有效，实际=" + gTs, gTs >= before);
    }
}
