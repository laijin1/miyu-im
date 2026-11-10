package com.familychat.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

public class PreferencesManager {
    private static final String TAG = "PreferencesManager";
    private static final String NAME = "family_chat_secure_prefs";
    private static PreferencesManager instance;
    private final SharedPreferences prefs;

    private PreferencesManager(Context ctx) {
        SharedPreferences prefsTemp;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            prefsTemp = EncryptedSharedPreferences.create(
                NAME,
                masterKeyAlias,
                ctx.getApplicationContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fail-closed: 绝不静默降级为明文存储(token/私钥一旦明文落盘即等于泄露)。
            // 这种异常通常是 Keystore 主密钥丢失或设备恢复备份导致, 应让用户知晓而非悄悄明文。
            Log.e(TAG, "EncryptedSharedPreferences init failed — refusing to fall back to plaintext", e);
            throw new IllegalStateException(
                    "安全存储初始化失败, 无法以安全模式运行(拒绝明文落盘 token/私钥)。请重装应用或检查系统锁屏/Keystore。", e);
        }
        prefs = prefsTemp;
    }

    public static synchronized PreferencesManager getInstance(Context ctx) {
        if (instance == null) instance = new PreferencesManager(ctx);
        return instance;
    }

    public void setToken(String token) {
        prefs.edit().putString("token", token).apply();
        // 会话重新建立 → 释放 401 去重名额（AuthFailureGuard）。
        // 放在这里的原因：登录、注册、切换账号、改密换新 token 这 4 条路径**全部**经过本方法，
        // 集中一处不会漏。若漏了释放，下一次 token 过期将不再自动登出（卡在旧页面回不到登录页）。
        com.familychat.app.auth.AuthFailureGuard.reset();
    }

    public String getToken() {
        return prefs.getString("token", null);
    }

    public void clearToken() {
        prefs.edit().remove("token").apply();
    }

    public void setServer(String server) {
        prefs.edit().putString("server", server).apply();
    }

    public String getServer() {
        return prefs.getString("server", "");
    }

    /** Store userId + token atomically to prevent account crossover. */
    public void setUserId(int id) {
        prefs.edit().putInt("user_id", id).apply();
    }

    public int getUserId() {
        return prefs.getInt("user_id", 0);
    }

    /** Verify stored userId matches the expected one; if not, clear everything. */
    public boolean verifyUserId(int expectedUserId) {
        int stored = prefs.getInt("user_id", -1);
        if (stored != expectedUserId) {
            Log.w(TAG, "USER_ID MISMATCH: stored=" + stored + " expected=" + expectedUserId + " — clearing login");
            logout();
            return false;
        }
        return true;
    }

    public boolean isLoggedIn() {
        return getToken() != null && prefs.getInt("user_id", 0) > 0;
    }

    public void setMyAvatar(String avatar) {
        prefs.edit().putString("my_avatar", avatar).apply();
    }
    public String getMyAvatar() {
        return prefs.getString("my_avatar", "😊");
    }
    public void setMyAvatarColor(String color) {
        prefs.edit().putString("my_avatar_color", color).apply();
    }
    public String getMyAvatarColor() {
        return prefs.getString("my_avatar_color", "#e8f5e9");
    }
    public void setMyNickname(String nickname) {
        prefs.edit().putString("my_nickname", nickname).apply();
    }
    public String getMyNickname() {
        return prefs.getString("my_nickname", "");
    }
    public void setMyWxId(String wxId) {
        prefs.edit().putString("my_wx_id", wxId == null ? "" : wxId).apply();
    }
    public String getMyWxId() {
        return prefs.getString("my_wx_id", "");
    }

    /**
     * 当前账号的登录名（界面显示为 {@code @账号名}）。
     *
     * <p>存在的理由：这里过去没有公开 getter，{@code MainActivity.updateProfileView()}
     * 只能依赖 {@code api.getProfile()} 的结果，断网时就退化成布局里的硬编码
     * 「@username」。登录名其实早已落盘（{@code saveLoginAccount} 写 prefs 的
     * {@code username}，并同步进账号快照），缺的只是一个读取入口。
     *
     * <p>prefs 缺失时回落到当前账号快照，保证切号/升级后也能读到。
     */
    public String getMyUsername() {
        String username = prefs.getString("username", "");
        if (username != null && !username.isEmpty()) return username;
        Account acc = findAccount(getUserId());
        return (acc != null && acc.username != null) ? acc.username : "";
    }

    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }
    public String getString(String key, String def) {
        return prefs.getString(key, def);
    }

    // ===== 群聊成员身份缓存 =====
    // 存逗号分隔的 group_id 列表，用于判断 WS 群推送是否属于自己（避免收到不属于自己的群消息）。

    /** 缓存我加入的群 id 列表。 */
    public void setMyGroupIds(java.util.Set<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs.edit().putString("my_group_ids", sb.toString()).apply();
    }

    /** 我是否在某群中。 */
    public boolean isMyGroup(int groupId) {
        String s = prefs.getString("my_group_ids", "");
        return ("," + s + ",").contains("," + groupId + ",");
    }

    /** 群未读计数。 */
    public void setGroupUnread(int groupId, int count) {
        prefs.edit().putInt("group_unread_" + groupId, count).apply();
    }
    public int getGroupUnread(int groupId) {
        return prefs.getInt("group_unread_" + groupId, 0);
    }

    /** 群会话置顶缓存（主会话混排用）。 */
    public void setGroupPinned(int groupId, boolean pinned) {
        prefs.edit().putBoolean("group_pinned_" + groupId, pinned).apply();
    }
    public boolean getGroupPinned(int groupId) {
        return prefs.getBoolean("group_pinned_" + groupId, false);
    }

    // ===== 好友会话置顶（本机有效）=====
    // 服务端没有好友级会话设置表（只有 group_settings），好友置顶/免打扰一律本机 prefs。
    //
    // ⚠️ 历史 bug（本批修复）：旧实现写 friendId + "," + pinned —— 首个置顶存成 "5,"，
    //    而读取查的是 ",5,"，永远命中不了；取消时 replace(",5,", ",") 同样不命中，
    //    重复点会累积成 "5,5,"。现统一：解析对脏数据宽容，写回一律规范成 ",5,3,"。
    // key 保持不变（不做数据迁移，parsePinned 已兼容全部遗留格式）。

    private static final String KEY_PINNED_CHATS = "pinned_chats";

    /** 解析置顶好友 id 集合。兼容 "5," / ",5," / "3,5," / "5,5," 等一切遗留格式（空串跳过、脏数据忽略）。 */
    static java.util.LinkedHashSet<Integer> parsePinned(String raw) {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        if (raw == null) return ids;
        for (String s : raw.split(",")) {
            try {
                if (!s.trim().isEmpty()) ids.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据（非数字）跳过，绝不抛异常打断会话列表
            }
        }
        return ids;
    }

    /** 置顶集合的规范序列化：",5,3,"；空集合写空串。 */
    static String formatPinned(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(",");
        for (int id : ids) sb.append(id).append(',');
        return sb.toString();
    }

    /** 好友会话置顶（与群侧 setGroupPinned 同构）。 */
    public void setFriendPinned(int friendId, boolean pinned) {
        java.util.LinkedHashSet<Integer> ids = parsePinned(prefs.getString(KEY_PINNED_CHATS, ""));
        if (pinned) ids.add(friendId); else ids.remove(friendId);
        prefs.edit().putString(KEY_PINNED_CHATS, formatPinned(ids)).apply();
    }

    public boolean isFriendPinned(int friendId) {
        return parsePinned(prefs.getString(KEY_PINNED_CHATS, "")).contains(friendId);
    }

    /** 好友会话免打扰（与群侧 setGroupQuiet 同构，键 friend_quiet_<id>）。 */
    public void setFriendQuiet(int friendId, boolean quiet) {
        prefs.edit().putBoolean("friend_quiet_" + friendId, quiet).apply();
    }
    public boolean getFriendQuiet(int friendId) {
        return prefs.getBoolean("friend_quiet_" + friendId, false);
    }

    // ===== 会话列表 dirty 标记 =====
    // 为什么落在 :core：写方是 :feature-chat（ChatActivity 的面板回调），读方是 :app（MainActivity），
    // 而 :feature-chat 看不见 :app（见 Routes 的跨模块注释），标记必须放在两边都可见的 :core。
    // 静态 volatile = 进程内有效：Activity 重建不影响；进程被杀后 MainActivity.onCreate 本来就会拉一次列表。

    private static volatile boolean conversationsDirty;

    /** 会话置顶/免打扰被改动：回主列表时绕过 onResume 的 5s 防抖。 */
    public void markConversationsDirty() {
        conversationsDirty = true;
    }

    /** 读后即清（只绕过一次防抖）。 */
    public boolean consumeConversationsDirty() {
        if (!conversationsDirty) return false;
        conversationsDirty = false;
        return true;
    }

    /** 群免打扰缓存（新消息是否弹通知）。 */
    public void setGroupQuiet(int groupId, boolean quiet) {
        prefs.edit().putBoolean("group_quiet_" + groupId, quiet).apply();
    }
    public boolean getGroupQuiet(int groupId) {
        return prefs.getBoolean("group_quiet_" + groupId, false);
    }

    // ===== E2EE Key Storage =====

    public void saveKeyPair(String publicKey, String privateKey) {
        prefs.edit().putString("e2ee_pub_key", publicKey)
                    .putString("e2ee_priv_key", privateKey).apply();
        // 同步到账号列表，保证切换账号后 E2EE 密钥正确
        updateAccountE2ee(getUserId(), publicKey, privateKey);
    }

    public String getMyPublicKey() {
        return prefs.getString("e2ee_pub_key", null);
    }

    public String getMyPrivateKey() {
        return prefs.getString("e2ee_priv_key", null);
    }

    /** Cache peer's public key locally. */
    public void savePeerPublicKey(int userId, String publicKey) {
        prefs.edit().putString("peer_pub_" + userId, publicKey).apply();
    }

    public String getPeerPublicKey(int userId) {
        return prefs.getString("peer_pub_" + userId, null);
    }

    // ===== E2EE TOFU pin（对端公钥固定，docs/audits/E2EE_SECURITY_AUDIT §1.1/§1.2）=====
    //
    // ⚠️ 前缀刻意用 peer_pin_ 而不是 peer_pub_：
    //    logout() 会遍历删除所有 peer_pub_*（见本文件后面那段 key.startsWith("peer_pub_")），
    //    而 pin 必须活过退出登录 —— 否则重新登录后每个对端都会被当成"首次见到"，
    //    TOFU 的威胁模型会被静默摧毁。peer_pin_* 不以 "peer_pub_" 开头，天然不在清理范围。
    // ⚠️ 与 peer_pub_* 一样不按"我的账号"分区（决策 3）。
    //
    // 值格式："<64位hex指纹>|<firstSeenAt>|<0|1>"，见 PeerKeyPinStore.PinRecord。

    private static final String KEY_PEER_PIN_PREFIX = "peer_pin_";

    /** 写入/覆盖某对端的 pin 记录（只有 PeerKeyPinStore 应当调用）。 */
    public void savePeerPin(int peerUid, String fingerprint, long firstSeenAt, boolean userConfirmed) {
        if (fingerprint == null || fingerprint.isEmpty()) return; // 绝不落一条空指纹
        prefs.edit().putString(KEY_PEER_PIN_PREFIX + peerUid,
                fingerprint + "|" + firstSeenAt + "|" + (userConfirmed ? "1" : "0")).apply();
    }

    /** 读取某对端的 pin 记录；没有或格式损坏时返回 null（当作"无 pin"，即首次信任）。 */
    public PeerKeyPinStore.PinRecord getPeerPin(int peerUid) {
        String raw = prefs.getString(KEY_PEER_PIN_PREFIX + peerUid, null);
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|");
        if (parts.length != 3 || parts[0].isEmpty()) return null;
        try {
            return new PeerKeyPinStore.PinRecord(parts[0], Long.parseLong(parts[1]), "1".equals(parts[2]));
        } catch (NumberFormatException e) {
            Log.w(TAG, "corrupt peer pin record, peer=" + peerUid);
            return null;
        }
    }

    public void removePeerPin(int peerUid) {
        prefs.edit().remove(KEY_PEER_PIN_PREFIX + peerUid).apply();
    }

    // ===== 支付密码（本地只存 SHA-256(PIN)，永不落明文）=====

    /** 存支付密码哈希（客户端 SHA-256(6位PIN)），仅用于发红包/转账/扫码时提交校验。 */
    public void setPayHash(String payHash) {
        prefs.edit().putString("pay_hash", payHash).apply();
    }

    /** 是否已设置支付密码（本地有哈希 = 已设置）。 */
    public boolean hasPayHash() {
        String h = prefs.getString("pay_hash", null);
        return h != null && !h.isEmpty();
    }

    public String getPayHash() {
        return prefs.getString("pay_hash", null);
    }

    public void logout() {
        // 保留服务器地址与账号列表（设备级配置），清空其余登录态，便于重新登录/切换账号。
        // 注意：不要用 prefs.edit().clear()——EncryptedSharedPreferences 的 clear()
        // 在部分设备/版本上会抛异常或留下残留，导致 token 未被清除，
        // 退出后登录页走自动登录路径又弹回主界面（表现为"退不出去"）。
        String server = prefs.getString("server", "");
        String accounts = prefs.getString(KEY_ACCOUNTS, "[]");
        boolean night = prefs.getBoolean("night_mode", false);
        String engine = prefs.getString("search_engine", "bing");

        SharedPreferences.Editor ed = prefs.edit()
                .remove("token")
                .remove("user_id")
                .remove("username")
                .remove("my_nickname")
                .remove("my_avatar")
                .remove("my_avatar_color")
                .remove("e2ee_pub_key")
                .remove("e2ee_priv_key")
                .remove("pay_hash");
        // 清掉好友公钥缓存、账号头像缓存与只读列表缓存（key 是动态的，逐个删）
        try {
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("peer_pub_") || key.startsWith("avatar_") || key.startsWith("ro_cache_")) ed.remove(key);
            }
        } catch (Exception ignored) {}
        ed.apply();

        // 恢复设备级配置（server / 账号列表 / 黑夜模式 / 搜索引擎）
        if (!server.isEmpty()) setServer(server);
        prefs.edit().putString(KEY_ACCOUNTS, accounts).apply();
        if (night) setNightMode(true);
        prefs.edit().putString("search_engine", engine).apply();

        // 登出时清通话状态机（reset() 全仓此前 0 调用点，导致"响铃中登出"
        // 会让 current 停在 INCOMING_RINGING，之后来电全被回 call_busy，
        // 直到 45s 定时器兜底）。注意：reset() 不发 onCallEnded，因此铃声/
        // 来电通知/引擎释放仍依赖各自的兜底路径 —— 本批只消除"current 永为
        // 响铃态"，不扩 reset 语义。
        com.familychat.app.api.call.CallManager.reset();
    }

    /** 黑夜模式开关（设备级配置）。 */
    public void setNightMode(boolean night) {
        prefs.edit().putBoolean("night_mode", night).apply();
    }

    public boolean getNightMode() {
        return prefs.getBoolean("night_mode", false);
    }

    // ===== 多账号管理 =====

    public static class Account {
        // ⚠️ @SerializedName 的值必须与「已保存到 SharedPreferences 的 JSON 键」一致，
        // 否则升级后会读不出旧账号（token / E2EE 私钥丢失 = 要重新登录且历史消息解不开）。
        // 这些键名就是原字段名，改注解 = 清空所有用户的多账号列表，切勿随意修改。
        @com.google.gson.annotations.SerializedName("userId") public int userId;
        @com.google.gson.annotations.SerializedName("username") public String username;
        @com.google.gson.annotations.SerializedName("nickname") public String nickname;
        @com.google.gson.annotations.SerializedName("avatar") public String avatar;
        @com.google.gson.annotations.SerializedName("avatarColor") public String avatarColor;
        @com.google.gson.annotations.SerializedName("wxId") public String wxId;   // 密语号式专属ID
        @com.google.gson.annotations.SerializedName("token") public String token;
        @com.google.gson.annotations.SerializedName("e2eePub") public String e2eePub;
        @com.google.gson.annotations.SerializedName("e2eePriv") public String e2eePriv;
        @com.google.gson.annotations.SerializedName("server") public String server;
        public Account() {}
    }

    private static final Gson ACCOUNT_GSON = new Gson();
    private static final String KEY_ACCOUNTS = "accounts";

    /** 读取已保存的账号列表。 */
    public List<Account> getAccounts() {
        try {
            String json = prefs.getString(KEY_ACCOUNTS, "[]");
            List<Account> list = ACCOUNT_GSON.fromJson(json,
                    new TypeToken<List<Account>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            Log.w(TAG, "getAccounts failed", e);
            return new ArrayList<>();
        }
    }

    /** 保存/更新一个账号（按 userId 去重）。 */
    public void saveAccount(Account acc) {
        if (acc == null || acc.userId <= 0) return;
        List<Account> list = getAccounts();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).userId == acc.userId) { list.set(i, acc); found = true; break; }
        }
        if (!found) list.add(acc);
        prefs.edit().putString(KEY_ACCOUNTS, ACCOUNT_GSON.toJson(list)).apply();
    }

    /** 登录/注册成功后记录账号。 */
    public void saveLoginAccount(int userId, String username, String nickname,
                                 String avatar, String avatarColor, String token, String wxId) {
        // E2EE 密钥保护：登录时若当前全局密钥为空（例如刚退出登录被 logout() 清空），
        // 绝不能把账号快照里保存的旧密钥覆盖成空——否则会生成一把新钥匙，
        // 以前用旧钥匙加密的聊天记录将永远无法解密。
        // 优先从旧快照恢复旧密钥到全局活跃态，并沿用旧钥匙。
        String myPub = getMyPublicKey();
        String myPriv = getMyPrivateKey();
        boolean keysMissing = myPub == null || myPub.isEmpty() || myPriv == null || myPriv.isEmpty();
        if (keysMissing) {
            Account existing = findAccount(userId);
            if (existing != null && existing.e2eePub != null && !existing.e2eePub.isEmpty()
                    && existing.e2eePriv != null && !existing.e2eePriv.isEmpty()) {
                myPub = existing.e2eePub;
                myPriv = existing.e2eePriv;
                // 恢复全局活跃态（getUserId() 此刻已设为该账号），ensureLoginKeys 即可沿用旧钥匙
                saveKeyPair(existing.e2eePub, existing.e2eePriv);
            }
        }
        Account acc = new Account();
        acc.userId = userId;
        acc.username = username != null ? username : "";
        acc.nickname = nickname != null ? nickname : "";
        acc.avatar = avatar != null ? avatar : "😊";
        acc.avatarColor = avatarColor != null ? avatarColor : "#e8f5e9";
        acc.wxId = wxId != null ? wxId : "";
        acc.token = token;
        acc.server = getServer();
        acc.e2eePub = myPub;
        acc.e2eePriv = myPriv;
        prefs.edit().putString("username", acc.username)
                    .putString("avatar_" + userId, acc.avatar)  // 账号头像 URL（key: avatar_用户ID）
                    .putString("my_wx_id", acc.wxId)
                    .apply();
        saveAccount(acc);
    }

    /** 把当前活跃账号的快照同步回账号列表（资料/E2EE 变更后调用）。 */
    public void saveCurrentAccount() {
        if (getUserId() <= 0) return;
        String myPub = getMyPublicKey();
        String myPriv = getMyPrivateKey();
        // 防御：全局密钥意外为空时，保留账号快照里的旧密钥，避免覆盖成空丢钥匙
        if (myPub == null || myPub.isEmpty() || myPriv == null || myPriv.isEmpty()) {
            Account existing = findAccount(getUserId());
            if (existing != null && existing.e2eePub != null && !existing.e2eePub.isEmpty()
                    && existing.e2eePriv != null && !existing.e2eePriv.isEmpty()) {
                if (myPub == null || myPub.isEmpty()) myPub = existing.e2eePub;
                if (myPriv == null || myPriv.isEmpty()) myPriv = existing.e2eePriv;
            }
        }
        Account acc = new Account();
        acc.userId = getUserId();
        acc.username = prefs.getString("username", "");
        acc.nickname = getMyNickname();
        acc.avatar = getMyAvatar();
        acc.avatarColor = getMyAvatarColor();
        acc.wxId = getMyWxId();
        acc.token = getToken();
        acc.e2eePub = myPub;
        acc.e2eePriv = myPriv;
        acc.server = getServer();
        saveAccount(acc);
    }

    /** 切换到指定账号：把该账号的 token/uid/资料/e2ee 载入活跃态。 */
    public void switchAccount(Account acc) {
        if (acc == null) return;
        setToken(acc.token);
        setUserId(acc.userId);
        SharedPreferences.Editor ed = prefs.edit()
                .putString("username", acc.username != null ? acc.username : "")
                .putString("my_nickname", acc.nickname != null ? acc.nickname : "")
                .putString("my_avatar", acc.avatar != null ? acc.avatar : "😊")
                .putString("my_avatar_color", acc.avatarColor != null ? acc.avatarColor : "#e8f5e9");
        // 仅当账号有有效密钥时才恢复；否则保持缺失，交给登录/打开聊天时补生成。
        // 不要写入空字符串 ""——否则 getMyPrivateKey()==null 判断会失效。
        if (acc.e2eePub != null && !acc.e2eePub.isEmpty())
            ed.putString("e2ee_pub_key", acc.e2eePub);
        if (acc.e2eePriv != null && !acc.e2eePriv.isEmpty())
            ed.putString("e2ee_priv_key", acc.e2eePriv);
        // wx_id 属于账号资料：快照有值才恢复（旧快照为空时留空，由进主界面 loadProfile 自愈回填）
        if (acc.wxId != null && !acc.wxId.isEmpty())
            ed.putString("my_wx_id", acc.wxId);
        ed.apply();
        if (acc.server != null && !acc.server.isEmpty()) setServer(acc.server);
    }

    /** 按 userId 查找账号快照（供登录时恢复 E2EE 密钥）。 */
    public Account findAccount(int userId) {
        for (Account a : getAccounts()) {
            if (a.userId == userId) return a;
        }
        return null;
    }

    /** 从本地删除某账号（仅移出账号列表与登录态，不清理其他账号数据）。 */
    public void removeAccount(int userId) {
        List<Account> list = getAccounts();
        list.removeIf(a -> a.userId == userId);
        prefs.edit().putString(KEY_ACCOUNTS, ACCOUNT_GSON.toJson(list)).apply();
    }

    /** E2EE 密钥生成后同步到账号列表。 */
    public void updateAccountE2ee(int userId, String pub, String priv) {
        List<Account> list = getAccounts();
        for (Account a : list) {
            if (a.userId == userId) { a.e2eePub = pub; a.e2eePriv = priv; break; }
        }
        prefs.edit().putString(KEY_ACCOUNTS, ACCOUNT_GSON.toJson(list)).apply();
    }
}
