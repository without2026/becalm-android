# UI / Commitment / edit-sheet — CommitmentEditSheet (편집 · 이의제기 · 삭제 · supersede · 편집이력 라벨) 전체 부재

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentManagement 화면 / 편집 시트
**Severity**: High (`commitment-edit.spec.yml` EDIT-001..008 전체가 UI 레벨에서 미구현. 사용자는 LLM 이 잘못 추출한 title/due_at/person_ref/direction 을 바로잡을 수 없고, quote 이의제기도, soft-delete 도, supersede 도 진입 경로 없음.)
**Type**: Gap (Compose Screen + VM + Dialog 군 전체 파일 부재)

---

## 1. Finding

`android/app/src/main/java/com/becalm/android/ui/commitments/` 하위에 `CommitmentEditSheet.kt`, `CommitmentEditViewModel.kt` 가 존재하지 않는다. `CommitmentDetailSheet` 도 아직 없으므로 (`ui-commitment-detail-sheet.md`), [편집] 버튼을 포함한 편집 진입점 전체가 부재. `CommitmentRepository` 에도 pre-fill 후 Room UPDATE + Railway PATCH 를 수행하는 `updateEditableFields` / `flagQuoteDisputed` / `softDelete` / `supersede` API 가 없다.

본 plan 은 **EDIT-001..008 8개 behavior 전체** 를 하나의 umbrella logic unit 으로 묶는다. 구현 세션이 sheet / VM / repo 확장 / dialog / supersede mode 를 linear commit stack 으로 쌓을 수 있도록 의존성과 acceptance 를 정의.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-edit.spec.yml:16-22` EDIT-001** — 편집 시트 진입:
  > "CommitmentDetailSheet 하단에 [편집] 버튼이 표시되며 탭 시 CommitmentEditSheet가 열린다. 편집 가능 필드는 pre-fill된 상태로 표시되고 quote 필드는 읽기 전용(dimmed) + [이의 제기] 보조 버튼과 함께 표시된다."

- **`.spec/commitment-edit.spec.yml:26-32` EDIT-002** — 편집 가능 필드:
  > "편집 가능 필드는 title(text), due_at(date+time picker), due_hint(text), due_is_approximate(toggle), person_ref(autocomplete — Room persons + enrichment names 제안), direction(give/take radio)이다. source_type·source_event_id·source_event_title·source_event_occurred_at·confidence·created_at은 읽기 전용. quote는 EDIT-005로만 변경(이의제기 플래그) — 문자열 자체는 절대 편집 불가"

- **`.spec/commitment-edit.spec.yml:36-42` EDIT-003** — 저장:
  > "[저장] 탭 시 클라이언트 측 validation을 수행한 뒤 Room UPDATE + Railway PATCH /v1/commitments/{id}를 optimistic 순서로 실행한다. last_edited_by(user_id) + last_edited_at(now())가 자동 기록된다."

- **`.spec/commitment-edit.spec.yml:46-52` EDIT-004** — Validation:
  > "title 공백 제거 후 길이 1~200. due_at이 입력된 경우 유효 ISO8601 KST(+09:00), 과거여도 허용(이미 지난 약속도 추후 기록 가능). person_ref는 공백 제거 후 lowercase 정규화(이메일은 lower, 전화번호는 E.164 정규화 시도). direction ∈ ('give','take'). 규칙 위반 시 해당 필드 하단에 빨간 헬퍼 텍스트 표시 + [저장] 비활성"

- **`.spec/commitment-edit.spec.yml:56-62` EDIT-005** — 이의 제기:
  > "[이의 제기] 탭 시 AlertDialog '이 인용문이 부정확하거나 오해를 유발한다고 표시하시겠습니까? 원문은 변경되지 않고 이의 제기 표시만 추가됩니다.' → 확인 시 commitments.quote_disputed=true로 UPDATE. quote 문자열 자체는 절대 수정되지 않음(법적 증거 성격)"

- **`.spec/commitment-edit.spec.yml:66-72` EDIT-006** — Soft delete:
  > "CommitmentEditSheet 하단 [삭제] 링크 탭 시 AlertDialog로 확인 후 deleted_at=now() soft delete. action_state='cancelled'(CMT-012)와 구분되는 의미: cancelled는 '약속이 무효화됨'(기록은 유지), deleted는 'LLM이 애초에 잘못 추출했거나 약속이 아님'"
  > "AlertDialog '이 약속을 삭제하시겠습니까? LLM이 잘못 추출한 경우에만 사용하세요. 실수로 약속이 취소된 경우 [취소]를 사용하세요.'"

- **`.spec/commitment-edit.spec.yml:76-82` EDIT-007** — Supersede:
  > "CommitmentEditSheet 상단 [이건 다른 약속입니다] 탭 시 새 commitment 작성 화면으로 이동한다. 저장 시 새 row INSERT + supersedes_commitment_id=old.id, old row는 deleted_at=now()로 soft delete된다. 원본 quote는 새 commitment에도 복사되어 출처 근거는 유지"

- **`.spec/commitment-edit.spec.yml:86-92` EDIT-008** — 편집 이력 라벨:
  > "CommitmentDetailSheet에 last_edited_at != null인 경우 '마지막 수정: {M/d HH:mm} (본인)' 보조 라벨이 표시된다."

- **`.spec/commitment-edit.spec.yml:97` invariant**:
  > "quote는 절대 편집되지 않는다 — EDIT-005 이의 제기만 허용(quote_disputed 플래그). 문자열 자체는 법적 증거로 보존"

- **`.spec/commitment-edit.spec.yml:100` invariant**:
  > "삭제(EDIT-006)는 hard DELETE가 아닌 deleted_at soft delete다 — 모든 클라이언트 쿼리는 `WHERE deleted_at IS NULL` 필터 포함"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 디렉토리 목록
```
android/app/src/main/java/com/becalm/android/ui/commitments/
├── CommitmentManagementScreen.kt
└── CommitmentManagementViewModel.kt
```
→ `CommitmentEditSheet.kt`, `CommitmentEditViewModel.kt`, `CommitmentCreateSheet.kt` (supersede mode) 전부 부재.

### 3.2 Repository API
```bash
grep -n "updateEditableFields\|flagQuoteDisputed\|softDelete\|supersede\|markDeleted" \
  android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt
# → empty
```
→ 편집 관련 method 없음. 현재는 `updateActionState(id, newState, updatedAt)` (`CommitmentRepository.kt:88-92`) 만 존재.

### 3.3 CommitmentEntity 컬럼 (PR #20 필요)
```bash
grep -n "last_edited_by\|last_edited_at\|quote_disputed\|deleted_at\|supersedes_commitment_id" \
  android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt
# → empty (현재)
```
→ 6 컬럼 부재. `db-commitment-edit-delete-dispute-supersede.md` (PR #20) 가 해결. 본 plan 은 PR #20 merge 후 시작.

### 3.4 DTO — 서버 PATCH body
```bash
grep -n "last_edited_by\|last_edited_at\|quote_disputed\|deleted_at\|supersedes_commitment_id" \
  android/app/src/main/java/com/becalm/android/data/remote/dto/CommitmentDtos.kt
# → empty (PR #20 이 추가)
```

### 3.5 네비게이션
`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:143-144` — `Commitments` 루트만. `CommitmentEdit(id)` 또는 `CommitmentCreate(superssedesId)` 라우트 없음. 본 PR 은 in-screen ModalBottomSheet 로 구현 가능 → 라우트 추가 불필요.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Edit Sheet Composable | `CommitmentEditSheet.kt` | 없음 | 신규 파일 |
| Edit VM | `CommitmentEditViewModel.kt` | 없음 | 신규 파일 |
| Pre-fill 로직 | Room `findById` → field 초기값 | 없음 | VM load 시 처리 |
| Validation (EDIT-004) | title 1-200, direction 필수, person_ref 정규화 | 없음 | VM `validate()` + 에러 state |
| quote 읽기 전용 + [이의 제기] | dimmed card + 텍스트 버튼 | 없음 | Composable 레이어 |
| Dispute Dialog (EDIT-005) | AlertDialog 확인 → `quote_disputed=true` | 없음 | Dialog + VM.flagDisputed |
| Delete Dialog (EDIT-006) | AlertDialog 확인 → `deleted_at=now()` | 없음 | Dialog + VM.softDelete |
| Supersede 진입 (EDIT-007) | CommitmentCreateSheet with prefilled quote | 없음 | `ui-commitment-manual-sheet.md` 의 supersede mode 로 진입 |
| Last-edited 라벨 (EDIT-008) | `CommitmentDetailSheet` 내 소제목 라벨 | 없음 | `ui-commitment-detail-sheet.md` 연계 |
| Repository ops | `updateEditableFields`, `flagQuoteDisputed`, `softDelete`, `insertSupersede` | 없음 | Repo 확장 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`**
   - 신규 메서드 4개:
     ```
     suspend fun updateEditableFields(
         id: String,
         title: String,
         dueAt: Instant?, dueHint: String?, dueIsApproximate: Boolean,
         personRef: String?, direction: String,
         editorUserId: String,
     ): BecalmResult<Unit>

     suspend fun flagQuoteDisputed(id: String, disputed: Boolean): BecalmResult<Unit>

     suspend fun softDelete(id: String): BecalmResult<Unit>

     suspend fun insertSupersede(
         oldId: String, newCommitment: CommitmentEntity,
     ): BecalmResult<Unit>
     ```
   - 모두 Room UPDATE/INSERT 즉시 + Railway PATCH/POST 비동기 + `sync_status='pending'` → 성공 시 `'synced'` 패턴. 실패 시 Room 낙관값 유지, UploadWorker 재시도 (기존 파이프라인 재사용).

2. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt`** — 위 4 메서드 구현.

3. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`** — DAO 레벨 쿼리 추가:
   - `@Query("UPDATE commitments SET title=:t, due_at=:d, ... WHERE id=:id")`
   - `updateQuoteDisputed(id, disputed: Boolean, now: Instant)` — `quote_disputed_at` 도 함께 기록
   - `updateDeletedAt(id, deletedAt: Instant)`
   - `insert(newEntity)` 는 기존 재사용.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditSheet.kt`**
   - `ModalBottomSheet` Composable.
   - 레이아웃:
     - 상단: direction 배지 + title (이 sheet 에서는 제목 = "약속 편집")
     - 상단 보조: `[이건 다른 약속입니다]` — EDIT-007 진입 버튼 (`onNavigateToSupersede(commitmentId)` 콜백)
     - quote 카드 (읽기 전용, dimmed) + `[이의 제기]` / `[이의 제기 해제]` 텍스트 버튼 (quote_disputed 상태에 따라 라벨 토글)
     - 편집 필드:
       - `BecalmTextField` title (max 200)
       - `DatePicker` + `TimePicker` for due_at (nullable)
       - `BecalmTextField` due_hint
       - `Switch` due_is_approximate
       - `BecalmTextField` person_ref + 자동완성 드롭다운 (EDIT-002 — Room persons_enrichment + calendar events 이름 prefix 매치)
       - `RadioButton` group direction (내가 한/상대가 한)
     - 출처 정보 섹션 (읽기 전용): source_type, source_event_title, source_event_occurred_at, created_at
     - 하단 `[취소]/[저장]` 버튼
     - 하단 보조 `[삭제]` 링크 (빨간색 subtle)
   - Validation 에러는 각 필드 하단 헬퍼 텍스트. `[저장]` disabled = !state.canSave.

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditViewModel.kt`**
   - `@HiltViewModel`. 생성자: `CommitmentRepository`, `PersonEnrichmentRepository`, `UserPrefsStore`, `Logger`.
   - `uiState: StateFlow<CommitmentEditUiState>`:
     - 필드 값 + validation 에러 + isSaving + isDisputed 토글 + `confirmDeleteDialogOpen` + `confirmDisputeDialogOpen` + saveSuccess 이벤트.
   - `load(id)` → Room `findById` → pre-fill.
   - `onField...Change` 핸들러 세트 + `validate()` 순수 함수.
   - `onSaveClick()` → validate → `updateEditableFields(...)`.
   - `onDisputeClick()` → `confirmDisputeDialogOpen = true`; `onDisputeConfirm()` → `flagQuoteDisputed(id, true)`.
   - `onDeleteClick()` → `confirmDeleteDialogOpen = true`; `onDeleteConfirm()` → `softDelete(id)` → sheet dismiss.
   - `onSupersedeClick()` → 콜백으로 `onNavigateToSupersede(id)` 실행 (`CommitmentCreateSheet` supersede mode 는 `ui-commitment-manual-sheet.md` 의 책임).

3. **`android/app/src/main/java/com/becalm/android/ui/commitments/PersonRefSuggestionsSource.kt`** (선택)
   - Room persons_enrichment + calendar events name prefix 매치 유틸 — 재사용성 고려. 단일 file 단일 책임.

4. **테스트 파일**:
   - `CommitmentEditViewModelValidationTest.kt` — EDIT-004 전 항목
   - `CommitmentEditViewModelSaveTest.kt`
   - `CommitmentEditViewModelDisputeTest.kt`
   - `CommitmentEditViewModelDeleteTest.kt`

### 5.3 Files to delete
없음.

### 5.4 Non-code changes

- `strings.xml` — 20+ 키 신규 (한국어 원문 그대로):
  - `R.string.commitment_edit_title = "약속 편집"`
  - `R.string.commitment_edit_dispute_action = "이의 제기"`
  - `R.string.commitment_edit_undispute_action = "이의 제기 해제"`
  - `R.string.commitment_edit_dispute_dialog_text = "이 인용문이 부정확하거나 오해를 유발한다고 표시하시겠습니까? 원문은 변경되지 않고 이의 제기 표시만 추가됩니다."`
  - `R.string.commitment_edit_delete_action = "삭제"`
  - `R.string.commitment_edit_delete_dialog_text = "이 약속을 삭제하시겠습니까? LLM이 잘못 추출한 경우에만 사용하세요. 실수로 약속이 취소된 경우 [취소]를 사용하세요."`
  - `R.string.commitment_edit_supersede_action = "이건 다른 약속입니다"`
  - `R.string.commitment_edit_validation_title_empty = "제목을 입력해주세요"`
  - `R.string.commitment_edit_validation_title_too_long = "최대 200자까지 입력할 수 있습니다"`
  - `R.string.commitment_edit_validation_due_at_invalid = "유효한 날짜와 시간을 선택해주세요"`
  - (전체 목록은 구현 시 strings.xml 참조)
- DB migration: PR #20 가 이미 처리 (본 plan 의 blocker).

---

## 6. Acceptance Criteria

- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditSheet.kt`
- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditViewModel.kt`
- [ ] **Grep invariant**: `grep -rn "updateEditableFields\|flagQuoteDisputed\|softDelete" android/app/src/main/java/com/becalm/android/data/repository/ | wc -l` ≥ 6 (interface + impl)
- [ ] **Grep invariant**: `grep -rn "quote_disputed\|deleted_at\|last_edited_at\|supersedes_commitment_id" android/app/src/main/java/com/becalm/android/data/local/db/ | wc -l` ≥ 8 (PR #20 머지 후)
- [ ] **Unit test**: `CommitmentEditViewModelValidationTest — title 공백만 입력 → canSave=false, error="제목을 입력해주세요"`
- [ ] **Unit test**: `CommitmentEditViewModelValidationTest — title 201자 → error="최대 200자까지 입력할 수 있습니다"`
- [ ] **Unit test**: `CommitmentEditViewModelValidationTest — person_ref "  ABC@Example.com " → 정규화 "abc@example.com"`
- [ ] **Unit test**: `CommitmentEditViewModelSaveTest — onSaveClick 은 repo.updateEditableFields 1회 호출 + last_edited_by=currentUserId`
- [ ] **Unit test**: `CommitmentEditViewModelDisputeTest — onDisputeConfirm → repo.flagQuoteDisputed(id, true)` 호출
- [ ] **Unit test**: `CommitmentEditViewModelDeleteTest — onDeleteConfirm → repo.softDelete(id)` 호출 후 sheet dismiss 이벤트 방출
- [ ] **UI test**: `CommitmentEditSheetTest — quote 카드는 읽기 전용 (onTextInput 불가)`
- [ ] **UI test**: `CommitmentEditSheetTest — [이의 제기] 버튼 탭 → AlertDialog "이 인용문이 부정확하거나..." 노드 존재`
- [ ] **UI test**: `CommitmentEditSheetTest — [삭제] 탭 → AlertDialog "이 약속을 삭제하시겠습니까?..." 노드 존재`
- [ ] **Grep invariant**: `grep -rn "commitments.quote\s*=\|quote\s*=\s*request.quote" android/app/src/main/java/com/becalm/android/data/ | wc -l` = 0 (quote 문자열 자체는 절대 수정 금지 — EDIT-005 invariant)

---

## 7. Out of Scope

- Supersede 대상 `CommitmentCreateSheet` (quote pre-filled 읽기 전용 + EDIT-007 저장 로직) — `ui-commitment-manual-sheet.md` 가 sheet 재사용 + supersede mode 처리
- EDIT-008 의 `CommitmentDetailSheet` 편집 이력 라벨 렌더 — `ui-commitment-detail-sheet.md` 가 처리
- supersede chain 역추적 UI (`(수정됨 — 이전 버전 보기)` 링크 활성화) — post-MVP (spec EDIT-007 도 "MVP는 DB만" 명시)
- Server-side validation 422 → 클라이언트 rollback — UploadWorker quarantine 경로 (기존 markFailed) 재사용
- "휴지통" / 복원 UI — post-MVP
- 편집 audit log 별도 테이블 — 본 PR 은 last_edited_* 컬럼 1회 덮어쓰기로 단순화

---

## 8. Dependencies

- **Blocked by**:
  - `feat/db/commitment/edit-delete-dispute-supersede` (PR #20) — 6 컬럼 + 2 인덱스 + `deleted_at IS NULL` 필터 선행 필수
  - `ui-commitment-detail-sheet.md` — [편집] 버튼 호스트 화면
  - `db-commitment-due-at-hint-approximate` (PR #17) — due_at/due_hint/due_is_approximate 필드 존재 전제
- **Blocks**:
  - `ui-commitment-manual-sheet.md` 의 supersede mode — 본 plan 의 `onNavigateToSupersede` 콜백 계약에 의존
- **병렬 가능**:
  - `ui-commitment-cancel-action.md`, `ui-commitment-completed-section-undo.md`, `ui-commitment-pull-refresh.md` — 파일 겹침 최소 (CommitmentManagementViewModel.kt 만 공통)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Sheet/VM/Repository 확장 모두 revert. 이미 사용자가 저장한 편집 값 (Room UPDATE) 은 revert 대상 아님 — 데이터는 유지, 편집 UI 만 사라짐. PR #20 의 6 컬럼도 그대로 남음.

급한 롤백이면 `CommitmentEditSheet` 파일만 제거 (`onEditClick = {}` 로 detail sheet 버튼 disabled) → 최소 롤백 가능.

---

## Appendix — Session handoff notes

- **EDIT-007 supersede 저장 로직**: 원자성 — old row `deleted_at` UPDATE + new row INSERT + new row `supersedes_commitment_id = old.id` 를 `withTransaction` 으로 묶음. Railway 측은 POST + PATCH 2 콜. 중간 실패 시 Room 이 inconsistent 될 위험 — `CommitmentRepository.insertSupersede(oldId, newEntity)` 내부에서 트랜잭션 처리. 실패 시 Room 은 기존 상태 롤백.
- **Person_ref 정규화**: 이메일 `lowercase()`, 전화번호는 libphonenumber E.164 시도 — libphonenumber dependency 기존 포함 여부 확인. 없으면 기존 regex-based 정규화 함수 재사용 (`domain/enrichment/` 또는 유사 위치 grep).
- **DatePicker + TimePicker**: Material3 `DatePicker` 와 `TimePicker` 를 조합. due_at nullable → "지우기" 버튼 필요. KST 기본 zone (`ui-commitment-dn-badge-kst.md` 와 동일 `Asia/Seoul`).
- **Autocomplete source**: `PersonEnrichmentRepository.observeEnrichmentMap()` + `CalendarEventRepository` 의 attendee names 합집합. prefix 매치 (case-insensitive). 상위 10 개 제한.
- **Optimistic UPDATE 실패 복구**: 현재 `updateActionState` 패턴은 낙관적 유지 + UploadWorker 재시도. `updateEditableFields` 도 동일 패턴 (Room 은 변경값 유지, sync_status='pending', worker 재시도). 422 (validation) 응답 시 `markFailed` 로 quarantine — 사용자가 다시 편집하면 sync_status='pending' 으로 돌아가 재시도.
- **EDIT-005 이의 제기 해제**: `quote_disputed=false` 로 다시 토글. UI 는 quote_disputed 상태에 따라 같은 버튼의 라벨만 교체. `quote_disputed_at` 는 disputed=true 시점에만 now() 로 UPDATE, false 시 null 로 clear 여부는 구현 세션 결정 (스펙 명시 없음 → 보수적으로 `last_edited_at` 만 갱신, dispute_at 는 유지 권장 — audit trail 보존).
- **cancelled / soft-deleted 편집 차단**: EDIT-001 precondition "action_state NOT IN ('cancelled'), deleted_at IS NULL". `CommitmentDetailSheet` 의 [편집] 버튼 disabled 조건 (`ui-commitment-detail-sheet.md`). Edit Sheet 가 열린 상태에서 동시성으로 deleted_at 이 세팅되면 [저장] 시 422 → Failure Toast "이미 삭제된 약속입니다" (UX decision).
