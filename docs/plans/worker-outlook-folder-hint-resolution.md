# Worker / Outlook / folder-hint-resolution — Derive `raw_ingestion_events.folder` for Graph messages

**Branch**: `feat/worker/outlook/folder-hint-resolution` (placeholder)
**Status**: PLAN ONLY — scheduled for Wave 3 (ADAPT-EMAIL-001 follow-up)
**E2E Stage**: 1 — Outlook email ingestion (Graph delta → Room raw_ingestion_events)
**Severity**: P1 (blocks EMAIL-001 person_ref derivation accuracy for Outlook accounts)
**Type**: Gap (Graph `messages/delta` response does not carry a well-known folder name, and the current single-endpoint architecture cannot distinguish Inbox / SentItems per message)

---

## 1. Finding

Wave 1 (`db-email-schema.md`) landed `raw_ingestion_events.folder TEXT` with an
application-level invariant: email workers MUST populate this column with
`INBOX` / `SENT` for every email row they insert. Gmail was wired in the same
wave via `labelIds` inspection. Outlook was not wired because the Graph API
surface this worker currently uses (`/me/messages/delta`) returns only the
message's `parentFolderId` (a per-mailbox GUID), not the well-known folder name
needed to resolve the hint — and the plumbing required to resolve GUIDs to names
exceeds Wave 1's surgical scope.

---

## 2. Spec Contract

- `.spec/email-pipeline.spec.yml:15-18` EMAIL-001 — `folder ∈ {INBOX, SENT}`
  required for email source types, used to derive `person_ref` direction.
- `.spec/contracts/data-model.yml:§ raw_ingestion_events.folder` — column is
  nullable at the schema level but NOT NULL at the app-level for email sources.

---

## 3. Code Reality

- `android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt`
  `toEntity()` leaves `folder = null` with a comment pointing at this plan doc.
- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClient.kt`
  `GraphMessage` does NOT expose `parentFolderId`.
- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClientImpl.kt`
  uses a single `INITIAL_MESSAGES_URL = /me/messages/delta` endpoint that spans
  the entire mailbox.

---

## 4. Gap

| Aspect | Spec | Code | Δ |
|---|---|---|---|
| Inbox rows tagged `folder = "INBOX"` | MUST | always null | wire |
| Sent rows tagged `folder = "SENT"` | MUST | always null | wire |

---

## 5. Proposed Fix (sketch)

Two viable architectures, to be chosen at implementation time:

### Option A: Split the delta endpoint (recommended)

Replace the single `/me/messages/delta` call with two per-folder endpoints:

1. `/me/mailFolders('inbox')/messages/delta` — all rows tagged `folder = "INBOX"`.
2. `/me/mailFolders('sentitems')/messages/delta` — all rows tagged `folder = "SENT"`.

Persist separate delta cursors per folder in `SyncCursorStore` to keep the two
syncs independent. On initial migration, seed both cursors with `null` and let
each do a one-time full sync. Idempotency survives because the Graph message
`id` is globally unique per mailbox and the Room `(user_id, client_event_id)`
constraint rejects duplicate inserts.

### Option B: Resolve `parentFolderId` per message

- Add `parentFolderId` to `GraphMessage` (and `$select`).
- On `MsGraphClient` construction, call `/me/mailFolders` once and cache a
  GUID → well-known-name map.
- In `OutlookMailWorker.toEntity`, look up `parentFolderId` in the cache:
  `"inbox"` → `FOLDER_INBOX`, `"sentitems"` → `FOLDER_SENT`, else null.

Option A is simpler and avoids a stateful cache, at the cost of two network
round-trips per sync instead of one. Option B keeps a single endpoint but
requires the extra folder-list call and the cache.

---

## 6. Acceptance Criteria

- `grep -n "folder = FOLDER_INBOX\|folder = FOLDER_SENT" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt` ≥ 2.
- `grep -n "folder = null" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt` = 0.
- Unit test: `OutlookMailWorkerTest — Inbox delta rows insert with folder="INBOX"`.
- Unit test: `OutlookMailWorkerTest — SentItems delta rows insert with folder="SENT"`.
- Integration (android): after running one sync pass on a seeded Graph mock,
  every email row in `raw_ingestion_events` has `folder IN ('INBOX','SENT')`.

---

## 7. Out of Scope

- Gmail folder hint — landed in Wave 1 via `labelIds` mapping.
- IMAP folder hint — landed in Wave 1 (IMAP workers explicitly SELECT INBOX).
- The `email_body.folder` column, which mirrors this value — populated by the
  ADAPT-EMAIL-002 worker body-write path, not this PR.
- Historical backfill for rows already inserted with `folder = null`. The
  retention sweep (30-day window) will prune those before EMAIL-001 consumers
  depend on the hint at scale.

---

## 8. Dependencies

- **Blocked by**: nothing. Wave 1 has landed all prerequisite schema + DTO changes.
- **Blocks**: EMAIL-001 `person_ref` derivation accuracy for Outlook accounts.
- **Parallel-safe with**: all other ADAPT-EMAIL-* PRs that touch Gmail / IMAP
  body fetch paths.

---

## 9. Rollback Plan

`git revert <merge-commit-sha>`. Rows inserted with a folder value between merge
and revert stay in the DB; the next refresh round would overwrite them with
null via the merge path in [CommitmentBatchMapper.toEntity], but email workers
insert via [RawIngestionRepository.insertLocal] / `insertLocalBatch` and do not
re-map. Safe forward-only.

---

## Appendix — Session Handoff Notes

- Option A touches `MsGraphClient` (new endpoint) + `MsGraphClientImpl` (second
  delta call) + `SyncCursorStore` (second cursor key) + `OutlookMailWorker`
  (invoke twice per run). ~150 LOC + tests.
- Option B touches `GraphMessage` (+`parentFolderId`) + `MsGraphClient`
  (folder-list call + cache) + `MsGraphClientImpl` ($select + parse) +
  `OutlookMailWorker` (lookup). ~100 LOC + tests.
- MSAL-backed Graph token already exists; neither option needs new auth work.
- Moshi codegen will pick up new fields automatically.
