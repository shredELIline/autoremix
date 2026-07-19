# Current state

Snapshot: `2.2.0` release candidate, 2026-07-19.

## Confirmed implementation

- Portable C++ audio core with a stable C ABI, deterministic bridge planner,
  renderer, technical quality evaluator, lifecycle epochs, and SPSC PCM ring.
- Exact progressive transition state machine. Target selection never starts
  audible transition audio; activation requires a valid candidate and a future
  musical boundary.
- Versioned preprocessing representation with 1/2/4/8-bar and phrase chunks,
  graph indexes, resumable chunk progress, embeddings, reusable conditioning,
  bounded serialization, and corruption rejection.
- Continuation reservoir, graph, bounded 16–32 bar beam planner, hard repetition
  constraints, and arrangement novelty metrics.
- Rolling 2-bar committed, 8-bar guaranteed, 16–32 bar rendered, and 32–64 bar
  planning contracts. Optional neural results can replace only uncommitted
  future PCM at a safe boundary.
- Android Kotlin/Compose shell, WorkManager preprocessing, Media3 session,
  retained Java deterministic provider, Oboe/AAudio output, and AudioTrack
  fallback.
- Android natural runway, instant Level 0 fallback, finite scene deque,
  low-watermark recovery, rapid-Next coalescing, seek epochs, stale-plan
  rejection, and anonymized diagnostic export.
- iOS SwiftUI/AVAudioEngine target using the shared C ABI. macOS CI builds and
  tests the simulator target.
- Host, Android unit, deterministic fuzz, click, cancellation, cache, buffer,
  and 90-second slow-generation tests.

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

## Release invariant

Playback must never wait on optional expensive generation by repeating one
short musical fragment indefinitely. Natural source runway or pre-rendered,
non-repeating deterministic continuation must remain available first.
