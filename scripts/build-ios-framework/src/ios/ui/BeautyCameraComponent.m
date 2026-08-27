#import "BeautyCameraComponent.h"
#import "BeautyCameraView.h"

// 由 NamaModule.m 实现
@interface NamaModule : NSObject
+ (void)attachHostedCameraView:(BeautyCameraView *)view;
+ (void)detachHostedCameraView:(BeautyCameraView *)view;
@end

@implementation BeautyCameraComponent {
    BeautyCameraView *_cameraView;
}

- (UIView *)loadView {
    _cameraView = [[BeautyCameraView alloc] initWithFrame:CGRectZero];
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
    BeautyCameraView *view = _cameraView;
    _cameraView = nil;
    if (view) {
        [NamaModule detachHostedCameraView:view];
        [view destroyPreviewAsync:nil];
    }
}

@end
