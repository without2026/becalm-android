#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB_BIN="${ADB:-/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
SCREENSHOT_DIR="${SCREENSHOT_DIR:-$ROOT_DIR/qa/emulator/screenshots/full-app-qa/$RUN_ID}"
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

mkdir -p "$SCREENSHOT_DIR/xml"
MANIFEST="$SCREENSHOT_DIR/manifest.tsv"
REPORT="$SCREENSHOT_DIR/report.md"
printf "name\troute_or_action\tpackage\tpng\txml\tstatus\tvisible_text\n" > "$MANIFEST"

adb_device() {
  "$ADB_BIN" -s "$DEVICE" "$@"
}

dump_ui() {
  adb_device shell uiautomator dump /data/local/tmp/becalm-window.xml >/dev/null
  adb_device shell cat /data/local/tmp/becalm-window.xml > "$TMP_XML"
}

focused_package() {
  adb_device shell dumpsys window |
    grep -E 'mCurrentFocus|mFocusedApp|mTopActivity' |
    tr '\r\n' ' ' |
    sed -E 's/.* ([a-zA-Z0-9_.]+)\/[a-zA-Z0-9_.$]+.*/\1/' |
    awk '{print $1}'
}

visible_text_summary() {
  python3 - "$TMP_XML" <<'PY'
import html
import re
import sys

xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
values = []
for node in re.finditer(r"<node\b[^>]*>", xml):
    raw = html.unescape(node.group(0))
    for attr in ("text", "content-desc"):
        match = re.search(attr + r'="([^"]*)"', raw)
        if match and match.group(1).strip():
            value = match.group(1).strip().replace("\n", " ")
            if value not in values:
                values.append(value)
    if len(values) >= 10:
        break
print(" | ".join(values))
PY
}

urlencode() {
  python3 - "$1" <<'PY'
import sys
import urllib.parse

print(urllib.parse.quote(sys.argv[1], safe=""))
PY
}

screenshot() {
  local name="$1"
  adb_device exec-out screencap -p > "$SCREENSHOT_DIR/$name.png"
}

close_external_pickers() {
  adb_device shell am force-stop com.google.android.photopicker >/dev/null 2>&1 || true
  adb_device shell am force-stop com.google.android.providers.media.module >/dev/null 2>&1 || true
  adb_device shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
  sleep 0.3
}

record_capture() {
  local name="$1"
  local route="$2"
  local status="${3:-ok}"
  local package_name
  local text
  local xml_path="$SCREENSHOT_DIR/xml/$name.xml"
  package_name="$(focused_package || true)"
  if [[ "$status" == "ok" && "$name" != "46-upload-message-screenshot-picker" && "$name" != "48-upload-meeting-audio-picker" && "$package_name" != "com.becalm.android" ]]; then
    status="wrong-package"
  fi
  cp "$TMP_XML" "$xml_path"
  text="$(visible_text_summary | tr '\t' ' ' | tr '\r\n' ' ')"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$name" "$route" "$package_name" "$SCREENSHOT_DIR/$name.png" "$xml_path" "$status" "$text" >> "$MANIFEST"
}

capture_current() {
  local name="$1"
  local route="$2"
  sleep "${3:-2}"
  dump_ui
  screenshot "$name"
  record_capture "$name" "$route" "ok"
}

open_route() {
  local name="$1"
  local route="$2"
  local encoded
  encoded="$(urlencode "$route")"
  close_external_pickers
  adb_device shell am start -S \
    -a android.intent.action.VIEW \
    -d "becalm://qa/route?path=$encoded" \
    -n com.becalm.android/.MainActivity >/dev/null
  capture_current "$name" "$route" "${3:-2}"
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
for node in re.finditer(r"<node\b[^>]*>", xml):
    raw = html.unescape(node.group(0))
    if f'text="{needle}"' not in raw and f'content-desc="{needle}"' not in raw:
        continue
    match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
    if not match:
        continue
    x1, y1, x2, y2 = map(int, match.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
)"
  if [[ -z "$coords" ]]; then
    echo "Unable to tap missing text: $text" >&2
    tr '<' '\n' < "$TMP_XML" | grep -E 'text=|content-desc=' | head -n 120 >&2 || true
    return 1
  fi
  adb_device shell input tap $coords
}

capture_auth_gate() {
  adb_device shell pm clear com.becalm.android >/dev/null
  adb_device shell am start -S -n com.becalm.android/.MainActivity >/dev/null
  sleep 0.7
  dump_ui
  screenshot "00-auth-gate-splash"
  record_capture "00-auth-gate-splash" "launcher:splash/auth-gate" "ok"
  open_route "01-terms" "terms" 1
  open_route "02-login-secure" "login" 1
}

capture_routes() {
  local routes=(
    "03-onboarding-setup|onboarding/setup"
    "04-onboarding-pipa-consent|onboarding/pipa-consent"
    "05-onboarding-recording-folder|onboarding/recording-folder"
    "06-onboarding-call-log-matching|onboarding/call-log-matching"
    "07-onboarding-contacts|onboarding/contacts"
    "08-onboarding-sources|onboarding/sources"
    "09-onboarding-email-pipa-gmail|onboarding/pipa-email/gmail"
    "10-onboarding-email-pipa-outlook|onboarding/pipa-email/outlook_mail"
    "11-onboarding-email-pipa-imap|onboarding/pipa-email/imap"
    "12-onboarding-gmail|onboarding/gmail"
    "13-onboarding-outlook-mail|onboarding/outlook-mail"
    "14-onboarding-imap|onboarding/imap"
    "15-onboarding-google-calendar|onboarding/google-calendar"
    "16-onboarding-outlook-calendar|onboarding/outlook-calendar"
    "17-onboarding-notifications|onboarding/notifications"
    "18-onboarding-battery|onboarding/battery"
    "19-onboarding-cold-sync|onboarding/cold-sync"
    "20-today|today"
    "21-persons|persons"
    "22-persons-unassigned|persons/unassigned"
    "23-person-detail-kim-youngkyung|persons/qa-person-kim-youngkyung"
    "24-raw-event-detail-kim-youngkyung|persons/qa-person-kim-youngkyung/events/qa-raw-img-8262"
    "25-person-detail-jihoon|persons/qa-person-jihoon-kang"
    "26-commitments|commitments"
    "27-commitment-detail|commitments/qa-cmt-youngkyung-schedule"
    "28-commitment-edit|commitments/qa-cmt-youngkyung-schedule/edit"
    "29-commitment-create-supersede|commitments/new?supersedeOf=qa-cmt-old-email-import"
    "30-settings|settings"
    "31-settings-privacy|settings/privacy"
    "32-settings-consents|settings/privacy/consents"
    "33-settings-processing-pause|settings/privacy/pause"
    "34-settings-processing-status|settings/processing-status"
    "35-settings-delete-account|settings/privacy/delete-account"
    "36-settings-activity-log|settings/privacy/activity-log"
    "37-settings-sources|settings/sources"
    "38-settings-source-connect|settings/sources/connect"
    "39-settings-contacts-source|settings/sources/contacts"
    "40-source-detail-gmail|settings/sources/gmail"
    "41-source-detail-naver-imap|settings/sources/naver_imap"
    "42-source-detail-meeting|settings/sources/meeting"
    "43-source-detail-message-screenshot|settings/sources/message_screenshot"
  )

  for entry in "${routes[@]}"; do
    local name="${entry%%|*}"
    local route="${entry#*|}"
    open_route "$name" "$route" 2
  done
}

capture_upload_surfaces() {
  open_route "44-upload-entry-persons" "persons" 2
  adb_device shell input tap 992 2024
  capture_current "45-upload-sheet" "tap:floating-upload-button" 1

  if tap_text "메신저 스크린샷"; then
    sleep 2
    dump_ui
    screenshot "46-upload-message-screenshot-picker"
    record_capture "46-upload-message-screenshot-picker" "tap:메신저 스크린샷" "ok"
  else
    screenshot "46-upload-message-screenshot-picker"
    record_capture "46-upload-message-screenshot-picker" "tap:메신저 스크린샷" "missing-control"
  fi

  close_external_pickers
  open_route "47-upload-entry-persons-again" "persons" 2
  adb_device shell input tap 992 2024
  sleep 1
  dump_ui
  if tap_text "회의 녹음"; then
    sleep 2
    dump_ui
    screenshot "48-upload-meeting-audio-picker"
    record_capture "48-upload-meeting-audio-picker" "tap:회의 녹음" "ok"
  else
    screenshot "48-upload-meeting-audio-picker"
    record_capture "48-upload-meeting-audio-picker" "tap:회의 녹음" "missing-control"
  fi
}

capture_meeting_review() {
  close_external_pickers
  adb_device shell am broadcast \
    -a com.becalm.android.DEBUG_SEED_MEETING_SPEAKER_JOURNEY \
    -n com.becalm.android/.debug.DebugPersonRenderingSeedReceiver >/dev/null
  sleep 2
  open_route "49-meeting-speaker-review-persons" "persons" 2
  if tap_text "매칭 확인"; then
    capture_current "50-meeting-speaker-review-queue" "tap:매칭 확인" 1
  else
    screenshot "50-meeting-speaker-review-queue"
    record_capture "50-meeting-speaker-review-queue" "tap:매칭 확인" "missing-control"
  fi
  if tap_text "다른 사람"; then
    capture_current "51-meeting-speaker-select-person" "tap:다른 사람" 1
  else
    screenshot "51-meeting-speaker-select-person"
    record_capture "51-meeting-speaker-select-person" "tap:다른 사람" "missing-control"
  fi
}

write_report() {
  local total
  total="$(tail -n +2 "$MANIFEST" | wc -l | tr -d ' ')"
  {
    echo "# BeCalm Full App Emulator QA"
    echo
    echo "- Device: \`$DEVICE\`"
    echo "- Package: \`com.becalm.android\`"
    echo "- Screens captured: \`$total\`"
    echo "- Screenshot directory: \`$SCREENSHOT_DIR\`"
    echo "- Manifest: \`$MANIFEST\`"
    echo
    echo "## Coverage"
    echo
    echo "- Auth/public: launcher auth gate, terms, login secure screen."
    echo "- Onboarding compatibility routes: setup, PIPA, recording folder, call log, contacts, sources, provider consent, mail/calendar connectors, notifications, battery, cold sync."
    echo "- Main: today, persons, unassigned, person detail, raw event detail, commitments."
    echo "- Commitment sheets: detail, edit, supersede create."
    echo "- Settings: root, privacy, consent withdrawal, processing pause/status, delete account, activity log, source list/connect/detail."
    echo "- Upload: floating upload sheet, message screenshot picker, meeting audio picker."
    echo "- Person matching: meeting speaker review queue and existing-person selector."
    echo
    echo "## Notes"
    echo
    echo "- Login is protected by \`FLAG_SECURE\`; the XML manifest remains the reliable evidence for that screen when PNG capture is blank."
    echo "- External picker rows focus Android system picker/media packages, so package names may differ from \`com.becalm.android\`."
  } > "$REPORT"
}

adb_device wait-for-device
capture_auth_gate
"$ROOT_DIR/qa/emulator/scripts/seed_emulator_qa.sh" >/dev/null
capture_routes
capture_upload_surfaces
capture_meeting_review
write_report

cat <<EOF
Full app emulator QA screenshots captured on $DEVICE.

Screenshots:
  $SCREENSHOT_DIR

Report:
  $REPORT

Manifest:
  $MANIFEST
EOF
