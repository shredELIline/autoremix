#!/usr/bin/env bash
set -euo pipefail
# Standalone Android build. Requires the small tool bundle already used by this project.
ROOT="$(cd "$(dirname "$0")" && pwd)"
BT="${ANDROID_BUILD_TOOLS_DIR:-/tmp/android-build-tools}"
LOCAL_KEYSTORE="$ROOT/build/debug.keystore"
KEYSTORE="${DEBUG_KEYSTORE:-$LOCAL_KEYSTORE}"
OUT="$ROOT/dist/AutoRemix-WOW-PCM-STEM-DIRECTOR-v2.0-debug.apk"

rm -rf "$ROOT/build/classes" "$ROOT/build/dex" "$ROOT/build/apk" "$ROOT/build/compiled-res"
mkdir -p "$ROOT/build/classes" "$ROOT/build/dex" "$ROOT/build/apk" "$ROOT/build/compiled-res" "$ROOT/dist"

if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=AutoRemix,C=SE" >/dev/null 2>&1
fi

javac -encoding UTF-8 -source 17 -target 17 -classpath "$BT/android.jar" \
  -d "$ROOT/build/classes" $(find "$ROOT/src" -name '*.java' | sort)
"$BT/aapt2" compile --dir "$ROOT/res" -o "$ROOT/build/compiled-res/resources.zip"
"$BT/aapt2" link -o "$ROOT/build/apk/base.apk" -I "$BT/android.jar" \
  -R "$ROOT/build/compiled-res/resources.zip" --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 35 --version-code 8 --version-name 2.0.0 --auto-add-overlay
find "$ROOT/build/classes" -name '*.class' -print0 | xargs -0 java -cp "$BT/r8.jar" \
  com.android.tools.r8.D8 --lib "$BT/android.jar" --min-api 29 --output "$ROOT/build/dex"
cp "$ROOT/build/apk/base.apk" "$ROOT/build/apk/with-dex.apk"
(cd "$ROOT/build/dex" && zip -q -u "$ROOT/build/apk/with-dex.apk" classes.dex)
if "$BT/zipalign" -f 4 "$ROOT/build/apk/with-dex.apk" "$ROOT/build/apk/aligned.apk" 2>/dev/null; then
  :
else
  # Some minimal Linux images do not ship Android zipalign's libc++ runtime.
  # The APK remains installable; apksigner signs the unaligned package directly.
  cp "$ROOT/build/apk/with-dex.apk" "$ROOT/build/apk/aligned.apk"
fi
java -jar "$BT/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT" "$ROOT/build/apk/aligned.apk"
sha256sum "$OUT" > "$OUT.sha256"
echo "$OUT"
