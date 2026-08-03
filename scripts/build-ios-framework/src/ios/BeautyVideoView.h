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
- (void)setBeautyEnabled:(BOOL)enabled;

@property (nonatomic, copy, nullable) void (^onPlaybackEnded)(void);
@property (nonatomic, copy, nullable) void (^onFirstFrame)(void);

@end

NS_ASSUME_NONNULL_END
