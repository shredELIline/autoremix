# Current state

Snapshot: `2.2.0` release candidate, 2026-07-19.

## Confirmed implementation

- Portable C++ audio core with a stable C ABI, deterministic bridge planner,
  renderer, technical quality evaluator, lifecycle epochs, and SPSC PCM ring.
- Android structured `ContinuousSceneTransitionPlan`, ten deterministic stem
  candidate families, anchor selection, strict vocal ownership, and explicit
  legacy/phrase/basic fallback reasons.
- Versioned preprocessing representation with 1/2/4/8-bar and phrase chunks,
  graph indexes, resumable chunk progress, embeddings, reusable conditioning,
  bounded serialization, and corruption rejection.
- Continuation reservoir, graph, bounded 16–32 bar beam planner, hard repetition
  constraints, and arrangement novelty metrics.
- Persistent 48 kHz stereo `MasterAudioGraph`, one sample clock/output stream,
  incremental `PreparedStemScene` rendering, a 16-bar handoff, an 8-bar in-scene
  B landing, and a prepared B runway.
- Android Kotlin/Compose shell, WorkManager preprocessing, Media3 session,
  retained Java deterministic provider, Oboe/AAudio output, and AudioTrack
  fallback.
- Android natural runway, preloaded A/B stem nodes, sample-accurate per-stem
  automation, low-watermark recovery, seek epochs, stale-plan rejection,
  continuity metrics, strategy statistics, inspector UI, and anonymized JSON.
- iOS SwiftUI/AVAudioEngine target using the shared C ABI. macOS CI builds and
  tests the simulator target.
- Host and Android unit tests, deterministic fuzz, exact 51.000-second graph
  activation, vocal collision, fallback order, landing, click, and golden-audio
  tests.

The separator is a deterministic spectral approximation. It is not a neural
separator and does not yield studio-clean isolated stems.

## Confirmed gaps

- No neural continuation provider, model, or weights ship. Level 2 is a guarded
  provider contract, not a performance claim.
- Physical Android and iOS latency, memory, battery, thermal, inference, audio
  interruption, and sustained-underrun measurements are not available.
- Android publishes a CI-built debug-preview APK. Stable signing, Play-ready
  AAB delivery, SBOM publication, and reproducible-build comparison remain.
- The iOS target has no public IPA and still needs physical-device and signing
  verification.
- Android still uses the retained deterministic Java analysis/separation path;
  full shared-core planning/render integration remains incremental.
- The Java producer owns stem automation and feeds the native SPSC ring. Native
  callback-side stem mixing is not implemented.

## Release invariant

Playback must never wait on optional expensive generation by repeating one
short musical fragment indefinitely. Natural source runway or pre-rendered,
non-repeating deterministic continuation must remain available first.
