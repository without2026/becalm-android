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
SOURCE_TYPE="gmail"
EXPECTED_COUNT=620
TIMEOUT_SECONDS=240
POLL_SECONDS=10
INSTALL_APK=true
CONFIRM_DEBUG_SESSION=false
USER_ID=""
ACCESS_TOKEN=""
ACCESS_TOKEN_ENV=""
REFRESH_TOKEN=""
REFRESH_TOKEN_ENV=""
EXPIRES_AT_EPOCH_MS=""

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/mirror_resume_device_smoke.sh --confirm-debug-session --access-token-env BECALM_STAGING_JWT [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --source-type TYPE         Source type to refresh. Default: gmail
  --expected-count N         Minimum local mirror rows expected. Default: 620
  --timeout-seconds N        Poll timeout. Default: 240
  --poll-seconds N           Poll interval. Default: 10
  --user-id UUID             Optional user id. Defaults to the access-token subject when omitted.
  --access-token VALUE       Access token value. Prefer --access-token-env to keep it out of shell history.
  --access-token-env ENV     Read access token from ENV. Default for live staging: BECALM_STAGING_JWT.
  --refresh-token VALUE      Optional refresh token for the debug session. Prefer --refresh-token-env when used.
  --refresh-token-env ENV    Read optional refresh token from ENV.
  --expires-at-epoch-ms N    Optional access-token expiry.
  --skip-install             Do not install APK before running.
  --confirm-debug-session    Required. Replaces local debug app session and mirror rows.
  -h, --help                 Show this help.

Before running, seed staging rows with:
  cd ../becalm-backend
  BECALM_STAGING_JWT=... python3 scripts/seed_staging_android_mirror_resume.py --environment staging --service dev
  cd ../becalm-android
  BECALM_STAGING_JWT=... qa/device/scripts/mirror_resume_device_smoke.sh --confirm-debug-session --access-token-env BECALM_STAGING_JWT

The script does not print token values. Reports contain counts and non-secret device metadata only.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb) ADB_BIN="$2"; shift 2 ;;
    --device) DEVICE_SERIAL="$2"; shift 2 ;;
    --apk) APK_PATH="$2"; shift 2 ;;
    --package) PACKAGE_NAME="$2"; shift 2 ;;
    --report-root) REPORT_ROOT="$2"; shift 2 ;;
    --source-type) SOURCE_TYPE="$2"; shift 2 ;;
    --expected-count) EXPECTED_COUNT="$2"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
    --poll-seconds) POLL_SECONDS="$2"; shift 2 ;;
    --user-id) USER_ID="$2"; shift 2 ;;
    --access-token) ACCESS_TOKEN="$2"; shift 2 ;;
    --access-token-env) ACCESS_TOKEN_ENV="$2"; shift 2 ;;
    --refresh-token) REFRESH_TOKEN="$2"; shift 2 ;;
    --refresh-token-env) REFRESH_TOKEN_ENV="$2"; shift 2 ;;
    --expires-at-epoch-ms) EXPIRES_AT_EPOCH_MS="$2"; shift 2 ;;
    --skip-install) INSTALL_APK=false; shift ;;
    --confirm-debug-session) CONFIRM_DEBUG_SESSION=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$CONFIRM_DEBUG_SESSION" != "true" ]]; then
  echo "Refusing to run without --confirm-debug-session." >&2
  echo "This changes local debug app session and clears local mirror rows for ${PACKAGE_NAME}." >&2
  exit 2
fi
if [[ -z "$ACCESS_TOKEN" && -n "$ACCESS_TOKEN_ENV" ]]; then
  ACCESS_TOKEN="${!ACCESS_TOKEN_ENV:-}"
fi
if [[ -z "$REFRESH_TOKEN" && -n "$REFRESH_TOKEN_ENV" ]]; then
  REFRESH_TOKEN="${!REFRESH_TOKEN_ENV:-}"
fi
derive_user_id_from_access_token() {
  ACCESS_TOKEN_FOR_DECODE="$ACCESS_TOKEN" python3 - <<'PY'
import base64
import json
import os
import sys

token = os.environ.get("ACCESS_TOKEN_FOR_DECODE", "")
parts = token.split(".")
if len(parts) < 2:
    sys.exit(1)
payload = parts[1] + "=" * (-len(parts[1]) % 4)
try:
    parsed = json.loads(base64.urlsafe_b64decode(payload.encode("utf-8")))
except Exception:
    sys.exit(1)
subject = parsed.get("sub")
if not subject:
    sys.exit(1)
print(subject)
PY
}
if [[ -z "$USER_ID" && -n "$ACCESS_TOKEN" ]]; then
  USER_ID="$(derive_user_id_from_access_token || true)"
fi
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "--access-token or --access-token-env is required." >&2
  exit 2
fi
if [[ -z "$USER_ID" ]]; then
  echo "--user-id is required when the access token subject cannot be decoded." >&2
  exit 2
fi
if [[ "$INSTALL_APK" == "true" && ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Build it first, for example: ./gradlew :app:assembleDebug" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
report_dir="${REPORT_ROOT}/mirror-resume-device-${timestamp}"
mkdir -p "$report_dir"

adb_args=(-s "$DEVICE_SERIAL")
run_adb() {
  "$ADB_BIN" "${adb_args[@]}" "$@"
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

run_adb logcat -c || true

prepare_args=(
  shell am broadcast
  --receiver-foreground
  -a com.becalm.android.DEBUG_PREPARE_STAGING_MIRROR_SMOKE
  -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver"
  --es user_id "$USER_ID"
  --es access_token "$ACCESS_TOKEN"
  --es source_type "$SOURCE_TYPE"
)
if [[ -n "$REFRESH_TOKEN" ]]; then
  prepare_args+=(--es refresh_token "$REFRESH_TOKEN")
fi
if [[ -n "$EXPIRES_AT_EPOCH_MS" ]]; then
  prepare_args+=(--el expires_at_epoch_ms "$EXPIRES_AT_EPOCH_MS")
fi
run_adb "${prepare_args[@]}" > "${report_dir}/prepare-broadcast.txt"

deadline=$((SECONDS + TIMEOUT_SECONDS))
raw_count=0
source_participant_count=0
commitment_count=0
commitment_participant_count=0
latest_report=""

while [[ $SECONDS -le $deadline ]]; do
  run_adb shell am broadcast \
    --receiver-foreground \
    -a com.becalm.android.DEBUG_REPORT_STAGING_MIRROR_SMOKE \
    -n "${PACKAGE_NAME}/.debug.DebugPersonRenderingSeedReceiver" \
    --es user_id "$USER_ID" \
    --es source_type "$SOURCE_TYPE" \
    > "${report_dir}/report-broadcast-last.txt" || true
  sleep "$POLL_SECONDS"
  run_adb logcat -d -v time > "${report_dir}/logcat.txt" || true
  latest_report="$(grep -F "Debug staging mirror smoke report" "${report_dir}/logcat.txt" | tail -1 || true)"
  if [[ -z "$latest_report" ]]; then
    continue
  fi
  raw_count="$(sed -n 's/.* rawCount=\([0-9][0-9]*\).*/\1/p' <<<"$latest_report")"
  source_participant_count="$(sed -n 's/.* sourceParticipantCount=\([0-9][0-9]*\).*/\1/p' <<<"$latest_report")"
  commitment_count="$(sed -n 's/.* commitmentCount=\([0-9][0-9]*\).*/\1/p' <<<"$latest_report")"
  commitment_participant_count="$(sed -n 's/.* commitmentParticipantCount=\([0-9][0-9]*\).*/\1/p' <<<"$latest_report")"
  raw_count="${raw_count:-0}"
  source_participant_count="${source_participant_count:-0}"
  commitment_count="${commitment_count:-0}"
  commitment_participant_count="${commitment_participant_count:-0}"
  if [[ "$raw_count" -ge "$EXPECTED_COUNT" &&
        "$source_participant_count" -ge "$EXPECTED_COUNT" &&
        "$commitment_count" -ge "$EXPECTED_COUNT" &&
        "$commitment_participant_count" -ge "$EXPECTED_COUNT" ]]; then
    break
  fi
done

fatal_count="$(
  grep -Eci \
    "FATAL EXCEPTION|AndroidRuntime|ANR in ${PACKAGE_NAME}|OutOfMemoryError|Force finishing activity.*${PACKAGE_NAME}|Process ${PACKAGE_NAME}.*has died" \
    "${report_dir}/logcat.txt" || true
)"

{
  echo "mirror_resume_device_smoke_result=completed"
  echo "package=${PACKAGE_NAME}"
  echo "device=${DEVICE_SERIAL}"
  echo "source_type=${SOURCE_TYPE}"
  echo "expected_count=${EXPECTED_COUNT}"
  echo "raw_count=${raw_count}"
  echo "source_participant_count=${source_participant_count}"
  echo "commitment_count=${commitment_count}"
  echo "commitment_participant_count=${commitment_participant_count}"
  echo "fatal_anr_oom_count=${fatal_count}"
  echo "report_dir=${report_dir}"
} > "${report_dir}/summary.env"

if [[ "$raw_count" -lt "$EXPECTED_COUNT" ||
      "$source_participant_count" -lt "$EXPECTED_COUNT" ||
      "$commitment_count" -lt "$EXPECTED_COUNT" ||
      "$commitment_participant_count" -lt "$EXPECTED_COUNT" ]]; then
  echo "Mirror resume counts did not reach expected_count=${EXPECTED_COUNT}. See $report_dir" >&2
  exit 1
fi
if [[ "$fatal_count" != "0" ]]; then
  echo "Device smoke found fatal/ANR/OOM signals. See $report_dir" >&2
  exit 1
fi

echo "Mirror resume device smoke passed. Report: $report_dir"
