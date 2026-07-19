# AutoRemix iOS platform

Real iOS 16+ application target for the shared AutoRemix audio core.

## Included

- SwiftUI now-playing UI with waveform scrubbing, current/next tracks, transition readiness,
  queue, Back, Next, Like, Dislike, and storage-budget controls.
- `AVAudioSession` playback category and background-audio declaration.
- `AVAudioEngine` + `AVAudioSourceNode` output. The realtime callback only pulls Float32 PCM from
  the shared core ring and deinterleaves into the Audio Unit buffers.
- ObjC++ adapter for `../audio-core/include/autoremix/audio_core_c.h`.
- `BGProcessingTask` registration and a cancellable pending-analysis hook.
- Now Playing metadata and play/pause/seek/next/previous/like/dislike remote commands.
- Deterministic SwiftUI preview fixtures and renderer-based unit tests.

The Xcode target compiles `../audio-core/src/audio_core.cpp` and
`../audio-core/src/audio_core_c.cpp` directly. All paths are relative to the repository. Keep
platform-specific C ABI adaptation isolated in `AutoRemix/Audio/NativeAudioBridge.mm`.

## Requirements

- macOS with Xcode 16 or newer.
- An installed iOS 16+ Simulator runtime.
- The shared `audio-core` directory at the repository root.

No third-party package manager is required.

## Build

From the repository root:

```bash
cd platform-ios
xcodebuild \
  -project AutoRemix.xcodeproj \
  -scheme AutoRemix \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Device archives require a valid Apple development team and signing configuration:

```bash
cd platform-ios
xcodebuild \
  -project AutoRemix.xcodeproj \
  -scheme AutoRemix \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  archive \
  -archivePath "$PWD/build/AutoRemix.xcarchive"
```

## Tests

Choose an available simulator name. Example:

```bash
cd platform-ios
IOS_SIMULATOR="${IOS_SIMULATOR:-iPhone 16 Pro}"
xcodebuild \
  -project AutoRemix.xcodeproj \
  -scheme AutoRemix \
  -configuration Debug \
  -destination "platform=iOS Simulator,name=$IOS_SIMULATOR,OS=latest" \
  CODE_SIGNING_ALLOWED=NO \
  test
```

List valid destinations when that simulator is not installed:

```bash
xcodebuild -project platform-ios/AutoRemix.xcodeproj -scheme AutoRemix -showdestinations
```

`PreviewFixturesTests` renders the same SwiftUI fixture twice and compares SHA-256 digests. The
fixture metadata is synthetic and exists only for previews/tests. It is not presented as a real app
screenshot or generated music.

## Background processing

The permitted identifier is `com.alexey.autoremix.analysis`. Enqueue real offline analysis work
through `BackgroundAnalysisQueue`; the scaffold does not invent completed analysis when no job is
queued. The scheduler requests no network access.

## Shared-core contract

The adapter uses these C ABI entry points:

- `ar_audio_core_create` / `ar_audio_core_destroy`;
- `ar_audio_core_render_interleaved`;
- `ar_audio_core_set_paused`;
- `ar_audio_core_seek`;
- `ar_audio_ring_create`, `ar_audio_ring_read`, `ar_audio_ring_write`, and related ring functions.

Bridge generation runs off the realtime callback. The source-node callback performs no allocation,
file access, model inference, network work, or logging.

## Current verification status

This scaffold was authored on Windows. Windows has no Xcode, iOS SDK, Simulator, `xcodebuild`, or
code-signing toolchain. The project and relative paths were checked statically only. The macOS build,
tests, background scheduling, remote controls, device audio, interruptions, and signing still need
verification on macOS and physical iOS hardware.

The platform target does not yet provide a local media decoder/library coordinator or model runtime.
No neural model, AI generation, model license, latency, battery, or quality claim is made here.
