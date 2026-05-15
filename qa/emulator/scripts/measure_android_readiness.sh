#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
if [[ -n "${ADB:-}" ]]; then
  ADB_BIN="$ADB"
elif command -v adb >/dev/null 2>&1; then
  ADB_BIN="$(command -v adb)"
else
  ADB_BIN="/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe"
fi
PACKAGE_NAME="${BECALM_PACKAGE:-com.becalm.android}"
ACTIVITY_COMPONENT="${BECALM_ACTIVITY_COMPONENT:-$PACKAGE_NAME/.MainActivity}"
APP_APK="${BECALM_APK:-$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk}"
STRICT="${BECALM_READINESS_STRICT:-1}"
MAX_COLD_START_MS="${BECALM_MAX_COLD_START_MS:-3000}"
MAX_TOTAL_PSS_KB="${BECALM_MAX_TOTAL_PSS_KB:-262144}"
MAX_SKIPPED_FRAMES="${BECALM_MAX_SKIPPED_FRAMES:-60}"
OUT_DIR="$ROOT_DIR/qa/emulator/reports/readiness"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT_DIR/readiness-$STAMP.txt"
FAILURES=0

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

record_failure() {
  local message="$1"
  FAILURES=$((FAILURES + 1))
  metric "readiness_failure_$FAILURES" "$message"
}

digits_only() {
  tr -cd '0-9'
}

run_adb wait-for-device

section "app_install"
if run_adb shell pm path "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | grep -q "^package:"; then
  metric package_installed "present"
else
  metric package_installed "missing"
  if [[ ! -f "$APP_APK" ]]; then
    metric app_apk "missing"
    record_failure "app package missing and APK not found at $APP_APK"
  else
    metric app_apk "$APP_APK"
    run_adb install -r "$APP_APK" | tr -d '\r' | tee -a "$REPORT"
  fi
fi

section "device"
metric model "$(run_adb shell getprop ro.product.model | tr -d '\r')"
metric android "$(run_adb shell getprop ro.build.version.release | tr -d '\r')"
metric heap "$(run_adb shell getprop dalvik.vm.heapsize | tr -d '\r')"
metric strict "$STRICT"
metric max_cold_start_ms "$MAX_COLD_START_MS"
metric max_total_pss_kb "$MAX_TOTAL_PSS_KB"
metric max_skipped_frames "$MAX_SKIPPED_FRAMES"

section "cold_start"
run_adb logcat -c >/dev/null 2>&1 || true
run_adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
START_OUTPUT="$(
  run_adb shell am start -W \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    "$ACTIVITY_COMPONENT" 2>/dev/null | tr -d '\r'
)"
printf '%s\n' "$START_OUTPUT" | tee -a "$REPORT"
TOTAL_TIME="$(printf '%s\n' "$START_OUTPUT" | awk -F': ' '/TotalTime/ {print $2; exit}')"
WAIT_TIME="$(printf '%s\n' "$START_OUTPUT" | awk -F': ' '/WaitTime/ {print $2; exit}')"
TOTAL_TIME="$(printf '%s' "${TOTAL_TIME:-}" | digits_only)"
WAIT_TIME="$(printf '%s' "${WAIT_TIME:-}" | digits_only)"
metric cold_start_total_ms "${TOTAL_TIME:-unknown}"
metric cold_start_wait_ms "${WAIT_TIME:-unknown}"
APP_PID="$(run_adb shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
metric app_pid "${APP_PID:-unknown}"

section "memory"
run_adb shell dumpsys meminfo "$PACKAGE_NAME" | tee -a "$REPORT" >/dev/null
TOTAL_PSS="$(
  run_adb shell dumpsys meminfo "$PACKAGE_NAME" |
    tr -d '\r' |
    awk '
      /TOTAL PSS:/ && value == "" {
        value = $3
        gsub(/[^0-9]/, "", value)
      }
      /^TOTAL[[:space:]]/ && value == "" {
        value = $2
        gsub(/[^0-9]/, "", value)
      }
      END {print value}
    '
)"
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
if [[ -z "${TOTAL_TIME:-}" ]]; then
  metric cold_start_threshold "FAIL unavailable"
  record_failure "cold_start_total_ms unavailable"
elif [[ "$TOTAL_TIME" -gt "$MAX_COLD_START_MS" ]]; then
  metric cold_start_threshold "FAIL over ${MAX_COLD_START_MS}ms"
  record_failure "cold_start_total_ms=${TOTAL_TIME} over ${MAX_COLD_START_MS}"
else
  metric cold_start_threshold "PASS <=${MAX_COLD_START_MS}ms"
fi

if [[ -z "${TOTAL_PSS:-}" ]]; then
  metric memory_threshold "FAIL unavailable"
  record_failure "total_pss_kb unavailable"
elif [[ "$TOTAL_PSS" -gt "$MAX_TOTAL_PSS_KB" ]]; then
  metric memory_threshold "FAIL over ${MAX_TOTAL_PSS_KB}KB PSS"
  record_failure "total_pss_kb=${TOTAL_PSS} over ${MAX_TOTAL_PSS_KB}"
else
  metric memory_threshold "PASS <=${MAX_TOTAL_PSS_KB}KB PSS"
fi

section "logcat"
LOGCAT_OUTPUT="$(run_adb logcat -d -v time 2>/dev/null | tr -d '\r' || true)"
printf '%s\n' "$LOGCAT_OUTPUT" | tee -a "$REPORT" >/dev/null
SKIPPED_FRAMES_MAX="0"
if [[ -n "${APP_PID:-}" ]]; then
  SKIPPED_FRAMES_MAX="$(
    printf '%s\n' "$LOGCAT_OUTPUT" |
      grep -E "Choreographer\\([[:space:]]*${APP_PID}\\): Skipped [0-9]+ frames" |
      sed -E 's/.*Skipped ([0-9]+) frames.*/\1/' |
      sort -nr |
      head -n1 ||
      true
  )"
fi
SKIPPED_FRAMES_MAX="${SKIPPED_FRAMES_MAX:-0}"
metric skipped_frames_max "$SKIPPED_FRAMES_MAX"
if [[ "$SKIPPED_FRAMES_MAX" =~ ^[0-9]+$ && "$SKIPPED_FRAMES_MAX" -gt "$MAX_SKIPPED_FRAMES" ]]; then
  metric skipped_frames_threshold "FAIL over ${MAX_SKIPPED_FRAMES} frames"
  record_failure "skipped_frames_max=${SKIPPED_FRAMES_MAX} over ${MAX_SKIPPED_FRAMES}"
else
  metric skipped_frames_threshold "PASS <=${MAX_SKIPPED_FRAMES} frames"
fi
LOGCAT_FAILURES="$(
  printf '%s\n' "$LOGCAT_OUTPUT" |
    grep -E "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|lowmemorykiller.*${PACKAGE_NAME}|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" ||
    true
)"
if [[ -n "$LOGCAT_FAILURES" ]]; then
  metric logcat_threshold "FAIL fatal_or_anr_or_oom"
  printf '%s\n' "$LOGCAT_FAILURES" | tee -a "$REPORT"
  record_failure "logcat contains fatal/ANR/OOM pattern"
else
  metric logcat_threshold "PASS no fatal/ANR/OOM patterns"
fi

section "result"
metric readiness_failure_count "$FAILURES"

cat <<EOF

Readiness measurement written to:
  $REPORT

Use this after seeding large QA data to compare cold start, memory, and frame stats
before and after UI or sync pipeline changes.
EOF

if [[ "$STRICT" != "0" && "$FAILURES" -gt 0 ]]; then
  echo "BeCalm readiness measurement failed with $FAILURES failure(s)." >&2
  exit 1
fi
