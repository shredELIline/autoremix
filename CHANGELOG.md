# Changelog

## 2.3.0 — Continuous stem scene engine

- Replaced separately scheduled Android transition PCM with one structured stem
  scene rendered through the persistent 48 kHz stereo master graph.
- Added ten deterministic candidate families, multidimensional scoring, hard
  vetoes, explicit anchor coverage, and strict single-source vocal ownership.
- Added independent A/B/generated stem timelines with per-stem automation,
  non-vocal generated fills, an in-scene clean-B landing, and a prepared B
  runway.
- Added deadline-aware preparation, real fallback ordering and reasons, plus a
  transition inspector with activation, buffer, click, loudness, and spectral
  diagnostics.
- Added exact 51-second activation, landing, vocal-conflict, fallback, golden
  audio, and 1,000-case deterministic planner coverage.
- Fixed scene-duration integer overflow and removed transition-boundary buffer
  allocation from the primary playback path.
- No neural model or weights are bundled. Physical-device performance and
  listening verification remain required and are not claimed by this release.

## 2.2.0 — Progressive non-repeating transitions

- Removed indefinite anchor hold loops. Source material now plays once by
  default and may repeat at most once inside a bounded varied scene.
- Added the explicit transition lifecycle, natural runway, guarded musical
  activation boundaries, stale-plan rejection, and deterministic end fallback.
- Added versioned preprocessing descriptors, continuation graphs and
  reservoirs, bounded 16–32 bar beam planning, repetition metrics, and rolling
  committed/rendered/planning horizons.
- Added Level 0/1/2 candidate contracts. Slow or failed neural work can only
  improve uncommitted future audio and cannot block deterministic playback.
- Integrated non-repeating continuation, low-watermark recovery, cancellation,
  diagnostics, and anonymized report export into the Android provider.
- Added state, repetition, buffer, click, cancellation, cache-corruption, and
  90-second golden-scenario coverage. No neural model or weights are included.
- Real-device latency, memory, battery, thermal, inference, and sustained
  underrun benchmarks remain required and are not claimed by this release.

## 2.1.1 — Versioned APK delivery

- Fixed the Android 37 CI package identifier used by release builds.
- Added an immutable latest-APK release while retaining source-only 2.0.0 and
  2.1.0 history.

## 2.1.0 — Shared core vertical slice

- Added a portable C++17 transition planner, renderer, evaluator, lifecycle,
  bounded queues, cache identities, C ABI, host tests, fuzzing, and benchmark.
- Added Gradle, Kotlin/Compose, Media3, WorkManager, and Oboe integration for
  Android while retaining the deterministic Java analysis/stem provider.
- Added a SwiftUI/AVAudioEngine iOS target and macOS CI job. Local iOS build
  verification remains unavailable in the Windows workspace.
- Added a persistent bounded Android analysis cache, seek epochs, rapid-Next
  coalescing, feedback, and guaranteed continuity fallbacks.
- Added open-source policy files, ADRs, CI/security/Pages workflows, licensed
  synthetic audio, renderer screenshot harness, and static showcase.
- Added cross-platform version validation and tag-based APK releases with
  checksums and retained version history.
- No neural model or model weights are included.

## 2.0.0 — PCM Stem Director

- Replaced two vendor-controlled MediaPlayer decks with one pre-rendered PCM timeline and one
  AudioTrack master output.
- Added MediaCodec segment decoding and linear sample-rate conversion.
- Added local four-role separation: lead, drums, bass, and backing via complementary STFT HPSS plus
  center/side analysis.
- Added WSOLA tempo matching with a strict ±10% safety range.
- Added incoming beat-phase alignment using onset-envelope correlation inside one beat.
- Added nine continuity-only layer narratives; no automatic whole-track cut exists in the new engine.
- Added vocal-priority sidechain space management and sequential lead relay for two vocal tracks.
- Added strict same-vibe filtering and a two-step local vibe graph to avoid future route dead ends.
- Added controlled top-beam randomness so the output remains a random remix without sacrificing a
  clearly better match.
- Added sample-level cosine automation, complementary-stem endpoint reconstruction, DC blocking,
  limiter, soft saturation, and scene-boundary crossfades.
- Fixed an internal normalisation error that could drop level at the exact start of a stem scene.
- Fixed sidechain activation before an incoming lead was audible.
- Removed constant-sample padding that could create DC edges.
- Limited render-ahead to one queued scene to reduce peak memory pressure.
- Added reconstruction, layer endpoint, continuity director, beat phase, WSOLA, and master-bus tests.
