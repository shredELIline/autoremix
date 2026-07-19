# AutoRemix 2.2.0 quality report

Verified on 2026-07-19 from the local commit series. The host was Windows 11 with Linux containers.

## Android build

- package: `com.alexey.autoremix`
- version: `2.2.0` (`versionCode=11`)
- `minSdk=29`, `targetSdk=37`, `compileSdk=37`
- `lintDebug`: 0 errors, 13 warnings
- `testDebugUnitTest`: 9 passed, 0 failed
- `assembleDebug`, `bundleDebug`, and `assembleDebugAndroidTest`: passed
- Compose screenshot tests: 8 passed on the API 37 emulator at 1080 × 2400

The lint warnings are one tool-update notice, seven obsolete SDK guards, two
resources used only by the screenshot harness, one idle/charging constraint
advisory, one backup-rule migration advisory, and one fixed-orientation
advisory. None are suppressed.

### Local debug artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `platform-android-debug.apk` | 72,264,471 bytes | `5fa8b1d089ecd4b977803fe42c5d4409787a43d7e4783505c3db1eb10375a2de` |
| `platform-android-debug.aab` | 19,682,990 bytes | `f8f4b7bbf0c3291d013cc703baa5fc5cdf5e2c6c56810ccca014e5d5d780b507` |
| `platform-android-debug-androidTest.apk` | 2,442,353 bytes | `4911559110a3fa26f7aa7798f2393e651ac0e417a1e17716be0a055ca56e2080` |

These are debug artifacts. The APK uses APK Signature Scheme v2 and the standard `Android Debug`
certificate. No release-signing or upgrade-certificate claim is made. Build outputs are ignored by Git.

## Shared audio core

- Release build in Debian 12: passed with GCC 12.2 and CMake 3.25.1
- deterministic test executable: 26 cases passed
- ASan + UBSan Linux build and tests: passed
- Android NDK arm64 build and C ABI probe: passed
- C ABI version: 2

The test suite covers independent stem timelines, exact progressive lifecycle,
fallback ordering, anchor continuity, continuation graphs, bounded non-repeating
beam search, repetition quality, rolling horizons, future-only neural upgrades,
ring-buffer behavior, cancellation, invalid/non-finite inputs, cache corruption,
chunk boundaries, clicks, a 90-second slow-generation scenario, diagnostics,
and the C boundary. It does not consume the committed demo WAV files as golden
tests.

### Host benchmark

Docker on WSL2, AMD Ryzen AI 9 HX 370, 24 logical CPUs, Release, sanitizers off:

| Metric | Result |
| --- | ---: |
| sample rate / channels | 48,000 Hz / 2 |
| rendered duration | 4.0 s |
| iterations | 7 |
| median render time | 270.8090 ms |
| median realtime factor | 0.0677 |
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
- The published Android package is a debug-signed preview. No store package,
  production signing key, release certificate, or public iOS archive is
  claimed.
