# Architecture

AutoRemix separates the realtime data plane from the offline control plane.
The C++ core is the portable planner/render contract. The iOS bridge renders
through it. Android currently uses it for native lifecycle/output primitives
while the retained Java Tier-C renderer is ported behind the same contract.

```mermaid
flowchart LR
  Media["Platform media decoder"] --> Analysis["Offline analysis"]
  Analysis --> Core["Shared C++ plan / render contract"]
  Analysis --> AndroidProvider["Retained Android Tier-C renderer"]
  Core --> Blocks["Prepared PCM blocks"]
  AndroidProvider --> Blocks
  Blocks --> Session["Playback session"]
  Session --> Ring["Preallocated SPSC ring"]
  Ring --> Output["AAudio/Oboe or AVAudioEngine"]
```

The callback side only reads pre-rendered blocks. It does not decode, allocate,
lock, infer, read files, log, or access the network.

Start with [current state](docs/architecture/CURRENT_STATE.md), then read the
[target state](docs/architecture/TARGET_STATE.md),
[implementation plan](docs/architecture/IMPLEMENTATION_PLAN.md), and ADRs in
`docs/architecture/decisions/`.
