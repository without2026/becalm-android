#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
TEST_CLASSES="${TEST_CLASSES:-com.becalm.android.ui.sources.SourcesListScreenTest,com.becalm.android.ui.sources.SourceResilienceCheckpoint6E2eTest}"

if ! "$ADB_BIN" -s "$DEVICE" get-state >/dev/null 2>&1; then
  echo "No connected emulator/device found at $DEVICE via $ADB_BIN" >&2
  echo "Set ADB and ANDROID_SERIAL, then retry." >&2
  exit 1
fi

(
  cd "$ANDROID_DIR"
  ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
)

"$ADB_BIN" -s "$DEVICE" install -r "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
"$ADB_BIN" -s "$DEVICE" install -r "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

"$ADB_BIN" -s "$DEVICE" shell am instrument -w \
  -e class "$TEST_CLASSES" \
  com.becalm.android.test/androidx.test.runner.AndroidJUnitRunner
