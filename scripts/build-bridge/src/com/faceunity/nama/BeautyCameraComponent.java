package com.faceunity.nama;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.taobao.weex.ui.action.BasicComponentData;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniVContainer;

/**
 * nvue 原生相机组件：GL 嵌在页面视图树底部，上层 nvue UI 可正常盖住取景。
 * <p>
 * initComponentHostView 绝不能抛异常或返回 null，
 * 否则 Weex callAddElement 会 NPE（getAttrs on null）。
 * 也不要在此处调用 setHostView，由框架根据返回值设置。
 */
public class BeautyCameraComponent extends UniComponent<FrameLayout> {

    private static final String TAG = "FaceUnity-CameraCmp";

    private BeautyCameraGLView cameraView;

    public BeautyCameraComponent(UniSDKInstance instance, UniVContainer parent, BasicComponentData data) {
        super(instance, parent, data);
    }

    @Override
    protected FrameLayout initComponentHostView(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 透明容器，避免黑底先盖住下层 Surface，再让上层 nvue UI 盖住取景
        root.setBackgroundColor(0x00000000);
        try {
            // 必须 createEmbedded：构造期就 ZOrderOnTop=false，UI 才能压在取景上
            BeautyCameraGLView view = BeautyCameraGLView.createEmbedded(context);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            cameraView = view;
            NamaModule.attachHostedCameraView(view);
            Log.e(TAG, "initComponentHostView embedded");
        } catch (Throwable t) {
            // 相机创建失败也不要让整页闪退：空 FrameLayout 仍可完成 Weex 注册
            Log.e(TAG, "initComponentHostView camera failed", t);
        }
        return root;
    }

    @Override
    public void destroy() {
        BeautyCameraGLView view = cameraView;
        cameraView = null;
        if (view != null) {
            NamaModule.detachHostedCameraView(view);
            try {
                view.destroyPreviewAsync(null);
            } catch (Throwable t) {
                Log.w(TAG, "destroyPreviewAsync", t);
            }
        }
        super.destroy();
        Log.e(TAG, "destroy");
    }
}
