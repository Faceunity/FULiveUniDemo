#import "VideoBeautyExporter.h"
#import "BeautyCameraView.h"
#import "CNamaSDK.h"
#import "FuBeautyHandle.h"
#import <AVFoundation/AVFoundation.h>
#import <OpenGLES/EAGL.h>
#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>
#import <UIKit/UIKit.h>
#import <math.h>

#define FU_LOG(fmt, ...) do {} while (0)

typedef struct {
    BOOL ready;
    CVOpenGLESTextureCacheRef textureCache;
    GLuint program;
    GLuint positionAttr;
    GLuint texCoordAttr;
    GLuint textureUniform;
    GLuint mirrorUniform;
    GLuint mirrorYUniform;
    GLuint fbo;
    GLuint fallbackTex;
} FuVideoExportGpu;

static volatile int32_t gFuVideoExportActive = 0;
static volatile int32_t gFuVideoExportCancel = 0;

@implementation VideoBeautyExporter

+ (BOOL)isExportActive {
    return gFuVideoExportActive != 0;
}

+ (void)cancelActiveExport {
    gFuVideoExportCancel = 1;
}

+ (NSString *)normalizePath:(NSString *)path {
    if ([path hasPrefix:@"file://"]) {
        NSURL *url = [NSURL URLWithString:path];
        if (url.path.length > 0) {
            return url.path;
        }
        return [path substringFromIndex:7];
    }
    return path;
}

+ (CGSize)displaySizeForTrack:(AVAssetTrack *)track maxSide:(int)maxSide {
    CGSize nat = track.naturalSize;
    CGAffineTransform t = track.preferredTransform;
    CGSize size = CGSizeApplyAffineTransform(nat, t);
    size.width = fabs(size.width);
    size.height = fabs(size.height);
    if (size.width < 2 || size.height < 2) {
        size = nat;
    }
    double longSide = MAX(size.width, size.height);
    if (maxSide > 0 && longSide > maxSide) {
        double scale = (double)maxSide / longSide;
        size.width = floor(size.width * scale);
        size.height = floor(size.height * scale);
    }
    size.width = ((int)size.width) & ~1;
    size.height = ((int)size.height) & ~1;
    if (size.width < 2) {
        size.width = 2;
    }
    if (size.height < 2) {
        size.height = 2;
    }
    return size;
}

/** VideoToolbox 合成：GPU 完成旋转+缩放到 outSize，导出循环不再 CPU 转像素 */
+ (AVMutableVideoComposition *)gpuCompositionForTrack:(AVAssetTrack *)track
                                             duration:(CMTime)duration
                                              outSize:(CGSize)outSize
                                                  fps:(float)fps {
    CGSize nat = track.naturalSize;
    CGAffineTransform pref = track.preferredTransform;
    CGRect mapped = CGRectApplyAffineTransform(CGRectMake(0, 0, nat.width, nat.height), pref);
    CGFloat dispW = fabs(mapped.size.width);
    CGFloat dispH = fabs(mapped.size.height);
    if (dispW < 2) {
        dispW = nat.width;
    }
    if (dispH < 2) {
        dispH = nat.height;
    }

    CGAffineTransform t = pref;
    t = CGAffineTransformConcat(t, CGAffineTransformMakeTranslation(-mapped.origin.x, -mapped.origin.y));
    CGFloat sx = outSize.width / dispW;
    CGFloat sy = outSize.height / dispH;
    t = CGAffineTransformConcat(t, CGAffineTransformMakeScale(sx, sy));

    AVMutableVideoCompositionLayerInstruction *layer =
        [AVMutableVideoCompositionLayerInstruction videoCompositionLayerInstructionWithAssetTrack:track];
    [layer setTransform:t atTime:kCMTimeZero];

    AVMutableVideoCompositionInstruction *inst = [AVMutableVideoCompositionInstruction videoCompositionInstruction];
    inst.timeRange = CMTimeRangeMake(kCMTimeZero, duration);
    inst.layerInstructions = @[layer];

    AVMutableVideoComposition *comp = [AVMutableVideoComposition videoComposition];
    comp.renderSize = outSize;
    int32_t fpsInt = (int32_t)MAX(1, MIN(60, lroundf(fps)));
    comp.frameDuration = CMTimeMake(1, fpsInt);
    comp.instructions = @[inst];
    return comp;
}

/** 对齐 Android：视频编码完成后再 mux 原音轨（passthrough，稳定不卡 writer） */
+ (BOOL)muxAudioFromAsset:(AVURLAsset *)sourceAsset
          intoVideoAtPath:(NSString *)videoPath
               outputPath:(NSString *)outputPath
                 progress:(void (^)(float))progress
                    error:(NSError **)error {
    NSArray<AVAssetTrack *> *audioTracks = [sourceAsset tracksWithMediaType:AVMediaTypeAudio];
    NSFileManager *fm = [NSFileManager defaultManager];
    if (audioTracks.count == 0) {
        if ([videoPath isEqualToString:outputPath]) {
            return YES;
        }
        [fm removeItemAtPath:outputPath error:nil];
        if (![fm moveItemAtPath:videoPath toPath:outputPath error:error]) {
            return NO;
        }
        return YES;
    }

    AVURLAsset *videoAsset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:videoPath] options:nil];
    AVAssetTrack *vTrack = [[videoAsset tracksWithMediaType:AVMediaTypeVideo] firstObject];
    AVAssetTrack *aTrack = audioTracks.firstObject;
    if (!vTrack || !aTrack) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"合成音轨失败：缺少轨"}];
        }
        return NO;
    }

    AVMutableComposition *composition = [AVMutableComposition composition];
    NSError *composeErr = nil;
    AVMutableCompositionTrack *compVideo =
        [composition addMutableTrackWithMediaType:AVMediaTypeVideo preferredTrackID:kCMPersistentTrackID_Invalid];
    CMTimeRange videoRange = CMTimeRangeMake(kCMTimeZero, videoAsset.duration);
    if (![compVideo insertTimeRange:videoRange ofTrack:vTrack atTime:kCMTimeZero error:&composeErr]) {
        if (error) {
            *error = composeErr ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"插入视频轨失败"}];
        }
        return NO;
    }
    compVideo.preferredTransform = vTrack.preferredTransform;

    AVMutableCompositionTrack *compAudio =
        [composition addMutableTrackWithMediaType:AVMediaTypeAudio preferredTrackID:kCMPersistentTrackID_Invalid];
    CMTime muxDuration = videoAsset.duration;
    if (CMTIME_IS_VALID(sourceAsset.duration) && CMTimeCompare(sourceAsset.duration, muxDuration) < 0) {
        muxDuration = sourceAsset.duration;
    }
    CMTimeRange audioRange = CMTimeRangeMake(kCMTimeZero, muxDuration);
    if (![compAudio insertTimeRange:audioRange ofTrack:aTrack atTime:kCMTimeZero error:&composeErr]) {
        if (error) {
            *error = composeErr ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"插入音频轨失败"}];
        }
        return NO;
    }

    [fm removeItemAtPath:outputPath error:nil];
    AVAssetExportSession *session =
        [AVAssetExportSession exportSessionWithAsset:composition presetName:AVAssetExportPresetPassthrough];
    if (!session) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无法创建合成会话"}];
        }
        return NO;
    }
    session.outputURL = [NSURL fileURLWithPath:outputPath];
    session.outputFileType = AVFileTypeMPEG4;
    session.shouldOptimizeForNetworkUse = YES;

    if (progress) {
        progress(0.95f);
    }

    dispatch_semaphore_t muxSem = dispatch_semaphore_create(0);
    [session exportAsynchronouslyWithCompletionHandler:^{
        dispatch_semaphore_signal(muxSem);
    }];
    dispatch_semaphore_wait(muxSem, DISPATCH_TIME_FOREVER);

    if (session.status != AVAssetExportSessionStatusCompleted) {
        if (error) {
            *error = session.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"音轨合成失败"}];
        }
        return NO;
    }
    [fm removeItemAtPath:videoPath error:nil];
    if (progress) {
        progress(1.f);
    }
    return YES;
}

#pragma mark - GPU export helpers

+ (GLuint)compileShader:(GLenum)type source:(const char *)source {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

+ (GLuint)buildBlitProgram {
    const char *vertex =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "uniform float uMirror;\n"
    "uniform float uMirrorY;\n"
    "varying vec2 vTexCoord;\n"
    "void main() {\n"
    "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
    "  float mx = uMirror > 0.5 ? (1.0 - aTexCoord.x) : aTexCoord.x;\n"
    "  float my = uMirrorY > 0.5 ? (1.0 - aTexCoord.y) : aTexCoord.y;\n"
    "  vTexCoord = vec2(mx, my);\n"
    "}\n";
    const char *fragment =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "void main() {\n"
    "  vec4 c = texture2D(uTexture, vTexCoord);\n"
    "  gl_FragColor = vec4(c.rgb, 1.0);\n"
    "}\n";

    GLuint vs = [self compileShader:GL_VERTEX_SHADER source:vertex];
    GLuint fs = [self compileShader:GL_FRAGMENT_SHADER source:fragment];
    if (!vs || !fs) {
        if (vs) {
            glDeleteShader(vs);
        }
        if (fs) {
            glDeleteShader(fs);
        }
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!linked) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

+ (void)drawTexture:(unsigned int)textureId gpu:(FuVideoExportGpu *)gpu mirrorY:(float)mirrorY {
    static const GLfloat kFullQuad[8] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };
    static const GLfloat kTexCoords[8] = { 0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f };

    glUseProgram(gpu->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(gpu->textureUniform, 0);
    glUniform1f(gpu->mirrorUniform, 0.f);
    glUniform1f(gpu->mirrorYUniform, mirrorY);

    glEnableVertexAttribArray(gpu->positionAttr);
    glVertexAttribPointer(gpu->positionAttr, 2, GL_FLOAT, GL_FALSE, 0, kFullQuad);
    glEnableVertexAttribArray(gpu->texCoordAttr);
    glVertexAttribPointer(gpu->texCoordAttr, 2, GL_FLOAT, GL_FALSE, 0, kTexCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(gpu->positionAttr);
    glDisableVertexAttribArray(gpu->texCoordAttr);
}

+ (BOOL)setupExportGpu:(FuVideoExportGpu *)gpu {
    if (!gpu) {
        return NO;
    }
    memset(gpu, 0, sizeof(*gpu));

    __block BOOL ok = NO;
    [BeautyCameraView performWithSharedGLLock:^{
        fuMakeGLContextCurrent();
        EAGLContext *ctx = [EAGLContext currentContext];
        if (!ctx) {
            return;
        }
        [EAGLContext setCurrentContext:ctx];
        fuSetForceUseGL2(1);

        CVReturn cacheRet = CVOpenGLESTextureCacheCreate(
            kCFAllocatorDefault,
            NULL,
            ctx,
            NULL,
            &gpu->textureCache
        );
        if (cacheRet != kCVReturnSuccess || !gpu->textureCache) {
            return;
        }

        gpu->program = [self buildBlitProgram];
        if (gpu->program == 0) {
            return;
        }
        gpu->positionAttr = (GLuint)glGetAttribLocation(gpu->program, "aPosition");
        gpu->texCoordAttr = (GLuint)glGetAttribLocation(gpu->program, "aTexCoord");
        gpu->textureUniform = (GLuint)glGetUniformLocation(gpu->program, "uTexture");
        gpu->mirrorUniform = (GLuint)glGetUniformLocation(gpu->program, "uMirror");
        gpu->mirrorYUniform = (GLuint)glGetUniformLocation(gpu->program, "uMirrorY");

        glGenFramebuffers(1, &gpu->fbo);
        glGenTextures(1, &gpu->fallbackTex);
        glBindTexture(GL_TEXTURE_2D, gpu->fallbackTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        gpu->ready = (gpu->fbo != 0 && gpu->fallbackTex != 0);
        ok = gpu->ready;
    }];
    return ok;
}

+ (void)teardownExportGpu:(FuVideoExportGpu *)gpu {
    if (!gpu || !gpu->ready) {
        return;
    }
    [BeautyCameraView performWithSharedGLLock:^{
        fuMakeGLContextCurrent();
        if (gpu->fallbackTex) {
            glDeleteTextures(1, &gpu->fallbackTex);
            gpu->fallbackTex = 0;
        }
        if (gpu->fbo) {
            glDeleteFramebuffers(1, &gpu->fbo);
            gpu->fbo = 0;
        }
        if (gpu->program) {
            glDeleteProgram(gpu->program);
            gpu->program = 0;
        }
        if (gpu->textureCache) {
            CVOpenGLESTextureCacheFlush(gpu->textureCache, 0);
            CFRelease(gpu->textureCache);
            gpu->textureCache = NULL;
        }
        gpu->ready = NO;
    }];
}

+ (unsigned int)uploadBGRAToFallback:(FuVideoExportGpu *)gpu
                                data:(void *)fuInput
                               width:(int)width
                              height:(int)height {
    size_t rowBytes = (size_t)width * 4;
    size_t imgBytes = rowBytes * (size_t)height;
    unsigned char *flippedUpload = (unsigned char *)malloc(imgBytes);
    if (flippedUpload) {
        for (int row = 0; row < height; row++) {
            memcpy(flippedUpload + (size_t)row * rowBytes,
                   (unsigned char *)fuInput + (size_t)(height - 1 - row) * rowBytes,
                   rowBytes);
        }
    }
    const void *uploadPtr = flippedUpload ? flippedUpload : fuInput;
    glBindTexture(GL_TEXTURE_2D, gpu->fallbackTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA,
        width,
        height,
        0,
        GL_BGRA,
        GL_UNSIGNED_BYTE,
        uploadPtr
    );
    if (flippedUpload) {
        free(flippedUpload);
    }
    return gpu->fallbackTex;
}

/**
 * GPU 导出单帧：src BGRA → fuRender 纹理输出 → FBO 直写 dst IOSurface（无 glReadPixels / 无 src→dst memcpy）
 */
+ (BOOL)renderBeautyFrameGpu:(FuVideoExportGpu *)gpu
              srcPixelBuffer:(CVPixelBufferRef)src
              dstPixelBuffer:(CVPixelBufferRef)dst
                       width:(int)width
                      height:(int)height
                     frameId:(int)frameId
                beautyHandle:(int)beautyHandle {
    if (!gpu || !gpu->ready || !src || !dst || beautyHandle <= 0 || width <= 0 || height <= 0) {
        return NO;
    }

    __block BOOL ok = NO;
    [BeautyCameraView performWithSharedGLLock:^{
        fuMakeGLContextCurrent();
        EAGLContext *ctx = [EAGLContext currentContext];
        if (ctx) {
            [EAGLContext setCurrentContext:ctx];
        }

        fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
        fuSetInputCameraMatrix(0, 0, FU_ROTATION_MODE_0);
        fuSetInputCameraBufferMatrix(CCROT0);
        fuSetInputCameraBufferMatrixState(true);
        fuSetInputCameraTextureMatrixState(false);
        fuSetOutputMatrixState(false);
        fuSetFaceProcessorDetectMode(1);
        fuSetOutputResolution(width, height);
        [BeautyCameraView flushPendingBeautyParams];
        FuReconfirmSpecialBeautySwitches(beautyHandle);

        CVPixelBufferLockBaseAddress(src, kCVPixelBufferLock_ReadOnly);
        void *baseAddr = CVPixelBufferGetBaseAddress(src);
        size_t stride = CVPixelBufferGetBytesPerRow(src);
        if (!baseAddr) {
            CVPixelBufferUnlockBaseAddress(src, kCVPixelBufferLock_ReadOnly);
            return;
        }

        void *fuInput = baseAddr;
        unsigned char *packed = NULL;
        size_t expect = (size_t)width * 4;
        if (stride != expect) {
            packed = (unsigned char *)malloc(expect * (size_t)height);
            if (!packed) {
                CVPixelBufferUnlockBaseAddress(src, kCVPixelBufferLock_ReadOnly);
                return;
            }
            for (int row = 0; row < height; row++) {
                memcpy(packed + (size_t)row * expect,
                       (unsigned char *)baseAddr + (size_t)row * stride,
                       expect);
            }
            fuInput = packed;
        }

        int items[1] = { beautyHandle };
        int flags = NAMA_RENDER_FEATURE_FULL | NAMA_RENDER_OPTION_FORCE_OUTPUT_ALPHA_ONE;
        FuUpdateBeautyBlurEffect(beautyHandle);

        unsigned int outTex = 0;
        int ret = fuRender(
            FU_FORMAT_RGBA_TEXTURE,
            &outTex,
            FU_FORMAT_BGRA_BUFFER,
            fuInput,
            width,
            height,
            frameId,
            items,
            1,
            flags,
            NULL
        );

        unsigned int drawTex = 0;
        if (outTex > 0 && ret > 0 && fuGetSystemError() == 0) {
            drawTex = outTex;
        } else {
            ret = fuRender(
                FU_FORMAT_BGRA_BUFFER,
                fuInput,
                FU_FORMAT_BGRA_BUFFER,
                fuInput,
                width,
                height,
                frameId,
                items,
                1,
                flags,
                NULL
            );
            if (ret >= 0 && fuGetSystemError() == 0) {
                drawTex = [self uploadBGRAToFallback:gpu data:fuInput width:width height:height];
            }
        }

        if (packed) {
            free(packed);
        }
        CVPixelBufferUnlockBaseAddress(src, kCVPixelBufferLock_ReadOnly);

        if (drawTex == 0) {
            return;
        }

        CVPixelBufferLockBaseAddress(dst, 0);
        CVOpenGLESTextureRef dstTexRef = NULL;
        CVReturn texRet = CVOpenGLESTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            gpu->textureCache,
            dst,
            NULL,
            GL_TEXTURE_2D,
            GL_RGBA,
            width,
            height,
            GL_BGRA,
            GL_UNSIGNED_BYTE,
            0,
            &dstTexRef
        );
        if (texRet != kCVReturnSuccess || !dstTexRef) {
            CVPixelBufferUnlockBaseAddress(dst, 0);
            return;
        }

        GLuint dstTexId = CVOpenGLESTextureGetName(dstTexRef);
        GLenum dstTarget = CVOpenGLESTextureGetTarget(dstTexRef);

        GLint prevFbo = 0;
        GLint prevViewport[4] = {0, 0, 0, 0};
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
        glGetIntegerv(GL_VIEWPORT, prevViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, gpu->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, dstTarget, dstTexId, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            CFRelease(dstTexRef);
            CVPixelBufferUnlockBaseAddress(dst, 0);
            return;
        }

        glViewport(0, 0, width, height);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        // 导出写入 CVPixelBuffer（顶左原点），勿用预览 mirrorY=1（会导致成片上下颠倒）
        [self drawTexture:drawTex gpu:gpu mirrorY:0.f];
        glFinish();

        glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        CFRelease(dstTexRef);
        CVOpenGLESTextureCacheFlush(gpu->textureCache, 0);
        CVPixelBufferUnlockBaseAddress(dst, 0);
        ok = YES;
    }];
    return ok;
}

/** CPU 回退：BGRA 原地美颜 */
+ (BOOL)renderBeautyInPlaceBGRA:(unsigned char *)bgra
                          width:(int)width
                         height:(int)height
                          stride:(size_t)stride
                        frameId:(int)frameId
                   beautyHandle:(int)beautyHandle {
    if (beautyHandle <= 0 || !bgra || width <= 0 || height <= 0) {
        return NO;
    }
    unsigned char *fuInput = bgra;
    unsigned char *packed = NULL;
    size_t expect = (size_t)width * 4;
    if (stride != expect) {
        size_t packedSize = expect * (size_t)height;
        packed = (unsigned char *)malloc(packedSize);
        if (!packed) {
            return NO;
        }
        for (int row = 0; row < height; row++) {
            memcpy(packed + (size_t)row * expect,
                   bgra + (size_t)row * stride,
                   expect);
        }
        fuInput = packed;
    }

    __block BOOL ok = NO;
    [BeautyCameraView performWithSharedGLLock:^{
        fuMakeGLContextCurrent();
        fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
        fuSetInputCameraMatrix(0, 0, FU_ROTATION_MODE_0);
        fuSetInputCameraBufferMatrix(CCROT0);
        fuSetInputCameraBufferMatrixState(true);
        fuSetInputCameraTextureMatrixState(false);
        fuSetOutputMatrixState(false);
        fuSetFaceProcessorDetectMode(1);
        fuSetOutputResolution(width, height);
        [BeautyCameraView flushPendingBeautyParams];
        FuReconfirmSpecialBeautySwitches(beautyHandle);
        int items[1] = { beautyHandle };
        int flags = NAMA_RENDER_FEATURE_FULL | NAMA_RENDER_OPTION_FORCE_OUTPUT_ALPHA_ONE;
        int ret = fuRender(
            FU_FORMAT_BGRA_BUFFER,
            fuInput,
            FU_FORMAT_BGRA_BUFFER,
            fuInput,
            width,
            height,
            frameId,
            items,
            1,
            flags,
            NULL
        );
        ok = (ret >= 0 && fuGetSystemError() == 0);
    }];

    if (ok && packed) {
        for (int row = 0; row < height; row++) {
            memcpy(bgra + (size_t)row * stride,
                   packed + (size_t)row * expect,
                   expect);
        }
    }
    if (packed) {
        free(packed);
    }
    return ok;
}

+ (void)exportVideoAtPath:(NSString *)path
             beautyHandle:(int)beautyHandle
           maxDurationSec:(NSTimeInterval)maxDurationSec
                  maxSide:(int)maxSide
               completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    [self exportVideoAtPath:path
               beautyHandle:beautyHandle
             maxDurationSec:maxDurationSec
                    maxSide:maxSide
                   progress:nil
                 completion:completion];
}

+ (void)exportVideoAtPath:(NSString *)path
             beautyHandle:(int)beautyHandle
           maxDurationSec:(NSTimeInterval)maxDurationSec
                  maxSide:(int)maxSide
                 progress:(void (^)(float))progress
               completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        @autoreleasepool {
            NSError *err = nil;
            NSString *outPath = [self exportSync:path
                                    beautyHandle:beautyHandle
                                  maxDurationSec:maxDurationSec
                                         maxSide:maxSide
                                        progress:progress
                                           error:&err];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (completion) {
                    completion(outPath, err);
                }
            });
        }
    });
}

+ (NSString *)exportSync:(NSString *)path
            beautyHandle:(int)beautyHandle
          maxDurationSec:(NSTimeInterval)maxDurationSec
                 maxSide:(int)maxSide
                progress:(void (^)(float))progress
                   error:(NSError **)error {
    if (beautyHandle <= 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"请先 loadBundle"}];
        }
        return nil;
    }
    NSString *real = [self normalizePath:path];
    if (real.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:real]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"视频文件不存在"}];
        }
        return nil;
    }

    NSError *glErr = nil;
    if (![BeautyCameraView ensureSharedGLContextReady:&glErr]) {
        if (error) {
            *error = glErr;
        }
        return nil;
    }

    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:real] options:nil];
    NSArray *videoTracks = [asset tracksWithMediaType:AVMediaTypeVideo];
    if (videoTracks.count == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无视频轨"}];
        }
        return nil;
    }
    AVAssetTrack *videoTrack = videoTracks.firstObject;
    CMTime duration = asset.duration;
    Float64 durationSec = CMTimeGetSeconds(duration);
    if (maxDurationSec > 0 && durationSec > maxDurationSec + 0.05) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey:
                [NSString stringWithFormat:@"视频超过 %.0f 秒限制", maxDurationSec]}];
        }
        return nil;
    }

    int sideLimit = maxSide > 0 ? maxSide : 1280;
    if (sideLimit > 1280) {
        sideLimit = 1280;
    }
    CGSize outSize = [self displaySizeForTrack:videoTrack maxSide:sideLimit];
    float fps = videoTrack.nominalFrameRate > 1.f ? videoTrack.nominalFrameRate : 30.f;
    if (fps > 30.f) {
        fps = 30.f;
    }

    NSError *readerErr = nil;
    AVAssetReader *reader = [[AVAssetReader alloc] initWithAsset:asset error:&readerErr];
    if (!reader) {
        if (error) {
            *error = readerErr ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无法读取视频"}];
        }
        return nil;
    }

    NSDictionary *outSettings = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
    };
    AVMutableVideoComposition *comp = [self gpuCompositionForTrack:videoTrack
                                                          duration:duration
                                                           outSize:outSize
                                                               fps:fps];
    AVAssetReaderVideoCompositionOutput *videoOut =
        [[AVAssetReaderVideoCompositionOutput alloc] initWithVideoTracks:@[videoTrack]
                                                           videoSettings:outSettings];
    videoOut.videoComposition = comp;
    videoOut.alwaysCopiesSampleData = NO;
    if (![reader canAddOutput:videoOut]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无法添加视频输出"}];
        }
        return nil;
    }
    [reader addOutput:videoOut];

    BOOL sourceHasAudio = [asset tracksWithMediaType:AVMediaTypeAudio].count > 0;
    NSString *finalPath = [NSTemporaryDirectory() stringByAppendingPathComponent:
        [NSString stringWithFormat:@"fu_video_%.0f.mp4", [[NSDate date] timeIntervalSince1970] * 1000]];
    NSString *tempVideoPath = sourceHasAudio
        ? [NSTemporaryDirectory() stringByAppendingPathComponent:
            [NSString stringWithFormat:@"fu_video_v_%.0f.mp4", [[NSDate date] timeIntervalSince1970] * 1000]]
        : finalPath;
    [[NSFileManager defaultManager] removeItemAtPath:tempVideoPath error:nil];
    [[NSFileManager defaultManager] removeItemAtPath:finalPath error:nil];
    NSURL *outURL = [NSURL fileURLWithPath:tempVideoPath];

    NSError *writerErr = nil;
    AVAssetWriter *writer = [[AVAssetWriter alloc] initWithURL:outURL
                                                      fileType:AVFileTypeMPEG4
                                                         error:&writerErr];
    if (!writer) {
        if (error) {
            *error = writerErr;
        }
        return nil;
    }

    int width = (int)outSize.width;
    int height = (int)outSize.height;
    NSDictionary *videoSettings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @(width),
        AVVideoHeightKey: @(height),
        AVVideoCompressionPropertiesKey: @{
            AVVideoAverageBitRateKey: @(MAX(1200000, width * height * 3)),
            AVVideoMaxKeyFrameIntervalKey: @((int)fps),
            AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel,
        },
    };
    AVAssetWriterInput *videoIn = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                                                     outputSettings:videoSettings];
    videoIn.expectsMediaDataInRealTime = NO;
    videoIn.transform = CGAffineTransformIdentity;

    NSDictionary *srcPixelAttrs = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (id)kCVPixelBufferWidthKey: @(width),
        (id)kCVPixelBufferHeightKey: @(height),
        (id)kCVPixelBufferOpenGLESCompatibilityKey: @YES,
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
    };
    AVAssetWriterInputPixelBufferAdaptor *adaptor =
        [AVAssetWriterInputPixelBufferAdaptor assetWriterInputPixelBufferAdaptorWithAssetWriterInput:videoIn
                                                                         sourcePixelBufferAttributes:srcPixelAttrs];
    if (![writer canAddInput:videoIn]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无法添加视频写入"}];
        }
        return nil;
    }
    [writer addInput:videoIn];

    if (![reader startReading]) {
        if (error) {
            *error = reader.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"startReading 失败"}];
        }
        return nil;
    }
    if (![writer startWriting]) {
        if (error) {
            *error = writer.error;
        }
        return nil;
    }
    [writer startSessionAtSourceTime:kCMTimeZero];

    EAGLContext *prev = [EAGLContext currentContext];
    [BeautyCameraView ensureSharedGLContextReady:nil];
    fuSetFaceProcessorDetectMode(1);
    fuSetOutputResolution(width, height);

    FuVideoExportGpu gpu;
    BOOL useGpu = [self setupExportGpu:&gpu];
    gFuVideoExportActive = 1;
    gFuVideoExportCancel = 0;
    [BeautyCameraView performWithSharedGLLock:^{
        [BeautyCameraView flushPendingBeautyParams];
    }];

    int estimatedFrames = MAX(1, (int)lround(durationSec * fps));
    int frameId = 200000;
    NSError *loopErr = nil;
    int videoFrames = 0;
    NSDictionary *pixelAttrs = srcPixelAttrs;

    while (true) {
        @autoreleasepool {
            if (gFuVideoExportCancel) {
                loopErr = [NSError errorWithDomain:@"FaceUnityNama" code:-2
                                         userInfo:@{NSLocalizedDescriptionKey: @"导出已取消"}];
                break;
            }
            if (!videoIn.isReadyForMoreMediaData) {
                if (reader.status == AVAssetReaderStatusFailed ||
                    reader.status == AVAssetReaderStatusCancelled) {
                    loopErr = reader.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                             userInfo:@{NSLocalizedDescriptionKey: @"读取视频失败"}];
                    break;
                }
                if (reader.status == AVAssetReaderStatusCompleted) {
                    break;
                }
                [NSThread sleepForTimeInterval:0.005];
                continue;
            }

            CMSampleBufferRef sample = [videoOut copyNextSampleBuffer];
            if (!sample) {
                break;
            }
            CVPixelBufferRef src = CMSampleBufferGetImageBuffer(sample);
            CMTime pts = CMSampleBufferGetPresentationTimeStamp(sample);
            if (!src) {
                CFRelease(sample);
                continue;
            }

            CVPixelBufferRef dst = NULL;
            CVReturn cvRet = CVPixelBufferPoolCreatePixelBuffer(NULL, adaptor.pixelBufferPool, &dst);
            if (cvRet != kCVReturnSuccess || !dst) {
                CVPixelBufferCreate(kCFAllocatorDefault, width, height,
                                    kCVPixelFormatType_32BGRA,
                                    (__bridge CFDictionaryRef)pixelAttrs, &dst);
            }
            if (!dst) {
                CFRelease(sample);
                loopErr = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"创建输出帧失败"}];
                break;
            }

            frameId += 1;
            BOOL rendered = NO;
            if (useGpu) {
                rendered = [self renderBeautyFrameGpu:&gpu
                                       srcPixelBuffer:src
                                       dstPixelBuffer:dst
                                                width:width
                                               height:height
                                              frameId:frameId
                                         beautyHandle:beautyHandle];
            }
            if (!rendered) {
                CVPixelBufferLockBaseAddress(src, kCVPixelBufferLock_ReadOnly);
                CVPixelBufferLockBaseAddress(dst, 0);
                void *srcBase = CVPixelBufferGetBaseAddress(src);
                void *dstBase = CVPixelBufferGetBaseAddress(dst);
                size_t srcStride = CVPixelBufferGetBytesPerRow(src);
                size_t dstStride = CVPixelBufferGetBytesPerRow(dst);
                if (srcBase && dstBase) {
                    for (int row = 0; row < height; row++) {
                        memcpy((unsigned char *)dstBase + (size_t)row * dstStride,
                               (unsigned char *)srcBase + (size_t)row * srcStride,
                               (size_t)width * 4);
                    }
                    rendered = [self renderBeautyInPlaceBGRA:(unsigned char *)dstBase
                                                       width:width
                                                      height:height
                                                       stride:dstStride
                                                      frameId:frameId
                                                 beautyHandle:beautyHandle];
                }
                CVPixelBufferUnlockBaseAddress(src, kCVPixelBufferLock_ReadOnly);
                CVPixelBufferUnlockBaseAddress(dst, 0);
            }

            CFRelease(sample);
            if (!rendered) {
                FU_LOG("frame render failed id=%d", frameId);
            }

            if (![adaptor appendPixelBuffer:dst withPresentationTime:pts]) {
                loopErr = writer.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"写入视频帧失败"}];
                CVPixelBufferRelease(dst);
                break;
            }
            CVPixelBufferRelease(dst);
            videoFrames += 1;
            if (progress && durationSec > 0.01) {
                Float64 t = CMTimeGetSeconds(pts);
                float byPts = (float)(t / durationSec * 0.92);
                float byFrame = (float)videoFrames / (float)estimatedFrames * 0.92f;
                progress(MAX(0.f, MIN(0.92f, MAX(byPts, byFrame))));
            }
        }
    }
    [videoIn markAsFinished];
    FU_LOG("export video frames=%d duration=%.2f", videoFrames, durationSec);

    [self teardownExportGpu:&gpu];
    gFuVideoExportActive = 0;
    gFuVideoExportCancel = 0;
    [EAGLContext setCurrentContext:prev];
    fuSetFaceProcessorDetectMode(1);

    if (loopErr) {
        [writer cancelWriting];
        [reader cancelReading];
        [[NSFileManager defaultManager] removeItemAtPath:tempVideoPath error:nil];
        if (error) {
            *error = loopErr;
        }
        return nil;
    }

    if (videoFrames < 1) {
        [writer cancelWriting];
        [reader cancelReading];
        [[NSFileManager defaultManager] removeItemAtPath:tempVideoPath error:nil];
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"未写入任何视频帧"}];
        }
        return nil;
    }

    dispatch_semaphore_t finishSem = dispatch_semaphore_create(0);
    __block BOOL finishOk = NO;
    [writer finishWritingWithCompletionHandler:^{
        finishOk = (writer.status == AVAssetWriterStatusCompleted);
        dispatch_semaphore_signal(finishSem);
    }];
    dispatch_semaphore_wait(finishSem, DISPATCH_TIME_FOREVER);
    [reader cancelReading];

    if (!finishOk) {
        [[NSFileManager defaultManager] removeItemAtPath:tempVideoPath error:nil];
        if (error) {
            *error = writer.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"视频编码失败"}];
        }
        return nil;
    }

    NSString *outPath = finalPath;
    if (sourceHasAudio) {
        if (progress) {
            progress(0.93f);
        }
        NSError *muxErr = nil;
        if (![self muxAudioFromAsset:asset
                     intoVideoAtPath:tempVideoPath
                          outputPath:finalPath
                            progress:progress
                               error:&muxErr]) {
            [[NSFileManager defaultManager] removeItemAtPath:tempVideoPath error:nil];
            if (error) {
                *error = muxErr;
            }
            return nil;
        }
    } else if (progress) {
        progress(1.f);
    }

    FU_LOG("export ok %@ %dx%d gpu=%d frames=%d", outPath, width, height, useGpu ? 1 : 0, videoFrames);
    return outPath;
}

@end
