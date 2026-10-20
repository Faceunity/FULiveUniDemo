package com.faceunity.nama;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 全屏导出进度：半透明遮罩 + 圆环百分比（对齐 Demo 导出 HUD）。
 */
final class FuExportProgressHud extends FrameLayout {

    private final RingView ring;
    private final TextView percentTv;
    private final TextView tipTv;

    FuExportProgressHud(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(0x99000000);

        FrameLayout center = new FrameLayout(context);
        LayoutParams clp = new LayoutParams(dp(120), dp(120));
        clp.gravity = Gravity.CENTER;
        addView(center, clp);

        ring = new RingView(context);
        center.addView(ring, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        percentTv = new TextView(context);
        percentTv.setTextColor(0xFFFFFFFF);
        percentTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        percentTv.setGravity(Gravity.CENTER);
        percentTv.setText("0%");
        center.addView(percentTv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        tipTv = new TextView(context);
        tipTv.setText("导出中");
        tipTv.setTextColor(0xFFFFFFFF);
        tipTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tipTv.setGravity(Gravity.CENTER);
        LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        tlp.topMargin = dp(88);
        addView(tipTv, tlp);

        setProgress(0f);
    }

    void setProgress(float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        ring.setProgress(r);
        percentTv.setText(Math.round(r * 100f) + "%");
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private static final class RingView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float progress;

        RingView(Context context) {
            super(context);
            bgPaint.setStyle(Paint.Style.STROKE);
            bgPaint.setStrokeWidth(dp(4));
            bgPaint.setColor(0x44FFFFFF);
            fgPaint.setStyle(Paint.Style.STROKE);
            fgPaint.setStrokeWidth(dp(4));
            fgPaint.setStrokeCap(Paint.Cap.ROUND);
            fgPaint.setColor(0xFF5EC7FE);
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float pad = dp(10);
            oval.set(pad, pad, getWidth() - pad, getHeight() - pad);
            canvas.drawArc(oval, -90f, 360f, false, bgPaint);
            canvas.drawArc(oval, -90f, 360f * progress, false, fgPaint);
        }

        private float dp(int v) {
            float d = getResources().getDisplayMetrics().density;
            return v * d;
        }
    }
}
