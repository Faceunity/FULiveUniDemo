#import "DCUniComponent.h"

@implementation DCUniComponent

- (UIView *)loadView {
    return [[UIView alloc] initWithFrame:CGRectZero];
}

- (void)viewDidLoad {}
- (void)viewWillUnload {}
- (void)layoutDidFinish {}
- (void)updateAttributes:(NSDictionary *)attributes {}
- (void)addEvent:(NSString *)eventName {}
- (void)removeEvent:(NSString *)eventName {}
- (void)fireEvent:(NSString *)eventName params:(NSDictionary *)params domChanges:(NSDictionary *)domChanges {}

@end
