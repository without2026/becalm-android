#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_PROJECT="$REPO_ROOT/android"
WORKSPACE_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

PACKAGE_NAME="${BECALM_ANDROID_PACKAGE:-com.becalm.android}"
DEVICE_SERIAL="${ADB_DEVICE:-emulator-5554}"
DEFAULT_ADB="/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe"
ADB_BIN="${ADB:-$DEFAULT_ADB}"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="adb"
fi

SOURCE_AUDIO="${1:-$WORKSPACE_ROOT/becalm-backend/data/private_llm_fixtures/calls/meeting_001.wav}"
CLIP_SECONDS="${BECALM_CLOVA_E2E_SECONDS:-90}"
TIMEOUT_SECONDS="${BECALM_CLOVA_E2E_TIMEOUT_SECONDS:-900}"
SOURCE_TYPE="${BECALM_CLOVA_E2E_SOURCE_TYPE:-meeting}"

if [[ ! -f "$SOURCE_AUDIO" ]]; then
  echo "Missing audio fixture: $SOURCE_AUDIO" >&2
  exit 1
fi

TMP_DIR="$REPO_ROOT/qa/emulator/tmp"
mkdir -p "$TMP_DIR"

if [[ "$CLIP_SECONDS" == "full" ]]; then
  AUDIO_TO_PUSH="$SOURCE_AUDIO"
  DURATION_SECONDS="$(python3 - "$SOURCE_AUDIO" <<'PY'
import sys, wave
with wave.open(sys.argv[1], "rb") as wav:
    print(int(round(wav.getnframes() / wav.getframerate())))
PY
)"
else
  AUDIO_TO_PUSH="$TMP_DIR/clova_meeting_001_${CLIP_SECONDS}s.wav"
  DURATION_SECONDS="$(python3 - "$SOURCE_AUDIO" "$AUDIO_TO_PUSH" "$CLIP_SECONDS" <<'PY'
import sys, wave
source, target, seconds_raw = sys.argv[1:4]
seconds = int(seconds_raw)
if seconds <= 0:
    raise SystemExit("BECALM_CLOVA_E2E_SECONDS must be positive or 'full'")
with wave.open(source, "rb") as src:
    params = src.getparams()
    frame_count = min(src.getnframes(), seconds * src.getframerate())
    with wave.open(target, "wb") as dst:
        dst.setparams(params)
        remaining = frame_count
        while remaining > 0:
            chunk = min(remaining, src.getframerate() * 10)
            dst.writeframes(src.readframes(chunk))
            remaining -= chunk
    print(int(round(frame_count / src.getframerate())))
PY
)"
fi

HOST="$(python3 - <<'PY'
from urllib.parse import urlparse
from pathlib import Path
props = Path('/home/jakek/without/becalm-android/android/local.properties').read_text(errors='ignore').splitlines()
base = ''
for line in props:
    if line.startswith('BECALM_API_BASE_URL='):
        base = line.split('=', 1)[1].strip()
        break
print(urlparse(base).hostname or 'dev-dev-7309.up.railway.app')
PY
)"

TARGET_NAME="$(basename "$AUDIO_TO_PUSH")"
TMP_DEVICE_DIR="/data/local/tmp/becalm_clova_e2e"
TMP_DEVICE_PATH="$TMP_DEVICE_DIR/$TARGET_NAME"
APP_RELATIVE_PATH="files/qa/clova/$TARGET_NAME"
APP_FILE_PATH="/data/data/$PACKAGE_NAME/$APP_RELATIVE_PATH"
ACTION="com.becalm.android.DEBUG_ENQUEUE_CLOVA_AUDIO_E2E"
RECEIVER="$PACKAGE_NAME/.debug.DebugPersonRenderingSeedReceiver"

echo "ADB: $ADB_BIN"
echo "Device: $DEVICE_SERIAL"
echo "Audio: $AUDIO_TO_PUSH"
echo "Duration seconds: $DURATION_SECONDS"
echo "Source type: $SOURCE_TYPE"
echo "Backend host: $HOST"

"$ADB_BIN" -s "$DEVICE_SERIAL" wait-for-device
if "$ADB_BIN" -s "$DEVICE_SERIAL" shell "ping -c 1 -W 2 '$HOST'" 2>&1 | grep -qi "unknown host"; then
  echo "Emulator cannot resolve backend host before test: $HOST" >&2
  exit 4
fi

if [[ "${BECALM_SKIP_INSTALL:-0}" != "1" ]]; then
  (cd "$ANDROID_PROJECT" && ./gradlew :app:assembleDebug)
  "$ADB_BIN" -s "$DEVICE_SERIAL" install -r "$ANDROID_PROJECT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
fi

if [[ "${BECALM_E2E_CLEAR_APP:-0}" == "1" ]]; then
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null
  sleep "${BECALM_E2E_POST_CLEAR_SLEEP_SECONDS:-8}"
fi

APP_UID="$("$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd package list packages -U "$PACKAGE_NAME" | sed -n 's/.*uid://p' | tr -d '\r')"
"$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd deviceidle whitelist +"$PACKAGE_NAME" >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell am set-standby-bucket "$PACKAGE_NAME" active >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd appops set "$PACKAGE_NAME" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd appops set "$PACKAGE_NAME" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
if [[ -n "$APP_UID" ]]; then
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd netpolicy add restrict-background-whitelist "$APP_UID" >/dev/null 2>&1 || true
fi

"$ADB_BIN" -s "$DEVICE_SERIAL" shell pm grant "$PACKAGE_NAME" android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

"$ADB_BIN" -s "$DEVICE_SERIAL" shell "mkdir -p '$TMP_DEVICE_DIR'"
"$ADB_BIN" -s "$DEVICE_SERIAL" push "$AUDIO_TO_PUSH" "$TMP_DEVICE_PATH" >/dev/null
"$ADB_BIN" -s "$DEVICE_SERIAL" shell "chmod 644 '$TMP_DEVICE_PATH'"
"$ADB_BIN" -s "$DEVICE_SERIAL" shell run-as "$PACKAGE_NAME" sh -c "'mkdir -p files/qa/clova && cp \"$TMP_DEVICE_PATH\" \"$APP_RELATIVE_PATH\" && chmod 600 \"$APP_RELATIVE_PATH\"'"

"$ADB_BIN" -s "$DEVICE_SERIAL" logcat -c
broadcast_args=(
  shell am broadcast
  --receiver-foreground
  -a "$ACTION"
  -n "$RECEIVER"
  --es audio_path "$APP_FILE_PATH"
  --ei duration_seconds "$DURATION_SECONDS"
  --es source_type "$SOURCE_TYPE"
  --es event_title "Clova_E2E_meeting_001_${DURATION_SECONDS}s"
)
if [[ -n "${BECALM_E2E_ACCESS_TOKEN:-}" && -n "${BECALM_E2E_USER_ID:-}" ]]; then
  broadcast_args+=(--es access_token "$BECALM_E2E_ACCESS_TOKEN")
  broadcast_args+=(--es user_id "$BECALM_E2E_USER_ID")
  broadcast_args+=(--es refresh_token "${BECALM_E2E_REFRESH_TOKEN:-}")
  broadcast_args+=(--es email "${BECALM_E2E_EMAIL:-adb-clova-e2e@becalm.local}")
  broadcast_args+=(--el expires_at_epoch_ms "${BECALM_E2E_EXPIRES_AT_EPOCH_MS:-$(( ($(date +%s) + 3600) * 1000 ))}")
fi
"$ADB_BIN" -s "$DEVICE_SERIAL" "${broadcast_args[@]}"

echo "Waiting for VoiceUploadWorker / Clova pipeline result..."
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
last_log="$TMP_DIR/clova_e2e_last.log"
while (( $(date +%s) < deadline )); do
  "$ADB_BIN" -s "$DEVICE_SERIAL" logcat -d -v time > "$last_log"
  if grep -q "upload success" "$last_log"; then
    grep -E "Debug Clova audio E2E|VoiceUploadWorker|StructuredExtractionPersister|upload success" "$last_log" || true
    echo "Clova audio E2E succeeded."
    exit 0
  fi
  if grep -Eq "Debug action failed|No active userId|Sign in or provide|audio permission not granted|cannot open audio stream|HTTP (401|403|413|422|500|502|503|504)|non-retryable" "$last_log"; then
    grep -E "Debug action failed|No active userId|Sign in or provide|Debug Clova audio E2E|VoiceUploadWorker|HTTP (401|403|413|422|500|502|503|504)|audio permission|cannot open audio|non-retryable|mark failed" "$last_log" || true
    echo "Clova audio E2E failed. Full log saved to $last_log" >&2
    exit 2
  fi
  sleep 10
done

grep -E "Debug Clova audio E2E|VoiceUploadWorker|StructuredExtractionPersister|HTTP |Work .*VoiceUploadWorker|WM-WorkerWrapper" "$last_log" || true
echo "Timed out waiting for Clova audio E2E. Full log saved to $last_log" >&2
exit 3
