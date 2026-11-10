package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.button.MaterialButton;

/** 用户资料页（密语式）：大头像 + 昵称 + @账号名 + 取消/添加好友。
 *  从搜索结果点击或扫码命中 familychat://user?id= 进入。 */
public class UserProfileActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private int targetId;
    private Models.User lastUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_profile);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        targetId = getIntent().getIntExtra("user_id", 0);

        findViewById(R.id.btnProfileBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        MaterialButton btnAdd = findViewById(R.id.btnAddFriend);
        btnAdd.setOnClickListener(v -> sendRequest());

        // 已是好友 → 显示"发消息"按钮，点击直接进聊天框
        findViewById(R.id.btnChatFriend).setOnClickListener(v -> openChat());

        loadUser();
        checkIsFriend();
    }

    /** 拉取好友列表判断目标是否已是好友：
     *  是 → 显示"发消息"按钮，且"添加好友"变灰不可点（"已经是好友了"）。 */
    private void checkIsFriend() {
        if (targetId <= 0) return;
        new Thread(() -> {
            try {
                Models.FriendsResponse res = api.getFriends();
                if (res != null && res.friends != null) {
                    for (Models.User f : res.friends) {
                        if (f.id == targetId) {
                            runOnUiThread(this::markAsFriend);
                            return;
                        }
                    }
                }
            } catch (Exception ignored) { /* 失败则不处理 */ }
        }).start();
    }

    /** 已是好友：显示"发消息"，"添加好友"置灰（灰色+提示文案，点击无效果）。 */
    private void markAsFriend() {
        findViewById(R.id.btnChatFriend).setVisibility(View.VISIBLE);
        MaterialButton btnAdd = findViewById(R.id.btnAddFriend);
        btnAdd.setEnabled(false);   // 禁用后点击不再触发任何效果
        btnAdd.setText("已经是好友了");
        btnAdd.setTextColor(android.graphics.Color.parseColor("#999999"));
        btnAdd.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E5E5E5")));
    }

    /** 打开与目标用户的聊天框。 */
    private void openChat() {
        Models.User u = lastUser;
        if (u == null) return;
        Intent it = new Intent(this, ChatActivity.class);
        it.putExtra("friend_id", u.id);
        it.putExtra("friend_name", u.nickname);
        it.putExtra("friend_username", u.username);
        it.putExtra("friend_avatar", u.avatar);
        it.putExtra("friend_remark", u.remark);
        it.putExtra("friend_online", u.is_online);
        startActivity(it);
    }

    /** 拉取用户资料并展示。 */
    private void loadUser() {
        if (targetId <= 0) {
            Toast.makeText(this, "无效的用户", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new Thread(() -> {
            try {
                Models.ProfileResponse res = api.getUserById(targetId);
                Models.User u = res.user;
                runOnUiThread(() -> {
                    if (u == null) {
                        Toast.makeText(this, "用户不存在", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    bindUser(u);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void bindUser(Models.User u) {
        lastUser = u;
        ImageView ivAvatar = findViewById(R.id.ivProfileAvatar);
        TextView tvName = findViewById(R.id.tvProfileName);
        TextView tvAccount = findViewById(R.id.tvProfileAccount);

        tvName.setText(u.nickname != null && !u.nickname.isEmpty() ? u.nickname : u.username);
        tvAccount.setText("@" + (u.username != null ? u.username : ""));

        if (u.avatar != null && u.avatar.startsWith("/")) {
            String url = ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + u.avatar,
                    prefs.getToken());
            com.bumptech.glide.Glide.with(ivAvatar.getContext()).load(url).circleCrop().into(ivAvatar);
        } else {
            ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
        }
    }

    /** 发送好友请求。 */
    private void sendRequest() {
        MaterialButton btnAdd = findViewById(R.id.btnAddFriend);
        btnAdd.setEnabled(false);
        new Thread(() -> {
            try {
                Models.FriendRequestResult res = api.sendFriendRequest(targetId);
                runOnUiThread(() -> {
                    if (res != null && res.error != null && !res.error.isEmpty()) {
                        btnAdd.setEnabled(true);
                        Toast.makeText(this, res.error, Toast.LENGTH_SHORT).show();
                    } else {
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnAdd.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}
