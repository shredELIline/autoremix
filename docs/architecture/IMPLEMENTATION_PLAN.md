# Implementation plan

| Phase | Status | Deliverable | Remaining exit gate |
| --- | --- | --- | --- |
| 0 | Complete | Audit, reproducible builds, ADRs | — |
| 1 | Complete | Shared PCM core, C ABI, and SPSC output | — |
| 2 | Partial | Versioned bounded analysis and preprocessing cache contracts | Persist and pin all native stem/chunk artifacts on both platforms |
| 3 | Complete | Independent stem and non-repeating continuation planners | — |
| 4 | Complete | Deterministic bridge, repetition evaluator, and click gates | — |
| 5 | Complete | State machine, rolling horizons, cancellation, EOF fallback, and queue epochs | — |
| 6 | Partial | Android app, Oboe output, unit/lint CI, screenshot harness, preview APK | Physical-device instrumentation and stable signing |
| 7 | Partial | iOS target with simulator build/tests in macOS CI | Physical-device audio, background, interruption, and signing verification |
| 8 | Pending | Measured provider/device spikes | Named-device latency, RAM, battery, thermal, inference, and underrun matrix |
| 9 | Partial | Showcase, licensed fixtures, checksums, tagged preview APK | SBOM, signed AAB/IPA, reproducible-build comparison |

Neural tiers are not release blockers. A deterministic Tier-C vertical slice is
the first complete product path. A model may replace a component only after it
passes the same quality and fallback contracts.
