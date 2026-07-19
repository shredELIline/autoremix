# AutoRemix 2.1.1 quality report

Verified on 2026-07-19 from the local commit series. The host was Windows 11 with Linux containers.

## Android build

- package: `com.alexey.autoremix`
- version: `2.1.1` (`versionCode=10`)
- `minSdk=29`, `targetSdk=37`, `compileSdk=37`
- `lintDebug`: 0 errors, 11 warnings
- `testDebugUnitTest`: 7 passed, 0 failed
- `assembleDebug`, `bundleDebug`, and `assembleDebugAndroidTest`: passed
- Compose screenshot tests: 8 passed on the API 37 emulator at 1080 × 2400

The lint warnings are one tool-update notice, two manifest migration notices, six obsolete SDK guards,
and two resources used only by the screenshot harness. None are suppressed.

### Local debug artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `platform-android-debug.apk` | 72,198,935 bytes | `13fdf38eb0f821f3b0344f5d3a4dd71cd95d459bab4f3fd2f7e2b7e51e6f8d7b` |
| `platform-android-debug.aab` | 19,651,846 bytes | `42793f530103954354f680f1bdc2b3f32bdbbbebe8d709811f1ffff3d19f7162` |
| `platform-android-debug-androidTest.apk` | 2,442,261 bytes | `6beabb48bd567c699d5001d8a8f50a4921f574d8f6038fdc3e360dc66c76185a` |

These are debug artifacts. The APK uses APK Signature Scheme v2 and the standard `Android Debug`
certificate. No release-signing or upgrade-certificate claim is made. Build outputs are ignored by Git.

## Shared audio core

- strict Release build in Alpine 3.22: passed with GCC 14.2, CMake 3.31.7, and warnings as errors
- deterministic test executable: 16 cases passed
- ASan + UBSan Linux build and tests: passed
- Android NDK arm64 build and C ABI probe: passed
- C ABI version: 2

The test suite covers independent stem timelines, fallback ordering, anchor continuity, lifecycle
linearization, ring-buffer behavior, cancellation, invalid/non-finite inputs, chunk boundaries, output
diagnostics, and the C boundary. It does not consume the committed demo WAV files as golden tests.

### Host benchmark

Docker on WSL2, AMD Ryzen AI 9 HX 370, 24 logical CPUs, Release, sanitizers off:

| Metric | Result |
| --- | ---: |
| sample rate / channels | 48,000 Hz / 2 |
| rendered duration | 4.0 s |
| iterations | 7 |
| median render time | 253.9599 ms |
| median realtime factor | 0.0635 |
| output samples | 384,000 |
| peak | 0.1816 |
| fallback stage | 2 — deterministic multi-stem |

This is a desktop host result. It is not a phone latency, underrun, battery, thermal, or inference
measurement.

## iOS

The project structure, scheme, plist, assets, relative core paths, C ABI constants, and stereo guards
were checked statically. macOS CI is configured to build and test the iOS Simulator target. This Windows workspace has
no Xcode or iOS SDK, so no local iOS build or runtime result is claimed.

## Showcase and fixtures

- local documentation/link validator: passed
- desktop and 390 × 844 mobile Playwright checks: passed with zero console errors
- synthetic CC0 demo WAV generation is deterministic and records checksums/diagnostics in its manifest
- the eight published Android images come from the Compose emulator screenshot harness

## Open limits and risks

- No neural model or weights are bundled. Current separation is deterministic Tier C DSP.
- Android still renders scenes in the retained Java Tier C path. The C++ core owns the native
  lifecycle/output boundary, but its renderer is not yet the Android end-to-end render source.
- The iOS target has no local media-library decoder or model runtime yet.
- Android audio-focus and `ACTION_AUDIO_BECOMING_NOISY` handling are not implemented.
- Real-device listening, interruption, route-change, background, battery, thermal, memory, and
  underrun tests remain required on named Android and iOS devices.
- No release archive, store package, production signing key, or release certificate was produced.
