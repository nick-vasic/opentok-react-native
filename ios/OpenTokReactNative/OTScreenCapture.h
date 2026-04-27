//
//  OTScreenCapture.h
//  Screen-Sharing
//
//  Copyright (c) 2014 TokBox Inc. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <OpenTok/OpenTok.h>

@protocol OTVideoCapture;

/**
 * Sends ReplayKit screen-capture frames to a Publisher.
 */
@interface OTScreenCapture : NSObject <OTVideoCapture>

@property(readonly) UIView* view;

/**
 * Initializes a video capturer for a screen-sharing publisher.
 */
- (instancetype)initWithView:(UIView*)view;


@end
