# AutoRemix 2.3.0 quality report

Verified on 2026-07-19 from the local commit series. The host was Windows 11 with Linux containers.

## Android build

- package: `com.alexey.autoremix`
- version: `2.3.0` (`versionCode=12`)
- `minSdk=29`, `targetSdk=37`, `compileSdk=37`
- `lintDebug`: 0 errors, 13 warnings
- `testDebugUnitTest`: 16 passed, 0 failed
- `assembleDebug` and `bundleDebug`: passed

The lint warnings are one tool-update notice, seven obsolete SDK guards, two
resources used only by the screenshot harness, one idle/charging constraint
advisory, one backup-rule migration advisory, and one fixed-orientation
advisory. None are suppressed.

### Local debug artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `platform-android-debug.apk` | 72,362,779 bytes | `ac7754ff336be62a4be4acdff9003820db4080c821f1e5a23660cd39080ed539` |
| `platform-android-debug.aab` | 19,727,099 bytes | `ad52b410cda483012fba5c6c2f82f042d6bee5dbae1a7465a54e0efad4ced629` |

These are debug artifacts. The APK uses APK Signature Scheme v2 and the standard `Android Debug`
certificate. No release-signing or upgrade-certificate claim is made. Build outputs are ignored by Git.

## Shared audio core

- Release build in Debian 12: passed with GCC 12.2 and CMake 3.25.1
- deterministic test executable: 26 cases passed
- ASan + UBSan Linux build and tests: passed
- Android NDK arm64 build and C ABI probe: passed
- C ABI version: 2

The shared-core suite covers independent stem timelines, exact progressive lifecycle,
fallback ordering, anchor continuity, continuation graphs, bounded non-repeating
beam search, repetition quality, rolling horizons, future-only neural upgrades,
ring-buffer behavior, cancellation, invalid/non-finite inputs, cache corruption,
chunk boundaries, clicks, a 90-second slow-generation scenario, diagnostics,
and the C boundary. Android tests additionally cover structured stem planning,
strict vocal ownership, exact 51-second graph activation, clean-B landing,
fallback order, diagnostics, and committed golden scene audio.

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
- the golden fixture is one continuous 48 kHz stem scene, not concatenated transition PCM
- the eight published Android images come from the Compose emulator screenshot harness

## Open limits and risks

- No neural model or weights are bundled. Current separation is deterministic Tier C DSP.
- Android still renders scenes in the retained Java Tier C path. Its producer owns stem automation
  and feeds the native SPSC ring; native callback-side stem mixing is not implemented.
- The iOS target has no local media-library decoder or model runtime yet.
- Android audio-focus and `ACTION_AUDIO_BECOMING_NOISY` handling are not implemented.
- Real-device listening, interruption, route-change, background, battery, thermal, memory, and
  underrun tests remain required on named Android and iOS devices.
- The published Android package is a debug-signed preview. No store package,
  production signing key, release certificate, or public iOS archive is
  claimed.
