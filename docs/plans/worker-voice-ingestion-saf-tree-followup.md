# UI / Onboarding / saf-tree — SAF tree URI migration for voice ingestion (deferred from PR #14)

**Branch**: `refactor/ui/onboarding/saf-tree`
**Status**: PLAN ONLY — scheduled for Wave 6.
**E2E Stage**: 1 — Voice/Call recording ingestion (MediaStore → Room) + Onboarding (ONB-002 / ONB-003)
**Severity**: Critical (Samsung One UI 6.x parity; spec ONB-003 invariant unmet until shipped)
**Type**: Drift (spec requires SAF tree URI grant; code still reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` directly)

---

## 1. Finding

Finding PR #14 (`docs/plans/worker-voice-ingestion-realign.md`) identified that ingestion silently returns zero rows on Samsung One UI 6.x because the recorder-folder LIKE pattern used `"VoiceRecorder"` (no space) while the actual relative path is `Recordings/Voice Recorder/`. That PR was split: Wave 1 (folder-name realignment + worker-leaf KDoc + `CALL_FOLDER` constant) shipped in `refactor/worker/voice/ingestion-realign`; the full SAF tree URI pivot required by `.spec/onboarding.spec.yml:37-44` (ONB-003) — `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` + `DocumentsContract.buildChildDocumentsUriUsingTree` child traversal — was deferred because it requires a coordinated onboarding UI rework (RecordingFolderScreen + OnboardingViewModel + UserPrefsStore persistable-URI key) and a full rewrite of `VoiceMediaStoreProbe` (~400-line test file), scope that does not fit a Wave 1 worker-leaf PR. Until shipped, `VoiceMediaStoreProbe` continues using MediaStore directly with the realigned `"Voice Recorder/"` LIKE patterns — functional but not spec-invariant for ONB-003.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/onboarding.spec.yml:37-44`** — ONB-003 SAF tree URI grant:
  > "SAF grant는 상위 'Recordings/' 단일 URI에 부여되며 하위 'Voice Recorder/'(source_type='voice')와 'Call/'(source_type='call_recording') 두 서브디렉토리를 동시에 커버한다 — 사용자는 한 번의 폴더 선택으로 두 소스를 모두 활성화한다"
  > "grant된 URI는 tree permission으로 하위 서브디렉토리 traverse를 포함하므로 음성/통화 두 폴더를 별도 grant 없이 감지한다"

- **`.spec/onboarding.spec.yml:29-33`** — ONB-002 auto-discovery path:
  > "자동 탐색 우선순위: (1) '/storage/emulated/0/Recordings/' (Samsung One UI 6.x 표준), (2) '/storage/emulated/0/VoiceRecorder/' (일부 구형/변형 기종 fallback)"

- **`.spec/voice-pipeline.spec.yml` VOI-005** — permission gate (still required even after SAF pivot):
  > "READ_MEDIA_AUDIO 권한 미부여 시 ContentObserver가 등록되지 않고 VoiceUploadWorker도 enqueue되지 않는다"

---

## 3. Code Reality (지금 무엇인가)

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt:160`** — still queries MediaStore directly:
  ```kotlin
  uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
  ```
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/RecordingFolderScreen.kt`** — no SAF picker launch. Grep:
  ```bash
  grep -rn "openDocumentTree\|takePersistableUriPermission\|DocumentsContract" android/app/src/main/
  # → 0 matches
  ```
- **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`** — no persistable tree URI key.

Wave 1 PR #14 closed the folder-name LIKE drift only; the SAF pivot was deferred by design.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| Access mechanism | SAF tree URI (DocumentsContract) | MediaStore Audio direct query | Full rewrite of probe |
| SAF persistence | `takePersistableUriPermission` after grant | Not called anywhere | Add to Onboarding VM callback |
| Tree URI storage | Persistable URI in DataStore | No key defined | Add `observeRecordingTreeUri` |
| Watermark unit | `COLUMN_LAST_MODIFIED` (epoch ms) | `DATE_ADDED` (epoch sec) | Convert cursor / rewrite tests |
| Observer attach | `registerContentObserver` on tree URI | Observer only on MediaStore / SMS | Repoint ContentObserverBootstrap |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/ui/onboarding/RecordingFolderScreen.kt` — add `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` + call-site to initial URI `/storage/emulated/0/Recordings/`; surface grant result in ViewModel state.
- `android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt` — persist granted tree URI via UserPrefsStore; call `context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` inside the launcher callback.
- `android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt` — add `observeRecordingTreeUri()` / `setRecordingTreeUri(uri)` key (or new `RecordingFolderStore`).
- `android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt` — replace `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` query with `DocumentsContract.buildChildDocumentsUriUsingTree` + child traversal. Watermark switches from `DATE_ADDED` (sec) to `COLUMN_LAST_MODIFIED` (ms). `audioUri` stored in `sourceRef` becomes a SAF document URI string. Classification by parent-folder name (`Voice Recorder/` → `SourceType.VOICE`, `Call/` → `SourceType.CALL_RECORDING`).
- `android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt` — register `contentResolver.registerContentObserver(treeUri, notifyForDescendants = true, …)` when a persisted tree URI exists. (Coordinate with `refactor/worker/sms/remove-dead-code`.)
- `android/app/src/test/java/com/becalm/android/worker/ingestion/MediaStoreWorkerTest.kt` + `VoiceMediaStoreProbeTest.kt` — rewrite fixtures against a fake SAF tree (`MatrixCursor` for `DocumentsContract.queryChildDocuments` rather than `MediaStore.Audio.Media`).

### 5.2 Files to add
- (Optional) `android/app/src/main/java/com/becalm/android/data/local/datastore/RecordingFolderStore.kt` — if the tree URI deserves a dedicated store rather than a `UserPrefsStore` key.

### 5.3 Files to delete (dead code)
None.

### 5.4 Non-code changes
- Permission: `READ_MEDIA_AUDIO` stays (VOI-005 still references it). SAF tree URI makes it optional, but the safe default is to keep the permission gate.
- DataStore migration: existing `KIND_VOICE` watermark is already epoch ms, so switching to `COLUMN_LAST_MODIFIED` (ms) requires no key migration.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "MediaStore\.Audio\.Media\.EXTERNAL_CONTENT_URI" android/app/src/main/java/com/becalm/android/worker/ | wc -l` is 0.
- [ ] **Grep invariant**: `grep -rn "takePersistableUriPermission\|openDocumentTree\|DocumentsContract" android/app/src/main/ | wc -l` is at least 1.
- [ ] **Unit test**: `VoiceMediaStoreProbeTest` — SAF tree URI mock with child traversal verifies only `Voice Recorder/` files are inserted as `SourceType.VOICE` and `Call/` files as `SourceType.CALL_RECORDING`.
- [ ] **Unit test**: `RecordingFolderScreenTest` (or ViewModel test) — `Recordings/` SAF picker launch and `takePersistableUriPermission` call verified.
- [ ] **Integration (Robolectric or device)**: Fake SAF tree rooted at `Recordings/` + probe run → `raw_ingestion_events` gets 1 row per fake audio file.
- [ ] **Manual (device)**: Samsung One UI 6.x device records via Voice Recorder → app re-launch → `raw_ingestion_events` contains 1 fresh row.

---

## 7. Out of Scope

Everything already shipped by Wave 1 (`refactor/worker/voice/ingestion-realign`, finding PR #14 partial):

- `RECORDER_FOLDER_SAMSUNG` string value change (`"VoiceRecorder"` → `"Voice Recorder"`).
- `CALL_FOLDER` constant declaration in `MediaStoreWorker` companion.
- Three-pattern LIKE OR (Samsung-nested, Samsung-root-relative, stock AOSP).
- Test-fixture string updates from `"VoiceRecorder/"` → `"Voice Recorder/"`.
- KDoc "Known gap (follow-up)" notes in `MediaStoreWorker.kt` / `VoiceMediaStoreProbe.kt`.

Also out of scope for this follow-up:

- Actually inserting `Call/` files as `SourceType.CALL_RECORDING` — owned by `feat/worker/voice/call-recording` (finding PR #15). This PR only guarantees the SAF traversal surfaces `Call/` paths to that PR's classifier.
- `SourceTypes.CALL_RECORDING` enum constant — owned by `feat/db/voice/call-recording-enum`.
- SMS/CallLog observer removal — owned by `refactor/worker/sms/remove-dead-code`.

---

## 8. Dependencies

- **Blocked by**: nothing critical. Functionally runnable today — MediaStore LIKE patterns realigned in Wave 1 already unblock Samsung One UI 6.x ingestion. Coordination with `refactor/worker/sms/remove-dead-code` recommended so `ContentObserverBootstrap` is not modified in two conflicting directions.
- **Depends on**: Wave 6 onboarding UI availability (`RecordingFolderScreen` + `OnboardingViewModel` uplift). The SAF picker launch + `takePersistableUriPermission` require Activity context, so the implementation touches Compose + ViewModel simultaneously with the worker layer.
- **Blocks**: spec-invariant ONB-003 closure. Does not block any downstream PR because Wave 1's MediaStore-based realignment is functionally sufficient for the app to ingest.

---

## 9. Rollback plan

Revert of this future PR is safe:
1. `git revert <commit-sha>` reverts the probe back to MediaStore direct query.
2. Persisted SAF tree URIs on user devices remain orphaned but harmless (no code reads them post-revert).
3. `KIND_VOICE` watermark unit (epoch ms) was preserved across the pivot, so no DataStore migration is required in either direction.

Caveat: if `feat/worker/voice/call-recording` has already shipped and `SourceType.CALL_RECORDING` rows exist on-device / server, revert orphans those rows. Revert in reverse merge order (call-recording PR first, then this PR).

---

## Appendix — Session handoff notes

- Biggest trap: `ContentResolver.openInputStream(uri)` works for both MediaStore and SAF URIs, but metadata columns differ. SAF exposes `DocumentsContract.Document.COLUMN_LAST_MODIFIED` / `COLUMN_DISPLAY_NAME` / `COLUMN_SIZE`; there is no `DURATION` column. Fall back to `MediaMetadataRetriever` or a secondary MediaStore query when duration is needed for the upload payload.
- Samsung device validation is mandatory — the emulator cannot reproduce `Recordings/Voice Recorder/`. Inject a fake MediaStore row with `RELATIVE_PATH='Recordings/Voice Recorder/'` for integration tests, or run on a real Galaxy device.
- `takePersistableUriPermission` requires Activity context → cannot be called from a ViewModel. Use `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` inside the Screen and forward the URI to the ViewModel callback.
- Existing test (`MediaStoreWorkerTest.kt`, ~400 lines) is MediaStore-cursor-based and will require large-scale rewriting against a fake SAF tree. Consider splitting the implementation into two sessions (probe rewrite + test rewrite) to keep each PR reviewable.
