package com.familychat.app.ui.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_chat.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 群聊详情面板：右滑进入（与单聊 ChatDetailPanel 进入方式一致）。
 * 展示群头像/名称/人数/我的角色/我的群名片，
 * 并提供公告/文件/相册/投票/搜索入口、置顶/免打扰、完整群详情、退出群聊。
 */
public class GroupDetailPanel {

    public interface Callback {
        void onOpenAnnouncement();
        void onOpenFiles();
        void onOpenAlbums();
        void onOpenPolls();
        void onOpenSearch();
        void onOpenFullDetail();
        void onTogglePin(boolean pinned);
        void onToggleQuiet(boolean quiet);
        void onLeaveGroup();
    }

    private Context context;
    private int groupId;
    private Models.Group group;
    private PreferencesManager prefs;
    private ApiClient api;
    private int myId;
    private Callback callback;

    private ImageView avatarIv;
    private TextView nameTv, roleTv, countTv;
    /** 「我的群名片」那一行：当前名片值放在它的右值（subtitle）里，由 refresh() 填。 */
    private LinearLayout nicknameRow, pinRow, quietRow;

    public GroupDetailPanel(Context context, int groupId, Models.Group group,
                            PreferencesManager prefs, ApiClient api, int myId) {
        this.context = context;
        this.groupId = groupId;
        this.group = group;
        this.prefs = prefs;
        this.api = api;
        this.myId = myId;
    }

    public void setCallback(Callback cb) { this.callback = cb; }

    public View build() {
        ScrollView scroll = new ScrollView(context);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setPadding(dp(16), 0, dp(16), dp(32));
        scroll.setBackgroundColor(context.getColor(R.color.bg));
        scroll.setFillViewport(true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 顶部留白 80dp：本面板是右滑覆盖层，顶部要让出 GroupChatActivity 的自绘顶栏
        container.setPadding(0, dp(80), 0, 0);

        // ═══ ① 群头像卡（头像 + 群名 + 角色 + 人数 + 我的群名片）═══
        LinearLayout profileCard = new LinearLayout(context);
        profileCard.setOrientation(LinearLayout.VERTICAL);
        profileCard.setGravity(Gravity.START);   // 顶部卡整体左对齐：头像/群名/人数都靠左
        profileCard.setBackgroundResource(R.drawable.bg_ff_card);
        profileCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        container.addView(profileCard, cardLp(0));

        int avatarSize = dp(72);
        FrameLayout avatarWrap = new FrameLayout(context);
        avatarWrap.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
        avatarIv = new ImageView(context);
        avatarIv.setLayoutParams(new FrameLayout.LayoutParams(avatarSize, avatarSize));
        avatarIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0xFFE0E0E0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            avatarIv.setBackground(avatarBg);
        } else {
            avatarIv.setBackgroundDrawable(avatarBg);
        }
        loadAvatar(group != null ? group.avatar : null);
        avatarWrap.addView(avatarIv);
        profileCard.addView(avatarWrap);

        // 群名 + 角色标签（左对齐）
        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        // 只把水平方向改成左对齐，垂直方向必须保留 CENTER_VERTICAL：
        // 若这里只写 Gravity.START，垂直居中会一并丢掉，"群主"标签会跳到行顶（视觉回归）。
        nameRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        nameRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 原来"群名上方 10dp 留白"挂在 nameTv 的 paddingTop 上，会让 nameTv 的盒子比"群主"
        // 标签的盒子高 10dp；行内垂直居中就会把标签压到偏低处（原来的 topMargin=16dp 正是
        // 在手工补偿这个偏差，属于依赖字体度量的 magic number）。把这段留白上移到行 padding
        // 后，两个子视图共享同一内容区，CENTER_VERTICAL 自动把标签居中到群名文字的垂直中线上，
        // 且与字号缩放无关。视觉间距不变（原本也是 10dp）。
        nameRow.setPadding(0, dp(10), 0, 0);

        nameTv = new TextView(context);
        nameTv.setText(group != null && group.name != null && !group.name.isEmpty() ? group.name : "群聊");
        nameTv.setTextColor(context.getColor(R.color.text));
        nameTv.setTextSize(20);
        nameTv.setTypeface(nameTv.getTypeface(), android.graphics.Typeface.BOLD);
        nameTv.setPadding(0, 0, 0, 0);   // 上方 10dp 留白改由 nameRow 的 paddingTop 承担（见上）
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);

        roleTv = new TextView(context);
        roleTv.setTextSize(12);
        roleTv.setPadding(dp(8), dp(2), dp(8), dp(2));

        nameRow.addView(nameTv);
        LinearLayout.LayoutParams roleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        roleLp.leftMargin = dp(12);
        roleLp.topMargin = 0;   // 垂直对齐交给 nameRow 的 CENTER_VERTICAL，不再手工补偿
        nameRow.addView(roleTv, roleLp);
        profileCard.addView(nameRow);

        countTv = new TextView(context);
        countTv.setTextColor(context.getColor(R.color.text_secondary));
        countTv.setTextSize(13);
        countTv.setGravity(Gravity.START);   // "X人"靠左（countTv 自身是全宽，靠它把文字左对齐）
        countTv.setPadding(0, dp(4), 0, 0);
        profileCard.addView(countTv);

        // ═══ ② 卡片「资料」═══
        // 「我的群名片」既是指入口，也负责显示当前值（右值 subtitle，由 refresh() 填），
        // 所以顶部卡不再重复展示它 —— 信息只有一个来源，不会出现两处值不一致。
        // ⚠️ subtitle 必须传 ""（不能传 null）：createSectionButton 只在 subtitle != null 时
        //    才创建右值 TextView 并 setTag(subTv)；传 null 会让 tag 为 null，
        //    之后 setSectionSub 会因 tag 不是 TextView 而静默失效（值永远不显示）。
        nicknameRow = createSectionButton("我的群名片",
                "", v -> editMyNickname(), false, R.drawable.ic_avatar_placeholder);
        container.addView(sectionCard("资料", new LinearLayout[]{
                nicknameRow,
                createSectionButton("群公告",
                        null, v -> safeOpen(c -> c.onOpenAnnouncement()), false, R.drawable.ic_msg)
        }));

        // ═══ ③ 卡片「群应用」═══
        container.addView(sectionCard("群应用", new LinearLayout[]{
                createSectionButton("群文件",
                        null, v -> safeOpen(c -> c.onOpenFiles()), false, R.drawable.ic_file),
                createSectionButton("群相册",
                        null, v -> safeOpen(c -> c.onOpenAlbums()), false, R.drawable.ic_gallery),
                createSectionButton("群投票",
                        null, v -> safeOpen(c -> c.onOpenPolls()), false, R.drawable.ic_ff_poll),
                createSectionButton("群内搜索",
                        null, v -> safeOpen(c -> c.onOpenSearch()), false, R.drawable.ic_search_black_24)
        }));

        // ═══ ④ 卡片「会话设置」═══
        // 置顶 / 免打扰用开关表达状态：一眼看到当前值，点整行或点开关都能切。
        //
        // ⚠️ listener 里那两行不是多余的，它们防一个真实竞态：
        //    refresh() 会在收到 group_members_changed 广播时被调用（GroupChatActivity:360 →
        //    reloadMembers → :746 refresh），而它里面会 setChecked(...) 同步开关，setChecked
        //    同样会触发本 listener。若此时恰好有"用户刚点完、服务端还没回"的在途请求，
        //    不做比对就会拿旧值再发一次 PUT，把用户刚做的修改覆盖掉。
        //    · 第 1 行比对：值没变 ⇒ 是 refresh() 在同步，不是用户操作，直接返回；
        //    · 第 2 行立即改本地 group 字段：它与宿主 toggleGroupPin 改的是同一个对象，
        //      提前生效可避免"连点第二下被比对逻辑当成同步而吞掉"。
        pinRow = createSwitchRow("置顶会话", R.drawable.ic_group,
                group != null && group.pinned == 1,
                (btn, checked) -> {
                    boolean cur = group != null && group.pinned == 1;
                    if (checked == cur) return;              // 来自 refresh() 的同步，不是用户操作
                    if (group != null) group.pinned = checked ? 1 : 0;
                    safeOpen(c -> c.onTogglePin(checked));
                });
        quietRow = createSwitchRow("消息免打扰", R.drawable.ic_voice,
                group != null && group.quiet == 1,
                (btn, checked) -> {
                    boolean cur = group != null && group.quiet == 1;
                    if (checked == cur) return;
                    if (group != null) group.quiet = checked ? 1 : 0;
                    safeOpen(c -> c.onToggleQuiet(checked));
                });
        container.addView(sectionCard("会话设置", new LinearLayout[]{pinRow, quietRow}));

        // ═══ ⑤ 卡片「群管理」═══
        container.addView(sectionCard("群管理", new LinearLayout[]{
                createSectionButton("查看完整群详情",
                        "成员管理 / 群主转让 / 解散群聊",
                        v -> safeOpen(c -> c.onOpenFullDetail()), false, R.drawable.ic_group, false)
        }));

        // ═══ ⑥ 危险区卡片（独立卡片，危险色文字 + 危险色淡底）═══
        container.addView(dangerCard(createSectionButton("退出群聊",
                null, v -> confirmLeave(), true, R.drawable.ic_back, false)));

        scroll.addView(container);
        refresh(group);
        return scroll;
    }

    /** 群数据变化后刷新面板（需在主线程调用）。 */
    public void refresh(final Models.Group g) {
        if (g == null || nameTv == null) return;
        this.group = g;
        nameTv.setText(g.name != null && !g.name.isEmpty() ? g.name : "群聊");
        loadAvatar(g.avatar);

        String role = g.my_role;
        int roleColor;
        String roleText;
        if ("owner".equals(role)) { roleText = "群主"; roleColor = context.getColor(R.color.green); }
        else if ("admin".equals(role)) { roleText = "管理员"; roleColor = 0xFFFB8C00; }
        else { roleText = "成员"; roleColor = context.getColor(R.color.text_secondary); }
        roleTv.setText(roleText);
        roleTv.setTextColor(roleColor);
        GradientDrawable rb = new GradientDrawable();
        rb.setShape(GradientDrawable.RECTANGLE);
        rb.setCornerRadius(dp(4));
        rb.setStroke(dp(1), roleColor);
        roleTv.setBackground(rb);

        countTv.setText(g.member_count > 0 ? g.member_count + "人" : "");
        String myNick = (g.my_nickname != null && !g.my_nickname.isEmpty()) ? g.my_nickname : "（使用全局昵称）";
        setSectionSub(nicknameRow, myNick);

        setSectionChecked(pinRow, g.pinned == 1);
        setSectionChecked(quietRow, g.quiet == 1);
    }

    // ═══ 群名片 / 退出群聊 ═══

    /** 修改我的群名片（所有人）。 */
    private void editMyNickname() {
        final EditText et = new EditText(context);
        et.setHint("输入群名片（留空则用全局昵称）");
        et.setSingleLine(true);
        if (group != null && group.my_nickname != null && !group.my_nickname.isEmpty()) {
            et.setText(group.my_nickname);
            et.setSelection(et.getText().length());
        }
        new MaterialAlertDialogBuilder(context)
            .setTitle("修改我的群名片")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                final String nick = et.getText().toString().trim();
                new Thread(() -> {
                    try {
                        api.setMyGroupNickname(groupId, nick);
                        if (group != null) group.my_nickname = nick;
                        ((android.app.Activity) context).runOnUiThread(() -> refresh(group));
                    } catch (Exception e) {
                        android.util.Log.e("GroupDetail", "set nickname error", e);
                        ((android.app.Activity) context).runOnUiThread(() ->
                                Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmLeave() {
        new MaterialAlertDialogBuilder(context)
            .setTitle("退出群聊")
            .setMessage("确定退出该群？")
            .setPositiveButton("退出", (d, w) -> new Thread(() -> {
                try {
                    api.removeGroupMember(groupId, myId);
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        if (callback != null) callback.onLeaveGroup();
                    });
                } catch (Exception e) {
                    android.util.Log.e("GroupDetail", "leave error", e);
                    ((android.app.Activity) context).runOnUiThread(() ->
                            Toast.makeText(context, "退出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start())
            .setNegativeButton("取消", null)
            .show();
    }

    private void safeOpen(java.util.function.Consumer<Callback> action) {
        if (callback != null) action.accept(callback);
    }

    // ═══ UI 构建工具（群详情：卡片 + 图标行，全仿 QQ 详情页）═══

    /** 卡片外边距（卡片之间 12dp，首尾 0）。 */
    private LinearLayout.LayoutParams cardLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    /** 「标题 + N 个行」的分组卡片。 */
    private LinearLayout sectionCard(String header, LinearLayout[] rows) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_ff_card);
        LinearLayout.LayoutParams lp = cardLp(12);
        card.setLayoutParams(lp);

        if (header != null && !header.isEmpty()) {
            TextView h = new TextView(context);
            h.setText(header);
            h.setTextSize(12);
            h.setTextColor(context.getColor(R.color.text_secondary));
            h.setPadding(dp(16), dp(12), dp(16), dp(2));
            card.addView(h);
        }
        for (int i = 0; i < rows.length; i++) {
            card.addView(rows[i]);
            if (i < rows.length - 1) card.addView(rowDivider());
        }
        return card;
    }

    /**
     * 危险区卡片：单个危险行，去掉圆角卡片的行底，换成 danger 淡底 + danger 描边，
     * 与卡片的圆角完全一致，避免圆角处出现直角色块。
     */
    private LinearLayout dangerCard(LinearLayout row) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(cardLp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(context.getColor(R.color.danger_bg));
        bg.setStroke(dp(1), context.getColor(R.color.danger));
        card.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x1FE5484D), bg, null));
        row.setBackground(null);
        card.addView(row);
        return card;
    }

    /** 卡片内行间分割线（左缩进 16dp，与行内文字对齐，图标不穿过）。 */
    private View rowDivider() {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        v.setBackgroundColor(context.getColor(R.color.divider));
        return v;
    }

    /** 行右侧箭头。 */
    private TextView arrowTv() {
        TextView tv = new TextView(context);
        tv.setText("›");
        tv.setTextSize(16);
        tv.setTextColor(context.getColor(R.color.text_secondary));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        return tv;
    }

    /** 四参兼容入口（保持原签名，内部委托五参版本，不带图标）。 */
    private LinearLayout createSectionButton(String title, String subtitle,
                                            View.OnClickListener listener, boolean isDanger) {
        return createSectionButton(title, subtitle, listener, isDanger, 0);
    }

    /**
     * 图标行：ImageView(20dp 装饰) + 标题 15sp + Space(weight=1) + 右值 13sp + "›"。
     *
     * <p>iconRes=0 表示不带图标（副标题型行）；hasArrow=false 用于副标题行（右侧已有说明文字）。
     * <p>isDanger 只换文字色，不换控件类型。
     * <p>右值 TextView 仍通过 {@code setTag} 挂在本行上，供 {@link #setSectionSub} 回取。
     */
    private LinearLayout createSectionButton(String title, String subtitle,
                                             View.OnClickListener listener, boolean isDanger, int iconRes) {
        return createSectionButton(title, subtitle, listener, isDanger, iconRes, true);
    }

    /**
     * 行骨架：HORIZONTAL / CENTER_VERTICAL / 高 52dp / 左右内边距 16dp / ripple 底 / 可点，
     * 内容为「装饰图标 + 标题 + 撑开的 Space」。
     *
     * <p>尾部（右值 / 箭头 / 开关）由调用方追加 —— {@link #createSectionButton} 与
     * {@link #createSwitchRow} 共用本骨架。
     *
     * <p>实现已移到 {@link SectionRowHelper#newSectionRow}（与单聊面板共用同一份，
     * 避免行高/内边距/图标着色两处各写一份而漂移）。本方法只转发，签名与语义不变。
     *
     * <p>⚠️ 本方法<b>不</b>设置 click listener：尾部控件不同，点击语义也不同，由调用方决定。
     */
    private LinearLayout newSectionRow(int iconRes, String title, boolean isDanger) {
        return SectionRowHelper.newSectionRow(context, iconRes, title, isDanger);
    }

    private LinearLayout createSectionButton(String title, String subtitle,
                                             View.OnClickListener listener, boolean isDanger,
                                             int iconRes, boolean hasArrow) {
        LinearLayout row = newSectionRow(iconRes, title, isDanger);
        row.setOnClickListener(listener);

        TextView subTv = null;
        if (subtitle != null) {
            subTv = new TextView(context);
            subTv.setText(subtitle);
            subTv.setTextSize(13);
            subTv.setTextColor(context.getColor(R.color.text_secondary));
            subTv.setSingleLine(true);
            subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subTv.setGravity(Gravity.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.leftMargin = dp(8);
            row.addView(subTv, subLp);
        }
        row.setTag(subTv);

        if (hasArrow) {
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            arrowLp.leftMargin = dp(6);
            row.addView(arrowTv(), arrowLp);
        }
        return row;
    }

    /**
     * 开关行：骨架 + 尾部 Switch。
     *
     * <p>实现已移到 {@link SectionRowHelper#createSwitchRow}（开关行构造、{@code setTag} 机制、
     * "点整行 = toggle" 且只回调一次的语义都在那里，单聊面板共用）。本方法只转发，签名不变。
     */
    private LinearLayout createSwitchRow(String title, int iconRes, boolean checked,
                                         CompoundButton.OnCheckedChangeListener onToggle) {
        return SectionRowHelper.createSwitchRow(context, iconRes, title, checked, onToggle);
    }

    /** 更新某个入口行的副标题（群名片等右值）。 */
    private void setSectionSub(LinearLayout row, String sub) {
        if (row == null) return;
        Object tag = row.getTag();
        if (tag instanceof TextView) ((TextView) tag).setText(sub);
    }

    /**
     * 同步某个开关行的选中态（置顶/免打扰）。
     *
     * <p>实现已移到 {@link SectionRowHelper#setSectionChecked}（靠 {@code row.getTag()} 取回尾部
     * 开关，用 {@code CompoundButton} 判断而不用具体开关类型）。本方法只转发，签名不变。
     *
     * <p>⚠️ 这里的 {@code setChecked} 在值发生变化时<b>会</b>触发该开关的 listener；
     * 调用方（createSwitchRow 的 onToggle）已用"与当前 group 状态比对"处理了这种程序性同步，
     * 不会因此误发请求。
     */
    private void setSectionChecked(LinearLayout row, boolean checked) {
        SectionRowHelper.setSectionChecked(row, checked);
    }

    private void loadAvatar(String avatarUrl) {
        try {
            if (avatarUrl != null && avatarUrl.startsWith("/")) {
                Glide.with(context)
                        .load(ServerProtocol.withToken(
                                ServerProtocol.baseUrl(prefs.getServer()) + avatarUrl,
                                prefs.getToken()))
                        .circleCrop()
                        .skipMemoryCache(true)
                        .error(R.drawable.ic_group)
                        .into(avatarIv);
            } else {
                avatarIv.setImageResource(R.drawable.ic_group);
            }
        } catch (Exception e) {
            avatarIv.setImageResource(R.drawable.ic_group);
        }
    }

    private int dp(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
