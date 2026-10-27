#!/usr/bin/env bash
#
# Build the APK, boot the emulator if needed, install, and start the floater.
#
#   ./build-and-run.sh            emulator
#   ./build-and-run.sh -d         a plugged-in phone
#
# macOS / Linux: run it directly.
# Windows:       run it from Git Bash, not cmd or PowerShell.
#
# Uses Android Studio's bundled JDK and the cached Gradle, so it needs neither
# on PATH.

set -euo pipefail
cd "$(dirname "$0")"

# Find Android Studio's bundled JDK and the SDK across macOS, Linux and
# Windows (Git Bash / WSL). Set JAVA_HOME or ANDROID_HOME yourself to skip
# the search entirely.
find_first() { for d in "$@"; do [ -e "$d" ] && { echo "$d"; return; }; done; }

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(find_first \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/opt/android-studio/jbr" \
    "/usr/local/android-studio/jbr" \
    "$HOME/android-studio/jbr" \
    "/c/Program Files/Android/Android Studio/jbr" \
    "/mnt/c/Program Files/Android/Android Studio/jbr")
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  ANDROID_HOME=$(find_first \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "$HOME/AppData/Local/Android/Sdk" \
    "/c/Users/$USER/AppData/Local/Android/Sdk" \
    "${ANDROID_SDK_ROOT:-}")
fi

[ -n "${JAVA_HOME:-}" ]    || { echo "No JDK found. Install Android Studio, or set JAVA_HOME."; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "No Android SDK found. Open Android Studio once, or set ANDROID_HOME."; exit 1; }
export JAVA_HOME ANDROID_HOME

# .exe suffixes on Windows; harmless everywhere else.
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB="$ADB.exe"
AVD="${AVD:-Pixel_7_Pro}"

# -V is a version sort. Plain `sort` puts gradle-8.2 AFTER gradle-8.13
# (lexicographically '2' > '1'), which silently selects an old Gradle that
# the Android plugin rejects — and the failure looks like a code error.
GRADLE=$(find "$HOME/.gradle/wrapper/dists" -name gradle -type f -path '*/bin/*' 2>/dev/null | sort -V | tail -1)
[ -n "$GRADLE" ] || { echo "No Gradle found. Open android/ in Android Studio once to fetch it."; exit 1; }
echo "▸ gradle: $("$GRADLE" --version 2>/dev/null | awk '/^Gradle/{print $2}')"

echo "▸ building"
"$GRADLE" :app:assembleDebug --console=plain -q

if [ "${1:-}" != "-d" ] && ! "$ADB" get-state >/dev/null 2>&1; then
  echo "▸ booting $AVD"
  EMU="$ANDROID_HOME/emulator/emulator"; [ -x "$EMU" ] || EMU="$EMU.exe"
  "$EMU" -avd "$AVD" -no-boot-anim -no-audio >/dev/null 2>&1 &
  for _ in $(seq 1 60); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 5
  done
fi

echo "▸ installing"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

# On a real phone this prompt cannot be bypassed — grant it in the app.
"$ADB" shell appops set app.vaaniledger SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

"$ADB" shell am start -n app.vaaniledger/.SplashActivity >/dev/null

cat <<'TXT'

  Installed. In the app:
    1. Both permission cards should read CHALU HAI. Tap either one that
       does not — they open Settings, since neither can be granted in-app.
    2. Tap "Bubble chalu karein".
    3. Press Home and open WhatsApp — the bubble floats on top.
    4. Tap the bubble for the mini dashboard.

TXT
