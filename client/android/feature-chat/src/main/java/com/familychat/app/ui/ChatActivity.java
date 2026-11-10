package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.Toast;

import com.familychat.app.feature_chat.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.MessageDatabase;
import com.familychat.app.utils.MessageEncryptor;
import com.familychat.app.utils.PeerKeyPinStore;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.familychat.app.ui.chat.ChatDetailPanel;
import com.familychat.app.ui.chat.SwipeContainer;
import com.familychat.app.ui.ImagePreviewDialog;
import com.familychat.app.ui.voice.VoiceRecordOverlay;
import com.familychat.app.ui.voice.WaveformView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";
    /** 单聊解密失败占位（与群聊 GroupEncryptHelper.PLACEHOLDER_FAILED 文案对齐）。 */
    private static final String E2EE_DECRYPT_FAILED = "[消息解密失败，密钥可能已变更]";
    /** Tracks which friend's chat is currently visible. null = no chat open. */
    public static volatile int currentFriendId = -1;

    private PreferencesManager prefs;
    private ApiClient api;
    /**
     * 本页对推送连接的监听器。
     *
     * <p>连接不再由本页持有 —— 唯一持有者是 :core 的 {@code PushConnection}
     * （由 ForegroundService 驱动生命周期）。这里只留引用供 onDestroy 反注册。
     */
    private WebSocketManager.Listener wsListener;
    private Models.User friend;
    private String friendAvatar, friendAvatarColor;
    private String myAvatar, myAvatarColor;
    private List<Models.Message> messages = new ArrayList<>();
    private final Gson gson = new Gson();
    private MessageAdapter adapter;
    private int friendId;
    private int scrollToMsgId = 0; // 查找聊天记录跳转定位的消息ID
    // 统一用 HttpClientProvider 的共享 client（自带假域名模式 Dns），
    // 不再自建实例：裸 new OkHttpClient() 会缺 Dns 配置且各自泄漏连接池。
    private OkHttpClient httpClient = HttpClientProvider.shared();
    private VoiceRecordOverlay voiceOverlay;
    private MessageDatabase localDb;
    private RecyclerView rvMessages;
    private View plusPanel;
    private EditText etMessage;
    private android.widget.ImageButton btnPlus;
    private int navInsetBottom = 0;
    private SwipeContainer swipeContainer;
    private ChatDetailPanel detailPanelHelper;
    // Polling fallback
    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private static final long POLL_INTERVAL_MS = 5000;

    // Voice playback — instance fields (NOT static): a static View reference would pin the
    // Activity forever; per-instance players also avoid two chats sharing one player.
    // Note: rotating the screen stops any playing voice (the old Activity releases the player).
    private MediaPlayer voicePlayer = null;
    private int playingPosition = -1;
    private WaveformView playingWaveform = null;
    // Voice preload cache: URL → local cache file path (process-wide, thread-safe, size-capped)
    private static final int VOICE_CACHE_MAX = 200;
    private static java.util.concurrent.ConcurrentHashMap<String, String> voiceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.concurrent.Executor voicePreloader = java.util.concurrent.Executors.newFixedThreadPool(3);

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    /** 聊天转账：TransferSendActivity 成功返回后，把服务端 transfer 消息即时上屏。 */
    private final ActivityResultLauncher<Intent> transferLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String json = result.getData().getStringExtra("json");
                    if (json != null && !json.isEmpty()) {
                        try {
                            Models.Message saved = parseSentMessage(json, json, "transfer");
                            if (saved != null) {
                                localDb.insertMessage(saved);
                                messages.add(saved);
                                adapter.notifyAppended();
                                rvMessages.scrollToPosition(adapter.rowCount() - 1);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "transfer append", e);
                        }
                    }
                }
            });

    /** 显示名：有备注显示备注，否则显示昵称。 */
    private String friendName(Models.User u) {
        return (u != null && u.remark != null && !u.remark.isEmpty()) ? u.remark : (u != null ? u.nickname : "");
    }

    /** 查找聊天记录跳转：滚动到指定消息并高亮。 */
    private void scrollToTargetMessage() {
        if (scrollToMsgId <= 0) return;
        int idx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id == scrollToMsgId) { idx = i; break; }
        }
        // 消息下标 → 显示行下标（中间可能有时间行）
        final int target = adapter != null ? adapter.rowIndexOfMessage(idx) : -1;
        if (target < 0) {
            Toast.makeText(this, "未定位到该消息（记录较早）", Toast.LENGTH_SHORT).show();
            scrollToMsgId = 0;
            return;
        }
        RecyclerView.LayoutManager lm = rvMessages.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(target, dp(60));
        }
        rvMessages.post(() -> highlightMessage(target));
    }

    private void highlightMessage(int pos) {
        RecyclerView.ViewHolder vh = rvMessages.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView != null) {
            android.graphics.drawable.Drawable orig = vh.itemView.getBackground();
            vh.itemView.setBackgroundColor(0x332196F3);
            vh.itemView.postDelayed(() -> {
                if (vh.itemView != null) vh.itemView.setBackground(orig);
            }, 2000);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_chat);
        } catch (Exception e) {
            Log.d(TAG, "err: " + e.getMessage());
            finish();
            return;
        }

        try {
            // Fix: add bottom padding for system navigation bar
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                    navInsetBottom = insets.getSystemWindowInsetBottom();
                    applyNavPadding();
                    return insets;
                });
                rootView.requestApplyInsets();
            }

            prefs = PreferencesManager.getInstance(this);
            localDb = MessageDatabase.getInstance(this);
            api = new ApiClient(prefs.getServer(), prefs.getToken());

            friendId = getIntent().getIntExtra("friend_id", 0);
            currentFriendId = friendId;
            prefs.putString("unread_" + friendId, "0");

            String friendName = getIntent().getStringExtra("friend_name");
            boolean friendOnline = getIntent().getBooleanExtra("friend_online", false);
            String friendRemark = getIntent().getStringExtra("friend_remark");
            scrollToMsgId = getIntent().getIntExtra("scroll_to_msg_id", 0);

            friend = new Models.User();
            friend.id = friendId;
            friend.nickname = friendName != null ? friendName : "";
            // 备注优先：intent 传入 > 本地缓存
            friend.remark = (friendRemark != null) ? friendRemark : prefs.getString("remark_" + friendId, "");
            // @账号名与头像：intent 传入（MainActivity 好友列表有完整数据），旧包没传则为空，稍后由后台同步补全
            friend.username = getIntent().getStringExtra("friend_username");
            String friendAvatarIntent = getIntent().getStringExtra("friend_avatar");
            if (friendAvatarIntent != null && !friendAvatarIntent.isEmpty()) {
                friendAvatar = friendAvatarIntent;
                friend.avatar = friendAvatarIntent;
            }

            // Load avatar info
            myAvatar = prefs.getMyAvatar();
            myAvatarColor = prefs.getMyAvatarColor();

            TextView tvName = findViewById(R.id.tvChatName);
            tvName.setText(friendName(friend));

            TextView tvStatus = findViewById(R.id.tvChatStatus);
            tvStatus.setText(friendOnline ? "在线" : "离线");
            tvStatus.setTextColor(friendOnline ? getColor(R.color.green) : getColor(R.color.text_secondary));

            rvMessages = findViewById(R.id.rvMessages);
            etMessage = findViewById(R.id.etMessage);
            plusPanel = findViewById(R.id.plusPanel);
            android.widget.ImageButton btnBack = findViewById(R.id.btnBack);
            android.widget.Button btnSend = findViewById(R.id.btnSend);
            btnPlus = findViewById(R.id.btnPlus);
            android.widget.ImageButton btnVoiceToggle = findViewById(R.id.btnVoiceToggle);
            android.widget.ImageButton btnVoiceCall = findViewById(R.id.btnVoiceCall);
            android.widget.ImageButton btnVideoCall = findViewById(R.id.btnVideoCall);
            TextView btnHoldTalk = findViewById(R.id.btnHoldTalk);

            adapter = new MessageAdapter();
            LinearLayoutManager llm = new LinearLayoutManager(this);
            llm.setStackFromEnd(true);
            rvMessages.setLayoutManager(llm);
            rvMessages.setAdapter(adapter);

            // Initialize file picker
            filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        uploadAndSendFile(result.getData().getData());
                    }
                });
            // 视频选择器：选完视频生成首帧缩略图并发送视频消息
            videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        uploadAndSendVideo(result.getData().getData());
                    }
                });

            btnBack.setOnClickListener(v -> finish());
            btnPlus.setOnClickListener(v -> togglePlusPanel());
            btnVoiceToggle.setOnClickListener(v -> {
                setPlusPanelVisible(false);
                toggleVoiceMode(etMessage, btnHoldTalk, btnVoiceToggle);
            });
            // 点输入框 → 收回"+"面板（密语行为：键盘和面板只显其一）
            etMessage.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) setPlusPanelVisible(false);
            });
            setupPlusPanel();

            // ── SwipeContainer: wraps chat+detail, intercepts only horizontal swipes ──
            detailPanelHelper = new ChatDetailPanel(
                    ChatActivity.this, friend, friendAvatar,
                    prefs, api, localDb, prefs.getUserId());
            detailPanelHelper.setCallback(new ChatDetailPanel.Callback() {
                @Override
                public void onClearChat() {
                    runOnUiThread(() -> {
                        messages.clear();
                        adapter.notifyReload();
                        Log.d(TAG, "聊天记录已清空");
                    });
                }
                @Override
                public void onDeleteFriend() {
                    finish();
                }
                @Override
                public void onRemarkChanged(String newRemark) {
                    friend.remark = newRemark;
                    TextView tvName = findViewById(R.id.tvChatName);
                    if (tvName != null) tvName.setText(friendName(friend));
                    Log.d(TAG, "remark updated: " + newRemark);
                }
                @Override
                public void onTogglePin(boolean pinned) {
                    // 好友置顶纯本地（服务端无好友级会话设置表）
                    prefs.setFriendPinned(friendId, pinned);
                    // 回主列表时绕过 onResume 的 5s 防抖，排序立即生效
                    prefs.markConversationsDirty();
                }
                @Override
                public void onToggleQuiet(boolean quiet) {
                    // 好友免打扰同样纯本地；通知判定在 NotificationDispatcher 里读这个键
                    prefs.setFriendQuiet(friendId, quiet);
                    // 列表行静音图标立即刷新
                    prefs.markConversationsDirty();
                }
            });
            View detailView = detailPanelHelper.build();
            detailView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // Replace content with SwipeContainer
            final ViewGroup contentRoot = findViewById(android.R.id.content);
            final View originalRoot = contentRoot.getChildAt(0);
            contentRoot.removeView(originalRoot);

            swipeContainer = new SwipeContainer(ChatActivity.this);
            swipeContainer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.addView(originalRoot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.addView(detailView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.init(originalRoot, detailView,
                    getResources().getDisplayMetrics().widthPixels);

            contentRoot.addView(swipeContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // Fetch friend avatar from server for detail panel
            new Thread(() -> {
                if (detailPanelHelper != null) {
                    detailPanelHelper.refreshFromServer();
                }
            }).start();

            // Voice recording overlay (Miyu-style)
            voiceOverlay = new VoiceRecordOverlay(ChatActivity.this);
            ((ViewGroup) findViewById(android.R.id.content)).addView(voiceOverlay);
            voiceOverlay.setCallback(new VoiceRecordOverlay.Callback() {
                @Override
                public void onSend(String filePath, int durationMs) {
                    uploadVoice(filePath, durationMs);
                }
                @Override
                public void onCancel() {
                    // No Toast — overlay handles visual feedback
                }
            });

            btnHoldTalk.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        if (!hasAudioPermission()) {
                            if (Build.VERSION.SDK_INT >= 23)
                                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
                            return true;
                        }
                        voiceOverlay.start(event.getRawY());
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                        voiceOverlay.updateSwipe(event.getRawY());
                        return true;
                    } else if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_CANCEL) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        voiceOverlay.onRelease();
                        return true;
                    }
                    return false;
                }
            });
            btnVoiceCall.setOnClickListener(v -> startCall(false));
            btnVideoCall.setOnClickListener(v -> startCall(true));
            btnSend.setOnClickListener(v -> {
                String text = etMessage.getText().toString().trim();
                if (text.isEmpty() || friendId == 0) return;
                etMessage.setText("");
                Models.Message msg = new Models.Message();
                msg.sender_id = prefs.getUserId();
                msg.receiver_id = friendId;
                msg.content = text;
                msg.msg_type = "text";
                messages.add(msg);
                adapter.notifyAppended();
                rvMessages.scrollToPosition(adapter.rowCount() - 1);
                new Thread(() -> {
                    try {
                        // P1-②: 加密失败（本地密钥缺失/对方无公钥）时取消发送，绝不静默明文降级
                        String cipher = maybeEncrypt(text, friendId);
                        if (cipher == null) {
                            Log.e("E2EE_LOG", "send text blocked: cannot encrypt");
                            runOnUiThread(() -> {
                                messages.remove(msg);
                                adapter.notifyReload();
                                Toast.makeText(ChatActivity.this,
                                        "无法加密发送：本地缺少密钥或对方未设置公钥，已取消发送", Toast.LENGTH_LONG).show();
                            });
                            return;
                        }
                        JsonObject jo = new JsonObject();
                        jo.addProperty("receiver_id", friendId);
                        jo.addProperty("content", cipher);
                        jo.addProperty("msg_type", "text");
                        okhttp3.Request req = new okhttp3.Request.Builder()
                            .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/messages/send")
                            .header("Authorization", "Bearer " + prefs.getToken())
                            .header("Content-Type", "application/json")
                            .post(okhttp3.RequestBody.create(jo.toString(), okhttp3.MediaType.parse("application/json")))
                            .build();
                        okhttp3.Response resp = httpClient.newCall(req).execute();
                        Log.d("SYNC_LOG", "text msg send: code=" + resp.code());
                        if (resp.isSuccessful()) {
                            String respBody = resp.body() != null ? resp.body().string() : "";
                            Models.Message saved = parseSentMessage(respBody, text, "text");
                            if (saved != null) {
                                localDb.insertMessage(saved);
                                Log.d("SYNC_LOG", "text msg saved to DB: id=" + saved.id);
                                Log.d("DB_LOG", "insert message id=" + saved.id + " type=" + saved.msg_type);
                                // 把真实 id/时间回填到内存中的乐观消息，否则撤回/删除会用 id=0（撤回必然失败）
                                msg.id = saved.id;
                                msg.created_at = saved.created_at;
                                // 回填后必须重算显示行：乐观插入那一刻 created_at 还是 null，
                                // rebuildRows() 的 `if (t > 0)` 会跳过这条的时间行；不 notify 就永远不补，
                                // 且 poll（fetchNewMessages 按 id > lastId 过滤）也不会再把它当新消息。
                                runOnUiThread(() -> adapter.notifyReload());
                            }
                        } else {
                            Log.e(TAG, "Send message failed: " + resp.code());
                            runOnUiThread(() -> {
                                messages.remove(msg);
                                adapter.notifyReload();
                                Toast.makeText(ChatActivity.this,
                                        "发送失败，请检查网络", Toast.LENGTH_SHORT).show();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Send message error", e);
                        runOnUiThread(() -> {
                            messages.remove(msg);
                            adapter.notifyReload();
                            Toast.makeText(ChatActivity.this,
                                    "发送失败，请检查网络", Toast.LENGTH_SHORT).show();
                        });
                    }
                }).start();
            });

            // WebSocket：连接宿主已下沉到 :core 的 PushConnection，本页只注册监听。
            try {
                // Delayed polling to avoid conflict with initial message load
                new Handler().postDelayed(() -> startPolling(), 3000);
                wsListener = new WebSocketManager.Listener() {
                    @Override public void onConnected() {
                        Log.d("SYNC_LOG", "WS connected");
                    }
                    @Override public void onDisconnected() {
                        Log.d("SYNC_LOG", "WS disconnected");
                    }
                    @Override
                    public void onNewMessage(Models.Message msg) {
                        Log.d("SYNC_LOG", "WS push received: id=" + msg.id + " from=" + msg.sender_id + " type=" + msg.msg_type);
                        if (msg.sender_id == friendId || msg.receiver_id == friendId) {
                            // P3-② 重放防护：同 id 消息（WS 重复推送/服务器重放旧密文）不再重复插入展示
                            if (messageIdExists(msg.id)) {
                                Log.d("SYNC_LOG", "WS push skipped (duplicate id=" + msg.id + ")");
                                return;
                            }
                            localDb.insertMessage(msg);
                            Log.d("SYNC_LOG", "WS push saved to DB: id=" + msg.id);
                            Log.d("DB_LOG", "insert message id=" + msg.id + " type=" + msg.msg_type + " (ws push)");
                            fetchPeerPublicKey(msg.sender_id);
                            if (msg.content != null && !"recalled".equals(msg.msg_type)) {
                                Log.d("E2EE_LOG", "onNewMessage: decrypting incoming msg id=" + msg.id
                                        + " sender=" + msg.sender_id);
                                msg.content = maybeDecrypt(msg.content, friendId);
                            }
                            runOnUiThread(() -> {
                                messages.add(msg);
                                adapter.notifyAppended();
                                rvMessages.scrollToPosition(adapter.rowCount() - 1);
                            });
                        }
                    }
                    @Override public void onFriendRequest() {}
                    @Override public void onFriendRequestHandled(int fsId, String action, Models.User byUser) {}
                    @Override public void onUserOnline(int userId) {}
                    @Override public void onUserOffline(int userId) {}

                    // 转账收款/撤回：即时刷新气泡状态（服务端已更新消息 content，轮询 5s 兜底）
                    @Override public void onTransferEvent(String kind, int tid, long amount) {
                        runOnUiThread(ChatActivity.this::reloadMessages);
                    }

                    // ---- 通话信令：本页不再处理 ----
                    // Step 1 / C4 起来电由 :core 的 CallManager 统一接管（状态机 + callId 去重 + 超时 + 拉 UI）。
                    // 旧实现在这里直接 startActivity(CallActivity)，并与 MainActivity 的对话框靠
                    // CallActivity.shouldHandleIncoming 的 4 秒窗口抢名额 —— 谁先注册谁生效，
                    // 结果就是"在聊天框里来电、退出聊天框才弹出来电界面"。整段已删。
                    // 留空不影响编译：WebSocketManager.Listener 的这几个通话方法是 default 实现。
                    // ⚠️ 不要在这里重新实现通话事件 —— 那会立刻回到"两条路径互相打架"的状态。

                    @Override
                    public void onMessageRecalled(int msgId) {
                        android.util.Log.d("RECALL_LOG", "WS received recall: msgId=" + msgId);
                        // Always update local DB first (works even if msg not in memory list)
                        localDb.recallMessage(msgId);
                        // Also update in-memory list if message is loaded
                        for (int i = 0; i < messages.size(); i++) {
                            if (messages.get(i).id == msgId) {
                                messages.get(i).content = "[已撤回]";
                                messages.get(i).msg_type = "recalled";
                                final int idx = i;
                                runOnUiThread(() -> adapter.notifyMessageChanged(idx));
                                break;
                            }
                        }
                    }
                };
                PushConnection.addListener(wsListener);
            } catch (Exception e) {
                Log.e(TAG, "WebSocket setup failed", e);
                runOnUiThread(() -> Log.d(TAG, "实时连接初始化失败"));
            }

            // Load friend profile for avatar (cache to local)
            new Thread(() -> {
                try {
                    // Try local cache first
                    String cachedJson = prefs.getString("friend_" + friendId, null);
                    if (cachedJson != null) {
                        Models.User cached = gson.fromJson(cachedJson, Models.User.class);
                        if (cached != null) {
                            friendAvatar = cached.avatar;
                            friendAvatarColor = cached.avatar_color;
                            // 缓存里有完整资料：补全 @账号名/头像，避免显示初始占位
                            if (cached.username != null && !cached.username.isEmpty()) friend.username = cached.username;
                            if (cached.avatar != null && !cached.avatar.isEmpty()) friend.avatar = cached.avatar;
                        }
                    }
                    // Sync from server
                    Models.FriendsResponse fres = api.getFriends();
                    if (fres.friends != null) {
                        for (Models.User f : fres.friends) {
                            if (f.id == friendId) {
                                friendAvatar = f.avatar;
                                friendAvatarColor = f.avatar_color;
                                // 把完整资料写进 friend 对象（通话/详情都要用）
                                friend.username = f.username;
                                friend.nickname = f.nickname;
                                friend.avatar = f.avatar;
                                friend.is_online = f.is_online;
                                // 详情面板：头像 + 昵称 + @账号名 全部刷新
                                final Models.User fresh = f;
                                runOnUiThread(() -> {
                                    if (detailPanelHelper != null) {
                                        detailPanelHelper.updateIdentity(fresh);
                                    }
                                });
                                // Cache friend info locally
                                prefs.putString("friend_" + friendId, gson.toJson(f));
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}
            }).start();

            // Load messages: local DB first (instant display), then background decrypt/key/sync
            new Thread(() -> {
                int myId = prefs.getUserId();

                // ===== 1. 本地消息立即加载显示（不联网，<200ms）=====
                List<Models.Message> localMsgs = localDb.getMessages(myId, friendId);
                Log.d("DB_LOG", "load messages count=" + localMsgs.size() + " myId=" + myId + " friendId=" + friendId);
                // 用缓存公钥能解就解；解不了的先占位"解密中..."，绝不在显示前联网等待
                List<Integer> pendingIdx = new ArrayList<>();
                List<String> pendingRaw = new ArrayList<>();
                for (int i = 0; i < localMsgs.size(); i++) {
                    Models.Message lm = localMsgs.get(i);
                    if (lm.content != null && !"recalled".equals(lm.msg_type)) {
                        String raw = lm.content;
                        lm.content = maybeDecryptCachedOnly(raw, friendId);
                        if (looksLikeCiphertext(raw) && "解密中...".equals(lm.content)) {
                            pendingIdx.add(i);
                            pendingRaw.add(raw);
                        }
                    }
                }
                final List<Models.Message> shown = localMsgs;
                runOnUiThread(() -> {
                    messages.clear();
                    messages.addAll(shown);
                    adapter.notifyReload();
                });
                Log.d("E2EE_LOG", "loadMessages: shown " + shown.size() + " local msgs instantly, pending decrypt=" + pendingIdx.size());

                // ===== 2. 后台拉取对方公钥，补齐"解密中..."条目（不阻塞显示）=====
                if (!pendingIdx.isEmpty()) {
                    // 收口点（同 maybeDecrypt attempt#2）：密钥与 pin 不一致时只用新键【展示】，
                    // 不写缓存、不更新 pin，并给这几条消息加上可见提示。
                    PeerKeyFetch fetched = fetchPeerPublicKeyChecked(friendId);
                    final boolean changedKey = fetched.isChanged();
                    String freshPub = fetched.keyForDisplayOnly();
                    String myPriv = prefs.getMyPrivateKey();
                    if (freshPub != null && myPriv != null) {
                        for (int k = 0; k < pendingIdx.size(); k++) {
                            final int idx = pendingIdx.get(k);
                            final String raw = pendingRaw.get(k);
                            final String plain = MessageEncryptor.decryptMessage(raw, myPriv, freshPub);
                            final String resolved = (plain != null)
                                    ? (changedKey ? plain + PEER_KEY_CHANGED_SUFFIX : plain)
                                    : E2EE_DECRYPT_FAILED;
                            runOnUiThread(() -> {
                                if (idx >= 0 && idx < messages.size()) {
                                    messages.get(idx).content = resolved;
                                    adapter.notifyMessageChanged(idx);
                                }
                            });
                        }
                        if (changedKey) {
                            Log.w("E2EE_LOG", "pending decrypt used CHANGED key — display only, pin/cache untouched");
                            com.familychat.app.utils.AppLog.e("E2EE",
                                    "对端公钥已变更，历史消息由新密钥解密展示 friendId=" + friendId);
                        }
                    } else {
                        // 拉不到公钥：占位改为失败提示，避免一直卡在"解密中..."
                        runOnUiThread(() -> {
                            for (Integer idx : pendingIdx) {
                                if (idx >= 0 && idx < messages.size()) {
                                    messages.get(idx).content = E2EE_DECRYPT_FAILED;
                                    adapter.notifyMessageChanged(idx);
                                }
                            }
                        });
                    }
                }

                // ===== 3. 后台确保本机公钥已上传（不阻塞显示）=====
                ensureOwnPublicKeyUploaded();

                // ===== 4. 后台同步服务器新消息（增量更新，不全量刷新）=====
                try {
                    Models.MessagesResponse res = api.getMessages(friendId, scrollToMsgId > 0 ? 200 : 100);
                    List<Models.Message> serverMsgs = res.messages != null ? res.messages : new ArrayList<>();
                    localDb.insertMessages(serverMsgs);
                    for (Models.Message sm : serverMsgs) {
                        if (sm.content != null && !"recalled".equals(sm.msg_type)) {
                            sm.content = maybeDecrypt(sm.content, friendId);
                        }
                    }
                    final List<Models.Message> serverFinal = serverMsgs;
                    runOnUiThread(() -> {
                        // 旧版 bug 遗留：本地可能有 id=0 的消息（其真实版本在服务器上，因 E2EE 密文无法可靠匹配）。
                        // 一旦检测到，直接用服务器列表全量替换并清掉本地 id=0 行，一劳永逸；否则走增量追加。
                        boolean hasIdZero = false;
                        for (Models.Message m : messages) {
                            if (m.id == 0) { hasIdZero = true; break; }
                        }
                        if (hasIdZero && !serverFinal.isEmpty()) {
                            localDb.deleteMessage(0);
                            messages.clear();
                            messages.addAll(serverFinal);
                            adapter.notifyReload();
                        } else {
                            java.util.Map<Integer, Integer> posById = new java.util.HashMap<>();
                            java.util.Set<Integer> have = new java.util.HashSet<>();
                            for (int i = 0; i < messages.size(); i++) {
                                int mid = messages.get(i).id;
                                posById.put(mid, i);
                                have.add(mid);
                            }
                            List<Models.Message> toAdd = new ArrayList<>();
                            for (Models.Message sm : serverFinal) {
                                Integer pos = posById.get(sm.id);
                                if (pos != null) {
                                    // 已存在列表：若服务端已撤回而本地还是原内容，就地更新（撤回传播，
                                    // 否则增量去重会跳过已加载的消息，撤回状态永远不生效）
                                    if ("recalled".equals(sm.msg_type) && !"recalled".equals(messages.get(pos).msg_type)) {
                                        messages.get(pos).content = "[已撤回]";
                                        messages.get(pos).msg_type = "recalled";
                                        adapter.notifyMessageChanged(pos);
                                    }
                                } else {
                                    have.add(sm.id);
                                    toAdd.add(sm);
                                }
                            }
                            if (!toAdd.isEmpty()) {
                                int lastId = messages.isEmpty() ? 0 : messages.get(messages.size() - 1).id;
                                boolean cleanAppend = messages.isEmpty() || toAdd.get(0).id > lastId;
                                if (cleanAppend) {
                                    int base = messages.size();
                                    messages.addAll(toAdd);
                                    adapter.notifyRangeAppended(base);
                                } else {
                                    messages.addAll(toAdd);
                                    messages.sort((a, b) -> Integer.compare(a.id, b.id));
                                    adapter.notifyReload();
                                }
                            }
                        }
                        // NO scrollToPosition here — setStackFromEnd handles positioning
                        if (scrollToMsgId > 0) scrollToTargetMessage();
                    });
                    Log.d("SYNC_LOG", "loaded " + serverMsgs.size() + " messages from server");
                    Log.d("DB_LOG", "server sync count=" + serverMsgs.size() + " myId=" + myId + " friendId=" + friendId);
                } catch (Exception e) {
                    Log.e(TAG, "Load messages failed", e);
                    Log.e("DB_LOG", "server sync FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            Log.d(TAG, "err: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            finish();
        }
    }

    /** 从 Content URI 查询原始文件名（如 "假期照片.jpg"）。 */
    private String queryDisplayName(Uri uri) {
        try {
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queryDisplayName failed", e);
        }
        return null;
    }

    private void uploadAndSendFile(Uri uri) {
        Log.d(TAG, "上传中...");
        new Thread(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                boolean isImage = mime != null && mime.startsWith("image/");

                // Extract original file extension from URI
                String extension = "";
                String uriPath = uri.getPath();
                if (uriPath != null && uriPath.contains(".")) {
                    extension = uriPath.substring(uriPath.lastIndexOf('.'));
                } else if (mime != null) {
                    // Fallback: derive extension from MIME type
                    if (mime.equals("image/png")) extension = ".png";
                    else if (mime.equals("image/jpeg")) extension = ".jpg";
                    else if (mime.equals("image/gif")) extension = ".gif";
                    else if (mime.equals("image/webp")) extension = ".webp";
                    else if (mime.startsWith("image/")) extension = ".jpg";
                    else if (mime.equals("application/pdf")) extension = ".pdf";
                    else if (mime.startsWith("video/")) extension = ".mp4";
                    else if (mime.startsWith("audio/")) extension = ".mp3";
                }
                String fileName = "file_" + System.currentTimeMillis() + extension;
                // 取原始文件名（用于查找聊天记录时按文件名搜索）
                String originalName = queryDisplayName(uri);
                if (originalName == null || originalName.isEmpty()) originalName = fileName;

                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> Log.d(TAG, "无法读取文件"));
                        return;
                    }
                    // Compress images before upload
                    if (isImage) {
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            int w = bmp.getWidth(), h = bmp.getHeight();
                            if (w > 1280 || h > 1280) {
                                float scale = Math.min(1280f / w, 1280f / h);
                                bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (int)(w * scale), (int)(h * scale), true);
                            }
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos);
                            bytes = baos.toByteArray();
                            bmp.recycle();
                        } else {
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[4096]; int n;
                            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                            bytes = baos.toByteArray();
                        }
                    } else {
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[4096]; int n;
                        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                        bytes = baos.toByteArray();
                    }
                }

                MultipartBody mBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                        RequestBody.create(bytes, MediaType.parse(mime != null ? mime : "application/octet-stream")))
                    .build();
                Request req = new Request.Builder()
                    .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/chat/upload")
                    .header("Authorization", "Bearer " + prefs.getToken())
                    .post(mBody).build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful()) {
                    Log.e(TAG, "Upload failed: " + resp.code());
                    runOnUiThread(() -> Log.d(TAG, "上传失败: " + resp.code()));
                    return;
                }
                String respBody = resp.body() != null ? resp.body().string() : "";
                JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
                if (json.has("url")) {
                    String url = json.get("url").getAsString();
                    String msgType = isImage ? "image" : "file";
                    // P1-②: 加密失败时取消发送，绝不静默明文降级
                    String cipherUrl = maybeEncrypt(url, friendId);
                    if (cipherUrl == null) {
                        Log.e("E2EE_LOG", "send file blocked: cannot encrypt");
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                "无法加密发送：本地缺少密钥或对方未设置公钥，已取消发送", Toast.LENGTH_LONG).show());
                        return;
                    }
                    // Send message
                    JsonObject jo = new JsonObject();
                    jo.addProperty("receiver_id", friendId);
                    jo.addProperty("content", cipherUrl);
                    jo.addProperty("msg_type", msgType);
                    jo.addProperty("filename", originalName);
                    Request req2 = new Request.Builder()
                        .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/messages/send")
                        .header("Authorization", "Bearer " + prefs.getToken())
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(jo.toString(), MediaType.parse("application/json")))
                        .build();
                    okhttp3.Response sendResp = httpClient.newCall(req2).execute();
                    boolean sendOk = sendResp.isSuccessful();
                    sendResp.close();
                    if (!sendOk) {
                        Log.e(TAG, "Send file/image failed");
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                "文件发送失败", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    // Add to local list
                    Models.Message msg = new Models.Message();
                    msg.sender_id = prefs.getUserId();
                    msg.receiver_id = friendId;
                    msg.content = url;
                    msg.msg_type = msgType;
                    msg.filename = originalName;
                    runOnUiThread(() -> {
                        messages.add(msg);
                        adapter.notifyAppended();
                        rvMessages.scrollToPosition(adapter.rowCount() - 1);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "file upload failed", e);
                runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                        "文件上传失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 发送视频消息（密语式）：取首帧做缩略图 → 上传缩略图+视频 → 发 msg_type=video */
    private void uploadAndSendVideo(Uri uri) {
        Log.d(TAG, "上传视频中...");
        new Thread(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                String extension = ".mp4";
                String uriPath = uri.getPath();
                if (uriPath != null && uriPath.contains(".")) {
                    String e = uriPath.substring(uriPath.lastIndexOf('.'));
                    if (e != null && e.length() >= 2 && e.length() <= 5) extension = e;
                } else if (mime != null) {
                    // 相册返回的 uri 常无扩展名，按 MIME 兜底（iPhone 的 mov 常见）
                    if (mime.equals("video/quicktime")) extension = ".mov";
                    else if (mime.startsWith("video/")) extension = ".mp4";
                }
                String originalName = queryDisplayName(uri);
                if (originalName == null || originalName.isEmpty()) {
                    originalName = "视频_" + System.currentTimeMillis() + extension;
                }

                // 1) 读视频本体
                byte[] videoBytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        runOnUiThread(() -> Log.d(TAG, "无法读取视频"));
                        return;
                    }
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    videoBytes = baos.toByteArray();
                }

                // 2) 取第 1 秒帧做缩略图（压缩到 ≤512px 宽）
                byte[] thumbBytes = null;
                try {
                    android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                    mmr.setDataSource(this, uri);
                    android.graphics.Bitmap frame = mmr.getFrameAtTime(1_000_000);
                    mmr.release();
                    if (frame != null) {
                        int w = frame.getWidth(), h = frame.getHeight();
                        if (w > 512) {
                            float s = 512f / w;
                            frame = android.graphics.Bitmap.createScaledBitmap(frame, 512, (int)(h * s), true);
                        }
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos);
                        thumbBytes = baos.toByteArray();
                        frame.recycle();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "videoThumb", e);
                }

                String videoUrl = uploadFileBytes(videoBytes, "video_" + System.currentTimeMillis() + extension,
                        mime != null ? mime : "video/mp4");
                if (videoUrl == null) {
                    runOnUiThread(() -> Log.d(TAG, "视频上传失败"));
                    return;
                }
                String thumbUrl = "";
                if (thumbBytes != null && thumbBytes.length > 0) {
                    thumbUrl = uploadFileBytes(thumbBytes, "thumb_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
                }

                // 3) 发送视频消息（content=视频URL，与图片一致走 E2EE 加密；缩略图明文预览）
                // P1-②: 加密失败时取消发送，绝不静默明文降级
                String cipherVideo = maybeEncrypt(videoUrl, friendId);
                if (cipherVideo == null) {
                    Log.e("E2EE_LOG", "send video blocked: cannot encrypt");
                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                            "无法加密发送：本地缺少密钥或对方未设置公钥，已取消发送", Toast.LENGTH_LONG).show());
                    return;
                }
                JsonObject jo = new JsonObject();
                jo.addProperty("receiver_id", friendId);
                jo.addProperty("content", cipherVideo);
                jo.addProperty("msg_type", "video");
                jo.addProperty("thumbnail", thumbUrl);
                jo.addProperty("filename", originalName);
                Request req2 = new Request.Builder()
                    .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/messages/send")
                    .header("Authorization", "Bearer " + prefs.getToken())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(jo.toString(), MediaType.parse("application/json")))
                    .build();
                okhttp3.Response videoSendResp = httpClient.newCall(req2).execute();
                boolean videoSendOk = videoSendResp.isSuccessful();
                videoSendResp.close();
                if (!videoSendOk) {
                    Log.e(TAG, "Send video failed");
                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                            "视频发送失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 本地立即显示（明文 URL，与图片/文件本地条目一致）
                Models.Message msg = new Models.Message();
                msg.sender_id = prefs.getUserId();
                msg.receiver_id = friendId;
                msg.content = videoUrl;
                msg.msg_type = "video";
                msg.thumbnail = thumbUrl;
                msg.filename = originalName;
                runOnUiThread(() -> {
                    messages.add(msg);
                    adapter.notifyAppended();
                    rvMessages.scrollToPosition(adapter.rowCount() - 1);
                });
            } catch (Exception e) {
                Log.e(TAG, "uploadAndSendVideo", e);
                runOnUiThread(() -> Log.d(TAG, "发送视频失败"));
            }
        }).start();
    }

    /** 上传一段字节到 /api/chat/upload，返回服务器 url（失败返回 null）。 */
    private String uploadFileBytes(byte[] bytes, String fileName, String mime) {
        try {
            MultipartBody mBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    RequestBody.create(bytes, MediaType.parse(mime != null ? mime : "application/octet-stream")))
                .build();
            Request req = new Request.Builder()
                .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/chat/upload")
                .header("Authorization", "Bearer " + prefs.getToken())
                .post(mBody).build();
            okhttp3.Response resp = httpClient.newCall(req).execute();
            if (!resp.isSuccessful()) {
                Log.e(TAG, "uploadFileBytes: " + resp.code());
                return null;
            }
            String respBody = resp.body() != null ? resp.body().string() : "";
            JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
            return json.has("url") ? json.get("url").getAsString() : null;
        } catch (Exception e) {
            Log.e(TAG, "uploadFileBytes", e);
            return null;
        }
    }

    private void uploadVoice(String filePath, int durationMs) {
        if (filePath == null) return;
        final int durationSec = durationMs / 1000;
        new Thread(() -> {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists()) return;
                MultipartBody mBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "voice.mp4",
                        RequestBody.create(file, MediaType.parse("audio/mp4")))
                    .build();
                Request req = new Request.Builder()
                    .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/chat/upload")
                    .header("Authorization", "Bearer " + prefs.getToken())
                    .post(mBody).build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                String respBody = resp.body() != null ? resp.body().string() : "";
                JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
                if (json.has("url")) {
                    String url = json.get("url").getAsString();
                    // P1-②: 加密失败时取消发送，绝不静默明文降级
                    String cipherUrl = maybeEncrypt(url, friendId);
                    if (cipherUrl == null) {
                        Log.e("E2EE_LOG", "send voice blocked: cannot encrypt");
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                "无法加密发送：本地缺少密钥或对方未设置公钥，已取消发送", Toast.LENGTH_LONG).show());
                        return;
                    }
                    JsonObject jo = new JsonObject();
                    jo.addProperty("receiver_id", friendId);
                    jo.addProperty("content", cipherUrl);
                    jo.addProperty("msg_type", "voice");
                    jo.addProperty("duration_sec", durationSec);
                    Request req2 = new Request.Builder()
                        .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/messages/send")
                        .header("Authorization", "Bearer " + prefs.getToken())
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(jo.toString(), MediaType.parse("application/json")))
                        .build();
                    okhttp3.Response resp2 = httpClient.newCall(req2).execute();
                    if (resp2.isSuccessful()) {
                        String sendResp = resp2.body() != null ? resp2.body().string() : "";
                        Models.Message newMsg = new Models.Message();
                        newMsg.sender_id = prefs.getUserId();
                        newMsg.receiver_id = friendId;
                        newMsg.content = url;
                        newMsg.msg_type = "voice";
                        newMsg.duration_sec = durationSec;
                        newMsg.id = parseMsgId(sendResp);
                        localDb.insertMessage(newMsg);
                        Log.d("DB_LOG", "insert message id=" + newMsg.id + " type=" + newMsg.msg_type + " (voice/img send)");
                        runOnUiThread(() -> {
                            messages.add(newMsg);
                            adapter.notifyAppended();
                            rvMessages.scrollToPosition(adapter.rowCount() - 1);
                        });
                    } else {
                        Log.e(TAG, "Send voice failed: " + resp2.code());
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                "语音发送失败", Toast.LENGTH_SHORT).show());
                    }
                }
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "uploadVoice", e);
                runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                        "语音发送失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int parseMsgId(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("id")) return obj.get("id").getAsInt();
            if (obj.has("message") && obj.get("message").isJsonObject()) {
                return obj.getAsJsonObject("message").get("id").getAsInt();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ─── Polling & Fetch Missed Messages ─────────────────────────

    /** 内存中是否已存在该消息 id（WS 重复推送 / 服务器重放旧密文去重用）。 */
    private boolean messageIdExists(int id) {
        if (id <= 0) return false;
        for (Models.Message m : messages) {
            if (m.id == id) return true;
        }
        return false;
    }

    private boolean isPollingActive = false;

    private void startPolling() {
        stopPolling();
        isPollingActive = true;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPollingActive) return;
                // 轮询是网络请求，放到后台线程执行，避免主线程 NetworkOnMainThreadException
                new Thread(() -> {
                    try {
                        fetchNewMessages();
                    } catch (Exception ignored) {}
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS);
                }).start();
            }
        };
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        Log.d("SYNC_LOG", "polling started (interval=" + POLL_INTERVAL_MS + "ms)");
    }

    private void stopPolling() {
        isPollingActive = false;
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    /** Poll: fetch messages newer than last known ID. */
    private void fetchNewMessages() {
        if (friendId <= 0) return;
        try {
            int lastId = 0;
            if (!messages.isEmpty()) lastId = messages.get(messages.size() - 1).id;
            Models.MessagesResponse res = api.getMessages(friendId, 50);
            List<Models.Message> all = res.messages != null ? res.messages : new ArrayList<>();
            List<Models.Message> fresh = new ArrayList<>();
            for (Models.Message m : all) {
                if (m.id > lastId) fresh.add(m);
            }
            if (fresh.isEmpty()) return;
            Log.d("SYNC_LOG", "poll found " + fresh.size() + " new msgs (lastId=" + lastId + ")");
            for (Models.Message m : fresh) {
                localDb.insertMessage(m);
                Log.d("DB_LOG", "insert message id=" + m.id + " type=" + m.msg_type + " (poll)");
                if (m.content != null && !"recalled".equals(m.msg_type)) {
                    m.content = maybeDecrypt(m.content, friendId);
                }
            }
            runOnUiThread(() -> {
                int base = messages.size();
                messages.addAll(fresh);
                adapter.notifyRangeAppended(base);
                rvMessages.scrollToPosition(adapter.rowCount() - 1);
            });
        } catch (Exception e) {
            Log.e("SYNC_LOG", "poll failed", e);
        }
    }

    /**
     * 转账收款/撤回后刷新气泡状态：从服务端拉全量消息，就地更新已存在消息的 content/type。
     * 不重排、不滚屏，避免打断聊天。
     */
    private void reloadMessages() {
        if (friendId <= 0) return;
        new Thread(() -> {
            try {
                Models.MessagesResponse res = api.getMessages(friendId, 100);
                if (res.messages == null) return;
                localDb.insertMessages(res.messages);
                for (Models.Message sm : res.messages) {
                    if (sm.content != null && !"recalled".equals(sm.msg_type)) {
                        sm.content = maybeDecrypt(sm.content, friendId);
                    }
                }
                final List<Models.Message> serverMsgs = res.messages;
                runOnUiThread(() -> {
                    java.util.Map<Integer, Integer> posById = new java.util.HashMap<>();
                    for (int i = 0; i < messages.size(); i++) {
                        posById.put(messages.get(i).id, i);
                    }
                    boolean changed = false;
                    for (Models.Message sm : serverMsgs) {
                        Integer pos = posById.get(sm.id);
                        if (pos == null) continue;
                        Models.Message cur = messages.get(pos);
                        boolean typeChanged = sm.msg_type != null && !sm.msg_type.equals(cur.msg_type);
                        boolean contentChanged = sm.content != null && !sm.content.equals(cur.content);
                        if (typeChanged || contentChanged) {
                            cur.msg_type = sm.msg_type;
                            cur.content = sm.content;
                            adapter.notifyMessageChanged(pos);
                            changed = true;
                        }
                    }
                    if (changed) Log.d("SYNC_LOG", "transfer status refreshed");
                });
            } catch (Exception e) {
                Log.e("SYNC_LOG", "reloadMessages failed", e);
            }
        }).start();
    }

    // ─── E2EE Helpers ───────────────────────────────────────────

    // ─── TOFU：对端公钥 pin（docs/audits/E2EE_SECURITY_AUDIT §1.1 Critical / §1.2 High）───
    // 唯一真相源 = PeerKeyPinStore 里的 pin；peer_pub_<uid> 只是网络缓存。
    // 历史上该缓存被 4 条路径无条件覆盖（本文件 / PreviewDecryptor / SearchChatActivity /
    // GroupEncryptHelper），所以**读缓存也必须过 pin**：否则未收口的写入路径
    // 只要把攻击者的键写进缓存，pin 就被绕过。

    /** 用"已变更密钥"解密展示时追加在正文后的提示（§1.2：单条历史消息展示必须可见）。 */
    private static final String PEER_KEY_CHANGED_SUFFIX = "\n\n⚠️ 对方加密密钥已变更，本条消息由新密钥解密";

    /** {@link #fetchPeerPublicKeyChecked} 的结果：公钥 + pin 状态 + 新旧指纹（供告警 UI）。 */
    private static final class PeerKeyFetch {
        /** 通过 pin 校验、可用于加密的公钥；CHANGED（或彻底拿不到）时为 null。 */
        final String publicKey;
        /** 与 pin 不一致的候选公钥（服务端新键，或本地被污染的缓存）。仅 CHANGED 时非 null。 */
        final String candidatePublicKey;
        final PeerKeyPinStore.PinState state;
        /** 已 pin 的指纹（旧）；本地从未 pin 过时为 null。 */
        final String pinnedFingerprint;
        /** 候选公钥的指纹（新）。 */
        final String candidateFingerprint;

        PeerKeyFetch(String publicKey, String candidatePublicKey, PeerKeyPinStore.PinState state,
                     String pinnedFingerprint, String candidateFingerprint) {
            this.publicKey = publicKey;
            this.candidatePublicKey = candidatePublicKey;
            this.state = state;
            this.pinnedFingerprint = pinnedFingerprint;
            this.candidateFingerprint = candidateFingerprint;
        }

        boolean isChanged() {
            return state == PeerKeyPinStore.PinState.CHANGED;
        }

        /**
         * 供"只解密本条历史消息用于展示"使用的公钥。
         * CHANGED 时返回候选键 —— 调用方<b>不得</b>写进 peer_pub_ 缓存、
         * <b>不得</b>用它加密发送、<b>不得</b>更新 pin（§1.2 的收口要求）。
         */
        String keyForDisplayOnly() {
            return publicKey != null ? publicKey : candidatePublicKey;
        }
    }

    /**
     * 从服务端拉取对端公钥并过 TOFU pin。
     *
     * <ul>
     *   <li>UNKNOWN：首次见到 → {@link PeerKeyPinStore#check} 已落 pin，正常使用并写缓存；</li>
     *   <li>MATCH：与 pin 一致 → 正常使用并写缓存；</li>
     *   <li>CHANGED：与 pin 不一致 → <b>不写 peer_pub_ 缓存、不更新 pin</b>，
     *       只把候选公钥交回调用方决定（阻断发送 / 单条展示解密）。</li>
     * </ul>
     *
     * <p>网络失败回退本地缓存（与历史行为一致），但缓存值同样要过 pin —— 未收口的
     * 另外几处写缓存路径可能已经把服务端的新键写进来了。
     */
    private PeerKeyFetch fetchPeerPublicKeyChecked(int peerUserId) {
        try {
            String pubKey = api.getPublicKey(peerUserId);
            if (pubKey != null) {
                PeerKeyPinStore.PinState st = PeerKeyPinStore.check(peerUserId, pubKey);
                if (st == PeerKeyPinStore.PinState.CHANGED) {
                    Log.w("E2EE_LOG", "fetch: peer key CHANGED for user " + peerUserId);
                    return new PeerKeyFetch(null, pubKey, st,
                            PeerKeyPinStore.getPinnedFingerprint(peerUserId),
                            PeerKeyPinStore.fingerprintOf(pubKey));
                }
                prefs.savePeerPublicKey(peerUserId, pubKey);
                Log.d("E2EE_LOG", "fetched fresh pubKey for user " + peerUserId + " (" + pubKey.length() + " chars)");
                return new PeerKeyFetch(pubKey, null, st, null, null);
            } else {
                Log.e("E2EE_LOG", "fetchPeerPublicKey: server returned null for user " + peerUserId);
            }
        } catch (Exception e) {
            Log.e("E2EE_LOG", "fetchPeerPublicKey failed for user " + peerUserId, e);
        }
        // Fallback to stale cache（同样过 pin）
        String cached = prefs.getPeerPublicKey(peerUserId);
        if (cached != null) {
            Log.w("E2EE_LOG", "using STALE cached pubKey for user " + peerUserId);
            PeerKeyPinStore.PinState st = PeerKeyPinStore.check(peerUserId, cached);
            if (st == PeerKeyPinStore.PinState.CHANGED) {
                return new PeerKeyFetch(null, cached, st,
                        PeerKeyPinStore.getPinnedFingerprint(peerUserId),
                        PeerKeyPinStore.fingerprintOf(cached));
            }
            return new PeerKeyFetch(cached, null, st, null, null);
        }
        return new PeerKeyFetch(null, null, PeerKeyPinStore.PinState.UNKNOWN, null, null);
    }

    /** 兼容壳：返回可信公钥（CHANGED 或拿不到时为 null）。 */
    private String fetchPeerPublicKey(int peerUserId) {
        return fetchPeerPublicKeyChecked(peerUserId).publicKey;
    }

    /** Get peer's public key from cache only (no network). 缓存值必须先过 pin（见 core 同名方法）。 */
    private String getCachedPeerPublicKey(int peerUserId) {
        return PeerKeyPinStore.trustedOrNull(peerUserId, prefs.getPeerPublicKey(peerUserId));
    }

    /** Get peer's public key from cache or server. Used during real-time message processing. */
    private String getPeerPublicKey(int peerUserId) {
        String cached = getCachedPeerPublicKey(peerUserId);
        if (cached != null) return cached;
        return fetchPeerPublicKey(peerUserId);
    }

    /**
     * Encrypt content if keys are available. Returns ciphertext or null (send must be cancelled).
     *
     * <p>TOFU（审计 §1.1/§1.2，决策 4）：对端公钥与本地 pin 的指纹不一致时
     * <b>阻断发送</b>并弹告警；只有用户点「信任新密钥」才更新 pin 并用新钥重新加密发送，
     * 点「取消发送」则与既有 fail-closed 路径一致 —— 调用方取消发送，绝不回退明文。
     */
    private String maybeEncrypt(String plainText, int peerUserId) {
        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            Log.d("E2EE_LOG", "cannot encrypt: own private key missing");
            return null;
        }
        // Force-fetch the peer's CURRENT public key from server (bypass stale cache) so we never
        // encrypt with a key the receiver has since discarded. Falls back to cache on network error.
        // 两种来源都必须过 TOFU pin —— 见 fetchPeerPublicKeyChecked。
        PeerKeyFetch fetched = fetchPeerPublicKeyChecked(peerUserId);
        String peerPub = fetched.publicKey;
        if (fetched.isChanged()) {
            Log.w("E2EE_LOG", "send blocked: peer key CHANGED peer=" + peerUserId
                    + " pinned=" + fetched.pinnedFingerprint + " new=" + fetched.candidateFingerprint);
            com.familychat.app.utils.AppLog.e("E2EE", "对端公钥已变更，已阻断发送 peerUserId=" + peerUserId);
            if (!askUserToTrustChangedKey(peerUserId, fetched.pinnedFingerprint, fetched.candidateFingerprint)) {
                Log.w("E2EE_LOG", "user rejected changed peer key — send stays cancelled");
                return null;
            }
            // 用户显式确认：只有这条路径会覆盖 pin，并让网络缓存与 pin 重新对齐
            PeerKeyPinStore.acceptChange(peerUserId, fetched.candidatePublicKey);
            prefs.savePeerPublicKey(peerUserId, fetched.candidatePublicKey);
            peerPub = fetched.candidatePublicKey;
            Log.w("E2EE_LOG", "user trusted changed peer key, re-encrypting for user " + peerUserId);
        }
        if (peerPub == null) {
            Log.d("E2EE_LOG", "cannot encrypt: keys missing (myPriv=true peerPub=false)");
            return null;
        }
        String cipher = MessageEncryptor.encryptMessage(plainText, myPriv, peerPub);
        if (cipher != null) {
            Log.d("E2EE_LOG", "send: encrypted");
        }
        return cipher;
    }

    /**
     * 对端公钥变更告警（决策 4：阻断发送 + 用户显式确认）。
     *
     * <p>本方法必须在<b>后台线程</b>调用：内部切主线程弹框，并用 {@link java.util.concurrent.CountDownLatch}
     * 等用户选择。之所以阻塞等待而不是"先取消发送、确认后再补发"：调用方的语义是
     * "拿到密文才发送"，阻塞等一次点击可以让确认后<b>同一次</b>发送成功，
     * 用户不会觉得"点了信任还得再把话打一遍"。
     *
     * @return true = 用户点「信任新密钥」；false = 取消 / 弹框失败 / 等待超时
     */
    private boolean askUserToTrustChangedKey(int peerUserId, String pinnedFp, String newFp) {
        if (isFinishing() || isDestroyed()) return false;
        final String oldCode = PeerKeyPinStore.displayCode(pinnedFp).isEmpty()
                ? "(未记录)" : PeerKeyPinStore.displayCode(pinnedFp);
        final String newCode = PeerKeyPinStore.displayCode(newFp).isEmpty()
                ? "(无法识别)" : PeerKeyPinStore.displayCode(newFp);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean accepted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        runOnUiThread(() -> {
            try {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 20);
                box.setPadding(pad, pad / 2, pad, 0);
                box.addView(fingerprintRow("已记录的指纹（旧）", oldCode, 0xFFD32F2F));
                box.addView(fingerprintRow("服务器现在的指纹（新）", newCode, 0xFFD32F2F));
                box.addView(fingerprintRow("", "如果对方没有重装应用、也没换过设备，这次变化就很可能是"
                        + "有人在中间替换了他的公钥。确认之前请不要继续发送。", 0xFF757575));
                new MaterialAlertDialogBuilder(ChatActivity.this)
                        .setTitle("⚠️ 对方的加密密钥已变更")
                        .setView(box)
                        .setPositiveButton("信任新密钥", (d, w) -> {
                            accepted.set(true);
                            latch.countDown();
                        })
                        .setNegativeButton("取消发送", (d, w) -> latch.countDown())
                        .setOnCancelListener(d -> latch.countDown())
                        .show();
            } catch (Exception e) {
                Log.e("E2EE_LOG", "show key-changed dialog failed", e);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w("E2EE_LOG", "key-changed confirm timed out — treat as cancel");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return accepted.get();
    }

    /** 指纹展示行：小标题 + 等宽可选中的值。 */
    private LinearLayout fingerprintRow(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, (int) (getResources().getDisplayMetrics().density * 14));
        if (label != null && !label.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(12);
            t.setTextColor(0xFF757575);
            row.addView(t);
        }
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(16);
        v.setTextColor(color);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        row.addView(v);
        return row;
    }

    /** Decrypt content with partner's public key. partnerUserId = friendId (chat partner), NOT sender. */
    private String maybeDecrypt(String content, int partnerUserId) {
        if (content == null || content.isEmpty()) return content;
        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            Log.e("E2EE_LOG", "decrypt skipped: own private key missing");
            return looksLikeCiphertext(content) ? E2EE_DECRYPT_FAILED : content;
        }
        // Ciphertext is Base64 of (12-byte IV + GCM ciphertext+tag), always ≥ 40 chars.
        // Plaintext passes through untouched.
        if (!looksLikeCiphertext(content)) {
            return content;
        }
        // Attempt 1: cached (or fetched-if-missing) peer public key
        String peerPub = getPeerPublicKey(partnerUserId);
        Log.d("E2EE_LOG", "decrypt attempt#1: myPriv=" + (myPriv != null)
                + " peerPub=" + (peerPub != null) + " partnerUserId=" + partnerUserId);
        String plain = (peerPub != null) ? MessageEncryptor.decryptMessage(content, myPriv, peerPub) : null;
        if (plain != null) {
            Log.d("E2EE_LOG", "decrypt OK (cached key)");
            return plain;
        }
        // Attempt 2: force re-fetch the peer's latest public key from server and retry once.
        // 收口点（审计 §1.2 High / 决策 5）：新键必须先过 TOFU pin。
        //   MATCH  ：与 pin 一致 → 正常解密（旧消息确实可能是用旧键加密的）；
        //   CHANGED：绝不用它覆盖 peer_pub_ 缓存、绝不更新 pin，只用它解密
        //            【本条历史消息】用于展示，并在正文后追加可见提示。
        Log.d("E2EE_LOG", "decrypt attempt#1 failed — re-fetching peer pub key from server and retrying");
        PeerKeyFetch fetched = fetchPeerPublicKeyChecked(partnerUserId);
        String freshPub = fetched.keyForDisplayOnly();
        if (freshPub != null && !freshPub.equals(peerPub)) {
            plain = MessageEncryptor.decryptMessage(content, myPriv, freshPub);
            if (plain != null) {
                if (fetched.isChanged()) {
                    Log.w("E2EE_LOG", "decrypt OK with CHANGED key — display only, pin/cache untouched");
                    com.familychat.app.utils.AppLog.e("E2EE",
                            "对端公钥已变更，本条消息由新密钥解密展示 partnerUserId=" + partnerUserId);
                    return plain + PEER_KEY_CHANGED_SUFFIX;
                }
                Log.d("E2EE_LOG", "decrypt OK (fresh key)");
                return plain;
            }
        } else {
            Log.d("E2EE_LOG", "decrypt retry skipped: fresh key " + (freshPub == null ? "still null" : "same as cached"));
        }
        Log.e("E2EE_LOG", "decrypt FAILED");
        com.familychat.app.utils.AppLog.e("E2EE", "消息解密失败 partnerUserId=" + partnerUserId);
        return E2EE_DECRYPT_FAILED;
    }

    /**
     * Decrypt using ONLY the locally cached peer key — NEVER touches the network.
     * Used for the instant-display pass. If the key is missing (or stale) and the content
     * is ciphertext, returns the placeholder "解密中..." so the background pass can resolve it
     * after fetching the fresh key. Plaintext passes through untouched.
     */
    private String maybeDecryptCachedOnly(String content, int partnerUserId) {
        if (content == null || content.isEmpty()) return content;
        String myPriv = prefs.getMyPrivateKey();
        if (myPriv == null || myPriv.isEmpty()) {
            return looksLikeCiphertext(content) ? E2EE_DECRYPT_FAILED : content;
        }
        if (!looksLikeCiphertext(content)) return content;
        String peerPub = getCachedPeerPublicKey(partnerUserId);
        if (peerPub == null) return "解密中...";  // 需要联网拉公钥，先占位
        String plain = MessageEncryptor.decryptMessage(content, myPriv, peerPub);
        if (plain != null) return plain;
        return "解密中...";  // 缓存可能是旧公钥，交给后台联网重试
    }

    /** True if content looks like a Base64 E2EE ciphertext (not plaintext). */
    private boolean looksLikeCiphertext(String content) {
        if (content == null || content.length() < 40) return false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Ensure OUR public key is present locally and uploaded to the server.
     * Call on chat open (background thread). Idempotent.
     */
    private void ensureOwnPublicKeyUploaded() {
        try {
            String myPriv = prefs.getMyPrivateKey();
            String myPub = prefs.getMyPublicKey();
            if (myPriv == null || myPriv.isEmpty() || myPub == null || myPub.isEmpty()) {
                Log.w("E2EE_LOG", "ensureOwnPublicKeyUploaded: keys missing, generating new pair");
                String[] keys = MessageEncryptor.generateKeyPair();
                if (keys == null) {
                    Log.e("E2EE_LOG", "ensureOwnPublicKeyUploaded: generateKeyPair failed");
                    return;
                }
                prefs.saveKeyPair(keys[0], keys[1]);
                myPub = keys[0];
            }
            boolean ok = api.uploadPublicKey(myPub);
            Log.d("E2EE_LOG", "ensureOwnPublicKeyUploaded: upload my pub (" + myPub.length() + " chars) → " + (ok ? "OK" : "FAILED"));
        } catch (Exception e) {
            Log.e("E2EE_LOG", "ensureOwnPublicKeyUploaded failed", e);
        }
    }

    private void saveImageToGallery(String fullUrl) {
        new Thread(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder().url(fullUrl).build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) return;
                byte[] data = resp.body().bytes();
                String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";

                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES);
                    var uri = getContentResolver().insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (var os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(data);
                        }
                    }
                } else {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES);
                    dir.mkdirs();
                    java.io.File file = new java.io.File(dir, fileName);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                        fos.write(data);
                    }
                    android.media.MediaScannerConnection.scanFile(
                            this, new String[]{file.getAbsolutePath()}, null, null);
                }
                runOnUiThread(() -> Log.d(TAG, "已保存到相册"));
            } catch (Exception e) {
                Log.e(TAG, "saveImage", e);
            }
        }).start();
    }

    /**
     * Download a file from the server and open it with system intent.
     */
    private void downloadAndOpenFile(String path, String fileName) {
        Log.d(TAG, "正在下载...");
        new Thread(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + path, prefs.getToken()))
                        .build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    runOnUiThread(() -> Log.d(TAG, "下载失败"));
                    return;
                }
                byte[] data = resp.body().bytes();

                // Save to Downloads directory
                String dir;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                } else {
                    dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                }
                new java.io.File(dir).mkdirs();
                java.io.File savedFile = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(savedFile)) {
                    fos.write(data);
                }

                runOnUiThread(() -> {
                    Log.d(TAG, "已下载: " + savedFile.getAbsolutePath());
                    // Open with system app
                    try {
                        Uri fileUri;
                        if (android.os.Build.VERSION.SDK_INT >= 24) {
                            fileUri = FileProvider.getUriForFile(this,
                                    getPackageName() + ".fileprovider", savedFile);
                        } else {
                            fileUri = Uri.fromFile(savedFile);
                        }
                        Intent openIntent = new Intent(Intent.ACTION_VIEW);
                        String mime = getContentResolver().getType(fileUri);
                        if (mime == null) {
                            // Guess from extension
                            String ext = fileName.contains(".") ?
                                    fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
                            if (ext.equals("pdf")) mime = "application/pdf";
                            else if (ext.matches("(jpg|jpeg|png|gif|bmp|webp)")) mime = "image/*";
                            else if (ext.matches("(mp4|mov|avi)")) mime = "video/*";
                            else if (ext.matches("(mp3|wav|aac|flac)")) mime = "audio/*";
                            else mime = "*/*";
                        }
                        openIntent.setDataAndType(fileUri, mime);
                        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(openIntent, "打开文件"));
                    } catch (Exception e2) {
                        Log.d(TAG, "文件已保存到下载目录");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Log.d(TAG, "下载失败: " + e.getMessage()));
            }
        }).start();
    }


    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // Permission granted silently — user can try again
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    /** 构建密语式"+"面板：4 列格子，灰底彩色图标 */
    /** 打开「转账给 XX」页，成功后把服务端 transfer 消息带上屏。 */
    private void startTransfer() {
        if (friendId == 0) return;
        Intent it = new Intent(this, TransferSendActivity.class);
        it.putExtra("friend_id", friendId);
        it.putExtra("friend_name", friendName(friend));
        transferLauncher.launch(it);
    }

    private void setupPlusPanel() {
        try {
            android.widget.GridLayout grid = findViewById(R.id.plusGrid);
            addPanelItem(grid, R.drawable.ic_album, "相册", this::pickImageFromGallery);
            addPanelItem(grid, R.drawable.ic_camera, "拍摄", this::captureFromCamera);
            addPanelItem(grid, R.drawable.ic_video_send, "视频", this::pickVideoFromGallery);
            addPanelItem(grid, R.drawable.ic_transfer, "转账", this::startTransfer);
            addPanelItem(grid, R.drawable.ic_file, "文件", this::pickFile);
            addPanelItem(grid, R.drawable.ic_phone, "语音通话", () -> startCall(false));
            addPanelItem(grid, R.drawable.ic_video, "视频通话", () -> startCall(true));
        } catch (Exception e) {
            Log.e(TAG, "setupPlusPanel", e);
            Log.d(TAG, "面板初始化失败");
        }
    }

    /** 单个面板格子：图标 + 灰色文字，点击后收起面板再执行动作 */
    private void addPanelItem(android.widget.GridLayout grid, int iconRes, String label, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        item.setLayoutParams(new android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)));
        item.setPadding(0, 10, 0, 10);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        item.setBackgroundResource(ripple.resourceId);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(58)));
        // 复用自带的黑白电话/视频图标，面板里着密语绿
        if (iconRes == R.drawable.ic_phone || iconRes == R.drawable.ic_video) {
            iv.setColorFilter(getColor(R.color.panel_call_icon));
        }
        item.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tv.setPadding(0, 6, 0, 0);
        item.addView(tv);

        item.setOnClickListener(v -> {
            setPlusPanelVisible(false);
            try {
                action.run();
            } catch (Exception e) {
                Log.e(TAG, "plusPanelItem", e);
                Log.d(TAG, "操作失败");
            }
        });
        grid.addView(item);
    }

    /** 点"+"切换面板显隐（密语行为） */
    private void togglePlusPanel() {
        setPlusPanelVisible(plusPanel.getVisibility() != View.VISIBLE);
    }

    /** 显示/隐藏"+"面板：打开时收键盘、清输入焦点；"+"图标高亮/还原 */
    private void setPlusPanelVisible(boolean show) {
        if (show) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            if (etMessage != null) etMessage.clearFocus();
            plusPanel.setVisibility(View.VISIBLE);
            if (btnPlus != null) btnPlus.setColorFilter(getColor(R.color.icon_nav_selected));
        } else {
            plusPanel.setVisibility(View.GONE);
            if (btnPlus != null) btnPlus.setColorFilter(getColor(R.color.icon));
        }
        applyNavPadding();
    }

    /** 底部系统导航栏留白：只给最下面那个可见的（输入栏 or "+"面板） */
    private void applyNavPadding() {
        View inputBar = findViewById(R.id.layoutInput);
        if (inputBar == null || plusPanel == null) return;
        boolean panelVisible = plusPanel.getVisibility() == View.VISIBLE;
        inputBar.setPadding(inputBar.getPaddingLeft(), inputBar.getPaddingTop(),
                inputBar.getPaddingRight(), panelVisible ? 0 : navInsetBottom);
        plusPanel.setPadding(plusPanel.getPaddingLeft(), plusPanel.getPaddingTop(),
                plusPanel.getPaddingRight(), panelVisible ? navInsetBottom : 0);
    }

    /** Initiate an audio or video call to the current friend */
    private void startCall(boolean video) {
        if (friend == null || friendId <= 0) {
            Log.d(TAG, "请先选择聊天对象");
            return;
        }
        // Step 1 / C4：占线守卫下沉到 CallManager。
        // 旧实现靠 CallActivity.isActive（onPause 即置 false）判断，通话页被暂停时第二通电话照样能起 —— 已删。
        if (com.familychat.app.api.call.CallManager.isBusy()) {
            Toast.makeText(this, "正在通话中", Toast.LENGTH_SHORT).show();
            return;
        }
        // 兜底：friend 还没同步到完整资料时，用已缓存/已加载的 friendAvatar 补头像，避免通话显示占位
        if ((friend.avatar == null || friend.avatar.isEmpty()) && friendAvatar != null && !friendAvatar.isEmpty()) {
            friend.avatar = friendAvatar;
        }
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("friend", new Gson().toJson(friend));
        intent.putExtra("friend_name", friend.nickname);
        intent.putExtra("from_user_id", friend.id);
        intent.putExtra("video", video);
        intent.putExtra("incoming", false);
        // 外呼的 callId 由 CallActivity.onCreate 调 CallManager.startCall 生成（见 C5）
        startActivity(intent);
    }

    /** 打开手机相册选图片 */
    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        filePickerLauncher.launch(intent);
    }

    /** 打开手机相册选视频（走视频选择器，选完生成缩略图发送） */
    private void pickVideoFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        videoPickerLauncher.launch(intent);
    }

    /** Capture a photo using camera */
    private Uri cameraPhotoUri;
    private void captureFromCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 101);
            }
            return;
        }
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                java.io.File photoFile = java.io.File.createTempFile(
                        "camera_", ".jpg", getCacheDir());
                cameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        photoFile);
                intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                // Use a separate launcher for camera
                cameraLauncher.launch(intent);
            } catch (Exception e) {
                Log.d(TAG, "无法启动相机");
            }
        } else {
            Log.d(TAG, "没有可用的相机应用");
        }
    }

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && cameraPhotoUri != null) {
                    uploadAndSendFile(cameraPhotoUri);
                }
            });

    private void toggleVoiceMode(EditText input, TextView holdTalk, ImageButton toggleBtn) {
        if (input.getVisibility() == View.VISIBLE) {
            // Switch to voice mode
            input.setVisibility(View.GONE);
            holdTalk.setVisibility(View.VISIBLE);
            toggleBtn.setImageResource(R.drawable.ic_msg);
        } else {
            // Switch to text mode
            input.setVisibility(View.VISIBLE);
            holdTalk.setVisibility(View.GONE);
            toggleBtn.setImageResource(R.drawable.ic_voice);
        }
    }

    // ===== Local Message Cache =====
    private Models.Message parseSentMessage(String json, String content, String msgType) {
        try {
            Models.Message msg = new Models.Message();
            msg.sender_id = prefs.getUserId();
            msg.receiver_id = friendId;
            msg.content = content;
            msg.msg_type = msgType;
            msg.is_read = true;
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            // 服务端返回 {"success":true,"message":{id,sender_id,...,created_at}} —— id 等字段嵌套在 message 里。
            // 若只读顶层 id，msg.id 恒为 0，insertWithOnConflict(CONFLICT_REPLACE) 会把所有发出的文本消息
            // 压缩成同一条 id=0 的记录，退出重进后本地只剩一条，其余"消失"。
            JsonObject saved = (obj.has("message") && obj.get("message").isJsonObject())
                    ? obj.getAsJsonObject("message") : obj;
            if (saved.has("id")) msg.id = saved.get("id").getAsInt();
            if (saved.has("sender_id")) msg.sender_id = saved.get("sender_id").getAsInt();
            if (saved.has("receiver_id")) msg.receiver_id = saved.get("receiver_id").getAsInt();
            if (saved.has("created_at")) msg.created_at = saved.get("created_at").getAsString();
            // 转账消息：服务端生成 content JSON（{"transfer_id","amount",...}），以服务端为准
            if ("transfer".equals(msgType) && saved.has("content") && !saved.get("content").isJsonNull()) {
                msg.content = saved.get("content").getAsString();
            }
            if (saved.has("filename") && !saved.get("filename").isJsonNull())
                msg.filename = saved.get("filename").getAsString();
            if (saved.has("duration_sec")) msg.duration_sec = saved.get("duration_sec").getAsInt();
            if (saved.has("is_read")) msg.is_read = saved.get("is_read").getAsInt() == 1;
            return msg;
        } catch (Exception e) {
            Log.e(TAG, "parseSentMessage", e);
            return null;
        }
    }

    /**
     * 是否是通话状态消息。
     *
     * <p>三种：{@code call_video}（视频通话）、{@code call_voice}（语音通话）、
     * {@code call}（1.1.8 及更早的历史类型，无法区分音视频，按语音处理）。
     *
     * <p>刻意放在 {@code ChatActivity} 而不是 {@code MessageAdapter} 里：渲染分支与
     * {@link #showMessageMenu} 都要用它（后者不在 Adapter 内）。Adapter 是内部类，够得着。
     */
    private static boolean isCallType(String t) {
        return "call".equals(t) || "call_voice".equals(t) || "call_video".equals(t);
    }

    private void showMessageMenu(Models.Message msg, int position) {
        boolean isMe = msg.sender_id == prefs.getUserId();
        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();

        // Copy (for text & image URLs)
        if (msg.content != null && !msg.content.isEmpty()) {
            if ("text".equals(msg.msg_type)) {
                items.add("复制");
                final String textContent = msg.content;
                actions.add(() -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("消息", textContent);
                    clipboard.setPrimaryClip(clip);
                    Log.d(TAG, "已复制");
                });
            } else if ("image".equals(msg.msg_type)) {
                items.add("复制");
                final String imgFullUrl = ServerProtocol.baseUrl(prefs.getServer()) + msg.content;
                actions.add(() -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("图片链接", imgFullUrl);
                    clipboard.setPrimaryClip(clip);
                    Log.d(TAG, "图片链接已复制");
                });
            }
        }

        // Save to gallery (for images) — 下载需带 token, 否则 401
        if ("image".equals(msg.msg_type) && msg.content != null && !msg.content.isEmpty()) {
            items.add("保存");
            String imgFullUrl = ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + msg.content,
                    prefs.getToken());
            actions.add(() -> saveImageToGallery(imgFullUrl));
        }

        // 已撤回的消息不再显示"撤回"菜单（重复撤回只会得到"撤回失败"）
        // 通话记录也排除：它是服务端生成的系统消息（"通话时长 00:30"），撤回后会变成
        // "[已撤回]"，语义荒谬。删除仍然可用（见下方 isMe 分支）。
        if (isMe && !"recalled".equals(msg.msg_type) && !isCallType(msg.msg_type)) {
            // Recall (within 2 minutes)
            items.add("撤回");
            actions.add(() -> {
                new Thread(() -> {
                    try {
                        int targetId = msg.id;
                        // 内存中 id 缺失（旧版遗留 id=0）时，从服务器按时间/收发方找回真实 id
                        if (targetId == 0) {
                            Models.MessagesResponse res = api.getMessages(friendId, 50);
                            if (res.messages != null) {
                                for (Models.Message m : res.messages) {
                                    if (m.sender_id == prefs.getUserId() && m.receiver_id == friendId
                                            && m.created_at != null && m.created_at.equals(msg.created_at)) {
                                        targetId = m.id;
                                        break;
                                    }
                                }
                            }
                        }
                        if (targetId == 0) {
                            runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                    "撤回失败: 消息ID异常，请退出重进聊天后再试", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        // 必须确认服务器真正撤回成功，否则不显示"已撤回"（避免假撤回）
                        Models.StatusResponse resp = api.recallMessage(targetId);
                        if (resp != null && resp.success) {
                            android.util.Log.d("RECALL_LOG", "recall ok: msgId=" + targetId);
                            // 回填真实 id，避免下次再丢
                            msg.id = targetId;
                            msg.content = "[已撤回]";
                            msg.msg_type = "recalled";
                            localDb.recallMessage(targetId);
                            runOnUiThread(() -> adapter.notifyMessageChanged(position));
                        } else {
                            String err = (resp != null && resp.error != null && !resp.error.isEmpty())
                                    ? resp.error : "服务器未找到该消息";
                            runOnUiThread(() -> Toast.makeText(ChatActivity.this, "撤回失败: " + err, Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                "撤回失败: " + (e.getMessage() != null ? e.getMessage() : "网络错误"), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });
        }

        // 删除仍对已撤回消息可用
        if (isMe) {
            // Delete
            items.add("删除");
            actions.add(() -> {
                new MaterialAlertDialogBuilder(ChatActivity.this)
                    .setTitle("删除消息").setMessage("确定删除这条消息?")
                    .setPositiveButton("删除", (d, w) -> {
                        new Thread(() -> {
                            try {
                                api.deleteMessage(msg.id);
                                runOnUiThread(() -> {
                                    messages.remove(position);
                                    adapter.notifyMessageRemoved(position);
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> Log.d(TAG, "删除失败"));
                            }
                        }).start();
                    }).setNegativeButton("取消", null).show();
            });
        }

        if (items.isEmpty()) return;
        String[] itemArr = items.toArray(new String[0]);
        new MaterialAlertDialogBuilder(ChatActivity.this)
            .setItems(itemArr, (d, which) -> actions.get(which).run()).show();
    }

    private void loadMsgAvatar(ImageView iv, String avatar, String color) {
        if (avatar == null || avatar.isEmpty()) {
            iv.setImageResource(R.drawable.ic_avatar_placeholder);
            return;
        }
        if (avatar.startsWith("/")) {
            com.bumptech.glide.Glide.with(this)
                .load(ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                        prefs.getToken()))
                .circleCrop().into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_avatar_placeholder);
            try { iv.setBackgroundColor(android.graphics.Color.parseColor(color != null ? color : "#e8f5e9")); } catch (Exception e) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (swipeContainer != null && swipeContainer.isOpen()) {
            swipeContainer.close();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read own avatar from prefs; refresh if it changed while the chat was in background
        String newAvatar = prefs.getMyAvatar();
        if (adapter != null && myAvatar != null && !myAvatar.equals(newAvatar)) {
            myAvatar = newAvatar;
            adapter.notifyReload();
        }
    }

    /**
     * ★ 连接宿主下沉顺带修复：currentFriendId 原先只在 onDestroy 清除。
     *
     * <p>ChatActivity 作为栈内页面会在"用户已经离开"之后长期存活（返回主界面、
     * 打开通话页、按 Home 等），此时 currentFriendId 仍等于那个好友的 id，
     * MainActivity 便判定"用户正在这个聊天里"，于是**不累计未读、不弹通知**。
     * 改为与可见性对齐的 onStart/onStop；onCreate/onDestroy 的赋值保留作双保险。
     */
    @Override
    protected void onStart() {
        super.onStart();
        currentFriendId = friendId;
        // 通话应用内浮层：本页可见时接管（无通话时 attach 内部直接返回，无副作用）
        com.familychat.app.api.call.CallOverlayController.attach(this);
    }

    @Override
    protected void onStop() {
        currentFriendId = -1;
        super.onStop();
        // 宿主作用域卸载 + 延迟判定是否需要系统浮窗（见 CallOverlayController.onHostStopped）
        com.familychat.app.api.call.CallOverlayController.detach(this);
        com.familychat.app.api.call.CallOverlayController.onHostStopped(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        currentFriendId = -1;
        // Clear unread for this friend
        if (friendId > 0) {
            prefs.putString("unread_" + friendId, "0");
        }
        if (voicePlayer != null) { try { voicePlayer.release(); } catch (Exception ignored) {} voicePlayer = null; }
        playingPosition = -1;
        playingWaveform = null;
        stopPolling();
        // ⚠️ 只反注册监听器：连接归 PushConnection（由 ForegroundService 驱动），
        // 调用 stop() 会切断整个后台推送。
        PushConnection.removeListener(wsListener);
        wsListener = null;
        if (voiceOverlay != null) {
            voiceOverlay.destroy(); // release MediaRecorder / handler / animator
            ((ViewGroup) findViewById(android.R.id.content)).removeView(voiceOverlay);
            voiceOverlay = null;
        }
    }

    class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
        private static final int TYPE_ME = 0, TYPE_OTHER = 1, TYPE_TIME = 2;
        /** 相邻消息时间差 ≥ 5 分钟才插入时间行 */
        private static final long TIME_THRESHOLD_MS = 5 * 60 * 1000L;

        /** 显示行：消息行(0, msgIndex) 或 时间行(1, timeText)。由 rebuildRows() 从 messages 生成。 */
        private static class Row {
            final int kind;        // 0=消息行 1=时间行
            final int msgIndex;    // kind==0：对应 messages 里的下标
            final String timeText; // kind==1：已格式化好的时间文案
            Row(int kind, int msgIndex, String timeText) {
                this.kind = kind; this.msgIndex = msgIndex; this.timeText = timeText;
            }
        }
        private final java.util.List<Row> rows = new java.util.ArrayList<>();

        /**
         * 由 messages 重建显示行：第一条消息总显示时间；相邻消息时间差 ≥5 分钟
         * 就在后一条前插入一条时间行。每次 messages 变化后、notify 前调用。
         */
        void rebuildRows() {
            rows.clear();
            int n = messages.size();
            for (int i = 0; i < n; i++) {
                long t = msgMillis(messages.get(i));
                if (t > 0) {
                    boolean showTime;
                    if (i == 0) {
                        showTime = true; // 第一条消息总显示时间
                    } else {
                        long prev = msgMillis(messages.get(i - 1));
                        showTime = prev <= 0 || (t - prev) >= TIME_THRESHOLD_MS;
                    }
                    if (showTime) rows.add(new Row(1, -1, ChatTimeFormatter.format(t)));
                }
                rows.add(new Row(0, i, null));
            }
        }

        /** 消息毫秒时间戳：优先用已缓存的 msg.timestamp，否则解析 created_at 并回填缓存。 */
        private long msgMillis(Models.Message m) {
            if (m.timestamp > 0) return m.timestamp;
            m.timestamp = ChatTimeFormatter.parseUtc(m.created_at);
            return m.timestamp;
        }

        int rowCount() { return rows.size(); }

        /** 消息下标 → 显示行下标；找不到返回 -1（用于滚动/刷新定位）。 */
        int rowIndexOfMessage(int msgIndex) {
            for (int r = 0; r < rows.size(); r++) {
                if (rows.get(r).kind == 0 && rows.get(r).msgIndex == msgIndex) return r;
            }
            return -1;
        }

        /** 数据重载（清空/排序/整体替换）：重建行后全量刷新。 */
        void notifyReload() {
            rebuildRows();
            notifyDataSetChanged();
        }

        /** 末尾追加一条消息（可能顺带多一条时间行）：从首个新增行刷新到末尾。 */
        void notifyAppended() {
            rebuildRows();
            int lastMsg = messages.size() - 1;
            int r = rowIndexOfMessage(lastMsg);
            if (r < 0) { notifyDataSetChanged(); return; }
            // 时间行会插在消息行之前 —— 通知区间必须从它开始，否则这一行不在通知范围内
            int from = (r > 0 && rows.get(r - 1).kind == 1) ? r - 1 : r;
            notifyItemRangeInserted(from, rows.size() - from);
        }

        /** 某个消息内容就地变化（解密完成/撤回）：按消息下标定位到行并刷新。 */
        void notifyMessageChanged(int msgIndex) {
            rebuildRows();
            int r = rowIndexOfMessage(msgIndex);
            if (r >= 0) notifyItemChanged(r);
        }

        /** 末尾追加一段消息（可能穿插时间行）：刷新从首个新增行到末尾。 */
        void notifyRangeAppended(int firstMsgIndex) {
            rebuildRows();
            int r = rowIndexOfMessage(firstMsgIndex);
            if (r < 0) { notifyDataSetChanged(); return; }
            // 与 notifyAppended 同理：时间行会插在首条新消息之前，通知区间必须从它开始
            int from = (r > 0 && rows.get(r - 1).kind == 1) ? r - 1 : r;
            notifyItemRangeInserted(from, rows.size() - from);
        }

        /** 删除一条消息：删除后下方行整体前移，rebuild 后全量刷新最稳妥（顺带清理可能多余的时间行）。 */
        void notifyMessageRemoved(int msgIndex) {
            rebuildRows();
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            Row r = rows.get(position);
            if (r.kind == 1) return TYPE_TIME;
            return messages.get(r.msgIndex).sender_id == prefs.getUserId() ? TYPE_ME : TYPE_OTHER;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_TIME) {
                View v = getLayoutInflater().inflate(R.layout.item_msg_time, parent, false);
                return new VH(v);
            }
            int layout = viewType == TYPE_ME ? R.layout.item_msg_me : R.layout.item_msg_other;
            View itemView = getLayoutInflater().inflate(layout, parent, false);
            VH h = new VH(itemView);
            h.itemGravityDefault = viewType == TYPE_ME ? Gravity.END : Gravity.START;
            // 通话气泡背景也按左右选（与 itemGravityDefault 同款：在 onCreateViewHolder 定死，
            // onBind 里直接用，避免每帧再算一次 me/other）
            h.callBgRes = viewType == TYPE_ME ? R.drawable.bg_call_me : R.drawable.bg_call_other;

            // Programmatically build voice bubble container with bubble background
            android.widget.FrameLayout voiceContainer = new android.widget.FrameLayout(ChatActivity.this);
            voiceContainer.setVisibility(View.GONE);
            voiceContainer.setTag("voice_container");

            // Bubble background drawable (rounded rect)
            android.graphics.drawable.GradientDrawable bubbleBg = new android.graphics.drawable.GradientDrawable();
            bubbleBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bubbleBg.setCornerRadius(dp(8));
            if (viewType == TYPE_ME) {
                bubbleBg.setColor(getColor(R.color.bubble_self)); // 自己：白天蓝 / 黑夜深色
            } else {
                bubbleBg.setColor(getColor(R.color.bubble_other)); // 对方：白天灰 / 黑夜深色
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                voiceContainer.setBackground(bubbleBg);
            } else {
                voiceContainer.setBackgroundDrawable(bubbleBg);
            }

            // Inner horizontal layout for play + waveform + duration
            android.widget.LinearLayout voiceRow = new android.widget.LinearLayout(ChatActivity.this);
            voiceRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            voiceRow.setGravity(Gravity.CENTER_VERTICAL);
            voiceRow.setPadding(dp(10), dp(6), dp(12), dp(6));
            voiceRow.setTag("voice_row");

            // Play button (circular)
            android.widget.FrameLayout playBtn = new android.widget.FrameLayout(ChatActivity.this);
            int playSize = dp(32);
            playBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(playSize, playSize));
            android.graphics.drawable.GradientDrawable playBg = new android.graphics.drawable.GradientDrawable();
            playBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            playBg.setColor(getColor(R.color.play_btn_bg)); // 播放按钮：白天深灰 / 黑夜浅灰
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                playBtn.setBackground(playBg);
            } else {
                playBtn.setBackgroundDrawable(playBg);
            }
            // Play triangle icon
            TextView playIcon = new TextView(ChatActivity.this);
            playIcon.setText("▶");
            playIcon.setTextColor(getColor(R.color.play_icon)); // 播放图标：白天白 / 黑夜深色
            playIcon.setTextSize(14);
            playIcon.setGravity(Gravity.CENTER);
            playBtn.addView(playIcon, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            playBtn.setTag("voice_play_btn");

            // Waveform
            WaveformView waveform = new WaveformView(ChatActivity.this);
            int waveW = dp(64);
            int waveH = dp(24);
            waveform.setLayoutParams(new android.widget.LinearLayout.LayoutParams(waveW, waveH));
            waveform.setTag("voice_waveform");

            // Duration text
            TextView durationTv = new TextView(ChatActivity.this);
            durationTv.setText("0\"");
            durationTv.setTextColor(viewType == TYPE_ME ? Color.WHITE : getColor(R.color.bubble_voice_text));
            durationTv.setTextSize(12);
            durationTv.setPadding(dp(6), 0, 0, 0);
            durationTv.setTag("voice_duration");

            voiceRow.addView(playBtn);
            voiceRow.addView(waveform);
            voiceRow.addView(durationTv);
            voiceContainer.addView(voiceRow, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Add voice container to the parent message layout
            // item_msg_me: content LL is at index 0, avatar at index 1 (me)
            // item_msg_other: avatar at index 0, content LL at index 1 (other)
            ViewGroup itemContent = (ViewGroup) itemView;
            if (viewType == TYPE_OTHER && itemContent.getChildCount() > 1) {
                View contentLl = itemContent.getChildAt(1);
                if (contentLl instanceof ViewGroup) {
                    ((ViewGroup) contentLl).addView(voiceContainer, 0);
                }
            } else {
                View contentLl = itemContent.getChildAt(0);
                if (contentLl instanceof ViewGroup) {
                    ((ViewGroup) contentLl).addView(voiceContainer, 0);
                }
            }

            h.voiceContainer = voiceContainer;
            h.voiceRow = voiceRow;
            h.btnPlay = playBtn;
            h.playIcon = playIcon;
            h.waveform = waveform;
            h.voiceDuration = durationTv;

            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Row r = rows.get(i);
            if (r.kind == 1) { // 时间行：只显示文案，无消息视图
                if (h.timeText != null) h.timeText.setText(r.timeText);
                return;
            }
            final int msgIdx = r.msgIndex;
            Models.Message msg = messages.get(msgIdx);
            String type = msg.msg_type != null ? msg.msg_type : "text";
            final String content = msg.content != null ? msg.content : "";

            // Restore default bubble style (the holder may have rendered a "call" system
            // message last time, which changed the bubble / text colour / added an icon).
            h.text.setBackground(h.textBgDefault);
            h.text.setTextColor(h.textColorDefault);
            h.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, h.textSizeDefault);
            h.text.setGravity(h.textGravityDefault);
            h.text.setPadding(dp(14), dp(12), dp(14), dp(12));
            // 通话分支会往 tvMsgText 挂图标（电话/摄像头）并接管 itemView 的点击。
            // holder 复用必须先清掉这两样，否则普通文本消息会残留图标，
            // 且点空白处会误触发回拨。tint 也要清（否则将来别处设图标会带上通话的着色）。
            h.text.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            h.text.setCompoundDrawableTintList(null);
            h.itemView.setOnClickListener(null);
            h.rootLayout.setGravity(h.itemGravityDefault);
            if (h.avatar != null) h.avatar.setVisibility(View.VISIBLE);

            if (h.imageWrap != null) {
                h.imageWrap.setVisibility(View.GONE);
                h.imageWrap.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
            h.image.setVisibility(View.GONE);
            h.text.setVisibility(View.VISIBLE);
            h.voiceContainer.setVisibility(View.GONE);
            if (h.transferCard != null) h.transferCard.setVisibility(View.GONE);

            if (isCallType(type)) {
                // 通话状态消息：淡底气泡 + 电话/摄像头图标，按 sender_id 归属到某一侧
                // （左右与头像由 getItemViewType() 依据 sender_id 选好的 item_msg_me /
                //  item_msg_other 决定，已在上面复位块按 itemGravityDefault 恢复）。
                // 点气泡回拨：call_video → 视频；call_voice 与历史类型 "call" → 语音。
                // 时间不随消息自带，统一由 rebuildRows() 的时间分隔符按 5 分钟规则给出。
                final boolean isVideo = "call_video".equals(type);   // legacy "call" 按语音

                h.text.setText(content);
                h.text.setBackgroundResource(h.callBgRes);
                // me 侧的默认文字色是 bubble_text_self（白色），而通话气泡是淡底 ——
                // 不显式改色就是"白字淡底"，看不见。
                h.text.setTextColor(getColor(R.color.bubble_call_text));
                h.text.setTextSize(14);
                // 图标不能用 WithIntrinsicBounds：ic_phone/ic_video 是 200x200px 且放在无密度
                // 限定的 drawable/ 下，intrinsic 尺寸 = 200dp，会画出巨型图标（plus 面板没这问题，
                // 那里用 ImageView + LayoutParams 强制了 58dp）。这里显式 setBounds。
                android.graphics.drawable.Drawable callIcon =
                        getDrawable(isVideo ? R.drawable.ic_video : R.drawable.ic_phone);
                int iconSize = dp(18);
                callIcon.setBounds(0, 0, iconSize, iconSize);
                h.text.setCompoundDrawables(callIcon, null, null, null);
                h.text.setCompoundDrawablePadding(dp(6));
                // 两个图标都是黑色字形，夜间气泡 #1E1E1E 上完全不可见 → 必须着色，
                // 且跟着文字色走（日间深色 / 夜间白色）
                h.text.setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.bubble_call_text)));
                h.itemView.setOnClickListener(v -> startCall(isVideo));
            } else if ("recalled".equals(type)) {
                h.text.setText("[已撤回]");
                h.text.setTextColor(getColor(R.color.text_secondary));
                h.image.setVisibility(View.GONE);
            } else if ("image".equals(type)) {
                h.text.setVisibility(View.GONE);
                if (h.imageWrap != null) {
                    h.imageWrap.setVisibility(View.VISIBLE);
                    if (h.msgPlay != null) h.msgPlay.setVisibility(View.GONE);
                }
                h.image.setVisibility(View.VISIBLE);
                h.image.setAdjustViewBounds(true);
                h.image.setMaxWidth(400);
                h.image.setMaxHeight(300);
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                final String imgUrl = ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + content,
                        prefs.getToken());
                com.bumptech.glide.Glide.with(h.image.getContext())
                    .load(imgUrl).fitCenter().into(h.image);
                final int imgPos = msgIdx;
                h.image.setOnClickListener(v -> {
                    new ImagePreviewDialog(ChatActivity.this, imgUrl).show();
                });
                h.image.setOnLongClickListener(v -> {
                    showMessageMenu(msg, imgPos);
                    return true; // consume long-press, prevents onClick from firing
                });
            } else if ("video".equals(type)) {
                // 密语式视频消息：首帧缩略图 + 中央播放按钮，点击进全屏播放
                h.text.setVisibility(View.GONE);
                if (h.imageWrap != null) {
                    h.imageWrap.setVisibility(View.VISIBLE);
                    if (h.msgPlay != null) h.msgPlay.setVisibility(View.VISIBLE);
                }
                h.image.setVisibility(View.VISIBLE);
                h.image.setAdjustViewBounds(false);
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                final String vBase = ServerProtocol.baseUrl(prefs.getServer());
                final String videoUrl = ServerProtocol.withToken(vBase + content, prefs.getToken());
                String thumb = msg.thumbnail;
                if (thumb != null && !thumb.isEmpty()) {
                    final String thumbUrl = thumb.startsWith("http") ? thumb
                            : ServerProtocol.withToken(vBase + thumb, prefs.getToken());
                    com.bumptech.glide.Glide.with(h.image.getContext())
                        .load(thumbUrl).centerCrop().into(h.image);
                } else {
                    // 旧消息没有缩略图：深色占位，仍可点播放
                    h.image.setImageDrawable(null);
                    if (h.imageWrap != null) h.imageWrap.setBackgroundColor(0xFF2A2A2A);
                }
                final int videoPos = msgIdx;
                View videoTap = h.imageWrap != null ? h.imageWrap : h.image;
                videoTap.setOnClickListener(v -> {
                    try {
                        Intent it = new Intent(ChatActivity.this, ChatVideoPlayerActivity.class);
                        it.putExtra("url", videoUrl);
                        startActivity(it);
                    } catch (Exception e) {
                        Log.e(TAG, "openVideo", e);
                        Log.d(TAG, "无法播放视频");
                    }
                });
                videoTap.setOnLongClickListener(v -> {
                    showMessageMenu(msg, videoPos);
                    return true;
                });
            } else if ("voice".equals(type)) {
                h.text.setVisibility(View.GONE);
                h.voiceContainer.setVisibility(View.VISIBLE);

                boolean isMe = msg.sender_id == prefs.getUserId();
                int barColor = isMe ? 0xCCFFFFFF : getColor(R.color.voice_wave_other);
                h.waveform.setBarColor(barColor);

                // Use real duration from the message data
                int voiceDuration = msg.duration_sec > 0 ? msg.duration_sec : 1;
                h.voiceDuration.setText(voiceDuration + "\"");

                // ── Preload voice file in background ──
                final String voiceUrl = content;
                h.btnPlay.setAlpha(1f);
                h.btnPlay.setEnabled(true);
                preloadVoiceFile(voiceUrl);

                // Check if already cached locally
                boolean isCached = voiceCache.containsKey(voiceUrl)
                        && new java.io.File(voiceCache.get(voiceUrl)).exists();

                // Manage play state
                if (i == playingPosition && voicePlayer != null && voicePlayer.isPlaying()) {
                    h.playIcon.setText("■");
                    h.waveform.startAnimation();
                    playingWaveform = h.waveform;
                } else if (i == playingPosition && voicePlayer != null && !voicePlayer.isPlaying()) {
                    h.playIcon.setText("▶");
                    h.waveform.stopAnimation();
                } else {
                    h.playIcon.setText("▶");
                    h.waveform.stopAnimation();
                }

                // Play button click
                final int pos = i;
                h.btnPlay.setOnClickListener(v -> {
                    if (pos == playingPosition && voicePlayer != null && voicePlayer.isPlaying()) {
                        // Pause current
                        voicePlayer.pause();
                        h.playIcon.setText("▶");
                        h.waveform.stopAnimation();
                        return;
                    }
                    // Start playback: check cache first
                    String cachedPath = voiceCache.get(voiceUrl);
                    if (cachedPath != null && new java.io.File(cachedPath).exists()) {
                        Log.d(TAG, "play from cache: " + cachedPath);
                        playVoiceLocal(cachedPath, h, pos, voiceDuration);
                    } else {
                        // Not cached yet, show loading, preload then auto-play
                        Log.d(TAG, "voice not cached, preloading: " + voiceUrl);
                        h.playIcon.setText("⋯");
                        h.btnPlay.setEnabled(false);
                        preloadVoiceFile(voiceUrl);
                        // Retry after short delay
                        h.btnPlay.postDelayed(() -> {
                            String retryPath = voiceCache.get(voiceUrl);
                            if (retryPath != null && new java.io.File(retryPath).exists()) {
                                h.btnPlay.setEnabled(true);
                                h.playIcon.setText("■");
                                playVoiceLocal(retryPath, h, pos, voiceDuration);
                            } else {
                                h.btnPlay.setEnabled(true);
                                h.playIcon.setText("▶");
                            }
                        }, 1500);
                    }
                });

                // Also allow tapping the whole voice row
                h.voiceContainer.setOnClickListener(v -> h.btnPlay.performClick());
                h.voiceContainer.setOnLongClickListener(v -> {
                    showMessageMenu(msg, msgIdx);
                    return true;
                });
            } else if ("file".equals(type)) {
                String fname = content.contains("/") ? content.substring(content.lastIndexOf('/') + 1) : content;
                String ext = fname.contains(".") ? fname.substring(fname.lastIndexOf('.') + 1).toLowerCase() : "";
                String icon = "📎";
                if (ext.matches("(jpg|jpeg|png|gif|webp|bmp)")) icon = "🖼";
                else if (ext.matches("(mp4|mov|avi|mkv)")) icon = "🎬";
                else if (ext.matches("(mp3|wav|aac|flac)")) icon = "🎵";
                else if (ext.matches("(pdf)")) icon = "📄";
                else if (ext.matches("(doc|docx)")) icon = "📝";
                else if (ext.matches("(zip|rar|7z)")) icon = "📦";
                h.text.setText(icon + " " + fname);
                h.text.setOnClickListener(v -> downloadAndOpenFile(content, fname));
            } else if ("transfer".equals(type)) {
                // 密语橙色转账卡片：金额 + 状态，点击进转账详情。content 为服务端明文 JSON。
                h.text.setVisibility(View.GONE);
                h.image.setVisibility(View.GONE);
                if (h.imageWrap != null) h.imageWrap.setVisibility(View.GONE);
                h.voiceContainer.setVisibility(View.GONE);
                if (h.transferCard != null) {
                    h.transferCard.setVisibility(View.VISIBLE);
                    try {
                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                        long amount = obj.has("amount") ? obj.get("amount").getAsLong() : 0;
                        final int tid = obj.has("transfer_id") ? obj.get("transfer_id").getAsInt() : 0;
                        String status = obj.has("status") && !obj.get("status").isJsonNull()
                                ? obj.get("status").getAsString() : "";
                        h.transferAmount.setText(String.format(java.util.Locale.CHINA, "¥%.2f", amount / 100.0));
                        String st = "pending".equals(status) ? "待收款"
                                : ("accepted".equals(status) ? "已收款" : "已退款");
                        h.transferStatus.setText(st);
                        h.transferCard.setOnClickListener(v -> {
                            if (tid > 0) {
                                Intent it = new Intent(ChatActivity.this, TransferDetailActivity.class);
                                it.putExtra("transfer_id", tid);
                                startActivity(it);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "transfer content parse", e);
                    }
                }
            } else {
                h.text.setText(content);
            }

            // Load avatar
            if (h.avatar != null) {
                boolean isMe = msg.sender_id == prefs.getUserId();
                if (isMe) {
                    loadMsgAvatar(h.avatar, myAvatar, myAvatarColor);
                } else {
                    loadMsgAvatar(h.avatar, friendAvatar, friendAvatarColor);
                }
            }

            // Long press menu：长按监听**无条件**设置（含通话消息，决策 5：放开删除）。
            // 具体给哪些菜单项由 showMessageMenu 按「是否我发的 / 是否通话记录」筛：
            //   我发起的通话记录 → 「删除」（「撤回」被通话类型判断挡掉）
            //   对方发起的通话记录 → 菜单为空 → showMessageMenu 直接 return（服务端
            //     deleteMessage 只认 sender_id，非发送方删不掉，见 HEALTH-CHECK §9.3）
            h.itemView.setOnLongClickListener(v -> {
                showMessageMenu(msg, msgIdx);
                return true;
            });
        }

        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView text; ImageView image, avatar;
            View imageWrap; // 图片/视频外框（含播放按钮浮层）
            TextView msgPlay; // 视频消息中央播放按钮
            TextView timeText; // 时间行专用
            // 转账卡片
            android.widget.LinearLayout transferCard;
            TextView transferAmount, transferStatus;
            // Voice bubble views
            View voiceContainer, voiceRow, btnPlay;
            TextView playIcon, voiceDuration;
            WaveformView waveform;
            // Defaults for restoring a normal bubble after a "call" system message
            android.graphics.drawable.Drawable textBgDefault;
            int textColorDefault;
            float textSizeDefault; // px
            int textGravityDefault;
            int itemGravityDefault;
            // 通话气泡背景（me → bg_call_me，other → bg_call_other），在 onCreateViewHolder 按 viewType 定死
            int callBgRes;
            android.widget.LinearLayout rootLayout;

            VH(View v) {
                super(v);
                rootLayout = (android.widget.LinearLayout) v;
                timeText = v.findViewById(R.id.tvMsgTime);
                if (timeText != null) return; // 时间行：只有居中文本，无需绑定消息视图
                text = v.findViewById(R.id.tvMsgText);
                image = v.findViewById(R.id.ivMsgImage);
                imageWrap = v.findViewById(R.id.ivMsgImageWrap);
                msgPlay = v.findViewById(R.id.tvMsgPlay);
                transferCard = v.findViewById(R.id.llTransferCard);
                transferAmount = v.findViewById(R.id.tvTransferAmount);
                transferStatus = v.findViewById(R.id.tvTransferStatus);
                avatar = v.findViewById(R.id.ivMsgAvatar);
                textBgDefault = text.getBackground();
                textColorDefault = text.getCurrentTextColor();
                textSizeDefault = text.getTextSize();
                textGravityDefault = text.getGravity();
            }
        }
    }

    /**
     * Preload a voice file into local cache in background.
     * Uses the process-wide voiceCache map: URL → local file path (thread-safe, size-capped).
     * Skips if already cached.
     */
    private void preloadVoiceFile(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) return;
        if (voiceCache.containsKey(relativeUrl) && new java.io.File(voiceCache.get(relativeUrl)).exists()) {
            return; // already cached
        }
        final String fullUrl = ServerProtocol.withToken(
                ServerProtocol.baseUrl(prefs.getServer()) + relativeUrl,
                prefs.getToken());
        Log.d(TAG, "preloadVoiceFile start: " + fullUrl);

        // Capture only non-Activity references — the Runnable runs on a static pool and must
        // not pin this ChatActivity while a download is queued/in flight.
        final OkHttpClient client = httpClient;
        final File cacheDir = getCacheDir();

        voicePreloader.execute(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder().url(fullUrl).build();
                okhttp3.Response resp = client.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.e(TAG, "preload failed: " + resp.code());
                    return;
                }
                byte[] data = resp.body().bytes();
                final File cacheFile = new File(cacheDir, "voice_cache_" + relativeUrl.hashCode() + ".mp4");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
                fos.write(data);
                fos.close();
                // Size cap: clear the map only (leave files on disk so nothing currently playing breaks)
                if (voiceCache.size() >= VOICE_CACHE_MAX) {
                    voiceCache.clear();
                    Log.d(TAG, "voiceCache reached max, cleared");
                }
                voiceCache.put(relativeUrl, cacheFile.getAbsolutePath());
                Log.d(TAG, "preloadVoiceFile done: " + cacheFile.getAbsolutePath() + " (" + data.length + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "preloadVoiceFile error", e);
            }
        });
    }

    /**
     * Play voice from a local cache file using the singleton MediaPlayer.
     * Start waveform animation immediately. Playback from local file is instant.
     */
    private void playVoiceLocal(String localPath, MessageAdapter.VH h, int position, int durationSec) {
        Log.d(TAG, "playVoiceLocal: " + localPath);

        // Stop any previous playback
        if (voicePlayer != null) {
            try { voicePlayer.reset(); } catch (Exception ignored) {}
        } else {
            voicePlayer = new MediaPlayer();
        }

        // Start waveform immediately (zero-delay visual feedback)
        playingPosition = position;
        playingWaveform = h.waveform;
        h.playIcon.setText("■");
        h.waveform.startAnimation();

        // Reuse singleton MediaPlayer
        try {
            voicePlayer.reset();
            voicePlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "voicePlayer error: " + what + "/" + extra);
                resetVoicePlayback();
                return true;
            });
            voicePlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "voicePlayer completed");
                resetVoicePlayback();
            });
            voicePlayer.setDataSource(localPath);
            voicePlayer.prepare(); // local file → < 50ms
            voicePlayer.start();
            Log.d(TAG, "voicePlayer started");

            // Duration + progress
            final int totalMs = voicePlayer.getDuration();
            final int totalSec = totalMs / 1000;
            if (h.voiceDuration != null) {
                h.voiceDuration.setText(formatPlayTime(0, totalSec));
            }
            // Progress timer using Handler
            final Handler progressHandler = new Handler();
            progressHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (voicePlayer != null && voicePlayer.isPlaying() && playingPosition == position) {
                        int currentMs = voicePlayer.getCurrentPosition();
                        if (h.voiceDuration != null) {
                            h.voiceDuration.setText(formatPlayTime(currentMs / 1000, totalSec));
                        }
                        progressHandler.postDelayed(this, 200);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "playVoiceLocal error", e);
            resetVoicePlayback();
        }
    }

    private void resetVoicePlayback() {
        if (playingWaveform != null) {
            playingWaveform.stopAnimation();
            playingWaveform = null;
        }
        playingPosition = -1;
        runOnUiThread(() -> {
            if (adapter != null) adapter.notifyReload();
        });
    }

    private String formatPlayTime(int currentSec, int totalSec) {
        if (totalSec > 0 && currentSec > 0) {
            return currentSec + "\"";
        }
        return totalSec + "\"";
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
