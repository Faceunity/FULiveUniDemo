package com.faceunity.nama;

/**
 * 历史兼容占位：曾尝试 JNI 注入未公开的 FUAI_FaceProcessorSetUse*。
 * 该路径会闪退，已彻底禁用；皮肤分割/祛斑等只用公开 API
 * （fuSetFaceAlgorithmConfig / enable_skinseg / ARMeshV2 等）。
 */
final class FuAiExtras {

    private FuAiExtras() {
    }

    static void resetSetUseApplied() {
        // no-op
    }

    static boolean isSetUseApplied() {
        return false;
    }

    /** @return 永远 false：不再调用未公开 SetUse* */
    static boolean tryApplySetUseAfterRender() {
        return false;
    }
}
