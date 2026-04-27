//
//  OTScreenCapture.h
//  Screen-Sharing
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
