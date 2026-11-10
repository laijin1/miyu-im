package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.familychat.app.utils.ServerProtocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 发起群聊：从好友里多选成员（可填群名），点"完成"建群并进入群聊。
 * 服务端建群时自动把创建者加进去，所以 member_ids 只传选中的好友。
 */
public class CreateGroupActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private List<Models.User> friends = new ArrayList<>();
    private final Set<Integer> selected = new LinkedHashSet<>();
    private SelectAdapter adapter;
    private EditText etGroupName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_create_group);
        } catch (Exception e) {
            finish();
            return;
        }

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        etGroupName = findViewById(R.id.etGroupName);
        findViewById(R.id.btnDone).setOnClickListener(v -> createGroup());

        RecyclerView rv = findViewById(R.id.rvSelectFriends);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SelectAdapter();
        rv.setAdapter(adapter);

        loadFriends();
    }

    private void loadFriends() {
        new Thread(() -> {
            try {
                Models.FriendsResponse res = api.getFriends();
                friends = res.friends != null ? res.friends : new ArrayList<>();
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载好友失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void createGroup() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请选择至少一位好友", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = etGroupName.getText().toString().trim();
        if (name.isEmpty()) name = "群聊";
        final String finalName = name;
        findViewById(R.id.btnDone).setEnabled(false);

        new Thread(() -> {
            try {
                Models.Group group = api.createGroup(finalName, new ArrayList<>(selected));
                if (group == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "建群失败", Toast.LENGTH_SHORT).show();
                        findViewById(R.id.btnDone).setEnabled(true);
                    });
                    return;
                }
                final int gid = group.id;
                runOnUiThread(() -> {
                    Intent it = new Intent(CreateGroupActivity.this, GroupChatActivity.class);
                    it.putExtra("group_id", gid);
                    startActivity(it);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "建群失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    findViewById(R.id.btnDone).setEnabled(true);
                });
            }
        }).start();
    }

    class SelectAdapter extends RecyclerView.Adapter<SelectAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_group_member_select, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            Models.User f = friends.get(i);
            String name = (f.remark != null && !f.remark.isEmpty()) ? f.remark : f.nickname;
            h.name.setText(name != null ? name : f.username);
            loadAvatar(h.avatar, f.avatar);
            h.check.setImageResource(selected.contains(f.id) ? R.drawable.ic_check_on : R.drawable.ic_check_off);
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(f.id)) selected.remove(f.id);
                else selected.add(f.id);
                notifyItemChanged(i);
            });
        }
        @Override public int getItemCount() { return friends.size(); }
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
                com.bumptech.glide.Glide.with(this)
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
}
