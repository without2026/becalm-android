# UI / Commitment / Pull-to-Refresh — CMT-010 wiring

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: PLAN ONLY — Wave 4 commit C3
**E2E Stage**: 5 — Commitment Management
**Severity**: Medium
**Type**: Gap

---

## 1. Finding

`CommitmentManagementScreen` currently has **no user-initiated refresh path** — the Room subscription is the sole reactivity source. Spec CMT-010 requires pull-to-refresh that calls `GET /v1/commitments` and re-hydrates Room while preserving the active filter. The only existing pull-refresh implementation lives on `TodayTimelineScreen.kt` (uses `androidx.compose.material.pullrefresh.rememberPullRefreshState`).

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:97-104`** — CMT-010:
  > "Pull-to-refresh (당겨서 새로고침): CommitmentManagementScreen 상단에서 아래로 당기면 서버 fetch 를 트리거한다. 동작: `GET /v1/commitments` → Room upsert → UI 자동 반영. 현재 탭/필터는 유지. 네트워크 실패 시 Snackbar `새로고침 실패 — 잠시 뒤 다시 시도해주세요`."

- **`.spec/commitment-management.spec.yml:137-140`** (invariants):
  > "Room 이 primary source (MUST). 서버 결과는 Room upsert 로만 반영."

---

## 3. Code Reality (지금 무엇인가)

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:53-124`** — `LazyColumn` without `PullToRefresh` wrapper.
- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`** — no `onRefresh()` / `onPullRefresh()` handler.
- **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt:58`** — `suspend refreshSince(userId, since, personRef?, direction?, actionState?): BecalmResult<RefreshStats>` already exists, so no new repo work required.
- **Reference implementation**: `android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt:16-18,95-157` + `TodayViewModel.kt:285` (`onPullRefresh()`).

```bash
grep -rn "PullToRefresh\|pullRefresh\|rememberPullRefreshState" android/app/src/main/java/com/becalm/android/ui/commitments/
# expected: 0
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| Pull-to-refresh UI | SwipeRefresh indicator 상단 | 없음 | 추가 필요 |
| VM refresh handler | `onPullRefresh()` | 없음 | 추가 필요 |
| 필터 유지 | 현재 필터 보존 | N/A | refresh 이후 `applyFilter` 재호출 |
| 실패 Snackbar | 실패 시 한국어 메시지 | `_errorFlow` 미사용 | VM emit |

---

## 5. Proposed Fix

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
  — wrap `LazyColumn` with `Box` + `PullToRefreshBox` (Material 3) or `PullRefreshIndicator` (Material 2 pattern from `TodayTimelineScreen`). Delegate state to `rememberPullToRefreshState()`. On trigger → `viewModel.onPullRefresh()`.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  — add `private val _refreshing = MutableStateFlow(false)` + `val refreshing: StateFlow<Boolean>`; add `fun onPullRefresh()` that: sets refreshing=true → `repository.refreshSince(userId, since = Instant.EPOCH, personRef=null, direction=null, actionState=null)` → on error emit `_errorFlow` with `R.string.commitment_refresh_failed` → set refreshing=false. `since=EPOCH` because CMT-010 requires full refresh semantics, not delta.
- `android/app/src/main/res/values/strings.xml`
  — add `commitment_refresh_failed` = "새로고침 실패 — 잠시 뒤 다시 시도해주세요".

### 5.2 Files to add
- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentManagementViewModelPullRefreshTest.kt`
  — verifies `onPullRefresh` toggles refreshing flag, calls `repository.refreshSince`, emits error on failure.

### 5.3 Files to delete (dead code)
- none.

### 5.4 Non-code changes
- none.

---

## 6. Acceptance Criteria

- [ ] **Compile**: `:app:compileDebugKotlin` pass.
- [ ] **Grep**: `grep -n "onPullRefresh" android/app/src/main/java/com/becalm/android/ui/commitments/` ≥ 2 (screen + VM).
- [ ] **Unit test**: `CommitmentManagementViewModelPullRefreshTest` 의 success + failure 케이스 통과.
- [ ] **Filter preservation**: refresh 후에도 `uiState.value.filter` 가 변하지 않는다 (test 로 assert).
- [ ] **Manual**: 앱 실행 → 당겨서 새로고침 → 인디케이터 표시 → Room 재구독 확인.

---

## 7. Out of Scope

- 서버 엔드포인트 수정 (`RailwayApi.getCommitments` 는 이미 존재).
- 무한 스크롤 / pagination.
- Card UI 변경.
- Detail sheet / edit sheet 항목 (별도 plan).

---

## 8. Dependencies

- **Blocked by**: 없음 (`refreshSince` 이미 Wave 2 완료).
- **Blocks**: 없음.

Wave 4 umbrella 안에서 linear commit. 선행: `ui-commitment-action-state-alignment` 이후 (VM 구조 변경 후 hook 이 단순).

---

## 9. Rollback plan

`git revert <sha>`. VM + screen + strings.xml 3-파일만 수정하므로 단일 revert 안전.

---

## Appendix — Session handoff notes

- Material 3 `PullToRefreshBox` (compose-material3 1.2+) 가 권장. `TodayTimelineScreen` 은 material (Material 2) API 를 쓰지만 commitment 화면은 신규 도입이므로 M3 API 로 진행하면 향후 표준화 부담 감소.
- `refreshSince(since = Instant.EPOCH)` 는 서버가 전체 목록을 반환하는 semantics 를 따름 (CMT-010 "서버 fetch 를 트리거"). delta 가 아닌 full-refresh 의도.
- refresh 중 사용자가 필터를 바꿔도 `uiState.value.filter` 가 in-memory 에 보존됨 — 따로 건드리지 않음.
