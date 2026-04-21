# UI / Commitment / Completed Section + Undo — CMT-009 + CMT-013

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: IMPLEMENTED — Wave 4 commit C6.
**E2E Stage**: 5 — Commitment Management
**Severity**: Medium
**Type**: Gap

---

## 1. Finding

Spec CMT-009 requires a `이행 완료` expandable section (dimmed cards) below the active list. Spec CMT-013 requires a 5-second Undo Snackbar for completed/cancelled transitions, reverting to the prior state (with one-shot action). Neither exists — the current screen is a single `LazyColumn` (`CommitmentManagementScreen.kt:53-124`) with no section boundary, no expand/collapse, and no undo plumbing.

---

## 2. Spec Contract

- **`.spec/commitment-management.spec.yml:87-95`** — CMT-009:
  > "`이행 완료` 섹션: 활성 목록 아래 expandable header `이행 완료 (N)`, 기본 접힘. 펼치면 `action_state='completed'` cards 를 dimmed (alpha 0.6) 로 렌더. `취소된 약속` 도 동일 패턴 (separate header)."

- **`.spec/commitment-management.spec.yml:125-133`** — CMT-013:
  > "`completed`/`cancelled` 로 transition 직후 5초 Snackbar `[복구]` 액션 노출. 탭하면 직전 상태로 revert. 타임아웃 후 action_state 는 확정. completed → undo 는 알람 재등록 하지 않음 (알람은 이미 cancel 됨)."

- **`.spec/commitment-management.spec.yml:143`** (invariant):
  > "Undo window 는 5s, one-shot."

---

## 3. Code Reality

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:53-124`** — plain `LazyColumn` without section headers.
- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:269`** — `onMarkDone(id)` performs transition + alarm cancel, **no undo emission**.
- **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt:133-135`** — already dims with `alpha 0.55` when `derivedStatus in (DONE, DISMISSED)`. Compatible; only value may need to align to 0.6 per spec.

```bash
grep -rn "이행 완료\|취소된 약속\|Undo\|Snackbar" android/app/src/main/java/com/becalm/android/ui/commitments/ | wc -l
# expected: very low
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| 완료 섹션 헤더 | `이행 완료 (N)` 접힘 | 없음 | 섹션 + 상태 |
| 취소 섹션 헤더 | `취소된 약속` 접힘 | 없음 | 섹션 + 상태 |
| Dim 카드 | alpha 0.6 | 0.55 | 값 조정 |
| Undo Snackbar | 5초 window, revert | 없음 | 신규 플로우 |
| Prior-state 추적 | 직전 상태 보관 | 없음 | VM state |
| Completed undo → no alarm | 재등록 금지 | N/A | undo 핸들러 guard |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
  — split the LazyColumn into three groups via `items(key)` blocks joined by `item { ExpandableSectionHeader(title, count, expanded, onToggle) }`. Group 1: `action_state in (pending, reminded, followed_up, overdue)`. Group 2: `completed` (collapsed by default). Group 3: `cancelled` (collapsed by default). Pass `Scaffold(snackbarHost = ...)` with VM `snackbarFlow` collection — on emit show `SnackbarWithAction(label = "복구", duration = 5_000ms)`.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  — add `sealed interface UndoSnapshot { val id: String; val priorState: CommitmentActionState }` (subtypes for completed/cancelled). Add `_snackbarFlow = MutableSharedFlow<UndoSnapshot>(replay=0, extraBufferCapacity=1)`. Modify `onComplete(id)` and `onCancel(id)`: fetch prior state → transition → emit snapshot. Add `onUndo(snapshot)` that re-transitions to prior state **without** calling `ReminderScheduler.schedule` (guard: if prior state was `reminded` AND current op is completed-undo, still re-register per spec "completed → undo 는 알람 재등록 하지 않음" ⇒ interpret as "do not re-register for completed undo"; cancelled undo may re-register if prior was reminded — spec is silent ⇒ **default: never re-register from undo; rely on user to press 리마인드 again**). Document this decision inline + in Appendix.
- `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
  — update dim alpha from `0.55f` → `0.6f` to match spec.
- `android/app/src/main/res/values/strings.xml`
  — `commitment_section_completed_fmt = "이행 완료 (%1$d)"`, `commitment_section_cancelled_fmt = "취소된 약속 (%1$d)"`, `commitment_undo_completed = "완료 처리됨"`, `commitment_undo_cancelled = "취소 처리됨"`, `commitment_undo_action = "복구"`.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/components/ExpandableSectionHeader.kt`
  — reusable composable: icon (`chevron`) + title + count chip + toggle.
- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentManagementUndoTest.kt`
  — VM test: `onComplete` emits `UndoSnapshot(id, priorState)`; `onUndo(snapshot)` re-transitions via repo; alarm not re-registered (verify `ReminderScheduler.schedule` not called); 5s timeout simulated via `TestCoroutineScope` — but since Snackbar timer lives in Compose/UI, VM-side test only covers emit + onUndo logic.

### 5.3 Files to delete
- none.

### 5.4 Non-code changes
- none beyond strings.

---

## 6. Acceptance Criteria

- [ ] **Grep**: `grep -n "이행 완료\|취소된 약속" android/app/src/main/res/values/strings.xml` ≥ 2.
- [ ] **Grep — undo emit**: `grep -rn "UndoSnapshot\|_snackbarFlow" android/app/src/main/java/com/becalm/android/ui/commitments/` ≥ 3.
- [ ] **Unit test**: `CommitmentManagementUndoTest` 의 emit + revert 케이스 통과.
- [ ] **No alarm re-register**: test verifies `ReminderScheduler.schedule` called 0 times during `onUndo`.
- [ ] **Alpha**: `grep -n "0.6f\|0.55f" android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt` — 0.6f 1건, 0.55f 0건.
- [ ] **Manual**: 완료 버튼 → Snackbar 표시 → 복구 탭 → 카드 active 섹션으로 복귀. 5초 지나면 복구 불가.

---

## 7. Out of Scope

- Sticky header / 스크롤 offset 애니메이션 (polish 는 별도 plan).
- Offline queue — undo 가 이미 서버 PATCH 이후 일어나면 reverse PATCH 필요. 이 plan 은 **Room-first + `sync_status='pending'`** 패턴 기존 재활용 — 별도 POST/PATCH 불필요.
- Overdue 전용 섹션 (OverdueSweepWorker 자동 처리 → 시각 표기는 배지만).

---

## 8. Dependencies

- **Blocked by**: C2 `ui-commitment-action-state-alignment` (6-value enum 필수); C4 `ui-commitment-detail-sheet` (완료/취소 버튼 시트에서).
- **Blocks**: C7 `ui-commitment-cancel-action` (undo 메커니즘 재사용).

---

## 9. Rollback plan

- Revert. UI 순수 로컬 + 서버 PATCH 는 이미 이전 상태로 감긴 Room UPDATE → `sync_status=pending` 재전송 queue 처리.

---

## Appendix — Session handoff notes

- **Undo 중 알람 정책**: 스펙 L131 "completed → undo 는 알람 재등록 하지 않음" 만 명시. `cancelled → undo (prior=reminded)` 는 silent. 보수적 해석으로 **어느 undo 도 알람 재등록하지 않음**. 사용자가 명시적으로 [리마인드] 재탭하도록 유도.
- Snackbar duration: Material3 `SnackbarDuration.Short` = 4s, `Long` = 10s. 스펙은 5s → `SnackbarDuration.Indefinite` 로 열고 `LaunchedEffect(snapshot) { delay(5_000); currentSnackbarData?.dismiss() }` 로 정확히 5초 제어.
- `UndoSnapshot` 은 VM state 에 하나만 보관 — 새 transition 오면 이전 snapshot 무효화 (one-shot 불변).
- 만약 `action_state='completed'` commitment 에 다시 [완료] 버튼이 눌릴 수 없으므로 자기 자신을 revert 하는 엣지 케이스 방지.
