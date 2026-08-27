package com.faceunity.nama;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * uni-app 只有一个 {@code PandoraEntryActivity}。切系统导航时小米等会重建它，
 * 进程却还在：JS 重载、Nama 静态 overlay / handle 留在死 Activity 上，就是「死一半」。
 * 一旦确认是配置变更重建，立刻杀进程并从桌面入口冷启动。
 */
public final class NamaHostRecreateWatch {

    private static final String TAG = "FaceUnity-Nama";
    private static final String HOST = "io.dcloud.PandoraEntryActivity";

    private static volatile boolean sInstalled;
    private static volatile boolean sRelaunching;
    private static volatile boolean sExpectRecreate;
    private static WeakReference<Activity> sHostRef;

    private NamaHostRecreateWatch() {
    }

    public static void install(Context context) {
        if (sInstalled || context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        if (!(appCtx instanceof Application)) {
            return;
        }
        synchronized (NamaHostRecreateWatch.class) {
            if (sInstalled) {
                return;
            }
            ((Application) appCtx).registerActivityLifecycleCallbacks(new Callbacks());
            sInstalled = true;
            Log.i(TAG, "host recreate watch installed");
        }
    }

    public static void relaunch(Context context) {
        if (sRelaunching) {
            return;
        }
        sRelaunching = true;
        Log.w(TAG, "relaunch process after host Activity recreate");
        Context app = context != null ? context.getApplicationContext() : null;
        if (app == null) {
            Activity host = peekHost();
            if (host != null) {
                app = host.getApplicationContext();
            }
        }
        try {
            if (app != null) {
                Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    app.startActivity(intent);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "relaunch startActivity", t);
        }
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    private static boolean isHost(Activity activity) {
        return activity != null && HOST.equals(activity.getClass().getName());
    }

    private static Activity peekHost() {
        return sHostRef == null ? null : sHostRef.get();
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            if (!isHost(activity) || sRelaunching) {
                return;
            }
            Activity prev = peekHost();
            boolean configRecreate =
                    sExpectRecreate
                            || (prev != null && prev != activity && prev.isChangingConfigurations());
            if (configRecreate) {
                relaunch(activity);
                return;
            }
            sHostRef = new WeakReference<>(activity);
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (!isHost(activity)) {
                return;
            }
            if (activity.isChangingConfigurations()) {
                sExpectRecreate = true;
            }
            Activity cur = peekHost();
            if (cur == activity) {
                sHostRef = null;
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }
    }
}
