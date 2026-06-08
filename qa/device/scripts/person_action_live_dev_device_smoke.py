#!/usr/bin/env python3
"""Live dev proof for Railway person actions reaching Android Room and UI.

This smoke creates a temporary dev Supabase Auth user from Railway dev
variables, writes a synthetic manual memory through the deployed dev API, waits
for a real `/v1/person_action_items?surface=person` row, injects that temporary
session into the debug app, refreshes Android through `PersonActionRepository`,
and verifies the People screen renders the action-first row.

The report intentionally contains only redacted metadata. Tokens, passwords,
service-role keys, and raw emails are never written to disk or stdout.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import random
import re
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


SCRIPT = Path(__file__).resolve()
ANDROID_ROOT = SCRIPT.parents[3]
WORKSPACE_ROOT = ANDROID_ROOT.parent
DEFAULT_BACKEND_ROOT = WORKSPACE_ROOT / "becalm-backend"
DEFAULT_ADB = "/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEFAULT_DEVICE = "R5CT83SMP4P"
DEFAULT_PACKAGE = "com.becalm.android"
DEFAULT_API_BASE = "https://dev-dev-7309.up.railway.app"
ACTION_TITLE = "Dev live action follow-up proof"
PERSON_NAME = "Dev Live Action Proof"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--confirm-live-dev", action="store_true")
    parser.add_argument("--confirm-clear-data", action="store_true")
    parser.add_argument("--environment", default="dev")
    parser.add_argument("--service", default="dev")
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--backend-root", type=Path, default=DEFAULT_BACKEND_ROOT)
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--device", default=DEFAULT_DEVICE)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument(
        "--apk",
        type=Path,
        default=ANDROID_ROOT / "android/app/build/outputs/apk/debug/app-debug.apk",
    )
    parser.add_argument("--report-root", type=Path, default=ANDROID_ROOT / "qa/device/reports")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--skip-clear-data", action="store_true")
    parser.add_argument("--poll-seconds", type=float, default=120.0)
    parser.add_argument("--poll-interval-seconds", type=float, default=2.0)
    args = parser.parse_args()

    if not args.confirm_live_dev:
        return emit_blocked("missing:--confirm-live-dev")
    if not args.confirm_clear_data and not args.skip_clear_data:
        return emit_blocked("missing:--confirm-clear-data")
    if not args.skip_install and not args.apk.is_file():
        return emit_blocked(f"missing:apk:{args.apk}")

    report_dir = args.report_root / f"person-action-live-dev-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S')}"
    report_dir.mkdir(parents=True, exist_ok=True)

    smoke = LivePersonActionSmoke(args=args, report_dir=report_dir)
    result = smoke.run()
    write_json(report_dir / "summary.json", result)
    write_env_summary(report_dir / "summary.env", result)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result.get("result") == "passed" else 1


def emit_blocked(reason: str) -> int:
    print(json.dumps({"result": "blocked", "reason": reason}, sort_keys=True))
    return 2


class LivePersonActionSmoke:
    def __init__(self, *, args: argparse.Namespace, report_dir: Path) -> None:
        self.args = args
        self.report_dir = report_dir
        self.user_id = ""
        self.access_token = ""
        self.refresh_token = ""
        self.email = ""
        self.password = ""
        self.supabase_url = ""
        self.service_role = ""
        self.anon_key = ""

    def run(self) -> dict[str, Any]:
        now = dt.datetime.now(dt.timezone.utc)
        summary: dict[str, Any] = {
            "result": "failed",
            "dev_api_base": normalize_url(self.args.api_base),
            "report": str(self.report_dir),
            "checks": {},
            "issues": [],
        }
        try:
            self.load_railway_auth(summary)
            self.assert_device()
            if not self.args.skip_install:
                self.install_apk(summary)
            self.create_temp_user(now, summary)
            self.write_manual_memory(now, summary)
            self.wait_for_backend_action(summary)
            self.refresh_android(summary)
            self.verify_people_ui(summary)
            checks = summary["checks"]
            passed = all(bool(value) for value in checks.values()) and summary.get("fatal_anr_oom_count") == 0
            summary["result"] = "passed" if passed else "failed"
            if not passed:
                summary["issues"].append("checks:not_all_passed")
        except SmokeBlocked as exc:
            summary["result"] = "blocked"
            summary["issues"].append(exc.reason)
        except SmokeFailed as exc:
            summary["result"] = "failed"
            summary["issues"].append(exc.reason)
        finally:
            self.cleanup_temp_user(summary)
        return summary

    def load_railway_auth(self, summary: dict[str, Any]) -> None:
        values = load_railway_variables(
            backend_root=self.args.backend_root,
            environment=self.args.environment,
            service=self.args.service,
        )
        self.supabase_url = normalize_url(values.get("SUPABASE_AUTH_URL") or values.get("SUPABASE_URL") or "")
        self.service_role = str(values.get("SUPABASE_AUTH_SERVICE_ROLE_KEY") or values.get("SUPABASE_SERVICE_ROLE_KEY") or "").strip()
        self.anon_key = str(values.get("SUPABASE_AUTH_ANON_KEY") or values.get("SUPABASE_ANON_KEY") or "").strip()
        if not self.supabase_url or not self.service_role or not self.anon_key:
            raise SmokeBlocked("railway:missing_supabase_auth_values")
        summary["dev_supabase_host"] = urllib.parse.urlparse(self.supabase_url).netloc

    def assert_device(self) -> None:
        result = self.adb(["devices"], include_serial=False)
        if result.returncode != 0 or f"{self.args.device}\tdevice" not in result.stdout:
            raise SmokeBlocked(f"adb:device_not_attached:{self.args.device}")

    def install_apk(self, summary: dict[str, Any]) -> None:
        result = self.adb(["install", "-r", str(self.args.apk)], timeout=90)
        summary["checks"]["apk_installed"] = result.returncode == 0
        if result.returncode != 0:
            raise SmokeBlocked("adb:apk_install_failed")

    def create_temp_user(self, now: dt.datetime, summary: dict[str, Any]) -> None:
        stamp = now.strftime("%Y%m%d-%H%M%S")
        suffix = "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))
        self.email = f"becalm-live-action-{stamp}-{suffix}@example.invalid"
        self.password = "Tmp!" + "".join(random.choice(string.ascii_letters + string.digits) for _ in range(30))
        admin_headers = {"apikey": self.service_role, "Authorization": f"Bearer {self.service_role}"}
        status, body, _ = request_json(
            "POST",
            f"{self.supabase_url}/auth/v1/admin/users",
            headers=admin_headers,
            body={
                "email": self.email,
                "password": self.password,
                "email_confirm": True,
                "user_metadata": {"qa": "person_action_live_dev"},
            },
        )
        if status not in {200, 201}:
            raise SmokeBlocked(f"auth:create_user_http_{status}")
        self.user_id = str(body.get("id") or body.get("user", {}).get("id") or "")
        if not self.user_id:
            raise SmokeBlocked("auth:create_user_missing_id")
        summary["user_hash"] = stable_hash(self.user_id)

        login_status, login_body, _ = request_json(
            "POST",
            f"{self.supabase_url}/auth/v1/token?grant_type=password",
            headers={"apikey": self.anon_key, "Authorization": f"Bearer {self.anon_key}"},
            body={"email": self.email, "password": self.password},
        )
        if login_status != 200:
            raise SmokeBlocked(f"auth:login_http_{login_status}")
        self.access_token = str(login_body.get("access_token") or "")
        self.refresh_token = str(login_body.get("refresh_token") or "")
        if not self.access_token:
            raise SmokeBlocked("auth:login_missing_access_token")
        summary["session_expires_in_seconds"] = int(login_body.get("expires_in") or 3600)

    def write_manual_memory(self, now: dt.datetime, summary: dict[str, Any]) -> None:
        health_status, health_body, _ = request_json("GET", f"{normalize_url(self.args.api_base)}/health")
        summary["health_status"] = health_status
        summary["checks"]["health_ok"] = health_status == 200 and health_body.get("status") == "ok"
        due_at = (now + dt.timedelta(hours=22)).isoformat().replace("+00:00", "Z")
        client_memory_id = "android-live-person-action-" + now.strftime("%Y%m%d-%H%M%S")
        status, _, _ = request_json(
            "POST",
            f"{normalize_url(self.args.api_base)}/v1/manual_memories",
            headers={"Authorization": f"Bearer {self.access_token}"},
            body={
                "client_memory_id": client_memory_id,
                "person_display_name": PERSON_NAME,
                "origin_channel": "meeting",
                "memory_kind": "my_action",
                "title": ACTION_TITLE,
                "occurred_at": now.isoformat().replace("+00:00", "Z"),
                "due_at": due_at,
            },
        )
        summary["manual_memory_status"] = status
        summary["checks"]["manual_memory_created"] = status in {200, 201}
        if status not in {200, 201}:
            raise SmokeFailed(f"manual_memory:http_{status}")

    def wait_for_backend_action(self, summary: dict[str, Any]) -> None:
        deadline = time.time() + self.args.poll_seconds
        last: dict[str, Any] = {}
        action: dict[str, Any] | None = None
        while time.time() < deadline:
            status, body, _ = request_json(
                "GET",
                f"{normalize_url(self.args.api_base)}/v1/person_action_items?surface=person&limit=10",
                headers={"Authorization": f"Bearer {self.access_token}"},
                timeout=20,
            )
            rows = body.get("data") if isinstance(body, dict) else None
            last = {
                "status": status,
                "count": len(rows) if isinstance(rows, list) else 0,
                "recompute_state": body.get("recompute_state") if isinstance(body, dict) else None,
            }
            if status == 200 and isinstance(rows, list) and rows:
                action = rows[0]
                break
            time.sleep(self.args.poll_interval_seconds)
        summary["backend_feed_last"] = last
        summary["checks"]["backend_action_visible"] = bool(action)
        if not action:
            raise SmokeFailed("backend_action:not_visible_after_poll")
        title = str(action.get("title") or "")
        surfaces = action.get("surfaces") if isinstance(action.get("surfaces"), list) else []
        summary["action_hash"] = stable_hash(str(action.get("id") or ""))
        summary["checks"]["backend_action_title_contains_source_title"] = ACTION_TITLE in title or "follow-up proof" in title
        summary["checks"]["backend_action_surface_person"] = "person" in surfaces

    def refresh_android(self, summary: dict[str, Any]) -> None:
        self.adb(["logcat", "-c"], timeout=15)
        if not self.args.skip_clear_data:
            clear = self.adb(["shell", "pm", "clear", self.args.package], timeout=30)
            if clear.returncode != 0:
                raise SmokeBlocked("adb:pm_clear_failed")
        expires_at_ms = int(time.time() * 1000) + max(300, int(summary.get("session_expires_in_seconds", 3600)) - 60) * 1000
        broadcast = self.adb(
            [
                "shell",
                "am",
                "broadcast",
                "-n",
                f"{self.args.package}/.debug.DebugPersonRenderingSeedReceiver",
                "-a",
                "com.becalm.android.DEBUG_REFRESH_PERSON_ACTIONS_E2E",
                "--es",
                "user_id",
                self.user_id,
                "--es",
                "access_token",
                self.access_token,
                "--es",
                "refresh_token",
                self.refresh_token,
                "--es",
                "email",
                self.email,
                "--el",
                "expires_at_epoch_ms",
                str(expires_at_ms),
                "--es",
                "surface",
                "person",
            ],
            timeout=60,
        )
        summary["checks"]["broadcast_completed"] = broadcast.returncode == 0
        if broadcast.returncode != 0:
            raise SmokeFailed("android:broadcast_failed")
        time.sleep(10)
        logs = self.adb(["logcat", "-d", "-v", "time"], timeout=30).stdout
        redacted_log = "\n".join(
            line
            for line in logs.splitlines()
            if "Debug person action E2E refresh" in line
            or "FATAL EXCEPTION" in line
            or "ANR in com.becalm.android" in line
            or "OutOfMemoryError" in line
        )
        (self.report_dir / "logcat-redacted.txt").write_text(redacted_log, encoding="utf-8")
        summary["checks"]["android_refresh_logged_success"] = (
            "Debug person action E2E refresh success" in logs
            and re.search(r"cachedActions=[1-9]", logs) is not None
        )
        summary["fatal_anr_oom_count"] = fatal_anr_oom_count(logs)

    def verify_people_ui(self, summary: dict[str, Any]) -> None:
        self.adb(
            [
                "shell",
                "am",
                "start",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                "becalm://persons",
                self.args.package,
            ],
            timeout=30,
        )
        time.sleep(8)
        focus_dump = self.adb(["shell", "dumpsys", "window"], timeout=30).stdout
        summary["checks"]["app_focused"] = self.args.package in focus_dump
        self.adb(["shell", "uiautomator", "dump", "/sdcard/becalm-live-person-action.xml"], timeout=30)
        xml = self.adb(["exec-out", "cat", "/sdcard/becalm-live-person-action.xml"], timeout=30).stdout
        (self.report_dir / "window.xml").write_text(xml, encoding="utf-8")
        summary["checks"]["headline_present"] = "지금 챙길 사람" in xml
        summary["checks"]["next_action_copy_present"] = "다음 행동" in xml
        summary["checks"]["today_section_present"] = "오늘 안에" in xml
        summary["checks"]["action_title_present"] = ACTION_TITLE in xml
        summary["checks"]["person_name_present"] = PERSON_NAME in xml
        redacted_log = (self.report_dir / "logcat-redacted.txt").read_text(encoding="utf-8")
        summary["checks"]["secret_free_report"] = (
            self.access_token not in xml
            and self.refresh_token not in xml
            and self.access_token not in redacted_log
            and self.refresh_token not in redacted_log
        )

    def cleanup_temp_user(self, summary: dict[str, Any]) -> None:
        if not self.user_id or not self.supabase_url or not self.service_role:
            summary["temp_auth_user_deleted"] = False
            return
        status, _, _ = request_json(
            "DELETE",
            f"{self.supabase_url}/auth/v1/admin/users/{urllib.parse.quote(self.user_id)}",
            headers={"apikey": self.service_role, "Authorization": f"Bearer {self.service_role}"},
        )
        summary["temp_auth_user_deleted"] = status in {200, 204}

    def adb(self, args: list[str], *, include_serial: bool = True, timeout: int = 30) -> subprocess.CompletedProcess[str]:
        command = [self.args.adb]
        if include_serial:
            command.extend(["-s", self.args.device])
        command.extend(args)
        return subprocess.run(command, text=True, capture_output=True, timeout=timeout, check=False)


class SmokeBlocked(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class SmokeFailed(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def load_railway_variables(*, backend_root: Path, environment: str, service: str) -> dict[str, str]:
    raw = subprocess.check_output(
        ["railway", "variable", "list", "--environment", environment, "--service", service, "--json"],
        cwd=backend_root,
        text=True,
        stderr=subprocess.PIPE,
    )
    values = json.loads(raw)
    if not isinstance(values, dict):
        raise SmokeBlocked("railway:variables_invalid")
    return {str(key): str(value) for key, value in values.items()}


def request_json(
    method: str,
    url: str,
    *,
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
    timeout: int = 30,
) -> tuple[int, dict[str, Any], dict[str, str]]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw else {}), dict(response.headers)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"error": "non_json_error_body"}
        return exc.code, parsed, dict(exc.headers)
    except urllib.error.URLError as exc:
        raise SmokeFailed(f"http:request_failed:{type(exc.reason).__name__}") from exc


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")


def write_env_summary(path: Path, payload: dict[str, Any]) -> None:
    checks = payload.get("checks") if isinstance(payload.get("checks"), dict) else {}
    rows: dict[str, Any] = {
        "person_action_live_dev_device_smoke_result": payload.get("result", ""),
        "dev_api_base": payload.get("dev_api_base", ""),
        "dev_supabase_host": payload.get("dev_supabase_host", ""),
        "health_status": payload.get("health_status", ""),
        "manual_memory_status": payload.get("manual_memory_status", ""),
        "backend_feed_status": nested_get(payload, "backend_feed_last.status"),
        "backend_feed_count": nested_get(payload, "backend_feed_last.count"),
        "backend_recompute_state": nested_get(payload, "backend_feed_last.recompute_state"),
        "fatal_anr_oom_count": payload.get("fatal_anr_oom_count", ""),
        "temp_auth_user_deleted": payload.get("temp_auth_user_deleted", False),
        "issues": ",".join(str(issue) for issue in payload.get("issues", []) if isinstance(issue, str)),
    }
    for key in sorted(checks):
        rows[key] = checks[key]
    path.write_text("\n".join(f"{key}={env_value(value)}" for key, value in rows.items()) + "\n", encoding="utf-8")


def nested_get(payload: dict[str, Any], dotted_path: str) -> Any:
    current: Any = payload
    for part in dotted_path.split("."):
        if not isinstance(current, dict):
            return ""
        current = current.get(part)
    return "" if current is None else current


def env_value(value: Any) -> str:
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def normalize_url(value: str) -> str:
    return value.strip().rstrip("/")


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def fatal_anr_oom_count(logs: str) -> int:
    markers = ("FATAL EXCEPTION", "ANR in com.becalm.android", "OutOfMemoryError")
    return sum(1 for marker in markers if marker in logs)


if __name__ == "__main__":
    raise SystemExit(main())
