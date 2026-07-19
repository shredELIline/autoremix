#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/dist/AutoRemix-2.1.0-debug.apk"

"$ROOT/gradlew" :platform-android:assembleDebug
mkdir -p "$ROOT/dist"
cp "$ROOT/platform-android/build/outputs/apk/debug/platform-android-debug.apk" "$OUT"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUT" > "$OUT.sha256"
fi

echo "$OUT"
