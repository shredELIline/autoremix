#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then
  for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [[ -x "$sdk/platform-tools/adb" ]]; then
      export ANDROID_SDK_ROOT="$sdk"
      export ANDROID_HOME="$sdk"
      export PATH="$sdk/platform-tools:$PATH"
      break
    fi
  done
fi
command -v adb >/dev/null 2>&1 || { echo "Android SDK platform-tools not found" >&2; exit 1; }
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
for name in timeline-normal-00-15-dark.png timeline-entry-01-20-dark.png \
  timeline-planned-transition-dark.png transition-preparing-dark.png \
  transition-guitar-anchor-dark.png transition-bass-handoff-dark.png \
  transition-key-change-dark.png transition-vocal-handoff-dark.png \
  timeline-landed-01-14-dark.png transition-reduced-motion-dark.png; do
  test -s "$ROOT/docs/assets/screenshots/$name"
done
