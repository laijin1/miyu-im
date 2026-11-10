package com.familychat.app.auth;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 401 自动登出的去重闸门 + 会话重建时的释放点。
 *
 * 背景：token 过期时会有多个并发请求几乎同时返回 401
 * （MainActivity 的 getProfile / getFriends / getMyGroups 等，典型 3~5 个），
 * 每次都会触发全局 authListener。若不去重，LoginActivity 会被创建/销毁 N 次：
 * 登录页反复闪烁、每次都重跑局域网扫描（scanSubnet 每次新建 10 线程池）。
 *
 * 用法：
 *   - FamilyChatApp 的全局监听器里先调用 {@link #tryBegin()}，返回 false 表示
 *     已有一次在处理，调用方应直接 return；处理体抛异常时调用 {@link #reset()} 归还名额。
 *   - PreferencesManager.setToken() 末尾调用 {@link #reset()}：会话一旦重新建立，
 *     名额必须归还。否则下一次 token 过期将不再自动登出（表现为卡在旧页面无法返回登录页）。
 *
 * 本类只做这一件事，且只依赖 AtomicBoolean/Log，不引用 api 或 utils 包，
 * 因此单独放在 auth 包中，不会与既有包形成包级循环依赖。
 */
public final class AuthFailureGuard {

    private static final String TAG = "AuthFailureGuard";

    private static final AtomicBoolean handling = new AtomicBoolean(false);

    /** 工具类，不允许实例化。 */
    private AuthFailureGuard() {
    }

    /**
     * 抢占"正在处理 401 自动登出"的名额。
     *
     * @return true  抢占成功，本次是唯一一次处理，调用方应继续执行登出+跳登录页；
     *         false 已有一个在处理，调用方应立即返回，不要重复启动登录页。
     */
    public static boolean tryBegin() {
        boolean acquired = handling.compareAndSet(false, true);
        if (acquired) {
            Log.i(TAG, "acquired — 本次为唯一一次 401 处理（去重生效）");
        } else {
            Log.i(TAG, "skipped — 已有 401 处理在进行中，忽略本次");
        }
        return acquired;
    }

    /**
     * 释放名额。会话重新建立后（登录/注册/切换账号/改密换新 token）必须调用；
     * 处理体执行失败时也应调用，否则自动登出会永久失效。
     */
    public static void reset() {
        handling.set(false);
        Log.i(TAG, "released — 名额已释放（会话重新建立或处理失败）");
    }
}
