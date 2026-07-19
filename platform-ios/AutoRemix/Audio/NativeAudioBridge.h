#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Thin ObjC++ adapter. The render callback only reads the shared core's lock-free ring.
@interface NativeAudioBridge : NSObject {
@private
    void *_state;
}

- (nullable instancetype)initWithSampleRate:(double)sampleRate
                                    channels:(uint32_t)channels
                          ringCapacityFrames:(uint32_t)ringCapacityFrames
    NS_SWIFT_NAME(init(sampleRate:channels:ringCapacityFrames:));

@property(nonatomic, readonly, getter=isReady) BOOL ready;
@property(nonatomic, readonly) uint64_t renderedFrame;
@property(nonatomic, readonly) uint32_t availableFrames;

- (OSStatus)renderIntoBufferList:(AudioBufferList *)bufferList
                      frameCount:(AVAudioFrameCount)frameCount
    NS_SWIFT_NAME(render(into:frameCount:));

/// Control-thread API. Returns the number of accepted interleaved samples.
- (uint32_t)enqueueInterleavedSamples:(const float *)samples
                          sampleCount:(uint32_t)sampleCount
    NS_SWIFT_NAME(enqueue(interleavedSamples:sampleCount:));

/// Offline render helper. Inputs are interleaved Float32 stereo PCM.
- (BOOL)prepareBridgeFromSourceA:(NSData *)sourceA
                         sourceB:(NSData *)sourceB
                      frameCount:(uint32_t)frameCount
                            bpmA:(double)bpmA
                            bpmB:(double)bpmB
                           error:(NSError *_Nullable *_Nullable)error
    NS_SWIFT_NAME(prepareBridge(sourceA:sourceB:frameCount:bpmA:bpmB:));

- (BOOL)setPaused:(BOOL)paused error:(NSError *_Nullable *_Nullable)error;
- (BOOL)seekToFrame:(uint64_t)targetFrame error:(NSError *_Nullable *_Nullable)error
    NS_SWIFT_NAME(seek(toFrame:));

@end

NS_ASSUME_NONNULL_END
