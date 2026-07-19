#import "NativeAudioBridge.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

#include <autoremix/audio_core_c.h>

namespace {

NSString *const kNativeAudioErrorDomain = @"com.alexey.autoremix.audio-core";
constexpr uint32_t kMaximumRenderFrames = 8192;
constexpr uint32_t kOutputChannels = 2;

static_assert(AR_AUDIO_CORE_ABI_VERSION == 2U,
              "NativeAudioBridge must be updated for audio-core ABI changes");

bool validFallbackStage(ar_fallback_stage_t stage) noexcept {
    switch (stage) {
        case AR_FALLBACK_CACHED_APPROVED_BRIDGE:
        case AR_FALLBACK_APPROVED_PROVIDER_CANDIDATE:
        case AR_FALLBACK_DETERMINISTIC_MULTI_STEM:
        case AR_FALLBACK_LEGACY_INTELLIGENT:
        case AR_FALLBACK_PHRASE_ALIGNED_CROSSFADE:
        case AR_FALLBACK_EMERGENCY_BASIC_CROSSFADE:
            return true;
        default:
            return false;
    }
}

struct NativeAudioState final {
    ar_audio_core_t *core{nullptr};
    ar_audio_ring_t *ring{nullptr};
    uint32_t channels{kOutputChannels};
    uint32_t sampleRate{48000};
    std::vector<float> scratch;
    std::atomic<uint64_t> renderedFrame{0};
    std::atomic<bool> paused{true};

    ~NativeAudioState() {
        if (ring != nullptr) {
            ar_audio_ring_destroy(ring);
        }
        if (core != nullptr) {
            ar_audio_core_destroy(core);
        }
    }
};

NSError *makeError(ar_status_t status, NSString *operation) {
    NSString *description = [NSString stringWithFormat:@"%@ failed with audio-core status %u",
                                                       operation,
                                                       status];
    return [NSError errorWithDomain:kNativeAudioErrorDomain
                               code:static_cast<NSInteger>(status)
                           userInfo:@{NSLocalizedDescriptionKey: description}];
}

void zeroBuffers(AudioBufferList *bufferList, AVAudioFrameCount frameCount) noexcept {
    if (bufferList == nullptr) {
        return;
    }
    for (UInt32 index = 0; index < bufferList->mNumberBuffers; ++index) {
        AudioBuffer &buffer = bufferList->mBuffers[index];
        if (buffer.mData == nullptr) {
            continue;
        }
        const auto requested = static_cast<size_t>(frameCount) *
            std::max<UInt32>(1, buffer.mNumberChannels) * sizeof(float);
        std::memset(buffer.mData, 0, std::min(requested, static_cast<size_t>(buffer.mDataByteSize)));
    }
}

}  // namespace

@implementation NativeAudioBridge

- (nullable instancetype)initWithSampleRate:(double)sampleRate
                                    channels:(uint32_t)channels
                          ringCapacityFrames:(uint32_t)ringCapacityFrames {
    self = [super init];
    if (self == nil || sampleRate < 8000 || sampleRate > 384000 ||
        channels != kOutputChannels ||
        ringCapacityFrames == 0) {
        return nil;
    }

    auto state = std::make_unique<NativeAudioState>();
    state->channels = channels;
    state->sampleRate = static_cast<uint32_t>(sampleRate);
    state->scratch.resize(static_cast<size_t>(kMaximumRenderFrames) * channels, 0.0F);
    state->core = ar_audio_core_create();
    const auto requestedSamples = static_cast<uint64_t>(ringCapacityFrames) * channels;
    if (requestedSamples > std::numeric_limits<uint32_t>::max()) {
        return nil;
    }
    state->ring = ar_audio_ring_create(static_cast<uint32_t>(requestedSamples));
    if (state->core == nullptr || state->ring == nullptr ||
        ar_audio_core_abi_version() != AR_AUDIO_CORE_ABI_VERSION) {
        return nil;
    }

    _state = state.release();
    return self;
}

- (void)dealloc {
    delete static_cast<NativeAudioState *>(_state);
    _state = nullptr;
}

- (BOOL)isReady {
    const auto state = static_cast<NativeAudioState *>(_state);
    return state != nullptr && state->core != nullptr && state->ring != nullptr;
}

- (uint64_t)renderedFrame {
    const auto state = static_cast<NativeAudioState *>(_state);
    return state == nullptr ? 0 : state->renderedFrame.load(std::memory_order_acquire);
}

- (uint32_t)availableFrames {
    const auto state = static_cast<NativeAudioState *>(_state);
    if (state == nullptr || state->ring == nullptr) {
        return 0;
    }
    return ar_audio_ring_available_read(state->ring) / state->channels;
}

- (OSStatus)renderIntoBufferList:(AudioBufferList *)bufferList
                      frameCount:(AVAudioFrameCount)frameCount {
    const auto state = static_cast<NativeAudioState *>(_state);
    zeroBuffers(bufferList, frameCount);
    if (state == nullptr || bufferList == nullptr || state->paused.load(std::memory_order_acquire) ||
        frameCount == 0 || frameCount > kMaximumRenderFrames) {
        return noErr;
    }

    const auto requestedSamples = static_cast<uint32_t>(frameCount) * state->channels;
    const auto samplesRead = ar_audio_ring_read(state->ring, state->scratch.data(), requestedSamples);
    const auto framesRead = samplesRead / state->channels;

    if (bufferList->mNumberBuffers == 1 &&
        bufferList->mBuffers[0].mNumberChannels == state->channels &&
        bufferList->mBuffers[0].mData != nullptr) {
        const auto bytes = static_cast<size_t>(framesRead) * state->channels * sizeof(float);
        std::memcpy(bufferList->mBuffers[0].mData,
                    state->scratch.data(),
                    std::min(bytes, static_cast<size_t>(bufferList->mBuffers[0].mDataByteSize)));
    } else {
        const auto outputChannels = std::min<UInt32>(bufferList->mNumberBuffers, state->channels);
        for (UInt32 channel = 0; channel < outputChannels; ++channel) {
            AudioBuffer &buffer = bufferList->mBuffers[channel];
            if (buffer.mData == nullptr) {
                continue;
            }
            auto *output = static_cast<float *>(buffer.mData);
            const auto writableFrames = std::min<uint32_t>(
                framesRead,
                buffer.mDataByteSize / static_cast<uint32_t>(sizeof(float)));
            for (uint32_t frame = 0; frame < writableFrames; ++frame) {
                output[frame] = state->scratch[static_cast<size_t>(frame) * state->channels + channel];
            }
        }
    }

    state->renderedFrame.fetch_add(framesRead, std::memory_order_release);
    return noErr;
}

- (uint32_t)enqueueInterleavedSamples:(const float *)samples
                          sampleCount:(uint32_t)sampleCount {
    const auto state = static_cast<NativeAudioState *>(_state);
    if (state == nullptr || samples == nullptr || sampleCount == 0) {
        return 0;
    }
    return ar_audio_ring_write(state->ring, samples, sampleCount);
}

- (BOOL)prepareBridgeFromSourceA:(NSData *)sourceA
                         sourceB:(NSData *)sourceB
                      frameCount:(uint32_t)frameCount
                            bpmA:(double)bpmA
                            bpmB:(double)bpmB
                           error:(NSError **)error {
    const auto state = static_cast<NativeAudioState *>(_state);
    const auto bytesPerFrame =
        static_cast<size_t>(state == nullptr ? kOutputChannels : state->channels) * sizeof(float);
    if (state == nullptr || frameCount == 0 || sourceA.length < bytesPerFrame ||
        sourceB.length < bytesPerFrame) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_INVALID_ARGUMENT, @"prepare bridge");
        }
        return NO;
    }

    const auto sourceAFrames = sourceA.length / bytesPerFrame;
    const auto sourceBFrames = sourceB.length / bytesPerFrame;
    const auto *sourceASamples = static_cast<const float *>(sourceA.bytes);
    const auto *sourceBSamples = static_cast<const float *>(sourceB.bytes);
    ar_stem_input_t stem{};
    stem.struct_size = sizeof(stem);
    stem.stem_id = 1;
    stem.role = AR_STEM_OTHER;
    stem.source_a = {sourceASamples,
                     static_cast<uint64_t>(sourceAFrames),
                     state->channels,
                     state->sampleRate};
    stem.source_b = {sourceBSamples,
                     static_cast<uint64_t>(sourceBFrames),
                     state->channels,
                     state->sampleRate};
    stem.confidence = 0.5F;
    stem.continuity = 0.8F;
    stem.transient_stability = 0.5F;
    stem.harmonic_stability = 0.5F;
    stem.foreground = 0.2F;
    stem.generated = {nullptr, 0, 0, 0};

    ar_bridge_request_t request{};
    request.struct_size = sizeof(request);
    request.sample_rate = state->sampleRate;
    request.bpm_a = bpmA;
    request.bpm_b = bpmB;
    request.duration_frames = frameCount;
    request.stems = &stem;
    request.stem_count = 1;
    request.capability.render_realtime_factor = 1.0;
    request.capability.memory_budget_bytes = 64ULL * 1024ULL * 1024ULL;
    request.capability.logical_cores = 1;
    request.capability.thermal_headroom = 0;
    request.capability.battery_fraction = 0.5F;
    request.capability.low_power_mode = 1;
    request.has_previous_output = 1;
    request.has_next_output = 1;
    const auto previousFrame = (sourceAFrames - 1) * state->channels;
    for (uint32_t channel = 0; channel < kOutputChannels; ++channel) {
        request.previous_output[channel] = sourceASamples[previousFrame + channel];
        request.next_output[channel] = sourceBSamples[channel];
    }

    std::vector<float> rendered(static_cast<size_t>(frameCount) * state->channels, 0.0F);
    ar_bridge_result_t result{};
    result.struct_size = sizeof(result);
    result.interleaved = rendered.data();
    result.capacity_samples = rendered.size();
    const auto status = ar_audio_core_render_interleaved(state->core, &request, &result);
    if (status != AR_STATUS_OK) {
        if (error != nullptr) {
            *error = makeError(status, @"prepare bridge");
        }
        return NO;
    }
    if (result.frames_written == 0) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_NO_AUDIO, @"prepare bridge audio");
        }
        return NO;
    }
    if (result.channels != kOutputChannels || !validFallbackStage(result.fallback_stage)) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_INVALID_STATE, @"validate prepared bridge");
        }
        return NO;
    }

    const auto sampleCount = result.frames_written * result.channels;
    if (sampleCount > rendered.size() || sampleCount > std::numeric_limits<uint32_t>::max()) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_BUFFER_TOO_SMALL, @"validate prepared bridge size");
        }
        return NO;
    }
    const auto samples = static_cast<uint32_t>(sampleCount);
    const auto written = ar_audio_ring_write(state->ring, rendered.data(), samples);
    if (written != samples) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_BUFFER_TOO_SMALL, @"queue prepared bridge");
        }
        return NO;
    }
    return YES;
}

- (BOOL)setPaused:(BOOL)paused error:(NSError **)error {
    const auto state = static_cast<NativeAudioState *>(_state);
    if (state == nullptr) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_INVALID_STATE, @"set pause state");
        }
        return NO;
    }
    const auto status = ar_audio_core_set_paused(state->core, paused ? 1 : 0);
    if (status != AR_STATUS_OK) {
        if (error != nullptr) {
            *error = makeError(status, @"set pause state");
        }
        return NO;
    }
    state->paused.store(paused, std::memory_order_release);
    return YES;
}

- (BOOL)seekToFrame:(uint64_t)targetFrame error:(NSError **)error {
    const auto state = static_cast<NativeAudioState *>(_state);
    if (state == nullptr) {
        if (error != nullptr) {
            *error = makeError(AR_STATUS_INVALID_STATE, @"seek");
        }
        return NO;
    }
    uint64_t generation = 0;
    const auto status = ar_audio_core_seek(state->core, targetFrame, &generation);
    if (status != AR_STATUS_OK) {
        if (error != nullptr) {
            *error = makeError(status, @"seek");
        }
        return NO;
    }

    while (ar_audio_ring_read(state->ring,
                              state->scratch.data(),
                              static_cast<uint32_t>(state->scratch.size())) > 0) {
    }
    const auto completionStatus = ar_engine_complete_seek(state->core, generation);
    if (completionStatus != AR_STATUS_OK) {
        if (error != nullptr) {
            *error = makeError(completionStatus, @"complete seek");
        }
        return NO;
    }
    state->renderedFrame.store(targetFrame, std::memory_order_release);
    return YES;
}

@end
