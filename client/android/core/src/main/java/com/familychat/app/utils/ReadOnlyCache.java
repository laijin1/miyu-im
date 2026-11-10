package com.familychat.app.utils;

import android.content.Context;
import android.util.Log;

import com.familychat.app.api.Models;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * 只读本地缓存：把服务端返回的列表原样镜像到本地，供断网时显示。
 *
 * <h3>核心不变量（改这个类之前必读）</h3>
 * <ol>
 *   <li><b>只镜像服务端数据，绝不接受用户操作。</b>本类只有 saveXxx/loadXxx 两组方法，
 *       没有任何"本地修改后写回"的入口。调用方必须在 {@code api.getXxx()} 返回的
 *       <b>下一行</b>就调用 save，赶在任何用户操作类字段（置顶/免打扰/未读）被写入之前，
 *       这样落盘的就是服务端原文。<b>任何"把本地改动也 save 一遍"的想法都是错的</b>——
 *       历史事故（撤回状态本地化后与服务端不同步）正是这么来的。</li>
 *   <li><b>网络永远是唯一权威。</b>缓存只用于"网络没给东西"时兜底显示。
 *       加载顺序固定为：先渲染缓存（若有）→ 再请求网络 → 成功则覆盖缓存并隐藏离线提示。
 *       缓存绝不能替代网络请求，也绝不能参与任何写回服务端的逻辑。</li>
 *   <li><b>失败时绝不写空覆盖。</b>网络异常时不调用 save，旧缓存原样保留。
 *       唯一的例外是"服务端明确回了 2xx 且列表字段非 null"，那才算一次有效响应。</li>
 * </ol>
 *
 * <h3>敏感度说明（安全）</h3>
 * 缓存内容包含好友/群的昵称、账号名、密语号、备注、头像路径等个人信息。
 * 其中 <b>{@code avatar} URL 内含 {@code ?token=...}（见 ServerProtocol.withToken）</b>，
 * 与 {@code prefs.setToken} 写入的登录凭据<b>属同一敏感级别</b>。
 * 本类的处置方式是"只镜像、不额外暴露"：不打印、不上报、不写日志、不导出，
 * 存储介质沿用 PreferencesManager 已加密的 EncryptedSharedPreferences，
 * 因此不新增任何明文落盘面，也不比 token 本身的暴露面更宽。
 * 任何新增的调试输出都不得包含缓存的原始 JSON。
 *
 * <h3>作用域与容量</h3>
 * key 以 uid 为前缀：切号/登出后 uid 变化 → key 变化 → 天然读不到上一个账号的缓存，
 * 不依赖调用方记得清理。每个 uid 固定 2 个 key（friends / groups），覆盖写，
 * 不存在历史版本或 append，因此不会随时间增长。
 * <b>uid 不可信（读不到，或 &lt;= 0）时一律拒绝读写</b>：宁可这次不缓存，
 * 也绝不让两个账号共用同一个 key（不缓存 &gt; 串号缓存）。
 */
public final class ReadOnlyCache {

    private static final String TAG = "ReadOnlyCache";

    private static final String KEY_PREFIX = "ro_cache_";
    private static final String KEY_FRIENDS = "_friends";
    private static final String KEY_GROUPS = "_groups";

    /**
     * 单个列表的 JSON 上限。GET /api/friends 与 GET /api/groups 服务端均<b>未分页</b>
     * （全量返回），家庭规模下每条约 200–400B、总量约 10KB，远低于此值。
     * 设上限是为了让"某天列表异常膨胀"变成一条可见的日志，而不是悄悄把加密 prefs 撑大。
     */
    private static final int MAX_JSON_CHARS = 512 * 1024;

    private static final Gson GSON = new Gson();

    private ReadOnlyCache() {}

    // =================================================================
    //  存储接缝
    // =================================================================

    /**
     * 本类真正依赖的全部存储能力：uid 作用域 + 字符串读写。
     * 存在的唯一目的是让纯 JVM 单测能替换成内存实现——{@link PreferencesManager}
     * 是私有构造 + Keystore 的单例，在单测里无法构造。
     * 包可见：不扩大本类的 public API。
     */
    interface Store {
        /** 当前账号 uid；不可信时允许抛异常（由 {@link #scopeKey} 决定降级）。 */
        int uid();

        /** 读 key；不存在返回 null。 */
        String get(String key);

        /** 写 key。 */
        void put(String key, String value);
    }

    private static Store storeOf(final PreferencesManager prefs) {
        return new Store() {
            @Override public int uid() { return prefs.getUserId(); }
            @Override public String get(String key) { return prefs.getString(key, null); }
            @Override public void put(String key, String value) { prefs.putString(key, value); }
        };
    }

    // =================================================================
    //  写入（只应在 api 成功返回后调用）
    // =================================================================

    /**
     * 镜像好友列表。必须紧贴 {@code api.getFriends()} 调用，早于任何用户操作类字段回写。
     *
     * @param res 服务端响应；{@code res == null} 或列表字段为 null 时静默跳过（不覆盖旧缓存）
     */
    public static void saveFriends(Context ctx, PreferencesManager prefs, Models.FriendsResponse res) {
        if (prefs == null) return;
        saveFriends(storeOf(prefs), res);
    }

    static void saveFriends(Store store, Models.FriendsResponse res) {
        if (res == null || res.friends == null) return;
        save(store, KEY_FRIENDS, res);
    }

    /** 镜像群列表。约束同 {@link #saveFriends}。 */
    public static void saveGroups(Context ctx, PreferencesManager prefs, Models.GroupListResponse res) {
        if (prefs == null) return;
        saveGroups(storeOf(prefs), res);
    }

    static void saveGroups(Store store, Models.GroupListResponse res) {
        if (res == null || res.groups == null) return;
        save(store, KEY_GROUPS, res);
    }

    private static void save(Store store, String suffix, Object res) {
        if (store == null) return;
        String key = null;
        try {
            key = scopeKey(store, suffix);
            if (key == null) {
                // uid 不可信：宁可这次不缓存，也不写进会把两个账号混在一起的 key
                AppLog.e("CACHE", "跳过写盘 suffix=" + suffix + "：uid 不可用");
                return;
            }
            String json = GSON.toJson(res);
            if (json == null || json.isEmpty()) return;
            if (json.length() > MAX_JSON_CHARS) {
                // 只记长度，绝不记内容（内容含 PII / avatar URL 里的 token）
                AppLog.e("CACHE", "跳过写盘 " + suffix + "：JSON 过大 len=" + json.length()
                        + " 上限=" + MAX_JSON_CHARS);
                return;
            }
            // 信封：时间戳与载荷写在同一个值里，保证两者永远一致
            // （拆成两个 prefs key 时，极端情况下会出现"时间戳已更新但载荷还是旧的"）。
            // 注意：这里刻意不把 uid 写进信封——uid 已经在 key 里了，重复存储只会多一处可不一致的来源。
            //
            // ⚠️ 必须交给 Gson 序列化，绝不再手拼 JSON 字符串。
            // 历史事故：手拼时把 payload 当成对象裸内联（"...,\"payload\":" + json），
            // 而 Envelope.payload 是 String，Gson 反序列化遇到 BEGIN_OBJECT 直接抛
            // "Expected a string but was BEGIN_OBJECT" → load 恒返回 null → 断网冷启动空白。
            // 交给 Gson 序列化后读写天然对称，转义由它负责。
            Envelope env = new Envelope();
            env.ts = System.currentTimeMillis();
            env.len = json.length();
            env.payload = json;
            store.put(key, GSON.toJson(env));
        } catch (Exception e) {
            // 缓存失败绝不能影响主流程：loadFriends/loadGroups 失败会伪装成"加载好友失败"。
            // 这里只记类型不记内容。
            Log.w(TAG, "save" + suffix + " failed (ignored)", e);
            AppLog.e("CACHE", "写盘失败 suffix=" + suffix + " key=" + key
                    + " type=" + e.getClass().getSimpleName());
        }
    }

    // =================================================================
    //  读取（断网兜底；全部在后台线程调用）
    // =================================================================

    /** 读取好友列表缓存；无缓存/损坏/uid 不可用/安全存储不可用时返回 null。 */
    public static Models.FriendsResponse loadFriends(Context ctx, PreferencesManager prefs) {
        return prefs == null ? null : loadFriends(storeOf(prefs));
    }

    static Models.FriendsResponse loadFriends(Store store) {
        String key = null;
        try {
            key = scopeKey(store, KEY_FRIENDS);
            if (key == null) {
                // uid 不可信＝"还没登录"的正常态，不该污染 AppLog（设置→日志）
                Log.d(TAG, "loadFriends skipped: uid unavailable");
                return null;
            }
            Envelope env = readEnvelope(store, key);
            if (env == null || env.payload == null) return null;
            Models.FriendsResponse res = GSON.fromJson(env.payload, Models.FriendsResponse.class);
            return (res != null && res.friends != null) ? res : null;
        } catch (Exception e) {
            Log.w(TAG, "loadFriends failed (treated as no cache)", e);
            AppLog.e("CACHE", "读盘失败 suffix=" + KEY_FRIENDS + " key=" + key
                    + " type=" + e.getClass().getSimpleName());
            return null;
        }
    }

    /** 读取群列表缓存；无缓存/损坏/uid 不可用/安全存储不可用时返回 null。 */
    public static Models.GroupListResponse loadGroups(Context ctx, PreferencesManager prefs) {
        return prefs == null ? null : loadGroups(storeOf(prefs));
    }

    static Models.GroupListResponse loadGroups(Store store) {
        String key = null;
        try {
            key = scopeKey(store, KEY_GROUPS);
            if (key == null) {
                Log.d(TAG, "loadGroups skipped: uid unavailable");
                return null;
            }
            Envelope env = readEnvelope(store, key);
            if (env == null || env.payload == null) return null;
            Models.GroupListResponse res = GSON.fromJson(env.payload, Models.GroupListResponse.class);
            return (res != null && res.groups != null) ? res : null;
        } catch (Exception e) {
            Log.w(TAG, "loadGroups failed (treated as no cache)", e);
            AppLog.e("CACHE", "读盘失败 suffix=" + KEY_GROUPS + " key=" + key
                    + " type=" + e.getClass().getSimpleName());
            return null;
        }
    }

    /** 缓存写入时刻（毫秒）；无缓存或损坏时返回 0。 */
    public static long friendsCachedAt(PreferencesManager prefs) {
        return prefs == null ? 0L : friendsCachedAt(storeOf(prefs));
    }

    static long friendsCachedAt(Store store) {
        return readTs(store, KEY_FRIENDS);
    }

    /** 缓存写入时刻（毫秒）；无缓存或损坏时返回 0。 */
    public static long groupsCachedAt(PreferencesManager prefs) {
        return prefs == null ? 0L : groupsCachedAt(storeOf(prefs));
    }

    static long groupsCachedAt(Store store) {
        return readTs(store, KEY_GROUPS);
    }

    // 清理入口：见 PreferencesManager#logout() 里按 ro_cache_ 前缀清理的既有实现。

    /**
     * 组装带 uid 作用域的 key：{@code ro_cache_<uid>_friends} / {@code ro_cache_<uid>_groups}。
     * uid 参与 key 是切号安全的根本保证——切号或登出后 uid 变化 → key 变化 → 天然读不到
     * 上一个账号的缓存，不依赖任何清理动作是否被执行。
     *
     * <p><b>uid 不可信时返回 {@code null}，绝不退化成 uid=0 的共享桶。</b>
     * "读不到 uid"（抛异常）与"uid &lt;= 0"（未登录/已登出）都视为不可信：
     * 两个账号共用同一个 key 造成的串号，比这次不缓存严重得多。
     * 调用方必须处理 null——save 跳过写盘，load 当无缓存，readTs 返回 0。
     */
    static String scopeKey(Store store, String suffix) {
        if (store == null) return null;
        int uid;
        try {
            uid = store.uid();
        } catch (Exception e) {
            Log.w(TAG, "uid unavailable, refusing to scope cache", e);
            return null;
        }
        if (uid <= 0) return null;
        return KEY_PREFIX + uid + suffix;
    }

    private static Envelope readEnvelope(Store store, String key) throws JsonSyntaxException {
        if (store == null || key == null) return null;
        String raw = store.get(key);
        if (raw == null || raw.isEmpty()) return null;
        return GSON.fromJson(raw, Envelope.class);
    }

    private static long readTs(Store store, String suffix) {
        String key = null;
        try {
            key = scopeKey(store, suffix);
            if (key == null) return 0L;
            Envelope env = readEnvelope(store, key);
            return env != null ? env.ts : 0L;
        } catch (Exception e) {
            Log.w(TAG, "readTs" + suffix + " failed", e);
            AppLog.e("CACHE", "读时间戳失败 suffix=" + suffix + " key=" + key
                    + " type=" + e.getClass().getSimpleName());
            return 0L;
        }
    }

    /**
     * 缓存信封：时间戳与载荷同源，保证一致性。
     * 包可见（非 private）以便同包单测直接构造与断言。
     *
     * <p><b>只由 Gson 读写，不得手工拼装或解析</b>——{@code payload} 是 JSON
     * <b>字符串</b>而非内联对象，手拼会破坏这个不变量（见 {@code save} 里的历史事故说明）。
     */
    static final class Envelope {
        long ts;
        int len;
        String payload;
    }

    // =================================================================
    //  展示辅助
    // =================================================================

    /**
     * 把"缓存写于何时"渲染成人类可读的相对时间，用于离线横幅。
     * 超过 24 小时额外追加"可能已过期"——只影响文案措辞，不影响是否显示缓存。
     */
    public static String describeAge(long cachedAtMs) {
        // 时间戳缺失/损坏时仍要给出"这是缓存"的事实，否则会退化成"静默显示离线数据"。
        if (cachedAtMs <= 0L) return "离线 · 显示的是缓存数据";
        long delta = System.currentTimeMillis() - cachedAtMs;
        if (delta < 0L) delta = 0L;
        long minutes = delta / 60000L;
        String age;
        if (minutes < 1L) age = "刚刚";
        else if (minutes < 60L) age = minutes + " 分钟前";
        else if (minutes < 60L * 24L) age = (minutes / 60L) + " 小时前";
        else age = (minutes / (60L * 24L)) + " 天前";
        String text = "离线 · 显示 " + age + "的缓存";
        if (delta > 24L * 60L * 60L * 1000L) text += "，可能已过期";
        return text;
    }
}
