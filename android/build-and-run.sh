#!/usr/bin/env bash
#
# Build the APK, boot the emulator if needed, install, and start the floater.
#
#   ./build-and-run.sh            emulator
#   ./build-and-run.sh -d         a plugged-in phone
#
# Uses Android Studio's bundled JDK and the cached Gradle, so it needs neither
# on PATH.

set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
AVD="${AVD:-Pixel_7_Pro}"

GRADLE=$(find "$HOME/.gradle/wrapper/dists" -name gradle -type f -path '*/bin/*' 2>/dev/null | sort | tail -1)
[ -n "$GRADLE" ] || { echo "No Gradle found. Open android/ in Android Studio once to fetch it."; exit 1; }

echo "▸ building"
"$GRADLE" :app:assembleDebug --console=plain -q

if [ "${1:-}" != "-d" ] && ! "$ADB" get-state >/dev/null 2>&1; then
  echo "▸ booting $AVD"
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-boot-anim -no-audio >/dev/null 2>&1 &
  for _ in $(seq 1 60); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 5
  done
fi

echo "▸ installing"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

# On a real phone this prompt cannot be bypassed — grant it in the app.
"$ADB" shell appops set app.vaaniledger SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

"$ADB" shell am start -n app.vaaniledger/.MainActivity >/dev/null

cat <<'TXT'

  Installed. In the app:
    1. Both permission cards should read CHALU HAI. Tap either one that
       does not — they open Settings, since neither can be granted in-app.
    2. Tap "Bubble chalu karein".
    3. Press Home and open WhatsApp — the bubble floats on top.
    4. Tap the bubble for the mini dashboard.

TXT
