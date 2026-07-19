#pragma once

#include "autoremix/audio_core.hpp"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace autoremix::audio {

constexpr std::uint32_t kPreprocessedTrackSchemaVersion = 1U;

enum class TransitionState : std::uint8_t {
  IDLE = 0,
  TARGET_SELECTED = 1,
  PREPARING = 2,
  FALLBACK_READY = 3,
  NEURAL_CANDIDATES_PENDING = 4,
  ARMED = 5,
  WAITING_FOR_ACTIVATION_BOUNDARY = 6,
  TRANSITIONING = 7,
  LANDED = 8,
  CANCELLED = 9,
  FAILED = 10,
};

enum class CandidateLevel : std::uint8_t {
  LEVEL_0 = 0,
  LEVEL_1 = 1,
  LEVEL_2 = 2,
  InstantSafety = LEVEL_0,
  EnhancedDeterministic = LEVEL_1,
  Neural = LEVEL_2,
};

enum class ChunkKind : std::uint8_t {
  OneBar = 0,
  TwoBars = 1,
  FourBars = 2,
  EightBars = 3,
  Phrase = 4,
};

enum class PreprocessChunkState : std::uint8_t {
  Pending = 0,
  Processing = 1,
  Complete = 2,
  Failed = 3,
};

enum class GraphEdgeKind : std::uint8_t {
  Phrase = 0,
  Chord = 1,
  StemContinuation = 2,
};

struct FragmentDescriptor final {
  std::uint64_t fragment_id{0};
  std::uint64_t track_id{0};
  StemRole stem_role{StemRole::Other};
  SourceKind source{SourceKind::SourceA};
  ChunkKind chunk_kind{ChunkKind::OneBar};
  std::uint64_t start_sample{0};
  std::uint64_t end_sample{0};
  std::uint32_t bar_count{1};
  float beat_phase{0.0F};
  std::int64_t downbeat_offset{0};
  std::vector<float> bpm_curve;
  std::int32_t key{-1};
  std::int32_t chord_start{-1};
  std::int32_t chord_end{-1};
  std::vector<float> chroma_embedding;
  std::vector<float> timbre_embedding;
  std::vector<float> instrument_embedding;
  std::uint64_t melodic_fingerprint{0};
  std::uint64_t harmonic_fingerprint{0};
  std::uint64_t rhythmic_fingerprint{0};
  std::uint64_t groove_fingerprint{0};
  std::uint64_t arrangement_fingerprint{0};
  std::vector<float> groove_embedding;
  float energy{0.0F};
  float density{0.0F};
  std::vector<float> transient_profile;
  std::vector<float> stereo_profile;
  float loopability_score{0.0F};
  std::vector<float> boundary_in_descriptor;
  std::vector<float> boundary_out_descriptor;
  float vocal_activity{0.0F};
  float generation_confidence{0.0F};
  float generation_latency_seconds{0.0F};
  bool cache_available{false};
  std::uint64_t audible_layer_mask{0};
  std::uint64_t source_identity{0};
  std::uint32_t variation_mask{0};

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] std::uint64_t sample_count() const noexcept;
};

struct ContinuationEdge final {
  std::uint64_t from_fragment_id{0};
  std::uint64_t to_fragment_id{0};
  GraphEdgeKind kind{GraphEdgeKind::StemContinuation};
  float sample_boundary_continuity{0.0F};
  float phase_continuity{0.0F};
  float beat_alignment{0.0F};
  float chord_compatibility{0.0F};
  float key_compatibility{0.0F};
  float timbre_compatibility{0.0F};
  float groove_compatibility{0.0F};
  float energy_trajectory{0.0F};
  float stem_role_fit{0.0F};
  float masking_fit{0.0F};
  float latency_fit{0.0F};
  float cache_fit{0.0F};

  [[nodiscard]] float score() const noexcept;
};

class ContinuationGraph final {
public:
  [[nodiscard]] bool add_fragment(FragmentDescriptor fragment);
  [[nodiscard]] bool add_edge(ContinuationEdge edge);
  [[nodiscard]] const FragmentDescriptor *
  find_fragment(std::uint64_t fragment_id) const noexcept;
  [[nodiscard]] std::vector<ContinuationEdge>
  outgoing(std::uint64_t fragment_id) const;
  [[nodiscard]] const std::vector<FragmentDescriptor> &fragments() const noexcept {
    return fragments_;
  }
  [[nodiscard]] const std::vector<ContinuationEdge> &edges() const noexcept {
    return edges_;
  }

private:
  std::vector<FragmentDescriptor> fragments_;
  std::vector<ContinuationEdge> edges_;
};

struct ContinuationContext final {
  std::uint64_t current_fragment_id{0};
  std::uint64_t track_id{0};
  StemRole stem_role{StemRole::Other};
  float beat_phase{0.0F};
  std::int32_t key{-1};
  std::int32_t chord{-1};
  float energy{0.0F};
  std::uint64_t audible_layer_mask{0};
};

struct DeviceBudget final {
  std::size_t max_candidates{16};
  std::size_t max_expansions{256};
  bool allow_generated{true};
  bool allow_neural{true};
  float thermal_headroom{1.0F};
};

class ContinuationReservoir final {
public:
  ContinuationReservoir() = default;
  explicit ContinuationReservoir(ContinuationGraph graph)
      : graph_(std::move(graph)) {}

  [[nodiscard]] bool add_fragment(FragmentDescriptor fragment) {
    return graph_.add_fragment(std::move(fragment));
  }
  [[nodiscard]] bool add_edge(ContinuationEdge edge) {
    return graph_.add_edge(std::move(edge));
  }
  [[nodiscard]] const ContinuationGraph &graph() const noexcept { return graph_; }

  [[nodiscard]] std::vector<FragmentDescriptor> get_candidates(
      const ContinuationContext &current_context, std::uint32_t desired_bars,
      const std::vector<std::uint64_t> &excluded_fragment_ids,
      const std::vector<std::uint64_t> &recent_melodic_fingerprints,
      const ContinuationContext &target_track_context,
      const DeviceBudget &device_budget) const;

  [[nodiscard]] std::vector<FragmentDescriptor> get_candidates(
      const ContinuationContext &current_context, std::uint32_t desired_bars,
      const std::vector<std::uint64_t> &excluded_fragment_ids,
      const std::vector<std::uint64_t> &recent_melodic_fingerprints,
      const DeviceBudget &device_budget) const {
    return get_candidates(current_context, desired_bars, excluded_fragment_ids,
                          recent_melodic_fingerprints, ContinuationContext{},
                          device_budget);
  }

  [[nodiscard]] std::vector<FragmentDescriptor> getCandidates(
      const ContinuationContext &current_context, std::uint32_t desired_bars,
      const std::vector<std::uint64_t> &excluded_fragment_ids,
      const std::vector<std::uint64_t> &recent_melodic_fingerprints,
      const ContinuationContext &target_track_context,
      const DeviceBudget &device_budget) const {
    return get_candidates(current_context, desired_bars, excluded_fragment_ids,
                          recent_melodic_fingerprints, target_track_context,
                          device_budget);
  }

private:
  ContinuationGraph graph_;
};

struct ContinuationPlanningRequest final {
  ContinuationContext current_context{};
  ContinuationContext target_context{};
  std::uint32_t target_bars{16};
  std::size_t beam_width{8};
  std::size_t max_expansions{256};
  std::size_t fragment_reuse_window{8};
  std::size_t fingerprint_window{8};
  std::uint64_t anchor_fragment_id{0};
  std::vector<std::uint64_t> excluded_fragment_ids;
  std::vector<std::uint64_t> recent_melodic_fingerprints;
  DeviceBudget device_budget{};
};

struct PlannedFragment final {
  FragmentDescriptor fragment{};
  std::uint32_t timeline_start_bar{0};
  std::uint32_t bars_used{0};
  double edge_score{0.0};
};

struct ContinuationPlan final {
  std::vector<PlannedFragment> fragments;
  std::uint32_t total_bars{0};
  double score{-std::numeric_limits<double>::infinity()};
  std::size_t expansions{0};
  bool complete{false};
};

class NonRepeatingContinuationPlanner final {
public:
  [[nodiscard]] ContinuationPlan
  plan(const ContinuationReservoir &reservoir,
       const ContinuationPlanningRequest &request) const;
};

struct RepetitionMetrics final {
  float exact_fragment_repeat_rate{0.0F};
  float melodic_repeat_rate{0.0F};
  float harmonic_repeat_rate{0.0F};
  float rhythmic_repeat_rate{0.0F};
  float full_arrangement_repeat_rate{0.0F};
  std::uint32_t longest_identical_run_bars{0};
  float novelty_per_bar{1.0F};
};

struct RepetitionLimits final {
  float max_exact_fragment_repeat_rate{0.25F};
  float max_melodic_repeat_rate{0.25F};
  float max_full_arrangement_repeat_rate{0.20F};
  std::uint32_t max_identical_run_bars{4};
  float min_novelty_per_bar{0.25F};
};

struct RepetitionEvaluation final {
  RepetitionMetrics metrics{};
  bool accepted{true};
};

class RepetitionQualityEvaluator final {
public:
  [[nodiscard]] RepetitionEvaluation
  evaluate(const std::vector<PlannedFragment> &fragments,
           const RepetitionLimits &limits = {}) const noexcept;
};

struct ChunkJobProgress final {
  std::uint64_t chunk_id{0};
  ChunkKind chunk_kind{ChunkKind::OneBar};
  std::uint64_t start_sample{0};
  std::uint64_t end_sample{0};
  std::uint64_t next_sample{0};
  PreprocessChunkState state{PreprocessChunkState::Pending};
  std::uint32_t attempt_count{0};
  std::uint64_t checksum{0};

  [[nodiscard]] bool valid() const noexcept;
};

struct PreprocessedTrack final {
  std::uint32_t schema_version{kPreprocessedTrackSchemaVersion};
  std::uint32_t analyzer_revision{1};
  std::uint64_t track_id{0};
  std::uint64_t content_revision{0};
  std::uint32_t model_revision{0};
  ContinuationGraph continuation_graph{};
  std::vector<ChunkJobProgress> chunk_jobs;
  std::vector<std::uint64_t> entry_port_fragment_ids;
  std::vector<std::uint64_t> exit_port_fragment_ids;
  std::vector<std::uint64_t> instrument_similarity_fragment_ids;
  std::vector<std::uint64_t> loop_candidate_fragment_ids;
  std::vector<std::uint64_t> variation_candidate_fragment_ids;
  std::vector<std::uint64_t> fill_candidate_fragment_ids;
  std::vector<float> track_embedding;
  std::vector<float> stem_embeddings;
  std::vector<float> cached_encoder_output;
  std::vector<float> cached_conditioning_state;
  std::vector<std::uint8_t> quantized_stem_representation;
  std::vector<std::uint8_t> audio_codec_tokens;

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] bool preprocessing_complete() const noexcept;
  [[nodiscard]] std::vector<FragmentDescriptor>
  chunk_bank(ChunkKind kind) const;
  [[nodiscard]] std::vector<std::uint8_t> serialize() const;
  [[nodiscard]] static std::optional<PreprocessedTrack>
  deserialize(const std::uint8_t *bytes, std::size_t size) noexcept;
  [[nodiscard]] static std::optional<PreprocessedTrack>
  deserialize(const std::vector<std::uint8_t> &bytes) noexcept {
    return deserialize(bytes.data(), bytes.size());
  }
};

struct ProgressiveBufferConfig final {
  std::uint32_t channels{2};
  std::uint64_t frames_per_bar{1};
  std::uint32_t committed_bars{2};
  std::uint32_t guaranteed_bars{8};
  std::uint32_t target_bars{16};
  std::uint32_t planning_bars{32};
  std::uint32_t low_watermark_bars{8};
  std::uint32_t high_watermark_bars{16};
};

struct ProgressiveBufferDiagnostics final {
  std::uint64_t playback_frame{0};
  std::uint64_t rendered_end_frame{0};
  std::uint64_t committed_horizon_frames{0};
  std::uint64_t guaranteed_rendered_horizon_frames{0};
  std::uint64_t target_horizon_frames{0};
  std::uint64_t planning_horizon_frames{0};
  std::uint64_t low_watermark_frames{0};
  std::uint64_t high_watermark_frames{0};
  std::uint64_t low_watermark_events{0};
  std::uint64_t neural_upgrades_applied{0};
  CandidateLevel active_candidate_level{CandidateLevel::LEVEL_0};
  bool below_low_watermark{true};
  bool at_or_above_high_watermark{false};
  bool activation_ready{false};
};

class ProgressivePcmBuffer final {
public:
  explicit ProgressivePcmBuffer(ProgressiveBufferConfig config);

  [[nodiscard]] bool append_pcm(const std::vector<float> &interleaved,
                                CandidateLevel level,
                                std::uint64_t candidate_id = 0);
  [[nodiscard]] bool append_deterministic(
      const std::vector<float> &interleaved,
      CandidateLevel level = CandidateLevel::LEVEL_0,
      std::uint64_t candidate_id = 0);
  [[nodiscard]] bool replace_uncommitted_with_neural(
      std::uint64_t timeline_start_frame,
      const std::vector<float> &interleaved,
      std::uint64_t candidate_id = 0);
  [[nodiscard]] std::uint64_t read(float *destination,
                                   std::uint64_t frames) noexcept;
  void discard_consumed();
  void reset(std::uint64_t playback_frame = 0);

  [[nodiscard]] bool needs_deterministic_recovery() const noexcept;
  [[nodiscard]] bool activation_ready() const noexcept;
  [[nodiscard]] std::uint64_t playback_frame() const noexcept;
  [[nodiscard]] std::uint64_t rendered_end_frame() const noexcept;
  [[nodiscard]] std::uint64_t committed_end_frame() const noexcept;
  [[nodiscard]] std::uint64_t available_frames() const noexcept;
  [[nodiscard]] ProgressiveBufferDiagnostics diagnostics() const noexcept;
  [[nodiscard]] const ProgressiveBufferConfig &config() const noexcept {
    return config_;
  }

private:
  [[nodiscard]] std::uint64_t bars_to_frames(std::uint32_t bars) const noexcept;
  [[nodiscard]] std::uint64_t available_frames_locked() const noexcept;
  [[nodiscard]] std::uint64_t rendered_end_frame_locked() const noexcept;
  void update_watermark_locked() noexcept;

  ProgressiveBufferConfig config_{};
  mutable std::mutex mutex_;
  std::vector<float> interleaved_;
  std::uint64_t buffer_start_frame_{0};
  std::uint64_t read_offset_frames_{0};
  std::uint64_t low_watermark_events_{0};
  std::uint64_t neural_upgrades_applied_{0};
  std::uint64_t last_candidate_id_{0};
  CandidateLevel active_candidate_level_{CandidateLevel::LEVEL_0};
  bool previously_above_low_watermark_{false};
};

struct TransitionCandidate final {
  std::uint64_t candidate_id{0};
  std::uint64_t generation{0};
  std::uint64_t target_track_id{0};
  CandidateLevel level{CandidateLevel::LEVEL_0};
  std::uint64_t rendered_frames{0};
  double quality_score{0.0};
  bool technically_valid{false};
  bool repetition_valid{false};

  [[nodiscard]] bool valid() const noexcept;
};

struct TransitionDiagnosticsSnapshot final {
  TransitionState state{TransitionState::IDLE};
  std::uint64_t natural_runway_remaining_frames{0};
  double generation_eta_seconds{0.0};
  std::uint64_t guaranteed_rendered_horizon_frames{0};
  std::uint64_t planning_horizon_frames{0};
  std::uint64_t committed_horizon_frames{0};
  CandidateLevel active_candidate_level{CandidateLevel::LEVEL_0};
  std::vector<std::uint64_t> recent_fragment_ids;
  float repetition_score{0.0F};
  float novelty_score{1.0F};
  std::uint64_t buffer_low_watermark_events{0};
  std::uint64_t neural_upgrades_applied{0};
  std::uint64_t missed_activation_boundaries{0};
  std::uint64_t next_activation_boundary_frame{0};
  std::uint64_t generation{0};
  std::string fallback_reason;
};

[[nodiscard]] std::string anonymized_debug_report(
    const TransitionDiagnosticsSnapshot &snapshot);

class TransitionCoordinator final {
public:
  explicit TransitionCoordinator(std::uint64_t minimum_viable_frames = 1U)
      : minimum_viable_frames_(minimum_viable_frames) {}

  [[nodiscard]] bool select_target(std::uint64_t track_id);
  [[nodiscard]] bool begin_preparing();
  [[nodiscard]] bool publish_candidate(TransitionCandidate candidate);
  [[nodiscard]] bool mark_neural_candidates_pending();
  [[nodiscard]] bool arm(std::uint64_t current_playback_frame);
  [[nodiscard]] bool wait_for_activation_boundary();
  [[nodiscard]] bool advance_playback(std::uint64_t playback_frame);
  [[nodiscard]] bool land();
  void cancel();
  void fail(std::string reason);
  void reset();

  [[nodiscard]] bool set_activation_boundaries(
      std::uint64_t generation, std::uint64_t target_track_id,
      std::vector<std::uint64_t> boundaries);
  void set_natural_runway_remaining(std::uint64_t frames) noexcept;
  void set_generation_eta(double seconds) noexcept;
  void set_recent_fragment_ids(std::vector<std::uint64_t> fragment_ids);
  void set_repetition_metrics(RepetitionMetrics metrics) noexcept;
  void set_fallback_reason(std::string reason);

  [[nodiscard]] TransitionState state() const noexcept;
  [[nodiscard]] std::uint64_t generation() const noexcept;
  [[nodiscard]] std::uint64_t target_track_id() const noexcept;
  [[nodiscard]] std::optional<TransitionCandidate> active_candidate() const;
  [[nodiscard]] TransitionDiagnosticsSnapshot
  diagnostics(const ProgressivePcmBuffer &buffer) const;

private:
  [[nodiscard]] bool can_publish_locked() const noexcept;
  [[nodiscard]] bool candidate_better_locked(
      const TransitionCandidate &candidate) const noexcept;
  void choose_next_boundary_locked(std::uint64_t current_playback_frame) noexcept;

  mutable std::mutex mutex_;
  TransitionState state_{TransitionState::IDLE};
  std::uint64_t minimum_viable_frames_{1};
  std::uint64_t generation_{1};
  std::uint64_t target_track_id_{0};
  bool has_target_{false};
  bool has_deterministic_fallback_{false};
  std::optional<TransitionCandidate> active_candidate_;
  std::vector<std::uint64_t> activation_boundaries_;
  std::size_t next_boundary_index_{0};
  std::uint64_t last_playback_frame_{0};
  std::uint64_t missed_activation_boundaries_{0};
  std::uint64_t natural_runway_remaining_frames_{0};
  double generation_eta_seconds_{0.0};
  std::vector<std::uint64_t> recent_fragment_ids_;
  RepetitionMetrics repetition_metrics_{};
  std::string fallback_reason_;
};

} // namespace autoremix::audio
