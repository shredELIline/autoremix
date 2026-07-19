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

Transition preparation and audible activation are separate. The minimum
deterministic candidate is rendered first, then armed for a future musical
boundary. A missed boundary advances to the next valid boundary without
starting a hold loop.

Every track exposes versioned 1/2/4/8-bar and phrase chunk banks, continuation
graphs, entry/exit indexes, repetition fingerprints, and resumable preprocessing
jobs. Planning keeps 2 committed bars immutable, at least 8 rendered bars
playable, a 16–32 bar target, and 32–64 bars of lookahead. Expensive providers
may improve only uncommitted future audio.

## Quality tiers

- Tier A: optional licensed neural providers after device measurement.
- Tier B: compact providers plus deterministic DSP after measurement.
- Tier C: deterministic separation, non-repeating graph continuation, bounded
  variation, WSOLA, phase alignment, and staged stem replacement.

The application selects a tier from measured render throughput, memory,
battery, and thermal signals. Network type never selects compute quality.

## Platform shells

Android uses Kotlin, Compose, WorkManager, scoped MediaStore access, a foreground
media service, MediaSession, and Oboe/AAudio. iOS uses SwiftUI, AVAudioEngine,
background audio, BGProcessingTask, Now Playing, and remote commands.

Both restore queue, feedback, metadata, cache manifests, and valid transition
plans locally. No backend is required.
