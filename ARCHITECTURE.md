# Architecture

AutoRemix separates the realtime data plane from the offline control plane.
The C++ core is the portable planner/render contract. The iOS bridge renders
through it. Android currently uses it for native lifecycle/output primitives
while the retained Java Tier-C renderer is ported behind the same contract.

```mermaid
flowchart LR
  Media["Platform media decoder"] --> Analysis["Offline analysis and preprocessing"]
  Analysis --> Stems["Prepared A/B/generated stem buffers"]
  Stems --> Planner["Structured candidate search and hard vetoes"]
  Planner --> Plan["ContinuousSceneTransitionPlan"]
  Plan --> Scene["PreparedStemScene automation and processors"]
  Scene --> Master["Persistent 48 kHz stereo MasterAudioGraph"]
  Master --> Ring["Preallocated SPSC ring"]
  Ring --> Output["AAudio/Oboe or AVAudioEngine"]
```

The callback side only reads pre-rendered blocks. It does not decode, allocate,
lock, infer, read files, log, or access the network.

## Progressive transition invariants

Target selection and audible activation are separate. Track A follows its
natural runway while A/B stems, automation, processors, B landing, and a B
runway are prepared. `TRANSITIONING` is forbidden until one valid plan is
`ARMED`. Activation changes envelopes on the existing master timeline.

The deterministic stem planner evaluates ten scene families. Hard vetoes reject
double lead vocals, missing anchors, unready buffers, underrun risk, and sample
discontinuity. If no stem plan survives, fallback order is legacy intelligent,
phrase-aware crossfade, then basic crossfade. The reason is mandatory.

The rolling horizons are:

- committed: 2 bars, immutable;
- guaranteed rendered: at least 8 bars of playable PCM;
- target rendered: 16–32 bars;
- planning: 32–64 bars.

No neural provider, model, or weights are bundled. The shipped Android path is
deterministic Tier C and reports `aiUsed=false`. The provider boundary can add
an AI-authored structured plan later; it cannot return a monolithic bridge WAV.

The control plane exposes plan provenance, strategy, anchor, vocal/stem owner
timelines, scores, vetoes, horizons, underruns, continuity metrics, statistics,
and fallback reason. It logs outside the callback and exports no user audio.
Named-device latency, memory, battery, thermal, and underrun traces remain
required.

Start with [current state](docs/architecture/CURRENT_STATE.md), then read the
[target state](docs/architecture/TARGET_STATE.md),
[implementation plan](docs/architecture/IMPLEMENTATION_PLAN.md), and ADRs in
`docs/architecture/decisions/`.
