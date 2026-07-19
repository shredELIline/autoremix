#ifndef AUTOREMIX_AUDIO_CORE_C_H
#define AUTOREMIX_AUDIO_CORE_C_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(AR_AUDIO_CORE_SHARED)
#if defined(AR_AUDIO_CORE_BUILDING)
#define AR_AUDIO_CORE_API __declspec(dllexport)
#else
#define AR_AUDIO_CORE_API __declspec(dllimport)
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define AR_AUDIO_CORE_API __attribute__((visibility("default")))
#else
#define AR_AUDIO_CORE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define AR_AUDIO_CORE_ABI_VERSION 2U

typedef uint32_t ar_status_t;
#define AR_STATUS_OK 0U
#define AR_STATUS_INVALID_ARGUMENT 1U
#define AR_STATUS_BUFFER_TOO_SMALL 2U
#define AR_STATUS_CANCELLED 3U
#define AR_STATUS_INVALID_STATE 4U
#define AR_STATUS_INTERNAL_ERROR 5U
#define AR_STATUS_NO_AUDIO 6U

typedef uint32_t ar_stem_role_t;
#define AR_STEM_VOCAL 0U
#define AR_STEM_DRUMS 1U
#define AR_STEM_BASS 2U
#define AR_STEM_HARMONY 3U
#define AR_STEM_OTHER 4U
#define AR_STEM_BACKING_VOCAL 5U
#define AR_STEM_PERCUSSION 6U
#define AR_STEM_GUITAR 7U
#define AR_STEM_KEYS 8U
#define AR_STEM_SYNTH 9U
#define AR_STEM_STRINGS 10U
#define AR_STEM_LEAD 11U
#define AR_STEM_ATMOSPHERE 12U
#define AR_STEM_EFFECTS 13U

typedef uint32_t ar_quality_t;
#define AR_QUALITY_ECONOMY 0U
#define AR_QUALITY_BALANCED 1U
#define AR_QUALITY_STUDIO 2U

typedef uint32_t ar_fallback_stage_t;
#define AR_FALLBACK_CACHED_APPROVED_BRIDGE 0U
#define AR_FALLBACK_APPROVED_PROVIDER_CANDIDATE 1U
#define AR_FALLBACK_DETERMINISTIC_MULTI_STEM 2U
#define AR_FALLBACK_LEGACY_INTELLIGENT 3U
#define AR_FALLBACK_PHRASE_ALIGNED_CROSSFADE 4U
#define AR_FALLBACK_EMERGENCY_BASIC_CROSSFADE 5U
/* Source compatibility only. ABI v2 never returns this value. */
#define AR_FALLBACK_SILENCE UINT32_MAX

typedef uint32_t ar_engine_state_t;
#define AR_ENGINE_STOPPED 0U
#define AR_ENGINE_PLAYING 1U
#define AR_ENGINE_PAUSED 2U
#define AR_ENGINE_SEEKING 3U

typedef struct ar_engine ar_engine_t;
typedef ar_engine_t ar_audio_core_t;
typedef struct ar_audio_ring ar_audio_ring_t;

typedef struct ar_audio_view {
  const float *interleaved;
  uint64_t frames;
  uint32_t channels;
  uint32_t sample_rate;
} ar_audio_view_t;

typedef struct ar_stem_input {
  uint32_t struct_size;
  uint32_t stem_id;
  ar_stem_role_t role;
  ar_audio_view_t source_a;
  ar_audio_view_t source_b;
  float confidence;
  float continuity;
  float transient_stability;
  float harmonic_stability;
  float foreground;
  ar_audio_view_t generated;
} ar_stem_input_t;

typedef struct ar_capability {
  double render_realtime_factor;
  uint64_t memory_budget_bytes;
  uint32_t logical_cores;
  float thermal_headroom;
  float battery_fraction;
  uint32_t low_power_mode;
} ar_capability_t;

typedef int32_t (*ar_cancel_callback_t)(void *user_data);

typedef struct ar_bridge_request {
  uint32_t struct_size;
  uint32_t sample_rate;
  double bpm_a;
  double bpm_b;
  uint64_t duration_frames;
  double harmonic_distance;
  double stem_conflict_score;
  const ar_stem_input_t *stems;
  size_t stem_count;
  ar_capability_t capability;
  ar_cancel_callback_t cancel_callback;
  void *cancel_user_data;
  uint32_t has_previous_output;
  uint32_t has_next_output;
  float previous_output[2];
  float next_output[2];
} ar_bridge_request_t;

typedef struct ar_quality_diagnostics {
  uint32_t failure_mask;
  float peak;
  float preclip_peak;
  float dc_offset;
  float max_dc_jump;
  float max_sample_derivative;
  float max_second_derivative;
  float max_boundary_jump;
  float loudness_dbfs;
  float rhythm_error;
  float harmony_conflict;
  float stem_conflict;
  float minimum_anchor_gain;
  float anchor_rms;
} ar_quality_diagnostics_t;

typedef struct ar_bridge_result {
  uint32_t struct_size;
  float *interleaved;
  uint64_t capacity_samples;
  uint64_t frames_written;
  uint32_t channels;
  ar_fallback_stage_t fallback_stage;
  ar_quality_t quality;
  uint64_t plan_id;
  uint64_t search_expansions;
  ar_quality_diagnostics_t diagnostics;
} ar_bridge_result_t;

typedef struct ar_cache_key {
  const char *source_a_id;
  const char *source_b_id;
  uint64_t source_a_revision;
  uint64_t source_b_revision;
  uint64_t plan_id;
  uint32_t sample_rate;
  ar_quality_t quality;
  uint32_t planner_revision;
} ar_cache_key_t;

AR_AUDIO_CORE_API uint32_t ar_audio_core_abi_version(void);

/* Stable mobile facade. These aliases keep platform bindings independent of C++
 * names. */
AR_AUDIO_CORE_API ar_audio_core_t *ar_audio_core_create(void);
AR_AUDIO_CORE_API void ar_audio_core_destroy(ar_audio_core_t *core);
AR_AUDIO_CORE_API ar_status_t ar_audio_core_render_interleaved(
    ar_audio_core_t *core, const ar_bridge_request_t *request,
    ar_bridge_result_t *result);
AR_AUDIO_CORE_API ar_status_t ar_audio_core_set_paused(ar_audio_core_t *core,
                                                       int32_t paused);
AR_AUDIO_CORE_API ar_status_t ar_audio_core_seek(ar_audio_core_t *core,
                                                 uint64_t target_frame,
                                                 uint64_t *generation);

AR_AUDIO_CORE_API ar_engine_t *ar_engine_create(void);
AR_AUDIO_CORE_API void ar_engine_destroy(ar_engine_t *engine);

AR_AUDIO_CORE_API ar_quality_t
ar_engine_select_quality(const ar_capability_t *capability);

AR_AUDIO_CORE_API ar_status_t
ar_engine_render_bridge(ar_engine_t *engine, const ar_bridge_request_t *request,
                        ar_bridge_result_t *result);

AR_AUDIO_CORE_API ar_status_t ar_engine_start(ar_engine_t *engine);
AR_AUDIO_CORE_API ar_status_t ar_engine_pause(ar_engine_t *engine);
AR_AUDIO_CORE_API ar_status_t ar_engine_resume(ar_engine_t *engine);
AR_AUDIO_CORE_API void ar_engine_stop(ar_engine_t *engine);
AR_AUDIO_CORE_API ar_status_t ar_engine_seek(ar_engine_t *engine,
                                             uint64_t target_frame,
                                             uint64_t *generation);
AR_AUDIO_CORE_API ar_status_t ar_engine_complete_seek(ar_engine_t *engine,
                                                      uint64_t generation);
AR_AUDIO_CORE_API ar_status_t ar_engine_request_next(ar_engine_t *engine,
                                                     uint64_t track_id,
                                                     uint64_t *generation);
AR_AUDIO_CORE_API int32_t ar_engine_consume_next(ar_engine_t *engine,
                                                 uint64_t *track_id,
                                                 uint64_t *generation);
AR_AUDIO_CORE_API ar_engine_state_t
ar_engine_get_state(const ar_engine_t *engine);
AR_AUDIO_CORE_API uint64_t ar_engine_get_generation(const ar_engine_t *engine);

AR_AUDIO_CORE_API ar_audio_ring_t *
ar_audio_ring_create(uint32_t sample_capacity);
AR_AUDIO_CORE_API void ar_audio_ring_destroy(ar_audio_ring_t *ring);
AR_AUDIO_CORE_API uint32_t ar_audio_ring_write(ar_audio_ring_t *ring,
                                               const float *samples,
                                               uint32_t count);
AR_AUDIO_CORE_API uint32_t ar_audio_ring_read(ar_audio_ring_t *ring,
                                              float *samples, uint32_t count);
AR_AUDIO_CORE_API uint32_t
ar_audio_ring_available_read(const ar_audio_ring_t *ring);
AR_AUDIO_CORE_API uint32_t
ar_audio_ring_available_write(const ar_audio_ring_t *ring);

AR_AUDIO_CORE_API uint64_t ar_cache_key_hash(const ar_cache_key_t *key);

#ifdef __cplusplus
}
#endif

#endif
