#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
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

dump_ui() {
  "$ADB_BIN" -s "$DEVICE" shell uiautomator dump /sdcard/window.xml >/dev/null
  "$ADB_BIN" -s "$DEVICE" shell cat /sdcard/window.xml > "$TMP_XML"
}

tap_text() {
  local text="$1"
  local coords
  coords="$(python3 - "$TMP_XML" "$text" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
text = sys.argv[2]
for node in re.finditer(r'<node\b[^>]*>', xml):
    s = node.group(0)
    if f'text="{text}"' not in s and f'content-desc="{text}"' not in s:
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
    exit 1
  fi
  "$ADB_BIN" -s "$DEVICE" shell input tap $coords
}

require_text() {
  local text="$1"
  if ! grep -Fq "$text" "$TMP_XML"; then
    echo "Missing expected UI text: $text" >&2
    echo "Current UI excerpt:" >&2
    tr '<' '\n' < "$TMP_XML" | grep -E 'text=|content-desc=' | head -n 120 >&2
    exit 1
  fi
}

wait_for_focused_package() {
  local package_name="$1"
  local window_dump
  window_dump="$(mktemp)"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    "$ADB_BIN" -s "$DEVICE" shell dumpsys window > "$window_dump"
    if grep -Fq "$package_name" "$window_dump"; then
      rm -f "$window_dump"
      return
    fi
    sleep 1
  done
  echo "Expected focused package did not appear: $package_name" >&2
  grep -E 'mCurrentFocus|mFocusedApp|mTopActivity' "$window_dump" >&2 || true
  rm -f "$window_dump"
  exit 1
}

open_people_tab() {
  for _ in 1 2 3 4 5; do
    "$ADB_BIN" -s "$DEVICE" shell am start \
      -a android.intent.action.VIEW \
      -d becalm://persons \
      -n com.becalm.android/.MainActivity >/dev/null
    sleep 1
    dump_ui
    if grep -Fq "김영경" "$TMP_XML" && grep -Fq "Jihoon Kang" "$TMP_XML"; then
      return
    fi
  done
  echo "People tab did not show seeded person cards." >&2
  echo "Current UI excerpt:" >&2
  tr '<' '\n' < "$TMP_XML" | grep -E 'text=|content-desc=' | head -n 120 >&2
  exit 1
}

"$ROOT_DIR/qa/emulator/scripts/seed_emulator_qa.sh"

open_people_tab
require_text "사람"
require_text "동기화됨"
require_text "김영경"
require_text "박진규"
require_text "Jihoon Kang"

"$ADB_BIN" -s "$DEVICE" shell input tap 992 2024
sleep 1
dump_ui
require_text "증거 추가"
require_text "메신저 스크린샷"
require_text "회의 녹음"

"$ADB_BIN" -s "$DEVICE" shell input tap 520 1800
wait_for_focused_package "com.google.android.photopicker"

"$ADB_BIN" -s "$DEVICE" shell am force-stop com.google.android.photopicker >/dev/null 2>&1 || true
sleep 1
open_people_tab
tap_text "김영경"
sleep 1
dump_ui
require_text "김영경"
require_text "타임라인"
require_text "상대가 해야 할 일"
require_text "일정"

"$ADB_BIN" -s "$DEVICE" shell input keyevent KEYCODE_BACK
sleep 1
open_people_tab
tap_text "김채린"
sleep 1
dump_ui
require_text "김채린"
require_text "타임라인"
require_text "내가 해야 할 일"

MEMORY_ROOT="$("$ADB_BIN" -s "$DEVICE" shell run-as com.becalm.android find files/person_memory -maxdepth 1 -mindepth 1 -type d | tr -d '\r' | head -n 1)"
if [[ -z "$MEMORY_ROOT" ]]; then
  echo "Person memory directory was not created." >&2
  exit 1
fi
KIM_MEMORY="$("$ADB_BIN" -s "$DEVICE" shell run-as com.becalm.android cat "$MEMORY_ROOT/qa-person-kim-youngkyung/memory.md" | tr -d '\r')"
JIHOON_MEMORY="$("$ADB_BIN" -s "$DEVICE" shell run-as com.becalm.android cat "$MEMORY_ROOT/qa-person-jihoon-kang/memory.md" | tr -d '\r')"
if [[ "$KIM_MEMORY" != *"다음 주 화요일 오후 김영경 센터장님 미팅"* ]]; then
  echo "김영경 memory.md missing expected schedule context." >&2
  exit 1
fi
if [[ "$JIHOON_MEMORY" != *"원문은 로컬에 두고 memory.md와 DB를 분리 관리"* ]]; then
  echo "Jihoon Kang memory.md missing expected decision context." >&2
  exit 1
fi

cat <<EOF
BeCalm emulator QA smoke passed on $DEVICE.

Verified:
  - Korean People tab
  - Connected source seed state
  - Person cards for real screenshot-derived people
  - Evidence import sheet labels
  - Message screenshot opens Android Photo Picker
  - Person detail timeline renders give/take/schedule sections
  - Local memory.md files include schedule and decision context
EOF
