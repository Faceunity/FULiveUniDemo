package io.dcloud.feature.uniapp.ui.component;

import android.content.Context;
import android.view.View;

import com.taobao.weex.ui.action.BasicComponentData;

import io.dcloud.feature.uniapp.UniSDKInstance;

public abstract class UniComponent<T extends View> {

    private T hostView;

    protected UniComponent(UniSDKInstance instance, UniVContainer parent, BasicComponentData data) {
    }

    protected abstract T initComponentHostView(Context context);

    protected T getHostView() {
        return hostView;
    }

    protected void setHostView(T view) {
        this.hostView = view;
    }

    public void destroy() {
    }
}
