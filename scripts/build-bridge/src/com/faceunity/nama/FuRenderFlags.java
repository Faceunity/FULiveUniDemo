package com.faceunity.nama;

import com.faceunity.wrapper.faceunity;

/**
 * 对齐 CNamaSDK.h / iOS BeautyCameraView：{@code fuRender} 的 func_flag。
 * {@code fuRenderTexture} / {@code fuRender} 的 func_flag（2D 纹理、buffer）。
 * 相机预览须用 FULL，ItemsEx2（fuDualInputToTexture）不含高级美颜。
 */
final class FuRenderFlags {

    private static final int FEATURE_TRACK_FACE = 0x10;
    private static final int FEATURE_BEAUTIFY_IMAGE = 0x20;
    private static final int FEATURE_RENDER = 0x40;
    private static final int FEATURE_ADDITIONAL_DETECTOR = 0x80;
    private static final int FEATURE_RENDER_ITEM = 0x100;
    static final int FEATURE_FULL = FEATURE_RENDER_ITEM | FEATURE_TRACK_FACE
            | FEATURE_BEAUTIFY_IMAGE | FEATURE_RENDER | FEATURE_ADDITIONAL_DETECTOR;
    static final int OPTION_FORCE_OUTPUT_ALPHA_ONE = 0x8000;

    private FuRenderFlags() {
    }

    /** CNamaSDK {@code FU_FORMAT_NV21_BUFFER} */
    static final int FORMAT_NV21_BUFFER = 2;

    /** fuRenderDualInput 第 6 参：OES 外部纹理；勿混用 iOS fuRender 的 FEATURE_FULL / OPTION_FORCE */
    static int fuRenderDualInputFlags() {
        return faceunity.FU_ADM_FLAG_EXTERNAL_OES_TEXTURE;
    }

    /** RGBA readback（ItemsEx2：fuRenderToRgbaImage） */
    static int rgbaReadbackFlags() {
        return faceunity.FU_ADM_FLAG_ENABLE_READBACK;
    }
}
