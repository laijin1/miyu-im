package com.familychat.app.ui.voice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Small waveform visualization view for voice message bubbles.
 * Draws 4 animated bars that cycle during playback.
 */
public class WaveformView extends View {

    private static final int BAR_COUNT = 4;
    private static final long ANIM_DURATION_MS = 600;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] barHeights = new float[BAR_COUNT];
    private ValueAnimator animator;
    private int barColor = 0xFF2196F3;
    private float density;
    private boolean isPlaying = false;

    public WaveformView(Context context) {
        super(context);
        init(context);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);

        // Initial static bars
        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] = dp(8) + i * dp(3);
        }
    }

    public void setBarColor(int color) {
        this.barColor = color;
        invalidate();
    }

    public void startAnimation() {
        if (isPlaying) return;
        isPlaying = true;
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, (float)(2 * Math.PI));
            animator.setDuration(ANIM_DURATION_MS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                float phase = (float) animation.getAnimatedValue();
                float maxH = getHeight() * 0.85f;
                float minH = dp(6);
                for (int i = 0; i < BAR_COUNT; i++) {
                    float p = phase + i * (float)(Math.PI / (BAR_COUNT - 1));
                    float h = (float)(Math.sin(p) * 0.5 + 0.5);
                    barHeights[i] = minH + h * (maxH - minH);
                }
                invalidate();
            });
        }
        animator.start();
    }

    public void stopAnimation() {
        isPlaying = false;
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        // Reset to static bars
        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] = dp(8) + i * dp(3);
        }
        invalidate();
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        paint.setColor(barColor);
        float totalW = getWidth();
        float barW = dp(3);
        float gap = (totalW - BAR_COUNT * barW) / (BAR_COUNT + 1);
        float bottom = getHeight();

        for (int i = 0; i < BAR_COUNT; i++) {
            float x = gap + i * (barW + gap);
            float h = Math.min(barHeights[i], getHeight());
            float top = bottom - h;
            canvas.drawRoundRect(new RectF(x, top, x + barW, bottom),
                    barW / 2, barW / 2, paint);
        }
    }

    private float dp(float dp) {
        return dp * density;
    }
}
