package com.familychat.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableString;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.feature_group.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.GroupEncryptHelper;
import com.familychat.app.utils.GroupKeyChangeGate;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.familychat.app.ui.chat.GroupDetailPanel;
import com.familychat.app.ui.chat.SwipeContainer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 群聊界面（密语式）。
 * - 群消息 E2EE 用"每人一份密文"（GroupEncryptHelper），服务端只存不解。
 * - 支持全部消息类型：文字 / 图片 / 视频 / 语音 / 文件。
 * - 他人消息显示发送者昵称；点群名或右上角群图标进群详情。
 */
public class GroupChatActivity extends AppCompatActivity {

    private static final String TAG = "GroupChat";
    /** Tracks which group's chat is currently visible. -1 = none (用于主界面未读跳过的判断). */
    public static volatile int currentGroupId = -1;

    private PreferencesManager prefs;
    private ApiClient api;
    /**
     * 本页对推送连接的监听器。
     *
     * <p>连接不再由本页持有 —— 唯一持有者是 :core 的 {@code PushConnection}
     * （由 ForegroundService 驱动生命周期）。这里只留引用供 onDestroy 反注册。
     */
    private WebSocketManager.Listener wsListener;
    private Models.Group group;
    private String myAvatar, myAvatarColor;
    private List<Models.GroupMessage> messages = new ArrayList<>();
    private List<Models.User> members = new ArrayList<>();
    private final Map<Integer, Models.User> memberById = new HashMap<>();
    private List<Integer> memberIdList = new ArrayList<>();
    private final Gson gson = new Gson();
    private MessageAdapter adapter;
    private int groupId;
    // 统一用 HttpClientProvider 的共享 client（自带假域名模式 Dns），不再自建实例。
    private OkHttpClient httpClient = HttpClientProvider.shared();
    private VoiceRecordOverlay voiceOverlay;
    private RecyclerView rvMessages;
    private View plusPanel;
    private EditText etMessage;
    private ImageButton btnPlus;
    private int navInsetBottom = 0;

    // ── 群详情右滑面板（与单聊 ChatDetailPanel 进入方式一致）──
    private SwipeContainer swipeContainer;
    private GroupDetailPanel groupDetailPanel;

    // ── 阶段3：群公告横幅 + @提及 ──
    private View layoutAnnouncement;
    private TextView tvAnnouncementPreview;
    private int latestAnnouncementId;      // 最新公告 id（横幅）
    private int dismissedAnnouncementId;   // 本次会话已关闭的公告（不再显示）

    // Voice playback（与单聊一致：实例级 player，避免两个聊天共享）
    private MediaPlayer voicePlayer = null;
    private int playingPosition = -1;
    private WaveformView playingWaveform = null;
    private static final int VOICE_CACHE_MAX = 200;
    private static java.util.concurrent.ConcurrentHashMap<String, String> voiceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.concurrent.Executor voicePreloader = java.util.concurrent.Executors.newFixedThreadPool(3);

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private Uri cameraPhotoUri;
    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && cameraPhotoUri != null) {
                    uploadAndSendFile(cameraPhotoUri);
                }
            });

    /** 发送者昵称：从成员列表查，查不到显示"成员"（退出群/服务端异常兜底）。 */
    private String groupSenderName(int senderId) {
        if (senderId == prefs.getUserId()) return prefs.getMyNickname();
        Models.User m = memberById.get(senderId);
        return (m != null && m.nickname != null && !m.nickname.isEmpty()) ? m.nickname : "成员";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_group_chat);
        } catch (Exception e) {
            Log.d(TAG, "err: " + e.getMessage());
            finish();
            return;
        }

        try {
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
            api = new ApiClient(prefs.getServer(), prefs.getToken());

            groupId = getIntent().getIntExtra("group_id", 0);
            if (groupId <= 0) { finish(); return; }
            currentGroupId = groupId;

            myAvatar = prefs.getMyAvatar();
            myAvatarColor = prefs.getMyAvatarColor();

            rvMessages = findViewById(R.id.rvMessages);
            etMessage = findViewById(R.id.etMessage);
            plusPanel = findViewById(R.id.plusPanel);
            ImageButton btnBack = findViewById(R.id.btnBack);
            Button btnSend = findViewById(R.id.btnSend);
            btnPlus = findViewById(R.id.btnPlus);
            ImageButton btnVoiceToggle = findViewById(R.id.btnVoiceToggle);
            TextView btnHoldTalk = findViewById(R.id.btnHoldTalk);
            View layoutGroupTitle = findViewById(R.id.layoutGroupTitle);
            ImageButton btnGroupInfo = findViewById(R.id.btnGroupInfo);

            adapter = new MessageAdapter();
            LinearLayoutManager llm = new LinearLayoutManager(this);
            llm.setStackFromEnd(true);
            rvMessages.setLayoutManager(llm);
            rvMessages.setAdapter(adapter);

            filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        uploadAndSendFile(result.getData().getData());
                    }
                });
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
            etMessage.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) setPlusPanelVisible(false); });
            // 点群名 / 右上角群图标 → 群详情
            View.OnClickListener openDetail = v -> openGroupDetail();
            layoutGroupTitle.setOnClickListener(openDetail);
            btnGroupInfo.setOnClickListener(openDetail);
            // 顶栏"…"群功能菜单（全仿 QQ）
            ImageButton btnGroupMenu = findViewById(R.id.btnGroupMenu);
            btnGroupMenu.setOnClickListener(v -> showGroupMenu(btnGroupMenu));
            // 输入栏 @ 提及按钮
            ImageButton btnAt = findViewById(R.id.btnAt);
            btnAt.setOnClickListener(v -> showAtPicker());
            // 群公告横幅
            layoutAnnouncement = findViewById(R.id.layoutAnnouncement);
            tvAnnouncementPreview = findViewById(R.id.tvAnnouncementPreview);
            findViewById(R.id.tvAnnouncementView).setOnClickListener(v -> openAnnouncement());
            findViewById(R.id.tvAnnouncementClose).setOnClickListener(v -> {
                dismissedAnnouncementId = latestAnnouncementId;
                layoutAnnouncement.setVisibility(View.GONE);
            });
            setupPlusPanel();

            // ── 群详情右滑面板（与单聊一致：SwipeContainer 包聊天+详情，右滑进入群详情）──
            groupDetailPanel = new GroupDetailPanel(this, groupId, group, prefs, api, prefs.getUserId());
            groupDetailPanel.setCallback(new GroupDetailPanel.Callback() {
                @Override public void onOpenAnnouncement() { openAnnouncement(); }
                @Override public void onOpenFiles() { openGroupFiles(); }
                @Override public void onOpenAlbums() { openGroupAlbums(); }
                @Override public void onOpenPolls() { openGroupPolls(); }
                @Override public void onOpenSearch() { openGroupSearch(); }
                @Override public void onOpenFullDetail() { openGroupDetail(); }
                @Override public void onTogglePin(boolean pinned) { toggleGroupPin(pinned); }
                @Override public void onToggleQuiet(boolean quiet) { toggleGroupQuiet(quiet); }
                @Override public void onLeaveGroup() { finish(); }
            });
            View groupDetailView = groupDetailPanel.build();
            groupDetailView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            final ViewGroup contentRoot = findViewById(android.R.id.content);
            final View originalRoot = contentRoot.getChildAt(0);
            contentRoot.removeView(originalRoot);

            swipeContainer = new SwipeContainer(this);
            swipeContainer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.addView(originalRoot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.addView(groupDetailView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            swipeContainer.init(originalRoot, groupDetailView,
                    getResources().getDisplayMetrics().widthPixels);

            contentRoot.addView(swipeContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // 语音录制（密语式按住说话）
            voiceOverlay = new VoiceRecordOverlay(this);
            ((ViewGroup) findViewById(android.R.id.content)).addView(voiceOverlay);
            voiceOverlay.setCallback(new VoiceRecordOverlay.Callback() {
                @Override public void onSend(String filePath, int durationMs) { uploadVoice(filePath, durationMs); }
                @Override public void onCancel() {}
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

            btnSend.setOnClickListener(v -> {
                String text = etMessage.getText().toString().trim();
                if (text.isEmpty() || groupId == 0) return;
                etMessage.setText("");
                sendTextMessage(text);
            });

            // ── WebSocket：群消息 / 撤回 / 成员变化实时推送 ──
            // 连接宿主已下沉到 :core 的 PushConnection，本页只注册监听。
            try {
                wsListener = new WebSocketManager.Listener() {
                    @Override public void onConnected() {}
                    @Override public void onDisconnected() {}
                    @Override public void onNewMessage(Models.Message msg) {}
                    @Override public void onFriendRequest() {}
                    @Override public void onFriendRequestHandled(int fsId, String action, Models.User byUser) {}
                    @Override public void onUserOnline(int userId) {}
                    @Override public void onUserOffline(int userId) {}
                    @Override public void onCallOffer(int fromUserId, String offerSdp, boolean videoEnabled) {}
                    @Override public void onCallAnswer(String answerSdp) {}
                    @Override public void onCallIce(String candidateJson) {}
                    @Override public void onCallHangup(int fromUserId) {}
                    @Override public void onGroupNewMessage(Models.GroupMessage gmsg) {
                        if (gmsg == null || gmsg.group_id != groupId) return;
                        // 忽略自己的回显：服务端会广播给发送者本人，本地已乐观显示，否则会重复。
                        // 系统消息（msg_type=system）由服务端生成、我可能是触发者，需照常接收。
                        if (gmsg.sender_id == prefs.getUserId() && !"system".equals(gmsg.msg_type)) return;
                        runOnUiThread(() -> {
                            // P3-② 重放防护：同 id 消息（WS 重复推送/服务器重放旧密文）不再重复展示
                            for (Models.GroupMessage m : messages) {
                                if (gmsg.id > 0 && m.id == gmsg.id) {
                                    Log.d(TAG, "group WS push skipped (duplicate id=" + gmsg.id + ")");
                                    return;
                                }
                            }
                            gmsg.content = decryptGroup(gmsg);
                            messages.add(gmsg);
                            adapter.notifyAppended();
                            rvMessages.scrollToPosition(adapter.rowCount() - 1);
                        });
                    }
                    @Override public void onGroupMessageRecalled(int gid, int msgId) {
                        if (gid != groupId) return;
                        runOnUiThread(() -> {
                            for (int i = 0; i < messages.size(); i++) {
                                if (messages.get(i).id == msgId) {
                                    messages.get(i).content = "[已撤回]";
                                    messages.get(i).msg_type = "recalled";
                                    final int idx = i;
                                    adapter.notifyMessageChanged(idx);
                                    break;
                                }
                            }
                        });
                    }
                    @Override public void onGroupMembersChanged(int gid) {
                        if (gid != groupId) return;
                        reloadMembers();
                    }
                    @Override public void onGroupDissolved(int gid) {
                        if (gid != groupId) return;
                        runOnUiThread(() -> {
                            Toast.makeText(GroupChatActivity.this, "群聊已解散", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                    @Override public void onGroupRemoved(int gid) {
                        if (gid != groupId) return;
                        runOnUiThread(() -> {
                            Toast.makeText(GroupChatActivity.this, "你已被移出群聊", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                    @Override public void onGroupAnnouncement(int gid, String action, int aid) {
                        if (gid != groupId) return;
                        runOnUiThread(() -> loadAnnouncementBanner());
                    }
                    @Override public void onGroupMention(int gid, int messageId, int senderId, String senderName) {
                        // 提示用：@ 的新消息会走 onGroupNewMessage 正常展示，这里只加个轻提示
                        if (gid != groupId) return;
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                (senderName == null || senderName.isEmpty() ? "有人" : senderName) + " @了你",
                                Toast.LENGTH_SHORT).show());
                    }
                };
                PushConnection.addListener(wsListener);
            } catch (Exception e) {
                Log.e(TAG, "WS setup failed", e);
            }

            // 后台加载：群详情 + 成员 + 历史
            new Thread(() -> {
                try {
                    Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                    if (det != null && det.group != null) {
                        group = det.group;
                        members = det.members != null ? det.members : new ArrayList<>();
                        memberById.clear();
                        memberIdList = new ArrayList<>();
                        for (Models.User m : members) {
                            memberById.put(m.id, m);
                            memberIdList.add(m.id);
                        }
                        runOnUiThread(() -> {
                            updateGroupHeader();
                            loadAnnouncementBanner();
                            if (groupDetailPanel != null) groupDetailPanel.refresh(group);
                        });
                    }
                    // 历史（服务端只存密文，客户端逐条解密）
                    Models.GroupMessagesResponse res = api.getGroupMessages(groupId, 100);
                    if (res.messages != null) {
                        for (Models.GroupMessage gm : res.messages) {
                            gm.content = decryptGroup(gm);
                        }
                        final List<Models.GroupMessage> loaded = res.messages;
                        runOnUiThread(() -> {
                            messages.clear();
                            messages.addAll(loaded);
                            adapter.notifyReload();
                        });
                        // 打开群聊即清除本群未读，并标记服务端已读（下次进主界面群列表不再亮红点）
                        if (!loaded.isEmpty()) {
                            try { api.markGroupRead(groupId, loaded.get(loaded.size() - 1).id); } catch (Exception ignored) {}
                        }
                        prefs.setGroupUnread(groupId, 0);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "loadGroup failed", e);
                    // 报错不弹窗，只写入应用内日志（设置→日志 可查看）
                    com.familychat.app.utils.AppLog.e("GROUPS", "加载群聊失败 groupId=" + groupId, e);
                }
            }).start();
        } catch (Exception e) {
            Log.d(TAG, "err: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            finish();
        }
    }

    private void updateGroupHeader() {
        TextView tvName = findViewById(R.id.tvChatName);
        TextView tvStatus = findViewById(R.id.tvChatStatus);
        if (group != null) {
            if (tvName != null) tvName.setText(group.name != null && !group.name.isEmpty() ? group.name : "群聊");
            if (tvStatus != null) {
                int n = memberById.size();
                if (n > 0) tvStatus.setText(n + "人");
                else if (group.member_count > 0) tvStatus.setText(group.member_count + "人");
            }
        }
    }

    private void openGroupDetail() {
        Intent it = new Intent(this, GroupDetailActivity.class);
        it.putExtra("group_id", groupId);
        startActivity(it);
    }

    // ═══════════════ 阶段3：群功能菜单 / @提及 / 群公告横幅 ═══════════════

    /** 顶栏"…"群功能菜单（全仿 QQ）。 */
    private void showGroupMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "群公告");
        pm.getMenu().add(0, 2, 0, "群文件");
        pm.getMenu().add(0, 3, 0, "群相册");
        pm.getMenu().add(0, 4, 0, "群投票");
        pm.getMenu().add(0, 5, 0, "群设置");
        pm.getMenu().add(0, 6, 0, "群详情");
        pm.getMenu().add(0, 7, 0, "群内搜索");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openAnnouncement(); break;
                case 2: openGroupFiles(); break;
                case 3: openGroupAlbums(); break;
                case 4: openGroupPolls(); break;
                case 5: showGroupSettingsMenu(anchor); break;
                case 6: openGroupDetail(); break;
                case 7: openGroupSearch(); break;
            }
            return true;
        });
        pm.show();
    }

    /** 群内搜索页。 */
    private void openGroupSearch() {
        Intent it = new Intent(this, GroupSearchActivity.class);
        it.putExtra("group_id", groupId);
        it.putExtra("group_name", group != null && group.name != null ? group.name : "");
        startActivity(it);
    }

    /** 群文件库页（阶段4）。 */
    private void openGroupFiles() {
        Intent it = new Intent(this, GroupFileActivity.class);
        it.putExtra("group_id", groupId);
        it.putExtra("group_name", group != null && group.name != null ? group.name : "");
        startActivity(it);
    }

    /** 群相册页（阶段4）。 */
    private void openGroupAlbums() {
        Intent it = new Intent(this, GroupAlbumActivity.class);
        it.putExtra("group_id", groupId);
        it.putExtra("group_name", group != null && group.name != null ? group.name : "");
        startActivity(it);
    }

    /** 群投票页（阶段5）。 */
    private void openGroupPolls() {
        Intent it = new Intent(this, GroupPollActivity.class);
        it.putExtra("group_id", groupId);
        it.putExtra("group_name", group != null && group.name != null ? group.name : "");
        startActivity(it);
    }

    /** 群设置子菜单：置顶 / 免打扰。 */
    private void showGroupSettingsMenu(View anchor) {
        final boolean pinned = group != null && group.pinned == 1;
        final boolean quiet = group != null && group.quiet == 1;
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 11, 0, pinned ? "取消置顶会话" : "置顶会话");
        pm.getMenu().add(0, 12, 0, quiet ? "开启消息提醒" : "消息免打扰");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 11) {
                toggleGroupPin(!pinned);
            } else if (item.getItemId() == 12) {
                toggleGroupQuiet(!quiet);
            }
            return true;
        });
        pm.show();
    }

    private void toggleGroupPin(final boolean pinned) {
        new Thread(() -> {
            try {
                api.updateGroupSettings(groupId, pinned ? 1 : 0, null);
                if (group != null) group.pinned = pinned ? 1 : 0;
                prefs.setGroupPinned(groupId, pinned);
                // 成功不再弹 Toast：置顶现在由详情面板的开关直接表达状态，再弹一次是噪音。
                // ⚠️ 失败 Toast 必须保留（见 catch）：开关是乐观更新，服务端失败时它是唯一反馈。
            } catch (Exception e) {
                Log.e(TAG, "toggleGroupPin", e);
                runOnUiThread(() -> Toast.makeText(GroupChatActivity.this, "操作失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void toggleGroupQuiet(final boolean quiet) {
        new Thread(() -> {
            try {
                api.updateGroupSettings(groupId, null, quiet ? 1 : 0);
                if (group != null) group.quiet = quiet ? 1 : 0;
                prefs.setGroupQuiet(groupId, quiet);
                // 成功不再弹 Toast（同上）；失败 Toast 保留。
            } catch (Exception e) {
                Log.e(TAG, "toggleGroupQuiet", e);
                runOnUiThread(() -> Toast.makeText(GroupChatActivity.this, "操作失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 输入栏 @ 按钮：选择成员/全员，插入 @昵称 到输入框。 */
    private void showAtPicker() {
        if (members.isEmpty()) {
            reloadMembers();
            Toast.makeText(this, "成员加载中，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> names = new ArrayList<>();
        List<Integer> uids = new ArrayList<>();
        names.add("所有人");
        uids.add(-1);
        for (Models.User m : members) {
            names.add("@" + displayNameOf(m));
            uids.add(m.id);
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle("选择要@的成员")
            .setItems(names.toArray(new String[0]), (d, which) -> {
                int uid = uids.get(which);
                if (uid == -1) {
                    insertAtText("@所有人");
                } else {
                    insertAtText("@" + displayNameOf(members.get(which - 1)));
                }
            })
            .show();
    }

    /** 成员显示名（与群聊发送者昵称保持一致：全局昵称优先）。 */
    private String displayNameOf(Models.User m) {
        if (m == null) return "成员";
        return (m.nickname != null && !m.nickname.isEmpty()) ? m.nickname : ("成员" + m.id);
    }

    private void insertAtText(String token) {
        if (etMessage == null) return;
        int sel = etMessage.getSelectionStart();
        if (sel < 0) sel = etMessage.getText().length();
        etMessage.getText().insert(sel, token + " ");
        etMessage.setSelection(sel + token.length() + 1);
    }

    /** 从输入文本解析 @提及：@所有人 → "all"；否则收集匹配的成员 uid。 */
    private Object parseMentionsFromText(String text) {
        if (text == null || text.isEmpty() || !text.contains("@")) return null;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("@([\\p{L}\\p{N}_]+|所有人)");
            java.util.regex.Matcher m = p.matcher(text);
            List<Integer> mentioned = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            while (m.find()) {
                String name = m.group(1);
                if ("所有人".equals(name)) return "all";
                if (seen.contains(name)) continue;
                seen.add(name);
                for (Models.User u : members) {
                    if (name.equals(displayNameOf(u))) { mentioned.add(u.id); break; }
                }
            }
            return mentioned.isEmpty() ? null : mentioned;
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本气泡 @提及高亮：@成员昵称 / @所有人 显示为蓝色。 */
    private CharSequence highlightMentions(String text) {
        if (text == null || text.isEmpty() || !text.contains("@")) return text;
        try {
            SpannableString ss = new SpannableString(text);
            int color = getColor(R.color.green);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("@([\\p{L}\\p{N}_]+|所有人)");
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                boolean matched = "所有人".equals(name);
                if (!matched) {
                    for (Models.User u : members) {
                        if (name.equals(displayNameOf(u))) { matched = true; break; }
                    }
                }
                if (matched) {
                    ss.setSpan(new android.text.style.ForegroundColorSpan(color), m.start(), m.end(),
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return ss;
        } catch (Exception e) {
            return text;
        }
    }

    /** 加载公告横幅：取最新公告，解密预览，显示在消息列表上方（全仿 QQ）。 */
    private void loadAnnouncementBanner() {
        if (layoutAnnouncement == null || tvAnnouncementPreview == null) return;
        new Thread(() -> {
            try {
                Models.AnnouncementListResponse res = api.getGroupAnnouncements(groupId);
                if (res.announcements == null || res.announcements.isEmpty()) {
                    runOnUiThread(() -> layoutAnnouncement.setVisibility(View.GONE));
                    return;
                }
                Models.GroupAnnouncement latest = res.announcements.get(0);
                final int aid = latest.id;
                final String plain = decryptAnnouncement(latest);
                runOnUiThread(() -> {
                    latestAnnouncementId = aid;
                    if (group != null) group.latest_announcement_id = aid;
                    if (dismissedAnnouncementId == aid) {
                        layoutAnnouncement.setVisibility(View.GONE);
                        return;
                    }
                    tvAnnouncementPreview.setText(plain == null || plain.isEmpty() ? "查看群公告" : plain);
                    layoutAnnouncement.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadAnnouncementBanner", e);
            }
        }).start();
    }

    /** 解密公告内容（{成员id:密文} JSON → 明文）。 */
    private String decryptAnnouncement(Models.GroupAnnouncement ann) {
        if (ann.content == null || !ann.content.trim().startsWith("{")) return ann.content;
        try {
            return GroupEncryptHelper.decryptGroupMessage(ann.content, ann.publisher_id,
                    prefs.getUserId(), api, prefs);
        } catch (Exception e) {
            return "[公告解密失败]";
        }
    }

    private void openAnnouncement() {
        Intent it = new Intent(this, GroupAnnouncementActivity.class);
        it.putExtra("group_id", groupId);
        it.putExtra("group_name", group != null && group.name != null ? group.name : "");
        startActivity(it);
    }

    /**
     * 发送前确保成员列表已加载：避免"进群立刻发消息"时成员还没拉下来，密文只加密给空列表、
     * 别人收到 [发送者未将你列入加密名单]。
     *
     * @return true 表示成员列表可用；false 表示加载失败或结果为空，调用方必须中止发送并提示用户
     */
    private boolean ensureMembersLoaded() {
        if (!memberIdList.isEmpty()) return true;
        try {
            Models.GroupDetailResponse det = api.getGroupDetail(groupId);
            if (det != null) {
                if (det.group != null) group = det.group;
                members = det.members != null ? det.members : new ArrayList<>();
                memberById.clear();
                memberIdList = new ArrayList<>();
                for (Models.User m : members) {
                    memberById.put(m.id, m);
                    memberIdList.add(m.id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureMembersLoaded", e);
            return false;
        }
        // 接口成功但成员为空（被踢/群已解散等）：同样不能继续用空列表加密
        return !memberIdList.isEmpty();
    }

    /** 后台刷新成员（成员变化推送后调用），并更新群信息。 */
    private void reloadMembers() {
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null) {
                    if (det.group != null) group = det.group;
                    members = det.members != null ? det.members : new ArrayList<>();
                    memberById.clear();
                    memberIdList = new ArrayList<>();
                    for (Models.User m : members) {
                        memberById.put(m.id, m);
                        memberIdList.add(m.id);
                    }
                    runOnUiThread(() -> {
                        updateGroupHeader();
                        adapter.notifyReload();
                        if (groupDetailPanel != null) groupDetailPanel.refresh(group);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "reloadMembers failed", e);
            }
        }).start();
    }

    /** 解密一条群消息（"每人一份密文" JSON → 明文/URL）。非 JSON 由 helper 按 msg_type 判定。 */
    private String decryptGroup(Models.GroupMessage gm) {
        if (gm.content == null || "recalled".equals(gm.msg_type)) return gm.content;
        try {
            return GroupEncryptHelper.decryptGroupMessage(
                    gm.content, gm.sender_id, prefs.getUserId(), api, prefs, gm.msg_type);
        } catch (Exception e) {
            com.familychat.app.utils.AppLog.e("E2EE",
                    "群消息解密失败 msgId=" + gm.id + " sender=" + gm.sender_id
                            + " error=" + e.getMessage());
            return GroupEncryptHelper.PLACEHOLDER_FAILED;
        }
    }

    /** 为群里每个成员各加密一份。json==null 表示无法加密（本地私钥缺失），应取消发送。 */
    private GroupEncryptHelper.EncryptResult encryptForGroup(String plain) {
        try {
            return GroupEncryptHelper.encryptForGroup(plain, prefs.getUserId(),
                    memberIdList, api, prefs);
        } catch (Exception e) {
            Log.e(TAG, "encryptForGroup failed", e);
            GroupEncryptHelper.EncryptResult r = new GroupEncryptHelper.EncryptResult();
            r.json = null;
            r.ownKeyMissing = true;
            return r;
        }
    }

    /** 群成员显示名（找不到就退化成"用户&lt;id&gt;"），传给 {@link GroupKeyChangeGate} 用于告警列表。 */
    private String memberDisplayName(int uid) {
        Models.User u = memberById.get(uid);
        return (u != null && u.nickname != null && !u.nickname.isEmpty()) ? u.nickname : ("用户" + uid);
    }

    private void sendTextMessage(String text) {
        if (isMuted()) {
            Toast.makeText(this, "你已被禁言", Toast.LENGTH_SHORT).show();
            return;
        }
        Models.GroupMessage msg = new Models.GroupMessage();
        msg.sender_id = prefs.getUserId();
        msg.group_id = groupId;
        msg.content = text;
        msg.msg_type = "text";
        messages.add(msg);
        adapter.notifyAppended();
        rvMessages.scrollToPosition(adapter.rowCount() - 1);

        new Thread(() -> {
            try {
                if (!ensureMembersLoaded()) {
                    runOnUiThread(() -> {
                        messages.remove(msg);
                        adapter.notifyReload();
                        Toast.makeText(GroupChatActivity.this,
                                "无法获取群成员列表，请检查网络后重试", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                Object mentions = parseMentionsFromText(text);
                GroupEncryptHelper.EncryptResult er = GroupKeyChangeGate.encryptWithGate(
                        this, prefs, this::encryptForGroup, this::memberDisplayName, text);
                if (er == null || er.json == null) {
                    // P1-②: 本地私钥缺失 / 用户对"成员密钥已变更"选了取消 → 取消发送并提示
                    Log.e(TAG, "send text blocked: cannot encrypt (own key missing)");
                    runOnUiThread(() -> {
                        messages.remove(msg);
                        adapter.notifyReload();
                        Toast.makeText(GroupChatActivity.this,
                                "无法加密发送：本地缺少加密密钥，请重新登录后重试", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                Models.GroupMessage saved = api.sendGroupMessage(groupId, er.json, "text", null, null, 0, mentions);
                if (saved != null) {
                    // 回填真实 id/时间（撤回需要真实 id）
                    msg.id = saved.id;
                    msg.created_at = saved.created_at;
                    // 回填后必须重算显示行：乐观插入那一刻 created_at 还是 null，
                    // rebuildRows() 的 `if (t > 0)` 会跳过这条的时间行；不 notify 就永远不补。
                    runOnUiThread(() -> adapter.notifyReload());
                }
                if (!er.skippedMembers.isEmpty()) {
                    // P2-⑥: 告知发送者哪些成员收不到（不静默跳过）
                    final int n = er.skippedMembers.size();
                    runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                            "已发送，但有 " + n + " 名成员未设置密钥，无法查看此消息", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "send text failed", e);
                runOnUiThread(() -> {
                    // 发送失败（如禁言 403）：移除乐观消息并提示，避免"看似已发送实则未发"
                    messages.remove(msg);
                    adapter.notifyReload();
                    Toast.makeText(GroupChatActivity.this,
                            e.getMessage() != null ? e.getMessage() : "发送失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /** 我在本群是否被禁言（群详情返回 muted）。 */
    private boolean isMuted() {
        return group != null && group.muted == 1;
    }

    /** 上传并发送图片/文件消息（群聊）。 */
    private void uploadAndSendFile(Uri uri) {
        Log.d(TAG, "上传中...");
        new Thread(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                boolean isImage = mime != null && mime.startsWith("image/");

                String extension = "";
                String uriPath = uri.getPath();
                if (uriPath != null && uriPath.contains(".")) {
                    extension = uriPath.substring(uriPath.lastIndexOf('.'));
                } else if (mime != null) {
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
                String originalName = queryDisplayName(uri);
                if (originalName == null || originalName.isEmpty()) originalName = fileName;

                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) { runOnUiThread(() -> Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show()); return; }
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
                    runOnUiThread(() -> Toast.makeText(this, "上传失败: " + resp.code(), Toast.LENGTH_SHORT).show());
                    return;
                }
                String respBody = resp.body() != null ? resp.body().string() : "";
                JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
                if (json.has("url")) {
                    String url = json.get("url").getAsString();
                    String msgType = isImage ? "image" : "file";
                    if (!ensureMembersLoaded()) {
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "无法获取群成员列表，请检查网络后重试", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    GroupEncryptHelper.EncryptResult er = GroupKeyChangeGate.encryptWithGate(
                            this, prefs, this::encryptForGroup, this::memberDisplayName, url);
                    if (er == null || er.json == null) {
                        // P1-②: 本地私钥缺失时禁止明文回退，取消发送
                        Log.e(TAG, "send file blocked: cannot encrypt (own key missing)");
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "无法加密发送：本地缺少加密密钥，请重新登录后重试", Toast.LENGTH_LONG).show());
                        return;
                    }
                    Models.GroupMessage saved = api.sendGroupMessage(groupId, er.json, msgType, originalName, null, 0);
                    if (!er.skippedMembers.isEmpty()) {
                        // P2-⑥: 告知发送者哪些成员收不到
                        final int n = er.skippedMembers.size();
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "已发送，但有 " + n + " 名成员无法查看此消息（未设置密钥或密钥已变更）", Toast.LENGTH_LONG).show());
                    }
                    // 本地立即显示（明文 URL）
                    Models.GroupMessage msg = new Models.GroupMessage();
                    msg.sender_id = prefs.getUserId();
                    msg.group_id = groupId;
                    msg.content = url;
                    msg.msg_type = msgType;
                    msg.filename = originalName;
                    if (saved != null) { msg.id = saved.id; msg.created_at = saved.created_at; }
                    runOnUiThread(() -> {
                        messages.add(msg);
                        adapter.notifyAppended();
                        rvMessages.scrollToPosition(adapter.rowCount() - 1);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadAndSendFile", e);
                runOnUiThread(() -> Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 上传并发送视频消息（群聊）：首帧缩略图 + 视频。 */
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
                    if (mime.equals("video/quicktime")) extension = ".mov";
                    else if (mime.startsWith("video/")) extension = ".mp4";
                }
                String originalName = queryDisplayName(uri);
                if (originalName == null || originalName.isEmpty()) originalName = "视频_" + System.currentTimeMillis() + extension;

                byte[] videoBytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) { runOnUiThread(() -> Toast.makeText(this, "无法读取视频", Toast.LENGTH_SHORT).show()); return; }
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    videoBytes = baos.toByteArray();
                }

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
                    runOnUiThread(() -> Toast.makeText(this, "视频上传失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                String thumbUrl = "";
                if (thumbBytes != null && thumbBytes.length > 0) {
                    thumbUrl = uploadFileBytes(thumbBytes, "thumb_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
                }

                String sendContent = videoUrl;
                if (!ensureMembersLoaded()) {
                    runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                            "无法获取群成员列表，请检查网络后重试", Toast.LENGTH_SHORT).show());
                    return;
                }
                GroupEncryptHelper.EncryptResult er = GroupKeyChangeGate.encryptWithGate(
                        this, prefs, this::encryptForGroup, this::memberDisplayName, videoUrl);
                if (er == null || er.json == null) {
                    // P1-②: 本地私钥缺失时禁止明文回退，取消发送
                    Log.e(TAG, "send video blocked: cannot encrypt (own key missing)");
                    runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                            "无法加密发送：本地缺少加密密钥，请重新登录后重试", Toast.LENGTH_LONG).show());
                    return;
                }
                sendContent = er.json;
                Models.GroupMessage saved = api.sendGroupMessage(groupId, sendContent, "video", originalName, thumbUrl, 0);
                if (!er.skippedMembers.isEmpty()) {
                    // P2-⑥: 告知发送者哪些成员收不到
                    final int n = er.skippedMembers.size();
                    runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                            "已发送，但有 " + n + " 名成员未设置密钥，无法查看此消息", Toast.LENGTH_LONG).show());
                }

                Models.GroupMessage msg = new Models.GroupMessage();
                msg.sender_id = prefs.getUserId();
                msg.group_id = groupId;
                msg.content = videoUrl;
                msg.msg_type = "video";
                msg.thumbnail = thumbUrl;
                msg.filename = originalName;
                if (saved != null) { msg.id = saved.id; msg.created_at = saved.created_at; }
                runOnUiThread(() -> {
                    messages.add(msg);
                    adapter.notifyAppended();
                    rvMessages.scrollToPosition(adapter.rowCount() - 1);
                });
            } catch (Exception e) {
                Log.e(TAG, "uploadAndSendVideo", e);
                runOnUiThread(() -> Toast.makeText(this, "发送视频失败", Toast.LENGTH_SHORT).show());
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
            if (!resp.isSuccessful()) { Log.e(TAG, "uploadFileBytes: " + resp.code()); return null; }
            String respBody = resp.body() != null ? resp.body().string() : "";
            JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
            return json.has("url") ? json.get("url").getAsString() : null;
        } catch (Exception e) {
            Log.e(TAG, "uploadFileBytes", e);
            return null;
        }
    }

    /** 上传并发送语音消息（群聊，带时长）。 */
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
                    String sendContent = url;
                    if (!ensureMembersLoaded()) {
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "无法获取群成员列表，请检查网络后重试", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    GroupEncryptHelper.EncryptResult er = GroupKeyChangeGate.encryptWithGate(
                            this, prefs, this::encryptForGroup, this::memberDisplayName, url);
                    if (er == null || er.json == null) {
                        // P1-②: 本地私钥缺失时禁止明文回退，取消发送
                        Log.e(TAG, "send voice blocked: cannot encrypt (own key missing)");
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "无法加密发送：本地缺少加密密钥，请重新登录后重试", Toast.LENGTH_LONG).show());
                        return;
                    }
                    sendContent = er.json;
                    Models.GroupMessage saved = api.sendGroupMessage(groupId, sendContent, "voice", null, null, durationSec);
                    if (!er.skippedMembers.isEmpty()) {
                        // P2-⑥: 告知发送者哪些成员收不到
                        final int n = er.skippedMembers.size();
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "已发送，但有 " + n + " 名成员无法查看此消息（未设置密钥或密钥已变更）", Toast.LENGTH_LONG).show());
                    }

                    Models.GroupMessage msg = new Models.GroupMessage();
                    msg.sender_id = prefs.getUserId();
                    msg.group_id = groupId;
                    msg.content = url;
                    msg.msg_type = "voice";
                    msg.duration_sec = durationSec;
                    if (saved != null) { msg.id = saved.id; msg.created_at = saved.created_at; }
                    runOnUiThread(() -> {
                        messages.add(msg);
                        adapter.notifyAppended();
                        rvMessages.scrollToPosition(adapter.rowCount() - 1);
                    });
                }
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "uploadVoice", e);
            }
        }).start();
    }

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

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        filePickerLauncher.launch(intent);
    }

    private void pickVideoFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        videoPickerLauncher.launch(intent);
    }

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
                java.io.File photoFile = java.io.File.createTempFile("camera_", ".jpg", getCacheDir());
                cameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", photoFile);
                intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                cameraLauncher.launch(intent);
            } catch (Exception e) {
                Log.d(TAG, "无法启动相机");
            }
        } else {
            Log.d(TAG, "没有可用的相机应用");
        }
    }

    /** 群聊"+"面板：相册 / 拍摄 / 视频 / 文件（无通话，群里没有单聊通话）。 */
    private void setupPlusPanel() {
        try {
            android.widget.GridLayout grid = findViewById(R.id.plusGrid);
            addPanelItem(grid, R.drawable.ic_album, "相册", this::pickImageFromGallery);
            addPanelItem(grid, R.drawable.ic_camera, "拍摄", this::captureFromCamera);
            addPanelItem(grid, R.drawable.ic_video_send, "视频", this::pickVideoFromGallery);
            addPanelItem(grid, R.drawable.ic_file, "文件", this::pickFile);
        } catch (Exception e) {
            Log.e(TAG, "setupPlusPanel", e);
        }
    }

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
            try { action.run(); } catch (Exception e) { Log.e(TAG, "plusPanelItem", e); }
        });
        grid.addView(item);
    }

    private void togglePlusPanel() {
        setPlusPanelVisible(plusPanel.getVisibility() != View.VISIBLE);
    }

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

    private void applyNavPadding() {
        View inputBar = findViewById(R.id.layoutInput);
        if (inputBar == null || plusPanel == null) return;
        boolean panelVisible = plusPanel.getVisibility() == View.VISIBLE;
        inputBar.setPadding(inputBar.getPaddingLeft(), inputBar.getPaddingTop(),
                inputBar.getPaddingRight(), panelVisible ? 0 : navInsetBottom);
        plusPanel.setPadding(plusPanel.getPaddingLeft(), plusPanel.getPaddingTop(),
                plusPanel.getPaddingRight(), panelVisible ? navInsetBottom : 0);
    }

    private void toggleVoiceMode(EditText input, TextView holdTalk, ImageButton toggleBtn) {
        if (input.getVisibility() == View.VISIBLE) {
            input.setVisibility(View.GONE);
            holdTalk.setVisibility(View.VISIBLE);
            toggleBtn.setImageResource(R.drawable.ic_msg);
        } else {
            input.setVisibility(View.VISIBLE);
            holdTalk.setVisibility(View.GONE);
            toggleBtn.setImageResource(R.drawable.ic_voice);
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
                runOnUiThread(() -> Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "saveImage", e);
            }
        }).start();
    }

    private void downloadAndOpenFile(String path, String fileName) {
        new Thread(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + path, prefs.getToken()))
                        .build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    runOnUiThread(() -> Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                byte[] data = resp.body().bytes();
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
                        Toast.makeText(this, "文件已保存到下载目录", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showMessageMenu(Models.GroupMessage msg, int position) {
        boolean isMe = msg.sender_id == prefs.getUserId();
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (msg.content != null && !msg.content.isEmpty()) {
            if ("text".equals(msg.msg_type)) {
                items.add("复制");
                final String textContent = msg.content;
                actions.add(() -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("消息", textContent);
                    clipboard.setPrimaryClip(clip);
                });
            } else if ("image".equals(msg.msg_type)) {
                items.add("复制");
                final String imgFullUrl = ServerProtocol.baseUrl(prefs.getServer()) + msg.content;
                actions.add(() -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("图片链接", imgFullUrl);
                    clipboard.setPrimaryClip(clip);
                });
            }
        }
        if ("image".equals(msg.msg_type) && msg.content != null && !msg.content.isEmpty()) {
            items.add("保存");
            String imgFullUrl = ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + msg.content,
                    prefs.getToken());
            actions.add(() -> saveImageToGallery(imgFullUrl));
        }
        if (isMe && !"recalled".equals(msg.msg_type)) {
            items.add("撤回");
            actions.add(() -> {
                new Thread(() -> {
                    try {
                        int targetId = msg.id;
                        if (targetId == 0) {
                            runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                    "撤回失败: 消息ID异常，请退出重进聊天后再试", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        Models.StatusResponse resp = api.recallGroupMessage(groupId, targetId);
                        if (resp != null && resp.success) {
                            msg.id = targetId;
                            msg.content = "[已撤回]";
                            msg.msg_type = "recalled";
                            runOnUiThread(() -> adapter.notifyMessageChanged(position));
                        } else {
                            String err = (resp != null && resp.error != null && !resp.error.isEmpty())
                                    ? resp.error : "服务器未找到该消息";
                            runOnUiThread(() -> Toast.makeText(GroupChatActivity.this, "撤回失败: " + err, Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                "撤回失败: " + (e.getMessage() != null ? e.getMessage() : "网络错误"), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });
        }
        if (items.isEmpty()) return;
        String[] itemArr = items.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
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
    protected void onResume() {
        super.onResume();
        String newAvatar = prefs.getMyAvatar();
        if (adapter != null && myAvatar != null && !myAvatar.equals(newAvatar)) {
            myAvatar = newAvatar;
            adapter.notifyReload();
        }
    }

    /**
     * ★ 连接宿主下沉顺带修复：currentGroupId 原先只在 onDestroy 清除。
     *
     * <p>本页作为栈内页面会在"用户已经离开"之后长期存活（返回主界面、打开群详情、
     * 按 Home 等），此时 currentGroupId 仍等于那个群的 id，MainActivity 便判定
     * "用户正在这个群里"，于是**不累计未读、不弹通知**。
     * 改为与可见性对齐的 onStart/onStop；onCreate/onDestroy 的赋值保留作双保险。
     */
    @Override
    protected void onStart() {
        super.onStart();
        currentGroupId = groupId;
    }

    @Override
    protected void onStop() {
        currentGroupId = -1;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        currentGroupId = -1;
        if (voicePlayer != null) { try { voicePlayer.release(); } catch (Exception ignored) {} voicePlayer = null; }
        playingPosition = -1;
        playingWaveform = null;
        // ⚠️ 只反注册监听器：连接归 PushConnection（由 ForegroundService 驱动），
        // 调用 stop() 会切断整个后台推送。
        PushConnection.removeListener(wsListener);
        wsListener = null;
        if (voiceOverlay != null) {
            voiceOverlay.destroy();
            ((ViewGroup) findViewById(android.R.id.content)).removeView(voiceOverlay);
            voiceOverlay = null;
        }
    }

    // ═══════════════ MessageAdapter（群聊，含发送者昵称） ═══════════════

    class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
        private static final int TYPE_ME = 0, TYPE_OTHER = 1, TYPE_TIME = 2, TYPE_SYSTEM = 3;
        private static final long TIME_THRESHOLD_MS = 5 * 60 * 1000L;

        private static class Row {
            final int kind;        // 0=消息行 1=时间行
            final int msgIndex;
            final String timeText;
            Row(int kind, int msgIndex, String timeText) {
                this.kind = kind; this.msgIndex = msgIndex; this.timeText = timeText;
            }
        }
        private final List<Row> rows = new ArrayList<>();

        void rebuildRows() {
            rows.clear();
            int n = messages.size();
            for (int i = 0; i < n; i++) {
                long t = msgMillis(messages.get(i));
                if (t > 0) {
                    boolean showTime;
                    if (i == 0) {
                        showTime = true;
                    } else {
                        long prev = msgMillis(messages.get(i - 1));
                        showTime = prev <= 0 || (t - prev) >= TIME_THRESHOLD_MS;
                    }
                    if (showTime) rows.add(new Row(1, -1, ChatTimeFormatter.format(t)));
                }
                rows.add(new Row(0, i, null));
            }
        }

        private long msgMillis(Models.GroupMessage m) {
            if (m.timestamp > 0) return m.timestamp;
            m.timestamp = ChatTimeFormatter.parseUtc(m.created_at);
            return m.timestamp;
        }

        int rowCount() { return rows.size(); }

        int rowIndexOfMessage(int msgIndex) {
            for (int r = 0; r < rows.size(); r++) {
                if (rows.get(r).kind == 0 && rows.get(r).msgIndex == msgIndex) return r;
            }
            return -1;
        }

        void notifyReload() { rebuildRows(); notifyDataSetChanged(); }

        void notifyAppended() {
            rebuildRows();
            int lastMsg = messages.size() - 1;
            int r = rowIndexOfMessage(lastMsg);
            if (r < 0) { notifyDataSetChanged(); return; }
            // 时间行会插在消息行之前 —— 通知区间必须从它开始
            int from = (r > 0 && rows.get(r - 1).kind == 1) ? r - 1 : r;
            notifyItemRangeInserted(from, rows.size() - from);
        }

        void notifyMessageChanged(int msgIndex) {
            rebuildRows();
            int r = rowIndexOfMessage(msgIndex);
            if (r >= 0) notifyItemChanged(r);
        }

        @Override
        public int getItemViewType(int position) {
            Row r = rows.get(position);
            if (r.kind == 1) return TYPE_TIME;
            Models.GroupMessage m = messages.get(r.msgIndex);
            if ("system".equals(m.msg_type)) return TYPE_SYSTEM;
            return m.sender_id == prefs.getUserId() ? TYPE_ME : TYPE_OTHER;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_TIME) {
                View v = getLayoutInflater().inflate(R.layout.item_msg_time, parent, false);
                return new VH(v);
            }
            if (viewType == TYPE_SYSTEM) {
                View v = getLayoutInflater().inflate(R.layout.item_group_msg_system, parent, false);
                return new VH(v);
            }
            int layout = viewType == TYPE_ME ? R.layout.item_msg_me : R.layout.item_group_msg_other;
            View itemView = getLayoutInflater().inflate(layout, parent, false);
            VH h = new VH(itemView);
            h.itemGravityDefault = viewType == TYPE_ME ? Gravity.END : Gravity.START;

            // 程序化构建语音气泡容器（与单聊一致）
            android.widget.FrameLayout voiceContainer = new android.widget.FrameLayout(GroupChatActivity.this);
            voiceContainer.setVisibility(View.GONE);
            voiceContainer.setTag("voice_container");

            android.graphics.drawable.GradientDrawable bubbleBg = new android.graphics.drawable.GradientDrawable();
            bubbleBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bubbleBg.setCornerRadius(dp(8));
            if (viewType == TYPE_ME) {
                bubbleBg.setColor(getColor(R.color.bubble_self));
            } else {
                bubbleBg.setColor(getColor(R.color.bubble_other));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                voiceContainer.setBackground(bubbleBg);
            } else {
                voiceContainer.setBackgroundDrawable(bubbleBg);
            }

            android.widget.LinearLayout voiceRow = new android.widget.LinearLayout(GroupChatActivity.this);
            voiceRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            voiceRow.setGravity(Gravity.CENTER_VERTICAL);
            voiceRow.setPadding(dp(10), dp(6), dp(12), dp(6));
            voiceRow.setTag("voice_row");

            android.widget.FrameLayout playBtn = new android.widget.FrameLayout(GroupChatActivity.this);
            int playSize = dp(32);
            playBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(playSize, playSize));
            android.graphics.drawable.GradientDrawable playBg = new android.graphics.drawable.GradientDrawable();
            playBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            playBg.setColor(getColor(R.color.play_btn_bg));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                playBtn.setBackground(playBg);
            } else {
                playBtn.setBackgroundDrawable(playBg);
            }
            TextView playIcon = new TextView(GroupChatActivity.this);
            playIcon.setText("▶");
            playIcon.setTextColor(getColor(R.color.play_icon));
            playIcon.setTextSize(14);
            playIcon.setGravity(Gravity.CENTER);
            playBtn.addView(playIcon, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            playBtn.setTag("voice_play_btn");

            WaveformView waveform = new WaveformView(GroupChatActivity.this);
            waveform.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(64), dp(24)));
            waveform.setTag("voice_waveform");

            TextView durationTv = new TextView(GroupChatActivity.this);
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

            ViewGroup itemContent = (ViewGroup) itemView;
            if (viewType == TYPE_OTHER) {
                View contentLl = itemContent.getChildAt(1);
                if (contentLl instanceof ViewGroup) {
                    ((ViewGroup) contentLl).addView(voiceContainer, 1); // 0 是发送者昵称，语音气泡放昵称下面
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
            if (r.kind == 1) {
                if (h.timeText != null) h.timeText.setText(r.timeText);
                return;
            }
            final int msgIdx = r.msgIndex;
            Models.GroupMessage msg = messages.get(msgIdx);
            String type = msg.msg_type != null ? msg.msg_type : "text";
            final String content = msg.content != null ? msg.content : "";

            // 系统消息：居中灰字，无气泡无头像（服务端生成的明文中文）
            if ("system".equals(type)) {
                h.text.setText(content.isEmpty() ? "系统消息" : content);
                h.text.setTextColor(getColor(R.color.text_secondary));
                h.text.setTextSize(12);
                h.text.setGravity(Gravity.CENTER);
                h.text.setBackground(null);
                h.text.setPadding(0, dp(8), 0, dp(8));
                if (h.avatar != null) h.avatar.setVisibility(View.GONE);
                if (h.senderName != null) h.senderName.setVisibility(View.GONE);
                if (h.rootLayout != null) h.rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);
                return;
            }

            h.text.setBackground(h.textBgDefault);
            h.text.setTextColor(h.textColorDefault);
            h.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, h.textSizeDefault);
            h.text.setGravity(h.textGravityDefault);
            h.text.setPadding(dp(14), dp(12), dp(14), dp(12));
            h.rootLayout.setGravity(h.itemGravityDefault);
            if (h.avatar != null) h.avatar.setVisibility(View.VISIBLE);

            if (h.imageWrap != null) {
                h.imageWrap.setVisibility(View.GONE);
                h.imageWrap.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
            h.image.setVisibility(View.GONE);
            h.text.setVisibility(View.VISIBLE);
            h.voiceContainer.setVisibility(View.GONE);

            // 群聊：他人消息显示发送者昵称
            if (h.senderName != null) {
                if (msg.sender_id == prefs.getUserId()) {
                    h.senderName.setVisibility(View.GONE);
                } else {
                    h.senderName.setVisibility(View.VISIBLE);
                    h.senderName.setText(groupSenderName(msg.sender_id));
                }
            }

            if ("recalled".equals(type)) {
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
                    new ImagePreviewDialog(GroupChatActivity.this, imgUrl).show();
                });
                h.image.setOnLongClickListener(v -> {
                    showMessageMenu(msg, imgPos);
                    return true;
                });
            } else if ("video".equals(type)) {
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
                    h.image.setImageDrawable(null);
                    if (h.imageWrap != null) h.imageWrap.setBackgroundColor(0xFF2A2A2A);
                }
                final int videoPos = msgIdx;
                View videoTap = h.imageWrap != null ? h.imageWrap : h.image;
                videoTap.setOnClickListener(v -> {
                    try {
                        Intent it = new Intent(GroupChatActivity.this, ChatVideoPlayerActivity.class);
                        it.putExtra("url", videoUrl);
                        startActivity(it);
                    } catch (Exception e) {
                        Log.e(TAG, "openVideo", e);
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

                int voiceDuration = msg.duration_sec > 0 ? msg.duration_sec : 1;
                h.voiceDuration.setText(voiceDuration + "\"");

                final String voiceUrl = content;
                h.btnPlay.setAlpha(1f);
                h.btnPlay.setEnabled(true);
                preloadVoiceFile(voiceUrl);

                boolean isCached = voiceCache.containsKey(voiceUrl)
                        && new java.io.File(voiceCache.get(voiceUrl)).exists();

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

                final int pos = i;
                h.btnPlay.setOnClickListener(v -> {
                    if (pos == playingPosition && voicePlayer != null && voicePlayer.isPlaying()) {
                        voicePlayer.pause();
                        h.playIcon.setText("▶");
                        h.waveform.stopAnimation();
                        return;
                    }
                    String cachedPath = voiceCache.get(voiceUrl);
                    if (cachedPath != null && new java.io.File(cachedPath).exists()) {
                        playVoiceLocal(cachedPath, h, pos, voiceDuration);
                    } else {
                        h.playIcon.setText("⋯");
                        h.btnPlay.setEnabled(false);
                        preloadVoiceFile(voiceUrl);
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
            } else {
                h.text.setText(highlightMentions(content));
            }

            // 头像
            if (h.avatar != null) {
                if (msg.sender_id == prefs.getUserId()) {
                    loadMsgAvatar(h.avatar, myAvatar, myAvatarColor);
                } else {
                    Models.User m = memberById.get(msg.sender_id);
                    String av = (m != null) ? m.avatar : "";
                    String ac = (m != null) ? m.avatar_color : "#e8f5e9";
                    loadMsgAvatar(h.avatar, av, ac);
                }
            }

            h.itemView.setOnLongClickListener(v -> {
                showMessageMenu(msg, msgIdx);
                return true;
            });
        }

        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView text; ImageView image, avatar;
            View imageWrap;
            TextView msgPlay;
            TextView senderName; // 群聊：他人消息的发送者昵称
            TextView timeText;
            View voiceContainer, voiceRow, btnPlay;
            TextView playIcon, voiceDuration;
            WaveformView waveform;
            android.graphics.drawable.Drawable textBgDefault;
            int textColorDefault;
            float textSizeDefault;
            int textGravityDefault;
            int itemGravityDefault;
            android.widget.LinearLayout rootLayout;

            VH(View v) {
                super(v);
                rootLayout = (android.widget.LinearLayout) v;
                timeText = v.findViewById(R.id.tvMsgTime);
                if (timeText != null) return;
                text = v.findViewById(R.id.tvMsgText);
                image = v.findViewById(R.id.ivMsgImage);
                imageWrap = v.findViewById(R.id.ivMsgImageWrap);
                msgPlay = v.findViewById(R.id.tvMsgPlay);
                avatar = v.findViewById(R.id.ivMsgAvatar);
                senderName = v.findViewById(R.id.tvGroupSender); // 单聊布局里为 null，群聊他人布局才有
                textBgDefault = text.getBackground();
                textColorDefault = text.getCurrentTextColor();
                textSizeDefault = text.getTextSize();
                textGravityDefault = text.getGravity();
            }
        }
    }

    // ═══════════════ 语音播放（与单聊一致） ═══════════════

    private void preloadVoiceFile(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) return;
        if (voiceCache.containsKey(relativeUrl) && new java.io.File(voiceCache.get(relativeUrl)).exists()) {
            return;
        }
        final String fullUrl = ServerProtocol.withToken(
                ServerProtocol.baseUrl(prefs.getServer()) + relativeUrl,
                prefs.getToken());
        final OkHttpClient client = httpClient;
        final File cacheDir = getCacheDir();

        voicePreloader.execute(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder().url(fullUrl).build();
                okhttp3.Response resp = client.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) return;
                byte[] data = resp.body().bytes();
                final File cacheFile = new File(cacheDir, "voice_cache_" + relativeUrl.hashCode() + ".mp4");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
                fos.write(data);
                fos.close();
                if (voiceCache.size() >= VOICE_CACHE_MAX) {
                    voiceCache.clear();
                }
                voiceCache.put(relativeUrl, cacheFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "preloadVoiceFile error", e);
            }
        });
    }

    private void playVoiceLocal(String localPath, MessageAdapter.VH h, int position, int durationSec) {
        if (voicePlayer != null) {
            try { voicePlayer.reset(); } catch (Exception ignored) {}
        } else {
            voicePlayer = new MediaPlayer();
        }
        playingPosition = position;
        playingWaveform = h.waveform;
        h.playIcon.setText("■");
        h.waveform.startAnimation();

        try {
            voicePlayer.reset();
            voicePlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "voicePlayer error: " + what + "/" + extra);
                resetVoicePlayback();
                return true;
            });
            voicePlayer.setOnCompletionListener(mp -> resetVoicePlayback());
            voicePlayer.setDataSource(localPath);
            voicePlayer.prepare();
            voicePlayer.start();

            final int totalMs = voicePlayer.getDuration();
            final int totalSec = totalMs / 1000;
            if (h.voiceDuration != null) h.voiceDuration.setText(formatPlayTime(0, totalSec));
            final Handler progressHandler = new Handler();
            progressHandler.post(new Runnable() {
                @Override public void run() {
                    if (voicePlayer != null && voicePlayer.isPlaying() && playingPosition == position) {
                        int currentMs = voicePlayer.getCurrentPosition();
                        if (h.voiceDuration != null) h.voiceDuration.setText(formatPlayTime(currentMs / 1000, totalSec));
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
