package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.familychat.app.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.util.ArrayList;
import java.util.List;

/** 搜索结果页（密语式）：显示搜索到的用户，点击进资料页。 */
public class SearchUsersActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private EditText etQuery;
    private RecyclerView rvResults;
    private TextView tvEmpty;
    private ResultAdapter adapter;
    private List<Models.User> users = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_users);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnSearchBack).setOnClickListener(v -> finish());
        etQuery = findViewById(R.id.etSearchQuery);
        rvResults = findViewById(R.id.rvSearchResults);
        tvEmpty = findViewById(R.id.tvSearchEmpty);

        String query = getIntent().getStringExtra("query");
        if (query != null && !query.isEmpty()) etQuery.setText(query);

        // 回车即搜索
        etQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnSearchGo).setOnClickListener(v -> doSearch());

        adapter = new ResultAdapter();
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        // 自动执行首次搜索
        if (query != null && !query.isEmpty()) doSearch();
    }

    private void doSearch() {
        String q = etQuery.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "请输入密语号或账号名", Toast.LENGTH_SHORT).show();
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                Models.UserListResponse res = api.searchUsers(q);
                List<Models.User> list = res.users != null ? res.users : new ArrayList<>();
                runOnUiThread(() -> {
                    users = list;
                    adapter.notifyDataSetChanged();
                    boolean empty = users.isEmpty();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    rvResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("搜索失败：" + e.getMessage());
                    rvResults.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Models.User u = users.get(i);
            h.name.setText(u.nickname != null && !u.nickname.isEmpty() ? u.nickname : u.username);
            h.account.setText("@" + (u.username != null ? u.username : ""));
            if (u.avatar != null && u.avatar.startsWith("/")) {
                String url = ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + u.avatar,
                        prefs.getToken());
                com.bumptech.glide.Glide.with(h.avatar.getContext()).load(url).circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
            }
            h.itemView.setOnClickListener(v -> {
                Intent it = new Intent(SearchUsersActivity.this, UserProfileActivity.class);
                it.putExtra("user_id", u.id);
                startActivity(it);
            });
        }

        @Override public int getItemCount() { return users.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView avatar;
            TextView name, account;
            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.ivSearchAvatar);
                name = v.findViewById(R.id.tvSearchName);
                account = v.findViewById(R.id.tvSearchAccount);
            }
        }
    }
}
