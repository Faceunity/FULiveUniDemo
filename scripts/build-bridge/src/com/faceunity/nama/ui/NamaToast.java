package com.faceunity.nama.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 滤镜名 / 未检测到人脸 / 机型提示：PopupWindow 盖住 ZOrderOnTop 取景与底栏。
 */
public final class NamaToast {

    private static final String TAG = "FaceUnity-Nama";
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static FrameLayout sHost;
    private static TextView sNoFaceTip;
    private static TextView sFilterTip;
    private static PopupWindow sPopup;
    private static Runnable sHideTask;

    private static volatile boolean sTipsEnabled = false;
    private static volatile boolean sFaceTracked = true;
    private static volatile long sTipsEnabledAtMs = 0L;

    private NamaToast() {
    }

    public static void setTipsEnabled(boolean enabled) {
        sTipsEnabled = enabled;
        if (enabled) {
            sTipsEnabledAtMs = System.currentTimeMillis();
            sFaceTracked = true;
        }
        sHandler.post(() -> {
            if (!enabled) {
                hideAll();
            } else {
                refreshNoFaceUi();
            }
        });
    }

    public static void onFaceTrackingUpdated(boolean tracked) {
        if (sFaceTracked == tracked) {
            return;
        }
        sFaceTracked = tracked;
        sHandler.post(NamaToast::refreshNoFaceUi);
    }

    public static void showPerfLimitTip(Activity activity, String message) {
        if (message == null || message.isEmpty() || activity == null) {
            return;
        }
        sHandler.post(() -> {
            ensureOverlay(activity);
            if (sFilterTip != null) {
                sFilterTip.setTextSize(14);
                sFilterTip.setTypeface(Typeface.DEFAULT);
                sFilterTip.setText(message);
                sFilterTip.setVisibility(View.VISIBLE);
                scheduleFilterHide(2000L);
            }
        });
    }

    public static void showFilterName(Activity activity, String name) {
        if (name == null || name.isEmpty() || activity == null) {
            return;
        }
        sHandler.post(() -> {
            ensureOverlay(activity);
            if (sFilterTip != null) {
                sFilterTip.setTextSize(20);
                sFilterTip.setTypeface(Typeface.DEFAULT_BOLD);
                sFilterTip.setText(name);
                sFilterTip.setVisibility(View.VISIBLE);
                scheduleFilterHide(1000L);
            }
        });
    }

    public static void setNoFaceVisible(Activity activity, boolean visible) {
        sHandler.post(() -> {
            ensureOverlay(activity);
            if (sNoFaceTip != null) {
                sNoFaceTip.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    public static void hideAll() {
        sHandler.post(() -> {
            if (sHideTask != null) {
                sHandler.removeCallbacks(sHideTask);
                sHideTask = null;
            }
            if (sNoFaceTip != null) {
                sNoFaceTip.setVisibility(View.GONE);
            }
            if (sFilterTip != null) {
                sFilterTip.setVisibility(View.GONE);
            }
            if (sPopup != null && sPopup.isShowing()) {
                try {
                    sPopup.dismiss();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void refreshNoFaceUi() {
        if (!sTipsEnabled || sNoFaceTip == null) {
            return;
        }
        if (System.currentTimeMillis() - sTipsEnabledAtMs < 2000L) {
            sNoFaceTip.setVisibility(View.GONE);
            return;
        }
        sNoFaceTip.setVisibility(sFaceTracked ? View.GONE : View.VISIBLE);
    }

    private static void ensureOverlay(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            if (sHost == null) {
                sHost = new FrameLayout(activity);
                sHost.setClickable(false);
                sHost.setFocusable(false);

                sNoFaceTip = new TextView(activity);
                sNoFaceTip.setText("未检测到人脸");
                sNoFaceTip.setTextColor(0xFFFFFFFF);
                sNoFaceTip.setTextSize(16);
                sNoFaceTip.setGravity(Gravity.CENTER);
                sNoFaceTip.setShadowLayer(4f, 0f, 1f, 0x99000000);
                sNoFaceTip.setVisibility(View.GONE);
                FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.gravity = Gravity.CENTER;
                sHost.addView(sNoFaceTip, nlp);

                sFilterTip = new TextView(activity);
                sFilterTip.setTextColor(0xFFFFFFFF);
                sFilterTip.setTextSize(20);
                sFilterTip.setGravity(Gravity.CENTER);
                sFilterTip.setTypeface(Typeface.DEFAULT_BOLD);
                sFilterTip.setShadowLayer(6f, 0f, 2f, 0xCC000000);
                sFilterTip.setVisibility(View.GONE);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                flp.gravity = Gravity.CENTER;
                sHost.addView(sFilterTip, flp);
            }
            if (sPopup == null) {
                sPopup = new PopupWindow(sHost,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        false);
                sPopup.setTouchable(false);
                sPopup.setFocusable(false);
                sPopup.setOutsideTouchable(false);
                sPopup.setClippingEnabled(false);
                sPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    sPopup.setElevation(200f);
                }
            } else if (sPopup.getContentView() != sHost) {
                sPopup.setContentView(sHost);
            }
            if (!sPopup.isShowing()) {
                showPopupSafe(activity);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureOverlay", t);
        }
    }

    private static void showPopupSafe(Activity activity) {
        if (activity.isFinishing()) {
            return;
        }
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null || decor.getWindowToken() == null) {
            final Activity act = activity;
            sHandler.post(() -> {
                try {
                    if (act.isFinishing() || sPopup == null || sPopup.isShowing()) {
                        return;
                    }
                    View d = act.getWindow() != null ? act.getWindow().getDecorView() : null;
                    if (d != null && d.getWindowToken() != null) {
                        sPopup.showAtLocation(d, Gravity.NO_GRAVITY, 0, 0);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "ensureOverlay delayed show", t);
                }
            });
            return;
        }
        try {
            sPopup.showAtLocation(decor, Gravity.NO_GRAVITY, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "ensureOverlay show", t);
        }
    }

    private static void scheduleFilterHide(long delayMs) {
        if (sHideTask != null) {
            sHandler.removeCallbacks(sHideTask);
        }
        sHideTask = () -> {
            if (sFilterTip != null) {
                sFilterTip.setVisibility(View.GONE);
            }
        };
        sHandler.postDelayed(sHideTask, delayMs);
    }
}
