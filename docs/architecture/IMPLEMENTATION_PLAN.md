# Implementation plan

| Phase | Deliverable | Exit gate |
| --- | --- | --- |
| 0 | Audit, reproducible builds, ADRs | Baseline facts recorded; CI parses all targets |
| 1 | Shared PCM core and C ABI | Host DSP tests pass; callback path allocates and locks zero times |
| 2 | Versioned analysis/stem cache | Budget, LRU, pinning, corruption, and restart tests pass |
| 3 | Independent stem planner | Anchor coverage, arbitrary points, vocal provenance, and beam-search tests pass |
| 4 | Procedural bridge and evaluator | Every fixture passes finite/peak/DC/click hard gates |
| 5 | Playback lifecycle and queue | Pause, seek epoch, EOF fallback, Next, rapid Next, and feedback tests pass |
| 6 | Android integration | Debug APK and instrumented UI tests pass on CI/emulator |
| 7 | iOS integration | Simulator build and tests pass on macOS CI |
| 8 | Measured model spikes | License, checksum, size, RAM, latency, and device matrix committed |
| 9 | Showcase and release | Renderer screenshots, demo licenses, SBOM, checksums, APK/AAB attached |

Neural tiers are not release blockers. A deterministic Tier-C vertical slice is
the first complete product path. A model may replace a component only after it
passes the same quality and fallback contracts.
