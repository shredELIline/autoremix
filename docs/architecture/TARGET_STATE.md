# Target state

## Data plane

`audio-core` is portable C++17 behind a stable C ABI. Android and iOS own their
audio devices but pull the same interleaved float render graph.

- fixed-size preallocated blocks;
- lock-free single-producer/single-consumer queues;
- atomic immutable-plan handoff;
- sample clock as the only audible timeline;
- sample-accurate automation and persistent DSP state;
- no callback allocation, locks, I/O, inference, network, or logging.

## Control plane

Platform decoders and background schedulers feed replaceable providers for
analysis, separation, classification, embeddings, continuation, and optional
learned quality prediction. Technical quality gates remain authoritative.

Planning uses bounded beam search. Each candidate contains:

- arbitrary exit/entry samples;
- one algorithm-selected anchor;
- independent timelines for every available stem;
- original-A, original-B, or generated provenance;
- gain, pan, width, filter, EQ, pitch, formant, tempo, transient, harmonic,
  tail, and morph automation;
- a bridge-wide anchor-coverage proof;
- quality diagnostics and a reproducible cache key.

Generated provenance is invalid for vocal roles. Vocal chops may reference only
fragments decoded from the two source tracks.

## Quality tiers

- Tier A: optional licensed neural providers after device measurement.
- Tier B: compact providers plus deterministic DSP after measurement.
- Tier C: deterministic separation, looping, granular continuation, WSOLA,
  phase alignment, and staged stem replacement.

The application selects a tier from measured render throughput, memory,
battery, and thermal signals. Network type never selects compute quality.

## Platform shells

Android uses Kotlin, Compose, WorkManager, scoped MediaStore access, a foreground
media service, MediaSession, and Oboe/AAudio. iOS uses SwiftUI, AVAudioEngine,
background audio, BGProcessingTask, Now Playing, and remote commands.

Both restore queue, feedback, metadata, cache manifests, and valid transition
plans locally. No backend is required.
