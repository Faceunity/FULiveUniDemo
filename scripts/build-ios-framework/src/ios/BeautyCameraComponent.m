#import "BeautyCameraComponent.h"
#import "FURenderKitCameraView.h"

// 由 NamaModule.m 实现
@interface NamaModule : NSObject
+ (void)attachHostedCameraView:(FURenderKitCameraView *)view;
+ (void)detachHostedCameraView:(FURenderKitCameraView *)view;
@end

@implementation BeautyCameraComponent {
    FURenderKitCameraView *_cameraView;
}

- (UIView *)loadView {
    _cameraView = [[FURenderKitCameraView alloc] initWithFrame:CGRectZero];
    return _cameraView;
}

- (void)viewDidLoad {
    if (_cameraView) {
        [NamaModule attachHostedCameraView:_cameraView];
        [_cameraView startPreview];
    }
}

- (void)layoutDidFinish {
    if (!_cameraView) {
        return;
    }
    CGSize size = _cameraView.bounds.size;
    if (size.width < 1 || size.height < 1) {
        size = self.calculatedFrame.size;
    }
    if (size.width > 1 && size.height > 1) {
        [_cameraView bindLayoutSize:(int)size.width height:(int)size.height];
    }
}

- (void)viewWillUnload {
    FURenderKitCameraView *view = _cameraView;
    _cameraView = nil;
    if (view) {
        [NamaModule detachHostedCameraView:view];
        [view destroyPreviewAsync:nil];
    }
}

@end
