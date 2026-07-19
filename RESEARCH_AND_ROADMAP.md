# Research basis and roadmap

## Current design

AutoRemix uses one PCM timeline and one output clock. Independent stem events
can start, stop, filter, time-stretch, pitch-shift, widen, or morph without
creating independent player clocks. A source fragment plays once by default;
one bounded repeat is permitted only inside a larger varied scene. The director
chooses an anchor from analyzed material; it is not hard-coded to vocals.

The shared C++ core exposes a versioned C ABI, exact progressive lifecycle,
preprocessed continuation graph, bounded non-repeating planner, repetition
evaluator, rolling PCM horizons, deterministic fallback ladder, SPSC ring,
procedural bridge renderer, and output diagnostics. Android currently feeds the
native Oboe output from its retained Java Tier C renderer. iOS connects the C++
renderer to an `AVAudioSourceNode` but does not yet include a local media
decoder.

## Why stems matter

A transition is an arrangement change, not only a master-bus crossfade. Separately controlled musical
roles let one viable anchor remain audible while rhythm, harmony, bass, texture, and lead responsibilities
move at musically useful points. Every automatic path still has a non-silent, bounded fallback.

## Priority work

1. Make the C++ renderer the Android end-to-end scene source; add parity fixtures at the Java/native boundary.
2. Add Android audio-focus, noisy-route, interruption, and device-level underrun tests.
3. Add iOS media-library decode/cache coordination; verify builds, background work, remote controls,
   interruptions, and routes on macOS and physical devices.
4. Measure named phones for latency, memory, battery, thermals, cache size, and sustained playback.
5. Harden the persisted analysis cache with corrupt-record, schema-invalidation, restart, and
   storage-budget eviction tests.

## Optional neural tier

Keep a neural separator or generator behind the existing provider boundary. Select a mobile runtime and
model only after license provenance, download consent, checksum verification, RAM, latency, thermal,
leakage, and fallback behavior are measured. Do not make network access or a model download mandatory.

Later work may add richer phrase/downbeat structure, preference learning from explicit feedback, and
authorized source adapters. Export and streaming integrations require separate product and licensing review.
