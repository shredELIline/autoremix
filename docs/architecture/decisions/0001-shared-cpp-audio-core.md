# ADR 0001: Shared C++ audio core

- Status: Accepted
- Date: 2026-07-19

## Decision

Use a portable C++17 audio and transition core behind a C ABI. Android uses a
JNI/Oboe adapter. iOS uses an Objective-C++/AVAudioEngine adapter.

## Why

The baseline Java `AudioTrack` engine proves the single-master approach but
cannot share its render graph with iOS. C++ provides one sample clock, explicit
memory ownership, preallocation, and portable deterministic tests.

## Consequences

Platform decoders and lifecycle APIs remain native to each OS. C ABI changes
require versioning. Xcode validation remains a macOS CI responsibility.
