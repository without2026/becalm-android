#!/usr/bin/env python3
"""Fetch small mail samples into git-ignored QA input folders.

Credentials are read from environment variables only. Naver/Daum use IMAP app
passwords; Gmail uses an OAuth access token and the Gmail API. The script writes
message metadata and plain text bodies to qa/emulator/user_inputs/fetched_mail/.
"""

from __future__ import annotations

import argparse
import base64
import email
import html
import imaplib
import json
import os
import re
import ssl
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from email.header import decode_header
from email.message import Message
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[3]
OUT_DIR = ROOT / "qa" / "emulator" / "user_inputs" / "fetched_mail"
GMAIL_API_ROOT = "https://gmail.googleapis.com/gmail/v1/users/me"


@dataclass
class MailSample:
    provider: str
    folder: str
    uid: str
    message_id: str | None
    subject: str
    from_addr: str
    to_addrs: list[str]
    cc_addrs: list[str]
    date: str | None
    body_text: str


def decode_mime(value: str | None) -> str:
    if not value:
        return ""
    parts: list[str] = []
    for raw, enc in decode_header(value):
        if isinstance(raw, bytes):
            parts.append(raw.decode(enc or "utf-8", errors="replace"))
        else:
            parts.append(raw)
    return "".join(parts)


def parse_addresses(value: str | None) -> list[str]:
    return [decode_mime(addr).strip() for addr in email.utils.getaddresses([value or ""]) if addr[1]]


def html_to_text(value: str) -> str:
    text = re.sub(r"(?is)<(script|style).*?>.*?</\\1>", " ", value)
    text = re.sub(r"(?i)<br\\s*/?>", "\n", text)
    text = re.sub(r"(?i)</p\\s*>", "\n", text)
    text = re.sub(r"(?s)<.*?>", " ", text)
    return html.unescape(text)


def normalize_body(value: str) -> str:
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in value.splitlines()]
    compact = "\n".join(line for line in lines if line)
    return compact[:12000]


def body_from_message(msg: Message) -> str:
    plain_parts: list[str] = []
    html_parts: list[str] = []
    if msg.is_multipart():
        for part in msg.walk():
            disposition = (part.get("Content-Disposition") or "").lower()
            if "attachment" in disposition:
                continue
            content_type = part.get_content_type()
            payload = part.get_payload(decode=True)
            if not payload:
                continue
            charset = part.get_content_charset() or "utf-8"
            decoded = payload.decode(charset, errors="replace")
            if content_type == "text/plain":
                plain_parts.append(decoded)
            elif content_type == "text/html":
                html_parts.append(html_to_text(decoded))
    else:
        payload = msg.get_payload(decode=True)
        if payload:
            charset = msg.get_content_charset() or "utf-8"
            decoded = payload.decode(charset, errors="replace")
            if msg.get_content_type() == "text/html":
                html_parts.append(html_to_text(decoded))
            else:
                plain_parts.append(decoded)
    return normalize_body("\n".join(plain_parts or html_parts))


def select_folder(conn: imaplib.IMAP4_SSL, preferred: str) -> str:
    status, _ = conn.select(preferred, readonly=True)
    if status == "OK":
        return preferred
    status, boxes = conn.list()
    if status != "OK":
        raise RuntimeError("could not list IMAP folders")
    wanted = preferred.lower()
    for box in boxes or []:
        decoded = box.decode("utf-8", errors="replace")
        folder = decoded.split(' "/" ')[-1].strip('"')
        if folder.lower() == wanted:
            status, _ = conn.select(folder, readonly=True)
            if status == "OK":
                return folder
    raise RuntimeError(f"could not select folder: {preferred}")


def fetch_samples(
    *,
    provider: str,
    host: str,
    username: str,
    password: str,
    folders: Iterable[str],
    limit: int,
) -> list[MailSample]:
    context = ssl.create_default_context()
    samples: list[MailSample] = []
    with imaplib.IMAP4_SSL(host, 993, ssl_context=context) as conn:
        conn.login(username, password)
        for folder in folders:
            selected = select_folder(conn, folder)
            status, data = conn.uid("search", None, "ALL")
            if status != "OK" or not data or not data[0]:
                continue
            uids = data[0].split()[-limit:]
            for uid_raw in reversed(uids):
                uid = uid_raw.decode("ascii", errors="replace")
                status, msg_data = conn.uid("fetch", uid, "(RFC822)")
                if status != "OK":
                    continue
                raw_msg = next((item[1] for item in msg_data if isinstance(item, tuple)), None)
                if not raw_msg:
                    continue
                msg = email.message_from_bytes(raw_msg)
                date_tuple = email.utils.parsedate_to_datetime(msg.get("Date")) if msg.get("Date") else None
                if date_tuple and date_tuple.tzinfo is None:
                    date_tuple = date_tuple.replace(tzinfo=timezone.utc)
                samples.append(
                    MailSample(
                        provider=provider,
                        folder=selected,
                        uid=uid,
                        message_id=msg.get("Message-ID"),
                        subject=decode_mime(msg.get("Subject")),
                        from_addr=", ".join(parse_addresses(msg.get("From"))),
                        to_addrs=parse_addresses(msg.get("To")),
                        cc_addrs=parse_addresses(msg.get("Cc")),
                        date=date_tuple.isoformat() if date_tuple else None,
                        body_text=body_from_message(msg),
                    )
                )
    return samples


def gmail_api_get(path: str, access_token: str, params: dict[str, str]) -> dict:
    query = urllib.parse.urlencode(params)
    request = urllib.request.Request(
        f"{GMAIL_API_ROOT}/{path}?{query}",
        headers={"Authorization": f"Bearer {access_token}"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def gmail_header(message: dict, name: str) -> str | None:
    for header in message.get("payload", {}).get("headers", []):
        if header.get("name", "").lower() == name.lower():
            return header.get("value")
    return None


def decode_gmail_body_data(value: str) -> str:
    padded = value + "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8", errors="replace")


def gmail_body_from_payload(payload: dict) -> str:
    body_data = payload.get("body", {}).get("data")
    if body_data and payload.get("mimeType") in {"text/plain", "text/html"}:
        decoded = decode_gmail_body_data(body_data)
        return html_to_text(decoded) if payload.get("mimeType") == "text/html" else normalize_body(decoded)

    plain_parts: list[str] = []
    html_parts: list[str] = []
    stack = list(payload.get("parts", []))
    while stack:
        part = stack.pop(0)
        stack.extend(part.get("parts", []))
        data = part.get("body", {}).get("data")
        if not data:
            continue
        decoded = decode_gmail_body_data(data)
        if part.get("mimeType") == "text/plain":
            plain_parts.append(decoded)
        elif part.get("mimeType") == "text/html":
            html_parts.append(html_to_text(decoded))
    return normalize_body("\n".join(plain_parts or html_parts))


def fetch_gmail_samples(*, access_token: str, folders: Iterable[str], limit: int) -> list[MailSample]:
    samples: list[MailSample] = []
    per_folder_limit = max(1, limit)
    for folder in folders:
        query = folder
        payload = gmail_api_get(
            "messages",
            access_token,
            {"q": query, "maxResults": str(per_folder_limit)},
        )
        for item in payload.get("messages", []):
            message_id = item.get("id")
            if not message_id:
                continue
            message = gmail_api_get(f"messages/{message_id}", access_token, {"format": "full"})
            internal_date = message.get("internalDate")
            date = None
            if internal_date:
                date = datetime.fromtimestamp(int(internal_date) / 1000, timezone.utc).isoformat()
            samples.append(
                MailSample(
                    provider="gmail",
                    folder=folder,
                    uid=message_id,
                    message_id=gmail_header(message, "Message-ID"),
                    subject=decode_mime(gmail_header(message, "Subject")),
                    from_addr=", ".join(parse_addresses(gmail_header(message, "From"))),
                    to_addrs=parse_addresses(gmail_header(message, "To")),
                    cc_addrs=parse_addresses(gmail_header(message, "Cc")),
                    date=date,
                    body_text=gmail_body_from_payload(message.get("payload", {})),
                )
            )
    return samples[:limit]


def write_jsonl(provider: str, samples: list[MailSample]) -> Path:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = OUT_DIR / f"{timestamp}_{provider}.jsonl"
    with path.open("w", encoding="utf-8") as f:
        for sample in samples:
            f.write(json.dumps(asdict(sample), ensure_ascii=False) + "\n")
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", required=True, choices=["naver_imap", "gmail"])
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--folders", nargs="+", default=["INBOX"])
    args = parser.parse_args()

    if args.provider == "gmail":
        access_token = os.environ["BECALM_QA_GMAIL_ACCESS_TOKEN"]
        samples = fetch_gmail_samples(
            access_token=access_token,
            folders=args.folders,
            limit=args.limit,
        )
        path = write_jsonl(args.provider, samples)
        print(json.dumps({"provider": args.provider, "count": len(samples), "path": str(path)}, ensure_ascii=False))
        return

    if args.provider == "naver_imap":
        host = "imap.naver.com"
        username = os.environ["BECALM_QA_NAVER_EMAIL"]
        password = os.environ["BECALM_QA_NAVER_APP_PASSWORD"]

    samples = fetch_samples(
        provider=args.provider,
        host=host,
        username=username,
        password=password,
        folders=args.folders,
        limit=args.limit,
    )
    path = write_jsonl(args.provider, samples)
    print(json.dumps({"provider": args.provider, "count": len(samples), "path": str(path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
