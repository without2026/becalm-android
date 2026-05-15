#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.becalm.android"
ADB_BIN="${ADB:-adb}"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
REPORT_ROOT="qa/device/reports"
CLEAR_DATA=false

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/fresh_no_user_data_smoke.sh --confirm-clear-data [options]

Options:
  --adb PATH                 adb executable. On WSL/Windows this can be adb.exe.
  --device SERIAL            target device serial.
  --apk PATH                 APK to install. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --confirm-clear-data       Required. Runs "pm clear" for the package.
  -h, --help                 Show this help.

This script verifies the destructive reset boundary only for Android local app data.
A true no-user-data beta run also needs a backend-clean tester account or an admin
backend data reset for the authenticated user.
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
    --confirm-clear-data)
      CLEAR_DATA=true
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

if [[ "$CLEAR_DATA" != "true" ]]; then
  echo "Refusing to run fresh smoke without --confirm-clear-data." >&2
  echo "This test deletes local app data for ${PACKAGE_NAME}." >&2
  exit 2
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Build it first, for example: ./gradlew :app:assembleDebug" >&2
  exit 2
fi

ADB_ARGS=()
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB_ARGS=(-s "$DEVICE_SERIAL")
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
report_dir="${REPORT_ROOT}/fresh-no-user-data-${timestamp}"
mkdir -p "$report_dir"

run_adb() {
  "$ADB_BIN" "${ADB_ARGS[@]}" "$@"
}

echo "Writing report to $report_dir"
"$ADB_BIN" devices -l > "${report_dir}/devices.txt"

run_adb get-state > "${report_dir}/device-state.txt"
run_adb shell getprop ro.product.model > "${report_dir}/device-model.txt"
run_adb shell getprop ro.build.version.release > "${report_dir}/android-release.txt"
run_adb shell getprop ro.build.version.sdk > "${report_dir}/android-sdk.txt"

run_adb install -r "$APK_PATH" > "${report_dir}/install.txt"
run_adb logcat -c || true
run_adb shell pm clear "$PACKAGE_NAME" > "${report_dir}/pm-clear.txt"
run_adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 > "${report_dir}/launch.txt"
sleep 5

run_adb shell dumpsys window > "${report_dir}/window.txt" || true
run_adb exec-out uiautomator dump /dev/tty > "${report_dir}/ui-initial.xml" || true
run_adb logcat -d -t 1200 > "${report_dir}/logcat-initial.txt" || true

fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|OutOfMemoryError|lowmemorykiller.*${PACKAGE_NAME}|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat-initial.txt" || true
)"

{
  echo "fresh_no_user_data_smoke_result=started"
  echo "package=${PACKAGE_NAME}"
  echo "apk=${APK_PATH}"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "manual_next_steps=login_onboarding_google_oauth_gmail_calendar_source_status_restart"
} > "${report_dir}/summary.env"

if [[ "$fatal_count" != "0" ]]; then
  echo "Fresh smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Fresh local reset smoke reached first screen without fatal/ANR/OOM."
echo "Continue manually from the current device screen and record notes in $report_dir/manual-notes.md."
