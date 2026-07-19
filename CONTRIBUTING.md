# Contributing

AutoRemix accepts focused fixes and measured improvements to the local audio
pipeline.

## Before opening a change

1. Open an issue for architecture changes, new model runtimes, or new model
   weights.
2. Keep realtime code allocation-free and non-blocking.
3. Add deterministic tests for DSP or planner changes.
4. Use only generated, CC0, public-domain, or explicitly licensed audio.
5. Record benchmark device, OS, build type, sample rate, duration, and thermal
   state. Do not publish estimated numbers as measurements.

## Local checks

```bash
(cd audio-core && cmake --preset release)
(cd audio-core && cmake --build --preset release)
(cd audio-core && ctest --preset release)
./gradlew :platform-android:testDebugUnitTest
./gradlew :platform-android:lintDebug :platform-android:assembleDebug
python .github/scripts/validate_docs.py docs
python .github/scripts/repository_policy.py
```

iOS requires macOS and Xcode. See `platform-ios/README.md`.

## Commit style

Use small imperative commits, for example `audio: preserve anchor coverage`.
Do not commit generated keys, copyrighted songs, private paths, model weights,
or local build output.
