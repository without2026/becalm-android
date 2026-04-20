# Round 6 Refactor Plan — becalm-android

> **Goal.** Close every BLOCKER / HIGH / MED / LOW surfaced in `docs/round6-audit.md` against the Big Tech rubric in `docs/big-tech-rubric.md`.
> Output = branch `refactor/android-spaghetti-sweep` reaches a state where an independent senior engineer PR review finds **zero** new findings.
> **Principle.** Each sub-step is byte-surgical, spec-first, test-preserving, and blast-radius-contained. No opportunistic cleanup.

## CTO decisions applied (from Q&A)

| # | Decision |
|---|----------|
| Q1 | Onboarding canonical = **11 steps**. Delete `SMS_PERM` / `CALL_PERM` / `NOTIF_PERM` enums. Update `onboarding.spec.yml` line 19 `'12단계'` → `'11단계'`. |
| Q2 | Add `POST /v1/commitments:batch` to `api-contract.yml`. Implement `UploadWorker.flushCommitments` against it. Client-side first; Railway impl is separate PR. |
| Q3 | Add `GET /v1/source_status` to `api-contract.yml`. Convert client to poll server truth. Client fallback on offline. |
| Q4 | SP-27b wire-up: connect existing `OutlookCalendarWorker` / `ImapDaumWorker` to `WorkSchedulerImpl`; remove TODO stubs. |
| Q5 | Collapse filter to **3 tabs** (전체 / 내가 한 / 상대가 한). Remove `DUE_TODAY` / `OVERDUE` / `DONE` chips. |
| Q6 | Keep dual-path ingestion (defense-in-depth). No code change; add inline note only if clarity demands. |
| Q7 | 6 chips in `SourceStatusStrip` (voice excluded). |

## Acceptance bar

A senior engineer at Google / Meta / Square / Netflix / Uber, using `docs/big-tech-rubric.md` A-L, would merge the PR without requesting further changes:
- **Spec alignment.** Every `.spec.yml` item has a corresponding, verifiable code path. No drift.
- **Architecture.** A-A6 pass. No layer leak, no DAO-in-VM, no entity crossing `:data`.
- **Kotlin / Coroutines / Compose.** B, C, D pass. No `!!`, no `GlobalScope`, no `collectAsState()`, no hoist-miss.
- **DI / Persistence / Work / Net.** E-H pass. No field-inject in non-Android classes, no blocking DAO, no missing `@Transaction`, no hard-coded dispatcher in business logic, no non-idempotent worker.
- **Errors / Tests / Hygiene / Security.** I-L pass. No swallowed `CancellationException`, no untested VM/worker/migration, no file > 400 LOC, no plain-text token.

Every finding in `docs/round6-audit.md` + Claude's K1 cross-check list closes. No new `TODO(...)` without a linked issue.

---

## Round sequencing — 3 rounds, strict dependency order

**Round 6A (Foundations).** Contract + primitives that everything else depends on. Nothing user-visible; all downstream rounds read these outputs. Ships first; rest of plan waits on it.

**Round 6B (BLOCKER + HIGH fixes).** Concurrent parallel streams per layer; only merges after 6A lands. This is where user-visible behavior changes.

**Round 6C (Tests + K1 file-size splits).** Final polish. Test harness plus surgical file splits for the 9 files > 400 LOC. Only touches code that 6A + 6B have stabilized — prevents merge-conflict churn.

---

## Round 6A — Foundations (serial within round)

Subagents run sequentially because Round 6B reads these outputs.

### 6A.1 — Spec-contract updates · agent: `voltagent-core-dev:api-designer`
- Edit `.spec/contracts/api-contract.yml`: add `POST /v1/commitments:batch` (mirror `/v1/raw_ingestion_events:batch` shape), add `GET /v1/source_status` returning the 6-source chip payload (Q3+Q7).
- Edit `.spec/onboarding.spec.yml` line 19: `'12단계'` → `'11단계'` (Q1 canonical).
- Edit `.spec/commitment-management.spec.yml` CMT-001 section: remove any language that could be read as permitting DUE_TODAY / OVERDUE / DONE filters (Q5).
- Acceptance: both new endpoints have request + response schema, idempotency header spec, and `spec_refs`. YAML lints; existing `data-model.yml` / `ui-map.yml` unchanged.

### 6A.2 — Core primitives: Clock, Dispatcher, Logger, CancellationException helper · agent: `kotlin-specialist`
- Confirm `core.util.Clock` abstraction exists, is `@Singleton`, is injected via Hilt (`@Provides`), and has a `FakeClock` test double.
- Confirm `core.util.CoroutineDispatchers` provides `io`, `default`, `main` as injectable `CoroutineDispatcher`s with a `@Qualifier` each.
- Add `core.util.coroutines.rethrowIfCancellation(Throwable)` utility — one-line extension for `runCatching { ... }.fold(onFailure = { e -> e.rethrowIfCancellation(); ... })` call sites to invoke once.
- Acceptance: no new runtime behavior; primitives exist and are Hilt-wired. Unit tests for `FakeClock` + `rethrowIfCancellation` pass.

### 6A.3 — Database regression fix · agent: `voltagent-infra:database-administrator`
- `BeCalmDatabase.kt:117`: delete `.fallbackToDestructiveMigrationOnDowngrade()`. KDoc lines 49-52 and 100-104 already forbid it; code just needs to match the contract.
- Acceptance: Room build succeeds; schema tests unchanged; destructive downgrade impossible.

### 6A.4 — Network layer: auth-token cache + Mutex-dedup refresh · agent: `voltagent-core-dev:backend-developer`
- Rework `DefaultAuthTokenProvider` (in `core/di/NetworkModule.kt`): in-memory `@Volatile` token cache seeded on first call, warmed on login, cleared on `invalidateSession`. Eliminate `runBlocking { sessionStore.load() }` on the hot path — hit disk once per process lifetime or on explicit invalidation.
- Rework `data/remote/interceptor/AuthInterceptor.kt`: wrap refresh in `Mutex.withLock` + in-flight `Deferred<String?>` cache so N concurrent 401s produce 1 refresh call.
- Keep `runBlocking` only at the OkHttp sync-boundary crossing; every async path stays suspend.
- Acceptance: no hot-path disk read, concurrent 401 thundering herd eliminated. Unit test: 10 concurrent requests against a 401-then-200 fake server call `refresh()` exactly once.

### 6A.5 — Wire SP-27b workers · agent: `kotlin-specialist`
- `worker/WorkSchedulerImpl.kt:159-163, 180-183, 200-204`: replace `TODO("SP-27b")` dispatch with real `enqueueUnique* / enqueuePeriodic*` calls to existing `OutlookCalendarWorker` / `ImapDaumWorker` (both already 420 / ~280 LOC and fully implemented).
- Constraints + backoff mirror `GoogleCalendarWorker` / `ImapNaverWorker` patterns.
- Acceptance: connecting Outlook Calendar / Daum IMAP in onboarding results in actual ingestion; WorkSchedulerTest covers new dispatch paths.

---

## Round 6B — BLOCKER + HIGH fixes (parallel streams; one subagent per stream)

All five streams run concurrently once 6A merges. Streams have **no mutual file overlap** — verified below.

### 6B.1 — Commitment sync path (BLOCKER×2) · agent: `voltagent-core-dev:backend-developer`
Files: `data/remote/api/RailwayApi.kt`, `data/repository/CommitmentRepositoryImpl.kt`, `worker/UploadWorker.kt`, `data/remote/dto/CommitmentBatchDto.kt` (new).
- Add Retrofit method mirroring `/v1/raw_ingestion_events:batch` for commitments.
- `CommitmentRepositoryImpl.updateActionState`: on non-2xx PATCH response or `IOException`, call `dao.markPending(id)` (or equivalent) before returning `BecalmResult.Failure`. Unit-test both success and failure paths with Turbine.
- `UploadWorker.flushCommitments` (lines 236-263): replace local-only mark-synced with `commitmentRepository.uploadBatch(pending)` that PATCHes the new batch endpoint and advances `sync_status='synced'` only on server ack. Remove the apologetic inline comment.
- Acceptance: PATCH 500 → local row demoted to `pending`; next worker cycle re-uploads. Batch test covers 1/10/100 commitments in one call.

### 6B.2 — Today screen completion (BLOCKER) · agent: `voltagent-core-dev:mobile-developer`
Files: `ui/today/TodayTimelineScreen.kt`, `ui/today/TodayViewModel.kt`, `ui/components/SourceStatusStrip.kt` (new or extend), `ui/components/OverallSyncIndicator.kt` (extend).
- Implement `SourceStatusStrip` with 6 chips (Q7) backed by `SourceStatusRepository` which now calls `GET /v1/source_status` (6A.1) with offline fallback to local `SyncCursorStore`.
- Add `PullRefreshIndicator` + `rememberPullRefreshState` wiring `onRefresh = vm::forceSyncAll` (TDY-006).
- Add text banner `OverallSyncIndicator` state machine: `idle` / `syncing N/6` / `synced HH:mm` / `일부 소스 실패` (TDY-008).
- Add catch-up gesture: tap on strip → `vm::forceCatchUp()` (TDY-009).
- Acceptance: all four TDY-003/006/008/009 specs verifiable from screen.

### 6B.3 — Person enrichment join across VMs (HIGH) · agent: `kotlin-specialist`
Files: `ui/today/TodayViewModel.kt`, `ui/commitments/CommitmentManagementViewModel.kt`, `data/repository/PersonEnrichmentRepository.kt`.
- Replace `counterpartyDisplayName = entity.counterpartyRaw?.take(MAX)` with a `combine(commitmentsFlow, enrichmentFlow) { c, e -> c.map { it.enrich(e) } }` pattern that joins on `person_ref`.
- Re-inject `core.util.Clock` (from 6A.2) in both VMs — delete `Clock.System.now()` (TodayVM:255-262) and `java.time.LocalDate.now()` (CommitmentVM:309).
- Acceptance: both VMs render enriched display name; existing VM tests pass with `FakeClock`.

### 6B.4 — Onboarding 13→11 (BLOCKER) · agent: `voltagent-core-dev:mobile-developer`
Files: `ui/onboarding/OnboardingViewModel.kt`, `ui/onboarding/OnboardingNavGraph.kt`, `ui/onboarding/OnboardingViewModelTest.kt`.
- Delete `OnboardingStep.SMS_PERM`, `CALL_PERM`, `NOTIF_PERM` (lines 33-47).
- Delete the `stepsWithoutScreen` escape hatch in `isTerminalGatePassed` (346-363); remaining 11 steps all have screens.
- Update `step.ordinal / SIZE` progress arithmetic.
- Acceptance: `OnboardingViewModelTest` updated; navigation flow from /splash to /onboarding/cold-sync is 11 screens; "N/11" shown consistently.

### 6B.5 — Misc HIGH + MED + LOW cleanups · agent: `voltagent-dev-exp:refactoring-specialist`
Files (non-overlapping with above):
- `data/repository/{AuthRepositoryImpl, CommitmentRepositoryImpl, SourceStatusRepositoryImpl, PersonEnrichmentRepositoryImpl}`: replace `runCatching {}.fold {}` with `try { ... } catch (e: CancellationException) { throw e } catch (e: IOException) { ... }` — or the 6A.2 `rethrowIfCancellation()` helper.
- `data/repository/CommitmentRepositoryImpl.transitionState`: inject `@IoDispatcher` from 6A.2; remove hard-coded `withContext(Dispatchers.IO)`.
- `data/repository/AuthRepositoryImpl.signOut` + `invalidateSession`: extract shared `runAllWipeSteps(steps: List<Step>)` skeleton so ~90% duplication collapses to a single list-of-steps + a flag.
- `domain/reminder/ReminderScheduler.kt:11`: replace `java.time.Instant` import and call sites with `kotlinx.datetime.Instant`.
- `worker/WorkSchedulerImpl.kt:70,77,87,130,161,182,202,206`: replace `android.util.Log.d/e` calls with injected `Logger`.
- `ui/commitments/CommitmentManagementScreen.kt`: remove `DUE_TODAY` / `OVERDUE` / `DONE` filter chips; 3 tabs only (Q5).
- Acceptance: file-by-file diff review, each change surgical (no drive-by refactors). All existing tests still pass.

### Round 6B overlap check

| File | 6B.1 | 6B.2 | 6B.3 | 6B.4 | 6B.5 |
|------|:----:|:----:|:----:|:----:|:----:|
| `TodayViewModel.kt` | | X | X | | |
| `TodayTimelineScreen.kt` | | X | | | |
| `CommitmentManagementViewModel.kt` | | | X | | |
| `CommitmentRepositoryImpl.kt` | X | | | | X |
| `UploadWorker.kt` | X | | | | |
| `OnboardingViewModel.kt` | | | | X | |

**Three conflicts:** `TodayViewModel` (6B.2 & 6B.3), `CommitmentRepositoryImpl` (6B.1 & 6B.5). Resolution: **6B.2 precedes 6B.3 on TodayViewModel**; **6B.1 precedes 6B.5 on CommitmentRepositoryImpl**. Both second agents rebase on the first after merge. Encoded as explicit blocking.

---

## Round 6C — Tests + K1 file-size splits (parallel streams)

### 6C.1 — Worker unit tests · agent: `voltagent-qa-sec:test-automator`
Missing: `UploadWorkerTest`, `EnrichmentWorkerTest`, `GmailWorkerTest`, `MediaStoreWorkerTest`, `ImapNaverWorkerTest`, `GoogleCalendarWorkerTest`, `OutlookMailWorkerTest`. Pattern: follow existing `VoiceUploadWorkerTest` / `OutlookCalendarWorkerTest`. Use fakes from `kotlin-specialist`-provided `rethrowIfCancellation` + `FakeClock`.

### 6C.2 — Room migration tests · agent: `voltagent-qa-sec:test-automator`
`androidTest/.../MigrationTest.kt` covering 1→2 (`commitment_state` column) and 2→3 (index drop). Use `MigrationTestHelper`.

### 6C.3 — K1 file-size splits · agent: `voltagent-dev-exp:refactoring-specialist`
Split each > 400 LOC file into a primary class + 1-2 purely-functional helper files; zero behavior change. File map:
| File | LOC | Split |
|------|-----|-------|
| `MediaStoreWorker.kt` | 549 | Extract SMS path + Voice path into `SmsMediaStoreProbe.kt` + `VoiceMediaStoreProbe.kt`; worker becomes orchestrator. |
| `SettingsScreen.kt` | 457 | Extract 3 section composables into `SettingsSourcesSection.kt`, `SettingsAccountSection.kt`, `SettingsPipaSection.kt`. |
| `UploadWorker.kt` | 436 (post-6B.1 may be smaller) | Split per-source upload bodies into `RawEventUploader.kt` + `CommitmentUploader.kt`. |
| `VoiceUploadWorker.kt` | 421 | Extract retry + consent gate into `VoiceUploadStateMachine.kt`. |
| `GmailClient.kt` | 393 | Extract `GmailHistoryParser.kt` (below 400 already — watch list only). |
| `ImapClient.kt` | 373 | Watch list only. |
| `GmailWorker.kt` | 371 | Watch list only. |
| `RawIngestionRepository.kt` | 366 | Watch list only. |
| `OnboardingViewModel.kt` | 364 (post-6B.4 ~ 300) | Likely already under after step removal; confirm. |

Only the top 4 get mandatory splits; rest are on the K1 watch list and only split if they exceed 400 after Round 6B.

---

## Senior-engineer review gate (Phase 5)

After 6A + 6B + 6C merge, run three adversarial reviews in parallel:
1. `voltagent-qa-sec:code-reviewer` — rubric A-L pass.
2. `voltagent-qa-sec:architect-reviewer` — layer / dependency-direction pass.
3. `codex:codex-rescue` — independent adversarial pass against the spec.

Round 6 exits only when all three return **zero new findings**. New findings → loop back to an appropriate 6B / 6C slot.

---

## Build-and-test gates per round

After each round, and before declaring the round merge-ready:
- `./gradlew :app:assembleDebug` passes.
- `./gradlew :app:testDebugUnitTest` passes.
- `./gradlew :app:lintDebug` passes.
- Detekt + ktlint clean (rubric K items).

If any gate fails, patch within the same round before advancing.

---

## Risks + mitigations

| Risk | Mitigation |
|------|-----------|
| `POST /v1/commitments:batch` backend not ready | Ship client + contract; 202 fallback path gracefully no-ops; flag for QA. |
| `GET /v1/source_status` backend not ready | Same — client fallback to local `SyncCursorStore` keeps Today screen functional. |
| Round 6B merge conflict on `TodayViewModel` / `CommitmentRepositoryImpl` | Explicit ordering (6B.2 → 6B.3, 6B.1 → 6B.5). Rebase, don't merge. |
| K1 file splits cause hidden behavior drift | Mandate byte-identical function bodies; any behavior change rolls back to single-file form. |
| Detekt tightening blocks existing suppressions | Audit suppressions once; document or fix. No new `@Suppress` added by R6. |

---

## Open items (non-blocking — tracked for Round 7 if they surface)

- Railway backend PRs for `POST /v1/commitments:batch` + `GET /v1/source_status` (out of Android scope; spec contract in place).
- Samsung sleeping-apps dashboard (monitoring dashboard for `periodic_sync_completion_rate ≥ 80%` per data-model.yml migration_notes).
