package com.familychat.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 谷歌扫一扫风格遮罩（Google Lens 同款）：
 * 全屏相机预览 + 框外半透明深色遮罩 + 圆角取景框（白色细边 + 白色四角）+ 绿色扫描线上下往复。
 * 纯绘制层，放在相机预览之上。
 */
public class ScanLensOverlay extends View {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();

    private final int GREEN = Color.rgb(0, 200, 83);   // Google 绿
    private final int WHITE = Color.WHITE;

    private long lastFrame;
    private float scanY;
    private boolean rising = true;
    private boolean running = false;

    public ScanLensOverlay(Context c) { super(c); init(); }
    public ScanLensOverlay(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        scrimPaint.setColor(Color.argb(175, 0, 0, 0));          // 框外半透明黑遮罩
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));
        borderPaint.setColor(Color.argb(120, 255, 255, 255));   // 白色半透明细边框
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(3));
        cornerPaint.setColor(WHITE);
        laserPaint.setStyle(Paint.Style.STROKE);
        laserPaint.setStrokeWidth(dp(2));
        laserPaint.setColor(GREEN);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR)); // 挖出圆角扫描窗
        lastFrame = System.currentTimeMillis();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrame = System.currentTimeMillis();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) { postInvalidateOnAnimation(); return; }

        float fw = Math.min(w * 0.80f, 640f);   // 正常扫码尺寸：宽占屏 80%，封顶 640
        float fh = fw;                          // 正方形（二维码是方的）
        float left = (w - fw) / 2f;
        float top = (h - fh) / 2f - h * 0.04f;
        float right = left + fw;
        float bottom = top + fh;
        float radius = dp(18);
        frame.set(left, top, right, bottom);

        // 1) 全屏遮罩 + 圆角窗口挖空（露出相机）
        int sc = canvas.saveLayer(0, 0, w, h, null, Canvas.ALL_SAVE_FLAG);
        canvas.drawRect(0, 0, w, h, scrimPaint);
        canvas.drawRoundRect(frame, radius, radius, clearPaint);
        canvas.restoreToCount(sc);

        // 2) 取景框细边框
        canvas.drawRoundRect(frame, radius, radius, borderPaint);

        // 3) 白色四角
        drawCorners(canvas, left, top, right, bottom, radius);

        // 4) 绿色扫描线
        updateScanLine();
        drawLaser(canvas, left, right);

        if (running) postInvalidateOnAnimation();
    }

    private void drawCorners(Canvas c, float l, float t, float r, float b, float rad) {
        float len = dp(22);
        // 左上
        c.drawLine(l, t + rad, l, t + rad + len, cornerPaint);
        c.drawLine(l + rad, t, l + rad + len, t, cornerPaint);
        // 右上
        c.drawLine(r - rad - len, t, r - rad, t, cornerPaint);
        c.drawLine(r, t + rad, r, t + rad + len, cornerPaint);
        // 左下
        c.drawLine(l, b - rad - len, l, b - rad, cornerPaint);
        c.drawLine(l + rad, b, l + rad + len, b, cornerPaint);
        // 右下
        c.drawLine(r - rad - len, b, r - rad, b, cornerPaint);
        c.drawLine(r, b - rad - len, r, b - rad, cornerPaint);
    }

    private void updateScanLine() {
        long now = System.currentTimeMillis();
        float dt = (now - lastFrame) / 1000f;
        lastFrame = now;
        float step = 120f * dt;                 // dp/s
        if (scanY == 0f) scanY = frame.top + dp(3);
        if (rising) {
            scanY -= step;
            if (scanY <= frame.top + dp(3)) { scanY = frame.top + dp(3); rising = false; }
        } else {
            scanY += step;
            if (scanY >= frame.bottom - dp(3)) { scanY = frame.bottom - dp(3); rising = true; }
        }
    }

    private void drawLaser(Canvas c, float l, float r) {
        float trailH = dp(36);
        float trailTop = Math.max(frame.top, scanY - trailH);
        LinearGradient g = new LinearGradient(0, trailTop, 0, scanY,
                Color.argb(90, 0, 200, 83), Color.argb(0, 0, 200, 83), Shader.TileMode.CLAMP);
        Paint gp = new Paint();
        gp.setShader(g);
        c.drawRect(l, trailTop, r, scanY, gp);

        laserPaint.setShadowLayer(dp(10f), 0, 0, GREEN);
        c.drawLine(l, scanY, r, scanY, laserPaint);
        laserPaint.clearShadowLayer();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
