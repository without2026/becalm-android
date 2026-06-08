# System / Idempotency / reinstall-onboarding — 반복 설치, 로그아웃 후 새 가입 계정 격리

**Branch**: `fix/system/idempotency-reinstall-onboarding`
**Status**: PLAN ONLY — 구현은 별도 slice. 이 문서는 audit/plan handoff 용도.
**E2E Stage**: Auth -> Onboarding -> Source sync -> Local projection
**Severity**: Critical
**Type**: Gap / Drift

---

## 1. Problem

반복 APK 삭제/설치, 동일 계정 재로그인, 그리고 앱을 지우지 않은 routine 로그아웃 후 새 계정 가입에서 system idempotency가 깨지면 안 된다.

Acceptance boundary:

- 앱을 N회 삭제 후 재설치하고 새 계정으로 가입하면, 이전 계정의 local/server-derived 데이터가 보이거나 이전 계정 상태 때문에 오류가 나면 안 된다.
- 앱을 N회 삭제 후 재설치하고 같은 계정으로 로그인하면, backend durable 데이터와 source connection이 복구되고 background sync가 정상 재개되어야 한다.
- 앱을 지우지 않고 routine 로그아웃 후 새 계정으로 가입하는 onboarding에서도, 이전 계정의 local consent/status/runtime state가 새 계정 consent처럼 동작하면 안 된다.

현재 안전한 부분도 있다. Android manifest는 backup/transfer를 끄고 있고, Room DB와 대부분의 onboarding/source prefs는 user-scoped다. 하지만 process-local scheduler state, 일부 global DataStore, device-global runtime permission, raw ingestion ack contract는 아직 이 기준을 만족하지 못한다.

---

## 2. Current Safety Baseline

- `AndroidManifest.xml:59-61` — `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules` 사용.
- `res/xml/data_extraction_rules.xml:7-13` — cloud backup/device transfer 모두 root exclude.
- `BeCalmDatabaseProvider.kt:24-28`, `82-99` — user hash 기반 DB file을 열어 계정 간 Room file sharing을 막는다.
- `UserPrefsStore.kt:470-488`, `502-511` — onboarding/source/PIPA prefs는 `user_<hash>_*` namespace로 분리한다.
- Backend source/status/read APIs는 `request.state.user_id` 기준으로 조회한다. 예: `v1_source_status.py:11-18`, `source_status/queries.py:29-55`.
- Backend source connection/source sync idempotency는 대체로 양호하다. `source_connections`는 user/provider/capability/account identity 기준 upsert/unique active index를 사용하고, source sync job도 active unique index가 있다.

이 baseline 때문에 "이전 계정의 Room row가 그대로 새 계정 DB에서 보이는" 직접 누출은 현재 주요 실패 형태가 아니다. 주요 실패 형태는 아래 P0/P1 findings다.

---

## 3. Findings

### P0-1. Clean reinstall 후 같은 계정 source connection이 runtime scheduling으로 복구되지 않음

**Failure mode**

새 APK 설치 후 같은 계정으로 로그인하면 Room/DataStore는 비어 있다. Backend에는 Gmail/Outlook/Calendar `source_connections`가 남아 있지만 Android bootstrap은 profile/onboarding만 복구하고, local source enable/backend-managed flags를 hydrate하지 않는다. 그 결과 backend-managed source periodic work가 자동으로 다시 잡히지 않을 수 있다.

**UI exposure / user action**

사용자가 앱을 삭제/재설치한 뒤 같은 계정으로 로그인하고 Today, People, Sources, Settings source 화면을 열면 기존 Gmail/Calendar 연결이 즉시 복구된 것처럼 동작하지 않는다. 연결된 출처인데도 새 기록 확인이 자동으로 돌지 않거나, source card가 `never connected`/idle처럼 보이고 사용자가 수동 refresh나 재연결을 눌러야 데이터가 돌아오는 형태로 드러난다.

**Evidence**

- `AuthViewModel.kt:463-474` — auth routing refresh는 `userProfileRepository.refreshFromServer()`와 onboarding cache만 갱신한다.
- `AuthenticatedRuntimeBootstrap.kt:72-90` — cursor migration, DB warm-open, `appRuntimeSyncCoordinator.startAfterStartup()`만 수행한다.
- `RuntimeSyncSourceResolver.kt:40-58` — runtime source 여부는 local `UserPrefsStore` flags로 결정한다.
- `IdentityRepository.kt:319-340` — `SourceConnectionRepository.refresh(userId)`는 이미 존재하지만 auth/bootstrap path에서 호출되지 않는다.
- `SourceStatusMerger.kt:48-110` — source status refresh는 connection state cache만 쓰고 local source enable/backend-managed flags는 세팅하지 않는다.

**Minimal fix direction**

- Authenticated bootstrap 또는 post-auth hydrator에서 `SourceConnectionRepository.refresh(userId)`를 먼저 실행한다.
- connected `SourceConnectionEntity`를 local runtime flags로 투영한다.
  - Gmail/Outlook mail: `setEmailSourceConnected(provider, true)` + `setEmailSourceManagedByBackend(provider, true)`.
  - Google/Outlook calendar: `setSourceEnabled(sourceType, true)`.
- 그 다음 `sourceStatusRepository.refreshFromServer()`와 `appRuntimeSyncCoordinator.refresh()`를 호출한다.
- 이 helper는 onboarding/settings OAuth 성공 path에서도 재사용 가능하게 작게 둔다.

**Acceptance**

- Clean reinstall -> same account login -> backend에 남은 Gmail/Calendar connection만으로 `BackendMailSyncWorker`/calendar sync가 다시 schedule된다.
- Clean reinstall -> new account signup -> backend connection이 없으면 source rows는 idle/never connected이며 이전 account source flags가 생기지 않는다.

---

### P0-2. Fresh install/new account에서 device public MediaStore audio 후보가 노출될 수 있음

**Failure mode**

앱 삭제 후 재설치하면 app-private DB/prefs는 지워지지만, 기기 public MediaStore audio file은 그대로 남는다. 새 계정이 audio permission과 recording source/path를 허용하면 최근 30일 window의 기존 기기 녹음이 새 계정의 `detected_pending_confirmation` 후보로 들어온다. 이것은 OS-level shared device data이지만, "새 계정에서 그 기기에 남아 있던 다른 계정의 데이터가 드러나면 안 된다" 기준에서는 critical이다.

**UI exposure / user action**

사용자가 앱을 삭제/재설치한 뒤 새 계정으로 가입하고, onboarding/settings에서 녹음 source 권한 또는 폴더 접근을 허용하면 이전 계정 시절 기기에 남아 있던 녹음 파일명이 confirmation/import 후보로 보일 수 있다. 사용자가 그 후보를 누르면 이전 계정의 녹음 제목, 시간, sourceRef 같은 단서가 새 계정 UI에 노출된다.

**Evidence**

- `MediaStoreWorker.kt:48-68` — MediaStore audio를 읽고 fresh row는 `detected_pending_confirmation`에 둔다.
- `MediaStoreWorker.kt:160-170`, `193-200` — permission + user-scoped source enabled + path selected이면 scan한다.
- `MediaStoreWorker.kt:335-340` — default auto detection lookback은 30일.
- `VoiceMediaStoreProbe.kt:103-117`, `178-191` — cursor가 없으면 lookback lower bound로 MediaStore를 조회한다.
- `VoiceMediaStoreProbe.kt:833-843` — row title/displayName/sourceRef를 새 raw event entity에 저장한다.

**Decision required**

Device public audio를 "계정 data"로 볼지, "기기 owner가 새 계정에 다시 허용한 source"로 볼지 제품 판단이 필요하다. 사용자의 idempotency 기준을 엄격하게 적용하면 계정별 consent timestamp 이전 file은 자동 후보로 보여주면 안 된다.

**Minimal safe fix direction**

- Per-user, per-source `enabled_at` 또는 `folder_connected_at` timestamp를 추가한다.
- automatic MediaStore scan lower bound를 `max(cursor, enabled_at)`로 제한한다.
- 과거 30일 backfill은 별도 explicit CTA로 분리한다. CTA copy는 "이 계정에 기존 기기 녹음도 가져오기"처럼 계정 전환 의미를 명확히 해야 한다.

**Acceptance**

- Clean reinstall -> new account -> audio source/path 허용 직후, consent 이전 file은 자동 후보에 나타나지 않는다.
- Same account reinstall에서 기존 server/local durable raw mirror는 복구되되, public MediaStore backfill은 명시 동의 전 자동으로 과거 file을 재후보화하지 않는다.

---

### P0-3. Routine logout -> login/signup에서 process-local scheduler flags와 global cursors/status가 계정 경계를 흐림

**Failure mode**

Settings/onboarding의 routine sign-out은 `invalidateSession()`이고 Room/user-scoped prefs를 보존한다. 그러나 WorkManager는 cancel되고 content observer도 stop된다. 같은 process에서 다시 로그인할 때 `AuthenticatedRuntimeBootstrap`은 이미 bootstrapped된 user라고 판단하면 no-op할 수 있고, `AppRuntimeSyncCoordinator`의 process-local schedule flags는 `currentUserId == null` refresh가 실행될 때만 reset된다. 새 계정에서는 global cursor/status stores도 이전 계정 값을 들고 있을 수 있다.

**UI exposure / user action**

사용자가 앱을 종료하지 않은 상태에서 Settings에서 로그아웃한 뒤 같은 계정으로 다시 로그인하면 Sources/Today가 열려도 recurring sync가 다시 등록되지 않아 새 Gmail/Calendar/IMAP 기록이 들어오지 않을 수 있다. 같은 흐름에서 새 계정으로 가입하면 이전 계정의 cursor/status 때문에 source screen이나 processing screen이 이미 처리 중이거나 이미 확인한 상태처럼 보이고, 사용자가 새 기록 확인을 눌러도 일부 source가 skip될 수 있다.

**Evidence**

- `SettingsActionHandlers.kt:135-144`, `AuthViewModel.kt:326-340` — routine sign-out은 `authRepository.invalidateSession()`.
- `AuthRepositorySupport.kt:63-72` — invalidate는 work/content observer/credentials/session/currentUserId만 clear한다.
- `AuthRepository.kt:318-333` — `personEnrichmentRepository.deleteAll()`, `syncCursorStore.clearAll()`, `userPrefsStore.clearAll()`을 의도적으로 호출하지 않는다.
- `AuthenticatedRuntimeBootstrap.kt:99-107` — `bootstrappedUserId == userId`면 bootstrap no-op.
- `AppRuntimeSyncCoordinator.kt:47-54`, `99-113`, `116-128` — scheduled state는 process field이고 null-user refresh 때만 reset된다.
- `SyncCursorStore.kt:292-318` — cursor keys are global (`gmail_history_id`, `imap_*`, `mediastore_*`, `cursor_$source`).
- `SourceStatusRepositorySupport.kt:9-20` — source status keys are global (`source_status.$source.*`).
- `ProcessingStatusRepository.kt:74-77`, `192-202` — processing status keys are global (`processing_status.$source.*`) and no clear API exists.

**Minimal fix direction**

- Add an explicit auth-boundary reset method, called from both `invalidateSession()` and permanent auth failure cleanup:
  - reset `AuthenticatedRuntimeBootstrap` bootstrapped/in-flight marker,
  - reset `AppRuntimeSyncCoordinator` process-local scheduling fields,
  - stop content observers.
- Add/route volatile status cleanup:
  - `SourceStatusRepository.clearAll()`,
  - new `ProcessingStatusRepository.clearAll()`,
  - `SyncCursorStore.clearAll()` or user-scoped cursor migration.

**Decision required**

For cursors, two options:

- Minimum alpha fix: clear all cursors on routine logout. Same-account relogin may do a fuller resync, but data idempotency should hold through backend/client dedupe.
- Maintainable fix: user-scope cursor keys like `UserPrefsStore` does. This preserves same-account efficiency and prevents cross-account cursor skip. Bigger but cleaner.

Do not implement cursor strategy without confirming the tradeoff.

**Acceptance**

- Routine logout -> same account login in same process re-runs runtime bootstrap and recurring work registration.
- Routine logout -> new account signup does not inherit source/processing status, cursor, or process "already scheduled" state from the prior user.

---

### P0-4. Raw ingestion batch ack does not return server ids; local random ids can diverge from backend ids

**Failure mode**

Android local raw rows use random local ids. Backend raw ingestion dedupes by `(user_id, client_event_id)` and may reuse an existing server row, but `/raw_ingestion_events:batch` only returns acknowledged/failed ids. Android marks local random ids synced if they are not failed. Same-account reinstall or re-detect can create a local id that does not match backend `raw_ingestion_events.id`; relation/participant refresh paths then depend on a later raw mirror refresh to repair ids.

**UI exposure / user action**

사용자가 같은 계정으로 재설치한 뒤 같은 녹음, 스크린샷, 또는 local source event를 다시 가져오면 upload는 성공처럼 끝나지만 로컬 row id와 backend row id가 다를 수 있다. 이후 사용자가 Today/People의 약속이나 사람 상세에서 원본 기록을 누르면 source detail이 비어 있거나, participant/commitment refresh가 원본 row를 못 찾아 duplicate/missing 상태처럼 보일 수 있다.

**Evidence**

- `IngestionDtos.kt:303-316` — Android response has acknowledged/failed only.
- `v1_raw_ingestion.py:49-54` — backend response has acknowledged/failed/filtered/processing only.
- `raw_ingestion/store.py:121-147` — backend existing lookup is `(user_id, client_event_id)`.
- `raw_ingestion/ingest.py:45-68` — existing extracted/skipped rows are not reinserted; existing queued rows reuse server id internally.
- `RawEventUploader.kt:197-218` — Android treats absence from failed as synced and marks local ids.
- `UploadWorkerCoordinator.kt:68-75`, `SourceRelationRefreshCoordinator.kt:56-70` — post-upload relation refresh does not always include raw mirror refresh.

**Minimal fix direction**

- Backend response should return per-event ack objects: `client_event_id`, `server_raw_event_id`, `status` (`inserted|existing|queued|filtered|failed`).
- Android should update local raw event server id / mirror identity before marking upload complete.
- `UploadWorkerCoordinator` should request raw source refresh for uploaded source types when server ids can change.

**Acceptance**

- Re-uploading the same `client_event_id` after reinstall maps to the same backend raw event id locally.
- Person/commitment/source participants never point at a server id that Android cannot mirror back to its local raw row.

---

### P0-5. Secure store clear uses async `apply()` in session/device key stores

**Failure mode**

Sign-out/account switch clear should be a durable leak barrier. `EncryptedTokenStore.clear()` and `DeviceKeyStore.clear()` mutate encrypted SharedPreferences with `apply()`, which updates memory immediately but flushes disk asynchronously. A process death after sign-out returns but before disk flush can leave previous credential material on disk.

**UI exposure / user action**

사용자가 Settings에서 로그아웃을 누른 직후 앱을 강제 종료하거나 OS가 process를 죽이면, 다음 실행 때 이전 credential material이 disk에 남아 있을 수 있다. UI에서는 로그인 화면 대신 이전 계정 세션으로 라우팅되거나, 새 계정 로그인/가입 중 authority mismatch/session recovery 오류가 튀는 형태로 드러날 수 있다.

**Evidence**

- `EncryptedTokenStore.kt:176-187` — `prefs.edit().clear().apply()`.
- `EncryptedTokenStore.kt:229-232` — authority mismatch clear also uses `apply()`.
- `DeviceKeyStore.kt:100-104` — device key clear uses `apply()`.
- `ImapCredentialStore.kt:216-228` and `OAuthCredentialStore.kt:282-301` already document why `commit()` is required for cross-account barriers.

**Minimal fix direction**

- Use `commit()` for `EncryptedTokenStore.clear()`, authority mismatch clear, and `DeviceKeyStore.clear()`.
- Log `commit=false`; for session clear, consider returning/throwing failure so auth cleanup can surface a failed sign-out.
- Keep `save()` as `apply()` unless separately audited; the critical barrier is clear.

**Acceptance**

- Unit tests verify `clear()` does not emit cleared session before durable clear is attempted.
- Crash-window test or fake SharedPreferences test verifies clear failure is observable.

---

### P0-6. Routine logout -> new signup inherits app-level READ_CONTACTS permission and can auto-enrich contacts before new-account consent

**Failure mode**

Runtime permissions are app-level, not account-level. If account A granted READ_CONTACTS, then routine logout preserves app permission. Account B signs up in the same install. During auth bootstrap, `AppRuntimeSyncCoordinator.refreshPermissionManagedRegistrations()` schedules/enqueues contacts enrichment solely based on `contactsPermissionChecker.isGranted()`, before B explicitly grants contacts in onboarding. `EnrichmentWorker` then scans ContactsContract and writes the new user's DB. Onboarding preview can show names from contacts without B's per-account consent.

**UI exposure / user action**

계정 A가 연락처 권한을 허용한 뒤 앱을 삭제하지 않고 로그아웃하고, 계정 B가 같은 설치에서 가입하면 B가 onboarding에서 연락처 단계를 누르기 전에도 연락처 enrichment가 실행될 수 있다. B가 onboarding complete summary, contacts preview, Sources/People 관련 화면을 열면 기기 연락처 이름이 B 계정 데이터처럼 보인다.

**Evidence**

- `AppRuntimeSyncCoordinator.kt:287-293` — contacts enrichment is scheduled/enqueued whenever app permission is granted.
- `OnboardingViewModel.kt:490-497` — onboarding immediately calls `hydrateContactsPreview()`.
- `OnboardingViewModel.kt:2288-2312` — contacts preview renders `personEnrichmentRepository.observeAll()` names.
- `OnboardingViewModel.kt:1419-1431` — explicit contacts permission result is tracked, but runtime coordinator does not require this user-scoped step/consent.
- `EnrichmentWorker.kt:110-126` — worker only checks auth session and app permission, then scans ContactsContract.

**Minimal fix direction**

- Add user-scoped contacts source/consent flag in `UserPrefsStore`, separate from Android permission.
- `onContactsPermissionResult(granted=true)` sets that flag for the current user. `onSkipContacts()` clears/keeps false.
- `AppRuntimeSyncCoordinator` and `SourcesList` contact row should require both app permission and user-scoped contacts consent before enrichment/detail.
- `hydrateContactsPreview()` should show preview only when current user's contacts consent is granted, or it should show an empty/non-data state.

**Acceptance**

- Account A grants contacts -> routine logout -> account B signup -> before B taps contacts grant, no enrichment worker is enqueued and no contact names appear.
- B grants contacts -> enrichment runs for B and preview appears.
- A same-account relogin preserves A's contacts consent if product wants that, via user-scoped flag.

---

### P1-7. Global source/processing status can show stale onboarding/settings state for a new account

**Failure mode**

`SourceStatusRepository` and `ProcessingStatusRepository` store state in the global user prefs file without user namespacing. `SourceStatusRepository.clearAll()` exists but routine invalidate does not call it. `ProcessingStatusRepository` has no clear API. A new account can momentarily see previous account "syncing", "error", "awaiting confirmation", or last sync state in Sources/Processing surfaces until a refresh/repair overwrites it.

**UI exposure / user action**

사용자가 A에서 로그아웃하고 B로 가입한 직후 Sources, source detail, Processing status 화면을 열면 A의 `syncing`, `error`, `awaiting confirmation`, `last synced` 상태가 B의 현재 상태처럼 보일 수 있다. 사용자가 해당 source를 눌러 retry하거나 처리 현황을 보면 실제 B source connection이 없는데도 이전 상태 문구가 먼저 노출된다.

**Evidence**

- `SourceStatusRepository.kt:229-247` — status is observed from prefs plus connected source overlay.
- `SourceStatusRepository.kt:410-420` — clearAll exists and comments explicitly mention cross-account stale key risk.
- `ProcessingStatusRepository.kt:74-77`, `192-202` — processing state is global and has no clearAll.
- `SourcesListViewModel.kt:117-134` — authenticated source list combines source status, processing status, contacts summary, permission state.

**Minimal fix direction**

- Either user-scope these keys or clear them at routine auth boundary.
- Add `ProcessingStatusRepository.clearAll()` and call it from auth cleanup.
- Ensure source/status refresh runs on auth bootstrap after current user is set.

**Acceptance**

- New account source/settings screens start from idle/empty state, not previous account status.
- Same-account relogin does not stay stuck in stale active processing if no corresponding raw events exist.

---

### P1-8. Global notification/terms/telemetry prefs are inherited across routine logout

**Failure mode**

Some prefs are intentionally app-level today: terms accepted, notification toggle, telemetry, locale. For routine logout -> new signup, onboarding may inherit previous account's notification preference/terms state. This is not direct data leakage, but it is account-level onboarding idempotency drift if legal/product expects per-account consent.

**UI exposure / user action**

계정 A가 약관, 알림, telemetry를 설정한 뒤 routine logout하고 B가 가입하면 B의 onboarding/complete summary가 A의 선택을 이미 완료된 설정처럼 보여줄 수 있다. 사용자는 B 계정에서 약관 확인이나 알림 설정을 한 적이 없는데 Terms route가 skip되거나 notification 상태가 켜진 것으로 보이는 방식으로 드러난다.

**Evidence**

- `UserPrefsStore.kt:493-500` — `notifications_enabled`, `telemetry_enabled`, `terms_accepted`, locale are global keys.
- `UserPrefsStore.kt:700-710`, `766-770` — notifications/terms are read/written globally.
- `OnboardingCompleteViewModel.kt:88-104`, `158-203` — notification status contributes to onboarding completion summary.
- `AuthViewModel.kt:478-479` — signed-out state uses global terms acceptance.

**Decision required**

Confirm whether these are app-install preferences or account consent records. If they are account-level, user-scope them. If app-level, leave them and document the boundary.

---

## 3B. 2nd Audit Delta — New Findings Only

**Scope note**

This section intentionally excludes issues already covered by P0-1 through P1-8, and excludes issues that would naturally disappear once those fixes are implemented. The additional pass focused on routine logout -> new signup, clean reinstall -> same account, OAuth browser handoff, durable backend mirrors, local-only source artifacts, and long-running extraction jobs.

Android backup/restore was also checked as a baseline. `AndroidManifest.xml` already sets `allowBackup=false`, `fullBackupContent=false`, and `dataExtractionRules=@xml/data_extraction_rules`; `data_extraction_rules.xml` excludes all roots for cloud backup and device transfer. No additional Auto Backup issue was found in this pass.

### P0-9. Same-account clean reinstall lacks a post-auth durable mirror bootstrap beyond source scheduling

**Failure mode**

After a clean reinstall, logging into the same account starts with an empty Room database. Today the auth path refreshes profile/onboarding state and schedules runtime workers, but it does not immediately hydrate the durable mirrors that the first screens depend on: self identity anchors, source connections/status, source participants, commitments, commitment participants, schedule links, and raw/source anchors.

P0-1 covers local runtime flags for backend source connections. This issue is separate: even if source flags are hydrated, Today/People/onboarding can still render empty or incomplete until a user-initiated refresh or a later source sync pulls durable relation mirrors.

**UI exposure / user action**

사용자가 앱을 삭제/재설치한 뒤 같은 계정으로 로그인하고 첫 Today, People, Onboarding identity, source detail 화면을 열면 기존 사람, 약속, source participant, self identity anchor가 없는 신규 계정처럼 보일 수 있다. 사용자가 pull-to-refresh를 하거나 Settings identity/source 화면에 들어가 특정 refresh path를 밟아야 복구되는 식이면 clean reinstall login이 idempotent하지 않다.

**Evidence**

- `AuthViewModel.kt:463-475` refreshes `UserProfileRepository` for onboarding completion, but not relation mirrors.
- `AuthViewModel.kt:417-432` starts `AuthenticatedRuntimeBootstrap` after signed-in state.
- `AuthenticatedRuntimeBootstrap.kt:72-91` performs IMAP/cursor migrations, warm-opens the DB, and calls `AppRuntimeSyncCoordinator.startAfterStartup()`.
- `AppRuntimeSyncCoordinator.kt:99-144` schedules periodic/background work but does not invoke `SourceRelationRefreshCoordinator.refresh`.
- `TodayViewModel.kt:419-459` has a durable mirror pull path, but it is tied to pull-to-refresh rather than post-auth bootstrap.
- `SourceRelationRefreshCoordinator.kt:37-184` already has the reusable path to pull raw events, calendar relations, source participants, commitments, commitment participants, schedule links, and trigger person-index rebuilds.
- `SettingsIdentityViewModel.kt:80-124` refreshes profile/source connections/self identity only when the settings identity screen opens.
- `OnboardingViewModel.kt:2205-2275` hydrates self identity from local profile/anchors and does not force `SelfIdentityRepository.refresh(userId)` first.

**Minimal fix direction**

- Add a small idempotent post-auth mirror bootstrap, or expand the P0-1 hydrator into one helper that runs after the current user is committed:
  - refresh `SelfIdentityRepository`.
  - refresh `SourceConnectionRepository` and `SourceStatusRepository`.
  - call `SourceRelationRefreshCoordinator.refresh` with a bounded post-auth plan for backend-mirrored relations.
  - trigger person-index rebuild only when refreshed mirror stats changed.
- Keep login fail-open: bootstrap failures should surface as recoverable sync/status errors, not block authentication indefinitely.
- New accounts should converge to empty mirrors without special branching.

**Acceptance**

- Clean reinstall -> same account -> first Today/People/onboarding screens recover durable backend data without manual pull-to-refresh.
- Clean reinstall -> new account -> bootstrap completes to empty mirrors and does not borrow previous local state.
- Repeating login/reinstall does not duplicate local mirrors or enqueue unbounded duplicate work.

### P0-10. OAuth callback state is stateless and replayable across logout/reinstall account boundaries

**Failure mode**

OAuth start is authenticated, but the browser callback is not session-bound; it trusts a signed JWT `state` containing user id and provider. The state has a short TTL, but no nonce/jti, no server-side attempt row, no consumed marker, and no cancellation boundary.

Scenario: account A starts Gmail/Calendar OAuth, then logs out, reinstalls, or signs up as account B before the browser redirects back. The old callback can still complete for A and mutate A's `source_connections` and self-identity anchors. This does not leak B's local data, but it violates the auth boundary and makes logout/new-signup behavior non-idempotent. A replay within TTL can also re-complete the same attempt.

**UI exposure / user action**

사용자가 A로 Gmail/Calendar 연결을 시작해 브라우저가 열린 상태에서 앱으로 돌아와 로그아웃하거나 재설치한 뒤 B로 가입하고, 이후 이전 브라우저 tab의 callback을 완료하면 브라우저 성공 화면은 현재 B 세션의 행동처럼 보일 수 있다. Android는 B의 source status만 refresh하므로 B 화면에는 즉시 연결이 안 보일 수 있지만, backend에서는 A의 source connection/self identity가 session boundary 밖에서 변경된다. 사용자가 나중에 A로 로그인하면 본인이 현재 세션에서 끝내지 않은 연결이 이미 완료되어 있고, 같은 callback URL replay도 TTL 안에서는 다시 성공할 수 있다.

**Evidence**

- `v1_source_connections.py:41-53` and `126-138` start OAuth from authenticated `request.state.user_id`.
- `v1_source_connections.py:72-123` and `157-208` process callbacks from `code/state` without an authenticated app session.
- `mail/oauth.py:93-114` signs and decodes state with `sub`, `provider`, `iat`, `exp`; no nonce, attempt id, consumed flag, or cancellation check.
- `mail/oauth.py:247-278` completes the callback by upserting the connection for `payload["sub"]`.
- `calendar/oauth.py:85-106` and `237-272` have the same state/callback pattern.
- `source_connections/credentials.py:51-62`, `158-162` upsert source connections and self-identity anchors.
- Android `EmailOAuthConnector.kt:38-123` and `CalendarOAuthConnector.kt:40-124` open the browser and then refresh current-user status; they cannot invalidate an old browser callback server-side.
- No `oauth_states`, `oauth_attempts`, or equivalent server-side one-time attempt store was found.

**Minimal fix direction**

- Add a small backend `oauth_connection_attempts` table keyed by nonce/jti or state hash:
  - `user_id`, provider/capability, redirect metadata, `expires_at`, `consumed_at`, `cancelled_at`.
- Include the nonce/jti in signed state.
- On callback, atomically consume exactly one unexpired, unconsumed attempt that matches user/provider. Reject replayed, expired, cancelled, or mismatched states.
- Optional later improvement: Android can store the current attempt id for UI recovery/status, but server-side one-time consume must be the source of truth.

**Acceptance**

- Starting OAuth, logging out, signing up as another account, then completing the old browser redirect does not mutate the current account and cannot be replayed.
- Replaying the same callback URL returns an invalid/consumed state response.
- Existing connected-source refresh behavior remains unchanged after a valid one-time callback.

### P1-11. Same-account reinstall cannot restore local-only originals, transcripts, and email bodies; product contract is undefined

**Failure mode**

Some source detail data is intentionally local-only: email bodies, archived markdown originals, meeting transcripts, and file-backed source artifacts. After same-account reinstall, durable summaries/commitments can be restored, but full original evidence bodies disappear because uninstall removes app-private files and Room rows.

This is not a direct privacy leak. It is a recovery contract gap: if "same account reinstall restores required data" includes raw originals/transcripts, the current architecture cannot satisfy it. If local-only retention is the intended privacy boundary, the UI should explicitly represent missing local originals instead of silently looking broken.

**UI exposure / user action**

사용자가 같은 계정으로 재설치한 뒤 People/Today/source timeline에서 복구된 약속이나 source event를 열면 durable summary는 보이지만 원문 이메일 body, transcript, archived markdown 원본이 비어 있거나 로딩 실패처럼 보일 수 있다. 사용자가 원본 보기, evidence detail, raw event detail sheet를 누를 때 "이 기기에만 있던 원본은 사라졌다"는 상태가 없으면 데이터 손상처럼 인식된다.

**Evidence**

- `EmailBodyRepository.kt:13-21` documents `email_body` as on-device-only; backend does not persist mirrored email bodies.
- `SourceArtifactEntity.kt:11-16` stores metadata in Room while content lives in app-private files.
- `SourceArchiveStore.kt:29-45` writes markdown under `Context.filesDir`, which is removed on uninstall.
- `SourceArtifactRepository.kt:75-87`, `112-123`, `148-160` archives and resolves originals from local storage.
- `SourceOriginalResolver.kt:19-32` resolves raw detail originals only from local email body/artifact repositories.
- `RawEventDetailProjector.kt:21-47`, `EmailEventDetailSection.kt:111-120`, and `RawEventDetailSheet.kt:57-66`, `227-250` render these local-only bodies/transcripts in detail UI.

**Minimal fix direction**

- Do not mirror raw bodies/transcripts by default as part of this audit; that would be a privacy/storage design change.
- Add an explicit missing-local-original UI state when durable source anchors or commitments exist but local body/artifact content is absent after reinstall.
- If product says these originals are required after same-account reinstall, discuss a separate design before implementation:
  - encrypted backend artifact storage, or
  - provider on-demand refetch for connected email/calendar sources, or
  - explicit re-import for local audio files.

**Acceptance**

- Same-account reinstall never shows a broken/blank detail section for missing local originals.
- UI clearly distinguishes "durable summary restored" from "original content was only stored on this device".
- No raw email body/transcript is uploaded to backend without an explicit product/privacy decision.

### P1-12. In-flight or zero-item local evidence extraction jobs are not discoverable by a new install

**Failure mode**

Backend persists source events and extraction jobs, but Android does not currently consume `/v1/source_events`, and the extraction job id lives only in local WorkManager input. If the app is uninstalled while a long audio extraction is pending/processing, the new install has no local raw row, no WorkManager state, and no job id to poll.

If the backend job eventually creates commitments/source participants, those may be recovered through other mirrors. But zero-item, failed, pending, or processing source events are not reattached. This can leave same-account reinstall with no honest state for "still processing", "failed", or "re-import required".

**UI exposure / user action**

사용자가 긴 녹음/회의 업로드 또는 extraction 진행 중 앱을 삭제하고 같은 계정으로 재설치하면 Processing status, source detail, Today/People에는 해당 작업이 진행 중인지 실패했는지 재가져오기가 필요한지 표시되지 않을 수 있다. 사용자는 같은 파일을 다시 import하거나 기다리게 되는데, backend에는 이미 processing/completed/failed job이 있어 duplicate upload, 빈 화면, 또는 무한 대기처럼 보일 수 있다.

**Evidence**

- Backend `jobs.py:12-21`, `158-206`, `303-304` persists extraction jobs with stable ids derived from user/raw event.
- `v1_extractions.py:437-445` exposes lookup by job id, but no list/lookup endpoint by user/raw event for Android recovery.
- `v1_source_events.py:25-48` and `source_events/queries.py:23-52` expose backend source-event listing.
- `extractions/persistence.py:89-150` upserts `source_events` and can mark extraction failure.
- Android `RailwayApi.kt:121-154` exposes raw ingestion/source participant endpoints but no `source_events` consumer.
- `VoiceUploadWorker.kt:196-307`, `548-588` keeps job polling state in WorkManager input and re-enqueue data.
- `SourceExtractionUploadRunner.kt:326-342` handles accepted async jobs, and `204-260` can reupload after 404 only if the local source/audio still exists.

**Minimal fix direction**

- Add a light Android source-event mirror using existing backend `/v1/source_events`.
- Refresh that mirror during post-auth bootstrap and foreground catch-up.
- For pending/processing retryable events, reattach to the existing backend job when possible. If the local audio URI/payload is gone and backend cannot finish alone, show a stable "re-import required" state rather than spinning or hiding the event.
- This is broader than a tiny cleanup because it touches source-event UI contract and job recovery semantics; confirm product behavior before implementation.

**Acceptance**

- Same-account reinstall during a long extraction converges to one of: completed mirrored result, active backend processing state, failed state, or explicit re-import-required state.
- Zero-item/failed local evidence source events are visible as durable history if product wants them visible.
- New-account reinstall never sees previous account local evidence state.

---

## 3C. 3rd Audit Delta — P0/P1 Only

**Scope note**

This pass looked only for issues that remain after P0-1 through P1-12 are fixed. It focused on surfaces that are easy to miss during reinstall/idempotency review: local notifications/alarms, Android exported entry points, user-created first memories, pending manual-review mirrors, app-level runtime permissions beyond contacts/audio, and backend uniqueness/RLS boundaries.

**Approved checks**

- No FCM/Firebase remote-push device-token registration path was found, so there is no server-side push token to unregister for account switching in the current code.
- Commitment/person/settings deep links are not a new P0: `CommitmentDetailViewModel` and `PersonDetailViewModel` re-resolve using the current `userId`, and missing rows render as missing state.
- `READ_CALL_LOG` is already gated by user-scoped `call_log_matching_consent`; the remaining call-recording risk is the public MediaStore file visibility already covered by P0-2.
- Backend uniqueness/RLS scan did not find a new P0/P1 account-boundary issue. Newly relevant tables such as `manual_memories` are keyed by `user_id`, and source-event/provider uniqueness includes `user_id`.
- `SourceParticipantMirrorWorker` and `AppRuntimeSyncCoordinator.enqueuePendingSourceParticipantMirrorsIfNeeded` already provide a retry lane for manual person/self match patches, so manual participant-review retry is not a new issue.

### P0-13. Auth boundary does not cancel previously posted commitment notifications or scheduled reminder alarms

**Failure mode**

Local commitment reminders are outside WorkManager. `invalidateSession()` and full `signOut()` cancel workers and content observers, but they do not cancel AlarmManager reminder `PendingIntent`s or already posted reminder notifications. A routine logout from account A can therefore leave an A reminder visible in the notification shade, or allow an A alarm to fire before account B signs up.

The receiver has a strong user-scoped lookup when it runs after an account swap, but that does not protect already posted notifications. It also does not protect the window after logout while the A database is still open and `currentUserId` has been cleared: the scheduled alarm still carries A's `user_id`, queries A's row, and posts a notification whose body includes the A commitment title.

This is separate from P1-8. P1-8 is about notification preference/consent being global; this issue is about previous account user data being left in OS notification/alarm surfaces.

**UI exposure / user action**

사용자가 A에서 due commitment를 만들고 알림/리마인드가 잡힌 뒤 Settings에서 로그아웃하면, B가 같은 설치에서 가입하거나 onboarding 중일 때 notification shade에 A의 약속 제목이 그대로 남아 있을 수 있다. 또는 A의 alarm fire time이 지난 뒤 B 화면을 보고 있는 중 A commitment title을 담은 reminder notification이 새로 뜬다.

**Evidence**

- `ReminderScheduler.kt:63-115` schedules an `AlarmManager` reminder with a `PendingIntent` carrying `commitment_id` and scheduled `user_id`.
- `ReminderScheduler.kt:126-134` can cancel one alarm by commitment id, but there is no auth-boundary bulk cleanup.
- `ReminderBroadcastReceiver.kt:146-178` re-queries `CommitmentDao.findByIdForUser(scheduledUserId, commitmentId)` and builds the notification from the row.
- `ReminderBroadcastReceiver.kt:212-221` posts a notification; the body includes the commitment title via `buildNotificationSpec`.
- `AuthRepositorySupport.kt:46-60` full sign-out steps do not cancel reminder alarms or posted reminder notifications.
- `AuthRepositorySupport.kt:63-72` routine invalidate-session steps also omit reminder cleanup.

**Minimal fix direction**

- Add a small `ReminderBoundaryCleaner` or extend `ReminderScheduler` with an auth-boundary cleanup method.
- Before clearing `currentUserId` and before full DB wipe, query reminder-eligible rows for the outgoing `session.userId`:
  - live commitments with `action_state = 'reminded'`, `due_at IS NOT NULL`, `deleted_at IS NULL`.
- For each row, call existing `ReminderScheduler.cancel(commitmentId)`.
- Expose a matching `cancelNotification(commitmentId)` using the same stable notification id derivation as `ReminderBroadcastReceiver`, or move notification id derivation into a shared helper.
- Also cancel fixed local notification IDs that are account-derived enough to confuse the next user:
  - `MatchingRequiredNotifier` if it is visible.
  - `VoiceFailureNotifier` if a local evidence failure is visible.
- Keep this cleanup idempotent and best-effort, but it must run before the auth boundary is reported as clean.

**Acceptance**

- A posts a reminder notification -> routine logout -> B signs up: B never sees A's reminder title in the notification shade.
- A schedules a reminder for the near future -> routine logout -> wait through fire time -> no A reminder notification is posted while signed out or while B is onboarding.
- Full local wipe also clears posted reminder/failure/matching notifications.
- Repeating cleanup with no scheduled reminders is a no-op.

### P1-14. First-memory manual write has no durable retry outbox when backend sync is deferred

**Failure mode**

Onboarding first memory writes local person/identity/commitment/interactions first, then calls backend `/v1/manual_memories`. If the backend write fails, the method returns success with `syncedRemotely=false`; the commitment remains local with `sync_status='manual_pending'`.

That local state is useful within the same install, but it is not durable across uninstall/reinstall. The normal commitment uploader only flushes `sync_status='pending'`, not `manual_pending`, and there is no manual-memory outbox worker. Therefore a network/backend failure during first-memory save can create user-authored product data that looks saved, but disappears on same-account clean reinstall because it never reached backend.

This is not covered by P0-9. Post-auth durable mirror bootstrap can only restore manual memories that were actually synced to backend.

**UI exposure / user action**

사용자가 onboarding first memory를 입력하고 저장했을 때 네트워크/backend 오류가 나면 화면은 local save 성공처럼 다음 단계로 진행할 수 있다. 그 상태에서 사용자가 앱을 삭제/재설치하고 같은 계정으로 로그인하면 방금 입력한 첫 memory, 사람, 약속이 복구되지 않아 onboarding에서 저장된 것으로 보였던 데이터가 사라진 것처럼 보인다.

**Evidence**

- `FirstMemoryRepositoryImpl.kt:122-149` creates a local `CommitmentEntity` for first memory with `syncStatus = "manual_pending"`.
- `FirstMemoryRepositoryImpl.kt:201-218` commits the local projection first, then calls `syncRemote`; on success it marks only the commitment synced.
- `FirstMemoryRepositoryImpl.kt:252-267` logs backend failure as "deferred" and returns `false`.
- `CommitmentDao.kt:1088-1096` normal upload only selects rows where `sync_status = 'pending'`, so `manual_pending` is never drained by `CommitmentUploader`.
- Backend `/manual_memories` already exists and is idempotent: `v1_manual_memories.py:36-50`, `manual_memories/write.py:65-75`, `202605260001_manual_memories.sql:1-24`.
- `SourceParticipantMirrorWorker` has a pending mirror outbox pattern that can be reused conceptually for manual-memory retry.

**Minimal fix direction**

- Add a small Android `manual_memory_outbox` Room table keyed by `(user_id, client_memory_id)` with:
  - request payload fields needed by `/v1/manual_memories`,
  - `payload_hash`, `retry_count`, `last_error`, `created_at`, `updated_at`.
- `FirstMemoryRepositoryImpl.save` should write the outbox row in the same local transaction as the projected person/commitment/interactions.
- If the immediate remote call succeeds, delete or mark the outbox row synced and mark the commitment synced.
- Add `ManualMemoryOutboxWorker` and schedule it:
  - immediately on first-memory deferred sync,
  - during authenticated runtime bootstrap when pending outbox rows exist,
  - from the existing upload/catch-up lane if that matches local convention.
- On backend idempotency conflict, mark the outbox failed with a visible recovery path instead of silently dropping it.

**Acceptance**

- First-memory save succeeds locally while backend is offline -> same install later regains network -> backend manual memory is created exactly once.
- First-memory save succeeds locally while backend is offline -> clean reinstall before retry -> product should either warn before uninstall is impossible, or more practically the retry must have been attempted opportunistically before onboarding completion is accepted. If product accepts local-only-first here, the UI must not imply durable restore.
- Repeating the same `client_memory_id` does not create duplicate people/commitments/interactions.
- Same-account clean reinstall after a successful retry restores the first memory through P0-9 mirror bootstrap.

---

## 3D. 4th Audit Delta — P0/P1 Only

**Scope note**

This pass focused on account-boundary behavior that remains outside normal UI state: product analytics queueing and notification attribution. It intentionally excludes analytics event catalog quality and metric naming; the issue below is only about cross-account idempotency when persisted analytics payloads survive routine logout/login in the same install.

### P1-15. Product analytics queue and notification attribution can cross account boundary

**Failure mode**

Product analytics events are persisted in an app-global file under `filesDir` and event DTOs do not carry the originating user id. Routine sign-out/new login only resets the analytics user scope for Amplitude/observability; it does not clear the backend mirror queue or notification-open attribution store. If account A has queued offline analytics events, or A opens an A commitment notification and attribution is saved, then account B logs in before the queue drains or before the next matching action event, those payloads can be flushed to Railway using B's current authenticated request. Backend persistence then writes the event rows under B's `user_id`.

**UI exposure / user action**

Android 앱 안에서 일반 사용자에게 직접 보이는 UI 노출은 거의 없다. 노출 위치는 운영/QA surface다. Supabase `product_events`, QA dashboard, retention 분석, notification attribution debugging에서 B가 A-derived object id인 `notification_commitment_id`를 가진 event를 수행한 것처럼 보일 수 있다. 재현 행동은 A가 offline이거나 backend mirror 실패 상태에서 tracked action을 하거나 commitment notification을 열고, A가 로그아웃한 뒤 같은 설치에서 B가 로그인하고, B가 tracked commitment action을 하거나 analytics queue가 drain되는 흐름이다. 이때 다음 backend analytics flush가 A의 queued event/attribution을 B 계정에 붙일 수 있다.

**Evidence**

- `ProductAnalyticsEventQueue.kt:16-20`, `36`, `94-102` — queue API is global and file-backed at `analytics/product-events.jsonl`; DTO conversion omits user id.
- `CompositeProductAnalyticsClient.kt:64-72` — `setUserScope`/`resetUserScope` only update Amplitude and observability.
- `CompositeProductAnalyticsClient.kt:94-132` and `BackendProductEventsMirrorClient.kt:13-28` — queued events are flushed later through the current `RailwayApi` auth context.
- `ProductAnalyticsAttributionStore.kt:21-32`, `49-55` — notification attribution is stored in app-global `SharedPreferences`; clear exists but auth cleanup does not call it.
- `ProductAnalyticsContext.kt:52-75` — the next `COMMITMENT_ACTION_SELECTED` can inherit persisted `notification_instance_id` and `notification_commitment_id`.
- `AuthRepository.kt:300-310`, `313-340` — sign-out/invalidate only reset analytics scope after cleanup.
- `v1_analytics.py:92-102` — backend attributes batch events to `request.state.user_id`.
- `analytics/ingestion.py:18-35`, `40-45` — persisted `product_events` rows use that supplied `user_id` and `event_id` idempotency is scoped by `user_id`.

**Minimal fix direction**

- Add `ProductAnalyticsEventQueue.clearAll()` and make the file-backed implementation delete/rewrite empty under the existing mutex.
- Clear `ProductAnalyticsAttributionStore` at auth boundary.
- Call both from the existing auth cleanup planner for routine `invalidateSession()`, full `signOut()`, and permanent auth-failure cleanup.
- If product wants to preserve unsent analytics across same-account routine logout, do not implement that implicitly. Discuss a user-scoped queue design first because the current event shape has no durable originating-user field.

**Acceptance**

- A queues product analytics while offline -> routine logout -> B login -> analytics drain never writes A events under B's `product_events`.
- A opens a commitment notification -> routine logout -> B login -> B commitment action is not enriched with A's notification attribution.
- Repeating auth-boundary cleanup with no queued analytics or attribution is a no-op.

---

## 4. Proposed Implementation Slices

### Slice A — Auth boundary reset for runtime/status stores

**Files**

- `AuthenticatedRuntimeBootstrap.kt` — add `resetForAuthBoundary()` or make `startForUser` restartable after `invalidateSession`.
- `AppRuntimeSyncCoordinator.kt` — add explicit reset of process-local scheduling fields.
- `AuthRepositorySupport.kt` and `AuthFailureSessionInvalidator.kt` — call reset/clear steps after work cancellation and before currentUserId clear.
- `ProcessingStatusRepository.kt` — add `clearAll()`.
- `SourceStatusRepository.kt` — call existing `clearAll()` on routine auth boundary.
- `SyncCursorStore.kt` — either clear on boundary or migrate to user-scoped keys after decision.

**Tests**

- `AuthRepositorySupport`/cleanup planner unit test: routine invalidate includes runtime reset + status clear.
- `AuthenticatedRuntimeBootstrap` unit test: same user can bootstrap again after reset.
- `AppRuntimeSyncCoordinator` unit test: account switch reschedules periodic/backend mail work after WorkManager cancel.

### Slice B — Post-auth durable source hydration

**Files**

- New small helper, e.g. `SourceConnectionLocalStateHydrator`.
- `AuthenticatedRuntimeBootstrap.kt` or `AuthViewModel.kt` — call hydrator after current user is known.
- `UserPrefsStore.kt` — use existing source/email flag setters.
- `OnboardingViewModel.kt` — reuse helper after OAuth connect if it reduces duplication.

**Tests**

- Source connection refresh maps Gmail/Outlook/Calendar connections to local runtime flags.
- Clean reinstall same-account auth path schedules backend mail/calendar work.

### Slice C — Per-account contacts consent gate

**Files**

- `UserPrefsStore.kt` — add user-scoped contacts consent/source flag.
- `OnboardingViewModel.kt` — write flag on contacts grant/skip.
- `AppRuntimeSyncCoordinator.kt` — require permission + user-scoped contacts flag.
- `SourcesListViewModel.kt` / projector — contact row state respects consent separately from OS permission.
- `OnboardingCompleteViewModel.kt` and contacts preview — do not show contact names before current-user consent.

**Tests**

- A grants contacts -> logout -> B signup -> no enrichment enqueue before B consent.
- B grant triggers exactly one enrichment enqueue and periodic sweep.

### Slice D — Device audio consent cutoff

**Files**

- `UserPrefsStore.kt` — add user-scoped `source_enabled_at` or `recording_folder_connected_at`.
- `MediaStoreWorker.kt` / `VoiceMediaStoreProbe.kt` — lower bound scan by consent timestamp.
- `RecordingFolderScreen.kt` / source settings path selection — set timestamp when the user connects a folder for the current account.

**Tests**

- Existing file before enable timestamp is not auto-detected for a new account.
- File added after enable timestamp is detected.
- Same-account repeated scan remains deduped by `clientEventId`.

### Slice E — Raw ingestion ack identity contract

**Android files**

- `IngestionDtos.kt`
- `RawEventUploader.kt`
- `RawIngestionRepository.kt`
- `UploadWorkerCoordinator.kt`
- `SourceRelationRefreshCoordinator.kt`

**Backend files**

- `app/api/v1_raw_ingestion.py`
- `app/services/raw_ingestion/ingest.py`
- `app/services/raw_ingestion/store.py`
- backend tests for duplicate `client_event_id`.

**Tests**

- Duplicate upload returns same server raw id.
- Android updates local row identity/mirror state before relation refresh.

### Slice F — Secure clear durability

**Files**

- `EncryptedTokenStore.kt`
- `DeviceKeyStore.kt`
- secure-store unit tests.

**Tests**

- Clear uses synchronous commit path and logs/returns failure on commit false.
- Auth cleanup does not report clean success when session clear failed, unless product explicitly accepts best-effort sign-out.

### 2nd Audit Slice G — Post-auth durable mirror bootstrap

**Files**

- New small helper, e.g. `PostAuthDurableMirrorBootstrap` or expand the Slice B hydrator.
- `AuthenticatedRuntimeBootstrap.kt` — run the helper once per active user after DB warm-open.
- `SelfIdentityRepository.kt`, `SourceConnectionRepository.kt`, `SourceStatusRepository.kt`, `SourceRelationRefreshCoordinator.kt` — reuse existing refresh APIs.
- Person index worker/coordinator — rebuild only when mirror refresh changed relevant rows.

**Tests**

- Clean reinstall same-account path hydrates self identity, source status, commitments, participants, and schedule links without pull-to-refresh.
- New-account path completes with empty mirrors.
- Repeating bootstrap is deduped and does not create duplicate WorkManager chains.

### 2nd Audit Slice H — One-time OAuth attempt lifecycle

**Backend files**

- New migration/table for `oauth_connection_attempts`.
- `app/services/sources/mail/oauth.py`
- `app/services/sources/calendar/oauth.py`
- `app/api/v1_source_connections.py`
- backend OAuth callback tests.

**Android files**

- No required source-of-truth change. Optionally store current attempt id for UI status only after backend contract exists.

**Tests**

- Callback consumes an attempt exactly once.
- Replay, expired, cancelled, provider-mismatched, and user-mismatched states are rejected.
- Logout/new-signup while browser is open cannot attach a source to the current account.

### 2nd Audit Slice I — Local-only source-original recovery contract

**Files**

- `SourceOriginalResolver.kt`
- `RawEventDetailProjector.kt`
- `EmailEventDetailSection.kt`
- `RawEventDetailSheet.kt`
- strings/resources for explicit missing-local-original state.

**Tests**

- Same-account reinstall with durable mirror but missing local artifact shows a clear local-only missing state.
- New-account install cannot resolve previous account local artifacts.
- No backend raw-body upload is introduced.

### 2nd Audit Slice J — Source-event/job reattachment design

**Files**

- `RailwayApi.kt` — add `/v1/source_events` client if product confirms this recovery surface.
- New source-event mirror repository/entity, or reuse `SourceEventAnchorEntity` if it is sufficient.
- `SourceRelationRefreshCoordinator.kt` or post-auth bootstrap helper — refresh source-event mirror.
- `VoiceUploadWorker.kt` / extraction repair path — reattach or mark re-import-required.
- Backend job lookup/list endpoint only if existing `/v1/source_events` plus deterministic job id is insufficient.

**Tests**

- Uninstall during async extraction -> reinstall same account -> recover completed/processing/failed/re-import-required state.
- Zero-item extraction remains visible only if product wants durable zero-item source history.
- Repeating recovery does not duplicate uploads or poll loops.

### 3rd Audit Slice K — Auth-boundary notification and alarm cleanup

**Files**

- `ReminderScheduler.kt` — expose shared notification id derivation or a cancel-posted-notification helper.
- `ReminderBroadcastReceiver.kt` — move notification id derivation to the shared helper if needed.
- `CommitmentDao.kt` — add an outgoing-user query for live reminded commitments.
- `AuthRepositorySupport.kt` / `AuthFailureSessionInvalidator.kt` — run cleanup before `currentUserId` clear and before full DB wipe.
- `MatchingRequiredNotifier.kt` and `VoiceFailureNotifier.kt` — expose public `cancel` helpers if currently private/missing.

**Tests**

- Auth cleanup cancels alarms for all reminded commitments owned by the outgoing user.
- Auth cleanup cancels posted reminder/matching/failure notification IDs.
- Reminder receiver still silently drops mismatched/missing-user alarms.

### 3rd Audit Slice L — First-memory retry outbox

**Files**

- New Room entity/DAO for manual-memory outbox.
- `FirstMemoryRepositoryImpl.kt` — write outbox atomically with local first-memory projection.
- New `ManualMemoryOutboxWorker` or an extension of the existing upload lane.
- `AppRuntimeSyncCoordinator.kt` — schedule pending manual-memory outbox retry after authenticated startup.
- `RailwayApi.kt` / `ManualMemoryDtos.kt` — reuse existing `/v1/manual_memories` DTOs.

**Tests**

- Backend failure after local first-memory save leaves an outbox row and schedules retry.
- Retry creates backend manual memory exactly once and clears outbox.
- Idempotency conflict becomes a visible recoverable failed state.
- Clean reinstall after successful retry restores the first memory via mirror bootstrap.

### 4th Audit Slice M — Analytics auth-boundary cleanup

**Files**

- `ProductAnalyticsEventQueue.kt` — add `clearAll()` to queue contract and file-backed implementation.
- `ProductAnalyticsAttributionStore.kt` — reuse `clearNotificationOpen()` at auth boundary.
- `AuthRepositorySupport.kt` / `AuthFailureSessionInvalidator.kt` — run analytics queue/attribution cleanup in routine invalidate, full sign-out, and auth-failure cleanup.
- Existing analytics unit tests, plus auth cleanup planner tests if present.

**Tests**

- Queued backend mirror events are cleared during auth-boundary cleanup.
- Notification attribution is cleared during auth-boundary cleanup.
- B login after A logout cannot flush A event ids or A `notification_commitment_id` under B.

---

## 5. QA Matrix

Use debug APK unless release-specific backup/restore behavior is being verified.

- Clean reinstall, new account:
  - `adb uninstall com.becalm.android`
  - install APK
  - sign up with new account
  - verify no previous source status, processing status, contacts names, audio candidates, commitments, people, or source flags appear.
- Clean reinstall, same account:
  - uninstall/install
  - login same account
  - verify backend source connections hydrate, runtime sync schedules, commitments/people mirror back without crashes.
- Routine logout, same account:
  - grant contacts/audio/source permissions, connect sources
  - Settings sign out
  - login same account in same process
  - verify runtime bootstrap runs again and work is rescheduled.
- Routine logout, new account:
  - A grants contacts and connects at least one source
  - A signs out without full wipe
  - B signs up
  - verify no contact enrichment before B consent; no stale source/processing status; no backend mail/calendar work unless B has durable connections.
- Crash-window:
  - sign out, kill process immediately
  - relaunch
  - verify no previous session/device key survives.
- Raw ingestion duplicate:
  - upload local source event
  - reinstall/same account/re-detect same `client_event_id`
  - verify local raw row maps to same server id and relation refresh works.
- Post-auth mirror bootstrap:
  - uninstall/install
  - login same account with existing commitments, source participants, self identity, and schedule links
  - verify Today/People/onboarding recover without manual pull-to-refresh.
- OAuth stale callback:
  - A starts Gmail or Calendar OAuth in browser
  - return to app, sign out, sign up as B, then complete A's old browser redirect
  - verify callback is rejected or consumed for A only, never reflected in B, and replaying the URL fails.
- Local-only original detail:
  - create source detail with local email body/transcript/artifact
  - uninstall/install and login same account
  - verify durable summary remains while original body/transcript shows explicit local-only missing state.
- Async extraction recovery:
  - start a long audio extraction and uninstall before completion
  - reinstall/login same account
  - verify completed, processing, failed, or re-import-required state is deterministic and does not spin forever.
- Reminder auth boundary:
  - A creates a due commitment, taps remind, and verifies an alarm/notification can be posted
  - A signs out without full wipe
  - B signs up
  - verify no A reminder title remains posted and no A reminder fires while B is onboarding.
- First-memory deferred sync:
  - simulate backend/network failure for `/v1/manual_memories`
  - save onboarding first memory
  - verify a retry outbox row exists and retry succeeds exactly once when network/backend returns
  - reinstall/login same account after successful retry and verify the memory rehydrates.
- Analytics auth boundary:
  - force backend analytics mirror failure or airplane mode
  - A performs tracked actions or opens a commitment notification
  - A signs out without full wipe, then B logs in
  - verify queued analytics are cleared and B's next tracked action is not enriched with A `notification_commitment_id`.

---

## 6. Out of Scope

- Full redesign of auth/session model.
- Deleting previous user's Room DB during routine logout. The product contract says same-account relogin should restore local data.
- Treating backend durable data as local wipe target. Backend account deletion/export/wipe remains separate.
- Changing source connection backend unique constraints unless raw/source contract tests prove a backend duplicate.

---

## 7. Open Decisions Before Implementation

1. **Cursor strategy**: clear volatile cursors on routine logout now, or user-scope cursor keys first?
2. **Device public audio policy**: should a new account ever see pre-existing public recordings automatically after granting source/path, or only after explicit historical import consent?
3. **Global prefs policy**: terms/notification/telemetry/locale are install-level today. Should terms and notification onboarding state become account-scoped?
4. **Local-only original policy**: are email bodies, transcripts, and archived originals intentionally device-only after reinstall, or must same-account reinstall restore them through encrypted backend storage/provider refetch?
5. **Source-event recovery scope**: should pending/failed/zero-item source events be a durable user-visible history, or only successful extracted relations/commitments?
6. **OAuth attempt schema**: approve a small backend one-time attempt table/migration before implementation.
7. **First-memory durability policy**: should onboarding first memory block completion until remote sync succeeds, or is local-first plus retry outbox acceptable?
8. **Analytics retention policy**: is it acceptable to drop unsent product analytics at auth boundary, or do we need a user-scoped analytics queue before preserving them?

---

## 8. Rollback Plan

Most slices are Android-only and rollback by revert. Raw ingestion ack contract is backend/API-facing and must be backward compatible:

- Backend should accept old Android clients and can add fields without removing existing `acknowledged`/`failed`.
- Android should tolerate old backend responses during rollout by falling back to raw mirror refresh.
- Cursor/user-scoped DataStore migration must be forward-only but safe: old global keys can be read once and copied into the current user namespace, then left unused until a later cleanup.

---

## Appendix — Audit Commands

```bash
rg -n "invalidateSession|signOut|setCurrentUserId|startForUser|startAfterStartup|scheduledPeriodicSources|backendMailScheduled" android/app/src/main/java
rg -n "observeSourceEnabled|setEmailSourceConnected|source_status|processing_status|clearAll" android/app/src/main/java
rg -n "READ_CONTACTS|contactsPermissionChecker|EnrichmentWorker|hydrateContactsPreview" android/app/src/main/java
rg -n "raw_ingestion_events:batch|acknowledged|client_event_id|source_event_id" android/app/src/main/java ../becalm-backend/app
rg -n "prefs\\.edit\\(\\)\\.clear\\(\\)\\.apply|commit\\(\\)" android/app/src/main/java/com/becalm/android/data/local/secure
rg -n "ReminderScheduler|ReminderBroadcastReceiver|NotificationManagerCompat|AlarmManager|manual_pending|manual_memories" android/app/src/main/java ../becalm-backend/app ../becalm-backend/supabase/migrations
rg -n "ProductAnalyticsEventQueue|ProductAnalyticsAttributionStore|ProductAnalyticsContext|events:batch|product_events" android/app/src/main/java ../becalm-backend/app
```
