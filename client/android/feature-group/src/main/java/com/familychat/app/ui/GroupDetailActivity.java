package com.familychat.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_group.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.api.PushConnection;
import com.familychat.app.api.WebSocketManager;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 群聊详情：群名/群头像/成员列表 + 权限管理（全仿 QQ）。
 * 角色：群主(owner)/管理员(admin)/成员(member)。
 * 群主：改名/换头像/任免管理员/禁言/移除/转让/解散、拉人、修改我的群名片。
 * 管理员：禁言/移除普通成员、修改我的群名片。
 * 成员：修改我的群名片、退出群聊。
 */
public class GroupDetailActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    /**
     * 本页对推送连接的监听器。
     *
     * <p>连接不再由本页持有 —— 唯一持有者是 :core 的 {@code PushConnection}
     * （由 ForegroundService 驱动生命周期）。这里只留引用供 onDestroy 反注册。
     */
    private WebSocketManager.Listener wsListener;
    private int groupId;
    private Models.Group group;
    private List<Models.User> members = new ArrayList<>();
    private MemberAdapter adapter;
    private TextView tvGroupName, tvGroupCount;
    private ImageView ivGroupAvatar;
    private Button btnTransfer, btnLeave;
    /** 本地主动解散标志：避免 WS 回包后重复 toast。 */
    private boolean locallyDissolved = false;

    // 更换群头像（ACTION_PICK 直接打开系统相册/画廊，避免打开文件管理）
    private final ActivityResultLauncher<Intent> avatarGetContentLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) uploadGroupAvatar(uri);
            }
        });
    // 备用：系统 Photo Picker（个别机型无 ACTION_PICK 处理器时兜底）
    private final ActivityResultLauncher<PickVisualMediaRequest> avatarPickerLauncher =
        registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) uploadGroupAvatar(uri);
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_group_detail);
        } catch (Exception e) {
            finish();
            return;
        }

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        tvGroupName = findViewById(R.id.tvGroupName);
        tvGroupCount = findViewById(R.id.tvGroupCount);
        ivGroupAvatar = findViewById(R.id.ivGroupAvatar);
        btnTransfer = findViewById(R.id.btnTransfer);
        btnLeave = findViewById(R.id.btnLeaveGroup);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAdd).setOnClickListener(v -> pickFriendsToAdd());
        findViewById(R.id.btnEditNickname).setOnClickListener(v -> editMyNickname());
        btnTransfer.setOnClickListener(v -> transferGroup());
        btnLeave.setOnClickListener(v -> leaveOrDissolve());

        // 群主点击群名/群头像改名换头像（非群主提示原因，不静默）
        ivGroupAvatar.setOnClickListener(v -> {
            if (isOwner()) { changeGroupAvatar(); }
            else { Toast.makeText(this, "只有群主可以更换群头像", Toast.LENGTH_SHORT).show(); }
        });
        tvGroupName.setOnClickListener(v -> {
            if (isOwner()) { renameGroup(); }
            else { Toast.makeText(this, "只有群主可以修改群名称", Toast.LENGTH_SHORT).show(); }
        });

        RecyclerView rv = findViewById(R.id.rvGroupMembers);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemberAdapter();
        rv.setAdapter(adapter);

        try {
            // 连接宿主已下沉到 :core 的 PushConnection，本页只注册监听。
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
                @Override public void onGroupMembersChanged(int gid) {
                    if (gid == groupId) runOnUiThread(GroupDetailActivity.this::loadGroup);
                }
                @Override public void onGroupDissolved(int gid) {
                    if (gid == groupId) runOnUiThread(() -> {
                        if (!locallyDissolved) Toast.makeText(GroupDetailActivity.this, "群聊已解散", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
                @Override public void onGroupRemoved(int gid) {
                    if (gid == groupId) runOnUiThread(() -> {
                        Toast.makeText(GroupDetailActivity.this, "你已被移出群聊", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
            };
            PushConnection.addListener(wsListener);
        } catch (Exception ignored) {}

        loadGroup();
    }

    private void loadGroup() {
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det == null) return;
                group = det.group;
                members = det.members != null ? det.members : new ArrayList<>();
                runOnUiThread(() -> {
                    if (group != null) {
                        String name = (group.name != null && !group.name.isEmpty()) ? group.name : "群聊";
                        tvGroupName.setText(name);
                        tvGroupCount.setText(members.size() + "人");
                        loadGroupAvatar(ivGroupAvatar, group.avatar);
                        boolean owner = isOwner();
                        btnTransfer.setVisibility(owner ? View.VISIBLE : View.GONE);
                        btnLeave.setText(owner ? "解散群聊" : "退出群聊");
                    }
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载群详情失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean isOwner() {
        // 优先用服务端返回的角色，兼容旧服务端则回退 owner_id 判断
        if (group != null && group.my_role != null) return "owner".equals(group.my_role);
        return group != null && group.owner_id == prefs.getUserId();
    }

    private String myRole() {
        if (group != null && group.my_role != null && !group.my_role.isEmpty()) return group.my_role;
        return isOwner() ? "owner" : "member";
    }

    /** 展示名：群名片 > 全局昵称 > 用户名。 */
    private String displayName(Models.User m) {
        if (m.group_nickname != null && !m.group_nickname.isEmpty()) return m.group_nickname;
        if (m.nickname != null && !m.nickname.isEmpty()) return m.nickname;
        return m.username != null ? m.username : ("用户" + m.id);
    }

    // ═══ 群主拉人 ═══
    private void pickFriendsToAdd() {
        if (!isOwner()) { Toast.makeText(this, "仅群主可邀请成员", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            try {
                Models.FriendsResponse res = api.getFriends();
                List<Models.User> all = res.friends != null ? res.friends : new ArrayList<>();
                Set<Integer> inGroup = new LinkedHashSet<>();
                for (Models.User m : members) inGroup.add(m.id);
                List<Models.User> candidates = new ArrayList<>();
                for (Models.User f : all) {
                    if (!inGroup.contains(f.id)) candidates.add(f);
                }
                final List<Models.User> c = candidates;
                runOnUiThread(() -> showAddDialog(c));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载好友失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showAddDialog(List<Models.User> candidates) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "好友都已在群里了", Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<Integer> sel = new LinkedHashSet<>();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(8, 8, 8, 0);
        TextView title = new TextView(this);
        title.setText("选择联系人");
        title.setTextSize(16);
        title.setPadding(dp(12), dp(8), dp(12), dp(4));
        wrap.addView(title);

        RecyclerView rv = new RecyclerView(this);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(RecyclerView.VERTICAL);
        rv.setLayoutManager(llm);
        rv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        PickAdapter pickAdapter = new PickAdapter(candidates, sel);
        rv.setAdapter(pickAdapter);
        wrap.addView(rv);

        new MaterialAlertDialogBuilder(this)
            .setTitle("添加到群聊")
            .setView(wrap)
            .setPositiveButton("确定", (d, w) -> {
                if (sel.isEmpty()) {
                    Toast.makeText(this, "请选择好友", Toast.LENGTH_SHORT).show();
                    return;
                }
                addMembers(new ArrayList<>(sel));
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void addMembers(List<Integer> userIds) {
        new Thread(() -> {
            try {
                for (int uid : userIds) {
                    api.addGroupMember(groupId, uid);
                }
                runOnUiThread(this::loadGroup);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "添加失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ═══ 群名片 / 群信息 ═══
    /** 修改我的群名片（所有人）。 */
    private void editMyNickname() {
        final EditText et = new EditText(this);
        et.setHint("输入群名片（留空则用全局昵称）");
        et.setSingleLine(true);
        if (group != null && group.my_nickname != null && !group.my_nickname.isEmpty()) {
            et.setText(group.my_nickname);
            et.setSelection(et.getText().length());
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle("修改我的群名片")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                final String nick = et.getText().toString().trim();
                new Thread(() -> {
                    try {
                        api.setMyGroupNickname(groupId, nick);
                        runOnUiThread(this::loadGroup);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 群主修改群名称。 */
    private void renameGroup() {
        final EditText et = new EditText(this);
        et.setHint("输入群名称");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (group != null && group.name != null && !group.name.isEmpty()) {
            et.setText(group.name);
            et.setSelection(et.getText().length());
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle("修改群名称")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                final String name = et.getText().toString().trim();
                if (name.isEmpty()) { Toast.makeText(this, "群名称不能为空", Toast.LENGTH_SHORT).show(); return; }
                new Thread(() -> {
                    try {
                        api.updateGroupInfo(groupId, name, null);
                        runOnUiThread(this::loadGroup);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "修改失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 群主更换群头像（ACTION_PICK 系统相册）。 */
    private void changeGroupAvatar() {
        Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (pick.resolveActivity(getPackageManager()) != null) {
            avatarGetContentLauncher.launch(pick);
        } else {
            avatarPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }
    }

    private void uploadGroupAvatar(Uri uri) {
        Toast.makeText(this, "上传群头像...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                byte[] bytes = baos.toByteArray();
                String url = api.uploadFile(bytes, "group_avatar_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
                if (url != null && !url.isEmpty()) {
                    api.updateGroupInfo(groupId, null, url);
                    runOnUiThread(this::loadGroup);
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "上传失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ═══ 权限管理 ═══
    /** 群主转让。 */
    private void transferGroup() {
        final List<Models.User> candidates = new ArrayList<>();
        for (Models.User m : members) if (m.id != prefs.getUserId()) candidates.add(m);
        if (candidates.isEmpty()) { Toast.makeText(this, "没有可转让的成员", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) names[i] = displayName(candidates.get(i));
        new MaterialAlertDialogBuilder(this)
            .setTitle("转让群主")
            .setItems(names, (d, w) -> {
                final Models.User target = candidates.get(w);
                new MaterialAlertDialogBuilder(this)
                    .setTitle("确认转让")
                    .setMessage("确定将群主转让给 " + displayName(target) + "？转让后你将成为管理员。")
                    .setPositiveButton("转让", (d2, w2) -> new Thread(() -> {
                        try {
                            api.transferGroup(groupId, target.id);
                            runOnUiThread(this::loadGroup);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "转让失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start())
                    .setNegativeButton("取消", null)
                    .show();
            })
            .show();
    }

    /** 底部按钮：群主=解散，成员=退出。 */
    private void leaveOrDissolve() {
        if (isOwner()) dissolveGroup();
        else leaveGroup();
    }

    private void dissolveGroup() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("解散群聊").setMessage("确定解散该群？所有成员都将被移出，且无法恢复。")
            .setPositiveButton("解散", (d, w) -> new Thread(() -> {
                try {
                    api.dissolveGroup(groupId);
                    locallyDissolved = true;
                    runOnUiThread(() -> { Toast.makeText(this, "群聊已解散", Toast.LENGTH_SHORT).show(); finish(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "解散失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    /** 成员退群。 */
    private void leaveGroup() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("退出群聊").setMessage("确定退出该群?")
            .setPositiveButton("退出", (d, w) -> new Thread(() -> {
                try {
                    api.removeGroupMember(groupId, prefs.getUserId());
                    runOnUiThread(this::finish);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "退出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    /** 移除成员（群主/管理员可移除普通成员；群主可移除任何人）。 */
    private void removeMember(final int uid) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("移除成员").setMessage("确定将该成员移出群聊?")
            .setPositiveButton("移除", (d, w) -> new Thread(() -> {
                try {
                    api.removeGroupMember(groupId, uid);
                    runOnUiThread(this::loadGroup);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "移除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    /** 长按成员 → 管理菜单。 */
    private void showManageMenu(Models.User target) {
        final boolean me = target.id == prefs.getUserId();
        final String tRole = target.role != null ? target.role : "";
        final String myRole = myRole();
        List<String> options = new ArrayList<>();
        if (me) {
            options.add("修改我的群名片");
        } else if ("owner".equals(myRole)) {
            options.add("admin".equals(tRole) ? "取消管理员" : "任命为管理员");
            options.add("禁言/解除禁言");
            options.add("移除成员");
        } else if ("admin".equals(myRole)) {
            if ("owner".equals(tRole)) { Toast.makeText(this, "不能管理群主", Toast.LENGTH_SHORT).show(); return; }
            if ("admin".equals(tRole)) { Toast.makeText(this, "仅群主可管理管理员", Toast.LENGTH_SHORT).show(); return; }
            options.add("禁言/解除禁言");
            options.add("移除成员");
        } else {
            Toast.makeText(this, "仅群主/管理员可管理成员", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] arr = options.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
            .setTitle(displayName(target))
            .setItems(arr, (d, w) -> {
                switch (arr[w]) {
                    case "修改我的群名片": editMyNickname(); break;
                    case "任命为管理员": confirmAppoint(target, true); break;
                    case "取消管理员": confirmAppoint(target, false); break;
                    case "禁言/解除禁言": showMuteDialog(target); break;
                    case "移除成员": removeMember(target.id); break;
                }
            })
            .show();
    }

    /** 任/免管理员（仅群主）。 */
    private void confirmAppoint(Models.User target, boolean appoint) {
        String label = appoint ? "任命为管理员" : "取消管理员";
        String msg = appoint ? "确定将 " + displayName(target) + " 设为管理员？" : "确定取消 " + displayName(target) + " 的管理员身份？";
        new MaterialAlertDialogBuilder(this)
            .setTitle(label).setMessage(msg)
            .setPositiveButton("确定", (d, w) -> new Thread(() -> {
                try {
                    api.appointAdmin(groupId, target.id, appoint ? "appoint" : "revoke");
                    runOnUiThread(this::loadGroup);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    /** 禁言/解除禁言（群主/管理员）。 */
    private void showMuteDialog(Models.User target) {
        final long now = System.currentTimeMillis();
        final String[] opts = {"禁言 10 分钟", "禁言 1 小时", "禁言 24 小时", "永久禁言", "解除禁言"};
        final long[] vals = {now + 10 * 60_000L, now + 3_600_000L, now + 86_400_000L, -1, 0};
        new MaterialAlertDialogBuilder(this)
            .setTitle("禁言 - " + displayName(target))
            .setItems(opts, (d, w) -> new Thread(() -> {
                try {
                    api.setMemberMute(groupId, target.id, vals[w]);
                    runOnUiThread(this::loadGroup);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .show();
    }

    private boolean isMutedUser(Models.User m) {
        long until = m.mute_until;
        return until == -1 || until > System.currentTimeMillis();
    }

    private String muteText(Models.User m) {
        long until = m.mute_until;
        if (until == -1) return "永久禁言";
        if (until > System.currentTimeMillis()) return "禁言中";
        return "";
    }

    /** 我能否管理该成员（用于快速移除按钮显示）。 */
    private boolean canManage(Models.User m) {
        if (m.id == prefs.getUserId()) return false;
        String tRole = m.role != null ? m.role : "";
        if ("owner".equals(tRole)) return false;
        String myRole = myRole();
        if ("owner".equals(myRole)) return true;
        return "admin".equals(myRole) && !"admin".equals(tRole);
    }

    // ═══ 成员列表 ═══
    class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_group_member_detail, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            Models.User m = members.get(i);
            boolean me = m.id == prefs.getUserId();
            h.name.setText(displayName(m));
            String mute = muteText(m);
            String sub = me ? "我" : "";
            if (!mute.isEmpty()) sub = sub.isEmpty() ? mute : sub + " · " + mute;
            h.sub.setText(sub);
            // 副标题为空时不占一行高度（布局里 tvMemberSub 默认 gone）；
            // 有值（"我" / 禁言状态）时必须显式切回 VISIBLE，否则该信息会被布局默认值吃掉。
            h.sub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            loadAvatar(h.avatar, m.avatar);

            String role = m.role != null ? m.role : "";
            if ("owner".equals(role)) {
                h.ownerTag.setVisibility(View.VISIBLE);
                h.ownerTag.setText("群主");
                h.ownerTag.setTextColor(getColor(R.color.green));
            } else if ("admin".equals(role)) {
                h.ownerTag.setVisibility(View.VISIBLE);
                h.ownerTag.setText("管理员");
                h.ownerTag.setTextColor(0xFFFB8C00);
            } else {
                h.ownerTag.setVisibility(View.GONE);
            }

            boolean canRemove = canManage(m) && !me;
            h.remove.setVisibility(canRemove ? View.VISIBLE : View.GONE);
            final int uid = m.id;
            h.remove.setOnClickListener(v -> removeMember(uid));
            // 长按 → 管理菜单
            h.itemView.setOnLongClickListener(v -> { showManageMenu(m); return true; });
        }
        @Override public int getItemCount() { return members.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView name, sub, ownerTag, remove; ImageView avatar;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tvMemberName);
                sub = v.findViewById(R.id.tvMemberSub);
                ownerTag = v.findViewById(R.id.tvOwnerTag);
                remove = v.findViewById(R.id.btnRemove);
                avatar = v.findViewById(R.id.ivMemberAvatar);
            }
        }
    }

    // ═══ 拉人选择好友 ═══
    class PickAdapter extends RecyclerView.Adapter<PickAdapter.VH> {
        private final List<Models.User> list;
        private final Set<Integer> sel;
        PickAdapter(List<Models.User> list, Set<Integer> sel) { this.list = list; this.sel = sel; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_group_member_select, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            Models.User f = list.get(i);
            String name = (f.remark != null && !f.remark.isEmpty()) ? f.remark : f.nickname;
            h.name.setText(name != null ? name : f.username);
            loadAvatar(h.avatar, f.avatar);
            h.check.setImageResource(sel.contains(f.id) ? R.drawable.ic_check_on : R.drawable.ic_check_off);
            h.itemView.setOnClickListener(v -> {
                if (sel.contains(f.id)) sel.remove(f.id);
                else sel.add(f.id);
                notifyItemChanged(i);
            });
        }
        @Override public int getItemCount() { return list.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView name; ImageView avatar, check;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tvSelectName);
                avatar = v.findViewById(R.id.ivSelectAvatar);
                check = v.findViewById(R.id.ivSelectCheck);
            }
        }
    }

    private void loadAvatar(ImageView iv, String avatar) {
        try {
            if (avatar != null && avatar.startsWith("/")) {
                Glide.with(this)
                        .load(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                                prefs.getToken()))
                        .circleCrop().into(iv);
            } else {
                iv.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        } catch (Exception e) {
            iv.setImageResource(R.drawable.ic_avatar_placeholder);
        }
    }

    private void loadGroupAvatar(ImageView iv, String avatar) {
        try {
            if (avatar != null && avatar.startsWith("/")) {
                Glide.with(this)
                        .load(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + avatar,
                                prefs.getToken()))
                        .centerCrop().into(iv);
            } else {
                iv.setImageResource(R.drawable.ic_group);
            }
        } catch (Exception e) {
            iv.setImageResource(R.drawable.ic_group);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ⚠️ 只反注册监听器：连接归 PushConnection（由 ForegroundService 驱动），
        // 调用 stop() 会切断整个后台推送。
        PushConnection.removeListener(wsListener);
        wsListener = null;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }
}
