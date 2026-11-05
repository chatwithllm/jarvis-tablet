#!/usr/bin/env bash
# Sideload the Jarvis tablet APK over Wi-Fi adb (PRD handoff).
# Usage: ./scripts/adb-install.sh [path/to/jarvis-tablet.apk]
set -euo pipefail
APK="${1:-android/build/jarvis-tablet.apk}"
adb connect 192.168.20.109:5555
adb install -r "$APK"
echo "Installed $APK. Verify: tablet shows immersive Jarvis + wake service."
