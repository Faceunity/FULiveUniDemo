//
// DCUniComponent.h — compile stub for custom uni component
//
#import <UIKit/UIKit.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface DCUniComponent : NSObject

- (UIView *)loadView;
- (void)viewDidLoad;
- (void)viewWillUnload;
- (void)layoutDidFinish;
- (void)updateAttributes:(NSDictionary *)attributes;
- (void)addEvent:(NSString *)eventName;
- (void)removeEvent:(NSString *)eventName;
- (void)fireEvent:(NSString *)eventName params:(nullable NSDictionary *)params domChanges:(nullable NSDictionary *)domChanges;

@property (nonatomic, strong, readonly) UIView *view;
@property (nonatomic, assign) CGRect calculatedFrame;

@end

NS_ASSUME_NONNULL_END
