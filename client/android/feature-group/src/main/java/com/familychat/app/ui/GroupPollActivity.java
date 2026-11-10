package com.familychat.app.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.feature_group.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 群投票（全仿 QQ，阶段5）：投票卡片（选项+进度条+票数+百分比+我的选中态）+ 点击投票/改票；
 * 多选先选后提交；FAB 发起（标题+动态选项+单选/多选+匿名+截止）；长按卡片管理（结束/删除，发起者或群主/管理员）。
 * 投票标题/描述/选项为明文字节（不走 E2EE），仅群成员可访问。
 */
public class GroupPollActivity extends AppCompatActivity {
    private static final String TAG = "GroupPoll";

    private PreferencesManager prefs;
    private ApiClient api;
    private int groupId;
    private boolean isAdmin;
    private final List<Models.GroupPoll> polls = new ArrayList<>();
    private Adapter adapter;
    private TextView tvEmpty;
    private final Map<Integer, Set<Integer>> pendingMulti = new LinkedHashMap<>(); // 多选临时勾选 pollId -> optionIds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_poll);
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        // H5 口径提示：群投票为明文存储，仅群成员可访问
        Toast.makeText(this,
                "提示：群投票为明文存储，仅群成员可访问，不享受端到端加密保护。",
                Toast.LENGTH_LONG).show();

        ((ImageButton) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());
        String gname = getIntent().getStringExtra("group_name");
        if (gname != null && !gname.isEmpty()) {
            ((TextView) findViewById(R.id.tvTitle)).setText(gname + " · 群投票");
        }
        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvPolls);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        FloatingActionButton fab = findViewById(R.id.fabNewPoll);
        fab.setOnClickListener(v -> showCreateDialog());

        loadAdminFlag();
        loadPolls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPolls();
    }

    private void loadAdminFlag() {
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null && det.group != null) {
                    isAdmin = "owner".equals(det.group.my_role) || "admin".equals(det.group.my_role);
                }
            } catch (Exception e) { Log.d(TAG, "role load failed", e); }
        }).start();
    }

    private void loadPolls() {
        new Thread(() -> {
            try {
                Models.PollsResponse r = api.getGroupPolls(groupId);
                final List<Models.GroupPoll> list = r != null && r.polls != null ? r.polls : new ArrayList<>();
                runOnUiThread(() -> {
                    polls.clear();
                    polls.addAll(list);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(polls.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                Log.e(TAG, "load polls failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载投票失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean isClosed(Models.GroupPoll p) {
        return "closed".equals(p.status) || p.expired;
    }

    private String statusText(Models.GroupPoll p) {
        if ("closed".equals(p.status)) return "已结束";
        if (p.expired) return "已过期";
        return "进行中";
    }

    private String endText(Models.GroupPoll p) {
        if (p.end_at == null || p.end_at.isEmpty()) return "不限时";
        String t = p.end_at.replace('T', ' ');
        return t.length() > 16 ? "截止 " + t.substring(0, 16) : "截止 " + t;
    }

    // ═══════════════ 发起投票弹窗 ═══════════════

    private void showCreateDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(4));
        int pad = dp(4);

        final EditText etTitle = new EditText(this);
        etTitle.setHint("投票标题（必填）");
        etTitle.setSingleLine(true);
        root.addView(etTitle, lp(matchP(), wrap(), 0, pad));

        final EditText etDesc = new EditText(this);
        etDesc.setHint("补充说明（可选）");
        etDesc.setSingleLine(true);
        root.addView(etDesc, lp(matchP(), wrap(), 0, pad));

        final LinearLayout llOptions = new LinearLayout(this);
        llOptions.setOrientation(LinearLayout.VERTICAL);
        root.addView(llOptions, lp(matchP(), wrap(), 0, pad));

        final List<EditText> optionInputs = new ArrayList<>();
        final Runnable addOption = new Runnable() {
            @Override public void run() {
                if (optionInputs.size() >= 10) return;
                EditText et = new EditText(GroupPollActivity.this);
                et.setHint("选项 " + (optionInputs.size() + 1));
                et.setSingleLine(true);
                llOptions.addView(et, lp(matchP(), wrap(), 0, dp(2)));
                optionInputs.add(et);
            }
        };
        addOption.run();
        addOption.run();

        TextView tvAdd = new TextView(this);
        tvAdd.setText("＋ 添加选项");
        tvAdd.setTextSize(14);
        tvAdd.setTextColor(getColor(R.color.green));
        tvAdd.setPadding(0, dp(6), 0, dp(2));
        tvAdd.setOnClickListener(v -> addOption.run());
        root.addView(tvAdd, lp(matchP(), wrap(), 0, 0));

        final CheckBox cbMulti = new CheckBox(this);
        cbMulti.setText("多选（默认单选）");
        final CheckBox cbAnon = new CheckBox(this);
        cbAnon.setText("匿名投票（隐藏实名）");
        root.addView(cbMulti, lp(matchP(), wrap(), 0, pad));
        root.addView(cbAnon, lp(matchP(), wrap(), 0, 0));

        TextView tvEndLabel = new TextView(this);
        tvEndLabel.setText("结束时间：");
        tvEndLabel.setTextSize(13);
        tvEndLabel.setTextColor(getColor(R.color.text_secondary));
        root.addView(tvEndLabel, lp(matchP(), wrap(), 0, pad));

        final LinearLayout llEnd = new LinearLayout(this);
        llEnd.setOrientation(LinearLayout.HORIZONTAL);
        final int[] offsets = {0, 3600, 86400, 3 * 86400, 7 * 86400};
        final String[] endNames = {"不限", "1小时", "1天", "3天", "7天"};
        final int[] selectedOffset = {0};
        final List<TextView> endChips = new ArrayList<>();
        for (int i = 0; i < endNames.length; i++) {
            final int off = offsets[i];
            final TextView chip = new TextView(this);
            chip.setText(endNames[i]);
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(wrap(), wrap());
            clp.setMarginEnd(dp(8));
            chip.setLayoutParams(clp);
            chip.setBackgroundColor(getColor(R.color.surface));
            chip.setTextColor(getColor(R.color.text));
            if (off == 0) { chip.setBackgroundColor(getColor(R.color.green)); chip.setTextColor(getColor(R.color.white)); }
            chip.setOnClickListener(v -> {
                selectedOffset[0] = off;
                for (int j = 0; j < endChips.size(); j++) {
                    boolean sel = offsets[j] == selectedOffset[0];
                    endChips.get(j).setBackgroundColor(sel ? getColor(R.color.green) : getColor(R.color.surface));
                    endChips.get(j).setTextColor(sel ? getColor(R.color.white) : getColor(R.color.text));
                }
            });
            llEnd.addView(chip);
            endChips.add(chip);
        }
        root.addView(llEnd, lp(matchP(), wrap(), 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        new MaterialAlertDialogBuilder(this)
            .setTitle("发起投票")
            .setView(sv)
            .setPositiveButton("发布", (d, w) -> {
                String title = etTitle.getText().toString().trim();
                if (title.isEmpty()) { Toast.makeText(this, "请填写投票标题", Toast.LENGTH_SHORT).show(); return; }
                List<String> options = new ArrayList<>();
                for (EditText et : optionInputs) {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) options.add(t);
                }
                if (options.size() < 2) { Toast.makeText(this, "至少需要 2 个选项", Toast.LENGTH_SHORT).show(); return; }
                long endAt = selectedOffset[0] > 0 ? System.currentTimeMillis() + selectedOffset[0] * 1000L : 0;
                final List<String> opts = options;
                new Thread(() -> {
                    try {
                        Models.PollResponse res = api.createGroupPoll(groupId, title,
                                etDesc.getText().toString().trim(), opts, cbMulti.isChecked(), cbAnon.isChecked(), endAt);
                        runOnUiThread(() -> {
                            if (res != null && res.success) {
                                loadPolls();
                            } else {
                                Toast.makeText(this, res != null && res.error != null ? res.error : "发布失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "发布失败", Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ═══════════════ 投票卡片 ═══════════════

    private void buildOptions(final Models.GroupPoll poll, LinearLayout llOptions, int position) {
        llOptions.removeAllViews();
        if (poll.options == null) return;
        boolean closed = isClosed(poll);
        Set<Integer> pending = pendingMulti.get(poll.id);
        boolean isMulti = poll.is_multi;
        for (final Models.PollOption opt : poll.options) {
            final boolean selected = opt.my_voted || (pending != null && pending.contains(opt.id));
            int pct = poll.total_voters > 0 ? opt.vote_count * 100 / poll.total_voters : 0;

            FrameLayout row = new FrameLayout(this);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(pct);
            bar.setPadding(0, 0, 0, 0);
            bar.setBackgroundColor(0x11000000);
            bar.getProgressDrawable().setTint(getColor(selected ? R.color.green : R.color.surface));
            row.addView(bar, new FrameLayout.LayoutParams(matchP(), dp(40)));

            LinearLayout overlay = new LinearLayout(this);
            overlay.setOrientation(LinearLayout.HORIZONTAL);
            overlay.setGravity(android.view.Gravity.CENTER_VERTICAL);
            overlay.setPadding(dp(12), 0, dp(12), 0);
            TextView tvText = new TextView(this);
            tvText.setTextSize(14);
            tvText.setMaxLines(1);
            tvText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvText.setText((selected ? "✓ " : "") + opt.option_text);
            tvText.setTextColor(getColor(selected ? R.color.green : R.color.text));
            tvText.setTypeface(tvText.getTypeface(), selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            overlay.addView(tvText, new LinearLayout.LayoutParams(0, wrap(), 1f));
            TextView tvCount = new TextView(this);
            tvCount.setTextSize(12);
            tvCount.setTextColor(getColor(R.color.text_secondary));
            tvCount.setText(opt.vote_count + "票" + (pct > 0 ? " " + pct + "%" : ""));
            overlay.addView(tvCount, new LinearLayout.LayoutParams(wrap(), wrap()));
            row.addView(overlay, new FrameLayout.LayoutParams(matchP(), dp(40)));

            if (closed) {
                row.setEnabled(false);
            } else {
                row.setOnClickListener(v -> onOptionTap(poll, opt, position));
            }
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(matchP(), dp(40));
            rlp.bottomMargin = dp(6);
            llOptions.addView(row, rlp);
        }
    }

    private void onOptionTap(Models.GroupPoll poll, Models.PollOption opt, int position) {
        if (isClosed(poll)) return;
        if (poll.is_multi) {
            Set<Integer> set = pendingMulti.get(poll.id);
            if (set == null) { set = new HashSet<>(); pendingMulti.put(poll.id, set); }
            if (set.contains(opt.id)) set.remove(opt.id); else set.add(opt.id);
            if (set.isEmpty()) pendingMulti.remove(poll.id);
            adapter.notifyItemChanged(position);
        } else {
            List<Integer> ids = new ArrayList<>();
            ids.add(opt.id);
            submitVote(poll, ids);
        }
    }

    private void submitVote(final Models.GroupPoll poll, List<Integer> ids) {
        final List<Integer> picks = new ArrayList<>(ids);
        new Thread(() -> {
            try {
                Models.PollResponse res = api.votePoll(groupId, poll.id, picks);
                runOnUiThread(() -> {
                    pendingMulti.remove(poll.id);
                    if (res != null && res.success) {
                        loadPolls();
                    } else {
                        Toast.makeText(this, res != null && res.error != null ? res.error : "投票失败", Toast.LENGTH_SHORT).show();
                        loadPolls();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "投票失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ═══════════════ 管理（结束/删除） ═══════════════

    private void showManageMenu(Models.GroupPoll poll, View anchor) {
        boolean canManage = poll.creator_id == prefs.getUserId() || isAdmin;
        if (!canManage) return;
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        if (!isClosed(poll)) pm.getMenu().add(0, 1, 0, "结束投票");
        pm.getMenu().add(0, 2, 0, "删除投票");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                new Thread(() -> {
                    try {
                        api.closePoll(groupId, poll.id);
                        runOnUiThread(this::loadPolls);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show());
                    }
                }).start();
                return true;
            }
            if (item.getItemId() == 2) {
                new MaterialAlertDialogBuilder(this)
                    .setTitle("删除投票")
                    .setMessage("确定删除「" + poll.title + "」吗？")
                    .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                        try {
                            api.deletePoll(groupId, poll.id);
                            runOnUiThread(this::loadPolls);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show());
                        }
                    }).start())
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
            return false;
        });
        pm.show();
    }

    // ═══════════════ 适配器 ═══════════════

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_poll, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Models.GroupPoll poll = polls.get(pos);
            h.tvTitle.setText(poll.title);
            h.tvStatus.setText(statusText(poll));
            h.tvStatus.setTextColor(getColor(isClosed(poll) ? R.color.text_secondary : R.color.green));
            String meta = (poll.creator_name != null ? poll.creator_name : "成员")
                    + " · " + poll.total_voters + "人已投"
                    + " · " + (poll.anonymous ? "匿名" : "公开")
                    + " · " + endText(poll);
            h.tvMeta.setText(meta);
            buildOptions(poll, h.llOptions, pos);

            // 多选：已勾选时提示提交；否则显示说明
            Set<Integer> pending = pendingMulti.get(poll.id);
            boolean closed = isClosed(poll);
            if (closed) {
                h.tvHint.setText("已结束 · 共" + poll.total_voters + "人参与");
            } else if (poll.is_multi && pending != null && !pending.isEmpty()) {
                h.tvHint.setText("已选 " + pending.size() + " 项，点击此处提交投票");
                h.tvHint.setTextColor(getColor(R.color.green));
                h.tvHint.setOnClickListener(v -> submitVote(poll, new ArrayList<>(pending)));
            } else {
                h.tvHint.setText(poll.is_multi ? "多选 · 可更改" : "单选 · 可更改");
                h.tvHint.setTextColor(getColor(R.color.text_secondary));
                h.tvHint.setOnClickListener(null);
            }
            h.itemView.setOnLongClickListener(v -> {
                showManageMenu(poll, h.itemView);
                return true;
            });
        }

        @Override public int getItemCount() { return polls.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvStatus, tvMeta, tvHint;
            LinearLayout llOptions;
            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvTitle);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvMeta = v.findViewById(R.id.tvMeta);
                tvHint = v.findViewById(R.id.tvHint);
                llOptions = v.findViewById(R.id.llOptions);
            }
        }
    }

    // ═══════════════ helpers ═══════════════

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }
    private int matchP() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private LinearLayout.LayoutParams lp(int w, int h, float weight, int mt) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.topMargin = mt;
        return lp;
    }
}
