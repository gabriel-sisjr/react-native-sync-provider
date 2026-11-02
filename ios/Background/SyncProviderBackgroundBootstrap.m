//
//  SyncProviderBackgroundBootstrap.m
//  SyncProvider
//
//  Registers the BGTask handlers from `+load`, which runs before `main`. This
//  is the only hook that needs no change to the consumer's AppDelegate and
//  still runs before `application:didFinishLaunchingWithOptions:` returns.
//
//  The Swift side is reached through a C symbol rather than the generated
//  header: this pod disables `SWIFT_INSTALL_OBJC_HEADER`.
//

#import <Foundation/Foundation.h>

extern void SyncProviderRegisterBackgroundTasks(void);

@interface SyncProviderBackgroundBootstrap : NSObject
@end

@implementation SyncProviderBackgroundBootstrap

+ (void)load {
  SyncProviderRegisterBackgroundTasks();
}

@end
