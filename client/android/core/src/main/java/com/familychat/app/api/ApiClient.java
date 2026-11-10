package com.familychat.app.api;

import android.util.Log;

import com.familychat.app.api.Models.*;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.ServerProtocol;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final Gson gson = new Gson();
    // 共享 client 由 HttpClientProvider 统一持有：它还负责给 OkHttp 装上
    // 假域名模式的自定义 Dns（miyu.lan → 用户配置的 IP）。此处不再自建实例。
    //
    // 仍然刻意**不用 static final 字段缓存**它，但理由已经变了：过去是因为
    // client 会把"建实例那一刻"的 Dns 固化下来（早期类加载时 prefs 还没读到 →
    // 整个进程都用系统 DNS，miyu.lan 永远解析不出来）。现在 Dns 每次 lookup 都
    // 现取当前地址（ServerProtocol.fakeHostDns），旧快照也不会再被锁死在旧 IP 上。
    // 依然不缓存的实际理由：连接池与超时配置不宜在类加载早期就定型，而且地址变更
    // 后 refresh() 重建的是 Provider 的缓存，留个静态副本只会让"当前 client 是哪份"
    // 变得含糊。每次经 getSharedClient() 动态取即可（Provider 内部仍有双重检查缓存，
    // 所以这里不是每调用一次就重建 client）。

    /**
     * 共享的 OkHttpClient（进程级唯一，自带 Dispatcher 线程池与连接池）。
     *
     * <p><b>任何地方都不要再自己 {@code new OkHttpClient()}。</b>
     * 每个 OkHttpClient 实例都自带一套 Dispatcher 线程池 + ConnectionPool
     * （空闲连接默认 60s 才回收）。在周期性调用点新建实例会造成线程与 socket 无界增长
     * —— 历史 bug：ServerInfoActivity 每 3 秒轮询一次就 new 一个，停留在该页面时持续泄漏。
     *
     * <p>需要更短的超时，请用<b>单次调用覆盖</b>，而不要为此新建 client：
     * <pre>
     *   okhttp3.Call call = ApiClient.getSharedClient().newCall(req);
     *   call.timeout().timeout(4, java.util.concurrent.TimeUnit.SECONDS);
     *   okhttp3.Response resp = call.execute();
     * </pre>
     * {@code call.timeout()} 覆盖的是本次调用的全部阶段（连接 / 写入 / 读取）超时，
     * 不会影响其它调用方，也不需要第二个 client。
     *
     * <p>每次调用都向 {@link HttpClientProvider} 取当前实例（内部有缓存，不是重建）：
     * 地址变更后 {@link HttpClientProvider#refresh()} 换掉的是这份缓存，本类不留自己的副本，
     * 所以连接池与超时配置永远跟着 Provider 的最新一份走。（域名解析本身已与实例解耦，
     * 见 {@code ServerProtocol.fakeHostDns}，不再依赖"重建 client"才能生效。）
     */
    public static OkHttpClient getSharedClient() {
        return HttpClientProvider.shared();
    }

    /** Callback for auth failures (token expired/invalid) */
    public interface AuthFailureListener {
        void onAuthFailure();
    }
    private static AuthFailureListener authListener;

    /**
     * 注册全局 auth 失败监听器（401 自动登出）。
     *
     * 只应由 {@code FamilyChatApp.onCreate()} 注册一次，且监听器必须是 application 级的
     * ——不得捕获任何 Activity。本字段是 static、与进程同生命周期，若监听器持有
     * Activity 引用就会造成内存泄漏。
     *
     * 本类刻意不提供"取消注册"的方法：任何 Activity 都不得清除该监听器。一旦被清除，
     * 其它页面收到 401 就不再自动登出（历史 bug：CallActivity 在 onDestroy 中清除它，
     * 导致通话结束后全局自动登出失效）。
     */
    public static void setAuthFailureListener(AuthFailureListener listener) {
        authListener = listener;
    }

    private String server;
    private String token;

    public ApiClient(String server, String token) {
        this.server = server;
        this.token = token;
    }

    /**
     * URL 编码查询参数。
     * 用 String 版 encode(值, "UTF-8")（API 1 即有）而非 StandardCharsets 重载
     * （需 API 33，低版本设备会抛 NoSuchMethodError 崩溃）。"UTF-8" 必然存在，捕获即可。
     */
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    public ApiClient(String server) {
        this(server, null);
    }

    public void setToken(String token) { this.token = token; }
    public void setServer(String server) { this.server = server; }
    public String getServer() { return server; }

    // 注：原有 getWsUrl() { wsOf(server) + server } 已删除。
    // 它是全仓零调用的死代码，且返回的是**不带 path/query** 的半成品 URL，
    // 留着会诱导后来者拿它拼 WS 地址（协议前缀与主机名替换规则都收在
    // ServerProtocol.wsBaseUrl()，唯一入口见 WebSocketManager.doConnect()）。

    private String url(String path) {
        // 统一走 ServerProtocol.baseUrl()：IPv4 服务器会被改写成
        // https://miyu.lan:<port>（假域名模式），域名服务器保持原逻辑。
        return ServerProtocol.baseUrl(server) + path;
    }

    /** Like doRequest but returns raw response string. */
    private String doRequestRaw(String method, String path) throws ApiException {
        Request.Builder rb = new Request.Builder().url(url(path));
        if (token != null) rb.addHeader("Authorization", "Bearer " + token);
        rb.method(method, null);
        try (Response resp = getSharedClient().newCall(rb.build()).execute()) {
            return resp.body() != null ? resp.body().string() : "";
        } catch (IOException e) {
            throw new ApiException("网络错误: " + e.getMessage());
        }
    }

    private <T> T doRequest(String method, String path, Object body, Class<T> cls) throws ApiException {
        // 默认：401 视为"登录态失效"，触发全局自动登出。除 login/register 外的所有接口都用这个重载。
        return doRequest(method, path, body, cls, true);
    }

    /**
     * @param notifyAuthFailure 401 时是否触发全局 authListener（自动登出）。
     *        仅 login / register 传 false：这两个接口的 401 含义是"用户名或密码错误"，
     *        不是"登录态失效"。若照样触发自动登出，每次输错密码都会重建登录页
     *        （清空输入框、重跑局域网扫描，且错误 Toast 因实例已销毁可能看不到）。
     *        其它接口行为完全不变。
     */
    private <T> T doRequest(String method, String path, Object body, Class<T> cls,
                            boolean notifyAuthFailure) throws ApiException {
        try {
            return doRequestInternal(method, path, body, cls, notifyAuthFailure);
        } catch (ApiException e) {
            // 所有接口失败都写入应用内日志（设置→日志 可见，方便排查"加载失败"）
            com.familychat.app.utils.AppLog.e("API", method + " " + path + " 失败: " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // 如 Gson 解析失败等，也记录下来（调用方通常吞掉只弹 toast）
            com.familychat.app.utils.AppLog.e("API", method + " " + path + " 解析/执行异常: " + e);
            throw e;
        }
    }

    private <T> T doRequestInternal(String method, String path, Object body, Class<T> cls,
                                    boolean notifyAuthFailure) throws ApiException {
        Request.Builder rb = new Request.Builder().url(url(path));
        if (token != null) rb.addHeader("Authorization", "Bearer " + token);

        if (body != null) {
            String json = gson.toJson(body);
            rb.method(method, RequestBody.create(json, MediaType.get("application/json; charset=utf-8")));
        } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            // OkHttp 规定 POST/PUT/PATCH 不能传 null body（会抛 IllegalArgumentException，请求发不出去）。
            // 无参操作发空 JSON 对象 {}；对应服务端路由不读 body（转账 accept/refund、收款请求 decline 等）。
            rb.method(method, RequestBody.create("{}", MediaType.get("application/json; charset=utf-8")));
        } else {
            rb.method(method, null);
        }

        try (Response resp = getSharedClient().newCall(rb.build()).execute()) {
            int statusCode = resp.code();
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                // Auto-logout on 401 (token expired or invalid).
                // notifyAuthFailure=false 用于 login/register：其 401 是"账号或密码错误"，
                // 不表示登录态失效，必须跳过回调，否则会误触发全局登出并重建登录页。
                if (statusCode == 401 && notifyAuthFailure && authListener != null) {
                    authListener.onAuthFailure();
                }
                try {
                    AuthResponse err = gson.fromJson(respBody, AuthResponse.class);
                    String errorMsg = (err != null && err.error != null) ? err.error
                            : (err != null && err.message != null) ? err.message
                            : "服务器错误: " + statusCode;
                    throw new ApiException(errorMsg, statusCode);
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ApiException("服务器返回错误: " + statusCode, statusCode);
                }
            }
            return gson.fromJson(respBody, cls);
        } catch (java.net.ConnectException e) {
            throw new ApiException("无法连接(" + server + ")，请检查地址或防火墙");
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiException("连接超时，请检查网络");
        } catch (IOException e) {
            throw new ApiException("网络错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    // ===== Auth =====
    public AuthResponse login(String username, String password) throws ApiException {
        if (username == null || username.trim().isEmpty()) {
            throw new ApiException("用户名不能为空");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new ApiException("密码不能为空");
        }
        AuthBody body = new AuthBody(); body.username = username.trim(); body.password = password;
        // 传 false：密码错误返回的 401 不触发全局自动登出（详见 doRequest 的注释）
        return doRequest("POST", "/api/login", body, AuthResponse.class, false);
    }

    public AuthResponse register(String username, String password, String nickname) throws ApiException {
        if (username == null || username.trim().isEmpty()) {
            throw new ApiException("用户名不能为空");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new ApiException("密码不能为空");
        }
        RegBody body = new RegBody(); body.username = username.trim(); body.password = password; body.nickname = nickname;
        // 传 false：注册接口的 401/失败同样不触发全局自动登出（当前服务端只返回 400/409，此处为一致性防御）
        return doRequest("POST", "/api/register", body, AuthResponse.class, false);
    }

    // ===== Profile =====
    public ProfileResponse getProfile() throws ApiException {
        return doRequest("GET", "/api/user/profile", null, ProfileResponse.class);
    }

    public ProfileResponse updateProfile(String nickname, String avatar, String avatarColor) throws ApiException {
        ProfileUpdateBody body = new ProfileUpdateBody();
        body.nickname = nickname; body.avatar = avatar; body.avatar_color = avatarColor;
        return doRequest("PUT", "/api/user/profile", body, ProfileResponse.class);
    }

    public ProfileResponse uploadAvatar(byte[] imageBytes, String fileName) throws ApiException {
        RequestBody fileBody = RequestBody.create(imageBytes, MediaType.parse("image/*"));
        MultipartBody mBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build();

        Request.Builder rb = new Request.Builder()
                .url(url("/api/user/avatar"))
                .post(mBody);
        if (token != null) rb.addHeader("Authorization", "Bearer " + token);

        try (Response resp = getSharedClient().newCall(rb.build()).execute()) {
            int statusCode = resp.code();
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                try {
                    AuthResponse err = gson.fromJson(respBody, AuthResponse.class);
                    String errorMsg = (err != null && err.error != null) ? err.error : "上传失败 (" + statusCode + ")";
                    throw new ApiException(errorMsg, statusCode);
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ApiException("上传失败 (" + statusCode + ")", statusCode);
                }
            }
            return gson.fromJson(respBody, ProfileResponse.class);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("上传失败: " + e.getMessage());
        }
    }

    // ===== E2EE Public Key =====

    /**
     * 通用文件上传（/api/chat/upload）。返回服务端相对路径 URL（如 /chat_files/xxx），失败抛异常。
     * 供群头像、群文件库、群相册等复用。
     */
    public String uploadFile(byte[] bytes, String fileName, String mime) throws ApiException {
        RequestBody fileBody = RequestBody.create(bytes, MediaType.parse(mime != null ? mime : "application/octet-stream"));
        MultipartBody mBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build();

        Request.Builder rb = new Request.Builder()
                .url(url("/api/chat/upload"))
                .post(mBody);
        if (token != null) rb.addHeader("Authorization", "Bearer " + token);

        try (Response resp = getSharedClient().newCall(rb.build()).execute()) {
            int statusCode = resp.code();
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                try {
                    AuthResponse err = gson.fromJson(respBody, AuthResponse.class);
                    String errorMsg = (err != null && err.error != null) ? err.error : "上传失败 (" + statusCode + ")";
                    throw new ApiException(errorMsg, statusCode);
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ApiException("上传失败 (" + statusCode + ")", statusCode);
                }
            }
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(respBody).getAsJsonObject();
                if (obj.has("url")) return obj.get("url").getAsString();
            } catch (Exception ignored) {}
            throw new ApiException("上传失败：响应缺少 url");
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("上传失败: " + e.getMessage());
        }
    }

    /** Upload own public key to server. */
    public boolean uploadPublicKey(String publicKey) {
        try {
            doRequest("POST", "/api/user/publickey",
                    java.util.Map.of("public_key", publicKey), Object.class);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "uploadPublicKey", e);
            return false;
        }
    }

    /** Get a user's public key by their userId. */
    public String getPublicKey(int userId) {
        try {
            String resp = doRequestRaw("GET", "/api/user/publickey?userId=" + userId);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
            if (json.has("public_key")) return json.get("public_key").getAsString();
        } catch (Exception e) {
            Log.e(TAG, "getPublicKey", e);
        }
        return null;
    }

    /** Upload password-encrypted private key backup (for reinstall recovery). */
    public boolean uploadKeyBackup(String backupB64) {
        try {
            doRequest("PUT", "/api/user/keybackup",
                    java.util.Map.of("backup_b64", backupB64), Object.class);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "uploadKeyBackup", e);
            return false;
        }
    }

    /**
     * 查询服务端「密码加密密钥备份」的状态（H2 修复的核心接口）。
     *
     * 与旧接口的关键区别：**失败不再是返回值，而是 checked 异常**。
     *   · 返回非 null → 一定成功问到了服务端（`exists` 区分"有备份/确认没有"）
     *   · 抛 ApiException → 网络 / HTTP 非 2xx / 响应解析失败，**无法确认现状**
     *
     * 为什么必须这样：调用方（重置/导入/登录）在"无法确认现状"时若继续上传，
     * 可能用错误密码加密的新备份覆盖掉唯一可恢复的旧备份，且用户当场发现不了。
     * 旧实现 `fetchKeyBackup()` 把这两种情况都返回 null，导致
     * `KeyResetActivity` 的 catch 成了死代码（可用 grep 验证已改为本方法）。
     *
     * 报文解析逻辑在纯 JVM 的 {@link com.familychat.app.utils.KeyBackupResponse} 里，
     * 由 `KeyBackupResponseTest` 覆盖；本方法只负责网络那一段。
     */
    public com.familychat.app.utils.KeyBackupResponse.State fetchKeyBackupState()
            throws ApiException {
        String resp = doRequestRaw("GET", "/api/user/keybackup");
        try {
            return com.familychat.app.utils.KeyBackupResponse.parse(200, resp);
        } catch (com.familychat.app.utils.KeyBackupResponse.ParseException e) {
            // 响应到了但内容不可用 —— 与网络失败同样是"无法确认"，一律当失败处理。
            throw new ApiException("密钥备份状态无法确认: " + e.getMessage());
        }
    }

    // 注：旧的 `fetchKeyBackup()`（返回 String/null，把「确认无备份」与「拉取失败」混成
    //     同一个 null）已在 H2 修复中**整体删除**，而不是保留 @Deprecated ——
    //     实测全项目已无调用点，而留着它等于留着"把失败当无备份"的入口，
    //     任何人将来顺手用回它就会复发同一个缺陷。
    //     需要取备份请用上面的 fetchKeyBackupState()，并显式处理"无法确认现状"。

    // ===== Friends =====
    /** 按 id 获取单个用户（扫码/资料页用）。 */
    public ProfileResponse getUserById(int userId) throws ApiException {
        return doRequest("GET", "/api/users/" + userId, null, ProfileResponse.class);
    }

    public UserListResponse searchUsers(String query) throws ApiException {
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            return doRequest("GET", "/api/users/search?q=" + encodedQuery, null, UserListResponse.class);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("搜索用户失败: " + e.getMessage());
        }
    }

    public FriendRequestResult sendFriendRequest(int friendId) throws ApiException {
        FriendReqBody body = new FriendReqBody(); body.friend_id = friendId;
        return doRequest("POST", "/api/friends/request", body, FriendRequestResult.class);
    }

    public FriendRequestsResponse getPendingRequests() throws ApiException {
        return doRequest("GET", "/api/friends/requests", null, FriendRequestsResponse.class);
    }

    public FriendRequestsResponse getSentRequests() throws ApiException {
        return doRequest("GET", "/api/friends/requests/sent", null, FriendRequestsResponse.class);
    }

    public StatusResponse handleFriendRequest(int friendshipId, String action) throws ApiException {
        return doRequest("POST", "/api/friends/request/" + friendshipId + "/" + action,
                new Object(), StatusResponse.class);
    }

    public FriendsResponse getFriends() throws ApiException {
        return doRequest("GET", "/api/friends", null, FriendsResponse.class);
    }

    // ===== Messages =====
    public MessagesResponse getMessages(int friendId, int limit) throws ApiException {
        return doRequest("GET", "/api/messages/" + friendId + "?limit=" + limit, null, MessagesResponse.class);
    }

    public MarkReadResponse markRead(int senderId) throws ApiException {
        return doRequest("POST", "/api/messages/read/" + senderId, new Object(), MarkReadResponse.class);
    }

    public StatusResponse deleteMessage(int msgId) throws ApiException {
        return doRequest("DELETE", "/api/messages/" + msgId, null, StatusResponse.class);
    }

    public StatusResponse recallMessage(int msgId) throws ApiException {
        return doRequest("POST", "/api/messages/" + msgId + "/recall", new Object(), StatusResponse.class);
    }

    public StatusResponse clearConversation(int friendId) throws ApiException {
        return doRequest("DELETE", "/api/messages/clear/" + friendId, null, StatusResponse.class);
    }

    /** 上报通话状态，生成一条系统消息写入会话。
     *  event: "rejected"(被拒绝) / "ended"(已结束，带时长) / "missed"(无人接听) / "canceled"(已取消)。
     *  video: 服务端据此把 msg_type 落成 call_video / call_voice，客户端据此渲染图标并决定回拨类型。 */
    public StatusResponse reportCallStatus(int friendId, String event, int durationSec, boolean video) throws ApiException {
        CallStatusBody body = new CallStatusBody();
        body.friend_id = friendId;
        body.event = event;
        body.duration_sec = durationSec;
        body.video = video;
        return doRequest("POST", "/api/messages/call-status", body, StatusResponse.class);
    }

    public StatusResponse deleteFriend(int friendId) throws ApiException {
        // First clear messages, then delete friendship
        clearConversation(friendId);
        return doRequest("DELETE", "/api/friends/" + friendId, null, StatusResponse.class);
    }

    /** 设置好友备注（服务器保存）。 */
    public StatusResponse setFriendRemark(int friendId, String remark) throws ApiException {
        RemarkBody body = new RemarkBody();
        body.friend_id = friendId;
        body.remark = remark != null ? remark : "";
        return doRequest("POST", "/api/friend/remark", body, StatusResponse.class);
    }

    /** 服务端搜索会话消息（仅未加密/可搜索字段）。 */
    public MessagesResponse searchMessages(int friendId, String keyword, int page, int pageSize) throws ApiException {
        String path = "/api/messages/search?friend_id=" + friendId
                + "&keyword=" + urlEncode(keyword)
                + "&page=" + page + "&page_size=" + pageSize;
        return doRequest("GET", path, null, MessagesResponse.class);
    }

    /** 修改密码（旧密码 + 新密码）。 */
    public AuthResponse changePassword(String oldPassword, String newPassword) throws ApiException {
        ChangePwdBody body = new ChangePwdBody();
        body.old_password = oldPassword;
        body.new_password = newPassword;
        return doRequest("POST", "/api/user/change-password", body, AuthResponse.class);
    }

    // ===== Groups =====

    /** 建群：name + member_ids[]，服务端自动把创建者加进去。 */
    public Models.Group createGroup(String name, List<Integer> memberIds) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name != null ? name : "群聊");
        body.put("member_ids", memberIds != null ? memberIds : new ArrayList<>());
        Object resp = doRequest("POST", "/api/groups", body, Object.class);
        // resp 是 {success, group:{...}}，用 gson 二次解出 group
        com.google.gson.JsonObject obj = com.google.gson.JsonParser
                .parseString(new Gson().toJson(resp)).getAsJsonObject();
        if (obj.has("group")) {
            return gson.fromJson(obj.get("group"), Models.Group.class);
        }
        return null;
    }

    /** 我加入的群列表。 */
    public Models.GroupListResponse getMyGroups() throws ApiException {
        return doRequest("GET", "/api/groups", null, Models.GroupListResponse.class);
    }

    /** 群会话设置（置顶/免打扰）。 */
    public Models.GroupSettings getGroupSettings(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/settings", null, Models.GroupSettings.class);
    }

    /** 更新群会话设置（pinned/quiet 传 null 表示不改）。 */
    public Models.GroupSettings updateGroupSettings(int groupId, Integer pinned, Integer quiet) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (pinned != null) body.put("pinned", pinned);
        if (quiet != null) body.put("quiet", quiet);
        return doRequest("PUT", "/api/groups/" + groupId + "/settings", body, Models.GroupSettings.class);
    }

    /** 群详情（群信息 + 成员列表）。 */
    public Models.GroupDetailResponse getGroupDetail(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId, null, Models.GroupDetailResponse.class);
    }

    /** 拉人进群。 */
    public StatusResponse addGroupMember(int groupId, int userId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/members",
                java.util.Map.of("user_id", userId), StatusResponse.class);
    }

    /** 移除成员/退群。 */
    public StatusResponse removeGroupMember(int groupId, int userId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/members/" + userId,
                null, StatusResponse.class);
    }

    /** 发群消息。content 为"每人一份密文"的 JSON，由客户端 GroupEncryptHelper 生成。 */
    public Models.GroupMessage sendGroupMessage(int groupId, String content, String msgType,
                                                String filename, String thumbnail, int durationSec) throws ApiException {
        return sendGroupMessage(groupId, content, msgType, filename, thumbnail, durationSec, null);
    }

    /**
     * 发群消息（带 @提及）。
     * @param mentions "all"=@全员；List&lt;Integer&gt;=@指定成员；null=不@。
     */
    public Models.GroupMessage sendGroupMessage(int groupId, String content, String msgType,
                                                String filename, String thumbnail, int durationSec,
                                                Object mentions) throws ApiException {
        return sendGroupMessage(groupId, content, msgType, filename, thumbnail, durationSec,
                mentions, 0, null);
    }

    /**
     * 发群消息（带 @提及 + 文件库索引参数）。
     * @param fileSize  文件字节数（>0 才传，服务端索引进群文件库）
     * @param fileFolder 自定义文件夹（null=按类型默认：聊天图片/聊天视频/聊天语音/群文件）
     */
    public Models.GroupMessage sendGroupMessage(int groupId, String content, String msgType,
                                                String filename, String thumbnail, int durationSec,
                                                Object mentions, long fileSize, String fileFolder) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("content", content);
        body.put("msg_type", msgType != null ? msgType : "text");
        if (filename != null) body.put("filename", filename);
        if (thumbnail != null) body.put("thumbnail", thumbnail);
        if (durationSec > 0) body.put("duration_sec", durationSec);
        if (mentions != null) body.put("mentions", mentions);
        if (fileSize > 0) body.put("file_size", fileSize);
        if (fileFolder != null) body.put("file_folder", fileFolder);
        Object resp = doRequest("POST", "/api/groups/" + groupId + "/messages", body, Object.class);
        com.google.gson.JsonObject obj = com.google.gson.JsonParser
                .parseString(new Gson().toJson(resp)).getAsJsonObject();
        if (obj.has("message")) {
            return gson.fromJson(obj.get("message"), Models.GroupMessage.class);
        }
        return null;
    }

    /** 拉群历史。 */
    public Models.GroupMessagesResponse getGroupMessages(int groupId, int limit) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/messages?limit=" + limit,
                null, Models.GroupMessagesResponse.class);
    }

    /** 标记已读到某条群消息。 */
    public StatusResponse markGroupRead(int groupId, int lastMsgId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/read",
                java.util.Map.of("last_msg_id", lastMsgId), StatusResponse.class);
    }

    /** 撤回群消息。 */
    public StatusResponse recallGroupMessage(int groupId, int msgId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/messages/" + msgId + "/recall",
                new Object(), StatusResponse.class);
    }

    // ---- 阶段1：群权限/群资料 ----

    /** 改群名/群头像（仅群主）。只传要改的字段，null 不改。 */
    public Models.Group updateGroupInfo(int groupId, String name, String avatar) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (name != null) body.put("name", name);
        if (avatar != null) body.put("avatar", avatar);
        Object resp = doRequest("PUT", "/api/groups/" + groupId, body, Object.class);
        com.google.gson.JsonObject obj = com.google.gson.JsonParser
                .parseString(new Gson().toJson(resp)).getAsJsonObject();
        if (obj.has("group")) return gson.fromJson(obj.get("group"), Models.Group.class);
        return null;
    }

    /** 设置我的群名片（群内昵称）。 */
    public StatusResponse setMyGroupNickname(int groupId, String nickname) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/nickname",
                java.util.Map.of("nickname", nickname == null ? "" : nickname), StatusResponse.class);
    }

    /** 任/免管理员（仅群主）。action = "appoint" | "revoke"。 */
    public StatusResponse appointAdmin(int groupId, int userId, String action) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/admin",
                java.util.Map.of("user_id", userId, "action", action), StatusResponse.class);
    }

    /** 禁言/解除禁言（群主/管理员）。untilMs: 0=解除，-1=永久，否则为到期时间戳(毫秒)。 */
    public StatusResponse setMemberMute(int groupId, int userId, long untilMs) throws ApiException {
        return doRequest("PUT", "/api/groups/" + groupId + "/members/" + userId + "/mute",
                java.util.Map.of("until_ms", untilMs), StatusResponse.class);
    }

    /** 转让群主（仅群主）。 */
    public StatusResponse transferGroup(int groupId, int userId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/transfer",
                java.util.Map.of("user_id", userId), StatusResponse.class);
    }

    /** 解散群（仅群主）。 */
    public StatusResponse dissolveGroup(int groupId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId, null, StatusResponse.class);
    }

    // ---- 阶段3：群公告 + 群内搜索 ----

    /** 发群公告（群主/管理员）。content 为 {成员id:密文} JSON。 */
    public Models.AnnouncementResponse createGroupAnnouncement(int groupId, String content, boolean pinned) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("content", content);
        if (pinned) body.put("pinned", 1);
        return doRequest("POST", "/api/groups/" + groupId + "/announcements", body, Models.AnnouncementResponse.class);
    }

    /** 公告列表（带 member_count）。 */
    public Models.AnnouncementListResponse getGroupAnnouncements(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/announcements", null, Models.AnnouncementListResponse.class);
    }

    /** 公告详情（含已读成员名单）。 */
    public Models.AnnouncementResponse getGroupAnnouncement(int groupId, int announcementId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/announcements/" + announcementId,
                null, Models.AnnouncementResponse.class);
    }

    /** 改公告/置顶（群主/管理员）。content/pinned 传 null 表示该项不变。 */
    public Models.AnnouncementResponse updateGroupAnnouncement(int groupId, int announcementId,
                                                               String content, Integer pinned) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (content != null) body.put("content", content);
        if (pinned != null) body.put("pinned", pinned);
        return doRequest("PUT", "/api/groups/" + groupId + "/announcements/" + announcementId,
                body, Models.AnnouncementResponse.class);
    }

    /** 删公告（群主/管理员）。 */
    public StatusResponse deleteGroupAnnouncement(int groupId, int announcementId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/announcements/" + announcementId,
                null, StatusResponse.class);
    }

    /** 标记公告已读（成员自己）。 */
    public StatusResponse markGroupAnnouncementRead(int groupId, int announcementId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/announcements/" + announcementId + "/read",
                new Object(), StatusResponse.class);
    }

    /** 群内搜索（服务端只搜明文：文件名/系统消息；E2EE 密文不可搜，需客户端本地解密过滤）。 */
    public Models.GroupMessagesResponse searchGroupMessages(int groupId, String keyword, int limit) throws ApiException {
        String path = "/api/groups/" + groupId + "/messages/search?keyword="
                + urlEncode(keyword)
                + "&limit=" + limit;
        return doRequest("GET", path, null, Models.GroupMessagesResponse.class);
    }

    // ═══════════════ 群文件库 / 群相册（阶段4）═══════════════

    /** 文件列表：?folder="全部"或分类名 &page=1 */
    public Models.GroupFilesResponse getGroupFiles(int groupId, String folder, int page) throws ApiException {
        String path = "/api/groups/" + groupId + "/files?folder="
                + urlEncode(folder == null ? "全部" : folder)
                + "&page=" + page;
        return doRequest("GET", path, null, Models.GroupFilesResponse.class);
    }

    /** 文件夹分类计数（chips）。 */
    public Models.FileFoldersResponse getGroupFileFolders(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/files/folders", null, Models.FileFoldersResponse.class);
    }

    /** 手动索引进文件库（上传成功后调用）。 */
    public Models.AddFileResponse addGroupFile(int groupId, String filename, String fileUrl,
                                               long sizeBytes, String folder) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("filename", filename);
        body.put("file_url", fileUrl);
        if (sizeBytes > 0) body.put("file_size", sizeBytes);
        if (folder != null) body.put("folder", folder);
        return doRequest("POST", "/api/groups/" + groupId + "/files", body, Models.AddFileResponse.class);
    }

    /** 删文件（上传者或群主/管理员）。 */
    public StatusResponse deleteGroupFile(int groupId, int fileId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/files/" + fileId, null, StatusResponse.class);
    }

    /** 相册列表（带 photo_count + cover_url）。 */
    public Models.GroupAlbumsResponse getGroupAlbums(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/albums", null, Models.GroupAlbumsResponse.class);
    }

    /** 建相册。 */
    public StatusResponse createGroupAlbum(int groupId, String name) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/albums",
                java.util.Map.of("name", name), StatusResponse.class);
    }

    /** 相册照片列表。 */
    public Models.AlbumPhotosResponse getGroupAlbumPhotos(int groupId, int albumId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/albums/" + albumId + "/photos",
                null, Models.AlbumPhotosResponse.class);
    }

    /** 批量传照片：{photo_urls:[...]}（先逐个 /api/chat/upload 再拼 URL 列表）。 */
    public Models.AddPhotosResponse addAlbumPhotos(int groupId, int albumId, java.util.List<String> photoUrls) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/albums/" + albumId + "/photos",
                java.util.Map.of("photo_urls", photoUrls), Models.AddPhotosResponse.class);
    }

    /** 删相册（创建者或群主/管理员）。 */
    public StatusResponse deleteGroupAlbum(int groupId, int albumId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/albums/" + albumId, null, StatusResponse.class);
    }

    /** 删照片（上传者或群主/管理员）。 */
    public StatusResponse deleteAlbumPhoto(int groupId, int albumId, int photoId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/albums/" + albumId + "/photos/" + photoId,
                null, StatusResponse.class);
    }

    // ===== 群投票 =====

    /** 投票列表（带选项计数 + my_voted）。 */
    public Models.PollsResponse getGroupPolls(int groupId) throws ApiException {
        return doRequest("GET", "/api/groups/" + groupId + "/polls", null, Models.PollsResponse.class);
    }

    /** 发起投票。 */
    public Models.PollResponse createGroupPoll(int groupId, String title, String description,
                                               List<String> options, boolean isMulti,
                                               boolean anonymous, long endAt) throws ApiException {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description == null ? "" : description);
        body.put("options", options);
        body.put("is_multi", isMulti);
        body.put("anonymous", anonymous);
        if (endAt > 0) body.put("end_at", endAt);
        return doRequest("POST", "/api/groups/" + groupId + "/polls", body, Models.PollResponse.class);
    }

    /** 投票/改票。 */
    public Models.PollResponse votePoll(int groupId, int pollId, List<Integer> optionIds) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/polls/" + pollId + "/vote",
                java.util.Map.of("option_ids", optionIds), Models.PollResponse.class);
    }

    /** 结束投票（创建者或群主/管理员）。 */
    public StatusResponse closePoll(int groupId, int pollId) throws ApiException {
        return doRequest("POST", "/api/groups/" + groupId + "/polls/" + pollId + "/close",
                java.util.Map.of(), StatusResponse.class);
    }

    /** 删除投票（创建者或群主/管理员）。 */
    public StatusResponse deletePoll(int groupId, int pollId) throws ApiException {
        return doRequest("DELETE", "/api/groups/" + groupId + "/polls/" + pollId, null, StatusResponse.class);
    }

    // ═══════════════ 支付系统（钱包/转账/红包/收付款码/账单）═══════════════

    /** 钱包信息：{balance(分), pay_set, nickname, avatar}。 */
    public Models.WalletInfo getWallet() throws ApiException {
        return doRequest("GET", "/api/wallet", null, Models.WalletInfo.class);
    }

    /** 首次设置支付密码（pinHash = 客户端 SHA-256(6位PIN)）。 */
    public Models.WalletResponse setPayPassword(String pinHash) throws ApiException {
        return doRequest("PUT", "/api/wallet/password",
                java.util.Map.of("pin_hash", pinHash), Models.WalletResponse.class);
    }

    /** 修改支付密码（oldHash 为旧密码 SHA-256，newHash 为新密码 SHA-256）。 */
    public Models.WalletResponse changePayPassword(String oldHash, String newHash) throws ApiException {
        return doRequest("PUT", "/api/wallet/password",
                java.util.Map.of("pin_hash", newHash, "old_hash", oldHash), Models.WalletResponse.class);
    }

    /** 管理员后台充值（仅 ADMIN_USER_IDS 白名单内的用户可调）。amount 单位:分。 */
    public Models.WalletResponse rechargeWallet(long amount) throws ApiException {
        return doRequest("POST", "/api/wallet/recharge",
                java.util.Map.of("amount", amount), Models.WalletResponse.class);
    }

    /** 发起转账（阶段2）。scanPay=true 表示收款码扫码付款（跳过好友校验）。返回服务端生成的 transfer 消息。 */
    public Models.TransferSendResponse sendTransfer(int toUserId, long amount, String memo,
                                                    String pinHash, boolean scanPay) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("to_user", toUserId);
        body.put("amount", amount);
        body.put("memo", memo == null ? "" : memo);
        body.put("pin_hash", pinHash);
        body.put("scan_pay", scanPay);
        return doRequest("POST", "/api/wallet/transfer", body, Models.TransferSendResponse.class);
    }

    /** 接收方收款（阶段2）。 */
    public Models.WalletResponse acceptTransfer(int tid) throws ApiException {
        return doRequest("POST", "/api/wallet/transfer/" + tid + "/accept",
                null, Models.WalletResponse.class);
    }

    /** 撤回/立即退还（阶段2）：发起方撤回、接收方立即退还都走此接口。 */
    public Models.WalletResponse refundTransfer(int tid) throws ApiException {
        return doRequest("POST", "/api/wallet/transfer/" + tid + "/refund",
                null, Models.WalletResponse.class);
    }

    /** 转账详情（阶段5）。 */
    public Models.TransferDetailResponse getTransferDetail(int tid) throws ApiException {
        return doRequest("GET", "/api/wallet/transfer/" + tid, null, Models.TransferDetailResponse.class);
    }

    /** 钱包账单（阶段5）。 */
    public Models.BillsResponse getBills() throws ApiException {
        return doRequest("GET", "/api/wallet/bills", null, Models.BillsResponse.class);
    }

    /** 商家发起收款请求（阶段4）：向付款码主人收钱。 */
    public Models.ChargeResponse createCharge(int toUserId, long amount, String memo) throws ApiException {
        return doRequest("POST", "/api/wallet/charge",
                java.util.Map.of("to_user", toUserId, "amount", amount,
                        "memo", memo == null ? "" : memo), Models.ChargeResponse.class);
    }

    /** 付款码主人确认付款（阶段4）。 */
    public Models.WalletResponse approveCharge(int cid, String pinHash) throws ApiException {
        return doRequest("POST", "/api/wallet/charge/" + cid + "/approve",
                java.util.Map.of("pin_hash", pinHash), Models.WalletResponse.class);
    }

    /** 付款码主人拒绝收款请求（阶段4）。 */
    public Models.WalletResponse declineCharge(int cid) throws ApiException {
        return doRequest("POST", "/api/wallet/charge/" + cid + "/decline",
                null, Models.WalletResponse.class);
    }

    // ═══════════════ 商城（淘宝首页风格，阶段2）═══════════════

    /** 商城商品列表（category 分类过滤，keyword 关键词）：{products:[...]}。 */
    public Models.ProductsResponse getProducts(String category, String keyword) throws ApiException {
        StringBuilder sb = new StringBuilder("/api/mall/products");
        boolean first = true;
        if (category != null && !category.isEmpty()) {
            sb.append('?').append("category=")
                    .append(urlEncode(category));
            first = false;
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sb.append(first ? '?' : '&').append("keyword=")
                    .append(urlEncode(keyword.trim()));
        }
        return doRequest("GET", sb.toString(), null, Models.ProductsResponse.class);
    }

    /** 商城分类（金刚区宫格）：{categories:[...]}。 */
    public Models.CategoriesResponse getCategories() throws ApiException {
        return doRequest("GET", "/api/mall/categories", null, Models.CategoriesResponse.class);
    }

    // ═══════════════ 商城（阶段3：淘宝式闭环——详情/下单/支付/订单/商家后台）═══════════════

    /** 商品详情：{product:{...}}。 */
    public Models.ProductDetailResponse getProduct(int id) throws ApiException {
        return doRequest("GET", "/api/mall/products/" + id, null, Models.ProductDetailResponse.class);
    }

    /** 我的订单（买家）：{orders:[...]}。 */
    public Models.MallOrderListResponse getMyOrders() throws ApiException {
        return doRequest("GET", "/api/mall/orders", null, Models.MallOrderListResponse.class);
    }

    /** 订单详情：{order:{...}}。 */
    public Models.MallOrderResponse getOrder(int id) throws ApiException {
        return doRequest("GET", "/api/mall/orders/" + id, null, Models.MallOrderResponse.class);
    }

    /** 下单（淘宝式先填收货信息），成功返回订单。 */
    public Models.CreateOrderResponse createOrder(int productId, int quantity,
                                                  String name, String phone, String address) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("product_id", productId);
        body.put("quantity", quantity);
        body.put("receiver_name", name != null ? name : "");
        body.put("receiver_phone", phone != null ? phone : "");
        body.put("receiver_address", address != null ? address : "");
        return doRequest("POST", "/api/mall/orders", body, Models.CreateOrderResponse.class);
    }

    /** 批量下单（购物车结算）：items=[{product_id,quantity},...]。 */
    public Models.CreateOrdersResponse createOrders(java.util.List<java.util.Map<String, Object>> items,
                                                    String name, String phone, String address) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items != null ? items : new java.util.ArrayList<>());
        body.put("receiver_name", name != null ? name : "");
        body.put("receiver_phone", phone != null ? phone : "");
        body.put("receiver_address", address != null ? address : "");
        return doRequest("POST", "/api/mall/orders", body, Models.CreateOrdersResponse.class);
    }

    /** 批量支付（购物车结算，一次密码付多单）。 */
    public Models.StatusResponse payOrders(java.util.List<Integer> orderIds, String pinHash) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("order_ids", orderIds != null ? orderIds : new java.util.ArrayList<>());
        body.put("pin_hash", pinHash != null ? pinHash : "");
        return doRequest("POST", "/api/mall/orders/pay-batch", body, Models.StatusResponse.class);
    }

    /** 支付订单（支付密码客户端 SHA-256 后上传）。 */
    public Models.StatusResponse payOrder(int orderId, String pinHash) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/pay",
                java.util.Map.of("pin_hash", pinHash != null ? pinHash : ""), Models.StatusResponse.class);
    }

    /** 取消待付款订单（恢复库存）。 */
    public Models.StatusResponse cancelOrder(int orderId) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/cancel", null, Models.StatusResponse.class);
    }

    /** 确认收货（shipped→completed）。 */
    public Models.StatusResponse confirmOrder(int orderId) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/confirm", null, Models.StatusResponse.class);
    }

    /** 我的商品（商家后台）：{products:[...]}。 */
    public Models.ProductsResponse getSellerProducts() throws ApiException {
        return doRequest("GET", "/api/mall/merchant/products", null, Models.ProductsResponse.class);
    }

    /** 店铺订单（商家后台=购买账单）：{orders:[...]}。 */
    public Models.MallOrderListResponse getSellerOrders() throws ApiException {
        return doRequest("GET", "/api/mall/merchant/orders", null, Models.MallOrderListResponse.class);
    }

    /** 发布商品（价格单位:分）。 */
    public Models.StatusResponse createProduct(String name, String category, long price, int stock,
                                               String description, String imageUrl) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name != null ? name : "");
        body.put("category", category != null ? category : "");
        body.put("price", price);
        body.put("stock", stock);
        body.put("description", description != null ? description : "");
        body.put("image_url", imageUrl != null ? imageUrl : "");
        return doRequest("POST", "/api/mall/products", body, Models.StatusResponse.class);
    }

    /** 编辑商品（价格单位:分）。 */
    public Models.StatusResponse updateProduct(int id, String name, String category, long price, int stock,
                                               String description, String imageUrl) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name != null ? name : "");
        body.put("category", category != null ? category : "");
        body.put("price", price);
        body.put("stock", stock);
        body.put("description", description != null ? description : "");
        body.put("image_url", imageUrl != null ? imageUrl : "");
        return doRequest("PUT", "/api/mall/products/" + id, body, Models.StatusResponse.class);
    }

    /** 上架/下架切换。 */
    public Models.StatusResponse toggleProduct(int id) throws ApiException {
        return doRequest("POST", "/api/mall/products/" + id + "/toggle", null, Models.StatusResponse.class);
    }

    /** 删除商品。 */
    public Models.StatusResponse deleteProduct(int id) throws ApiException {
        return doRequest("DELETE", "/api/mall/products/" + id, null, Models.StatusResponse.class);
    }

    /** 卖家发货（paid→shipped，可填快递公司/单号）。 */
    public Models.StatusResponse shipOrder(int orderId, String shippingCompany, String trackingNo) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("shipping_company", shippingCompany != null ? shippingCompany : "");
        body.put("tracking_no", trackingNo != null ? trackingNo : "");
        return doRequest("POST", "/api/mall/orders/" + orderId + "/ship", body, Models.StatusResponse.class);
    }

    // ═══════════════ 商城（阶段4完善：购物车/收藏/地址簿/评价/退款/热搜/分页/店铺）═══════════════

    /** 商品列表（分页版）：{products, has_more, total}。 */
    public Models.ProductsResponse getProductsPage(String category, String keyword, int page, int pageSize) throws ApiException {
        StringBuilder sb = new StringBuilder("/api/mall/products?page=").append(page).append("&pageSize=").append(pageSize);
        if (category != null && !category.isEmpty()) {
            sb.append("&category=").append(urlEncode(category));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sb.append("&keyword=").append(urlEncode(keyword.trim()));
        }
        return doRequest("GET", sb.toString(), null, Models.ProductsResponse.class);
    }

    /** 搜索热词榜：{keywords:[{keyword,count}]}。 */
    public Models.HotSearchesResponse getHotSearches() throws ApiException {
        return doRequest("GET", "/api/mall/hot-searches", null, Models.HotSearchesResponse.class);
    }

    /** 卖家店铺主页：{seller_id, shop_name, products}。 */
    public Models.ShopResponse getSellerShop(int sellerId) throws ApiException {
        return doRequest("GET", "/api/mall/sellers/" + sellerId + "/products", null, Models.ShopResponse.class);
    }

    /** 我的购物车：{items:[...]}。 */
    public Models.CartResponse getCart() throws ApiException {
        return doRequest("GET", "/api/mall/cart", null, Models.CartResponse.class);
    }

    /** 加购（同商品自动合并数量）。 */
    public Models.StatusResponse addCart(int productId, int quantity) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("product_id", productId);
        body.put("quantity", quantity);
        return doRequest("POST", "/api/mall/cart", body, Models.StatusResponse.class);
    }

    /** 改购物车数量。 */
    public Models.StatusResponse updateCartQuantity(int cartId, int quantity) throws ApiException {
        return doRequest("PUT", "/api/mall/cart/" + cartId,
                java.util.Map.of("quantity", quantity), Models.StatusResponse.class);
    }

    /** 删除购物车项。 */
    public Models.StatusResponse deleteCartItem(int cartId) throws ApiException {
        return doRequest("DELETE", "/api/mall/cart/" + cartId, null, Models.StatusResponse.class);
    }

    /** 收藏/取消收藏：{favorited}。 */
    public Models.FavoriteStatusResponse toggleFavorite(int productId) throws ApiException {
        return doRequest("POST", "/api/mall/favorites/toggle",
                java.util.Map.of("product_id", productId), Models.FavoriteStatusResponse.class);
    }

    /** 我的收藏：{favorites:[...]}。 */
    public Models.FavoritesResponse getFavorites() throws ApiException {
        return doRequest("GET", "/api/mall/favorites", null, Models.FavoritesResponse.class);
    }

    /** 是否已收藏：{favorited}。 */
    public Models.FavoriteStatusResponse isFavorite(int productId) throws ApiException {
        return doRequest("GET", "/api/mall/favorites/" + productId, null, Models.FavoriteStatusResponse.class);
    }

    /** 我的收货地址：{addresses:[...]}。 */
    public Models.AddressListResponse getAddresses() throws ApiException {
        return doRequest("GET", "/api/mall/addresses", null, Models.AddressListResponse.class);
    }

    /** 新增地址。 */
    public Models.StatusResponse addAddress(String name, String phone, String address) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name != null ? name : "");
        body.put("phone", phone != null ? phone : "");
        body.put("address", address != null ? address : "");
        return doRequest("POST", "/api/mall/addresses", body, Models.StatusResponse.class);
    }

    /** 编辑地址。 */
    public Models.StatusResponse updateAddress(int id, String name, String phone, String address) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name != null ? name : "");
        body.put("phone", phone != null ? phone : "");
        body.put("address", address != null ? address : "");
        return doRequest("PUT", "/api/mall/addresses/" + id, body, Models.StatusResponse.class);
    }

    /** 删除地址。 */
    public Models.StatusResponse deleteAddress(int id) throws ApiException {
        return doRequest("DELETE", "/api/mall/addresses/" + id, null, Models.StatusResponse.class);
    }

    /** 设为默认地址。 */
    public Models.StatusResponse setDefaultAddress(int id) throws ApiException {
        return doRequest("POST", "/api/mall/addresses/" + id + "/default", null, Models.StatusResponse.class);
    }

    /** 商品评价列表：{reviews:[...]}。 */
    public Models.ReviewListResponse getProductReviews(int productId) throws ApiException {
        return doRequest("GET", "/api/mall/products/" + productId + "/reviews", null, Models.ReviewListResponse.class);
    }

    /** 订单评价（completed 订单，rating 1-5）。 */
    public Models.StatusResponse addReview(int orderId, int rating, String content) throws ApiException {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("rating", rating);
        body.put("content", content != null ? content : "");
        return doRequest("POST", "/api/mall/orders/" + orderId + "/review", body, Models.StatusResponse.class);
    }

    /** 买家申请退款：{reason}。 */
    public Models.StatusResponse applyRefund(int orderId, String reason) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/refund",
                java.util.Map.of("reason", reason != null ? reason : ""), Models.StatusResponse.class);
    }

    /** 卖家同意退款（资金原路退回）。 */
    public Models.StatusResponse approveRefund(int orderId) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/refund/approve", null, Models.StatusResponse.class);
    }

    /** 卖家拒绝退款。 */
    public Models.StatusResponse rejectRefund(int orderId) throws ApiException {
        return doRequest("POST", "/api/mall/orders/" + orderId + "/refund/reject", null, Models.StatusResponse.class);
    }

    // ===== Request Bodies =====
    // ⚠️ 每个字段都必须有 @SerializedName：字段名会被 R8 混淆（username -> a），
    // 而服务端是按 JSON 键名读取的，键名一改就会反序列化失败并返回空体 400
    //（"无效的请求格式"）。加注解后键名与字段名解耦，混淆不再影响接口契约。
    static class AuthBody {
        @SerializedName("username") String username;
        @SerializedName("password") String password;
    }
    static class RegBody {
        @SerializedName("username") String username;
        @SerializedName("password") String password;
        @SerializedName("nickname") String nickname;
    }
    static class FriendReqBody {
        @SerializedName("friend_id") int friend_id;
    }
    static class RemarkBody {
        @SerializedName("friend_id") int friend_id;
        @SerializedName("remark") String remark;
    }
    static class CallStatusBody {
        @SerializedName("friend_id") int friend_id;
        @SerializedName("event") String event;
        @SerializedName("duration_sec") int duration_sec;
        @SerializedName("video") boolean video;
    }
    static class ChangePwdBody {
        @SerializedName("old_password") String old_password;
        @SerializedName("new_password") String new_password;
    }
    static class ProfileUpdateBody {
        @SerializedName("nickname") String nickname;
        @SerializedName("avatar") String avatar;
        @SerializedName("avatar_color") String avatar_color;
    }

    // ===== Custom Exception =====
    public static class ApiException extends Exception {
        private final int statusCode;

        public ApiException(String message) {
            super(message);
            this.statusCode = 0;
        }

        public ApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
