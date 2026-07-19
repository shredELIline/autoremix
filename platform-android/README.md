# Android app

Android 10+ (`minSdk 29`) app using Kotlin, Compose, WorkManager, Media3, and an Oboe/AAudio output path. The retained Java provider performs local MediaStore scan, MediaCodec decode, deterministic analysis/separation, progressive non-repeating continuation, and scene preparation.

## Build

Install JDK 17, Android SDK 37, Build Tools 37.0.0, NDK 29.0.14206865, and CMake 3.31.6. Set `ANDROID_HOME`, then run from the repository root:

```bash
./gradlew :platform-android:lintDebug \
  :platform-android:testDebugUnitTest \
  :platform-android:assembleDebug \
  :platform-android:bundleDebug
```

The APK and AAB are written under `platform-android/build/outputs/`.

Tagged releases publish the tested debug-preview APK and `SHA256SUMS.txt`.
The preview uses an ephemeral debug certificate; it is not a Play-ready signed
release build.

## Screenshots

Start an API 29+ emulator, then run:

```powershell
./platform-android/scripts/capture-screenshots.ps1
```

or:

```bash
./platform-android/scripts/capture-screenshots.sh
```

The instrumented Compose renderer writes reproducible fixtures to `docs/assets/screenshots/`.
