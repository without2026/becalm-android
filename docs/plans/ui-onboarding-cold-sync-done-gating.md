# ui/onboarding — cold-sync `done` 게이트를 user-enabled source 로 좁히기

## Problem

Cold sync 화면(`OnboardingColdSync`)에서 progress 가 멈춘 채 `[다음으로]`가 자동으로 발화하지 않고, 사용자는 5초 뒤 [나중에 하기] 만 누를 수 있다. 결과적으로 "동기화 중" 메시지가 무한히 머문다.

## Root cause

`ColdSyncViewModel.buildUiState` (`android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt:172`) 가 hardcoded 6개 stage1 source 전체에 대해 `done = trackedProgress.values.all { >= 1f }` 를 요구한다.

| Source state | progress |
| --- | --- |
| `NEVER_CONNECTED` | 0f |
| `SYNCING` | 0f |
| `CONNECTED` | 1f |
| `ERROR` | 1f |

사용자가 onboarding 에서 일부 source 만 연결하고 나머지를 [건너뛰기] 한 경우, 건너뛴 source 의 status 는 `NEVER_CONNECTED` 로 영구 유지 → progress 0f → `done` 영원히 false.

`ColdSyncRuntimeCoordinator.startStage1` (line 60) 은 이미 `userPrefsStore.observeEnabledSources()` 로 worker enqueue 대상을 좁히고 있다. ViewModel 만 hardcoded set 을 사용하는 **불일치** 가 원인.

## Fix

ViewModel 의 추적 대상을 `STAGE1_TRACKED_SOURCES ∩ enabledSources` 로 좁힌다 (= runtime coordinator 와 동일한 기준).

### 변경

**File**: `android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt`

1. 생성자에 `private val userPrefsStore: UserPrefsStore` 추가.
2. `derivedState` 의 `combine(...)` 에 `userPrefsStore.observeEnabledSources()` 추가 (현재 5개 → 6개 source). `kotlinx.coroutines.flow.combine(vararg)` 로 전환하거나 nested combine 사용.
3. `buildUiState` 시그니처에 `enabledSources: Set<String>` 추가.
4. tracked source 결정 로직:
   ```kotlin
   val activeTracked = STAGE1_TRACKED_SOURCES intersect enabledSources
   val sourceProgress = statuses
       .asSequence()
       .filter { it.sourceType in activeTracked }
       .associate { status -> status.sourceType to sourceProgress(status.status) }
   ```
5. `done` 계산:
   - `activeTracked.isEmpty()` (사용자가 stage1 source 0개 enable) 인 경우 → `userProfileReady` 만으로 done.
   - 그 외 → 모든 active tracked source progress >= 1f **AND** userProfileReady.

### 변경 안 함

- `STAGE1_TRACKED_SOURCES` 자체는 그대로 유지 (Gmail/OutlookMail/Naver/Daum/GCal/OCal 6개). 이는 "stage1 에 의미 있는 source 의 superset" 정의로 둠.
- `sourceProgress(NEVER_CONNECTED) = 0f` 매핑 유지 (현재 의도된 의미 — "아직 시도 안 됨" 은 in-progress).
- Runtime coordinator (`ColdSyncRuntimeCoordinator.kt`) 는 변경 없음 — 이미 enabledSources 로 필터링 중.

## Tests

**File**: `android/app/src/test/java/com/becalm/android/unit/ui/today/ColdSyncViewModelSpecTest.kt`

### 기존 테스트 수정
- `TDY-010 COLD-001` (line 64): `enabledSources = emptySet()` 라서 perSourceProgress 가 user_profile 만 포함하는 동작은 그대로 통과 (수정 불필요, 단 `enabledSources` 흐름을 fake 에 추가 필요).
- `TDY-010 COLD-002` (line 86): 현재 OUTLOOK_MAIL 이 NEVER_CONNECTED 로 perSourceProgress 에 포함되는 걸 검증 — 새 동작에서는 enabledSources 에 OUTLOOK_MAIL 이 들어있어야 포함되도록 fake 설정 갱신.
- `TDY-010 COLD-003` (line 112): 6개 모두 enable 된 경우의 done flip — `enabledSources = STAGE1_TRACKED_SOURCES` 명시.

### 신규 테스트 추가
- `COLD-002b`: `enabledSources = {GMAIL}` 이고 GMAIL=CONNECTED, userProfileReady=true → done=true. (skipped sources 는 waiting 안 함)
- `COLD-002c`: `enabledSources = emptySet()`, userProfileReady=true → done=true.
- `COLD-002d`: `enabledSources = {GMAIL, OUTLOOK_MAIL}`, GMAIL=CONNECTED, OUTLOOK_MAIL=SYNCING → done=false.

### 신규 fake
`FakeUserPrefsStore` 또는 mockk 로 `observeEnabledSources()` 만 공급. 다른 메서드는 사용 안 됨.

## Risk

낮음.
- 변경 범위가 ViewModel 단일 파일.
- Worker / scheduler / repository / DB 미변경.
- Runtime coordinator 의 enabledSources 필터와 정합 → semantic drift 해소.
- progress mapping (`NEVER_CONNECTED → 0f`) 보존 → SYNCING 중인 enabled source 의 진행 표시는 동일.

## 검증 시나리오 (수동 / E2E)

1. Onboarding 에서 Gmail 만 연결 → cold-sync 화면 진입 → Gmail sync 완료 시 자동 forward.
2. Onboarding 에서 모든 source skip → cold-sync 진입 → user_profile bootstrap 완료 시 자동 forward.
3. Onboarding 에서 GCal+Naver 연결 → 둘 다 CONNECTED 도달 시 forward, 한쪽 ERROR 도 terminal 로 forward.
4. Sync 중 [나중에 하기] (5초 후) → defer 경로 동작 (변경 없음, regression check).

## Branch / commit

- 신규 브랜치: `fix/ui/onboarding` (CLAUDE.md 컨벤션, 직전 PR #72 merged 상태이므로 신규 가능).
- Commit: `fix(ui/onboarding): cold-sync-done-gating — gate done flip on user-enabled sources`.

## Phase fast-track 사유

P1 UX bug — cold-sync 화면이 멈춘 것처럼 보여 사용자 첫 인상에 직격. Phase 1→3→6→7 축소 (CTO 구두 확인 필요).
