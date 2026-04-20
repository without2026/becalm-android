# Round 6 Audit

Evidence base: ~70% file coverage on `android/app/src/main/java/com/becalm/android/` plus all 10 `.spec.yml` and 3 contracts. Findings below are ordered BLOCKER → HIGH → MED → LOW within each layer.

## data/

### [BLOCKER] Commitment PATCH failure leaves row `sync_status='synced'`
- Where: `data/repository/CommitmentRepositoryImpl.kt:136-191` (`updateActionState`)
- What: Optimistic Room write runs, then Railway PATCH runs. On non-2xx (or IOException) the local write is kept but `sync_status` is not downgraded to `'pending'`. `CommitmentEntityMapper.toEntity` defaults missing `syncStatus` to `'synced'` (line 315 of same file).
- Why it blocks approval: `CommitmentRepository.kt:83-92` KDoc contract and `commitment-management.spec.yml` invariant 3 both mandate `sync_status='pending'` on PATCH failure so SP-29 can retry. Without it, every network hiccup silently desynchronizes device vs. backend.
- Fix size: S
- Depends on: Finding below (flushCommitments) — fixing one without the other is incoherent.

### [BLOCKER] Database allows destructive downgrade despite own prohibition
- Where: `data/local/db/BeCalmDatabase.kt:117`
- What: `.fallbackToDestructiveMigrationOnDowngrade()` is called at builder time. KDoc lines 49-52 and 100-104 plus `di/DatabaseModule.kt:42` all say destructive fallback is forbidden.
- Why it blocks approval: R2-01 in `refactor-codex-r1-r10.md` was marked fixed at `ea6a210`, but the regression is present. Any downgrade (QA APK install on dev device with higher schema) will wipe user commitments, PIPA consent state, and contacts cache.
- Fix size: S

### [HIGH] Token fetch happens on every Railway request via `runBlocking`
- Where: `core/di/NetworkModule.kt:225-236` (`DefaultAuthTokenProvider.currentAccessToken`); `data/remote/interceptor/AuthInterceptor.kt:79` (refresh path)
- What: The interceptor calls `runBlocking { sessionStore.load()?.accessToken }` on *every* outbound Railway call. The refresh path also `runBlocking`s. No in-flight refresh dedup — N concurrent 401s produce N refresh requests.
- Why it blocks approval: Stalls OkHttp threads, defeats Supabase SDK's own async cache, and will thunder-herd refresh under the Today screen's simultaneous multi-source prefetch (TDY flows).
- Fix size: M

### [MED] `runCatching{}.fold{}` pattern swallows CancellationException
- Where: `data/repository/PersonEnrichmentRepositoryImpl.kt:111-139` (pattern repeats in `AuthRepositoryImpl`, `SourceStatusRepositoryImpl`, `CommitmentRepositoryImpl`).
- What: `runCatching { dao.upsert(...) }.fold(...)` catches `CancellationException` as a regular `Throwable`, preventing structured-concurrency propagation when the caller scope cancels.
- Why it blocks approval: Kotlin coroutines convention: always re-throw `CancellationException` before mapping.
- Fix size: S

### [MED] `CommitmentRepositoryImpl.transitionState` dispatches manually
- Where: `data/repository/CommitmentRepositoryImpl.kt:122`
- What: `withContext(Dispatchers.IO)` is hard-coded despite the injected dispatcher pattern being used elsewhere.
- Why it blocks approval: Non-deterministic under `TestDispatcher` runners; `CommitmentStateMachineTest` cannot drive virtual time.
- Fix size: S

## domain/

### [MED] `ReminderScheduler` imports `java.time.Instant` in a `kotlinx.datetime` codebase
- Where: `domain/reminder/ReminderScheduler.kt:11`
- What: `java.time.Instant` mixed with `kotlinx.datetime.Instant` used everywhere else.
- Why it blocks approval: Two `Instant` types in one graph is an early signal of leaking JVM time types into shared modules and breaks KMP readiness.
- Fix size: S

## worker/

### [BLOCKER] `UploadWorker.flushCommitments` marks rows synced without uploading
- Where: `worker/UploadWorker.kt:236-263`
- What: Fetches pending commitments, then calls `commitmentRepository.markSynced(pending.map { it.id })`. The inline comment admits: "The repository exposes no `uploadBatch` for commitments, so we mark them synced locally." There is no Railway call on this path.
- Why it blocks approval: Violates `backend-sync.spec.yml` SYNC-001 and invariant 2 ("모든 commitment 변경은 Railway로 업로드된다"). Combined with the PATCH-failure finding above, commitments can never reach the backend after any transient network error.
- Fix size: L (needs a new RailwayApi batch endpoint)
- Depends on: API contract gap — `api-contract.yml` does not yet define `POST /v1/commitments:batch`.

### [HIGH] `WorkSchedulerImpl` stubs workers that already exist
- Where: `worker/WorkSchedulerImpl.kt:159-163,180-183,200-204`
- What: Dispatches `OutlookCalWorker` and `ImapDaumWorker` as `TODO("SP-27b")`, yet `worker/ingestion/OutlookCalendarWorker.kt` is a 420-LOC production implementation with its own worker test.
- Why it blocks approval: Either the real workers run (scheduler must wire them) or the real files are dead. Both states ship; reviewers cannot tell which is authoritative.
- Fix size: S

### [MED] `WorkSchedulerImpl` uses `android.util.Log` not `Logger`
- Where: `worker/WorkSchedulerImpl.kt:70,77,87,130,161,182,202,206`
- What: Direct `Log.d/e` calls despite the Hilt graph supplying `Logger` everywhere else.
- Why it blocks approval: Unredacted userId/source writes to logcat — minor PIPA risk.
- Fix size: S

## ui/

### [BLOCKER] Today screen missing four mandated controls
- Where: `ui/today/TodayTimelineScreen.kt`
- What: No SourceStatusStrip (TDY-003), no pull-to-refresh (TDY-006), no OverallSyncIndicator text banner (TDY-008, only an icon-sized spinner at lines 63-70), no catch-up tap/drag gesture (TDY-009). Per-row commitment badges with enriched person display are absent (TDY-001 is partial).
- Why it blocks approval: Each missing control is a P1 user-visible spec gap.
- Fix size: L

### [BLOCKER] Onboarding step count drifts from spec
- Where: `ui/onboarding/OnboardingViewModel.kt:33-47,346-363`
- What: `OnboardingStep` enum has 13 values including `SMS_PERM`, `CALL_PERM`, `NOTIF_PERM` which have no screens. `isTerminalGatePassed` uses a `stepsWithoutScreen` escape hatch.
- Why it blocks approval: `onboarding.spec.yml` line 19 says "12단계" but line 48 says "11단계" — the spec itself is internally inconsistent (see QUESTIONS).
- Fix size: M
- Depends on: Spec owner deciding 11 vs 12 vs 13.

### [HIGH] `counterparty_raw` displayed instead of enriched person
- Where: `ui/today/TodayViewModel.kt:231`; `ui/commitments/CommitmentManagementViewModel.kt:333`
- What: Both VMs assign `counterpartyDisplayName = entity.counterpartyRaw?.take(MAX)` rather than joining through `person_ref` → `PersonEnrichmentRepository`.
- Why it blocks approval: TDY-001 and CMT-001 explicitly require the enriched display. Defeats the PIPA persons_enrichment table's existence reason.
- Fix size: M

### [HIGH] ViewModels use wall-clock directly, bypassing injected `Clock`
- Where: `ui/today/TodayViewModel.kt:255-262` (`kotlinx.datetime.Clock.System.now()`); `ui/commitments/CommitmentManagementViewModel.kt:309` (`java.time.LocalDate.now(ZoneOffset.UTC)`)
- What: Both VMs reach for global clocks despite `core.util.Clock` being Hilt-provided.
- Why it blocks approval: `CommitmentManagementViewModelTest` and `TodayViewModelTest` cannot drive deterministic time.
- Fix size: S

### [LOW] `CommitmentManagementScreen` renders 6 filter chips where spec defines 3 tabs
- Where: `ui/commitments/CommitmentManagementScreen.kt`
- What: FilterChips for `ALL | GIVE | TAKE | DUE_TODAY | OVERDUE | DONE`. CMT-001 specifies three tabs: 전체 / 내가 한 / 상대가 한.
- Why it blocks approval: Scope drift — may be acceptable as IMPLIED extension, but undocumented in any ADR.
- Fix size: S

### [LOW] `AuthRepositoryImpl.signOut` / `invalidateSession` are near-god-methods
- Where: `data/repository/AuthRepositoryImpl.kt:159-216,220-274`
- What: ~55 lines / 11 steps each; well-documented but dense. **Major duplication** — 90% of code repeats between the two methods.
- Why it blocks approval: Readability + DRY.
- Fix size: M (extract per-step helpers + share skeleton)

## core/

### [HIGH] Concurrent 401 refresh has no in-flight dedup
- Where: `data/remote/interceptor/AuthInterceptor.kt:75-100`
- What: On 401, `runBlocking { tokenProvider.refresh() }`. Nothing prevents two simultaneous Railway requests from triggering two refreshes.
- Why it blocks approval: Under Today's parallel prefetch the interceptor can double-call Supabase refresh, invalidating the first token mid-use.
- Fix size: M

## di/

No BLOCKER / HIGH findings. NetworkModule, DatabaseModule, RepositoryModule, WorkerModule all validated.

## receiver/

Not fully re-audited this round; `ReminderBroadcastReceiver` signal-pattern dispatch still clean per spot-check.

## test/

### [MED] No Room migration tests despite `exportSchema=true`
- Where: `app/schemas/.../1.json,2.json,3.json` exist but `src/test/.../MigrationTest.kt` does not
- What: Migrations 1→2 (`commitment_state`) and 2→3 (index drop) have zero regression coverage.
- Why it blocks approval: Room's `MigrationTestHelper` is the only safety net — a malformed migration wipes production users.
- Fix size: M

### [MED] Missing worker tests
- Where: `src/test/.../worker/` lacks `UploadWorkerTest`, `EnrichmentWorkerTest`, `GmailWorkerTest`, `MediaStoreWorkerTest`, `ImapNaverWorkerTest`, `GoogleCalendarWorkerTest`, `OutlookMailWorkerTest`.
- What: Only `VoiceUploadWorkerTest` and `OutlookCalendarWorkerTest` exist.
- Why it blocks approval: Worker logic owns every PIPA gate and sync_status transition.
- Fix size: L

## spec-contracts/

### [HIGH] `api-contract.yml` v1 lacks `POST /v1/commitments:batch`
- Where: `.spec/contracts/api-contract.yml`
- What: No endpoint to satisfy `UploadWorker.flushCommitments` + `CommitmentRepositoryImpl.updateActionState` retry-on-pending flow.
- Why it blocks approval: The two BLOCKER commitment-sync findings cannot be fixed without it.
- Fix size: M (contract + server)

### [HIGH] `api-contract.yml` v1 lacks `GET /v1/source_status`
- Where: `.spec/contracts/api-contract.yml` vs `data/repository/SourceStatusRepository.kt`
- What: TDY-003's SourceStatusStrip is currently client-derived from `SyncCursorStore` + `DataStore`.
- Why it blocks approval: If the server is the source of truth, the client is lying to the user about sync health.
- Fix size: M

## QUESTIONS (spec ambiguities — CTO must resolve)

1. **Onboarding step count.** `onboarding.spec.yml` line 19 says "12단계" and line 48 says "11단계". Code has 13 enum values. Canonical?
2. **CMT-001 filter tabs.** Spec mandates 3 tabs (전체 / 내가 한 / 상대가 한). Code ships 6 chips (ALL / GIVE / TAKE / DUE_TODAY / OVERDUE / DONE). Expansion approved as IMPLIED?
3. **Commitment upload batch.** `POST /v1/commitments:batch` in scope for R6, or ship BLOCKER as-is with feature flag?
4. **Source status endpoint.** SourceStatusRepository's client-derived model the intended final design, or is `GET /v1/source_status` coming?
5. **SP-27b scope.** `TODO("SP-27b")` on OutlookCal/Daum IMAP dispatch coexists with full implementations. Does SP-27b mean "wire scheduler" or "build workers"?
6. **ING-011 vs ING-006..010 redundancy.** Primary 100% arrival path + periodic backup path both ingest same events. Intentional defense-in-depth or transitional?
7. **TDY-003 chip count.** SourceStatusStrip — 6 chips (spec text) or 7 (VOICE from `SourceTypes.kt`)?

## Claude's cross-check additions (not in audit)

- **K1 violation (file size > 400 LOC):** 9 files — `MediaStoreWorker` (549), `SettingsScreen` (457), `UploadWorker` (436), `VoiceUploadWorker` (421), `GmailClient` (393), `ImapClient` (373), `GmailWorker` (371), `RawIngestionRepository` (366), `OnboardingViewModel` (364).
- **C1 violation (hard-coded Dispatchers.IO in business logic):** `MediaStoreWorker:95` — matches audit finding on `CommitmentRepositoryImpl.transitionState`; pattern likely repeats across other workers.
- **DAO quality:** `RawIngestionEventDao` transaction usage is correct (`releaseAwaitingConsentVoiceAndReturnIds`, `parkCancellablePendingVoiceAndReturnIds`). No DAO findings.
