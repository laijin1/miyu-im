package com.familychat.app.utils;

/**
 * 跨功能导航路由（字符串常量）。
 * <p>
 * 功能模块之间不直接 import 对方的 Activity 类（那会产生编译期耦合/循环依赖），
 * 统一通过这里的类名常量 + {@code Intent.setClassName()} 跳转：
 * <pre>
 *   startActivity(new Intent().setClassName(this, Routes.CHAT_ACTIVITY));
 * </pre>
 * 类名在打包后仍位于同一 APK 内，运行时一定可解析；常量集中在此便于统一管理/改名。
 * <p>
 * 本类属于 :core，所有功能模块都可见；功能模块只依赖 :core + :ui-common，
 * 不依赖其它功能模块 → 「改一个功能不影响其他」。
 */
public final class Routes {

    // 聊天（:feature-chat）
    public static final String CHAT_ACTIVITY = "com.familychat.app.ui.ChatActivity";

    // 个人二维码（app 壳层）
    public static final String MY_QR_CODE_ACTIVITY = "com.familychat.app.ui.MyQrCodeActivity";

    // 商城（:feature-mall）
    public static final String MALL_ACTIVITY = "com.familychat.app.ui.MallActivity";

    // 钱包（:feature-wallet）
    public static final String WALLET_ACTIVITY = "com.familychat.app.ui.WalletActivity";

    // 登录页（app 壳层）
    public static final String LOGIN_ACTIVITY = "com.familychat.app.ui.LoginActivity";

    // 通话页（:feature-chat）。Step 1 起来电由 :core 的 CallManager 用 setClassName 拉起 ——
    // :core 看不见 :feature-chat，只能走类名字符串。
    public static final String CALL_ACTIVITY = "com.familychat.app.ui.CallActivity";

    // 通话前台服务（app 壳层）。反向同理：:feature-chat 看不见 :app，
    // CallEngine 只能通过类名字符串 startForegroundService / stopService。
    public static final String CALL_SERVICE = "com.familychat.app.call.CallService";

    // 安全码核对页（app 壳层）。E2EE TOFU 第二层：展示自己的安全码二维码 + 扫对方的码做带外比对。
    // 放 :app 是因为扫码（journeyapps）与二维码显示（QrUtils 依赖的 zxing）都已在壳层，
    // :feature-chat 无需为此新增任何依赖 —— 它只用本常量 + setClassName 拉起。
    public static final String SAFETY_NUMBER_ACTIVITY = "com.familychat.app.ui.SafetyNumberActivity";

    private Routes() {
    }
}
