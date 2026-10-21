//
//  VideoBeautyExporter.m
//
//  FURenderKit 版离线视频美颜导出：把自建 AVAssetReader/AVAssetWriter + 旧 C SDK fuRender 的链路
//  替换为官方 FUVideoReader + FUVideoWriter + [FURenderKit shareRenderKit] renderWithInput。
//
//  设计（对齐官方 FUVideoProcessor.startProcessing）：
//  - writer 的 videoInputReadyHandler / audioInputReadyHandler 驱动 reader 逐帧读取（背压安全，
//    writer 输入就绪才读下一帧，避免解码过快撑爆内存或丢帧）。
//  - 每帧在 delegate 里 renderWithInput（pixelBuffer + imageOrientation + customOutputSize 缩放到
//    maxSide），返回美颜后 buffer 交给 writer appendPixelBuffer。
//  - 音频由 reader 解码为 LPCM，逐帧透传 writer appendAudioSampleBuffer。
//  - reader 读完触发 videoReaderDidFinishReading → writer finishWritingWithCompletion。
//
//  不再调用 fuRender / FuReconfirmSpecialBeautySwitches / FuUpdateBeautyBlurEffect / muxAudioFromAsset。
//

#import "VideoBeautyExporter.h"
#import "FURenderKitCameraView.h"
#import <FURenderKit/FURenderKit.h>
#import <FURenderKit/FUVideoReader.h>
#import <FURenderKit/FUVideoWriter.h>
#import <FURenderKit/FUVideoComponentDefines.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <UIKit/UIKit.h>
#import <math.h>

#define FU_LOG(fmt, ...) do { } while (0)

static volatile int32_t gFuVideoExportActive = 0;

#pragma mark - 任务状态

@interface FuVideoExportTask : NSObject
@property (nonatomic, assign) BOOL failed;
@property (nonatomic, assign) BOOL finished;
@property (nonatomic, copy) NSError *error;
@property (nonatomic, assign) double totalDuration;
@property (nonatomic, copy) void (^progress)(float);
@property (nonatomic, copy) void (^completion)(NSString *outPath, NSError *error);
@property (nonatomic, copy) NSString *outPath;
@end

@implementation FuVideoExportTask
@end

#pragma mark - Reader delegate 中转对象

@interface FuVideoExportReaderProxy : NSObject <FUVideoReaderDelegate>
@property (nonatomic, copy) void (^onVideoBuffer)(CVPixelBufferRef buffer, CMTime pts);
@property (nonatomic, copy) void (^onAudioBuffer)(CMSampleBufferRef sample);
@property (nonatomic, copy) void (^onFinishReading)(void);
@end

@implementation FuVideoExportReaderProxy

- (void)videoReaderDidOutputVideoSampleBuffer:(CMSampleBufferRef)videoSampleBuffer {
    CVPixelBufferRef buffer = CMSampleBufferGetImageBuffer(videoSampleBuffer);
    CMTime pts = CMSampleBufferGetPresentationTimeStamp(videoSampleBuffer);
    if (self.onVideoBuffer && buffer) {
        self.onVideoBuffer(buffer, pts);
    }
    // FUVideoReader 回调的 sampleBuffer 需要调用方释放（官方 demo 同样处理）
    CMSampleBufferInvalidate(videoSampleBuffer);
    CFRelease(videoSampleBuffer);
}

- (void)videoReaderDidOutputAudioSampleBuffer:(CMSampleBufferRef)audioSampleBuffer {
    if (self.onAudioBuffer) {
        self.onAudioBuffer(audioSampleBuffer);
    }
    CMSampleBufferInvalidate(audioSampleBuffer);
    CFRelease(audioSampleBuffer);
}

- (void)videoReaderDidFinishReading {
    if (self.onFinishReading) {
        self.onFinishReading();
    }
}

@end

#pragma mark - 导出上下文（持有 reader/writer/proxy/task，单次导出存活）

@interface FuVideoExportContext : NSObject
@property (nonatomic, strong) FUVideoReader *reader;
@property (nonatomic, strong) FUVideoWriter *writer;
@property (nonatomic, strong) FuVideoExportTask *task;
@property (nonatomic, strong) FuVideoExportReaderProxy *proxy;
@property (nonatomic, assign) BOOL finished;
@end

@implementation FuVideoExportContext
@end

static FuVideoExportContext *gActiveExportContext = nil;

@implementation VideoBeautyExporter

+ (BOOL)isExportActive {
    return gFuVideoExportActive != 0;
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

/**
 * 计算输出尺寸（在“自然像素方向”上）。
 * 官方 FUVideoReader/FUVideoWriter 链路：reader 输出的帧是视频轨的自然尺寸（未应用
 * preferredTransform），方向信息通过 writer 的 videoOrientation 旋转元数据在播放时纠正。
 * 因此 maxSide 缩放必须作用在自然尺寸上，缩放比例取自“显示尺寸”（旋转后）的长边。
 */
+ (CGSize)outputSizeForTrack:(AVAssetTrack *)track maxSide:(int)maxSide {
    CGSize nat = track.naturalSize;
    CGAffineTransform t = track.preferredTransform;
    CGSize disp = CGSizeApplyAffineTransform(nat, t);
    disp.width = fabs(disp.width);
    disp.height = fabs(disp.height);
    double longDisp = MAX(disp.width, disp.height);
    if (longDisp < 2) {
        longDisp = MAX(nat.width, nat.height);
    }
    double scale = 1.0;
    if (maxSide > 0 && longDisp > maxSide) {
        scale = (double)maxSide / longDisp;
    }
    double w = floor(nat.width * scale);
    double h = floor(nat.height * scale);
    int iw = ((int)w) & ~1;
    int ih = ((int)h) & ~1;
    if (iw < 2) {
        iw = 2;
    }
    if (ih < 2) {
        ih = 2;
    }
    return CGSizeMake(iw, ih);
}

+ (FUImageOrientation)imageOrientationForReaderOrientation:(FUVideoOrientation)orientation {
    switch (orientation) {
        case FUVideoOrientationLandscapeRight:
            return FUImageOrientationLeft;
        case FUVideoOrientationUpsideDown:
            return FUImageOrientationDown;
        case FUVideoOrientationLandscapeLeft:
            return FUImageOrientationRight;
        case FUVideoOrientationPortrait:
        default:
            return FUImageOrientationUP;
    }
}

#pragma mark - 公开接口

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
            [self exportSyncWithPath:path
                        beautyHandle:beautyHandle
                      maxDurationSec:maxDurationSec
                             maxSide:maxSide
                            progress:progress
                          completion:completion];
        }
    });
}

+ (void)exportSyncWithPath:(NSString *)path
              beautyHandle:(int)beautyHandle
            maxDurationSec:(NSTimeInterval)maxDurationSec
                   maxSide:(int)maxSide
                  progress:(void (^)(float))progress
                completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    void (^fail)(NSError *) = ^(NSError *e) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (completion) {
                completion(nil, e);
            }
        });
    };

    if (beautyHandle <= 0) {
        fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                             userInfo:@{NSLocalizedDescriptionKey: @"请先 loadBundle"}]);
        return;
    }
    if (path.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                             userInfo:@{NSLocalizedDescriptionKey: @"视频文件不存在"}]);
        return;
    }

    NSError *glErr = nil;
    if (![FURenderKitCameraView ensureSharedGLContextReady:&glErr]) {
        fail(glErr);
        return;
    }

    NSURL *url = [NSURL fileURLWithPath:path];
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:url options:nil];
    NSArray<AVAssetTrack *> *videoTracks = [asset tracksWithMediaType:AVMediaTypeVideo];
    if (videoTracks.count == 0) {
        fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                             userInfo:@{NSLocalizedDescriptionKey: @"无视频轨"}]);
        return;
    }
    AVAssetTrack *videoTrack = videoTracks.firstObject;

    CMTime duration = asset.duration;
    Float64 durationSec = CMTimeGetSeconds(duration);
    if (maxDurationSec > 0 && durationSec > maxDurationSec + 0.05) {
        fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                             userInfo:@{NSLocalizedDescriptionKey:
                [NSString stringWithFormat:@"视频超过 %.0f 秒限制", maxDurationSec]}]);
        return;
    }

    int sideLimit = maxSide > 0 ? maxSide : 1280;
    if (sideLimit > 1280) {
        sideLimit = 1280;
    }
    CGSize outSize = [self outputSizeForTrack:videoTrack maxSide:sideLimit];
    int outW = (int)outSize.width;
    int outH = (int)outSize.height;

    BOOL sourceHasAudio = [asset tracksWithMediaType:AVMediaTypeAudio].count > 0;

    // 源音频原生采样率/声道：reader 解码为 LPCM 后，writer 的 AAC 输入必须匹配，否则编码失败
    double sourceAudioRate = 0.0;
    NSInteger sourceAudioChannels = 0;
    if (sourceHasAudio) {
        AVAssetTrack *audioTrack = [asset tracksWithMediaType:AVMediaTypeAudio].firstObject;
        NSArray *descs = audioTrack.formatDescriptions;
        if (descs.count > 0) {
            CMAudioFormatDescriptionRef desc = (__bridge CMAudioFormatDescriptionRef)descs[0];
            AudioStreamBasicDescription asbd = *CMAudioFormatDescriptionGetStreamBasicDescription(desc);
            sourceAudioRate = asbd.mSampleRate;
            sourceAudioChannels = asbd.mChannelsPerFrame;
        }
    }

    NSString *outPath = [NSTemporaryDirectory() stringByAppendingPathComponent:
        [NSString stringWithFormat:@"fu_video_%.0f.mp4", [[NSDate date] timeIntervalSince1970] * 1000]];
    [[NSFileManager defaultManager] removeItemAtPath:outPath error:nil];
    NSURL *outURL = [NSURL fileURLWithPath:outPath];

    // ---- Reader：由 writer 的 ready handler 驱动，不自动读 ----
    FUVideoReaderSettings *readerSettings = [[FUVideoReaderSettings alloc] init];
    readerSettings.readingAutomatically = NO;
    readerSettings.videoOutputFormat = kCVPixelFormatType_32BGRA;
    readerSettings.needsAudioTrack = sourceHasAudio;
    // 导出不按真实码率节流（writer 背压已限速），让导出尽可能快
    readerSettings.readAtVideoRate = NO;

    FUVideoReader *reader = [[FUVideoReader alloc] initWithURL:url settings:readerSettings];

    // ---- Writer：输出尺寸可自定义（maxSide 缩放）----
    // 音频：reader 解码为 LPCM，writer 用 AAC 编码；采样率/声道对齐源轨道。
    FUVideoWriterSettings *writerSettings = [[FUVideoWriterSettings alloc] init];
    writerSettings.needsAudioTrack = sourceHasAudio;
    writerSettings.fileType = AVFileTypeMPEG4;
    writerSettings.isRealTimeData = NO;
    writerSettings.videoOrientation = reader.videoOrientation;
    writerSettings.videoInputFormat = AVVideoCodecTypeH264;
    if (sourceHasAudio) {
        if (sourceAudioRate > 0) {
            writerSettings.audioRate = sourceAudioRate;
        }
        if (sourceAudioChannels > 0) {
            writerSettings.audioChannels = sourceAudioChannels;
        }
    }

    FUVideoWriter *writer = [[FUVideoWriter alloc] initWithVideoURL:outURL
                                                          videoSize:CGSizeMake(outW, outH)
                                                            setting:writerSettings];

    // ---- 上下文 ----
    FuVideoExportTask *task = [[FuVideoExportTask alloc] init];
    task.totalDuration = durationSec;
    task.outPath = outPath;
    task.progress = progress;
    task.completion = completion;

    FuVideoExportContext *ctx = [[FuVideoExportContext alloc] init];
    ctx.reader = reader;
    ctx.writer = writer;
    ctx.task = task;

    FuVideoExportReaderProxy *proxy = [[FuVideoExportReaderProxy alloc] init];
    ctx.proxy = proxy;
    reader.delegate = proxy;

    gActiveExportContext = ctx;
    gFuVideoExportActive = 1;

    __weak FUVideoWriter *weakWriter = writer;
    __weak FuVideoExportTask *weakTask = task;

    // 收尾：writer finishWriting 后回调 completion 并清理
    void (^cleanup)(void) = ^{
        [reader stop];
        [writer stop];
        gFuVideoExportActive = 0;
        gActiveExportContext = nil;
    };

    // 兜底：FUVideoReader 解码失败时不会触发 finish delegate（官方协议无错误回调），
    // readyHandler 返回 NO 后若 5s 内收尾未开始，强制失败回调，避免导出永久挂起
    __block BOOL exportDone = NO;
    __block NSInteger exhaustTick = 0;
    void (^armExhaustCheck)(void) = ^{
        NSInteger tick = ++exhaustTick;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5 * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            if (exportDone || exhaustTick != tick) {
                return;
            }
            exportDone = YES;
            cleanup();
            fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                                 userInfo:@{NSLocalizedDescriptionKey: @"视频导出异常中止"}]);
        });
    };

    proxy.onVideoBuffer = ^(CVPixelBufferRef buffer, CMTime pts) {
        FuVideoExportTask *t = weakTask;
        FUVideoWriter *w = weakWriter;
        if (!t || !w || t.finished || t.failed) {
            return;
        }
        @autoreleasepool {
            [FURenderKitCameraView flushPendingBeautyParams];
            FURenderInput *input = [[FURenderInput alloc] init];
            input.pixelBuffer = buffer;
            input.renderConfig.imageOrientation = [self imageOrientationForReaderOrientation:reader.videoOrientation];
            input.renderConfig.customOutputSize = CGSizeMake(outW, outH);
            FURenderOutput *output = [[FURenderKit shareRenderKit] renderWithInput:input];
            CVPixelBufferRef rendered = output.pixelBuffer;
            if (rendered) {
                [w appendPixelBuffer:rendered time:pts];
            }
            if (t.totalDuration > 0.01) {
                Float64 sec = CMTimeGetSeconds(pts);
                float p = (float)(sec / t.totalDuration);
                p = MAX(0.f, MIN(0.97f, p));
                if (t.progress) {
                    t.progress(p);
                }
            }
        }
    };

    proxy.onAudioBuffer = ^(CMSampleBufferRef sample) {
        FuVideoExportTask *t = weakTask;
        FUVideoWriter *w = weakWriter;
        if (!t || !w || t.finished || t.failed) {
            return;
        }
        [w appendAudioSampleBuffer:sample];
    };

    proxy.onFinishReading = ^{
        FUVideoWriter *w = weakWriter;
        FuVideoExportTask *t = weakTask;
        if (!w || !t || t.finished) {
            return;
        }
        t.finished = YES;
        exportDone = YES;
        if (t.failed) {
            [w stop];
            [[NSFileManager defaultManager] removeItemAtPath:t.outPath error:nil];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (t.completion) {
                    t.completion(nil, t.error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                                             userInfo:@{NSLocalizedDescriptionKey: @"视频导出失败"}]);
                }
            });
            cleanup();
            return;
        }
        [w finishWritingWithCompletion:^{
            NSString *resultPath = t.outPath;
            NSDictionary *attrs = [[NSFileManager defaultManager] attributesOfItemAtPath:resultPath error:nil];
            long long fileSize = attrs.fileSize;
            if (fileSize <= 0) {
                [[NSFileManager defaultManager] removeItemAtPath:resultPath error:nil];
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (t.completion) {
                        t.completion(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                                        userInfo:@{NSLocalizedDescriptionKey: @"视频编码失败"}]);
                    }
                });
                cleanup();
                return;
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                if (t.completion) {
                    t.completion(resultPath, nil);
                }
            });
            cleanup();
        }];
    };

    // writer 的 ready handler 驱动 reader 读取（对齐官方 FUVideoProcessor 的背压设计）
    __weak FUVideoReader *weakReader = reader;
    writer.videoInputReadyHandler = ^BOOL {
        FUVideoReader *r = weakReader;
        if (!r) {
            return NO;
        }
        BOOL ok = [r readNextVideoBuffer];
        if (!ok) {
            armExhaustCheck();
        }
        return ok;
    };
    writer.audioInputReadyHandler = ^BOOL {
        FUVideoReader *r = weakReader;
        if (!r) {
            return NO;
        }
        BOOL ok = [r readNextAudioBuffer];
        if (!ok) {
            armExhaustCheck();
        }
        return ok;
    };

    // 顺序：先 reader.start（创建 AVAssetReader + startReading），再 writer.start（驱动读取）
    [reader start];
    if (![reader isReading]) {
        // reader 启动失败（如轨道解码不可用），立即清理并失败
        [[NSFileManager defaultManager] removeItemAtPath:outPath error:nil];
        gFuVideoExportActive = 0;
        gActiveExportContext = nil;
        fail([NSError errorWithDomain:@"FaceUnityNama" code:-1
                             userInfo:@{NSLocalizedDescriptionKey: @"无法读取视频"}]);
        return;
    }
    [writer start];
    FU_LOG("export started %@ %dx%d audio=%d", outPath, outW, outH, sourceHasAudio ? 1 : 0);
}

@end
