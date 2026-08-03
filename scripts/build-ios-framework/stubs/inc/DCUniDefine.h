#ifndef DCUniDefine_h
#define DCUniDefine_h

#import <Foundation/Foundation.h>

// DCloud iOS SDK 运行时通过 class_copyMethodList 扫描类方法名前缀来注册 JS 方法。
// 实测/文档：旧版 Weex 使用 wx_export_method_，部分 SDK 同时识别 uni_export_method_。
// 为兼容各版本 HBuilderX，两个前缀同时导出。
#define UNI_CONCAT(a, b)   a ## b
#define UNI_CONCAT_WRAPPER(a, b) UNI_CONCAT(a, b)

#define UNI_EXPORT_METHOD(method) \
    UNI_EXPORT_METHOD_INTERNAL(method, wx_export_method_) \
    UNI_EXPORT_METHOD_INTERNAL(method, uni_export_method_)

#define UNI_EXPORT_METHOD_SYNC(method) \
    UNI_EXPORT_METHOD_INTERNAL(method, wx_export_method_sync_) \
    UNI_EXPORT_METHOD_INTERNAL(method, uni_export_method_sync_)

#define UNI_EXPORT_METHOD_INTERNAL(method, token) \
+ (NSString *)UNI_CONCAT_WRAPPER(token, __LINE__) { \
    return NSStringFromSelector(method); \
}

#endif /* DCUniDefine_h */
