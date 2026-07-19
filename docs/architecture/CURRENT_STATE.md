# Current state audit

Audit baseline: local root commit `5406113`. The linked GitHub repository had no
commits or files when the audit began.

## Confirmed implementation

- Android-only Java 17 source with raw Views and a foreground service.
- MediaStore scan of local audio URIs.
- MediaCodec window and segment decode.
- Five-window heuristic BPM, key, onset, loudness, and role analysis.
- One pre-rendered PCM programme feeding one Java `AudioTrack`.
- WSOLA tempo adjustment and onset-envelope phase alignment.
- Complementary four-role HPSS + mid/side decomposition.
- Nine deterministic stem gain narratives.
- DC blocking, soft limiting, scene-edge fades, and synthetic smoke tests.

The separator is a deterministic spectral approximation. It is not a neural
separator and does not yield studio-clean isolated stems.

## Confirmed gaps

- No shared native core, Oboe/AAudio path, iOS target, Gradle project, or CI.
- No explicit anchor or independent per-stem transition timelines.
- No bounded candidate search or diagnostic quality evaluator.
- No model providers, model weights, embeddings, instrument classifier, vocal
  chops, or neural continuation.
- No capability benchmark or adaptive tier selection.
- No persistent cache, budget, LRU, pinning, or schema/model invalidation.
- No seek-safe planning epoch, guaranteed end fallback, rapid-next coalescing,
  likes/dislikes, or durable recommendation queue.
- No WorkManager analysis, MediaSession controls, Compose UI, or screenshot
  harness.
- No automated boundary click detector or measured device benchmarks.

## Defects found

- Short continuation requests can be forced to eight seconds after available
  audio reaches zero, making the following guard unreachable.
- Transition state can advance by the requested duration after decode returns a
  shorter tail.
- End-of-track incompatibility can stop playback instead of using a guaranteed
  bridge.
- Progress uses wall time, so pause can advance the displayed position.
- Render failures are broadly caught and may retry without a bounded policy.
- Whole-scene materialization and STFT matrices can exceed mobile memory
  budgets on long scenes.
- Several decode and library failures are swallowed.

## Reuse decision

Retain the FFT, WSOLA, beat-phase alignment, analysis heuristics, complementary
separator, envelope shapes, and generated synthetic fixtures as a Tier-C
fallback and porting reference. Replace the service-owned control flow,
unbounded scene ownership, UI, output callback, cache, plan schema, and build.
