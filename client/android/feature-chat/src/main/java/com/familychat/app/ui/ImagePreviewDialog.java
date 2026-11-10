package com.familychat.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

/**
 * Full-screen image preview dialog.
 * Supports pinch-zoom and pan only. No save UI.
 */
public class ImagePreviewDialog {

    private final Context context;
    private final String imageUrl;

    public ImagePreviewDialog(Context context, String imageUrl) {
        this.context = context;
        // 防御性兜底: 若调用方漏拼 token, 这里补上(幂等); 服务器文件端点已强制鉴权
        this.imageUrl = ServerProtocol.withToken(imageUrl,
                PreferencesManager.getInstance(context).getToken());
    }

    public void show() {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ZoomImageView zoomIv = new ZoomImageView(context);
        zoomIv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Glide.with(context).load(imageUrl).fitCenter().into(zoomIv);

        dialog.setContentView(zoomIv);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        dialog.show();
    }

    // ─── Zoomable ImageView ─────────────────────────────────────

    private static class ZoomImageView extends androidx.appcompat.widget.AppCompatImageView {

        private final Matrix matrix = new Matrix();
        private int width, height;
        private float maxScale = 5f, minScale = 1f;

        private int mode = NONE;
        private static final int NONE = 0, DRAG = 1, ZOOM = 2;
        private final PointF start = new PointF();
        private final PointF mid = new PointF();
        private float oldDist;

        private final GestureDetector gd;

        public ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            gd = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    // Dismiss on single tap
                    if (getContext() instanceof android.app.Activity) {
                        ((android.app.Activity) getContext()).onBackPressed();
                    } else if (isAttachedToWindow()) {
                        ((Dialog) getRootView().getParent()).dismiss();
                    }
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            width = w;
            height = h;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            gd.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = DRAG;
                    start.set(ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(ev);
                    if (oldDist > 10f) {
                        mode = ZOOM;
                        midPoint(mid, ev);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG) {
                        float dx = ev.getX() - start.x;
                        float dy = ev.getY() - start.y;
                        float[] vals = new float[9];
                        matrix.getValues(vals);
                        if (vals[Matrix.MSCALE_X] > minScale || hasOverflow()) {
                            matrix.postTranslate(dx, dy);
                        }
                        start.set(ev.getX(), ev.getY());
                    } else if (mode == ZOOM) {
                        float newDist = spacing(ev);
                        if (newDist > 10f) {
                            float scale = newDist / oldDist;
                            float[] vals = new float[9];
                            matrix.getValues(vals);
                            float cur = vals[Matrix.MSCALE_X];
                            float result = cur * scale;
                            if (result < minScale) scale = minScale / cur;
                            if (result > maxScale) scale = maxScale / cur;
                            matrix.postScale(scale, scale, mid.x, mid.y);
                            oldDist = newDist;
                        }
                    }
                    setImageMatrix(matrix);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    mode = DRAG;
                    break;
            }
            return true;
        }

        private float spacing(MotionEvent ev) {
            float x = ev.getX(0) - ev.getX(1);
            float y = ev.getY(0) - ev.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void midPoint(PointF pt, MotionEvent ev) {
            pt.set((ev.getX(0) + ev.getX(1)) / 2, (ev.getY(0) + ev.getY(1)) / 2);
        }

        private boolean hasOverflow() {
            if (getDrawable() == null) return false;
            int dw = getDrawable().getIntrinsicWidth();
            int dh = getDrawable().getIntrinsicHeight();
            return dw > width || dh > height;
        }
    }
}
