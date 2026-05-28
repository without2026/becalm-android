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
MAX_SWIPES=80

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/scale_slice_device_smoke.sh --confirm-debug-seed [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --skip-install             do not install APK before running.
  --skip-clear-data          do not clear app data before seeding.
  --max-swipes N             max list swipes while searching the long timeline. Default: 80
  --confirm-debug-seed       Required. Replaces the local debug user/session with QA seed data.
  -h, --help                 Show this help.

This smoke uses debug-only seed data to prove the device can open a 220-item
person timeline, expose the 150-item "더 보기" pagination action, and render the
oldest item after expanding. It does not prove real OAuth/Samsung account flows.
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
  echo "Build it first, for example: ./gradlew :app:assembleDebug" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
report_dir="${REPORT_ROOT}/scale-slice-device-${timestamp}"
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

contains_text() {
  local file="$1"
  local text="$2"
  grep -Fq "$text" "$file"
}

contains_package() {
  local file="$1"
  grep -Fq "package=\"${PACKAGE_NAME}\"" "$file"
}

wait_for_app_dump() {
  local target="$1"
  local attempts="${2:-12}"
  for i in $(seq 1 "$attempts"); do
    wake_device
    dump_ui "$target"
    if contains_package "$target"; then
      return 0
    fi
    sleep 1
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
  -a com.becalm.android.DEBUG_SEED_SCALE_PERSON_TIMELINE \
  -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver" \
  > "${report_dir}/seed-broadcast.txt"
sleep 3

wake_device
run_adb shell am start \
  -a android.intent.action.VIEW \
  -d "becalm://persons/qa-scale-person" \
  "${PACKAGE_NAME}" \
  > "${report_dir}/open-person-detail.txt"
sleep 1

if ! wait_for_app_dump "${report_dir}/ui-person-detail-initial.xml"; then
  echo "Device did not expose ${PACKAGE_NAME} UI after launch. It may still be locked. See $report_dir" >&2
  exit 1
fi
if ! contains_text "${report_dir}/ui-person-detail-initial.xml" "Scale Customer"; then
  echo "Person detail did not show Scale Customer. See $report_dir" >&2
  exit 1
fi

load_more_dump=""
for i in $(seq 1 "$MAX_SWIPES"); do
  current_dump="${report_dir}/ui-find-load-more-${i}.xml"
  dump_ui "$current_dump"
  if contains_text "$current_dump" "더 보기"; then
    load_more_dump="$current_dump"
    break
  fi
  run_adb shell input swipe 540 2200 540 500 350
  sleep 0.4
done

if [[ -z "$load_more_dump" ]]; then
  echo "Could not find person detail load-more action after $MAX_SWIPES swipes. See $report_dir" >&2
  exit 1
fi

tap_text_from_dump "$load_more_dump" "더 보기"
sleep 2

oldest_dump=""
for i in $(seq 1 "$MAX_SWIPES"); do
  current_dump="${report_dir}/ui-find-oldest-${i}.xml"
  dump_ui "$current_dump"
  if contains_text "$current_dump" "Scale mail 219"; then
    oldest_dump="$current_dump"
    break
  fi
  run_adb shell input swipe 540 2200 540 500 350
  sleep 0.4
done

run_adb logcat -d -v time > "${report_dir}/logcat.txt" || true
fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat.txt" || true
)"

{
  echo "scale_slice_device_smoke_result=completed"
  echo "package=${PACKAGE_NAME}"
  echo "device=${DEVICE_SERIAL}"
  echo "person_detail_loaded=true"
  echo "load_more_found=true"
  echo "oldest_item_found=$([[ -n "$oldest_dump" ]] && echo true || echo false)"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "report_dir=${report_dir}"
} > "${report_dir}/summary.env"

if [[ -z "$oldest_dump" ]]; then
  echo "Load more was tapped, but oldest item was not visible after $MAX_SWIPES swipes. See $report_dir" >&2
  exit 1
fi

if [[ "$fatal_count" != "0" ]]; then
  echo "Device smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Scale slice device smoke passed. Report: $report_dir"
