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
CONFIRM_STAGING_LOGIN=false
VERIFY_OAUTH_START=true
WAIT_FOR_USER_OAUTH_CONSENT=false
OAUTH_CONSENT_TIMEOUT_SECONDS=180
VERIFY_BACKEND_SYNC_AFTER_CONSENT=false
BACKEND_ROOT="../becalm-backend"
BACKEND_ENVIRONMENT="staging"
BACKEND_SERVICE="dev"
BACKEND_SYNC_TIMEOUT_SECONDS=300
BACKEND_SYNC_POLL_INTERVAL_SECONDS=5
INSTALL_APK=true

usage() {
  cat <<'USAGE'
Usage:
  qa/device/scripts/staging_auth_oauth_device_smoke.sh --confirm-clear-data --confirm-staging-login [options]

Options:
  --adb PATH                 adb executable. Defaults to Windows adb.exe when available.
  --device SERIAL            target device serial. Default: R5CT83SMP4P
  --apk PATH                 debug APK path. Default: android/app/build/outputs/apk/debug/app-debug.apk
  --package NAME             Android package. Default: com.becalm.android
  --report-root PATH         Report directory. Default: qa/device/reports
  --email-env ENV            env var containing staging email. Default: BECALM_STAGING_EMAIL
  --password-env ENV         env var containing staging password. Default: BECALM_STAGING_PASSWORD
  --skip-install             do not install APK before running
  --skip-oauth-start         stop after source state recovery/detail verification
  --wait-for-user-oauth-consent
                             after opening Google OAuth, wait for the user to
                             select an account and approve scopes on device
  --oauth-consent-timeout-seconds N
                             max wait for user OAuth consent. Default: 180
  --verify-backend-sync-after-consent
                             after user consent, run the backend Gmail sync
                             verifier for provider=google, capability=mail
  --backend-root PATH        backend repo path. Default: ../becalm-backend
  --backend-environment NAME Railway environment for verifier. Default: staging
  --backend-service NAME     Railway service for verifier. Default: dev
  --backend-sync-timeout-seconds N
                             backend sync poll timeout. Default: 300
  --confirm-clear-data       Required. Runs "pm clear" for the package.
  --confirm-staging-login    Required. Uses staging tester credentials from env.
  -h, --help                 Show this help.

This smoke proves the live staging Android auth boundary on a Samsung device:
fresh local app data, real Supabase Auth login, OAuth completion deep-link
return, backend source state recovery, and OAuth browser start.

The script never prints credential values. Reports contain redacted logcat,
non-secret UI dumps from BeCalm screens, counts, and status booleans only. It
stops at the Google account chooser because account selection and scope consent
are user-controlled. With --wait-for-user-oauth-consent it waits after opening
the chooser and verifies that the callback returns to BeCalm and Gmail is no
longer disconnected. With --verify-backend-sync-after-consent it also runs the
backend live sync verifier after the callback returns.
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
    --wait-for-user-oauth-consent) WAIT_FOR_USER_OAUTH_CONSENT=true; shift ;;
    --oauth-consent-timeout-seconds) OAUTH_CONSENT_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --verify-backend-sync-after-consent) VERIFY_BACKEND_SYNC_AFTER_CONSENT=true; shift ;;
    --backend-root) BACKEND_ROOT="$2"; shift 2 ;;
    --backend-environment) BACKEND_ENVIRONMENT="$2"; shift 2 ;;
    --backend-service) BACKEND_SERVICE="$2"; shift 2 ;;
    --backend-sync-timeout-seconds) BACKEND_SYNC_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --confirm-clear-data) CONFIRM_CLEAR_DATA=true; shift ;;
    --confirm-staging-login) CONFIRM_STAGING_LOGIN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$CONFIRM_CLEAR_DATA" != "true" ]]; then
  echo "Refusing to run without --confirm-clear-data." >&2
  echo "This test deletes local app data for ${PACKAGE_NAME}." >&2
  exit 2
fi
if [[ "$CONFIRM_STAGING_LOGIN" != "true" ]]; then
  echo "Refusing to run without --confirm-staging-login." >&2
  echo "This test signs in with the staging tester account from env." >&2
  exit 2
fi
if [[ -z "${!EMAIL_ENV:-}" ]]; then
  echo "Missing staging email env: ${EMAIL_ENV}" >&2
  exit 2
fi
if [[ -z "${!PASSWORD_ENV:-}" ]]; then
  echo "Missing staging password env: ${PASSWORD_ENV}" >&2
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
export BECALM_QA_WAIT_FOR_USER_OAUTH_CONSENT="$WAIT_FOR_USER_OAUTH_CONSENT"
export BECALM_QA_OAUTH_CONSENT_TIMEOUT_SECONDS="$OAUTH_CONSENT_TIMEOUT_SECONDS"
export BECALM_QA_VERIFY_BACKEND_SYNC_AFTER_CONSENT="$VERIFY_BACKEND_SYNC_AFTER_CONSENT"
export BECALM_QA_BACKEND_ROOT="$BACKEND_ROOT"
export BECALM_QA_BACKEND_ENVIRONMENT="$BACKEND_ENVIRONMENT"
export BECALM_QA_BACKEND_SERVICE="$BACKEND_SERVICE"
export BECALM_QA_BACKEND_SYNC_TIMEOUT_SECONDS="$BACKEND_SYNC_TIMEOUT_SECONDS"
export BECALM_QA_BACKEND_SYNC_POLL_INTERVAL_SECONDS="$BACKEND_SYNC_POLL_INTERVAL_SECONDS"

python3 <<'PY'
from __future__ import annotations

import os
import re
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
wait_for_user_oauth_consent = os.environ["BECALM_QA_WAIT_FOR_USER_OAUTH_CONSENT"] == "true"
oauth_consent_timeout_seconds = float(os.environ["BECALM_QA_OAUTH_CONSENT_TIMEOUT_SECONDS"])
verify_backend_sync_after_consent = os.environ["BECALM_QA_VERIFY_BACKEND_SYNC_AFTER_CONSENT"] == "true"
backend_root = Path(os.environ["BECALM_QA_BACKEND_ROOT"]).expanduser()
backend_environment = os.environ["BECALM_QA_BACKEND_ENVIRONMENT"]
backend_service = os.environ["BECALM_QA_BACKEND_SERVICE"]
backend_sync_timeout_seconds = float(os.environ["BECALM_QA_BACKEND_SYNC_TIMEOUT_SECONDS"])
backend_sync_poll_interval_seconds = float(os.environ["BECALM_QA_BACKEND_SYNC_POLL_INTERVAL_SECONDS"])

adb_prefix = [adb_bin]
if device:
    adb_prefix += ["-s", device]


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
    last_root: ET.Element | None = None
    while time.monotonic() < deadline:
        last_root = dump_ui()
        if has_text(last_root, expected):
            return last_root
        time.sleep(1.0)
    return last_root


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
    # adb shell input treats spaces specially; staging credentials should not
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
        if has_text(root, "Gmail") and has_text(root, "연결 상태"):
            return root, last_foreground_package
        time.sleep(2.0)
    return None, last_foreground_package


def confirm_gmail_connected_after_consent() -> bool:
    root = dump_ui("ui-after-user-oauth-callback.xml")
    if has_text(root, "Gmail") and not has_text(root, "연결 안 됨"):
        if tap_text("Gmail"):
            time.sleep(2.0)
            root = dump_ui("ui-gmail-after-user-oauth-consent.xml")
    elif not has_text(root, "연결 상태"):
        open_deeplink("becalm://settings/sources/gmail")
        root = wait_for_text("연결 상태", timeout_seconds=8)
        if root is None:
            root = dump_ui("ui-gmail-after-user-oauth-consent.xml")
        else:
            dump_ui("ui-gmail-after-user-oauth-consent.xml")

    return (
        has_text(root, "연결 상태")
        and not has_text(root, "연결 안 됨")
        and not has_text(root, "먼저 연결이 필요합니다.")
    )


def verify_backend_gmail_sync_after_consent() -> bool:
    runner = backend_root / "scripts/run_with_staging_user_jwt.py"
    verifier = backend_root / "scripts/verify_staging_oauth_source_sync.py"
    if not runner.is_file():
        write_text("backend-gmail-sync-after-consent-redacted.txt", f"missing runner: {runner}\n")
        return False
    if not verifier.is_file():
        write_text("backend-gmail-sync-after-consent-redacted.txt", f"missing verifier: {verifier}\n")
        return False
    command = ["python3", "scripts/run_with_staging_user_jwt.py", "--environment", backend_environment, "--service", backend_service, "--", "python3", "scripts/verify_staging_oauth_source_sync.py", "--environment", backend_environment, "--service", backend_service, "--jwt-env", "BECALM_STAGING_JWT", "--provider", "google", "--capability", "mail", "--timeout-seconds", str(int(backend_sync_timeout_seconds)), "--poll-interval-seconds", str(int(backend_sync_poll_interval_seconds))]
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
            "argv=" + " ".join(command[:2] + ["...", command[-7], command[-6], command[-5], command[-4]]),
            f"returncode={completed.returncode}",
            "stdout:",
            completed.stdout or "",
            "stderr:",
            completed.stderr or "",
        ],
    )
    write_text("backend-gmail-sync-after-consent-redacted.txt", redact(output))
    return completed.returncode == 0


issues: list[str] = []
stay_awake_previous = ""
stay_awake_restored = False
proofs: dict[str, str | int | bool] = {
    "fresh_local_data": False,
    "login_succeeded": False,
    "oauth_completion_routed_to_sources": False,
    "calendar_connection_recovered": False,
    "calendar_detail_connected": False,
    "oauth_start_accounts_google": False,
    "oauth_start_account_chooser_requires_user_consent": verify_oauth_start,
    "oauth_callback_returned_to_app": False,
    "gmail_connection_confirmed_after_consent": False,
    "backend_gmail_sync_succeeded": False,
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

root = wait_for_text("시작 설정", timeout_seconds=20)
if root is None:
    root = dump_ui("ui-after-login-redacted.xml")
if has_text(root, "시작 설정") or has_text(root, "데이터 출처") or has_text(root, "오늘"):
    proofs["login_succeeded"] = True
    dump_ui("ui-after-login.xml")
else:
    issues.append("login:did_not_reach_authenticated_screen")

if not issues:
    open_deeplink("becalm://oauth-complete?result=success&provider=google_calendar&family=calendar")
    root = wait_for_text("데이터 출처", timeout_seconds=12)
    if root is None:
        root = dump_ui("ui-sources-redacted.xml")
    if has_text(root, "데이터 출처"):
        proofs["oauth_completion_routed_to_sources"] = True
        dump_ui("ui-sources.xml")
    else:
        issues.append("oauth_completion:sources_not_visible")

if not issues:
    calendar_list_root = find_text_with_swipes("구글 캘린더")
    if calendar_list_root is not None:
        root = dump_ui("ui-sources-calendar-visible.xml")
        if has_text(root, "최근 확인함"):
            proofs["calendar_connection_recovered"] = True
        if not tap_text("구글 캘린더"):
            issues.append("calendar:row_tap_failed")
    else:
        issues.append("calendar:row_not_visible")

if not issues:
    root = wait_for_text("연결 상태", timeout_seconds=8)
    if root is None:
        root = dump_ui("ui-google-calendar-detail-redacted.xml")
    dump_ui("ui-google-calendar-detail.xml")
    if has_text(root, "최근 확인함") and has_text(root, "연결되었습니다."):
        proofs["calendar_detail_connected"] = True
    else:
        issues.append("calendar_detail:connected_state_not_visible")

if verify_oauth_start and not issues:
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    gmail_list_root = find_text_with_swipes("Gmail")
    if gmail_list_root is None:
        issues.append("gmail:row_not_visible")
    elif not tap_text("Gmail"):
        issues.append("gmail:row_tap_failed")
    else:
        wait_for_text("다시 연결", timeout_seconds=8)
        if not tap_text("다시 연결"):
            issues.append("gmail:reconnect_not_found")
        else:
            wait_for_text("동의하고 연결", timeout_seconds=8)
            if not tap_text("동의하고 연결"):
                issues.append("gmail:consent_connect_not_found")
            else:
                time.sleep(10)
                browser_root = dump_ui()
                browser_text = "\n".join(
                    (node_text(node) + "\n" + node_desc(node))
                    for node in browser_root.iter("node")
                )
                browser_packages = {
                    node.attrib.get("package", "")
                    for node in browser_root.iter("node")
                    if node.attrib.get("package")
                }
                if "accounts.google.com" in browser_text and package not in browser_packages:
                    proofs["oauth_start_accounts_google"] = True
                elif "accounts.google.com" in browser_text and any(pkg != package for pkg in browser_packages):
                    proofs["oauth_start_accounts_google"] = True
                else:
                    issues.append("oauth_start:accounts_google_not_visible")
                # Do not continue through account selection or consent.
                if wait_for_user_oauth_consent and not issues:
                    print(
                        "Waiting for user OAuth consent on device "
                        f"for up to {int(oauth_consent_timeout_seconds)}s...",
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
                        if confirm_gmail_connected_after_consent():
                            proofs["gmail_connection_confirmed_after_consent"] = True
                        else:
                            issues.append("oauth_callback:gmail_not_connected_after_consent")
                        if verify_backend_sync_after_consent and not issues:
                            if verify_backend_gmail_sync_after_consent():
                                proofs["backend_gmail_sync_succeeded"] = True
                            else:
                                issues.append("backend_sync:gmail_sync_verifier_failed")
                else:
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
    if key in {"oauth_callback_returned_to_app", "gmail_connection_confirmed_after_consent"}:
        if wait_for_user_oauth_consent and value is False:
            issues.append(f"proof_missing:{key}")
        continue
    if key == "backend_gmail_sync_succeeded":
        if verify_backend_sync_after_consent and value is False:
            issues.append(f"proof_missing:{key}")
        continue
    if value is False and (key != "oauth_start_accounts_google" or verify_oauth_start):
        issues.append(f"proof_missing:{key}")

summary_lines = [
    "staging_auth_oauth_device_smoke_result=" + ("passed" if not issues else "failed"),
    f"package={package}",
    f"device={device}",
    f"fresh_local_data={str(proofs['fresh_local_data']).lower()}",
    f"login_succeeded={str(proofs['login_succeeded']).lower()}",
    "oauth_completion_routed_to_sources="
    + str(proofs["oauth_completion_routed_to_sources"]).lower(),
    f"calendar_connection_recovered={str(proofs['calendar_connection_recovered']).lower()}",
    f"calendar_detail_connected={str(proofs['calendar_detail_connected']).lower()}",
    f"oauth_start_accounts_google={str(proofs['oauth_start_accounts_google']).lower()}",
    "oauth_start_account_chooser_requires_user_consent="
    + str(proofs["oauth_start_account_chooser_requires_user_consent"]).lower(),
    f"waited_for_user_oauth_consent={str(wait_for_user_oauth_consent).lower()}",
    f"oauth_callback_returned_to_app={str(proofs['oauth_callback_returned_to_app']).lower()}",
    "gmail_connection_confirmed_after_consent="
    + str(proofs["gmail_connection_confirmed_after_consent"]).lower(),
    f"backend_sync_after_consent_requested={str(verify_backend_sync_after_consent).lower()}",
    f"backend_gmail_sync_succeeded={str(proofs['backend_gmail_sync_succeeded']).lower()}",
    f"last_foreground_package_at_timeout={last_foreground_package_at_timeout if 'last_foreground_package_at_timeout' in globals() else ''}",
    f"stay_awake_restored={str(stay_awake_restored).lower()}",
    f"fatal_anr_oom_count={fatal_count}",
    f"secret_log_count={secret_log_count}",
    "issues=" + ",".join(issues),
    f"report_dir={report_dir}",
]
write_text("summary.env", "\n".join(summary_lines) + "\n")

if issues:
    print("Staging auth/OAuth device smoke failed. Report: " + str(report_dir), file=sys.stderr)
    print("Issues: " + ",".join(issues), file=sys.stderr)
    raise SystemExit(1)

print("Staging auth/OAuth device smoke passed. Report: " + str(report_dir))
PY
