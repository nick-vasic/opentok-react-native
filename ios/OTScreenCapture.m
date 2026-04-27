//
//  OTScreenCapture.m
//  Screen-Sharing
//

#import <ReplayKit/ReplayKit.h>
#import "OTScreenCapture.h"

@implementation OTScreenCapture {
    dispatch_queue_t _queue;
    BOOL _capturing;
    UIView* _view;
    RPScreenRecorder *_recorder;
}

@synthesize videoCaptureConsumer;
@synthesize videoContentHint;

#pragma mark - Class Lifecycle.

- (instancetype)initWithView:(UIView *)view
{
    self = [super init];
    if (self) {
        _view = view;
        _queue = dispatch_queue_create("SCREEN_CAPTURE", NULL);
        _recorder = [RPScreenRecorder sharedRecorder];
    }
    return self;
}

- (void)dealloc
{
    [self stopCapture];
}

#pragma mark - Private Methods

- (void)consumeSampleBuffer:(CMSampleBufferRef)sampleBuffer
                 bufferType:(RPSampleBufferType)bufferType
                      error:(NSError *)error
{
    if (!(_capturing && self.videoCaptureConsumer)) {
        return;
    }

    if (error != nil) {
        NSLog(@"OTScreenCapture ReplayKit error: %@", error);
        return;
    }

    if (bufferType != RPSampleBufferTypeVideo) {
        return;
    }

    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (imageBuffer == NULL) {
        return;
    }

    CMTime timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
    if (!CMTIME_IS_VALID(timestamp)) {
        timestamp = CMClockGetTime(CMClockGetHostTimeClock());
    }
    CVBufferRetain(imageBuffer);

    __weak typeof(self) weakSelf = self;
    dispatch_async(_queue, ^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf != nil && strongSelf->_capturing && strongSelf.videoCaptureConsumer) {
            [strongSelf.videoCaptureConsumer consumeImageBuffer:imageBuffer
                                                   orientation:OTVideoOrientationUp
                                                     timestamp:timestamp
                                                      metadata:nil];
        }
        CVBufferRelease(imageBuffer);
    });
}

#pragma mark - Capture lifecycle

- (void)initCapture {
    
}

- (void)releaseCapture {
    [self stopCapture];
}

- (int32_t)startCapture
{
    if (_capturing) {
        return 0;
    }

    if (@available(iOS 11.0, *)) {
        if (!_recorder.isAvailable) {
            NSLog(@"OTScreenCapture ReplayKit recorder is not available");
            return -1;
        }

        _capturing = YES;
        __weak typeof(self) weakSelf = self;
        [_recorder startCaptureWithHandler:^(CMSampleBufferRef sampleBuffer,
                                             RPSampleBufferType bufferType,
                                             NSError *error) {
            [weakSelf consumeSampleBuffer:sampleBuffer bufferType:bufferType error:error];
        } completionHandler:^(NSError *error) {
            if (error != nil) {
                NSLog(@"OTScreenCapture failed to start ReplayKit capture: %@", error);
                __strong typeof(weakSelf) strongSelf = weakSelf;
                if (strongSelf != nil) {
                    strongSelf->_capturing = NO;
                }
            }
        }];
        return 0;
    }

    NSLog(@"OTScreenCapture requires iOS 11.0 or newer for ReplayKit capture");
    return -1;
}

- (int32_t)stopCapture
{
    if (!_capturing) {
        return 0;
    }

    _capturing = NO;
    if (@available(iOS 11.0, *)) {
        if (_recorder.isRecording) {
            [_recorder stopCaptureWithHandler:^(NSError *error) {
                if (error != nil) {
                    NSLog(@"OTScreenCapture failed to stop ReplayKit capture: %@", error);
                }
            }];
        }
    }
    return 0;
}

- (BOOL)isCaptureStarted
{
    return _capturing;
}

- (int32_t)captureSettings:(OTVideoFormat*)videoFormat
{
    videoFormat.pixelFormat = OTPixelFormatARGB;
    return 0;
}

@end
