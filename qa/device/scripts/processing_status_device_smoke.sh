#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.becalm.android"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="${ADB:-adb}"
fi
DEVICE_SERIAL="${ANDROID_SERIAL:-R5CT83SMP4P}"
APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
REPORT_ROOT="qa/device/reports"
CONFIRM_DEBUG_SEED=false
INSTALL_APK=true
CLEAR_APP_DATA=true
MAX_SWIPES=12

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/processing_status_device_smoke.sh --confirm-debug-seed [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --skip-install             do not install APK before running.
  --skip-clear-data          do not clear app data before seeding.
  --max-swipes N             max detail swipes while searching status copy. Default: 12
  --confirm-debug-seed       Required. Replaces the local debug user/session with QA seed data.
  -h, --help                 Show this help.

This smoke uses debug-only seed data to prove P1-2 delayed, blocked, and retry
processing statuses render as user-facing copy on a Samsung device.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb)
      ADB_BIN="$2"
      shift 2
      ;;
    --device)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --apk)
      APK_PATH="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --report-root)
      REPORT_ROOT="$2"
      shift 2
      ;;
    --skip-install)
      INSTALL_APK=false
      shift
      ;;
    --skip-clear-data)
      CLEAR_APP_DATA=false
      shift
      ;;
    --max-swipes)
      MAX_SWIPES="$2"
      shift 2
      ;;
    --confirm-debug-seed)
      CONFIRM_DEBUG_SEED=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$CONFIRM_DEBUG_SEED" != "true" ]]; then
  echo "Refusing to run without --confirm-debug-seed." >&2
  echo "This changes local debug app state for ${PACKAGE_NAME}." >&2
  exit 2
fi

if [[ "$INSTALL_APK" == "true" && ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Build it first, for example: ./gradlew :app:assembleDebug from android/." >&2
  exit 2
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
report_dir="${REPORT_ROOT}/processing-status-device-${timestamp}"
mkdir -p "$report_dir"

adb_args=(-s "$DEVICE_SERIAL")
run_adb() {
  "$ADB_BIN" "${adb_args[@]}" "$@"
}

dump_ui() {
  local target="$1"
  run_adb exec-out uiautomator dump /dev/tty > "$target" 2>/dev/null || true
}

wake_device() {
  run_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  run_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  run_adb shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 1
}

contains_text() {
  local file="$1"
  local text="$2"
  grep -Fq "$text" "$file"
}

contains_package() {
  local file="$1"
  grep -Fq "package=\"${PACKAGE_NAME}\"" "$file"
}

tap_text_from_dump() {
  local dump_file="$1"
  local text="$2"
  local coords
  coords="$(python3 - "$dump_file" "$text" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, target = sys.argv[1:3]
try:
    raw = open(path, "r", encoding="utf-8").read()
    end = raw.find("</hierarchy>")
    if end >= 0:
        raw = raw[: end + len("</hierarchy>")]
    root = ET.fromstring(raw)
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    if node.attrib.get("text") != target:
        continue
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
    sys.exit(0)
sys.exit(1)
PY
)"
  [[ -n "$coords" ]] || return 1
  run_adb shell input tap $coords
}

wait_for_text() {
  local target="$1"
  local text="$2"
  local attempts="${3:-12}"
  for i in $(seq 1 "$attempts"); do
    wake_device
    dump_ui "$target"
    if contains_package "$target" && contains_text "$target" "$text"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

find_text_with_swipes() {
  local text="$1"
  local prefix="$2"
  for i in $(seq 1 "$MAX_SWIPES"); do
    local dump_file="${report_dir}/${prefix}-${i}.xml"
    dump_ui "$dump_file"
    if contains_text "$dump_file" "$text"; then
      echo "$dump_file"
      return 0
    fi
    run_adb shell input swipe 540 2100 540 700 300
    sleep 0.4
  done
  return 1
}

echo "Writing report to $report_dir"
"$ADB_BIN" devices -l > "${report_dir}/devices.txt"
run_adb get-state > "${report_dir}/device-state.txt"
run_adb shell getprop ro.product.model > "${report_dir}/device-model.txt" || true
run_adb shell getprop ro.build.version.release > "${report_dir}/android-release.txt" || true
run_adb shell getprop ro.build.version.sdk > "${report_dir}/android-sdk.txt" || true

if [[ "$INSTALL_APK" == "true" ]]; then
  run_adb install -r "$APK_PATH" > "${report_dir}/install.txt"
fi

if [[ "$CLEAR_APP_DATA" == "true" ]]; then
  run_adb shell pm clear "$PACKAGE_NAME" > "${report_dir}/clear-app-data.txt"
fi

run_adb logcat -c || true
wake_device
run_adb shell am broadcast \
  --receiver-foreground \
  -a com.becalm.android.DEBUG_SEED_PROCESSING_STATUS_SMOKE \
  -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver" \
  > "${report_dir}/seed-broadcast.txt"
sleep 3

wake_device
run_adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 \
  > "${report_dir}/launch.txt"

today_dump="${report_dir}/ui-today.xml"
if ! wait_for_text "$today_dump" "일정"; then
  echo "App did not expose the main tab bar after launch. See $report_dir" >&2
  exit 1
fi
tap_text_from_dump "$today_dump" "일정"
sleep 2
if ! wait_for_text "$today_dump" "확인 필요한 정리 3개"; then
  echo "Today did not show the P1-2 action-needed processing strip. See $report_dir" >&2
  exit 1
fi
if ! contains_text "$today_dump" "상세 보기"; then
  echo "Today processing strip did not expose the detail action. See $report_dir" >&2
  exit 1
fi

tap_text_from_dump "$today_dump" "상세 보기"
sleep 2

detail_dump="${report_dir}/ui-processing-status.xml"
if ! wait_for_text "$detail_dump" "정리 상태"; then
  echo "Processing status detail did not open. See $report_dir" >&2
  exit 1
fi
if ! grep -Eq 'text="[0-9]+개 연결 확인 필요"' "$detail_dump"; then
  echo "Processing status summary did not show action-needed rows. See $report_dir" >&2
  exit 1
fi
if ! contains_text "$detail_dump" "확인 필요"; then
  echo "Processing status detail did not show the action-needed group. See $report_dir" >&2
  exit 1
fi

find_text_with_swipes "서버 작업이 많아 새 기록 확인을 대기 중입니다" "ui-find-backpressure" >/dev/null
find_text_with_swipes "오늘 사용할 수 있는 정리 작업을 모두 사용했습니다" "ui-find-budget" >/dev/null
find_text_with_swipes "서버 사용량 제한으로 잠시 후 다시 정리합니다" "ui-find-rate-limit" >/dev/null

if grep -R -Fq \
  -e "source_sync_backpressure_delayed" \
  -e "llm_daily_budget_exceeded" \
  -e "llm_rate_limited_retrying" \
  -e "Unauthorized" \
  "${report_dir}"/ui-*.xml; then
  echo "Processing status UI exposed raw internal message codes. See $report_dir" >&2
  exit 1
fi

run_adb logcat -d -v time > "${report_dir}/logcat.txt" || true
fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat.txt" || true
)"

{
  echo "processing_status_device_smoke_result=completed"
  echo "package=${PACKAGE_NAME}"
  echo "device=${DEVICE_SERIAL}"
  echo "today_action_strip_visible=true"
  echo "processing_detail_opened=true"
  echo "localized_backpressure_visible=true"
  echo "localized_budget_visible=true"
  echo "localized_rate_limit_visible=true"
  echo "raw_internal_codes_visible=false"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "report_dir=${report_dir}"
} > "${report_dir}/summary.env"

if [[ "$fatal_count" != "0" ]]; then
  echo "Device smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Processing status device smoke passed. Report: $report_dir"
