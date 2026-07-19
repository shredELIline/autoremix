#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <exception>
#include <memory>
#include <new>
#include <vector>

#if AUTOREMIX_HAS_NATIVE_CORE
#include <autoremix/audio_core_c.h>
#endif

namespace {

constexpr std::uint32_t kMaxRingSamples = 48'000U * 2U * 4U;
static_assert(std::atomic<std::uint32_t>::is_always_lock_free,
              "The Oboe callback requires lock-free 32-bit atomics");
static_assert(std::atomic<std::uint64_t>::is_always_lock_free,
              "The sample clock must stay lock-free in the Oboe callback");

class SpscFloatRing final {
public:
    explicit SpscFloatRing(std::uint32_t capacity)
        : samples_(std::max(2U, std::min(kMaxRingSamples, capacity))) {}

    std::uint32_t write(const float *source, std::uint32_t count) noexcept {
        if (source == nullptr || count == 0U) return 0U;
        const auto write = write_.load(std::memory_order_relaxed);
        const auto read = read_.load(std::memory_order_acquire);
        const auto used = write - read;
        const auto amount = std::min(count, capacity() - std::min(used, capacity()));
        copyIn(source, write % capacity(), amount);
        write_.store(write + amount, std::memory_order_release);
        return amount;
    }

    std::uint32_t read(float *destination, std::uint32_t count) noexcept {
        if (destination == nullptr || count == 0U) return 0U;
        auto read = read_.load(std::memory_order_relaxed);
        const auto write = write_.load(std::memory_order_acquire);
        const auto amount = std::min(count, write - read);
        copyOut(destination, read % capacity(), amount);
        if (!read_.compare_exchange_strong(read, read + amount,
                                           std::memory_order_release,
                                           std::memory_order_relaxed)) {
            return 0U;
        }
        return amount;
    }

    std::uint32_t availableRead() const noexcept {
        return write_.load(std::memory_order_acquire)
                - read_.load(std::memory_order_acquire);
    }

    void clear() noexcept {
        read_.store(write_.load(std::memory_order_acquire), std::memory_order_release);
    }

private:
    std::uint32_t capacity() const noexcept {
        return static_cast<std::uint32_t>(samples_.size());
    }

    void copyIn(const float *source, std::uint32_t start, std::uint32_t count) noexcept {
        const auto first = std::min(count, capacity() - start);
        std::memcpy(samples_.data() + start, source, first * sizeof(float));
        std::memcpy(samples_.data(), source + first, (count - first) * sizeof(float));
    }

    void copyOut(float *destination, std::uint32_t start, std::uint32_t count) const noexcept {
        const auto first = std::min(count, capacity() - start);
        std::memcpy(destination, samples_.data() + start, first * sizeof(float));
        std::memcpy(destination + first, samples_.data(), (count - first) * sizeof(float));
    }

    std::vector<float> samples_;
    alignas(64) std::atomic<std::uint32_t> write_{0U};
    alignas(64) std::atomic<std::uint32_t> read_{0U};
};

class NativeEngine final : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    NativeEngine(std::int32_t sampleRate, std::int32_t channels,
                 std::uint32_t bufferFrames)
        : sampleRate_(sampleRate), channels_(channels),
          ring_(bufferFrames * static_cast<std::uint32_t>(channels)) {
#if AUTOREMIX_HAS_NATIVE_CORE
        core_ = ar_engine_create();
#endif
    }

    ~NativeEngine() override {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
#if AUTOREMIX_HAS_NATIVE_CORE
        ar_engine_destroy(core_);
#endif
    }

    bool open() {
#if !AUTOREMIX_HAS_NATIVE_CORE
        return false;
#else
        if (core_ == nullptr || sampleRate_ < 8'000 || sampleRate_ > 192'000
                || channels_ != 2) return false;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setSampleRate(sampleRate_);
        builder.setChannelCount(channels_);
        builder.setUsage(oboe::Usage::Media);
        builder.setContentType(oboe::ContentType::Music);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setChannelConversionAllowed(true);
        builder.setFormatConversionAllowed(true);
        builder.setDataCallback(this);
        builder.setErrorCallback(this);
        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            stream_.reset();
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
        }
        return result == oboe::Result::OK && stream_ != nullptr;
#endif
    }

    bool start() noexcept {
#if !AUTOREMIX_HAS_NATIVE_CORE
        return false;
#else
        if (!stream_ || disconnected_.load(std::memory_order_acquire)) return false;
        const auto state = ar_engine_get_state(core_);
        const auto coreResult = state == AR_ENGINE_PAUSED
                ? ar_engine_resume(core_) : ar_engine_start(core_);
        resumeAfterSeek_.store(false, std::memory_order_release);
        paused_.store(false, std::memory_order_release);
        return coreResult == AR_STATUS_OK && stream_->requestStart() == oboe::Result::OK;
#endif
    }

    bool pause() noexcept {
#if !AUTOREMIX_HAS_NATIVE_CORE
        return false;
#else
        if (!stream_ || disconnected_.load(std::memory_order_acquire)) return false;
        resumeAfterSeek_.store(false, std::memory_order_release);
        paused_.store(true, std::memory_order_release);
        const auto streamResult = stream_->requestPause();
        return ar_engine_pause(core_) == AR_STATUS_OK && streamResult == oboe::Result::OK;
#endif
    }

    bool seek(std::uint64_t targetFrame) noexcept {
#if !AUTOREMIX_HAS_NATIVE_CORE
        (void)targetFrame;
        return false;
#else
        if (!stream_ || disconnected_.load(std::memory_order_acquire)) return false;
        const bool shouldResume = !paused_.exchange(true, std::memory_order_acq_rel);
        resumeAfterSeek_.store(shouldResume, std::memory_order_release);
        const auto pauseResult = stream_->pause();
        if (pauseResult != oboe::Result::OK && pauseResult != oboe::Result::ErrorInvalidState) {
            resumeAfterSeek_.store(false, std::memory_order_release);
            paused_.store(!shouldResume, std::memory_order_release);
            return false;
        }
        ring_.clear();
        const auto flushResult = stream_->flush();
        if (flushResult != oboe::Result::OK && flushResult != oboe::Result::ErrorInvalidState) {
            return false;
        }
        std::uint64_t generation = 0U;
        if (ar_engine_seek(core_, targetFrame, &generation) != AR_STATUS_OK) return false;
        return ar_engine_complete_seek(core_, generation) == AR_STATUS_OK;
#endif
    }

    bool completeSeek() noexcept {
        if (!stream_ || disconnected_.load(std::memory_order_acquire)) return false;
        if (!resumeAfterSeek_.exchange(false, std::memory_order_acq_rel)) return true;
        paused_.store(false, std::memory_order_release);
        return stream_->start() == oboe::Result::OK;
    }

    std::uint64_t requestNext(std::uint64_t trackId) noexcept {
#if !AUTOREMIX_HAS_NATIVE_CORE
        (void)trackId;
        return 0U;
#else
        std::uint64_t generation = 0U;
        return ar_engine_request_next(core_, trackId, &generation) == AR_STATUS_OK
                ? generation : 0U;
#endif
    }

    std::int32_t enqueue(const float *samples, std::uint32_t count) noexcept {
        if (disconnected_.load(std::memory_order_acquire)) return -1;
        count -= count % static_cast<std::uint32_t>(channels_);
        return static_cast<std::int32_t>(ring_.write(samples, count));
    }

    std::uint64_t playedFrames() const noexcept {
        return playedFrames_.load(std::memory_order_acquire);
    }

    std::uint32_t queuedFrames() const noexcept {
        return ring_.availableRead() / static_cast<std::uint32_t>(channels_);
    }

    std::uint64_t generation() const noexcept {
#if AUTOREMIX_HAS_NATIVE_CORE
        return ar_engine_get_generation(core_);
#else
        return 0U;
#endif
    }

    std::uint32_t underruns() const noexcept {
        return underruns_.load(std::memory_order_acquire);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
                                           std::int32_t numFrames) override {
        if (audioData == nullptr || numFrames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }
        auto *output = static_cast<float *>(audioData);
        const auto requested = static_cast<std::uint32_t>(numFrames)
                * static_cast<std::uint32_t>(channels_);
        if (paused_.load(std::memory_order_acquire)) {
            std::fill_n(output, requested, 0.0F);
            return oboe::DataCallbackResult::Continue;
        }
        const auto read = ring_.read(output, requested);
        if (read < requested) {
            std::fill(output + read, output + requested, 0.0F);
            underruns_.fetch_add(1U, std::memory_order_relaxed);
        }
        playedFrames_.fetch_add(static_cast<std::uint32_t>(numFrames),
                                std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream *, oboe::Result) override {
        disconnected_.store(true, std::memory_order_release);
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        disconnected_.store(true, std::memory_order_release);
    }

private:
    const std::int32_t sampleRate_;
    const std::int32_t channels_;
    SpscFloatRing ring_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> paused_{true};
    std::atomic<bool> resumeAfterSeek_{false};
    std::atomic<bool> disconnected_{false};
    std::atomic<std::uint64_t> playedFrames_{0U};
    std::atomic<std::uint32_t> underruns_{0U};
#if AUTOREMIX_HAS_NATIVE_CORE
    ar_engine_t *core_{nullptr};
#endif
};

NativeEngine *fromHandle(jlong handle) noexcept {
    return reinterpret_cast<NativeEngine *>(static_cast<std::uintptr_t>(handle));
}

bool ringSelfTest() {
    SpscFloatRing ring(8U);
    const float input[] = {1, 2, 3, 4, 5, 6};
    float output[8]{};
    if (ring.write(input, 6U) != 6U || ring.availableRead() != 6U) return false;
    if (ring.read(output, 4U) != 4U || output[0] != 1.0F || output[3] != 4.0F) return false;
    ring.clear();
    return ring.availableRead() == 0U;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeSelfTest(JNIEnv *, jclass) {
#if AUTOREMIX_HAS_NATIVE_CORE
    try {
        return ringSelfTest() && ar_audio_core_abi_version() == AR_AUDIO_CORE_ABI_VERSION
                ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &) {
        return JNI_FALSE;
    }
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeCreate(JNIEnv *, jclass,
                                                          jint sampleRate,
                                                          jint channels,
                                                          jint bufferFrames) {
    if (sampleRate < 8'000 || sampleRate > 192'000 || channels != 2
            || bufferFrames <= 0 || bufferFrames > 192'000) return 0;
    try {
        auto *engine = new (std::nothrow) NativeEngine(sampleRate, channels,
                                                       static_cast<std::uint32_t>(bufferFrames));
        if (engine == nullptr || !engine->open()) {
            delete engine;
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(engine));
    } catch (const std::exception &) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeStart(JNIEnv *, jclass, jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine != nullptr && const_cast<NativeEngine *>(engine)->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativePause(JNIEnv *, jclass, jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine != nullptr && const_cast<NativeEngine *>(engine)->pause() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeSeek(JNIEnv *, jclass, jlong handle,
                                                        jlong targetFrame) {
    auto *engine = fromHandle(handle);
    return engine != nullptr && targetFrame >= 0
            && engine->seek(static_cast<std::uint64_t>(targetFrame)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeCompleteSeek(JNIEnv *, jclass,
                                                                jlong handle) {
    auto *engine = fromHandle(handle);
    return engine != nullptr && engine->completeSeek() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeRequestNext(JNIEnv *, jclass,
                                                               jlong handle,
                                                               jlong trackId) {
    auto *engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(
            engine->requestNext(static_cast<std::uint64_t>(trackId)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeEnqueue(JNIEnv *env, jclass,
                                                           jlong handle,
                                                           jfloatArray pcm,
                                                           jint offset,
                                                           jint sampleCount) {
    auto *engine = fromHandle(handle);
    if (engine == nullptr || pcm == nullptr || offset < 0 || sampleCount < 0
            || offset > env->GetArrayLength(pcm) - sampleCount) return -1;
    auto *samples = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (samples == nullptr) return -1;
    const auto written = engine->enqueue(samples + offset,
            static_cast<std::uint32_t>(sampleCount));
    env->ReleasePrimitiveArrayCritical(pcm, samples, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativePlayedFrames(JNIEnv *, jclass,
                                                                jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->playedFrames());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeQueuedFrames(JNIEnv *, jclass,
                                                                jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jint>(engine->queuedFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeGeneration(JNIEnv *, jclass,
                                                              jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->generation());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alexey_autoremix_NativeAudioEngine_nativeUnderruns(JNIEnv *, jclass,
                                                            jlong handle) {
    const auto *engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jint>(engine->underruns());
}
