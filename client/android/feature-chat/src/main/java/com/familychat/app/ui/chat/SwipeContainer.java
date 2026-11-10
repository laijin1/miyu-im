package com.familychat.app.ui.chat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * FrameLayout that wraps chat+detail views.
 * Intercepts ONLY horizontal swipes for page switching.
 * Lets ALL other touches (buttons, text input, scrolling, long-press) pass through.
 */
public class SwipeContainer extends FrameLayout {

    private View chatView, detailView;
    private int screenWidth;
    private boolean isOpen = false;

    private float downX, downY, lastX;
    private boolean isSwiping = false;
    private ValueAnimator animator;

    public interface Listener { void onPageChanged(boolean isOpen); }
    private Listener listener;

    public SwipeContainer(Context context) {
        super(context);
    }

    public void init(View chat, View detail, int width) {
        this.chatView = chat;
        this.detailView = detail;
        this.screenWidth = width;
        chatView.setTranslationX(0);
        detailView.setTranslationX(screenWidth);
    }

    public void setListener(Listener l) { this.listener = l; }

    // ─── Intercept only horizontal swipes, pass everything else through ───

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            lastX = downX;
            isSwiping = false;
            if (animator != null) animator.cancel();
            return false; // Never intercept on DOWN — children process normally
        }

        if (action == MotionEvent.ACTION_MOVE && !isSwiping) {
            float dx = ev.getX() - downX;
            float dy = Math.abs(ev.getY() - downY);
            float absDx = Math.abs(dx);

            // Only intercept if clearly horizontal and can actually swipe from current position
            boolean canSwipe = isOpen ? (dx > 0) : (dx < 0);
            if (canSwipe && absDx > dp(12) && absDx > dy + dp(10)) {
                isSwiping = true;
                return true; // Intercept! Subsequent events go to onTouchEvent
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_MOVE && isSwiping) {
            float dx = ev.getX() - lastX;
            lastX = ev.getX();

            float chatTx = chatView.getTranslationX() + dx;
            float detailTx = detailView.getTranslationX() + dx;

            // Clamp
            if (isOpen) {
                chatTx = Math.min(0, Math.max(-screenWidth, chatTx));
                detailTx = Math.min(screenWidth, Math.max(0, detailTx));
            } else {
                chatTx = Math.max(-screenWidth, Math.min(0, chatTx));
                detailTx = Math.max(0, Math.min(screenWidth, detailTx));
            }

            chatView.setTranslationX(chatTx);
            detailView.setTranslationX(detailTx);
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (isSwiping) {
                snapAfterRelease();
                isSwiping = false;
            }
            return true;
        }

        return false;
    }

    private void snapAfterRelease() {
        float chatTx = chatView.getTranslationX();
        isOpen = chatTx < -screenWidth * 0.30f;

        float targetChat = isOpen ? -screenWidth : 0;
        float targetDetail = isOpen ? 0 : screenWidth;

        final float fromChat = chatTx;
        final float fromDetail = detailView.getTranslationX();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            chatView.setTranslationX(fromChat + (targetChat - fromChat) * f);
            detailView.setTranslationX(fromDetail + (targetDetail - fromDetail) * f);
        });
        animator.start();

        if (listener != null) listener.onPageChanged(isOpen);
    }

    public void open() {
        if (animator != null) animator.cancel();
        isOpen = true;
        animateTo(-screenWidth, 0);
    }

    public void close() {
        if (animator != null) animator.cancel();
        isOpen = false;
        animateTo(0, screenWidth);
    }

    private void animateTo(float chatTarget, float detailTarget) {
        final float fromChat = chatView.getTranslationX();
        final float fromDetail = detailView.getTranslationX();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            chatView.setTranslationX(fromChat + (chatTarget - fromChat) * f);
            detailView.setTranslationX(fromDetail + (detailTarget - fromDetail) * f);
        });
        animator.start();
    }

    public boolean isOpen() { return isOpen; }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
