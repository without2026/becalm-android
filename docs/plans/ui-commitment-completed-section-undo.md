# UI / Commitment / completed-section-undo — 접힘 '이행 완료' 섹션 + 5초 Undo Snackbar

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentManagement 화면 / Snackbar 호스트
**Severity**: Medium (주 기능 동작에는 영향 없으나 UX parity 에서 드리프트 — 완료 카드가 활성 목록에 섞여 시각 노이즈가 커지고, [완료]/[취소] 오탭 시 복원 경로 없음.)
**Type**: Gap (CMT-009, CMT-013 모두 화면/핸들러 부재)

---

## 1. Finding

`CommitmentManagementScreen` 은 모든 commitment 를 단일 `LazyColumn` 에 쏟아낸다 (`CommitmentManagementScreen.kt:99-117`). "이행 완료" 섹션 헤더, 접힘 상태, 펼침 토글, 완료 카드의 dimmed 처리가 모두 없다. ViewModel 의 `applyFilter` 는 direction 필터만 처리할 뿐 `action_state='completed'` 를 분리하지 않는다 (`CommitmentManagementViewModel.kt:325-345`).

Undo Snackbar 경로 역시 부재. `[완료]` 액션은 `onMarkDone` 으로 전달되지만 (`:259-267`) 직전 action_state 를 기억하거나 5초 window 로 되돌릴 훅이 없다. `[취소]` 는 그 자체가 아직 구현 전이다 (`ui-commitment-cancel-action.md` 에서 추가).

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:86-94` CMT-009**:
  > "완료된 약속 섹션 — '이행 완료' 섹션이 활성 약속 목록 아래에 접힌 상태로 표시된다. 탭 시 펼쳐져 completed commitments 목록이 표시됨"
  > expected: "상단: pending 1건 표시됨. 하단: '이행 완료 (2)' 섹션 헤더 표시됨. 헤더 탭 시 completed 2건 펼쳐짐. 완료 카드는 회색 처리(dimmed)됨"

- **`.spec/commitment-management.spec.yml:125-133` CMT-013**:
  > "action_state 변경(completed 또는 cancelled) 후 5초 이내 Undo Snackbar 제공 — [실행 취소] 탭 시 직전 action_state로 복원한다. 다른 action_state 전이(reminded/followed_up/overdue)에는 Undo 없음"
  > expected: "Snackbar: '완료로 변경되었습니다. [실행 취소]' (또는 '취소되었습니다. [실행 취소]'). [실행 취소] 탭 시 Room의 action_state가 직전 값(예: pending/reminded/followed_up/overdue)으로 UPDATE됨. PATCH /v1/commitments/{id} {action_state: 직전값} 호출됨. completed→undo 시 해제됐던 AlarmManager alarm은 재등록하지 않음(사용자가 다시 [리마인드] 필요). 5초 dismiss 시 Snackbar 사라지며 전이는 확정됨"

- **`.spec/commitment-management.spec.yml:143` invariant**:
  > "Undo Snackbar(CMT-013)는 completed·cancelled 전이에만 제공되며 5초 window 내 1회 복원 가능"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:98-118`
```kotlin
else -> {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = state.items, key = { it.id }) { row ->
            CommitmentCard(
                title = row.title,
                direction = row.direction,
                derivedStatus = row.derivedStatus,
                dueDate = row.dueDate,
                counterpartyDisplayName = row.counterpartyDisplayName,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onMarkDone = { viewModel.onMarkDone(row.id) },
                onClick = {},
            )
        }
    }
}
```
→ 단일 items 블록. `stickyHeader` / section split 없음.

### 3.2 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:325-345`
```kotlin
private fun applyFilter(
    entities: List<CommitmentEntity>,
    filter: CommitmentFilter,
    enrichment: Map<String, PersonEnrichmentEntity>,
): List<CommitmentRow> {
    val filtered = when (filter) {
        CommitmentFilter.ALL -> entities
        CommitmentFilter.GIVE -> entities.filter { it.direction == "give" }
        CommitmentFilter.TAKE -> entities.filter { it.direction == "take" }
    }
    return filtered.map { entity -> CommitmentRow(...) }
}
```
→ active / completed 분리 없음.

### 3.3 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:259-267`
```kotlin
public fun onMarkDone(id: String) {
    launchAction(
        name = "onMarkDone",
        id = id,
        effect = { reminderScheduler.cancel(id) },
    ) {
        commitmentRepository.transitionState(id, CommitmentEvent.MarkDone)
    }
}
```
→ 직전 action_state 를 기억하지 않음, Snackbar 트리거 state 없음.

### 3.4 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:57-68`
```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val scope = rememberCoroutineScope()

LaunchedEffect(state.error) {
    state.error?.let { err ->
        scope.launch {
            snackbarHostState.showSnackbar(err)
            viewModel.onErrorDismissed()
        }
    }
}
```
→ Snackbar 는 오류 표시만 담당. Undo action 없음.

검증 grep:
```bash
grep -rn "이행 완료\|undoAction\|action = {\|SnackbarResult.ActionPerformed" \
  android/app/src/main/java/com/becalm/android/ui/commitments/
# → empty
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 섹션 분리 | 활성 (pending/reminded/followed_up) + 접힘 "이행 완료 (N)" | 단일 list | activeItems / completedItems 분리 |
| 섹션 토글 | 탭으로 펼침/접힘 | 없음 | isCompletedExpanded State + 헤더 Composable |
| 완료 카드 dimmed | 회색 처리 | `CommitmentCard` 는 `DONE` 에 `alpha=0.55f` 적용 (이미 존재, `CommitmentCard.kt:117`) | 재사용, action_state="completed" → derivedStatus="DONE" 매핑 확인 |
| Undo Snackbar | completed/cancelled 전이 후 5초 내 `[실행 취소]` | 없음 | VM state + Screen 의 Snackbar 처리 |
| Undo 복원 액션 | Room UPDATE 직전 값 + PATCH | 없음 | VM 에 `onUndo(id)` + 스냅샷 저장 |
| AlarmManager 재등록 | undo 시 재등록 안함 | (현재 cancel 호출조차 없음) | 명시적 "재등록 생략" 주석 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`**
   - `CommitmentUiState` 확장:
     ```
     val activeItems: List<CommitmentRow>
     val completedItems: List<CommitmentRow>
     val isCompletedExpanded: Boolean
     val pendingUndo: UndoSnapshot? // nullable
     ```
     `items` 필드는 유지하되 `activeItems + completedItems` 의 합으로 computed 하거나 deprecated. 호환성을 위해 기존 필드 유지 + 신규 두 필드 추가.
   - `applyFilter` → `splitActiveCompleted(filtered)` 유틸 추가. `entity.actionState == "completed"` 이면 completedItems 로 분리.
   - `onToggleCompleted()` → `isCompletedExpanded` 토글.
   - `onMarkDone(id)` 수정: 전이 전 `commitmentRepository.findById(id)?.actionState` 스냅샷 저장 → `pendingUndo = UndoSnapshot(id, prevState, transitionType = "completed")` → 5초 후 null 로 클리어 (`viewModelScope.launch { delay(5_000); ... }`).
   - `onUndo()` → `pendingUndo?.let { repo.updateActionState(it.id, it.prevState, now()) }`. Reminder 재등록은 생략.
   - `UndoSnapshot` data class 추가 (VM private).

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`**
   - `LazyColumn` 을 두 section 으로 재구성:
     - activeItems items block
     - sticky/normal header: `"이행 완료 (${completedItems.size})"` clickable — `onToggleCompleted` 호출
     - `isCompletedExpanded` 이면 completedItems items block render
   - Undo Snackbar: `LaunchedEffect(state.pendingUndo) { ... }` 으로 Snackbar show (label="실행 취소", duration=Short) → `SnackbarResult.ActionPerformed` 이면 `viewModel.onUndo()` 호출.
   - 기존 `state.error` Snackbar 와 충돌 방지 위해 분리된 `LaunchedEffect` + 문자열 우선순위 정의.

3. **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`**
   - **변경 없음** — DONE / DISMISSED alpha=0.55 처리 이미 존재 (`CommitmentCard.kt:115-117`). VM 이 `action_state="completed"` → `derivedStatus="DONE"` 매핑을 정확히 해주면 재사용 가능.
   - 현재 `derivedStatus = entity.commitmentState.name` (`CommitmentManagementViewModel.kt:340`) 인데, `CommitmentState` enum 에 "DONE" 이 있는지는 `ui-commitment-action-state-alignment.md` 플랜에서 다루는 별건. 본 plan 은 `entity.actionState == "completed"` 기반 분리만 담당하고 dim 처리는 Card 의 기존 로직을 믿음.

### 5.2 Files to add

- **테스트**: `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentManagementViewModelUndoTest.kt`

### 5.3 Files to delete
없음.

### 5.4 Non-code changes

- `strings.xml`:
  - `R.string.commitments_section_completed` = "이행 완료 (%1$d)"
  - `R.string.commitments_undo_completed` = "완료로 변경되었습니다."
  - `R.string.commitments_undo_cancelled` = "취소되었습니다."
  - `R.string.commitments_undo_action` = "실행 취소"

---

## 6. Acceptance Criteria

- [ ] **Unit test**: `CommitmentManagementViewModelUndoTest — onMarkDone 후 uiState.pendingUndo != null, 5초 후 null`
- [ ] **Unit test**: `CommitmentManagementViewModelUndoTest — onUndo 는 repo.updateActionState(id, prevState) 를 호출`
- [ ] **Unit test**: `CommitmentManagementViewModelUndoTest — 직전 action_state가 "reminded" 이면 undo 후 "reminded" 로 복원`
- [ ] **Unit test**: `CommitmentManagementViewModelTest — completedItems 는 action_state="completed" rows 만 포함`
- [ ] **Unit test**: `CommitmentManagementViewModelTest — activeItems 에 action_state="completed" 없음 invariant`
- [ ] **UI test**: `CommitmentManagementScreenTest — "이행 완료 (2)" 헤더 노드 존재, 탭 시 completed 카드 2건 가시`
- [ ] **UI test**: `CommitmentManagementScreenTest — [완료] 후 Snackbar "[실행 취소]" 액션 노드 존재`
- [ ] **Grep invariant**: `grep -n "isCompletedExpanded\|pendingUndo\|onUndo" android/app/src/main/java/com/becalm/android/ui/commitments/ | wc -l` ≥ 6

---

## 7. Out of Scope

- [취소] 버튼 자체의 action_state 전이 — `ui-commitment-cancel-action.md`
- Undo 시 AlarmManager 재등록 결정 (현재 스펙 = 재등록 안함)
- "취소된 약속" 섹션 별도 표시 — 스펙 CMT-012 가 "취소된 약속 섹션(접힌 상태)" 을 언급하나, 본 PR 은 **완료 섹션만** 처리. "취소 섹션" 은 `ui-commitment-cancel-action.md` 에서 symmetric 하게 추가.
- DOZE / background 에서 5초 타이머 정확도 — 스펙이 foreground UX 로 전제
- CMT-011 overdue — 별도
- `CommitmentCard` alpha dim 로직 수정 — 이미 동작 중

---

## 8. Dependencies

- **Blocked by**: 없음 — 현 Room 스키마로 `entity.actionState` 필드만으로 구현 가능. PR #17/#20 무관.
- **Blocks**:
  - `ui-commitment-cancel-action.md` — 동일 Undo Snackbar 파이프라인 재사용 (transitionType 확장)
- **병렬 가능**:
  - `ui-commitment-detail-sheet.md` — 시트에서도 [완료] 탭 시 동일 Snackbar 필요. 상세 시트 VM 이 `pendingUndo` 를 list VM 과 공유할지 / 각자 관리할지는 구현 세션 결정. 기본 권장: **share via `CommitmentManagementViewModel`** (동일 화면 scope).

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

UI state + VM state 추가만 변경. Revert 시 기존 단일 list 로 복귀. 저장된 action_state 는 영향 없음. Safe.

---

## Appendix — Session handoff notes

- `pendingUndo` 5초 timer 는 `viewModelScope.launch { delay(5_000); clear() }` 패턴. 새 전이가 들어오면 기존 job `cancel()` 후 재시작 — `pendingUndoJob: Job?` 필드로 관리.
- Snackbar duration 은 Material3 `SnackbarDuration.Short` (4초 근처) — 스펙의 5초와 약간 어긋남. 정확성을 위해 `SnackbarDuration.Indefinite` + VM 타이머로 dismiss 제어 권장.
- `completedItems` 분리 기준은 `actionState == "completed"` 문자열. `CommitmentState.DONE` enum 과는 구분됨 — 본 PR 은 `actionState` 만 본다 (`commitmentState` 는 다른 상태 머신).
- List VM 과 Detail Sheet VM 이 동일한 Undo snapshot 을 공유하려면 `pendingUndo` 를 repository-level SharedFlow 로 끌어올리는 방법도 있음. MVP 에서는 screen-scope 단일 VM 에서 관리 충분.
- "취소된 약속" section 을 본 PR 에서 추가할지 여부: 스펙 CMT-012 가 언급하나, 본 plan 은 scope 한정을 위해 "완료 섹션" 만. 구현 세션이 비용 대비 이득 판단 후 동일 파일에서 mirror pattern 으로 추가해도 무방.
