#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
USER_FIXTURE_DIR="$ROOT_DIR/qa/emulator/user_inputs/message_screenshots"
DEFAULT_FIXTURE_DIR="$ROOT_DIR/qa/emulator/fixtures/message_screenshots"
if [[ -d "$USER_FIXTURE_DIR" ]] && find "$USER_FIXTURE_DIR" -maxdepth 1 -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.webp' \) | grep -q .; then
  FIXTURE_DIR="$USER_FIXTURE_DIR"
else
  FIXTURE_DIR="$DEFAULT_FIXTURE_DIR"
fi
REMOTE_DIR="/sdcard/Pictures/BeCalmQa/message_screenshots"
MEETING_FIXTURE="$ROOT_DIR/backend/data/fixture/meeting_001.wav"
if [[ ! -f "$MEETING_FIXTURE" ]]; then
  MEETING_FIXTURE="$ROOT_DIR/qa/emulator/tmp/clova_meeting_001_60s.wav"
fi
REMOTE_MEETING_DIR="/sdcard/Music/BeCalmQa/meetings"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or not executable: $ADB_BIN" >&2
  echo "Set ADB=/path/to/adb and rerun." >&2
  exit 1
fi

if [[ ! -d "$FIXTURE_DIR" ]]; then
  echo "Fixture directory missing: $FIXTURE_DIR" >&2
  exit 1
fi

"$ADB_BIN" -s "$DEVICE" wait-for-device
"$ADB_BIN" -s "$DEVICE" shell am force-stop com.google.android.photopicker >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE" shell am force-stop com.google.android.apps.photos >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE" shell mkdir -p "$REMOTE_DIR"
"$ADB_BIN" -s "$DEVICE" push "$FIXTURE_DIR/." "$REMOTE_DIR/"
if [[ -f "$MEETING_FIXTURE" ]]; then
  "$ADB_BIN" -s "$DEVICE" shell mkdir -p "$REMOTE_MEETING_DIR"
  "$ADB_BIN" -s "$DEVICE" push "$MEETING_FIXTURE" "$REMOTE_MEETING_DIR/meeting_001.wav" >/dev/null
  if ! "$ADB_BIN" -s "$DEVICE" shell cmd media scan-file "$REMOTE_MEETING_DIR/meeting_001.wav" >/dev/null 2>&1; then
    "$ADB_BIN" -s "$DEVICE" shell am broadcast \
      -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file://$REMOTE_MEETING_DIR/meeting_001.wav" >/dev/null || true
  fi
fi

while IFS= read -r image; do
  remote="$REMOTE_DIR/$(basename "$image")"
  if ! "$ADB_BIN" -s "$DEVICE" shell cmd media scan-file "$remote" >/dev/null 2>&1; then
    "$ADB_BIN" -s "$DEVICE" shell am broadcast \
      -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file://$remote" >/dev/null || true
  fi
done < <(find "$FIXTURE_DIR" -maxdepth 1 -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' \) | sort)

"$ADB_BIN" -s "$DEVICE" shell am broadcast \
  -a com.becalm.android.DEBUG_SEED_PERSON_RENDERING \
  -n com.becalm.android/.debug.DebugPersonRenderingSeedReceiver >/dev/null

sleep 1
"$ADB_BIN" -s "$DEVICE" shell am force-stop com.becalm.android >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE" shell am start -S \
  -a android.intent.action.VIEW \
  -d becalm://persons \
  -n com.becalm.android/.MainActivity >/dev/null
sleep 2

cat <<EOF
Seeded BeCalm QA data on $DEVICE.

Images:
  $REMOTE_DIR

Meeting audio:
  $REMOTE_MEETING_DIR/meeting_001.wav

Debug account:
  debug.person.rendering@becalm.local

Expected people:
  김현수, 김영경, 김채린, 박진규, Jihoon Kang, Kye Lim
EOF
