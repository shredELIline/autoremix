# Hardcoded timeline audit

Scope: playback position, song duration, transition start, transition length, landing, and test activation boundaries.

## Removed playback assumptions

Line numbers refer to `v2.3.0`.

| File | Line | Old value | Purpose | Bug impact | Replacement |
|---|---:|---:|---|---|---|
| `RemixEngineService.java` | 349 | `52_000 ms` | First decoded scene | UI treated a technical scene as the song | Planner marker determines decoded runway; metadata remains the public duration |
| `RemixEngineService.java` | 113, 523, 824 | `20_000 ms` | Runway chunk | Every later scene reset visible duration/position | Renamed decode granularity; `SceneTimelineMapping` projects each chunk into original-song time |
| `SceneRenderer.java` | 32 | minimum `20_000 ms` | Prelude decode floor | Overrode an earlier musical marker | Decode accepts the planner request, bounded only by available media |
| `SceneRenderer.java` | 192–194 | `16` transition bars, `8` landing bars | Continuous scene sizing | Transition and landing cadence were fixed | Timeline analysis selects 6–16 transition bars; the accepted plan selects 4–6 landing-runway bars |
| `SceneRenderer.java` | 238, 260 | divisor/multiplier `16` | Derived samples per bar and buffer | Made every plan internally assume 16 bars | Samples per bar come from analyzed beat period; buffer uses actual transition samples |
| `ContinuousScenePlanner.java` | 17, 232 | `TRANSITION_BARS = 16` | Default plan duration | Callers could silently get a fixed transition | Every `PlanningRequest` must provide explicit `transitionSamples` |
| `AudioContinuityValidatorSmokeTest.java` | regression fixture | `51 s` only | Activation continuity check | Encoded the reported boundary as a special invariant | Parameterized boundaries at 7, 19, 37, and 51 seconds |

## Intentional fixed values

These values do not define the public musical timeline:

- `DECODE_CHUNK_TARGET_MS = 20_000`: decode and queue granularity only.
- `FALLBACK_BRIDGE_MS = 12_000`: bounded emergency fallback, used only after structured planning fails.
- `6–16` transition bars: variable musical planning policy chosen before a plan is accepted; no accepted duration is cropped during rendering.
- Decoder deadlines, fade lengths, output block sizes, and low-watermark bar counts: technical safety/buffering controls.
- Android screenshot timeout `20_000` and screenshot fixture duration `252_000`: test infrastructure/data.

## Timeline authority

- Media metadata owns full song duration.
- `MusicalTimelinePlanner` owns entry, next transition, planned exit, and estimated transition length.
- `SceneTimelineMapping` owns conversion from master samples to source/target song positions.
- `ContinuousSceneTransitionPlan` owns stem transformation ranges and full-target ownership.
- `PlaybackUiSnapshot` is the only atomic UI projection. Pause changes its outer phase to `PAUSED`; the nested transition phase remains active/landing.
- Full landing is published at the mapped sample boundary only after target ownership and the continuity quality gate pass. Post-landing PCM may continue without keeping transition UI active.

## Regression coverage

- `MusicalTimelinePlannerTest`: 128 profiles, variable BPM, duration, marker, and transition length.
- `MusicalTimelinePlannerTest`: metadata duration, non-zero target position, mapped landing boundary, pause state, and target-ownership landing semantics.
- `AudioContinuityValidatorSmokeTest`: activation boundaries at 7, 19, 37, and 51 seconds.
- `ContinuousSceneEngineSmokeTest`: persistent graph activation at 11, 29, and 51 seconds.

Audit command:

```powershell
rg -n "52_000|51_000|currentDurationMs\(\)|transitionMs \* .* / 16_000" src platform-android tests
```

Expected: no production playback-timeline dependency on those values. `currentDurationMs()` may remain inside audio buffering code; it must not feed public song duration.
