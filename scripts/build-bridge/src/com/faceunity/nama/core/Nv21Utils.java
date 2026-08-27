package com.faceunity.nama.core;

/** NV21 工具：录像/导出路径 RGBA → NV21。 */
public final class Nv21Utils {

    private Nv21Utils() {
    }

    /** RGBA → NV21；{@code flipY=true} 时按 GL readPixels 原点翻到顶左。 */
    public static void rgbaToNv21(byte[] rgba, byte[] nv21, int width, int height, boolean flipY) {
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
}
