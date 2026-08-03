#import "BeautyVideoView.h"
#import "BeautyCameraView.h"
#import "CNamaSDK.h"
#import "FuBeautyHandle.h"
#import <AVFoundation/AVFoundation.h>
#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>
#import <math.h>
#define FU_LOG(fmt, ...) do { } while (0)

@interface BeautyVideoView () <GLKViewDelegate>
@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, strong) id endObserver;
@property (nonatomic, assign) BOOL glReady;
@property (nonatomic, assign) BOOL beautyEnabled;
@property (nonatomic, assign) BOOL playing;
@property (nonatomic, assign) int layoutWidth;
@property (nonatomic, assign) int layoutHeight;
@property (nonatomic, assign) int frameId;
@property (nonatomic, assign) GLuint renderProgram;
@property (nonatomic, assign) GLuint positionAttr;
@property (nonatomic, assign) GLuint texCoordAttr;
@property (nonatomic, assign) GLint textureUniform;
@property (nonatomic, assign) GLint mirrorYUniform;
@property (nonatomic, assign) GLuint outputTexture;
@property (nonatomic, assign) int cachedFuOutW;
@property (nonatomic, assign) int cachedFuOutH;
@property (nonatomic, assign) int frameWidth;
@property (nonatomic, assign) int frameHeight;
@property (nonatomic, assign) unsigned int pendingTexId;
@property (nonatomic, assign) float pendingMirrorY;
@property (nonatomic, assign) int preferredRotation; // 0/90/180/270
@property (nonatomic, assign) int naturalWidth;
@property (nonatomic, assign) int naturalHeight;
@property (nonatomic, assign) CVPixelBufferRef lastPixelBuffer;
@property (nonatomic, assign) BOOL ended;
@end

@implementation BeautyVideoView {
    GLfloat _quadVertices[8];
}

- (instancetype)initWithFrame:(CGRect)frame {
    EAGLContext *context = [BeautyCameraView createContextInSharedGroup];
    self = [super initWithFrame:frame context:context];
    if (self) {
        _beautyEnabled = YES;
        self.backgroundColor = [UIColor blackColor];
        self.opaque = YES;
        self.enableSetNeedsDisplay = NO;
        self.drawableColorFormat = GLKViewDrawableColorFormatRGBA8888;
        self.drawableDepthFormat = GLKViewDrawableDepthFormatNone;
        self.delegate = self;
        self.contentScaleFactor = [UIScreen mainScreen].scale;
        _layoutWidth = (int)(CGRectGetWidth(frame) * self.contentScaleFactor);
        _layoutHeight = (int)(CGRectGetHeight(frame) * self.contentScaleFactor);
        memcpy(_quadVertices, (GLfloat[]){
            -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f
        }, sizeof(_quadVertices));
    }
    return self;
}

- (void)dealloc {
    [self stopAndRelease];
}

- (void)bindLayoutSize:(int)width height:(int)height {
    if (width > 32 && height > 32) {
        _layoutWidth = width;
        _layoutHeight = height;
        [self rebuildVertexBuffer];
    }
}

- (void)setBeautyEnabled:(BOOL)enabled {
    _beautyEnabled = enabled;
}

- (BOOL)isPlaying {
    return _playing;
}

- (BOOL)loadVideoPath:(NSString *)path error:(NSError **)error {
    [self stopPlaybackOnly];
    NSString *real = path;
    if ([real hasPrefix:@"file://"]) {
        real = [NSURL URLWithString:real].path ?: [real substringFromIndex:7];
    }
    if (real.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:real]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"视频文件不存在" }];
        }
        return NO;
    }

    NSURL *url = [NSURL fileURLWithPath:real];
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:url options:nil];
    AVAssetTrack *vTrack = [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
    _preferredRotation = 0;
    _naturalWidth = 0;
    _naturalHeight = 0;
    if (vTrack) {
        CGSize nat = vTrack.naturalSize;
        _naturalWidth = (int)nat.width;
        _naturalHeight = (int)nat.height;
        CGAffineTransform t = vTrack.preferredTransform;
        // 用角度判断，兼容带translation 的preferredTransform
        double deg = atan2(t.b, t.a) * 180.0 / M_PI;
        int nearest = (int)llround(deg / 90.0) * 90;
        while (nearest < 0) {
            nearest += 360;
        }
        nearest %= 360;
        if (nearest == 90 || nearest == 270 || nearest == 180) {
            _preferredRotation = nearest;
        }
    }
    AVPlayerItem *item = [AVPlayerItem playerItemWithAsset:asset];
    NSDictionary *attrs = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (id)kCVPixelBufferOpenGLESCompatibilityKey: @YES,
    };
    _videoOutput = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:attrs];
    [item addOutput:_videoOutput];
    _player = [AVPlayer playerWithPlayerItem:item];
    _player.actionAtItemEnd = AVPlayerActionAtItemEndPause;
    _ended = NO;

    __weak typeof(self) weakSelf = self;
    _endObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:AVPlayerItemDidPlayToEndTimeNotification
                    object:item
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(__unused NSNotification *note) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            return;
        }
        self.playing = NO;
        self.ended = YES;
        if (self.displayLink) {
            self.displayLink.paused = YES;
        }
        [self.player pause];
        if (self.onPlaybackEnded) {
            self.onPlaybackEnded();
        }
    }];

    NSError *glErr = nil;
    if (![BeautyCameraView ensureSharedGLContextReady:&glErr]) {
        if (error) {
            *error = glErr;
        }
        return NO;
    }
    //  静图/视频模式；禁止fuOnCameraChange，避免打乱美颜相机跟踪状态    // 与后置相机一致开启identity BufferMatrix，避免视频美颜发灰发白
    fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
    fuSetInputCameraMatrix(0, 0, FU_ROTATION_MODE_0);
    fuSetInputCameraBufferMatrix(CCROT0);
    fuSetInputCameraBufferMatrixState(true);
    fuSetInputCameraTextureMatrixState(false);
    fuSetOutputMatrixState(false);
    fuSetFaceProcessorDetectMode(1);
    [self setupGLIfNeeded];
    FU_LOG("loadVideo %@", real);
    return YES;
}

- (void)prepareFirstFrame {
    if (!_player || !_videoOutput) {
        return;
    }
    _playing = NO;
    _ended = NO;
    __weak typeof(self) weakSelf = self;
    [_player seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero completionHandler:^(__unused BOOL finished) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            return;
        }
        [self.player pause];
        // 等解码出首帧再渲染
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.05 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self sampleAndRenderCurrentFrame];
            if (self.onFirstFrame) {
                self.onFirstFrame();
            }
        });
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.25 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self sampleAndRenderCurrentFrame];
        });
    }];
}

- (void)play {
    if (!_player) {
        return;
    }
    if (_ended) {
        _ended = NO;
        __weak typeof(self) weakSelf = self;
        [_player seekToTime:kCMTimeZero toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero completionHandler:^(__unused BOOL finished) {
            __strong typeof(weakSelf) self = weakSelf;
            if (!self) {
                return;
            }
            [self startPlaybackInternal];
        }];
        return;
    }
    [self startPlaybackInternal];
}

- (void)startPlaybackInternal {
    _playing = YES;
    [_player play];
    if (!_displayLink) {
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(onDisplayLink:)];
        [_displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
    }
    _displayLink.paused = NO;
}

- (void)pause {
    _playing = NO;
    [_player pause];
    if (_displayLink) {
        _displayLink.paused = YES;
    }
    // 保留最后一帧；调参时走 redrawBeautyFrame
}

- (void)redrawBeautyFrame {
    if (_lastPixelBuffer) {
        [self renderPixelBuffer:_lastPixelBuffer];
        [self display];
        return;
    }
    [self sampleAndRenderCurrentFrame];
}

- (void)sampleAndRenderCurrentFrame {
    if (!_videoOutput || !_player) {
        return;
    }
    CMTime t = _player.currentItem ? _player.currentItem.currentTime : kCMTimeZero;
    if (CMTIME_IS_INVALID(t)) {
        t = kCMTimeZero;
    }
    if (![_videoOutput hasNewPixelBufferForItemTime:t]) {
        // 仍尝试取最近一帧
        t = [_videoOutput itemTimeForHostTime:CACurrentMediaTime()];
    }
    CVPixelBufferRef pb = [_videoOutput copyPixelBufferForItemTime:t itemTimeForDisplay:NULL];
    if (!pb) {
        return;
    }
    [self retainLastPixelBuffer:pb];
    [self renderPixelBuffer:pb];
    CVPixelBufferRelease(pb);
    [self display];
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

- (void)stopPlaybackOnly {
    _playing = NO;
    if (_displayLink) {
        [_displayLink invalidate];
        _displayLink = nil;
    }
    if (_endObserver) {
        [[NSNotificationCenter defaultCenter] removeObserver:_endObserver];
        _endObserver = nil;
    }
    [_player pause];
    _player = nil;
    _videoOutput = nil;
    if (_lastPixelBuffer) {
        CVPixelBufferRelease(_lastPixelBuffer);
        _lastPixelBuffer = NULL;
    }
}

- (void)stopAndRelease {
    [self stopPlaybackOnly];
    [self teardownGL];
}

- (void)onDisplayLink:(CADisplayLink *)link {
    (void)link;
    if (!_playing || !_videoOutput || !_player) {
        return;
    }
    CMTime t = [_videoOutput itemTimeForHostTime:CACurrentMediaTime()];
    if (![_videoOutput hasNewPixelBufferForItemTime:t]) {
        return;
    }
    CVPixelBufferRef pb = [_videoOutput copyPixelBufferForItemTime:t itemTimeForDisplay:NULL];
    if (!pb) {
        return;
    }
    [self retainLastPixelBuffer:pb];
    [self renderPixelBuffer:pb];
    CVPixelBufferRelease(pb);
    [self display];
}

#pragma mark - GL

- (void)setupGLIfNeeded {
    if (_glReady) {
        return;
    }
    [EAGLContext setCurrentContext:self.context];
    NSError *err = nil;
    [BeautyCameraView ensureSharedGLContextReady:&err];
    fuMakeGLContextCurrent();

    _renderProgram = [self buildProgram];
    if (_renderProgram == 0) {
        FU_LOG("GL program failed");
        return;
    }
    _positionAttr = (GLuint)glGetAttribLocation(_renderProgram, "aPosition");
    _texCoordAttr = (GLuint)glGetAttribLocation(_renderProgram, "aTexCoord");
    _textureUniform = glGetUniformLocation(_renderProgram, "uTexture");
    _mirrorYUniform = glGetUniformLocation(_renderProgram, "uMirrorY");

    glGenTextures(1, &_outputTexture);
    glBindTexture(GL_TEXTURE_2D, _outputTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    _glReady = YES;
}

- (void)teardownGL {
    if (!_glReady) {
        return;
    }
    [EAGLContext setCurrentContext:self.context];
    if (_outputTexture) {
        glDeleteTextures(1, &_outputTexture);
        _outputTexture = 0;
    }
    if (_renderProgram) {
        glDeleteProgram(_renderProgram);
        _renderProgram = 0;
    }
    _glReady = NO;
}

- (GLuint)buildProgram {
    const char *vs =
        "attribute vec2 aPosition;"
        "attribute vec2 aTexCoord;"
        "varying vec2 vTexCoord;"
        "uniform float uMirrorY;"
        "void main(){"
        "  gl_Position=vec4(aPosition,0.0,1.0);"
        "  float ty=mix(aTexCoord.y,1.0-aTexCoord.y,uMirrorY);"
        "  vTexCoord=vec2(aTexCoord.x,ty);"
        "}";
    const char *fs =
        "precision mediump float;"
        "varying vec2 vTexCoord;"
        "uniform sampler2D uTexture;"
        "void main(){ vec4 c=texture2D(uTexture,vTexCoord); gl_FragColor=vec4(c.rgb,1.0); }";
    GLuint v = [self compile:GL_VERTEX_SHADER src:vs];
    GLuint f = [self compile:GL_FRAGMENT_SHADER src:fs];
    if (!v || !f) {
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

- (GLuint)compile:(GLenum)type src:(const char *)src {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glDeleteShader(s);
        return 0;
    }
    return s;
}

- (void)updateOutputResolution:(int)w height:(int)h {
    w = w & ~1;
    h = h & ~1;
    if (w <= 32 || h <= 32 || (w == _cachedFuOutW && h == _cachedFuOutH)) {
        return;
    }
    _cachedFuOutW = w;
    _cachedFuOutH = h;
    fuSetOutputResolution(w, h);
}

- (void)rebuildVertexBuffer {
    static const GLfloat kFull[8] = {-1, -1, 1, -1, -1, 1, 1, 1};
    int vw = _layoutWidth;
    int vh = _layoutHeight;
    if (vw <= 0 || vh <= 0 || _frameWidth <= 0 || _frameHeight <= 0) {
        memcpy(_quadVertices, kFull, sizeof(kFull));
        return;
    }
    float viewAspect = (float)vw / (float)vh;
    float contentAspect = (float)_frameWidth / (float)_frameHeight;
    float sx = 1.f;
    float sy = 1.f;
    if (contentAspect > viewAspect) {
        sx = contentAspect / viewAspect;
    } else {
        sy = viewAspect / contentAspect;
    }
    _quadVertices[0] = -sx; _quadVertices[1] = -sy;
    _quadVertices[2] = sx;  _quadVertices[3] = -sy;
    _quadVertices[4] = -sx; _quadVertices[5] = sy;
    _quadVertices[6] = sx;  _quadVertices[7] = sy;
}

- (unsigned char *)rotateBGRA:(const unsigned char *)src
                        width:(int)w
                       height:(int)h
                     rotation:(int)rotation
                       outW:(int *)outW
                       outH:(int *)outH {
    if (rotation == 0) {
        size_t n = (size_t)w * (size_t)h * 4;
        unsigned char *copy = (unsigned char *)malloc(n);
        if (copy) {
            memcpy(copy, src, n);
        }
        *outW = w;
        *outH = h;
        return copy;
    }
    int dw = (rotation == 90 || rotation == 270) ? h : w;
    int dh = (rotation == 90 || rotation == 270) ? w : h;
    unsigned char *dst = (unsigned char *)malloc((size_t)dw * (size_t)dh * 4);
    if (!dst) {
        return NULL;
    }
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int si = (y * w + x) * 4;
            int dx;
            int dy;
            if (rotation == 90) {
                //  iOS preferredTransform (0,1,-1,0)：缓冲顺时针 90° 到直立
                dx = h - 1 - y;
                dy = x;
            } else if (rotation == 270) {
                dx = y;
                dy = w - 1 - x;
            } else { // 180
                dx = w - 1 - x;
                dy = h - 1 - y;
            }
            int di = (dy * dw + dx) * 4;
            dst[di] = src[si];
            dst[di + 1] = src[si + 1];
            dst[di + 2] = src[si + 2];
            dst[di + 3] = src[si + 3];
        }
    }
    *outW = dw;
    *outH = dh;
    return dst;
}

- (int)rotationForBufferWidth:(int)width height:(int)height {
    if (_preferredRotation == 0) {
        return 0;
    }
    //  AVPlayerItemVideoOutput 有时已按 preferredTransform 输出直立帧，再转会整帧颠倒
    if ((_preferredRotation == 90 || _preferredRotation == 270) &&
        _naturalWidth > 0 && _naturalHeight > 0 &&
        _naturalWidth >= _naturalHeight &&
        height > width) {
        return 0;
    }
    if (_preferredRotation == 180 &&
        _naturalWidth > 0 && _naturalHeight > 0 &&
        width == _naturalWidth && height == _naturalHeight) {
        return 180;
    }
    return _preferredRotation;
}

- (void)prepareNamaForStillLikeFrameWidth:(int)width height:(int)height {
    fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
    fuSetInputCameraMatrix(0, 0, FU_ROTATION_MODE_0);
    // 与后置相机一致：开启identity BufferMatrix
    fuSetInputCameraBufferMatrix(CCROT0);
    fuSetInputCameraBufferMatrixState(true);
    fuSetInputCameraTextureMatrixState(false);
    fuSetOutputMatrixState(false);
    fuSetFaceProcessorDetectMode(1);
    if (width > 0 && height > 0) {
        fuSetOutputResolution(width, height);
    }
}

/// 视频帧常见alpha=0；进 FU 前强制不透明，避免美颜按透明合成导致发灰发白
- (void)forceOpaqueBGRA:(unsigned char *)bgra width:(int)width height:(int)height {
    if (!bgra || width <= 0 || height <= 0) {
        return;
    }
    size_t px = (size_t)width * (size_t)height;
    if (px == 0) {
        return;
    }
    // 抽样已不透明则跳过整帧扫描（导入视频黑很久的常见耗时点）
    if (bgra[3] == 255 &&
        bgra[((px / 2) * 4) + 3] == 255 &&
        bgra[((px - 1) * 4) + 3] == 255) {
        return;
    }
    for (size_t i = 0; i < px; i++) {
        bgra[i * 4 + 3] = 255;
    }
}

- (void)uploadBGRAToOutputTexture:(const void *)bgra width:(int)width height:(int)height {
    if (!bgra || !_outputTexture || width <= 0 || height <= 0) {
        return;
    }
    glBindTexture(GL_TEXTURE_2D, _outputTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_BGRA, GL_UNSIGNED_BYTE, bgra);
}

- (void)renderPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    if (!_glReady || fuIsLibraryInit() == 0) {
        return;
    }
    int width = (int)CVPixelBufferGetWidth(pixelBuffer);
    int height = (int)CVPixelBufferGetHeight(pixelBuffer);
    if (width <= 0 || height <= 0) {
        return;
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    void *baseAddr = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t stride = CVPixelBufferGetBytesPerRow(pixelBuffer);
    if (!baseAddr) {
        CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
        return;
    }

    // 始终拷到自有连续缓冲：可安全改alpha，且不污染AVPlayer 的CVPixelBuffer
    size_t packedSize = (size_t)width * (size_t)height * 4;
    unsigned char *packed = (unsigned char *)malloc(packedSize);
    if (!packed) {
        CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
        return;
    }
    for (int row = 0; row < height; row++) {
        memcpy(packed + (size_t)row * width * 4,
               (unsigned char *)baseAddr + (size_t)row * stride,
               (size_t)width * 4);
    }
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    int rotW = width;
    int rotH = height;
    unsigned char *rotated = NULL;
    unsigned char *fuInput = packed;
    int applyRot = [self rotationForBufferWidth:width height:height];
    if (applyRot != 0) {
        rotated = [self rotateBGRA:packed width:width height:height rotation:applyRot outW:&rotW outH:&rotH];
        if (rotated) {
            fuInput = rotated;
            width = rotW;
            height = rotH;
        }
    }
    [self forceOpaqueBGRA:fuInput width:width height:height];

    if (_frameWidth != width || _frameHeight != height) {
        _frameWidth = width;
        _frameHeight = height;
        [self updateOutputResolution:width height:height];
        [self rebuildVertexBuffer];
    }

    __block unsigned int displayTex = 0;
    __block float displayMirrorY = 0.f;
    __block unsigned char *lockedInput = fuInput;
    __block int lockedW = width;
    __block int lockedH = height;

    [BeautyCameraView performWithSharedGLLock:^{
        [EAGLContext setCurrentContext:self.context];
        fuMakeGLContextCurrent();
        [EAGLContext setCurrentContext:self.context];
        [self prepareNamaForStillLikeFrameWidth:lockedW height:lockedH];
        self.frameId += 1;
        int beautyHandle = FuBeautyMediaHandle > 0 ? FuBeautyMediaHandle : FuBeautyCameraHandle;
        [BeautyCameraView flushPendingBeautyParams];
        FuReconfirmSpecialBeautySwitches(beautyHandle);

        //  先上传原帧，保证有画面；美颜成功后再替换，避免AI/GL 未就绪时长时间全黑
        if (lockedInput) {
            [self uploadBGRAToOutputTexture:lockedInput width:lockedW height:lockedH];
            displayTex = self.outputTexture;
            displayMirrorY = 0.f;
        }

        if (self.beautyEnabled && beautyHandle > 0 && lockedInput) {
            int items[1] = { beautyHandle };
            // 视频预览优先 BGRA 原地路径：RGBA 纹理在会话切换后常出黑帧
            int ret = fuRender(
                FU_FORMAT_BGRA_BUFFER,
                lockedInput,
                FU_FORMAT_BGRA_BUFFER,
                lockedInput,
                lockedW,
                lockedH,
                self.frameId,
                items,
                1,
                NAMA_RENDER_FEATURE_FULL,
                NULL
            );
            if (ret >= 0 && fuGetSystemError() == 0) {
                [self forceOpaqueBGRA:lockedInput width:lockedW height:lockedH];
                [self uploadBGRAToOutputTexture:lockedInput width:lockedW height:lockedH];
                displayTex = self.outputTexture;
                displayMirrorY = 0.f;
                FuUpdateBeautyBlurEffect(beautyHandle);
            } else {
                unsigned int outTex = 0;
                ret = fuRender(
                    FU_FORMAT_RGBA_TEXTURE,
                    &outTex,
                    FU_FORMAT_BGRA_BUFFER,
                    lockedInput,
                    lockedW,
                    lockedH,
                    self.frameId,
                    items,
                    1,
                    NAMA_RENDER_FEATURE_FULL,
                    NULL
                );
                if (outTex > 0 && ret > 0 && fuGetSystemError() == 0) {
                    displayTex = outTex;
                    displayMirrorY = 1.f;
                    FuUpdateBeautyBlurEffect(beautyHandle);
                }
            }
        }
    }];

    if (rotated) {
        free(rotated);
    }
    free(packed);
    _pendingTexId = displayTex;
    _pendingMirrorY = displayMirrorY;
}

- (void)glkView:(GLKView *)view drawInRect:(CGRect)rect {
    (void)view;
    (void)rect;
    if (!_glReady) {
        return;
    }
    int eglW = (int)self.drawableWidth;
    int eglH = (int)self.drawableHeight;
    if (eglW < 32 || eglH < 32) {
        return;
    }
    [EAGLContext setCurrentContext:self.context];
    [self bindDrawable];
    glViewport(0, 0, eglW, eglH);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    unsigned int tex = _pendingTexId;
    if (tex == 0 || _renderProgram == 0) {
        return;
    }
    glUseProgram(_renderProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(_textureUniform, 0);
    //  FU RGBA 纹理需翻Y；CPU 顶左 BGRA 上传则不翻
    glUniform1f(_mirrorYUniform, _pendingMirrorY);
    static const GLfloat kTex[8] = {0, 1, 1, 1, 0, 0, 1, 0};
    glEnableVertexAttribArray(_positionAttr);
    glVertexAttribPointer(_positionAttr, 2, GL_FLOAT, GL_FALSE, 0, _quadVertices);
    glEnableVertexAttribArray(_texCoordAttr);
    glVertexAttribPointer(_texCoordAttr, 2, GL_FLOAT, GL_FALSE, 0, kTex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(_positionAttr);
    glDisableVertexAttribArray(_texCoordAttr);
}

@end
