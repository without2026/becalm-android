# UI / Commitment / pull-refresh — CommitmentManagement pull-to-refresh 부재

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentManagement 화면 refresh 제스처
**Severity**: Low (Room 은 반응형이라 자동으로 최신 반영되나, 서버→클라 동기화 강제 트리거가 없어 사용자가 "방금 다른 기기에서 수정한 약속" 을 보려면 앱 재시작 필요.)
**Type**: Gap (CMT-010 제스처 미구현)

---

## 1. Finding

`CommitmentManagementScreen` 의 `LazyColumn` 은 `SwipeRefresh` / `PullToRefreshBox` 래퍼 없이 렌더된다 (`CommitmentManagementScreen.kt:99-117`). VM 에도 `onRefresh()` 공개 핸들러가 없다 (`CommitmentManagementViewModel.kt` 전체에 `refresh` 문자열 부재). `CommitmentRepository.refreshSince` 인터페이스는 이미 존재 (`CommitmentRepository.kt:57-63`) 하므로 **Repository 확장은 불필요**, VM + Screen 레벨 훅만 추가하면 CMT-010 을 만족한다.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:96-104` CMT-010**:
  > "pull-to-refresh 시 Railway GET /v1/commitments를 재호출하고 Room 캐시를 갱신한다"
  > gesture: "pull-to-refresh"
  > expected: "GET /v1/commitments 호출됨(현재 action_state 필터 유지). Room 갱신됨. 목록 업데이트됨"

- **`.spec/commitment-management.spec.yml:137` invariant**:
  > "action_state 변경은 Room 즉시 UPDATE + Railway PATCH /v1/commitments/{id} 비동기 호출 순서로 처리된다"
  → pull-to-refresh 는 inbound sync (서버→Room) 만 담당.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:74-121`
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding),
) {
    FilterChipRow(...)
    when {
        state.loading -> { ... }
        state.items.isEmpty() -> { ... }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(...) { ... }
            }
        }
    }
}
```
→ `PullToRefreshBox`, `SwipeRefresh`, `rememberPullToRefreshState` 모두 부재.

### 3.2 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:195-284`
```bash
grep -n "refresh\|Refresh" android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt
# → empty
```
→ VM 에 refresh 진입점 없음.

### 3.3 `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt:57-63`
```kotlin
public suspend fun refreshSince(
    userId: String,
    since: Instant?,
    personRef: String? = null,
    direction: String? = null,
    actionState: String? = null,
): BecalmResult<RefreshStats>
```
→ 이미 존재. 재사용만 하면 됨.

검증 grep:
```bash
grep -rn "PullToRefreshBox\|SwipeRefresh\|rememberPullToRefreshState" \
  android/app/src/main/java/com/becalm/android/ui/commitments/
# → empty
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 제스처 | Pull-to-refresh | 없음 | PullToRefreshBox 도입 |
| VM 핸들러 | 비동기 refreshSince 실행 + isRefreshing state | 없음 | `onRefresh()` + `isRefreshing: Boolean` |
| 필터 유지 | "현재 action_state 필터 유지" | N/A (아직 action_state 필터 UI 없음 — direction filter 만 존재) | direction filter 유지 (refresh 시 재적용 불필요, Room flow 자동) |
| Room 갱신 후 목록 반영 | 자동 | Room flow 반응형이므로 별도 처리 불필요 | 없음 (이미 충족) |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`**
   - `CommitmentUiState` 에 `isRefreshing: Boolean = false` 추가.
   - 신규 public fun `onRefresh()`:
     ```
     userId 현재값 읽기 → _uiState.update { isRefreshing=true }
     → commitmentRepository.refreshSince(userId, since = null) 호출
       · 인자 since=null 이 최선인지 / lastCursor 주입인지 구현 세션 결정.
         cold-sync 와의 중복 비용 고려 — Repository 내부가 cursor 기반이면 since=null 은 처음부터 재동기 비용 큼.
         권장: SyncCursorStore.getCommitmentsCursor() 주입.
     → Success/Failure 모두 isRefreshing=false
     → Failure 시 state.error 세팅 (기존 Snackbar 호스트가 이미 처리)
     ```
   - 기존 `launchAction` helper 는 `BecalmResult` 래퍼 가정이므로 refresh 경로 전용 `launch { ... }` 별도 작성이 더 깔끔. (surgical — helper 변형 X)

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`**
   - Material3 `PullToRefreshBox` (또는 `androidx.compose.material3:material3-pull-refresh`) 로 `LazyColumn` 을 감싼다.
     ```
     val pullState = rememberPullToRefreshState()
     PullToRefreshBox(
         isRefreshing = state.isRefreshing,
         onRefresh = viewModel::onRefresh,
         state = pullState,
     ) { LazyColumn(...) { ... } }
     ```
   - 빈 상태 (`state.items.isEmpty()`) 에서도 pull-to-refresh 가 동작하도록 EmptyState 를 PullToRefreshBox 내부 Column/Box 로 이동.

### 5.2 Files to add

- **테스트**: `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentManagementViewModelRefreshTest.kt`

### 5.3 Files to delete
없음.

### 5.4 Non-code changes

- `strings.xml`: (선택) `R.string.commitments_refreshing = "새로고침 중…"` — 접근성 descriptor. 필수 아님.
- `build.gradle.kts` — Material3 `material3-pullrefresh` dependency 가 이미 포함되었는지 확인. 없으면 추가.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "PullToRefreshBox\|rememberPullToRefreshState" android/app/src/main/java/com/becalm/android/ui/commitments/ | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -n "onRefresh\|isRefreshing" android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt | wc -l` ≥ 2
- [ ] **Unit test**: `CommitmentManagementViewModelRefreshTest — onRefresh 호출 시 isRefreshing true → refreshSince 1회 호출 → isRefreshing false`
- [ ] **Unit test**: `CommitmentManagementViewModelRefreshTest — refreshSince Failure 시 isRefreshing false 로 복귀 + error 세팅`
- [ ] **Unit test**: `CommitmentManagementViewModelRefreshTest — 동시 2회 onRefresh 호출 시 refreshSince 는 1회만 호출 (idempotent 가드)` — 선택적 invariant
- [ ] **Manual**: 빈 목록 화면에서도 pull gesture 가 먹는다

---

## 7. Out of Scope

- action_state 필터 UI — 현재 direction 만. CMT-010 본문의 "현재 action_state 필터 유지" 는 action_state 필터가 도입된 뒤 적용. 본 PR 은 제스처 + refresh 경로만.
- SyncCursorStore 확장 / 신규 저장소 도입
- 서버 → Room 충돌 해결 로직 (Repository 내부 책임)
- Pull-to-refresh 애니메이션 커스터마이즈 (기본 Material3 indicator 사용)

---

## 8. Dependencies

- **Blocked by**: 없음
- **Blocks**: 없음 (다른 CMT-* logic 과 독립)
- **병렬 가능**: 모든 다른 `ui-commitment-*` plan 과 파일 겹침 최소 — ViewModel 수정만 공통이므로 commit 순서로 충돌 해결

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

UI 컴포넌트 1개 래퍼 + VM 함수 1개 + state 필드 1개. Revert 시 원래 LazyColumn 복귀. Repository 변경 없음. Safe.

---

## Appendix — Session handoff notes

- `PullToRefreshBox` 는 Material3 1.3+ 이후 API. 현재 프로젝트 버전 확인 필요 (`libs.versions.toml` 또는 `build.gradle`). 낮으면 `com.google.accompanist:accompanist-swiperefresh` 폴백 검토하되 deprecated — 버전 bump 권장.
- `refreshSince(userId, since=null)` 는 전체 재페치. 대용량 대비 비용 고려되면 `SyncCursorStore.getCommitmentsCursor()` 를 주입받아 delta 동기화. 본 plan 은 실용적 기본값 `since=null` 로 시작, 후속 최적화 plan 에서 cursor 도입.
- `isRefreshing` 은 `loading` 과 별도 필드여야 함 — `loading` 은 첫 Room emit 대기용. 둘을 합치면 초기 렌더 UX 가 망가짐.
- onRefresh 중 userId 가 null (로그아웃) 이면 early return + isRefreshing=false. NullPointer 방지.
- 네트워크 오프라인 시 `refreshSince` 는 Failure(`BecalmError.Network`) — 기존 `state.error` 경로로 Snackbar 노출 (CommitmentManagementScreen.kt:61-68 이 처리). 추가 UI 불필요.
