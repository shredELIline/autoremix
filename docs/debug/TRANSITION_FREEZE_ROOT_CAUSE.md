# Transition freeze root cause

Audit baseline: `v2.2.0` (`ea66d2b`), 2026-07-19.

## Confirmed failure path

The Android runtime owns one Oboe/AAudio stream, with `AudioTrack` only as device fallback. It does not create a second player at a normal transition boundary.

The discontinuity is above the device stream:

1. `RemixEngineService.bootstrap()` queues a roughly 52-second `RenderedScene`.
2. `renderAhead()` renders an independent emergency crossfade `RenderedScene` first.
3. `runEnhancedCandidate()` may later replace that queued scene with an HPSS stem mix.
4. `SceneAudioPlayer` holds the last 14 ms of the current scene and waits for the next scene object.
5. If the queue is empty, it fades and writes the held tail, increments `queueUnderruns`, and sleeps in 20 ms intervals.
6. The native callback then drains its approximately 500 ms SPSC queue and emits zeroes on shortage.

That queue-empty branch is the exact software path that creates the reported freeze/gap. Decoder work, spectral separation, WSOLA, and mastering all happen before enqueue, but their variable latency decides whether the separately rendered scene reaches the queue in time.

## Boundary work in the baseline

- No new `MediaPlayer`, Media3 item, Oboe stream, or `AudioTrack` is created for a normal transition.
- No compressed-media decoder is created in the native callback.
- The Java output thread allocates head, overlap, and tail arrays at each `RenderedScene` boundary.
- Per-scene mastering resets DC-blocker and limiter state before enqueue.
- Transition activation is tied to dequeuing a scene object, not to envelope activation on the master sample clock.
- `activationBoundary` is derived from source-track milliseconds. It is not the persistent output sample clock.
- A seek flushes the device queue by design. Normal transition activation does not.

## Other confirmed defects

- The guaranteed first candidate is a full-mix emergency crossfade, not a stem scene.
- The deterministic stem render is optional replacement work. Missing its replacement window silently leaves the crossfade audible.
- Several legacy gain families allow both lead stems above audible level.
- Landing is signalled when the transition scene ends, producing a second control boundary before the next B scene.
- `fallbackReason` records ordinary status strings and does not prove why a legacy strategy was required.

## Reproduction status

The queue-starvation branch and underrun counter are confirmed by code and deterministic host tests. No physical-device trace was available in this Windows workspace. Device-specific callback timing remains to be measured; the architectural cause does not depend on a particular handset.

