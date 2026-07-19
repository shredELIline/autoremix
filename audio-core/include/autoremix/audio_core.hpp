#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace autoremix::audio {

constexpr std::uint32_t kApiVersion = 2;

enum class StemRole : std::uint8_t {
  Vocal = 0,
  Drums = 1,
  Bass = 2,
  Harmony = 3,
  Other = 4,
  BackingVocal = 5,
  Percussion = 6,
  Guitar = 7,
  Keys = 8,
  Synth = 9,
  Strings = 10,
  Lead = 11,
  Atmosphere = 12,
  Effects = 13,
};

enum class SourceKind : std::uint8_t {
  SourceA = 0,
  SourceB = 1,
  Generated = 2,
};

enum class AutomationCurve : std::uint8_t {
  Linear = 0,
  SmoothStep = 1,
  Hold = 2,
};

enum class GenerationMode : std::uint8_t {
  None = 0,
  Supplied = 1,
  Procedural = 2,
  Morph = 3,
};

enum class SourcePlaybackPolicy : std::uint8_t {
  NoRepeat = 0,
  RepeatOnce = 1,
};

enum class QualityTier : std::uint8_t {
  Economy = 0,
  Balanced = 1,
  Studio = 2,
};

enum class FallbackStage : std::uint8_t {
  CachedApprovedBridge = 0,
  ApprovedProviderCandidate = 1,
  DeterministicMultiStem = 2,
  LegacyIntelligent = 3,
  PhraseAlignedCrossfade = 4,
  EmergencyBasicCrossfade = 5,
};

enum class DiagnosticCode : std::uint8_t {
  NonFiniteSample = 0,
  UnexpectedLength = 1,
  PeakExceeded = 2,
  DcOffset = 3,
  DerivativeSpike = 4,
  LoudnessOutOfRange = 5,
  RhythmMismatch = 6,
  HarmonyConflict = 7,
  StemConflict = 8,
  AnchorDropout = 9,
  PreclipExceeded = 10,
  SecondDerivativeSpike = 11,
  BoundaryDiscontinuity = 12,
  AnchorInaudible = 13,
};

struct AutomationPoint final {
  std::uint64_t frame{0};
  float value{1.0F};
};

class AutomationLane final {
public:
  explicit AutomationLane(float constant = 1.0F);
  AutomationLane(std::vector<AutomationPoint> points,
                 float default_value = 1.0F,
                 AutomationCurve curve = AutomationCurve::Linear);

  [[nodiscard]] float value_at(std::uint64_t frame) const noexcept;
  [[nodiscard]] const std::vector<AutomationPoint> &points() const noexcept {
    return points_;
  }
  [[nodiscard]] float default_value() const noexcept { return default_value_; }
  [[nodiscard]] AutomationCurve curve() const noexcept { return curve_; }

private:
  std::vector<AutomationPoint> points_;
  float default_value_{1.0F};
  AutomationCurve curve_{AutomationCurve::Linear};
};

struct TimelineEvent final {
  SourceKind source{SourceKind::SourceA};
  std::uint64_t timeline_start{0};
  std::uint64_t source_start{0};
  std::uint64_t duration{0};
  AutomationLane gain{1.0F};
  AutomationLane pan{0.0F};
  AutomationLane width{1.0F};
  AutomationLane lowpass_hz{20'000.0F};
  AutomationLane eq_low_db{0.0F};
  AutomationLane eq_mid_db{0.0F};
  AutomationLane eq_high_db{0.0F};
  AutomationLane pitch_semitones{0.0F};
  AutomationLane formant_semitones{0.0F};
  AutomationLane tempo_ratio{1.0F};
  AutomationLane transient_preservation{1.0F};
  AutomationLane harmonic_adaptation{0.0F};
  AutomationLane reverb_send{0.0F};
  AutomationLane delay_send{0.0F};
  AutomationLane morph{0.0F};
  GenerationMode generation_mode{GenerationMode::None};
  SourcePlaybackPolicy playback_policy{SourcePlaybackPolicy::NoRepeat};
};

class StemTimeline final {
public:
  StemTimeline(std::uint32_t stem_id, StemRole role,
               std::vector<TimelineEvent> events);

  [[nodiscard]] std::uint32_t stem_id() const noexcept { return stem_id_; }
  [[nodiscard]] StemRole role() const noexcept { return role_; }
  [[nodiscard]] const std::vector<TimelineEvent> &events() const noexcept {
    return events_;
  }
  [[nodiscard]] bool has_generated_audio() const noexcept;
  [[nodiscard]] float summed_gain_at(std::uint64_t frame) const noexcept;

private:
  std::uint32_t stem_id_{0};
  StemRole role_{StemRole::Other};
  std::vector<TimelineEvent> events_;
};

class ProjectTimeline final {
public:
  ProjectTimeline() = default;
  explicit ProjectTimeline(std::vector<StemTimeline> stems);

  [[nodiscard]] const std::vector<StemTimeline> &stems() const noexcept {
    return stems_;
  }
  [[nodiscard]] const StemTimeline *find(std::uint32_t stem_id) const noexcept;

private:
  std::vector<StemTimeline> stems_;
};

struct AudioView final {
  const float *interleaved{nullptr};
  std::uint64_t frames{0};
  std::uint32_t channels{0};
  std::uint32_t sample_rate{0};

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] float sample(std::uint64_t frame,
                             std::uint32_t channel) const noexcept;
};

struct AnchorFeatures final {
  std::uint32_t stem_id{0};
  StemRole role{StemRole::Other};
  bool available_a{false};
  bool available_b{false};
  float confidence{0.0F};
  float continuity{0.0F};
  float transient_stability{0.0F};
  float harmonic_stability{0.0F};
  float foreground{0.0F};
  bool available_generated{false};
};

struct StemInput final {
  AnchorFeatures features{};
  AudioView source_a{};
  AudioView source_b{};
  AudioView generated{};
};

struct AnchorChoice final {
  std::uint32_t stem_id{0};
  StemRole role{StemRole::Other};
  double score{-std::numeric_limits<double>::infinity()};
};

class AnchorSelector final {
public:
  [[nodiscard]] static std::optional<AnchorChoice>
  select(const std::vector<AnchorFeatures> &features) noexcept;
};

struct CancellationProbe final {
  using Callback = bool (*)(void *user_data);

  Callback callback{nullptr};
  void *user_data{nullptr};

  [[nodiscard]] bool cancelled() const noexcept {
    return callback != nullptr && callback(user_data);
  }
};

class CancellationToken final {
public:
  void cancel() noexcept { cancelled_.store(true, std::memory_order_release); }
  void reset() noexcept { cancelled_.store(false, std::memory_order_release); }
  [[nodiscard]] bool cancelled() const noexcept {
    return cancelled_.load(std::memory_order_acquire);
  }
  [[nodiscard]] CancellationProbe probe() noexcept;

private:
  static bool probe_callback(void *user_data) noexcept;
  std::atomic<bool> cancelled_{false};
};

struct SearchLimits final {
  std::size_t beam_width{8};
  std::size_t max_expansions{128};
  std::size_t max_candidates{8};
};

struct SearchContext final {
  std::uint32_t sample_rate{48'000};
  double bpm_a{120.0};
  double bpm_b{120.0};
  std::uint64_t requested_frames{0};
  double harmonic_distance{0.0};
  QualityTier tier{QualityTier::Balanced};
  SearchLimits limits{};
  CancellationProbe cancellation{};
};

struct BridgePlan final {
  ProjectTimeline timeline{};
  std::uint32_t anchor_stem_id{0};
  std::uint64_t total_frames{0};
  double objective{0.0};
  std::uint64_t stable_id{0};
};

struct SearchResult final {
  std::vector<BridgePlan> candidates;
  std::size_t expansions{0};
  bool cancelled{false};
};

class BridgePlanner final {
public:
  [[nodiscard]] SearchResult search(const std::vector<StemInput> &stems,
                                    const SearchContext &context) const;

  [[nodiscard]] BridgePlan safe_crossfade(const std::vector<StemInput> &stems,
                                          std::uint32_t sample_rate,
                                          std::uint64_t frames) const;

  [[nodiscard]] BridgePlan anchor_only(const std::vector<StemInput> &stems,
                                       std::uint32_t sample_rate,
                                       std::uint64_t frames) const;
};

struct RenderMetrics final {
  float minimum_anchor_gain{0.0F};
  float anchor_rms{0.0F};
  float preclip_peak{0.0F};
  std::uint32_t anchor_source_mask{0};
  bool had_nonfinite{false};
  std::uint64_t rendered_frames{0};
};

struct RenderResult final {
  std::vector<float> interleaved;
  std::uint32_t sample_rate{0};
  std::uint32_t channels{2};
  bool cancelled{false};
  RenderMetrics metrics{};
};

class BridgeRenderer final {
public:
  [[nodiscard]] RenderResult render(const BridgePlan &plan,
                                    const std::vector<StemInput> &stems,
                                    std::uint32_t sample_rate,
                                    CancellationProbe cancellation = {}) const;
};

struct QualityContext final {
  std::uint32_t sample_rate{48'000};
  std::uint64_t expected_frames{0};
  double bpm_a{120.0};
  double bpm_b{120.0};
  double harmonic_distance{0.0};
  double stem_conflict_score{0.0};
  float max_peak{1.0F};
  float max_dc_offset{0.035F};
  float max_derivative{1.25F};
  float max_second_derivative{1.75F};
  float max_boundary_jump{0.35F};
  float min_loudness_dbfs{-60.0F};
  float max_loudness_dbfs{-0.25F};
  std::uint32_t control_block_frames{256};
  bool has_previous_output{false};
  bool has_next_output{false};
  std::array<float, 2> previous_output{};
  std::array<float, 2> next_output{};
};

struct QualityDiagnostics final {
  bool finite_samples{true};
  bool expected_length{true};
  bool peak_ok{true};
  bool preclip_ok{true};
  bool dc_ok{true};
  bool derivative_ok{true};
  bool second_derivative_ok{true};
  bool boundaries_ok{true};
  bool loudness_ok{true};
  bool rhythm_ok{true};
  bool harmony_ok{true};
  bool stem_conflicts_ok{true};
  bool anchor_continuous{true};
  bool anchor_audible{true};
  float peak{0.0F};
  float preclip_peak{0.0F};
  float dc_offset{0.0F};
  float max_dc_jump{0.0F};
  float max_sample_derivative{0.0F};
  float max_second_derivative{0.0F};
  float max_boundary_jump{0.0F};
  float anchor_rms{0.0F};
  float loudness_dbfs{-120.0F};
  float rhythm_error{0.0F};
  float harmony_conflict{0.0F};
  float stem_conflict{0.0F};
  std::vector<DiagnosticCode> failures;

  [[nodiscard]] bool accepted() const noexcept { return failures.empty(); }
};

class QualityEvaluator final {
public:
  [[nodiscard]] QualityDiagnostics
  evaluate(const RenderResult &render, const QualityContext &context) const;
};

struct CapabilityMeasurements final {
  double render_realtime_factor{1.0};
  std::uint64_t memory_budget_bytes{64ULL * 1024ULL * 1024ULL};
  std::uint32_t logical_cores{2};
  float thermal_headroom{0.5F};
  float battery_fraction{1.0F};
  bool low_power_mode{false};
};

struct QualityProfile final {
  QualityTier tier{QualityTier::Economy};
  SearchLimits search{};
  std::uint32_t control_block_frames{256};
  bool allow_generated_textures{false};
};

class AdaptiveQualitySelector final {
public:
  [[nodiscard]] static QualityProfile
  select(const CapabilityMeasurements &measured) noexcept;
};

struct CacheKey final {
  std::string source_a_id;
  std::string source_b_id;
  std::uint64_t source_a_revision{0};
  std::uint64_t source_b_revision{0};
  std::uint64_t plan_id{0};
  std::uint32_t sample_rate{0};
  QualityTier quality{QualityTier::Economy};
  std::uint32_t planner_revision{1};

  [[nodiscard]] std::uint64_t stable_hash() const noexcept;
  [[nodiscard]] bool operator==(const CacheKey &other) const noexcept;
};

template <typename T> class SpscRing final {
  static_assert(std::is_trivially_copyable<T>::value,
                "SPSC elements must be trivially copyable");
  static_assert(std::atomic<std::uint32_t>::is_always_lock_free,
                "audio core requires lock-free 32-bit atomics");

public:
  explicit SpscRing(std::uint32_t capacity)
      : slots_(validated_slots(capacity)), capacity_(capacity) {}

  SpscRing(const SpscRing &) = delete;
  SpscRing &operator=(const SpscRing &) = delete;

  [[nodiscard]] std::uint32_t capacity() const noexcept { return capacity_; }

  [[nodiscard]] std::uint32_t available_read() const noexcept {
    const auto head = head_.load(std::memory_order_acquire);
    const auto tail = tail_.load(std::memory_order_acquire);
    return distance(tail, head);
  }

  [[nodiscard]] std::uint32_t available_write() const noexcept {
    return capacity_ - available_read();
  }

  [[nodiscard]] std::uint32_t write(const T *source,
                                    std::uint32_t count) noexcept {
    if (source == nullptr || count == 0U) {
      return 0U;
    }
    const auto head = head_.load(std::memory_order_relaxed);
    const auto tail = tail_.load(std::memory_order_acquire);
    const auto writable = capacity_ - distance(tail, head);
    const auto amount = std::min(count, writable);
    copy_into(head, source, amount);
    head_.store(advance(head, amount), std::memory_order_release);
    return amount;
  }

  [[nodiscard]] std::uint32_t read(T *destination,
                                   std::uint32_t count) noexcept {
    if (destination == nullptr || count == 0U) {
      return 0U;
    }
    const auto tail = tail_.load(std::memory_order_relaxed);
    const auto head = head_.load(std::memory_order_acquire);
    const auto amount = std::min(count, distance(tail, head));
    copy_out(tail, destination, amount);
    tail_.store(advance(tail, amount), std::memory_order_release);
    return amount;
  }

  [[nodiscard]] bool try_push(const T &value) noexcept {
    return write(&value, 1U) == 1U;
  }
  [[nodiscard]] bool try_pop(T &value) noexcept {
    return read(&value, 1U) == 1U;
  }

  void reset_quiescent() noexcept {
    tail_.store(0U, std::memory_order_relaxed);
    head_.store(0U, std::memory_order_relaxed);
  }

private:
  static std::size_t validated_slots(std::uint32_t capacity) {
    if (capacity == 0U ||
        capacity == std::numeric_limits<std::uint32_t>::max()) {
      throw std::invalid_argument("SPSC capacity must be in [1, UINT32_MAX)");
    }
    return static_cast<std::size_t>(capacity) + 1U;
  }

  [[nodiscard]] std::uint32_t advance(std::uint32_t index,
                                      std::uint32_t amount) const noexcept {
    const auto slots = capacity_ + 1U;
    return static_cast<std::uint32_t>(
        (static_cast<std::uint64_t>(index) + amount) % slots);
  }

  [[nodiscard]] std::uint32_t distance(std::uint32_t from,
                                       std::uint32_t to) const noexcept {
    if (to >= from) {
      return to - from;
    }
    return capacity_ + 1U - (from - to);
  }

  void copy_into(std::uint32_t index, const T *source,
                 std::uint32_t count) noexcept {
    const auto first = std::min(count, capacity_ + 1U - index);
    std::memcpy(slots_.data() + index, source, sizeof(T) * first);
    if (count > first) {
      std::memcpy(slots_.data(), source + first, sizeof(T) * (count - first));
    }
  }

  void copy_out(std::uint32_t index, T *destination,
                std::uint32_t count) const noexcept {
    const auto first = std::min(count, capacity_ + 1U - index);
    std::memcpy(destination, slots_.data() + index, sizeof(T) * first);
    if (count > first) {
      std::memcpy(destination + first, slots_.data(),
                  sizeof(T) * (count - first));
    }
  }

  std::vector<T> slots_;
  std::uint32_t capacity_{0};
  alignas(64) std::atomic<std::uint32_t> head_{0};
  alignas(64) std::atomic<std::uint32_t> tail_{0};
};

class SpscAudioRingBuffer final {
public:
  explicit SpscAudioRingBuffer(std::uint32_t sample_capacity)
      : ring_(sample_capacity) {}

  [[nodiscard]] std::uint32_t write(const float *samples,
                                    std::uint32_t count) noexcept {
    return ring_.write(samples, count);
  }
  [[nodiscard]] std::uint32_t read(float *samples,
                                   std::uint32_t count) noexcept {
    return ring_.read(samples, count);
  }
  [[nodiscard]] std::uint32_t available_read() const noexcept {
    return ring_.available_read();
  }
  [[nodiscard]] std::uint32_t available_write() const noexcept {
    return ring_.available_write();
  }
  [[nodiscard]] std::uint32_t capacity() const noexcept {
    return ring_.capacity();
  }
  void reset_quiescent() noexcept { ring_.reset_quiescent(); }

private:
  SpscRing<float> ring_;
};

enum class EngineState : std::uint8_t {
  Stopped = 0,
  Playing = 1,
  Paused = 2,
  Seeking = 3,
};

struct NextRequest final {
  std::uint64_t track_id{0};
  std::uint64_t generation{0};
};

class LifecycleController final {
public:
  [[nodiscard]] bool start();
  [[nodiscard]] bool pause();
  [[nodiscard]] bool resume();
  [[nodiscard]] std::uint64_t seek(std::uint64_t target_frame);
  [[nodiscard]] bool complete_seek(std::uint64_t generation);
  void stop();
  [[nodiscard]] std::uint64_t request_next(std::uint64_t track_id);
  [[nodiscard]] std::optional<NextRequest> consume_next();

  [[nodiscard]] EngineState state() const noexcept {
    return state_.load(std::memory_order_acquire);
  }
  [[nodiscard]] std::uint64_t generation() const noexcept {
    return generation_.load(std::memory_order_acquire);
  }
  [[nodiscard]] std::uint64_t position() const noexcept {
    return position_.load(std::memory_order_acquire);
  }
  [[nodiscard]] bool generation_valid(std::uint64_t generation) const noexcept {
    return generation_.load(std::memory_order_acquire) == generation;
  }

private:
  std::atomic<EngineState> state_{EngineState::Stopped};
  std::atomic<EngineState> resume_after_seek_{EngineState::Stopped};
  std::atomic<std::uint64_t> generation_{1};
  std::atomic<std::uint64_t> position_{0};
  std::mutex control_mutex_;
  std::optional<NextRequest> pending_next_;
};

enum class FeedbackKind : std::uint8_t {
  Played = 0,
  Skipped = 1,
  Liked = 2,
  Disliked = 3,
};

struct Recommendation final {
  std::uint64_t track_id{0};
  float score{0.0F};
  std::uint32_t reason_flags{0};
};

struct RecommendationFeedback final {
  std::uint64_t track_id{0};
  FeedbackKind kind{FeedbackKind::Played};
  std::uint64_t monotonic_millis{0};
  std::uint32_t dwell_millis{0};
};

class RecommendationQueue final {
public:
  explicit RecommendationQueue(std::uint32_t capacity) : queue_(capacity) {}
  [[nodiscard]] bool enqueue(const Recommendation &item) noexcept {
    return queue_.try_push(item);
  }
  [[nodiscard]] bool dequeue(Recommendation &item) noexcept {
    return queue_.try_pop(item);
  }
  [[nodiscard]] std::uint32_t size() const noexcept {
    return queue_.available_read();
  }

private:
  SpscRing<Recommendation> queue_;
};

class FeedbackQueue final {
public:
  explicit FeedbackQueue(std::uint32_t capacity) : queue_(capacity) {}
  [[nodiscard]] bool enqueue(const RecommendationFeedback &item) noexcept {
    return queue_.try_push(item);
  }
  [[nodiscard]] bool dequeue(RecommendationFeedback &item) noexcept {
    return queue_.try_pop(item);
  }
  [[nodiscard]] std::uint32_t size() const noexcept {
    return queue_.available_read();
  }

private:
  SpscRing<RecommendationFeedback> queue_;
};

struct BridgeRequest final {
  std::uint32_t sample_rate{48'000};
  double bpm_a{120.0};
  double bpm_b{120.0};
  std::uint64_t duration_frames{0};
  double harmonic_distance{0.0};
  double stem_conflict_score{0.0};
  std::vector<StemInput> stems;
  CapabilityMeasurements capability{};
  CancellationProbe cancellation{};
  bool has_previous_output{false};
  bool has_next_output{false};
  std::array<float, 2> previous_output{};
  std::array<float, 2> next_output{};
};

struct FallbackAttempt final {
  FallbackStage stage{FallbackStage::DeterministicMultiStem};
  QualityDiagnostics diagnostics{};
};

struct BridgeOutcome final {
  RenderResult render{};
  QualityDiagnostics diagnostics{};
  FallbackStage stage{FallbackStage::EmergencyBasicCrossfade};
  QualityTier quality{QualityTier::Economy};
  std::size_t search_expansions{0};
  std::uint64_t plan_id{0};
  bool no_audio{false};
  std::vector<FallbackAttempt> attempts;
};

class BridgeEngine final {
public:
  [[nodiscard]] BridgeOutcome render(const BridgeRequest &request) const;

private:
  BridgePlanner planner_{};
  BridgeRenderer renderer_{};
  QualityEvaluator evaluator_{};
};

} // namespace autoremix::audio
