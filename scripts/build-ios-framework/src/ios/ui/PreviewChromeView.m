#import "PreviewChromeView.h"
#import "BeautyCameraView.h"

static const NSInteger kFuChromeInteractiveTag = 88219901;

@interface FuCaptureProgressView : UIView
@property (nonatomic, assign) CGFloat percent;
@end

@implementation FuCaptureProgressView
- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
        self.userInteractionEnabled = NO;
        self.hidden = YES;
        self.contentMode = UIViewContentModeRedraw;
    }
    return self;
}
- (void)setPercent:(CGFloat)percent {
    _percent = MAX(0, MIN(1, percent));
    self.hidden = _percent <= 0.0005;
    [self setNeedsDisplay];
}
- (void)drawRect:(CGRect)rect {
    CGFloat stroke = 3;
    CGRect oval = CGRectInset(self.bounds, stroke, stroke);
    UIBezierPath *track = [UIBezierPath bezierPathWithOvalInRect:oval];
    track.lineWidth = stroke;
    [[[UIColor whiteColor] colorWithAlphaComponent:0.4] setStroke];
    [track stroke];
    if (_percent > 0) {
        CGFloat radius = MIN(CGRectGetWidth(oval), CGRectGetHeight(oval)) * 0.5;
        UIBezierPath *prog = [UIBezierPath bezierPathWithArcCenter:CGPointMake(CGRectGetMidX(oval), CGRectGetMidY(oval))
                                                             radius:radius
                                                         startAngle:(CGFloat)(-M_PI_2)
                                                           endAngle:(CGFloat)(-M_PI_2 + M_PI * 2 * _percent)
                                                          clockwise:YES];
        prog.lineWidth = stroke;
        prog.lineCapStyle = kCGLineCapRound;
        // 对齐 Android 0xFF5EC7FE
        [[UIColor colorWithRed:0.369 green:0.780 blue:0.996 alpha:1] setStroke];
        [prog stroke];
    }
}
@end

@interface PreviewChromeView ()
@property (nonatomic, strong) UIView *topBar;
@property (nonatomic, strong) UIButton *homeBtn;
@property (nonatomic, strong) UIButton *dualTab;
@property (nonatomic, strong) UIButton *singleTab;
@property (nonatomic, strong) UIButton *moreBtn;
@property (nonatomic, strong) UIButton *buglyBtn;
@property (nonatomic, strong) UIButton *switchBtn;
@property (nonatomic, strong) UIView *moreMenu;
@property (nonatomic, strong) NSArray<UIButton *> *resTabs;
@property (nonatomic, strong) UIButton *importBtn;
@property (nonatomic, strong) UIView *debugPanel;
@property (nonatomic, strong) UILabel *debugLabel;
@property (nonatomic, strong) UIButton *compareBtn;
@property (nonatomic, strong) UIView *captureBtn;
@property (nonatomic, strong) UIImageView *captureInner;
@property (nonatomic, strong) FuCaptureProgressView *captureProgress;
@property (nonatomic, strong) NSTimer *longPressTimer;
@property (nonatomic, strong) NSTimer *recordTimer;
@property (nonatomic, assign) BOOL longPressFired;
@property (nonatomic, assign) BOOL recording;
@property (nonatomic, assign) BOOL dualInput;
@property (nonatomic, assign) BOOL moreVisible;
@property (nonatomic, assign) BOOL debugVisible;
@property (nonatomic, copy) NSString *selectedResId;
@property (nonatomic, strong) NSArray<NSString *> *resIds;
@property (nonatomic, assign) NSTimeInterval recordStart;
@property (nonatomic, assign) CGFloat bottomChromeInset;
@property (nonatomic, strong) UILabel *filterNameLabel;
@property (nonatomic, strong) UILabel *noFaceLabel;
@property (nonatomic, strong) UIView *perfLimitTipCard;
@property (nonatomic, strong) UILabel *perfLimitTipLabel;
@property (nonatomic, strong, nullable) NSTimer *filterNameTimer;
@property (nonatomic, strong, nullable) NSTimer *perfLimitTipTimer;
@end

@implementation PreviewChromeView

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
        self.userInteractionEnabled = YES;
        _dualInput = YES;
        _selectedResId = @"720";
        _resIds = @[ @"480", @"720", @"1080" ];
        [self buildTopBar];
        [self buildMoreMenu];
        [self buildDebugPanel];
        [self buildBottomChrome];
        [self buildPreviewTips];
    }
    return self;
}

#pragma mark - UI build

- (UIImage *)chromeImage:(NSString *)name {
    NSString *base = [name stringByDeletingPathExtension];
    NSString *ext = name.pathExtension.length ? name.pathExtension : @"png";
    NSString *fileName = [NSString stringWithFormat:@"%@.%@", base, ext];

    // 1) framework 内嵌 fu_chrome（对齐 Android AAR assets）
    NSBundle *plugin = [NSBundle bundleForClass:[PreviewChromeView class]];
    // 直接拼 framework 路径，避免 pathForResource 在部分基座打包下找不到子目录
    if (plugin.bundlePath.length) {
        NSString *direct = [[plugin.bundlePath stringByAppendingPathComponent:@"fu_chrome"]
            stringByAppendingPathComponent:fileName];
        UIImage *img = [UIImage imageWithContentsOfFile:direct];
        if (img) {
            return img;
        }
    }
    NSArray *bundleDirs = @[ @"fu_chrome", @"fu-chrome", @"" ];
    for (NSString *dir in bundleDirs) {
        NSString *path = dir.length
            ? [plugin pathForResource:base ofType:ext inDirectory:dir]
            : [plugin pathForResource:base ofType:ext];
        if (path.length) {
            UIImage *img = [UIImage imageWithContentsOfFile:path];
            if (img) {
                return img;
            }
        }
        // 部分打包把文件平铺在 framework 根
        NSString *flat = [[plugin.bundlePath stringByAppendingPathComponent:dir.length ? dir : @""]
            stringByAppendingPathComponent:fileName];
        UIImage *img2 = [UIImage imageWithContentsOfFile:flat];
        if (img2) {
            return img2;
        }
    }

    // 2) UniApp 运行时资源：Documents/Pandora/.../www/static/fu-chrome（勿用 pathForResource）
    NSString *home = NSHomeDirectory();
    NSArray *docCandidates = @[
        [home stringByAppendingPathComponent:@"Documents/Pandora/apps"],
        [home stringByAppendingPathComponent:@"Library/Pandora/apps"],
    ];
    NSFileManager *fm = [NSFileManager defaultManager];
    for (NSString *appsRoot in docCandidates) {
        NSArray *apps = [fm contentsOfDirectoryAtPath:appsRoot error:nil];
        for (NSString *appId in apps) {
            NSString *path = [[[appsRoot stringByAppendingPathComponent:appId]
                stringByAppendingPathComponent:@"www/static/fu-chrome"]
                stringByAppendingPathComponent:fileName];
            UIImage *img = [UIImage imageWithContentsOfFile:path];
            if (img) {
                return img;
            }
        }
    }

    // 3) mainBundle 兜底
    NSBundle *main = [NSBundle mainBundle];
    for (NSString *dir in @[ @"static/fu-chrome", @"www/static/fu-chrome", @"fu-chrome", @"fu_chrome" ]) {
        NSString *path = [main pathForResource:base ofType:ext inDirectory:dir];
        if (path.length) {
            UIImage *img = [UIImage imageWithContentsOfFile:path];
            if (img) {
                return img;
            }
        }
    }
    return nil;
}

- (UIButton *)iconButton:(NSString *)asset {
    UIButton *btn = [UIButton buttonWithType:UIButtonTypeCustom];
    btn.tag = kFuChromeInteractiveTag;
    // 对齐 Android：顶栏 icon 无底色（对比/单双输入除外）
    btn.backgroundColor = [UIColor clearColor];
    btn.opaque = NO;
    btn.adjustsImageWhenHighlighted = YES;
    btn.showsTouchWhenHighlighted = NO;
    UIImage *img = [self chromeImage:asset];
    if (img) {
        UIImage *orig = [img imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal];
        [btn setImage:orig forState:UIControlStateNormal];
        [btn setImage:orig forState:UIControlStateHighlighted];
        btn.imageView.contentMode = UIViewContentModeScaleAspectFit;
        btn.contentEdgeInsets = UIEdgeInsetsMake(6, 6, 6, 6);
    } else {
        // 资源缺失时也要看得见，避免「画面上没有按钮」
        NSString *fallback = [[asset stringByDeletingPathExtension] uppercaseString];
        if ([fallback hasPrefix:@"BUGLY"]) {
            fallback = @"FPS";
        } else if (fallback.length > 3) {
            fallback = [fallback substringToIndex:3];
        }
        [btn setTitle:fallback forState:UIControlStateNormal];
        [btn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
        btn.titleLabel.font = [UIFont systemFontOfSize:10 weight:UIFontWeightBold];
        btn.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.45];
        btn.layer.cornerRadius = 8;
        btn.clipsToBounds = YES;
    }
    return btn;
}

- (void)buildTopBar {
    _topBar = [[UIView alloc] init];
    _topBar.userInteractionEnabled = YES;
    [self addSubview:_topBar];

    _homeBtn = [self iconButton:@"home.png"];
    [_homeBtn addTarget:self action:@selector(onHome) forControlEvents:UIControlEventTouchUpInside];
    [_topBar addSubview:_homeBtn];

    UIView *io = [[UIView alloc] init];
    io.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.4];
    io.layer.cornerRadius = 7;
    io.clipsToBounds = YES;
    io.tag = kFuChromeInteractiveTag;
    [_topBar addSubview:io];

    _dualTab = [UIButton buttonWithType:UIButtonTypeCustom];
    _dualTab.tag = kFuChromeInteractiveTag;
    [_dualTab setTitle:@"双输入" forState:UIControlStateNormal];
    _dualTab.titleLabel.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    [_dualTab addTarget:self action:@selector(onDual) forControlEvents:UIControlEventTouchUpInside];
    [io addSubview:_dualTab];

    _singleTab = [UIButton buttonWithType:UIButtonTypeCustom];
    _singleTab.tag = kFuChromeInteractiveTag;
    [_singleTab setTitle:@"单输入" forState:UIControlStateNormal];
    _singleTab.titleLabel.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    [_singleTab addTarget:self action:@selector(onSingle) forControlEvents:UIControlEventTouchUpInside];
    [io addSubview:_singleTab];
    [self refreshSegTabs];

    _moreBtn = [self iconButton:@"more.png"];
    [_moreBtn addTarget:self action:@selector(onMore) forControlEvents:UIControlEventTouchUpInside];
    [_topBar addSubview:_moreBtn];

    _buglyBtn = [self iconButton:@"bugly.png"];
    [_buglyBtn addTarget:self action:@selector(onBugly) forControlEvents:UIControlEventTouchUpInside];
    _buglyBtn.accessibilityLabel = @"debug";
    _buglyBtn.hidden = NO;
    _buglyBtn.alpha = 1;
    // 再保险：即便图标加载失败也显示 FPS 字样，保证顶栏看得到入口
    if (_buglyBtn.currentImage == nil && _buglyBtn.currentTitle.length == 0) {
        [_buglyBtn setTitle:@"FPS" forState:UIControlStateNormal];
        [_buglyBtn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
        _buglyBtn.titleLabel.font = [UIFont systemFontOfSize:10 weight:UIFontWeightBold];
        _buglyBtn.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.45];
        _buglyBtn.layer.cornerRadius = 8;
        _buglyBtn.clipsToBounds = YES;
    }
    [_topBar addSubview:_buglyBtn];
    [_topBar bringSubviewToFront:_buglyBtn];

    _switchBtn = [self iconButton:@"switch_camera.png"];
    [_switchBtn addTarget:self action:@selector(onSwitch) forControlEvents:UIControlEventTouchUpInside];
    [_topBar addSubview:_switchBtn];

    // keep io ref via associated layout in layoutSubviews — store as subview of topBar
    io.accessibilityIdentifier = @"fu_io_tabs";
}

- (UIView *)ioTabsView {
    for (UIView *v in _topBar.subviews) {
        if ([v.accessibilityIdentifier isEqualToString:@"fu_io_tabs"]) {
            return v;
        }
    }
    return nil;
}

- (void)buildMoreMenu {
    _moreMenu = [[UIView alloc] init];
    _moreMenu.backgroundColor = [[UIColor colorWithWhite:0.1 alpha:0.93] colorWithAlphaComponent:0.93];
    _moreMenu.layer.cornerRadius = 10;
    _moreMenu.hidden = YES;
    _moreMenu.tag = kFuChromeInteractiveTag;
    [self addSubview:_moreMenu];

    NSMutableArray *tabs = [NSMutableArray array];
    // 分辨率按钮只显示短边档位；debug 面板仍用「宽*高」
    NSArray *labels = @[ @"480", @"720", @"1080" ];
    for (NSInteger i = 0; i < 3; i++) {
        UIButton *tab = [UIButton buttonWithType:UIButtonTypeCustom];
        tab.tag = kFuChromeInteractiveTag;
        [tab setTitle:labels[i] forState:UIControlStateNormal];
        tab.titleLabel.font = [UIFont systemFontOfSize:12 weight:UIFontWeightMedium];
        tab.layer.cornerRadius = 8;
        tab.clipsToBounds = YES;
        [tab addTarget:self action:@selector(onResTab:) forControlEvents:UIControlEventTouchUpInside];
        [_moreMenu addSubview:tab];
        [tabs addObject:tab];
    }
    _resTabs = tabs;

    _importBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _importBtn.tag = kFuChromeInteractiveTag;
    [_importBtn setTitle:@"载入图片或视频" forState:UIControlStateNormal];
    _importBtn.titleLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightMedium];
    _importBtn.backgroundColor = [UIColor colorWithWhite:0.17 alpha:1];
    _importBtn.layer.cornerRadius = 8;
    [_importBtn addTarget:self action:@selector(onImport) forControlEvents:UIControlEventTouchUpInside];
    [_moreMenu addSubview:_importBtn];
    [self refreshResTabs];
}

- (void)buildDebugPanel {
    _debugPanel = [[UIView alloc] init];
    _debugPanel.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.6];
    _debugPanel.layer.cornerRadius = 8;
    _debugPanel.hidden = YES;
    _debugPanel.tag = kFuChromeInteractiveTag;
    [self addSubview:_debugPanel];

    _debugLabel = [[UILabel alloc] init];
    _debugLabel.textColor = [[UIColor whiteColor] colorWithAlphaComponent:0.9];
    _debugLabel.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    _debugLabel.numberOfLines = 0;
    [_debugPanel addSubview:_debugLabel];
}

- (void)buildBottomChrome {
    _compareBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _compareBtn.tag = kFuChromeInteractiveTag;
    _compareBtn.backgroundColor = [UIColor clearColor];
    _compareBtn.adjustsImageWhenHighlighted = NO;
    UIImage *cmp = [self chromeImage:@"compare.png"];
    if (cmp) {
        [_compareBtn setImage:[cmp imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                     forState:UIControlStateNormal];
    }
    [self addSubview:_compareBtn];
    [_compareBtn addTarget:self action:@selector(onCompareDown) forControlEvents:UIControlEventTouchDown];
    [_compareBtn addTarget:self action:@selector(onCompareUp) forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];

    // 对齐 Android：外 72 / 内 56，样式由 capture.png（白环+深灰心）承担，容器无底色
    _captureBtn = [[UIView alloc] init];
    _captureBtn.tag = kFuChromeInteractiveTag;
    _captureBtn.userInteractionEnabled = YES;
    _captureBtn.backgroundColor = [UIColor clearColor];
    _captureBtn.opaque = NO;
    [self addSubview:_captureBtn];

    _captureProgress = [[FuCaptureProgressView alloc] init];
    _captureProgress.backgroundColor = [UIColor clearColor];
    [_captureBtn addSubview:_captureProgress];

    _captureInner = [[UIImageView alloc] init];
    _captureInner.contentMode = UIViewContentModeScaleAspectFit;
    _captureInner.userInteractionEnabled = NO;
    _captureInner.backgroundColor = [UIColor clearColor];
    _captureInner.opaque = NO;
    UIImage *cap = [self chromeImage:@"capture.png"];
    if (cap) {
        _captureInner.image = [cap imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal];
    } else {
        // 与 Android 无图兜底一致：白实心圆
        _captureInner.backgroundColor = [UIColor whiteColor];
        _captureInner.layer.cornerRadius = 28;
        _captureInner.clipsToBounds = YES;
    }
    [_captureBtn addSubview:_captureInner];

    UILongPressGestureRecognizer *capPress =
        [[UILongPressGestureRecognizer alloc] initWithTarget:self action:@selector(onCaptureHold:)];
    capPress.minimumPressDuration = 0.01;
    capPress.allowableMovement = 20;
    [_captureBtn addGestureRecognizer:capPress];
}

- (void)buildPreviewTips {
    _filterNameLabel = [[UILabel alloc] init];
    _filterNameLabel.textColor = [UIColor whiteColor];
    _filterNameLabel.font = [UIFont systemFontOfSize:32 weight:UIFontWeightSemibold];
    _filterNameLabel.textAlignment = NSTextAlignmentCenter;
    _filterNameLabel.backgroundColor = [UIColor clearColor];
    _filterNameLabel.layer.shadowColor = [UIColor blackColor].CGColor;
    _filterNameLabel.layer.shadowOpacity = 0.55;
    _filterNameLabel.layer.shadowRadius = 4;
    _filterNameLabel.layer.shadowOffset = CGSizeMake(0, 1);
    _filterNameLabel.hidden = YES;
    _filterNameLabel.userInteractionEnabled = NO;
    [self addSubview:_filterNameLabel];

    _noFaceLabel = [[UILabel alloc] init];
    _noFaceLabel.text = @"未检测到人脸";
    _noFaceLabel.textColor = [UIColor whiteColor];
    _noFaceLabel.font = [UIFont systemFontOfSize:17 weight:UIFontWeightMedium];
    _noFaceLabel.textAlignment = NSTextAlignmentCenter;
    _noFaceLabel.backgroundColor = [UIColor clearColor];
    _noFaceLabel.layer.shadowColor = [UIColor blackColor].CGColor;
    _noFaceLabel.layer.shadowOpacity = 0.55;
    _noFaceLabel.layer.shadowRadius = 4;
    _noFaceLabel.layer.shadowOffset = CGSizeMake(0, 1);
    _noFaceLabel.hidden = YES;
    _noFaceLabel.userInteractionEnabled = NO;
    [self addSubview:_noFaceLabel];

    _perfLimitTipCard = [[UIView alloc] init];
    _perfLimitTipCard.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.72];
    _perfLimitTipCard.layer.cornerRadius = 10.f;
    _perfLimitTipCard.clipsToBounds = YES;
    _perfLimitTipCard.hidden = YES;
    _perfLimitTipCard.userInteractionEnabled = NO;
    [self addSubview:_perfLimitTipCard];

    _perfLimitTipLabel = [[UILabel alloc] init];
    _perfLimitTipLabel.textColor = [UIColor whiteColor];
    _perfLimitTipLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightMedium];
    _perfLimitTipLabel.textAlignment = NSTextAlignmentCenter;
    _perfLimitTipLabel.numberOfLines = 0;
    [_perfLimitTipCard addSubview:_perfLimitTipLabel];
}

#pragma mark - layout

- (void)layoutSubviews {
    [super layoutSubviews];
    CGFloat w = CGRectGetWidth(self.bounds);
    CGFloat h = CGRectGetHeight(self.bounds);
    // 正常态顶栏固定 6；偶发整层上移由 previewBox 坐标稳定逻辑处理，勿常驻叠 safeArea
    CGFloat top = 6;
    CGFloat barH = 44;
    _topBar.frame = CGRectMake(10, top, MAX(0, w - 20), barH);

    // 对齐 Android：顶栏图标 36，无背景色
    CGFloat icon = 36;
    _homeBtn.frame = CGRectMake(0, (barH - icon) * 0.5, icon, icon);
    _homeBtn.backgroundColor = [UIColor clearColor];
    _switchBtn.frame = CGRectMake(CGRectGetWidth(_topBar.bounds) - icon, (barH - icon) * 0.5, icon, icon);
    _switchBtn.backgroundColor = [UIColor clearColor];
    _buglyBtn.frame = CGRectMake(CGRectGetMinX(_switchBtn.frame) - 4 - icon, (barH - icon) * 0.5, icon, icon);
    if (_buglyBtn.currentImage == nil) {
        _buglyBtn.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.45];
    } else {
        _buglyBtn.backgroundColor = [UIColor clearColor];
    }
    _buglyBtn.hidden = NO;
    _buglyBtn.alpha = 1;
    _buglyBtn.userInteractionEnabled = YES;
    [_topBar bringSubviewToFront:_buglyBtn];
    [_topBar bringSubviewToFront:_switchBtn];
    _moreBtn.frame = CGRectMake(CGRectGetMinX(_buglyBtn.frame) - 4 - icon, (barH - icon) * 0.5, icon, icon);
    _moreBtn.backgroundColor = [UIColor clearColor];

    UIView *io = [self ioTabsView];
    CGFloat ioX = CGRectGetMaxX(_homeBtn.frame) + 8;
    CGFloat ioW = MAX(80, CGRectGetMinX(_moreBtn.frame) - 8 - ioX);
    io.frame = CGRectMake(ioX, (barH - 32) * 0.5, ioW, 32);
    _dualTab.frame = CGRectMake(2, 2, (ioW - 4) * 0.5, 28);
    _singleTab.frame = CGRectMake(CGRectGetMaxX(_dualTab.frame), 2, (ioW - 4) * 0.5, 28);

    CGFloat menuW = 220;
    _moreMenu.frame = CGRectMake(w - 10 - menuW, top + barH + 2, menuW, 100);
    CGFloat tabW = (menuW - 20 - 12) / 3.0;
    for (NSInteger i = 0; i < _resTabs.count; i++) {
        _resTabs[i].frame = CGRectMake(10 + i * (tabW + 6), 10, tabW, 32);
    }
    _importBtn.frame = CGRectMake(10, 52, menuW - 20, 38);

    // 对齐 Android：WRAP_CONTENT 风格小条，背景 0x99000000，圆角 8，字 11
    _debugPanel.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.6];
    _debugPanel.layer.cornerRadius = 8;
    _debugLabel.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    _debugLabel.numberOfLines = 0;
    CGSize textSize = [_debugLabel sizeThatFits:CGSizeMake(180, CGFLOAT_MAX)];
    CGFloat dbgW = MAX(96, MIN(168, ceil(textSize.width) + 16));
    CGFloat dbgH = MAX(48, MIN(72, ceil(textSize.height) + 12));
    _debugPanel.frame = CGRectMake(10, 56, dbgW, dbgH);
    _debugLabel.frame = CGRectInset(_debugPanel.bounds, 8, 6);

    CGFloat cmp = 44;
    CGFloat inset = _bottomChromeInset;
    // 拍摄钮整颗露在 Tab 上方（panelGap），勿再减导致被盖住
    CGFloat panelGap = 56;
    _compareBtn.frame = CGRectMake(24, h - 18 - cmp - inset - panelGap, cmp, cmp);

    CGFloat cap = 72;
    _captureBtn.frame = CGRectMake((w - cap) * 0.5, h - 8 - cap - inset - panelGap, cap, cap);
    _captureBtn.backgroundColor = [UIColor clearColor];
    _captureProgress.frame = _captureBtn.bounds;
    CGFloat inner = 56;
    _captureInner.frame = CGRectMake((cap - inner) * 0.5, (cap - inner) * 0.5, inner, inner);
    if (_captureInner.image) {
        _captureInner.backgroundColor = [UIColor clearColor];
        _captureInner.layer.cornerRadius = 0;
        _captureInner.clipsToBounds = NO;
    } else {
        _captureInner.layer.cornerRadius = inner * 0.5;
        _captureInner.clipsToBounds = YES;
    }

    CGFloat previewMidY = CGRectGetMidY(self.bounds) * 0.84;
    if (_noFaceLabel) {
        CGSize ns = [_noFaceLabel sizeThatFits:CGSizeMake(w - 80, 40)];
        CGFloat nw = MIN(w - 64, MAX(140, ns.width + 28));
        CGFloat nh = MAX(24, ns.height + 8);
        _noFaceLabel.frame = CGRectMake((w - nw) * 0.5, previewMidY - nh * 0.5, nw, nh);
    }
    if (_perfLimitTipCard && !_perfLimitTipCard.hidden) {
        CGFloat maxTipW = MIN(w - 48, 300);
        CGSize ts = [_perfLimitTipLabel sizeThatFits:CGSizeMake(maxTipW - 28, CGFLOAT_MAX)];
        CGFloat tw = MIN(maxTipW, MAX(120, ts.width + 28));
        CGFloat th = MAX(36, ts.height + 22);
        CGFloat tipY = previewMidY - th * 0.5 - 36;
        if (!_noFaceLabel.hidden) {
            tipY = CGRectGetMinY(_noFaceLabel.frame) - th - 12;
        }
        _perfLimitTipCard.frame = CGRectMake((w - tw) * 0.5, tipY, tw, th);
        _perfLimitTipLabel.frame = CGRectMake(14, 11, tw - 28, th - 22);
    }
    if (_filterNameLabel && !_filterNameLabel.hidden) {
        CGSize fs = [_filterNameLabel sizeThatFits:CGSizeMake(w - 80, 60)];
        CGFloat fw = MIN(w - 64, MAX(120, fs.width + 28));
        CGFloat fh = MAX(40, fs.height + 12);
        CGFloat filterY = _noFaceLabel.hidden
            ? (previewMidY - fh * 0.5 + 24)
            : (CGRectGetMaxY(_noFaceLabel.frame) + 16);
        _filterNameLabel.frame = CGRectMake((w - fw) * 0.5, filterY, fw, fh);
    }
}

#pragma mark - public

- (void)updateStatsWithResolution:(NSString *)resolution fps:(int)fps renderTimeMs:(int)renderTimeMs {
    NSString *res = resolution.length ? resolution : @"-";
    _debugLabel.text = [NSString stringWithFormat:@"分辨率:%@\n帧率:%d\nrendertime:%d", res, fps, renderTimeMs];
    [self setNeedsLayout];
}

- (void)setRecording:(BOOL)recording {
    _recording = recording;
    if (!recording) {
        [self stopRecordProgress];
    }
    _captureInner.alpha = recording ? 0.85 : 1;
}

- (void)setSelectedResolutionId:(NSString *)resolutionId {
    if (resolutionId.length == 0) {
        return;
    }
    _selectedResId = [resolutionId copy];
    [self refreshResTabs];
}

- (void)setDualInputState:(BOOL)dual {
    _dualInput = dual;
    [self refreshSegTabs];
}

- (void)setDebugVisibleState:(BOOL)visible {
    _debugVisible = visible;
    _debugPanel.hidden = !visible;
    if (visible) {
        [self bringSubviewToFront:_debugPanel];
    }
}

- (void)setBottomChromeInset:(CGFloat)insetPts animated:(BOOL)animated {
    _bottomChromeInset = MAX(0, insetPts);
    void (^apply)(void) = ^{
        [self setNeedsLayout];
        [self layoutIfNeeded];
    };
    if (animated) {
        // 与美颜面板 Tab 伸缩 0.2s 对齐，避免拍照钮不同步
        [UIView animateWithDuration:0.2 animations:apply];
    } else {
        apply();
    }
}

- (void)setCompareButtonHidden:(BOOL)hidden {
    _compareBtn.hidden = hidden;
}

- (void)showFilterName:(NSString *)name {
    if (name.length == 0) {
        return;
    }
    [_filterNameTimer invalidate];
    _filterNameLabel.text = name;
    _filterNameLabel.hidden = NO;
    _filterNameLabel.alpha = 0;
    [self setNeedsLayout];
    [self layoutIfNeeded];
    [self bringSubviewToFront:_filterNameLabel];
    [UIView animateWithDuration:0.18 animations:^{
        self.filterNameLabel.alpha = 1;
    }];
    __weak typeof(self) weakSelf = self;
    _filterNameTimer = [NSTimer timerWithTimeInterval:1.0 repeats:NO block:^(__unused NSTimer *timer) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            return;
        }
        [UIView animateWithDuration:0.22 animations:^{
            self.filterNameLabel.alpha = 0;
        } completion:^(__unused BOOL finished) {
            self.filterNameLabel.hidden = YES;
        }];
    }];
    [[NSRunLoop mainRunLoop] addTimer:_filterNameTimer forMode:NSRunLoopCommonModes];
}

- (void)setNoFaceVisible:(BOOL)visible {
    _noFaceLabel.hidden = !visible;
    if (visible) {
        [self setNeedsLayout];
        [self bringSubviewToFront:_noFaceLabel];
        if (!_perfLimitTipCard.hidden) {
            [self bringSubviewToFront:_perfLimitTipCard];
        }
    }
}

- (void)hidePerfLimitTip {
    [_perfLimitTipTimer invalidate];
    _perfLimitTipTimer = nil;
    _perfLimitTipCard.hidden = YES;
    _perfLimitTipCard.alpha = 1;
}

- (void)showPerfLimitTip:(NSString *)message {
    if (message.length == 0) {
        return;
    }
    [_perfLimitTipTimer invalidate];
    _perfLimitTipTimer = nil;
    _perfLimitTipLabel.text = message;
    _perfLimitTipCard.hidden = NO;
    _perfLimitTipCard.alpha = 0;
    [self setNeedsLayout];
    [self layoutIfNeeded];
    [self bringSubviewToFront:_perfLimitTipCard];
    if (!_noFaceLabel.hidden) {
        [self bringSubviewToFront:_noFaceLabel];
        [self bringSubviewToFront:_perfLimitTipCard];
    }
    [UIView animateWithDuration:0.18 animations:^{
        self.perfLimitTipCard.alpha = 1;
    }];
    __weak typeof(self) weakSelf = self;
    _perfLimitTipTimer = [NSTimer timerWithTimeInterval:2.0 repeats:NO block:^(__unused NSTimer *timer) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            return;
        }
        [UIView animateWithDuration:0.22 animations:^{
            self.perfLimitTipCard.alpha = 0;
        } completion:^(__unused BOOL finished) {
            [self hidePerfLimitTip];
        }];
    }];
    [[NSRunLoop mainRunLoop] addTimer:_perfLimitTipTimer forMode:NSRunLoopCommonModes];
}

- (BOOL)hitInteractiveAtPoint:(CGPoint)point {
    NSArray *views = @[ _homeBtn, _dualTab, _singleTab, _moreBtn, _buglyBtn, _switchBtn,
                        _compareBtn, _captureBtn ];
    for (UIView *v in views) {
        if (!v || v.hidden || v.alpha < 0.01f || !v.userInteractionEnabled) {
            continue;
        }
        // 扩大顶栏按钮命中，避免穿透到 WebView 导致 bugly「点了没反应」
        CGFloat pad = (v == _buglyBtn || v == _moreBtn || v == _switchBtn || v == _homeBtn) ? 12.f : 6.f;
        CGPoint p = [self convertPoint:point toView:v];
        if (CGRectContainsPoint(CGRectInset(v.bounds, -pad, -pad), p)) {
            return YES;
        }
    }
    UIView *io = [self ioTabsView];
    if (io) {
        CGPoint p = [self convertPoint:point toView:io];
        if (CGRectContainsPoint(CGRectInset(io.bounds, -4, -4), p)) {
            return YES;
        }
    }
    if (!_moreMenu.hidden) {
        CGPoint p = [self convertPoint:point toView:_moreMenu];
        if (CGRectContainsPoint(CGRectInset(_moreMenu.bounds, -4, -4), p)) {
            return YES;
        }
    }
    if (!_debugPanel.hidden) {
        CGPoint p = [self convertPoint:point toView:_debugPanel];
        if (CGRectContainsPoint(_debugPanel.bounds, p)) {
            return YES;
        }
    }
    return NO;
}

/** 空白处放行给下层 WebView → 点击对焦；仅按钮/菜单拦截 */
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    if (self.hidden || !self.userInteractionEnabled || self.alpha < 0.01f) {
        return nil;
    }
    if (![self pointInside:point withEvent:event]) {
        return nil;
    }
    // 顶栏按钮优先直接命中（含扩大区域），避免 super 因 insets 漏掉
    NSArray *topBtns = @[ _homeBtn, _buglyBtn, _moreBtn, _switchBtn, _dualTab, _singleTab ];
    for (UIView *v in topBtns) {
        if (!v || v.hidden || !v.userInteractionEnabled) {
            continue;
        }
        CGPoint p = [self convertPoint:point toView:v];
        if (CGRectContainsPoint(CGRectInset(v.bounds, -12, -12), p)) {
            return v;
        }
    }
    if (![self hitInteractiveAtPoint:point]) {
        return nil;
    }
    return [super hitTest:point withEvent:event];
}

#pragma mark - actions

- (void)refreshSegTabs {
    void (^style)(UIButton *, BOOL) = ^(UIButton *btn, BOOL on) {
        [btn setTitleColor:(on ? [UIColor whiteColor] : [[UIColor whiteColor] colorWithAlphaComponent:0.75])
                  forState:UIControlStateNormal];
        btn.backgroundColor = on ? [UIColor colorWithRed:0.369 green:0.780 blue:0.996 alpha:1]
                                 : [UIColor clearColor];
        btn.layer.cornerRadius = on ? 7 : 0;
        btn.clipsToBounds = YES;
    };
    style(_dualTab, _dualInput);
    style(_singleTab, !_dualInput);
    UIView *io = [self ioTabsView];
    if (io) {
        io.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.4];
        io.layer.cornerRadius = 7;
        io.clipsToBounds = YES;
        io.layer.borderWidth = 0;
        io.layer.borderColor = nil;
    }
}

- (void)refreshResTabs {
    for (NSInteger i = 0; i < _resTabs.count; i++) {
        BOOL on = [_resIds[i] isEqualToString:_selectedResId];
        UIButton *tab = _resTabs[i];
        [tab setTitleColor:(on ? [UIColor whiteColor] : [[UIColor whiteColor] colorWithAlphaComponent:0.9])
                  forState:UIControlStateNormal];
        tab.backgroundColor = on ? [UIColor colorWithRed:0.369 green:0.780 blue:0.996 alpha:1]
                                 : [UIColor colorWithWhite:0.17 alpha:1];
    }
}

- (void)hideMoreMenu {
    _moreMenu.hidden = YES;
    _moreVisible = NO;
}

- (void)onHome {
    [self hideMoreMenu];
    [self.delegate previewChromeHome];
}
- (void)onDual {
    _dualInput = YES;
    [self refreshSegTabs];
    [self.delegate previewChromeToggleDualInput:YES];
}
- (void)onSingle {
    _dualInput = NO;
    [self refreshSegTabs];
    [self.delegate previewChromeToggleDualInput:NO];
}
- (void)onMore {
    _moreVisible = _moreMenu.hidden;
    _moreMenu.hidden = !_moreVisible;
    if (_moreVisible) {
        [self bringSubviewToFront:_moreMenu];
    }
}
- (void)onBugly {
    _debugVisible = !_debugVisible;
    _debugPanel.hidden = !_debugVisible;
        if (_debugVisible) {
            // 打开时立刻刷一帧统计，避免等 JS 轮询才显示
            NSDictionary *stats = [BeautyCameraView previewStats];
            if ([stats isKindOfClass:[NSDictionary class]]) {
                int fw = [stats[@"frameWidth"] intValue];
                int fh = [stats[@"frameHeight"] intValue];
                NSString *res = (fw > 0 && fh > 0)
                    ? [NSString stringWithFormat:@"%d*%d", fw, fh]
                    : [NSString stringWithFormat:@"%@", stats[@"resolution"] ?: @"-"];
                int fps = (int)lround([stats[@"fps"] doubleValue]);
                int rt = [stats[@"renderTime"] intValue];
                [self updateStatsWithResolution:res fps:fps renderTimeMs:rt];
            }
            [self bringSubviewToFront:_debugPanel];
            [self setNeedsLayout];
            [self layoutIfNeeded];
        }
    if ([self.delegate respondsToSelector:@selector(previewChromeDebugVisibleChanged:)]) {
        [self.delegate previewChromeDebugVisibleChanged:_debugVisible];
    }
}

- (void)onSwitch {
    [self hideMoreMenu];
    [self.delegate previewChromeSwitchCamera];
}
- (void)onResTab:(UIButton *)sender {
    NSInteger idx = [_resTabs indexOfObject:sender];
    if (idx == NSNotFound || idx >= (NSInteger)_resIds.count) {
        return;
    }
    _selectedResId = _resIds[idx];
    [self refreshResTabs];
    [self hideMoreMenu];
    [self.delegate previewChromeSelectResolution:_selectedResId];
}
- (void)onImport {
    [self hideMoreMenu];
    [self.delegate previewChromeImportMedia];
}

- (void)setCompareButtonPressed:(BOOL)pressed {
    _compareBtn.alpha = pressed ? 0.55 : 1.0;
}

- (void)onCompareDown {
    [self setCompareButtonPressed:YES];
    [self.delegate previewChromeCompareStart];
}

- (void)onCompareUp {
    [self setCompareButtonPressed:NO];
    [self.delegate previewChromeCompareEnd];
}

- (void)onCompareHold:(UILongPressGestureRecognizer *)gr {
    if (gr.state == UIGestureRecognizerStateBegan) {
        [self.delegate previewChromeCompareStart];
    } else if (gr.state == UIGestureRecognizerStateEnded ||
               gr.state == UIGestureRecognizerStateCancelled ||
               gr.state == UIGestureRecognizerStateFailed) {
        [self.delegate previewChromeCompareEnd];
    }
}

- (void)onCaptureHold:(UILongPressGestureRecognizer *)gr {
    if (gr.state == UIGestureRecognizerStateBegan) {
        [self hideMoreMenu];
        _longPressFired = NO;
        [self.delegate previewChromeCaptureTouchDown];
        __weak typeof(self) weakSelf = self;
        [_longPressTimer invalidate];
        _longPressTimer = [NSTimer timerWithTimeInterval:0.42
                                                 repeats:NO
                                                   block:^(__unused NSTimer *timer) {
            __strong typeof(weakSelf) strong = weakSelf;
            if (!strong) {
                return;
            }
            strong.longPressFired = YES;
            [strong startRecordProgress];
            [strong.delegate previewChromeCaptureLongPress];
        }];
        [[NSRunLoop mainRunLoop] addTimer:_longPressTimer forMode:NSRunLoopCommonModes];
    } else if (gr.state == UIGestureRecognizerStateEnded ||
               gr.state == UIGestureRecognizerStateCancelled ||
               gr.state == UIGestureRecognizerStateFailed) {
        [_longPressTimer invalidate];
        _longPressTimer = nil;
        BOOL wasLong = _longPressFired;
        [self stopRecordProgress];
        [self.delegate previewChromeCaptureTouchUp:wasLong];
    }
}

- (void)startRecordProgress {
    _recording = YES;
    _recordStart = [NSDate date].timeIntervalSince1970;
    // 立刻露出轨道，避免等第一帧 percent 才 unhide（对齐 Android 录制环）
    _captureProgress.hidden = NO;
    _captureProgress.percent = 0.001f;
    [_captureBtn bringSubviewToFront:_captureProgress];
    __weak typeof(self) weakSelf = self;
    [_recordTimer invalidate];
    // CommonModes：手指按住（tracking）时 Default 模式 NSTimer 不回调，蓝环不会转
    _recordTimer = [NSTimer timerWithTimeInterval:0.05
                                           repeats:YES
                                             block:^(__unused NSTimer *timer) {
        __strong typeof(weakSelf) strong = weakSelf;
        if (!strong || !strong.recording) {
            return;
        }
        NSTimeInterval elapsed = [NSDate date].timeIntervalSince1970 - strong.recordStart;
        CGFloat p = (CGFloat)MIN(1.0, elapsed / 10.0);
        strong.captureProgress.percent = MAX(p, 0.001f);
        if (p >= 1.0) {
            [strong.delegate previewChromeCaptureTouchUp:YES];
            [strong stopRecordProgress];
        }
    }];
    [[NSRunLoop mainRunLoop] addTimer:_recordTimer forMode:NSRunLoopCommonModes];
}

- (void)stopRecordProgress {
    _recording = NO;
    [_recordTimer invalidate];
    _recordTimer = nil;
    _captureProgress.percent = 0;
}

@end
