package com.familychat.app.ui;

import android.app.Activity;
import android.view.View;

import com.familychat.app.feature_mall.R;

/**
 * 商城底部导航（首页/购物车/我的订单）绑定助手 —— 与主界面导航一致：单 Activity 内原位切换视图。
 * bind() 高亮当前 tab，点击其它 tab 回调 {@link MallActivity#showMallTab(int)} 切换视图。
 */
public final class MallNav {

    private MallNav() {
    }

    /**
     * 绑定底部导航。active: 0=首页 1=购物车 2=我的订单。
     * tab 点击：当前页不变；其它页通过回调原位切换（不再开新 Activity，返回栈干净）。
     */
    public static void bind(Activity activity, int active, java.util.function.IntConsumer onTabSelected) {
        int[] tabs = {R.id.tabNavHome, R.id.tabNavCart, R.id.tabNavOrders};
        int[] inds = {R.id.indNavHome, R.id.indNavCart, R.id.indNavOrders};
        for (int i = 0; i < tabs.length; i++) {
            View tab = activity.findViewById(tabs[i]);
            if (tab == null) continue;
            tab.setSelected(i == active);
            View ind = activity.findViewById(inds[i]);
            if (ind != null) ind.setVisibility(i == active ? View.VISIBLE : View.GONE);
            final int idx = i;
            tab.setOnClickListener(v -> {
                if (idx == active) return; // 当前页，无操作
                if (onTabSelected != null) onTabSelected.accept(idx);
            });
        }
    }
}
