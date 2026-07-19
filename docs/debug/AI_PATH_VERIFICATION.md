# AI and stem path verification

Audit baseline: `v2.2.0` (`ea66d2b`).

## Verdict

Android does not currently execute an AI transition planner. No neural model or weights ship. The UI state `NEURAL_CANDIDATES_PENDING` is a lifecycle label, not evidence of model inference.

## What runs

- `chooseCandidate()` scores at most five cached tracks with deterministic metadata heuristics.
- `ContinuityDirector.plan()` returns one coarse `LayerPlan` family.
- `SceneRenderer.renderTransition()` decodes and separates A/B with a deterministic spectral approximation.
- `LayerTransitionMixer` applies hard-coded per-layer gain curves.
- The C++ core contains richer `StemTimeline`, anchor selection, candidate search, and quality gates, but Android JNI uses that core only for lifecycle/output primitives. The C++ bridge planner is not wired into Android playback.

## What was not proven in the baseline

- No model returns a structured semantic scene plan.
- No AI-selected anchor reaches Android playback.
- No runtime diagnostic records candidate scores and rejections.
- No strategy counters prove AI/stem/legacy selection rates.
- No fallback reason enum is enforced.
- No Android test distinguishes a neural plan from deterministic mode.

## Stem-path reality

The deterministic separator is used only when the asynchronous enhanced render completes and replaces the queued emergency crossfade before playback. Otherwise the audible path is the full-mix crossfade. Therefore stem use is real but not guaranteed or primary.

The four Android pseudo-stems are lead, drums, bass, and backing. They are complementary DSP estimates, not studio-clean or neural stems. Backing-vocal ownership cannot be proven independently from the combined backing stem.

## Vocal collision

The baseline `LayerTransitionMixer` contains families where A and B lead gains overlap. Side-chain ducking lowers backing layers; it does not enforce exclusive lead ownership. A strict owner validator and an instrumental handoff interval are required.

## Required truth labels

- Built-in path: `DETERMINISTIC_LAYERED_STEM` when validation succeeds.
- Optional provider path: `AI_LAYERED_STEM` only after a provider returns a structured plan and diagnostics record that fact.
- Legacy/crossfade path: always include a machine-readable fallback reason.

