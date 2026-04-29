//
//  OTScreenCapture.m
//  Screen-Sharing
//
//  Copyright (c) 2014 TokBox Inc. All rights reserved.
//

#include <mach/mach.h>
#include <mach/mach_time.h>
#include <ReplayKit/ReplayKit.h>
#import "OTScreenCapture.h"

@implementation OTScreenCapture {
    dispatch_queue_t _queue;
    dispatch_source_t _timer;

    CVPixelBufferRef _pixelBuffer;
    BOOL _capturing;
    BOOL _timerResumed;
    UIView* _view;
    CIContext* _ciContext;
}

@synthesize videoCaptureConsumer;
@synthesize videoContentHint;
@synthesize recorder;

#pragma mark - Class Lifecycle.

- (instancetype)initWithView:(UIView *)view
{
    self = [super init];
    if (self) {
        _view = view;
        _queue = dispatch_queue_create("SCREEN_CAPTURE", NULL);
        _ciContext = [CIContext contextWithOptions:nil];
    }
    return self;
}

- (void)createTimer {
    if (_timer) {
        return;
    }

    __unsafe_unretained OTScreenCapture* _self = self;
    _timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, DISPATCH_TIMER_STRICT, _queue);
    _timerResumed = NO;

    dispatch_source_set_timer(_timer, dispatch_walltime(NULL, 0),
                              200ull * NSEC_PER_MSEC, 50ull * NSEC_PER_MSEC);

    dispatch_source_set_event_handler(_timer, ^{
        @autoreleasepool {
            if ([_self latestImage] != nil) {
                CGImageRef paddedScreen = [self resizeAndPadImage:self.latestImage];
                [_self consumeFrame:paddedScreen];
            }
        }
    });
}

- (void)dealloc
{
    [self stopCapture];
    CVPixelBufferRelease(_pixelBuffer);
}

#pragma mark - Private Methods

/**
 * Make sure receiving video frame container is setup for this image.
 */
- (void)checkImageSize:(CGImageRef)image {
    CGFloat width = CGImageGetWidth(image);
    CGFloat height = CGImageGetHeight(image);

    if (_pixelBuffer != NULL &&
        CVPixelBufferGetHeight(_pixelBuffer) == height &&
        CVPixelBufferGetWidth(_pixelBuffer) == width) {
        // don't rock the boat. if nothing has changed, don't update anything.
        return;
    }

    CGSize frameSize = CGSizeMake(width, height);
    NSDictionary *options = [NSDictionary dictionaryWithObjectsAndKeys:
                             @YES,
                             kCVPixelBufferCGImageCompatibilityKey,
                             @YES,
                             kCVPixelBufferCGBitmapContextCompatibilityKey,
                             nil];

    if (NULL != _pixelBuffer) {
        CVPixelBufferRelease(_pixelBuffer);
    }

    CVReturn status = CVPixelBufferCreate(kCFAllocatorDefault,
                                          frameSize.width,
                                          frameSize.height,
                                          kCVPixelFormatType_32ARGB,
                                          (__bridge CFDictionaryRef)(options),
                                          &_pixelBuffer);

    NSParameterAssert(status == kCVReturnSuccess && _pixelBuffer != NULL);

}

#pragma mark - Capture lifecycle

/**
 * Allocate capture resources; in this case we're just setting up a timer and
 * block to execute periodically to send video frames.
 */
- (void)initCapture {
    [self createTimer];
}

- (void)releaseCapture {
    if (_timer && !_timerResumed) {
        dispatch_resume(_timer);
    }
    if (_timer) {
        dispatch_source_cancel(_timer);
    }
    _timer = nil;
    _timerResumed = NO;
}

- (int32_t)startCapture
{
    _capturing = YES;

    [self createTimer];
    if (_timer && !_timerResumed) {
        dispatch_resume(_timer);
        _timerResumed = YES;
    }
    [self startRecording];
    return 0;
}

- (int32_t)stopCapture
{
    _capturing = NO;

    if (_timer) {
        dispatch_source_cancel(_timer);
        _timer = nil;
        _timerResumed = NO;
    }
    [self stopRecording];

    return 0;
}

- (BOOL)isCaptureStarted
{
    return _capturing;
}

#pragma mark - Screen capture implementation

- (CVPixelBufferRef)pixelBufferFromCGImage:(CGImageRef)image
{
    CGFloat width = CGImageGetWidth(image);
    CGFloat height = CGImageGetHeight(image);
    CGSize frameSize = CGSizeMake(width, height);
    CVPixelBufferLockBaseAddress(_pixelBuffer, 0);
    void *pxdata = CVPixelBufferGetBaseAddress(_pixelBuffer);

    CGColorSpaceRef rgbColorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context =
    CGBitmapContextCreate(pxdata,
                          frameSize.width,
                          frameSize.height,
                          8,
                          CVPixelBufferGetBytesPerRow(_pixelBuffer),
                          rgbColorSpace,
                          kCGImageAlphaPremultipliedFirst |
                          kCGBitmapByteOrder32Little);


    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    CGColorSpaceRelease(rgbColorSpace);
    CGContextRelease(context);

    CVPixelBufferUnlockBaseAddress(_pixelBuffer, 0);

    return _pixelBuffer;
}

- (int32_t)captureSettings:(OTVideoFormat*)videoFormat
{
    videoFormat.pixelFormat = OTPixelFormatARGB;
    return 0;
}

+ (void)dimensionsForInputSize:(CGSize)input
                 containerSize:(CGSize*)destContainerSize
                      drawRect:(CGRect*)destDrawRect
{
    CGFloat sourceWidth = input.width;
    CGFloat sourceHeight = input.height;
    double sourceAspectRatio = sourceWidth / sourceHeight;

    CGFloat destContainerWidth = sourceWidth;
    CGFloat destContainerHeight = sourceHeight;
    CGFloat destImageWidth = sourceWidth;
    CGFloat destImageHeight = sourceHeight;

    // if image is wider than tall and width breaks edge size limit
    if (MAX_EDGE_SIZE_LIMIT < sourceWidth && sourceAspectRatio >= 1.0) {
        destContainerWidth = MAX_EDGE_SIZE_LIMIT;
        destContainerHeight = destContainerWidth / sourceAspectRatio;
        if (0 != fmod(destContainerHeight, EDGE_DIMENSION_COMMON_FACTOR)) {
            // add padding to make height % 16 == 0
            destContainerHeight +=
            (EDGE_DIMENSION_COMMON_FACTOR - fmod(destContainerHeight,
                                                 EDGE_DIMENSION_COMMON_FACTOR));
        }
        destImageWidth = destContainerWidth;
        destImageHeight = destContainerWidth / sourceAspectRatio;
    }

    // if image is taller than wide and height breaks edge size limit
    if (MAX_EDGE_SIZE_LIMIT < destContainerHeight && sourceAspectRatio <= 1.0) {
        destContainerHeight = MAX_EDGE_SIZE_LIMIT;
        destContainerWidth = destContainerHeight * sourceAspectRatio;
        if (0 != fmod(destContainerWidth, EDGE_DIMENSION_COMMON_FACTOR)) {
            // add padding to make width % 16 == 0
            destContainerWidth +=
            (EDGE_DIMENSION_COMMON_FACTOR - fmod(destContainerWidth,
                                                 EDGE_DIMENSION_COMMON_FACTOR));
        }
        destImageHeight = destContainerHeight;
        destImageWidth = destContainerHeight * sourceAspectRatio;
    }

    // ensure the dimensions of the resulting container are safe
    if (fmod(destContainerWidth, EDGE_DIMENSION_COMMON_FACTOR) != 0) {
        double remainder = fmod(destContainerWidth,
                                EDGE_DIMENSION_COMMON_FACTOR);
        // increase the edge size only if doing so does not break the edge limit
        if (destContainerWidth + (EDGE_DIMENSION_COMMON_FACTOR - remainder) >
            MAX_EDGE_SIZE_LIMIT)
        {
            destContainerWidth -= remainder;
        } else {
            destContainerWidth += EDGE_DIMENSION_COMMON_FACTOR - remainder;
        }
    }
    // ensure the dimensions of the resulting container are safe
    if (fmod(destContainerHeight, EDGE_DIMENSION_COMMON_FACTOR) != 0) {
        double remainder = fmod(destContainerHeight,
                                EDGE_DIMENSION_COMMON_FACTOR);
        // increase the edge size only if doing so does not break the edge limit
        if (destContainerHeight + (EDGE_DIMENSION_COMMON_FACTOR - remainder) >
            MAX_EDGE_SIZE_LIMIT)
        {
            destContainerHeight -= remainder;
        } else {
            destContainerHeight += EDGE_DIMENSION_COMMON_FACTOR - remainder;
        }
    }

    destContainerSize->width = destContainerWidth;
    destContainerSize->height = destContainerHeight;

    // scale and recenter source image to fit in destination container
    if (sourceAspectRatio > 1.0) {
        destDrawRect->origin.x = 0;
        destDrawRect->origin.y =
        (destContainerHeight - destImageHeight) / 2;
        destDrawRect->size.width = destContainerWidth;
        destDrawRect->size.height =
        destContainerWidth / sourceAspectRatio;
    } else {
        destDrawRect->origin.x =
        (destContainerWidth - destImageWidth) / 2;
        destDrawRect->origin.y = 0;
        destDrawRect->size.height = destContainerHeight;
        destDrawRect->size.width =
        destContainerHeight * sourceAspectRatio;
    }

}

- (CGImageRef)resizeAndPadImage:(UIImage*)sourceUIImage {
    CGImageRef sourceCGImage = [sourceUIImage CGImage];
    CGFloat sourceWidth = CGImageGetWidth(sourceCGImage);
    CGFloat sourceHeight = CGImageGetHeight(sourceCGImage);
    CGSize sourceSize = CGSizeMake(sourceWidth, sourceHeight);
    CGSize destContainerSize = CGSizeZero;
    CGRect destRectForSourceImage = CGRectZero;

    [OTScreenCapture dimensionsForInputSize:sourceSize
                              containerSize:&destContainerSize
                                   drawRect:&destRectForSourceImage];

    UIGraphicsBeginImageContextWithOptions(destContainerSize, YES, 0.0);
    CGContextRef context = UIGraphicsGetCurrentContext();

    // flip source image to match destination coordinate system
    CGContextScaleCTM(context, 1.0, -1.0);
    CGContextTranslateCTM(context, 0, -destRectForSourceImage.size.height);
    CGContextDrawImage(context, destRectForSourceImage, sourceCGImage);

    // Clean up and get the new image.
    UIImage *newImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return [newImage CGImage];
}

- (void) consumeFrame:(CGImageRef)frame {

    [self checkImageSize:frame];

    if (!(_capturing && self.videoCaptureConsumer)) {
        return;
    }

    CMTime time = [self getTimeStamp];
    CVImageBufferRef ref = [self pixelBufferFromCGImage:frame];

    [self.videoCaptureConsumer consumeImageBuffer:ref
                                      orientation:OTVideoOrientationUp
                                        timestamp:time
                                         metadata:nil];
}

- (CMTime)getTimeStamp {
    static mach_timebase_info_data_t time_info;
    uint64_t time_stamp = 0;
    if (time_info.denom == 0) {
        (void) mach_timebase_info(&time_info);
    }
    time_stamp = mach_absolute_time();
    time_stamp *= time_info.numer;
    time_stamp /= time_info.denom;
    CMTime time = CMTimeMake(time_stamp, 1000);
    return time;
}


- (void)startRecording {
    // Sets up ReplayKit itself.
    self.recorder = [RPScreenRecorder sharedRecorder];

    // Starts ReplayKit's recording session.
    // Sample buffers will be sent to `captureSampleBuffer` method.
    //[self.recorder startCaptureWithHandler:bufferHandler completionHandler:errorHandler];

    if (@available(iOS 11.0, *)) {
        [self.recorder startCaptureWithHandler:^(CMSampleBufferRef sampleBuffer, RPSampleBufferType bufferType, NSError* error) {
            double timeSinceLastCapture = [NSDate.date timeIntervalSince1970] - [self lastCaptureMillis];
            if (timeSinceLastCapture > 0.2) {
            if (bufferType == RPSampleBufferTypeVideo) {
                CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
                CIImage *ciImage = [CIImage imageWithCVPixelBuffer:imageBuffer];
                CGImageRef videoImage = [self->_ciContext
                                         createCGImage:ciImage
                                         fromRect:CGRectMake(0, 0,
                                                             CVPixelBufferGetWidth(imageBuffer),
                                                             CVPixelBufferGetHeight(imageBuffer))];

                UIImage *image = [[UIImage alloc] initWithCGImage:videoImage];
                CGImageRelease(videoImage);
                self.latestImage = image;
                self.lastCaptureMillis = [NSDate date].timeIntervalSince1970;
            }
        }

        } completionHandler:^(NSError* error) {
            NSLog(@"startCapture: %@", error);
        }];
    }
}

- (void)stopRecording {


    if (@available(iOS 11.0, *)) {
        [self.recorder stopCaptureWithHandler:^(NSError* error){
            NSLog(@"stoppingCapture: %@", error);
        }];
    }
}

@end
