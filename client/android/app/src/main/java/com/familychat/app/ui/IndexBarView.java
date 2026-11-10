package com.familychat.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.familychat.app.R;

/**
 * QQ 联系人页右侧字母索引条：A-Z + # 竖排，触摸/滑动定位字母。
 * 通过 {@link OnLetterChangedListener} 通知外部（滚动列表 + 显示居中字母气泡）。
 */
public class IndexBarView extends View {

    public static final String[] LETTERS = {
            "A","B","C","D","E","F","G","H","I","J","K","L","M",
            "N","O","P","Q","R","S","T","U","V","W","X","Y","Z","#"};

    public interface OnLetterChangedListener {
        void onLetterChanged(String letter);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnLetterChangedListener listener;
    private int currentIndex = -1;

    public IndexBarView(Context context) { this(context, null); }
    public IndexBarView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public IndexBarView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setOnLetterChangedListener(OnLetterChangedListener l) {
        this.listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        float cellH = h / (float) LETTERS.length;
        // 用主题色资源：白天深灰、黑夜浅灰，保证黑夜模式可读性（原来硬编码 #666666 黑夜下对比度低）
        int textColor = getResources().getColor(R.color.text_secondary, null);
        for (int i = 0; i < LETTERS.length; i++) {
            float y = cellH * i + cellH / 2f;
            paint.setTextSize(sp(10));
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(i == currentIndex ? Color.parseColor("#2196F3") : textColor);
            canvas.drawText(LETTERS[i], getWidth() / 2f, y - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applyGestureExclusionRects();     // 必须在布局完成后设置，否则坐标系无效
    }

    /**
     * 手势导航下，贴屏幕右缘的竖条很容易被系统返回手势抢走（收到 ACTION_CANCEL），
     * 表现就是"滑动/点按字母条没反应"。
     *
     * 这里申请整条高度的手势排除区（宽 = 视图宽度，高 = 视图高度）。
     * 系统对每个 App 的排除区总量有配额，超额会被静默截断——但被截断只是
     * "没生效"，与刻意缩小申请范围的结果相同，所以不做保守申请，直接要满。
     * 兜底还有布局侧的 layout_marginEnd="6dp"：条子整体离开了约 20dp 的
     * 系统手势抢占区，即使这里完全不生效也仍可用。
     */
    private void applyGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;   // API 29+
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int excludeH = h;
        setSystemGestureExclusionRects(
                java.util.Collections.singletonList(new Rect(0, 0, w, excludeH)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float y = event.getY();
        int idx = (int) (y / (getHeight() / (float) LETTERS.length));
        if (idx < 0) idx = 0;
        if (idx >= LETTERS.length) idx = LETTERS.length - 1;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 每次按下都无条件重新定位：currentIndex 可能因上一次手势没收到
                // UP/CANCEL（被系统手势抢走、View 中途 detach 等）而残留旧值，
                // 那样"再点同一个字母"会被去重逻辑吞掉，看起来就是完全没反应。
                currentIndex = idx;
                if (listener != null) listener.onLetterChanged(LETTERS[idx]);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (idx != currentIndex) {          // 去重只保留给滑动
                    currentIndex = idx;
                    if (listener != null) listener.onLetterChanged(LETTERS[idx]);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                currentIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
