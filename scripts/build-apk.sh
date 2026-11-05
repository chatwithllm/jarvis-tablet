#!/usr/bin/env bash
# Build the Jarvis tablet debug APK (Phase 1).
#
# This runs the MANUAL pipeline (android/build-manual.py: aapt2 + kotlinc
# + R8 + zipalign + apksigner), NOT Gradle. The Gradle daemon cannot run
# in the cloud sandbox — the sandbox network filter blocks loopback TCP
# from Java processes, so the Gradle client can never talk to its daemon
# ("Could not receive a message from the daemon"). CI (GitHub Actions,
# .github/workflows/build-apk.yml) still builds with Gradle on push.
#
# Needs: ANDROID_HOME (SDK with platform android-34 + build-tools 34.0.0),
# JAVA_HOME (JDK 17), kotlinc (KOTLINC_HOME or ~/workspace/.kotlin/kotlinc),
# and android/libs/resolved.txt (run android/libs/resolve.py first if it
# is missing — it re-downloads the dependencies). Output:
# android/build/jarvis-tablet-debug.apk. Debug signing only — never a
# release keystore (the release keystore lives on the Mac Mini).
# Sideload with scripts/adb-install.sh (needs LAN adb to the tablet).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:=$HOME/workspace/.android-sdk}"
: "${JAVA_HOME:=$HOME/workspace/.jdk17}"
export ANDROID_HOME JAVA_HOME

missing=0
[ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "Missing SDK platform android-34 in $ANDROID_HOME"; missing=1; }
[ -x "$ANDROID_HOME/build-tools/34.0.0/aapt2" ] || { echo "Missing build-tools 34.0.0 in $ANDROID_HOME"; missing=1; }
[ -x "$JAVA_HOME/bin/java" ] || { echo "Missing JDK 17 at $JAVA_HOME"; missing=1; }
KOTLINC_HOME="${KOTLINC_HOME:-$HOME/workspace/.kotlin/kotlinc}"
[ -x "$KOTLINC_HOME/bin/kotlinc" ] || { echo "Missing kotlinc at $KOTLINC_HOME (Kotlin 2.0.20: https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip)"; missing=1; }
[ -f android/libs/resolved.txt ] || { echo "Missing android/libs/resolved.txt — run: python3 android/libs/resolve.py"; missing=1; }
[ "$missing" = 1 ] && { echo "Prerequisites missing; see android/README.md. Or push to main and let CI build it."; exit 1; }
export KOTLINC_HOME

python3 android/build-manual.py
echo "Built android/build/jarvis-tablet-debug.apk — sideload with scripts/adb-install.sh"
