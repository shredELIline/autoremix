# AutoRemix 2.1.0 quality report

Verified on 2026-07-19 from the local commit series. The host was Windows 11 with Linux containers.

## Android build

- package: `com.alexey.autoremix`
- version: `2.1.0` (`versionCode=9`)
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
| `platform-android-debug.apk` | 72,962,314 bytes | `ccf77789152312049c04c4a54afe488804ce89cb96c470a79de345234fc713e1` |
| `platform-android-debug.aab` | 19,651,840 bytes | `4d3d2b2358f30761e201e7b035a4fc1dfb40569c7ac22c5f5bfe09a913953102` |
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
