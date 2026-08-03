#import "FuBeautyPanelView.h"
#import "FuBeautyHandle.h"
#import <objc/runtime.h>

static const NSInteger kFuBeautyInteractiveTag = 88219901;
static const CGFloat kFuCategoryH = 49.0;
static const CGFloat kFuFunctionH = 141.0;
static const CGFloat kFuSliderH = 30.0;
static const CGFloat kFuBrandR = 0.369; // #5EC7FE
static const CGFloat kFuBrandG = 0.780;
static const CGFloat kFuBrandB = 0.996;
static const void *kFuBeautyItemAssocKey = &kFuBeautyItemAssocKey;

@interface FuBeautyBidirectionalSlider : UISlider
@property (nonatomic, assign) BOOL bidirection;
/** 双向无效果点（UI 值）：-50~50 为 0，瞳孔 0~100 为 50 */
@property (nonatomic, assign) float bipolarZero;
@property (nonatomic, strong) UIView *midLine;
@property (nonatomic, strong) UIView *bipolarBgTrack;  // 双向灰色底轨（整条）
@property (nonatomic, strong) UIView *bipolarTrack;    // 中点→滑块蓝色段
@property (nonatomic, strong) UIImageView *tipBubble;
@property (nonatomic, strong) UILabel *tipLabel;
@property (nonatomic, weak) UIView *tipHost; // 挂到面板根视图，避免被 blur/取景挡住
@property (nonatomic, strong) UIImage *tipBackgroundImage;
@end

@implementation FuBeautyBidirectionalSlider

+ (UIImage *)fuThumbImageHighlighted:(BOOL)highlighted {
    (void)highlighted;
    CGFloat s = 16.0;
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(s, s), NO, 0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    CGContextSetFillColorWithColor(ctx, [UIColor whiteColor].CGColor);
    CGContextFillEllipseInRect(ctx, CGRectMake(0, 0, s, s));
    UIImage *img = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return img;
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.minimumTrackTintColor = [UIColor colorWithRed:kFuBrandR green:kFuBrandG blue:kFuBrandB alpha:1];
        self.maximumTrackTintColor = [[UIColor whiteColor] colorWithAlphaComponent:0.25];
        UIImage *thumb = [[self class] fuThumbImageHighlighted:NO];
        [self setThumbImage:thumb forState:UIControlStateNormal];
        [self setThumbImage:thumb forState:UIControlStateHighlighted];
        [self setThumbImage:thumb forState:UIControlStateSelected];
        [self setThumbImage:thumb forState:UIControlStateDisabled];

        _tipBubble = [[UIImageView alloc] init];
        _tipBubble.contentMode = UIViewContentModeScaleAspectFit;
        _tipBubble.hidden = YES;
        _tipBubble.userInteractionEnabled = NO;
        _tipBubble.layer.zPosition = 99999;
        [self addSubview:_tipBubble];

        _tipLabel = [[UILabel alloc] init];
        _tipLabel.font = [UIFont systemFontOfSize:11 weight:UIFontWeightSemibold];
        _tipLabel.textColor = [UIColor whiteColor];
        _tipLabel.textAlignment = NSTextAlignmentCenter;
        _tipLabel.hidden = YES;
        _tipLabel.userInteractionEnabled = NO;
        [_tipBubble addSubview:_tipLabel];

        // 灰色底轨：双向时盖住系统左右分色轨，保证整条有灰底
        _bipolarBgTrack = [[UIView alloc] init];
        _bipolarBgTrack.backgroundColor = [[UIColor whiteColor] colorWithAlphaComponent:0.25];
        _bipolarBgTrack.layer.cornerRadius = 1.5;
        _bipolarBgTrack.hidden = YES;
        _bipolarBgTrack.userInteractionEnabled = NO;
        [self insertSubview:_bipolarBgTrack atIndex:0];
        _bipolarTrack = [[UIView alloc] init];
        _bipolarTrack.backgroundColor = [UIColor colorWithRed:kFuBrandR green:kFuBrandG blue:kFuBrandB alpha:1];
        _bipolarTrack.layer.cornerRadius = 1.5;
        _bipolarTrack.hidden = YES;
        _bipolarTrack.userInteractionEnabled = NO;
        [self insertSubview:_bipolarTrack atIndex:1];
        _midLine = [[UIView alloc] init];
        _midLine.backgroundColor = [[UIColor whiteColor] colorWithAlphaComponent:0.85];
        _midLine.hidden = YES;
        [self insertSubview:_midLine atIndex:2];
        _bipolarZero = 0.f;
        [self addTarget:self action:@selector(onTouch:) forControlEvents:UIControlEventValueChanged];
        [self addTarget:self action:@selector(onTouchDown) forControlEvents:UIControlEventTouchDown];
        [self addTarget:self action:@selector(onTouchUp) forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];
    }
    return self;
}
- (void)setTipBackgroundImage:(UIImage *)tipBackgroundImage {
    _tipBackgroundImage = tipBackgroundImage;
    if (tipBackgroundImage) {
        _tipBubble.image = [tipBackgroundImage imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal];
        _tipBubble.backgroundColor = [UIColor clearColor];
    } else {
        _tipBubble.backgroundColor = [UIColor colorWithRed:kFuBrandR green:kFuBrandG blue:kFuBrandB alpha:1];
        _tipBubble.layer.cornerRadius = 10;
        _tipBubble.clipsToBounds = YES;
    }
}
- (void)setBidirection:(BOOL)bidirection {
    _bidirection = bidirection;
    _midLine.hidden = !bidirection;
    _bipolarTrack.hidden = !bidirection;
    _bipolarBgTrack.hidden = !bidirection;
    if (bidirection) {
        // 系统轨透明，灰底+蓝段由自定义 view 画
        self.minimumTrackTintColor = [UIColor clearColor];
        self.maximumTrackTintColor = [UIColor clearColor];
    } else {
        self.minimumTrackTintColor = [UIColor colorWithRed:kFuBrandR green:kFuBrandG blue:kFuBrandB alpha:1];
        self.maximumTrackTintColor = [[UIColor whiteColor] colorWithAlphaComponent:0.25];
        _bipolarTrack.hidden = YES;
        _bipolarBgTrack.hidden = YES;
    }
    [self setNeedsLayout];
}
- (CGFloat)fuTrackBarHeight {
    CGRect track = [self trackRectForBounds:self.bounds];
    return MAX(2.0, CGRectGetHeight(track));
}

- (void)fuSendBipolarTracksBehindThumb {
    [self sendSubviewToBack:_bipolarBgTrack];
    [self insertSubview:_bipolarTrack aboveSubview:_bipolarBgTrack];
    [self insertSubview:_midLine aboveSubview:_bipolarTrack];
    for (UIView *sub in self.subviews) {
        if (sub == _bipolarBgTrack || sub == _bipolarTrack || sub == _midLine || sub == _tipBubble) {
            continue;
        }
        [self bringSubviewToFront:sub];
    }
    [self bringSubviewToFront:_tipBubble];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    CGRect track = [self trackRectForBounds:self.bounds];
    CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
    CGFloat thumbCy = CGRectGetMidY(thumb);
    if (_bidirection) {
        CGFloat trackH = [self fuTrackBarHeight];
        CGFloat cornerR = trackH * 0.5;
        _bipolarBgTrack.layer.cornerRadius = cornerR;
        _bipolarTrack.layer.cornerRadius = cornerR;
        CGFloat inset = 8;
        CGFloat trackW = MAX(2, CGRectGetWidth(self.bounds) - inset * 2);
        _bipolarBgTrack.frame = CGRectMake(inset, thumbCy - trackH * 0.5, trackW, trackH);
        _midLine.frame = CGRectMake([self fuMidXForBipolar] - 0.5, thumbCy - 6, 1, 12);
        [self layoutBipolarTrackWithThumb:thumb trackRect:track];
        [self fuSendBipolarTracksBehindThumb];
    }
    [self layoutTipBubbleWithThumb:thumb];
}
- (CGFloat)fuMidXForBipolar {
    CGFloat inset = 8;
    CGFloat trackW = MAX(2, CGRectGetWidth(self.bounds) - inset * 2);
    CGFloat minV = self.minimumValue;
    CGFloat maxV = self.maximumValue;
    CGFloat span = MAX(0.0001f, maxV - minV);
    CGFloat zeroRatio = (_bipolarZero - minV) / span;
    return inset + trackW * zeroRatio;
}

- (void)layoutBipolarTrackWithThumb:(CGRect)thumb trackRect:(CGRect)track {
    if (!_bidirection || _bipolarTrack.hidden) {
        return;
    }
    CGFloat inset = 8;
    CGFloat trackW = CGRectGetWidth(self.bounds) - inset * 2;
    CGFloat midX = [self fuMidXForBipolar];
    CGFloat thumbX = CGRectGetMidX(thumb);
    CGFloat left = MIN(midX, thumbX);
    CGFloat right = MAX(midX, thumbX);
    CGFloat barH = [self fuTrackBarHeight];
    CGFloat thumbCy = CGRectGetMidY(thumb);
    _bipolarTrack.layer.cornerRadius = barH * 0.5;
    _bipolarTrack.frame = CGRectMake(left, thumbCy - barH * 0.5, MAX(2, right - left), barH);
}
- (void)layoutTipBubbleWithThumb:(CGRect)thumb {
    if (_tipBubble.hidden) {
        return;
    }
    CGFloat bubbleW = 28;
    CGFloat bubbleH = 32;
    UIView *host = _tipHost ?: self;
    CGPoint thumbCenterInHost = [self convertPoint:CGPointMake(CGRectGetMidX(thumb), CGRectGetMinY(thumb))
                                            toView:host];
    CGFloat x = thumbCenterInHost.x - bubbleW * 0.5;
    x = MAX(4, MIN(CGRectGetWidth(host.bounds) - bubbleW - 4, x));
    CGFloat y = thumbCenterInHost.y - bubbleH - 2;
    if (host != self) {
        if (_tipBubble.superview != host) {
            [host addSubview:_tipBubble];
        }
        [host bringSubviewToFront:_tipBubble];
    }
    _tipBubble.frame = CGRectMake(x, y, bubbleW, bubbleH);
    _tipLabel.frame = CGRectMake(0, 0, bubbleW, bubbleH - 4);
    [_tipBubble bringSubviewToFront:_tipLabel];
    [self bringSubviewToFront:_tipBubble];
}
- (void)onTouchDown {
    _tipBubble.hidden = NO;
    _tipLabel.hidden = NO;
    [self refreshTip];
    CGRect track = [self trackRectForBounds:self.bounds];
    CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
    [self layoutTipBubbleWithThumb:thumb];
}
- (void)onTouch:(id)sender {
    (void)sender;
    [self refreshTip];
    if (_bidirection) {
        CGRect track = [self trackRectForBounds:self.bounds];
        CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
        [self layoutBipolarTrackWithThumb:thumb trackRect:track];
    }
    CGRect track = [self trackRectForBounds:self.bounds];
    CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
    [self layoutTipBubbleWithThumb:thumb];
}
- (void)onTouchUp {
    _tipBubble.hidden = YES;
    _tipLabel.hidden = YES;
    if (_tipHost && _tipBubble.superview == _tipHost) {
        [self addSubview:_tipBubble];
    }
}
- (void)setValue:(float)value {
    [super setValue:value];
    if (_bidirection) {
        [self setNeedsLayout];
    }
    if (!_tipBubble.hidden) {
        [self refreshTip];
        CGRect track = [self trackRectForBounds:self.bounds];
        CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
        [self layoutTipBubbleWithThumb:thumb];
    }
}
- (void)setValue:(float)value animated:(BOOL)animated {
    [super setValue:value animated:animated];
    if (_bidirection) {
        CGRect track = [self trackRectForBounds:self.bounds];
        CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
        [self layoutBipolarTrackWithThumb:thumb trackRect:track];
    }
    if (!_tipBubble.hidden) {
        [self refreshTip];
        CGRect track = [self trackRectForBounds:self.bounds];
        CGRect thumb = [self thumbRectForBounds:self.bounds trackRect:track value:self.value];
        [self layoutTipBubbleWithThumb:thumb];
    }
}
- (void)refreshTip {
    if (_bidirection) {
        int display = (int)lroundf(self.value - _bipolarZero);
        if (display > 0) {
            _tipLabel.text = [NSString stringWithFormat:@"+%d", display];
        } else {
            _tipLabel.text = [NSString stringWithFormat:@"%d", display];
        }
    } else {
        _tipLabel.text = [NSString stringWithFormat:@"%d", (int)lroundf(self.value)];
    }
}
@end

@interface FuBeautyPanelView () <UIScrollViewDelegate>
@property (nonatomic, strong) UIView *categoryBar;
@property (nonatomic, strong) NSArray<UIButton *> *tabButtons;
@property (nonatomic, strong) UIVisualEffectView *functionBlur;
@property (nonatomic, strong) UIView *functionBody;
@property (nonatomic, strong) FuBeautyBidirectionalSlider *slider;
@property (nonatomic, strong) UIScrollView *iconScroll;
@property (nonatomic, strong) UIButton *recoverBtn;
@property (nonatomic, strong) UIImageView *recoverIcon;
@property (nonatomic, strong) UILabel *recoverLabel;
@property (nonatomic, strong) UIView *recoverDivider;
@property (nonatomic, strong) UIView *whiteningSeg;
@property (nonatomic, strong) UIButton *whiteningGlobalBtn;
@property (nonatomic, strong) UIButton *whiteningSkinBtn;
@property (nonatomic, strong) UIButton *compareBtn;
@property (nonatomic, strong) UIButton *saveBtn;
@property (nonatomic, strong) UIButton *backBtn; // 媒体页返回，对齐选择页 back.png
@property (nonatomic, strong, nullable) UIView *recoverConfirmView; // 面板内确认卡（不走独立 UIWindow）
@property (nonatomic, copy) NSString *activeTab; // skin|shape|filter|"" 
@property (nonatomic, assign) BOOL expanded;
@property (nonatomic, strong) NSArray *skinEffects;
@property (nonatomic, strong) NSArray *shapeEffects;
@property (nonatomic, strong) NSArray *filters;
@property (nonatomic, strong) NSMutableDictionary *values; // key -> slider UI number
@property (nonatomic, strong) NSMutableDictionary *effectMeta; // key -> dict
@property (nonatomic, copy) NSString *selectedEffectKey;
@property (nonatomic, copy) NSString *selectedFilterId;
@property (nonatomic, copy) NSString *whiteningMode; // global|skin
@property (nonatomic, copy) NSString *lastSkinEffectKey;
@property (nonatomic, copy) NSString *lastShapeEffectKey;
@property (nonatomic, assign) CGFloat safeBottom;
@property (nonatomic, assign) int devicePerfLevel;
@property (nonatomic, strong) NSMutableArray<UIView *> *iconCells;
@end

@implementation FuBeautyPanelView

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
        self.userInteractionEnabled = YES;
        self.tag = kFuBeautyInteractiveTag;
        _mode = @"camera";
        _activeTab = @"";
        _expanded = NO;
        _values = [NSMutableDictionary dictionary];
        _effectMeta = [NSMutableDictionary dictionary];
        _iconCells = [NSMutableArray array];
        _selectedFilterId = @"ziran1";
        _whiteningMode = @"global";
        _devicePerfLevel = FuDevicePerformanceLevelCached();
        _safeBottom = 0;
        if (@available(iOS 11.0, *)) {
            // filled in layout from window
        }
        self.clipsToBounds = NO;
        self.layer.zPosition = 30100.f;
        [self buildCategoryBar];
        [self buildFunctionArea];
        [self buildCompare];
        [self buildSaveBtn];
        [self buildBackBtn];
    }
    return self;
}

- (UIColor *)brandColor {
    return [UIColor colorWithRed:kFuBrandR green:kFuBrandG blue:kFuBrandB alpha:1];
}

- (UIColor *)iconBgColorSelected:(BOOL)selected {
    if (selected) {
        return [UIColor colorWithRed:4/255.0 green:11/255.0 blue:14/255.0 alpha:0.36];
    }
    // 对齐恢复按钮可点击态：半透明白底
    return [[UIColor whiteColor] colorWithAlphaComponent:0.2];
}

- (void)buildCategoryBar {
    _categoryBar = [[UIView alloc] init];
    _categoryBar.tag = kFuBeautyInteractiveTag;
    _categoryBar.backgroundColor = [UIColor colorWithRed:5/255.0 green:15/255.0 blue:20/255.0 alpha:1];
    [self addSubview:_categoryBar];

    NSArray *titles = @[ @"美肤", @"美型", @"滤镜" ];
    NSArray *ids = @[ @"skin", @"shape", @"filter" ];
    NSMutableArray *btns = [NSMutableArray array];
    for (NSInteger i = 0; i < 3; i++) {
        UIButton *b = [UIButton buttonWithType:UIButtonTypeCustom];
        b.tag = kFuBeautyInteractiveTag;
        [b setTitle:titles[i] forState:UIControlStateNormal];
        b.titleLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightMedium];
        [b setTitleColor:[[UIColor whiteColor] colorWithAlphaComponent:0.7] forState:UIControlStateNormal];
        [b setTitleColor:[self brandColor] forState:UIControlStateSelected];
        b.accessibilityIdentifier = ids[i];
        [b addTarget:self action:@selector(onTab:) forControlEvents:UIControlEventTouchUpInside];
        [_categoryBar addSubview:b];
        [btns addObject:b];
    }
    _tabButtons = btns;
}

- (void)buildFunctionArea {
    UIBlurEffect *blur = [UIBlurEffect effectWithStyle:UIBlurEffectStyleDark];
    _functionBlur = [[UIVisualEffectView alloc] initWithEffect:blur];
    _functionBlur.tag = kFuBeautyInteractiveTag;
    _functionBlur.clipsToBounds = NO;
    _functionBlur.contentView.clipsToBounds = NO;
    _functionBlur.layer.zPosition = 400;
    _functionBlur.layer.masksToBounds = NO;
    [self addSubview:_functionBlur];

    _functionBody = [[UIView alloc] init];
    _functionBody.tag = kFuBeautyInteractiveTag;
    _functionBody.backgroundColor = [UIColor clearColor];
    _functionBody.clipsToBounds = NO;
    _functionBody.layer.masksToBounds = NO;
    [_functionBlur.contentView addSubview:_functionBody];

    _slider = [[FuBeautyBidirectionalSlider alloc] init];
    _slider.tag = kFuBeautyInteractiveTag;
    _slider.minimumValue = 0;
    _slider.maximumValue = 100;
    _slider.clipsToBounds = NO;
    _slider.layer.masksToBounds = NO;
    _slider.layer.zPosition = 500;
    [_slider addTarget:self action:@selector(onSliderChanged:) forControlEvents:UIControlEventValueChanged];
    [_slider addTarget:self action:@selector(onSliderEnded:) forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];
    [_functionBody addSubview:_slider];
    [_functionBody bringSubviewToFront:_slider];
    _slider.tipHost = self;
    UIImage *tipBg = [self chromeImage:@"slider_tip_background.png"];
    if (!tipBg) {
        tipBg = [self chromeImage:@"slider_tip_background"];
    }
    _slider.tipBackgroundImage = tipBg;

    _recoverBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _recoverBtn.tag = kFuBeautyInteractiveTag;
    // 对齐 Android / Demo：上图标下文案
    _recoverIcon = [[UIImageView alloc] init];
    _recoverIcon.contentMode = UIViewContentModeScaleAspectFit;
    _recoverIcon.userInteractionEnabled = NO;
    UIImage *recImg = [self chromeImage:@"recover.png"];
    if (recImg) {
        _recoverIcon.image = [recImg imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal];
    } else {
        _recoverIcon.backgroundColor = [[UIColor whiteColor] colorWithAlphaComponent:0.2];
        _recoverIcon.layer.cornerRadius = 8;
    }
    [_recoverBtn addSubview:_recoverIcon];
    _recoverLabel = [[UILabel alloc] init];
    _recoverLabel.text = @"恢复";
    _recoverLabel.font = [UIFont systemFontOfSize:10];
    _recoverLabel.textColor = [[UIColor whiteColor] colorWithAlphaComponent:0.85];
    _recoverLabel.textAlignment = NSTextAlignmentCenter;
    _recoverLabel.userInteractionEnabled = NO;
    [_recoverBtn addSubview:_recoverLabel];
    _recoverBtn.alpha = 0.6;
    _recoverBtn.enabled = NO;
    [_recoverBtn addTarget:self action:@selector(onRecover) forControlEvents:UIControlEventTouchUpInside];
    [_functionBody addSubview:_recoverBtn];

    // Demo：恢复与功能图标之间竖向分割线
    _recoverDivider = [[UIView alloc] init];
    _recoverDivider.backgroundColor = [[UIColor colorWithRed:229/255.0 green:229/255.0 blue:229/255.0 alpha:1]
                                       colorWithAlphaComponent:0.2];
    _recoverDivider.hidden = YES;
    [_functionBody addSubview:_recoverDivider];

    _iconScroll = [[UIScrollView alloc] init];
    _iconScroll.tag = kFuBeautyInteractiveTag;
    _iconScroll.showsHorizontalScrollIndicator = NO;
    _iconScroll.showsVerticalScrollIndicator = NO;
    _iconScroll.alwaysBounceVertical = NO;
    _iconScroll.alwaysBounceHorizontal = YES;
    _iconScroll.directionalLockEnabled = YES;
    _iconScroll.delegate = self;
    [_functionBody addSubview:_iconScroll];

    _whiteningSeg = [[UIView alloc] init];
    _whiteningSeg.tag = kFuBeautyInteractiveTag;
    _whiteningSeg.backgroundColor = [UIColor clearColor];
    _whiteningSeg.layer.cornerRadius = 12;
    _whiteningSeg.layer.borderWidth = 1;
    _whiteningSeg.layer.borderColor = [UIColor whiteColor].CGColor;
    _whiteningSeg.clipsToBounds = YES;
    _whiteningSeg.hidden = YES;
    [_functionBody addSubview:_whiteningSeg];

    _whiteningGlobalBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _whiteningGlobalBtn.tag = kFuBeautyInteractiveTag;
    [_whiteningGlobalBtn setTitle:@"全局" forState:UIControlStateNormal];
    _whiteningGlobalBtn.titleLabel.font = [UIFont systemFontOfSize:11 weight:UIFontWeightMedium];
    [_whiteningGlobalBtn addTarget:self action:@selector(onWhiteningGlobal) forControlEvents:UIControlEventTouchUpInside];
    [_whiteningSeg addSubview:_whiteningGlobalBtn];

    _whiteningSkinBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _whiteningSkinBtn.tag = kFuBeautyInteractiveTag;
    [_whiteningSkinBtn setTitle:@"仅皮肤" forState:UIControlStateNormal];
    _whiteningSkinBtn.titleLabel.font = [UIFont systemFontOfSize:11 weight:UIFontWeightMedium];
    [_whiteningSkinBtn addTarget:self action:@selector(onWhiteningSkin) forControlEvents:UIControlEventTouchUpInside];
    [_whiteningSeg addSubview:_whiteningSkinBtn];
    [self refreshWhiteningSeg];
}

- (void)buildCompare {
    _compareBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _compareBtn.tag = kFuBeautyInteractiveTag;
    _compareBtn.backgroundColor = [UIColor clearColor];
    _compareBtn.adjustsImageWhenHighlighted = NO;
    UIImage *cmp = [self chromeImage:@"compare.png"];
    if (cmp) {
        [_compareBtn setImage:[cmp imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                     forState:UIControlStateNormal];
    }
    [self addSubview:_compareBtn];
    [_compareBtn addTarget:self action:@selector(onCompareDown) forControlEvents:UIControlEventTouchDown];
    [_compareBtn addTarget:self action:@selector(onCompareUp)
          forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];
}

- (void)buildSaveBtn {
    _saveBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _saveBtn.tag = kFuBeautyInteractiveTag;
    _saveBtn.hidden = YES;
    _saveBtn.backgroundColor = [UIColor whiteColor];
    _saveBtn.layer.cornerRadius = 29;
    _saveBtn.clipsToBounds = YES;
    _saveBtn.adjustsImageWhenHighlighted = NO;
    UIImage *saveIcon = [self chromeImage:@"download.png"];
    if (saveIcon) {
        [_saveBtn setImage:[saveIcon imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                  forState:UIControlStateNormal];
        _saveBtn.imageView.contentMode = UIViewContentModeScaleAspectFit;
        _saveBtn.contentEdgeInsets = UIEdgeInsetsMake(4, 4, 4, 4);
    } else {
        [_saveBtn setTitle:@"保存" forState:UIControlStateNormal];
        [_saveBtn setTitleColor:[UIColor colorWithWhite:0.1 alpha:1] forState:UIControlStateNormal];
        _saveBtn.titleLabel.font = [UIFont systemFontOfSize:15 weight:UIFontWeightSemibold];
    }
    [_saveBtn addTarget:self action:@selector(onSave) forControlEvents:UIControlEventTouchUpInside];
    [self addSubview:_saveBtn];
}

- (void)buildBackBtn {
    _backBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    _backBtn.tag = kFuBeautyInteractiveTag;
    _backBtn.hidden = YES;
    // 深色半透明底，避免箭头在黑底视频上「看不见」
    _backBtn.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.35];
    _backBtn.layer.cornerRadius = 22;
    _backBtn.clipsToBounds = YES;
    _backBtn.layer.zPosition = 50000.f;
    UIImage *back = [self chromeImage:@"back.png"];
    if (back) {
        [_backBtn setImage:[back imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                  forState:UIControlStateNormal];
        _backBtn.imageView.contentMode = UIViewContentModeScaleAspectFit;
        _backBtn.contentEdgeInsets = UIEdgeInsetsMake(8, 8, 8, 8);
    } else {
        [_backBtn setTitle:@"‹" forState:UIControlStateNormal];
        [_backBtn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
        _backBtn.titleLabel.font = [UIFont systemFontOfSize:28 weight:UIFontWeightLight];
    }
    [_backBtn addTarget:self action:@selector(onBack) forControlEvents:UIControlEventTouchUpInside];
    [self addSubview:_backBtn];
}

- (UIImage *)chromeImage:(NSString *)name {
    NSString *base = [name stringByDeletingPathExtension];
    NSBundle *b = [NSBundle bundleForClass:self.class];
    NSString *path = [b pathForResource:base ofType:@"png" inDirectory:@"fu_chrome"];
    if (!path) {
        path = [b pathForResource:base ofType:@"png"];
    }
    return path ? [UIImage imageWithContentsOfFile:path] : nil;
}

#pragma mark - Public

- (void)setMode:(NSString *)mode {
    _mode = [mode copy] ?: @"camera";
    BOOL media = [_mode isEqualToString:@"image"] || [_mode isEqualToString:@"video"];
    _saveBtn.hidden = !media;
    // 返回钮改由 NamaModule 叠在 host 上（避免被面板/视频盖住看不见）
    _backBtn.hidden = YES;
    // 媒体页对比仍可用
    [self setNeedsLayout];
    [self notifyHeight];
}

- (void)applyConfig:(NSDictionary *)config {
    if (![config isKindOfClass:[NSDictionary class]]) {
        return;
    }
    _skinEffects = [config[@"skin"] isKindOfClass:[NSArray class]] ? config[@"skin"] : @[];
    _shapeEffects = [config[@"shape"] isKindOfClass:[NSArray class]] ? config[@"shape"] : @[];
    _filters = [config[@"filters"] isKindOfClass:[NSArray class]] ? config[@"filters"] : @[];
    [_effectMeta removeAllObjects];
    for (NSArray *arr in @[ _skinEffects, _shapeEffects ]) {
        for (NSDictionary *e in arr) {
            if ([e isKindOfClass:[NSDictionary class]] && e[@"key"]) {
                _effectMeta[e[@"key"]] = e;
            }
        }
    }
    NSDictionary *vals = config[@"values"];
    if ([vals isKindOfClass:[NSDictionary class]]) {
        [_values addEntriesFromDictionary:vals];
    }
    if ([config[@"filterId"] isKindOfClass:[NSString class]]) {
        _selectedFilterId = config[@"filterId"];
    }
    if (config[@"devicePerfLevel"] != nil) {
        _devicePerfLevel = (int)MAX(1, MIN(4, [config[@"devicePerfLevel"] intValue]));
    } else {
        _devicePerfLevel = FuDevicePerformanceLevelCached();
    }
    if ([config[@"whiteningMode"] isKindOfClass:[NSString class]]) {
        _whiteningMode = config[@"whiteningMode"];
    }
    if (_devicePerfLevel < 4 && [_whiteningMode isEqualToString:@"skin"]) {
        _whiteningMode = @"global";
    }
    if ([config[@"selectedKey"] isKindOfClass:[NSString class]]) {
        _selectedEffectKey = config[@"selectedKey"];
    } else if (_skinEffects.count > 0) {
        _selectedEffectKey = _skinEffects[0][@"key"];
    }
    if (_selectedEffectKey.length) {
        _lastSkinEffectKey = [_selectedEffectKey copy];
    } else if (_skinEffects.count > 0) {
        _lastSkinEffectKey = [_skinEffects[0][@"key"] copy];
        _selectedEffectKey = [_lastSkinEffectKey copy];
    }
    if (_shapeEffects.count > 0) {
        _lastShapeEffectKey = [_shapeEffects[0][@"key"] copy];
    }
    NSString *mode = config[@"mode"];
    if ([mode isKindOfClass:[NSString class]]) {
        self.mode = mode;
    }
    [self reloadIconStrip];
    [self syncSliderToSelection];
    [self refreshWhiteningSeg];
    [self setNeedsLayout];
}

- (void)updateValues:(NSDictionary *)values {
    if (![values isKindOfClass:[NSDictionary class]]) {
        return;
    }
    [_values addEntriesFromDictionary:values];
    if (_iconCells.count > 0) {
        [self refreshIconSelectionOnly];
    } else {
        [self reloadIconStrip];
    }
    [self syncSliderToSelection];
    [self refreshRecoverEnabled];
}

- (void)setSelectedFilterId:(NSString *)filterId {
    _selectedFilterId = [filterId copy] ?: @"origin";
    if ([_activeTab isEqualToString:@"filter"]) {
        [self refreshIconSelectionOnly];
        [self syncSliderToSelection];
        [self setNeedsLayout];
    }
}

- (void)setSelectedEffectKey:(NSString *)key {
    if (key.length == 0) {
        return;
    }
    _selectedEffectKey = [key copy];
    if ([_activeTab isEqualToString:@"skin"]) {
        _lastSkinEffectKey = _selectedEffectKey;
    } else if ([_activeTab isEqualToString:@"shape"]) {
        _lastShapeEffectKey = _selectedEffectKey;
    }
    if (![_activeTab isEqualToString:@"filter"]) {
        [self refreshIconSelectionOnly];
        [self syncSliderToSelection];
        [self setNeedsLayout];
    }
}

- (void)setWhiteningMode:(NSString *)mode {
    _whiteningMode = [mode copy] ?: @"global";
    [self refreshWhiteningSeg];
}

- (CGFloat)currentPanelHeight {
    CGFloat safe = [self resolvedSafeBottom];
    if (_expanded) {
        return kFuCategoryH + kFuFunctionH + safe;
    }
    return kFuCategoryH + safe;
}

- (BOOL)hitInteractiveAtPoint:(CGPoint)point {
    if (!self.userInteractionEnabled || self.hidden || self.alpha < 0.01) {
        return NO;
    }
    UIView *hit = [self hitTest:point withEvent:nil];
    return hit != nil && hit != self;
}

#pragma mark - Layout

- (CGFloat)resolvedSafeBottom {
    if (@available(iOS 11.0, *)) {
        UIWindow *w = self.window;
        if (w) {
            return w.safeAreaInsets.bottom;
        }
    }
    return _safeBottom;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    CGFloat w = CGRectGetWidth(self.bounds);
    CGFloat h = CGRectGetHeight(self.bounds);
    CGFloat safe = [self resolvedSafeBottom];
    CGFloat catH = kFuCategoryH + safe;
    _categoryBar.frame = CGRectMake(0, h - catH, w, catH);

    CGFloat tabW = w / 3.0;
    for (NSInteger i = 0; i < _tabButtons.count; i++) {
        _tabButtons[i].frame = CGRectMake(tabW * i, 0, tabW, kFuCategoryH);
    }

    CGFloat funcY = h - catH - (_expanded ? kFuFunctionH : 0);
    _functionBlur.frame = CGRectMake(0, funcY, w, kFuFunctionH);
    _functionBlur.hidden = !_expanded;
    _functionBody.frame = _functionBlur.contentView.bounds;

    // 滑杆略靠上；tip 气泡挂到面板根视图，不占用功能区顶部空间
    const CGFloat sliderTop = 18.0;
    _slider.frame = CGRectMake(24, sliderTop, w - 48, kFuSliderH);
    [_functionBody bringSubviewToFront:_slider];
    [_functionBlur.superview bringSubviewToFront:_functionBlur];

    BOOL filterTab = [_activeTab isEqualToString:@"filter"];
    _recoverBtn.hidden = filterTab;
    _recoverDivider.hidden = filterTab;
    CGFloat recoverW = filterTab ? 0 : 44;
    // 与分割线垂直对齐：图标行顶对齐恢复钮，分割线对 44 图标中心（勿用整钮 74 中心）
    const CGFloat iconRowY = 71.0;
    if (!filterTab) {
        _recoverBtn.frame = CGRectMake(8, iconRowY, recoverW, 74);
        _recoverIcon.frame = CGRectMake(0, 0, 44, 44);
        _recoverLabel.frame = CGRectMake(0, 46, 44, 16);
        CGFloat divX = 8 + recoverW + 8;
        CGFloat divCy = iconRowY + 22.0; // 44 图标垂直中心
        _recoverDivider.frame = CGRectMake(divX, divCy - 12, 1, 24);
        [self refreshRecoverEnabled];
    }
    CGFloat scrollX = filterTab ? 8 : (8 + recoverW + 8 + 8); // +divider gap
    _iconScroll.frame = CGRectMake(scrollX, iconRowY, w - scrollX - 8, 84);
    // 锁死纵向：content 高度 = 可视高度，禁止上下滚
    CGSize cs = _iconScroll.contentSize;
    if (cs.width < 1) {
        cs.width = CGRectGetWidth(_iconScroll.bounds);
    }
    cs.height = CGRectGetHeight(_iconScroll.bounds);
    _iconScroll.contentSize = cs;
    if (fabs(_iconScroll.contentOffset.y) > 0.5) {
        _iconScroll.contentOffset = CGPointMake(_iconScroll.contentOffset.x, 0);
    }

    BOOL showWhite = _expanded && [_activeTab isEqualToString:@"skin"] &&
        ([_selectedEffectKey isEqualToString:@"color_level_mode2"] ||
         [_selectedEffectKey isEqualToString:@"color_level"]);
    _whiteningSeg.hidden = !showWhite;
    if (showWhite) {
        // 对齐 Android / Demo：美白分段在滑杆左侧，不被滑杆盖住
        CGFloat segW = 80;
        _whiteningSeg.frame = CGRectMake(16, sliderTop + 2, segW, 24);
        _whiteningGlobalBtn.frame = CGRectMake(0, 0, 40, 24);
        _whiteningSkinBtn.frame = CGRectMake(40, 0, 40, 24);
        _slider.frame = CGRectMake(16 + segW + 12, sliderTop, w - (16 + segW + 12) - 24, kFuSliderH);
        [_functionBody bringSubviewToFront:_whiteningSeg];
        [_functionBody bringSubviewToFront:_slider];
    }

    CGFloat compareY = funcY - 54;
    if (!_expanded) {
        compareY = h - catH - 54;
    }
    _compareBtn.frame = CGRectMake(15, compareY, 44, 44);
    _compareBtn.hidden = NO;

    BOOL media = [_mode isEqualToString:@"image"] || [_mode isEqualToString:@"video"];
    _saveBtn.hidden = !media;
    _backBtn.hidden = YES; // NamaModule.ensureMediaBackButton 负责显示
    if (media) {
        CGFloat panelTop = _expanded ? funcY : (h - catH);
        _saveBtn.frame = CGRectMake((w - 58) * 0.5, panelTop - 10 - 58, 58, 58);
    }
    if (_recoverConfirmView) {
        _recoverConfirmView.frame = self.bounds;
        [self bringSubviewToFront:_recoverConfirmView];
    }
}

- (void)notifyHeight {
    CGFloat ht = [self currentPanelHeight];
    if ([self.delegate respondsToSelector:@selector(beautyPanelDidChangeHeight:)]) {
        [self.delegate beautyPanelDidChangeHeight:ht];
    }
}

#pragma mark - Icons

- (NSArray *)currentEffects {
    if ([_activeTab isEqualToString:@"shape"]) {
        return _shapeEffects ?: @[];
    }
    if ([_activeTab isEqualToString:@"filter"]) {
        return _filters ?: @[];
    }
    return _skinEffects ?: @[];
}

/** 对齐 beauty.vue isEffectDisabled：unimplemented 或 devicePerfLevel < performanceLevel */
- (BOOL)isEffectItemDisabled:(NSDictionary *)item {
    if (![item isKindOfClass:[NSDictionary class]]) {
        return YES;
    }
    if ([item[@"unimplemented"] boolValue]) {
        return YES;
    }
    int need = 0;
    if (item[@"performanceLevel"] != nil) {
        need = [item[@"performanceLevel"] intValue];
    }
    if (need < 0) {
        return NO;
    }
    if (need == 0) {
        return NO;
    }
    return _devicePerfLevel < need;
}

- (BOOL)canUseSkinWhitening {
    return _devicePerfLevel >= 4;
}

- (NSString *)perfLevelName:(int)level {
    switch (level) {
        case 1: return @"低端";
        case 2: return @"中高端";
        case 3: return @"高端";
        case 4: return @"旗舰";
        default: return @"更高";
    }
}

- (void)showPerfLimitToastForItem:(NSDictionary *)item {
    if (![item isKindOfClass:[NSDictionary class]]) {
        return;
    }
    int need = 0;
    if (item[@"performanceLevel"] != nil) {
        need = [item[@"performanceLevel"] intValue];
    }
    NSString *name = [item[@"name"] isKindOfClass:[NSString class]] ? item[@"name"] : @"该功能";
    NSString *msg = need > 0
        ? [NSString stringWithFormat:@"%@仅支持%@及以上机型", name, [self perfLevelName:need]]
        : [NSString stringWithFormat:@"%@当前不可用", name];
    if ([self.delegate respondsToSelector:@selector(beautyPanelShowPerfLimitTip:)]) {
        [self.delegate beautyPanelShowPerfLimitTip:msg];
    }
}

/** 图标四态「开启」：滑杆相对默认值有偏移，对齐 JS isEffectChanged */
- (BOOL)isChangedKey:(NSString *)key meta:(NSDictionary *)meta {
    if (key.length == 0) {
        return NO;
    }
    double zeroRef = [self defaultSliderForItem:meta];
    if (meta[@"sliderZero"] != nil) {
        zeroRef = [meta[@"sliderZero"] doubleValue];
    } else if ([meta[@"bidirectional"] boolValue] && meta[@"defaultSlider"] != nil) {
        zeroRef = [meta[@"defaultSlider"] doubleValue];
    }
    double cur = _values[key] ? [_values[key] doubleValue] : zeroRef;
    return fabs(cur - zeroRef) > 0.01;
}

- (void)reloadIconStrip {
    for (UIView *v in _iconCells) {
        [v removeFromSuperview];
    }
    [_iconCells removeAllObjects];

    BOOL filterTab = [_activeTab isEqualToString:@"filter"];
    NSArray *items = [self currentEffects];
    CGFloat x = 0;
    CGFloat cellW = 44;
    CGFloat cellH = 74;
    CGFloat gap = 22;

    for (NSInteger i = 0; i < (NSInteger)items.count; i++) {
        NSDictionary *item = items[i];
        if (![item isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        UIView *cell = [[UIView alloc] initWithFrame:CGRectMake(x, 0, cellW, cellH)];
        cell.tag = kFuBeautyInteractiveTag;
        cell.userInteractionEnabled = YES;

        UIImageView *iv = [[UIImageView alloc] initWithFrame:CGRectMake(0, 0, cellW, 44)];
        iv.tag = kFuBeautyInteractiveTag;
        iv.contentMode = UIViewContentModeScaleAspectFill;
        iv.clipsToBounds = YES;
        iv.layer.cornerRadius = filterTab ? 4 : 22;
        NSString *key = item[@"key"] ?: item[@"id"] ?: @"";
        BOOL selected = filterTab ? [key isEqualToString:_selectedFilterId] || [item[@"id"] isEqualToString:_selectedFilterId]
                                 : [key isEqualToString:_selectedEffectKey];
        iv.backgroundColor = [self iconBgColorSelected:selected];
        [cell addSubview:iv];

        UILabel *lab = [[UILabel alloc] initWithFrame:CGRectMake(-4, CGRectGetMaxY(iv.frame) + 4, cellW + 8, 16)];
        lab.tag = kFuBeautyInteractiveTag;
        lab.font = [UIFont systemFontOfSize:10];
        lab.textAlignment = NSTextAlignmentCenter;
        lab.textColor = [[UIColor whiteColor] colorWithAlphaComponent:0.85];
        lab.text = item[@"name"] ?: @"";
        [cell addSubview:lab];

        BOOL changed = filterTab ? NO : [self isChangedKey:key meta:item];
        if (selected) {
            lab.textColor = [self brandColor];
            if (filterTab) {
                iv.layer.borderWidth = 2;
                iv.layer.borderColor = [self brandColor].CGColor;
            }
        }
        NSString *iconUrl = item[@"iconUrl"] ?: item[@"icon"] ?: @"";
        if (!filterTab) {
            // 四态：default / Changes / Active / ChangesActive
            NSString *base = item[@"iconBase"] ?: @"";
            NSString *suffix = @"";
            if (selected && changed) {
                suffix = @"ChangesActive";
            } else if (selected) {
                suffix = @"Active";
            } else if (changed) {
                suffix = @"Changes";
            }
            if (suffix.length && [item[[NSString stringWithFormat:@"iconUrl%@", suffix]] isKindOfClass:[NSString class]]) {
                iconUrl = item[[NSString stringWithFormat:@"iconUrl%@", suffix]];
            }
            (void)base;
        }
        [self loadImage:iconUrl into:iv];

        BOOL disabled = [self isEffectItemDisabled:item];
        cell.alpha = disabled ? 0.35 : 1.0;
        cell.userInteractionEnabled = !disabled;
        lab.alpha = disabled ? 0.5 : 1.0;

        UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onIconTap:)];
        cell.accessibilityIdentifier = key;
        objc_setAssociatedObject(cell, kFuBeautyItemAssocKey, item, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        [cell addGestureRecognizer:tap];
        [_iconScroll addSubview:cell];
        [_iconCells addObject:cell];
        x += cellW + gap;
    }
    _iconScroll.contentSize = CGSizeMake(MAX(x, CGRectGetWidth(_iconScroll.bounds)),
                                         CGRectGetHeight(_iconScroll.bounds));
    _iconScroll.contentOffset = CGPointMake(_iconScroll.contentOffset.x, 0);
    if (!filterTab) {
        [self refreshRecoverEnabled];
    }
}

/** Demo：有改动 α=1 可点；已是默认值则 α=0.6 不可点 */
- (void)refreshRecoverEnabled {
    if (!_recoverBtn || _recoverBtn.hidden) {
        return;
    }
    BOOL changed = [self tabHasChanges:_activeTab];
    _recoverBtn.alpha = changed ? 1.0 : 0.6;
    _recoverBtn.enabled = changed;
    _recoverBtn.userInteractionEnabled = changed;
}

- (double)defaultSliderForItem:(NSDictionary *)item {
    if (![item isKindOfClass:[NSDictionary class]]) {
        return 0;
    }
    if (item[@"defaultSlider"] != nil) {
        return [item[@"defaultSlider"] doubleValue];
    }
    double defSlider = [item[@"default"] ?: @0 doubleValue];
    if (item[@"default"] && item[@"min"] && item[@"max"]) {
        double minV = [item[@"min"] doubleValue];
        double maxV = [item[@"max"] doubleValue];
        double sMin = [item[@"sliderMin"] ?: @0 doubleValue];
        double sMax = [item[@"sliderMax"] ?: @100 doubleValue];
        double sdk = [item[@"default"] doubleValue];
        if (fabs(maxV - minV) > 1e-6) {
            defSlider = sMin + (sdk - minV) / (maxV - minV) * (sMax - sMin);
        }
    }
    return defSlider;
}

- (BOOL)tabHasChanges:(NSString *)tabId {
    if ([tabId isEqualToString:@"filter"]) {
        return NO;
    }
    NSArray *list = [tabId isEqualToString:@"shape"] ? _shapeEffects : _skinEffects;
    for (NSDictionary *item in list) {
        if (![item isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        NSString *key = item[@"key"];
        if (key.length == 0 || [self isEffectItemDisabled:item]) {
            continue;
        }
        double def = [self defaultSliderForItem:item];
        double cur = _values[key] ? [_values[key] doubleValue] : def;
        if (fabs(cur - def) > 0.5) { // 滑杆整数步进，容差 0.5
            return YES;
        }
    }
    if ([tabId isEqualToString:@"skin"] && ![_whiteningMode isEqualToString:@"global"]) {
        return YES;
    }
    return NO;
}

/** 仅刷新选中态/图标，不拆子 View，避免滤镜切换整条闪白 */
- (void)refreshIconSelectionOnly {
    BOOL filterTab = [_activeTab isEqualToString:@"filter"];
    NSArray *items = [self currentEffects];
    for (NSInteger i = 0; i < (NSInteger)_iconCells.count && i < (NSInteger)items.count; i++) {
        UIView *cell = _iconCells[i];
        NSDictionary *item = items[i];
        if (![item isKindOfClass:[NSDictionary class]] || cell.subviews.count < 2) {
            continue;
        }
        UIImageView *iv = (UIImageView *)cell.subviews[0];
        UILabel *lab = (UILabel *)cell.subviews[1];
        if (![iv isKindOfClass:[UIImageView class]] || ![lab isKindOfClass:[UILabel class]]) {
            continue;
        }
        NSString *key = item[@"key"] ?: item[@"id"] ?: @"";
        BOOL selected = filterTab
            ? [key isEqualToString:_selectedFilterId] || [item[@"id"] isEqualToString:_selectedFilterId]
            : [key isEqualToString:_selectedEffectKey];
        BOOL changed = filterTab ? NO : [self isChangedKey:key meta:item];
        BOOL disabled = [self isEffectItemDisabled:item];
        cell.alpha = disabled ? 0.35 : 1.0;
        cell.userInteractionEnabled = !disabled;
        lab.alpha = disabled ? 0.5 : 1.0;
        lab.textColor = selected ? [self brandColor] : [[UIColor whiteColor] colorWithAlphaComponent:0.85];
        iv.backgroundColor = [self iconBgColorSelected:selected];
        if (filterTab) {
            iv.layer.borderWidth = selected ? 2 : 0;
            iv.layer.borderColor = selected ? [self brandColor].CGColor : nil;
        }
        NSString *iconUrl = item[@"iconUrl"] ?: item[@"icon"] ?: @"";
        if (!filterTab) {
            NSString *suffix = @"";
            if (selected && changed) {
                suffix = @"ChangesActive";
            } else if (selected) {
                suffix = @"Active";
            } else if (changed) {
                suffix = @"Changes";
            }
            NSString *altKey = [NSString stringWithFormat:@"iconUrl%@", suffix];
            if (suffix.length && [item[altKey] isKindOfClass:[NSString class]]) {
                iconUrl = item[altKey];
            }
        }
        [self loadImage:iconUrl into:iv];
    }
}

- (void)loadImage:(NSString *)urlOrPath into:(UIImageView *)iv {
    if (urlOrPath.length == 0) {
        return;
    }
    if ([urlOrPath hasPrefix:@"http://"] || [urlOrPath hasPrefix:@"https://"]) {
        NSURL *url = [NSURL URLWithString:urlOrPath];
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSData *data = [NSData dataWithContentsOfURL:url];
            UIImage *img = data ? [UIImage imageWithData:data] : nil;
            dispatch_async(dispatch_get_main_queue(), ^{
                if (img) {
                    iv.image = img;
                }
            });
        });
        return;
    }
    NSString *path = urlOrPath;
    if ([path hasPrefix:@"file://"]) {
        path = [path substringFromIndex:7];
    }
    UIImage *img = [UIImage imageWithContentsOfFile:path];
    if (img) {
        iv.image = img;
    }
}

#pragma mark - Tab selection memory

- (NSString *)firstEffectKeyInList:(NSArray *)list {
    for (NSDictionary *item in list) {
        if (![item isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        if ([self isEffectItemDisabled:item]) {
            continue;
        }
        NSString *key = [item[@"key"] isKindOfClass:[NSString class]]
            ? item[@"key"]
            : [[item[@"key"] description] copy];
        if (key.length) {
            return key;
        }
    }
    return @"";
}

- (BOOL)effectList:(NSArray *)list containsKey:(NSString *)key {
    if (key.length == 0) {
        return NO;
    }
    for (NSDictionary *item in list) {
        if (![item isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        NSString *k = [item[@"key"] isKindOfClass:[NSString class]]
            ? item[@"key"]
            : [[item[@"key"] description] copy];
        if ([k isEqualToString:key]) {
            return YES;
        }
    }
    return NO;
}

- (void)rememberTabSelection:(NSString *)tabId {
    if ([tabId isEqualToString:@"skin"] && _selectedEffectKey.length) {
        _lastSkinEffectKey = [_selectedEffectKey copy];
    } else if ([tabId isEqualToString:@"shape"] && _selectedEffectKey.length) {
        _lastShapeEffectKey = [_selectedEffectKey copy];
    }
}

- (void)restoreTabSelection:(NSString *)tabId {
    if ([tabId isEqualToString:@"skin"]) {
        if (!_lastSkinEffectKey.length) {
            _lastSkinEffectKey = [self firstEffectKeyInList:_skinEffects];
        }
        if (_lastSkinEffectKey.length) {
            _selectedEffectKey = [_lastSkinEffectKey copy];
        }
    } else if ([tabId isEqualToString:@"shape"]) {
        if (!_lastShapeEffectKey.length) {
            _lastShapeEffectKey = [self firstEffectKeyInList:_shapeEffects];
        }
        if (_lastShapeEffectKey.length) {
            _selectedEffectKey = [_lastShapeEffectKey copy];
        }
    }
    if ([tabId isEqualToString:@"skin"] || [tabId isEqualToString:@"shape"]) {
        NSArray *list = [tabId isEqualToString:@"shape"] ? _shapeEffects : _skinEffects;
        if (![self effectList:list containsKey:_selectedEffectKey]) {
            NSString *fallback = [self firstEffectKeyInList:list];
            if (fallback.length) {
                _selectedEffectKey = fallback;
            }
        }
        if ([tabId isEqualToString:@"skin"]) {
            _lastSkinEffectKey = [_selectedEffectKey copy];
        } else {
            _lastShapeEffectKey = [_selectedEffectKey copy];
        }
    }
}

- (NSString *)activeEffectKey {
    if ([_activeTab isEqualToString:@"filter"]) {
        return @"filter_level";
    }
    NSArray *list = [self currentEffects];
    if ([self effectList:list containsKey:_selectedEffectKey]) {
        return _selectedEffectKey;
    }
    NSString *fallback = [self firstEffectKeyInList:list];
    if (fallback.length) {
        _selectedEffectKey = [fallback copy];
        if ([_activeTab isEqualToString:@"skin"]) {
            _lastSkinEffectKey = [_selectedEffectKey copy];
        } else if ([_activeTab isEqualToString:@"shape"]) {
            _lastShapeEffectKey = [_selectedEffectKey copy];
        }
    }
    return _selectedEffectKey ?: @"";
}

#pragma mark - Slider mapping

- (NSDictionary *)selectedMeta {
    if ([_activeTab isEqualToString:@"filter"]) {
        return @{
            @"key": @"filter_level",
            @"sliderMin": @(0),
            @"sliderMax": @(100),
            @"min": @(0),
            @"max": @(1),
            @"bidirectional": @(NO),
        };
    }
    return _effectMeta[[self activeEffectKey]] ?: @{};
}

- (BOOL)isOriginFilterSelected {
    if (![_activeTab isEqualToString:@"filter"]) {
        return NO;
    }
    return [_selectedFilterId isEqualToString:@"origin"] || _selectedFilterId.length == 0;
}

- (void)updateSliderVisibility {
    BOOL hide = [self isOriginFilterSelected];
    _slider.hidden = hide;
    _slider.userInteractionEnabled = !hide;
}

- (void)syncSliderToSelection {
    [self updateSliderVisibility];
    if ([self isOriginFilterSelected]) {
        return;
    }
    NSDictionary *meta = [self selectedMeta];
    BOOL bi = [meta[@"bidirectional"] boolValue];
    double sMin = [meta[@"sliderMin"] ?: @0 doubleValue];
    double sMax = [meta[@"sliderMax"] ?: @100 doubleValue];
    _slider.bidirection = bi;
    if (bi && meta[@"sliderZero"] != nil) {
        _slider.bipolarZero = (float)[meta[@"sliderZero"] doubleValue];
    } else {
        _slider.bipolarZero = 0.f;
    }
    _slider.minimumValue = (float)sMin;
    _slider.maximumValue = (float)sMax;
    NSString *key = [self activeEffectKey];
    NSNumber *v = _values[key];
    double cur = v ? v.doubleValue : [self defaultSliderForItem:meta];
    [_slider setValue:(float)cur animated:NO];
    BOOL disabled = [self isEffectItemDisabled:meta];
    _slider.enabled = !disabled;
    _slider.alpha = disabled ? 0.4 : 1;
    [_slider setNeedsLayout];
    [_slider layoutIfNeeded];
}

- (double)sdkValueFromSlider:(double)slider meta:(NSDictionary *)meta {
    double sMin = [meta[@"sliderMin"] ?: @0 doubleValue];
    double sMax = [meta[@"sliderMax"] ?: @100 doubleValue];
    double minV = [meta[@"min"] ?: @0 doubleValue];
    double maxV = [meta[@"max"] ?: @1 doubleValue];
    if (fabs(sMax - sMin) < 1e-6) {
        return minV;
    }
    double ratio = (slider - sMin) / (sMax - sMin);
    double raw = minV + ratio * (maxV - minV);
    return round(raw * 100.0) / 100.0;
}

#pragma mark - Actions

- (void)onTab:(UIButton *)sender {
    NSString *tabId = sender.accessibilityIdentifier ?: @"skin";
    if ([tabId isEqualToString:_activeTab] && _expanded) {
        // 再点收起
        [self rememberTabSelection:_activeTab];
        _expanded = NO;
        _activeTab = @"";
        for (UIButton *b in _tabButtons) {
            b.selected = NO;
        }
        // 先通知高度，让拍照钮与面板同开 0.2s 动画（勿等 completion 再 notify）
        [self notifyHeight];
        [UIView animateWithDuration:0.2 animations:^{
            [self layoutSubviews];
        }];
        if ([self.delegate respondsToSelector:@selector(beautyPanelSelectTab:expanded:)]) {
            [self.delegate beautyPanelSelectTab:@"" expanded:NO];
        }
        return;
    }
    if (_activeTab.length && ![_activeTab isEqualToString:tabId]) {
        [self rememberTabSelection:_activeTab];
    }
    _activeTab = tabId;
    _expanded = YES;
    for (UIButton *b in _tabButtons) {
        b.selected = [b.accessibilityIdentifier isEqualToString:tabId];
    }
    [self restoreTabSelection:tabId];
    [self reloadIconStrip];
    [self syncSliderToSelection];
    [self refreshWhiteningSeg];
    [self notifyHeight];
    [UIView animateWithDuration:0.2 animations:^{
        [self layoutSubviews];
    }];
    if ([self.delegate respondsToSelector:@selector(beautyPanelSelectTab:expanded:)]) {
        [self.delegate beautyPanelSelectTab:tabId expanded:YES];
    }
    if ([tabId isEqualToString:@"skin"] || [tabId isEqualToString:@"shape"]) {
        if ([self.delegate respondsToSelector:@selector(beautyPanelSelectEffect:)]) {
            [self.delegate beautyPanelSelectEffect:_selectedEffectKey ?: @""];
        }
    }
}

- (void)onIconTap:(UITapGestureRecognizer *)gr {
    UIView *cell = gr.view;
    NSDictionary *item = objc_getAssociatedObject(cell, kFuBeautyItemAssocKey);
    if (![item isKindOfClass:[NSDictionary class]]) {
        return;
    }
    if ([self isEffectItemDisabled:item]) {
        [self showPerfLimitToastForItem:item];
        return;
    }
    if ([_activeTab isEqualToString:@"filter"]) {
        NSString *fid = item[@"id"] ?: item[@"key"] ?: @"";
        NSString *fkey = item[@"key"] ?: fid;
        _selectedFilterId = fid;
        [self refreshIconSelectionOnly];
        [self syncSliderToSelection];
        if ([self.delegate respondsToSelector:@selector(beautyPanelSelectFilter:filterKey:filterName:)]) {
            [self.delegate beautyPanelSelectFilter:fid filterKey:fkey filterName:item[@"name"] ?: fkey];
        }
        return;
    }
    NSString *key = item[@"key"] ?: @"";
    _selectedEffectKey = key;
    if ([_activeTab isEqualToString:@"skin"]) {
        _lastSkinEffectKey = key;
    } else if ([_activeTab isEqualToString:@"shape"]) {
        _lastShapeEffectKey = key;
    }
    [self refreshIconSelectionOnly];
    [self syncSliderToSelection];
    [self setNeedsLayout];
    if ([self.delegate respondsToSelector:@selector(beautyPanelSelectEffect:)]) {
        [self.delegate beautyPanelSelectEffect:key];
    }
}

- (void)onSliderChanged:(UISlider *)sender {
    NSDictionary *meta = [self selectedMeta];
    NSString *key = [self activeEffectKey];
    if (key.length == 0) {
        return;
    }
    _values[key] = @(sender.value);
    double sdk = [self sdkValueFromSlider:sender.value meta:meta];
    if ([self.delegate respondsToSelector:@selector(beautyPanelSliderChanged:value:)]) {
        [self.delegate beautyPanelSliderChanged:key value:sdk];
    }
}

- (void)onSliderEnded:(UISlider *)sender {
    [self refreshIconSelectionOnly];
    [self refreshRecoverEnabled];
    [self onSliderChanged:sender];
}

- (void)onRecover {
    if (!_recoverBtn.enabled || !_recoverBtn.userInteractionEnabled) {
        return;
    }
    if (![self tabHasChanges:_activeTab]) {
        [self refreshRecoverEnabled];
        return;
    }
    [self showRecoverConfirm];
}

/** 面板内确认卡：点得到、不依赖独立 UIWindow / Alert */
- (void)showRecoverConfirm {
    if (_recoverConfirmView) {
        return;
    }
    NSString *tab = _activeTab.length ? _activeTab : @"skin";
    NSString *tabLabel = [tab isEqualToString:@"shape"] ? @"美型" : @"美肤";

    UIView *wrap = [[UIView alloc] initWithFrame:self.bounds];
    wrap.tag = kFuBeautyInteractiveTag;
    wrap.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    wrap.backgroundColor = [UIColor clearColor];
    wrap.userInteractionEnabled = YES;
    wrap.layer.zPosition = 99999;

    // 透明全屏拦点击，避免点到下层；无半透明黑遮罩
    UIButton *blocker = [UIButton buttonWithType:UIButtonTypeCustom];
    blocker.frame = wrap.bounds;
    blocker.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    blocker.backgroundColor = [UIColor clearColor];
    [blocker addTarget:self action:@selector(onRecoverConfirmCancel) forControlEvents:UIControlEventTouchUpInside];
    [wrap addSubview:blocker];

    CGFloat cardW = MIN(CGRectGetWidth(self.bounds) - 56.f, 300.f);
    CGFloat cardH = 168.f;
    UIView *card = [[UIView alloc] initWithFrame:CGRectMake((CGRectGetWidth(self.bounds) - cardW) * 0.5f,
                                                            (CGRectGetHeight(self.bounds) - cardH) * 0.42f,
                                                            cardW, cardH)];
    card.tag = kFuBeautyInteractiveTag;
    card.backgroundColor = [UIColor colorWithWhite:0.14 alpha:0.98];
    card.layer.cornerRadius = 12.f;
    card.clipsToBounds = YES;
    card.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin
        | UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleBottomMargin;
    [wrap addSubview:card];

    UILabel *title = [[UILabel alloc] initWithFrame:CGRectMake(16, 18, cardW - 32, 22)];
    title.text = @"恢复默认";
    title.font = [UIFont systemFontOfSize:17 weight:UIFontWeightSemibold];
    title.textColor = [UIColor whiteColor];
    title.textAlignment = NSTextAlignmentCenter;
    [card addSubview:title];

    UILabel *msg = [[UILabel alloc] initWithFrame:CGRectMake(16, 48, cardW - 32, 44)];
    msg.text = [NSString stringWithFormat:@"确定将当前「%@」参数恢复为默认值？", tabLabel];
    msg.font = [UIFont systemFontOfSize:14];
    msg.textColor = [[UIColor whiteColor] colorWithAlphaComponent:0.78];
    msg.textAlignment = NSTextAlignmentCenter;
    msg.numberOfLines = 2;
    [card addSubview:msg];

    UIView *lineH = [[UIView alloc] initWithFrame:CGRectMake(0, cardH - 49.f, cardW, 1.f / UIScreen.mainScreen.scale)];
    lineH.backgroundColor = [[UIColor whiteColor] colorWithAlphaComponent:0.12];
    [card addSubview:lineH];

    UIView *lineV = [[UIView alloc] initWithFrame:CGRectMake(cardW * 0.5f, cardH - 49.f, 1.f / UIScreen.mainScreen.scale, 49.f)];
    lineV.backgroundColor = [[UIColor whiteColor] colorWithAlphaComponent:0.12];
    [card addSubview:lineV];

    UIButton *cancel = [UIButton buttonWithType:UIButtonTypeCustom];
    cancel.frame = CGRectMake(0, cardH - 49.f, cardW * 0.5f, 49.f);
    cancel.tag = kFuBeautyInteractiveTag;
    [cancel setTitle:@"取消" forState:UIControlStateNormal];
    [cancel setTitleColor:[[UIColor whiteColor] colorWithAlphaComponent:0.85] forState:UIControlStateNormal];
    cancel.titleLabel.font = [UIFont systemFontOfSize:16];
    [cancel addTarget:self action:@selector(onRecoverConfirmCancel) forControlEvents:UIControlEventTouchUpInside];
    [card addSubview:cancel];

    UIButton *ok = [UIButton buttonWithType:UIButtonTypeCustom];
    ok.frame = CGRectMake(cardW * 0.5f, cardH - 49.f, cardW * 0.5f, 49.f);
    ok.tag = kFuBeautyInteractiveTag;
    [ok setTitle:@"恢复" forState:UIControlStateNormal];
    [ok setTitleColor:[self brandColor] forState:UIControlStateNormal];
    ok.titleLabel.font = [UIFont systemFontOfSize:16 weight:UIFontWeightSemibold];
    [ok addTarget:self action:@selector(onRecoverConfirmOk) forControlEvents:UIControlEventTouchUpInside];
    [card addSubview:ok];

    _recoverConfirmView = wrap;
    [self addSubview:wrap];
    [self bringSubviewToFront:wrap];
}

- (void)onRecoverConfirmCancel {
    [_recoverConfirmView removeFromSuperview];
    _recoverConfirmView = nil;
}

- (void)onRecoverConfirmOk {
    [_recoverConfirmView removeFromSuperview];
    _recoverConfirmView = nil;

    // 纯原生：改 UI → setParam。不经过独立 Alert Window，也不依赖 JS。
    NSString *tab = _activeTab.length ? _activeTab : @"skin";
    NSDictionary<NSString *, NSNumber *> *sdkParams = [self recoverTabDefaults:tab] ?: @{};
    if ([self.delegate respondsToSelector:@selector(beautyPanelApplyRecoverDefaults:tab:)]) {
        [self.delegate beautyPanelApplyRecoverDefaults:sdkParams tab:tab];
    }
    if ([self.delegate respondsToSelector:@selector(beautyPanelDidRecoverDefaults:)]) {
        [self.delegate beautyPanelDidRecoverDefaults:tab];
    }
}

- (NSDictionary<NSString *, NSNumber *> *)recoverTabDefaults:(NSString *)tabId {
    NSString *tab = tabId.length ? tabId : (_activeTab.length ? _activeTab : @"skin");
    if ([tab isEqualToString:@"filter"]) {
        return @{};
    }
    NSArray *list = [tab isEqualToString:@"shape"] ? _shapeEffects : _skinEffects;
    if (![list isKindOfClass:[NSArray class]] || list.count == 0) {
        return @{};
    }
    _activeTab = tab;
    _expanded = YES;
    for (UIButton *b in _tabButtons) {
        b.selected = [b.accessibilityIdentifier isEqualToString:tab];
    }

    // 只改本地 _values + UI，禁止循环 fire slider 事件（会拖死/冲掉刷新）
    NSMutableDictionary<NSString *, NSNumber *> *sdkParams = [NSMutableDictionary dictionary];
    for (NSDictionary *item in list) {
        if (![item isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        id keyObj = item[@"key"];
        NSString *key = [keyObj isKindOfClass:[NSString class]]
            ? keyObj
            : ([keyObj respondsToSelector:@selector(description)] ? [keyObj description] : nil);
        if (key.length == 0) {
            continue;
        }
        if ([self isEffectItemDisabled:item]) {
            continue;
        }
        double defSlider = [self defaultSliderForItem:item];
        _values[key] = @(defSlider);
        sdkParams[key] = @([self sdkValueFromSlider:defSlider meta:item]);
    }

    if ([tab isEqualToString:@"skin"]) {
        _whiteningMode = @"global";
        [self refreshWhiteningSeg];
    }

    // 保持当前选中项，让滑杆数值变化肉眼可见（勿强跳到第一项）
    BOOL keepSel = NO;
    if (_selectedEffectKey.length) {
        for (NSDictionary *item in list) {
            if ([[item[@"key"] description] isEqualToString:_selectedEffectKey] &&
                ![self isEffectItemDisabled:item]) {
                keepSel = YES;
                break;
            }
        }
    }
    if (!keepSel) {
        for (NSDictionary *item in list) {
            if (![item isKindOfClass:[NSDictionary class]]) {
                continue;
            }
            if ([self isEffectItemDisabled:item]) {
                continue;
            }
            NSString *k = [item[@"key"] isKindOfClass:[NSString class]]
                ? item[@"key"]
                : [[item[@"key"] description] copy];
            if (k.length) {
                _selectedEffectKey = k;
                break;
            }
        }
    }
    if ([tab isEqualToString:@"skin"]) {
        _lastSkinEffectKey = _selectedEffectKey;
    } else {
        _lastShapeEffectKey = _selectedEffectKey;
    }

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [self reloadIconStrip];
    [self syncSliderToSelection];
    [self refreshRecoverEnabled];
    [self setNeedsLayout];
    [self layoutIfNeeded];
    [CATransaction commit];
    [self notifyHeight];

    return [sdkParams copy];
}

- (void)onWhiteningGlobal {
    _whiteningMode = @"global";
    [self refreshWhiteningSeg];
    if ([self.delegate respondsToSelector:@selector(beautyPanelWhiteningMode:)]) {
        [self.delegate beautyPanelWhiteningMode:@"global"];
    }
}

- (void)onWhiteningSkin {
    if (![self canUseSkinWhitening]) {
        [self showPerfLimitToastForItem:@{ @"name": @"皮肤美白", @"performanceLevel": @(4) }];
        return;
    }
    _whiteningMode = @"skin";
    [self refreshWhiteningSeg];
    if ([self.delegate respondsToSelector:@selector(beautyPanelWhiteningMode:)]) {
        [self.delegate beautyPanelWhiteningMode:@"skin"];
    }
}

- (void)refreshWhiteningSeg {
    BOOL global = [_whiteningMode isEqualToString:@"global"];
    UIColor *onText = [UIColor colorWithRed:44/255.0 green:46/255.0 blue:48/255.0 alpha:1];
    UIColor *offText = [[UIColor whiteColor] colorWithAlphaComponent:0.45];
    [_whiteningGlobalBtn setTitleColor:(global ? onText : offText) forState:UIControlStateNormal];
    [_whiteningSkinBtn setTitleColor:(global ? offText : onText) forState:UIControlStateNormal];
    _whiteningGlobalBtn.backgroundColor = global ? [UIColor whiteColor] : [UIColor clearColor];
    _whiteningSkinBtn.backgroundColor = global ? [UIColor clearColor] : [UIColor whiteColor];
    BOOL skinOk = [self canUseSkinWhitening];
    _whiteningSkinBtn.enabled = skinOk;
    _whiteningSkinBtn.alpha = skinOk ? 1.0 : 0.35;
    CGFloat r = 12;
    if (@available(iOS 11.0, *)) {
        _whiteningGlobalBtn.layer.cornerRadius = global ? r : 0;
        _whiteningGlobalBtn.layer.maskedCorners = kCALayerMinXMinYCorner | kCALayerMinXMaxYCorner;
        _whiteningGlobalBtn.clipsToBounds = YES;
        _whiteningSkinBtn.layer.cornerRadius = global ? 0 : r;
        _whiteningSkinBtn.layer.maskedCorners = kCALayerMaxXMinYCorner | kCALayerMaxXMaxYCorner;
        _whiteningSkinBtn.clipsToBounds = YES;
    }
}

- (void)setCompareButtonPressed:(BOOL)pressed {
    _compareBtn.alpha = pressed ? 0.55 : 1.0;
}

- (void)onCompareDown {
    [self setCompareButtonPressed:YES];
    if ([self.delegate respondsToSelector:@selector(beautyPanelCompareStart)]) {
        [self.delegate beautyPanelCompareStart];
    }
}

- (void)onCompareUp {
    [self setCompareButtonPressed:NO];
    if ([self.delegate respondsToSelector:@selector(beautyPanelCompareEnd)]) {
        [self.delegate beautyPanelCompareEnd];
    }
}

- (void)onSave {
    if ([self.delegate respondsToSelector:@selector(beautyPanelSave)]) {
        [self.delegate beautyPanelSave];
    }
}

- (void)onBack {
    if ([self.delegate respondsToSelector:@selector(beautyPanelBack)]) {
        [self.delegate beautyPanelBack];
    }
}

- (void)scrollViewDidScroll:(UIScrollView *)scrollView {
    // 功能区/滤镜只允许左右滚
    if (scrollView == _iconScroll && fabs(scrollView.contentOffset.y) > 0.01) {
        scrollView.contentOffset = CGPointMake(scrollView.contentOffset.x, 0);
    }
}

- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    UIView *hit = [super hitTest:point withEvent:event];
    if (hit == self) {
        return nil; // 空白穿透
    }
    return hit;
}

@end
