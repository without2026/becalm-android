# UI / Commitment / cancel-action — [취소] 버튼 / action_state='cancelled' 전이 / AlarmManager 해제 부재

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentDetailSheet 액션
**Severity**: Medium (약속 폐기 경로 자체가 없음. 사용자는 잘못된 약속 / 무효화된 약속을 완료 처리하거나 계속 방치해야 함.)
**Type**: Gap (CMT-012 전체 미구현)

---

## 1. Finding

`CommitmentManagementViewModel` 은 action_state 전이 중 `pending → reminded → followed_up → completed` 경로만 노출한다. `cancelled` 로의 전이 핸들러 (`onCancel`) 자체가 존재하지 않는다. `CommitmentCard` 상에도 [취소] 버튼 UI 는 없다. `CommitmentDetailSheet` 도 아직 파일이 없으므로 (`ui-commitment-detail-sheet.md` 참조), 취소 경로를 위한 UI + VM + AlarmManager 해제 로직을 본 plan 에서 함께 도입한다.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:115-123` CMT-012**:
  > "[취소] 버튼 탭 시 action_state='cancelled'로 전이한다 — 약속 자체가 무효화됐거나 LLM 오추출이어서 사용자가 더 이상 추적할 필요가 없음을 의미. completed와 달리 '이행 완료'가 아닌 '폐기'로 별도 집계"
  > screen: "CommitmentDetailSheet"
  > expected: "Room의 commitment action_state='cancelled'로 UPDATE됨. PATCH /v1/commitments/{id} {action_state:'cancelled'} 호출됨. 카드가 기본 목록에서 사라지고 '취소된 약속' 섹션(접힌 상태)으로 이동. CMT-005에서 등록된 AlarmManager alarm이 있으면 AlarmManager.cancel()로 해제. 사용자에게 CMT-013 undo Snackbar 표시"

- **`.spec/commitment-management.spec.yml:139` invariant**:
  > "AlarmManager 알림은 action_state IN ('completed','cancelled') 약속에 대해 발송하지 않는다"
  → Silent drop 은 `ui-commitment-reminder-due-gate.md` 가 담당. 본 plan 은 cancel 시점에 **사전** alarm 해제 (silent drop fallback 전 단계).

- **`.spec/commitment-management.spec.yml:141` invariant**:
  > "action_state 전이는 데이터 모델에 정의된 transition matrix를 따른다 — 불법 전이(예: completed→reminded)는 클라이언트·서버 양쪽에서 422로 거부"

- **`.spec/commitment-management.spec.yml:142` invariant**:
  > "overdue 전이는 시스템 자동(CMT-011)이며 사용자 수동으로 overdue 설정할 수 없다 — 사용자는 cancelled로만 명시적 폐기 가능"

- **`.spec/commitment-edit.spec.yml:101` invariant**:
  > "[삭제](deleted_at)와 [취소](action_state='cancelled')는 의미 구분된다: 삭제=잘못 추출된 기록 제거, 취소=유효한 약속이 무효화됨. 사용자 UI도 별도 CTA로 분리"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:196-284`
현재 공개 핸들러: `onErrorDismissed`, `onFilterChange`, `onConfirm`, `onSchedule`, `onMarkDone`, `onDismiss`. `onCancel` 없음.
```bash
grep -n "onCancel\|\"cancelled\"\|CANCELLED" \
  android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt
# → empty
```

### 3.2 `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentState.kt`
```bash
grep -n "CANCELLED\|cancelled" android/app/src/main/java/com/becalm/android/domain/commitment/
# → (확인 결과 empty)
```
→ state machine 에도 CANCELLED 값이 없음. 단, `action_state` 문자열은 state machine 과 분리되어 있으므로 (`CommitmentEntity.actionState`), 본 PR 은 **state machine 에 CANCELLED 전이 추가 여부를 결정**해야 함.
- 옵션 A: `CommitmentStateMachine` 과 무관하게 `action_state` 문자열만 UPDATE (VM 이 repo.updateActionState 직접 호출).
- 옵션 B: state machine 에 `Cancel` event + `CANCELLED` state 추가.
- 스펙 관점에서 action_state 는 데이터 컬럼, commitment_state 는 SP-36 lifecycle 이므로 분리된 축. CMT-012 는 action_state 만 언급 → **옵션 A 채택**.

### 3.3 `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt:88-92`
```kotlin
public suspend fun updateActionState(
    id: String,
    newState: String,
    updatedAt: Instant,
): BecalmResult<Unit>
```
→ `newState = "cancelled"` 를 그대로 전달 가능. Repository 확장 불필요 (문자열 free-form). 단, DAO 쪽 validation 이 있는지 확인 필요.

### 3.4 `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
→ 카드 레벨에 [취소] 버튼 추가는 UX 노이즈 → 상세 시트 안에만 배치 (CMT-012 screen = CommitmentDetailSheet).

### 3.5 `android/app/src/main/java/com/becalm/android/domain/reminder/ReminderScheduler.kt:80-86`
```kotlin
public fun cancel(commitmentId: String) {
    val requestCode = commitmentIdToRequestCode(commitmentId)
    val pi = buildPendingIntent(commitmentId, requestCode)
    alarmManager.cancel(pi)
    pi.cancel()
}
```
→ API 이미 존재. 재사용만.

검증 grep:
```bash
grep -rn "\"cancelled\"\|onCancel\b" android/app/src/main/java/com/becalm/android/ui/commitments/
# → empty
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| [취소] 버튼 UI | CommitmentDetailSheet 내 | 없음 | Sheet 에 button 추가 (`ui-commitment-detail-sheet.md` 연계) |
| VM 핸들러 | `onCancel(id)` | 없음 | 신규 메서드 |
| action_state 전이 | `cancelled` | 미지원 | `repo.updateActionState(id, "cancelled", now())` 호출 |
| AlarmManager 해제 | `ReminderScheduler.cancel(id)` | 호출 지점 없음 | onCancel 내에서 호출 |
| Undo Snackbar | 5초 내 복원 | 없음 | `ui-commitment-completed-section-undo.md` 의 pendingUndo 파이프라인 재사용 |
| "취소된 약속" 섹션 | 접힘 섹션 | 없음 | Out of Scope (별도 plan or 본 plan 확장) |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt`** (`ui-commitment-detail-sheet.md` 가 먼저 생성)
   - action_state 버튼 Row 에 `[취소]` Button 추가.
   - `enabled = actionState in {"pending","reminded","followed_up","overdue"}`.
   - 탭 시 `viewModel.onCancel(id)` 호출.

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailViewModel.kt`** (`ui-commitment-detail-sheet.md` 가 먼저 생성)
   - 신규 `onCancel(id: String)`:
     ```
     1. 현재 actionState 스냅샷 캡처 → pendingUndo
     2. commitmentRepository.updateActionState(id, "cancelled", now())
     3. reminderScheduler.cancel(id)   // alarm 등록되어 있었다면 해제
     4. UI: Undo Snackbar 트리거 (문구: "취소되었습니다.")
     ```
   - 구현 세션 주의: 이미 `onMarkDone` 이 `CommitmentManagementViewModel` 에 있고 `reminderScheduler.cancel` 도 호출함. 이 패턴 재사용. Detail VM 의 메서드는 `launchAction` 헬퍼 동일 패턴.

3. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`**
   - `UndoSnapshot.transitionType` enum 에 `CANCELLED` 추가 (`ui-commitment-completed-section-undo.md` 와 결합).
   - VM 레벨에서는 `onCancel` 을 직접 노출하지 않음 (detail VM 이 담당). 단, `pendingUndo` state 공유 방식은 `ui-commitment-completed-section-undo.md` 가 다룸.

4. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`** — 확인만
   - `updateActionState` 쿼리가 문자열 free-form 이면 변경 불필요.
   - DAO 레벨에 enum check 가 있으면 "cancelled" 추가 — 현재 없음 (문자열 free-form 확인됨).

### 5.2 Files to add

- **테스트**: `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentDetailViewModelCancelTest.kt`

### 5.3 Files to delete
없음.

### 5.4 Non-code changes

- `strings.xml`:
  - `R.string.commitment_detail_action_cancel = "취소"`
  - `R.string.commitments_undo_cancelled = "취소되었습니다."` (이미 `ui-commitment-completed-section-undo.md` 가 추가하면 공유)

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "onCancel\b" android/app/src/main/java/com/becalm/android/ui/commitments/ | wc -l` ≥ 2 (VM 선언 + Sheet 호출)
- [ ] **Grep invariant**: `grep -rn "\"cancelled\"" android/app/src/main/java/com/becalm/android/ui/commitments/ | wc -l` ≥ 1
- [ ] **Unit test**: `CommitmentDetailViewModelCancelTest — onCancel 은 repo.updateActionState(id, "cancelled", _) 호출 1회 + reminderScheduler.cancel(id) 호출 1회`
- [ ] **Unit test**: `CommitmentDetailViewModelCancelTest — 직전 action_state 가 "reminded" 이면 pendingUndo.prevState == "reminded"`
- [ ] **Unit test**: `CommitmentDetailViewModelCancelTest — updateActionState Failure 시 reminderScheduler.cancel 호출 안 함`
- [ ] **UI test**: `CommitmentDetailSheetTest — action_state="cancelled" 이면 [취소] 버튼 disabled`
- [ ] **Manual**: 취소 후 활성 목록에서 카드 사라짐 (completedItems / cancelledItems 분리 로직 완료 후 검증)

---

## 7. Out of Scope

- "취소된 약속" 섹션 UI (접힘) — 본 PR 은 **액션** 자체만. 섹션 렌더는 `ui-commitment-completed-section-undo.md` 에서 symmetric 확장 또는 후속 plan.
- Undo Snackbar 호스트 / pendingUndo state — `ui-commitment-completed-section-undo.md` 담당
- AlarmReceiver 의 silent drop (알람 시각 도달해도 cancelled 면 notify 생략) — `ui-commitment-reminder-due-gate.md` 담당
- [취소] 와 [삭제] UX 분리 다이얼로그 / 혼동 방지 — 별도 UX plan
- CMT-011 overdue 상태에서 [취소] 가능 여부 — 스펙은 "pending/reminded/followed_up/overdue" precondition 명시 → 허용. 구현 세션 확인.

---

## 8. Dependencies

- **Blocked by**:
  - `ui-commitment-detail-sheet.md` — 취소 버튼 호스트 화면
  - `ui-commitment-completed-section-undo.md` — Undo 인프라
- **Blocks**: "취소된 약속" 섹션 (있다면)
- **병렬 가능**: `ui-commitment-reminder-due-gate.md` (silent drop 은 Receiver 책임, 여긴 scheduler.cancel 선제 해제)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

VM 메서드 + Sheet 버튼 추가만. Revert 시 [취소] 기능 사라지고 Room 의 action_state 컬럼은 그대로. 이미 `cancelled` 로 설정된 row 는 남아 있으며 기존 active filter 에서도 제외되지 않는다 — revert 후에는 해당 row 가 다시 활성 목록에 표시됨. 롤백 안정성 위해서는 `section split` 로직 (`ui-commitment-completed-section-undo.md`) 머지 후 본 plan 을 머지하는 순서 권장.

---

## Appendix — Session handoff notes

- action_state 문자열 "cancelled" 는 data-model.yml enum 에 포함되어야 함. PR #17 까지 action_state enum 확장 여부 확인. 없으면 DB plan 에서 선행 처리 필요.
- `reminderScheduler.cancel(id)` 는 idempotent — alarm 미등록이어도 예외 없이 return. 따라서 onCancel 내에서 unconditional 호출 OK.
- Detail VM 에서 actionState 를 읽을 때는 Room 의 최신값 기준 (snapshot). StateFlow 의 현재 value 사용 — race 방지를 위해 `commitmentRepository.findById` suspend 재조회가 더 안전.
- Undo 시 AlarmManager 재등록 여부: `ui-commitment-completed-section-undo.md` 의 설계와 동일하게 **재등록 안함** (스펙 CMT-013 은 completed→undo 만 명시, cancelled→undo 도 same behaviour 가정 — 사용자가 다시 [리마인드] 필요).
