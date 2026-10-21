#import <GLKit/GLKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface BeautyVideoView : GLKView

- (void)bindLayoutSize:(int)width height:(int)height;
- (BOOL)loadVideoPath:(NSString *)path error:(NSError * _Nullable * _Nullable)error;
/** 解码首帧并渲染（默认暂停态用） */
- (void)prepareFirstFrame;
- (void)play;
- (void)pause;
- (BOOL)isPlaying;
/** 暂停时用上一帧重跑美颜（调参即时生效） */
- (void)redrawBeautyFrame;
- (void)stopAndRelease;
/** 对齐官方 backAction：先 stop reader，待解码线程停稳后再释放 GL（避免播放中返回 SIGSEGV） */
- (void)stopAndReleaseWhenIdle:(void (^ _Nullable)(void))completion;
- (BOOL)isVideoReading;
/** 对齐官方 downloadAction：导出前 stopReading + 释放预览 reader，避免与 FUVideoWriter 双 reader 竞态 */
- (void)suspendForExportWithCompletion:(void (^ _Nullable)(void))completion;
- (void)resumeAfterExport;
- (void)setBeautyEnabled:(BOOL)enabled;

@property (nonatomic, copy, nullable) void (^onPlaybackEnded)(void);
@property (nonatomic, copy, nullable) void (^onFirstFrame)(void);

@end

NS_ASSUME_NONNULL_END
