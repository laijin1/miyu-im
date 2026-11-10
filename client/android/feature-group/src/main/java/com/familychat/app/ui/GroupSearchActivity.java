package com.familychat.app.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
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
import com.familychat.app.utils.PreferencesManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群内搜索（服务端搜文件名/系统消息 + 本地拉历史解密过滤文本，合并倒序展示）。
 * E2EE 密文正文无法在服务端搜索，聊天正文只能在本端解密后过滤。
 */
public class GroupSearchActivity extends AppCompatActivity {
    private static final String TAG = "GroupSearch";

    private PreferencesManager prefs;
    private ApiClient api;
    private int groupId;
    private final List<Result> items = new ArrayList<>();
    private Adapter adapter;
    private final Map<Integer, String> nameMap = new HashMap<>();
    private TextView tvEmpty;

    /** 搜索结果条目：显示名 + 时间 + 展示文本（已解密/类型标签）。 */
    static class Result {
        int id;
        String name;
        String time;
        String display;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_search);
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        String gname = getIntent().getStringExtra("group_name");
        if (gname != null && !gname.isEmpty()) {
            ((TextView) findViewById(R.id.tvTitle)).setText(gname + " · 搜索");
        }

        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvResults);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        final EditText et = findViewById(R.id.etKeyword);
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch(et.getText().toString().trim()));
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch(et.getText().toString().trim());
                return true;
            }
            return false;
        });

        // 预加载成员昵称映射（显示发送者名）
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null && det.members != null) {
                    nameMap.clear();
                    for (Models.User m : det.members) {
                        if (m.nickname != null && !m.nickname.isEmpty()) nameMap.put(m.id, m.nickname);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "load members failed: " + e.getMessage());
            }
        }).start();
    }

    private void doSearch(final String keyword) {
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "搜索中…", Toast.LENGTH_SHORT).show();
        final List<Result> merged = new ArrayList<>();
        new Thread(() -> {
            try {
                // 1) 服务端搜索：文件名 + 系统消息（明文可搜）
                Models.GroupMessagesResponse serverRes = api.searchGroupMessages(groupId, keyword, 50);
                if (serverRes != null && serverRes.messages != null) {
                    for (Models.GroupMessage gm : serverRes.messages) {
                        merged.add(toResult(gm, keyword));
                    }
                }
                // 2) 本地解密过滤：文本正文 E2EE，拉历史解密后 contains 匹配
                Models.GroupMessagesResponse hist = api.getGroupMessages(groupId, 100);
                if (hist != null && hist.messages != null) {
                    for (Models.GroupMessage gm : hist.messages) {
                        String type = gm.msg_type != null ? gm.msg_type : "text";
                        if (!"text".equals(type)) continue;
                        if (gm.content == null) continue;
                        String plain = decrypt(gm);
                        if (plain != null && plain.contains(keyword)) {
                            Result r = new Result();
                            r.id = gm.id;
                            r.name = nameOf(gm.sender_id);
                            r.time = fmtTime(gm.created_at);
                            r.display = plain;
                            merged.add(r);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "search failed", e);
                runOnUiThread(() -> Toast.makeText(this, "搜索失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            // 去重 + 按 id 倒序
            Map<Integer, Result> dedup = new HashMap<>();
            for (Result r : merged) dedup.put(r.id, r);
            List<Result> list = new ArrayList<>(dedup.values());
            list.sort((a, b) -> Integer.compare(b.id, a.id));
            runOnUiThread(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private String decrypt(Models.GroupMessage gm) {
        try {
            return GroupEncryptHelper.decryptGroupMessage(
                    gm.content, gm.sender_id, prefs.getUserId(), api, prefs, gm.msg_type);
        } catch (Exception e) {
            return GroupEncryptHelper.PLACEHOLDER_FAILED;
        }
    }

    private String nameOf(int uid) {
        if (uid == prefs.getUserId()) {
            String me = prefs.getMyNickname();
            return me != null && !me.isEmpty() ? me : "我";
        }
        String n = nameMap.get(uid);
        return n != null ? n : "成员" + uid;
    }

    /** 时间格式化（created_at 是 UTC，先解析再按本地时区显示）。 */
    private String fmtTime(String iso) {
        return ChatTimeFormatter.format(ChatTimeFormatter.parseUtc(iso));
    }

    private Result toResult(Models.GroupMessage gm, String keyword) {
        Result r = new Result();
        r.id = gm.id;
        r.name = nameOf(gm.sender_id);
        r.time = fmtTime(gm.created_at);
        String type = gm.msg_type != null ? gm.msg_type : "text";
        if ("system".equals(type)) {
            r.display = gm.content != null ? gm.content : "系统消息";
        } else if ("text".equals(type)) {
            r.display = decrypt(gm);
        } else {
            // 文件名命中：图片/视频/语音/文件
            String label = "text".equals(type) ? "文本" : "image".equals(type) ? "图片"
                    : "video".equals(type) ? "视频" : "voice".equals(type) ? "语音" : "文件";
            r.display = "[" + label + "] " + (gm.filename != null && !gm.filename.isEmpty() ? gm.filename : "（无文件名）");
        }
        return r;
    }

    // ═══════════════ Adapter ═══════════════

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_search_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Result r = items.get(pos);
            h.tvSender.setText(r.name);
            h.tvTime.setText(r.time);
            h.tvContent.setText(r.display);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvSender, tvTime, tvContent;
            VH(View v) {
                super(v);
                tvSender = v.findViewById(R.id.tvSender);
                tvTime = v.findViewById(R.id.tvTime);
                tvContent = v.findViewById(R.id.tvContent);
            }
        }
    }
}
