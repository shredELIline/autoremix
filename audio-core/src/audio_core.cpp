#include "autoremix/audio_core.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <tuple>

namespace autoremix::audio {
namespace {

constexpr double kPi = 3.141592653589793238462643383279502884;
constexpr std::uint64_t kFnvOffset = 14695981039346656037ULL;
constexpr std::uint64_t kFnvPrime = 1099511628211ULL;

[[nodiscard]] float finite_clamp(float value, float low, float high) noexcept {
  if (!std::isfinite(value)) {
    return low;
  }
  return std::max(low, std::min(value, high));
}

[[nodiscard]] bool valid_sample_rate(std::uint32_t sample_rate) noexcept {
  return sample_rate >= 8'000U && sample_rate <= 384'000U;
}

[[nodiscard]] bool valid_bpm(double bpm) noexcept {
  return std::isfinite(bpm) && bpm >= 20.0 && bpm <= 400.0;
}

[[nodiscard]] bool is_vocal_role(StemRole role) noexcept {
  return role == StemRole::Vocal || role == StemRole::BackingVocal;
}

[[nodiscard]] bool audio_view_usable(const AudioView &view) noexcept {
  if (!view.valid()) {
    return false;
  }
  const auto sample_count =
      static_cast<std::size_t>(view.frames) * view.channels;
  float minimum = std::numeric_limits<float>::infinity();
  float maximum = -std::numeric_limits<float>::infinity();
  double square_sum = 0.0;
  std::size_t finite_count = 0U;
  for (std::size_t index = 0; index < sample_count; ++index) {
    const auto sample = view.interleaved[index];
    if (!std::isfinite(sample)) {
      continue;
    }
    minimum = std::min(minimum, sample);
    maximum = std::max(maximum, sample);
    square_sum += static_cast<double>(sample) * sample;
    ++finite_count;
  }
  if (finite_count < 2U || square_sum <= 1.0e-12) {
    return false;
  }
  return maximum - minimum > 1.0e-6F;
}

[[nodiscard]] std::uint64_t checked_duration(std::uint32_t sample_rate,
                                             double bpm_a, double bpm_b,
                                             std::uint64_t requested) {
  if (!valid_sample_rate(sample_rate)) {
    throw std::invalid_argument("sample rate must be in [8000, 384000]");
  }
  if (!valid_bpm(bpm_a) || !valid_bpm(bpm_b)) {
    throw std::invalid_argument("BPM must be finite and in [20, 400]");
  }

  const auto average_bpm = 0.5 * (bpm_a + bpm_b);
  const auto default_frames = static_cast<std::uint64_t>(
      std::llround(static_cast<double>(sample_rate) * 240.0 / average_bpm));
  const auto frames = requested == 0U ? default_frames : requested;
  const auto maximum = static_cast<std::uint64_t>(sample_rate) * 60ULL;
  if (frames == 0U || frames > maximum) {
    throw std::invalid_argument("bridge duration must be in (0, 60 seconds]");
  }
  return frames;
}

[[nodiscard]] bool source_available(const StemInput &stem,
                                    SourceKind source) noexcept {
  switch (source) {
  case SourceKind::SourceA:
    return stem.source_a.valid();
  case SourceKind::SourceB:
    return stem.source_b.valid();
  case SourceKind::Generated:
    return !is_vocal_role(stem.features.role);
  }
  return false;
}

[[nodiscard]] const AudioView *source_view(const StemInput &stem,
                                           SourceKind source) noexcept {
  switch (source) {
  case SourceKind::SourceA:
    return &stem.source_a;
  case SourceKind::SourceB:
    return &stem.source_b;
  case SourceKind::Generated:
    return &stem.generated;
  }
  return nullptr;
}

[[nodiscard]] const StemInput *find_stem(const std::vector<StemInput> &stems,
                                         std::uint32_t stem_id) noexcept {
  const auto iterator = std::find_if(stems.begin(), stems.end(),
                                     [stem_id](const StemInput &stem) {
                                       return stem.features.stem_id == stem_id;
                                     });
  return iterator == stems.end() ? nullptr : &*iterator;
}

void fnv_byte(std::uint64_t &hash, std::uint8_t byte) noexcept {
  hash ^= byte;
  hash *= kFnvPrime;
}

void fnv_u64(std::uint64_t &hash, std::uint64_t value) noexcept {
  for (unsigned shift = 0; shift < 64U; shift += 8U) {
    fnv_byte(hash, static_cast<std::uint8_t>((value >> shift) & 0xFFU));
  }
}

void fnv_quantized_float(std::uint64_t &hash, float value) noexcept {
  const auto quantized = static_cast<std::int64_t>(
      std::llround(static_cast<double>(value) * 1'000'000.0));
  fnv_u64(hash, static_cast<std::uint64_t>(quantized));
}

void fnv_string(std::uint64_t &hash, const std::string &value) noexcept {
  fnv_u64(hash, value.size());
  for (const auto character : value) {
    fnv_byte(hash, static_cast<std::uint8_t>(character));
  }
}

[[nodiscard]] std::vector<AnchorFeatures>
effective_features(const std::vector<StemInput> &stems) {
  std::vector<AnchorFeatures> result;
  result.reserve(stems.size());
  for (const auto &stem : stems) {
    auto features = stem.features;
    features.available_a = audio_view_usable(stem.source_a);
    features.available_b = audio_view_usable(stem.source_b);
    features.available_generated =
        !is_vocal_role(stem.features.role) && audio_view_usable(stem.generated);
    result.push_back(features);
  }
  return result;
}

struct PlanShape final {
  float switch_ratio{0.5F};
  float overlap_ratio{0.4F};
  AutomationCurve curve{AutomationCurve::SmoothStep};
  bool generated_textures{false};
  double score{0.0};
  std::uint64_t key{0};
};

[[nodiscard]] std::pair<std::uint64_t, std::uint64_t>
fade_window(std::uint64_t frames, float center, float overlap) noexcept {
  if (frames <= 1U) {
    return {0U, 0U};
  }
  const auto last = frames - 1U;
  const auto center_frame =
      static_cast<double>(last) * finite_clamp(center, 0.0F, 1.0F);
  const auto half_width =
      0.5 * static_cast<double>(last) * finite_clamp(overlap, 0.02F, 1.0F);
  const auto start = static_cast<std::uint64_t>(
      std::llround(std::max(0.0, center_frame - half_width)));
  const auto end = static_cast<std::uint64_t>(std::llround(
      std::min(static_cast<double>(last), center_frame + half_width)));
  return {std::min(start, last),
          std::max(std::min(end, last), std::min(start, last))};
}

[[nodiscard]] AutomationLane fade_out_lane(std::uint64_t frames,
                                           std::uint64_t start,
                                           std::uint64_t end,
                                           AutomationCurve curve) {
  const auto last = frames - 1U;
  std::vector<AutomationPoint> points{{0U, 1.0F}};
  if (start > 0U) {
    points.push_back({start, 1.0F});
  }
  if (end > start) {
    points.push_back({end, 0.0F});
  } else {
    points.back().value = 0.0F;
  }
  if (end < last) {
    points.push_back({last, 0.0F});
  }
  return AutomationLane(std::move(points), 1.0F, curve);
}

[[nodiscard]] AutomationLane fade_in_lane(std::uint64_t frames,
                                          std::uint64_t start,
                                          std::uint64_t end,
                                          AutomationCurve curve) {
  const auto last = frames - 1U;
  std::vector<AutomationPoint> points{{0U, 0.0F}};
  if (start > 0U) {
    points.push_back({start, 0.0F});
  }
  if (end > start) {
    points.push_back({end, 1.0F});
  } else {
    points.back().value = 1.0F;
  }
  if (end < last) {
    points.push_back({last, 1.0F});
  }
  return AutomationLane(std::move(points), 0.0F, curve);
}

[[nodiscard]] BridgePlan make_plan(const std::vector<StemInput> &stems,
                                   const AnchorChoice &anchor,
                                   std::uint64_t frames,
                                   const PlanShape &shape) {
  std::vector<StemTimeline> timelines;
  timelines.reserve(stems.size());
  auto hash = kFnvOffset;
  fnv_u64(hash, frames);
  fnv_u64(hash, anchor.stem_id);
  fnv_quantized_float(hash, shape.switch_ratio);
  fnv_quantized_float(hash, shape.overlap_ratio);
  fnv_u64(hash, static_cast<std::uint64_t>(shape.curve));
  fnv_u64(hash, shape.generated_textures ? 1U : 0U);

  for (const auto &stem : stems) {
    const auto has_a = stem.source_a.valid();
    const auto has_b = stem.source_b.valid();
    const auto has_generated = stem.generated.valid();
    const auto is_anchor = stem.features.stem_id == anchor.stem_id;
    auto center = shape.switch_ratio;
    auto overlap = shape.overlap_ratio;

    if (!is_anchor) {
      switch (stem.features.role) {
      case StemRole::Drums:
      case StemRole::Percussion:
        center = finite_clamp(center - 0.08F, 0.15F, 0.85F);
        overlap = finite_clamp(overlap * 0.65F, 0.05F, 1.0F);
        break;
      case StemRole::Bass:
        center = finite_clamp(center + 0.04F, 0.15F, 0.85F);
        break;
      case StemRole::Vocal:
      case StemRole::BackingVocal:
      case StemRole::Lead:
        overlap = finite_clamp(overlap * 0.75F, 0.05F, 1.0F);
        break;
      case StemRole::Harmony:
      case StemRole::Guitar:
      case StemRole::Keys:
      case StemRole::Synth:
      case StemRole::Strings:
      case StemRole::Atmosphere:
      case StemRole::Effects:
      case StemRole::Other:
        break;
      }
    } else {
      overlap = std::max(overlap, 0.45F);
    }

    const auto [fade_start, fade_end] = fade_window(frames, center, overlap);
    std::vector<TimelineEvent> events;
    events.reserve(3);

    if (has_a && has_b) {
      events.push_back(TimelineEvent{
          SourceKind::SourceA, 0U, 0U, frames,
          fade_out_lane(frames, fade_start, fade_end, shape.curve),
          AutomationLane(0.0F)});
      events.push_back(
          TimelineEvent{SourceKind::SourceB, 0U, 0U, frames,
                        fade_in_lane(frames, fade_start, fade_end, shape.curve),
                        AutomationLane(0.0F)});
    } else if (has_a) {
      events.push_back(TimelineEvent{SourceKind::SourceA, 0U, 0U, frames,
                                     AutomationLane(1.0F),
                                     AutomationLane(0.0F)});
    } else if (has_b) {
      events.push_back(TimelineEvent{SourceKind::SourceB, 0U, 0U, frames,
                                     AutomationLane(1.0F),
                                     AutomationLane(0.0F)});
    } else if ((has_generated || shape.generated_textures) &&
               !is_vocal_role(stem.features.role)) {
      events.push_back(TimelineEvent{SourceKind::Generated, 0U, 0U, frames,
                                     AutomationLane(0.08F),
                                     AutomationLane(0.0F)});
      events.back().generation_mode = has_generated ? GenerationMode::Supplied
                                                    : GenerationMode::Procedural;
    }

    if (shape.generated_textures && (has_a || has_b) &&
        !is_vocal_role(stem.features.role)) {
      events.push_back(TimelineEvent{SourceKind::Generated, 0U, 0U, frames,
                                     AutomationLane(0.02F),
                                     AutomationLane(0.0F)});
      events.back().generation_mode = GenerationMode::Procedural;
      events.back().width = AutomationLane(1.25F);
      events.back().lowpass_hz = AutomationLane(12'000.0F);
    }

    if (!events.empty()) {
      fnv_u64(hash, stem.features.stem_id);
      fnv_u64(hash, static_cast<std::uint64_t>(stem.features.role));
      timelines.emplace_back(stem.features.stem_id, stem.features.role,
                             std::move(events));
    }
  }

  BridgePlan plan;
  plan.timeline = ProjectTimeline(std::move(timelines));
  plan.anchor_stem_id = anchor.stem_id;
  plan.total_frames = frames;
  plan.objective = shape.score + anchor.score;
  plan.stable_id = hash;
  return plan;
}

[[nodiscard]] float raw_interpolated_sample(const AudioView &view,
                                            double source_frame,
                                            std::uint32_t channel,
                                            bool &nonfinite) noexcept {
  if (!view.valid() || !std::isfinite(source_frame)) {
    nonfinite = nonfinite || !std::isfinite(source_frame);
    return 0.0F;
  }
  const auto last = view.frames - 1U;
  const auto position = std::max(
      0.0, std::min(source_frame, static_cast<double>(last)));
  const auto first = static_cast<std::uint64_t>(position);
  const auto second = std::min(first + 1U, last);
  const auto fraction =
      static_cast<float>(position - static_cast<double>(first));
  auto a = view.sample(first, std::min(channel, view.channels - 1U));
  auto b = view.sample(second, std::min(channel, view.channels - 1U));
  if (!std::isfinite(a)) {
    nonfinite = true;
    a = 0.0F;
  }
  if (!std::isfinite(b)) {
    nonfinite = true;
    b = 0.0F;
  }
  return a + (b - a) * fraction;
}

[[nodiscard]] float looped_interpolated_sample(const AudioView &view,
                                               double source_frame,
                                               std::uint32_t channel,
                                               bool &nonfinite) noexcept {
  if (!view.valid() || !std::isfinite(source_frame)) {
    nonfinite = nonfinite || !std::isfinite(source_frame);
    return 0.0F;
  }
  const auto position = std::max(0.0, source_frame);
  const auto crossfade_frames =
      std::min<std::uint64_t>(64U, view.frames / 4U);
  if (crossfade_frames == 0U) {
    const auto wrapped = std::fmod(position, static_cast<double>(view.frames));
    return raw_interpolated_sample(view, wrapped, channel, nonfinite);
  }

  const auto frame_count = static_cast<double>(view.frames);
  const auto crossfade = static_cast<double>(crossfade_frames);
  double tail_position = 0.0;
  double head_position = 0.0;
  double mix = -1.0;
  if (position < frame_count) {
    if (position < frame_count - crossfade) {
      return raw_interpolated_sample(view, position, channel, nonfinite);
    }
    tail_position = position;
    head_position = position - (frame_count - crossfade);
    mix = head_position / crossfade;
  } else {
    const auto period = frame_count - crossfade;
    auto phase = std::fmod(position - frame_count, period);
    if (phase < 0.0) {
      phase += period;
    }
    const auto straight_frames = frame_count - 2.0 * crossfade;
    if (phase < straight_frames) {
      return raw_interpolated_sample(view, crossfade + phase, channel,
                                     nonfinite);
    }
    const auto local = phase - straight_frames;
    tail_position = frame_count - crossfade + local;
    head_position = local;
    mix = local / crossfade;
  }
  mix = mix * mix * (3.0 - 2.0 * mix);
  const auto tail =
      raw_interpolated_sample(view, tail_position, channel, nonfinite);
  const auto head =
      raw_interpolated_sample(view, head_position, channel, nonfinite);
  return static_cast<float>(tail * (1.0 - mix) + head * mix);
}

[[nodiscard]] float generated_sample(StemRole role, std::uint32_t stem_id,
                                     std::uint64_t frame,
                                     std::uint32_t sample_rate) noexcept {
  if (is_vocal_role(role) || sample_rate == 0U) {
    return 0.0F;
  }
  const auto time = static_cast<double>(frame) / sample_rate;
  switch (role) {
  case StemRole::Drums:
  case StemRole::Percussion: {
    auto value =
        frame ^ (static_cast<std::uint64_t>(stem_id) * 0x9E3779B97F4A7C15ULL);
    value ^= value >> 30U;
    value *= 0xBF58476D1CE4E5B9ULL;
    value ^= value >> 27U;
    const auto noise = static_cast<float>(
        static_cast<double>(value >> 40U) / 8388607.5 - 1.0);
    const auto phase = std::fmod(time * 4.0, 1.0);
    return noise * static_cast<float>(std::exp(-18.0 * phase));
  }
  case StemRole::Bass:
    return static_cast<float>(std::sin(2.0 * kPi * 55.0 * time));
  case StemRole::Harmony:
  case StemRole::Guitar:
  case StemRole::Keys:
  case StemRole::Synth:
  case StemRole::Strings:
  case StemRole::Lead:
  case StemRole::Atmosphere:
    return static_cast<float>(0.6 * std::sin(2.0 * kPi * 220.0 * time) +
                              0.4 * std::sin(2.0 * kPi * 330.0 * time));
  case StemRole::Other:
  case StemRole::Effects:
    return static_cast<float>(std::sin(2.0 * kPi * 440.0 * time));
  case StemRole::Vocal:
  case StemRole::BackingVocal:
    break;
  }
  return 0.0F;
}

struct StereoSample final {
  float left{0.0F};
  float right{0.0F};
};

struct EventRenderState final {
  double source_position{0.0};
  float filter_left{0.0F};
  float filter_right{0.0F};
  bool filter_initialized{false};
};

[[nodiscard]] StereoSample sample_view_stereo(const AudioView &view,
                                              double source_position,
                                              bool &nonfinite) noexcept {
  const auto left =
      looped_interpolated_sample(view, source_position, 0U, nonfinite);
  const auto right = view.channels == 1U
                         ? left
                         : looped_interpolated_sample(view, source_position, 1U,
                                                       nonfinite);
  return {left, right};
}

[[nodiscard]] StereoSample generated_stereo(const StemInput &stem,
                                            double source_position,
                                            std::uint32_t sample_rate,
                                            GenerationMode mode,
                                            bool &nonfinite) noexcept {
  if (is_vocal_role(stem.features.role)) {
    return {};
  }
  if (mode != GenerationMode::Procedural && stem.generated.valid()) {
    return sample_view_stereo(stem.generated, source_position, nonfinite);
  }
  const AudioView *derived = nullptr;
  if (stem.source_a.valid()) {
    derived = &stem.source_a;
  } else if (stem.source_b.valid()) {
    derived = &stem.source_b;
  }
  if (derived != nullptr) {
    const auto granular_position = source_position * 0.997;
    return sample_view_stereo(*derived, granular_position, nonfinite);
  }
  const auto bounded = static_cast<std::uint64_t>(std::max(
      0.0, std::min(source_position,
                    static_cast<double>(std::numeric_limits<std::uint64_t>::max()))));
  const auto sample = generated_sample(stem.features.role, stem.features.stem_id,
                                       bounded, sample_rate);
  return {sample, sample};
}

[[nodiscard]] StereoSample render_event_stereo(
    const TimelineEvent &event, const StemInput &stem, std::uint64_t local,
    std::uint32_t output_sample_rate, EventRenderState &state,
    bool &nonfinite) noexcept {
  StereoSample sample;
  const auto *view = source_view(stem, event.source);
  if (event.source == SourceKind::Generated) {
    sample = generated_stereo(stem, state.source_position, output_sample_rate,
                              event.generation_mode, nonfinite);
  } else if (view != nullptr && view->valid()) {
    sample = sample_view_stereo(*view, state.source_position, nonfinite);
  }

  auto morph = finite_clamp(event.morph.value_at(local), 0.0F, 1.0F);
  if (is_vocal_role(stem.features.role)) {
    morph = 0.0F;
  }
  if (morph > 0.0F && event.source != SourceKind::Generated) {
    const auto generated = generated_stereo(
        stem, state.source_position, output_sample_rate, GenerationMode::Morph,
        nonfinite);
    sample.left = sample.left * (1.0F - morph) + generated.left * morph;
    sample.right = sample.right * (1.0F - morph) + generated.right * morph;
  }

  const auto width = finite_clamp(event.width.value_at(local), 0.0F, 2.0F);
  const auto mid = 0.5F * (sample.left + sample.right);
  const auto side = 0.5F * (sample.left - sample.right) * width;
  sample.left = mid + side;
  sample.right = mid - side;

  const auto cutoff = finite_clamp(event.lowpass_hz.value_at(local), 20.0F,
                                   0.49F * static_cast<float>(output_sample_rate));
  const auto alpha = static_cast<float>(
      1.0 - std::exp(-2.0 * kPi * cutoff / output_sample_rate));
  if (!state.filter_initialized) {
    state.filter_left = sample.left;
    state.filter_right = sample.right;
    state.filter_initialized = true;
  } else {
    state.filter_left += alpha * (sample.left - state.filter_left);
    state.filter_right += alpha * (sample.right - state.filter_right);
  }
  sample.left = state.filter_left;
  sample.right = state.filter_right;

  const auto tempo =
      finite_clamp(event.tempo_ratio.value_at(local), 0.25F, 4.0F);
  const auto pitch = finite_clamp(event.pitch_semitones.value_at(local),
                                  -24.0F, 24.0F);
  const auto pitch_ratio = std::pow(2.0, static_cast<double>(pitch) / 12.0);
  auto source_rate = output_sample_rate;
  if (event.source == SourceKind::Generated &&
      event.generation_mode == GenerationMode::Procedural) {
    if (stem.source_a.valid()) {
      source_rate = stem.source_a.sample_rate;
    } else if (stem.source_b.valid()) {
      source_rate = stem.source_b.sample_rate;
    }
  } else if (view != nullptr && view->valid()) {
    source_rate = view->sample_rate;
  }
  state.source_position += static_cast<double>(source_rate) /
                           output_sample_rate * tempo * pitch_ratio;
  return sample;
}

[[nodiscard]] bool
diagnostics_equivalent(const QualityDiagnostics &diagnostics) noexcept {
  return diagnostics.accepted();
}

[[nodiscard]] bool valid_role(StemRole role) noexcept {
  switch (role) {
  case StemRole::Vocal:
  case StemRole::Drums:
  case StemRole::Bass:
  case StemRole::Harmony:
  case StemRole::Other:
  case StemRole::BackingVocal:
  case StemRole::Percussion:
  case StemRole::Guitar:
  case StemRole::Keys:
  case StemRole::Synth:
  case StemRole::Strings:
  case StemRole::Lead:
  case StemRole::Atmosphere:
  case StemRole::Effects:
    return true;
  }
  return false;
}

void validate_bridge_request(const BridgeRequest &request) {
  if (!std::isfinite(request.harmonic_distance) ||
      request.harmonic_distance < 0.0 || request.harmonic_distance > 12.0 ||
      !std::isfinite(request.stem_conflict_score) ||
      request.stem_conflict_score < 0.0 || request.stem_conflict_score > 1.0) {
    throw std::invalid_argument("bridge conflict metadata is invalid");
  }
  const auto &capability = request.capability;
  if (!std::isfinite(capability.render_realtime_factor) ||
      capability.render_realtime_factor <= 0.0 ||
      capability.memory_budget_bytes == 0U || capability.logical_cores == 0U ||
      !std::isfinite(capability.thermal_headroom) ||
      capability.thermal_headroom < 0.0F || capability.thermal_headroom > 1.0F ||
      !std::isfinite(capability.battery_fraction) ||
      capability.battery_fraction < 0.0F || capability.battery_fraction > 1.0F) {
    throw std::invalid_argument("capability measurements are invalid");
  }
  if ((request.has_previous_output &&
       (!std::isfinite(request.previous_output[0]) ||
        !std::isfinite(request.previous_output[1]))) ||
      (request.has_next_output &&
       (!std::isfinite(request.next_output[0]) ||
        !std::isfinite(request.next_output[1])))) {
    throw std::invalid_argument("boundary samples must be finite");
  }
  for (const auto &stem : request.stems) {
    const auto &feature = stem.features;
    if (!valid_role(feature.role) || !std::isfinite(feature.confidence) ||
        !std::isfinite(feature.continuity) ||
        !std::isfinite(feature.transient_stability) ||
        !std::isfinite(feature.harmonic_stability) ||
        !std::isfinite(feature.foreground)) {
      throw std::invalid_argument("stem metadata is invalid");
    }
    if (is_vocal_role(feature.role) && stem.generated.valid()) {
      throw std::invalid_argument("generated vocal audio is prohibited");
    }
  }
}

[[nodiscard]] bool has_usable_audio(const std::vector<StemInput> &stems) noexcept {
  return std::any_of(stems.begin(), stems.end(), [](const StemInput &stem) {
    return audio_view_usable(stem.source_a) ||
           audio_view_usable(stem.source_b) ||
           (!is_vocal_role(stem.features.role) &&
            audio_view_usable(stem.generated));
  });
}

void normalize_emergency(RenderResult &render) {
  if (render.channels == 0U || render.interleaved.empty()) {
    return;
  }
  std::vector<double> sum(render.channels, 0.0);
  const auto frames = render.interleaved.size() / render.channels;
  for (std::size_t frame = 0U; frame < frames; ++frame) {
    for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
      sum[channel] += render.interleaved[frame * render.channels + channel];
    }
  }
  float peak = 0.0F;
  double square_sum = 0.0;
  for (std::size_t frame = 0U; frame < frames; ++frame) {
    for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
      auto &sample = render.interleaved[frame * render.channels + channel];
      sample -= static_cast<float>(
          sum[channel] /
          static_cast<double>(std::max<std::size_t>(1U, frames)));
      peak = std::max(peak, std::abs(sample));
    }
  }
  const auto scale = peak > 0.95F ? 0.95F / peak : 1.0F;
  peak = 0.0F;
  for (auto &sample : render.interleaved) {
    sample *= scale;
    peak = std::max(peak, std::abs(sample));
    square_sum += static_cast<double>(sample) * sample;
  }
  render.metrics.preclip_peak = peak;
  render.metrics.anchor_rms = static_cast<float>(std::sqrt(
      square_sum / static_cast<double>(
                       std::max<std::size_t>(1U, render.interleaved.size()))));
}

} // namespace

AutomationLane::AutomationLane(float constant) : default_value_(constant) {
  if (!std::isfinite(constant)) {
    throw std::invalid_argument("automation value must be finite");
  }
}

AutomationLane::AutomationLane(std::vector<AutomationPoint> points,
                               float default_value, AutomationCurve curve)
    : points_(std::move(points)), default_value_(default_value), curve_(curve) {
  if (!std::isfinite(default_value_)) {
    throw std::invalid_argument("automation default must be finite");
  }
  std::sort(points_.begin(), points_.end(),
            [](const auto &left, const auto &right) {
              return left.frame < right.frame;
            });
  for (std::size_t index = 0; index < points_.size(); ++index) {
    if (!std::isfinite(points_[index].value)) {
      throw std::invalid_argument("automation point must be finite");
    }
    if (index > 0U && points_[index - 1U].frame == points_[index].frame) {
      throw std::invalid_argument("automation points must have unique frames");
    }
  }
}

float AutomationLane::value_at(std::uint64_t frame) const noexcept {
  if (points_.empty()) {
    return default_value_;
  }
  const auto upper =
      std::upper_bound(points_.begin(), points_.end(), frame,
                       [](std::uint64_t target, const AutomationPoint &point) {
                         return target < point.frame;
                       });
  if (upper == points_.begin()) {
    return default_value_;
  }
  if (upper == points_.end()) {
    return points_.back().value;
  }
  const auto &right = *upper;
  const auto &left = *(upper - 1);
  if (curve_ == AutomationCurve::Hold || right.frame == left.frame) {
    return left.value;
  }
  auto position = static_cast<float>(frame - left.frame) /
                  static_cast<float>(right.frame - left.frame);
  if (curve_ == AutomationCurve::SmoothStep) {
    position = position * position * (3.0F - 2.0F * position);
  }
  return left.value + (right.value - left.value) * position;
}

StemTimeline::StemTimeline(std::uint32_t stem_id, StemRole role,
                           std::vector<TimelineEvent> events)
    : stem_id_(stem_id), role_(role), events_(std::move(events)) {
  for (const auto &event : events_) {
    if (event.duration == 0U ||
        event.timeline_start >
            std::numeric_limits<std::uint64_t>::max() - event.duration) {
      throw std::invalid_argument("timeline event duration is invalid");
    }
    if (is_vocal_role(role_) && event.source == SourceKind::Generated) {
      throw std::invalid_argument("generated vocal audio is prohibited");
    }
  }
  std::stable_sort(events_.begin(), events_.end(),
                   [](const auto &left, const auto &right) {
                     return std::tie(left.timeline_start, left.source) <
                            std::tie(right.timeline_start, right.source);
                   });
}

bool StemTimeline::has_generated_audio() const noexcept {
  return std::any_of(events_.begin(), events_.end(),
                     [](const TimelineEvent &event) {
                       return event.source == SourceKind::Generated;
                     });
}

float StemTimeline::summed_gain_at(std::uint64_t frame) const noexcept {
  float result = 0.0F;
  for (const auto &event : events_) {
    if (frame >= event.timeline_start &&
        frame - event.timeline_start < event.duration) {
      result +=
          std::max(0.0F, event.gain.value_at(frame - event.timeline_start));
    }
  }
  return result;
}

ProjectTimeline::ProjectTimeline(std::vector<StemTimeline> stems)
    : stems_(std::move(stems)) {
  std::sort(stems_.begin(), stems_.end(),
            [](const auto &left, const auto &right) {
              return left.stem_id() < right.stem_id();
            });
  for (std::size_t index = 1U; index < stems_.size(); ++index) {
    if (stems_[index - 1U].stem_id() == stems_[index].stem_id()) {
      throw std::invalid_argument("project timeline has duplicate stem IDs");
    }
  }
}

const StemTimeline *
ProjectTimeline::find(std::uint32_t stem_id) const noexcept {
  const auto iterator =
      std::lower_bound(stems_.begin(), stems_.end(), stem_id,
                       [](const StemTimeline &stem, std::uint32_t id) {
                         return stem.stem_id() < id;
                       });
  return iterator != stems_.end() && iterator->stem_id() == stem_id ? &*iterator
                                                                    : nullptr;
}

bool AudioView::valid() const noexcept {
  return interleaved != nullptr && frames > 0U && channels > 0U &&
         channels <= 32U && valid_sample_rate(sample_rate) &&
         frames <= std::numeric_limits<std::size_t>::max() / channels;
}

float AudioView::sample(std::uint64_t frame,
                        std::uint32_t channel) const noexcept {
  if (!valid() || frame >= frames || channel >= channels) {
    return 0.0F;
  }
  const auto index = static_cast<std::size_t>(frame * channels + channel);
  return interleaved[index];
}

std::optional<AnchorChoice>
AnchorSelector::select(const std::vector<AnchorFeatures> &features) noexcept {
  std::optional<AnchorChoice> best;
  for (const auto &feature : features) {
    if (!feature.available_a && !feature.available_b &&
        !feature.available_generated) {
      continue;
    }
    const auto confidence = finite_clamp(feature.confidence, 0.0F, 1.0F);
    const auto continuity = finite_clamp(feature.continuity, 0.0F, 1.0F);
    const auto transient =
        finite_clamp(feature.transient_stability, 0.0F, 1.0F);
    const auto harmony = finite_clamp(feature.harmonic_stability, 0.0F, 1.0F);
    const auto foreground = finite_clamp(feature.foreground, 0.0F, 1.0F);
    const auto dual_source = feature.available_a && feature.available_b
                                 ? 0.22
                                 : (feature.available_generated ? -0.18 : -0.12);
    const auto vocal_penalty =
        is_vocal_role(feature.role) ? 0.08 * foreground : 0.0;
    const auto score = 0.25 * confidence + 0.35 * continuity +
                       0.16 * transient + 0.16 * harmony + dual_source -
                       vocal_penalty;
    const auto choice = AnchorChoice{feature.stem_id, feature.role, score};
    if (!best || choice.score > best->score + 1.0e-12 ||
        (std::abs(choice.score - best->score) <= 1.0e-12 &&
         choice.stem_id < best->stem_id)) {
      best = choice;
    }
  }
  return best;
}

bool CancellationToken::probe_callback(void *user_data) noexcept {
  return user_data != nullptr &&
         static_cast<CancellationToken *>(user_data)->cancelled();
}

CancellationProbe CancellationToken::probe() noexcept {
  return CancellationProbe{&CancellationToken::probe_callback, this};
}

SearchResult BridgePlanner::search(const std::vector<StemInput> &stems,
                                   const SearchContext &context) const {
  SearchResult result;
  const auto frames = checked_duration(context.sample_rate, context.bpm_a,
                                       context.bpm_b, context.requested_frames);
  const auto anchor = AnchorSelector::select(effective_features(stems));
  if (!anchor || context.limits.beam_width == 0U ||
      context.limits.max_candidates == 0U ||
      context.limits.max_expansions == 0U) {
    return result;
  }

  struct BeamState final {
    PlanShape shape{};
    std::uint8_t depth{0};
  };

  std::vector<BeamState> beam{{PlanShape{}, 0U}};
  const auto rhythm_error = std::abs(std::log2(context.bpm_b / context.bpm_a));
  const auto harmony_penalty = std::max(0.0, context.harmonic_distance) * 0.04;

  for (std::uint8_t depth = 0U; depth < 4U && !beam.empty(); ++depth) {
    std::vector<BeamState> expanded;
    for (const auto &parent : beam) {
      const auto append = [&](PlanShape child) {
        if (result.expansions >= context.limits.max_expansions) {
          return;
        }
        ++result.expansions;
        child.key = parent.shape.key * 131U + child.key + 1U;
        expanded.push_back(
            BeamState{child, static_cast<std::uint8_t>(depth + 1U)});
      };

      if (context.cancellation.cancelled()) {
        result.cancelled = true;
        return result;
      }
      if (result.expansions >= context.limits.max_expansions) {
        break;
      }

      if (depth == 0U) {
        for (const auto ratio : std::array<float, 3>{0.40F, 0.50F, 0.60F}) {
          auto child = parent.shape;
          child.switch_ratio = ratio;
          child.score += 0.20 - std::abs(ratio - 0.50F) - 0.15 * rhythm_error;
          child.key = static_cast<std::uint64_t>(ratio * 100.0F);
          append(child);
        }
      } else if (depth == 1U) {
        for (const auto curve : std::array<AutomationCurve, 2>{
                 AutomationCurve::Linear, AutomationCurve::SmoothStep}) {
          auto child = parent.shape;
          child.curve = curve;
          child.score += curve == AutomationCurve::SmoothStep ? 0.12 : 0.08;
          child.key = static_cast<std::uint64_t>(curve);
          append(child);
        }
      } else if (depth == 2U) {
        const auto allow_generated = context.tier != QualityTier::Economy;
        const auto option_count = allow_generated ? 2U : 1U;
        for (std::size_t option = 0; option < option_count; ++option) {
          auto child = parent.shape;
          child.generated_textures = option == 1U;
          child.score +=
              child.generated_textures ? 0.04 - harmony_penalty : 0.06;
          child.key = option;
          append(child);
        }
      } else {
        for (const auto overlap : std::array<float, 3>{0.25F, 0.42F, 0.60F}) {
          auto child = parent.shape;
          child.overlap_ratio = overlap;
          const auto target = finite_clamp(
              static_cast<float>(0.32 + 0.30 * rhythm_error), 0.25F, 0.60F);
          child.score += 0.18 - std::abs(overlap - target) - harmony_penalty;
          child.key = static_cast<std::uint64_t>(overlap * 100.0F);
          append(child);
        }
      }
    }

    std::sort(expanded.begin(), expanded.end(),
              [](const auto &left, const auto &right) {
                if (std::abs(left.shape.score - right.shape.score) > 1.0e-12) {
                  return left.shape.score > right.shape.score;
                }
                return left.shape.key < right.shape.key;
              });
    if (expanded.size() > context.limits.beam_width) {
      expanded.resize(context.limits.beam_width);
    }
    beam = std::move(expanded);
    if (result.expansions >= context.limits.max_expansions && depth < 3U) {
      break;
    }
  }

  std::sort(beam.begin(), beam.end(), [](const auto &left, const auto &right) {
    if (std::abs(left.shape.score - right.shape.score) > 1.0e-12) {
      return left.shape.score > right.shape.score;
    }
    return left.shape.key < right.shape.key;
  });
  const auto candidate_count =
      std::min(context.limits.max_candidates, beam.size());
  result.candidates.reserve(candidate_count);
  for (std::size_t index = 0; index < candidate_count; ++index) {
    if (context.cancellation.cancelled()) {
      result.cancelled = true;
      result.candidates.clear();
      return result;
    }
    result.candidates.push_back(
        make_plan(stems, *anchor, frames, beam[index].shape));
  }
  return result;
}

BridgePlan BridgePlanner::safe_crossfade(const std::vector<StemInput> &stems,
                                         std::uint32_t sample_rate,
                                         std::uint64_t frames) const {
  (void)sample_rate;
  const auto anchor = AnchorSelector::select(effective_features(stems));
  if (!anchor || frames == 0U) {
    return BridgePlan{};
  }
  PlanShape shape;
  shape.switch_ratio = 0.5F;
  shape.overlap_ratio = 1.0F;
  shape.curve = AutomationCurve::SmoothStep;
  shape.generated_textures = false;
  shape.score = -1.0;
  shape.key = 0x53414645ULL;
  return make_plan(stems, *anchor, frames, shape);
}

BridgePlan BridgePlanner::anchor_only(const std::vector<StemInput> &stems,
                                      std::uint32_t sample_rate,
                                      std::uint64_t frames) const {
  (void)sample_rate;
  const auto anchor = AnchorSelector::select(effective_features(stems));
  if (!anchor || frames == 0U) {
    return BridgePlan{};
  }
  const auto *input = find_stem(stems, anchor->stem_id);
  if (input == nullptr) {
    return BridgePlan{};
  }
  std::vector<TimelineEvent> events;
  const auto has_a = audio_view_usable(input->source_a);
  const auto has_b = audio_view_usable(input->source_b);
  const auto has_generated = audio_view_usable(input->generated);
  if (has_a && has_b) {
    const auto [start, end] = fade_window(frames, 0.5F, 1.0F);
    events.push_back(TimelineEvent{
        SourceKind::SourceA, 0U, 0U, frames,
        fade_out_lane(frames, start, end, AutomationCurve::SmoothStep),
        AutomationLane(0.0F)});
    events.push_back(TimelineEvent{
        SourceKind::SourceB, 0U, 0U, frames,
        fade_in_lane(frames, start, end, AutomationCurve::SmoothStep),
        AutomationLane(0.0F)});
  } else if (has_a || has_b) {
    events.push_back(TimelineEvent{
        has_a ? SourceKind::SourceA : SourceKind::SourceB, 0U, 0U, frames,
        AutomationLane(1.0F), AutomationLane(0.0F)});
  } else if (has_generated && !is_vocal_role(input->features.role)) {
    events.push_back(TimelineEvent{SourceKind::Generated, 0U, 0U, frames,
                                   AutomationLane(1.0F),
                                   AutomationLane(0.0F)});
    events.back().generation_mode = GenerationMode::Supplied;
  } else {
    return BridgePlan{};
  }
  auto hash = kFnvOffset;
  fnv_u64(hash, frames);
  fnv_u64(hash, anchor->stem_id);
  fnv_u64(hash, 0x414E43484F52ULL);
  BridgePlan plan;
  plan.timeline = ProjectTimeline(
      {StemTimeline(anchor->stem_id, anchor->role, std::move(events))});
  plan.anchor_stem_id = anchor->stem_id;
  plan.total_frames = frames;
  plan.objective = -2.0;
  plan.stable_id = hash;
  return plan;
}

RenderResult BridgeRenderer::render(const BridgePlan &plan,
                                    const std::vector<StemInput> &stems,
                                    std::uint32_t sample_rate,
                                    CancellationProbe cancellation) const {
  if (!valid_sample_rate(sample_rate)) {
    throw std::invalid_argument("invalid render sample rate");
  }
  if (plan.total_frames == 0U) {
    RenderResult empty;
    empty.sample_rate = sample_rate;
    return empty;
  }
  if (plan.total_frames > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::length_error("render output is too large");
  }

  RenderResult result;
  result.sample_rate = sample_rate;
  result.channels = 2U;
  result.interleaved.assign(static_cast<std::size_t>(plan.total_frames) * 2U,
                            0.0F);
  result.metrics.minimum_anchor_gain = std::numeric_limits<float>::max();

  const auto *anchor_timeline = plan.timeline.find(plan.anchor_stem_id);
  struct TimelineRenderState final {
    const StemInput *stem{nullptr};
    std::vector<EventRenderState> events;
  };
  std::vector<TimelineRenderState> render_states;
  render_states.reserve(plan.timeline.stems().size());
  for (const auto &timeline : plan.timeline.stems()) {
    TimelineRenderState state;
    state.stem = find_stem(stems, timeline.stem_id());
    state.events.resize(timeline.events().size());
    for (std::size_t index = 0; index < timeline.events().size(); ++index) {
      state.events[index].source_position =
          static_cast<double>(timeline.events()[index].source_start);
    }
    render_states.push_back(std::move(state));
  }
  double anchor_square_sum = 0.0;
  std::uint64_t anchor_sample_count = 0U;

  for (std::uint64_t frame = 0; frame < plan.total_frames; ++frame) {
    if ((frame & 63U) == 0U && cancellation.cancelled()) {
      result.cancelled = true;
      result.interleaved.clear();
      result.metrics.rendered_frames = frame;
      result.metrics.minimum_anchor_gain = 0.0F;
      return result;
    }

    float left = 0.0F;
    float right = 0.0F;
    float anchor_left = 0.0F;
    float anchor_right = 0.0F;
    float anchor_gain = 0.0F;
    bool frame_nonfinite = false;
    for (std::size_t timeline_index = 0;
         timeline_index < plan.timeline.stems().size(); ++timeline_index) {
      const auto &timeline = plan.timeline.stems()[timeline_index];
      auto &timeline_state = render_states[timeline_index];
      const auto *stem = timeline_state.stem;
      if (stem == nullptr) {
        continue;
      }
      for (std::size_t event_index = 0; event_index < timeline.events().size();
           ++event_index) {
        const auto &event = timeline.events()[event_index];
        if (frame < event.timeline_start ||
            frame - event.timeline_start >= event.duration) {
          continue;
        }
        const auto local = frame - event.timeline_start;
        const auto event_sample = render_event_stereo(
            event, *stem, local, sample_rate,
            timeline_state.events[event_index], frame_nonfinite);
        const auto gain = std::max(0.0F, event.gain.value_at(local));
        if (gain <= 0.0F) {
          continue;
        }
        const auto pan = finite_clamp(event.pan.value_at(local), -1.0F, 1.0F);
        const auto pan_left = std::sqrt(0.5F * (1.0F - pan));
        const auto pan_right = std::sqrt(0.5F * (1.0F + pan));
        const auto event_left = event_sample.left * gain * pan_left;
        const auto event_right = event_sample.right * gain * pan_right;
        left += event_left;
        right += event_right;

        if (&timeline == anchor_timeline &&
            source_available(*stem, event.source)) {
          anchor_gain += gain;
          result.metrics.anchor_source_mask |=
              1U << static_cast<std::uint32_t>(event.source);
          anchor_left += event_left;
          anchor_right += event_right;
        }
      }
    }

    if (anchor_timeline != nullptr) {
      result.metrics.minimum_anchor_gain =
          std::min(result.metrics.minimum_anchor_gain, anchor_gain);
      anchor_square_sum += static_cast<double>(anchor_left) * anchor_left +
                           static_cast<double>(anchor_right) * anchor_right;
      anchor_sample_count += 2U;
    }
    result.metrics.had_nonfinite =
        result.metrics.had_nonfinite || frame_nonfinite || !std::isfinite(left) ||
        !std::isfinite(right);
    if (std::isfinite(left)) {
      result.metrics.preclip_peak =
          std::max(result.metrics.preclip_peak, std::abs(left));
    }
    if (std::isfinite(right)) {
      result.metrics.preclip_peak =
          std::max(result.metrics.preclip_peak, std::abs(right));
    }
    left = std::isfinite(left) ? std::max(-1.0F, std::min(left, 1.0F)) : 0.0F;
    right =
        std::isfinite(right) ? std::max(-1.0F, std::min(right, 1.0F)) : 0.0F;
    result.interleaved[static_cast<std::size_t>(frame) * 2U] = left;
    result.interleaved[static_cast<std::size_t>(frame) * 2U + 1U] = right;
  }
  result.metrics.rendered_frames = plan.total_frames;
  result.metrics.anchor_rms = anchor_sample_count == 0U
                                  ? 0.0F
                                  : static_cast<float>(std::sqrt(
                                        anchor_square_sum /
                                        static_cast<double>(anchor_sample_count)));
  if (anchor_timeline == nullptr ||
      result.metrics.minimum_anchor_gain == std::numeric_limits<float>::max()) {
    result.metrics.minimum_anchor_gain = 0.0F;
  }
  return result;
}

QualityDiagnostics
QualityEvaluator::evaluate(const RenderResult &render,
                           const QualityContext &context) const {
  QualityDiagnostics diagnostics;
  const auto length_fits =
      render.channels > 0U &&
      context.expected_frames <=
          std::numeric_limits<std::size_t>::max() / render.channels;
  diagnostics.expected_length =
      length_fits &&
      render.interleaved.size() ==
          static_cast<std::size_t>(context.expected_frames) * render.channels &&
      render.metrics.rendered_frames == context.expected_frames;
  diagnostics.finite_samples = !render.metrics.had_nonfinite;

  double square_sum = 0.0;
  std::size_t finite_count = 0U;
  float peak = 0.0F;
  float derivative = 0.0F;
  float second_derivative = 0.0F;
  float boundary_jump = 0.0F;
  std::vector<double> channel_sum(render.channels, 0.0);
  std::vector<std::size_t> channel_count(render.channels, 0U);
  std::vector<float> previous(render.channels, 0.0F);
  std::vector<float> previous_derivative(render.channels, 0.0F);
  std::vector<bool> has_previous(render.channels, false);
  std::vector<bool> has_previous_derivative(render.channels, false);
  for (std::size_t index = 0; index < render.interleaved.size(); ++index) {
    const auto sample = render.interleaved[index];
    if (!std::isfinite(sample)) {
      diagnostics.finite_samples = false;
      continue;
    }
    peak = std::max(peak, std::abs(sample));
    square_sum += static_cast<double>(sample) * sample;
    ++finite_count;
    if (render.channels > 0U) {
      const auto channel = index % render.channels;
      const auto frame = index / render.channels;
      channel_sum[channel] += sample;
      ++channel_count[channel];
      if (has_previous[channel]) {
        const auto delta = sample - previous[channel];
        derivative = std::max(derivative, std::abs(delta));
        if (has_previous_derivative[channel]) {
          second_derivative = std::max(
              second_derivative,
              std::abs(delta - previous_derivative[channel]));
        }
        if (context.control_block_frames > 0U && frame > 0U &&
            frame % context.control_block_frames == 0U) {
          boundary_jump = std::max(boundary_jump, std::abs(delta));
        }
        previous_derivative[channel] = delta;
        has_previous_derivative[channel] = true;
      }
      previous[channel] = sample;
      has_previous[channel] = true;
    }
  }

  if (context.has_previous_output && render.channels <= 2U &&
      render.interleaved.size() >= render.channels) {
    for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
      boundary_jump = std::max(
          boundary_jump,
          std::abs(render.interleaved[channel] - context.previous_output[channel]));
    }
  }
  if (context.has_next_output && render.channels <= 2U &&
      render.interleaved.size() >= render.channels) {
    const auto last_frame = render.interleaved.size() - render.channels;
    for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
      boundary_jump = std::max(
          boundary_jump,
          std::abs(context.next_output[channel] -
                   render.interleaved[last_frame + channel]));
    }
  }

  float dc_offset = 0.0F;
  for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
    if (channel_count[channel] > 0U) {
      dc_offset = std::max(
          dc_offset,
          static_cast<float>(std::abs(
              channel_sum[channel] /
              static_cast<double>(channel_count[channel]))));
    }
  }
  float dc_jump = 0.0F;
  const auto block_frames = std::max<std::uint32_t>(
      1U, context.control_block_frames == 0U
              ? static_cast<std::uint32_t>(std::min<std::uint64_t>(
                    context.expected_frames, std::numeric_limits<std::uint32_t>::max()))
              : context.control_block_frames);
  std::vector<double> previous_block_mean(render.channels, 0.0);
  bool has_previous_block = false;
  const auto available_frames = render.channels == 0U
                                    ? 0U
                                    : render.interleaved.size() / render.channels;
  for (std::size_t start = 0U; start < available_frames;
       start += block_frames) {
    const auto end = std::min<std::size_t>(available_frames,
                                           start + block_frames);
    for (std::uint32_t channel = 0U; channel < render.channels; ++channel) {
      double block_sum = 0.0;
      std::size_t count = 0U;
      for (auto frame = start; frame < end; ++frame) {
        const auto sample =
            render.interleaved[frame * render.channels + channel];
        if (std::isfinite(sample)) {
          block_sum += sample;
          ++count;
        }
      }
      const auto mean = count == 0U
                            ? 0.0
                            : block_sum / static_cast<double>(count);
      if (has_previous_block) {
        dc_jump = std::max(
            dc_jump,
            static_cast<float>(std::abs(mean - previous_block_mean[channel])));
      }
      previous_block_mean[channel] = mean;
    }
    has_previous_block = true;
  }

  const auto finite_count_double = static_cast<double>(finite_count);
  const auto rms = finite_count > 0U
                       ? std::sqrt(square_sum / finite_count_double)
                       : 0.0;
  diagnostics.peak = peak;
  diagnostics.preclip_peak = render.metrics.preclip_peak;
  diagnostics.dc_offset = dc_offset;
  diagnostics.max_dc_jump = dc_jump;
  diagnostics.max_sample_derivative = derivative;
  diagnostics.max_second_derivative = second_derivative;
  diagnostics.max_boundary_jump = boundary_jump;
  diagnostics.anchor_rms = render.metrics.anchor_rms;
  diagnostics.loudness_dbfs =
      static_cast<float>(20.0 * std::log10(std::max(rms, 1.0e-6)));
  diagnostics.peak_ok = diagnostics.peak <= context.max_peak + 1.0e-6F;
  diagnostics.preclip_ok =
      diagnostics.preclip_peak <= context.max_peak + 1.0e-6F;
  diagnostics.dc_ok = diagnostics.dc_offset <= context.max_dc_offset &&
                      diagnostics.max_dc_jump <= context.max_boundary_jump;
  diagnostics.derivative_ok =
      diagnostics.max_sample_derivative <= context.max_derivative;
  diagnostics.second_derivative_ok =
      diagnostics.max_second_derivative <= context.max_second_derivative;
  diagnostics.boundaries_ok =
      diagnostics.max_boundary_jump <= context.max_boundary_jump;
  diagnostics.loudness_ok =
      diagnostics.loudness_dbfs >= context.min_loudness_dbfs &&
      diagnostics.loudness_dbfs <= context.max_loudness_dbfs;

  if (valid_bpm(context.bpm_a) && valid_bpm(context.bpm_b)) {
    diagnostics.rhythm_error =
        static_cast<float>(std::abs(std::log2(context.bpm_b / context.bpm_a)));
    diagnostics.rhythm_ok = diagnostics.rhythm_error <= 0.42F;
  } else {
    diagnostics.rhythm_error = std::numeric_limits<float>::infinity();
    diagnostics.rhythm_ok = false;
  }
  if (std::isfinite(context.harmonic_distance)) {
    diagnostics.harmony_conflict = static_cast<float>(std::max(
        0.0, std::min(std::abs(context.harmonic_distance) / 6.0, 1.0)));
    diagnostics.harmony_ok = diagnostics.harmony_conflict <= 0.67F;
  } else {
    diagnostics.harmony_conflict = std::numeric_limits<float>::infinity();
    diagnostics.harmony_ok = false;
  }
  if (std::isfinite(context.stem_conflict_score)) {
    diagnostics.stem_conflict = static_cast<float>(
        std::max(0.0, std::min(context.stem_conflict_score, 1.0)));
    diagnostics.stem_conflicts_ok = diagnostics.stem_conflict <= 0.75F;
  } else {
    diagnostics.stem_conflict = std::numeric_limits<float>::infinity();
    diagnostics.stem_conflicts_ok = false;
  }
  diagnostics.anchor_continuous =
      render.metrics.anchor_source_mask != 0U &&
      render.metrics.minimum_anchor_gain >= 1.0e-4F;
  diagnostics.anchor_audible = render.metrics.anchor_rms >= 1.0e-5F;

  const auto add_failure = [&diagnostics](bool passed, DiagnosticCode code) {
    if (!passed) {
      diagnostics.failures.push_back(code);
    }
  };
  add_failure(diagnostics.finite_samples, DiagnosticCode::NonFiniteSample);
  add_failure(diagnostics.expected_length, DiagnosticCode::UnexpectedLength);
  add_failure(diagnostics.peak_ok, DiagnosticCode::PeakExceeded);
  add_failure(diagnostics.preclip_ok, DiagnosticCode::PreclipExceeded);
  add_failure(diagnostics.dc_ok, DiagnosticCode::DcOffset);
  add_failure(diagnostics.derivative_ok, DiagnosticCode::DerivativeSpike);
  add_failure(diagnostics.second_derivative_ok,
              DiagnosticCode::SecondDerivativeSpike);
  add_failure(diagnostics.boundaries_ok,
              DiagnosticCode::BoundaryDiscontinuity);
  add_failure(diagnostics.loudness_ok, DiagnosticCode::LoudnessOutOfRange);
  add_failure(diagnostics.rhythm_ok, DiagnosticCode::RhythmMismatch);
  add_failure(diagnostics.harmony_ok, DiagnosticCode::HarmonyConflict);
  add_failure(diagnostics.stem_conflicts_ok, DiagnosticCode::StemConflict);
  add_failure(diagnostics.anchor_continuous, DiagnosticCode::AnchorDropout);
  add_failure(diagnostics.anchor_audible, DiagnosticCode::AnchorInaudible);
  return diagnostics;
}

QualityProfile AdaptiveQualitySelector::select(
    const CapabilityMeasurements &measured) noexcept {
  const auto realtime = std::isfinite(measured.render_realtime_factor) &&
                                measured.render_realtime_factor > 0.0
                            ? measured.render_realtime_factor
                            : std::numeric_limits<double>::infinity();
  const auto thermal = finite_clamp(measured.thermal_headroom, 0.0F, 1.0F);
  const auto battery = finite_clamp(measured.battery_fraction, 0.0F, 1.0F);

  if (!measured.low_power_mode && realtime <= 0.35 &&
      measured.memory_budget_bytes >= 256ULL * 1024ULL * 1024ULL &&
      measured.logical_cores >= 6U && thermal >= 0.60F && battery >= 0.25F) {
    return QualityProfile{QualityTier::Studio, {16U, 256U, 12U}, 64U, true};
  }
  if (!measured.low_power_mode && realtime <= 0.85 &&
      measured.memory_budget_bytes >= 96ULL * 1024ULL * 1024ULL &&
      measured.logical_cores >= 4U && thermal >= 0.30F && battery >= 0.12F) {
    return QualityProfile{QualityTier::Balanced, {8U, 128U, 8U}, 128U, true};
  }
  return QualityProfile{QualityTier::Economy, {4U, 48U, 4U}, 256U, false};
}

std::uint64_t CacheKey::stable_hash() const noexcept {
  auto hash = kFnvOffset;
  fnv_string(hash, source_a_id);
  fnv_string(hash, source_b_id);
  fnv_u64(hash, source_a_revision);
  fnv_u64(hash, source_b_revision);
  fnv_u64(hash, plan_id);
  fnv_u64(hash, sample_rate);
  fnv_u64(hash, static_cast<std::uint64_t>(quality));
  fnv_u64(hash, planner_revision);
  return hash;
}

bool CacheKey::operator==(const CacheKey &other) const noexcept {
  return source_a_id == other.source_a_id && source_b_id == other.source_b_id &&
         source_a_revision == other.source_a_revision &&
         source_b_revision == other.source_b_revision &&
         plan_id == other.plan_id && sample_rate == other.sample_rate &&
         quality == other.quality && planner_revision == other.planner_revision;
}

bool LifecycleController::start() {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  auto expected = EngineState::Stopped;
  return state_.compare_exchange_strong(expected, EngineState::Playing,
                                        std::memory_order_acq_rel,
                                        std::memory_order_acquire);
}

bool LifecycleController::pause() {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  auto expected = EngineState::Playing;
  return state_.compare_exchange_strong(expected, EngineState::Paused,
                                        std::memory_order_acq_rel,
                                        std::memory_order_acquire);
}

bool LifecycleController::resume() {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  auto expected = EngineState::Paused;
  return state_.compare_exchange_strong(expected, EngineState::Playing,
                                        std::memory_order_acq_rel,
                                        std::memory_order_acquire);
}

std::uint64_t LifecycleController::seek(std::uint64_t target_frame) {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  const auto current = state_.load(std::memory_order_acquire);
  if (current != EngineState::Playing && current != EngineState::Paused) {
    return 0U;
  }
  resume_after_seek_.store(current, std::memory_order_release);
  state_.store(EngineState::Seeking, std::memory_order_release);
  position_.store(target_frame, std::memory_order_release);
  pending_next_.reset();
  return generation_.fetch_add(1U, std::memory_order_acq_rel) + 1U;
}

bool LifecycleController::complete_seek(std::uint64_t generation) {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  if (generation == 0U ||
      generation_.load(std::memory_order_acquire) != generation) {
    return false;
  }
  auto expected = EngineState::Seeking;
  return state_.compare_exchange_strong(
      expected, resume_after_seek_.load(std::memory_order_acquire),
      std::memory_order_acq_rel, std::memory_order_acquire);
}

void LifecycleController::stop() {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  state_.store(EngineState::Stopped, std::memory_order_release);
  position_.store(0U, std::memory_order_release);
  generation_.fetch_add(1U, std::memory_order_acq_rel);
  pending_next_.reset();
}

std::uint64_t
LifecycleController::request_next(std::uint64_t track_id) {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  const auto generation =
      generation_.fetch_add(1U, std::memory_order_acq_rel) + 1U;
  pending_next_ = NextRequest{track_id, generation};
  return generation;
}

std::optional<NextRequest> LifecycleController::consume_next() {
  const std::lock_guard<std::mutex> lock(control_mutex_);
  if (pending_next_ && pending_next_->generation !=
                           generation_.load(std::memory_order_acquire)) {
    pending_next_.reset();
  }
  auto result = pending_next_;
  pending_next_.reset();
  return result;
}

BridgeOutcome BridgeEngine::render(const BridgeRequest &request) const {
  BridgeOutcome outcome;
  validate_bridge_request(request);
  const auto frames = checked_duration(request.sample_rate, request.bpm_a,
                                       request.bpm_b, request.duration_frames);
  const auto quality = AdaptiveQualitySelector::select(request.capability);
  outcome.quality = quality.tier;
  if (request.cancellation.cancelled()) {
    outcome.render.cancelled = true;
    return outcome;
  }
  if (!has_usable_audio(request.stems)) {
    outcome.no_audio = true;
    return outcome;
  }

  SearchContext search_context;
  search_context.sample_rate = request.sample_rate;
  search_context.bpm_a = request.bpm_a;
  search_context.bpm_b = request.bpm_b;
  search_context.requested_frames = frames;
  search_context.harmonic_distance = request.harmonic_distance;
  search_context.tier = quality.tier;
  search_context.limits = quality.search;
  search_context.cancellation = request.cancellation;

  QualityContext quality_context;
  quality_context.sample_rate = request.sample_rate;
  quality_context.expected_frames = frames;
  quality_context.bpm_a = request.bpm_a;
  quality_context.bpm_b = request.bpm_b;
  quality_context.harmonic_distance = request.harmonic_distance;
  quality_context.stem_conflict_score = request.stem_conflict_score;
  quality_context.control_block_frames = quality.control_block_frames;
  quality_context.has_previous_output = request.has_previous_output;
  quality_context.has_next_output = request.has_next_output;
  quality_context.previous_output = request.previous_output;
  quality_context.next_output = request.next_output;
  auto search = planner_.search(request.stems, search_context);
  outcome.search_expansions = search.expansions;
  if (search.cancelled || request.cancellation.cancelled()) {
    outcome.render.cancelled = true;
    return outcome;
  }

  for (const auto &candidate : search.candidates) {
    auto render = renderer_.render(candidate, request.stems,
                                   request.sample_rate, request.cancellation);
    if (render.cancelled) {
      outcome.render = std::move(render);
      return outcome;
    }
    auto diagnostics = evaluator_.evaluate(render, quality_context);
    outcome.attempts.push_back(
        {FallbackStage::DeterministicMultiStem, diagnostics});
    if (diagnostics_equivalent(diagnostics)) {
      outcome.render = std::move(render);
      outcome.diagnostics = std::move(diagnostics);
      outcome.stage = FallbackStage::DeterministicMultiStem;
      outcome.plan_id = candidate.stable_id;
      return outcome;
    }
  }

  const auto try_fallback = [&](FallbackStage stage, const BridgePlan &plan) {
    if (plan.total_frames == 0U || request.cancellation.cancelled()) {
      return false;
    }
    auto render = renderer_.render(plan, request.stems, request.sample_rate,
                                   request.cancellation);
    if (render.cancelled) {
      outcome.render = std::move(render);
      return true;
    }
    auto diagnostics = evaluator_.evaluate(render, quality_context);
    outcome.attempts.push_back({stage, diagnostics});
    if (diagnostics.accepted()) {
      outcome.render = std::move(render);
      outcome.diagnostics = std::move(diagnostics);
      outcome.stage = stage;
      outcome.plan_id = plan.stable_id;
      return true;
    }
    return false;
  };

  if (try_fallback(FallbackStage::PhraseAlignedCrossfade,
                   planner_.safe_crossfade(request.stems, request.sample_rate,
                                           frames))) {
    return outcome;
  }
  if (request.cancellation.cancelled()) {
    outcome.render.cancelled = true;
    return outcome;
  }

  const auto emergency =
      planner_.anchor_only(request.stems, request.sample_rate, frames);
  if (emergency.total_frames == 0U) {
    throw std::logic_error("usable audio did not produce an emergency plan");
  }
  outcome.render = renderer_.render(emergency, request.stems,
                                    request.sample_rate, request.cancellation);
  if (outcome.render.cancelled) {
    return outcome;
  }
  normalize_emergency(outcome.render);
  outcome.diagnostics = evaluator_.evaluate(outcome.render, quality_context);
  outcome.attempts.push_back(
      {FallbackStage::EmergencyBasicCrossfade, outcome.diagnostics});
  outcome.stage = FallbackStage::EmergencyBasicCrossfade;
  outcome.plan_id = emergency.stable_id;
  return outcome;
}

} // namespace autoremix::audio
