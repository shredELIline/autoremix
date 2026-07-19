#include "autoremix/audio_core.hpp"
#include "autoremix/audio_core_c.h"

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
  ar::TimelineEvent event{ar::SourceKind::SourceA, 0U, 0U, 256U,
                          ar::AutomationLane(1.0F),
                          ar::AutomationLane(0.0F)};
  ar::BridgePlan plan;
  plan.timeline = ar::ProjectTimeline(
      {ar::StemTimeline(1U, ar::StemRole::Atmosphere, {event})});
  plan.anchor_stem_id = 1U;
  plan.total_frames = 256U;
  const auto render = ar::BridgeRenderer{}.render(plan, stems, 48'000U);
  const auto jump = std::abs(render.interleaved[128U * 2U] -
                             render.interleaved[127U * 2U]);
  CHECK(jump < 0.2F);
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
