#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
SCREENSHOT_DIR="$ROOT_DIR/qa/emulator/screenshots/meeting-speaker-full-journey"
TMP_XML="$(mktemp)"

cleanup() {
  rm -f "$TMP_XML"
}
trap cleanup EXIT

if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or not executable: $ADB_BIN" >&2
  echo "Set ADB=/path/to/adb and rerun." >&2
  exit 1
fi

mkdir -p "$SCREENSHOT_DIR"

dump_ui() {
  "$ADB_BIN" -s "$DEVICE" shell uiautomator dump /sdcard/window.xml >/dev/null
  "$ADB_BIN" -s "$DEVICE" shell cat /sdcard/window.xml > "$TMP_XML"
}

screenshot() {
  local name="$1"
  "$ADB_BIN" -s "$DEVICE" exec-out screencap -p > "$SCREENSHOT_DIR/$name.png"
}

ui_excerpt() {
  tr '<' '\n' < "$TMP_XML" | grep -E 'text=|content-desc=' | head -n 160 >&2 || true
}

require_text() {
  local text="$1"
  if ! grep -Fq "$text" "$TMP_XML"; then
    echo "Missing expected UI text: $text" >&2
    ui_excerpt
    screenshot "missing-$(echo "$text" | tr -cd '[:alnum:]_-')"
    exit 1
  fi
}

tap_text() {
  local text="$1"
  local coords
  coords="$(python3 - "$TMP_XML" "$text" <<'PY'
import html
import re
import sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
needle = sys.argv[2]
for node in re.finditer(r'<node\b[^>]*>', xml):
    s = html.unescape(node.group(0))
    if f'text="{needle}"' not in s and f'content-desc="{needle}"' not in s:
        continue
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
)"
  if [[ -z "$coords" ]]; then
    echo "Unable to tap missing text: $text" >&2
    ui_excerpt
    exit 1
  fi
  "$ADB_BIN" -s "$DEVICE" shell input tap $coords
}

open_deep_link() {
  local link="$1"
  "$ADB_BIN" -s "$DEVICE" shell am start -S \
    -a android.intent.action.VIEW \
    -d "$link" \
    -n com.becalm.android/.MainActivity >/dev/null
  sleep 2
  dump_ui
}

wait_for_text() {
  local text="$1"
  local label="${2:-$text}"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    dump_ui
    if grep -Fq "$text" "$TMP_XML"; then
      return
    fi
    sleep 1
  done
  echo "Timed out waiting for: $label" >&2
  ui_excerpt
  screenshot "timeout-$label"
  exit 1
}

"$ROOT_DIR/qa/emulator/scripts/seed_emulator_qa.sh"

"$ADB_BIN" -s "$DEVICE" wait-for-device
"$ADB_BIN" -s "$DEVICE" shell am broadcast \
  -a com.becalm.android.DEBUG_SEED_MEETING_SPEAKER_JOURNEY \
  -n com.becalm.android/.debug.DebugPersonRenderingSeedReceiver >/dev/null
sleep 2

open_deep_link "becalm://persons"
require_text "사람"
require_text "Customer Lee"
require_text "매칭 확인"
screenshot "01-persons-review-required"

tap_text "매칭 확인"
sleep 1
dump_ui
require_text "사람 확인"
require_text "SPEAKER_02"
require_text "Customer Lee"
screenshot "02-speaker-review-queue"

if grep -Fq "Customer Lee" "$TMP_XML"; then
  tap_text "Customer Lee" || true
  sleep 1
  dump_ui
fi
if grep -Fq "사람 매칭" "$TMP_XML"; then
  tap_text "사람 매칭" || true
  sleep 2
elif grep -Fq "확인" "$TMP_XML"; then
  tap_text "확인" || true
  sleep 2
fi

open_deep_link "becalm://persons"
tap_text "Customer Lee"
sleep 1
dump_ui
require_text "Customer Lee"
require_text "타임라인"
require_text "회의"
require_text "상대가 해야 할 일"
require_text "일정"
screenshot "03-person-detail-meeting"

MEMORY_ROOT="$("$ADB_BIN" -s "$DEVICE" shell run-as com.becalm.android find files/person_memory -maxdepth 1 -mindepth 1 -type d 2>/dev/null | tr -d '\r' | head -n 1)"
if [[ -z "$MEMORY_ROOT" ]]; then
  echo "Person memory directory was not created." >&2
  screenshot "missing-memory-root"
  exit 1
fi
CUSTOMER_MEMORY="$("$ADB_BIN" -s "$DEVICE" shell run-as com.becalm.android find "$MEMORY_ROOT" -path '*memory.md' -print -exec cat {} \\; 2>/dev/null | tr -d '\r')"
if [[ "$CUSTOMER_MEMORY" != *"Customer Lee"* && "$CUSTOMER_MEMORY" != *"제안서"* ]]; then
  echo "Customer memory.md or semantic input was not generated for the meeting journey." >&2
  exit 1
fi

cat <<EOF
Meeting speaker matching QA smoke passed on $DEVICE.

Screenshots:
  $SCREENSHOT_DIR

Verified:
  - meeting audio fixture is available in emulator media storage
  - persistent reviewRequired banner opens the matching queue
  - speaker-label review item and existing person choice are visible
  - person detail renders meeting timeline plus take/schedule sections
  - person memory output is generated for the matched person
EOF
