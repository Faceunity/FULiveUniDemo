#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

@interface DCUniSDKInstance : NSObject
@property (nonatomic, weak) UIViewController *viewController;
- (void)fireGlobalEvent:(NSString *)event params:(NSDictionary *)params;
@end
