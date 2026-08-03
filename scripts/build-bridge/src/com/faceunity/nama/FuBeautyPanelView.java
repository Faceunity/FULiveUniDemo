package com.faceunity.nama;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对齐 FULiveDemo / iOS FuBeautyPanelView：底栏 Tab + 半透明功能区 + 滑杆，叠在取景上。
 */
final class FuBeautyPanelView extends FrameLayout {
    private static final int BRAND = 0xFF5EC7FE;
    private static final int CATEGORY_DP = 49;
    private static final int FUNCTION_DP = 141;
    private static final int SLIDER_DP = 30;
    /** 对齐 iOS：功能图标 44×44，间距 22 */
    private static final int ICON_CELL_W_DP = 44;
    private static final int ICON_GAP_DP = 22;
    private static final int TRACK_GRAY = 0x40FFFFFF;

    interface Listener {
        void onPanelHeightChanged(int heightPx);
        void onSelectTab(String tabId, boolean expanded);
        void onSelectEffect(String key);
        void onSliderChanged(String key, double sdkValue);
        void onSelectFilter(String filterId, String filterKey);
        void onWhiteningMode(String mode);
        void onRecoverTab(String tabId);
        void onCompareStart();
        void onCompareEnd();
        void onSave();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imgExec = Executors.newFixedThreadPool(2);
    private Listener listener;

    private final LinearLayout categoryBar;
    private final TextView[] tabBtns = new TextView[3];
    private final String[] tabIds = {"skin", "shape", "filter"};
    private final String[] tabLabels = {"美肤", "美型", "滤镜"};

    private final FrameLayout functionArea;
    private final SeekBar slider;
    private final View midLine;
    /** 双向滑杆灰色底轨（对齐 iOS bipolarBgTrack） */
    private final View bipolarBgTrack;
    private final View bipolarTrack;
    private final FrameLayout tipBubble;
    private final ImageView tipBubbleBg;
    private final TextView tipLabel;
    private final LinearLayout recoverBtn;
    private final ImageView recoverIcon;
    private final TextView recoverLabel;
    private final View recoverDivider;
    private final HorizontalScrollView iconScroll;
    private final LinearLayout iconRow;
    private final LinearLayout whiteningSeg;
    private final TextView whiteningGlobalBtn;
    private final TextView whiteningSkinBtn;
    /** 44dp 点击区；内部 compareIcon 放大绘制 */
    private final FrameLayout compareBtn;
    private final ImageView compareIcon;
    private final ImageView saveBtn;

    private String mode = "camera"; // camera | image | video
    private String activeTab = "";
    private boolean expanded = false;
    /** 0=收起 1=展开；动画插值，避免 Popup/保存钮瞬移跳动 */
    private float expandProgress = 0f;
    private ValueAnimator expandAnim;
    private String selectedEffectKey = "";
    /** 美肤/美型各自记住选中项，切 Tab 时滑杆跟着切（对齐 Demo） */
    private String selectedSkinKey = "";
    private String selectedShapeKey = "";
    private String selectedFilterId = "ziran1";
    private String whiteningMode = "global";
    /** 对齐 FULiveDemo 1=Low 2=High 3=VeryHigh 4=Excellent */
    private int devicePerfLevel = 1;
    private float sliderMin = 0f;
    private float sliderMax = 100f;
    private boolean bidirectional;

    private JSONArray skinEffects = new JSONArray();
    private JSONArray shapeEffects = new JSONArray();
    private JSONArray filters = new JSONArray();
    private final Map<String, JSONObject> effectMeta = new HashMap<>();
    private final Map<String, Double> values = new HashMap<>();
    private final List<View> iconCells = new ArrayList<>();
    private final Map<String, Bitmap> iconBmpCache = new HashMap<>();
    /** 各 Tab 独立横向滚动位置（对齐 Demo 三套 CollectionView） */
    private final Map<String, Integer> tabScrollX = new HashMap<>();

    FuBeautyPanelView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(true);
        setClipChildren(false);
        setClipToPadding(false);

        categoryBar = new LinearLayout(context);
        categoryBar.setOrientation(LinearLayout.HORIZONTAL);
        categoryBar.setBackgroundColor(0xFF050F14);
        categoryBar.setGravity(Gravity.CENTER);
        addView(categoryBar);

        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(context);
            t.setText(tabLabels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xB3FFFFFF);
            final String id = tabIds[i];
            t.setOnClickListener(v -> onTab(id));
            tabBtns[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(CATEGORY_DP), 1f);
            categoryBar.addView(t, lp);
        }

        // 子控件由 layoutFunctionChildren 手排；禁止 FrameLayout 默认把子 View 拉满导致只看见滑杆
        functionArea = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                // no-op
            }
        };
        functionArea.setBackgroundColor(0xCC16181A);
        functionArea.setClipChildren(true);
        functionArea.setClipToPadding(true);
        functionArea.setVisibility(GONE);
        addView(functionArea);

        tipBubble = new FrameLayout(context);
        tipBubble.setVisibility(GONE);
        addView(tipBubble);

        tipBubbleBg = new ImageView(context);
        Bitmap tipBmp = PreviewChromeView.loadAssetBitmap(context, "slider_tip_background.png");
        if (tipBmp != null) {
            tipBubbleBg.setImageBitmap(tipBmp);
            tipBubbleBg.setScaleType(ImageView.ScaleType.FIT_XY);
        }
        tipBubble.addView(tipBubbleBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tipLabel = new TextView(context);
        tipLabel.setTextColor(Color.WHITE);
        tipLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tipLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tipLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tipTextLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tipTextLp.bottomMargin = dp(4);
        tipBubble.addView(tipLabel, tipTextLp);

        midLine = new View(context);
        midLine.setBackgroundColor(0xD9FFFFFF);
        midLine.setVisibility(GONE);
        functionArea.addView(midLine);

        bipolarBgTrack = new View(context);
        GradientDrawable bgTrack = new GradientDrawable();
        bgTrack.setColor(TRACK_GRAY);
        bgTrack.setCornerRadius(dp(2));
        bipolarBgTrack.setBackground(bgTrack);
        bipolarBgTrack.setVisibility(GONE);
        functionArea.addView(bipolarBgTrack);

        // 双向：从中点向两侧延伸的蓝色填充（对齐 FUSlider trackView）
        bipolarTrack = new View(context);
        GradientDrawable biBg = new GradientDrawable();
        biBg.setColor(BRAND);
        biBg.setCornerRadius(dp(2));
        bipolarTrack.setBackground(biBg);
        bipolarTrack.setVisibility(GONE);
        functionArea.addView(bipolarTrack);

        slider = new SeekBar(context);
        slider.setMax(100);
        try {
            GradientDrawable thumbDr = new GradientDrawable();
            thumbDr.setShape(GradientDrawable.OVAL);
            thumbDr.setColor(Color.WHITE);
            thumbDr.setSize(dp(16), dp(16));
            slider.setThumb(thumbDr);
            slider.setSplitTrack(false);
            applySliderTrackColors(false);
        } catch (Throwable ignored) {
        }
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                layoutBipolarTrack();
                if (!fromUser) {
                    return;
                }
                tipBubble.setVisibility(VISIBLE);
                layoutSliderTip();
                refreshTip();
                emitSlider();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                tipBubble.setVisibility(VISIBLE);
                layoutSliderTip();
                refreshTip();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                tipBubble.setVisibility(GONE);
                emitSlider();
                reloadIconStrip();
            }
        });
        functionArea.addView(slider);

        // 对齐 Demo FUSquareButton recover：上图标下文案，默认 α=0.6
        recoverBtn = new LinearLayout(context);
        recoverBtn.setOrientation(LinearLayout.VERTICAL);
        recoverBtn.setGravity(Gravity.CENTER_HORIZONTAL);
        recoverIcon = new ImageView(context);
        Bitmap recoverBmp = PreviewChromeView.loadAssetBitmap(context, "recover.png");
        if (recoverBmp != null) {
            recoverIcon.setImageBitmap(recoverBmp);
            recoverIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else {
            recoverIcon.setBackgroundColor(0x33FFFFFF);
        }
        recoverBtn.addView(recoverIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        recoverLabel = new TextView(context);
        recoverLabel.setText("恢复");
        recoverLabel.setTextColor(0xD9FFFFFF);
        recoverLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        recoverLabel.setGravity(Gravity.CENTER);
        // 与右侧功能图标一致：图标下直接跟文案，无额外 topMargin
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
        recoverBtn.addView(recoverLabel, labLp);
        recoverBtn.setAlpha(0.6f);
        recoverBtn.setEnabled(false);
        recoverBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRecoverTab(activeTab == null || activeTab.isEmpty() ? "skin" : activeTab);
            }
        });
        functionArea.addView(recoverBtn);

        recoverDivider = new View(context);
        recoverDivider.setBackgroundColor(0x33E5E5E5); // Demo RGB(229,229,229) α=0.2
        functionArea.addView(recoverDivider);

        iconScroll = new HorizontalScrollView(context);
        iconScroll.setHorizontalScrollBarEnabled(false);
        iconScroll.setClipChildren(true);
        iconScroll.setClipToPadding(true);
        iconScroll.setOverScrollMode(HorizontalScrollView.OVER_SCROLL_NEVER);
        iconRow = new LinearLayout(context);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        // TOP：与左侧恢复按钮图标顶对齐（CENTER_VERTICAL 会把功能图标整体下沉）
        iconRow.setGravity(Gravity.TOP);
        iconScroll.addView(iconRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        functionArea.addView(iconScroll);

        whiteningSeg = new LinearLayout(context);
        whiteningSeg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBorder = new GradientDrawable();
        segBorder.setColor(Color.TRANSPARENT);
        segBorder.setStroke(dp(1), Color.WHITE);
        segBorder.setCornerRadius(dp(12));
        whiteningSeg.setBackground(segBorder);
        whiteningSeg.setClipChildren(true);
        whiteningSeg.setClipToPadding(true);
        whiteningSeg.setVisibility(GONE);
        functionArea.addView(whiteningSeg);

        whiteningGlobalBtn = segBtn(context, "全局", true);
        whiteningSkinBtn = segBtn(context, "仅皮肤", false);
        whiteningGlobalBtn.setOnClickListener(v -> setWhiteningModeInternal("global", true));
        whiteningSkinBtn.setOnClickListener(v -> {
            if (!canUseSkinWhitening()) {
                showPerfLimitToastForItem(skinWhiteningItem());
                return;
            }
            setWhiteningModeInternal("skin", true);
        });
        whiteningSeg.addView(whiteningGlobalBtn, new LinearLayout.LayoutParams(0, dp(24), 1f));
        whiteningSeg.addView(whiteningSkinBtn, new LinearLayout.LayoutParams(0, dp(24), 1f));

        compareBtn = new FrameLayout(context);
        compareIcon = new ImageView(context);
        Bitmap cmp = PreviewChromeView.loadAssetBitmap(context, "compare.png");
        if (cmp != null) {
            compareIcon.setImageBitmap(cmp);
            compareIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x66000000);
            bg.setCornerRadius(dp(22));
            compareIcon.setBackground(bg);
        }
        compareBtn.addView(compareIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        compareBtn.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                compareIcon.setAlpha(0.55f);
                if (listener != null) {
                    listener.onCompareStart();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                compareIcon.setAlpha(1f);
                if (listener != null) {
                    listener.onCompareEnd();
                }
                return true;
            }
            return true;
        });
        addView(compareBtn);

        saveBtn = new ImageView(context);
        GradientDrawable saveCircle = new GradientDrawable();
        saveCircle.setColor(Color.WHITE);
        saveCircle.setCornerRadius(dp(29));
        saveBtn.setBackground(saveCircle);
        Bitmap saveBmp = PreviewChromeView.loadAssetBitmap(context, "download.png");
        if (saveBmp != null) {
            saveBtn.setImageBitmap(saveBmp);
            saveBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            saveBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        }
        saveBtn.setVisibility(GONE);
        saveBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSave();
            }
        });
        addView(saveBtn);
    }

    private TextView segBtn(Context context, String text, boolean on) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        applyWhiteningBtnStyle(t, on);
        return t;
    }

    private void applyWhiteningBtnStyle(TextView t, boolean on) {
        float r = dp(12);
        GradientDrawable bg = new GradientDrawable();
        if (on) {
            t.setTextColor(0xFF2C2E30);
            bg.setColor(Color.WHITE);
            if (t == whiteningGlobalBtn) {
                bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            } else {
                bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            }
            t.setBackground(bg);
        } else {
            t.setTextColor(0x73FFFFFF);
            t.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setMode(String mode) {
        this.mode = mode == null ? "camera" : mode;
        boolean media = "image".equals(this.mode) || "video".equals(this.mode);
        saveBtn.setVisibility(media ? VISIBLE : GONE);
        requestLayout();
        notifyHeight();
    }

    void applyConfig(JSONObject config) {
        if (config == null) {
            return;
        }
        skinEffects = config.getJSONArray("skin");
        if (skinEffects == null) {
            skinEffects = new JSONArray();
        }
        shapeEffects = config.getJSONArray("shape");
        if (shapeEffects == null) {
            shapeEffects = new JSONArray();
        }
        filters = config.getJSONArray("filters");
        if (filters == null) {
            filters = new JSONArray();
        }
        effectMeta.clear();
        indexMeta(skinEffects);
        indexMeta(shapeEffects);
        JSONObject vals = config.getJSONObject("values");
        if (vals != null) {
            for (String k : vals.keySet()) {
                values.put(k, vals.getDoubleValue(k));
            }
        }
        if (config.containsKey("filterId")) {
            selectedFilterId = config.getString("filterId");
        }
        devicePerfLevel = MediaFuSetup.getDevicePerformanceLevel();
        if (config.containsKey("whiteningMode")) {
            whiteningMode = config.getString("whiteningMode");
        }
        if (devicePerfLevel < 4 && "skin".equals(whiteningMode)) {
            whiteningMode = "global";
        }
        if (config.containsKey("selectedKey")) {
            selectedEffectKey = config.getString("selectedKey");
        } else {
            selectedEffectKey = firstEnabledKeyOf(skinEffects);
        }
        selectedEffectKey = ensureEnabledSelection(skinEffects, selectedEffectKey);
        if (selectedEffectKey != null && !selectedEffectKey.isEmpty()) {
            selectedSkinKey = selectedEffectKey;
        } else {
            selectedSkinKey = firstEnabledKeyOf(skinEffects);
            selectedEffectKey = selectedSkinKey;
        }
        selectedShapeKey = ensureEnabledSelection(shapeEffects, firstEnabledKeyOf(shapeEffects));
        if (config.containsKey("mode")) {
            setMode(config.getString("mode"));
        }
        // 对齐 Demo：默认收起，点 Tab 才展开
        cancelExpandAnim();
        expanded = false;
        expandProgress = 0f;
        activeTab = "";
        functionArea.setVisibility(GONE);
        functionArea.setTranslationY(0f);
        functionArea.setAlpha(1f);
        refreshTabs();
        reloadIconStrip();
        syncSliderToSelection();
        refreshWhiteningSeg();
        requestLayout();
        notifyHeight();
    }

    void updateValues(JSONObject vals) {
        if (vals == null) {
            return;
        }
        for (String k : vals.keySet()) {
            values.put(k, vals.getDoubleValue(k));
        }
        reloadIconStrip();
        syncSliderToSelection();
    }

    /** @return 参数当前值；未设置返回 -1 */
    double peekParamValue(String key) {
        if (key == null || !values.containsKey(key)) {
            return -1;
        }
        Double v = values.get(key);
        return v != null ? v : -1;
    }

    /** 当前滑杆 UI 值对应的 SDK 强度；未设置返回 -1 */
    double peekSdkParamValue(String key) {
        if (key == null || key.isEmpty()) {
            return -1;
        }
        if ("filter_level".equals(key)) {
            double ui = values.containsKey("filter_level") ? values.get("filter_level") : 40;
            return Math.round(ui) / 100.0;
        }
        JSONObject meta = effectMeta.get(key);
        if (meta == null) {
            // 兼容 color_level / color_level_mode2 互查
            if ("color_level".equals(key)) {
                meta = effectMeta.get("color_level_mode2");
            } else if ("color_level_mode2".equals(key)) {
                meta = effectMeta.get("color_level");
            }
        }
        if (meta == null) {
            return -1;
        }
        double ui = values.containsKey(key)
                ? values.get(key)
                : (values.containsKey("color_level_mode2")
                ? values.get("color_level_mode2")
                : (values.containsKey("color_level") ? values.get("color_level") : -1));
        if (ui < 0 && meta.containsKey("defaultSlider")) {
            ui = meta.getDoubleValue("defaultSlider");
        }
        if (ui < 0) {
            return -1;
        }
        return sdkValueFromSlider(ui, meta);
    }

    /**
     * 对齐 iOS recoverTabDefaults：把当前 Tab 参数写回默认并回调 listener 落 SDK。
     */
    void recoverTabDefaults(String tabId) {
        String tab = tabId == null || tabId.isEmpty()
                ? (activeTab == null || activeTab.isEmpty() ? "skin" : activeTab)
                : tabId;
        if ("filter".equals(tab)) {
            return;
        }
        JSONArray list = "shape".equals(tab) ? shapeEffects : skinEffects;
        if (list == null || list.isEmpty()) {
            return;
        }
        activeTab = tab;
        expanded = true;
        expandProgress = 1f;
        functionArea.setVisibility(VISIBLE);
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String key = item.getString("key");
            if (key == null || key.isEmpty() || item.getBooleanValue("unimplemented")
                    || isEffectItemDisabled(item)) {
                continue;
            }
            double defSlider = item.containsKey("defaultSlider")
                    ? item.getDoubleValue("defaultSlider")
                    : (item.containsKey("default") ? item.getDoubleValue("default") : 0);
            values.put(key, defSlider);
            double sdk = sdkValueFromSlider(defSlider, item);
            if (listener != null) {
                listener.onSliderChanged(key, sdk);
            }
        }
        if ("skin".equals(tab)) {
            whiteningMode = "global";
            refreshWhiteningSeg();
            if (listener != null) {
                listener.onWhiteningMode("global");
            }
        }
        String firstKey = null;
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null || item.getBooleanValue("unimplemented") || isEffectItemDisabled(item)) {
                continue;
            }
            String key = item.getString("key");
            if (key != null && !key.isEmpty()) {
                firstKey = key;
                break;
            }
        }
        if (firstKey != null) {
            selectedEffectKey = firstKey;
            if ("skin".equals(tab)) {
                selectedSkinKey = firstKey;
            } else {
                selectedShapeKey = firstKey;
            }
        }
        refreshTabs();
        reloadIconStrip();
        syncSliderToSelection();
        refreshRecoverEnabled();
        requestLayout();
        notifyHeight();
    }

    void setSelectedFilterId(String filterId) {
        selectedFilterId = filterId == null ? "origin" : filterId;
        if ("filter".equals(activeTab)) {
            reloadIconStrip();
            syncSliderToSelection();
        }
    }

    void setWhiteningMode(String mode) {
        setWhiteningModeInternal(mode, false);
    }

    /**
     * Popup 始终按「展开」高度占位，收起只做内部动画，避免改 Popup 高度导致保存/对比钮跳动。
     */
    int getPreferredPopupHeightPx() {
        int safe = resolveSafeBottom();
        return dp(CATEGORY_DP) + dp(FUNCTION_DP) + safe + dp(100);
    }

    int getCurrentPanelHeightPx() {
        int safe = resolveSafeBottom();
        return dp(CATEGORY_DP) + Math.round(dp(FUNCTION_DP) * expandProgress) + safe;
    }

    boolean hitInteractive(float x, float y) {
        return hitView(categoryBar, x, y)
                || (expandProgress > 0.01f && functionArea.getVisibility() == VISIBLE && hitView(functionArea, x, y))
                || hitView(compareBtn, x, y)
                || (saveBtn.getVisibility() == VISIBLE && hitView(saveBtn, x, y));
    }

    private void indexMeta(JSONArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if (e != null && e.getString("key") != null) {
                effectMeta.put(e.getString("key"), e);
            }
        }
    }

    private void onTab(String tabId) {
        if (tabId.equals(activeTab) && expanded) {
            tabScrollX.put(tabId, iconScroll.getScrollX());
            rememberTabSelection(tabId);
            expanded = false;
            activeTab = "";
            refreshTabs();
            animateExpand(0f);
            if (listener != null) {
                listener.onSelectTab(tabId, false);
            }
            return;
        }
        if (activeTab != null && !activeTab.isEmpty()) {
            tabScrollX.put(activeTab, iconScroll.getScrollX());
            rememberTabSelection(activeTab);
        }
        activeTab = tabId;
        expanded = true;
        functionArea.setVisibility(VISIBLE);
        refreshTabs();
        restoreTabSelection(tabId);
        reloadIconStrip();
        syncSliderToSelection();
        final int restoreX = tabScrollX.containsKey(tabId) ? tabScrollX.get(tabId) : 0;
        iconScroll.post(() -> iconScroll.scrollTo(restoreX, 0));
        animateExpand(1f);
        if (listener != null) {
            listener.onSelectTab(tabId, true);
        }
    }

    private void rememberTabSelection(String tabId) {
        if ("skin".equals(tabId) && selectedEffectKey != null && !selectedEffectKey.isEmpty()) {
            selectedSkinKey = selectedEffectKey;
        } else if ("shape".equals(tabId) && selectedEffectKey != null && !selectedEffectKey.isEmpty()) {
            selectedShapeKey = selectedEffectKey;
        }
    }

    private void restoreTabSelection(String tabId) {
        if ("skin".equals(tabId)) {
            if (selectedSkinKey == null || selectedSkinKey.isEmpty()) {
                selectedSkinKey = firstEnabledKeyOf(skinEffects);
            }
            selectedEffectKey = ensureEnabledSelection(skinEffects, selectedSkinKey);
            selectedSkinKey = selectedEffectKey;
        } else if ("shape".equals(tabId)) {
            if (selectedShapeKey == null || selectedShapeKey.isEmpty()) {
                selectedShapeKey = firstEnabledKeyOf(shapeEffects);
            }
            selectedEffectKey = ensureEnabledSelection(shapeEffects, selectedShapeKey);
            selectedShapeKey = selectedEffectKey;
        }
    }

    /** 对齐 FULiveDemo：跳过 unimplemented / 机型不可用项 */
    private String firstEnabledKeyOf(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return "";
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null || item.getBooleanValue("unimplemented") || isEffectItemDisabled(item)) {
                continue;
            }
            String key = item.getString("key");
            if (key != null && !key.isEmpty()) {
                return key;
            }
        }
        return "";
    }

    private String ensureEnabledSelection(JSONArray arr, String key) {
        if (arr == null || arr.isEmpty()) {
            return key != null ? key : "";
        }
        if (key != null && !key.isEmpty()) {
            JSONObject item = effectMeta.get(key);
            if (item != null && !isEffectItemDisabled(item)) {
                return key;
            }
        }
        return firstEnabledKeyOf(arr);
    }

    private void cancelExpandAnim() {
        if (expandAnim != null) {
            expandAnim.cancel();
            expandAnim = null;
        }
    }

    private void animateExpand(float target) {
        cancelExpandAnim();
        float from = expandProgress;
        if (Math.abs(from - target) < 0.001f) {
            expandProgress = target;
            functionArea.setVisibility(target > 0.01f ? VISIBLE : GONE);
            requestLayout();
            notifyHeight();
            return;
        }
        if (target > 0.01f) {
            functionArea.setVisibility(VISIBLE);
        }
        expandAnim = ValueAnimator.ofFloat(from, target);
        expandAnim.setDuration(200);
        expandAnim.setInterpolator(new DecelerateInterpolator());
        expandAnim.addUpdateListener(a -> {
            expandProgress = (Float) a.getAnimatedValue();
            requestLayout();
            notifyHeight();
        });
        expandAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expandProgress = target;
                if (target <= 0.01f) {
                    functionArea.setVisibility(GONE);
                    functionArea.setTranslationY(0f);
                    functionArea.setAlpha(1f);
                }
                requestLayout();
                notifyHeight();
                expandAnim = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                expandAnim = null;
            }
        });
        expandAnim.start();
    }

    private void refreshTabs() {
        for (int i = 0; i < tabBtns.length; i++) {
            boolean on = expanded && tabIds[i].equals(activeTab);
            tabBtns[i].setTextColor(on ? BRAND : 0xB3FFFFFF);
        }
    }

    /** 对齐 beauty.vue / FuBeautyPerfGate：unimplemented 或档位不足 */
    private boolean isEffectItemDisabled(JSONObject item) {
        if (item == null) {
            return true;
        }
        if (item.getBooleanValue("unimplemented")) {
            return true;
        }
        String key = item.getString("key");
        int need = item.containsKey("performanceLevel") ? item.getIntValue("performanceLevel") : -1;
        if (need < 0 && key != null && !key.isEmpty()) {
            need = FuBeautyPerfGate.requiredLevel(key);
        }
        if (need < 0) {
            return false;
        }
        return devicePerfLevel < need;
    }

    private boolean canUseSkinWhitening() {
        return devicePerfLevel >= 4;
    }

    private JSONObject skinWhiteningItem() {
        JSONObject stub = new JSONObject();
        stub.put("name", "皮肤美白");
        stub.put("performanceLevel", 4);
        return stub;
    }

    private String perfLevelName(int level) {
        switch (level) {
            case -1:
                return "超低";
            case 1:
                return "低端";
            case 2:
                return "中高端";
            case 3:
                return "高端";
            case 4:
                return "旗舰";
            default:
                return "更高";
        }
    }

    private void showPerfLimitToastForItem(JSONObject item) {
        if (item == null) {
            return;
        }
        int need = item.containsKey("performanceLevel") ? item.getIntValue("performanceLevel") : 0;
        String name = item.getString("name");
        if (name == null || name.isEmpty()) {
            name = "该功能";
        }
        String msg = need > 0
                ? name + "仅支持" + perfLevelName(need) + "及以上机型"
                : name + "当前不可用";
        NamaModule.showPerfLimitTip(msg);
    }

    private void applyDisabledStyle(View cell, TextView lab, boolean disabled) {
        cell.setAlpha(disabled ? 0.35f : 1f);
        // 对齐 FULiveDemo shouldSelectItemAtIndexPath：灰显仍可点，弹出机型提示
        cell.setEnabled(true);
        cell.setClickable(true);
        if (lab != null) {
            lab.setAlpha(disabled ? 0.5f : 1f);
        }
    }

    private void reloadIconStrip() {
        iconRow.removeAllViews();
        iconCells.clear();
        boolean filterTab = "filter".equals(activeTab);
        JSONArray list = filterTab ? filters
                : ("shape".equals(activeTab) ? shapeEffects : skinEffects);
        recoverBtn.setVisibility(filterTab ? GONE : VISIBLE);
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, 0, 0, 0);
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            TextView lab = new TextView(getContext());
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            lab.setGravity(Gravity.CENTER);
            lab.setTextColor(0xCCFFFFFF);
            String name = filterTab ? item.getString("name") : item.getString("name");
            lab.setText(name == null ? "" : name);
            String key = filterTab
                    ? (item.getString("id") != null ? item.getString("id") : item.getString("key"))
                    : item.getString("key");
            boolean selected = filterTab
                    ? key != null && key.equals(selectedFilterId)
                    : key != null && key.equals(selectedEffectKey);
            boolean changed = !filterTab && isEffectChanged(key, item);
            applyIconBackground(iv, filterTab, selected);
            String iconUrl = pickIconUrl(item, selected, changed);
            loadImage(iconUrl, iv);
            if (selected) {
                lab.setTextColor(BRAND);
            }
            boolean disabled = !filterTab && isEffectItemDisabled(item);
            applyDisabledStyle(cell, lab, disabled);
            int iconW = dp(ICON_CELL_W_DP);
            cell.addView(iv, new LinearLayout.LayoutParams(iconW, iconW));
            LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(iconW, ViewGroup.LayoutParams.WRAP_CONTENT);
            labLp.topMargin = dp(4);
            cell.addView(lab, labLp);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (iconRow.getChildCount() > 0) {
                cellLp.leftMargin = dp(ICON_GAP_DP);
            }
            cell.setTag(key);
            final JSONObject captured = item;
            cell.setOnClickListener(v -> onIconTap(captured));
            iconRow.addView(cell, cellLp);
            iconCells.add(cell);
        }
        if (!filterTab) {
            refreshRecoverEnabled();
        }
        refreshWhiteningSeg();
        requestLayout();
    }

    /** Demo：有改动 α=1 可点；否则 α=0.6 不可点 */
    private void refreshRecoverEnabled() {
        boolean changed = tabHasChanges(activeTab);
        recoverBtn.setAlpha(changed ? 1f : 0.6f);
        recoverBtn.setEnabled(changed);
        recoverBtn.setClickable(changed);
    }

    private boolean tabHasChanges(String tabId) {
        if ("filter".equals(tabId)) {
            return false;
        }
        JSONArray list = "shape".equals(tabId) ? shapeEffects : skinEffects;
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String key = item.getString("key");
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (item.getBooleanValue("unimplemented") || isEffectItemDisabled(item)) {
                continue;
            }
            // 恢复按钮：相对 defaultSlider（对齐 Demo isDefaultValue）
            double def = item.containsKey("defaultSlider")
                    ? item.getDoubleValue("defaultSlider")
                    : (item.containsKey("default") ? item.getDoubleValue("default") : 0);
            double cur = values.containsKey(key) ? values.get(key) : def;
            if (Math.abs(cur - def) > 0.01) {
                return true;
            }
        }
        return false;
    }

    /** 图标四态「开启」：滑杆相对 UI 0（对齐 JS isEffectChanged / iOS isChangedKey） */
    private boolean isEffectChanged(String key, JSONObject item) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        double cur = values.containsKey(key) ? values.get(key) : 0;
        double zeroRef = 0;
        if (item != null) {
            if (item.containsKey("sliderZero")) {
                zeroRef = item.getDoubleValue("sliderZero");
            } else if (item.getBooleanValue("bidirectional") && item.containsKey("defaultSlider")) {
                zeroRef = item.getDoubleValue("defaultSlider");
            }
        }
        return Math.abs(cur - zeroRef) > 0.01;
    }

    /** 仅刷新选中态/图标，不拆子 View，避免滤镜切换整条闪白 */
    private void refreshIconSelectionOnly() {
        boolean filterTab = "filter".equals(activeTab);
        JSONArray list = filterTab ? filters
                : ("shape".equals(activeTab) ? shapeEffects : skinEffects);
        for (int i = 0; i < iconCells.size() && i < list.size(); i++) {
            View cell = iconCells.get(i);
            if (!(cell instanceof ViewGroup) || ((ViewGroup) cell).getChildCount() < 2) {
                continue;
            }
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String key = filterTab
                    ? (item.getString("id") != null ? item.getString("id") : item.getString("key"))
                    : item.getString("key");
            boolean selected = filterTab
                    ? key != null && key.equals(selectedFilterId)
                    : key != null && key.equals(selectedEffectKey);
            boolean changed = !filterTab && isEffectChanged(key, item);
            ImageView iv = (ImageView) ((ViewGroup) cell).getChildAt(0);
            TextView lab = (TextView) ((ViewGroup) cell).getChildAt(1);
            lab.setTextColor(selected ? BRAND : 0xCCFFFFFF);
            applyIconBackground(iv, filterTab, selected);
            loadImage(pickIconUrl(item, selected, changed), iv);
            boolean disabled = !filterTab && isEffectItemDisabled(item);
            applyDisabledStyle(cell, lab, disabled);
        }
        refreshWhiteningSeg();
    }

    private void applyIconBackground(ImageView iv, boolean filterTab, boolean selected) {
        if (!filterTab) {
            iv.setBackground(null);
            iv.setClipToOutline(false);
            return;
        }
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(selected ? 0x5C040B0E : 0x33FFFFFF);
        iconBg.setCornerRadius(dp(4));
        if (selected) {
            iconBg.setStroke(dp(2), BRAND);
        }
        iv.setBackground(iconBg);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(4));
                }
            });
            iv.setClipToOutline(true);
        }
    }

    /** 对齐 iOS minimumTrackTint / maximumTrackTint */
    private void applySliderTrackColors(boolean bidir) {
        Drawable drawable = slider.getProgressDrawable();
        if (!(drawable instanceof LayerDrawable)) {
            return;
        }
        LayerDrawable layer = (LayerDrawable) drawable.mutate();
        Drawable progress = layer.findDrawableByLayerId(android.R.id.progress);
        Drawable background = layer.findDrawableByLayerId(android.R.id.background);
        if (bidir) {
            if (progress != null) {
                progress.setColorFilter(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            }
            if (background != null) {
                background.setColorFilter(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            }
            bipolarBgTrack.setVisibility(VISIBLE);
        } else {
            if (progress != null) {
                progress.setColorFilter(BRAND, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            if (background != null) {
                background.setColorFilter(TRACK_GRAY, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            bipolarBgTrack.setVisibility(GONE);
        }
    }

    private String pickIconUrl(JSONObject item, boolean selected, boolean changed) {
        String base = item.getString("iconUrl");
        if (base == null) {
            base = item.getString("icon");
        }
        if (base == null) {
            base = "";
        }
        String suffix = "";
        if (selected && changed) {
            suffix = "ChangesActive";
        } else if (selected) {
            suffix = "Active";
        } else if (changed) {
            suffix = "Changes";
        }
        if (!suffix.isEmpty()) {
            String alt = item.getString("iconUrl" + suffix);
            if (!TextUtils.isEmpty(alt)) {
                return alt;
            }
        }
        return base;
    }

    private void onIconTap(JSONObject item) {
        if ("filter".equals(activeTab)) {
            String fid = item.getString("id");
            if (fid == null) {
                fid = item.getString("key");
            }
            String fkey = item.getString("key");
            if (fkey == null) {
                fkey = fid;
            }
            selectedFilterId = fid;
            refreshIconSelectionOnly();
            syncSliderToSelection();
            String fname = item.getString("name");
            if (fname == null || fname.isEmpty()) {
                fname = fid;
            }
            NamaModule.showFilterNameTip(fname);
            if (listener != null) {
                listener.onSelectFilter(fid, fkey);
            }
            return;
        }
        if (isEffectItemDisabled(item)) {
            showPerfLimitToastForItem(item);
            return;
        }
        selectedEffectKey = item.getString("key");
        if ("skin".equals(activeTab)) {
            selectedSkinKey = selectedEffectKey;
        } else if ("shape".equals(activeTab)) {
            selectedShapeKey = selectedEffectKey;
        }
        refreshIconSelectionOnly();
        refreshWhiteningSeg();
        syncSliderToSelection();
        requestLayout();
        if (listener != null && selectedEffectKey != null) {
            listener.onSelectEffect(selectedEffectKey);
        }
    }

    private JSONObject selectedMeta() {
        if ("filter".equals(activeTab)) {
            JSONObject m = new JSONObject();
            m.put("key", "filter_level");
            m.put("sliderMin", 0);
            m.put("sliderMax", 100);
            m.put("min", 0);
            m.put("max", 1);
            m.put("bidirectional", false);
            return m;
        }
        JSONObject meta = effectMeta.get(selectedEffectKey);
        return meta != null ? meta : new JSONObject();
    }

    private void syncSliderToSelection() {
        JSONObject meta = selectedMeta();
        bidirectional = meta.getBooleanValue("bidirectional");
        sliderMin = (float) meta.getDoubleValue("sliderMin");
        sliderMax = (float) meta.getDoubleValue("sliderMax");
        if (sliderMax <= sliderMin) {
            sliderMin = 0;
            sliderMax = 100;
        }
        midLine.setVisibility(bidirectional ? VISIBLE : GONE);
        bipolarTrack.setVisibility(bidirectional ? VISIBLE : GONE);
        applySliderTrackColors(bidirectional);
        String key = "filter".equals(activeTab) ? "filter_level" : selectedEffectKey;
        double cur = values.containsKey(key)
                ? values.get(key)
                : meta.getDoubleValue("defaultSlider");
        int max = Math.max(1, Math.round(sliderMax - sliderMin));
        slider.setMax(max);
        int progress = Math.round((float) cur - sliderMin);
        progress = Math.max(0, Math.min(max, progress));
        slider.setProgress(progress);
        boolean disabled = meta.getBooleanValue("unimplemented") || isEffectItemDisabled(meta);
        slider.setEnabled(!disabled);
        slider.setAlpha(disabled ? 0.4f : 1f);
        requestLayout();
    }

    private void emitSlider() {
        JSONObject meta = selectedMeta();
        String key = "filter".equals(activeTab) ? "filter_level" : selectedEffectKey;
        if (key == null || key.isEmpty()) {
            return;
        }
        if (meta.getBooleanValue("unimplemented") || isEffectItemDisabled(meta)) {
            return;
        }
        float ui = sliderMin + slider.getProgress();
        values.put(key, (double) ui);
        double sdk = sdkValueFromSlider(ui, meta);
        if (listener != null) {
            listener.onSliderChanged(key, sdk);
        }
        refreshRecoverEnabled();
    }

    private double sdkValueFromSlider(double sliderUi, JSONObject meta) {
        double sMin = meta.containsKey("sliderMin") ? meta.getDoubleValue("sliderMin") : 0;
        double sMax = meta.containsKey("sliderMax") ? meta.getDoubleValue("sliderMax") : 100;
        double minV = meta.containsKey("min") ? meta.getDoubleValue("min") : 0;
        double maxV = meta.containsKey("max") ? meta.getDoubleValue("max") : 1;
        if (Math.abs(sMax - sMin) < 1e-6) {
            return minV;
        }
        double ratio = (sliderUi - sMin) / (sMax - sMin);
        double raw = minV + ratio * (maxV - minV);
        return Math.round(raw * 100.0) / 100.0;
    }

    private void refreshTip() {
        tipLabel.setText(String.valueOf(Math.round(sliderMin + slider.getProgress())));
    }

    private void setWhiteningModeInternal(String mode, boolean notify) {
        whiteningMode = "skin".equals(mode) ? "skin" : "global";
        refreshWhiteningSeg();
        if (notify && listener != null) {
            listener.onWhiteningMode(whiteningMode);
        }
    }

    private void refreshWhiteningSeg() {
        boolean show = expandProgress > 0.5f && "skin".equals(activeTab)
                && ("color_level_mode2".equals(selectedEffectKey) || "color_level".equals(selectedEffectKey));
        whiteningSeg.setVisibility(show ? VISIBLE : GONE);
        boolean global = "global".equals(whiteningMode);
        applyWhiteningBtnStyle(whiteningGlobalBtn, global);
        applyWhiteningBtnStyle(whiteningSkinBtn, !global);
        boolean skinOk = canUseSkinWhitening();
        whiteningSkinBtn.setEnabled(skinOk);
        whiteningSkinBtn.setAlpha(skinOk ? 1f : 0.35f);
        requestLayout();
    }

    private void notifyHeight() {
        if (listener != null) {
            listener.onPanelHeightChanged(getCurrentPanelHeightPx());
        }
    }

    private int resolveSafeBottom() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.view.WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    int b = insets.getSystemWindowInsetBottom();
                    if (b > 0) {
                        return b;
                    }
                }
                // 媒体页面板在 PopupWindow 内，常拿不到 inset：从 Activity Decor 取
                android.content.Context ctx = getContext();
                if (ctx instanceof android.app.Activity) {
                    android.view.View decor = ((android.app.Activity) ctx).getWindow().getDecorView();
                    android.view.WindowInsets di = decor.getRootWindowInsets();
                    if (di != null) {
                        int b = di.getSystemWindowInsetBottom();
                        if (b > 0) {
                            return b;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (resId > 0) {
                int h = getResources().getDimensionPixelSize(resId);
                if (h > 0) {
                    return h;
                }
            }
        } catch (Throwable ignored) {
        }
        return dp(24);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        int safe = resolveSafeBottom();
        int catH = dp(CATEGORY_DP) + safe;
        measureExact(categoryBar, w, catH);
        categoryBar.layout(0, h - catH, w, h);

        int funcH = dp(FUNCTION_DP);
        int funcVisibleH = Math.round(funcH * expandProgress);
        if (expandProgress > 0.01f) {
            functionArea.setVisibility(VISIBLE);
            // 内容按满高排，用 translationY 做收起/展开，保存钮随 panelTop 平滑下移
            measureExact(functionArea, w, funcH);
            functionArea.layout(0, h - catH - funcH, w, h - catH);
            layoutFunctionChildren(w, funcH);
            functionArea.setTranslationY(funcH - funcVisibleH);
            functionArea.setAlpha(Math.min(1f, expandProgress * 1.4f));
        } else {
            functionArea.setVisibility(GONE);
            functionArea.setTranslationY(0f);
            functionArea.setAlpha(1f);
        }

        int panelTop = h - catH - funcVisibleH;
        int compareSize = dp(44);
        measureExact(compareBtn, compareSize, compareSize);
        compareBtn.layout(dp(15), panelTop - dp(54), dp(15) + compareSize, panelTop - dp(10));
        compareIcon.setPivotX(compareSize * 0.5f);
        compareIcon.setPivotY(compareSize * 0.5f);
        compareIcon.setScaleX(1.4f);
        compareIcon.setScaleY(1.4f);
        boolean media = "image".equals(mode) || "video".equals(mode);
        saveBtn.setVisibility(media ? VISIBLE : GONE);
        if (media) {
            int sw = dp(58);
            int sx = (w - sw) / 2;
            int sy = panelTop - dp(10) - sw;
            measureExact(saveBtn, sw, sw);
            saveBtn.layout(sx, sy, sx + sw, sy + sw);
        }
        // 收起动画功能区会下移，保证 Tab/按钮在上层不被盖住
        categoryBar.bringToFront();
        compareBtn.bringToFront();
        tipBubble.bringToFront();
        if (media) {
            saveBtn.bringToFront();
        }
    }

    private void layoutFunctionChildren(int w, int funcH) {
        boolean whitening = whiteningSeg.getVisibility() == VISIBLE;
        // 对齐 Demo FUCustomizeSkinView：美白分段在左，滑杆右移让位
        int sliderLeft = whitening ? dp(112) : dp(24);
        int sliderRight = dp(24);

        int sliderH = dp(SLIDER_DP);
        int sliderTop = dp(18);
        measureExact(slider, w - sliderLeft - sliderRight, sliderH);
        slider.layout(sliderLeft, sliderTop, w - sliderRight, sliderTop + sliderH);
        layoutSliderTip();
        if (bidirectional) {
            int midX = sliderLeft + (w - sliderLeft - sliderRight) / 2;
            measureExact(midLine, 2, sliderH - dp(16));
            midLine.layout(midX - 1, sliderTop + dp(8), midX + 1, sliderTop + sliderH - dp(8));
            layoutBipolarTrack();
        } else {
            bipolarTrack.layout(0, 0, 0, 0);
            bipolarBgTrack.layout(0, 0, 0, 0);
        }
        slider.bringToFront();
        tipBubble.bringToFront();

        boolean filterTab = "filter".equals(activeTab);
        int recoverW = filterTab ? 0 : dp(ICON_CELL_W_DP);
        int iconRowY = dp(66);
        if (!filterTab) {
            measureExact(recoverBtn, recoverW, dp(74));
            recoverBtn.layout(dp(8), iconRowY, dp(8) + recoverW, iconRowY + dp(74));
            refreshRecoverEnabled();
            int divX = dp(8) + recoverW + dp(8);
            int divCy = iconRowY + dp(22);
            measureExact(recoverDivider, 1, dp(24));
            recoverDivider.setVisibility(VISIBLE);
            recoverDivider.layout(divX, divCy - dp(12), divX + 1, divCy + dp(12));
        } else {
            recoverBtn.layout(0, 0, 0, 0);
            recoverDivider.layout(0, 0, 0, 0);
            recoverDivider.setVisibility(GONE);
        }
        int scrollX = filterTab ? dp(8) : (dp(8) + recoverW + dp(8) + dp(1) + dp(8));
        int scrollW = Math.max(dp(40), w - scrollX - dp(8));
        int scrollH = dp(84);
        measureExact(iconScroll, scrollW, scrollH);
        // 先量横向内容，否则 HorizontalScrollView 子项宽高为 0 → 图标不显示
        int rowH = MeasureSpec.makeMeasureSpec(scrollH, MeasureSpec.EXACTLY);
        int rowW = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        iconRow.measure(rowW, rowH);
        int contentW = Math.max(iconRow.getMeasuredWidth(), scrollW);
        ViewGroup.LayoutParams rowLp = iconRow.getLayoutParams();
        if (rowLp != null) {
            rowLp.width = contentW;
            rowLp.height = scrollH;
            iconRow.setLayoutParams(rowLp);
        }
        iconRow.layout(0, 0, contentW, scrollH);
        iconScroll.layout(scrollX, iconRowY, scrollX + scrollW, iconRowY + scrollH);
        if (!filterTab) {
            // 恢复区盖在滚动层之上，对齐 iOS 不滚到恢复按钮下
            recoverBtn.setElevation(dp(4));
            recoverDivider.setElevation(dp(4));
            recoverDivider.bringToFront();
            recoverBtn.bringToFront();
        }

        if (whitening) {
            int segW = dp(80);
            measureExact(whiteningSeg, segW, dp(24));
            whiteningSeg.layout(dp(16), sliderTop + dp(2), dp(16) + segW, sliderTop + dp(2) + dp(24));
            whiteningSeg.bringToFront();
        }
    }

    private void layoutSliderTip() {
        if (tipBubble.getVisibility() != VISIBLE || slider.getWidth() <= 0 || functionArea.getVisibility() != VISIBLE) {
            return;
        }
        int bubbleW = dp(28);
        int bubbleH = dp(32);
        int trackW = Math.max(1, slider.getWidth());
        int max = Math.max(1, slider.getMax());
        float ratio = slider.getProgress() / (float) max;
        int thumbInset = dp(8);
        int thumbX = slider.getLeft() + thumbInset
                + Math.round((trackW - thumbInset * 2) * ratio);
        int funcTop = functionArea.getTop() + Math.round(functionArea.getTranslationY());
        int x = functionArea.getLeft() + thumbX - bubbleW / 2;
        int y = funcTop + slider.getTop() - bubbleH + dp(4);
        measureExact(tipBubble, bubbleW, bubbleH);
        tipBubble.layout(x, y, x + bubbleW, y + bubbleH);
        tipBubble.bringToFront();
    }

    /** 双向蓝条：灰底 + 从中点向左/右蓝色填充 */
    private void layoutBipolarTrack() {
        if (!bidirectional || bipolarTrack.getVisibility() != VISIBLE) {
            return;
        }
        int sl = slider.getLeft();
        int sr = slider.getRight();
        int st = slider.getTop();
        int sb = slider.getBottom();
        int trackW = Math.max(1, sr - sl);
        int max = Math.max(1, slider.getMax());
        float ratio = slider.getProgress() / (float) max;
        int thumbInset = dp(8);
        int thumbX = sl + thumbInset + Math.round((trackW - thumbInset * 2) * ratio);
        int midX = sl + trackW / 2;
        int left = Math.min(midX, thumbX);
        int right = Math.max(midX, thumbX);
        int barH = sliderTrackBarHeight();
        int barY = (st + sb - barH) / 2;
        int bgLeft = sl + thumbInset;
        int bgRight = sr - thumbInset;
        measureExact(bipolarBgTrack, Math.max(dp(2), bgRight - bgLeft), barH);
        bipolarBgTrack.layout(bgLeft, barY, bgRight, barY + barH);
        GradientDrawable biBg = new GradientDrawable();
        biBg.setColor(BRAND);
        biBg.setCornerRadius(barH / 2f);
        bipolarTrack.setBackground(biBg);
        measureExact(bipolarTrack, Math.max(dp(2), right - left), barH);
        bipolarTrack.layout(left, barY, right, barY + barH);
        functionArea.bringChildToFront(bipolarBgTrack);
        functionArea.bringChildToFront(bipolarTrack);
        functionArea.bringChildToFront(slider);
    }

    /** 与单向 SeekBar 轨道视觉高度一致（对齐 iOS trackRect） */
    private int sliderTrackBarHeight() {
        return dp(3);
    }

    private void measureExact(View v, int width, int height) {
        v.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height), MeasureSpec.EXACTLY));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev == null) {
            return false;
        }
        if (hitInteractive(ev.getX(), ev.getY())) {
            return super.dispatchTouchEvent(ev);
        }
        // 空白穿透到下层 PreviewChrome（拍摄/对焦）
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    private void loadImage(String urlOrPath, ImageView iv) {
        if (TextUtils.isEmpty(urlOrPath) || iv == null) {
            return;
        }
        Bitmap cached = iconBmpCache.get(urlOrPath);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        final String key = urlOrPath;
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            imgExec.execute(() -> {
                Bitmap bmp = decodeHttp(urlOrPath);
                if (bmp != null) {
                    iconBmpCache.put(key, bmp);
                    main.post(() -> iv.setImageBitmap(bmp));
                }
            });
            return;
        }
        imgExec.execute(() -> {
            Bitmap bmp = decodeLocalBitmap(getContext(), urlOrPath);
            if (bmp != null) {
                iconBmpCache.put(key, bmp);
                main.post(() -> iv.setImageBitmap(bmp));
            }
        });
    }

    private static Bitmap decodeLocalBitmap(Context context, String urlOrPath) {
        if (TextUtils.isEmpty(urlOrPath) || context == null) {
            return null;
        }
        String path = urlOrPath.trim();
        try {
            if (path.startsWith("file://")) {
                Uri uri = Uri.parse(path);
                path = uri.getPath();
                // file:///android_asset/apps/.../www/static/...
                String full = uri.toString();
                int assetIdx = full.indexOf("/android_asset/");
                if (assetIdx >= 0) {
                    String assetPath = full.substring(assetIdx + "/android_asset/".length());
                    try (InputStream in = context.getAssets().open(assetPath)) {
                        return BitmapFactory.decodeStream(in);
                    }
                }
            }
            if (path != null && path.contains("android_asset/")) {
                String assetPath = path.substring(path.indexOf("android_asset/") + "android_asset/".length());
                try (InputStream in = context.getAssets().open(assetPath)) {
                    return BitmapFactory.decodeStream(in);
                }
            }
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    return BitmapFactory.decodeFile(path);
                }
            }
            // 相对 /static/...：在 UniApp www 常见目录下查找
            String rel = urlOrPath;
            int staticIdx = urlOrPath.indexOf("/static/");
            if (staticIdx >= 0) {
                rel = urlOrPath.substring(staticIdx + 1); // static/...
            } else if (urlOrPath.startsWith("static/")) {
                rel = urlOrPath;
            }
            if (rel.startsWith("static/")) {
                String[] assetTries = {
                        "apps/__UNI__/www/" + rel,
                        "www/" + rel,
                };
                for (String tryPath : assetTries) {
                    Bitmap b = openAssetBitmap(context, tryPath);
                    if (b != null) {
                        return b;
                    }
                }
                // 自定义基座 appid 目录名不固定：扫 assets/apps/*/www/static
                try {
                    String[] apps = context.getAssets().list("apps");
                    if (apps != null) {
                        for (String appId : apps) {
                            Bitmap b = openAssetBitmap(context, "apps/" + appId + "/www/" + rel);
                            if (b != null) {
                                return b;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                File base = context.getFilesDir();
                if (base != null && base.getParentFile() != null) {
                    File[] candidates = {
                            new File(base.getParentFile(), "apps"),
                            new File(context.getApplicationInfo().dataDir, "apps"),
                    };
                    for (File apps : candidates) {
                        if (apps == null || !apps.isDirectory()) {
                            continue;
                        }
                        File[] children = apps.listFiles();
                        if (children == null) {
                            continue;
                        }
                        for (File app : children) {
                            File f = new File(app, "www/" + rel);
                            if (f.exists()) {
                                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                                if (b != null) {
                                    return b;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("FuBeautyPanel", "decodeLocalBitmap " + urlOrPath, t);
        }
        return null;
    }

    private static Bitmap openAssetBitmap(Context context, String assetPath) {
        if (context == null || TextUtils.isEmpty(assetPath)) {
            return null;
        }
        try (InputStream in = context.getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeHttp(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean hitView(View v, float x, float y) {
        if (v == null || v.getVisibility() != VISIBLE) {
            return false;
        }
        return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
