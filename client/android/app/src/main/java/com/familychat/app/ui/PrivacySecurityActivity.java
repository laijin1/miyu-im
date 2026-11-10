package com.familychat.app.ui;

import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.familychat.app.R;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 隐私与安全：账号管理 + 修改密码。
 */
public class PrivacySecurityActivity extends AppCompatActivity {

    private PreferencesManager prefs;
    /** 当前打开的"账号管理"弹窗；移除账号后需 dismiss 并重建，否则旧行留在视图里。 */
    private AlertDialog accountDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_security);
        prefs = PreferencesManager.getInstance(this);

        ImageButton btnBack = findViewById(R.id.btnPrivacyBack);
        btnBack.setOnClickListener(v -> finish());

        findViewById(R.id.rowAccountManage).setOnClickListener(v -> showAccountManage());
        findViewById(R.id.rowChangePassword).setOnClickListener(v ->
                startActivity(new Intent(this, ChangePasswordActivity.class)));
    }

    // ===== 账号管理 =====

    private void showAccountManage() {
        List<PreferencesManager.Account> accounts = prefs.getAccounts();
        int currentUid = prefs.getUserId();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(8));

        if (accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无已保存的账号");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, dp(12), 0, dp(12));
            layout.addView(empty);
        } else {
            for (PreferencesManager.Account acc : accounts) {
                layout.addView(buildAccountRow(acc, acc.userId == currentUid));
            }
        }

        // 添加新账号（跳转登录页，登录成功后返回本页并刷新）
        TextView addBtn = new TextView(this);
        addBtn.setText("＋ 添加新账号");
        addBtn.setTextSize(15);
        addBtn.setTextColor(getColor(R.color.green));
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setPadding(0, dp(14), 0, dp(14));
        addBtn.setBackgroundResource(selectableItemBackground());
        addBtn.setOnClickListener(v -> goToAddAccount());
        layout.addView(addBtn);

        accountDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("账号管理")
                .setView(layout)
                .setNegativeButton("取消", null)
                .show();
    }

    private View buildAccountRow(final PreferencesManager.Account acc, boolean isCurrent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.setBackgroundResource(selectableItemBackground());

        // 头像：URL 用 Glide 加载真实头像（失败回退默认头像），否则显示表情
        int avatarSize = dp(46);
        if (acc.avatar != null && acc.avatar.startsWith("/")) {
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                String srv = (acc.server != null && !acc.server.isEmpty()) ? acc.server : prefs.getServer();
                // srv 可能来自账号快照（acc.server）而非当前 prefs，所以用 srv 而不是
                // prefs.getServer()；baseUrl 的假域名判断只看 host 是不是 IPv4，与此无关。
                String fullUrl = ServerProtocol.baseUrl(srv) + acc.avatar;
                Glide.with(this).load(fullUrl).circleCrop()
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .error(R.drawable.ic_avatar_placeholder)
                        .into(iv);
            } catch (Exception e) {
                iv.setImageResource(R.drawable.ic_avatar_placeholder);
            }
            row.addView(iv);
        } else {
            TextView av = new TextView(this);
            av.setText(acc.avatar != null && !acc.avatar.isEmpty() ? acc.avatar : "😊");
            av.setTextSize(26);
            av.setGravity(Gravity.CENTER);
            av.setTextColor(getColor(R.color.text));
            av.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
            row.addView(av);
        }

        // 昵称 + 用户名
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.setPadding(dp(10), 0, dp(10), 0);
        TextView nick = new TextView(this);
        String display = (acc.nickname != null && !acc.nickname.isEmpty()) ? acc.nickname : acc.username;
        nick.setText(display + (isCurrent ? "  [当前]" : ""));
        nick.setTextColor(getColor(R.color.text));
        nick.setTextSize(15);
        info.addView(nick);
        TextView uname = new TextView(this);
        uname.setText("@" + (acc.username != null ? acc.username : ""));
        uname.setTextColor(getColor(R.color.text_secondary));
        uname.setTextSize(12);
        info.addView(uname);
        row.addView(info);

        if (!isCurrent) {
            row.setOnClickListener(v -> switchToAccount(acc));
        }

        // 退出按钮
        TextView logoutTv = new TextView(this);
        logoutTv.setText("退出");
        logoutTv.setTextColor(getColor(R.color.red));
        logoutTv.setTextSize(14);
        logoutTv.setPadding(dp(12), dp(8), dp(12), dp(8));
        logoutTv.setOnClickListener(v -> removeAccount(acc));
        row.addView(logoutTv);

        return row;
    }

    /** 切换到指定账号：先同步当前账号快照，再载入目标账号，重启到主界面。 */
    private void switchToAccount(PreferencesManager.Account acc) {
        prefs.saveCurrentAccount();
        prefs.switchAccount(acc);
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
        finish();
    }

    /** 退出（移除）一个账号。 */
    private void removeAccount(final PreferencesManager.Account acc) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("退出账号")
                .setMessage("确定退出账号 " + (acc.nickname != null && !acc.nickname.isEmpty() ? acc.nickname : acc.username) + "？将删除该账号在本机的登录数据。")
                .setPositiveButton("退出", (d, w) -> {
                    boolean isCurrent = acc.userId == prefs.getUserId();
                    prefs.removeAccount(acc.userId);
                    if (isCurrent) {
                        // 当前账号被移除 → 退出登录
                        prefs.logout();
                        goToLogin();
                    } else {
                        // 账号已从 prefs 删除，但弹窗里的行视图不会自己更新：关掉旧弹窗重建。
                        if (accountDialog != null) accountDialog.dismiss();
                        showAccountManage();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 添加新账号：跳转登录页，登录成功后返回本页刷新账号列表。 */
    private void goToAddAccount() {
        Intent it = new Intent(this, LoginActivity.class);
        it.putExtra("from_account_manage", true);
        startActivityForResult(it, REQ_ADD_ACCOUNT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD_ACCOUNT && resultCode == RESULT_OK) {
            // 新账号已通过登录页保存，重新展示账号管理列表
            showAccountManage();
        }
    }

    /** 移除当前账号后跳转登录页（清空任务栈）。 */
    private void goToLogin() {
        Intent it = new Intent(this, LoginActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
        finish();
    }

    /** 解析 ?attr/selectableItemBackground 供代码创建的项使用（触摸反馈）。 */
    private int selectableItemBackground() {
        int[] attrs = {android.R.attr.selectableItemBackground};
        TypedArray ta = obtainStyledAttributes(attrs);
        int res = ta.getResourceId(0, 0);
        ta.recycle();
        return res;
    }

    private static final int REQ_ADD_ACCOUNT = 100;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
