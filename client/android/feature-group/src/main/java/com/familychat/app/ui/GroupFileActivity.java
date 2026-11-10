package com.familychat.app.ui;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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
import com.familychat.app.utils.ChatTimeFormatter;
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
 * 群文件库（全仿 QQ）：文件夹 chips + 文件列表(图标/名/大小/上传者/时间)+点击下载/长按删除；FAB 上传。
 * 文件为明文字节（/chat_files），仅群成员可看。删除权限：上传者本人或群主/管理员。
 */
public class GroupFileActivity extends AppCompatActivity {
    private static final String TAG = "GroupFile";

    private PreferencesManager prefs;
    private ApiClient api;
    // 统一用 HttpClientProvider 的共享 client（自带假域名模式 Dns），不再自建实例。
    private OkHttpClient httpClient = HttpClientProvider.shared();
    private int groupId;
    private boolean isAdmin;             // 群主/管理员可删他人文件
    private final List<Models.FileFolder> folders = new ArrayList<>();
    private final List<Models.GroupFile> files = new ArrayList<>();
    private Adapter adapter;
    private LinearLayout llFolders;
    private String selectedFolder = "全部";
    private TextView tvEmpty;
    private final List<View> chipViews = new ArrayList<>();

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) uploadPicked(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_file);
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        groupId = getIntent().getIntExtra("group_id", 0);
        if (groupId <= 0) { finish(); return; }

        // H5 口径提示：群文件为明文存储，仅群成员可访问
        Toast.makeText(this,
                "提示：群文件为明文存储，仅群成员可访问，不享受端到端加密保护。",
                Toast.LENGTH_LONG).show();

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        String gname = getIntent().getStringExtra("group_name");
        if (gname != null && !gname.isEmpty()) {
            ((TextView) findViewById(R.id.tvTitle)).setText(gname + " · 群文件");
        }
        llFolders = findViewById(R.id.llFolders);
        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvFiles);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fabUpload);
        fab.setOnClickListener(v -> {
            try { pickFile.launch("*/*"); } catch (Exception e) {
                Toast.makeText(this, "请到系统文件选择器选择文件", Toast.LENGTH_SHORT).show();
            }
        });

        loadAdminFlag();
        loadFoldersAndFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFoldersAndFiles();
    }

    private void loadAdminFlag() {
        new Thread(() -> {
            try {
                Models.GroupDetailResponse det = api.getGroupDetail(groupId);
                if (det != null && det.group != null) {
                    isAdmin = "owner".equals(det.group.my_role) || "admin".equals(det.group.my_role);
                }
            } catch (Exception e) { Log.d(TAG, "load role failed", e); }
        }).start();
    }

    private void loadFoldersAndFiles() {
        new Thread(() -> {
            try {
                Models.FileFoldersResponse fr = api.getGroupFileFolders(groupId);
                final List<Models.FileFolder> fl = fr != null && fr.folders != null ? fr.folders : new ArrayList<>();
                Models.GroupFilesResponse f2 = api.getGroupFiles(groupId, selectedFolder, 1);
                final List<Models.GroupFile> fl2 = f2 != null && f2.files != null ? f2.files : new ArrayList<>();
                runOnUiThread(() -> {
                    folders.clear();
                    folders.addAll(fl);
                    buildChips();
                    files.clear();
                    files.addAll(fl2);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                Log.e(TAG, "load failed", e);
                runOnUiThread(() -> Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void buildChips() {
        llFolders.removeAllViews();
        chipViews.clear();
        if (folders.isEmpty()) {
            Models.FileFolder all = new Models.FileFolder();
            all.folder = "全部"; all.count = 0;
            folders.add(all);
        }
        for (int i = 0; i < folders.size(); i++) {
            final Models.FileFolder f = folders.get(i);
            TextView chip = new TextView(this);
            chip.setText(f.folder + (f.count > 0 ? " " + f.count : ""));
            chip.setTextSize(13);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setBackgroundColor(0xFF000000 | getColor(R.color.surface));
            chip.setTextColor(getColor(R.color.text));
            // 选中态高亮
            final boolean selected = f.folder.equals(selectedFolder);
            if (selected) {
                chip.setBackgroundColor(getColor(R.color.green));
                chip.setTextColor(getColor(R.color.white));
            }
            chip.setOnClickListener(v -> {
                selectedFolder = f.folder;
                for (int j = 0; j < chipViews.size(); j++) {
                    TextView c = (TextView) chipViews.get(j);
                    Models.FileFolder fj = folders.get(j);
                    boolean sel = fj.folder.equals(selectedFolder);
                    c.setBackgroundColor(sel ? getColor(R.color.green) : getColor(R.color.surface));
                    c.setTextColor(sel ? getColor(R.color.white) : getColor(R.color.text));
                }
                loadFoldersAndFiles();
            });
            llFolders.addView(chip);
            chipViews.add(chip);
        }
    }

    private void uploadPicked(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            String name = queryDisplayName(cr, uri);
            if (name == null) name = "文件_" + System.currentTimeMillis() + ".bin";
            byte[] data = readAll(cr, uri);
            if (data == null || data.length == 0) {
                Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "上传中…", Toast.LENGTH_SHORT).show();
            final String fileName = name;
            new Thread(() -> {
                try {
                    String url = uploadBytes(data, fileName, cr.getType(uri));
                    if (url == null) {
                        runOnUiThread(() -> Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    Models.AddFileResponse res = api.addGroupFile(groupId, fileName, url,
                            data.length, "全部".equals(selectedFolder) ? "群文件" : selectedFolder);
                    runOnUiThread(() -> {
                        Toast.makeText(this, res != null && res.success ? "已上传到群文件" : "入库失败", Toast.LENGTH_SHORT).show();
                        loadFoldersAndFiles();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "upload failed", e);
                    runOnUiThread(() -> Toast.makeText(this, "上传失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "pick failed", e);
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String queryDisplayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
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

    private String uploadBytes(byte[] data, String fileName, String mime) throws Exception {
        MultipartBody mBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                        RequestBody.create(data, MediaType.parse(mime != null ? mime : "application/octet-stream")))
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

    private void downloadAndOpen(final Models.GroupFile f) {
        new Thread(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + f.file_url, prefs.getToken()))
                        .build();
                okhttp3.Response resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    runOnUiThread(() -> Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                byte[] data = resp.body().bytes();
                String dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                new java.io.File(dir).mkdirs();
                java.io.File saved = new java.io.File(dir, safeName(f.filename));
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(saved)) { fos.write(data); }
                runOnUiThread(() -> {
                    try {
                        Uri fileUri = androidXFileUri(saved);
                        Intent open = new Intent(Intent.ACTION_VIEW);
                        open.setDataAndType(fileUri, mimeFor(saved.getName()));
                        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(open, "打开文件"));
                    } catch (Exception e2) {
                        Toast.makeText(this, "文件已保存到下载目录", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private Uri androidXFileUri(java.io.File f) {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            return androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
        }
        return Uri.fromFile(f);
    }

    private String mimeFor(String fileName) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.matches("(jpg|jpeg|png|gif|bmp|webp)")) return "image/*";
        if (ext.matches("(mp4|mov|avi|mkv)")) return "video/*";
        if (ext.matches("(mp3|wav|aac|flac|m4a)")) return "audio/*";
        if (ext.matches("(doc|docx)")) return "application/msword";
        if (ext.matches("(xls|xlsx)")) return "application/vnd.ms-excel";
        if (ext.matches("(ppt|pptx)")) return "application/vnd.ms-powerpoint";
        if (ext.equals("txt")) return "text/plain";
        if (ext.equals("zip")) return "application/zip";
        return "*/*";
    }

    private String safeName(String n) {
        if (n == null || n.isEmpty()) return "文件_" + System.currentTimeMillis();
        String clean = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private String humanSize(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1024.0 / 1024.0);
        return String.format("%.1f GB", b / 1024.0 / 1024.0 / 1024.0);
    }

    private String typeIcon(String msgType) {
        if (msgType == null) return "📄";
        switch (msgType) {
            case "image": return "🖼️";
            case "video": return "🎬";
            case "voice": return "🎤";
            default: return "📄";
        }
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_group_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Models.GroupFile f = files.get(pos);
            h.tvIcon.setText(typeIcon(f.msg_type));
            h.tvName.setText(f.filename);
            String size = humanSize(f.size_bytes);
            String up = f.uploader_name != null ? f.uploader_name : "成员";
            // created_at 是 UTC 文本：先解析成毫秒，再按本地时区显示
            String time = ChatTimeFormatter.format(ChatTimeFormatter.parseUtc(f.created_at));
            h.tvMeta.setText((size.isEmpty() ? "" : size + " · ") + up + (time.isEmpty() ? "" : " · " + time));
            h.itemView.setOnClickListener(v -> downloadAndOpen(f));
            h.itemView.setOnLongClickListener(v -> {
                boolean mine = f.uploader_id == prefs.getUserId();
                if (mine || isAdmin) {
                    new MaterialAlertDialogBuilder(GroupFileActivity.this)
                        .setTitle("删除文件")
                        .setMessage("确定删除「" + f.filename + "」吗？")
                        .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                            try {
                                api.deleteGroupFile(groupId, f.id);
                                runOnUiThread(GroupFileActivity.this::loadFoldersAndFiles);
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(GroupFileActivity.this, "删除失败", Toast.LENGTH_SHORT).show());
                            }
                        }).start())
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                }
                Toast.makeText(GroupFileActivity.this, "仅上传者或群主/管理员可删除", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvName, tvMeta;
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvIcon);
                tvName = v.findViewById(R.id.tvName);
                tvMeta = v.findViewById(R.id.tvMeta);
            }
        }
    }
}
