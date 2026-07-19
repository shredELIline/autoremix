#include "autoremix/audio_core.hpp"
#include "autoremix/audio_core_c.h"
#include "autoremix/progressive_transition.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <functional>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

namespace ar = autoremix::audio;

extern "C" int ar_c_header_compile_probe(void);

namespace {

class TestFailure final : public std::runtime_error {
public:
  TestFailure(const char *expression, const char *file, int line)
      : std::runtime_error(std::string(file) + ":" + std::to_string(line) +
                           ": check failed: " + expression) {}
};

#define CHECK(expression)                                                      \
  do {                                                                         \
    if (!(expression)) {                                                       \
      throw TestFailure(#expression, __FILE__, __LINE__);                      \
    }                                                                          \
  } while (false)

constexpr double kPi = 3.141592653589793238462643383279502884;

struct OwnedStem final {
  std::uint32_t id{0};
  ar::StemRole role{ar::StemRole::Other};
  std::uint32_t source_rate{48'000};
  std::vector<float> source_a;
  std::vector<float> source_b;

  [[nodiscard]] ar::StemInput view() const {
    ar::StemInput result;
    result.features.stem_id = id;
    result.features.role = role;
    result.features.confidence = 0.88F;
    result.features.continuity = 0.92F;
    result.features.transient_stability =
        role == ar::StemRole::Drums ? 0.95F : 0.75F;
    result.features.harmonic_stability =
        role == ar::StemRole::Drums ? 0.55F : 0.90F;
    result.features.foreground = role == ar::StemRole::Vocal ? 0.90F : 0.35F;
    if (!source_a.empty()) {
      result.source_a = {source_a.data(), source_a.size() / 2U, 2U,
                         source_rate};
    }
    if (!source_b.empty()) {
      result.source_b = {source_b.data(), source_b.size() / 2U, 2U,
                         source_rate};
    }
    result.features.available_a = result.source_a.valid();
    result.features.available_b = result.source_b.valid();
    return result;
  }
};

[[nodiscard]] std::vector<float> sine_audio(std::uint64_t frames,
                                            std::uint32_t sample_rate,
                                            double frequency, float amplitude,
                                            double phase = 0.0) {
  std::vector<float> result(static_cast<std::size_t>(frames) * 2U);
  for (std::uint64_t frame = 0; frame < frames; ++frame) {
    const auto value =
        amplitude *
        static_cast<float>(std::sin(
            2.0 * kPi * frequency * static_cast<double>(frame) / sample_rate +
            phase));
    result[static_cast<std::size_t>(frame) * 2U] = value;
    result[static_cast<std::size_t>(frame) * 2U + 1U] = value;
  }
  return result;
}

[[nodiscard]] OwnedStem make_stem(std::uint32_t id, ar::StemRole role,
                                  std::uint32_t sample_rate,
                                  std::uint64_t frames, bool has_a = true,
                                  bool has_b = true, float amplitude = 0.08F) {
  OwnedStem result;
  result.id = id;
  result.role = role;
  result.source_rate = sample_rate;
  const auto frequency = 40.0 + 20.0 * id;
  if (has_a) {
    result.source_a =
        sine_audio(frames, sample_rate, frequency, amplitude, 0.0);
  }
  if (has_b) {
    result.source_b =
        sine_audio(frames, sample_rate, frequency * 1.01, amplitude, 0.2);
  }
  return result;
}

[[nodiscard]] std::vector<ar::StemInput>
views(const std::vector<OwnedStem> &owned) {
  std::vector<ar::StemInput> result;
  result.reserve(owned.size());
  for (const auto &stem : owned) {
    result.push_back(stem.view());
  }
  return result;
}

[[nodiscard]] ar::CapabilityMeasurements studio_capability() {
  ar::CapabilityMeasurements result;
  result.render_realtime_factor = 0.2;
  result.memory_budget_bytes = 512ULL * 1024ULL * 1024ULL;
  result.logical_cores = 8U;
  result.thermal_headroom = 0.8F;
  result.battery_fraction = 0.8F;
  return result;
}

[[nodiscard]] ar::FragmentDescriptor
make_fragment(std::uint64_t id, std::uint32_t bars,
              std::uint64_t melodic_fingerprint,
              std::uint64_t arrangement_fingerprint = 0U) {
  ar::FragmentDescriptor fragment;
  fragment.fragment_id = id;
  fragment.track_id = 7U;
  fragment.stem_role = ar::StemRole::Harmony;
  fragment.start_sample = (id - 1U) * 48'000U;
  fragment.end_sample = fragment.start_sample + bars * 48'000U;
  fragment.bar_count = bars;
  fragment.melodic_fingerprint = melodic_fingerprint;
  fragment.harmonic_fingerprint = 1'000U + id;
  fragment.rhythmic_fingerprint = 2'000U + id;
  fragment.groove_fingerprint = 3'000U + id;
  fragment.arrangement_fingerprint =
      arrangement_fingerprint == 0U ? 4'000U + id
                                    : arrangement_fingerprint;
  fragment.energy = 0.5F;
  fragment.density = 0.5F;
  fragment.loopability_score = 0.8F;
  fragment.generation_confidence = 0.9F;
  fragment.cache_available = true;
  fragment.audible_layer_mask = 1ULL << (id % 8U);
  fragment.source_identity = id;
  fragment.variation_mask = 1U << (id % 4U);
  return fragment;
}

[[nodiscard]] ar::ContinuationEdge continuation_edge(std::uint64_t from,
                                                      std::uint64_t to) {
  ar::ContinuationEdge edge;
  edge.from_fragment_id = from;
  edge.to_fragment_id = to;
  edge.sample_boundary_continuity = 1.0F;
  edge.phase_continuity = 1.0F;
  edge.beat_alignment = 1.0F;
  edge.chord_compatibility = 1.0F;
  edge.key_compatibility = 1.0F;
  edge.timbre_compatibility = 1.0F;
  edge.groove_compatibility = 1.0F;
  edge.energy_trajectory = 1.0F;
  edge.stem_role_fit = 1.0F;
  edge.masking_fit = 1.0F;
  edge.latency_fit = 1.0F;
  edge.cache_fit = 1.0F;
  return edge;
}

[[nodiscard]] ar::PlannedFragment planned_fragment(
    ar::FragmentDescriptor fragment, std::uint32_t start_bar) {
  ar::PlannedFragment result;
  result.bars_used = fragment.bar_count;
  result.timeline_start_bar = start_bar;
  result.fragment = std::move(fragment);
  return result;
}

[[nodiscard]] ar::ProgressiveBufferConfig tiny_progressive_config() {
  ar::ProgressiveBufferConfig config;
  config.channels = 1U;
  config.frames_per_bar = 1U;
  config.committed_bars = 2U;
  config.guaranteed_bars = 8U;
  config.target_bars = 16U;
  config.planning_bars = 32U;
  config.low_watermark_bars = 8U;
  config.high_watermark_bars = 16U;
  return config;
}

[[nodiscard]] std::vector<float> constant_pcm(std::uint64_t frames,
                                              std::uint32_t channels,
                                              float value) {
  return std::vector<float>(static_cast<std::size_t>(frames) * channels,
                            value);
}

[[nodiscard]] std::vector<float> ramp_pcm(std::uint64_t start_frame,
                                          std::uint64_t frames,
                                          std::uint64_t total_frames) {
  std::vector<float> result(static_cast<std::size_t>(frames));
  for (std::uint64_t frame = 0U; frame < frames; ++frame) {
    result[static_cast<std::size_t>(frame)] = static_cast<float>(
        static_cast<double>(start_frame + frame + 1U) /
        static_cast<double>(total_frames));
  }
  return result;
}

void test_automation_and_immutable_timelines() {
  ar::AutomationLane linear({{0U, 0.0F}, {10U, 1.0F}}, 0.0F,
                            ar::AutomationCurve::Linear);
  CHECK(linear.value_at(0U) == 0.0F);
  CHECK(std::abs(linear.value_at(5U) - 0.5F) < 1.0e-6F);
  CHECK(linear.value_at(10U) == 1.0F);
  CHECK(linear.value_at(100U) == 1.0F);

  ar::StemTimeline first(1U, ar::StemRole::Drums,
                         {{ar::SourceKind::SourceA, 0U, 0U, 16U, linear,
                           ar::AutomationLane(-0.5F)}});
  ar::StemTimeline second(
      2U, ar::StemRole::Bass,
      {{ar::SourceKind::SourceB, 4U, 0U, 8U, ar::AutomationLane(0.25F),
        ar::AutomationLane(0.5F)}});
  ar::ProjectTimeline project({second, first});
  CHECK(project.stems().size() == 2U);
  CHECK(project.stems()[0].stem_id() == 1U);
  CHECK(project.find(2U)->summed_gain_at(3U) == 0.0F);
  CHECK(project.find(2U)->summed_gain_at(4U) == 0.25F);
  CHECK(project.find(1U)->summed_gain_at(5U) !=
        project.find(2U)->summed_gain_at(5U));

  bool rejected_generated_vocal = false;
  try {
    (void)ar::StemTimeline(
        9U, ar::StemRole::Vocal,
        {{ar::SourceKind::Generated, 0U, 0U, 8U, ar::AutomationLane(1.0F),
          ar::AutomationLane(0.0F)}});
  } catch (const std::invalid_argument &) {
    rejected_generated_vocal = true;
  }
  CHECK(rejected_generated_vocal);
}

void test_anchor_selection_is_algorithmic_and_stable() {
  std::vector<ar::AnchorFeatures> features{
      {8U, ar::StemRole::Vocal, true, true, 0.9F, 0.9F, 0.7F, 0.8F, 1.0F},
      {3U, ar::StemRole::Bass, true, true, 0.9F, 0.9F, 0.7F, 0.8F, 0.1F},
      {1U, ar::StemRole::Drums, false, false, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
  };
  const auto selected = ar::AnchorSelector::select(features);
  CHECK(selected.has_value());
  CHECK(selected->stem_id == 3U);

  features = {
      {7U, ar::StemRole::Bass, true, true, 0.8F, 0.8F, 0.8F, 0.8F, 0.0F},
      {2U, ar::StemRole::Bass, true, true, 0.8F, 0.8F, 0.8F, 0.8F, 0.0F},
  };
  CHECK(ar::AnchorSelector::select(features)->stem_id == 2U);
}

void test_bounded_beam_and_vocal_prohibition() {
  const std::vector<OwnedStem> owned{
      make_stem(1U, ar::StemRole::Vocal, 48'000U, 8'000U),
      make_stem(2U, ar::StemRole::Drums, 48'000U, 8'000U),
      make_stem(3U, ar::StemRole::Bass, 48'000U, 8'000U),
      make_stem(4U, ar::StemRole::Harmony, 48'000U, 8'000U),
  };
  const auto stems = views(owned);
  ar::SearchContext context;
  context.sample_rate = 48'000U;
  context.bpm_a = 124.0;
  context.bpm_b = 128.0;
  context.requested_frames = 4'000U;
  context.tier = ar::QualityTier::Studio;
  context.limits = {5U, 37U, 3U};

  ar::BridgePlanner planner;
  const auto first = planner.search(stems, context);
  const auto second = planner.search(stems, context);
  CHECK(!first.cancelled);
  CHECK(first.expansions <= context.limits.max_expansions);
  CHECK(first.candidates.size() <= context.limits.max_candidates);
  CHECK(first.candidates.size() == second.candidates.size());
  CHECK(!first.candidates.empty());
  std::vector<std::uint64_t> stable_ids;
  for (std::size_t index = 0; index < first.candidates.size(); ++index) {
    CHECK(first.candidates[index].stable_id ==
          second.candidates[index].stable_id);
    CHECK(first.candidates[index].stable_id != 0U);
    CHECK(std::find(stable_ids.begin(), stable_ids.end(),
                    first.candidates[index].stable_id) == stable_ids.end());
    stable_ids.push_back(first.candidates[index].stable_id);
    const auto *anchor = first.candidates[index].timeline.find(
        first.candidates[index].anchor_stem_id);
    CHECK(anchor != nullptr);
    for (std::uint64_t frame = 0; frame < context.requested_frames; ++frame) {
      CHECK(anchor->summed_gain_at(frame) >= 0.999F);
    }
    const auto *vocal = first.candidates[index].timeline.find(1U);
    CHECK(vocal == nullptr || !vocal->has_generated_audio());
  }
}

void test_renderer_is_deterministic_and_anchor_continuous() {
  const std::vector<OwnedStem> owned{
      make_stem(1U, ar::StemRole::Drums, 48'000U, 8'000U),
      make_stem(2U, ar::StemRole::Bass, 44'100U, 7'500U),
      make_stem(3U, ar::StemRole::Harmony, 48'000U, 8'000U),
  };
  const auto stems = views(owned);
  ar::SearchContext context;
  context.sample_rate = 48'000U;
  context.bpm_a = 120.0;
  context.bpm_b = 125.0;
  context.requested_frames = 4'800U;
  context.tier = ar::QualityTier::Balanced;
  ar::BridgePlanner planner;
  const auto search = planner.search(stems, context);
  CHECK(!search.candidates.empty());

  ar::BridgeRenderer renderer;
  const auto first = renderer.render(search.candidates.front(), stems, 48'000U);
  const auto second =
      renderer.render(search.candidates.front(), stems, 48'000U);
  CHECK(first.interleaved == second.interleaved);
  CHECK(first.interleaved.size() == 9'600U);
  CHECK(first.metrics.rendered_frames == 4'800U);
  CHECK(first.metrics.minimum_anchor_gain >= 0.999F);
  CHECK(std::all_of(first.interleaved.begin(), first.interleaved.end(),
                    [](float value) {
                      return std::isfinite(value) && std::abs(value) <= 1.0F;
                    }));
}

void test_rich_event_controls_affect_render() {
  auto owned = make_stem(1U, ar::StemRole::Guitar, 48'000U, 2'048U, true,
                         false, 0.2F);
  for (std::size_t frame = 0U; frame < owned.source_a.size() / 2U; ++frame) {
    owned.source_a[frame * 2U + 1U] = -owned.source_a[frame * 2U];
  }
  const std::vector<ar::StemInput> stems{owned.view()};
  const auto render_event = [&](ar::TimelineEvent event) {
    ar::BridgePlan plan;
    plan.timeline = ar::ProjectTimeline(
        {ar::StemTimeline(1U, ar::StemRole::Guitar, {std::move(event)})});
    plan.anchor_stem_id = 1U;
    plan.total_frames = 256U;
    plan.stable_id = 1U;
    return ar::BridgeRenderer{}.render(plan, stems, 48'000U).interleaved;
  };

  ar::TimelineEvent base{ar::SourceKind::SourceA, 0U, 0U, 256U,
                         ar::AutomationLane(1.0F), ar::AutomationLane(0.0F)};
  const auto reference = render_event(base);

  auto changed = base;
  changed.tempo_ratio = ar::AutomationLane(0.5F);
  CHECK(render_event(changed) != reference);
  changed = base;
  changed.pitch_semitones = ar::AutomationLane(12.0F);
  CHECK(render_event(changed) != reference);
  changed = base;
  changed.width = ar::AutomationLane(0.0F);
  CHECK(render_event(changed) != reference);
  changed = base;
  changed.lowpass_hz = ar::AutomationLane(200.0F);
  CHECK(render_event(changed) != reference);
  changed = base;
  changed.morph = ar::AutomationLane(1.0F);
  changed.generation_mode = ar::GenerationMode::Morph;
  CHECK(render_event(changed) != reference);
}

void test_loop_overlap_removes_raw_wrap_jump() {
  OwnedStem owned;
  owned.id = 1U;
  owned.role = ar::StemRole::Atmosphere;
  owned.source_a.resize(128U * 2U);
  for (std::size_t frame = 0U; frame < 128U; ++frame) {
    const auto sample = static_cast<float>(frame) / 127.0F;
    owned.source_a[frame * 2U] = sample;
    owned.source_a[frame * 2U + 1U] = sample;
  }
  const std::vector<ar::StemInput> stems{owned.view()};
  ar::TimelineEvent event{ar::SourceKind::SourceA, 0U, 0U, 384U,
                          ar::AutomationLane(1.0F),
                          ar::AutomationLane(0.0F)};
  event.playback_policy = ar::SourcePlaybackPolicy::RepeatOnce;
  ar::BridgePlan plan;
  plan.timeline = ar::ProjectTimeline(
      {ar::StemTimeline(1U, ar::StemRole::Atmosphere, {event})});
  plan.anchor_stem_id = 1U;
  plan.total_frames = 384U;
  const auto render = ar::BridgeRenderer{}.render(plan, stems, 48'000U);
  const auto jump = std::abs(render.interleaved[128U * 2U] -
                             render.interleaved[127U * 2U]);
  CHECK(jump < 0.2F);
  CHECK(std::abs(render.interleaved[192U * 2U]) > 0.2F);
  CHECK(std::abs(render.interleaved[300U * 2U]) < 1.0e-6F);
}

void test_quality_diagnostics_are_specific() {
  ar::RenderResult render;
  render.sample_rate = 48'000U;
  render.channels = 2U;
  render.interleaved = {std::numeric_limits<float>::quiet_NaN(), 1.5F, 1.5F,
                        1.5F};
  render.metrics.minimum_anchor_gain = 0.0F;
  render.metrics.rendered_frames = 2U;
  ar::QualityContext context;
  context.sample_rate = 48'000U;
  context.expected_frames = 2U;
  context.bpm_a = 80.0;
  context.bpm_b = 180.0;
  context.harmonic_distance = 6.0;
  context.stem_conflict_score = 1.0;

  const auto diagnostics = ar::QualityEvaluator{}.evaluate(render, context);
  CHECK(!diagnostics.accepted());
  CHECK(!diagnostics.finite_samples);
  CHECK(!diagnostics.peak_ok);
  CHECK(!diagnostics.dc_ok);
  CHECK(!diagnostics.rhythm_ok);
  CHECK(!diagnostics.harmony_ok);
  CHECK(!diagnostics.stem_conflicts_ok);
  CHECK(!diagnostics.anchor_continuous);
  CHECK(diagnostics.failures.size() >= 7U);
}

void test_quality_detects_second_derivative_and_boundaries() {
  ar::RenderResult render;
  render.sample_rate = 48'000U;
  render.channels = 2U;
  render.interleaved = {0.0F, 0.0F, 0.8F, 0.8F,
                        -0.8F, -0.8F, 0.0F, 0.0F};
  render.metrics.minimum_anchor_gain = 1.0F;
  render.metrics.anchor_rms = 0.5F;
  render.metrics.preclip_peak = 0.8F;
  render.metrics.rendered_frames = 4U;
  ar::QualityContext context;
  context.expected_frames = 4U;
  context.control_block_frames = 2U;
  context.has_previous_output = true;
  context.previous_output = {1.0F, 1.0F};
  const auto diagnostics = ar::QualityEvaluator{}.evaluate(render, context);
  CHECK(!diagnostics.second_derivative_ok);
  CHECK(!diagnostics.boundaries_ok);
  CHECK(diagnostics.max_second_derivative > context.max_second_derivative);
  CHECK(diagnostics.max_boundary_jump > context.max_boundary_jump);
}

void test_nonfinite_metadata_and_generated_vocal_are_rejected() {
  const std::vector<OwnedStem> owned{
      make_stem(1U, ar::StemRole::Guitar, 48'000U, 512U, true, true)};
  ar::BridgeRequest request;
  request.duration_frames = 256U;
  request.stems = views(owned);
  request.harmonic_distance = std::numeric_limits<double>::quiet_NaN();
  bool rejected = false;
  try {
    (void)ar::BridgeEngine{}.render(request);
  } catch (const std::invalid_argument &) {
    rejected = true;
  }
  CHECK(rejected);

  request.harmonic_distance = 0.0;
  request.stems.front().features.role = ar::StemRole::BackingVocal;
  request.stems.front().generated = request.stems.front().source_a;
  rejected = false;
  try {
    (void)ar::BridgeEngine{}.render(request);
  } catch (const std::invalid_argument &) {
    rejected = true;
  }
  CHECK(rejected);
}

void test_fallback_cascade_is_guaranteed() {
  const std::vector<OwnedStem> owned{
      make_stem(1U, ar::StemRole::Bass, 48'000U, 2'048U, true, true)};

  ar::BridgeRequest request;
  request.sample_rate = 48'000U;
  request.bpm_a = 120.0;
  request.bpm_b = 120.0;
  request.duration_frames = 1'024U;
  request.harmonic_distance = 6.0;
  request.stems = views(owned);
  request.capability = studio_capability();
  const auto outcome = ar::BridgeEngine{}.render(request);
  CHECK(!outcome.render.cancelled);
  CHECK(outcome.stage == ar::FallbackStage::EmergencyBasicCrossfade);
  CHECK(outcome.render.interleaved.size() == 2'048U);
  CHECK(std::any_of(outcome.render.interleaved.begin(),
                    outcome.render.interleaved.end(),
                    [](float value) { return value != 0.0F; }));
  CHECK(!outcome.attempts.empty());
  CHECK(outcome.attempts.front().stage ==
        ar::FallbackStage::DeterministicMultiStem);
  CHECK(outcome.attempts[outcome.attempts.size() - 2U].stage ==
        ar::FallbackStage::PhraseAlignedCrossfade);
  CHECK(outcome.attempts.back().stage ==
        ar::FallbackStage::EmergencyBasicCrossfade);

  ar::BridgeRequest empty = request;
  empty.stems.clear();
  const auto empty_outcome = ar::BridgeEngine{}.render(empty);
  CHECK(empty_outcome.no_audio);
  CHECK(empty_outcome.render.interleaved.empty());

  auto dc_only = make_stem(7U, ar::StemRole::Bass, 48'000U, 256U, true,
                           false);
  std::fill(dc_only.source_a.begin(), dc_only.source_a.end(), 0.5F);
  const std::vector<OwnedStem> dc_owned{std::move(dc_only)};
  empty.stems = views(dc_owned);
  CHECK(ar::BridgeEngine{}.render(empty).no_audio);
}

bool always_cancel(void *) { return true; }

struct DelayedCancel final {
  std::atomic<std::uint32_t> calls{0U};
  std::uint32_t limit{0U};
};

bool cancel_after_limit(void *opaque) {
  auto &state = *static_cast<DelayedCancel *>(opaque);
  return state.calls.fetch_add(1U, std::memory_order_relaxed) >= state.limit;
}

void test_cancellation_stops_search_and_render() {
  const std::vector<OwnedStem> owned{
      make_stem(1U, ar::StemRole::Bass, 48'000U, 8'000U),
      make_stem(2U, ar::StemRole::Drums, 48'000U, 8'000U),
  };
  const auto stems = views(owned);
  ar::SearchContext context;
  context.sample_rate = 48'000U;
  context.bpm_a = 120.0;
  context.bpm_b = 120.0;
  context.requested_frames = 4'096U;
  context.cancellation = {&always_cancel, nullptr};
  const auto cancelled_search = ar::BridgePlanner{}.search(stems, context);
  CHECK(cancelled_search.cancelled);
  CHECK(cancelled_search.candidates.empty());

  context.cancellation = {};
  const auto search = ar::BridgePlanner{}.search(stems, context);
  CHECK(!search.candidates.empty());
  DelayedCancel delayed;
  delayed.limit = 3U;
  const auto cancelled_render =
      ar::BridgeRenderer{}.render(search.candidates.front(), stems, 48'000U,
                                  {&cancel_after_limit, &delayed});
  CHECK(cancelled_render.cancelled);
  CHECK(cancelled_render.interleaved.empty());

  ar::BridgeRequest request;
  request.sample_rate = 48'000U;
  request.bpm_a = request.bpm_b = 120.0;
  request.duration_frames = 1'024U;
  request.stems = stems;
  request.cancellation = {&always_cancel, nullptr};
  CHECK(ar::BridgeEngine{}.render(request).render.cancelled);
}

void test_spsc_ring_wrap_and_concurrency() {
  ar::SpscAudioRingBuffer ring(5U);
  const float first[] = {1.0F, 2.0F, 3.0F, 4.0F};
  CHECK(ring.write(first, 4U) == 4U);
  float output[8]{};
  CHECK(ring.read(output, 3U) == 3U);
  CHECK(output[0] == 1.0F && output[2] == 3.0F);
  const float second[] = {5.0F, 6.0F, 7.0F, 8.0F};
  CHECK(ring.write(second, 4U) == 4U);
  CHECK(ring.available_read() == 5U);
  CHECK(ring.read(output, 8U) == 5U);
  const float expected[] = {4.0F, 5.0F, 6.0F, 7.0F, 8.0F};
  CHECK(std::equal(output, output + 5U, expected));

  constexpr std::uint32_t total = 100'000U;
  ar::SpscRing<std::uint32_t> concurrent(257U);
  std::atomic<bool> valid{true};
  std::thread producer([&] {
    for (std::uint32_t value = 0U; value < total;) {
      if (concurrent.try_push(value)) {
        ++value;
      } else {
        std::this_thread::yield();
      }
    }
  });
  std::thread consumer([&] {
    for (std::uint32_t expected_value = 0U; expected_value < total;) {
      std::uint32_t value = 0U;
      if (concurrent.try_pop(value)) {
        if (value != expected_value) {
          valid.store(false, std::memory_order_relaxed);
        }
        ++expected_value;
      } else {
        std::this_thread::yield();
      }
    }
  });
  producer.join();
  consumer.join();
  CHECK(valid.load(std::memory_order_relaxed));
  CHECK(concurrent.available_read() == 0U);
}

void test_lifecycle_invalidation_and_next_coalescing() {
  ar::LifecycleController lifecycle;
  CHECK(lifecycle.start());
  CHECK(lifecycle.pause());
  CHECK(lifecycle.state() == ar::EngineState::Paused);
  CHECK(lifecycle.resume());
  CHECK(lifecycle.state() == ar::EngineState::Playing);

  const auto seek_generation = lifecycle.seek(9'999U);
  CHECK(seek_generation != 0U);
  CHECK(lifecycle.state() == ar::EngineState::Seeking);
  CHECK(lifecycle.position() == 9'999U);
  const auto first_next = lifecycle.request_next(10U);
  const auto second_next = lifecycle.request_next(20U);
  const auto last_next = lifecycle.request_next(30U);
  CHECK(first_next < second_next && second_next < last_next);
  CHECK(!lifecycle.complete_seek(seek_generation));
  const auto next = lifecycle.consume_next();
  CHECK(next.has_value());
  CHECK(next->track_id == 30U);
  CHECK(next->generation == last_next);
  CHECK(!lifecycle.consume_next().has_value());
  lifecycle.stop();
  CHECK(lifecycle.state() == ar::EngineState::Stopped);
  CHECK(!lifecycle.resume());

  CHECK(lifecycle.start());
  const auto valid_seek = lifecycle.seek(123U);
  CHECK(lifecycle.complete_seek(valid_seek));
  CHECK(lifecycle.state() == ar::EngineState::Playing);

  constexpr std::size_t request_count = 8U;
  std::array<std::uint64_t, request_count> generations{};
  std::vector<std::thread> requests;
  for (std::size_t index = 0U; index < request_count; ++index) {
    requests.emplace_back([&, index] {
      generations[index] =
          lifecycle.request_next(static_cast<std::uint64_t>(index + 1U));
    });
  }
  for (auto &request : requests) {
    request.join();
  }
  const auto latest =
      std::max_element(generations.begin(), generations.end());
  const auto concurrent_next = lifecycle.consume_next();
  CHECK(concurrent_next.has_value());
  CHECK(concurrent_next->generation == *latest);
  CHECK(concurrent_next->track_id ==
        static_cast<std::uint64_t>(std::distance(generations.begin(), latest) +
                                   1));
}

void test_capability_cache_and_recommendation_primitives() {
  CHECK(ar::AdaptiveQualitySelector::select(studio_capability()).tier ==
        ar::QualityTier::Studio);
  auto balanced = studio_capability();
  balanced.logical_cores = 4U;
  balanced.render_realtime_factor = 0.6;
  balanced.memory_budget_bytes = 128ULL * 1024ULL * 1024ULL;
  CHECK(ar::AdaptiveQualitySelector::select(balanced).tier ==
        ar::QualityTier::Balanced);
  balanced.low_power_mode = true;
  CHECK(ar::AdaptiveQualitySelector::select(balanced).tier ==
        ar::QualityTier::Economy);

  ar::CacheKey first{
      "song-a", "song-b", 1U, 2U, 3U, 48'000U, ar::QualityTier::Balanced, 7U};
  const auto copy = first;
  CHECK(first == copy);
  CHECK(first.stable_hash() == copy.stable_hash());
  auto changed = first;
  changed.plan_id++;
  CHECK(!(first == changed));
  CHECK(first.stable_hash() != changed.stable_hash());

  ar::RecommendationQueue recommendations(2U);
  CHECK(recommendations.enqueue({11U, 0.9F, 1U}));
  CHECK(recommendations.enqueue({22U, 0.8F, 2U}));
  CHECK(!recommendations.enqueue({33U, 0.7F, 4U}));
  ar::Recommendation recommendation;
  CHECK(recommendations.dequeue(recommendation));
  CHECK(recommendation.track_id == 11U);

  ar::FeedbackQueue feedback(1U);
  CHECK(feedback.enqueue({11U, ar::FeedbackKind::Liked, 500U, 4'000U}));
  CHECK(!feedback.enqueue({22U, ar::FeedbackKind::Skipped, 600U, 100U}));
  ar::RecommendationFeedback event;
  CHECK(feedback.dequeue(event));
  CHECK(event.kind == ar::FeedbackKind::Liked && event.track_id == 11U);
}

void test_stable_c_api() {
  CHECK(ar_c_header_compile_probe() == 1);
  CHECK(ar_audio_core_abi_version() == AR_AUDIO_CORE_ABI_VERSION);
  ar_audio_core_t *core = ar_audio_core_create();
  CHECK(core != nullptr);
  CHECK(ar_audio_core_set_paused(core, 0) == AR_STATUS_OK);
  CHECK(ar_audio_core_set_paused(core, 1) == AR_STATUS_OK);
  CHECK(ar_audio_core_set_paused(core, 0) == AR_STATUS_OK);
  uint64_t generation = 0U;
  CHECK(ar_audio_core_seek(core, 100U, &generation) == AR_STATUS_OK);
  CHECK(generation != 0U);
  CHECK(ar_engine_complete_seek(core, generation) == AR_STATUS_OK);

  const auto owned = make_stem(1U, ar::StemRole::Bass, 48'000U, 2'048U);
  ar_stem_input_t stem{};
  stem.struct_size = sizeof(stem);
  stem.stem_id = 1U;
  stem.role = AR_STEM_BASS;
  stem.source_a = {owned.source_a.data(), owned.source_a.size() / 2U, 2U,
                   48'000U};
  stem.source_b = {owned.source_b.data(), owned.source_b.size() / 2U, 2U,
                   48'000U};
  stem.confidence = stem.continuity = stem.transient_stability =
      stem.harmonic_stability = 0.9F;
  ar_bridge_request_t request{};
  request.struct_size = sizeof(request);
  request.sample_rate = 48'000U;
  request.bpm_a = request.bpm_b = 120.0;
  request.duration_frames = 1'024U;
  request.stems = &stem;
  request.stem_count = 1U;
  request.capability = {0.5, 128ULL * 1024ULL * 1024ULL, 4U, 0.8F, 0.8F, 0U};
  ar_bridge_result_t result{};
  result.struct_size = sizeof(result);
  CHECK(ar_audio_core_render_interleaved(core, &request, &result) ==
        AR_STATUS_BUFFER_TOO_SMALL);
  CHECK(result.frames_written == request.duration_frames);
  std::vector<float> buffer(static_cast<std::size_t>(result.frames_written) *
                            2U);
  result.interleaved = buffer.data();
  result.capacity_samples = buffer.size();
  CHECK(ar_audio_core_render_interleaved(core, &request, &result) ==
        AR_STATUS_OK);
  CHECK(result.channels == 2U);
  CHECK(std::all_of(buffer.begin(), buffer.end(), [](float value) {
    return std::isfinite(value) && std::abs(value) <= 1.0F;
  }));

  stem.role = AR_STEM_GUITAR;
  stem.generated = stem.source_a;
  stem.source_a = {};
  stem.source_b = {};
  std::fill(buffer.begin(), buffer.end(), 0.0F);
  CHECK(ar_audio_core_render_interleaved(core, &request, &result) ==
        AR_STATUS_OK);
  CHECK(std::any_of(buffer.begin(), buffer.end(),
                    [](float value) { return value != 0.0F; }));
  stem.role = AR_STEM_BACKING_VOCAL;
  CHECK(ar_audio_core_render_interleaved(core, &request, &result) ==
        AR_STATUS_INVALID_ARGUMENT);
  stem.role = AR_STEM_GUITAR;
  stem.generated = {};
  CHECK(ar_audio_core_render_interleaved(core, &request, &result) ==
        AR_STATUS_NO_AUDIO);

  ar_audio_ring_t *ring = ar_audio_ring_create(3U);
  CHECK(ring != nullptr);
  const float values[] = {1.0F, 2.0F, 3.0F, 4.0F};
  CHECK(ar_audio_ring_write(ring, values, 4U) == 3U);
  float read[3]{};
  CHECK(ar_audio_ring_read(ring, read, 3U) == 3U);
  CHECK(read[2] == 3.0F);
  ar_audio_ring_destroy(ring);

  const ar_cache_key_t key{"a", "b", 1U, 2U, 3U, 48'000U, AR_QUALITY_BALANCED,
                           1U};
  CHECK(ar_cache_key_hash(&key) != 0U);
  ar_audio_core_destroy(core);
}

void test_deterministic_fuzz_properties() {
  std::mt19937_64 random(0xA0170BEEFULL);
  const std::uint32_t sample_rates[] = {
      8'000U,  11'025U, 16'000U, 22'050U, 32'000U,
      44'100U, 48'000U, 88'200U, 96'000U, 192'000U,
  };
  const ar::StemRole roles[] = {ar::StemRole::Vocal, ar::StemRole::Drums,
                                ar::StemRole::Bass, ar::StemRole::Harmony,
                                ar::StemRole::Other};

  for (std::uint32_t iteration = 0U; iteration < 120U; ++iteration) {
    const auto output_rate = sample_rates[random() % std::size(sample_rates)];
    const auto source_rate = sample_rates[random() % std::size(sample_rates)];
    const auto duration = iteration < 4U
                              ? static_cast<std::uint64_t>(iteration + 1U)
                              : 32U + random() % 2'017U;
    const auto bpm_a = 40.0 + static_cast<double>(random() % 20'001U) / 100.0;
    const auto bpm_b = 40.0 + static_cast<double>(random() % 20'001U) / 100.0;
    const auto stem_count = static_cast<std::size_t>(random() % 6U);
    std::vector<OwnedStem> owned;
    owned.reserve(stem_count);
    for (std::size_t index = 0; index < stem_count; ++index) {
      const auto has_a = (random() & 1U) != 0U;
      const auto has_b = (random() & 1U) != 0U;
      owned.push_back(make_stem(static_cast<std::uint32_t>(index + 1U),
                                roles[index % std::size(roles)], source_rate,
                                std::max<std::uint64_t>(duration / 2U, 1U),
                                has_a, has_b, 0.03F));
    }

    ar::BridgeRequest request;
    request.sample_rate = output_rate;
    request.bpm_a = bpm_a;
    request.bpm_b = bpm_b;
    request.duration_frames = duration;
    request.harmonic_distance = static_cast<double>(random() % 700U) / 100.0;
    request.stem_conflict_score = static_cast<double>(random() % 101U) / 100.0;
    request.stems = views(owned);
    request.capability.render_realtime_factor = 1.2;
    request.capability.memory_budget_bytes = 48ULL * 1024ULL * 1024ULL;
    request.capability.logical_cores = 2U;

    ar::CancellationToken cancelled;
    if (iteration % 17U == 0U) {
      cancelled.cancel();
      request.cancellation = cancelled.probe();
    }

    const auto outcome = ar::BridgeEngine{}.render(request);
    if (iteration % 17U == 0U) {
      CHECK(outcome.render.cancelled);
      CHECK(outcome.render.interleaved.empty());
      continue;
    }
    CHECK(!outcome.render.cancelled);
    if (outcome.no_audio) {
      CHECK(outcome.render.interleaved.empty());
      continue;
    }
    CHECK(outcome.render.metrics.rendered_frames == duration);
    CHECK(outcome.render.interleaved.size() ==
          static_cast<std::size_t>(duration) * 2U);
    CHECK(outcome.search_expansions <= 48U);
    CHECK(std::all_of(outcome.render.interleaved.begin(),
                      outcome.render.interleaved.end(), [](float value) {
                        return std::isfinite(value) && std::abs(value) <= 1.0F;
                      }));
    CHECK(outcome.render.metrics.minimum_anchor_gain >= 1.0e-4F);

    ar::SearchContext search_context;
    search_context.sample_rate = output_rate;
    search_context.bpm_a = bpm_a;
    search_context.bpm_b = bpm_b;
    search_context.requested_frames = duration;
    search_context.tier = ar::QualityTier::Economy;
    search_context.limits = {3U, 19U, 2U};
    const auto search =
        ar::BridgePlanner{}.search(request.stems, search_context);
    CHECK(search.expansions <= search_context.limits.max_expansions);
    for (const auto &candidate : search.candidates) {
      for (const auto &timeline : candidate.timeline.stems()) {
        if (timeline.role() == ar::StemRole::Vocal) {
          CHECK(!timeline.has_generated_audio());
        }
      }
    }

    if (iteration < 20U) {
      const auto repeated = ar::BridgeEngine{}.render(request);
      CHECK(outcome.stage == repeated.stage);
      CHECK(outcome.plan_id == repeated.plan_id);
      CHECK(outcome.render.interleaved == repeated.render.interleaved);
    }
  }
}

void test_progressive_state_machine_activation_rules() {
  ar::TransitionCoordinator coordinator(8U);
  CHECK(coordinator.select_target(42U));
  CHECK(coordinator.state() == ar::TransitionState::TARGET_SELECTED);
  CHECK(coordinator.begin_preparing());
  CHECK(coordinator.state() == ar::TransitionState::PREPARING);

  CHECK(coordinator.set_activation_boundaries(coordinator.generation(), 42U,
                                              {100U, 200U, 300U}));
  CHECK(!coordinator.advance_playback(100U));
  CHECK(!coordinator.wait_for_activation_boundary());
  CHECK(!coordinator.arm(0U));
  CHECK(coordinator.state() == ar::TransitionState::PREPARING);

  ar::TransitionCandidate invalid;
  invalid.candidate_id = 1U;
  invalid.generation = coordinator.generation();
  invalid.target_track_id = 42U;
  invalid.level = ar::CandidateLevel::LEVEL_0;
  invalid.rendered_frames = 8U;
  invalid.quality_score = 1.0;
  invalid.technically_valid = false;
  invalid.repetition_valid = true;
  CHECK(!coordinator.publish_candidate(invalid));
  CHECK(coordinator.state() == ar::TransitionState::PREPARING);

  auto valid = invalid;
  valid.candidate_id = 2U;
  valid.technically_valid = true;
  CHECK(coordinator.publish_candidate(valid));
  CHECK(coordinator.state() == ar::TransitionState::FALLBACK_READY);
  CHECK(coordinator.arm(50U));
  CHECK(coordinator.state() == ar::TransitionState::ARMED);
  CHECK(coordinator.wait_for_activation_boundary());
  CHECK(coordinator.state() ==
        ar::TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY);

  CHECK(!coordinator.advance_playback(150U));
  CHECK(coordinator.state() ==
        ar::TransitionState::WAITING_FOR_ACTIVATION_BOUNDARY);
  ar::ProgressivePcmBuffer buffer(tiny_progressive_config());
  CHECK(buffer.append_deterministic(constant_pcm(8U, 1U, 0.1F)));
  const auto missed = coordinator.diagnostics(buffer);
  CHECK(missed.missed_activation_boundaries == 1U);
  CHECK(missed.next_activation_boundary_frame == 200U);

  CHECK(coordinator.advance_playback(200U));
  CHECK(coordinator.state() == ar::TransitionState::TRANSITIONING);
}

void test_transition_rejects_stale_and_short_candidates() {
  ar::TransitionCoordinator coordinator(8U);
  CHECK(!coordinator.select_target(0U));
  CHECK(coordinator.select_target(41U));
  CHECK(coordinator.begin_preparing());
  const auto stale_generation = coordinator.generation();
  CHECK(coordinator.set_activation_boundaries(stale_generation, 41U, {10U}));

  ar::TransitionCandidate stale;
  stale.candidate_id = 1U;
  stale.generation = coordinator.generation();
  stale.target_track_id = 41U;
  stale.level = ar::CandidateLevel::LEVEL_0;
  stale.rendered_frames = 8U;
  stale.quality_score = 1.0;
  stale.technically_valid = true;
  stale.repetition_valid = true;

  CHECK(coordinator.select_target(42U));
  CHECK(coordinator.begin_preparing());
  CHECK(!coordinator.set_activation_boundaries(stale_generation, 41U, {10U}));
  CHECK(!coordinator.publish_candidate(stale));

  auto current = stale;
  current.candidate_id = 2U;
  current.generation = coordinator.generation();
  current.target_track_id = 42U;
  current.rendered_frames = 7U;
  CHECK(!coordinator.publish_candidate(current));
  current.rendered_frames = 8U;
  CHECK(coordinator.publish_candidate(current));
  CHECK(coordinator.arm(0U));
  CHECK(!coordinator.wait_for_activation_boundary());

  CHECK(coordinator.set_activation_boundaries(coordinator.generation(), 42U,
                                              {20U}));
  CHECK(coordinator.wait_for_activation_boundary());
  auto short_upgrade = current;
  short_upgrade.candidate_id = 3U;
  short_upgrade.level = ar::CandidateLevel::LEVEL_2;
  short_upgrade.rendered_frames = 1U;
  short_upgrade.quality_score = 2.0;
  CHECK(!coordinator.publish_candidate(short_upgrade));
  CHECK(coordinator.advance_playback(20U));
}

void test_preprocessed_track_rejects_bad_indexes_and_bounded_counts() {
  ar::PreprocessedTrack track;
  track.track_id = 7U;
  CHECK(track.continuation_graph.add_fragment(make_fragment(1U, 1U, 11U)));
  track.entry_port_fragment_ids = {1U};
  track.exit_port_fragment_ids = {1U};
  CHECK(track.valid());

  const auto bytes = track.serialize();
  CHECK(!bytes.empty());
  const auto round_trip = ar::PreprocessedTrack::deserialize(bytes);
  CHECK(round_trip.has_value());
  CHECK(round_trip->valid());

  track.entry_port_fragment_ids = {999U};
  CHECK(!track.valid());
  CHECK(track.serialize().empty());

  track.entry_port_fragment_ids.assign(1'000'001U, 1U);
  CHECK(!track.valid());
  CHECK(track.serialize().empty());

  auto hostile_count = bytes;
  CHECK(hostile_count.size() > 36U);
  constexpr std::uint32_t excessive_count = 1'000'000U;
  for (std::size_t index = 0U; index < 4U; ++index) {
    hostile_count[32U + index] = static_cast<std::uint8_t>(
        (excessive_count >> (index * 8U)) & 0xFFU);
  }
  CHECK(!ar::PreprocessedTrack::deserialize(hostile_count).has_value());
}

void test_anonymized_debug_report_has_no_media_payload() {
  ar::TransitionDiagnosticsSnapshot snapshot;
  snapshot.state = ar::TransitionState::ARMED;
  snapshot.natural_runway_remaining_frames = 123U;
  snapshot.guaranteed_rendered_horizon_frames = 456U;
  snapshot.recent_fragment_ids = {7U, 8U};
  snapshot.fallback_reason = "neural timeout\nusing deterministic fallback";

  const auto report = ar::anonymized_debug_report(snapshot);
  CHECK(report.find("state=ARMED") != std::string::npos);
  CHECK(report.find("recent_fragment_ids=7,8") != std::string::npos);
  CHECK(report.find("fallback_reason=neural timeout using deterministic fallback") !=
        std::string::npos);
  CHECK(report.find("track_id") == std::string::npos);
  CHECK(report.find("audio=") == std::string::npos);
}

void test_continuation_excludes_recent_repetition() {
  ar::ContinuationReservoir reservoir;
  for (std::uint64_t id = 1U; id <= 5U; ++id) {
    CHECK(reservoir.add_fragment(make_fragment(id, 4U, id * 10U)));
  }
  CHECK(!reservoir.add_edge(continuation_edge(1U, 1U)));
  for (std::uint64_t from = 1U; from <= 5U; ++from) {
    for (std::uint64_t to = 2U; to <= 5U; ++to) {
      if (from != to) {
        CHECK(reservoir.add_edge(continuation_edge(from, to)));
      }
    }
  }

  ar::ContinuationContext context;
  context.current_fragment_id = 1U;
  context.track_id = 7U;
  context.stem_role = ar::StemRole::Harmony;
  ar::DeviceBudget budget;
  budget.max_candidates = 8U;
  budget.max_expansions = 64U;
  const auto candidates = reservoir.get_candidates(
      context, 4U, {2U}, {30U}, context, budget);
  CHECK(!candidates.empty());
  CHECK(std::all_of(candidates.begin(), candidates.end(),
                    [](const ar::FragmentDescriptor &fragment) {
                      return fragment.fragment_id != 1U &&
                             fragment.fragment_id != 2U &&
                             fragment.melodic_fingerprint != 30U;
                    }));

  ar::ContinuationPlanningRequest request;
  request.current_context = context;
  request.target_context = context;
  request.target_bars = 16U;
  request.beam_width = 8U;
  request.max_expansions = 64U;
  request.fragment_reuse_window = 8U;
  request.fingerprint_window = 8U;
  request.device_budget = budget;
  const auto plan = ar::NonRepeatingContinuationPlanner{}.plan(reservoir,
                                                               request);
  CHECK(plan.complete);
  CHECK(plan.total_bars >= request.target_bars);
  CHECK(plan.expansions <= request.max_expansions);
  for (std::size_t index = 0U; index < plan.fragments.size(); ++index) {
    CHECK(plan.fragments[index].fragment.fragment_id != 1U);
    const auto window_start =
        index > request.fragment_reuse_window
            ? index - request.fragment_reuse_window
            : 0U;
    for (std::size_t prior = window_start; prior < index; ++prior) {
      CHECK(plan.fragments[index].fragment.fragment_id !=
            plan.fragments[prior].fragment.fragment_id);
    }
    const auto fingerprint_start =
        index > request.fingerprint_window ? index - request.fingerprint_window
                                           : 0U;
    for (std::size_t prior = fingerprint_start; prior < index; ++prior) {
      CHECK(plan.fragments[index].fragment.melodic_fingerprint !=
            plan.fragments[prior].fragment.melodic_fingerprint);
    }
  }

  auto reuse_request = request;
  reuse_request.target_bars = 20U;
  reuse_request.max_expansions = 256U;
  reuse_request.fragment_reuse_window = 2U;
  reuse_request.fingerprint_window = 2U;
  reuse_request.anchor_fragment_id = 2U;
  reuse_request.device_budget.max_expansions = 256U;
  const auto reuse_plan =
      ar::NonRepeatingContinuationPlanner{}.plan(reservoir, reuse_request);
  CHECK(reuse_plan.complete);
  CHECK(reuse_plan.total_bars == reuse_request.target_bars);
  std::unordered_set<std::uint64_t> seen_ids;
  bool reused = false;
  std::size_t anchor_uses = 0U;
  for (std::size_t index = 0U; index < reuse_plan.fragments.size(); ++index) {
    const auto id = reuse_plan.fragments[index].fragment.fragment_id;
    reused = reused || !seen_ids.insert(id).second;
    anchor_uses += id == reuse_request.anchor_fragment_id ? 1U : 0U;
    const auto start = index > reuse_request.fragment_reuse_window
                           ? index - reuse_request.fragment_reuse_window
                           : 0U;
    for (std::size_t prior = start; prior < index; ++prior) {
      CHECK(id != reuse_plan.fragments[prior].fragment.fragment_id);
    }
  }
  CHECK(reused);
  CHECK(anchor_uses <= 2U);

  ar::ContinuationReservoir no_variation;
  for (std::uint64_t id = 1U; id <= 5U; ++id) {
    auto fragment = make_fragment(id, 4U, id * 10U);
    fragment.variation_mask = 0U;
    CHECK(no_variation.add_fragment(std::move(fragment)));
  }
  for (std::uint64_t from = 1U; from <= 5U; ++from) {
    for (std::uint64_t to = 2U; to <= 5U; ++to) {
      if (from != to) {
        CHECK(no_variation.add_edge(continuation_edge(from, to)));
      }
    }
  }
  CHECK(!ar::NonRepeatingContinuationPlanner{}
             .plan(no_variation, reuse_request)
             .complete);

  ar::ContinuationReservoir unchanged_layers;
  for (std::uint64_t id = 1U; id <= 5U; ++id) {
    auto fragment = make_fragment(id, 4U, id * 10U);
    fragment.audible_layer_mask = 1U;
    CHECK(unchanged_layers.add_fragment(std::move(fragment)));
  }
  for (std::uint64_t from = 1U; from <= 5U; ++from) {
    for (std::uint64_t to = 1U; to <= 5U; ++to) {
      if (from != to) {
        CHECK(unchanged_layers.add_edge(continuation_edge(from, to)));
      }
    }
  }
  CHECK(!ar::NonRepeatingContinuationPlanner{}
             .plan(unchanged_layers, reuse_request)
             .complete);

  ar::ContinuationReservoir current_anchor_reservoir;
  for (std::uint64_t id = 1U; id <= 5U; ++id) {
    auto fragment = make_fragment(id, 4U, id * 100U);
    if (id != 1U) {
      fragment.cache_available = false;
      fragment.generation_confidence = 0.0F;
      fragment.generation_latency_seconds = 10.0F;
      fragment.loopability_score = 0.0F;
    }
    CHECK(current_anchor_reservoir.add_fragment(std::move(fragment)));
  }
  for (std::uint64_t from = 1U; from <= 5U; ++from) {
    for (std::uint64_t to = 1U; to <= 5U; ++to) {
      if (from != to) {
        CHECK(current_anchor_reservoir.add_edge(continuation_edge(from, to)));
      }
    }
  }
  auto current_anchor_request = reuse_request;
  current_anchor_request.current_context.current_fragment_id = 1U;
  current_anchor_request.anchor_fragment_id = 1U;
  current_anchor_request.fragment_reuse_window = 1U;
  current_anchor_request.fingerprint_window = 1U;
  current_anchor_request.beam_width = 16U;
  current_anchor_request.max_expansions = 512U;
  current_anchor_request.device_budget.max_expansions = 512U;
  const auto current_anchor_plan = ar::NonRepeatingContinuationPlanner{}.plan(
      current_anchor_reservoir, current_anchor_request);
  CHECK(current_anchor_plan.complete);
  CHECK(std::count_if(current_anchor_plan.fragments.begin(),
                      current_anchor_plan.fragments.end(),
                      [](const ar::PlannedFragment &fragment) {
                        return fragment.fragment.fragment_id == 1U;
                      }) == 1);
}

void test_repetition_metrics_require_novelty() {
  std::vector<ar::PlannedFragment> diverse;
  std::vector<ar::PlannedFragment> repeated;
  std::vector<ar::PlannedFragment> repeated_arrangement;
  for (std::uint32_t bar = 0U; bar < 5U; ++bar) {
    diverse.push_back(planned_fragment(
        make_fragment(10U + bar, 1U, 100U + bar, 200U + bar), bar));
    repeated.push_back(
        planned_fragment(make_fragment(20U, 1U, 300U, 400U), bar));
    repeated_arrangement.push_back(planned_fragment(
        make_fragment(30U + bar, 1U, 500U + bar, 600U), bar));
  }

  const ar::RepetitionQualityEvaluator evaluator;
  const auto good = evaluator.evaluate(diverse);
  const auto bad = evaluator.evaluate(repeated);
  const auto stale_arrangement = evaluator.evaluate(repeated_arrangement);
  CHECK(good.accepted);
  CHECK(good.metrics.exact_fragment_repeat_rate == 0.0F);
  CHECK(good.metrics.full_arrangement_repeat_rate == 0.0F);
  CHECK(good.metrics.novelty_per_bar > 0.5F);
  CHECK(!bad.accepted);
  CHECK(bad.metrics.exact_fragment_repeat_rate >
        good.metrics.exact_fragment_repeat_rate);
  CHECK(bad.metrics.longest_identical_run_bars >= 4U);
  CHECK(bad.metrics.novelty_per_bar < good.metrics.novelty_per_bar);
  CHECK(!stale_arrangement.accepted);
  CHECK(stale_arrangement.metrics.full_arrangement_repeat_rate >
        good.metrics.full_arrangement_repeat_rate);
}

void test_progressive_buffer_survives_slow_and_failed_neural() {
  ar::ProgressivePcmBuffer buffer(tiny_progressive_config());
  CHECK(buffer.append_deterministic(constant_pcm(16U, 1U, 0.1F),
                                    ar::CandidateLevel::LEVEL_0, 1U));
  CHECK(buffer.activation_ready());
  const auto available_before_failure = buffer.available_frames();
  CHECK(!buffer.replace_uncommitted_with_neural(
      buffer.committed_end_frame(), {}, 90U));
  CHECK(buffer.available_frames() == available_before_failure);

  std::uint64_t candidate_id = 2U;
  for (std::uint32_t second = 0U; second < 32U; ++second) {
    if (buffer.needs_deterministic_recovery()) {
      CHECK(buffer.append_deterministic(constant_pcm(8U, 1U, 0.2F),
                                        ar::CandidateLevel::LEVEL_0,
                                        candidate_id++));
    }
    CHECK(buffer.available_frames() >= 8U);
    float sample = 0.0F;
    CHECK(buffer.read(&sample, 1U) == 1U);
    CHECK(std::isfinite(sample));
  }
  CHECK(buffer.diagnostics().neural_upgrades_applied == 0U);
}

void test_low_watermark_recovery_and_future_only_upgrade() {
  ar::ProgressivePcmBuffer recovery(tiny_progressive_config());
  CHECK(recovery.append_deterministic(constant_pcm(16U, 1U, 0.1F),
                                      ar::CandidateLevel::LEVEL_0, 7U));
  std::array<float, 9U> consumed{};
  CHECK(recovery.read(consumed.data(), consumed.size()) == consumed.size());
  CHECK(recovery.needs_deterministic_recovery());
  CHECK(recovery.diagnostics().low_watermark_events >= 1U);
  CHECK(!recovery.append_deterministic(constant_pcm(9U, 1U, 0.1F),
                                       ar::CandidateLevel::LEVEL_0, 7U));
  CHECK(recovery.append_deterministic(constant_pcm(9U, 1U, 0.2F),
                                      ar::CandidateLevel::LEVEL_0, 8U));
  CHECK(!recovery.needs_deterministic_recovery());

  ar::ProgressivePcmBuffer upgrade(tiny_progressive_config());
  CHECK(upgrade.append_deterministic(constant_pcm(16U, 1U, 0.1F),
                                     ar::CandidateLevel::LEVEL_0, 1U));
  const auto committed_end = upgrade.committed_end_frame();
  CHECK(committed_end == 2U);
  CHECK(!upgrade.replace_uncommitted_with_neural(
      committed_end - 1U, constant_pcm(4U, 1U, 0.12F), 2U));
  CHECK(upgrade.replace_uncommitted_with_neural(
      committed_end, constant_pcm(4U, 1U, 0.12F), 2U));

  std::array<float, 16U> rendered{};
  CHECK(upgrade.read(rendered.data(), rendered.size()) == rendered.size());
  CHECK(rendered[0] == 0.1F && rendered[1] == 0.1F);
  CHECK(std::abs(rendered[2] - 0.1F) < 1.0e-6F);
  CHECK(std::abs(rendered[5] - 0.1F) < 1.0e-6F);
  CHECK(rendered[6] == 0.1F);
  float maximum_upgrade_jump = 0.0F;
  for (std::size_t index = 1U; index < rendered.size(); ++index) {
    maximum_upgrade_jump = std::max(
        maximum_upgrade_jump,
        std::abs(rendered[index] - rendered[index - 1U]));
  }
  CHECK(maximum_upgrade_jump < 0.021F);
  const auto upgraded = upgrade.diagnostics();
  CHECK(upgraded.neural_upgrades_applied == 1U);
  CHECK(upgraded.active_candidate_level == ar::CandidateLevel::LEVEL_2);
}

void test_neural_upgrade_aligns_loudness_phase_and_boundaries() {
  ar::ProgressiveBufferConfig config;
  config.channels = 1U;
  config.frames_per_bar = 4U;
  config.committed_bars = 1U;
  config.guaranteed_bars = 2U;
  config.target_bars = 4U;
  config.planning_bars = 8U;
  config.low_watermark_bars = 2U;
  config.high_watermark_bars = 4U;
  ar::ProgressivePcmBuffer buffer(config);
  CHECK(buffer.append_deterministic(constant_pcm(32U, 1U, 0.8F),
                                    ar::CandidateLevel::LEVEL_0, 1U));
  CHECK(buffer.replace_uncommitted_with_neural(
      buffer.committed_end_frame(), constant_pcm(16U, 1U, -0.2F), 2U));

  std::array<float, 32U> rendered{};
  CHECK(buffer.read(rendered.data(), rendered.size()) == rendered.size());
  CHECK(std::abs(rendered[3] - 0.8F) < 1.0e-6F);
  CHECK(std::abs(rendered[4] - 0.8F) < 1.0e-6F);
  CHECK(std::abs(rendered[7] - 0.4F) < 1.0e-6F);
  CHECK(std::abs(rendered[19] - 0.8F) < 1.0e-6F);
  CHECK(std::abs(rendered[20] - 0.8F) < 1.0e-6F);
  float maximum_jump = 0.0F;
  for (std::size_t index = 1U; index < rendered.size(); ++index) {
    maximum_jump =
        std::max(maximum_jump, std::abs(rendered[index] - rendered[index - 1U]));
  }
  CHECK(maximum_jump < 0.21F);
}

void test_ninety_second_progressive_golden_scenario() {
  constexpr std::uint64_t natural_runway_seconds = 35U;
  constexpr std::uint64_t continuation_seconds = 55U;
  constexpr std::uint64_t neural_generation_seconds = 90U;
  static_assert(natural_runway_seconds + continuation_seconds ==
                neural_generation_seconds);

  ar::ProgressivePcmBuffer buffer(tiny_progressive_config());
  CHECK(buffer.append_deterministic(
      ramp_pcm(0U, 16U, continuation_seconds),
      ar::CandidateLevel::LEVEL_0, 1U));

  ar::TransitionCoordinator coordinator(8U);
  CHECK(coordinator.select_target(99U));
  CHECK(coordinator.begin_preparing());
  coordinator.set_natural_runway_remaining(natural_runway_seconds);
  coordinator.set_generation_eta(
      static_cast<double>(neural_generation_seconds));
  CHECK(coordinator.set_activation_boundaries(
      coordinator.generation(), 99U, {natural_runway_seconds}));
  ar::TransitionCandidate fallback;
  fallback.candidate_id = 1U;
  fallback.generation = coordinator.generation();
  fallback.target_track_id = 99U;
  fallback.level = ar::CandidateLevel::LEVEL_0;
  fallback.rendered_frames = 16U;
  fallback.quality_score = 1.0;
  fallback.technically_valid = true;
  fallback.repetition_valid = true;
  CHECK(coordinator.publish_candidate(fallback));
  CHECK(coordinator.arm(0U));
  CHECK(coordinator.wait_for_activation_boundary());
  for (std::uint64_t second = 0U; second < natural_runway_seconds; ++second) {
    CHECK(!coordinator.advance_playback(second));
  }
  CHECK(coordinator.advance_playback(natural_runway_seconds));

  std::vector<float> output;
  output.reserve(continuation_seconds);
  std::vector<ar::PlannedFragment> history;
  history.reserve(continuation_seconds);
  std::uint64_t candidate_id = 2U;
  for (std::uint64_t second = 0U; second < continuation_seconds; ++second) {
    if (buffer.needs_deterministic_recovery()) {
      const auto start = buffer.rendered_end_frame();
      CHECK(buffer.append_deterministic(
          ramp_pcm(start, 8U, continuation_seconds),
          ar::CandidateLevel::LEVEL_0, candidate_id++));
    }
    CHECK(buffer.available_frames() >= 8U);
    float sample = 0.0F;
    CHECK(buffer.read(&sample, 1U) == 1U);
    output.push_back(sample);
    history.push_back(planned_fragment(
        make_fragment(second + 1U, 1U, 10'000U + second,
                      20'000U + second),
        static_cast<std::uint32_t>(second)));
  }

  const auto repetition = ar::RepetitionQualityEvaluator{}.evaluate(history);
  CHECK(repetition.accepted);
  CHECK(repetition.metrics.exact_fragment_repeat_rate == 0.0F);
  CHECK(repetition.metrics.novelty_per_bar >= 0.25F);
  CHECK(output.size() == continuation_seconds);
  CHECK(std::is_sorted(output.begin(), output.end()));
  CHECK(output.front() < 0.1F);
  CHECK(output.back() >= 0.99F);
  float maximum_jump = 0.0F;
  for (std::size_t index = 1U; index < output.size(); ++index) {
    maximum_jump =
        std::max(maximum_jump, std::abs(output[index] - output[index - 1U]));
  }
  CHECK(maximum_jump < 0.02F);
  CHECK(buffer.diagnostics().neural_upgrades_applied == 0U);
  CHECK(coordinator.land());
  CHECK(coordinator.state() == ar::TransitionState::LANDED);
}

} // namespace

int main() {
  const std::vector<std::pair<const char *, std::function<void()>>> tests{
      {"automation_and_immutable_timelines",
       test_automation_and_immutable_timelines},
      {"anchor_selection_is_algorithmic_and_stable",
       test_anchor_selection_is_algorithmic_and_stable},
      {"bounded_beam_and_vocal_prohibition",
       test_bounded_beam_and_vocal_prohibition},
      {"renderer_is_deterministic_and_anchor_continuous",
       test_renderer_is_deterministic_and_anchor_continuous},
      {"rich_event_controls_affect_render",
       test_rich_event_controls_affect_render},
      {"loop_overlap_removes_raw_wrap_jump",
       test_loop_overlap_removes_raw_wrap_jump},
      {"quality_diagnostics_are_specific",
       test_quality_diagnostics_are_specific},
      {"quality_detects_second_derivative_and_boundaries",
       test_quality_detects_second_derivative_and_boundaries},
      {"nonfinite_metadata_and_generated_vocal_are_rejected",
       test_nonfinite_metadata_and_generated_vocal_are_rejected},
      {"fallback_cascade_is_guaranteed", test_fallback_cascade_is_guaranteed},
      {"cancellation_stops_search_and_render",
       test_cancellation_stops_search_and_render},
      {"spsc_ring_wrap_and_concurrency", test_spsc_ring_wrap_and_concurrency},
      {"lifecycle_invalidation_and_next_coalescing",
       test_lifecycle_invalidation_and_next_coalescing},
      {"capability_cache_and_recommendation_primitives",
       test_capability_cache_and_recommendation_primitives},
      {"stable_c_api", test_stable_c_api},
      {"deterministic_fuzz_properties", test_deterministic_fuzz_properties},
      {"progressive_state_machine_activation_rules",
       test_progressive_state_machine_activation_rules},
      {"transition_rejects_stale_and_short_candidates",
       test_transition_rejects_stale_and_short_candidates},
      {"preprocessed_track_rejects_bad_indexes_and_bounded_counts",
       test_preprocessed_track_rejects_bad_indexes_and_bounded_counts},
      {"anonymized_debug_report_has_no_media_payload",
       test_anonymized_debug_report_has_no_media_payload},
      {"continuation_excludes_recent_repetition",
       test_continuation_excludes_recent_repetition},
      {"repetition_metrics_require_novelty",
       test_repetition_metrics_require_novelty},
      {"progressive_buffer_survives_slow_and_failed_neural",
       test_progressive_buffer_survives_slow_and_failed_neural},
      {"low_watermark_recovery_and_future_only_upgrade",
       test_low_watermark_recovery_and_future_only_upgrade},
      {"neural_upgrade_aligns_loudness_phase_and_boundaries",
       test_neural_upgrade_aligns_loudness_phase_and_boundaries},
      {"ninety_second_progressive_golden_scenario",
       test_ninety_second_progressive_golden_scenario},
  };

  std::size_t failures = 0U;
  for (const auto &test : tests) {
    try {
      test.second();
      std::cout << "PASS " << test.first << '\n';
    } catch (const std::exception &error) {
      ++failures;
      std::cerr << "FAIL " << test.first << ": " << error.what() << '\n';
    } catch (...) {
      ++failures;
      std::cerr << "FAIL " << test.first << ": unknown exception\n";
    }
  }
  if (failures != 0U) {
    std::cerr << failures << " test(s) failed\n";
    return 1;
  }
  std::cout << tests.size() << " test(s) passed\n";
  return 0;
}
