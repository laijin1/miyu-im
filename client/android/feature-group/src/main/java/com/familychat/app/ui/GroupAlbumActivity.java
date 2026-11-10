package com.familychat.app.ui;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_group.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 群相册（全仿 QQ）：相册宫格(封面+名称+张数)→照片宫格；FAB 新建相册/批量上传照片；
 * 长按删除（创建者/上传者或群主/管理员）。相片为明文字节(/chat_files)，仅群成员可看。
 */
public class GroupAlbumActivity extends AppCompatActivity {
    private static final String TAG = "GroupAlbum";

    private PreferencesManager prefs;
    private ApiClient api;
    // 统一用 HttpClientProvider 的共享 client（自带假域名模式 Dns），不再自建实例。
    private OkHttpClient httpClient = HttpClientProvider.shared();
    private int groupId;
    private boolean isAdmin;

    // 相册模式
    private RecyclerView rvAlbums;
    private TextView tvEmptyAlbums;
    private FloatingActionButton fabNewAlbum;
    private final List<Models.GroupAlbum> albums = new ArrayList<>();
    private AlbumAdapter albumAdapter;

    // 照片模式
    private RecyclerView rvPhotos;
    private TextView tvEmptyPhotos;
    private FloatingActionButton fabAddPhotos;
    private final List<Models.AlbumPhoto> photos = new ArrayList<>();
    private PhotoAdapter photoAdapter;
    private Models.GroupAlbum currentAlbum;

    private final androidx.activity.result.ActivityResultLauncher<Intent> pickImages =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || currentAlbum == null) return;
                        List<Uri> uris = new ArrayList<>();
                        Intent data = result.getData();
                        if (data == null) return;
                        ClipData clip = data.getClipData();
                        if (clip != null) {
                            for (int i = 0; i < clip.getItemCount(); i++) {
                                uris.add(clip.getItemAt(i).getUri());
                            }
                        } else if (data.getData() != null) {
                            uris.add(data.getData());
                        }
                        uploadPhotos(uris);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        // H5 口径提示：群相册为明文存储，仅群成员可访问
        Toast.makeText(this,
                "提示：群相册为明文存储，仅群成员可访问，不享受端到端加密保护。",
                Toast.LENGTH_LONG).show();

        showAlbumsMode();

        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null && det.group != null) {
                    isAdmin = "owner".equals(det.group.my_role) || "admin".equals(det.group.my_role);
                }
            } catch (Exception e) { Log.d(TAG, "role load failed", e); }
        }).start();
    }

    private void showAlbumsMode() {
        setContentView(R.layout.activity_group_album);
        ((ImageButton) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());
        String gname = getIntent().getStringExtra("group_name");
        if (gname != null && !gname.isEmpty()) {
            ((TextView) findViewById(R.id.tvTitle)).setText(gname + " · 群相册");
        }
        rvAlbums = findViewById(R.id.rvAlbums);
        tvEmptyAlbums = findViewById(R.id.tvEmpty);
        fabNewAlbum = findViewById(R.id.fabNewAlbum);
        rvAlbums.setLayoutManager(new GridLayoutManager(this, 3));
        albumAdapter = new AlbumAdapter();
        rvAlbums.setAdapter(albumAdapter);
        fabNewAlbum.setOnClickListener(v -> promptNewAlbum());
        loadAlbums();
    }

    private void showPhotosMode(Models.GroupAlbum album) {
        currentAlbum = album;
        setContentView(R.layout.activity_group_album_photos);
        ((ImageButton) findViewById(R.id.btnBack)).setOnClickListener(v -> showAlbumsMode());
        ((TextView) findViewById(R.id.tvTitle)).setText(album.name);
        rvPhotos = findViewById(R.id.rvPhotos);
        tvEmptyPhotos = findViewById(R.id.tvEmpty);
        fabAddPhotos = findViewById(R.id.fabAddPhotos);
        rvPhotos.setLayoutManager(new GridLayoutManager(this, 3));
        photoAdapter = new PhotoAdapter();
        rvPhotos.setAdapter(photoAdapter);
        fabAddPhotos.setOnClickListener(v -> openImagePicker());
        loadPhotos();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
    }

    private void refreshCurrent() {
        if (currentAlbum == null) loadAlbums();
        else loadPhotos();
    }

    private void loadAlbums() {
        new Thread(() -> {
            try {
                Models.GroupAlbumsResponse r = api.getGroupAlbums(groupId);
                final List<Models.GroupAlbum> list = r != null && r.albums != null ? r.albums : new ArrayList<>();
                runOnUiThread(() -> {
                    albums.clear();
                    albums.addAll(list);
                    if (albumAdapter != null) albumAdapter.notifyDataSetChanged();
                    if (tvEmptyAlbums != null) {
                        tvEmptyAlbums.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "load albums failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载相册失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void loadPhotos() {
        new Thread(() -> {
            try {
                Models.AlbumPhotosResponse r = api.getGroupAlbumPhotos(groupId, currentAlbum.id);
                final List<Models.AlbumPhoto> list = r != null && r.photos != null ? r.photos : new ArrayList<>();
                runOnUiThread(() -> {
                    photos.clear();
                    photos.addAll(list);
                    if (photoAdapter != null) photoAdapter.notifyDataSetChanged();
                    if (tvEmptyPhotos != null) {
                        tvEmptyPhotos.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "load photos failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载照片失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void promptNewAlbum() {
        final androidx.appcompat.app.AlertDialog[] ref = new androidx.appcompat.app.AlertDialog[1];
        final androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
        b.setTitle("新建相册");
        final androidx.appcompat.widget.AppCompatEditText input = new androidx.appcompat.widget.AppCompatEditText(this);
        input.setHint("相册名称");
        input.setSingleLine(true);
        input.setText("我的相册");
        b.setView(input);
        b.setPositiveButton("创建", (d, w) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(this, "请输入相册名称", Toast.LENGTH_SHORT).show(); return; }
            new Thread(() -> {
                try {
                    Models.StatusResponse res = api.createGroupAlbum(groupId, name);
                    runOnUiThread(() -> {
                        if (res != null && res.success) loadAlbums();
                        else Toast.makeText(this, res != null && res.error != null ? res.error : "创建失败", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
        b.setNegativeButton("取消", null);
        ref[0] = b.show();
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { pickImages.launch(intent); } catch (Exception e) {
            Toast.makeText(this, "请到相册/文件选择图片", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadPhotos(List<Uri> uris) {
        if (uris.isEmpty()) return;
        Toast.makeText(this, "上传 " + uris.size() + " 张照片…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String> urls = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    byte[] data = readAll(getContentResolver(), uri);
                    if (data == null || data.length == 0) continue;
                    String url = uploadBytes(data, uri.getLastPathSegment());
                    if (url != null) urls.add(url);
                } catch (Exception e) { Log.d(TAG, "upload one failed", e); }
            }
            if (urls.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                api.addAlbumPhotos(groupId, currentAlbum.id, urls);
                runOnUiThread(this::loadPhotos);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "入库失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private byte[] readAll(ContentResolver cr, Uri uri) {
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) { return null; }
    }

    private String uploadBytes(byte[] data, String fileName) throws Exception {
        String name = fileName != null && !fileName.isEmpty() ? fileName : "photo_" + System.currentTimeMillis() + ".jpg";
        MultipartBody mBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", name,
                        RequestBody.create(data, MediaType.parse("image/*")))
                .build();
        Request req = new Request.Builder()
                .url(ServerProtocol.baseUrl(prefs.getServer()) + "/api/chat/upload")
                .header("Authorization", "Bearer " + prefs.getToken())
                .post(mBody).build();
        okhttp3.Response resp = httpClient.newCall(req).execute();
        if (!resp.isSuccessful() || resp.body() == null) return null;
        JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();
        return json.has("url") ? json.get("url").getAsString() : null;
    }

    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        return ServerProtocol.baseUrl(prefs.getServer()) + path;
    }

    class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_album, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Models.GroupAlbum a = albums.get(pos);
            h.tvName.setText(a.name);
            h.tvCount.setText(a.photo_count + " 张");
            if (a.cover_url != null && !a.cover_url.isEmpty()) {
                Glide.with(GroupAlbumActivity.this)
                        .load(ServerProtocol.withToken(fullUrl(a.cover_url), prefs.getToken()))
                        .centerCrop()
                        .placeholder(R.drawable.bg_msg_other)
                        .into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.bg_msg_other);
            }
            h.itemView.setOnClickListener(v -> showPhotosMode(a));
            h.itemView.setOnLongClickListener(v -> {
                boolean mine = a.created_by == prefs.getUserId();
                if (mine || isAdmin) {
                    new MaterialAlertDialogBuilder(GroupAlbumActivity.this)
                        .setTitle("删除相册")
                        .setMessage("确定删除「" + a.name + "」及其全部照片吗？")
                        .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                            try {
                                api.deleteGroupAlbum(groupId, a.id);
                                runOnUiThread(GroupAlbumActivity.this::loadAlbums);
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(GroupAlbumActivity.this, "删除失败", Toast.LENGTH_SHORT).show());
                            }
                        }).start())
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                }
                Toast.makeText(GroupAlbumActivity.this, "仅创建者或群主/管理员可删除", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override public int getItemCount() { return albums.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivCover;
            TextView tvName, tvCount;
            VH(View v) {
                super(v);
                ivCover = v.findViewById(R.id.ivCover);
                tvName = v.findViewById(R.id.tvName);
                tvCount = v.findViewById(R.id.tvCount);
            }
        }
    }

    class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_album_photo, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Models.AlbumPhoto p = photos.get(pos);
            Glide.with(GroupAlbumActivity.this)
                    .load(ServerProtocol.withToken(fullUrl(p.photo_url), prefs.getToken()))
                    .centerCrop()
                    .placeholder(R.drawable.bg_msg_other)
                    .into(h.ivPhoto);
            h.itemView.setOnClickListener(v -> new ImagePreviewDialog(GroupAlbumActivity.this,
                    ServerProtocol.withToken(fullUrl(p.photo_url), prefs.getToken())).show());
            h.itemView.setOnLongClickListener(v -> {
                boolean mine = p.uploader_id == prefs.getUserId();
                if (mine || isAdmin) {
                    new MaterialAlertDialogBuilder(GroupAlbumActivity.this)
                        .setTitle("删除照片")
                        .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                            try {
                                api.deleteAlbumPhoto(groupId, currentAlbum.id, p.id);
                                runOnUiThread(GroupAlbumActivity.this::loadPhotos);
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(GroupAlbumActivity.this, "删除失败", Toast.LENGTH_SHORT).show());
                            }
                        }).start())
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                }
                Toast.makeText(GroupAlbumActivity.this, "仅上传者或群主/管理员可删除", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override public int getItemCount() { return photos.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivPhoto;
            VH(View v) {
                super(v);
                ivPhoto = v.findViewById(R.id.ivPhoto);
            }
        }
    }
}
