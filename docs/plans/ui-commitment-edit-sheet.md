# UI / Commitment / Edit Sheet — EDIT-001..008 full wiring

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: IMPLEMENTED — Wave 4 commit C8.
**E2E Stage**: 5 — Commitment Management (PIPA 제36조 correction right)
**Severity**: High
**Type**: Gap

---

## 1. Finding

Spec EDIT-001..008 defines a full editing surface: `CommitmentEditSheet` (title/due_at/due_hint/due_is_approximate/person_ref/direction editable; source + quote + confidence read-only), quote dispute AlertDialog, soft delete, supersede creation. The Wave 1 DB migration (#20) already added all required columns (`last_edited_by`, `last_edited_at`, `quote_disputed`, `quote_disputed_at`, `deleted_at`, `supersedes_commitment_id`) **but no DAO or Repository write paths exist**. No edit UI exists. This is the **PIPA 제36조 correction right** entry point — mandatory for legal MVP.

---

## 2. Spec Contract

- **`.spec/commitment-edit.spec.yml:16-94`** — EDIT-001..008:
  > EDIT-001 entry: detail sheet [편집] button, disabled on cancelled or deleted.
  > EDIT-002 editable fields + read-only fields; `quote` edit MUST-NOT.
  > EDIT-003 save → Room UPDATE + PATCH + auto `last_edited_by`/`last_edited_at`; PATCH fail → `sync_status='pending'` retry.
  > EDIT-004 validation (title 1–200, due_at ISO8601+09:00, person_ref normalize, direction enum).
  > EDIT-005 quote dispute AlertDialog → `quote_disputed=true` + PATCH; quote string MUST-NOT change. Toggle-off via `[이의 제기 해제]`.
  > EDIT-006 soft delete `deleted_at=now()` + PATCH. Distinct from cancelled. All queries MUST `WHERE deleted_at IS NULL`.
  > EDIT-007 supersede → new INSERT with `supersedes_commitment_id=old.id` (quote + source copied) + old soft-delete. POST + PATCH.
  > EDIT-008 detail sheet shows `마지막 수정`, disputed badge, supersede backlink (MVP disabled).

- **`.spec/commitment-edit.spec.yml:96-103`** (invariants):
  > "quote never edited (MUST); edit = Room UPDATE + PATCH + auto last_edited_*; source read-only; delete is soft (MUST-NOT hard DELETE); deleted vs cancelled semantic split MUST."

- **`.spec/pipa-rights.spec.yml:22`** — PIPA-001:
  > "정정 권리 (제36조) 는 `commitment-edit.spec.yml` 을 통해 구현. PrivacyManagementScreen 에서 discoverable 해야 함."

---

## 3. Code Reality

- DB schema v5 columns present (`CommitmentEntity.kt:203-219`).
- `CommitmentDao.kt` — **no** `markDisputed`, `clearDispute`, `softDelete`, `editFields`, `insertSupersede` queries.
- `CommitmentRepository.kt` — no corresponding methods.
- `CommitmentDetailSheet.kt` (from C4) — button wired with TODO callbacks.
- `RailwayApi.kt` PATCH `commitments/{id}` body DTO — may need expansion to cover title/due_at/due_hint/due_is_approximate/person_ref/direction/quote_disputed/deleted_at fields.

```bash
grep -rn "markDisputed\|softDelete\|insertSupersede\|editFields" android/app/src/main/java/
# expected 0
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| Edit sheet UI | EDIT-001..004 | 없음 | 신규 composable + VM |
| Quote dispute | EDIT-005 | 없음 | AlertDialog + repo method |
| Soft delete | EDIT-006 | 컬럼만 | DAO + repo 메소드 |
| Supersede | EDIT-007 | 컬럼만 | 새 INSERT + old soft-delete transaction |
| Edit audit auto | EDIT-003 | 없음 | repo 내부 `last_edited_*` 채움 |
| PATCH body 확장 | EDIT-003 | 제한 | DTO 확장 |
| Detail footer | EDIT-008 | 없음 | C4 plan 에 뼈대. 이 plan 에서 실제 값 binding |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`
  — add:
    - `@Query("UPDATE commitments SET title=:title, due_at=:dueAt, due_hint=:dueHint, due_is_approximate=:approx, person_ref=:personRef, direction=:direction, last_edited_by=:actorId, last_edited_at=:editedAt, updated_at=:editedAt, sync_status='pending' WHERE id=:id") suspend fun applyEdit(...)`
    - `@Query("UPDATE commitments SET quote_disputed=1, quote_disputed_at=:at, last_edited_by=:actor, last_edited_at=:at, updated_at=:at, sync_status='pending' WHERE id=:id") suspend fun markQuoteDisputed(id, actor, at)`
    - `@Query("UPDATE commitments SET quote_disputed=0, quote_disputed_at=NULL, last_edited_by=:actor, last_edited_at=:at, updated_at=:at, sync_status='pending' WHERE id=:id") suspend fun clearQuoteDispute(id, actor, at)`
    - `@Query("UPDATE commitments SET deleted_at=:at, last_edited_by=:actor, last_edited_at=:at, updated_at=:at, sync_status='pending' WHERE id=:id") suspend fun softDelete(id, actor, at)`
    - `@Transaction suspend fun insertSupersedeAndSoftDeleteOld(newRow, oldId, actor, at) { insert(newRow); softDelete(oldId, actor, at) }` — Room transaction.
- `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt` + `Impl.kt`
  — add corresponding suspend functions: `editCommitment(id, patch: CommitmentEditPatch): BecalmResult<Unit>`, `markQuoteDisputed(id): BecalmResult<Unit>`, `clearQuoteDispute(id)`, `softDelete(id)`, `supersede(oldId, newPatch: ManualCommitmentInput): BecalmResult<String>` (returns new id). Each: validate input → `actorId = sessionManager.userId()` → DAO → best-effort PATCH/POST → on failure leave `sync_status='pending'`.
- `android/app/src/main/java/com/becalm/android/data/remote/dto/CommitmentPatchDto.kt`
  — expand PATCH DTO with nullable `title`, `due_at`, `due_hint`, `due_is_approximate`, `person_ref`, `direction`, `quote_disputed`, `deleted_at`, `last_edited_at`, `last_edited_by`. Existing Moshi adapter must treat all as optional.
- `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`
  — add `data class CommitmentEdit(val id: String)` with path `commitments/{id}/edit`.
- `android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`
  — register the edit route.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt`
  — wire `[편집]` button → navigate to edit route. Disable button if `action_state='cancelled' || deleted_at != null`. Bind `마지막 수정` label + disputed badge + supersede backlink to entity fields.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditSheet.kt`
  — full-screen composable (or large M3 modal sheet) with form fields. OutlinedTextField per editable field. DatePicker + TimePicker (KST formatting). `[삭제]` link → `ConfirmationDialog` → `viewModel.onDelete()`. `[이건 다른 약속입니다]` link → `navController.navigate(CommitmentManual(supersedeOf=id))` (C9).
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentEditViewModel.kt`
  — Hilt VM. State: `EditUiState(draft, source, validation, loading, saving, navigationAck)`. Actions: `onTitleChange`, `onDueAtChange`, `onDueHintChange`, `onApproxChange`, `onPersonRefChange`, `onDirectionChange`, `onSave()`, `onDispute()`, `onClearDispute()`, `onDelete()`. Validation uses `CommitmentEditValidator` (5.2 new).
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentEditValidator.kt`
  — pure Kotlin validator per EDIT-004: title length 1..200 after strip, due_at parse ISO8601+09:00 allow null, person_ref normalize lowercase trim + optional E.164 regex, direction `in {give, take}`. Returns `ValidationResult.Ok` or typed errors.
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentEditPatch.kt`
  — data class capturing validated edit.
- `android/app/src/test/java/com/becalm/android/domain/commitment/CommitmentEditValidatorTest.kt`
- `android/app/src/test/java/com/becalm/android/data/repository/CommitmentEditRepositoryTest.kt`
  — covers editCommitment/markDisputed/clearDispute/softDelete/supersede (all 5). MockK Retrofit + in-mem Room.
- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentEditViewModelTest.kt`
  — VM tests for each action.

### 5.3 Files to delete
- none.

### 5.4 Non-code changes
- `strings.xml`:
  - `commitment_edit_title`, `commitment_edit_field_title`, `commitment_edit_field_due`, `commitment_edit_field_due_hint`, `commitment_edit_field_approx`, `commitment_edit_field_person`, `commitment_edit_field_direction_give`, `commitment_edit_field_direction_take`.
  - `commitment_edit_quote_readonly_label = "원문 (수정 불가)"`.
  - `commitment_edit_dispute_button = "이 인용이 잘못됐습니다"`.
  - `commitment_edit_dispute_clear = "이의 제기 해제"`.
  - `commitment_edit_delete_button = "이 약속 삭제"` + confirm dialog strings.
  - `commitment_edit_supersede_button = "이건 다른 약속입니다"`.
  - `commitment_last_edited_fmt = "마지막 수정: %1$s (본인)"`.
  - `commitment_disputed_badge_fmt = "⚠ 원문 이의 제기됨 (%1$s)"`.
  - validation error strings.

---

## 6. Acceptance Criteria

- [ ] **DB invariant grep**: `grep -rn "deleted_at" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt | wc -l` ≥ 6 (모든 observe 쿼리 + findById 가 `WHERE deleted_at IS NULL` 필터 포함 확인).
- [ ] **Quote invariant**: `grep -rn "UPDATE commitments SET quote" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt` == 0.
- [ ] **Unit — validator**: `CommitmentEditValidatorTest` 의 8+ 케이스 통과 (title 경계, due_at parse, person_ref normalize, direction enum).
- [ ] **Unit — repo**: `CommitmentEditRepositoryTest` 의 5 메소드 전부 happy + PATCH-fail 재시도 케이스 통과.
- [ ] **Unit — VM**: `CommitmentEditViewModelTest` 의 save 성공 / validation 실패 / dispute toggle / softDelete 4+ 케이스 통과.
- [ ] **Supersede transaction**: `insertSupersedeAndSoftDeleteOld` Room `@Transaction` 애노테이션 존재 (`grep -B1 "insertSupersedeAndSoftDeleteOld" ...`).
- [ ] **PATCH DTO**: `CommitmentPatchDto` has optional fields for all editable columns (grep).
- [ ] **Compile**: `:app:assembleDebug` green.
- [ ] **Manual**: detail → 편집 → 저장 → 카드에 `마지막 수정` 표기; 이의 제기 → 배지; 삭제 → 목록에서 사라짐; 재실행 후에도 사라진 상태 유지 (soft-delete filter).

---

## 7. Out of Scope

- 서버 DELETE 엔드포인트 (soft-delete 이므로 PATCH 로 충분).
- 서버 validation — 클라이언트 검증만 신뢰.
- Supersede 백링크 UI 활성화 (MVP 비활성, EDIT-008).
- 다중 액터 편집 병합 (`last_edited_by` 만 기록).
- Offline queue retry 고급화 — 기존 `sync_status='pending'` 재사용.

---

## 8. Dependencies

- **Blocked by**: C2 (action-state-alignment — 새 enum 이 기본값), C4 (detail sheet 에서 진입). DB Wave 1 #20 (이미 머지).
- **Blocks**: C9 `ui-commitment-manual-sheet` (supersede 가 manual sheet 를 재사용; 다만 이 plan 은 supersede trigger 만, sheet 본체는 C9 에서).

---

## 9. Rollback plan

- Revert. 이미 편집된 commitment 는 Room 에 저장된 상태로 유지 (데이터 손실 없음). PATCH 실패 행은 `sync_status='pending'` 큐에서 재시도 — revert 해도 손실 없음. 단, 스키마는 그대로 v6 에서 유지되므로 Room 마이그레이션 되감기 불필요.

---

## Appendix — Session handoff notes

- **Quote invariance**: DAO 에 `UPDATE commitments SET quote=` 쿼리를 **절대** 만들지 않음. CI-level 가드로 `.github/` 에 grep 체크 추가 고려 (out of scope 로 보류).
- **Edit audit semantics**: PIPA 제36조 correction → `last_edited_by` 기록이 곧 감사 로그. `pipa_action_log` 와 분리되어 있다는 점 중요 (spec 확인).
- **Supersede**: 이 plan 에선 **trigger** 만 — `[이건 다른 약속입니다]` 버튼 → `CommitmentManualSheet(mode=Supersede(oldId))` navigate. 실제 supersede INSERT + softDelete 은 VM 의 `onSupersedeConfirmed(input)` 에서 repository 의 `supersede(oldId, newPatch)` 를 호출. DAO 는 `@Transaction` 안에서 둘 다 원자적.
- **Date picker**: Material3 `rememberDatePickerState` 로 충분. Time: `rememberTimePickerState`. KST rendering: `LocalDateTime.toInstant(KST_OFFSET).toEpochMilliseconds()`.
- **person_ref autocomplete**: `PersonEnrichmentRepository.observeByUserAllPersons(userId)` 가 있으면 사용; 없으면 simple text field + 정규화만 구현 (autocomplete 은 별도 plan 에서 enrich).
