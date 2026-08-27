package com.faceunity.nama.utils;

import com.alibaba.fastjson.JSONObject;

/** UniJSCallback 统一返回格式 */
public final class NamaJsResult {

    private NamaJsResult() {
    }

    public static JSONObject success(Object data) {
        JSONObject ret = new JSONObject();
        ret.put("code", 0);
        ret.put("data", data);
        return ret;
    }

    public static JSONObject fail(String message) {
        return fail(message, null);
    }

    public static JSONObject fail(String message, JSONObject diag) {
        JSONObject ret = new JSONObject();
        ret.put("code", -1);
        ret.put("message", message != null ? message : "unknown error");
        if (diag != null) {
            ret.put("data", diag);
        }
        return ret;
    }
}
