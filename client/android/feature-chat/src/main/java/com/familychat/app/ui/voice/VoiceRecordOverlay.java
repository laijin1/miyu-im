package com.familychat.app.ui.voice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Simplified voice recording overlay.
 * Shows waveform + real-time seconds. No confirm panel.
 * Release → callback.onSend, Swipe-up release → callback.onCancel.
 */
public class VoiceRecordOverlay extends FrameLayout {

    private static final String TAG = "VoiceRecordOverlay";

    private static final int COLOR_NORMAL = 0xFF2196F3;
    private static final int COLOR_CANCEL = 0xFFF44336;
    private static final int COLOR_BG = 0xCC000000;

    private static final float CANCEL_THRESHOLD_DP = 80f;
    private static final int WAVE_BAR_COUNT = 7;
    private static final long WAVE_ANIM_DURATION_MS = 900;
    private static final long SHOW_ANIM_DURATION_MS = 250;

    public enum State { RECORDING, CANCEL_READY }
    private State state = State.RECORDING;

    public interface Callback {
        void onSend(String filePath, int durationMs);
        void onCancel();
    }
    private Callback callback;

    private float density;
    private float cancelThresholdPx;

    private float startRawY = 0;
    private float cancelOffset = 0f;
    private boolean isCanceled = false;

    private MediaRecorder recorder;
    private String filePath;
    private long startTimeMs;
    private int durationMs;
    private boolean recording = false;

    private RecordCanvasView canvasView;
    private TextView tvSeconds;  // real-time seconds display

    private ValueAnimator waveAnimator;
    private float[] waveHeights = new float[WAVE_BAR_COUNT];
    private float wavePhase = 0f;

    private Handler handler = new Handler();
    private Runnable secondsUpdater;

    public VoiceRecordOverlay(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;
        cancelThresholdPx = CANCEL_THRESHOLD_DP * density;

        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setBackgroundColor(COLOR_BG);

        // Canvas: waveform + status text
        canvasView = new RecordCanvasView(context);
        addView(canvasView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Real-time seconds display (centered below waveform)
        tvSeconds = new TextView(context);
        tvSeconds.setText("0\"");
        tvSeconds.setTextColor(Color.WHITE);
        tvSeconds.setTextSize(40);
        tvSeconds.setGravity(Gravity.CENTER);
        tvSeconds.setVisibility(GONE);
        LayoutParams secLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.gravity = Gravity.CENTER;
        secLp.topMargin = (int)(60 * density); // below waveform
        addView(tvSeconds, secLp);

        setupWaveAnimation();

        // Start hidden
        setAlpha(0f);
        setScaleX(0.7f);
        setScaleY(0.7f);
    }

    // =================================================================
    //  Public API
    // =================================================================

    public void setCallback(Callback cb) { this.callback = cb; }

    public void start(float touchRawY) {
        Log.d(TAG, "start recording");
        startRawY = touchRawY;
        cancelOffset = 0f;
        isCanceled = false;
        state = State.RECORDING;
        canvasView.setVisibility(VISIBLE);
        tvSeconds.setVisibility(VISIBLE);
        tvSeconds.setText("0\"");
        animateShow();
        startRecordingInternal();
    }

    public void updateSwipe(float currentRawY) {
        if (state == State.CANCEL_READY && isCanceled) return; // already canceled
        float dy = startRawY - currentRawY;
        cancelOffset = Math.max(0, Math.min(1, dy / cancelThresholdPx));
        isCanceled = cancelOffset >= 1f;
        state = isCanceled ? State.CANCEL_READY : State.RECORDING;
        updateSecondsColor();
        canvasView.invalidate();
    }

    public void onRelease() {
        stopRecordingInternal();
        stopSecondsUpdater();
        Log.d(TAG, "onRelease canceled=" + isCanceled + " duration=" + durationMs);

        if (isCanceled) {
            deleteFile(filePath);
            filePath = null;
            if (callback != null) callback.onCancel();
        } else {
            if (callback != null && filePath != null) {
                callback.onSend(filePath, durationMs);
            }
        }
        animateHide(() -> {
            canvasView.setVisibility(VISIBLE);
            tvSeconds.setVisibility(GONE);
            state = State.RECORDING;
        });
    }

    /**
     * Release all resources: stop recording, cancel waveform animation, remove timer
     * callbacks, and drop the callback reference. Called from the host Activity's onDestroy.
     * Idempotent — safe to call even when not recording.
     */
    public void destroy() {
        Log.d(TAG, "destroy overlay");
        boolean wasRecording = recording; // whether a clip was being recorded when destroyed
        stopRecordingInternal();          // idempotent: stops & releases MediaRecorder
        stopSecondsUpdater();             // removes the secondsUpdater callback
        stopWaveAnimation();              // cancels the wave animator
        handler.removeCallbacksAndMessages(null); // clear any remaining callbacks
        animate().cancel();               // cancel any in-flight show/hide animation
        // Only delete an unsent clip; if onRelease already sent it, uploadVoice is reading the
        // file in the background and deleting it here would corrupt that upload.
        if (wasRecording && filePath != null) {
            deleteFile(filePath);
            filePath = null;
        }
        callback = null;                  // drop the reference to the host Activity
    }

    // =================================================================
    //  Animations
    // =================================================================

    private void animateShow() {
        setAlpha(0f);
        setScaleX(0.7f);
        setScaleY(0.7f);
        animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(SHOW_ANIM_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateHide(Runnable onEnd) {
        animate()
                .alpha(0f).scaleX(0.7f).scaleY(0.7f)
                .setDuration(SHOW_ANIM_DURATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(onEnd)
                .start();
    }

    // =================================================================
    //  Waveform Animation
    // =================================================================

    private void setupWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, (float)(2 * Math.PI));
        waveAnimator.setDuration(WAVE_ANIM_DURATION_MS);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(null);
        waveAnimator.addUpdateListener(anim -> {
            wavePhase = (float) anim.getAnimatedValue();
            float maxH = dp(30);
            float minH = dp(6);
            for (int i = 0; i < WAVE_BAR_COUNT; i++) {
                float phase = wavePhase + i * (float)(Math.PI / (WAVE_BAR_COUNT - 1));
                float h = (float)(Math.sin(phase) * 0.5 + 0.5);
                waveHeights[i] = minH + h * (maxH - minH);
            }
            canvasView.invalidate();
        });
    }

    private void startWaveAnimation() {
        if (waveAnimator != null && !waveAnimator.isRunning()) {
            waveAnimator.start();
        }
    }

    private void stopWaveAnimation() {
        if (waveAnimator != null && waveAnimator.isRunning()) {
            waveAnimator.cancel();
        }
        for (int i = 0; i < WAVE_BAR_COUNT; i++) waveHeights[i] = 0f;
    }

    // =================================================================
    //  Recording
    // =================================================================

    private void startRecordingInternal() {
        try {
            if (!hasAudioPermission()) return;
            filePath = getContext().getCacheDir() + "/voice_" + System.currentTimeMillis() + ".mp4";
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(filePath);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.prepare();
            recorder.start();
            recording = true;
            startTimeMs = System.currentTimeMillis();
            startWaveAnimation();
            startSecondsUpdater();
            Log.d(TAG, "recording started: " + filePath);
        } catch (Exception e) {
            Log.e(TAG, "startRecording", e);
            recording = false;
        }
    }

    private void stopRecordingInternal() {
        stopWaveAnimation();
        if (recorder != null && recording) {
            try { recorder.stop(); } catch (Exception e) { Log.e(TAG, "stop", e); }
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            recording = false;
        }
        durationMs = (int)(System.currentTimeMillis() - startTimeMs);
        Log.d(TAG, "recording stopped: " + durationMs + "ms");
    }

    private void startSecondsUpdater() {
        stopSecondsUpdater();
        secondsUpdater = new Runnable() {
            @Override
            public void run() {
                if (!recording) return;
                int sec = (int)((System.currentTimeMillis() - startTimeMs) / 1000);
                if (sec < 1) sec = 1;
                tvSeconds.setText(sec + "\"");
                handler.postDelayed(this, 200);
            }
        };
        handler.postDelayed(secondsUpdater, 200);
    }

    private void stopSecondsUpdater() {
        if (secondsUpdater != null) {
            handler.removeCallbacks(secondsUpdater);
            secondsUpdater = null;
        }
    }

    private void updateSecondsColor() {
        tvSeconds.setTextColor(isCanceled ? COLOR_CANCEL : Color.WHITE);
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            return getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void deleteFile(String path) {
        if (path != null) new File(path).delete();
    }

    private float dp(float dp) { return dp * density; }

    // =================================================================
    //  Canvas View
    // =================================================================

    private class RecordCanvasView extends View {
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float cx, cy;

        public RecordCanvasView(Context context) {
            super(context);
            initPaints();
        }
        public RecordCanvasView(Context context, AttributeSet attrs) {
            super(context, attrs);
            initPaints();
        }

        private void initPaints() {
            wavePaint.setStyle(Paint.Style.FILL);
            wavePaint.setStrokeCap(Paint.Cap.ROUND);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(16 * density);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            cx = w / 2f;
            cy = h / 2f - dp(40);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawWaveform(canvas);
            drawStatusText(canvas);
        }

        private void drawWaveform(Canvas canvas) {
            if (state == State.CANCEL_READY) {
                wavePaint.setAlpha((int)(255 * (1 - cancelOffset)));
            } else {
                wavePaint.setAlpha(255);
            }

            float barW = dp(4);
            float barGap = dp(3);
            float totalW = WAVE_BAR_COUNT * barW + (WAVE_BAR_COUNT - 1) * barGap;
            float startX = cx - totalW / 2;
            float bottom = cy + dp(20);

            for (int i = 0; i < WAVE_BAR_COUNT; i++) {
                float h = waveHeights[i];
                if (h <= 0) continue;

                int color;
                if (cancelOffset > 0) {
                    float t = Math.min(1f, cancelOffset * 2f);
                    int r = (int)(0x21 + (0xF4 - 0x21) * t);
                    int g = (int)(0x96 + (0x43 - 0x96) * t);
                    int b = (int)(0xF3 + (0x36 - 0xF3) * t);
                    color = Color.rgb(r, g, b);
                } else {
                    color = COLOR_NORMAL;
                }
                wavePaint.setColor(color);

                float x = startX + i * (barW + barGap);
                float top = bottom - h;
                canvas.drawRoundRect(new RectF(x, top, x + barW, bottom),
                        barW / 2, barW / 2, wavePaint);
            }
        }

        private void drawStatusText(Canvas canvas) {
            String text = isCanceled ? "松开 取消" : "上滑 取消";
            int textColor = isCanceled ? COLOR_CANCEL : Color.WHITE;
            textPaint.setColor(textColor);
            float textY = cy + dp(50);
            canvas.drawText(text, cx, textY, textPaint);
            textPaint.setTextSize(12 * density);
            canvas.drawText("⌃", cx, textY - dp(20), textPaint);
            textPaint.setTextSize(16 * density);
        }
    }
}
