//
//  BeautyVideoView.m
//
//  FURenderKit 版视频美颜预览视图：把自建 GLKView 渲染 + 旧 C SDK fuRender 的链路
//  替换为官方 FUVideoReader 逐帧解码 + [FURenderKit shareRenderKit] renderWithInput 美颜 +
//  FUGLDisplayView 显示。
//
//  接口契约与 BeautyVideoView.h 完全一致（类仍继承 GLKView，仅内部持有 FUGLDisplayView 显示）。
//  不再调用 fuRender / FuReconfirmSpecialBeautySwitches / FuUpdateBeautyBlurEffect 等旧 C SDK 接口。
//

#import "BeautyVideoView.h"
#import "FURenderKitCameraView.h"
#import <FURenderKit/FURenderKit.h>
#import <FURenderKit/FUVideoReader.h>
#import <FURenderKit/FUGLDisplayView.h>
#import <AVFoundation/AVFoundation.h>
#import <UIKit/UIKit.h>

#define FU_LOG(fmt, ...) do { } while (0)

@interface BeautyVideoView () <FUVideoReaderDelegate>
@property (nonatomic, strong) FUVideoReader *videoReader;
@property (nonatomic, strong) AVPlayer *audioPlayer;
@property (nonatomic, strong) FUGLDisplayView *glView;
@property (nonatomic, assign) BOOL beautyEnabled;
@property (nonatomic, assign) BOOL playing;
@property (nonatomic, assign) BOOL ended;
@property (nonatomic, assign) BOOL firstFrameOnly;
@property (nonatomic, assign) BOOL disposed;
@property (nonatomic, assign) BOOL exportSuspended;
@property (nonatomic, assign) int layoutWidth;
@property (nonatomic, assign) int layoutHeight;
@property (nonatomic, assign) CVPixelBufferRef lastPixelBuffer;
@property (nonatomic, copy) NSString *lastPath;
@end

@implementation BeautyVideoView

- (instancetype)initWithFrame:(CGRect)frame {
    // 保持 GLKView 超类（BeautyVideoView.h 契约），但不使用 GLKView 绘制能力；
    // 画面完全交给内部 FUGLDisplayView subview。
    self = [super initWithFrame:frame];
    if (self) {
        _beautyEnabled = YES;
        self.backgroundColor = [UIColor blackColor];
        self.opaque = YES;
        self.clipsToBounds = YES;
        _layoutWidth = (int)CGRectGetWidth(frame);
        _layoutHeight = (int)CGRectGetHeight(frame);

        FUGLDisplayView *glView = [[FUGLDisplayView alloc] initWithFrame:self.bounds];
        glView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
        glView.contentMode = FUGLDisplayViewContentModeScaleAspectFit;
        glView.orientation = FUGLDisplayViewOrientationPortrait;
        glView.backgroundColor = [UIColor blackColor];
        [self addSubview:glView];
        _glView = glView;
    }
    return self;
}

- (void)dealloc {
    [self stopAndRelease];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.glView.frame = self.bounds;
}

#pragma mark - 公开接口

- (void)bindLayoutSize:(int)width height:(int)height {
    if (width > 32 && height > 32) {
        _layoutWidth = width;
        _layoutHeight = height;
    }
}

- (void)setBeautyEnabled:(BOOL)enabled {
    _beautyEnabled = enabled;
    FUBeauty *beauty = [FURenderKit shareRenderKit].beauty;
    if (beauty) {
        beauty.enable = enabled;
    }
}

- (BOOL)isPlaying {
    return _playing;
}

- (BOOL)loadVideoPath:(NSString *)path error:(NSError **)error {
    return [self loadVideoPath:path keepGLView:NO error:error];
}

- (BOOL)loadVideoPath:(NSString *)path keepGLView:(BOOL)keepGLView error:(NSError **)error {
    [self teardownPlaybackKeepingGLView:keepGLView];

    NSString *real = path;
    if ([real hasPrefix:@"file://"]) {
        real = [NSURL URLWithString:real].path ?: [real substringFromIndex:7];
    }
    if (real.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:real]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"视频文件不存在"}];
        }
        return NO;
    }

    NSError *glErr = nil;
    if (![FURenderKitCameraView ensureSharedGLContextReady:&glErr]) {
        if (error) {
            *error = glErr;
        }
        return NO;
    }

    NSURL *url = [NSURL fileURLWithPath:real];
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:url options:nil];
    if ([asset tracksWithMediaType:AVMediaTypeVideo].count == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无视频轨"}];
        }
        return NO;
    }

    if (!self.glView) {
        FUGLDisplayView *glView = [[FUGLDisplayView alloc] initWithFrame:self.bounds];
        glView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
        glView.contentMode = FUGLDisplayViewContentModeScaleAspectFit;
        glView.backgroundColor = [UIColor blackColor];
        [self addSubview:glView];
        self.glView = glView;
    }

    FUVideoReaderSettings *settings = [[FUVideoReaderSettings alloc] init];
    settings.readingAutomatically = YES;
    settings.videoOutputFormat = kCVPixelFormatType_32BGRA;
    // needsAudioTrack 保持默认（YES），与官方 FULiveDemo 完全一致；
    // 设 NO 会改变 FUVideoReader 内部解码路径，实测在播放中 stop 时更易触发
    // copyNextSampleBuffer SIGSEGV。音频仍由 AVPlayer 单独播放。

    FUVideoReader *reader = [[FUVideoReader alloc] initWithURL:url settings:settings];
    reader.delegate = self;
    _videoReader = reader;

    self.glView.orientation = (FUGLDisplayViewOrientation)reader.videoOrientation;

    AVPlayer *player = [AVPlayer playerWithURL:url];
    player.actionAtItemEnd = AVPlayerActionAtItemEndNone;
    _audioPlayer = player;

    _playing = NO;
    _ended = NO;
    _firstFrameOnly = NO;
    _disposed = NO;
    _lastPath = [real copy];
    FU_LOG("loadVideo %@ orient=%ld", real, (long)reader.videoOrientation);
    return YES;
}

- (void)prepareFirstFrame {
    if (!_videoReader || !_audioPlayer) {
        return;
    }
    @synchronized (self) {
        _playing = NO;
        _ended = NO;
        _firstFrameOnly = YES;
    }
    if ([self.videoReader isReading]) {
        [self.videoReader stop];
    }
    [self.audioPlayer seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
    [self.videoReader start];
}

- (void)play {
    if (!_videoReader || !_audioPlayer) {
        return;
    }
    @synchronized (self) {
        if (_playing) {
            return;
        }
        // FUVideoReader 无法 seek，pause 后重开 reader 总是从头解码；
        // 因此音频也一律回到起点，保证 A/V 同步（pause/resume 不保留播放位置）。
        _playing = YES;
        _ended = NO;
        _firstFrameOnly = NO;
    }
    [self.audioPlayer seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
    [self.audioPlayer play];
    @synchronized (self) {
        if (![self.videoReader isReading]) {
            [self.videoReader start];
        }
    }
}

- (void)pause {
    @synchronized (self) {
        if (!_playing) {
            return;
        }
        _playing = NO;
        _firstFrameOnly = NO;
    }
    [self.audioPlayer pause];
    if ([self.videoReader isReading]) {
        [self.videoReader stop];
    }
}

- (void)redrawBeautyFrame {
    if (self.disposed || _exportSuspended) {
        return;
    }
    if (_lastPixelBuffer) {
        [self renderBufferToDisplay:_lastPixelBuffer];
    }
}

- (BOOL)isVideoReading {
    return _videoReader != nil && [_videoReader isReading];
}

- (void)stopAndRelease {
    [self stopAndReleaseWhenIdle:nil];
}

- (void)stopAndReleaseWhenIdle:(void (^)(void))completion {
    if (_disposed) {
        if (completion) {
            completion();
        }
        return;
    }
    _disposed = YES;
    @synchronized (self) {
        _playing = NO;
        _firstFrameOnly = NO;
        if (_videoReader && [_videoReader isReading]) {
            [_videoReader stop];
        }
        if (_audioPlayer) {
            [_audioPlayer pause];
            _audioPlayer = nil;
        }
    }
    [self finishStopAndReleaseWhenIdle:completion attempt:0];
}

- (void)finishStopAndReleaseWhenIdle:(void (^)(void))completion attempt:(int)attempt {
    BOOL reading = [self isVideoReading];
    if (!reading || attempt >= 24) {
        @synchronized (self) {
            if (_lastPixelBuffer) {
                CVPixelBufferRelease(_lastPixelBuffer);
                _lastPixelBuffer = NULL;
            }
            _lastPath = nil;
        }
        if (_glView) {
            [_glView removeFromSuperview];
            _glView = nil;
        }
        if (completion) {
            completion();
        }
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(25 * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        [self finishStopAndReleaseWhenIdle:completion attempt:attempt + 1];
    });
}

- (void)waitUntilReaderIdle:(void (^)(void))completion attempt:(int)attempt {
    BOOL reading = [self isVideoReading];
    if (!reading || attempt >= 24) {
        if (completion) {
            completion();
        }
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(25 * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        [self waitUntilReaderIdle:completion attempt:attempt + 1];
    });
}

- (void)suspendForExportWithCompletion:(void (^)(void))completion {
    if (_disposed) {
        if (completion) {
            completion();
        }
        return;
    }
    _exportSuspended = YES;
    @synchronized (self) {
        _playing = NO;
        _firstFrameOnly = NO;
        if (_videoReader && [_videoReader isReading]) {
            [_videoReader stop];
        }
        if (_audioPlayer) {
            [_audioPlayer pause];
            _audioPlayer = nil;
        }
    }
    __weak typeof(self) weakSelf = self;
    [self waitUntilReaderIdle:^{
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            if (completion) {
                completion();
            }
            return;
        }
        @synchronized (self) {
            self->_videoReader = nil;
            if (self->_lastPixelBuffer) {
                CVPixelBufferRelease(self->_lastPixelBuffer);
                self->_lastPixelBuffer = NULL;
            }
        }
        if (completion) {
            completion();
        }
    } attempt:0];
}

- (void)resumeAfterExport {
    if (_disposed) {
        return;
    }
    _exportSuspended = NO;
    NSString *path = _lastPath;
    if (path.length == 0) {
        return;
    }
    if (!_videoReader) {
        NSError *err = nil;
        if (![self loadVideoPath:path keepGLView:YES error:&err]) {
            return;
        }
    }
    [self prepareFirstFrame];
}

#pragma mark - 内部工具

- (void)teardownPlayback {
    [self teardownPlaybackKeepingGLView:NO];
}

- (void)teardownPlaybackKeepingGLView:(BOOL)keepGLView {
    @synchronized (self) {
        _playing = NO;
        _firstFrameOnly = NO;
        if (_videoReader) {
            // 对齐官方 FUVideoRenderViewModel stopReading：只 stop，不置 delegate=nil、
            // 不立即释放 reader（reader 保持引用，随 dealloc 自然释放）。
            // 播放中同步置空 delegate / 释放 reader 会撞上解码线程 → SIGSEGV。
            [_videoReader stop];
        }
        if (_audioPlayer) {
            [_audioPlayer pause];
            _audioPlayer = nil;
        }
        if (_lastPixelBuffer) {
            CVPixelBufferRelease(_lastPixelBuffer);
            _lastPixelBuffer = NULL;
        }
        _lastPath = nil;
    }
    // 换源时清掉 GL 上残留的旧帧，避免 reveal overlay 时闪上一段视频；
    // 导出后 resume 同一视频必须保留 glView，否则重建的显示层不上屏（黑屏）
    if (!keepGLView && _glView) {
        [_glView removeFromSuperview];
        _glView = nil;
    }
}

- (void)retainLastPixelBuffer:(CVPixelBufferRef)pb {
    if (!pb) {
        return;
    }
    CVPixelBufferRetain(pb);
    if (_lastPixelBuffer) {
        CVPixelBufferRelease(_lastPixelBuffer);
    }
    _lastPixelBuffer = pb;
}

- (FUImageOrientation)imageOrientationForReaderOrientation:(FUVideoOrientation)orientation {
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

- (void)renderBufferToDisplay:(CVPixelBufferRef)buffer {
    if (!buffer || _exportSuspended) {
        return;
    }
    if (!_glView) {
        return;
    }
    FUVideoOrientation orient = _videoReader ? _videoReader.videoOrientation : FUVideoOrientationPortrait;
    // 视频预览不经过内部相机渲染回调，手动把排队的美颜参数落地（对齐相机 renderKitWillRenderFromRenderInput）
    [FURenderKitCameraView flushPendingBeautyParams];
    FURenderInput *input = [[FURenderInput alloc] init];
    input.pixelBuffer = buffer;
    input.renderConfig.imageOrientation = [self imageOrientationForReaderOrientation:orient];
    FURenderOutput *output = [[FURenderKit shareRenderKit] renderWithInput:input];
    CVPixelBufferRef outBuffer = output.pixelBuffer;
    if (outBuffer) {
        [self.glView displayPixelBuffer:outBuffer];
    }
}

#pragma mark - FUVideoReaderDelegate

- (void)videoReaderDidOutputVideoSampleBuffer:(CMSampleBufferRef)videoSampleBuffer {
    @autoreleasepool {
        if (_disposed || _exportSuspended) {
            // 释放后回调节点：直接释放帧并返回，避免用已释放资源崩溃
            if (videoSampleBuffer) {
                CMSampleBufferInvalidate(videoSampleBuffer);
                CFRelease(videoSampleBuffer);
            }
            return;
        }
        BOOL firstFrame = NO;
        @synchronized (self) {
            CVPixelBufferRef videoBuffer = CMSampleBufferGetImageBuffer(videoSampleBuffer);
            if (videoBuffer) {
                BOOL shouldShow = _playing || _firstFrameOnly;
                if (shouldShow) {
                    [self renderBufferToDisplay:videoBuffer];
                }
                [self retainLastPixelBuffer:videoBuffer];
            }
            if (_firstFrameOnly) {
                _firstFrameOnly = NO;
                firstFrame = YES;
            }
        }
        if (firstFrame) {
            if ([self.videoReader isReading]) {
                [self.videoReader stop];
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                if (self.onFirstFrame) {
                    self.onFirstFrame();
                }
            });
        }
        // 官方 FUVideoReader 在 delegate 处理帧后不负责释放，需调用方释放
        CMSampleBufferInvalidate(videoSampleBuffer);
        CFRelease(videoSampleBuffer);
    }
}

- (void)videoReaderDidFinishReading {
    if (_disposed || _exportSuspended) {
        return;
    }
    _playing = NO;
    _ended = YES;
    _firstFrameOnly = NO;
    [self.audioPlayer pause];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.onPlaybackEnded) {
            self.onPlaybackEnded();
        }
    });
}

@end
