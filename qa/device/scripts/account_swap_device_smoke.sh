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

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/account_swap_device_smoke.sh --confirm-debug-seed [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --skip-install             do not install APK before running.
  --skip-clear-data          do not clear app data before seeding.
  --confirm-debug-seed       Required. Replaces the local debug user/session with QA seed data.
  -h, --help                 Show this help.

This smoke uses debug-only seed data to prove an in-process account swap opens
the second user's Room projection without exposing the first user's local rows.
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
report_dir="${REPORT_ROOT}/account-swap-device-${timestamp}"
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

pid_of_app() {
  run_adb shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}'
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
  -a com.becalm.android.DEBUG_SEED_ACCOUNT_SWAP_SMOKE \
  -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver" \
  > "${report_dir}/seed-broadcast.txt"
sleep 3

pid_after_seed="$(pid_of_app)"
if [[ -z "$pid_after_seed" ]]; then
  echo "Could not read app PID after account-swap seed. See $report_dir" >&2
  exit 1
fi
echo "$pid_after_seed" > "${report_dir}/pid-after-seed.txt"

wake_device
run_adb shell am start \
  -a android.intent.action.VIEW \
  -d "becalm://persons" \
  "$PACKAGE_NAME" \
  > "${report_dir}/open-persons.txt"

persons_dump="${report_dir}/ui-persons.xml"
if ! wait_for_text "$persons_dump" "Account B Contact"; then
  echo "Persons list did not show the current account B contact. See $report_dir" >&2
  exit 1
fi
if contains_text "$persons_dump" "Account A Contact"; then
  echo "Persons list exposed account A contact while account B is current. See $report_dir" >&2
  exit 1
fi

pid_after_persons="$(pid_of_app)"
echo "$pid_after_persons" > "${report_dir}/pid-after-persons.txt"
if [[ "$pid_after_seed" != "$pid_after_persons" ]]; then
  echo "App PID changed between seed and account B UI; in-process swap proof failed. See $report_dir" >&2
  exit 1
fi

tap_text_from_dump "$persons_dump" "Account B Contact"
detail_dump="${report_dir}/ui-person-detail.xml"
if ! wait_for_text "$detail_dump" "Account B Contact"; then
  echo "Person detail did not open for account B contact. See $report_dir" >&2
  exit 1
fi
if contains_text "$detail_dump" "Account A Contact"; then
  echo "Person detail exposed account A contact while account B is current. See $report_dir" >&2
  exit 1
fi

run_adb shell input keyevent KEYCODE_BACK
back_dump="${report_dir}/ui-after-back.xml"
if ! wait_for_text "$back_dump" "사람"; then
  echo "Back navigation did not return to the persons surface. See $report_dir" >&2
  exit 1
fi
if ! wait_for_text "$back_dump" "Account B Contact"; then
  echo "Back navigation lost account B list state. See $report_dir" >&2
  exit 1
fi
if contains_text "$back_dump" "Account A Contact"; then
  echo "Back navigation exposed account A contact while account B is current. See $report_dir" >&2
  exit 1
fi

pid_after_back="$(pid_of_app)"
echo "$pid_after_back" > "${report_dir}/pid-after-back.txt"
if [[ "$pid_after_seed" != "$pid_after_back" ]]; then
  echo "App PID changed during account B navigation; in-process proof failed. See $report_dir" >&2
  exit 1
fi

run_adb logcat -d -v time > "${report_dir}/logcat.txt" || true
fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat.txt" || true
)"

{
  echo "account_swap_device_smoke_result=completed"
  echo "package=${PACKAGE_NAME}"
  echo "device=${DEVICE_SERIAL}"
  echo "pid_after_seed=${pid_after_seed}"
  echo "pid_after_persons=${pid_after_persons}"
  echo "pid_after_back=${pid_after_back}"
  echo "pid_stable=true"
  echo "account_b_visible=true"
  echo "account_a_visible=false"
  echo "back_navigation_preserved_account_b=true"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "report_dir=${report_dir}"
} > "${report_dir}/summary.env"

if [[ "$fatal_count" != "0" ]]; then
  echo "Device smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Account swap device smoke passed. Report: $report_dir"
