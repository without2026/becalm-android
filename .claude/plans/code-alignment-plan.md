# Code Alignment Plan — PR1~3 Spec → Code 적용

**작성일**: 2026-04-20
**전제**: PR1~3 스펙 PR merge 완료 기준. Railway/Supabase 프로덕션 배포 전무 — local-first 로 한꺼번에 수정하고 첫 deploy.
**목표**: `.spec/` 현재 상태와 `android/` 코드베이스 간 drift 를 순차적 PR 로 해소.
**저자 결정 승인**: destructive Room fallback + `001_initial.sql` 단일 스키마 + 백엔드 병렬 진행 + 커밋 분리 방식.

---

## 1. 작업 범위 정의

### In scope
- **Android Room schema 전면 재정비** — `commitments.due_at triplet + audit + soft delete + supersede`, `calendar_events.recurring_event_id + original_start_at`
- **SP-36 `commitment_state` FSM 완전 제거** — 4개 파일 삭제 + 전 호출부 리라이트
- **Repository CRUD 확장** — edit / manual / supersede / soft delete / quote dispute
- **신규 UI 화면 4개** — CommitmentEditScreen, ManualCommitmentScreen, PipaRightsScreen, ColdSyncScreen(구조 개편)
- **기존 UI 수정** — CommitmentManagementScreen (FAB/undo/overdue/cancel), TodayTimelineScreen (unified list), SettingsScreen (개인정보 관리 entry)
- **워커 2개 신설** — OverdueSweepWorker, ColdSyncOrchestrator
- **Railway DTO + API 계약** — 신규 필드 매핑 + POST/PATCH extensions + `include_deleted` query param
- **백엔드 (별 repo) 연동** — `becalm-backend` FastAPI + `supabase/migrations/001_initial.sql`

### Out of scope (별 PR 또는 post-MVP)
- GraphRAG / 엔티티 해상도 / 3P person ref 개선
- Samsung sleeping apps 딥링크 QA
- 테스트 자동화 확장 (기존 카테고리 유지)

---

## 2. 승인된 핵심 결정

| # | 결정 | 채택 근거 |
|---|---|---|
| 1 | Room 마이그레이션 전략 = **destructive fallback** | 프로덕션 0 사용자 + 내부 테스트 기기만 재설치 허용. `execSQL` 수십 줄 + MigrationTestHelper 세트 모두 불필요. `DATABASE_VERSION = 4` 한 번만 올리고 `fallbackToDestructiveMigration()` 추가. |
| 2 | Supabase 마이그레이션 = **001_initial.sql 단일 파일** | 기존 history 0. 누적 스키마를 한 파일로 commit 하는 편이 번호 순서 혼동 없음. 차후 실제 배포 후부터 incremental. |
| 3 | SP-36 FSM 제거는 **스키마 재정비 PR 에 포함** (별 PR 분리하지 않음) | 수정 대상 파일 100% 겹침. 같은 파일 두 번 revision 방지. 의도 분리는 commit 단위로 해결. |
| 4 | 백엔드 작업 **병렬 진행** (별 repo / 별 세션) | Android 코드 수정 스코프 이미 큼. 직렬화하면 리드타임 낭비. API 계약은 `.spec/contracts/api-contract.yml` 고정 기준. |
| 5 | 첫 배포 전까지 **모든 PR merge 후 일괄 deploy** | DB schema 파괴적 변경 + 백엔드/클라이언트 계약 동시 전환 → rolling deploy 아님, cutover. |

---

## 3. PR 분할 및 의존성

```
PR-A ──→ PR-B ──→ PR-C ──→ PR-D
                                       
PR-E  (별 repo, 병렬 독립)
```

| PR | 이름 | 핵심 변경 | 의존 |
|---|---|---|---|
| **A** | data-layer foundation | Entity + Migration + SP-36 제거 | — |
| **B** | repository & API contract | Repository CRUD 확장 + DTO + RailwayApi 확장 | A |
| **C** | UI — new screens & existing edits | CommitmentEdit/Manual/Pipa/ColdSync + CommitmentManagement/Today/Settings 수정 | B |
| **D** | workers & orchestrators | OverdueSweepWorker, ColdSyncOrchestrator, DataStore state machine | B |
| **E** | backend (becalm-backend repo) | FastAPI 엔드포인트 + Vertex prompt + Supabase 001 | 없음 (Android 와 병렬) |

**PR 크기 예상** (파일 수 기준 추정):
- PR-A: ~10 (entity 2 + dao 2 + migration 1 + 삭제 4 + converter 1)
- PR-B: ~8 (repository 1 + DTO 2 + api 1 + mapper 2 + di 1 + tests 신규 포함)
- PR-C: ~15 (신규 screen 4 + viewmodel 4 + 기존 수정 3~5 + navigation 1)
- PR-D: ~5 (worker 2 + scheduler 1 + datastore 1 + di 1)
- PR-E: 별 repo 별도 plan.md 필요

---

## 4. PR-A 상세 — Data Layer Foundation

### 커밋 구조 (bisect 친화적)

```
commit 1  refactor(commitment): remove SP-36 FSM (absent from .spec/)
  삭제:
    - android/.../domain/commitment/CommitmentState.kt
    - android/.../domain/commitment/CommitmentStateMachine.kt
    - android/.../domain/commitment/CommitmentEvent.kt
    - android/.../domain/commitment/TransitionError.kt
    - android/app/src/test/.../domain/commitment/CommitmentStateMachineTest.kt
  수정:
    - CommitmentEntity.kt — commitmentState 필드 / @ColumnInfo 제거
    - CommitmentRepository.kt — applyEvent() 삭제, updateActionState() lifecycle 연동 블록(345-368) 제거
    - VoiceUploadWorker.kt:463 — commitmentState = CommitmentState.DRAFT 라인 삭제
    - CommitmentManagementViewModel.kt — CommitmentFilter.DONE 은 action_state=="completed" 로 전환, derivedStatus 재계산
    - PersonDetailViewModel.kt — Commitment row 에서 commitmentState 필드 제거, action_state 기반 분류로 전환
  테스트 수정:
    - CommitmentManagementViewModelTest.kt — commitmentState 참조 제거
    - PersonDetailViewModelTest.kt — 동일
    - CommitmentRepository 관련 테스트 — applyEvent 케이스 삭제
  결과: commitment_state 참조 0건 확인 (grep 빈 결과)

commit 2  feat(db): commitments schema — due_at triplet + audit + soft delete + supersede
  수정:
    - CommitmentEntity.kt
        - due_date: LocalDate? → dueAt: Instant?
        - 추가: dueHint: String?, dueIsApproximate: Boolean
        - 추가: lastEditedBy: String?, lastEditedAt: Instant?
        - 추가: quoteDisputed: Boolean (default false), quoteDisputedAt: Instant?
        - 추가: deletedAt: Instant?
        - 추가: supersedesCommitmentId: String?
        - 인덱스 교체: idx_commitments_user_action_due → idx_commitments_user_action_due_at
                    + idx_commitments_user_deleted + idx_commitments_supersedes
    - CommitmentDao.kt — due_date 쿼리 모두 due_at 으로, soft delete 필터 (deleted_at IS NULL) 기본 적용
    - Converters.kt — LocalDate 변환 아직 사용처 있으면 유지, 아니면 제거 후보
    - CommitmentDtos.kt — due_at/due_hint/due_is_approximate/last_edited_by/... 필드 매핑
    - CommitmentManagementViewModel.kt — due_at 기반 정렬/필터, OVERDUE 필터는 action_state 기준으로 복구

commit 3  feat(db): calendar_events — recurring_event_id + original_start_at
  수정:
    - CalendarEventEntity.kt — 2개 컬럼 추가 + idx_calendar_events_user_recurring 인덱스
    - CalendarEventDao.kt — 반복 시리즈 조회 쿼리 추가 (추후 PR 에서 사용)
    - CalendarDtos.kt — 필드 매핑

commit 4  chore(db): bump DATABASE_VERSION to 4 + destructive fallback
  수정:
    - BeCalmDatabase.kt
        - DATABASE_VERSION: Int = 3 → 4
        - build() 에 .fallbackToDestructiveMigration() 추가 (downgrade 용 기존 플래그는 별도 유지)
    - Migrations.kt — MIGRATION_1_2, MIGRATION_2_3 유지 (apk 업그레이드 호환), MIGRATIONS 배열 그대로. v3→v4 는 destructive 로 의도적 누락
    - 주석 블록 업데이트 — "v4 is a schema reset; DEV only pre-launch. After first prod install, forward-only."
```

### PR-A 수락 기준

- [ ] `grep -r 'commitment_state\|commitmentState\|CommitmentState' android/` 결과 0건
- [ ] `./gradlew :app:assembleDebug` 성공
- [ ] `./gradlew :app:test` 통과 (FSM 테스트 제거 반영)
- [ ] 앱 첫 실행 시 v3 DB 존재하던 기기에서 destructive wipe + 새 스키마 생성 확인
- [ ] `.spec/contracts/data-model.yml` 과 `CommitmentEntity.kt`·`CalendarEventEntity.kt` 필드 1:1 대조 (수동 체크리스트 PR 본문에)

### PR-A 위험

- **Converters.kt 의 LocalDate 변환기가 다른 엔티티(CalendarEventEntity.date?) 에서 쓰이는지 확인 필요** — 쓰이면 유지, 안 쓰이면 제거해서 unused import 경고 제거
- **CommitmentManagementViewModelTest** 가 SP-36 기반 assertions 를 얼마나 포함하는지 사전 count → 테스트 전면 리라이트 범위 예상
- **Voice/Email 파이프라인의 extraction payload** — Railway 로 commitment 올릴 때 `commitment_state='DRAFT'` 를 보내는 코드 있으면 제거. VoiceUploadWorker.kt:463 외 추가 탐색 필요

---

## 5. PR-B 상세 — Repository & API Contract

### 추가할 Repository 메서드

```kotlin
// CommitmentRepository.kt — 신규/확장
suspend fun editFields(
    id: String,
    title: String?,
    dueAt: Instant?,
    dueHint: String?,
    personRef: String?,
    direction: String?,
    now: Instant,
): BecalmResult<CommitmentEntity>
// EDIT-001..005: 편집 가능 필드 업데이트 + last_edited_by/at 자동 주입. PATCH /v1/commitments/{id}

suspend fun markQuoteDisputed(id: String, now: Instant): BecalmResult<Unit>
// EDIT-006: quote_disputed=true, quote_disputed_at=now. quote 필드는 수정 안 함

suspend fun softDelete(id: String, now: Instant): BecalmResult<Unit>
// EDIT-008 / CMT-013: deleted_at=now. Room UPDATE + PATCH /v1/commitments/{id} {deleted_at}

suspend fun supersede(
    oldId: String,
    newDraft: CommitmentDraft,
    now: Instant,
): BecalmResult<CommitmentEntity>
// EDIT-007: oldId soft delete + 신규 commitment(supersedes_commitment_id=oldId) INSERT.
// POST /v1/commitments 호출 (client-generated id + idempotency)

suspend fun saveManualCommitment(
    draft: ManualCommitmentDraft,
    now: Instant,
): BecalmResult<CommitmentEntity>
// MAN-001..003: source_type='manual', confidence=1.0, client-generated quote.
// POST /v1/commitments 호출

suspend fun listActive(userId: String, include_deleted: Boolean = false): Flow<List<CommitmentEntity>>
// deleted_at IS NULL 기본 필터 적용 (include_deleted=true 시 전체)
```

### DTO 추가

```kotlin
// CommitmentDtos.kt
data class EditCommitmentRequest(
    val title: String? = null,
    val due_at: Instant? = null,
    val due_hint: String? = null,
    val due_is_approximate: Boolean? = null,
    val person_ref: String? = null,
    val direction: String? = null,
    val quote_disputed: Boolean? = null,
    val deleted_at: Instant? = null,
    val last_edited_at: Instant,
)

data class CreateCommitmentRequest(
    val id: String,   // client-generated UUID for idempotency
    val direction: String,
    val title: String,
    val quote: String,
    val person_ref: String?,
    val due_at: Instant?,
    val due_hint: String?,
    val due_is_approximate: Boolean,
    val confidence: Double,
    val source_type: String,   // 'manual' or 'voice' etc
    val supersedes_commitment_id: String? = null,
)
```

### RailwayApi 확장

```kotlin
@POST("/v1/commitments")
suspend fun createCommitment(@Body request: CreateCommitmentRequest): Response<CommitmentDto>

// 기존 PATCH 확장
@PATCH("/v1/commitments/{id}")
suspend fun patchCommitment(
    @Path("id") id: String,
    @Body request: EditCommitmentRequest,  // PatchCommitmentRequest 교체
): Response<CommitmentDto>

@GET("/v1/commitments")
suspend fun listCommitments(
    @Query("since") since: String,
    @Query("direction") direction: String? = null,
    @Query("include_deleted") includeDeleted: Boolean? = null,
): Response<CommitmentListResponse>
```

### PR-B 수락 기준

- [ ] 신규 repo 메서드 각각 단위 테스트 (happy + error mapping)
- [ ] `IdempotencyInterceptor` 가 POST /v1/commitments 에도 적용되는지 확인
- [ ] API 계약 1:1 매칭 (`.spec/contracts/api-contract.yml` 기준)
- [ ] 기존 `updateActionState()` 가 정상 동작 유지 (PR-A 에서 단순화된 버전)

---

## 6. PR-C 상세 — UI Screens

### 신규 Compose 화면

| 파일 | 위치 | 근거 스펙 | 주요 컴포넌트 |
|---|---|---|---|
| `CommitmentEditScreen.kt` | `ui/commitments/edit/` | EDIT-001..008 | 편집 가능 필드 폼 + quote 고정 표시 + [이의 제기] 버튼 + [삭제] confirm dialog |
| `ManualCommitmentScreen.kt` | `ui/commitments/manual/` | MAN-001..006 | 7개 필드 입력 + 📝 배지 프리뷰 |
| `PipaRightsScreen.kt` | `ui/settings/pipa/` | PIPA-001..007 | 메뉴 entry 6개 + 계정 삭제 2단 confirm + 처리 일시중지 토글 |
| `ColdSyncScreen.kt` 개편 | `ui/today/coldsync/` (기존 파일 이동) | COLD-001..008 | Stage 1 7-parallel progress + [나중에 하기] + Stage 2 background banner |

### 신규 ViewModel (각 화면 1:1)

- `CommitmentEditViewModel.kt` — repo.editFields / markQuoteDisputed / softDelete / supersede 호출
- `ManualCommitmentViewModel.kt` — repo.saveManualCommitment + client-side validation (quote 1-500자)
- `PipaRightsViewModel.kt` — 동의 철회 / export ZIP / 처리 일시중지 토글 / 계정 삭제
- `ColdSyncViewModel.kt` — 기존 유지하되 DataStore state machine + Stage 1/2 분리 반영

### 기존 UI 수정

- `CommitmentManagementScreen.kt`:
  - FAB 추가 (ManualCommitmentScreen 진입, MAN-001)
  - Undo Snackbar (completed/cancelled 전이 후 5초, CMT-013)
  - 'overdue' 배지 + 취소된 섹션 (접힘) 반영
  - 📝 배지 렌더링 (source_type='manual')
- `TodayTimelineScreen.kt`:
  - CalendarEvent + Commitment unified list (TDY-001)
  - cancelled 이벤트 strike-through + dim (TDY-001)
  - OverallSyncIndicator + SourceStatusStrip (TDY-003/008)
  - 우상단 설정 아이콘 (TDY-007), 사이드 드로어 제거
- `SettingsScreen.kt`:
  - '개인정보 관리' 메뉴 항목 추가 (PipaRightsScreen 진입)
- `BecalmNavHost.kt` + `Routes.kt`:
  - 신규 4개 route 등록 (`commitments/{id}/edit`, `commitments/manual`, `settings/pipa`, …)

### PR-C 수락 기준

- [ ] Compose preview 렌더링 정상 (새 화면 4개 각각)
- [ ] Navigation 경로 `.spec/contracts/ui-map.yml` 준수
- [ ] CommitmentEditScreen 에서 quote 필드가 `readOnly` + disabled 상태인지 확인
- [ ] `docs/e2e-verification/` 에 신규 스펙 4개 수동 검증 항목 추가 (체크리스트만)

---

## 7. PR-D 상세 — Workers & Orchestrators

### 신규 워커

```kotlin
// worker/OverdueSweepWorker.kt
// CMT-012: due_at < now - 24h && action_state IN (pending,reminded,followed_up) → action_state='overdue'
// WorkManager periodic (6h 간격)

// worker/coldsync/ColdSyncOrchestrator.kt
// COLD-001: Stage 1 = 7 parallel coroutines (calendar×2 + email×4 + user_profile)
// COLD-002: 진행 state 를 DataStore 에 기록
// COLD-003: 전체 완료 시 onboarding_completed=true
// COLD-006: [나중에 하기] 선택 시 남은 어댑터를 WorkManager 로 전환
// COLD-007: process-kill 복구 (Scenario A/B/C)

// worker/coldsync/ColdSyncStage2Worker.kt
// COLD-004: 백그라운드 email 30d + MediaStore recordings scan
// COLD-005: TodayTimelineScreen 상단 배너로 progress surfacing
```

### 기존 워커 수정

- `VoiceUploadWorker.kt`: user_profile context (`phone_e164_self`, `timezone`, `preferred_locale`) payload 첨부 — 이미 일부 있지만 `llm-prompts.md` 기준 재검토
- `UploadWorker.kt`: `sync_status` → Room PATCH 큐 로직 그대로, 신규 필드(`last_edited_at`, `deleted_at` 등) PATCH 대상 포함

### PR-D 수락 기준

- [ ] ColdSync DataStore state machine `.spec/cold-sync.spec.yml` COLD-007 시나리오 A/B/C 테스트 커버
- [ ] OverdueSweepWorker 6h periodic + 배치 PATCH 네트워크 가용 시 동작
- [ ] Worker 재시작 시 Stage 1 progress 복원 확인

---

## 8. PR-E 상세 — Backend (별 Repo)

**위치**: `becalm-backend` (별 repo, 별 Claude 세션에서 진행)

이 plan.md 범위 밖. 별도 plan.md 작성 필요. 의존 관계만 명시:

- FastAPI endpoints: POST/PATCH /v1/commitments 확장, GET /v1/commitments `include_deleted`, user_profile CRUD
- Vertex AI Gemini 2.5 Flash prompt — `docs/llm-prompts.md` 계약 구현
- `supabase/migrations/001_initial.sql` — `.spec/contracts/data-model.yml` 전체 반영한 단일 파일
- RLS 정책, JWT validation, idempotency 지원

**Android ↔ Backend 인터페이스**: `.spec/contracts/api-contract.yml` 고정. 양측이 이 파일만 참조하면 병렬 진행 가능.

---

## 9. 테스트 전략

### 삭제 대상 테스트
- `CommitmentStateMachineTest.kt` — 파일 전체

### 대폭 수정 대상
- `CommitmentManagementViewModelTest.kt` — SP-36 assertion 제거, action_state flow 추가 (undo, overdue, cancel)
- `PersonDetailViewModelTest.kt` — commitmentState 문자열 분류 → action_state 기반 분류
- `CommitmentRepositoryTest.kt` — applyEvent 테스트 삭제, 신규 메서드 6개 테스트 추가

### 신규 추가 대상
- `CommitmentEditViewModelTest.kt`
- `ManualCommitmentViewModelTest.kt`
- `PipaRightsViewModelTest.kt`
- `OverdueSweepWorkerTest.kt`
- `ColdSyncOrchestratorTest.kt`

### 수동 검증 대상 (`docs/e2e-verification/` 추가)
- `10-commitment-edit.md` — EDIT-001..008 시나리오
- `11-manual-commitment.md` — MAN-001..006
- `12-pipa-rights.md` — PIPA-001..007
- `13-cold-sync-stages.md` — COLD-001..008 (프로세스 킬 복구 포함)

---

## 10. 실행 순서

### Phase 1 (Android) — 직렬
1. **PR-A** merge
2. **PR-B** branch from PR-A post-merge → merge
3. **PR-C** branch from PR-B post-merge → merge
4. **PR-D** branch from PR-B post-merge (C와 병렬 가능) → merge

### Phase 2 (Backend) — 병렬 진행, PR-E merge 는 Phase 3 전까지만 완료
- 별 Claude 세션 / 별 repo. Android 와 독립적으로 진행.

### Phase 3 — First Deploy (cutover)
1. Railway deploy (staging → production)
2. Supabase `001_initial.sql` 적용
3. APK 내부 테스터 배포
4. 스모크 테스트: 로그인 → 온보딩 → ColdSync → Today → Commitment create/edit/delete → PIPA export

---

## 11. 통합 리스크

| 리스크 | 완화 |
|---|---|
| PR-A 범위가 커서 리뷰 부담 | 커밋 4개로 분할 + bisect 친화적 |
| CommitmentRepository 테스트 wholesale rewrite | 기존 테스트 행동만 보존 (signature 변경) → golden-path 테스트 먼저 통과시킴 |
| Destructive wipe 가 테스트 기기의 실제 데이터 손실로 이어질 수 있음 | 내부 공지 + README 에 v4 = wipe 명시 |
| 백엔드 지연 시 Android PR-B~D merge 가능하나 실제 end-to-end 동작 안 함 | PR-E 진행률을 매 PR 리뷰 때 sync |
| `commitment_state` 참조가 스펙 외 문서(refactor-codex-r1-r10.md 등) 에 남음 | 참조만 존재하고 코드에 영향 없으면 유지, PR-A 에서 grep 확인 |
| Compose preview 의존성이 과거 필드 기대 | PR-C 에서 `@Preview` 렌더링 검증 |

---

## 12. 취소/롤백 전략

- **PR 단위**: 각 PR 은 merge 전에 local revert 가능 (destructive fallback 덕에 로컬 DB 는 언제든 재생성).
- **Phase 3 cutover 롤백**: Supabase `001_initial.sql` 은 한 번 적용 후 `DROP SCHEMA public CASCADE` 로만 되돌릴 수 있음 (프로덕션 사용자 0명이므로 문제 없음). APK 는 구 버전 거부(DATABASE_VERSION 다운그레이드 실패) — 테스터에게 재설치 안내.
- **장기 (post first-deploy)**: 향후 스키마 변경은 forward-only incremental. destructive fallback 주석 제거 + MIGRATION_4_5 부터 본격 작성.

---

## 13. 다음 액션

- [ ] 본 plan.md CTO 승인
- [ ] 승인 후 PR-A 브랜치 생성: `refactor/align-data-layer-with-spec`
- [ ] PR-A commit 1 (SP-36 제거) 부터 착수
- [ ] 별 Claude 세션에서 PR-E (`becalm-backend` repo) 를 시작할지 결정

**질문**:
1. PR-A 의 destructive fallback 도입 주석 문구는 내부용 README (`docs/e2e-verification/README.md`) 에도 알림 추가할까?
2. 백엔드 작업을 지금 이 세션과 병렬로 별 Claude 에 트리거할지, 아니면 Android Phase 1 완료 후 순차 진행할지?
