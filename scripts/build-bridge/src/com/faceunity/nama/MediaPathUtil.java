package com.faceunity.nama;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 将相册 content:// / file:// 转为应用可 File 打开的本地路径。
 * MediaExtractor / BitmapFactory.decodeFile 不能直接读 content URI。
 */
final class MediaPathUtil {

    private static final String TAG = "FaceUnity-Nama";

    private MediaPathUtil() {
    }

    static String toLocalFilePath(Context context, String path, String extHint) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String raw = path.trim();
        if (raw.startsWith("file://")) {
            raw = raw.substring(7);
        }
        if (raw.startsWith("content://")) {
            if (context == null) {
                throw new IllegalStateException("activity null，无法读取 content URI");
            }
            return copyContentUri(context, Uri.parse(raw), normalizeExt(extHint, raw));
        }
        File file = new File(raw);
        if (file.exists() && file.isFile() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        // 某些机型 gallery 返回无 scheme 的绝对路径
        if (raw.startsWith("/") && file.exists()) {
            return file.getAbsolutePath();
        }
        throw new java.io.IOException("媒体文件不可读: " + path);
    }

    private static String normalizeExt(String extHint, String raw) {
        if (extHint != null && extHint.length() > 0) {
            return extHint.startsWith(".") ? extHint : ("." + extHint);
        }
        String lower = raw.toLowerCase();
        if (lower.contains("video") || lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return ".mp4";
        }
        if (lower.endsWith(".png")) {
            return ".png";
        }
        if (lower.endsWith(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private static String copyContentUri(Context context, Uri uri, String ext) throws Exception {
        File dir = new File(context.getCacheDir(), "nama_import");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建缓存目录");
        }
        File dest = new File(dir, "import_" + System.currentTimeMillis() + ext);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new java.io.IOException("无法打开 content URI: " + uri);
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            if (total <= 0) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new java.io.IOException("content URI 内容为空");
            }
            Log.i(TAG, "copyContentUri ok bytes=" + total + " -> " + dest.getAbsolutePath());
            return dest.getAbsolutePath();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
