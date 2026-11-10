package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.ui.CallActivity;
import com.familychat.app.ui.ChatActivity;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.FlashText;
import com.familychat.app.utils.MessagePreview;
import com.familychat.app.notify.NotificationHelper;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.gson.Gson;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;
import android.graphics.Bitmap;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.yalantis.ucrop.UCrop;

import com.familychat.app.utils.HttpClientProvider;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreferencesManager prefs;
    private ApiClient api;
    /**
     * 本页对推送连接的监听器。
     *
     * <p>连接本身不再由本 Activity 持有 —— 唯一持有者是 :core 的 {@code PushConnection}
     * （由 ForegroundService 驱动生命周期）。这里只保留监听器引用，供 onDestroy 反注册。
     * 提升为字段是必须的：匿名监听器无法在 onDestroy 重新构造出同一个实例来移除。
     */
    private WebSocketManager.Listener wsListener;
    private Models.User myProfile;
    private List<Models.User> friends = new ArrayList<>();
    private List<Models.FriendRequest> pendingReqs = new ArrayList<>();

    private final Handler debounceHandler = new Handler();
    private Runnable refreshDebounceRunnable;
    private long lastRefreshTime = 0;
    private static final long REFRESH_DEBOUNCE_MS = 5000;
    private boolean refreshPending = false;

    /**
     * 本进程是否已经就"通知被关闭"打扰过用户一次（引导弹窗）。
     *
     * <p>刻意是 <b>static</b>：进程级去重，而不是"每个 MainActivity 实例一次"——
     * MainActivity 会因旋转/主题切换 recreate，用实例字段会导致反复弹窗。
     * 两条路径共用这一个闸门：onCreate 的首次权限申请、onResume 的
     * {@code areNotificationsEnabled()} 检查（后者本身也只在进程内第一次回到前台时触发）。
     */
    private static boolean notifGuidanceShown = false;

    /**
     * 是否已经在<b>这台设备</b>上弹过系统权限框（落盘，跨进程有效）。
     *
     * <p>为什么需要它：Android 13 上"不再询问"之后，再调 {@code launch()} 系统框不会出现，
     * 回调会立刻返回 false —— 于是每次启动都会退化成"直接弹引导弹窗"，变成骚扰。
     * 落盘标记保证系统框只在首次进入时弹一次，之后一律走引导弹窗（用户可"以后再说"）。
     * 与账号无关（是设备级权限），因此登出不清除。
     */
    private static final String PREF_NOTIF_PERM_ASKED = "notif_perm_asked";

    private TextView tvToolbarTitle;
    private RecyclerView rvChats, rvContacts;
    private ChatListAdapter chatAdapter;
    private ContactAdapter contactAdapter;
    private List<Models.Group> myGroups = new ArrayList<>();

    // ===== 只读离线缓存（P0：好友列表 / 群列表）=====
    // gen 用于丢弃过期回填：缓存与网络两条路径都经主线程，后投递者覆盖先投递者。
    private int friendsGen = 0;
    private int groupsGen = 0;
    // 本屏内是否已有过成功的网络响应；两个都为 true 才隐藏离线横幅。
    private boolean friendsOnline = false;
    private boolean groupsOnline = false;
    // 已经渲染了缓存、但网络结果尚未确定。只有等网络确认失败才亮横幅，
    // 否则网络只是慢的时候会先闪一下"离线"再消失。
    private boolean pendingFriendsOffline = false;
    private boolean pendingGroupsOffline = false;
    // QQ 联系人页：右侧 A-Z 索引条 + 居中字母气泡 + 我的群聊列表容器
    private com.familychat.app.ui.IndexBarView indexBar;
    private TextView tvIndexBubble;
    private LinearLayout contactGroupsContainer;
    private RecyclerView rvContactGroups;
    private ContactGroupAdapter contactGroupsAdapter;
    /** 统一会话列表（好友 Models.User 与群 Models.Group 混排，全仿 QQ）。 */
    private List<Object> conversations = new ArrayList<>();

    // ===== 会话预览：后台预解密 + 内存缓存 =====
    // onBindViewHolder 只读缓存（零 I/O、零网络）；解密全部在 previewExecutor 的后台线程完成。
    // 好友预览读本地库密文，群预览用服务端 last_content（同样是密文 JSON）。
    private final java.util.Map<Integer, String> friendPreviewCache = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> groupPreviewCache = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService previewExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 快照代次：loadFriends/loadGroups 可能连续触发刷新，只接受最后一次的结果，避免旧结果覆盖新结果。 */
    private volatile int previewGeneration = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferencesManager.getInstance(this);
        // 升级后首次进入：若已有登录态但账号列表为空，则回填当前账号
        if (prefs.isLoggedIn() && prefs.getAccounts().isEmpty()) {
            prefs.saveCurrentAccount();
        }
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        // WebSocket 连接不再由本页创建：连接宿主已下沉到 :core 的 PushConnection，
        // 由 ForegroundService 驱动生命周期（见下方 onCreate 末尾）。

        // 401 自动登出由 FamilyChatApp 全局注册一次，Activity 不再注册（避免互相覆盖）
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        rvChats = findViewById(R.id.rvChats);
        rvContacts = findViewById(R.id.rvContacts);

        // 发现页：商场入口（点击进入商品列表页）
        findViewById(R.id.rowMallEntry).setOnClickListener(v -> startActivity(new Intent(this, MallActivity.class)));

        // 发现页：商家后台入口（点击进入商家后台）
        findViewById(R.id.rowMerchantEntry).setOnClickListener(v ->
                startActivity(new Intent(this, MerchantBackendActivity.class)));

        // 设置入口（退出登录已迁移到设置页）
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        chatAdapter = new ChatListAdapter();
        rvChats.setLayoutManager(new LinearLayoutManager(this));
        rvChats.setAdapter(chatAdapter);

        contactAdapter = new ContactAdapter();
        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        rvContacts.setAdapter(contactAdapter);

        initQqContacts();

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_chats) { showChats(); return true; }
            if (id == R.id.nav_contacts) { showContacts(); return true; }
            if (id == R.id.nav_discover) { showDiscover(); return true; }
            if (id == R.id.nav_me) { showMe(); return true; }
            return false;
        });

        loadProfile();
        loadFriends();
        loadGroups();
        loadPendingReqs();

        // ── 推送连接：启动前台服务 + 注册监听 ──
        // 1) 显式 start() 一次：startForegroundService() 是异步的，Service.onCreate 不保证
        //    早于本方法执行；只依赖 Service 启动的话，下面 addListener 时连接可能尚未建立，
        //    PushConnection 的 onConnected 补发就不会发生。start() 幂等，重复调用无副作用。
        PushConnection.start(this);

        // 2) 启动前台服务，由它负责"进程被系统回收后把连接拉回来"
        try {
            Intent fgIntent = new Intent(this, com.familychat.app.ForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(fgIntent);
            } else {
                startService(fgIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "start foreground service failed", e);
        }

        // 3) 注册监听。createWsListener() 内的逻辑与拆分前完全一致。
        wsListener = createWsListener();
        PushConnection.addListener(wsListener);
        // 4) 通知权限（Android 13+）：首次进入弹一次系统框，结果处理见 notifPermLauncher。
        //    13 以下没有运行时权限，但"通知总开关"仍可能被用户在系统里关掉 ——
        //    那条路径由 onResume 的 areNotificationsEnabled() 检查覆盖，两者共用同一个引导弹窗。
        //    历史缺陷：这里原来只用 requestPermissions(..., 200) 弹框，从不处理结果，
        //    用户点"拒绝"之后通知链静默失效，App 内没有任何提示、也无法再引导。
        requestNotificationPermissionIfNeeded();
        // 已移除"申请忽略电池优化"逻辑：该权限常被杀软判为保活/常驻风险项，
        // 且原实现只在权限已授予时才跳转、实际从未生效。长连接保活依赖前台服务通知。
        showChats();
        initBackHandling();
    }

    /**
     * 通话应用内浮层接管（与 ChatActivity 同款）。
     *
     * <p>本页原先没有 onStart/onStop 覆写（grep onStart|onStop = 0 命中）。
     * attach 在无通话时内部直接返回，因此对日常启动/切页零副作用。
     */
    @Override
    protected void onStart() {
        super.onStart();
        com.familychat.app.api.call.CallOverlayController.attach(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 宿主作用域卸载 + 延迟判定是否需要系统浮窗（见 CallOverlayController.onHostStopped）
        com.familychat.app.api.call.CallOverlayController.detach(this);
        com.familychat.app.api.call.CallOverlayController.onHostStopped(this);
    }

    /**
     * 返回键统一走 OnBackPressedDispatcher（onBackPressed() 自 API 33 起弃用）。
     *
     * <p>分支顺序：
     * <ol>
     *   <li>「我的群聊」容器可见 → 关掉它（复用 onContactGroupsBack，不复制 setVisibility）</li>
     *   <li>「发现」页可见 → 切回消息列表</li>
     *   <li>其余 → 交还系统（退出 App）</li>
     * </ol>
     *
     * <p>背景：「我的群聊」不是 Activity，而是 activity_main.xml 里覆盖在 viewContacts
     * 之上的内嵌 LinearLayout，从未进过任务栈；而 LoginActivity 登录成功后会 finish()
     * 自己（LoginActivity.java:560-561），所以 MainActivity 就是栈底。过去这里漏了
     * 第一条分支，返回键直接 finish 掉唯一的 Activity，表现就是"直接退出 App"。
     */
    private void initBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 1. 我的群聊：内嵌容器优先关闭
                if (contactGroupsContainer != null
                        && contactGroupsContainer.getVisibility() == View.VISIBLE) {
                    onContactGroupsBack(null);      // 复用既有逻辑（该实现不使用入参）
                    return;
                }
                // 2. 发现页：返回消息列表
                if (findViewById(R.id.viewDiscover).getVisibility() == View.VISIBLE) {
                    showChats();
                    return;
                }
                // 3. 交还系统：禁用本 callback 后重新派发，走 Activity 默认（finish）
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private WebSocketManager.Listener createWsListener() {
        return new WebSocketManager.Listener() {
            @Override public void onConnected() {}
            @Override public void onDisconnected() {}
            @Override public void onNewMessage(Models.Message msg) {
                // ⚠️ 本页销毁后不再收到本回调（监听器在 onDestroy 反注册），这里只服务于
                //    「页面存活时的 UI 刷新」。消息未读计数与系统通知由 NotificationDispatcher
                //    独立负责（它注册在 Application 上，与任何 Activity 的生死无关）。
                //    此处不得再实现通知/未读逻辑 —— 会与 Dispatcher 双计、双弹。
                runOnUiThread(() -> {
                    loadFriends();
                });
            }
            @Override public void onMessageRecalled(int msgId) {
                // Update local DB even when ChatActivity is not open
                com.familychat.app.utils.MessageDatabase.getInstance(MainActivity.this).recallMessage(msgId);
                android.util.Log.d("RECALL_LOG", "MAIN: recall received msgId=" + msgId + " DB updated");
            }
            @Override public void onFriendRequest() {
                runOnUiThread(() -> { loadPendingReqs(); Toast.makeText(MainActivity.this, "收到好友请求", Toast.LENGTH_SHORT).show(); });
            }
            @Override public void onFriendRequestHandled(int fsId, String action, Models.User byUser) {
                if ("accept".equals(action)) {
                    runOnUiThread(() -> { Toast.makeText(MainActivity.this, byUser.nickname + " 已接受好友请求", Toast.LENGTH_SHORT).show(); loadFriends(); });
                }
            }
            @Override public void onUserOnline(int userId) { loadFriends(); }
            @Override public void onUserOffline(int userId) { loadFriends(); }

            // ---- 群聊推送：仅页面存活时的列表刷新 ----
            @Override public void onGroupNewMessage(Models.GroupMessage gmsg) {
                // ⚠️ 同 onNewMessage：本页销毁后不再收到本回调，这里只做「页面存活时的 UI 刷新」。
                //    群未读计数（含 system 消息）与群通知（当前群/系统消息/免打扰三重判定、
                //    密文解密、群名解析）已全部由 NotificationDispatcher 独立负责，
                //    本页不得重复实现，否则未读双计、通知双弹。
                runOnUiThread(() -> loadGroups());
            }
            @Override public void onGroupMessageRecalled(int groupId, int msgId) {
                runOnUiThread(() -> loadGroups());
            }
            @Override public void onGroupMembersChanged(int groupId) {
                runOnUiThread(() -> loadGroups());
            }
            @Override public void onGroupDissolved(int groupId) {
                prefs.setGroupUnread(groupId, 0);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "群聊已解散", Toast.LENGTH_SHORT).show();
                    loadGroups();
                });
            }
            @Override public void onGroupRemoved(int groupId) {
                prefs.setGroupUnread(groupId, 0);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "你已被移出群聊", Toast.LENGTH_SHORT).show();
                    loadGroups();
                });
            }

            // ---- 通话信令：本页不再处理 ----
            // Step 1 / C4 起，来电由 :core 的 CallManager（PushConnection 的常驻监听器）
            // 统一接管：状态机 + callId 去重 + 超时，并负责拉起通话页。
            // 旧实现（onCallOffer → handleIncomingCall 弹 MaterialAlertDialog）已整体删除，
            // 原因是它同时带来三个缺陷：
            //   1) Dialog 挂在 MainActivity 窗口上，ChatActivity 在前台时来电看不见（退出聊天才弹）；
            //   2) Dialog 引用被丢弃，对方取消后永不关闭；
            //   3) 与 ChatActivity 的 onCallOffer 靠 shouldHandleIncoming 的 4 秒窗口抢名额。
            // 方法体留空是接口 default 语义，不是漏实现。
            // ⚠️ 若将来又需要在 MainActivity 里处理通话事件，请先在 CallManager 里评估，
            //    不要在这里重新实现 —— 那会立刻回到"两条路径互相打架"的状态。

            // ---- 支付推送：收款请求弹窗 / 请求结果提示 ----
            @Override public void onChargeRequested(int cid, int fromUser, String fromNickname, long amount, String memo) {
                runOnUiThread(() -> {
                    Intent it = new Intent(MainActivity.this, ChargeConfirmActivity.class);
                    it.putExtra("charge_id", cid);
                    it.putExtra("from_nickname", fromNickname);
                    it.putExtra("amount", amount);
                    it.putExtra("memo", memo);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                });
            }
            @Override public void onChargeResult(int cid, String status, long amount) {
                runOnUiThread(() -> {
                    if ("accepted".equals(status)) {
                        Toast.makeText(MainActivity.this, "收款请求已确认，金额已到账", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "收款请求已被拒绝", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            // ---- 商城订单事件：买卖双方实时提示 ----
            @Override public void onMallEvent(String type, int orderId, String productName, long amount, boolean approved) {
                runOnUiThread(() -> {
                    String name = productName != null && !productName.isEmpty() ? productName : "商品";
                    String msg;
                    switch (type) {
                        case "mall_order_new":
                            msg = "📦 商城新订单：" + name;
                            break;
                        case "mall_order_paid":
                            msg = "💰 买家已付款：" + name;
                            break;
                        case "mall_order_shipped":
                            msg = "🚚 " + name + " 已发货，记得查收";
                            break;
                        case "mall_order_completed":
                            msg = "✅ 订单已完成：" + name;
                            break;
                        case "mall_order_cancelled":
                            msg = "❌ 订单已取消：" + name;
                            break;
                        case "mall_order_timeout":
                            msg = "⏰ 订单超时未付款已自动取消：" + name;
                            break;
                        case "mall_refund_apply":
                            msg = "↩️ 买家申请退款：" + name;
                            break;
                        case "mall_refund_result":
                            msg = approved ? "↩️ 退款成功：" + name : "↩️ 退款申请被拒绝：" + name;
                            break;
                        default:
                            msg = "商城订单状态更新：" + name;
                            break;
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }
        };
    }

    private void showChats() {
        tvToolbarTitle.setText("消息");
        findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
        findViewById(R.id.viewChats).setVisibility(View.VISIBLE);
        findViewById(R.id.viewContacts).setVisibility(View.GONE);
        findViewById(R.id.viewDiscover).setVisibility(View.GONE);
        findViewById(R.id.viewMe).setVisibility(View.GONE);
    }

    private void showContacts() {
        tvToolbarTitle.setText("通讯录");
        findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
        findViewById(R.id.viewChats).setVisibility(View.GONE);
        findViewById(R.id.viewContacts).setVisibility(View.VISIBLE);
        findViewById(R.id.viewDiscover).setVisibility(View.GONE);
        findViewById(R.id.viewMe).setVisibility(View.GONE);
        loadPendingReqs();
        if (contactGroupsContainer != null) contactGroupsContainer.setVisibility(View.GONE);
    }

    private void showDiscover() {
        tvToolbarTitle.setText("发现");
        findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
        findViewById(R.id.viewChats).setVisibility(View.GONE);
        findViewById(R.id.viewContacts).setVisibility(View.GONE);
        findViewById(R.id.viewDiscover).setVisibility(View.VISIBLE);
        findViewById(R.id.viewMe).setVisibility(View.GONE);
    }


    private void showMe() {
        tvToolbarTitle.setText("我的");
        findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
        findViewById(R.id.viewChats).setVisibility(View.GONE);
        findViewById(R.id.viewContacts).setVisibility(View.GONE);
        findViewById(R.id.viewDiscover).setVisibility(View.GONE);
        findViewById(R.id.viewMe).setVisibility(View.VISIBLE);
        updateProfileView();
    }

    // ===== Profile =====
    private void loadProfile() {
        new Thread(() -> {
            try {
                Models.ProfileResponse res = api.getProfile();
                myProfile = res.user;
                // Cache avatar info locally
                if (myProfile != null) {
                    prefs.setMyAvatar(myProfile.avatar);
                    prefs.setMyAvatarColor(myProfile.avatar_color);
                    prefs.setMyNickname(myProfile.nickname);
                    // 密语号式专属ID：登录/切号后每次进主界面都从服务端拉权威值回写 prefs，
                    // 旧账号快照没有 wx_id 时在此自愈。空值守卫：旧服务端不返回时不清掉已有值。
                    if (myProfile.wx_id != null && !myProfile.wx_id.isEmpty())
                        prefs.setMyWxId(myProfile.wx_id);
                }
                runOnUiThread(this::updateProfileView);
            } catch (Exception e) { Log.e(TAG, "Exception", e); }
        }).start();
    }

    private void updateProfileView() {
        TextView tvName = findViewById(R.id.tvMyName);
        TextView tvUid = findViewById(R.id.tvMyUid);
        ImageView ivAvatar = findViewById(R.id.ivMyAvatar);

        // 服务端资料优先；拿不到（断网/请求失败）时回落到本地已落盘的资料。
        // 历史 bug：这里第一行是 `if (myProfile == null) return;` —— 断网时直接退出，
        // 于是露出布局里的硬编码默认值「用户 / @username」+ 占位头像。
        // 而 prefs 里其实早就存着昵称/账号名/头像（loadProfile 成功过一次即可），
        // 只是从来没被读过。项目里其它页面（我的二维码/付款码/服务器信息）一直是用 prefs 兜底的。
        String nickname = myProfile != null ? myProfile.nickname : null;
        if (nickname == null || nickname.isEmpty()) nickname = prefs.getMyNickname();

        String username = myProfile != null ? myProfile.username : null;
        if (username == null || username.isEmpty()) username = prefs.getMyUsername();

        String avatar = myProfile != null ? myProfile.avatar : null;
        if (avatar == null || avatar.isEmpty()) avatar = prefs.getMyAvatar();

        // 两者都拿不到时保持布局默认值，不写空串（避免显示成「@」）
        if (nickname != null && !nickname.isEmpty()) tvName.setText(nickname);
        // 界面统一显示 @账号名（唯一身份号）；后台密语号不再展示
        if (username != null && !username.isEmpty()) tvUid.setText("@" + username);
        // 长按复制后闪回的目标：紧跟在 setText 之后取一次作为规范文字。
        // 不能在点击回调里现取 tvUid.getText()——上次闪的"已复制"还没复原时再长按，
        // 会把"已复制"当成规范文字，复原后一直显示"已复制"。
        final String uidNormal = tvUid.getText().toString();
        // 长按复制 @账号名
        tvUid.setOnLongClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("账号名", uidNormal));
                FlashText.flash(tvUid, "已复制", uidNormal);
            } catch (Exception e) {
                FlashText.flash(tvUid, "复制失败", uidNormal);
            }
            return true;
        });
        // avatar 为空或非 "/" 开头（如默认表情 "😊"）时 loadAvatar 内部会显示占位图
        loadAvatar(ivAvatar, avatar, 72);
    }

    // ===== Friends =====
    private void loadFriends() {
        // 本轮加载的代号：缓存回填与网络回填都会经过主线程，后投递者覆盖先投递者。
        // 用代号丢弃"上一轮已过期"的回填，避免慢的旧响应盖掉快的新响应。
        final int gen = ++friendsGen;
        new Thread(() -> {
            // ① 先渲染盘上镜像（若有）：断网时这是唯一的数据来源
            //    注意：此处只渲染列表，不显示横幅——网络此刻还没失败，
            //    否则网络只是慢的时候会先闪一下"离线"再消失。
            final Models.FriendsResponse cached =
                    com.familychat.app.utils.ReadOnlyCache.loadFriends(this, prefs);
            if (cached != null && cached.friends != null && !cached.friends.isEmpty()) {
                final List<Models.User> cachedList = cached.friends;
                runOnUiThread(() -> {
                    if (gen != friendsGen) return;      // 本轮已过期
                    friends = cachedList;
                    buildConversations();
                    contactAdapter.rebuild();
                    pendingFriendsOffline = true;       // 网络若失败再亮横幅
                });
            }
            // ② 再按原样请求网络（缓存优先不等于跳过网络）
            try {
                Models.FriendsResponse res = api.getFriends();
                // 镜像服务端原文：紧贴 api 调用，早于任何用户操作类字段回写
                com.familychat.app.utils.ReadOnlyCache.saveFriends(this, prefs, res);
                final List<Models.User> fresh =
                        res.friends != null ? res.friends : new ArrayList<>();
                runOnUiThread(() -> {
                    if (gen != friendsGen) return;      // 本轮已过期
                    friends = fresh;
                    friendsOnline = true;
                    pendingFriendsOffline = false;
                    buildConversations();
                    contactAdapter.rebuild();
                    hideOfflineBannerIfOnline();
                });
            } catch (Exception e) {
                // 网络失败：缓存已显示（若有），什么都不做——绝不清空 friends
                Log.e(TAG, "Exception", e);
                runOnUiThread(() -> {
                    if (gen != friendsGen) return;
                    maybeShowOfflineBanner();
                });
            }
        }).start();
    }

    // ===== Groups =====

    /**
     * 把服务端权威的群置顶/免打扰同步进 prefs，并记录我加入的群 id。
     *
     * <p>{@link #isPinned(Object)} 对群的排序键读的是 prefs（不是 {@code Models.Group.pinned}），
     * 所以<b>在线/离线两条分支都必须调用</b>：离线分支过去跳过这一步，
     * 缓存里明明带着 pinned 值却用不上，置顶群的位置会与在线时不一致（置顶是第一排序键）。
     *
     * <p>iOS/WS 侧依赖 {@code my_group_ids} 过滤不属于自己的群推送，故一并写入。
     */
    private void syncGroupPrefs(List<Models.Group> list) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Models.Group g : list) {
            ids.add(g.id);
            prefs.setGroupPinned(g.id, g.pinned == 1);
            prefs.setGroupQuiet(g.id, g.quiet == 1);
        }
        prefs.setMyGroupIds(ids);
    }

    /** 拉取我加入的群列表，缓存置顶/免打扰，刷新统一会话列表。 */
    private void loadGroups() {
        final int gen = ++groupsGen;
        new Thread(() -> {
            // ① 先渲染盘上镜像（若有）
            final Models.GroupListResponse cached =
                    com.familychat.app.utils.ReadOnlyCache.loadGroups(this, prefs);
            if (cached != null && cached.groups != null && !cached.groups.isEmpty()) {
                final List<Models.Group> cachedList = cached.groups;
                runOnUiThread(() -> {
                    if (gen != groupsGen) return;
                    myGroups = cachedList;
                    // 与网络分支同样同步置顶/免打扰：isPinned(Group) 读的是 prefs，
                    // 缓存里的 Models.Group.pinned 只是数据，不同步就会与在线排序不一致。
                    syncGroupPrefs(cachedList);
                    buildConversations();
                    if (contactGroupsAdapter != null) contactGroupsAdapter.notifyDataSetChanged();
                    pendingGroupsOffline = true;        // 网络若失败再亮横幅
                });
            }
            // ② 再请求网络
            try {
                Models.GroupListResponse res = api.getMyGroups();
                // 镜像服务端原文：紧贴 api 调用，早于下面的 setGroupPinned/Quiet 等本地回写
                com.familychat.app.utils.ReadOnlyCache.saveGroups(this, prefs, res);
                List<Models.Group> list = res.groups != null ? res.groups : new ArrayList<>();
                myGroups = list;
                // 同步服务端置顶/免打扰到本地（WS 通知/排序即时生效），并记录我加入的群
                syncGroupPrefs(list);
                runOnUiThread(() -> {
                    if (gen != groupsGen) return;
                    groupsOnline = true;
                    pendingGroupsOffline = false;
                    buildConversations();
                    if (contactGroupsAdapter != null) contactGroupsAdapter.notifyDataSetChanged();
                    hideOfflineBannerIfOnline();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadGroups", e);
                com.familychat.app.utils.AppLog.e("GROUPS", "加载群列表失败", e);
                runOnUiThread(() -> {
                    if (gen != groupsGen) return;
                    maybeShowOfflineBanner();
                });
            }
        }).start();
    }

    // ===== 离线横幅 =====
    // 只读缓存的可见性提示：告诉用户"这份列表来自缓存而非最新"。
    // 文案只体现"缓存写于何时"，不含任何用户操作语义。

    /**
     * 网络确认失败后调用：若本次确实渲染过缓存、且该列表还没有过成功的网络响应，
     * 才把横幅亮出来。
     *
     * <p>不在"渲染缓存"的那一刻亮横幅，是因为那时网络还没失败——网络只是慢的时候
     * 会先闪一下"离线"再消失。也不在已联网成功后再亮，那会把真实数据误标成离线。
     */
    private void maybeShowOfflineBanner() {
        boolean usedFriendsCache = pendingFriendsOffline && !friendsOnline;
        boolean usedGroupsCache = pendingGroupsOffline && !groupsOnline;
        if (!usedFriendsCache && !usedGroupsCache) return;

        long ts = usedFriendsCache
                ? com.familychat.app.utils.ReadOnlyCache.friendsCachedAt(prefs)
                : com.familychat.app.utils.ReadOnlyCache.groupsCachedAt(prefs);
        String text = com.familychat.app.utils.ReadOnlyCache.describeAge(ts);

        TextView a = findViewById(R.id.tvOfflineChats);
        TextView b = findViewById(R.id.tvOfflineContacts);
        if (a != null) { a.setText(text); a.setVisibility(View.VISIBLE); }
        if (b != null) { b.setText(text); b.setVisibility(View.VISIBLE); }
    }

    /** 好友与群两个列表都拿到过网络响应后，才隐藏横幅。 */
    private void hideOfflineBannerIfOnline() {
        if (!friendsOnline || !groupsOnline) return;
        TextView a = findViewById(R.id.tvOfflineChats);
        TextView b = findViewById(R.id.tvOfflineContacts);
        if (a != null) a.setVisibility(View.GONE);
        if (b != null) b.setVisibility(View.GONE);
    }

    /**
     * 合并好友 + 群为统一会话列表，全仿 QQ 排序：
     * 置顶优先（好友 pinned_chats / 群 group_settings.pinned），再按最后消息时间倒序。
     */
    private void buildConversations() {
        conversations = new ArrayList<>();
        conversations.addAll(friends);
        conversations.addAll(myGroups);
        // 合并排序：置顶的排最前，置顶内部/非置顶内部按最后消息时间倒序
        conversations.sort((a, b) -> {
            boolean pa = isPinned(a), pb = isPinned(b);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(lastMsgMillis(b), lastMsgMillis(a));
        });
        chatAdapter.notifyDataSetChanged();
        // 预览文字需要解密（好友读本地库、群读服务端 last_content），统一在后台刷新缓存；
        // 首帧读不到的会话预览为空，解密完成后 notifyDataSetChanged 补齐。
        refreshPreviewCacheAsync();
    }

    private boolean isPinned(Object o) {
        if (o instanceof Models.User) {
            return prefs.isFriendPinned(((Models.User) o).id);
        } else if (o instanceof Models.Group) {
            return prefs.getGroupPinned(((Models.Group) o).id);
        }
        return false;
    }

    /** 会话最后一条消息的毫秒时间（排序用）。 */
    private long lastMsgMillis(Object o) {
        try {
            if (o instanceof Models.User) {
                Models.Message m = com.familychat.app.utils.MessageDatabase
                        .getInstance(this).getLastMessage(prefs.getUserId(), ((Models.User) o).id);
                return m != null && m.created_at != null ? parseTime(m.created_at) : 0L;
            } else if (o instanceof Models.Group) {
                return groupSortMillis((Models.Group) o);
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    /** 解析服务端 created_at（UTC 文本）为毫秒；失败返回 0。 */
    private long parseTime(String s) {
        return ChatTimeFormatter.parseUtc(s);
    }

    /** 会话行时间文本：今天显示 HH:mm，跨天显示 MM-dd，跨年显示 yyyy-MM-dd。 */
    private String formatListTime(String createdAt) {
        try {
            long t = parseTime(createdAt);
            if (t <= 0) return "";
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(t);
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.text.SimpleDateFormat fmt;
            if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                    && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) {
                fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            } else if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
                fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
            } else {
                fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            }
            return fmt.format(c.getTime());
        } catch (Exception e) { return ""; }
    }

    /**
     * 会话预览文本：<b>只读内存缓存</b>，绝不在这里做 I/O 或网络。
     *
     * <p>历史 bug：本方法原先直接返回本地库的原始 content（好友分支完全没有解密），
     * 而本地库存的是密文 → 列表显示 Base64 乱码；群分支则在主线程里调用会联网的解密，
     * 公钥缓存未命中时必然退化成「消息解密失败，密钥可能已变更」。
     * 现在统一由 {@link #refreshPreviewCacheAsync()} 在后台线程解密后写入缓存。
     */
    private String previewFor(Object o) {
        if (o instanceof Models.User) {
            String cached = friendPreviewCache.get(((Models.User) o).id);
            return cached != null ? cached : "";
        } else if (o instanceof Models.Group) {
            String cached = groupPreviewCache.get(((Models.Group) o).id);
            return cached != null ? cached : "";
        }
        return "";
    }

    /**
     * 后台批量预解密会话预览，完成后回主线程刷新列表。
     *
     * <p>必须在后台线程：解密需要对方公钥，缓存未命中时就地拉取（同步 HTTP），
     * 主线程会抛 NetworkOnMainThreadException 导致解密静默失败。
     */
    private void refreshPreviewCacheAsync() {
        final int generation = ++previewGeneration;
        final List<Models.User> friendsSnap = new ArrayList<>(friends);
        final List<Models.Group> groupsSnap = new ArrayList<>(myGroups);
        final int myId = prefs.getUserId();
        previewExecutor.execute(() -> {
            final java.util.Map<Integer, String> friendResult = new java.util.HashMap<>();
            final java.util.Map<Integer, String> groupResult = new java.util.HashMap<>();
            for (Models.User f : friendsSnap) {
                friendResult.put(f.id, resolveFriendPreview(f, myId));
            }
            for (Models.Group g : groupsSnap) {
                groupResult.put(g.id, resolveGroupPreview(g, myId));
            }
            // 期间又有更新的刷新，丢弃本次结果（单线程执行器保证不会并发写入）
            if (generation != previewGeneration) return;
            runOnUiThread(() -> {
                if (generation != previewGeneration || isFinishing() || isDestroyed()) return;
                friendPreviewCache.clear();
                friendPreviewCache.putAll(friendResult);
                groupPreviewCache.clear();
                groupPreviewCache.putAll(groupResult);
                chatAdapter.notifyDataSetChanged();
            });
        });
    }

    /** 好友会话预览：取本地库最后一条，解密后按类型映射。仅后台线程调用。 */
    private String resolveFriendPreview(Models.User f, int myId) {
        try {
            Models.Message m = com.familychat.app.utils.MessageDatabase
                    .getInstance(this).getLastMessage(myId, f.id);
            // 本地确实没有该会话的消息（还没聊过 / 全新安装尚未拉过历史）：
            // 这不是解密失败，保持空白，不能对着一个没聊过的好友喊「无法解密」。
            if (m == null) return "";
            String plain = com.familychat.app.utils.PreviewDecryptor.decryptForPreview(
                    m.content, m.msg_type, f.id, api, prefs);
            return previewText(plain, m.msg_type);
        } catch (Exception e) {
            // 任何异常也必须留下可见痕迹：绝不返回空串造成"会话预览永远空白"。
            Log.e(TAG, "resolveFriendPreview failed, friendId=" + f.id, e);
            return com.familychat.app.utils.PreviewDecryptor.PLACEHOLDER_FAILED;
        }
    }

    /** 群会话预览：用服务端 last_content（{成员id:密文} JSON）取自己那份解密。仅后台线程调用。 */
    private String resolveGroupPreview(Models.Group g, int myId) {
        try {
            // 群里还没有任何消息：正常空白（非失败）
            if (g.last_content == null || g.last_content.isEmpty()) return "";
            if ("system".equals(g.last_msg_type)) return previewText(g.last_content, g.last_msg_type);
            String plain = com.familychat.app.utils.GroupEncryptHelper.decryptGroupMessage(
                    g.last_content, g.last_sender_id, myId, api, prefs, g.last_msg_type);
            return previewText(normalizeGroupFailure(plain), g.last_msg_type);
        } catch (Exception e) {
            // 同上：异常必须可见，不留空缓存
            Log.e(TAG, "resolveGroupPreview failed, groupId=" + g.id, e);
            return com.familychat.app.utils.PreviewDecryptor.PLACEHOLDER_FAILED;
        }
    }

    /**
     * 列表预览里把群解密的两种失败占位符统一成 {@code [无法解密]}。
     * 群聊页内的气泡保留「发送者未将你列入加密名单 / 消息解密失败，密钥可能已变更」的更细区分，
     * 只有列表统一口径；未加密/格式异常则保留原提示。
     */
    private static String normalizeGroupFailure(String text) {
        if (com.familychat.app.utils.GroupEncryptHelper.PLACEHOLDER_FAILED.equals(text)
                || com.familychat.app.utils.GroupEncryptHelper.PLACEHOLDER_NO_SELF.equals(text)) {
            return com.familychat.app.utils.PreviewDecryptor.PLACEHOLDER_FAILED;
        }
        return text;
    }

    /**
     * 列表/通知预览的类型映射，委托 {@link MessagePreview}。
     *
     * <p>历史缺陷：本方法过去只认 image/video/voice/file 四种，转账（{@code msg_type="transfer"}，
     * content 是服务端明文 JSON）落进 default 分支被截断 30 字符，
     * 列表里显示成 {@code {"transfer_id":87,"amount":100}}。
     * 现已把映射收敛到单一实现，禁止再在本类里加类型分支。
     */
    private String previewText(String content, String type) {
        return MessagePreview.forList(content, type);
    }

    /** 群最后一条消息的毫秒时间（群列表排序用，解析失败回退 0）。 */
    private long groupSortMillis(Models.Group g) {
        if (g == null) return 0L;
        return ChatTimeFormatter.parseUtc(g.last_created_at);
    }

    /** Called from activity_main.xml android:onClick="onNewFriendClick" */
    public void onNewFriendClick(View v) {
        startActivity(new Intent(this, FriendRequestsActivity.class));
    }

    private void loadPendingReqs() {
        new Thread(() -> {
            try {
                Models.FriendRequestsResponse res = api.getPendingRequests();
                pendingReqs = res.requests != null ? res.requests : new ArrayList<>();
                runOnUiThread(() -> {
                    // 新朋友入口角标（QQ 风格，adapter 内渲染）
                    contactAdapter.setPendingCount(pendingReqs.size());
                });
            } catch (Exception e) { Log.e(TAG, "Exception", e); }
        }).start();
    }

    private View createPendingView(Models.FriendRequest req) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_friend_request, null, false);
        TextView tvName = v.findViewById(R.id.tvReqName);
        ImageView ivAv = v.findViewById(R.id.ivReqAvatar);
        tvName.setText(req.user.nickname);
        loadAvatar(ivAv, req.user.avatar, 44);
        v.findViewById(R.id.btnAccept).setOnClickListener(view -> {
            v.setEnabled(false);
            new Thread(() -> {
                try {
                    api.handleFriendRequest(req.friendship_id, "accept");
                    runOnUiThread(() -> { loadPendingReqs(); loadFriends(); });
                } catch (Exception e) {
                    Log.e(TAG, "Accept failed", e);
                    runOnUiThread(() -> { v.setEnabled(true); Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); });
                }
            }).start();
        });
        v.findViewById(R.id.btnReject).setOnClickListener(view -> {
            v.setEnabled(false);
            new Thread(() -> {
                try {
                    api.handleFriendRequest(req.friendship_id, "reject");
                    runOnUiThread(this::loadPendingReqs);
                } catch (Exception e) {
                    Log.e(TAG, "Reject failed", e);
                    runOnUiThread(() -> v.setEnabled(true));
                }
            }).start();
        });
        return v;
    }

    // ===== + Button (Miyu-style dropdown panel) =====
    private android.widget.PopupWindow plusMenuPopup;

    public void onAddFriendClick(View v) {
        try {
            if (plusMenuPopup != null && plusMenuPopup.isShowing()) {
                plusMenuPopup.dismiss();
                return;
            }
            View content = getLayoutInflater().inflate(R.layout.popup_main_menu, null);

            content.findViewById(R.id.menuGroupChat).setOnClickListener(item -> {
                plusMenuPopup.dismiss();
                startActivity(new Intent(this, CreateGroupActivity.class));
            });
            content.findViewById(R.id.menuAddFriend).setOnClickListener(item -> {
                plusMenuPopup.dismiss();
                startActivity(new Intent(this, AddFriendActivity.class));
            });
            content.findViewById(R.id.menuScan).setOnClickListener(item -> {
                plusMenuPopup.dismiss();
                startScan();
            });
            content.findViewById(R.id.menuPay).setOnClickListener(item -> {
                plusMenuPopup.dismiss();
                startActivity(new Intent(this, WalletActivity.class));
            });

            plusMenuPopup = new android.widget.PopupWindow(content,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            plusMenuPopup.setFocusable(true);
            plusMenuPopup.setOutsideTouchable(true);
            plusMenuPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            plusMenuPopup.setAnimationStyle(R.style.PopupMenuAnim);
            plusMenuPopup.setOnDismissListener(() -> plusMenuPopup = null);

            // 右对齐显示在加号按钮下方：面板右侧与按钮右侧对齐，顶部在按钮下缘
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            // END 重力下 x 从屏幕右缘算起：面板右缘落在按钮右缘
            int xFromRight = screenW - (loc[0] + v.getWidth());
            // 再向左让面板与按钮右缘对齐即可；y 从窗口顶部算，落到按钮下缘
            plusMenuPopup.showAtLocation(getWindow().getDecorView(),
                    android.view.Gravity.TOP | android.view.Gravity.END,
                    xFromRight, loc[1] + v.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "onAddFriendClick error", e);
            Toast.makeText(this, "功能暂不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void startScan() {
        new com.google.zxing.integration.android.IntentIntegrator(this)
                .setCaptureActivity(ScanActivity.class)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("扫描二维码")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UCrop.REQUEST_CROP) {
            if (resultCode == RESULT_OK) {
                Uri cropped = UCrop.getOutput(data);
                if (cropped != null) {
                    uploadCroppedAvatar(cropped);
                } else {
                    Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
                }
            } else if (resultCode == UCrop.RESULT_ERROR) {
                Throwable t = UCrop.getError(data);
                Toast.makeText(this, "裁剪失败: " + (t != null ? t.getMessage() : "未知错误"), Toast.LENGTH_LONG).show();
            }
            return;
        }
        com.google.zxing.integration.android.IntentResult result =
                com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            handleScanResult(result.getContents());
        }
    }

    private void handleScanResult(String content) {
        android.util.Log.d(TAG, "scan result: " + content);
        try {
            if (content == null) throw new IllegalArgumentException();
            if (content.startsWith("familychat://collect?id=")) {
                int userId = Integer.parseInt(content.substring("familychat://collect?id=".length()));
                Intent it = new Intent(this, ScanPayActivity.class);
                it.putExtra("user_id", userId);
                startActivity(it);
            } else if (content.startsWith("familychat://pay?id=")) {
                int userId = Integer.parseInt(content.substring("familychat://pay?id=".length()));
                Intent it = new Intent(this, ChargeRequestActivity.class);
                it.putExtra("user_id", userId);
                startActivity(it);
            } else if (content.startsWith("familychat://user?id=")) {
                int userId = Integer.parseInt(content.substring("familychat://user?id=".length()));
                Intent it = new Intent(this, UserProfileActivity.class);
                it.putExtra("user_id", userId);
                startActivity(it);
            } else {
                // 万能扫码：不是加好友/收付款 → 进结果页，是网址可打开网页
                Intent it = new Intent(this, ScanResultActivity.class);
                it.putExtra("content", content);
                startActivity(it);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无效的二维码", Toast.LENGTH_SHORT).show();
        }
    }

    /** 「我的」页「添加朋友」行：直接打开添加朋友页（不走工具栏+下拉菜单） */
    public void onAddFriendRowClick(View v) {
        startActivity(new Intent(this, AddFriendActivity.class));
    }

    /** 「我」页钱包入口（activity_main.xml onWalletClick）。 */
    public void onWalletClick(View v) {
        startActivity(new Intent(this, WalletActivity.class));
    }

    private void showAddFriendDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_friend, null);
        android.widget.EditText etSearch = dialogView.findViewById(R.id.etSearchUser);
        new MaterialAlertDialogBuilder(this).setTitle("添加好友").setView(dialogView)
                .setPositiveButton("搜索", (dialog, which) -> {
                    String q = etSearch.getText().toString().trim();
                    if (!q.isEmpty()) searchUsers(q);
                }).setNegativeButton("取消", null).show();
    }

    private void searchUsers(String query) {
        new Thread(() -> {
            try {
                Models.UserListResponse res = api.searchUsers(query);
                List<Models.User> users = res.users != null ? res.users : new ArrayList<>();
                runOnUiThread(() -> showSearchResults(users));
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    private void showSearchResults(List<Models.User> users) {
        if (users.isEmpty()) { Toast.makeText(this, "未找到用户", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[users.size()];
        for (int i = 0; i < users.size(); i++) {
            names[i] = users.get(i).nickname + " (@" + users.get(i).username + ")";
        }
        new MaterialAlertDialogBuilder(this).setTitle("搜索结果").setItems(names, (dialog, which) -> sendFriendRequest(users.get(which).id))
                .setNegativeButton("取消", null).show();
    }

    private void sendFriendRequest(int friendId) {
        new Thread(() -> {
            try { api.sendFriendRequest(friendId); runOnUiThread(() -> Toast.makeText(this, "好友请求已发送", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    // ===== Incoming Call Handler =====
    // Step 1 / C4：handleIncomingCall() 已整体删除，来电入口收敛到 :core 的 CallManager。
    // 随之一起下移的还有它内部的 reportCallStatus(fromUserId, "rejected", 0, …) ——
    // 现在由 CallManager.rejectCall() 负责（全仓唯一落点，避免出现两条"已拒绝"系统消息）。

    // ===== Edit Profile =====
    private String uploadedAvatarUrl = null;
    private android.widget.TextView editProgressTv;
    // 上传头像/资料：直接用共享 client（参数与改造前完全一致：
    // 15s/30s/30s + 跟随重定向 + 连接失败重试），且自带假域名模式 Dns。
    // 不再保留一份重复的 Builder —— 原实现与 ApiClient 的 client 参数逐项相同。
    private final OkHttpClient uploadClient = HttpClientProvider.shared();

    public void onEditProfileClick(View v) {
        uploadedAvatarUrl = null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // Nickname
        TextInputEditText etNick = new TextInputEditText(this);
        etNick.setHint("昵称");
        if (myProfile != null) etNick.setText(myProfile.nickname);
        layout.addView(etNick);

        // Avatar buttons: 相册 + 相机
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 16, 0, 0);
        btnRow.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, 120, 1f);
        btnParams.setMargins(8, 0, 8, 0);

        Button btnGallery = new Button(this);
        btnGallery.setText("相册");
        btnGallery.setTextColor(0xFFFFFFFF);
        btnGallery.setTextSize(18);
        btnGallery.setBackgroundColor(getColor(R.color.green));
        btnGallery.setPadding(0, 0, 0, 0);
        btnGallery.setMinHeight(0); btnGallery.setMinWidth(0);
        btnGallery.setAllCaps(false);
        btnGallery.setLayoutParams(btnParams);
        btnGallery.setOnClickListener(bv -> pickAvatarFromGallery());
        btnRow.addView(btnGallery);

        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, 120, 1f);
        btnParams2.setMargins(8, 0, 8, 0);

        Button btnCamera = new Button(this);
        btnCamera.setText("相机");
        btnCamera.setTextColor(0xFFFFFFFF);
        btnCamera.setTextSize(18);
        btnCamera.setBackgroundColor(getColor(R.color.green));
        btnCamera.setPadding(0, 0, 0, 0);
        btnCamera.setMinHeight(0); btnCamera.setMinWidth(0);
        btnCamera.setAllCaps(false);
        btnCamera.setLayoutParams(btnParams2);
        btnCamera.setOnClickListener(bv -> pickAvatarFromCamera());
        btnRow.addView(btnCamera);
        layout.addView(btnRow);

        // Progress
        editProgressTv = new android.widget.TextView(this);
        editProgressTv.setTextSize(12);
        editProgressTv.setTextColor(getColor(R.color.text_secondary));
        editProgressTv.setGravity(android.view.Gravity.CENTER);
        editProgressTv.setPadding(0, 8, 0, 0);
        editProgressTv.setVisibility(View.GONE);
        layout.addView(editProgressTv);

        new MaterialAlertDialogBuilder(this).setTitle("编辑资料").setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String nick = etNick.getText().toString().trim();
                    if (nick.isEmpty()) { Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show(); return; }
                    editProgressTv.setVisibility(View.VISIBLE);
                    editProgressTv.setText("保存中...");
                    new Thread(() -> {
                        try {
                            String av = myProfile != null ? myProfile.avatar : null;
                            String cl = myProfile != null ? myProfile.avatar_color : "#e8f5e9";
                            if (uploadedAvatarUrl != null) {
                                av = uploadedAvatarUrl;
                            }
                            api.updateProfile(nick, av, cl);
                            prefs.setMyNickname(nick);
                            if (uploadedAvatarUrl != null) {
                                prefs.setMyAvatar(uploadedAvatarUrl);
                            }
                            // 同步账号列表中的头像，账号管理页能看到新头像
                            prefs.saveCurrentAccount();
                            runOnUiThread(() -> { loadProfile(); });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                }).setNegativeButton("取消", null).show();
    }

    private Uri cameraAvatarUri;

    // 相册选择（ACTION_PICK 直接打开系统相册/画廊，避免打开文件管理）
    private final ActivityResultLauncher<Intent> avatarPickLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) startAvatarCrop(uri);
            }
        });
    // 备用：系统 Photo Picker（个别机型无 ACTION_PICK 处理器时兜底）
    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPickerLauncher =
        registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) startAvatarCrop(uri);
        });

    // 拍照（TakePicture：先建 FileProvider 输出 Uri，再启动相机）
    private final ActivityResultLauncher<Uri> avatarTakePictureLauncher =
        registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (Boolean.TRUE.equals(ok) && cameraAvatarUri != null) {
                startAvatarCrop(cameraAvatarUri);
            } else {
                Toast.makeText(this, "拍照已取消", Toast.LENGTH_SHORT).show();
            }
        });

    // CAMERA 运行时权限
    private final ActivityResultLauncher<String> cameraPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (Boolean.TRUE.equals(granted)) {
                launchAvatarCamera();
            } else {
                Toast.makeText(this, "需要相机权限，请在系统设置中开启", Toast.LENGTH_LONG).show();
            }
        });

    // POST_NOTIFICATIONS 运行时权限（与 cameraPermLauncher 同款；取代旧的 requestPermissions(...,200)）
    private final ActivityResultLauncher<String> notifPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (Boolean.TRUE.equals(granted)) {
                Log.d(TAG, "POST_NOTIFICATIONS granted");
            } else {
                // 拒绝（含"不再询问"）：给出可见引导，不再静默失效。
                showNotificationGuidance();
            }
        });

    /**
     * 首次进入时的通知权限申请（仅 Android 13+）。
     *
     * <p>只申请一次（落盘标记 {@link #PREF_NOTIF_PERM_ASKED}）：系统对"不再询问"之后的
     * 申请会静默失败——弹框不出现、回调立刻返回 false，于是每次启动都会退化成直接弹引导弹窗。
     * 之后如何再引导，全部交给 {@link #showNotificationGuidance()}。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;                     // 13 以下无运行时权限
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;       // 已授予
        if ("1".equals(prefs.getString(PREF_NOTIF_PERM_ASKED, "0"))) return;  // 本设备已弹过

        try {
            prefs.putString(PREF_NOTIF_PERM_ASKED, "1");
        } catch (Exception e) {
            Log.e(TAG, "save notif_perm_asked failed", e);
        }
        notifGuidanceShown = true;   // 本进程已就通知打扰过用户 → onResume 不再补一刀
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * 通知被关闭时的引导弹窗（权限未授予 / 系统通知总开关关闭，两条路径共用）。
     *
     * <p>按钮分两种：
     * <ul>
     *   <li>还能再问（{@code shouldShowRequestPermissionRationale == true}，即拒过一次但没勾"不再询问"）
     *       → 【去开启】重新申请；</li>
     *   <li>不能再问（首次前的静默判断 / 勾了"不再询问" / 13 以下的总开关被关）
     *       → 【去设置】深链系统通知设置页，用户手动打开。</li>
     * </ul>
     * 无论哪种都提供【以后再说】，不做强制拦截：通知缺失不阻塞任何功能。
     */
    private void showNotificationGuidance() {
        if (isFinishing() || isDestroyed()) return;

        boolean canAskAgain = Build.VERSION.SDK_INT >= 33
                && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("通知已关闭")
                .setMessage("关闭通知后，收到新消息时不会有提醒和提示音，也不会显示在通知栏。\n\n"
                        + "可在系统设置中重新开启。")
                .setNegativeButton("以后再说", null);

        if (canAskAgain) {
            builder.setPositiveButton("去开启", (d, w) ->
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS));
        } else {
            builder.setPositiveButton("去设置", (d, w) -> openNotificationSettings());
        }
        builder.show();
    }

    /**
     * 跳系统通知设置页。
     *
     * <p>API 26+ 用 {@code ACTION_APP_NOTIFICATION_SETTINGS + EXTRA_APP_PACKAGE}
     * 直达本应用的通知页；API 24/25 没有该页面，回落到应用详情页。
     * 个别 ROM 缺 Action（ActivityNotFoundException）时同样回落到详情页，再失败只记日志——不崩。
     */
    private void openNotificationSettings() {
        Uri pkgUri = Uri.fromParts("package", getPackageName(), null);
        try {
            Intent it;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri);
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            Log.e(TAG, "open notification settings failed, fallback to app details", e);
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Log.e(TAG, "open app details settings failed", e2);
            }
        }
    }

    /** activity_main.xml: android:onClick="onAvatarClick" — 点头像：屏幕居中放大预览头像 */
    public void onAvatarClick(View v) {
        // 原"设置头像"菜单已移除（更换头像改到"编辑资料"里操作），点击头像改为居中放大预览。
        if (myProfile != null && myProfile.avatar != null && myProfile.avatar.startsWith("/")) {
            String full = ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + myProfile.avatar,
                    prefs.getToken());
            new ImagePreviewDialog(this, full).show();
        } else {
            Toast.makeText(this, "当前没有自定义头像", Toast.LENGTH_SHORT).show();
        }
    }

    /** 编辑资料弹窗的「相册」按钮复用此路径（ACTION_PICK 系统相册） */
    private void pickAvatarFromGallery() {
        Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (pick.resolveActivity(getPackageManager()) != null) {
            avatarPickLauncher.launch(pick);
        } else {
            avatarPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }
    }

    /** 编辑资料弹窗的「相机」按钮复用此路径（含 CAMERA 运行时权限检查） */
    private void pickAvatarFromCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchAvatarCamera();
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchAvatarCamera() {
        try {
            java.io.File photoFile = java.io.File.createTempFile("avatar_", ".jpg", getCacheDir());
            cameraAvatarUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photoFile);
            avatarTakePictureLauncher.launch(cameraAvatarUri);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动相机", Toast.LENGTH_SHORT).show();
        }
    }

    /** 启动 uCrop 1:1 正方形裁剪，输出到缓存目录，最大 400x400 */
    private void startAvatarCrop(Uri sourceUri) {
        try {
            java.io.File dest = new java.io.File(getCacheDir(), "crop_avatar_" + System.currentTimeMillis() + ".jpg");
            UCrop.Options options = new UCrop.Options();
            options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
            options.setCompressionQuality(90);
            UCrop.of(sourceUri, Uri.fromFile(dest))
                    .withOptions(options)
                    .withAspectRatio(1, 1)
                    .withMaxResultSize(400, 400)
                    .start(this);
        } catch (Exception e) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    /** 上传裁剪后的头像并刷新显示 */
    private void uploadCroppedAvatar(Uri croppedUri) {
        if (editProgressTv != null) {
            runOnUiThread(() -> { editProgressTv.setVisibility(View.VISIBLE); editProgressTv.setText("上传头像..."); });
        }
        new Thread(() -> {
            try {
                byte[] bytes;
                try (InputStream is = "file".equals(croppedUri.getScheme())
                        ? new java.io.FileInputStream(croppedUri.getPath())
                        : getContentResolver().openInputStream(croppedUri)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    bytes = baos.toByteArray();
                }
                android.util.Log.d(TAG, "Avatar cropped bytes: " + bytes.length);

                String uploadUrl = ServerProtocol.baseUrl(prefs.getServer()) + "/api/user/avatar";
                okhttp3.RequestBody body = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "avatar.jpg",
                        okhttp3.RequestBody.create(bytes, okhttp3.MediaType.parse("image/jpeg")))
                    .build();
                okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", "Bearer " + prefs.getToken())
                    .post(body).build();
                okhttp3.Response resp = uploadClient.newCall(req).execute();
                String respBody = resp.body() != null ? resp.body().string() : "";
                android.util.Log.d(TAG, "Avatar upload response: " + resp.code() + " " + respBody);

                if (resp.isSuccessful()) {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(respBody).getAsJsonObject();
                    String avatarUrl = null;
                    if (json.has("data") && json.getAsJsonObject("data").has("avatar_url")) {
                        avatarUrl = json.getAsJsonObject("data").get("avatar_url").getAsString();
                    } else if (json.has("user") && json.getAsJsonObject("user").has("avatar")) {
                        avatarUrl = json.getAsJsonObject("user").get("avatar").getAsString();
                    }
                    if (avatarUrl == null) throw new Exception("服务器未返回头像地址");
                    final String finalAvatarUrl = avatarUrl;

                    uploadedAvatarUrl = avatarUrl;
                    prefs.setMyAvatar(avatarUrl);
                    if (myProfile != null) myProfile.avatar = avatarUrl;
                    runOnUiThread(() -> {
                        if (editProgressTv != null) editProgressTv.setText("头像上传成功，请点保存");
                        updateProfileView();
                        refreshMyAvatar(finalAvatarUrl);
                        loadFriends();
                    });
                } else {
                    String msg = parseAvatarError(resp.code(), respBody);
                    runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "Avatar upload error", e);
                runOnUiThread(() -> Toast.makeText(this, "上传失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String parseAvatarError(int code, String body) {
        if (code == 404) return "服务器接口未找到，请检查服务端";
        if (code == 413) return "图片过大，不能超过5MB";
        try {
            com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (j.has("error")) return j.get("error").getAsString();
        } catch (Exception ignored) {}
        return "上传失败 (" + code + ")";
    }

    /** 上传成功后强制刷新「我的」页头像（文件名每次含新 uuid，绕开 Glide 缓存） */
    private void refreshMyAvatar(String url) {
        try {
            Glide.with(this)
                    .load(ServerProtocol.withToken(
                            ServerProtocol.baseUrl(prefs.getServer()) + url,
                            prefs.getToken()))
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .circleCrop()
                    .into((ImageView) findViewById(R.id.ivMyAvatar));
        } catch (Exception ignored) {}
    }

    private void pinChat(int friendId) {
        // 存储与解析统一走 PreferencesManager（旧实现裸拼 "," + id + ","，首置顶永远命中不了，
        // 取消也不命中，见 setFriendPinned 的注释）
        prefs.setFriendPinned(friendId, !prefs.isFriendPinned(friendId));
        loadFriends();
    }

    /** 显示名：有备注显示备注，否则显示昵称。 */
    private String friendDisplayName(Models.User f) {
        return (f != null && f.remark != null && !f.remark.isEmpty()) ? f.remark : (f != null ? f.nickname : "");
    }

    // ===== 会话操作（好友/群） =====
    private void openFriendChat(Models.User f) {
        Intent it = new Intent(MainActivity.this, ChatActivity.class);
        it.putExtra("friend_id", f.id);
        it.putExtra("friend_name", f.nickname);
        it.putExtra("friend_username", f.username);
        it.putExtra("friend_avatar", f.avatar);
        it.putExtra("friend_remark", f.remark);
        it.putExtra("friend_online", f.is_online);
        startActivity(it);
    }

    private void openGroupChat(Models.Group g) {
        Intent it = new Intent(MainActivity.this, GroupChatActivity.class);
        it.putExtra("group_id", g.id);
        it.putExtra("group_name", g.name != null ? g.name : "群聊");
        startActivity(it);
    }

    private void clearFriendChat(Models.User f) {
        new MaterialAlertDialogBuilder(MainActivity.this)
            .setTitle("清空记录").setMessage("确定清空与该好友的所有聊天记录?")
            .setPositiveButton("清空", (dd, ww) -> new Thread(() -> {
                try { api.clearConversation(f.id);
                    runOnUiThread(() -> { loadFriends(); });
                } catch (Exception ex) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "失败", Toast.LENGTH_SHORT).show()); }
            }).start()).setNegativeButton("取消", null).show();
    }

    private void deleteFriend(Models.User f) {
        new MaterialAlertDialogBuilder(MainActivity.this)
            .setTitle("删除会话").setMessage("确定删除该好友? 聊天记录也将被清空。")
            .setPositiveButton("删除", (dd, ww) -> new Thread(() -> {
                try { api.deleteFriend(f.id);
                    runOnUiThread(() -> { loadFriends(); });
                } catch (Exception ex) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "失败", Toast.LENGTH_SHORT).show()); }
            }).start()).setNegativeButton("取消", null).show();
    }

    /** 群置顶开关（服务端 PUT settings + 本地缓存）。 */
    private void toggleGroupPin(Models.Group g) {
        final boolean next = !prefs.getGroupPinned(g.id);
        new Thread(() -> {
            try {
                api.updateGroupSettings(g.id, next ? 1 : 0, null);
                prefs.setGroupPinned(g.id, next);
                runOnUiThread(() -> { buildConversations(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 群免打扰开关（服务端 PUT settings + 本地缓存）。 */
    private void toggleGroupQuiet(Models.Group g) {
        final boolean next = !prefs.getGroupQuiet(g.id);
        new Thread(() -> {
            try {
                api.updateGroupSettings(g.id, null, next ? 1 : 0);
                prefs.setGroupQuiet(g.id, next);
                runOnUiThread(() -> { buildConversations(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 退群（会话列表侧滑）。 */
    private void leaveGroup(Models.Group g) {
        new MaterialAlertDialogBuilder(MainActivity.this)
            .setTitle("退出群聊").setMessage("确定退出群 " + g.name + " ?")
            .setPositiveButton("退出", (dd, ww) -> new Thread(() -> {
                try {
                    api.removeGroupMember(g.id, prefs.getUserId());
                    prefs.setGroupUnread(g.id, 0);
                    NotificationHelper.cancelGroupNotification(MainActivity.this, g.id);
                    runOnUiThread(() -> { loadGroups(); });
                } catch (Exception ex) {
                    runOnUiThread(() -> Toast.makeText(this, "退出失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start()).setNegativeButton("取消", null).show();
    }

    /** 好友会话最后一条消息的时间字符串（本地库）。 */
    private String lastMsgString(Models.User f) {
        try {
            Models.Message m = com.familychat.app.utils.MessageDatabase
                    .getInstance(this).getLastMessage(prefs.getUserId(), f.id);
            return m != null ? m.created_at : "";
        } catch (Exception e) { return ""; }
    }

    /** 群头像（无自定义头像时用默认群图标）。 */
    private void loadGroupAvatar(ImageView iv, String avatar) {
        try {
            if (avatar != null && avatar.startsWith("/")) {
                Glide.with(this).load(ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                        prefs.getToken())).circleCrop().into(iv);
            } else {
                iv.setImageResource(R.drawable.ic_group);
            }
        } catch (Exception e) { iv.setImageResource(R.drawable.ic_group); }
    }

    // ===== Chat List Adapter (swipe-to-reveal) =====
    class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {
        private View openForeground = null;
        private int actionsWidthPx = 0;

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_chat, parent, false);
            if (actionsWidthPx == 0) {
                actionsWidthPx = (int) (210 * getResources().getDisplayMetrics().density);
            }
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            final Object item = conversations.get(i);
            boolean isFriend = item instanceof Models.User;
            final Models.User f = isFriend ? (Models.User) item : null;
            final Models.Group g = isFriend ? null : (Models.Group) item;

            // 名称 / 头像
            h.name.setText(isFriend ? friendDisplayName(f)
                    : (g.name != null && !g.name.isEmpty()) ? g.name : "群聊");
            if (isFriend) loadAvatar(h.avatar, f.avatar, 50);
            else loadGroupAvatar(h.avatar, g.avatar);

            // 未读红点
            int unread = isFriend
                    ? Integer.parseInt(prefs.getString("unread_" + f.id, "0"))
                    : prefs.getGroupUnread(g.id);
            if (unread > 0) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                h.badge.setVisibility(View.GONE);
            }

            // 免打扰标记：好友读 friend_quiet_<id>，群读 group_quiet_<id>；双向赋值以适配 RecyclerView 复用
            boolean quiet = isFriend ? prefs.getFriendQuiet(f.id) : prefs.getGroupQuiet(g.id);
            h.mute.setVisibility(quiet ? View.VISIBLE : View.GONE);

            // 预览 + 时间
            h.preview.setText(previewFor(item));
            h.time.setText(isFriend
                    ? formatListTime(lastMsgString(f))
                    : formatListTime(g.last_created_at));

            // 重置复用视图的滑动状态
            h.foreground.setTranslationX(0);

            // 侧滑动作按钮：好友=置顶/清空/删除，群=置顶/免打扰/退群
            h.btnPin.setText(isFriend ? "置顶" : "置顶");
            h.btnClear.setText(isFriend ? "清空" : "免打扰");
            h.btnDelete.setText(isFriend ? "删除" : "退群");
            h.btnPin.setOnClickListener(vv -> {
                animateClose(h);
                if (isFriend) pinChat(f.id);
                else toggleGroupPin(g);
            });
            h.btnClear.setOnClickListener(vv -> {
                animateClose(h);
                if (isFriend) clearFriendChat(f);
                else toggleGroupQuiet(g);
            });
            h.btnDelete.setOnClickListener(vv -> {
                animateClose(h);
                if (isFriend) deleteFriend(f);
                else leaveGroup(g);
            });

            // Swipe gesture on foreground — fixed for RecyclerView scroll conflict
            h.foreground.setOnTouchListener(new View.OnTouchListener() {
                float startX, startY, lastX;
                boolean isSwiping = false, isHorizontal = false;
                boolean parentDisallowed = false;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getAction()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            startX = e.getX();
                            startY = e.getY();
                            lastX = startX;
                            isSwiping = false;
                            isHorizontal = false;
                            parentDisallowed = false;
                            // Close other open item
                            if (openForeground != null && openForeground != v) {
                                openForeground.animate().translationX(0).setDuration(150).start();
                                openForeground = null;
                            }
                            return true;

                        case android.view.MotionEvent.ACTION_MOVE:
                            float dx = e.getX() - startX;
                            float dy = e.getY() - startY;
                            if (!isSwiping && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                                isSwiping = true;
                                isHorizontal = Math.abs(dx) > Math.abs(dy);
                                if (isHorizontal) {
                                    // Prevent RecyclerView from stealing the touch once horizontal swipe confirmed
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                    parentDisallowed = true;
                                }
                            }
                            if (isSwiping && isHorizontal) {
                                float cur = v.getTranslationX();
                                float raw = e.getX() - lastX;
                                float next = Math.max(-actionsWidthPx, Math.min(0, cur + raw));
                                v.setTranslationX(next);
                                lastX = e.getX();
                                return true;
                            }
                            break;

                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            if (parentDisallowed) {
                                v.getParent().requestDisallowInterceptTouchEvent(false);
                                parentDisallowed = false;
                            }
                            if (isSwiping && isHorizontal) {
                                float trans = v.getTranslationX();
                                if (trans < -actionsWidthPx / 3) {
                                    v.animate().translationX(-actionsWidthPx).setDuration(200).start();
                                    openForeground = v;
                                } else {
                                    v.animate().translationX(0).setDuration(200).start();
                                    openForeground = null;
                                }
                                return true;
                            }
                            // Tap (not a swipe): open chat if closed, close if open
                            if (!isSwiping) {
                                if (v.getTranslationX() != 0) {
                                    v.animate().translationX(0).setDuration(150).start();
                                    openForeground = null;
                                } else {
                                    if (isFriend) openFriendChat(f);
                                    else openGroupChat(g);
                                }
                            }
                            break;
                    }
                    return false;
                }
            });
        }

        private void animateClose(VH h) {
            h.foreground.animate().translationX(0).setDuration(150).start();
            if (openForeground == h.foreground) openForeground = null;
        }

        @Override public int getItemCount() { return conversations.size(); }

        class VH extends RecyclerView.ViewHolder {
            View foreground;
            TextView name, preview, time, btnPin, btnClear, btnDelete, badge;
            ImageView avatar, mute;
            VH(View v) {
                super(v);
                foreground = v.findViewById(R.id.foreground);
                name = v.findViewById(R.id.tvName);
                preview = v.findViewById(R.id.tvPreview);
                time = v.findViewById(R.id.tvTime);
                avatar = v.findViewById(R.id.ivAvatar);
                mute = v.findViewById(R.id.ivMute);
                btnPin = v.findViewById(R.id.btnPin);
                btnClear = v.findViewById(R.id.btnClear);
                btnDelete = v.findViewById(R.id.btnDelete);
                badge = v.findViewById(R.id.tvUnreadBadge);
            }
        }
    }

    // ===== QQ 联系人 Adapter：功能入口 + 字母分组 + 联系人行 =====
    class ContactAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        static final int TYPE_ENTRY = 0, TYPE_HEADER = 1, TYPE_USER = 2;
        private static final Object ENTRY_NEW_FRIEND = new Object();
        private static final Object ENTRY_MY_GROUPS = new Object();
        /** 扁平条目：String=组头字母, Models.User=联系人, ENTRY_*=功能入口。 */
        private final List<Object> items = new ArrayList<>();
        /** 字母 -> 列表位置（索引条跳转用）。 */
        final java.util.Map<String, Integer> letterPos = new java.util.LinkedHashMap<>();
        private int pendingCount = 0;

        /** 联系人数据变化后重建分组（loadFriends 调用）。按显示名（备注优先）拼音分组排序。 */
        void rebuild() {
            items.clear();
            letterPos.clear();
            items.add(ENTRY_NEW_FRIEND);
            items.add(ENTRY_MY_GROUPS);
            java.util.Map<String, List<Models.User>> byLetter = new java.util.TreeMap<>();
            for (Models.User f : friends) {
                byLetter.computeIfAbsent(firstLetter(friendDisplayName(f)), k -> new ArrayList<>()).add(f);
            }
            for (java.util.Map.Entry<String, List<Models.User>> e : byLetter.entrySet()) {
                letterPos.put(e.getKey(), items.size());
                items.add(e.getKey());          // 组头字母
                List<Models.User> group = new ArrayList<>(e.getValue());
                // 组内按显示名拼音排序（备注优先，与 QQ 一致）
                group.sort((a, b) -> pinyinOf(friendDisplayName(a)).compareTo(pinyinOf(friendDisplayName(b))));
                items.addAll(group);            // 该组联系人
            }
            notifyDataSetChanged();
        }

        /**
         * 索引条兜底查找。letterPos 是稀疏映射：只有"该首字母真有联系人"时才有 key，
         * 所以点一个不存在分组的字母原本会静默什么都不做。
         *
         * <p>规则（QQ 行为）：取比 letter 大的最小 key（跳到最近的后续分组）；
         * 若没有更大的 key（例如点 Z 但 Z 组不存在），取最大的 key（跳到最后一组）；
         * 映射为空时返回 null（不动）。
         *
         * <p>这里按 key 值比较而非依赖迭代顺序，因此与 rebuild() 里 TreeMap 的顺序、
         * 也就是列表实际渲染顺序天然一致，且不受 Map 实现变化影响。
         * 注意 '#' 的 ASCII 小于 'A'，TreeMap 中 '#' 组排在 A 之前，所以"最后一组"
         * 确实是 Z 侧而不是 '#'；本方法按 key 值比较，结论与之一致。
         */
        Integer nearestLetterPos(String letter) {
            String next = null, last = null;
            for (String k : letterPos.keySet()) {
                if (last == null || k.compareTo(last) > 0) last = k;
                if (k.compareTo(letter) > 0 && (next == null || k.compareTo(next) < 0)) next = k;
            }
            String pick = next != null ? next : last;
            return pick != null ? letterPos.get(pick) : null;
        }

        void setPendingCount(int n) {
            pendingCount = n;
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) {
            Object o = items.get(position);
            if (o == ENTRY_NEW_FRIEND || o == ENTRY_MY_GROUPS) return TYPE_ENTRY;
            if (o instanceof Models.User) return TYPE_USER;
            return TYPE_HEADER;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_ENTRY)
                return new EntryVH(getLayoutInflater().inflate(R.layout.item_contact_entry, parent, false));
            if (viewType == TYPE_HEADER)
                return new HeaderVH(getLayoutInflater().inflate(R.layout.item_contact_header, parent, false));
            return new UserVH(getLayoutInflater().inflate(R.layout.item_contact, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = items.get(position);
            if (holder instanceof EntryVH) {
                EntryVH h = (EntryVH) holder;
                boolean isNewFriend = o == ENTRY_NEW_FRIEND;
                h.title.setText(isNewFriend ? "新朋友" : "我的群聊");
                h.icon.setImageResource(isNewFriend ? R.drawable.ic_person_add : R.drawable.ic_group);
                if (isNewFriend) {
                    h.sub.setVisibility(View.GONE);
                    h.badge.setVisibility(pendingCount > 0 ? View.VISIBLE : View.GONE);
                    h.badge.setText(String.valueOf(pendingCount));
                } else {
                    h.sub.setVisibility(View.VISIBLE);
                    h.sub.setText(myGroups.size() + "个群聊");
                    h.badge.setVisibility(View.GONE);
                }
                h.itemView.setOnClickListener(v -> {
                    if (isNewFriend) onNewFriendClick(null);
                    else openMyGroups();
                });
            } else if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).letter.setText((String) o);
            } else if (holder instanceof UserVH) {
                Models.User f = (Models.User) o;
                UserVH h = (UserVH) holder;
                h.name.setText(friendDisplayName(f));
                loadAvatar(h.avatar, f.avatar, 42);
                // QQ 副标题：备注（无则显示账号）
                String sub = (f.remark != null && !f.remark.trim().isEmpty()) ? f.remark
                        : (f.username != null ? f.username : null);
                h.sub.setVisibility(sub != null ? View.VISIBLE : View.GONE);
                if (sub != null) h.sub.setText(sub);
                h.dot.setVisibility(f.is_online ? View.VISIBLE : View.GONE);
                h.itemView.setOnClickListener(v -> {
                    Intent it = new Intent(MainActivity.this, ChatActivity.class);
                    it.putExtra("friend_id", f.id);
                    it.putExtra("friend_name", f.nickname);
                    it.putExtra("friend_username", f.username);
                    it.putExtra("friend_avatar", f.avatar);
                    it.putExtra("friend_remark", f.remark);
                    it.putExtra("friend_online", f.is_online);
                    startActivity(it);
                });
            }
        }

        @Override public int getItemCount() { return items.size(); }

        class EntryVH extends RecyclerView.ViewHolder {
            ImageView icon; TextView title, sub, badge;
            EntryVH(View v) { super(v);
                icon = v.findViewById(R.id.ivEntryIcon);
                title = v.findViewById(R.id.tvEntryTitle);
                sub = v.findViewById(R.id.tvEntrySub);
                badge = v.findViewById(R.id.tvEntryBadge); }
        }
        class HeaderVH extends RecyclerView.ViewHolder {
            TextView letter;
            HeaderVH(View v) { super(v); letter = (TextView) v; }
        }
        class UserVH extends RecyclerView.ViewHolder {
            TextView name, sub; ImageView avatar; View dot;
            UserVH(View v) { super(v);
                name = v.findViewById(R.id.tvContactName);
                sub = v.findViewById(R.id.tvContactSub);
                avatar = v.findViewById(R.id.ivContactAvatar);
                dot = v.findViewById(R.id.vOnlineDot); }
        }
    }

    /** 全拼音（小写）：汉字转拼音、英文/数字原样、其它字符保留（用于组内排序）。 */
    private static String pinyinOf(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') { sb.append(c); continue; }
            if (c >= 'A' && c <= 'Z') { sb.append((char) (c + 32)); continue; }
            try {
                String[] arr = net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(c);
                if (arr != null && arr.length > 0) sb.append(arr[0]);
                else sb.append(c);
            } catch (Exception ignored) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** QQ 拼音首字母：汉字取拼音首字母大写；英文/数字原样；其它归 '#'。 */
    private static String firstLetter(String s) {
        String py = pinyinOf(s);
        if (py.isEmpty()) return "#";
        char c = py.charAt(0);
        if (c >= 'a' && c <= 'z') return String.valueOf((char) (c - 32));
        if (c >= 'A' && c <= 'Z') return String.valueOf(c);
        return "#";
    }

    // ===== QQ 联系人页：右侧索引条 + 我的群聊 =====
    private void initQqContacts() {
        indexBar = findViewById(R.id.indexBar);
        tvIndexBubble = findViewById(R.id.tvIndexBubble);
        contactGroupsContainer = findViewById(R.id.contactGroupsContainer);
        rvContactGroups = findViewById(R.id.rvContactGroups);
        contactGroupsAdapter = new ContactGroupAdapter();
        rvContactGroups.setLayoutManager(new LinearLayoutManager(this));
        rvContactGroups.setAdapter(contactGroupsAdapter);
        indexBar.setOnLetterChangedListener(letter -> {
            Integer pos = contactAdapter.letterPos.get(letter);
            if (pos == null) pos = contactAdapter.nearestLetterPos(letter);  // 缺组 → 最近的后续组
            if (pos != null && rvContacts != null) rvContacts.scrollToPosition(pos);
            if (tvIndexBubble != null) {
                tvIndexBubble.setText(letter);
                tvIndexBubble.setVisibility(View.VISIBLE);
                tvIndexBubble.removeCallbacks(hideIndexBubble);
                tvIndexBubble.postDelayed(hideIndexBubble, 500);
            }
        });
    }

    private final Runnable hideIndexBubble = () -> {
        if (tvIndexBubble != null) tvIndexBubble.setVisibility(View.GONE);
    };

    /** 通讯录顶部搜索框：进入全站搜索页。 */
    public void onContactSearchClick(View v) {
        startActivity(new Intent(this, SearchUsersActivity.class));
    }

    /** "我的群聊"入口：显示群聊列表。 */
    private void openMyGroups() {
        if (contactGroupsContainer != null) {
            contactGroupsContainer.setVisibility(View.VISIBLE);
            if (contactGroupsAdapter != null) contactGroupsAdapter.notifyDataSetChanged();
        }
    }

    /** 我的群聊列表返回按钮。 */
    public void onContactGroupsBack(View v) {
        if (contactGroupsContainer != null) contactGroupsContainer.setVisibility(View.GONE);
    }

    /** 我的群聊列表"创建群聊"。 */
    public void onCreateGroupFromContacts(View v) {
        startActivity(new Intent(this, CreateGroupActivity.class));
    }

    /** 通讯录-群聊 tab 的纵向群列表。 */
    class ContactGroupAdapter extends RecyclerView.Adapter<ContactGroupAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_contact_group, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            Models.Group g = myGroups.get(i);
            String name = (g.name != null && !g.name.isEmpty()) ? g.name : "群聊";
            h.name.setText(name);
            loadGroupAvatar(h.avatar, g.avatar);
            h.count.setText(g.member_count + "人");
            h.itemView.setOnClickListener(v -> openGroupChat(g));
        }
        @Override public int getItemCount() { return myGroups.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView name, count; ImageView avatar;
            VH(View v) { super(v); name = v.findViewById(R.id.tvContactGroupName); count = v.findViewById(R.id.tvContactGroupCount); avatar = v.findViewById(R.id.ivContactGroupAvatar); }
        }
    }

    // ===== Utility =====
    private void loadAvatar(ImageView iv, String avatar, int size) {
        try {
            if (avatar != null && avatar.startsWith("/")) {
                Glide.with(this).load(ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                        prefs.getToken())).circleCrop().into(iv);
            } else {
                iv.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        } catch (Exception e) { iv.setImageResource(R.drawable.ic_avatar_placeholder); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页切换黑夜/白天模式回来后，若当前主题与保存的模式不一致则重建刷新
        boolean savedNight = prefs.getNightMode();
        int uiMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean currentNight = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (savedNight != currentNight) {
            recreate();
            return;
        }
        // Debounce: avoid calling loadFriends() too frequently
        long now = System.currentTimeMillis();
        // 单聊详情面板里改过置顶/免打扰 → 这一次绕过防抖（标记读后即清，只绕过一次）
        boolean convDirty = prefs.consumeConversationsDirty();
        if (convDirty || now - lastRefreshTime > REFRESH_DEBOUNCE_MS) {
            lastRefreshTime = now;
            loadFriends();
        } else {
            Log.d(TAG, "Skipping loadFriends in onResume (debounced)");
        }
        // 从群聊/建群/群详情返回时刷新群列表（未读红点、新群）
        loadGroups();

        // 通知是否可用：每次回到前台轻量检查一次（areNotificationsEnabled 无 I/O），
        // 但同一进程只引导一次 —— 否则每次切前后台都弹窗骚扰。
        // 覆盖两种"通知没了"：13+ 的 POST_NOTIFICATIONS 被拒，以及 13 以下用户关掉了通知总开关；
        // 也覆盖"用户刚从系统设置里关掉又切回来"的场景。
        if (!notifGuidanceShown && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notifGuidanceShown = true;
            showNotificationGuidance();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ⚠️ 只反注册监听器，绝不能调用 PushConnection.stop()：
        // 连接现在归 ForegroundService 驱动，stop() 会在用户退出主界面时直接切断后台推送。
        // 本页销毁后连接必须继续存活，由 ForegroundService 持有。
        PushConnection.removeListener(wsListener);
        wsListener = null;
        debounceHandler.removeCallbacksAndMessages(null);
        previewExecutor.shutdownNow();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
