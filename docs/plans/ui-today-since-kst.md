# UI / Today / since-kst — `onPullRefresh` 가 `since=null` 로 호출 + `todayIso()`/`todayRange()` 가 시스템 TZ 사용 (KST invariant 위반)

**Branch**: `fix/ui/today/since-kst`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 문서 이외 금지.
**E2E Stage**: 5–6 (Today ViewModel / Railway 호출 계약)
**Severity**: Medium (TDY-004/005 스펙 `since={today_start}` 파라미터 위반 → 서버가 전체 range 를 돌려주어 네트워크/DB 부하 증가. KST invariant 누락은 해외 여행·디바이스 로케일 변경 시 "오늘" 경계 오작동)
**Type**: Drift (Spec TDY-004/005 및 프로젝트 KST invariant vs 실제 구현 불일치)

---

## 1. Finding

두 가지 관련 결함:

1. **TDY-004/005 `since={today_start}` 위반** — `TodayViewModel.onPullRefresh()` (line 283-300) 가 `commitmentRepository.refreshSince(userId = userId, since = null)` 과 `calendarEventRepository.refreshSince(userId = userId, since = null)` 을 그대로 호출. 스펙은 `since` 에 **오늘 KST start** 가 전달되어야 한다고 명시.

2. **KST invariant 위반** — `todayIso()` / `todayRange()` (line 366-377) 가 `TimeZone.currentSystemDefault()` 를 사용. 프로젝트 전반에 "KST (`Asia/Seoul`) 고정" invariant 가 적용되고 있으며 (예: `ui-commitment-dn-badge-kst.md` 이미 CommitmentCard 에 동일 fix 진행), Today 화면이 KST 를 벗어나면 해외 기기·시스템 TZ 조작 시 commitments 의 "오늘" 범위가 실제 한국 오늘과 어긋난다.

추가로 TDY-004 의 `due_at` 기반 필터와 현재 `CommitmentDao.observePendingForToday` (`CommitmentDao.kt:185-194`) 의 `due_date IS NULL OR due_date <= :todayIso` 의 **문자열 lexicographic 비교** 가 PR #17 (feat/db/commitment/due-at-hint-approximate) 이후 `due_at` (INTEGER millis) 으로 교체되면, lexical 비교 자체가 의미 없어져 본 PR 에서 함께 수정해야 한다 (PR #17 merge 후).

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/today-timeline.spec.yml:37-44` — TDY-004

> endpoint: "GET /v1/commitments"
> precondition: "Supabase JWT Bearer 토큰 유효, **query: since={today_start}**, direction 생략(전체)"
> expected: "HTTP 200, {data: Commitment[], cursor: string|null} — 서버 측 user 격리 적용됨"

### 2.2 `.spec/today-timeline.spec.yml:46-53` — TDY-005

> endpoint: "GET /v1/calendar_events"
> precondition: "Supabase JWT Bearer 토큰 유효, **query: since={today_start}**"
> expected: "HTTP 200, {data: CalendarEvent[], cursor: string|null} — 서버 측 user 격리 적용됨"

### 2.3 프로젝트 KST invariant — `.spec/commitment-management.spec.yml:40` CMT-004 (representative)

> "D-N 배지 — due_at까지 남은 일수를 **KST local date** 기준으로 CommitmentCard에 표시한다."

TDY-001 의 "오늘 KST range" (line 9, 12) 요구와 동일 선상. 프로젝트 전반에 `Asia/Seoul` 하드코딩 원칙 적용 중 (`docs/plans/ui-commitment-dn-badge-kst.md` 가 CommitmentCard 에 동일 fix 이미 문서화).

### 2.4 `.spec/today-timeline.spec.yml:105-110` — invariants

> "TodayTimelineScreen은 Room(로컬) 데이터를 primary source로 사용한다"

(본 PR 은 primary source 원칙을 유지 — pull-to-refresh 에서만 서버 `since` 전달.)

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt:283-300` — `onPullRefresh` 가 `since=null`

```kotlin
public fun onPullRefresh() {
    foregroundCatchUpScheduler.triggerCatchUp()
    viewModelScope.launch {
        val userId = authRepository.currentSession()?.userId
        refreshingFlow.value = true
        try {
            sourceStatusRepository.refreshFromServer()
            if (userId != null) {
                commitmentRepository.refreshSince(userId = userId, since = null)
                calendarEventRepository.refreshSince(userId = userId, since = null)
            }
        } finally {
            refreshingFlow.value = false
        }
    }
}
```
→ 두 repo 모두 `since = null`. 구현체 (`CalendarEventRepository.kt:150-189`) 는 `since = null` 을 "stored cursor 사용 + full fetch" 로 해석 — 스펙 `since={today_start}` 과 다름.

### 3.2 `android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt:366-377` — 시스템 TZ 사용

```kotlin
private fun todayIso(): String {
    val tz = TimeZone.currentSystemDefault()
    return clock.today(tz).toString()
}

private fun todayRange(): Pair<Instant, Instant> {
    val tz = TimeZone.currentSystemDefault()
    val today = clock.today(tz)
    val start = today.atStartOfDayIn(tz)
    val end = today.plus(DatePeriod(days = 1)).atStartOfDayIn(tz)
    return start to end
}
```
→ `TimeZone.currentSystemDefault()` 사용. `Asia/Seoul` 고정 필요.

### 3.3 `android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt:185-194` — due_date lexical 비교

```kotlin
@Query(
    """
    SELECT * FROM commitments
    WHERE user_id      = :userId
      AND action_state = 'pending'
      AND (due_date IS NULL OR due_date <= :todayIso)
    ORDER BY due_date IS NULL ASC, due_date ASC, created_at DESC
    """
)
public fun observePendingForToday(userId: String, todayIso: String): Flow<List<CommitmentEntity>>
```

→ 현 스키마(`due_date: LocalDate`, TEXT `yyyy-MM-dd`) 에서 lexical ≤ 비교는 의도대로 동작. 그러나 PR #17 이후 `due_at: INTEGER (millis)` 로 교체되면 이 쿼리는 **타입 mismatch + 의미 상실** — `yyyy-MM-dd` 문자열 vs INTEGER millis 비교 불가.

### 3.4 검증 grep

```bash
# 시스템 TZ 사용 확인
grep -n "TimeZone\.currentSystemDefault" \
  android/app/src/main/java/com/becalm/android/ui/today/
# → TodayViewModel.kt:367, TodayViewModel.kt:372 (2 건)

# Asia/Seoul 고정 여부
grep -rn "Asia/Seoul" android/app/src/main/java/com/becalm/android/ui/today/
# → 0 (없음)

# onPullRefresh 의 since 인자
grep -n "refreshSince(userId = userId, since" \
  android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt
# → 2 matches, 두 곳 모두 since = null
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| TDY-004 since 파라미터 | `since={today_start}` (KST) | `since = null` | onPullRefresh 에서 KST today_start Instant 전달 |
| TDY-005 since 파라미터 | `since={today_start}` (KST) | `since = null` | 동일 |
| todayIso TZ | KST (`Asia/Seoul`) | `currentSystemDefault()` | TZ 고정 |
| todayRange TZ | KST | `currentSystemDefault()` | 동일 |
| CommitmentDao 필터 (PR #17 후) | `due_at >= KST today start AND due_at < KST today end` | lexical `due_date <= :todayIso` | due_at INTEGER range 비교 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt`**
   - `todayIso()` / `todayRange()` 내 `TimeZone.currentSystemDefault()` → `TimeZone.of("Asia/Seoul")` 하드코딩. 또는 프로젝트 표준 헬퍼가 있다면 그것을 사용 (§5.4 참조).
   - `onPullRefresh()` 에서 `todayRange().first` (KST today_start Instant) 를 계산하여 `refreshSince(userId = userId, since = todayStart)` 로 양쪽 repo 에 전달.
   - KDoc 에 "KST(Asia/Seoul) 하드코딩 — TDY-001/004/005 의 'today KST range' 계약" 라인 추가.

2. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`** (PR #17 merge 후에만)
   - `observePendingForToday` 쿼리 교체:
     ```sql
     WHERE user_id = :userId
       AND action_state = 'pending'
       AND (due_at IS NULL OR (due_at >= :todayStartMs AND due_at < :todayEndMs))
     ORDER BY due_at IS NULL ASC, due_at ASC, created_at DESC
     ```
   - 함수 시그니처도 `(userId: String, todayStartMs: Long, todayEndMs: Long)` 로 변경 (millis Long 이 Room Instant TypeConverter 와 정합). 또는 Instant 인자로 받고 Converter 에 의존하는 방식 중 기존 코드 스타일(`CalendarEventDao.observeInRange(userId, Instant, Instant)`) 과 정렬.
   - KDoc: "KST 자정 경계 기반 closed-open range `[start, end)`. 상위 호출자는 `Asia/Seoul` TZ 로 계산한 Instant 를 전달해야 한다."

3. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt` + `CommitmentRepositoryImpl.kt`**
   - `observePendingForToday` 시그니처를 DAO 와 동일하게 변경 (Instant range 또는 millis range).

4. **`android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt` (연쇄)**
   - `commitmentFlow` 의 `observePendingForToday(userId, todayIso())` 호출을 새 range 시그니처로 교체. `todayIso()` 는 onPullRefresh 에서 여전히 TDY-004/005 의 `since` 문자열 변환에 쓰일 수 있으므로 유지.

### 5.2 Files to add

없음. 단, 만약 `Asia/Seoul` 이 여러 파일에 반복되면 **별도 refactor plan 에서** 상수 추출 (본 PR 범위 아님).

### 5.3 Files to delete (dead code)

없음. 기존 `todayIso()` 는 `since` 문자열 생성에 계속 사용.

### 5.4 Non-code changes

- (선택) 프로젝트에 이미 `core/time/` 아래 KST 헬퍼가 존재한다면 재사용. 본 탐색에서 `find … -path "*/core/time/*" -name "*.kt"` 0건 반환 — 없음 확정. 구현자가 `core/util/Clock.kt` 에 `fun todayInKst(): LocalDate` / `fun todayKstRange(): Pair<Instant, Instant>` 추가를 검토할 수 있으나 본 PR 은 surgical 하게 `TodayViewModel` 내부만 고치고 추출은 후속으로 미룸.
- 단위 테스트: `TodayViewModelTest` 에 가짜 Clock (`FakeClock`) 주입하여:
  - 시스템 TZ 를 `Pacific/Honolulu` 로 가정해도 `onPullRefresh` 에서 계산된 `since` 가 KST 자정 기준 Instant 임을 검증.
  - 기기 시스템 TZ 를 바꾸어도 timeline range 가 동일함을 검증.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "TimeZone\.currentSystemDefault" android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "Asia/Seoul" android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "since = null" android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt | wc -l` = 0
- [ ] **Grep invariant (PR #17 후)**: `grep -n "due_date <=\|due_date <" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt | wc -l` = 0
- [ ] **Unit test**: `TodayViewModelTest — onPullRefresh calls refreshSince with KST today start instant regardless of system timezone`
- [ ] **Unit test**: `TodayViewModelTest — todayRange returns KST 00:00..24:00 even when system tz is Pacific/Honolulu`
- [ ] **Unit test (PR #17 후)**: `CommitmentDaoTest — observePendingForToday returns rows only where due_at falls inside KST [start, end) range`
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- Today 외 다른 화면의 `TimeZone.currentSystemDefault()` — 별도 plans 가 있음 (`ui-commitment-dn-badge-kst.md` 등).
- `VoiceUploadMappers.kt:98` 의 `TimeZone.currentSystemDefault()` — voice ingestion date key. 별도 검토 대상.
- `OverallSyncIndicator.kt:97`, `SourceStatusStrip.kt:195` 의 `TimeZone.currentSystemDefault()` — 이들은 사용자 시계 표시 용도이므로 "기기 로컬" 유지가 맞을 수 있음. 본 PR 에서는 건드리지 않음.
- 서버 `since` 파라미터 포맷 (ISO8601 vs epoch millis) 협의 — 현 `refreshSince(userId, since: Instant?)` 가 `Instant.toString()` 을 ISO-8601 로 serialize 하므로 TDY-004/005 의 `{today_start}` 요구와 정합. 별도 서버 변경 없음.
- Today 화면의 SourceStatus / ColdSync 로직 — 본 PR 은 since + TZ 만.

---

## 8. Dependencies

- **Blocked by (partial)**:
  - **PR #17 (`feat/db/commitment/due-at-hint-approximate`)** — `CommitmentDao.observePendingForToday` 의 `due_date` → `due_at` 교체는 PR #17 이 스키마를 `due_at INTEGER` 로 확장한 이후에만 가능. 본 PR 을 두 단계로 나눌 수도 있음:
    - Phase A (PR #17 무관): `onPullRefresh` since + `todayIso/todayRange` TZ 교체
    - Phase B (PR #17 후): `observePendingForToday` 쿼리 range 교체
  - 구현자가 PR #17 머지 상태 확인 후 A 또는 A+B 선택.
- **Blocks**:
  - TDY-004 / TDY-005 full acceptance.
  - TDY-001 의 "due_at 이 오늘 KST range 에 속하는 commitment" 정확 동작 (PR #17 + 본 PR 조합).
- **병렬 가능**:
  - `feat/db/calendar/status-recurring` — 파일 겹침 없음 (calendar entity / worker vs today VM + commitment DAO). 완전 독립.
  - `feat/ui/today` (`docs/plans/ui-today-counterparty-direction.md`) — 같은 `TodayViewModel.kt` / `TodayTimelineScreen.kt` 편집. 단:
    - 본 PR: `onPullRefresh` (line 283-300) + `todayIso/todayRange` (line 366-377) + DAO
    - 저 PR: `TimelineItem` sealed class (line 42-82) + `TimelineItemRow` (line 196-230)
    - 물리적으로 다른 라인. merge 순서 주의, rebase 시 충돌 최소.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Phase A (TZ + since) revert 는 안전 — API 요청 파라미터만 바뀌며 서버는 since 무시해도 200 반환.

Phase B (DAO 쿼리 교체) revert:
- PR #17 이 이미 `due_date` 를 drop 했다면 `due_date <= :todayIso` 쿼리는 컴파일·실행 불가 → revert 가 위험. 반드시 forward-only fix 필요.
- 따라서 Phase B 는 **본 PR 에서 단독 revert 하지 말고** "DAO 쿼리를 다시 수정하는 hotfix PR" 으로 대응.

---

## Appendix — Session handoff notes

- **Phase 분할 여부**: 구현자가 PR #17 상태 확인 후 결정. 머지되어 있으면 Phase A+B 를 하나의 커밋 세트로 진행. 머지 전이면 Phase A 만 진행하고 `CommitmentDao` 는 `TODO(PR #17 merge 후)` 주석만 남김.
- **`Asia/Seoul` 상수 추출 여부**: 본 PR 에서는 단일 파일 2 곳만 수정하므로 인라인 `TimeZone.of("Asia/Seoul")` 로 충분. 반복이 3 곳 이상 되면 별도 refactor plan (`refactor/core/time/kst-constant`).
- **`since` 파라미터 의미 확인**: `CalendarEventRepositoryImpl.refreshSince` (line 150-189) 에서 `since = null` 이면 stored cursor 를 사용, `since != null` 이면 그 시각 이후 page 를 요청. KST today_start 를 전달할 경우 stored cursor 가 무시되는지 / `since` 가 cursor 와 중복되면 서버가 어떻게 해석하는지 Railway 계약 재확인 필요. 일반적으로 `since` 는 "initial pull 시점" hint 이고 `cursor` 는 "pagination continuation" 이므로 같이 전달해도 무해할 것으로 판단.
- **FakeClock 활용**: `Clock` 인터페이스(`core/util/Clock.kt:20-32`) 가 이미 `today(zone: TimeZone = TimeZone.currentSystemDefault())` 형태로 zone 인자를 받는다. `TodayViewModel` 이 `clock.today(TimeZone.of("Asia/Seoul"))` 만 호출하면 zone-safe 해진다 — 이미 인프라가 준비된 상태.
- **`onPullRefresh` side effects**: `foregroundCatchUpScheduler.triggerCatchUp()` 및 `sourceStatusRepository.refreshFromServer()` 는 본 PR 범위 아님 (TDY-009 가 소유). 건드리지 않는다.
- **TDY-006 pull-to-refresh 자체**: 본 PR 은 pull 동작 자체는 그대로 두고, 그 안에서 호출되는 `refreshSince(since=?)` 의 인자만 교체. `refreshingFlow` / `PullRefreshIndicator` 는 무변경.
- **CommitmentDao.observePendingForToday `ORDER BY due_date IS NULL ASC`**: PR #17 후 `due_at IS NULL ASC` 로 자동 전환됨 (이름만 바뀜). 논리는 "due 없는 것을 마지막에" — 유지.
