package com.familychat.app.utils;

import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 控件文字「临时变字」反馈工具：先显示反馈文案，ms 后自动复原。
 *
 * <p>用途：把"已复制/已保存"这类一次性反馈从 Toast 挪到触发控件自身的文字上，这样删掉
 * Toast 后用户仍有反馈。所有调用点都在主线程（含 runOnUiThread 里的异步回调），
 * 因此本类不做线程切换，也不做 Looper 判断。
 *
 * <p><b>与 core 定位的张力</b>：这是 core 里唯一 UI 特化工具（参数是 android.widget.TextView），
 * 而 core/build.gradle 的注释写着"只放不依赖任何界面/业务的基础能力"，两者有张力。
 * 选 core 而非 ui-common 的理由：① 5 个消费方（app / feature-wallet / feature-mall）已
 * {@code import com.familychat.app.utils.*}，零新增 import 概念；② QrUtils 已是
 * "UI 取向工具放 core/utils"的在库先例；③ ui-common 是纯资源模块（无任何 Java 源，
 * 放这里等于给它开一个新源根）。
 *
 * <p><b>待办（本批未做，记此备忘）</b>：
 * <ul>
 *   <li>core 的"不依赖任何界面"注释待更新 —— 要么把本类挪去 ui-common，要么把该注释
 *       改成"除 FlashText 外不依赖界面"。</li>
 *   <li>本类无自动化测试：core 开了 {@code unitTests.returnDefaultValues}，
 *       framework stub 在 JVM 单测里是空实现，{@code new TextView(...)} 无法断言文字；
 *       要测需引入 Robolectric 或写 androidTest。</li>
 * </ul>
 *
 * <p>用法：{@code FlashText.flash(btnCopy, "已复制", "复制内容");}
 */
public final class FlashText {

    /** 默认显示时长。 */
    private static final long DEFAULT_MS = 1200;

    /**
     * 每个目标控件上一次投递的复原任务，用于连点时撤销重排。
     *
     * <p>键弱引用，值在复原时即被移除：WeakHashMap 的值若强引用回自己的键，键就永远
     * 强可达、条目永不过期，所以这里必须"用完即删"，把条目寿命限制在一次 flash 窗口内。
     * 仅在主线程访问，故不加同步。
     */
    private static final Map<TextView, Runnable> PENDING = new WeakHashMap<>();

    private FlashText() {}

    /** 显示 flash 文案，{@value #DEFAULT_MS} 毫秒后复原为 normal。 */
    public static void flash(TextView v, CharSequence flash, CharSequence normal) {
        flash(v, flash, normal, DEFAULT_MS);
    }

    /**
     * 显示 flash 文案，ms 毫秒后复原为 normal。
     *
     * <p>连点安全：每次调用都先撤销上一个尚未触发的复原任务并重排计时；复原的永远是调用方
     * 传入的 normal（本方法从不读取控件当前文字），所以不存在"把反馈文案误当成原文复原"的问题。
     */
    public static void flash(TextView v, CharSequence flash, CharSequence normal, long ms) {
        Runnable prev = PENDING.remove(v);
        if (prev != null) v.removeCallbacks(prev);
        v.setText(flash);
        Runnable restore = () -> {
            PENDING.remove(v);
            v.setText(normal);
        };
        PENDING.put(v, restore);
        v.postDelayed(restore, ms);
    }
}
