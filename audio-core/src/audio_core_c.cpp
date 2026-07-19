#include "autoremix/audio_core_c.h"

#include "autoremix/audio_core.hpp"

#include <algorithm>
#include <cstring>
#include <new>
#include <string>
#include <vector>

using autoremix::audio::AdaptiveQualitySelector;
using autoremix::audio::AudioView;
using autoremix::audio::BridgeEngine;
using autoremix::audio::BridgeRequest;
using autoremix::audio::CacheKey;
using autoremix::audio::CancellationProbe;
using autoremix::audio::CapabilityMeasurements;
using autoremix::audio::DiagnosticCode;
using autoremix::audio::EngineState;
using autoremix::audio::LifecycleController;
using autoremix::audio::QualityDiagnostics;
using autoremix::audio::QualityTier;
using autoremix::audio::SpscAudioRingBuffer;
using autoremix::audio::StemInput;
using autoremix::audio::StemRole;

struct ar_engine final {
  BridgeEngine bridge;
  LifecycleController lifecycle;
};

struct ar_audio_ring final {
  explicit ar_audio_ring(std::uint32_t capacity) : ring(capacity) {}
  SpscAudioRingBuffer ring;
};

namespace {

[[nodiscard]] StemRole convert_role(ar_stem_role_t role) {
  switch (role) {
  case AR_STEM_VOCAL:
    return StemRole::Vocal;
  case AR_STEM_DRUMS:
    return StemRole::Drums;
  case AR_STEM_BASS:
    return StemRole::Bass;
  case AR_STEM_HARMONY:
    return StemRole::Harmony;
  case AR_STEM_OTHER:
    return StemRole::Other;
  case AR_STEM_BACKING_VOCAL:
    return StemRole::BackingVocal;
  case AR_STEM_PERCUSSION:
    return StemRole::Percussion;
  case AR_STEM_GUITAR:
    return StemRole::Guitar;
  case AR_STEM_KEYS:
    return StemRole::Keys;
  case AR_STEM_SYNTH:
    return StemRole::Synth;
  case AR_STEM_STRINGS:
    return StemRole::Strings;
  case AR_STEM_LEAD:
    return StemRole::Lead;
  case AR_STEM_ATMOSPHERE:
    return StemRole::Atmosphere;
  case AR_STEM_EFFECTS:
    return StemRole::Effects;
  default:
    throw std::invalid_argument("invalid stem role");
  }
}

[[nodiscard]] AudioView convert_view(const ar_audio_view_t &view) noexcept {
  return AudioView{view.interleaved, view.frames, view.channels,
                   view.sample_rate};
}

[[nodiscard]] CapabilityMeasurements
convert_capability(const ar_capability_t &capability) noexcept {
  CapabilityMeasurements result;
  result.render_realtime_factor = capability.render_realtime_factor;
  result.memory_budget_bytes = capability.memory_budget_bytes;
  result.logical_cores = capability.logical_cores;
  result.thermal_headroom = capability.thermal_headroom;
  result.battery_fraction = capability.battery_fraction;
  result.low_power_mode = capability.low_power_mode != 0U;
  return result;
}

[[nodiscard]] ar_quality_t convert_quality(QualityTier quality) noexcept {
  return static_cast<ar_quality_t>(quality);
}

[[nodiscard]] QualityTier convert_quality(ar_quality_t quality) noexcept {
  switch (quality) {
  case AR_QUALITY_STUDIO:
    return QualityTier::Studio;
  case AR_QUALITY_BALANCED:
    return QualityTier::Balanced;
  case AR_QUALITY_ECONOMY:
  default:
    return QualityTier::Economy;
  }
}

[[nodiscard]] std::uint32_t
failure_mask(const QualityDiagnostics &diagnostics) noexcept {
  std::uint32_t result = 0U;
  for (const auto failure : diagnostics.failures) {
    const auto bit = static_cast<std::uint32_t>(failure);
    if (bit < 32U) {
      result |= 1U << bit;
    }
  }
  return result;
}

struct RenderCancelContext final {
  ar_engine_t *engine{nullptr};
  std::uint64_t generation{0};
  ar_cancel_callback_t callback{nullptr};
  void *user_data{nullptr};
};

[[nodiscard]] bool render_cancelled(void *opaque) noexcept {
  const auto *context = static_cast<const RenderCancelContext *>(opaque);
  if (context == nullptr || context->engine == nullptr) {
    return true;
  }
  if (!context->engine->lifecycle.generation_valid(context->generation)) {
    return true;
  }
  if (context->callback == nullptr) {
    return false;
  }
  try {
    return context->callback(context->user_data) != 0;
  } catch (...) {
    return true;
  }
}

[[nodiscard]] bool
valid_request_shape(const ar_bridge_request_t *request,
                    const ar_bridge_result_t *result) noexcept {
  if (request == nullptr || result == nullptr ||
      request->struct_size < sizeof(ar_bridge_request_t) ||
      result->struct_size < sizeof(ar_bridge_result_t)) {
    return false;
  }
  if (request->stem_count > 0U && request->stems == nullptr) {
    return false;
  }
  return true;
}

void copy_diagnostics(const QualityDiagnostics &source,
                      float minimum_anchor_gain,
                      ar_quality_diagnostics_t &destination) noexcept {
  destination.failure_mask = failure_mask(source);
  destination.peak = source.peak;
  destination.preclip_peak = source.preclip_peak;
  destination.dc_offset = source.dc_offset;
  destination.max_dc_jump = source.max_dc_jump;
  destination.max_sample_derivative = source.max_sample_derivative;
  destination.max_second_derivative = source.max_second_derivative;
  destination.max_boundary_jump = source.max_boundary_jump;
  destination.loudness_dbfs = source.loudness_dbfs;
  destination.rhythm_error = source.rhythm_error;
  destination.harmony_conflict = source.harmony_conflict;
  destination.stem_conflict = source.stem_conflict;
  destination.minimum_anchor_gain = minimum_anchor_gain;
  destination.anchor_rms = source.anchor_rms;
}

} // namespace

extern "C" {

uint32_t ar_audio_core_abi_version(void) { return AR_AUDIO_CORE_ABI_VERSION; }

ar_audio_core_t *ar_audio_core_create(void) { return ar_engine_create(); }

void ar_audio_core_destroy(ar_audio_core_t *core) { ar_engine_destroy(core); }

ar_status_t ar_audio_core_render_interleaved(ar_audio_core_t *core,
                                             const ar_bridge_request_t *request,
                                             ar_bridge_result_t *result) {
  return ar_engine_render_bridge(core, request, result);
}

ar_status_t ar_audio_core_set_paused(ar_audio_core_t *core, int32_t paused) {
  if (core == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    const auto state = core->lifecycle.state();
    if (paused != 0) {
      if (state == EngineState::Paused) {
        return AR_STATUS_OK;
      }
      return core->lifecycle.pause() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
    }
    if (state == EngineState::Playing) {
      return AR_STATUS_OK;
    }
    if (state == EngineState::Stopped) {
      return core->lifecycle.start() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
    }
    return core->lifecycle.resume() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_audio_core_seek(ar_audio_core_t *core, uint64_t target_frame,
                               uint64_t *generation) {
  return ar_engine_seek(core, target_frame, generation);
}

ar_engine_t *ar_engine_create(void) {
  try {
    return new (std::nothrow) ar_engine_t{};
  } catch (...) {
    return nullptr;
  }
}

void ar_engine_destroy(ar_engine_t *engine) { delete engine; }

ar_quality_t ar_engine_select_quality(const ar_capability_t *capability) {
  if (capability == nullptr) {
    return AR_QUALITY_ECONOMY;
  }
  return convert_quality(
      AdaptiveQualitySelector::select(convert_capability(*capability)).tier);
}

ar_status_t ar_engine_render_bridge(ar_engine_t *engine,
                                    const ar_bridge_request_t *request,
                                    ar_bridge_result_t *result) {
  if (engine == nullptr || !valid_request_shape(request, result)) {
    return AR_STATUS_INVALID_ARGUMENT;
  }

  try {
    BridgeRequest converted;
    converted.sample_rate = request->sample_rate;
    converted.bpm_a = request->bpm_a;
    converted.bpm_b = request->bpm_b;
    converted.duration_frames = request->duration_frames;
    converted.harmonic_distance = request->harmonic_distance;
    converted.stem_conflict_score = request->stem_conflict_score;
    converted.capability = convert_capability(request->capability);
    converted.stems.reserve(request->stem_count);

    for (std::size_t index = 0; index < request->stem_count; ++index) {
      const auto &input = request->stems[index];
      if (input.struct_size < sizeof(ar_stem_input_t)) {
        return AR_STATUS_INVALID_ARGUMENT;
      }
      StemInput stem;
      stem.features.stem_id = input.stem_id;
      stem.features.role = convert_role(input.role);
      stem.source_a = convert_view(input.source_a);
      stem.source_b = convert_view(input.source_b);
      stem.generated = convert_view(input.generated);
      stem.features.available_a = stem.source_a.valid();
      stem.features.available_b = stem.source_b.valid();
      stem.features.available_generated = stem.generated.valid();
      stem.features.confidence = input.confidence;
      stem.features.continuity = input.continuity;
      stem.features.transient_stability = input.transient_stability;
      stem.features.harmonic_stability = input.harmonic_stability;
      stem.features.foreground = input.foreground;
      converted.stems.push_back(stem);
    }
    converted.has_previous_output = request->has_previous_output != 0U;
    converted.has_next_output = request->has_next_output != 0U;
    converted.previous_output = {request->previous_output[0],
                                 request->previous_output[1]};
    converted.next_output = {request->next_output[0], request->next_output[1]};

    RenderCancelContext cancel_context{engine, engine->lifecycle.generation(),
                                       request->cancel_callback,
                                       request->cancel_user_data};
    converted.cancellation =
        CancellationProbe{&render_cancelled, &cancel_context};
    auto outcome = engine->bridge.render(converted);
    if (outcome.render.cancelled) {
      result->frames_written = 0U;
      result->channels = 2U;
      return AR_STATUS_CANCELLED;
    }
    if (outcome.no_audio) {
      result->frames_written = 0U;
      result->channels = 2U;
      return AR_STATUS_NO_AUDIO;
    }

    result->frames_written = outcome.render.metrics.rendered_frames;
    result->channels = outcome.render.channels;
    result->fallback_stage = static_cast<ar_fallback_stage_t>(outcome.stage);
    result->quality = convert_quality(outcome.quality);
    result->plan_id = outcome.plan_id;
    result->search_expansions =
        static_cast<std::uint64_t>(outcome.search_expansions);
    copy_diagnostics(outcome.diagnostics,
                     outcome.render.metrics.minimum_anchor_gain,
                     result->diagnostics);

    if (outcome.render.interleaved.size() > result->capacity_samples ||
        (outcome.render.interleaved.size() > 0U &&
         result->interleaved == nullptr)) {
      return AR_STATUS_BUFFER_TOO_SMALL;
    }
    std::copy(outcome.render.interleaved.begin(),
              outcome.render.interleaved.end(), result->interleaved);
    return AR_STATUS_OK;
  } catch (const std::invalid_argument &) {
    return AR_STATUS_INVALID_ARGUMENT;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_engine_start(ar_engine_t *engine) {
  if (engine == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    if (engine->lifecycle.state() == EngineState::Playing) {
      return AR_STATUS_OK;
    }
    return engine->lifecycle.start() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_engine_pause(ar_engine_t *engine) {
  if (engine == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    if (engine->lifecycle.state() == EngineState::Paused) {
      return AR_STATUS_OK;
    }
    return engine->lifecycle.pause() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_engine_resume(ar_engine_t *engine) {
  if (engine == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    if (engine->lifecycle.state() == EngineState::Playing) {
      return AR_STATUS_OK;
    }
    return engine->lifecycle.resume() ? AR_STATUS_OK : AR_STATUS_INVALID_STATE;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

void ar_engine_stop(ar_engine_t *engine) {
  if (engine != nullptr) {
    try {
      engine->lifecycle.stop();
    } catch (...) {
    }
  }
}

ar_status_t ar_engine_seek(ar_engine_t *engine, uint64_t target_frame,
                           uint64_t *generation) {
  if (engine == nullptr || generation == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    const auto value = engine->lifecycle.seek(target_frame);
    if (value == 0U) {
      return AR_STATUS_INVALID_STATE;
    }
    *generation = value;
    return AR_STATUS_OK;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_engine_complete_seek(ar_engine_t *engine, uint64_t generation) {
  if (engine == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    return engine->lifecycle.complete_seek(generation) ? AR_STATUS_OK
                                                       : AR_STATUS_INVALID_STATE;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

ar_status_t ar_engine_request_next(ar_engine_t *engine, uint64_t track_id,
                                   uint64_t *generation) {
  if (engine == nullptr || generation == nullptr) {
    return AR_STATUS_INVALID_ARGUMENT;
  }
  try {
    *generation = engine->lifecycle.request_next(track_id);
    return AR_STATUS_OK;
  } catch (...) {
    return AR_STATUS_INTERNAL_ERROR;
  }
}

int32_t ar_engine_consume_next(ar_engine_t *engine, uint64_t *track_id,
                               uint64_t *generation) {
  if (engine == nullptr || track_id == nullptr || generation == nullptr) {
    return -1;
  }
  try {
    const auto next = engine->lifecycle.consume_next();
    if (!next) {
      return 0;
    }
    *track_id = next->track_id;
    *generation = next->generation;
    return 1;
  } catch (...) {
    return -1;
  }
}

ar_engine_state_t ar_engine_get_state(const ar_engine_t *engine) {
  return engine == nullptr
             ? AR_ENGINE_STOPPED
             : static_cast<ar_engine_state_t>(engine->lifecycle.state());
}

uint64_t ar_engine_get_generation(const ar_engine_t *engine) {
  return engine == nullptr ? 0U : engine->lifecycle.generation();
}

ar_audio_ring_t *ar_audio_ring_create(uint32_t sample_capacity) {
  try {
    return new (std::nothrow) ar_audio_ring_t(sample_capacity);
  } catch (...) {
    return nullptr;
  }
}

void ar_audio_ring_destroy(ar_audio_ring_t *ring) { delete ring; }

uint32_t ar_audio_ring_write(ar_audio_ring_t *ring, const float *samples,
                             uint32_t count) {
  return ring == nullptr ? 0U : ring->ring.write(samples, count);
}

uint32_t ar_audio_ring_read(ar_audio_ring_t *ring, float *samples,
                            uint32_t count) {
  return ring == nullptr ? 0U : ring->ring.read(samples, count);
}

uint32_t ar_audio_ring_available_read(const ar_audio_ring_t *ring) {
  return ring == nullptr ? 0U : ring->ring.available_read();
}

uint32_t ar_audio_ring_available_write(const ar_audio_ring_t *ring) {
  return ring == nullptr ? 0U : ring->ring.available_write();
}

uint64_t ar_cache_key_hash(const ar_cache_key_t *key) {
  if (key == nullptr) {
    return 0U;
  }
  try {
    CacheKey converted;
    converted.source_a_id =
        key->source_a_id == nullptr ? std::string{} : key->source_a_id;
    converted.source_b_id =
        key->source_b_id == nullptr ? std::string{} : key->source_b_id;
    converted.source_a_revision = key->source_a_revision;
    converted.source_b_revision = key->source_b_revision;
    converted.plan_id = key->plan_id;
    converted.sample_rate = key->sample_rate;
    converted.quality = convert_quality(key->quality);
    converted.planner_revision = key->planner_revision;
    return converted.stable_hash();
  } catch (...) {
    return 0U;
  }
}

} // extern "C"
