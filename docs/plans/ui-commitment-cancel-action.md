# UI / Commitment / Cancel Action — CMT-012

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: IMPLEMENTED — Wave 4 commit C7.
**E2E Stage**: 5 — Commitment Management
**Severity**: Medium
**Type**: Gap

---

## 1. Finding

Spec CMT-012 requires a `[취소]` button on `CommitmentDetailSheet` that transitions `action_state='cancelled'`, cancels the AlarmManager registration, moves the card into the '취소된 약속' collapsed section, and triggers the Undo Snackbar (reusing CMT-013 mechanism). Current code has **no cancel handler** anywhere in VM or repo beyond the legacy SP-36 `Dismiss` event.

---

## 2. Spec Contract

- **`.spec/commitment-management.spec.yml:115-123`** — CMT-012:
  > "[취소] → `action_state='cancelled'` + PATCH `/v1/commitments/{id}` + `AlarmManager.cancel` + 카드 '취소된 약속' 섹션으로 이동 + CMT-013 Undo Snackbar 트리거."

- **`.spec/commitment-management.spec.yml:141`** (transition invariant):
  > "cancelled 는 user-initiated terminal state (overdue 와 달리 system 이 설정하지 않음)."

---

## 3. Code Reality

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:286`** — `onDismiss(id)` uses legacy `CommitmentEvent.Dismiss` → `action_state='dismissed'` (legacy value).
- **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`** — no `cancel(id)` function; only generic `transitionState`.
- **`android/app/src/main/java/com/becalm/android/worker/reminder/ReminderScheduler.kt`** — `cancel(id)` exists (verified via Research).

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| [취소] 버튼 | Detail sheet | 없음 | C4 시트에 추가 |
| VM `onCancel(id)` | cancel 전이 | `onDismiss` (legacy) | 교체 |
| action_state = 'cancelled' | 6-value | 'dismissed' | C2 후 정합 |
| AlarmManager.cancel | 알람 취소 | scheduler.cancel 있음 | wiring |
| 섹션 이동 | '취소된 약속' | N/A | C6 섹션 재사용 |
| Undo snackbar | CMT-013 | N/A | C6 재사용 |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  — remove `onDismiss` (legacy). Add `fun onCancel(id: String)`:
    1. fetch entity prior state via `repository.findById(id)`;
    2. `repository.transitionState(id, Cancel)` (new event from C2);
    3. `reminderScheduler.cancel(id)` (idempotent);
    4. emit `UndoSnapshot(id, priorState)` into `_snackbarFlow` (from C6).
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt` (from C4)
  — add `[취소]` button to action strip, enabled when `action_state in (pending, reminded, followed_up, overdue)`. onClick → `viewModel.onCancel(id)` + sheet dismiss.
- `android/app/src/main/res/values/strings.xml`
  — `commitment_action_cancel = "취소"`.

### 5.2 Files to add

- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentCancelActionTest.kt`
  — VM test: (a) `onCancel(id)` calls `transitionState(Cancel)` + `ReminderScheduler.cancel(id)` + emits snapshot; (b) `priorState` captured matches fetched entity; (c) re-emits on each call (one-shot semantics handled by C6).

### 5.3 Files to delete
- VM `onDismiss` removed.
- `CommitmentEvent.Dismiss` (C2 already removes; this commit just clears the last caller).

### 5.4 Non-code changes
- strings.xml addition.

---

## 6. Acceptance Criteria

- [ ] **Grep — no legacy**: `grep -rn "onDismiss\|CommitmentEvent.Dismiss" android/app/src/main/java/` == 0.
- [ ] **Grep — cancel wiring**: `grep -rn "onCancel" android/app/src/main/java/com/becalm/android/ui/commitments/` ≥ 2.
- [ ] **Unit test**: `CommitmentCancelActionTest` 의 3 케이스 통과.
- [ ] **Alarm cancel**: test verifies `ReminderScheduler.cancel` 호출.
- [ ] **Section**: UI test or manual — 취소된 카드가 '취소된 약속' 섹션에 표시됨.

---

## 7. Out of Scope

- 서버 DELETE (cancel 은 soft state 전이 — 행 제거 아님).
- `deleted_at` soft-delete 와 혼동 금지 (그건 edit-sheet 소관, EDIT-006).

---

## 8. Dependencies

- **Blocked by**: C2 (enum), C4 (detail sheet host), C6 (undo mechanism).
- **Blocks**: 없음.

---

## 9. Rollback plan

- Revert. `ReminderScheduler.cancel` 은 idempotent — rollback 이후 취소된 commitment 의 알람이 다시 살아나지 않음 (복구 버튼으로 이미 처리).

---

## Appendix — Session handoff notes

- `cancelled` 와 `dismissed` 는 **다르다**. 레거시 `dismissed` 는 스팸성 인식("이건 commitment 가 아니다") 에 가까웠고, spec 의 `cancelled` 는 "실행하지 않기로 결정" 에 가깝다. 의미가 다르지만 현재 UI 진입점이 하나이므로 `dismissed` → `cancelled` rename + 제거로 통합.
- `deleted_at` (soft-delete) 는 EDIT-006 소관. `cancelled` 는 `action_state` 전이이며 행은 그대로 남음.
- 알람이 이미 과거가 되어 fire 된 뒤 user 가 cancel 을 누를 수 있음 → `ReminderScheduler.cancel` 은 no-op (PendingIntent 없음) — 안전.
