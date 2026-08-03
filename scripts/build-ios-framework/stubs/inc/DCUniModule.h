#import <Foundation/Foundation.h>
#import "DCUniDefine.h"
#import "DCUniSDKInstance.h"

typedef void (^UniModuleKeepAliveCallback)(id result, BOOL keepAlive);

@interface DCUniModule : NSObject

@property (nonatomic, weak) DCUniSDKInstance *uniInstance;
@property (nonatomic, assign) BOOL uniExecuteOnJSThread;
@property (nonatomic, strong) dispatch_queue_t uniExecuteQueue;
@property (nonatomic, strong) NSThread *uniExecuteThread;

@end
