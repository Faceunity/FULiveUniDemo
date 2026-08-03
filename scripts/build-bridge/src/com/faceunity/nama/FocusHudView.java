package com.faceunity.nama;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 对焦框 + 竖直曝光条（对齐 FULiveDemo）。
 * 作为 PreviewChromeView 的底层 child，与顶栏/拍摄按钮同层用 order 控层级。
 */
final class FocusHudView extends FrameLayout {

    interface OnExposureChangeListener {
        /**
         * @param finalizeLock true=抬手：可锁 AE；false=拖动中：只改 EV，不反复 lock/unlock
         */
        void onExposureChange(int value0to100, boolean finalizeLock);
    }

    interface OnTapListener {
        void onTap(float localPxX, float localPxY);
    }

    interface OnAutoHideListener {
        void onAutoHide();
    }

    private static final long HIDE_DELAY_MS = 1300L;
    private static final int ACCENT = 0xFF869DFF;

    private final CrosshairView crosshair;
    private final ExposureRailView exposureRail;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable hideTask;
    private OnExposureChangeListener exposureListener;
    private OnTapListener tapListener;
    private OnAutoHideListener autoHideListener;
    private int exposureProgress = 50;
    private int lastParentW;
    private int lastParentH;
    private long lastExposureEmitMs;

    FocusHudView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(true);

        crosshair = new CrosshairView(context);
        crosshair.setVisibility(GONE);
        addView(crosshair, new LayoutParams(dp(70), dp(70)));

        exposureRail = new ExposureRailView(context);
        exposureRail.setVisibility(GONE);
        // 对齐 Demo FULightingView：曝光条固定右侧偏上，不跟焦点移动
        LayoutParams railLp = new LayoutParams(dp(44), dp(220));
        railLp.gravity = Gravity.TOP | Gravity.END;
        railLp.rightMargin = dp(12);
        railLp.topMargin = dp(120);
        addView(exposureRail, railLp);
    }

    void setOnExposureChangeListener(OnExposureChangeListener listener) {
        exposureListener = listener;
    }

    void setOnTapListener(OnTapListener listener) {
        tapListener = listener;
    }

    void setOnAutoHideListener(OnAutoHideListener listener) {
        autoHideListener = listener;
    }

    void showAt(float localX, float localY, int exposure0to100) {
        int cx = Math.round(localX);
        int cy = Math.round(localY);
        int crossSize = dp(70);
        LayoutParams lp = (LayoutParams) crosshair.getLayoutParams();
        lp.width = crossSize;
        lp.height = crossSize;
        lp.leftMargin = Math.max(0, cx - crossSize / 2);
        lp.topMargin = Math.max(0, cy - crossSize / 2);
        lp.gravity = Gravity.TOP | Gravity.START;
        crosshair.setLayoutParams(lp);
        crosshair.setVisibility(VISIBLE);
        crosshair.setScaleX(1.25f);
        crosshair.setScaleY(1.25f);
        crosshair.animate().scaleX(1f).scaleY(1f).setDuration(280).start();

        setExposureProgress(exposure0to100);
        layoutExposureRailFixed();
        exposureRail.setVisibility(VISIBLE);

        scheduleHide();
        requestLayout();
        invalidate();
    }

    /** Demo：曝光条中心约 (width-20, height/2-60)，固定右侧偏上。 */
    private void layoutExposureRailFixed() {
        int railW = dp(44);
        int railH = dp(220);
        int parentW = Math.max(getWidth(), Math.max(getMeasuredWidth(), lastParentW));
        int parentH = Math.max(getHeight(), Math.max(getMeasuredHeight(), lastParentH));
        LayoutParams railLp = (LayoutParams) exposureRail.getLayoutParams();
        railLp.width = railW;
        railLp.height = railH;
        railLp.gravity = Gravity.TOP | Gravity.END;
        railLp.rightMargin = dp(12);
        railLp.leftMargin = 0;
        // height/2 - 60 - railH/2 ≈ 右侧略偏上
        int top = parentH > 0
                ? Math.max(dp(72), parentH / 2 - dp(60) - railH / 2)
                : dp(120);
        railLp.topMargin = top;
        if (parentW > 0) {
            // END gravity 已贴右；显式 left 仅作兜底量测
            railLp.leftMargin = Math.max(0, parentW - railW - dp(12));
            railLp.gravity = Gravity.TOP | Gravity.START;
        }
        exposureRail.setLayoutParams(railLp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            lastParentW = w;
        }
        if (h > 0) {
            lastParentH = h;
        }
        if (exposureRail.getVisibility() == VISIBLE) {
            layoutExposureRailFixed();
        }
    }

    void setExposureProgress(int value0to100) {
        exposureProgress = Math.max(0, Math.min(100, value0to100));
        exposureRail.invalidate();
    }

    int getExposureProgress() {
        return exposureProgress;
    }

    void hideAll() {
        crosshair.animate().cancel();
        crosshair.setVisibility(GONE);
        exposureRail.setVisibility(GONE);
        if (hideTask != null) {
            main.removeCallbacks(hideTask);
            hideTask = null;
        }
    }

    boolean isChromeVisible() {
        return crosshair.getVisibility() == VISIBLE || exposureRail.getVisibility() == VISIBLE;
    }

    /** 曝光条命中区域（含触摸容差），用于 PopupWindow 决定是否拦截。 */
    boolean hitExposureRail(float x, float y) {
        if (exposureRail.getVisibility() != VISIBLE) {
            return false;
        }
        if (exposureRail.getWidth() <= 0 || exposureRail.getHeight() <= 0) {
            return false;
        }
        android.graphics.Rect rect = new android.graphics.Rect(
                0, 0, exposureRail.getWidth(), exposureRail.getHeight());
        try {
            offsetDescendantRectToMyCoords(exposureRail, rect);
        } catch (Throwable ignored) {
            rect.offset(exposureRail.getLeft(), exposureRail.getTop());
        }
        float pad = dp(12);
        return x >= rect.left - pad
                && x <= rect.right + pad
                && y >= rect.top - pad
                && y <= rect.bottom + pad;
    }

    private void scheduleHide() {
        if (hideTask != null) {
            main.removeCallbacks(hideTask);
        }
        hideTask = () -> {
            hideAll();
            if (autoHideListener != null) {
                autoHideListener.onAutoHide();
            }
        };
        main.postDelayed(hideTask, HIDE_DELAY_MS);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (hitExposureRail(event.getX(), event.getY())) {
            float x = event.getX() - exposureRail.getLeft();
            float y = event.getY() - exposureRail.getTop();
            return exposureRail.handleTouch(event.getActionMasked(), x, y);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && tapListener != null) {
            tapListener.onTap(event.getX(), event.getY());
            return true;
        }
        return false;
    }

    private final class ExposureRailView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);

        ExposureRailView(Context context) {
            super(context);
            track.setColor(0xFFFFFFFF);
            track.setStrokeWidth(dp(4));
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
            mid.setColor(0xFFFFFFFF);
            mid.setStyle(Paint.Style.FILL);
            thumb.setColor(0xFFFFFFFF);
            thumb.setStyle(Paint.Style.FILL);
            icon.setColor(0xFFFFFFFF);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(1.5f));
            setClickable(true);
        }

        private int dp(float v) {
            float d = getResources().getDisplayMetrics().density;
            return (int) (v * d + 0.5f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float sunCy = dp(20);
            float moonCy = h - dp(20);
            float sunR = dp(10);
            float moonR = dp(6);
            float top = sunCy + sunR + dp(20);
            float bottom = moonCy - moonR - dp(20);
            canvas.drawLine(cx, top, cx, bottom, track);
            float midY = (top + bottom) / 2f;
            canvas.drawRoundRect(cx - dp(1), midY - dp(6), cx + dp(1), midY + dp(6), dp(1), dp(1), mid);

            float ratio = exposureProgress / 100f;
            float y = bottom - (bottom - top) * ratio;
            canvas.drawCircle(cx, y, dp(7), thumb);

            drawSun(canvas, cx, sunCy);
            drawMoon(canvas, cx, moonCy);
        }

        private void drawSun(Canvas canvas, float cx, float cy) {
            icon.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(cx, cy, dp(5), icon);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                float x0 = cx + (float) Math.cos(a) * dp(7);
                float y0 = cy + (float) Math.sin(a) * dp(7);
                float x1 = cx + (float) Math.cos(a) * dp(10);
                float y1 = cy + (float) Math.sin(a) * dp(10);
                canvas.drawLine(x0, y0, x1, y1, icon);
            }
        }

        private void drawMoon(Canvas canvas, float cx, float cy) {
            icon.setStyle(Paint.Style.STROKE);
            Path moon = new Path();
            moon.addCircle(cx, cy, dp(6), Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + dp(3), cy - dp(1), dp(5), Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            icon.setStyle(Paint.Style.FILL);
            canvas.drawPath(moon, icon);
        }

        boolean handleTouch(int action, float x, float y) {
            float sunCy = dp(20);
            float moonCy = getHeight() - dp(20);
            float top = sunCy + dp(10) + dp(20);
            float bottom = moonCy - dp(6) - dp(20);
            float span = Math.max(1f, bottom - top);
            float ratio = 1f - Math.max(0f, Math.min(1f, (y - top) / span));
            int progress = Math.round(ratio * 100f);
            FocusHudView.this.setExposureProgress(progress);
            scheduleHide();
            if (exposureListener == null) {
                return true;
            }
            // 拖动中实时改 EV（节流）；抬手再提交一次并锁定 AE
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastExposureEmitMs >= 48L) {
                    lastExposureEmitMs = now;
                    exposureListener.onExposureChange(progress, false);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastExposureEmitMs = 0L;
                exposureListener.onExposureChange(progress, true);
            }
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return handleTouch(event.getActionMasked(), event.getX(), event.getY());
        }
    }

    /** FULiveDemo render_adjust：四角括号 + 中心十字 */
    private static final class CrosshairView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CrosshairView(Context context) {
            super(context);
            paint.setColor(ACCENT);
            paint.setStrokeWidth(3.2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float inset = Math.min(w, h) * 0.08f;
            float arm = Math.min(w, h) * 0.22f;
            float left = inset;
            float top = inset;
            float right = w - inset;
            float bottom = h - inset;

            // 四角
            canvas.drawLine(left, top, left + arm, top, paint);
            canvas.drawLine(left, top, left, top + arm, paint);
            canvas.drawLine(right, top, right - arm, top, paint);
            canvas.drawLine(right, top, right, top + arm, paint);
            canvas.drawLine(left, bottom, left + arm, bottom, paint);
            canvas.drawLine(left, bottom, left, bottom - arm, paint);
            canvas.drawLine(right, bottom, right - arm, bottom, paint);
            canvas.drawLine(right, bottom, right, bottom - arm, paint);

            float cx = w / 2f;
            float cy = h / 2f;
            float cross = Math.min(w, h) * 0.18f;
            canvas.drawLine(cx - cross, cy, cx + cross, cy, paint);
            canvas.drawLine(cx, cy - cross, cx, cy + cross, paint);
        }
    }
}
