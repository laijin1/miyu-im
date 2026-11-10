package com.familychat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_chat.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.ChatTimeFormatter;
import com.familychat.app.utils.MessageDatabase;
import com.familychat.app.utils.MessageEncryptor;
import com.familychat.app.utils.MessagePreview;
import com.familychat.app.utils.PeerKeyPinStore;
import com.familychat.app.utils.PreviewDecryptor;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查找聊天记录：服务端搜索未加密字段 + 本地解密搜索加密消息，合并展示，点击跳转定位。
 */
public class SearchChatActivity extends AppCompatActivity {

    private static final String TAG = "SearchChat";
    private static final int PAGE_SIZE = 20;

    private EditText etSearch;
    private RecyclerView rvResults;
    private TextView tvEmpty;
    private int friendId;
    private String friendName;
    private String friendAvatar;
    private PreferencesManager prefs;
    private ApiClient api;
    private MessageDatabase localDb;
    private int myId;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String currentKeyword = "";
    private int serverPage = 0;
    private boolean hasMore = false;
    private boolean loading = false;
    private int searchToken = 0; // 丢弃过期搜索结果
    private final Map<Integer, Models.Message> results = new LinkedHashMap<>();
    private ResultAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_chat);

        friendId = getIntent().getIntExtra("friend_id", 0);
        friendName = getIntent().getStringExtra("friend_name");
        friendAvatar = getIntent().getStringExtra("friend_avatar");
        prefs = PreferencesManager.getInstance(this);
        api = new ApiClient(prefs.getServer(), prefs.getToken());
        localDb = MessageDatabase.getInstance(this);
        myId = prefs.getUserId();

        etSearch = findViewById(R.id.etSearch);
        rvResults = findViewById(R.id.rvSearchResults);
        tvEmpty = findViewById(R.id.tvSearchEmpty);
        ImageButton btnBack = findViewById(R.id.btnSearchBack);
        btnBack.setOnClickListener(v -> finish());

        adapter = new ResultAdapter();
        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvResults.setLayoutManager(llm);
        rvResults.setAdapter(adapter);
        rvResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2) {
                    loadMore();
                }
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 400);
            }
        });
    }

    private final Runnable searchRunnable = () -> doSearch(etSearch.getText().toString().trim());

    private void doSearch(String keyword) {
        if (keyword.isEmpty()) {
            currentKeyword = "";
            serverPage = 0;
            hasMore = false;
            searchToken++;
            results.clear();
            adapter.setData(new ArrayList<>());
            updateEmpty();
            return;
        }
        currentKeyword = keyword;
        serverPage = 0;
        hasMore = false;
        searchToken++;
        results.clear();
        runSearch(true, searchToken);
    }

    private void loadMore() {
        if (loading || currentKeyword.isEmpty() || !hasMore) return;
        runSearch(false, searchToken);
    }

    private void runSearch(final boolean reset, final int token) {
        if (!reset && loading) return; // 加载更多防重入；新搜索允许立即开始
        loading = true;
        new Thread(() -> {
            try {
                if (reset) serverPage = 1;
                // 1. 本地搜索（含加密消息，需解密）——先加入，便于服务端结果覆盖同名 id
                List<Models.Message> localMatches = localSearch(currentKeyword);
                // 2. 服务端搜索（未加密字段）
                List<Models.Message> serverBatch = new ArrayList<>();
                boolean more = false;
                try {
                    Models.MessagesResponse res = api.searchMessages(friendId, currentKeyword, serverPage, PAGE_SIZE);
                    if (res.messages != null) serverBatch.addAll(res.messages);
                    more = res.has_more;
                } catch (Exception e) {
                    android.util.Log.e(TAG, "server search error", e);
                }

                final int nextPage = serverPage + 1;
                final boolean finalMore = more;
                final List<Models.Message> merged = new ArrayList<>(localMatches);
                merged.addAll(serverBatch);
                runOnUiThread(() -> {
                    if (token != searchToken) return; // 过期结果丢弃
                    for (Models.Message m : merged) results.put(m.id, m);
                    List<Models.Message> sorted = new ArrayList<>(results.values());
                    sorted.sort((a, b) -> Integer.compare(b.id, a.id));
                    adapter.setData(sorted);
                    serverPage = nextPage;
                    hasMore = finalMore;
                    loading = false;
                    updateEmpty();
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "search error", e);
                runOnUiThread(() -> { if (token == searchToken) loading = false; });
            }
        }).start();
    }

    /** 本地搜索：解密加密消息后匹配关键词。 */
    private List<Models.Message> localSearch(String keyword) {
        List<Models.Message> out = new ArrayList<>();
        try {
            List<Models.Message> all = localDb.getMessages(myId, friendId);
            for (Models.Message m : all) {
                if ("recalled".equals(m.msg_type)) continue;
                String plain = decryptContent(m.content, m.msg_type);
                if ((plain != null && plain.contains(keyword))
                        || (m.filename != null && m.filename.contains(keyword))) {
                    out.add(m);
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "local search error", e);
        }
        return out;
    }

    /**
     * 与 ChatActivity 一致的解密逻辑：缓存公钥 → 服务端拉最新公钥两级重试；
     * <b>两级都必须过 TOFU pin</b>，失败显示占位而非密文。
     */
    private String decryptContent(String content, String msgType) {
        if (content == null || content.isEmpty()) return content;
        if ("text".equals(msgType) || msgType == null) {
            String myPriv = prefs.getMyPrivateKey();
            if (myPriv != null && content.length() >= 20) {
                // 尝试 1：缓存的对方公钥（先过 pin —— 缓存可能被其它路径覆盖过）
                String peerPub = PeerKeyPinStore.trustedOrNull(friendId, prefs.getPeerPublicKey(friendId));
                String plain = (peerPub != null) ? safeDecrypt(content, myPriv, peerPub) : null;
                if (plain != null) return plain;
                // 尝试 2：服务端拉取最新公钥重试一次（P3: 修复只用缓存公钥、解密失败直接显示密文的问题）
                try {
                    String fresh = api.getPublicKey(friendId);
                    if (fresh != null && !fresh.equals(peerPub)) {
                        if (PeerKeyPinStore.check(friendId, fresh) == PeerKeyPinStore.PinState.CHANGED) {
                            // 指纹与本地 pin 不一致：绝不写缓存，也绝不静默降级成"无法解密"
                            //（那会把疑似中间人替换显示成普通故障，用户不会去看一眼）
                            android.util.Log.w(TAG, "peer key CHANGED — search decrypt refused");
                            return PreviewDecryptor.PLACEHOLDER_KEY_CHANGED;
                        }
                        prefs.savePeerPublicKey(friendId, fresh);
                        plain = safeDecrypt(content, myPriv, fresh);
                        if (plain != null) return plain;
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "fetch fresh pubkey failed", e);
                }
                // 解密失败：不把密文当搜索内容/预览展示，避免搜索结果出现乱码 Base64
                if (looksLikeCiphertext(content)) {
                    return PreviewDecryptor.PLACEHOLDER_FAILED;
                }
            }
        }
        return content;
    }

    private String safeDecrypt(String content, String myPriv, String peerPub) {
        try {
            return MessageEncryptor.decryptMessage(content, myPriv, peerPub);
        } catch (Exception e) {
            return null;
        }
    }

    /** True if content looks like a Base64 E2EE ciphertext. */
    private boolean looksLikeCiphertext(String content) {
        if (content == null || content.length() < 40) return false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    private void updateEmpty() {
        boolean empty = adapter.getItemCount() == 0;
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            tvEmpty.setText(TextUtils.isEmpty(currentKeyword) ? "输入关键词搜索聊天记录" : "未找到相关消息");
        }
    }

    // ===== Result Adapter =====
    class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        private final List<Models.Message> data = new ArrayList<>();

        void setData(List<Models.Message> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(SearchChatActivity.this).inflate(R.layout.item_search_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Models.Message m = data.get(i);
            boolean isMe = m.sender_id == myId;

            h.name.setText(isMe ? "我" : (friendName != null ? friendName : "好友"));
            // 头像：好友的消息显示好友头像，自己显示自己的头像（简化：非空才加载）
            String av = isMe ? prefs.getMyAvatar() : friendAvatar;
            if (av != null && av.startsWith("/")) {
                Glide.with(SearchChatActivity.this)
                        .load(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + av,
                                prefs.getToken()))
                        .circleCrop().into(h.avatar);
            }
            h.time.setText(fmtTime(m.created_at));
            h.preview.setText(highlight(previewText(m), currentKeyword));
            h.root.setOnClickListener(v -> {
                Intent it = new Intent(SearchChatActivity.this, ChatActivity.class);
                it.putExtra("friend_id", friendId);
                it.putExtra("friend_name", friendName);
                it.putExtra("scroll_to_msg_id", m.id);
                startActivity(it);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            View root; ImageView avatar; TextView name; TextView preview; TextView time;
            VH(View v) {
                super(v);
                root = v;
                avatar = v.findViewById(R.id.srAvatar);
                name = v.findViewById(R.id.srName);
                preview = v.findViewById(R.id.srPreview);
                time = v.findViewById(R.id.srTime);
            }
        }
    }

    /**
     * 搜索结果预览文本。
     *
     * <p>image/file 保留「文件名优先」：搜索本身也按 {@code filename} 命中
     * （见 {@code searchLocal}），显示文件名并高亮关键词才是有用信息 ——
     * {@link MessagePreview} 只负责把不可读的内容变可读，不接管展示决策。
     *
     * <p>其余类型统一委托 {@link MessagePreview}：修掉了 video 显示裸路径、
     * transfer 显示原始 JSON 两个缺陷（历史缺陷见 §9.3）。
     *
     * <p>文本分支传 {@link Integer#MAX_VALUE} <b>刻意不截断</b>：搜索的核心价值是
     * 「看到关键词落在哪」，旧实现本来就不截断（靠 {@code srPreview} 的
     * {@code maxLines=2 + ellipsize=end} 视觉截断），截断到 30 字符会让命中位置
     * 在第 31 字符之后的结果丢失高亮，是真回归。会话列表/通知栏仍用默认 30。
     *
     * <p>未做（§9.3-F）：文本消息的 content 这里仍是本地库原文（对方发来的为密文），
     * 解密结果只用于 {@code searchLocal} 的关键词匹配、未回写，故对方文本的预览行会显示 Base64。
     */
    private String previewText(Models.Message m) {
        String type = m.msg_type != null ? m.msg_type : "text";
        if (("image".equals(type) || "file".equals(type))
                && m.filename != null && !m.filename.isEmpty()) {
            return m.filename;
        }
        return MessagePreview.forList(m.content, type, Integer.MAX_VALUE);
    }

    /** 关键词高亮。 */
    private CharSequence highlight(String text, String keyword) {
        if (text == null || TextUtils.isEmpty(keyword)) return text == null ? "" : text;
        String lower = text.toLowerCase();
        String kw = keyword.toLowerCase();
        SpannableString sp = new SpannableString(text);
        int idx = 0;
        while ((idx = lower.indexOf(kw, idx)) >= 0) {
            sp.setSpan(new BackgroundColorSpan(0xFFFFEB3B), idx, idx + kw.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx += kw.length();
        }
        return sp;
    }

    /** 消息时间格式化（created_at 是 UTC，先解析再按本地时区显示）。 */
    private String fmtTime(String ts) {
        return ChatTimeFormatter.format(ChatTimeFormatter.parseUtc(ts));
    }
}
