# Codex R1–R10 Refactor Tracker

Source: 10 parallel spec-anchored Codex reviews dispatched 2026-04-16 against `feat/becalm-mvp` HEAD (commit `3928c48`, PR #2).

Marking convention:
- `[ ]` open  ·  `[x]` resolved (+ commit SHA)  ·  `[~]` deferred (link CONSTRAINTS.md)  ·  `[!]` false positive (+ justification)

Spec-match convention:
- **SPEC** — direct clause in `.spec/<module>.spec.yml`
- **CONTRACT** — clause in `.spec/contracts/{api-contract,ui-map,data-model}.yml`
- **IMPLIED** — spec behavior requires the fix but no explicit clause
- **NONE** — no spec anchor; engineering/hygiene concern requiring CTO confirm

---

## P0 — runtime crashes / data-loss / PIPA

- [ ] **R1-01** `AuthInterceptor.kt:66-70` returns consumed 401 `Response`. — SPEC `auth.spec.yml:62-69` (AUTH-007 refresh+retry requires working response chain).
- [ ] **R2-01** `BeCalmDatabase.kt:107-115` `fallbackToDestructiveMigrationOnDowngrade()` enabled. — **NONE** (see CTO-confirm list).
- [!] **R4-01** `VoiceTranscriptionWorker.kt:103-145` raw audio never deleted after STT. — **FALSE POSITIVE**: verified 2026-04-16. Worker only passes MediaStore URI string to `sttBackend.transcribeAudio(uri.toString())` — no file copy, no temp buffer in app storage. Original file lives in Samsung Voice Recorder folder (user-owned), SAF grant is read-only; deleting would destroy user's own recording. Both STT backends are currently stubs (`AndroidSpeechRecognizerBackend` disabled; `WhisperJni` native lib unlinked) so STT doesn't actually complete today. Pipeline redesign tracked separately.
- [ ] **R4-02** All ingestion workers — no MAX_RETRIES guard. — IMPLIED `data-ingestion.spec.yml:33-40` (ING-003 quarantine path; no explicit cap).
- [ ] **R6-01** `PersonDetailViewModel.kt:125` `checkNotNull(savedStateHandle[ARG_PERSON_REF])`. — IMPLIED (ui-map.yml:106 path param required; invariants prefer "크래시 없음" e.g. TDY-002, SRC-007).
- [ ] **R6-02** `RawEventDetailViewModel.kt:51` same `checkNotNull` crash. — IMPLIED (ui-map.yml:113 path param required; same crash-safety invariants).
- [ ] **R8-01** `BecalmNavHost.kt:184-190` vs `SourceDetailViewModel.kt:23-25,95` — VM reads `sourceType`, should read `source_id`. — CONTRACT `ui-map.yml:150`.
- [ ] **R8-02** `BecalmNavHost.kt:153-163` vs `RawEventDetailViewModel.kt:34,51-53` — VM reads `eventId`, should read `event_id`. — CONTRACT `ui-map.yml:113`.

## P1 — spec violations / PII

- [ ] **R1-02** `IngestionDtos.kt:14-16` serializes `user_id` into `:batch` body. — CONTRACT `api-contract.yml:58-69` (body schema has no user_id; Bearer-derived).
- [ ] **R3-01** `CommitmentRepository.kt:320` `updateActionState()` bypasses state machine. — SPEC `commitment-management.spec.yml:47-75` (CMT-005/6/7) + CONTRACT `data-model.yml:132-137` enum.
- [ ] **R5-01** `CommitmentStateMachine.kt:65` allows `DONE → CONFIRMED`. — IMPLIED `commitment-management.spec.yml:67-75` (CMT-007 implies `completed` terminal; not explicit).
- [ ] **R5-02** `GeminiNanoExtractor.kt:57-61` non-functional stub. — SPEC `voice-pipeline.spec.yml:15-31` (VOI-002/003).
- [ ] **R5-03** `SttBackendSelector.kt:39-58` no device-capability check. — **NONE** (VOI-001 mentions two backends but no selection rule; see CTO-confirm list).
- [ ] **R6-03** `AuthUiState.SignedIn.email` stores raw email. — **NONE** (see CTO-confirm list).
- [ ] **R6-04** `TodayViewModel.kt:38` raw entities in UiState. — IMPLIED `today-timeline.spec.yml:7-14` (TDY-001 "person_ref 기반 표시명 사용") + `data-model.yml:119-120` (quote "legally sensitive, evidentiary"). Not an explicit "no raw in UiState" clause.
- [ ] **R6-05** `PersonRow.personRef` raw phone/email. — IMPLIED `source-viewer.spec.yml:7-14` (SRC-001 enrichment-joined display) + `person-enrichment.spec.yml:56-63` (ENR-006 fallback).
- [ ] **R6-06** `PersonDetailViewModel:86` raw personRef + full enrichment entity. — IMPLIED (same as R6-05).
- [ ] **R6-07** `RawEventDetailViewModel:26` raw personRef + eventSnippet. — **NONE for eventSnippet** (SRC-004 requires snippet display); personRef IMPLIED (same as R6-05). See CTO-confirm list.
- [ ] **R6-08** `CommitmentManagementViewModel:57` raw counterparty/personRef/quote. — IMPLIED (same as R6-04).
- [ ] **R6-09** `SettingsViewModel:23` raw userEmail. — **NONE** (see CTO-confirm list).
- [ ] **R6-10** `AuthViewModel:90` emits `result.error.toString()`. — SPEC `auth.spec.yml:18-23` (AUTH-002 specific text "이메일 또는 비밀번호가 올바르지 않습니다").
- [ ] **R6-11** `OnboardingViewModel:160` marks `onboarding_completed=true` immediately. — SPEC `onboarding.spec.yml:85-92` (ONB-008).
- [ ] **R6-12** `TodayViewModel:81` no aggregate sync states. — SPEC `today-timeline.spec.yml:75-83` (TDY-008).
- [ ] **R6-13** `PersonsViewModel:156` hardcodes lastInteractionAt/interactionCount, no pagination. — SPEC `source-viewer.spec.yml:7-14` (SRC-001).
- [ ] **R6-14** `PersonDetailViewModel:81` flat list, spec wants 3 sections. — SPEC `source-viewer.spec.yml:76-83` (SRC-008).
- [ ] **R6-15** `RawEventDetailViewModel:21` raw entity only. — SPEC `source-viewer.spec.yml:37-44` (SRC-004).
- [ ] **R6-16** `CommitmentManagementViewModel:179` wrong state model. — SPEC `commitment-management.spec.yml:47-75` (CMT-005/6/7) + CONTRACT `data-model.yml:132-137`.
- [ ] **R6-17** `SettingsViewModel:155` onSignOut and onWipeLocalData identical. — SPEC `auth.spec.yml:43-50` (AUTH-005 + invariant "로그아웃 시 Room DB 데이터는 삭제하지 않는다").
- [ ] **R6-18** `SourcesListViewModel:69` missing contacts pseudo-source. — SPEC `source-management.spec.yml:10-18` (SMG-001) + `person-enrichment.spec.yml:74-82` (ENR-008).
- [ ] **R6-19** `SourceDetailViewModel:121` read-only, missing disconnect + manual-sync. — SPEC `source-management.spec.yml:20-58` (SMG-002/4/5).
- [~] **R7-01** `BecalmTextField:66` no semantic fallback → TalkBack unlabeled. — **DEFERRED** by CTO 2026-04-16. Tracked as CONSTRAINTS.md BECALM-ANDROID-A11Y-001 (a11y pass out of MVP scope). Revisit before Play Store public release.
- [~] **R7-02** `CommitmentCard:134` raw title into semantics. — **DEFERRED** by CTO 2026-04-16. Tracked as CONSTRAINTS.md BECALM-ANDROID-A11Y-002 (PII-redacted screen-reader semantics). Revisit before Play Store public release.
- [ ] **R7-03** `CommitmentCard:206` renders counterparty verbatim. — IMPLIED CONTRACT `ui-map.yml:187-191` (CommitmentCard "person_ref 기반 표시명").
- [ ] **R8-03** `LoginScreen:91-93` never calls `onErrorDismissed()`. — **NONE** (see CTO-confirm list).
- [ ] **R8-04** `PersonsScreen`/`PersonDetailScreen` early-dismiss race. — **NONE** (see CTO-confirm list).
- [ ] **R9-01** `CommitmentStateMachineTest` missing spec action_state transitions. — SPEC `commitment-management.spec.yml:47-75` (CMT-005/6/7).
- [ ] **R9-02** Typed error-path coverage gaps across 8 VM tests. — **NONE** (see CTO-confirm list).
- [ ] **R9-03** No error-path tests: `OnboardingVMTest`, `SourcesListVMTest`, `ColdSyncVMTest`. — **NONE** (see CTO-confirm list).
- [ ] **R10-01** `AndroidManifest.xml` declares permissions not in onboarding spec. — IMPLIED (onboarding covers consent; extras like INTERNET/FOREGROUND_SERVICE/POST_NOTIFICATIONS justified by ING-002/CMT-008 etc; RECORD_AUDIO needs CTO confirm — possibly unused). See CTO-confirm list for unused-perm removal.
- [ ] **R10-02** `POST_NOTIFICATIONS` no runtime request site. — IMPLIED `commitment-management.spec.yml:77-84` (CMT-008 requires push notifications; API 33+ runtime request implicit).
- [ ] **R10-03** `SplashScreen` no `onboarding_completed` gate. — SPEC `onboarding.spec.yml:66-92` (ONB-006/008).

## P2 — schema / spec divergence

- [ ] **R2-02** `RawIngestionEventEntity:29-50` extra UNIQUE + missing FK. — CONTRACT `data-model.yml:76-84, 251-254`.
- [ ] **R2-03** `CommitmentEntity:60-127` missing FK. — CONTRACT `data-model.yml:160-169, 256-259`.
- [ ] **R2-04** `CalendarEventEntity:27-33` extra UNIQUE + missing FK. — CONTRACT `data-model.yml:206-209, 261-264`.
- [ ] **R2-05** `PersonEnrichmentEntity:40-49` redundant UNIQUE on PK. — IMPLIED `data-model.yml:246-248` (spec shows btree only).
- [ ] **R2-06** DAO `SELECT *` pervasive. — **NONE** (see CTO-confirm list).
- [ ] **R3-02** `BecalmError.Unknown(Throwable)` leaks throwable. — **NONE** (see CTO-confirm list).
- [ ] **R3-03** `CalendarEventRepository:161,164` `observeById` fake Flow. — **NONE** (see CTO-confirm list).

## P3 — hygiene

- [ ] **R1-03** `Routes.kt` strips leading `/`. — CONTRACT `ui-map.yml:11,18,24,...` (all routes leading `/`).
- [ ] **R6-20** Racy `_uiState.value = _uiState.value.copy(...)`. — **NONE** (see CTO-confirm list).
- [ ] **R6-21** No terminal `.catch` on flow chains. — **NONE** (see CTO-confirm list).
- [ ] **R7-04** `BecalmScaffold:130` single preview. — **NONE** (see CTO-confirm list).
- [ ] **R8-05** Missing `key` lambdas. — **NONE** (see CTO-confirm list).

---

## Spec-match totals

| Anchor | Count |
|---|---|
| SPEC (direct clause) | 17 |
| CONTRACT (api/ui/data-model) | 8 |
| IMPLIED (behavior requires fix) | 9 |
| NONE (pre-CTO) | 19 |
| **Total** | **53** |

## Resolution status (CTO decisions 2026-04-16)

| Status | Count | Items |
|---|---|---|
| Accepted (to fix) | 50 | all except below |
| Deferred → tech-debt | 2 | R7-01, R7-02 (a11y, CONSTRAINTS BECALM-ANDROID-A11Y-001/002) |
| False positive | 1 | R4-01 (verified — worker does not create temp files; audio file is user-owned Samsung asset) |
| **Total** | **53** | |

Note: STT pipeline itself is non-functional today (both backends stubbed). Gemini-based redesign tracked as separate Layer 0 cycle — not part of this refactor batch.

---

## False positives / clean (unchanged)
(see earlier section of tracker history)
