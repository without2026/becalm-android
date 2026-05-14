# Parallel Implementation Wave Plan

**Purpose**: 60 plan docs 를 파일 의존성 + `Blocked by` / `Blocks` 체인 + hot-file 충돌 매트릭스를 동시에 만족하는 **구현 wave** 로 배열한다. 각 wave 안에서는 병렬 세션이 충돌 없이 실행 가능하고, wave 간에는 반드시 선행 wave 전부 머지 후 다음 wave 시작.

**Authoring context**: 2026-04-20 스냅샷. traceability-audit.md Part 9 의 Open Finding PRs (#12~#24) + 이 세션에서 seed 된 30개 Stage 5-8 plan docs 모두 포함.

**Linearize invariant (CRITICAL)**: Room schema version 은 단일 counter. 어떤 wave 에서도 `BeCalmDatabase.kt` `DATABASE_VERSION` bump + `Migrations.kt` 에 `MIGRATION_N_{N+1}` 추가는 **정확히 한 PR 씩 순차** 머지. 같은 wave 안에 migration 두 개 들어가면 한쪽이 반드시 블록.

---

## Fresh Session Onboarding (ZERO CONTEXT)

**전제**: 너는 이 프로젝트에 대해 **아무것도 모른다**. CLAUDE.md 도 spec 도 코드도 본 적 없다. 아래 순서를 엄수.

### Phase A — Orient (반드시 read, 10분)

파일을 **이 순서대로** Read. 먼저 읽으면 뒤의 파일이 이해된다:

1. `/home/jakek/without/CLAUDE.md` — AI CTO 운영 원칙, 7-Phase pipeline, 브랜치 네이밍, Engineering Philosophy
2. `/home/jakek/without/docs/ONTOLOGY.md` — 무엇을 누구를 위해 만드는가
3. `/home/jakek/without/docs/GOALS.md` — 지금 무엇을 해야 하는가, 우선순위
4. `/home/jakek/without/docs/CONSTRAINTS.md` — 무엇을 하면 안 되는가
5. `/home/jakek/without/becalm-android/docs/traceability-audit.md` — DB ↔ API ↔ UI 전수 감사. 특히 **Part 1 (ER diagram)**, **Part 4 (E2E flow)**, **Part 9 (Open PR Index)**
6. `/home/jakek/without/becalm-android/docs/plans/_template.md` — plan doc 표준 구조
7. `/home/jakek/without/becalm-android/docs/plans/_wave-plan.md` — **이 문서**. 본인 wave 위치 + 병렬 구조 파악

### Phase B — Spec 파일 (해당 wave 의 모듈만)

각 wave 에 해당하는 `.spec/*.yml` 파일을 Read. spec 은 **MUST**-language contract, 코드가 따라야 할 진실.

| Wave | 읽어야 할 spec |
|---|---|
| W0 | `.spec/contracts/data-model.yml`, `.spec/voice-pipeline.spec.yml`, `.spec/data-ingestion.spec.yml` |
| W1 | `.spec/contracts/data-model.yml` (엔티티 6개 전수), `.spec/commitment-edit.spec.yml`, `.spec/email-pipeline.spec.yml` |
| W2 | `.spec/contracts/api-contract.yml`, `.spec/auth.spec.yml`, `.spec/email-pipeline.spec.yml`, `.spec/backend-sync.spec.yml` |
| W3 | `.spec/email-pipeline.spec.yml`, `.spec/cold-sync.spec.yml`, `.spec/data-ingestion.spec.yml` |
| W4 | `.spec/commitment-management.spec.yml`, `.spec/commitment-edit.spec.yml`, `.spec/manual-commitment.spec.yml` |
| W5 | `.spec/today-timeline.spec.yml`, `.spec/person-enrichment.spec.yml`, `.spec/source-viewer.spec.yml` |
| W6 | `.spec/auth.spec.yml`, `.spec/onboarding.spec.yml`, `.spec/contracts/ui-map.yml` |
| W7 | `.spec/pipa-rights.spec.yml`, `.spec/source-management.spec.yml` |
| W8 | `.spec/source-management.spec.yml`, `.spec/error-states.spec.yml` |

### Phase C — E2E Verification 문서 (해당 모듈)

`/home/jakek/without/becalm-android/docs/e2e-verification/` 아래에 모듈별 **코드 wiring 다이어그램 + 파일:심볼 매핑** 이 이미 정리돼 있다. 존재하는 파일:
- `01-auth.md` — AUTH-001..008 파일 wiring
- `02-onboarding.md` — ONB-001..008 + ONB-PIPA
- `03-today-timeline.md` — TDY-001..010
- `04-voice-pipeline.md` — VOI-001..008
- `05-data-ingestion.md` — ING-001..015
- `06-backend-sync.md` — SYNC-001..006
- `07-commitment-management.md` — CMT-001..013
- `08-person-enrichment.md` — ENR-001..008
- `09-source-management.md` — SMG-001..005
- `10-source-viewer.md` — SRC-001..008

본인 wave 의 모듈에 해당하는 e2e-verification 문서를 Read 하면 **어떤 파일이 어떤 behavior 에 연결돼 있는지 grep 없이 파악** 가능. 포맷은 아래 AUTH-001 예시처럼 `단계 | 파일 | 심볼` 3-column table.

```
| 단계 | 파일 | 심볼 |
| UI | ui/auth/LoginScreen.kt | form submit → AuthViewModel.onEmailSignIn |
| VM | ui/auth/AuthViewModel.kt:94 | onEmailSignIn(email, password) |
| Repo | data/repository/AuthRepository.kt:142 | AuthRepositoryImpl.signInWithEmail |
| Remote | data/remote/supabase/SupabaseAuthClient.kt:124 | signInWithEmail |
| Persist | data/remote/supabase/SupabaseSessionStore.kt | save(session) |
```

### Phase D — 본인 Plan Docs

마지막으로 wave 의 plan doc 파일 Read. 읽는 순서:

1. **본인 담당 plan doc** (`docs/plans/<layer>-<module>-<logic>.md`) — Section 1 (Finding), 2 (Spec Contract), 3 (Code Reality), 5 (Proposed Fix), 6 (Acceptance Criteria), 8 (Dependencies) 순
2. **Section 8 Blocked-by 에 적힌 모든 plan doc** — 전부 Read. 본인 작업이 기반하는 선행 PR 의 내용 숙지
3. **같은 wave 의 다른 병렬 plan doc** — Section 5 Files to change 목록만 훑어서 **hot-file 충돌 예상 지점 미리 파악**
4. **같은 umbrella branch (예: PR #22, `feat/ui/onboarding`, PIPA umbrella) 의 기존 커밋된 plan doc** — 읽어야 함 (이미 머지된 로직과 충돌 방지)

### Phase E — 코드 확인 (작업 시작 직전)

1. `git status` / `git branch --show-current` — 현재 브랜치 확인
2. `git log origin/main..HEAD` — 본인 브랜치에 뭐가 쌓여있나
3. Plan doc Section 3 (Code Reality) 에 인용된 파일/라인 을 Read — **코드의 현 상태가 plan 작성 시점과 같은지** 검증
4. 다르면 → CTO 에게 보고, plan doc 업데이트 먼저

### 필수 grep 습관

코드 변경 전:
```bash
# 본인 모듈의 spec ID 가 코드에 이미 참조되는지
grep -rn "CMT-005\|EDIT-003" android/app/src/main/

# 본인이 만들 심볼 이름이 이미 존재하는지 (dead code 충돌 방지)
grep -rn "CommitmentEditSheet" android/app/src/main/
```

---

## Execution Model — Strict Sequential Wave Gate

**규칙**: wave 는 **순차**. overlap 없음. 직전 wave 의 **모든 plan** 이 (1) 머지 (2) plan 파일에 완료 마킹 (3) `codex:review` approve 이 세 조건을 **전부 충족** 한 뒤에만 다음 wave 시작.

### Plan 파일 상태 전이

각 plan doc 의 header `**Status**:` 필드를 단계별로 업데이트:

| 단계 | Status 값 | 전이 조건 |
|---|---|---|
| 0 | `PLAN ONLY` | 초기 seed 상태 (현재) |
| 1 | `IN PROGRESS — <session-id or worktree path>` | 구현 세션 시작 시 |
| 2 | `IMPLEMENTED — PR #N` | PR 오픈 시, PR 번호 기록 |
| 3 | `MERGED — <sha>` | main 머지 시, commit sha 기록 |
| 4 | `COMPLETED — codex-review approved <date>` | codex:review approve 후 |

추가로 각 plan 의 Section 6 (Acceptance Criteria) 체크박스를 `[ ]` → `[x]` 로 채움. grep/test 증거를 PR description 에 링크.

### Wave 완료 게이트 (다음 wave 시작 전 MUST)

다음 3개를 **전부** 만족해야 wave N+1 착수:

1. **All merged**: wave N 에 속한 모든 plan 의 Status = `MERGED` 이상
2. **All marked**: 각 plan 파일에 완료 마킹 commit push (docs-only PR 로 일괄 또는 구현 PR 에 포함)
3. **codex:review approve**: wave N 의 refactor/구현 결과물 전체를 `codex:review` 로 review — **approve 받아야 pass**
   - 실행: `claude /codex:review <wave-scope-PRs>` 또는 CTO 가 wave 머지 완료 후 단일 review 요청
   - reject 나오면 → plan 재개 또는 hotfix plan doc 추가
   - dual-use (codex:rescue 병행 가능)

### Wave 완료 체크리스트 (세션이 wave 종료 전 확인)

```
wave N 종료 조건:
[ ] 이 wave 의 plan doc N 개 모두 header Status=MERGED
[ ] 각 plan 의 Acceptance Criteria 체크박스 전부 [x]
[ ] 이 wave 가 건드린 hot-file 충돌 레지스트리 업데이트
[ ] docs/traceability-audit.md Part 9 "Closed / Merged Finding PRs" 섹션에 이동
[ ] codex:review wave-scope PR 리뷰 approve 획득
[ ] CTO 구두 confirm (축약 가능한 wave 면 생략)
```

### 위반 시 대응

- wave N 의 일부 plan 이 COMPLETED 아닌 상태로 wave N+1 착수 → **즉시 중단**. rebase conflict / schema chain breakage / codex:review reject loop 발생 확률 급상승
- codex:review 에서 **reject** → plan doc 에 `Status: REJECTED — <reason>` 추가 + 후속 hotfix plan 을 같은 wave 에 추가 (다음 wave 로 deferred 금지)

---

## Hot-file Conflict Registry

같은 wave 안에 동일 파일을 수정하는 plan 이 두 개 이상 들어가면 linear stack 으로 운영 (같은 브랜치) 또는 순차 머지. 핵심 hot file:

| 파일 | 수정하는 plan (본 감사 범위 내) | 정책 |
|---|---|---|
| `BeCalmDatabase.kt` | db-auth-user-scoped, #17, #20, db-calendar, db-email | **linear**. 같은 wave 불가 |
| `Migrations.kt` | #17, #20, db-calendar, db-email | **linear** |
| `SourceTypes.kt` | #12 (PR #12), #21 (PR #21) | #12 → #21 순차 |
| `AndroidManifest.xml` | repo-auth-msgraph, ui-auth-google, ui-onboarding-notifications-crashlytics, ui-onboarding-outlook-oauth, worker-sms-remove-dead-code | 동일 wave 여러 rebase 필요; 가능하면 다른 wave |
| `WorkSchedulerImpl.kt` | domain-email-extractor, worker-sync-foreground-upload-trigger, worker-person-enrichment-periodic-observer, worker-retention-sweep, worker-commitment-overdue-sweep, worker-voice-retry-after-honor | 동일 wave 가능하지만 rebase 부담; 2-3개씩 sub-wave |
| `BecalmApp.kt` | worker-retention-sweep, worker-coldsync-orchestrator, ui-error-global-banners, ui-onboarding-notifications-crashlytics | rebase 순차 |
| `BecalmNavHost.kt` | domain-auth-signout, ui-commitment-detail-sheet, ui-settings-privacy-management + 5 sub, ui-onboarding-pipa-email-consent, ui-onboarding-notifications-crashlytics, ui-error-global-banners | umbrella 로 합쳐서 linear |
| `Routes.kt` | ui-commitment-detail-sheet, ui-settings-privacy-management, ui-onboarding-pipa-email-consent, ui-onboarding-notifications-crashlytics, ui-error-global-banners | umbrella linear |
| `CommitmentDao.kt` | #17, #20, worker-commitment-overdue-sweep, ui-today-since-kst, ui-commitment-edit-sheet, ui-commitment-cancel-action, ui-person-cards-detail-render | 대부분 DB wave 완료 후 접근 |
| `CommitmentRepository.kt` | ui-commitment-edit-sheet, ui-commitment-manual-sheet, ui-commitment-action-state-alignment, ui-today-since-kst, ui-commitment-pull-refresh, ui-person-cards-detail-render | umbrella #22 안에서 linear |
| `CommitmentCard.kt` | ui-commitment-dn-badge-kst, ui-commitment-action-state-alignment, ui-commitment-manual-sheet, ui-commitment-completed-section-undo | umbrella #22 linear |
| `CommitmentManagementScreen.kt` / `ViewModel.kt` | 거의 모든 ui-commitment-* plan | umbrella #22 linear |
| `OnboardingViewModel.kt` | ui-onboarding-* 5개 + worker-person-enrichment | umbrella 또는 rebase chain |
| `SettingsScreen.kt` | ui-settings-privacy-management, domain-auth-signout-preserve-data | 순차 |
| `strings.xml` | 10+ plans | 거의 모든 feature PR — merge conflict 는 trivial textual, 신경쓰지 않음 |

---

## Wave Overview

**동시 병렬 세션 수 권장: 4-5개**. 그 이상은 rebase overhead > 병렬 이득. Hot-file 이 wave 안에 모이면 2-3개 linear substack 으로 줄임.

| Wave | 목표 | 병렬 가능 plan 수 | 예상 소요 | 블로커 해제 |
|---|---|---|---|---|
| **W0** | 독립 tiny + DB foundation | 6 | 1-2일 | 모든 downstream |
| **W1** | DB layer 완성 + 음성 pipeline 마무리 | 5 | 2일 | UI/worker chain |
| **W2** | Worker / Repo layer (auth providers, email domain) | 7 | 3일 | email workers, UI sources |
| **W3** | Email adapter workers + ColdSync | 4 | 2일 | TDY-010 E2E |
| **W4** | UI Commitment umbrella (PR #22) — 단일 sequential branch | 1 세션 × 8 commit | 3-4일 | CMT/EDIT/MAN 전수 |
| **W5** | UI Today / Person / RawEvent | 4 | 2일 | Stage 6 / 8 UI |
| **W6** | UI Auth / Onboarding | 7 | 2-3일 | 온보딩 완결 |
| **W7** | UI Settings PIPA umbrella | 1 세션 × 6 commit | 3일 | 법규 MVP |
| **W8** | UI Sources / Error banners | 4 | 2일 | UX polish |

**Critical path** (P0/법규): W0 → W1 → W4 (commitment edit) + W6 (auth signout) + W7 (PIPA)

---

## Wave 0 — Independent Tiny + DB Foundation

**목표**: 가장 많은 downstream 을 풀어주는 독립 PR 먼저 soak. 전부 블로커 없음.

### 병렬 세션 배분 (5 세션 동시)

| 세션 | Plan | Branch | 비고 |
|---|---|---|---|
| S0-A | `db-voice-call-recording-enum` (#12) | `feat/db/voice/call-recording-enum` | SourceTypes.kt — W0 내에서 #21 보다 먼저 |
| S0-B | `worker-sms-remove-dead-code` (#13) | `refactor/worker/sms/remove-dead-code` | MediaStoreWorker.kt 정리 — #14 선행 |
| S0-C | `worker-voice-pipa-insert-status` (#18) | `fix/worker/voice/pipa-insert-status` | 1 파일 tiny |
| S0-D | `worker-voice-retry-after-honor` (#19) | `fix/worker/voice/retry-after-honor` | VoiceUploadWorker 독립 |
| S0-E | `db-commitment-due-at-hint-approximate` (#17) | `feat/db/commitment/due-at-hint-approximate` | Room 3→4 migration. 가장 큰 PR. 세션 하나 전담 |

**Merge 순서 (linear, hot-file 때문)**:
1. #13 (sms dead-code) — MediaStoreWorker 정리 먼저
2. #12 (source-type enum) — SourceTypes.kt 변경
3. #18, #19 (병렬 가능 — 파일 겹침 없음)
4. #17 (Room migration) — linear migration chain 시작

**블로커 해제 효과**: #17 merge 직후 → W1 의 #20, worker-commitment-overdue-sweep, ui-today-since-kst, ui-commitment-reminder-due-gate 전부 unblock.

---

## Wave 1 — DB Layer 완성 + 음성 마무리

**전제**: W0 전부 머지.

### 병렬 세션 배분 (5 세션)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S1-A | `db-commitment-edit-delete-dispute-supersede` (#20) | `feat/db/commitment/edit-delete-dispute-supersede` | #17 |
| S1-B | `db-email-schema` | `feat/db/email` | none (#17 와 파일 겹침 있음 — #17 merge 후 rebase) |
| S1-C | `repo-commitment-source-type-manual` (#21) | `feat/repo/commitment/source-type-manual` | #12 (W0 완료) |
| S1-D | `worker-voice-ingestion-realign` (#14) | `refactor/worker/voice/ingestion-realign` | #13 (W0 완료) |
| S1-E | `repo-voice-commitment-source-type-inherit` (#16) | `fix/repo/voice/commitment-source-type-inherit` | #12 |

**Merge 순서**:
1. #20 (Room 4→5 migration) — 반드시 단독
2. #21 (source-type MANUAL) — SourceTypes.kt rebase
3. db-email-schema (Room 5→6) — 단독
4. #14, #16 병렬 — 파일 겹침 없음

**블로커 해제**: #20 → 전체 ui-commitment-edit/manual/cancel/undo, ui-person-cards. db-email → repo-email-body, email workers.

---

## Wave 2 — Worker / Repo / Domain Layer

**전제**: W0, W1 전부 머지.

### 병렬 세션 배분 (7 세션)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S2-A | `repo-email-body` | `feat/repo/email` | db-email (W1) |
| S2-B | `domain-email-snippet-builder` | `feat/domain/email` (commit 1) | none |
| S2-C | `repo-auth-gmail-oauth-provider` | `feat/repo/auth` | none (AuthModule.kt base) |
| S2-D | `repo-imap-per-provider-credentials` | `fix/repo/imap` | none |
| S2-E | `worker-voice-call-recording` (#15) | `feat/worker/voice/call-recording` | #12 + #14 (W0+W1) |
| S2-F | `worker-commitment-overdue-sweep` | `feat/worker/commitment/overdue-sweep` | #17 |
| S2-G | `worker-sync-foreground-upload-trigger` | `fix/worker/sync/foreground-upload-trigger` | none — tiny 1-line fix |

**Merge 순서 & 병렬**:
- S2-A → S2-B 순차 (Gemini Nano 통합 부분에서 공유)
- S2-C → **S2-C' (`repo-auth-msgraph-oauth-provider`) 뒤따름** (AuthModule.kt 공유). msgraph 를 W2 마지막 혹은 W3 시작으로 이동.
- S2-D, S2-E, S2-F, S2-G 전부 병렬 (파일 겹침 없음)

**WorkSchedulerImpl.kt 충돌**: S2-F (overdue), S2-G (foreground-upload), S2-H (domain-email-extractor) — W2 안에서 3회 rebase 필요. 순차로 합치거나 마지막에 몰아서 rebase.

---

## Wave 3 — Email Adapter Workers + ColdSync

**전제**: W2 전부 머지 (특히 snippet-builder, repo-email-body, auth providers).

### 병렬 세션 배분 (4 세션)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S3-A | `domain-email-commitment-extractor` | `feat/domain/email` (commit 2) | snippet-builder, repo-email-body |
| S3-B | `worker-sync-cursor-invalidation` | `feat/worker/sync/cursor-invalidation` | none |
| S3-C | `worker-sync-quarantine-chunk-split` | `fix/worker/sync/quarantine-chunk-split` | none |
| S3-D | `repo-auth-msgraph-oauth-provider` | `feat/repo/auth` (follow-up commit) | gmail-oauth |

**S3-A 완료 후 sub-wave W3.5**:
- `worker-gmail-scope-body-and-headers` (blocked by 5+ items)
- `worker-outlook-mail-scope-body-and-headers` (blocked by msgraph)
- `worker-imap-scope-body-and-bounds` (blocked by imap creds)
- `worker-retention-sweep-30-day` (blocked by db-email + repo-email-body)
- `worker-coldsync-orchestrator` (blocked by cursor-invalidation + foreground-upload-trigger)
- `worker-person-enrichment-periodic-observer` (독립, W2/W3 어디든 가능)

이 6개는 전부 파일 겹침 거의 없음 → **완전 병렬 6 세션** 가능.

---

## Wave 4 — UI Commitment Umbrella (PR #22)

**전제**: W1 의 #20, #21 머지.

**Umbrella 운영**: `feat/ui/commitment` 단일 브랜치에 commit 누적 — 1 세션만 작업. 병렬 불가.

### 단일 세션 commit 순서

| # | logic slug | 선행 | 비고 |
|---|---|---|---|
| 1 | `ui-commitment-dn-badge-kst` | none | 가볍고 독립, 먼저 land |
| 2 | `ui-commitment-action-state-alignment` | none | FSM 제거, repository 정리 포함 |
| 3 | `ui-commitment-pull-refresh` | none | 1-2 파일 |
| 4 | `ui-commitment-detail-sheet` | none (PR#20 optional) | host composable. 모든 action entry point |
| 5 | `ui-commitment-reminder-due-gate` | #17, detail-sheet | 알림 채널 변경 |
| 6 | `ui-commitment-completed-section-undo` | detail-sheet | 섹션 + undo snackbar |
| 7 | `ui-commitment-cancel-action` | detail-sheet, completed-section-undo | undo 재사용 |
| 8 | `ui-commitment-edit-sheet` | #20, detail-sheet | EDIT-001..008 전수 |
| 9 | `ui-commitment-manual-sheet` | #20, #21, detail-sheet | FAB + supersede sheet 재사용 |

**예상**: 3-4일 단일 세션 연속 작업. CTO 가 umbrella PR #22 최종 1회 리뷰.

---

## Wave 5 — UI Today / Person / RawEvent

**전제**: W1 의 db-calendar-status-recurring 없음 — **블록**! db-calendar 는 어디에 들어가나?

→ **수정**: `db-calendar-status-recurring` 를 **W1 에 포함**. Wave 1 세션 6 추가:

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S1-F | `db-calendar-status-recurring` | `feat/db/calendar/status-recurring` | #17, #20 |

Merge 순서: #17 → #20 → db-calendar → db-email (W1 최종 Migration chain).

### W5 병렬 세션 배분 (4 세션)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S5-A | `ui-today-counterparty-direction` | `feat/ui/today` | db-calendar (W1) |
| S5-B | `ui-today-since-kst` | `fix/ui/today/since-kst` | #17 |
| S5-C | `ui-person-cards-detail-render` | `feat/ui/person` | #20, worker-person-enrichment (W3.5) |
| S5-D | `ui-raw-event-email-rendering` | `feat/ui/raw-event` | db-email, repo-email-body |

**병렬 가능**: S5-A, S5-B 는 같은 `TodayViewModel.kt` 건드림 → 순차 필요. S5-C, S5-D 독립.

---

## Wave 6 — UI Auth / Onboarding

**전제**: W0-W3 완료.

### 병렬 세션 배분 (7 세션 병렬 가능)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S6-A | `db-auth-user-scoped-database` | `feat/db/auth/user-scoped-database` | **P0** — 실제로 W0 / W1 에 넣어야 함 (아래 "P0 재배치" 참조) |
| S6-B | `domain-auth-signout-preserve-data` | `refactor/domain/auth/signout-preserve-data` | db-auth-user-scoped |
| S6-C | `ui-auth-google-signin-wiring` | `feat/ui/auth/google-signin-wiring` | none |
| S6-D | `ui-onboarding-pipa-email-consent` | `feat/ui/onboarding` | none |
| S6-E | `ui-onboarding-notifications-crashlytics` | `feat/ui/onboarding/notifications-crashlytics` | none |
| S6-F | `ui-onboarding-gmail-oauth` | `feat/ui/onboarding` | repo-auth-gmail (W2) |
| S6-G | `ui-onboarding-outlook-oauth` | `feat/ui/onboarding` | repo-auth-msgraph (W3) |
| S6-H | `ui-onboarding-imap-provider-selector` | `feat/ui/onboarding` | repo-imap (W2) |

**OnboardingViewModel.kt 충돌**: S6-D, E, F, G, H 모두 수정 → **umbrella `feat/ui/onboarding` 로 합치고 commit 순차** 권장. 단일 세션 × 5 commit.

---

## Wave 7 — UI Settings PIPA Umbrella

**전제**: W6 완료 (db-auth-user-scoped 필수 — account-deletion 에서 DB wipe).

**Umbrella**: `feat/ui/settings/pipa-rights` 단일 브랜치. 단일 세션 sequential commit.

### 단일 세션 commit 순서

| # | logic slug | 선행 | 비고 |
|---|---|---|---|
| 1 | `ui-settings-privacy-management` | none | landing screen, nav |
| 2 | `ui-settings-pipa-activity-log` | privacy-mgmt | PipaActionLogStore 기반 infra (다른 sub 에서 append) |
| 3 | `ui-settings-pipa-data-export` | privacy-mgmt, activity-log | SAF ZIP |
| 4 | `ui-settings-pipa-consent-withdraw` | privacy-mgmt, activity-log | OAuth revoke 6 소스 |
| 5 | `ui-settings-pipa-processing-pause` | privacy-mgmt, activity-log | 9 워커 early-return — 가장 넓은 영향 |
| 6 | `ui-settings-pipa-account-deletion` | privacy-mgmt, activity-log, db-auth-user-scoped, backend DELETE | 서버 DELETE 엔드포인트 필수 |

**3-4일 단일 세션**.

---

## Wave 8 — UI Sources / Error

**전제**: W3 (cursor-invalidation), W2 (auth providers), W0-W1.

### 병렬 세션 배분 (4 세션)

| 세션 | Plan | Branch | 블로커 |
|---|---|---|---|
| S8-A | `ui-sources-contacts-permission` | `fix/ui/sources/contacts-permission` | none — W0 에서도 가능 |
| S8-B | `ui-settings-source-actions` | `feat/ui/sources` | repo-auth-gmail, -msgraph, -imap |
| S8-C | `ui-sources-detail-actions-and-localization` | `feat/ui/sources` | repo-auth-* |
| S8-D | `ui-error-global-banners` | `feat/ui/error/global-banners` | worker-sync-cursor-invalidation (W3) |

**SourceDetail\*.kt 충돌**: S8-B + S8-C → **umbrella `feat/ui/sources`**. 단일 세션 × 3 commit (S8-A 포함).

---

## P0 재배치 — db-auth-user-scoped-database

**문제**: 이 plan 은 `BeCalmDatabase.kt` build() 시그니처 자체를 바꿈 (user_id hash 기반 파일명). Wave 1 의 다른 migration chain 과 같은 파일을 건드림 → **Wave 1 또는 더 이른 wave 에 놓아야 함**.

**권장**: `db-auth-user-scoped-database` 를 **Wave 1 의 첫 머지** 로 올림. 이유:
1. `BeCalmDatabase.build()` 시그니처 변경이 #17/#20/db-calendar/db-email 전부에 영향
2. 계정 전환 leakage 법규 리스크가 P0
3. 시그니처 변경 후에야 나머지 DB PR 이 새 시그니처를 따름

**수정된 Wave 1 merge 순서**:
1. `db-auth-user-scoped-database` (BeCalmDatabase.build() 재설계)
2. `db-commitment-edit-delete-dispute-supersede` (#20) — Room 4→5
3. `db-calendar-status-recurring` — Room 5→6
4. `db-email-schema` — Room 6→7
5. `#21`, `#14`, `#16` 병렬 (파일 겹침 없음)

---

## Critical Path (P0/법규 MVP 블로커)

W0 → W1.1 (db-auth-user-scoped) → W1.2 (#20) → W4 (umbrella #22 부분) → W6 (domain-auth-signout) → W7 (PIPA umbrella)

**Strict sequential 운영 (overlap 금지)**: 병렬 session 수는 각 wave 내부에서만 적용. wave 자체는 순차.

예상 소요 (strict sequential): **5-6주** (wave 9개 × 평균 3-4일 + codex:review gate 시간). 병렬 overlap 허용 시 3-4주였으나 안전성 우선.

---

## 세션 시작 전 Pre-flight (모든 wave 공통)

다음 wave 착수 전 아래 스크립트로 자동 검증:

```bash
# 1. 직전 wave 의 모든 plan 이 COMPLETED 인지 확인
grep -l "^\*\*Status\*\*: COMPLETED" docs/plans/<wave-N-plans>.md | wc -l
# 기대값 = wave N plan 개수

# 2. 직전 wave 관련 PR 이 전부 main 에 머지됐는지
gh pr list --state merged --search "base:main wave-N" --json number,title | jq 'length'

# 3. codex:review approve evidence
ls docs/codex-reviews/wave-N-approval.md  # 존재해야 함

# 위 3 조건 전부 통과 시에만 다음 wave worktree 생성
```

이 체크가 CI 로 강제되면 이상적. 최소한 CTO / orchestrator 세션이 수동 실행.

---

## 세션 운영 규칙

### 각 세션이 따라야 할 절차

1. **진입**: `git worktree add ../becalm-<layer>-<module> <branch>` 로 worktree 생성 (CLAUDE.md CI/CD Protocol)
2. **컨텍스트**: 해당 plan doc + blocked-by plan docs 전부 Read
3. **Pre-flight**: `git fetch origin && git rebase origin/main` — blocked-by 가 이미 머지됐는지 확인
4. **구현**: plan Section 5 Files to change/add/delete 엄수. **Out of Scope 침범 금지**
5. **검증**: plan Section 6 Acceptance Criteria 전부 grep + test 통과 확인
6. **PR**: `gh pr create` — 해당 wave 의 다른 open PR 과 base dependency 언급
7. **Rebase 타이밍**: 해당 wave 내 다른 PR 머지 알림 받으면 `git rebase origin/main` 즉시 실행

### 병렬 상한

- 같은 `{layer}/{module}` 브랜치에는 **항상 1 세션**. umbrella PR (#22, `feat/ui/onboarding`, `feat/ui/settings/pipa-rights`, `feat/ui/sources`) 은 sequential commit.
- 서로 다른 모듈의 세션은 hot-file 공유가 ≤ 1 인 경우에만 병렬. 2 이상이면 한쪽을 다음 wave 로 미룸.
- CTO 동시 감시 가능한 상한: **5 세션**. 그 이상이면 리뷰 병목.

### 파일 겹침이 생긴 경우

1. 같은 brach 에 stack → commit 자연 linear
2. 다른 branch 면 → 나중에 작업하는 세션이 `git rebase origin/main` 후 conflict 해결
3. 3개 이상 겹치면 → wave 분리 재검토

---

## Cheat Sheet — Wave 당 동시 세션 수

| Wave | 추천 세션 수 | 핵심 hot-file |
|---|---|---|
| W0 | 5 | SourceTypes.kt (linear), MediaStoreWorker (linear) |
| W1 | 4-5 (DB chain 1 + 병렬 3-4) | BeCalmDatabase.kt (linear) |
| W2 | 6 | WorkSchedulerImpl.kt (rebase) |
| W3 | 6 | none (대부분 worker 별 파일) |
| W4 | 1 (umbrella #22) | CommitmentManagementScreen/ViewModel |
| W5 | 2-3 (TodayViewModel linear) | TodayViewModel.kt |
| W6 | 1 (umbrella `feat/ui/onboarding`) + 3 (auth/crashlytics) | OnboardingViewModel.kt |
| W7 | 1 (PIPA umbrella) | BecalmNavHost.kt, SettingsScreen.kt |
| W8 | 2 (sources umbrella 1 + error 1 + contacts tiny 1) | SourceDetailScreen.kt |

---

## Summary

- **60 plans, 9 waves, 3-4주 구현** (병렬 5 세션 기준)
- **Critical path**: W0 → W1 (db-auth + #20) → W4 (commitment umbrella) → W7 (PIPA umbrella) — P0/법규
- **핵심 bottleneck**: `BeCalmDatabase.kt` / `Migrations.kt` 의 Room schema linear chain. 절대 병렬 금지
- **Umbrella PR 4개** (`#22`, `feat/ui/onboarding`, `feat/ui/settings/pipa-rights`, `feat/ui/sources`): 단일 세션 sequential commit 로 운영
- **나머지 42 plan**: 파일 겹침이 ≤1 이면 완전 병렬

다른 Claude Code 세션은 이 문서를 entry point 로 삼아 wave 별 세션 수만큼 worktree 생성 후 구현 시작.
