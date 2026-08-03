package com.faceunity.nama;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;

/**
 * NV21 旋转/镜像：相机帧为横向 buffer，预览在 GL 里逆时针 90° 转正。
 */
final class Nv21Utils {

    private Nv21Utils() {
    }

    /**
     * 逆时针 90°（对齐预览 shader：vec2(uv.y, 1.0 - uv.x)）；
     * 输出尺寸 height × width。
     */
    static void rotateCcw90(byte[] src, byte[] dst, int width, int height) {
        int frameSize = width * height;
        int i = 0;
        for (int x = width - 1; x >= 0; x--) {
            for (int y = 0; y < height; y++) {
                dst[i++] = src[y * width + x];
            }
        }
        i = frameSize;
        for (int x = width - 1; x > 0; x -= 2) {
            for (int y = 0; y < height / 2; y++) {
                int srcUv = frameSize + y * width + (x - 1);
                dst[i++] = src[srcUv];
                dst[i++] = src[srcUv + 1];
            }
        }
    }

    /** @deprecated 用 {@link #rotateCcw90} 对齐预览 */
    static void rotateCw90(byte[] src, byte[] dst, int width, int height) {
        int frameSize = width * height;
        int i = 0;
        for (int x = 0; x < width; x++) {
            for (int y = height - 1; y >= 0; y--) {
                dst[i++] = src[y * width + x];
            }
        }
        i = frameSize * 3 / 2 - 1;
        for (int x = width - 1; x > 0; x -= 2) {
            int offset = frameSize;
            for (int y = 0; y < height / 2; y++) {
                dst[i--] = src[offset + x];
                dst[i--] = src[offset + (x - 1)];
                offset += width;
            }
        }
    }

    /** 水平镜像（就地），对齐前置预览镜像 */
    static void mirrorHorizontal(byte[] data, int width, int height) {
        for (int y = 0; y < height; y++) {
            int off = y * width;
            int left = off;
            int right = off + width - 1;
            while (left < right) {
                byte t = data[left];
                data[left] = data[right];
                data[right] = t;
                left++;
                right--;
            }
        }
        int ySize = width * height;
        for (int y = 0; y < height / 2; y++) {
            int off = ySize + y * width;
            for (int x = 0; x < width / 2; x++) {
                int left = off + x * 2;
                int right = off + (width / 2 - 1 - x) * 2;
                if (left >= right) {
                    break;
                }
                byte v0 = data[left];
                byte u0 = data[left + 1];
                data[left] = data[right];
                data[left + 1] = data[right + 1];
                data[right] = v0;
                data[right + 1] = u0;
            }
        }
    }

    /**
     * RGBA → NV21；{@code flipY=true} 时按 GL readPixels 原点翻到顶左。
     */
    static void rgbaToNv21(byte[] rgba, byte[] nv21, int width, int height, boolean flipY) {
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < height; j++) {
            int srcRow = flipY ? (height - 1 - j) : j;
            for (int i = 0; i < width; i++) {
                int p = (srcRow * width + i) * 4;
                int r = rgba[p] & 0xff;
                int g = rgba[p + 1] & 0xff;
                int b = rgba[p + 2] & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv21[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    nv21[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                    nv21[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                }
            }
        }
    }

    static Bitmap toBitmap(byte[] nv21, int width, int height) {
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), 95, baos);
        byte[] jpeg = baos.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }
}
