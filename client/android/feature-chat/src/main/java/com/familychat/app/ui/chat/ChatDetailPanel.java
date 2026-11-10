package com.familychat.app.ui.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.familychat.app.feature_chat.R;
import com.familychat.app.api.ApiClient;
import com.familychat.app.api.Models;
import com.familychat.app.utils.MessageDatabase;
import com.familychat.app.utils.KeyTransferPayload;
import com.familychat.app.utils.PeerKeyPinStore;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.Routes;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Chat detail panel shown when swiping right.
 * Displays friend info, remark, and action buttons.
 */
public class ChatDetailPanel {

    public interface Callback {
        void onClearChat();
        void onDeleteFriend();
        void onRemarkChanged(String newRemark);
        void onTogglePin(boolean pinned);
        void onToggleQuiet(boolean quiet);
    }

    private Context context;
    private Models.User friend;
    private String friendAvatar;
    private PreferencesManager prefs;
    private ApiClient api;
    private MessageDatabase localDb;
    private int myId;
    private Callback callback;
    private ImageView avatarIv;
    private TextView nickTv;
    private TextView unameTv;
    /** 「备注」那一行：当前备注放在它的右值（subtitle）里，updateIdentity() 会刷新它。 */
    private LinearLayout remarkRow;
    /** 「置顶会话」开关行：初值在 build() 时从 prefs 读一次（本面板无 refresh()）。 */
    private LinearLayout pinRow;
    /** 「消息免打扰」开关行：同上，初值只读一次。 */
    private LinearLayout quietRow;

    public ChatDetailPanel(Context context, Models.User friend, String friendAvatar,
                           PreferencesManager prefs, ApiClient api, MessageDatabase localDb, int myId) {
        this.context = context;
        this.friend = friend;
        this.friendAvatar = friendAvatar;
        this.prefs = prefs;
        this.api = api;
        this.localDb = localDb;
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
        // 顶部留白 80dp：本面板是右滑覆盖层，顶部要让出宿主自绘顶栏
        container.setPadding(0, dp(80), 0, 0);

        // ═══ ① 顶部卡：头像 + 昵称 + @账号名 ═══
        LinearLayout profileCard = new LinearLayout(context);
        profileCard.setOrientation(LinearLayout.VERTICAL);
        profileCard.setGravity(Gravity.CENTER_HORIZONTAL);
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
        loadAvatar(friendAvatar);
        avatarWrap.addView(avatarIv);
        profileCard.addView(avatarWrap);

        nickTv = new TextView(context);
        nickTv.setText(friend != null ? friend.nickname : "");
        nickTv.setTextColor(context.getColor(R.color.text));
        nickTv.setTextSize(20);
        nickTv.setTypeface(nickTv.getTypeface(), android.graphics.Typeface.BOLD);
        nickTv.setGravity(Gravity.CENTER);
        nickTv.setPadding(0, dp(12), 0, 0);
        profileCard.addView(nickTv);

        // @账号名：保留全 App 统一的身份显示（见 MainActivity「界面统一显示 @账号名
        // （唯一身份号）；后台密语号不再展示」）。不要改成昵称，也不要换密语号。
        unameTv = new TextView(context);
        unameTv.setText("@" + (friend != null ? friend.username : ""));
        unameTv.setTextColor(context.getColor(R.color.text_secondary));
        unameTv.setTextSize(13);
        unameTv.setGravity(Gravity.CENTER);
        unameTv.setPadding(0, dp(2), 0, 0);
        profileCard.addView(unameTv);

        // ═══ ② 卡片「资料」═══
        // remarkRow 的右值必须非 null（remarkDisplay 永不返回 null）：
        // createSectionButton 只在 subtitle != null 时才创建右值 TextView 并 setTag(subTv)，
        // 传 null 会让 setSectionSub 静默失效 —— updateIdentity() 就再也刷不出备注。
        remarkRow = createSectionButton("备注", remarkDisplay(friend),
                v -> editRemark(), false, R.drawable.ic_ff_remark);
        container.addView(sectionCard("资料", new LinearLayout[]{
                remarkRow,
                createSectionButton("安全码", safetyNumberSubtitle(),
                        v -> showSafetyNumber(), false, R.drawable.ic_ff_safety)
        }));

        // ═══ ③ 卡片「聊天」═══
        LinearLayout searchRow = createSectionButton("查找聊天记录", null,
                v -> openSearch(), false, R.drawable.ic_search_black_24);
        LinearLayout clearRow = createSectionButton("清理聊天记录", null,
                v -> confirmClear(), false, R.drawable.ic_ff_clear);

        // 会话开关（置顶 / 免打扰）：两者都是纯本地 prefs（服务端没有好友级会话设置表，
        // 见 PreferencesManager.setFriendPinned / setFriendQuiet）。
        // 初值只读一次 —— 本面板 build() 只跑一次且没有群面板那种 refresh()，
        // 用户在本面板内切换时 prefs 立刻同步，所以不需要 setSectionChecked 回写。
        // friend == null 时不挂这两行（初值读不到）；其余不依赖 friend 的行照常显示。
        LinearLayout[] chatRows;
        if (friend == null) {
            chatRows = new LinearLayout[]{searchRow, clearRow};
        } else {
            // TODO(图标)：仓库里没有语义合适的"置顶"图标（无 ic_pin / ic_arrow_up / ic_top），
            //   暂借 R.drawable.ic_group（与群面板「置顶会话」同款）；补图后只换这一处。
            pinRow = SectionRowHelper.createSwitchRow(context, R.drawable.ic_group, "置顶会话",
                    prefs.isFriendPinned(friend.id),
                    (btn, checked) -> {
                        // 程序性 setChecked 也会走 listener：与 prefs 现值比对，值没变即非用户操作
                        if (checked == prefs.isFriendPinned(friend.id)) return;
                        if (callback != null) callback.onTogglePin(checked);
                    });
            // 图标复用群侧免打扰行的 ic_voice（同模块 feature-chat/src/main/res/drawable/ic_voice.png）
            quietRow = SectionRowHelper.createSwitchRow(context, R.drawable.ic_voice, "消息免打扰",
                    prefs.getFriendQuiet(friend.id),
                    (btn, checked) -> {
                        if (checked == prefs.getFriendQuiet(friend.id)) return;
                        if (callback != null) callback.onToggleQuiet(checked);
                    });
            chatRows = new LinearLayout[]{searchRow, clearRow, pinRow, quietRow};
        }
        container.addView(sectionCard("聊天", chatRows));

        // ═══ ④ 危险区卡片（danger 淡底 + danger 描边，与群面板「退出群聊」同款）═══
        container.addView(dangerCard(createSectionButton("删除好友", null,
                v -> confirmDelete(), true, R.drawable.ic_ff_delete, false)));

        scroll.addView(container);
        return scroll;
    }

    // ═══ UI 构建工具（与 GroupDetailPanel 同款：卡片 + 图标行 + 右值）═══

    /** 卡片外边距（卡片之间 12dp，首个卡片 0）。 */
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
        card.setLayoutParams(cardLp(12));

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
     * 危险区卡片：单个危险行。去掉行自带的 ripple 底，换成 danger 淡底 + danger 描边，
     * 圆角与卡片一致；ripple 用行内色重挂，按压反馈不丢。
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

    /** 五参入口（群面板同款签名），默认带右侧箭头。 */
    private LinearLayout createSectionButton(String title, String subtitle,
                                            View.OnClickListener listener, boolean isDanger, int iconRes) {
        return createSectionButton(title, subtitle, listener, isDanger, iconRes, true);
    }

    /**
     * 图标行：ImageView(20dp 装饰) + 标题 15sp + Space(weight=1) + 右值 13sp + "›"。
     *
     * <p>iconRes=0 表示不带图标；hasArrow=false 用于危险行（与群面板「退出群聊」一致）。
     * <p>isDanger 只换文字色，不换控件类型。
     * <p>右值 TextView 通过 {@code setTag} 挂在本行上，供 {@link #setSectionSub} 回取 ——
     * 所以想被 {@code setSectionSub} 更新的行，subtitle 必须传<b>非 null</b>（可以是 ""）。
     */
    private LinearLayout createSectionButton(String title, String subtitle,
                                             View.OnClickListener listener, boolean isDanger,
                                             int iconRes, boolean hasArrow) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);
        row.setBackgroundResource(R.drawable.bg_ff_row_ripple);

        // 装饰图标：纯展示，不绑定、不点击、不参与任何逻辑
        if (iconRes != 0) {
            ImageView icon = new ImageView(context);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(12);
            icon.setLayoutParams(iconLp);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 图标统一着色：白天深色、黑夜白色（沿用主题 colorIcon，与群面板一致）
            try {
                android.util.TypedValue tv = new android.util.TypedValue();
                context.getTheme().resolveAttribute(R.attr.colorIcon, tv, true);
                icon.setColorFilter(tv.data);
            } catch (Exception ignored) {}
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon);
        }

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(15);
        titleTv.setTextColor(context.getColor(isDanger ? R.color.danger : R.color.text));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(titleTv);

        // 撑开中缝，把右值与箭头推到行尾
        Space spacer = new Space(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(spacer);

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

    /** 更新某个入口行的右值（备注等状态）。 */
    private void setSectionSub(LinearLayout row, String sub) {
        if (row == null) return;
        Object tag = row.getTag();
        if (tag instanceof TextView) ((TextView) tag).setText(sub);
    }

    // ===== 安全码（TOFU pin 指纹，docs/audits/E2EE_SECURITY_AUDIT §1.1）=====

    /**
     * 「安全码」行的右值：只放短码，不放长文案。
     *
     * <p>为什么短：右值槽是 {@code setSingleLine(true)} + {@code ellipsize=END}，长文案会被
     * 静默截断（见 {@link #createSectionButton}）。原来的引导文案「首次加密聊天后自动记录，
     * 点击查看」在这个槽里会被截掉一半，所以这里只留「未记录」；完整引导交给点击后的对话框
     * （{@link #showSafetyNumber} 的 emptyHint）。
     *
     * <p>⚠️ 「已核对」状态用短后缀 {@code ✓} 保留在这里，不外移：安全码是 E2EE 的核心 UI
     * （人眼核对指纹），而这一行是「已核对」唯一的可见反馈，不能因为美化而消失。
     * 用 {@code ✓} 而不是原来的 {@code ✅}，是为了把右值压到 10 个字符以内、不被截断。
     *
     * <p>位数说明：{@code displayCode} 是<b>设计好的 8 位短码</b>（全码 64 位的前 4 字节），
     * 仅供人眼近似核对；严格核对请点开对话框看 64 位全码。这不是截断 bug。
     */
    private String safetyNumberSubtitle() {
        String code = PeerKeyPinStore.displayCode(PeerKeyPinStore.getPinnedFingerprint(friend.id));
        if (code.isEmpty()) return "未记录";
        return PeerKeyPinStore.isUserConfirmed(friend.id) ? (code + " ✓") : code;
    }

    /**
     * 「备注」行的右值：服务端值优先（跨设备一致），空则回退本地缓存，再空给引导文案。
     *
     * <p>永不返回 null —— 见 {@link #createSectionButton}：subtitle 为 null 时不会创建右值
     * TextView、也不会 setTag，{@link #setSectionSub} 会静默失效（备注就永远刷不出来）。
     */
    private String remarkDisplay(Models.User f) {
        if (f != null && f.remark != null && !f.remark.isEmpty()) return f.remark;
        String local = prefs.getString("remark_" + friend.id, "");
        return local.isEmpty() ? "点击修改备注" : local;
    }

    /**
     * 弹出安全码对话框：本机与对方的公钥指纹并排显示，供用户通过其它渠道人工核对。
     *
     * <p>本对话框本身<b>只读</b>——不会因为"看一眼"就去生成密钥或拉取公钥
     * （否则会与 pin 的语义打架）。对方指纹来自 {@link PeerKeyPinStore} 的 pin
     * （TOFU 首次信任时写入）。
     *
     * <p>「扫码验证」按钮会把用户带到 :app 的安全码核对页完成带外比对，
     * 本页不参与比对过程，只负责入口与展示。
     */
    private void showSafetyNumber() {
        String myPub = prefs.getMyPublicKey();
        String myFp = (myPub == null || myPub.isEmpty()) ? "" : PeerKeyPinStore.fingerprintOf(myPub);
        String peerFp = PeerKeyPinStore.getPinnedFingerprint(friend.id);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(codeBlock("本机", myFp, "本机还没有加密密钥：打开任意聊天后会自动生成。"));
        box.addView(codeBlock(friend != null && friend.nickname != null && !friend.nickname.isEmpty()
                        ? friend.nickname : "对方", peerFp,
                "还没有对方的密钥记录：与他进行一次加密聊天后会自动记下，再回到本页即可核对。"));

        TextView hint = new TextView(context);
        hint.setText("如需确认对方身份，可点下方「扫码验证」与对方当面互扫二维码，"
                + "或通过其它渠道（电话 / 视频）逐位核对上面的数字；"
                + "完全一致才说明中间没有人在替换密钥。对方重装应用会换新密钥，届时这里的数字也会变。");
        hint.setTextSize(12);
        hint.setTextColor(context.getColor(R.color.text_secondary));
        hint.setPadding(0, dp(4), 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(context)
                .setTitle("安全码")
                .setView(box)
                .setPositiveButton("扫码验证", (d, w) -> openSafetyNumberPage())
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 拉起 :app 的安全码核对页（出示自己的二维码 + 扫对方的码 + 比对）。
     *
     * <p>跨模块用 {@link Routes#SAFETY_NUMBER_ACTIVITY} + {@code setClassName} —— 这是本仓库
     * 既定的跨模块导航方式（{@code Routes} 类注释：:feature-chat 看不见 :app，
     * 不能直接 import 对方的 Activity）。这里刻意<b>不做 Context→Activity 强转</b>，
     * 也不需要任何结果回传：核对是否成功由 {@link PeerKeyPinStore#isUserConfirmed}
     * 在下一次打开本面板时反映出来。
     *
     * <p>⚠️ extras 的 key 必须与 {@code com.familychat.app.ui.SafetyNumberActivity} 里的
     * {@code EXTRA_PEER_USER_ID} / {@code EXTRA_PEER_NAME} 常量值一致（跨模块字符串契约，
     * 无法用编译期常量约束）。
     */
    private void openSafetyNumberPage() {
        if (friend == null) return;
        try {
            android.content.Intent it = new android.content.Intent();
            it.setClassName(context, Routes.SAFETY_NUMBER_ACTIVITY);
            it.putExtra("peer_user_id", friend.id);
            it.putExtra("peer_name", friend.nickname);
            context.startActivity(it);
        } catch (Exception e) {
            android.util.Log.e("ChatDetail", "open safety number page failed", e);
            Toast.makeText(context, "无法打开安全码核对页", Toast.LENGTH_SHORT).show();
        }
    }

    /** 一块指纹：小标题 + 短码（醒目）+ 全码（逐位核对用）。 */
    private LinearLayout codeBlock(String label, String fullFingerprint, String emptyHint) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(16));

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(13);
        title.setTextColor(context.getColor(R.color.text_secondary));
        col.addView(title);

        boolean empty = (fullFingerprint == null || fullFingerprint.isEmpty());
        TextView shortTv = new TextView(context);
        shortTv.setText(empty ? "尚未记录" : PeerKeyPinStore.displayCode(fullFingerprint));
        shortTv.setTextSize(24);
        shortTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        shortTv.setTextColor(context.getColor(R.color.text));
        col.addView(shortTv);

        TextView fullTv = new TextView(context);
        fullTv.setText(empty ? emptyHint : KeyTransferPayload.group4(fullFingerprint));
        fullTv.setTextSize(12);
        fullTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        fullTv.setTextColor(context.getColor(R.color.text_secondary));
        fullTv.setTextIsSelectable(true);
        col.addView(fullTv);
        return col;
    }

    private void editRemark() {
        android.widget.EditText et = new android.widget.EditText(context);
        et.setHint("输入备注");
        et.setText(prefs.getString("remark_" + friend.id, ""));
        new MaterialAlertDialogBuilder(context)
                .setTitle("修改备注")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String remark = et.getText().toString().trim();
                    // 保存到服务器（后台线程），本地立即生效
                    new Thread(() -> {
                        try {
                            api.setFriendRemark(friend.id, remark);
                        } catch (Exception e) {
                            android.util.Log.e("ChatDetail", "set remark error", e);
                        }
                    }).start();
                    prefs.putString("remark_" + friend.id, remark);
                    if (friend != null) friend.remark = remark;
                    if (callback != null) callback.onRemarkChanged(remark);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开查找聊天记录页面。 */
    private void openSearch() {
        try {
            android.content.Intent it = new android.content.Intent(context, com.familychat.app.ui.SearchChatActivity.class);
            it.putExtra("friend_id", friend.id);
            it.putExtra("friend_name", friend.nickname);
            it.putExtra("friend_avatar", friendAvatar);
            context.startActivity(it);
        } catch (Exception e) {
            android.util.Log.e("ChatDetail", "open search error", e);
            Toast.makeText(context, "无法打开搜索", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(context)
                .setTitle("清理聊天记录")
                .setMessage("确定清空与该好友的所有聊天记录？此操作不可撤销。")
                .setPositiveButton("清空", (d, w) -> {
                    new Thread(() -> {
                        try {
                            api.clearConversation(friend.id);
                        } catch (Exception e) {
                            android.util.Log.e("ChatDetail", "clear error", e);
                        }
                        localDb.deleteConversation(myId, friend.id);
                        new Handler(context.getMainLooper()).post(() -> {
                            if (callback != null) callback.onClearChat();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(context)
                .setTitle("删除好友")
                .setMessage("确定删除该好友？聊天记录也将被清空。")
                .setPositiveButton("删除", (d, w) -> {
                    new Thread(() -> {
                        try {
                            api.deleteFriend(friend.id);
                        } catch (Exception e) {
                            android.util.Log.e("ChatDetail", "delete error", e);
                        }
                        localDb.deleteConversation(myId, friend.id);
                        new Handler(context.getMainLooper()).post(() -> {
                            if (callback != null) callback.onDeleteFriend();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Load avatar from server URL or fallback to default. */
    private void loadAvatar(String avatarUrl) {
        if (avatarUrl != null && avatarUrl.startsWith("/")) {
            String fullUrl = ServerProtocol.withToken(
                    ServerProtocol.baseUrl(prefs.getServer()) + avatarUrl,
                    prefs.getToken());
            android.util.Log.d("ChatDetail", "loading avatar: " + fullUrl);
            Glide.with(context)
                    .load(fullUrl)
                    .circleCrop()
                    .skipMemoryCache(true)
                    .error(R.drawable.ic_avatar_placeholder)
                    .into(avatarIv);
        } else {
            avatarIv.setImageResource(R.drawable.ic_avatar_placeholder);
        }
    }

    /** Public method to update avatar after async load (e.g. friend profile sync). */
    public void updateAvatar(String avatarUrl) {
        this.friendAvatar = avatarUrl;
        if (avatarIv != null) {
            loadAvatar(avatarUrl);
        }
    }

    /** Fetch friend info from server and update avatar/nickname/@账号名. Call from background thread. */
    public void refreshFromServer() {
        try {
            Models.FriendsResponse fres = api.getFriends();
            if (fres.friends != null) {
                for (Models.User f : fres.friends) {
                    if (f.id == friend.id) {
                        updateIdentity(f);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ChatDetail", "refreshFromServer", e);
        }
    }

    /** 用服务端最新好友数据刷新头像/昵称/@账号名。任何线程可调用（内部切 UI 线程）。 */
    public void updateIdentity(final Models.User fresh) {
        if (fresh == null) return;
        this.friend = fresh;
        this.friendAvatar = fresh.avatar;
        ((android.app.Activity)context).runOnUiThread(() -> {
            if (avatarIv != null) loadAvatar(fresh.avatar);
            if (nickTv != null) nickTv.setText(fresh.nickname != null ? fresh.nickname : "");
            if (unameTv != null) unameTv.setText("@" + (fresh.username != null ? fresh.username : ""));
            // 备注原来只在 build() 里读一次本地缓存，服务端同步来的备注永远刷不出来 ——
            // 这里补上刷新（服务端值优先，见 remarkDisplay）。
            if (remarkRow != null) setSectionSub(remarkRow, remarkDisplay(fresh));
        });
    }

    private int dp(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
