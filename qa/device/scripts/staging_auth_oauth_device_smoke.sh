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
EMAIL_ENV="BECALM_STAGING_EMAIL"
PASSWORD_ENV="BECALM_STAGING_PASSWORD"
CONFIRM_CLEAR_DATA=false
CONFIRM_TEST_LOGIN=false
VERIFY_OAUTH_START=true
REQUIRE_CALENDAR_RECOVERY=true
OAUTH_SOURCE="gmail"
WAIT_FOR_USER_OAUTH_CONSENT=false
OAUTH_CONSENT_TIMEOUT_SECONDS=180
VERIFY_BACKEND_SYNC_AFTER_CONSENT=false
VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC=false
ANDROID_MIRROR_TIMEOUT_SECONDS=180
ANDROID_MIRROR_POLL_SECONDS=5
BACKEND_ROOT="../becalm-backend"
BACKEND_ENVIRONMENT="dev"
BACKEND_SERVICE="dev"
BACKEND_SYNC_TIMEOUT_SECONDS=300
BACKEND_SYNC_POLL_INTERVAL_SECONDS=5
INSTALL_APK=true

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/staging_auth_oauth_device_smoke.sh --confirm-clear-data --confirm-dev-login [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --email-env ENV            env var containing test email. Default: BECALM_STAGING_EMAIL
  --password-env ENV         env var containing test password. Default: BECALM_STAGING_PASSWORD
  --skip-install             do not install APK before running
  --skip-oauth-start         stop after source state recovery/detail verification
  --skip-calendar-recovery-check
                             do not require the fake calendar completion path
                             before verifying OAuth browser start
  --oauth-source SOURCE      Google OAuth source to open and verify. Supported:
                             gmail, google-calendar. Default: gmail
  --wait-for-user-oauth-consent
                             after opening Google OAuth, wait for the user to
                             select an account and approve scopes on device
  --oauth-consent-timeout-seconds N
                             max wait for user OAuth consent. Default: 180
  --verify-backend-sync-after-consent
                             after user consent, run the backend sync verifier
                             for the selected Google OAuth source
  --verify-android-mirror-after-backend-sync
                             after backend sync succeeds, clear local mirror rows,
                             refresh Android Room, and verify People UI readiness
  --android-mirror-timeout-seconds N
                             Android mirror/UI poll timeout. Default: 180
  --android-mirror-poll-seconds N
                             Android mirror/UI poll interval. Default: 5
  --backend-root PATH        backend repo path. Default: ../becalm-backend
  --backend-environment NAME Railway environment for verifier. Default: dev
  --backend-service NAME     Railway service for verifier. Default: dev
  --backend-sync-timeout-seconds N
                             backend sync poll timeout. Default: 300
  --confirm-clear-data       Required. Runs "pm clear" for the package.
  --confirm-dev-login        Required. Uses the configured dev/test account env.
  --confirm-staging-login    Compatibility alias for --confirm-dev-login.
  -h, --help                 Show this help.

This smoke proves the live dev Android auth boundary on a Samsung device:
fresh local app data, real Supabase Auth login, OAuth completion deep-link
return, backend source state recovery, and OAuth browser start.

The script never prints credential values. Reports contain redacted logcat,
non-secret UI dumps from BeCalm screens, counts, and status booleans only. It
stops at the Google account chooser because account selection and scope consent
are user-controlled. With --wait-for-user-oauth-consent it waits after opening
the chooser and verifies that the callback returns to BeCalm and the selected
source is no longer disconnected. With --verify-backend-sync-after-consent it
also runs the backend live sync verifier after the callback returns.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb) ADB_BIN="$2"; shift 2 ;;
    --device) DEVICE_SERIAL="$2"; shift 2 ;;
    --apk) APK_PATH="$2"; shift 2 ;;
    --package) PACKAGE_NAME="$2"; shift 2 ;;
    --report-root) REPORT_ROOT="$2"; shift 2 ;;
    --email-env) EMAIL_ENV="$2"; shift 2 ;;
    --password-env) PASSWORD_ENV="$2"; shift 2 ;;
    --skip-install) INSTALL_APK=false; shift ;;
    --skip-oauth-start) VERIFY_OAUTH_START=false; shift ;;
    --skip-calendar-recovery-check) REQUIRE_CALENDAR_RECOVERY=false; shift ;;
    --oauth-source) OAUTH_SOURCE="$2"; shift 2 ;;
    --wait-for-user-oauth-consent) WAIT_FOR_USER_OAUTH_CONSENT=true; shift ;;
    --oauth-consent-timeout-seconds) OAUTH_CONSENT_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --verify-backend-sync-after-consent) VERIFY_BACKEND_SYNC_AFTER_CONSENT=true; shift ;;
    --verify-android-mirror-after-backend-sync) VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC=true; shift ;;
    --android-mirror-timeout-seconds) ANDROID_MIRROR_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --android-mirror-poll-seconds) ANDROID_MIRROR_POLL_SECONDS="$2"; shift 2 ;;
    --backend-root) BACKEND_ROOT="$2"; shift 2 ;;
    --backend-environment) BACKEND_ENVIRONMENT="$2"; shift 2 ;;
    --backend-service) BACKEND_SERVICE="$2"; shift 2 ;;
    --backend-sync-timeout-seconds) BACKEND_SYNC_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --confirm-clear-data) CONFIRM_CLEAR_DATA=true; shift ;;
    --confirm-dev-login|--confirm-staging-login) CONFIRM_TEST_LOGIN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$CONFIRM_CLEAR_DATA" != "true" ]]; then
  echo "Refusing to run without --confirm-clear-data." >&2
  echo "This test deletes local app data for ${PACKAGE_NAME}." >&2
  exit 2
fi
if [[ "$CONFIRM_TEST_LOGIN" != "true" ]]; then
  echo "Refusing to run without --confirm-dev-login." >&2
  echo "This test signs in with the configured dev/test account from env." >&2
  exit 2
fi
if [[ -z "${!EMAIL_ENV:-}" ]]; then
  echo "Missing test email env: ${EMAIL_ENV}" >&2
  exit 2
fi
if [[ -z "${!PASSWORD_ENV:-}" ]]; then
  echo "Missing test password env: ${PASSWORD_ENV}" >&2
  exit 2
fi
if [[ "$INSTALL_APK" == "true" && ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Build it first, for example: ./gradlew :app:assembleDebug" >&2
  exit 2
fi
if [[ "$VERIFY_BACKEND_SYNC_AFTER_CONSENT" == "true" && "$WAIT_FOR_USER_OAUTH_CONSENT" != "true" ]]; then
  echo "--verify-backend-sync-after-consent requires --wait-for-user-oauth-consent." >&2
  exit 2
fi
if [[ "$VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC" == "true" && "$VERIFY_BACKEND_SYNC_AFTER_CONSENT" != "true" ]]; then
  echo "--verify-android-mirror-after-backend-sync requires --verify-backend-sync-after-consent." >&2
  exit 2
fi
case "$OAUTH_SOURCE" in
  gmail|google-calendar|google_calendar) ;;
  *)
    echo "Unsupported --oauth-source: ${OAUTH_SOURCE}" >&2
    echo "Supported values: gmail, google-calendar." >&2
    exit 2
    ;;
esac

timestamp="$(date +%Y%m%d-%H%M%S)"
report_dir="${REPORT_ROOT}/staging-auth-oauth-device-${timestamp}"
mkdir -p "$report_dir"

echo "Writing report to $report_dir"
"$ADB_BIN" devices -l > "${report_dir}/devices.txt"

export BECALM_QA_ADB="$ADB_BIN"
export BECALM_QA_DEVICE="$DEVICE_SERIAL"
export BECALM_QA_PACKAGE="$PACKAGE_NAME"
export BECALM_QA_APK="$APK_PATH"
export BECALM_QA_REPORT_DIR="$report_dir"
export BECALM_QA_EMAIL_ENV="$EMAIL_ENV"
export BECALM_QA_PASSWORD_ENV="$PASSWORD_ENV"
export BECALM_QA_INSTALL_APK="$INSTALL_APK"
export BECALM_QA_VERIFY_OAUTH_START="$VERIFY_OAUTH_START"
export BECALM_QA_REQUIRE_CALENDAR_RECOVERY="$REQUIRE_CALENDAR_RECOVERY"
export BECALM_QA_OAUTH_SOURCE="$OAUTH_SOURCE"
export BECALM_QA_WAIT_FOR_USER_OAUTH_CONSENT="$WAIT_FOR_USER_OAUTH_CONSENT"
export BECALM_QA_OAUTH_CONSENT_TIMEOUT_SECONDS="$OAUTH_CONSENT_TIMEOUT_SECONDS"
export BECALM_QA_VERIFY_BACKEND_SYNC_AFTER_CONSENT="$VERIFY_BACKEND_SYNC_AFTER_CONSENT"
export BECALM_QA_VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC="$VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC"
export BECALM_QA_ANDROID_MIRROR_TIMEOUT_SECONDS="$ANDROID_MIRROR_TIMEOUT_SECONDS"
export BECALM_QA_ANDROID_MIRROR_POLL_SECONDS="$ANDROID_MIRROR_POLL_SECONDS"
export BECALM_QA_BACKEND_ROOT="$BACKEND_ROOT"
export BECALM_QA_BACKEND_ENVIRONMENT="$BACKEND_ENVIRONMENT"
export BECALM_QA_BACKEND_SERVICE="$BACKEND_SERVICE"
export BECALM_QA_BACKEND_SYNC_TIMEOUT_SECONDS="$BACKEND_SYNC_TIMEOUT_SECONDS"
export BECALM_QA_BACKEND_SYNC_POLL_INTERVAL_SECONDS="$BACKEND_SYNC_POLL_INTERVAL_SECONDS"

python3 <<'PY'
from __future__ import annotations

import os
import re
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb_bin = os.environ["BECALM_QA_ADB"]
device = os.environ["BECALM_QA_DEVICE"]
package = os.environ["BECALM_QA_PACKAGE"]
apk_path = os.environ["BECALM_QA_APK"]
report_dir = Path(os.environ["BECALM_QA_REPORT_DIR"])
email_env = os.environ["BECALM_QA_EMAIL_ENV"]
password_env = os.environ["BECALM_QA_PASSWORD_ENV"]
email = os.environ[email_env]
password = os.environ[password_env]
install_apk = os.environ["BECALM_QA_INSTALL_APK"] == "true"
verify_oauth_start = os.environ["BECALM_QA_VERIFY_OAUTH_START"] == "true"
require_calendar_recovery = os.environ["BECALM_QA_REQUIRE_CALENDAR_RECOVERY"] == "true"
oauth_source = os.environ["BECALM_QA_OAUTH_SOURCE"]
wait_for_user_oauth_consent = os.environ["BECALM_QA_WAIT_FOR_USER_OAUTH_CONSENT"] == "true"
oauth_consent_timeout_seconds = float(os.environ["BECALM_QA_OAUTH_CONSENT_TIMEOUT_SECONDS"])
verify_backend_sync_after_consent = os.environ["BECALM_QA_VERIFY_BACKEND_SYNC_AFTER_CONSENT"] == "true"
verify_android_mirror_after_backend_sync = os.environ["BECALM_QA_VERIFY_ANDROID_MIRROR_AFTER_BACKEND_SYNC"] == "true"
android_mirror_timeout_seconds = float(os.environ["BECALM_QA_ANDROID_MIRROR_TIMEOUT_SECONDS"])
android_mirror_poll_seconds = float(os.environ["BECALM_QA_ANDROID_MIRROR_POLL_SECONDS"])
backend_root = Path(os.environ["BECALM_QA_BACKEND_ROOT"]).expanduser()
backend_environment = os.environ["BECALM_QA_BACKEND_ENVIRONMENT"]
backend_service = os.environ["BECALM_QA_BACKEND_SERVICE"]
backend_sync_timeout_seconds = float(os.environ["BECALM_QA_BACKEND_SYNC_TIMEOUT_SECONDS"])
backend_sync_poll_interval_seconds = float(os.environ["BECALM_QA_BACKEND_SYNC_POLL_INTERVAL_SECONDS"])

adb_prefix = [adb_bin]
if device:
    adb_prefix += ["-s", device]


def normalize_oauth_source(value: str) -> str:
    normalized = value.strip().lower().replace("-", "_")
    if normalized == "google_calendar":
        return "google_calendar"
    return "gmail"


oauth_source = normalize_oauth_source(oauth_source)
target_display_name = "구글 캘린더" if oauth_source == "google_calendar" else "Gmail"
target_backend_capability = "calendar" if oauth_source == "google_calendar" else "mail"
target_detail_uri = f"becalm://settings/sources/{oauth_source}"
target_report_slug = "google-calendar" if oauth_source == "google_calendar" else "gmail"


def run_adb(*args: str, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        adb_prefix + list(args),
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
    )


def write_text(path: str, text: str) -> None:
    (report_dir / path).write_text(text, encoding="utf-8")


def redact(text: str) -> str:
    redacted = text
    for secret in (email, password):
        if secret and len(secret) >= 4:
            redacted = redacted.replace(secret, "[redacted]")
    redacted = re.sub(
        r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
        "[email]",
        redacted,
    )
    return redacted


def parse_dump(raw: str) -> ET.Element:
    start = raw.find("<hierarchy")
    if start > 0:
        raw = raw[start:]
    end = raw.find("</hierarchy>")
    if end >= 0:
        raw = raw[: end + len("</hierarchy>")]
    return ET.fromstring(raw)


def dump_ui(path: str | None = None, *, redact_dump: bool = True) -> ET.Element:
    completed = run_adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=20)
    raw = completed.stdout or completed.stderr or ""
    root = parse_dump(raw)
    if path:
        write_text(path, redact(raw) if redact_dump else raw)
    return root


def bounds_center(bounds: str) -> tuple[int, int] | None:
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2


def tap_bounds(bounds: str) -> bool:
    center = bounds_center(bounds)
    if not center:
        return False
    run_adb("shell", "input", "tap", str(center[0]), str(center[1]))
    time.sleep(0.7)
    return True


def node_text(node: ET.Element) -> str:
    return (node.attrib.get("text") or "").strip()


def node_desc(node: ET.Element) -> str:
    return (node.attrib.get("content-desc") or "").strip()


def has_text(root: ET.Element, expected: str) -> bool:
    return any(node_text(node) == expected or node_desc(node) == expected for node in root.iter("node"))


def is_authenticated_screen(root: ET.Element) -> bool:
    return any(
        has_text(root, text)
        for text in ("시작 설정", "데이터 출처", "오늘", "인물", "출처 관리", "업무 자료 추가")
    )


def package_names(root: ET.Element) -> set[str]:
    return {
        node.attrib.get("package", "")
        for node in root.iter("node")
        if node.attrib.get("package")
    }


def tap_text(expected: str, *, contains: bool = False) -> bool:
    root = dump_ui()
    for node in root.iter("node"):
        values = (node_text(node), node_desc(node))
        if contains:
            matched = any(expected in value for value in values)
        else:
            matched = any(expected == value for value in values)
        if matched and tap_bounds(node.attrib.get("bounds", "")):
            return True
    return False


def wait_for_text(expected: str, *, timeout_seconds: float = 20.0) -> ET.Element | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        root = dump_ui()
        if has_text(root, expected):
            return root
        time.sleep(1.0)
    return None


def wait_for_authenticated_screen(*, timeout_seconds: float = 20.0) -> ET.Element | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        root = dump_ui()
        if is_authenticated_screen(root):
            return root
        time.sleep(1.0)
    return None


def find_text_with_swipes(expected: str, *, max_swipes: int = 8) -> ET.Element | None:
    for _ in range(max_swipes + 1):
        root = dump_ui()
        if has_text(root, expected):
            return root
        run_adb("shell", "input", "swipe", "540", "2200", "540", "800", "250")
        time.sleep(0.5)
    return None


def tap_first_edit_text(*, password_field: bool) -> bool:
    root = dump_ui()
    for node in root.iter("node"):
        if node.attrib.get("class") != "android.widget.EditText":
            continue
        is_password = node.attrib.get("password") == "true"
        if is_password == password_field:
            return tap_bounds(node.attrib.get("bounds", ""))
    return False


def input_text(value: str) -> None:
    # adb shell input treats spaces specially; test credentials should not
    # contain spaces, but keep the standard escape for completeness.
    escaped = value.replace(" ", "%s")
    run_adb("shell", "input", "text", escaped)
    time.sleep(0.5)


def launch_app() -> None:
    run_adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(5)


def open_deeplink(uri: str) -> None:
    shell_uri = uri.replace("&", r"\&")
    run_adb(
        "shell",
        "am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        shell_uri,
        package,
        timeout=20,
    )
    time.sleep(4)


def current_focus_package() -> str:
    completed = run_adb("shell", "dumpsys", "window", timeout=10)
    candidates = re.findall(
        r"(?:mCurrentFocus|mFocusedApp)=.*?\s([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+",
        completed.stdout or "",
    )
    for candidate in candidates:
        if candidate == package:
            return candidate
    return candidates[-1] if candidates else ""


def capture_google_oauth_start(path: str) -> bool:
    root = dump_ui(path)
    browser_text = "\n".join(
        (node_text(node) + "\n" + node_desc(node))
        for node in root.iter("node")
    )
    browser_packages = package_names(root)
    foreground = current_focus_package()
    if "accounts.google.com" in browser_text and package not in browser_packages:
        return True
    if "accounts.google.com" in browser_text and any(pkg != package for pkg in browser_packages):
        return True
    if foreground and foreground != package and "accounts.google.com" in browser_text:
        return True
    return False


def read_stay_awake_setting() -> str:
    completed = run_adb("shell", "settings", "get", "global", "stay_on_while_plugged_in", timeout=10)
    return (completed.stdout or "").strip()


def enable_stay_awake_for_consent() -> str:
    previous = read_stay_awake_setting()
    run_adb("shell", "settings", "put", "global", "stay_on_while_plugged_in", "7", timeout=10)
    return previous


def restore_stay_awake_after_consent(previous: str) -> None:
    value = previous if previous and previous != "null" else "0"
    run_adb("shell", "settings", "put", "global", "stay_on_while_plugged_in", value, timeout=10)


def wait_for_oauth_callback_to_app(timeout_seconds: float) -> tuple[ET.Element | None, str]:
    deadline = time.monotonic() + timeout_seconds
    last_foreground_package = ""
    while time.monotonic() < deadline:
        last_foreground_package = current_focus_package()
        if last_foreground_package != package:
            time.sleep(2.0)
            continue
        root = dump_ui()
        if has_text(root, "데이터 출처"):
            return root, last_foreground_package
        if has_text(root, target_display_name) and has_text(root, "연결 상태"):
            return root, last_foreground_package
        time.sleep(2.0)
    return None, last_foreground_package


def confirm_target_connected_after_consent() -> bool:
    root = dump_ui(f"ui-{target_report_slug}-after-user-oauth-callback.xml")
    if has_text(root, target_display_name) and not has_text(root, "연결 안 됨"):
        if tap_text(target_display_name):
            time.sleep(2.0)
            root = dump_ui(f"ui-{target_report_slug}-after-user-oauth-consent.xml")
    elif not has_text(root, "연결 상태"):
        open_deeplink(target_detail_uri)
        root = wait_for_text("연결 상태", timeout_seconds=8)
        if root is None:
            root = dump_ui(f"ui-{target_report_slug}-after-user-oauth-consent.xml")
        else:
            dump_ui(f"ui-{target_report_slug}-after-user-oauth-consent.xml")

    return (
        has_text(root, "연결 상태")
        and not has_text(root, "연결 안 됨")
        and not has_text(root, "먼저 연결이 필요합니다.")
    )


def target_detail_is_connected(root: ET.Element) -> bool:
    return (
        has_text(root, "연결 상태")
        and has_text(root, "연결되었습니다.")
        and not has_text(root, "연결 안 됨")
        and not has_text(root, "먼저 연결이 필요합니다.")
        and not has_text(root, "연결 실패")
        and not has_text(root, "오류")
        and not has_text(root, "다시 연결")
    )


def new_backend_sync_evidence() -> dict[str, str | int | bool]:
    return {
        "succeeded": False,
        "summary_json": "",
        "verifier_issues": "",
        "downstream_checked": False,
        "source_events_observed": False,
        "source_event_participants_observed": False,
        "calendar_events_observed": False,
        "person_action_feed_ready": False,
        "source_event_count": 0,
        "source_event_participant_count": 0,
        "calendar_event_count": 0,
        "calendar_matched_source_event_count": 0,
        "person_action_count": 0,
        "actions_with_evidence_refs": 0,
    }


def int_value(value: object) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0


def backend_sync_evidence_from_summary(summary_path: Path, *, succeeded: bool) -> dict[str, str | int | bool]:
    evidence = new_backend_sync_evidence()
    evidence["succeeded"] = succeeded
    evidence["summary_json"] = summary_path.name
    if not summary_path.is_file():
        return evidence
    try:
        parsed = json.loads(summary_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        evidence["verifier_issues"] = "summary_json_parse_failed"
        return evidence

    issues = parsed.get("issues") if isinstance(parsed, dict) else []
    if isinstance(issues, list):
        evidence["verifier_issues"] = ",".join(str(item) for item in issues)
    downstream = parsed.get("downstream") if isinstance(parsed, dict) else None
    if not isinstance(downstream, dict):
        return evidence
    evidence["downstream_checked"] = bool(downstream.get("checked"))

    source_events = downstream.get("source_events")
    if isinstance(source_events, dict):
        count = int_value(source_events.get("count"))
        evidence["source_event_count"] = count
        evidence["source_events_observed"] = count > 0

    calendar_events = downstream.get("calendar_events")
    if isinstance(calendar_events, dict):
        count = int_value(calendar_events.get("count"))
        evidence["calendar_event_count"] = count
        evidence["calendar_events_observed"] = count > 0
        evidence["calendar_matched_source_event_count"] = int_value(calendar_events.get("matched_source_event_count"))

    participants = downstream.get("source_event_participants")
    if isinstance(participants, dict):
        count = int_value(participants.get("count"))
        evidence["source_event_participant_count"] = count
        evidence["source_event_participants_observed"] = count > 0

    action_feed = downstream.get("person_action_feed")
    if isinstance(action_feed, dict):
        evidence["person_action_count"] = int_value(action_feed.get("count"))
        evidence["actions_with_evidence_refs"] = int_value(action_feed.get("actions_with_evidence_refs"))
        evidence["person_action_feed_ready"] = bool(action_feed.get("action_or_explicit_state"))
    return evidence


def new_android_mirror_evidence() -> dict[str, str | int | bool]:
    return {
        "requested": verify_android_mirror_after_backend_sync,
        "succeeded": False,
        "prepared": False,
        "source_mirror_observed": False,
        "source_participants_observed": False,
        "person_action_refresh_succeeded": False,
        "person_action_cache_ready": False,
        "people_ui_checked": False,
        "people_ui_ready": False,
        "raw_count": 0,
        "calendar_event_count": 0,
        "source_event_participant_count": 0,
        "commitment_count": 0,
        "commitment_participant_count": 0,
        "cached_action_count": 0,
        "issues": "",
    }


def bool_from_backend_evidence(evidence: dict[str, str | int | bool], key: str) -> bool:
    return bool(evidence.get(key))


def latest_debug_line(logs: str, marker: str) -> str:
    matches = [line for line in logs.splitlines() if marker in line]
    return matches[-1] if matches else ""


def parse_log_count(line: str, key: str) -> int:
    match = re.search(rf"{re.escape(key)}=([0-9]+)", line)
    return int_value(match.group(1) if match else None)


def run_debug_broadcast(action: str, *extras: str, timeout: float = 60.0) -> bool:
    args = [
        "shell",
        "am",
        "broadcast",
        "--receiver-foreground",
        "-a",
        action,
        "-n",
        f"{package}/.debug.DebugPersonRenderingSeedReceiver",
    ]
    args.extend(extras)
    return run_adb(*args, timeout=timeout).returncode == 0


def verify_android_mirror_after_backend(
    backend_evidence: dict[str, str | int | bool],
) -> dict[str, str | int | bool]:
    evidence = new_android_mirror_evidence()
    issues: list[str] = []
    run_adb("logcat", "-c", timeout=15)

    prepared = run_debug_broadcast(
        "com.becalm.android.DEBUG_PREPARE_STAGING_MIRROR_SMOKE",
        "--es",
        "source_type",
        oauth_source,
    )
    evidence["prepared"] = prepared
    if not prepared:
        evidence["issues"] = "android_mirror:prepare_broadcast_failed"
        return evidence

    deadline = time.monotonic() + android_mirror_timeout_seconds
    latest_report = ""
    while time.monotonic() <= deadline:
        run_debug_broadcast(
            "com.becalm.android.DEBUG_REPORT_STAGING_MIRROR_SMOKE",
            "--es",
            "source_type",
            oauth_source,
            timeout=30,
        )
        time.sleep(max(0.5, android_mirror_poll_seconds))
        logs = run_adb("logcat", "-d", "-v", "time", timeout=45).stdout
        latest_report = latest_debug_line(logs, "Debug staging mirror smoke report")
        if not latest_report:
            continue

        evidence["raw_count"] = parse_log_count(latest_report, "rawCount")
        evidence["calendar_event_count"] = parse_log_count(latest_report, "calendarEventCount")
        evidence["source_event_participant_count"] = parse_log_count(latest_report, "sourceParticipantCount")
        evidence["commitment_count"] = parse_log_count(latest_report, "commitmentCount")
        evidence["commitment_participant_count"] = parse_log_count(latest_report, "commitmentParticipantCount")

        if oauth_source == "google_calendar":
            evidence["source_mirror_observed"] = int_value(evidence["calendar_event_count"]) > 0
        else:
            evidence["source_mirror_observed"] = int_value(evidence["raw_count"]) > 0
        evidence["source_participants_observed"] = int_value(evidence["source_event_participant_count"]) > 0

        if oauth_source == "google_calendar":
            source_backend_observed = bool_from_backend_evidence(backend_evidence, "calendar_events_observed")
        else:
            source_backend_observed = bool_from_backend_evidence(backend_evidence, "source_events_observed")
        source_requirement_met = (
            not source_backend_observed
            or bool_from_backend_evidence(evidence, "source_mirror_observed")
        )
        participant_requirement_met = (
            not bool_from_backend_evidence(backend_evidence, "source_event_participants_observed")
            or bool_from_backend_evidence(evidence, "source_participants_observed")
        )
        if source_requirement_met and participant_requirement_met:
            break

    if oauth_source == "google_calendar":
        source_backend_observed = bool_from_backend_evidence(backend_evidence, "calendar_events_observed")
        missing_source_issue = "android_mirror:calendar_rows_missing"
    else:
        source_backend_observed = bool_from_backend_evidence(backend_evidence, "source_events_observed")
        missing_source_issue = "android_mirror:source_rows_missing"
    if source_backend_observed and not bool_from_backend_evidence(evidence, "source_mirror_observed"):
        issues.append(missing_source_issue)
    if bool_from_backend_evidence(backend_evidence, "source_event_participants_observed") and not bool_from_backend_evidence(evidence, "source_participants_observed"):
        issues.append("android_mirror:source_participants_missing")

    action_refresh = run_debug_broadcast(
        "com.becalm.android.DEBUG_REFRESH_PERSON_ACTIONS_E2E",
        "--es",
        "surface",
        "person",
        timeout=60,
    )
    if not action_refresh:
        issues.append("android_person_action:refresh_broadcast_failed")
    time.sleep(10)
    logs = run_adb("logcat", "-d", "-v", "time", timeout=45).stdout
    action_line = latest_debug_line(logs, "Debug person action E2E refresh")
    evidence["cached_action_count"] = parse_log_count(action_line, "cachedActions")
    evidence["person_action_refresh_succeeded"] = "Debug person action E2E refresh success" in action_line
    expected_action_count = int_value(backend_evidence.get("person_action_count"))
    evidence["person_action_cache_ready"] = (
        int_value(evidence["cached_action_count"]) > 0
        if expected_action_count > 0
        else bool_from_backend_evidence(evidence, "person_action_refresh_succeeded")
    )
    if expected_action_count > 0 and not bool_from_backend_evidence(evidence, "person_action_cache_ready"):
        issues.append("android_person_action:cache_missing")

    if int_value(evidence["cached_action_count"]) > 0:
        open_deeplink("becalm://persons")
        time.sleep(8)
        ui_root = dump_ui("ui-people-after-provider-mirror.xml")
        ui_text = "\n".join(node_text(node) + "\n" + node_desc(node) for node in ui_root.iter("node"))
        evidence["people_ui_checked"] = True
        evidence["people_ui_ready"] = "지금 챙길 사람" in ui_text and "다음 행동" in ui_text
        if not bool_from_backend_evidence(evidence, "people_ui_ready"):
            issues.append("android_ui:people_action_not_visible")

    redacted_lines = [
        line
        for line in logs.splitlines()
        if "Debug staging mirror smoke" in line
        or "Debug person action E2E refresh" in line
        or "FATAL EXCEPTION" in line
        or f"ANR in {package}" in line
        or "OutOfMemoryError" in line
    ]
    write_text("android-mirror-after-backend-redacted.txt", "\n".join(redacted_lines) + "\n")

    evidence["issues"] = ",".join(issues)
    evidence["succeeded"] = not issues
    return evidence


def verify_backend_target_sync_after_consent() -> dict[str, str | int | bool]:
    runner = backend_root / "scripts/run_with_staging_user_jwt.py"
    verifier = backend_root / "scripts/verify_staging_oauth_source_sync.py"
    evidence = new_backend_sync_evidence()
    if not runner.is_file():
        write_text(f"backend-{target_report_slug}-sync-after-consent-redacted.txt", f"missing runner: {runner}\n")
        evidence["verifier_issues"] = "missing_runner"
        return evidence
    if not verifier.is_file():
        write_text(f"backend-{target_report_slug}-sync-after-consent-redacted.txt", f"missing verifier: {verifier}\n")
        evidence["verifier_issues"] = "missing_verifier"
        return evidence
    backend_jwt_env = "BECALM_STAGING_JWT" if backend_environment == "staging" else "BECALM_DEV_JWT"
    summary_path = report_dir / f"backend-{target_report_slug}-sync-after-consent.json"
    command = [
        "python3",
        "scripts/run_with_staging_user_jwt.py",
        "--environment",
        backend_environment,
        "--service",
        backend_service,
        "--email-env",
        email_env,
        "--password-env",
        password_env,
        "--jwt-env",
        backend_jwt_env,
        "--",
        "python3",
        "scripts/verify_staging_oauth_source_sync.py",
        "--environment",
        backend_environment,
        "--service",
        backend_service,
        "--jwt-env",
        backend_jwt_env,
        "--provider",
        "google",
        "--capability",
        target_backend_capability,
        "--timeout-seconds",
        str(int(backend_sync_timeout_seconds)),
        "--poll-interval-seconds",
        str(int(backend_sync_poll_interval_seconds)),
        "--summary-json",
        str(summary_path.resolve()),
    ]
    completed = subprocess.run(
        command,
        cwd=str(backend_root),
        env=dict(os.environ),
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=backend_sync_timeout_seconds + 180,
    )
    output = "\n".join(
        [
            "argv=python3 scripts/run_with_staging_user_jwt.py ... "
            f"--provider google --capability {target_backend_capability} "
            f"--summary-json {summary_path.name}",
            f"returncode={completed.returncode}",
            "stdout:",
            completed.stdout or "",
            "stderr:",
            completed.stderr or "",
        ],
    )
    write_text(f"backend-{target_report_slug}-sync-after-consent-redacted.txt", redact(output))
    return backend_sync_evidence_from_summary(summary_path, succeeded=completed.returncode == 0)


issues: list[str] = []
stay_awake_previous = ""
stay_awake_restored = False
backend_sync_evidence = new_backend_sync_evidence()
android_mirror_evidence = new_android_mirror_evidence()
proofs: dict[str, str | int | bool] = {
    "fresh_local_data": False,
    "login_succeeded": False,
    "oauth_completion_routed_to_sources": False,
    "calendar_connection_recovered": False,
    "calendar_detail_connected": False,
    "oauth_start_accounts_google": False,
    "oauth_start_account_chooser_requires_user_consent": verify_oauth_start,
    "oauth_callback_returned_to_app": False,
    "target_connection_confirmed_after_consent": False,
    "target_already_connected_without_oauth": False,
    "backend_target_sync_succeeded": False,
}

write_text("device-state.txt", run_adb("get-state").stdout)
write_text("device-model.txt", run_adb("shell", "getprop", "ro.product.model").stdout)
write_text("android-release.txt", run_adb("shell", "getprop", "ro.build.version.release").stdout)
write_text("android-sdk.txt", run_adb("shell", "getprop", "ro.build.version.sdk").stdout)

if install_apk:
    write_text("install.txt", run_adb("install", "-r", apk_path, timeout=120).stdout)

run_adb("logcat", "-c")
write_text("pm-clear.txt", run_adb("shell", "pm", "clear", package).stdout)
proofs["fresh_local_data"] = True
run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
run_adb("shell", "wm", "dismiss-keyguard")
launch_app()
dump_ui("ui-after-launch.xml")

root = wait_for_text("로그인", timeout_seconds=5)
if root is None:
    root = dump_ui()
if has_text(root, "계속하기 전에 아래 내용을 읽고 동의해 주세요."):
    checkbox_tapped = False
    for node in root.iter("node"):
        if node.attrib.get("class") == "android.widget.CheckBox":
            checkbox_tapped = tap_bounds(node.attrib.get("bounds", ""))
            break
    if not checkbox_tapped:
        checkbox_tapped = tap_text("약관을 읽고 동의합니다", contains=True)
    if not checkbox_tapped:
        issues.append("terms:checkbox_not_found")
    if not tap_text("계속"):
        issues.append("terms:continue_not_found")
    root = wait_for_text("로그인", timeout_seconds=10)
    if root is None:
        root = dump_ui()

if not is_authenticated_screen(root):
    if not has_text(root, "이메일"):
        issues.append("login:email_field_not_visible")
    if not tap_first_edit_text(password_field=False):
        issues.append("login:email_edit_text_not_found")
    else:
        input_text(email)
    if not tap_first_edit_text(password_field=True):
        issues.append("login:password_edit_text_not_found")
    else:
        input_text(password)
    if not tap_text("로그인"):
        issues.append("login:button_not_found")

    root = wait_for_authenticated_screen(timeout_seconds=20)
    if root is None:
        root = dump_ui("ui-after-login-redacted.xml")
if is_authenticated_screen(root):
    proofs["login_succeeded"] = True
    dump_ui("ui-after-login.xml")
else:
    issues.append("login:did_not_reach_authenticated_screen")

if not issues:
    if wait_for_user_oauth_consent or oauth_source == "google_calendar":
        open_deeplink("becalm://settings/sources")
    else:
        open_deeplink("becalm://oauth-complete?result=success&provider=google_calendar&family=calendar")
    root = wait_for_text("데이터 출처", timeout_seconds=12)
    if root is None:
        root = dump_ui("ui-sources-redacted.xml")
    if has_text(root, "데이터 출처"):
        proofs["oauth_completion_routed_to_sources"] = True
        dump_ui("ui-sources.xml")
    else:
        issues.append("oauth_completion:sources_not_visible")

if require_calendar_recovery and not issues:
    calendar_list_root = find_text_with_swipes("구글 캘린더")
    if calendar_list_root is not None:
        root = dump_ui("ui-sources-calendar-visible.xml")
        if has_text(root, "최근 확인함"):
            proofs["calendar_connection_recovered"] = True
        if not tap_text("구글 캘린더"):
            issues.append("calendar:row_tap_failed")
    else:
        issues.append("calendar:row_not_visible")

if require_calendar_recovery and not issues:
    root = wait_for_text("연결 상태", timeout_seconds=8)
    if root is None:
        root = dump_ui("ui-google-calendar-detail-redacted.xml")
    root = dump_ui("ui-google-calendar-detail.xml")
    calendar_detail_deadline = time.monotonic() + 20.0
    while time.monotonic() < calendar_detail_deadline:
        if has_text(root, "최근 확인함") and has_text(root, "연결되었습니다."):
            break
        time.sleep(2.0)
        root = dump_ui("ui-google-calendar-detail.xml")
    if has_text(root, "최근 확인함") and has_text(root, "연결되었습니다."):
        proofs["calendar_connection_recovered"] = True
        proofs["calendar_detail_connected"] = True
    else:
        issues.append("calendar_detail:connected_state_not_visible")

if verify_oauth_start and not issues:
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    target_list_root = find_text_with_swipes(target_display_name)
    if target_list_root is None:
        open_deeplink(target_detail_uri)
        if wait_for_text("연결 상태", timeout_seconds=8) is None:
            dump_ui(f"ui-{target_report_slug}-detail-open-failed.xml")
            issues.append(f"{oauth_source}:detail_not_visible")
    elif not tap_text(target_display_name):
        open_deeplink(target_detail_uri)
        if wait_for_text("연결 상태", timeout_seconds=8) is None:
            dump_ui(f"ui-{target_report_slug}-detail-open-failed.xml")
            issues.append(f"{oauth_source}:row_tap_failed")
    else:
        wait_for_text("연결 상태", timeout_seconds=8)

    if not issues:
        target_detail_root = dump_ui(f"ui-{target_report_slug}-before-oauth-start.xml")
        if target_detail_is_connected(target_detail_root):
            proofs["target_already_connected_without_oauth"] = True
            proofs["target_connection_confirmed_after_consent"] = True
            if verify_backend_sync_after_consent:
                backend_sync_evidence = verify_backend_target_sync_after_consent()
                if backend_sync_evidence["succeeded"]:
                    proofs["backend_target_sync_succeeded"] = True
                    if verify_android_mirror_after_backend_sync:
                        android_mirror_evidence = verify_android_mirror_after_backend(backend_sync_evidence)
                        if not android_mirror_evidence["succeeded"]:
                            issues.append(f"android_mirror:{oauth_source}_verifier_failed")
                else:
                    issues.append(f"backend_sync:{oauth_source}_sync_verifier_failed")
        if proofs["target_already_connected_without_oauth"]:
            pass
        else:
            wait_for_text("다시 연결", timeout_seconds=8)
            if not tap_text("다시 연결"):
                wait_for_text("동의하고 연결", timeout_seconds=3)
                if not (tap_text("동의하고 연결") or tap_text("다시 시도")):
                    dump_ui(f"ui-{target_report_slug}-connect-cta-not-found.xml")
                    issues.append(f"{oauth_source}:connect_cta_not_found")
            else:
                time.sleep(4)
                if capture_google_oauth_start("ui-oauth-browser-after-reconnect.xml"):
                    proofs["oauth_start_accounts_google"] = True
            if not proofs["oauth_start_accounts_google"] and not issues:
                wait_for_text("동의하고 연결", timeout_seconds=3)
                if tap_text("동의하고 연결") or tap_text("다시 시도"):
                    time.sleep(10)
                    if capture_google_oauth_start("ui-oauth-browser-after-connect.xml"):
                        proofs["oauth_start_accounts_google"] = True
                    else:
                        issues.append("oauth_start:accounts_google_not_visible")
                else:
                    time.sleep(6)
                    if capture_google_oauth_start("ui-oauth-browser-after-connect.xml"):
                        proofs["oauth_start_accounts_google"] = True
                    else:
                        dump_ui(f"ui-{target_report_slug}-focused-connect-cta-not-found.xml")
                        issues.append(f"{oauth_source}:focused_connect_cta_not_found")

        if proofs["oauth_start_accounts_google"] and wait_for_user_oauth_consent and not issues:
            print(
                "Waiting for user OAuth consent on device "
                f"for {target_display_name} up to {int(oauth_consent_timeout_seconds)}s...",
                flush=True,
            )
            stay_awake_previous = enable_stay_awake_for_consent()
            try:
                callback_root, last_foreground_package_at_timeout = wait_for_oauth_callback_to_app(
                    oauth_consent_timeout_seconds,
                )
            finally:
                restore_stay_awake_after_consent(stay_awake_previous)
                stay_awake_restored = True
            if callback_root is None:
                issues.append("oauth_callback:user_consent_timeout")
            else:
                proofs["oauth_callback_returned_to_app"] = True
                if confirm_target_connected_after_consent():
                    proofs["target_connection_confirmed_after_consent"] = True
                else:
                    issues.append(f"oauth_callback:{oauth_source}_not_connected_after_consent")
                if verify_backend_sync_after_consent and not issues:
                    backend_sync_evidence = verify_backend_target_sync_after_consent()
                    if backend_sync_evidence["succeeded"]:
                        proofs["backend_target_sync_succeeded"] = True
                        if verify_android_mirror_after_backend_sync:
                            android_mirror_evidence = verify_android_mirror_after_backend(backend_sync_evidence)
                            if not android_mirror_evidence["succeeded"]:
                                issues.append(f"android_mirror:{oauth_source}_verifier_failed")
                    else:
                        issues.append(f"backend_sync:{oauth_source}_sync_verifier_failed")
        elif proofs["oauth_start_accounts_google"]:
            run_adb("shell", "input", "keyevent", "KEYCODE_BACK")

raw_logcat = run_adb("logcat", "-d", "-v", "time", timeout=60).stdout
secret_log_count = sum(raw_logcat.count(secret) for secret in (email, password) if secret)
write_text("logcat-redacted.txt", redact(raw_logcat))
fatal_pattern = re.compile(
    rf"FATAL EXCEPTION|ANR in {re.escape(package)}|OutOfMemoryError|"
    rf"Force finishing activity.*{re.escape(package)}|Process {re.escape(package)}.*has died",
    re.IGNORECASE,
)
fatal_count = len(fatal_pattern.findall(raw_logcat))
if secret_log_count:
    issues.append("privacy:credential_value_in_logcat")
if fatal_count:
    issues.append("device:fatal_anr_oom")

for key, value in proofs.items():
    if key == "oauth_start_account_chooser_requires_user_consent":
        continue
    if key == "target_already_connected_without_oauth":
        continue
    if proofs["target_already_connected_without_oauth"] and key in {
        "oauth_start_accounts_google",
        "oauth_callback_returned_to_app",
    }:
        continue
    if key in {"oauth_callback_returned_to_app", "target_connection_confirmed_after_consent"}:
        if wait_for_user_oauth_consent and value is False:
            issues.append(f"proof_missing:{key}")
        continue
    if key in {"calendar_connection_recovered", "calendar_detail_connected"} and not require_calendar_recovery:
        continue
    if key == "backend_target_sync_succeeded":
        if verify_backend_sync_after_consent and value is False:
            issues.append(f"proof_missing:{key}")
        continue
    if value is False and (key != "oauth_start_accounts_google" or verify_oauth_start):
        issues.append(f"proof_missing:{key}")

summary_lines = [
    "dev_auth_oauth_device_smoke_result=" + ("passed" if not issues else "failed"),
    "staging_auth_oauth_device_smoke_result=" + ("passed" if not issues else "failed"),
    f"package={package}",
    f"device={device}",
    f"fresh_local_data={str(proofs['fresh_local_data']).lower()}",
    f"login_succeeded={str(proofs['login_succeeded']).lower()}",
    "oauth_completion_routed_to_sources="
    + str(proofs["oauth_completion_routed_to_sources"]).lower(),
    f"calendar_connection_recovered={str(proofs['calendar_connection_recovered']).lower()}",
    f"calendar_detail_connected={str(proofs['calendar_detail_connected']).lower()}",
    f"calendar_recovery_required={str(require_calendar_recovery).lower()}",
    f"oauth_source={oauth_source}",
    f"oauth_source_display_name={target_display_name}",
    f"backend_sync_provider=google",
    f"backend_sync_capability={target_backend_capability}",
    f"oauth_start_accounts_google={str(proofs['oauth_start_accounts_google']).lower()}",
    "oauth_start_account_chooser_requires_user_consent="
    + str(proofs["oauth_start_account_chooser_requires_user_consent"]).lower(),
    f"waited_for_user_oauth_consent={str(wait_for_user_oauth_consent).lower()}",
    f"oauth_callback_returned_to_app={str(proofs['oauth_callback_returned_to_app']).lower()}",
    "target_connection_confirmed_after_consent="
    + str(proofs["target_connection_confirmed_after_consent"]).lower(),
    "target_already_connected_without_oauth="
    + str(proofs["target_already_connected_without_oauth"]).lower(),
    "gmail_connection_confirmed_after_consent="
    + str((oauth_source == "gmail" and proofs["target_connection_confirmed_after_consent"])).lower(),
    "google_calendar_connection_confirmed_after_consent="
    + str((oauth_source == "google_calendar" and proofs["target_connection_confirmed_after_consent"])).lower(),
    f"backend_sync_after_consent_requested={str(verify_backend_sync_after_consent).lower()}",
    f"backend_sync_jwt_env={'BECALM_STAGING_JWT' if backend_environment == 'staging' else 'BECALM_DEV_JWT'}",
    f"backend_target_sync_succeeded={str(proofs['backend_target_sync_succeeded']).lower()}",
    f"backend_gmail_sync_succeeded={str((oauth_source == 'gmail' and proofs['backend_target_sync_succeeded'])).lower()}",
    "backend_google_calendar_sync_succeeded="
    + str((oauth_source == "google_calendar" and proofs["backend_target_sync_succeeded"])).lower(),
    f"backend_sync_summary_json={backend_sync_evidence['summary_json']}",
    f"backend_sync_verifier_issues={backend_sync_evidence['verifier_issues']}",
    f"backend_downstream_evidence_checked={str(backend_sync_evidence['downstream_checked']).lower()}",
    f"backend_source_events_observed={str(backend_sync_evidence['source_events_observed']).lower()}",
    f"backend_calendar_events_observed={str(backend_sync_evidence['calendar_events_observed']).lower()}",
    f"backend_source_event_participants_observed={str(backend_sync_evidence['source_event_participants_observed']).lower()}",
    f"backend_person_action_feed_ready={str(backend_sync_evidence['person_action_feed_ready']).lower()}",
    f"backend_source_event_count={backend_sync_evidence['source_event_count']}",
    f"backend_calendar_event_count={backend_sync_evidence['calendar_event_count']}",
    "backend_calendar_matched_source_event_count="
    + str(backend_sync_evidence["calendar_matched_source_event_count"]),
    f"backend_source_event_participant_count={backend_sync_evidence['source_event_participant_count']}",
    f"backend_person_action_count={backend_sync_evidence['person_action_count']}",
    f"backend_actions_with_evidence_refs={backend_sync_evidence['actions_with_evidence_refs']}",
    f"android_mirror_after_backend_requested={str(verify_android_mirror_after_backend_sync).lower()}",
    f"android_mirror_after_backend_succeeded={str(android_mirror_evidence['succeeded']).lower()}",
    f"android_mirror_prepared={str(android_mirror_evidence['prepared']).lower()}",
    f"android_source_mirror_observed={str(android_mirror_evidence['source_mirror_observed']).lower()}",
    f"android_source_participants_observed={str(android_mirror_evidence['source_participants_observed']).lower()}",
    f"android_person_action_refresh_succeeded={str(android_mirror_evidence['person_action_refresh_succeeded']).lower()}",
    f"android_person_action_cache_ready={str(android_mirror_evidence['person_action_cache_ready']).lower()}",
    f"android_people_ui_checked={str(android_mirror_evidence['people_ui_checked']).lower()}",
    f"android_people_ui_ready={str(android_mirror_evidence['people_ui_ready']).lower()}",
    f"android_raw_count={android_mirror_evidence['raw_count']}",
    f"android_calendar_event_count={android_mirror_evidence['calendar_event_count']}",
    f"android_source_event_participant_count={android_mirror_evidence['source_event_participant_count']}",
    f"android_commitment_count={android_mirror_evidence['commitment_count']}",
    f"android_commitment_participant_count={android_mirror_evidence['commitment_participant_count']}",
    f"android_cached_action_count={android_mirror_evidence['cached_action_count']}",
    f"android_mirror_issues={android_mirror_evidence['issues']}",
    f"last_foreground_package_at_timeout={last_foreground_package_at_timeout if 'last_foreground_package_at_timeout' in globals() else ''}",
    f"stay_awake_restored={str(stay_awake_restored).lower()}",
    f"fatal_anr_oom_count={fatal_count}",
    f"secret_log_count={secret_log_count}",
    "issues=" + ",".join(issues),
    f"report_dir={report_dir}",
]
write_text("summary.env", "\n".join(summary_lines) + "\n")

if issues:
    print("Dev auth/OAuth device smoke failed. Report: " + str(report_dir), file=sys.stderr)
    print("Issues: " + ",".join(issues), file=sys.stderr)
    raise SystemExit(1)

print("Dev auth/OAuth device smoke passed. Report: " + str(report_dir))
PY
