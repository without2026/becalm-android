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
MAX_SWIPES=14

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/sync_failure_ui_device_smoke.sh --confirm-debug-seed [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --skip-install             do not install APK before running.
  --skip-clear-data          do not clear app data before seeding.
  --max-swipes N             max swipes while searching visible UI text. Default: 14
  --confirm-debug-seed       Required. Replaces local debug app state with P1-4 QA seed data.
  -h, --help                 Show this help.

This smoke uses debug-only local state to prove the P1-4 source/failure UI
contract on a Samsung device: OAuth completion return opens Sources, connection
health remains separate from processing state, retry/reconnect affordances are
visible, Today shows active + action-needed processing together, and back
navigation returns to the previous settings source surface.
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
report_dir="${REPORT_ROOT}/sync-failure-ui-device-${timestamp}"
mkdir -p "$report_dir"

adb_args=(-s "$DEVICE_SERIAL")
run_adb() {
  "$ADB_BIN" "${adb_args[@]}" "$@"
}

run_adb_timed() {
  local timeout_seconds="$1"
  shift
  timeout "${timeout_seconds}s" "$ADB_BIN" "${adb_args[@]}" "$@"
}

dump_ui() {
  local target="$1"
  run_adb_timed 20 exec-out uiautomator dump /dev/tty > "$target" 2>/dev/null || true
}

wake_device() {
  run_adb_timed 10 shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  run_adb_timed 10 shell wm dismiss-keyguard >/dev/null 2>&1 || true
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
  local attempts="${3:-24}"
  for _ in $(seq 1 "$attempts"); do
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

open_deeplink() {
  local uri="$1"
  local output="$2"
  local shell_uri="${uri//&/\\&}"
  wake_device
  run_adb_timed 20 shell am start \
    -a android.intent.action.VIEW \
    -d "$shell_uri" \
    "$PACKAGE_NAME" \
    > "$output"
  sleep 3
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
  -a com.becalm.android.DEBUG_SEED_SYNC_FAILURE_UI_SMOKE \
  -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver" \
  > "${report_dir}/seed-broadcast.txt"
sleep 3

open_deeplink \
  "becalm://oauth-complete?result=success&provider=gmail&family=mail" \
  "${report_dir}/open-oauth-complete.txt"

sources_dump="${report_dir}/ui-sources.xml"
if ! wait_for_text "$sources_dump" "데이터 출처"; then
  echo "Sources list did not open after OAuth completion deep link. See $report_dir" >&2
  exit 1
fi
if ! contains_text "$sources_dump" "Gmail"; then
  echo "Sources list did not show Gmail. See $report_dir" >&2
  exit 1
fi
if ! find_text_with_swipes "정리 상태: 내용 정리 중" "ui-find-gmail-processing" >/dev/null; then
  echo "Sources list did not show Gmail processing separately from connection status. See $report_dir" >&2
  exit 1
fi
if ! find_text_with_swipes "다음에 할 일: 다시 연결" "ui-find-outlook-reconnect-action" >/dev/null; then
  echo "Sources list did not show Outlook reconnect action. See $report_dir" >&2
  exit 1
fi

open_deeplink \
  "becalm://settings/sources/gmail" \
  "${report_dir}/open-gmail-detail.txt"
gmail_detail_dump="${report_dir}/ui-gmail-detail.xml"
if ! wait_for_text "$gmail_detail_dump" "연결 상태"; then
  echo "Gmail source detail did not open. See $report_dir" >&2
  exit 1
fi
for expected in \
  "연결 상태" \
  "최근 확인함" \
  "연결되었습니다." \
  "내용을 정리하고 있습니다." \
  "새 기록 확인"
do
  if ! contains_text "$gmail_detail_dump" "$expected" && ! find_text_with_swipes "$expected" "ui-find-gmail-detail-${expected// /-}" >/dev/null; then
    echo "Gmail source detail missed expected text: $expected. See $report_dir" >&2
    exit 1
  fi
done

run_adb shell input keyevent KEYCODE_BACK
sleep 1
back_to_sources_dump="${report_dir}/ui-back-to-sources-after-gmail.xml"
if ! wait_for_text "$back_to_sources_dump" "데이터 출처"; then
  echo "Back from Gmail detail did not return to Sources. See $report_dir" >&2
  exit 1
fi

open_deeplink \
  "becalm://settings/sources/outlook_mail" \
  "${report_dir}/open-outlook-detail.txt"
outlook_detail_dump="${report_dir}/ui-outlook-detail.xml"
if ! wait_for_text "$outlook_detail_dump" "연결 상태"; then
  echo "Outlook source detail did not open. See $report_dir" >&2
  exit 1
fi
for expected in \
  "오류" \
  "연결을 다시 확인해야 합니다. 다시 연결하면 다음 확인부터 이어집니다." \
  "다시 연결" \
  "새 기록 확인" \
  "정리를 끝내려면 확인이 필요합니다."
do
  if ! contains_text "$outlook_detail_dump" "$expected" && ! find_text_with_swipes "$expected" "ui-find-outlook-detail-${expected// /-}" >/dev/null; then
    echo "Outlook source detail missed expected text: $expected. See $report_dir" >&2
    exit 1
  fi
done

run_adb shell input keyevent KEYCODE_BACK
sleep 1
back_to_sources_after_outlook_dump="${report_dir}/ui-back-to-sources-after-outlook.xml"
if ! wait_for_text "$back_to_sources_after_outlook_dump" "데이터 출처"; then
  echo "Back from Outlook detail did not return to Sources. See $report_dir" >&2
  exit 1
fi

wake_device
run_adb_timed 20 shell am start \
  -n "${PACKAGE_NAME}/.MainActivity" \
  --es com.becalm.android.extra.START_ROUTE today \
  > "${report_dir}/open-today.txt"
sleep 2

today_dump="${report_dir}/ui-today.xml"
if ! wait_for_text "$today_dump" "1개 연결 확인 중, 1개 확인 필요"; then
  echo "Today did not show combined active/action-needed processing strip. See $report_dir" >&2
  exit 1
fi
if ! contains_text "$today_dump" "상세 보기"; then
  echo "Today processing strip did not expose the detail action. See $report_dir" >&2
  exit 1
fi
tap_text_from_dump "$today_dump" "상세 보기"
sleep 2

processing_detail_dump="${report_dir}/ui-processing-status.xml"
if ! wait_for_text "$processing_detail_dump" "정리 상태"; then
  echo "Processing status detail did not open from Today. See $report_dir" >&2
  exit 1
fi
for expected in \
  "1개 연결 확인 중, 1개 확인 필요" \
  "확인 중" \
  "확인 필요" \
  "Gmail" \
  "Outlook"
do
  if ! contains_text "$processing_detail_dump" "$expected" && ! find_text_with_swipes "$expected" "ui-find-processing-detail-${expected// /-}" >/dev/null; then
    echo "Processing status detail missed expected text: $expected. See $report_dir" >&2
    exit 1
  fi
done

run_adb logcat -d -v time > "${report_dir}/logcat.txt" || true
fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat.txt" || true
)"
raw_internal_codes_visible=false
if grep -R -Fq \
  -e "oauth_reauth_required" \
  -e "source_sync_backpressure_delayed" \
  "$report_dir"/ui-*.xml "$report_dir"/ui-find-*.xml 2>/dev/null; then
  raw_internal_codes_visible=true
fi

{
  echo "sync_failure_ui_device_smoke_result=passed"
  echo "package=${PACKAGE_NAME}"
  echo "device=${DEVICE_SERIAL}"
  echo "oauth_completion_sources_visible=true"
  echo "source_processing_separated=true"
  echo "source_retry_affordance_visible=true"
  echo "back_navigation_returned_sources=true"
  echo "today_active_action_strip_visible=true"
  echo "processing_detail_opened=true"
  echo "raw_internal_codes_visible=${raw_internal_codes_visible}"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "report_dir=${report_dir}"
} > "${report_dir}/summary.env"

if [[ "$raw_internal_codes_visible" != "false" ]]; then
  echo "Device smoke found raw internal status codes in UI dumps. See $report_dir" >&2
  exit 1
fi
if [[ "$fatal_count" != "0" ]]; then
  echo "Device smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Sync/failure UI device smoke passed. Report: $report_dir"
