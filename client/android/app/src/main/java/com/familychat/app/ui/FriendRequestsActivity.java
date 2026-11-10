package com.familychat.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class FriendRequestsActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    private ApiClient api;
    private RecyclerView rvRequests;
    private TextView tvEmpty;
    private RequestAdapter adapter;
    private List<Models.FriendRequest> requests = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friend_requests);

        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        rvRequests = findViewById(R.id.rvRequests);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new RequestAdapter();
        rvRequests.setLayoutManager(new LinearLayoutManager(this));
        rvRequests.setAdapter(adapter);

        loadRequests();
    }

    private void loadRequests() {
        new Thread(() -> {
            try {
                Models.FriendRequestsResponse res = api.getPendingRequests();
                requests = res.requests != null ? res.requests : new ArrayList<>();
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
                    rvRequests.setVisibility(requests.isEmpty() ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                android.util.Log.e("FriendRequests", "load error", e);
            }
        }).start();
    }

    class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_friend_request, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Models.FriendRequest req = requests.get(i);
            h.name.setText(req.user != null ? req.user.nickname : "未知用户");
            if (req.user != null && req.user.avatar != null && req.user.avatar.startsWith("/")) {
                String url = ServerProtocol.withToken(
                        ServerProtocol.baseUrl(prefs.getServer()) + req.user.avatar,
                        prefs.getToken());
                com.bumptech.glide.Glide.with(h.avatar.getContext()).load(url).circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
            }

            h.btnAccept.setOnClickListener(v -> {
                h.btnAccept.setEnabled(false);
                new Thread(() -> {
                    try {
                        api.handleFriendRequest(req.friendship_id, "accept");
                        runOnUiThread(() -> loadRequests());
                    } catch (Exception e) {
                        runOnUiThread(() -> { h.btnAccept.setEnabled(true); Toast.makeText(FriendRequestsActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show(); });
                    }
                }).start();
            });
            h.btnReject.setOnClickListener(v -> {
                h.btnReject.setEnabled(false);
                new Thread(() -> {
                    try {
                        api.handleFriendRequest(req.friendship_id, "reject");
                        runOnUiThread(() -> loadRequests());
                    } catch (Exception e) { runOnUiThread(() -> h.btnReject.setEnabled(true)); }
                }).start();
            });
        }

        @Override public int getItemCount() { return requests.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView avatar;
            TextView name;
            MaterialButton btnAccept, btnReject;
            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.ivReqAvatar);
                name = v.findViewById(R.id.tvReqName);
                btnAccept = v.findViewById(R.id.btnAccept);
                btnReject = v.findViewById(R.id.btnReject);
            }
        }
    }
}
