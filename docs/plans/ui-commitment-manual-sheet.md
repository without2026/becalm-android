# UI / Commitment / Manual Add Sheet — MAN-001..006 + EDIT-007 supersede reuse

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: IMPLEMENTED — Wave 4 commit C9.
**E2E Stage**: 5 — Commitment Management
**Severity**: High
**Type**: Gap

---

## 1. Finding

Spec MAN-001..006 requires a manual commitment creation FAB on `CommitmentManagementScreen` that opens a sheet with the user-written `quote` (1..500 chars, multi-line) and standard commitment fields. Saving POSTs to `/v1/commitments` with `source_type='manual'`, `source_ref=null`, `source_event_title=null`, `source_event_occurred_at=now()`, `confidence=1.0`, **without** creating a `raw_ingestion_events` row. LLM and retention workers must skip manual rows. The same sheet is reused by EDIT-007 supersede (`quote` + `source_*` pre-filled read-only from old row). No FAB, no sheet, no VM exist.

---

## 2. Spec Contract

- **`.spec/manual-commitment.spec.yml:16-74`** — MAN-001..006 (full spec already extracted in research).
- **`.spec/manual-commitment.spec.yml:76-83`** (invariants):
  > "source_type='manual' MUST; confidence=1.0; source_ref=null; source_event_title=null; source_event_occurred_at=created_at; quote 1..500 required; MUST-NOT create raw_ingestion_events row; LLM pipelines skip manual; action_state/edit/alarm identical to LLM commitments."
- **`.spec/commitment-edit.spec.yml:76-84`** — EDIT-007:
  > "supersede → 새 INSERT (`supersedes_commitment_id=old.id`, quote + source copied from old) + old soft-delete. 같은 sheet 재사용 (manual 모드 = empty form; supersede 모드 = pre-filled read-only quote/source)."

---

## 3. Code Reality

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`** — no FAB.
- **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`** — no `saveManualCommitment`.
- **`android/app/src/main/java/com/becalm/android/data/remote/RailwayApi.kt`** — existing `postCommitmentBatch` covers LLM upload; need plain `POST /v1/commitments` single-row DTO.
- **`android/app/src/main/java/com/becalm/android/worker/extraction/CommitmentExtractionWorker.kt`** — does not filter `source_type='manual'` (moot because manual rows never have `raw_ingestion_events` association, but retention/coldsync workers need explicit skip).
- **`android/app/src/main/java/com/becalm/android/worker/sync/ColdSyncOrchestrator.kt`** (or retention sweep) — need `WHERE source_type != 'manual'` when iterating rows tied to raw_event lifecycle.

```bash
grep -rn "source_type.*manual\|saveManualCommitment\|MANUAL" android/app/src/main/java/
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| FAB | `+ 약속 추가` | 없음 | 신규 |
| Manual sheet UI | MAN-001..005 | 없음 | 신규 composable + VM |
| Supersede 모드 | EDIT-007 | 없음 | 동일 sheet 재사용 |
| Repository save | `saveManualCommitment` | 없음 | 신규 |
| POST DTO | single-row | batch only | 추가 |
| `📝 수동 추가` 카드 배지 | MAN-004 | 없음 | CommitmentCard 조건부 |
| LLM/retention skip | manual 우회 | 없음 | worker guard |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
  — add `ExtendedFloatingActionButton(text = stringResource(R.string.commitment_fab_add), icon = Icons.Default.Add, onClick = onAddManual)` to `Scaffold.floatingActionButton`. Forward click to nav.
- `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`
  — add `data class CommitmentCreate(val supersedeOf: String?)` with path `commitments/new?supersedeOf={supersedeOf}` (nullable arg via defaultValue).
- `android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`
  — register the route.
- `android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`
  — ensure `MANUAL = "manual"` is defined (from #21 — already merged). Verify constant and import.
- `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt` + `Impl.kt`
  — add `suspend fun saveManualCommitment(input: ManualCommitmentInput, supersedeOf: String?): BecalmResult<String>`. Behavior: INSERT Room row (id = UUID; `source_type=MANUAL`; `source_ref=null`; `source_event_title=null`; `source_event_occurred_at=createdAt=now`; `confidence=1.0`; `last_edited_by=actorId`; `last_edited_at=now`; `sync_status='pending'`; `supersedes_commitment_id=supersedeOf`). If `supersedeOf != null`: also `softDelete(supersedeOf)` in same `@Transaction`. Async best-effort POST (`RailwayApi.postCommitment(singleDto)`). On failure leave `sync_status='pending'`.
- `android/app/src/main/java/com/becalm/android/data/remote/RailwayApi.kt`
  — `@POST("v1/commitments") suspend fun postCommitment(@Body dto: CommitmentDto): Response<CommitmentDto>` (if not present). Existing batch stays.
- `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
  — add `isManual: Boolean` param; when true, render `📝 수동 추가` chip near title (alongside direction stripe).
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  — map entity.source_type=MANUAL → `CommitmentRow.isManual=true`.
- `android/app/src/main/java/com/becalm/android/worker/retention/RetentionSweepWorker.kt` (if exists)
  — ensure selector predicate `source_type != 'manual'` OR joins on `raw_ingestion_events` (implicit skip). Add unit test to pin behavior.
- `android/app/src/main/java/com/becalm/android/worker/extraction/CommitmentExtractionWorker.kt`
  — defensive: when iterating commitments, predicate `source_type != 'manual'`. Spec MAN-006 mandates LLM skip.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateSheet.kt`
  — composable with mode toggle (manual vs supersede). Supersede mode: quote + source section read-only, pre-filled from `viewModel.oldEntity`. Manual mode: empty. Direction RadioGroup default `give`. Title TextField. Quote multi-line TextField (3 rows). person_ref TextField. Due date+time optional picker. `due_is_approximate` checkbox → enables `due_hint` TextField.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateViewModel.kt`
  — Hilt VM receives `supersedeOf` from SavedStateHandle. If non-null: `flatMap repository.observeById(supersedeOf)` into state.oldEntity. Validation via `CommitmentManualValidator`. `onSave()` → validator → `repository.saveManualCommitment(input, supersedeOf)` → navigate back.
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentManualValidator.kt`
  — pure: title 1..200, quote 1..500 (manual), direction enum, person_ref optional normalize, due_at optional parse.
- `android/app/src/main/java/com/becalm/android/domain/commitment/ManualCommitmentInput.kt`
  — validated data class.
- `android/app/src/test/java/com/becalm/android/domain/commitment/CommitmentManualValidatorTest.kt`
- `android/app/src/test/java/com/becalm/android/data/repository/CommitmentManualRepositoryTest.kt`
  — happy + supersede + POST-fail queue. MockK Retrofit + in-mem Room.
- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentCreateViewModelTest.kt`

### 5.3 Files to delete
- none.

### 5.4 Non-code changes
- `strings.xml`:
  - `commitment_fab_add = "+ 약속 추가"`, `commitment_fab_add_content_desc`.
  - `commitment_manual_badge = "📝 수동 추가"`.
  - `commitment_manual_sheet_title`, field labels, `commitment_manual_quote_hint = "상대의 약속 내용을 메모하세요 (1~500자)"`.
  - `commitment_manual_source_manual_fmt = "사용자 직접 추가 %1$s KST"`.
  - `commitment_supersede_quote_readonly_label`.
  - validation errors.

---

## 6. Acceptance Criteria

- [ ] **Grep — source_type=manual**: `grep -rn "SourceType.MANUAL\|\"manual\"" android/app/src/main/java/com/becalm/android/data/repository/` ≥ 1.
- [ ] **Grep — no raw_event on manual**: `grep -rn "rawIngestionEventDao.insert\|rawEvent.*insert" android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt` — the manual path MUST NOT reach this. Test asserts.
- [ ] **Unit — validator**: `CommitmentManualValidatorTest` 의 7+ 케이스 (quote 경계 1/500/빈값/501자, direction default, person_ref normalize).
- [ ] **Unit — repo**: `CommitmentManualRepositoryTest` 3+ 케이스 (happy, supersede transaction, POST-fail queue).
- [ ] **Unit — VM**: `CommitmentCreateViewModelTest` supersede 모드 prefill + manual 모드 empty 둘 다 검증.
- [ ] **Badge**: `CommitmentCard` 테스트 또는 preview 로 `isManual=true` 시 배지 렌더.
- [ ] **Compile**: `:app:assembleDebug` green.
- [ ] **Manual**: FAB → sheet → 저장 → 카드 상단에 `📝 수동 추가` 배지 보임; detail sheet 의 source 섹션 `사용자 직접 추가 ... KST` 표기.

---

## 7. Out of Scope

- LLM-driven 음성/이메일 → commitment 추출 변경 (별도).
- Naver/Gmail 뉴스레터 auto-detection (source_type 별건).
- Server-side POST 검증 / rate-limit 정책 변경.
- Autocomplete (person_ref) — 나중에 enrichment 를 붙일 때.
- Supersede UI 자체의 "선택" flow — EDIT-007 에서 old 를 특정하는 진입은 edit-sheet 의 `[이건 다른 약속입니다]` 버튼 1개 경로.

---

## 8. Dependencies

- **Blocked by**: C2 (enum), C4 (detail sheet host; supersede 접근 경로), C8 (edit-sheet 의 `[이건 다른 약속입니다]` 진입 버튼). Wave 1 #20, #21 (이미 머지).
- **Blocks**: 없음 (Wave 4 마지막 커밋).

---

## 9. Rollback plan

- Revert. manual 로 생성된 기존 행은 Room 에 남지만 POST 가 실패해도 `sync_status=pending` 큐로 회수. revert 해도 데이터 손실 없음.

---

## Appendix — Session handoff notes

- **`source_type='manual'` 이지만 SOURCE_TYPE_ALL 에서 제외** — `SourceTypes.PRODUCT_SOURCES` 에 MANUAL 이 없어야 함. 기존 `repo-commitment-source-type-manual` (#21, Wave 1) 에서 확인.
- **Raw event 금지**: manual commitment 는 `raw_ingestion_events` 행을 만들지 않음. repository 의 manual save 경로에서 `rawEventDao` 를 절대 호출하지 말 것.
- **Supersede 시 quote 복사**: `old.quote` 그대로 복사 (EDIT-007). 새 행의 quote 는 사용자가 편집 가능한가? spec 은 "quote + source copied" + sheet 에서 **read-only** 라고 명시 (manual-commitment.spec.yml L18 + edit L82). 즉 supersede 모드에선 quote 편집 불가. manual 모드에서만 입력.
- **confidence=1.0**: spec 명시. LLM 신뢰도 값이 아닌 user 직접 = 100% 확신이라는 의미.
- **source_event_occurred_at=created_at**: spec MAN-003. Room DEFAULT 가 아니라 repo 가 `now()` 동일값으로 세팅.
- **strings 의 이모지 📝**: UI 스펙 명시. Compose Text 에 그대로 렌더.
- **POST DTO**: 기존 batch DTO 는 `List<CommitmentDto>` 래퍼가 있을 수 있음 — single-row endpoint 가 없으면 `postCommitmentBatch(listOf(dto))` 재사용 가능. RailwayApi 인터페이스 확인 후 결정.
