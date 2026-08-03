#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface VideoBeautyExporter : NSObject

+ (BOOL)isExportActive;

/** 取消进行中的导出（主线程/后台线程均可调用） */
+ (void)cancelActiveExport;

/**
 * 离线视频美颜导出。path 为本地文件路径；成功返回输出 mp4 路径。
 * maxDurationSec / maxSide 限制；beautyHandle 为 Nama item。
 * progress 可选，0~1。
 */
+ (void)exportVideoAtPath:(NSString *)path
             beautyHandle:(int)beautyHandle
           maxDurationSec:(NSTimeInterval)maxDurationSec
                  maxSide:(int)maxSide
                 progress:(void (^ _Nullable)(float progress))progress
               completion:(void (^)(NSString * _Nullable outPath, NSError * _Nullable error))completion;

/** 兼容旧调用 */
+ (void)exportVideoAtPath:(NSString *)path
             beautyHandle:(int)beautyHandle
           maxDurationSec:(NSTimeInterval)maxDurationSec
                  maxSide:(int)maxSide
               completion:(void (^)(NSString * _Nullable outPath, NSError * _Nullable error))completion;

@end

NS_ASSUME_NONNULL_END
