#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
"$ROOT/gradlew" :platform-android:assembleDebug :platform-android:assembleDebugAndroidTest
adb install -r "$ROOT/platform-android/build/outputs/apk/debug/platform-android-debug.apk"
adb install -r "$ROOT/platform-android/build/outputs/apk/androidTest/debug/platform-android-debug-androidTest.apk"
adb shell rm -rf /sdcard/Android/data/com.alexey.autoremix/files/Pictures
adb shell am instrument -w -r \
  -e class com.alexey.autoremix.AutoRemixScreenshotTest \
  com.alexey.autoremix.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$ROOT/docs/assets/screenshots"
adb pull /sdcard/Android/data/com.alexey.autoremix/files/Pictures/. \
  "$ROOT/docs/assets/screenshots/"
for name in library-dark.png now-playing-dark.png transition-dark.png transition-in-progress-dark.png queue-dark.png \
  analysis-cache-dark.png settings-dark.png now-playing-light.png; do
  test -s "$ROOT/docs/assets/screenshots/$name"
done
