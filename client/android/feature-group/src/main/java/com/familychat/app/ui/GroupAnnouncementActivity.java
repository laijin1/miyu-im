package com.familychat.app.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.feature_group.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.GroupEncryptHelper;
import com.familychat.app.utils.GroupKeyChangeGate;
import com.familychat.app.utils.PreferencesManager;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 群公告（全仿 QQ）：列表 + 已读 X/Y + 已读成员名单。
 * 群主/管理员可发布/编辑/置顶/删除（FAB + 长按管理）。
 * 公告内容沿用群消息 E2EE（{成员id:密文} JSON），服务端只存不解。
 */
public class GroupAnnouncementActivity extends AppCompatActivity {
    private static final String TAG = "GroupAnnouncement";

    private PreferencesManager prefs;
    private ApiClient api;
    private int groupId;
    private Models.Group group;
    private List<Models.User> members = new ArrayList<>();
    private List<Integer> memberIdList = new ArrayList<>();
    private final List<Models.GroupAnnouncement> items = new ArrayList<>();
    private Adapter adapter;
    private int memberCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_announcement);
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        String gname = getIntent().getStringExtra("group_name");
        if (gname != null && !gname.isEmpty()) {
            ((TextView) findViewById(R.id.tvTitle)).setText(gname + " · 群公告");
        }

        RecyclerView rv = findViewById(R.id.rvAnnouncements);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        com.google.android.material.floatingactionbutton.FloatingActionButton fab =
                findViewById(R.id.fabAnnouncement);
        fab.setOnClickListener(v -> showEditDialog(null));

        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null) {
                    group = det.group;
                    members = det.members != null ? det.members : new ArrayList<>();
                    memberIdList = new ArrayList<>();
                    for (Models.User m : members) memberIdList.add(m.id);
                    if (group != null && ("owner".equals(group.my_role) || "admin".equals(group.my_role))) {
                        runOnUiThread(() -> findViewById(R.id.fabAnnouncement).setVisibility(View.VISIBLE));
                    }
                }
                Models.AnnouncementListResponse res = api.getGroupAnnouncements(groupId);
                if (res != null) {
                    memberCount = res.member_count;
                    final List<Models.GroupAnnouncement> list = res.announcements != null
                            ? res.announcements : new ArrayList<>();
                    runOnUiThread(() -> {
                        items.clear();
                        items.addAll(list);
                        adapter.notifyDataSetChanged();
                    });
                    // 进群公告页自动标记全部已读（幂等）
                    for (Models.GroupAnnouncement a : list) {
                        try { api.markGroupAnnouncementRead(groupId, a.id); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "load failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载公告失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void reload() {
        new Thread(() -> {
            try {
                Models.AnnouncementListResponse res = api.getGroupAnnouncements(groupId);
                if (res != null) {
                    memberCount = res.member_count;
                    final List<Models.GroupAnnouncement> list = res.announcements != null
                            ? res.announcements : new ArrayList<>();
                    runOnUiThread(() -> {
                        items.clear();
                        items.addAll(list);
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "reload failed", e);
            }
        }).start();
    }

    private String decryptAnn(Models.GroupAnnouncement a) {
        if (a.content == null || !a.content.trim().startsWith("{")) return a.content;
        try {
            return GroupEncryptHelper.decryptGroupMessage(a.content, a.publisher_id,
                    prefs.getUserId(), api, prefs);
        } catch (Exception e) {
            return "[公告解密失败]";
        }
    }

    private GroupEncryptHelper.EncryptResult encryptAnn(String plain) {
        try {
            return GroupEncryptHelper.encryptForGroup(plain, prefs.getUserId(), memberIdList, api, prefs);
        } catch (Exception e) {
            Log.e(TAG, "encryptAnn failed", e);
            GroupEncryptHelper.EncryptResult r = new GroupEncryptHelper.EncryptResult();
            r.json = null;
            r.ownKeyMissing = true;
            return r;
        }
    }

    /** 群成员显示名（找不到就退化成"用户&lt;id&gt;"），传给 {@link GroupKeyChangeGate} 用于告警列表。 */
    private String memberDisplayName(int uid) {
        for (Models.User m : members) {
            if (m != null && m.id == uid) {
                return (m.nickname != null && !m.nickname.isEmpty()) ? m.nickname : ("用户" + uid);
            }
        }
        return "用户" + uid;
    }

    /** 发布/编辑公告对话框。a==null 表示新建。 */
    private void showEditDialog(final Models.GroupAnnouncement a) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText et = new EditText(this);
        et.setHint("公告内容（发送给全体成员，端到端加密）");
        et.setTextSize(15);
        et.setMinLines(3);
        et.setMaxLines(8);
        et.setGravity(Gravity.TOP);
        if (a != null) et.setText(decryptAnn(a));
        root.addView(et);

        final MaterialCheckBox cbPin = new MaterialCheckBox(this);
        cbPin.setText("置顶公告");
        if (a != null && a.pinned == 1) cbPin.setChecked(true);
        root.addView(cbPin);

        new MaterialAlertDialogBuilder(this)
            .setTitle(a == null ? "发布群公告" : "编辑群公告")
            .setView(root)
            .setPositiveButton(a == null ? "发布" : "保存", (d, w) -> {
                final String text = et.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(this, "公告内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                final boolean pin = cbPin.isChecked();
                new Thread(() -> {
                    try {
                        if (memberIdList.isEmpty()) {
                            Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                            if (det != null && det.members != null) {
                                members = det.members;
                                memberIdList.clear();
                                for (Models.User m : members) memberIdList.add(m.id);
                            }
                        }
                        GroupEncryptHelper.EncryptResult er = GroupKeyChangeGate.encryptWithGate(
                                this, prefs, this::encryptAnn, this::memberDisplayName, text);
                        if (er == null || er.json == null) {
                            // P1-②: 本地私钥缺失 / 用户对"成员密钥已变更"选了取消 → 取消发布，绝不回退明文
                            Log.e(TAG, "announcement blocked: cannot encrypt (own key missing or user cancelled)");
                            runOnUiThread(() -> Toast.makeText(GroupAnnouncementActivity.this,
                                    "无法加密发布公告：本地缺少加密密钥，请重新登录后重试", Toast.LENGTH_LONG).show());
                            return;
                        }
                        String sendContent = er.json;
                        if (a == null) {
                            api.createGroupAnnouncement(groupId, sendContent, pin);
                        } else {
                            api.updateGroupAnnouncement(groupId, a.id, sendContent, pin ? 1 : 0);
                        }
                        if (!er.skippedMembers.isEmpty()) {
                            // P2-⑥: 告知发布者哪些成员收不到（可能是没设公钥，也可能是密钥已变更）
                            final int n = er.skippedMembers.size();
                            runOnUiThread(() -> Toast.makeText(GroupAnnouncementActivity.this,
                                    "已发布，但有 " + n + " 名成员无法查看此公告（未设置密钥或密钥已变更）", Toast.LENGTH_LONG).show());
                        }
                        runOnUiThread(this::reload);
                    } catch (Exception e) {
                        Log.e(TAG, "save announcement failed", e);
                        runOnUiThread(() -> Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 长按公告管理菜单（群主/管理员）。 */
    private void showManageMenu(final Models.GroupAnnouncement a, final int pos) {
        List<String> actions = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();
        actions.add("编辑");
        callbacks.add(() -> showEditDialog(a));
        if (a.pinned == 1) {
            actions.add("取消置顶");
            callbacks.add(() -> setPinned(a, pos, 0));
        } else {
            actions.add("置顶");
            callbacks.add(() -> setPinned(a, pos, 1));
        }
        actions.add("删除");
        callbacks.add(() -> confirmDelete(a, pos));
        String[] arr = actions.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
            .setItems(arr, (d, which) -> callbacks.get(which).run())
            .show();
    }

    private void setPinned(final Models.GroupAnnouncement a, final int pos, final int pin) {
        new Thread(() -> {
            try {
                api.updateGroupAnnouncement(groupId, a.id, null, pin);
                runOnUiThread(() -> {
                    a.pinned = pin;
                    adapter.notifyItemChanged(pos);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void confirmDelete(final Models.GroupAnnouncement a, final int pos) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("删除公告")
            .setMessage("确定删除这条公告吗？")
            .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                try {
                    api.deleteGroupAnnouncement(groupId, a.id);
                    runOnUiThread(() -> {
                        items.remove(pos);
                        adapter.notifyItemRemoved(pos);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    /** 查看公告详情：全文 + 已读 X/Y + 已读成员名单（并标记已读）。 */
    private void showDetail(final Models.GroupAnnouncement a) {
        new Thread(() -> {
            try {
                final Models.AnnouncementResponse det = api.getGroupAnnouncement(groupId, a.id);
                api.markGroupAnnouncementRead(groupId, a.id);
                runOnUiThread(() -> {
                    if (det == null || det.announcement == null) { Toast.makeText(this, "公告不存在", Toast.LENGTH_SHORT).show(); return; }
                    Models.GroupAnnouncement full = det.announcement;
                    StringBuilder sb = new StringBuilder();
                    sb.append(decryptAnn(full)).append("\n\n");
                    sb.append("发布人：").append(full.publisher_name != null ? full.publisher_name : "").append("\n");
                    sb.append("时间：").append(ChatTimeFormatter.format(
                            ChatTimeFormatter.parseUtc(full.created_at))).append("\n");
                    int total = full.member_count > 0 ? full.member_count : memberCount;
                    sb.append("已读：").append(full.read_count).append("/").append(total).append("\n\n");
                    List<Models.ReadUser> users = full.read_users != null ? full.read_users : new ArrayList<>();
                    if (users.isEmpty()) {
                        sb.append("还没有成员已读");
                    } else {
                        sb.append("已读成员：");
                        for (int i = 0; i < users.size(); i++) {
                            if (i > 0) sb.append("、");
                            sb.append(users.get(i).name);
                        }
                    }
                    new MaterialAlertDialogBuilder(this)
                        .setTitle("群公告")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show();
                    reload();
                });
            } catch (Exception e) {
                Log.e(TAG, "detail failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ═══════════════ Adapter ═══════════════

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_announcement, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Models.GroupAnnouncement a = items.get(pos);
            h.tvContent.setText(decryptAnn(a));
            h.tvPinned.setVisibility(a.pinned == 1 ? View.VISIBLE : View.GONE);
            String publisher = a.publisher_name != null && !a.publisher_name.isEmpty() ? a.publisher_name : "群主";
            String time = ChatTimeFormatter.format(ChatTimeFormatter.parseUtc(a.created_at));
            int total = a.member_count > 0 ? a.member_count : memberCount;
            h.tvMeta.setText(publisher + "  " + time + "    已读 " + a.read_count + "/" + total);
            h.itemView.setOnClickListener(v -> showDetail(a));
            h.itemView.setOnLongClickListener(v -> {
                if (group != null && ("owner".equals(group.my_role) || "admin".equals(group.my_role))) {
                    showManageMenu(a, pos);
                    return true;
                }
                showDetail(a);
                return true;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvPinned, tvContent, tvMeta;
            VH(View v) {
                super(v);
                tvPinned = v.findViewById(R.id.tvPinned);
                tvContent = v.findViewById(R.id.tvContent);
                tvMeta = v.findViewById(R.id.tvMeta);
            }
        }
    }
}
