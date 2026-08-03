package com.faceunity.nama;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 取景原生 HUD：对焦层 + 顶栏/拍摄/对比（同一 FrameLayout，child order 控层级）。
 * 对焦在最底，按钮在上，无需双 Popup 抢 elevation。
 */
final class PreviewChromeView extends FrameLayout {

    interface Listener {
        void onCaptureTouchDown();

        void onCaptureLongPress();

        void onCaptureTouchUp();

        void onCompareStart();

        void onCompareEnd();

        void onHome();

        void onSwitchCamera();

        void onToggleDualInput(boolean dual);

        void onSelectResolution(String id);

        void onImportMedia();

        void onDebugVisibleChanged(boolean visible);
    }

    private static final long RECORD_MAX_MS = 10_000L;
    private static final long LONG_PRESS_MS = 420L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    /** 最底层：对焦框 + 曝光条 */
    private final FocusHudView focusHud;
    private FuBeautyPanelView beautyPanel;

    private final LinearLayout topBar;
    private final ImageView homeBtn;
    private final TextView dualTab;
    private final TextView singleTab;
    private final LinearLayout ioBar;
    private final ImageView moreBtn;
    private final ImageView buglyBtn;
    private final ImageView switchBtn;

    private final LinearLayout moreMenu;
    private final TextView[] resTabs = new TextView[3];
    private final String[] resIds = {"480", "720", "1080"};
    private TextView importBtn;

    private final LinearLayout debugPanel;
    private final TextView debugText;
    private boolean debugVisible;
    private boolean moreVisible;
    private boolean dualInput = true;
    private String selectedResId = "720";

    private final ImageView compareBtn;
    private final FrameLayout captureBtn;
    private final ImageView captureInner;
    private final CaptureProgressView captureProgress;
    /** 对齐 iOS PreviewChromeView：滤镜名 / 未检测到人脸 */
    private final TextView filterNameTip;
    private final TextView noFaceTip;
    private Runnable filterHideTask;
    private boolean tipsEnabled;

    /** 美颜面板叠层时抬升拍摄/对比（像素） */
    private int bottomChromeInsetPx;
    private int compareBaseBottomMarginPx;
    private int captureBaseBottomMarginPx;

    private Runnable longPressTask;
    private Runnable recordTickTask;
    private boolean longPressFired;
    private boolean recording;
    private long recordStartMs;
    private float recordPercent;

    PreviewChromeView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(true);

        // index 0 = 底层：对焦；后续 addView 的按钮自然盖在上面
        focusHud = new FocusHudView(context);
        addView(focusHud, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams topLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        topLp.gravity = Gravity.TOP;
        topLp.topMargin = dp(6);
        topLp.leftMargin = dp(8);
        topLp.rightMargin = dp(8);
        addView(topBar, topLp);

        homeBtn = iconBtn(context, "home.png", 36);
        topBar.addView(homeBtn, iconLp(36));
        homeBtn.setOnClickListener(v -> {
            hideMoreMenu();
            if (listener != null) {
                listener.onHome();
            }
        });

        LinearLayout io = new LinearLayout(context);
        io.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable ioBg = new GradientDrawable();
        ioBg.setColor(0x66000000);
        ioBg.setCornerRadius(dp(7));
        io.setBackground(ioBg);
        io.setPadding(dp(2), dp(2), dp(2), dp(2));
        io.setClickable(true);
        LinearLayout.LayoutParams ioLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        ioLp.leftMargin = dp(8);
        ioLp.rightMargin = dp(8);
        topBar.addView(io, ioLp);
        ioBar = io;

        dualTab = segTab(context, "双输入", true);
        singleTab = segTab(context, "单输入", false);
        // 禁止子 TextView 抢触摸，整段左右半区可点
        dualTab.setClickable(false);
        dualTab.setFocusable(false);
        singleTab.setClickable(false);
        singleTab.setFocusable(false);
        io.addView(dualTab, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        io.addView(singleTab, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        io.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                float mid = Math.max(1f, v.getWidth()) * 0.5f;
                setDualInput(ev.getX() < mid, true);
            }
            return true;
        });

        moreBtn = iconBtn(context, "more.png", 36);
        topBar.addView(moreBtn, iconLp(36));
        moreBtn.setOnClickListener(v -> toggleMoreMenu());

        buglyBtn = iconBtn(context, "bugly.png", 36);
        LinearLayout.LayoutParams buglyLp = iconLp(36);
        buglyLp.leftMargin = dp(4);
        topBar.addView(buglyBtn, buglyLp);
        buglyBtn.setOnClickListener(v -> setDebugVisible(!debugVisible, true));

        switchBtn = iconBtn(context, "switch_camera.png", 36);
        LinearLayout.LayoutParams swLp = iconLp(36);
        swLp.leftMargin = dp(4);
        topBar.addView(switchBtn, swLp);
        switchBtn.setOnClickListener(v -> {
            hideMoreMenu();
            if (listener != null) {
                listener.onSwitchCamera();
            }
        });

        moreMenu = buildMoreMenu(context);
        moreMenu.setVisibility(GONE);
        LayoutParams menuLp = new LayoutParams(dp(220), LayoutParams.WRAP_CONTENT);
        menuLp.gravity = Gravity.TOP | Gravity.END;
        menuLp.topMargin = dp(52);
        menuLp.rightMargin = dp(10);
        addView(moreMenu, menuLp);

        debugPanel = new LinearLayout(context);
        debugPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable dbgBg = new GradientDrawable();
        dbgBg.setColor(0x99000000);
        dbgBg.setCornerRadius(dp(8));
        debugPanel.setBackground(dbgBg);
        debugPanel.setPadding(dp(8), dp(6), dp(8), dp(6));
        debugPanel.setVisibility(GONE);
        debugText = new TextView(context);
        debugText.setTextColor(0xE6FFFFFF);
        debugText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        debugText.setLineSpacing(dp(2), 1f);
        debugPanel.addView(debugText, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams dbgLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dbgLp.gravity = Gravity.TOP | Gravity.START;
        dbgLp.topMargin = dp(56);
        dbgLp.leftMargin = dp(10);
        addView(debugPanel, dbgLp);

        compareBtn = new ImageView(context);
        Bitmap cmpBmp = loadAssetBitmap(context, "compare.png");
        if (cmpBmp != null) {
            compareBtn.setImageBitmap(cmpBmp);
            compareBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            compareBtn.setBackgroundColor(Color.TRANSPARENT);
        } else {
            GradientDrawable cmpBg = new GradientDrawable();
            cmpBg.setColor(0x66000000);
            cmpBg.setCornerRadius(dp(22));
            compareBtn.setBackground(cmpBg);
        }
        LayoutParams cmpLp = new LayoutParams(dp(44), dp(44));
        cmpLp.gravity = Gravity.BOTTOM | Gravity.START;
        cmpLp.leftMargin = dp(24);
        compareBaseBottomMarginPx = dp(18);
        cmpLp.bottomMargin = compareBaseBottomMarginPx;
        addView(compareBtn, cmpLp);
        compareBtn.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                compareBtn.setAlpha(0.55f);
                if (listener != null) {
                    listener.onCompareStart();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                compareBtn.setAlpha(1f);
                if (listener != null) {
                    listener.onCompareEnd();
                }
                return true;
            }
            return true;
        });

        captureBtn = new FrameLayout(context);
        LayoutParams capLp = new LayoutParams(dp(72), dp(72));
        capLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        captureBaseBottomMarginPx = dp(8);
        capLp.bottomMargin = captureBaseBottomMarginPx;
        addView(captureBtn, capLp);

        captureProgress = new CaptureProgressView(context);
        captureBtn.addView(captureProgress, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        captureInner = new ImageView(context);
        Bitmap capBmp = loadAssetBitmap(context, "capture.png");
        if (capBmp != null) {
            captureInner.setImageBitmap(capBmp);
            captureInner.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else {
            GradientDrawable inner = new GradientDrawable();
            inner.setColor(Color.WHITE);
            inner.setCornerRadius(dp(26));
            captureInner.setBackground(inner);
        }
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        innerLp.gravity = Gravity.CENTER;
        captureBtn.addView(captureInner, innerLp);

        captureBtn.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                hideMoreMenu();
                longPressFired = false;
                if (listener != null) {
                    listener.onCaptureTouchDown();
                }
                cancelLongPressTask();
                longPressTask = () -> {
                    longPressFired = true;
                    startRecordProgress();
                    if (listener != null) {
                        listener.onCaptureLongPress();
                    }
                };
                main.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                cancelLongPressTask();
                stopRecordProgress();
                if (listener != null) {
                    listener.onCaptureTouchUp();
                }
                return true;
            }
            return true;
        });

        noFaceTip = new TextView(context);
        noFaceTip.setText("未检测到人脸");
        noFaceTip.setTextColor(Color.WHITE);
        noFaceTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        noFaceTip.setGravity(Gravity.CENTER);
        noFaceTip.setShadowLayer(4f, 0f, 1f, 0x8C000000);
        noFaceTip.setVisibility(GONE);
        LayoutParams noFaceLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        noFaceLp.gravity = Gravity.CENTER;
        addView(noFaceTip, noFaceLp);

        filterNameTip = new TextView(context);
        filterNameTip.setTextColor(Color.WHITE);
        filterNameTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        filterNameTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        filterNameTip.setGravity(Gravity.CENTER);
        filterNameTip.setShadowLayer(4f, 0f, 1f, 0x8C000000);
        filterNameTip.setVisibility(GONE);
        LayoutParams filterLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        filterLp.gravity = Gravity.CENTER;
        addView(filterNameTip, filterLp);
    }

    void setTipsEnabled(boolean enabled) {
        tipsEnabled = enabled;
        if (!enabled) {
            noFaceTip.setVisibility(GONE);
            filterNameTip.setVisibility(GONE);
            if (filterHideTask != null) {
                main.removeCallbacks(filterHideTask);
                filterHideTask = null;
            }
        }
    }

    void setNoFaceVisible(boolean visible) {
        if (!tipsEnabled) {
            noFaceTip.setVisibility(GONE);
            return;
        }
        noFaceTip.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            bringChildToFront(noFaceTip);
        }
        layoutPreviewTips();
    }

    void showFilterNameTip(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        filterNameTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        filterNameTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        filterNameTip.setText(name);
        filterNameTip.setVisibility(VISIBLE);
        filterNameTip.setAlpha(0f);
        filterNameTip.animate().alpha(1f).setDuration(120).start();
        bringChildToFront(filterNameTip);
        layoutPreviewTips();
        if (filterHideTask != null) {
            main.removeCallbacks(filterHideTask);
        }
        filterHideTask = () -> {
            filterNameTip.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                filterNameTip.setVisibility(GONE);
                filterNameTip.setAlpha(1f);
            }).start();
        };
        main.postDelayed(filterHideTask, 1000L);
    }

    /** 机型限制提示：半透明黑底卡片，位于预览区中部偏上 */
    void showPerfLimitTip(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        filterNameTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        filterNameTip.setTypeface(android.graphics.Typeface.DEFAULT);
        filterNameTip.setText(message);
        filterNameTip.setVisibility(VISIBLE);
        filterNameTip.setAlpha(0f);
        filterNameTip.animate().alpha(1f).setDuration(120).start();
        bringChildToFront(filterNameTip);
        layoutPreviewTips();
        if (filterHideTask != null) {
            main.removeCallbacks(filterHideTask);
        }
        filterHideTask = () -> {
            filterNameTip.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                filterNameTip.setVisibility(GONE);
                filterNameTip.setAlpha(1f);
                filterNameTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
                filterNameTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }).start();
        };
        main.postDelayed(filterHideTask, 2000L);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutPreviewTips();
    }

    private void layoutPreviewTips() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int midY = h / 2;
        if (noFaceTip.getVisibility() == VISIBLE) {
            int nw = noFaceTip.getMeasuredWidth();
            int nh = noFaceTip.getMeasuredHeight();
            if (nw <= 0) {
                nw = dp(200);
            }
            if (nh <= 0) {
                nh = dp(28);
            }
            noFaceTip.layout((w - nw) / 2, midY - nh / 2, (w + nw) / 2, midY + nh / 2);
        }
        if (filterNameTip.getVisibility() == VISIBLE || filterNameTip.getAlpha() < 1f) {
            int fw = filterNameTip.getMeasuredWidth();
            int fh = filterNameTip.getMeasuredHeight();
            if (fw <= 0) {
                fw = dp(240);
            }
            if (fh <= 0) {
                fh = dp(40);
            }
            int filterY = noFaceTip.getVisibility() == VISIBLE
                    ? noFaceTip.getBottom() + dp(16)
                    : (midY - fh / 2);
            filterNameTip.layout((w - fw) / 2, filterY, (w + fw) / 2, filterY + fh);
        }
    }

    private LinearLayout buildMoreMenu(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1A1A1A);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout resRow = new LinearLayout(context);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resRow.setGravity(Gravity.CENTER);
        String[] labels = {"480P", "720P", "1080P"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = new TextView(context);
            tab.setText(labels[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = dp(6);
            }
            resRow.addView(tab, lp);
            resTabs[i] = tab;
            tab.setOnClickListener(v -> selectResolution(resIds[idx], true));
        }
        box.addView(resRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        refreshResTabs();

        importBtn = new TextView(context);
        importBtn.setText("载入图片或视频");
        importBtn.setGravity(Gravity.CENTER);
        importBtn.setTextColor(Color.WHITE);
        importBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable importBg = new GradientDrawable();
        importBg.setColor(0xFF2C2C2C);
        importBg.setCornerRadius(dp(8));
        importBtn.setBackground(importBg);
        importBtn.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        importLp.topMargin = dp(10);
        box.addView(importBtn, importLp);
        importBtn.setOnClickListener(v -> {
            hideMoreMenu();
            if (listener != null) {
                listener.onImportMedia();
            }
        });
        return box;
    }

    private TextView segTab(Context context, String text, boolean active) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        applySegStyle(tv, active);
        return tv;
    }

    private void applySegStyle(TextView tv, boolean active) {
        tv.setTextColor(active ? Color.WHITE : 0xBFFFFFFF);
        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF5EC7FE);
            bg.setCornerRadius(dp(7));
            tv.setBackground(bg);
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private ImageView iconBtn(Context context, String asset, int sizeDp) {
        ImageView iv = new ImageView(context);
        Bitmap bmp = loadAssetBitmap(context, asset);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
        } else {
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0x66FFFFFF);
            ph.setCornerRadius(dp(sizeDp / 2));
            iv.setBackground(ph);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        return iv;
    }

    private LinearLayout.LayoutParams iconLp(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean wasLongPress() {
        return longPressFired;
    }

    void updateStats(String resolution, int fps, int renderTimeMs) {
        String text = "分辨率:" + (resolution != null ? resolution : "-")
                + "\n帧率:" + fps
                + "\nrendertime:" + renderTimeMs;
        debugText.setText(text);
    }

    void setRecording(boolean on) {
        recording = on;
        if (!on) {
            stopRecordProgress();
        }
        captureInner.setAlpha(on ? 0.85f : 1f);
    }

    void setSelectedResolutionId(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        selectedResId = id;
        refreshResTabs();
    }

    void setDualInputState(boolean dual) {
        setDualInput(dual, false);
    }

    void setDebugVisibleState(boolean visible) {
        setDebugVisible(visible, false);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 曝光条在底层 FocusHud：顶栏/按钮不挡时，强制把事件交给对焦层
        if (focusHud != null && focusHud.getVisibility() == VISIBLE
                && focusHud.hitExposureRail(ev.getX(), ev.getY())) {
            return focusHud.dispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    boolean hitInteractive(float x, float y) {
        // 美颜底栏 / 对比须高于对焦：先命中面板再判断其它控件
        if (beautyPanel != null && beautyPanel.getVisibility() == VISIBLE
                && beautyPanel.hitInteractive(x, y)) {
            return true;
        }
        if (hitView(homeBtn, x, y) || hitView(ioBar, x, y)
                || hitView(moreBtn, x, y) || hitView(buglyBtn, x, y) || hitView(switchBtn, x, y)
                || (compareBtn.getVisibility() == VISIBLE && hitView(compareBtn, x, y))
                || hitView(captureBtn, x, y)) {
            return true;
        }
        if (moreVisible && hitView(moreMenu, x, y)) {
            return true;
        }
        if (debugVisible && hitView(debugPanel, x, y)) {
            return true;
        }
        // 曝光条也算可交互，交给底层 FocusHud
        return focusHud.hitExposureRail(x, y);
    }

    /** 美颜面板高度变化时上推拍摄/对比；insetPts 为面板区域高度（含 safe bottom） */
    void setBottomChromeInset(int insetPx, boolean animate) {
        int next = Math.max(0, insetPx);
        if (next == bottomChromeInsetPx) {
            return;
        }
        bottomChromeInsetPx = next;
        Runnable apply = () -> {
            LayoutParams cmpLp = (LayoutParams) compareBtn.getLayoutParams();
            cmpLp.bottomMargin = compareBaseBottomMarginPx + bottomChromeInsetPx;
            compareBtn.setLayoutParams(cmpLp);
            LayoutParams capLp = (LayoutParams) captureBtn.getLayoutParams();
            capLp.bottomMargin = captureBaseBottomMarginPx + bottomChromeInsetPx;
            captureBtn.setLayoutParams(capLp);
        };
        if (animate) {
            animate().setDuration(150).withStartAction(apply).start();
            apply.run();
        } else {
            apply.run();
        }
    }

    void setCompareButtonHidden(boolean hidden) {
        compareBtn.setVisibility(hidden ? GONE : VISIBLE);
    }

    FocusHudView focusHud() {
        return focusHud;
    }

    void showFocusAt(float localX, float localY, int exposure0to100) {
        focusHud.showAt(localX, localY, exposure0to100);
        // 保险：对焦刷新后把按钮重新抬到对焦层之上
        raiseControlsAboveFocus();
    }

    void hideFocusHud() {
        focusHud.hideAll();
    }

    int getFocusExposure() {
        return focusHud.getExposureProgress();
    }

    void setFocusExposure(int value0to100) {
        focusHud.setExposureProgress(value0to100);
    }

    private void raiseControlsAboveFocus() {
        // 对焦层保持底层；美颜面板高于对焦，拍摄/顶栏再高于面板空白区
        if (beautyPanel != null && beautyPanel.getParent() == this) {
            bringChildToFront(beautyPanel);
        }
        bringChildToFront(topBar);
        if (moreVisible) {
            bringChildToFront(moreMenu);
        }
        if (debugVisible) {
            bringChildToFront(debugPanel);
        }
        if (compareBtn.getVisibility() == VISIBLE) {
            bringChildToFront(compareBtn);
        }
        bringChildToFront(captureBtn);
    }

    /** 挂上美颜面板后：面板压对焦，拍摄/顶栏仍可点 */
    void attachBeautyPanel(FuBeautyPanelView panel) {
        beautyPanel = panel;
        raiseBeautyPanelAboveFocus();
    }

    void clearBeautyPanel() {
        if (beautyPanel != null && beautyPanel.getParent() == this) {
            removeView(beautyPanel);
        }
        beautyPanel = null;
    }

    void raiseBeautyPanelAboveFocus() {
        if (beautyPanel == null || beautyPanel.getParent() != this) {
            return;
        }
        bringChildToFront(beautyPanel);
        bringChildToFront(captureBtn);
        bringChildToFront(topBar);
        if (moreVisible) {
            bringChildToFront(moreMenu);
        }
        if (debugVisible) {
            bringChildToFront(debugPanel);
        }
    }

    /** @deprecated 使用 raiseBeautyPanelAboveFocus */
    void raiseControlsAboveBeautyPanel() {
        raiseBeautyPanelAboveFocus();
    }

    private void setDualInput(boolean dual, boolean notify) {
        dualInput = dual;
        applySegStyle(dualTab, dual);
        applySegStyle(singleTab, !dual);
        if (notify && listener != null) {
            listener.onToggleDualInput(dual);
        }
    }

    private void selectResolution(String id, boolean notify) {
        selectedResId = id;
        refreshResTabs();
        hideMoreMenu();
        if (notify && listener != null) {
            listener.onSelectResolution(id);
        }
    }

    private void refreshResTabs() {
        for (int i = 0; i < resTabs.length; i++) {
            boolean on = resIds[i].equals(selectedResId);
            TextView tab = resTabs[i];
            tab.setTextColor(on ? Color.WHITE : 0xE6FFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFF5EC7FE : 0xFF2C2C2C);
            bg.setCornerRadius(dp(8));
            tab.setBackground(bg);
        }
    }

    private void toggleMoreMenu() {
        if (moreVisible) {
            hideMoreMenu();
        } else {
            moreMenu.setVisibility(VISIBLE);
            moreVisible = true;
            bringChildToFront(moreMenu);
        }
    }

    private void hideMoreMenu() {
        moreMenu.setVisibility(GONE);
        moreVisible = false;
    }

    private void setDebugVisible(boolean visible, boolean notify) {
        debugVisible = visible;
        debugPanel.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            bringChildToFront(debugPanel);
        }
        if (notify && listener != null) {
            listener.onDebugVisibleChanged(visible);
        }
    }

    private void startRecordProgress() {
        recording = true;
        recordStartMs = System.currentTimeMillis();
        recordPercent = 0f;
        captureProgress.setPercent(0f);
        cancelRecordTick();
        recordTickTask = new Runnable() {
            @Override
            public void run() {
                if (!recording) {
                    return;
                }
                long elapsed = System.currentTimeMillis() - recordStartMs;
                recordPercent = Math.min(1f, elapsed / (float) RECORD_MAX_MS);
                captureProgress.setPercent(recordPercent);
                if (recordPercent >= 1f) {
                    // 满 10s：交给上层 touchUp 收尾
                    if (listener != null) {
                        listener.onCaptureTouchUp();
                    }
                    stopRecordProgress();
                    return;
                }
                main.postDelayed(this, 50);
            }
        };
        main.post(recordTickTask);
    }

    private void stopRecordProgress() {
        recording = false;
        cancelRecordTick();
        captureProgress.setPercent(0f);
    }

    private void cancelLongPressTask() {
        if (longPressTask != null) {
            main.removeCallbacks(longPressTask);
            longPressTask = null;
        }
    }

    private void cancelRecordTick() {
        if (recordTickTask != null) {
            main.removeCallbacks(recordTickTask);
            recordTickTask = null;
        }
    }

    private boolean hitView(View v, float x, float y) {
        if (v == null || v.getVisibility() != VISIBLE) {
            return false;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        Rect rect = new Rect(0, 0, v.getWidth(), v.getHeight());
        try {
            offsetDescendantRectToMyCoords(v, rect);
        } catch (Throwable ignored) {
            // 非子树时回退本地坐标（可能不准）
            rect.offset(v.getLeft(), v.getTop());
        }
        float pad = dp(6);
        return x >= rect.left - pad
                && x <= rect.right + pad
                && y >= rect.top - pad
                && y <= rect.bottom + pad;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    static Bitmap loadAssetBitmap(Context context, String name) {
        InputStream in = null;
        try {
            ClassLoader cl = PreviewChromeView.class.getClassLoader();
            if (cl != null) {
                in = cl.getResourceAsStream("fu_chrome/" + name);
            }
            if (in == null) {
                in = context.getAssets().open("fu_chrome/" + name);
            }
            return BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 录制 10s 圆环进度（对齐 Demo FUCircleProgressView） */
    private static final class CaptureProgressView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float percent;

        CaptureProgressView(Context context) {
            super(context);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(dp(3));
            track.setColor(0x66FFFFFF);
            prog.setStyle(Paint.Style.STROKE);
            prog.setStrokeWidth(dp(3));
            prog.setStrokeCap(Paint.Cap.ROUND);
            prog.setColor(0xFF5EC7FE);
            // 初始不可见：否则 onDraw 会先画一圈灰色底轨
            percent = 0f;
            setVisibility(INVISIBLE);
        }

        void setPercent(float p) {
            percent = Math.max(0f, Math.min(1f, p));
            setVisibility(percent > 0.001f ? VISIBLE : INVISIBLE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (percent <= 0.001f) {
                return;
            }
            float stroke = dp(3);
            oval.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
            canvas.drawArc(oval, -90, 360, false, track);
            canvas.drawArc(oval, -90, 360f * percent, false, prog);
        }

        private int dp(int v) {
            float d = getResources().getDisplayMetrics().density;
            return (int) (v * d + 0.5f);
        }
    }
}
