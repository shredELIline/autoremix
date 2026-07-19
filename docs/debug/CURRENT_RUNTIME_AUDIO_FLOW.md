# Current runtime audio flow

Audit baseline: `v2.2.0` (`ea66d2b`). This document describes the pre-refactor Android path.

```text
MediaStore track A
  -> MediaCodec decode on worker
  -> per-scene mastering and resample
  -> RenderedScene A (~52 s)
  -> Java scene queue

parallel control path
  -> select B with heuristic metadata
  -> render emergency full-mix crossfade scene
  -> optionally decode A/B again
  -> WSOLA + phase align
  -> deterministic spectral pseudo-stems
  -> LayerTransitionMixer
  -> optionally replace queued crossfade scene

Java output thread
  -> dequeue scene
  -> 14 ms scene-to-scene overlap
  -> enqueue float PCM into native SPSC ring

one native callback
  -> Oboe/AAudio stream
  -> AudioTrack only after device-output failure
```

## Source and transition boundaries

- Source PCM ends when `SceneAudioPlayer` reaches the held tail of the current `RenderedScene`.
- Transition PCM begins when the next transition-marked `RenderedScene` is dequeued.
- The bridge is an in-memory PCM array, not a persisted WAV, but it is still a separately scheduled audible programme segment.
- The output stream stays open. Scene identity, clock origin, mastering state, and listener state change at the boundary.

## Realtime guarantees in the baseline

The Oboe callback is lock-free and allocation-free. It only reads the SPSC ring and writes zeroes on shortage. ML, decoding, storage reads, logging, and spectral separation do not run in that callback.

The producer side does not yet satisfy a continuous scene graph:

- it queues monolithic scenes;
- it can consume the final tail before a successor exists;
- it resets scene-relative clocks;
- it allocates overlap buffers per scene;
- it does not keep A/B/generated stem nodes attached to one persistent graph;
- it cannot prove an 8-bar prepared stem horizon at activation.

## Refactored Android flow

```text
decode A/B ahead -> WSOLA/phase alignment -> deterministic pseudo-stems
  -> structured candidate search + hard vetoes
  -> immutable ContinuousSceneTransitionPlan
  -> preloaded PreparedStemScene
       A nodes + B nodes + optional generated instrumental nodes
       per-stem gain/pitch/formant/tempo/pan/width/EQ/filter/reverb/
       transient/harmonic/timbre automation
  -> persistent 48 kHz stereo MasterAudioGraph
       bus -> DC blocker -> lookahead limiter -> continuity meters
  -> one native SPSC ring -> one Oboe callback/output stream
```

The prepared scene contains the A→hybrid→clean-B landing and eight bars of B.
The next B runway is prepared before activation. Normal activation does not
open media, create a decoder, allocate boundary arrays, seek, flush the ring,
or restart graph nodes. Explicit user seek remains the only normal graph-state
restart path.
