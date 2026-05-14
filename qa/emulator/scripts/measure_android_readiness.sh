#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PACKAGE_NAME="${BECALM_PACKAGE:-com.becalm.android}"
OUT_DIR="$ROOT_DIR/qa/emulator/reports/readiness"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT_DIR/readiness-$STAMP.txt"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or not executable: $ADB_BIN" >&2
  echo "Set ADB=/path/to/adb and rerun." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

run_adb() {
  "$ADB_BIN" -s "$DEVICE" "$@"
}

section() {
  printf '\n## %s\n' "$1" | tee -a "$REPORT"
}

metric() {
  printf '%s=%s\n' "$1" "$2" | tee -a "$REPORT"
}

run_adb wait-for-device

section "device"
metric model "$(run_adb shell getprop ro.product.model | tr -d '\r')"
metric android "$(run_adb shell getprop ro.build.version.release | tr -d '\r')"
metric heap "$(run_adb shell getprop dalvik.vm.heapsize | tr -d '\r')"

section "cold_start"
run_adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
START_OUTPUT="$(
  run_adb shell am start -W \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    "$PACKAGE_NAME/.MainActivity" 2>/dev/null | tr -d '\r'
)"
printf '%s\n' "$START_OUTPUT" | tee -a "$REPORT"
TOTAL_TIME="$(printf '%s\n' "$START_OUTPUT" | awk -F': ' '/TotalTime/ {print $2; exit}')"
WAIT_TIME="$(printf '%s\n' "$START_OUTPUT" | awk -F': ' '/WaitTime/ {print $2; exit}')"
metric cold_start_total_ms "${TOTAL_TIME:-unknown}"
metric cold_start_wait_ms "${WAIT_TIME:-unknown}"

section "memory"
run_adb shell dumpsys meminfo "$PACKAGE_NAME" | tee -a "$REPORT" >/dev/null
TOTAL_PSS="$(run_adb shell dumpsys meminfo "$PACKAGE_NAME" | tr -d '\r' | awk '/TOTAL PSS:/ {print $3; exit}')"
metric total_pss_kb "${TOTAL_PSS:-unknown}"

section "frames"
run_adb shell dumpsys gfxinfo "$PACKAGE_NAME" framestats | tee -a "$REPORT" >/dev/null
FRAME_ROWS="$(
  run_adb shell dumpsys gfxinfo "$PACKAGE_NAME" framestats |
    tr -d '\r' |
    awk -F',' '/^[0-9]+,/ {count++} END {print count + 0}'
)"
metric frame_rows "$FRAME_ROWS"

section "thresholds"
if [[ "${TOTAL_TIME:-0}" =~ ^[0-9]+$ && "$TOTAL_TIME" -gt 3000 ]]; then
  metric cold_start_threshold "WARN over 3000ms"
else
  metric cold_start_threshold "PASS <=3000ms or unavailable"
fi

if [[ "${TOTAL_PSS:-0}" =~ ^[0-9]+$ && "$TOTAL_PSS" -gt 262144 ]]; then
  metric memory_threshold "WARN over 256MB PSS"
else
  metric memory_threshold "PASS <=256MB PSS or unavailable"
fi

cat <<EOF

Readiness measurement written to:
  $REPORT

Use this after seeding large QA data to compare cold start, memory, and frame stats
before and after UI or sync pipeline changes.
EOF
