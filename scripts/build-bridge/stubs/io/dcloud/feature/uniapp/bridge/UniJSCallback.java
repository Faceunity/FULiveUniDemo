package io.dcloud.feature.uniapp.bridge;

public interface UniJSCallback {
    void invoke(Object data);

    /** 异步多次回调时保持回调存活（如 startActivityForResult） */
    default void invokeAndKeepAlive(Object data) {
        invoke(data);
    }
}
