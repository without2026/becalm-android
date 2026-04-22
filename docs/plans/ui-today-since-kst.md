# UI / Today / since-kst — `TimelineItemRow` 에 KST 기반 "N분 전" 상대시간 렌더 + `todayRange()` KST 교정

**Branch**: `feat/ui/wave-5` (umbrella; S5-B commit)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — Today timeline row (TDY-001 row detail + `.spec/today-timeline.spec.yml` KST invariant)
**Severity**: Medium — 사용자는 오늘 타임라인에서 "언제 일어난 일인지" 파악할 단서가 없음 (현재 timestamp 렌더 0). 법규 리스크는 아니지만 Today 의 핵심 정보설계 누락.
**Type**: Gap (timestamp 렌더 부재) + Drift (`todayRange()` 가 `TimeZone.currentSystemDefault()` 사용 — KST 고정 invariant 위배)

---

## 1. Finding

Today 타임라인은 "오늘(KST) 발생한 일" 을 시간순 표시해야 하나 현재 Row 는 sortKey / timestamp 를 전혀 렌더하지 않는다. 사용자는 "이 약속이 언제 생긴 건지" 알 수 없어 dashboard 로서 의미가 반감. 또한 VM 의 `todayRange()` 가 `TimeZone.currentSystemDefault()` 를 쓰기 때문에 사용자가 기기 TZ 를 미국/일본 등으로 두면 "오늘" 판정이 KST 캘린더 day 와 어긋남 (데이터 누수 없음 — 범위만 잘못됨).

본 plan 은 **(a) `TimelineItemRow` 에 KST 기준 "N분 전" / "N시간 전" / "HH:mm" 렌더**, **(b) `todayRange()` KST 상수 사용으로 교정** 두 가지만 수정.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/today-timeline.spec.yml` — KST invariant (line 106-110)
> "시간 표시는 전부 Asia/Seoul (KST) 기준이다. 사용자 기기 타임존이 KST 가 아니어도 Today 화면의 오늘 판정과 시간 포맷은 KST 고정."

### 2.2 `.spec/contracts/ui-map.yml § TodayTimelineRow`
> `components: [DirectionBadge, CounterpartyText, EventTitleText, SourceTypeIcon, TimestampText]`

`TimestampText` = row 의 상대/절대 시간 표시. S5-A 가 `DirectionBadge` + `CounterpartyText` 를 담당, S5-B 는 `TimestampText` 를 담당.

### 2.3 `.spec/today-timeline.spec.yml` — TDY-001 expected
> "…sorted by sortKey desc, today-KST window only."

### 2.4 `.spec/data-ingestion.spec.yml:140-150` — KST render rule
> "모든 Room `timestamp` 컬럼은 `timestamptz` 로 저장되나 UI 렌더 시 Asia/Seoul 로 고정 변환."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Row 에 timestamp 렌더 없음

`ui/today/TodayTimelineScreen.kt:197-230` 의 `TimelineItemRow` 는 `item.sortKey` 를 전혀 참조하지 않는다. grep 확인:

```bash
grep -n "sortKey\|timestamp" android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt
# → 0 matches
```

### 3.2 `todayRange()` KST 위배

`ui/today/TodayViewModel.kt:381-387`:
```kotlin
private fun todayRange(clock: Clock): ClosedRange<Instant> {
    val now = clock.now()
    val zone = TimeZone.currentSystemDefault()          // ← KST 고정이어야 함
    val today = now.toLocalDateTime(zone).date
    val start = today.atStartOfDayIn(zone)
    val end = start.plus(1, DateTimeUnit.DAY, zone)
    return start..end
}
```

캐노니컬 KST 상수는 `core/util/TimeFormat.kt:26`:
```kotlin
public val KST: TimeZone = TimeZone.of("Asia/Seoul")
```

### 3.3 상대시간 util 부재

프로젝트 내 "N분 전" / "N시간 전" 형태를 생성하는 helper 는 현재 없음. grep:
```bash
grep -rn "분 전\|ago\|RelativeTime" android/app/src/main/java/com/becalm/android/ui/ | wc -l
# → 0
```

`CommitmentCard.kt` 에 `daysUntilInKst` (due-date D-N) 는 있으나 "since now" 방향은 다른 util.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| `TimestampText` component | ui-map.yml row 필수 | 렌더 안 됨 | 신규 Composable |
| KST 렌더 고정 | KST invariant | 기기 TZ 사용 (`todayRange`) | `TimeZone.currentSystemDefault()` → `KST` 상수 치환 |
| "N분 전" 포맷 | 자연스러운 표기 | 없음 | 새 util `formatRelativeSinceInKst(now, past)` |

---

## 5. Proposed Fix

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt`**
  - `todayRange(clock)` 내부 `TimeZone.currentSystemDefault()` → `com.becalm.android.core.util.KST` (import 1 줄 + 본문 1 줄 교체). `clock` 파라미터 그대로 유지. 다른 로직 수정 금지.
  - `buildTimeline` 은 건드리지 않음. VM DTO `TimelineItem.sortKey` 는 이미 `Instant` — UI 가 직접 포맷.

- **`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt`**
  - `TimelineItemRow` 최하단에 `TimestampText(sortKey = item.sortKey)` 추가 렌더 (S5-A 의 Direction/Counterparty 영역 아래). 3 분기 (Commitment / CalendarEvent / Meeting) 공통으로 `item.sortKey` 접근.

- **`android/app/src/main/res/values/strings.xml`** + `values-ko/`
  - `today_since_just_now` = "Just now" / "방금 전"
  - `today_since_minutes` = "%1$d min ago" / "%1$d분 전"
  - `today_since_hours` = "%1$d hr ago" / "%1$d시간 전"
  - `today_since_today_at` = "Today %1$s" / "오늘 %1$s"  (HH:mm format arg)

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/components/TimestampText.kt`** — `@Composable fun TimestampText(sortKey: Instant, clock: Clock = rememberedClock)`. 내부:
  - `val now = clock.now()`
  - `val diffMinutes = (now - sortKey).inWholeMinutes`
  - `diffMinutes < 1` → `today_since_just_now`
  - `1..59` → `today_since_minutes` with `diffMinutes`
  - `60..(24*60)` → `today_since_hours` with `diffMinutes / 60`
  - `else` → `today_since_today_at` with KST `HH:mm` formatted past
  - Style: `labelSmall`, `onSurfaceVariant`

- **`android/app/src/main/java/com/becalm/android/core/util/RelativeTime.kt`** — pure function `@VisibleForTesting fun relativeSinceKst(now: Instant, past: Instant): RelativeSince`. `sealed interface RelativeSince { JustNow; data class Minutes(val n: Int); data class Hours(val n: Int); data class TodayAt(val hhmm: String) }`. Composable 은 이 결과를 받아 string resource 로 변환. 이렇게 분리하면 JVM unit test 가능 (Composable 없이).

- **Tests**:
  - `android/app/src/test/java/com/becalm/android/core/util/RelativeTimeTest.kt` — 5 boundary case (0s, 59s, 1m, 59m, 1h, 23h, 24h+).
  - `android/app/src/test/java/com/becalm/android/ui/today/TodayViewModelTodayRangeTest.kt` — 기기 TZ 를 UTC 로 강제한 뒤 `todayRange()` 가 KST 기준 00:00..다음날00:00 반환하는지 검증 (기존 테스트가 있다면 수정, 없으면 신규).

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- Permission / DB / manifest: 없음.
- 빌드 설정: 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "TimeZone.currentSystemDefault" android/app/src/main/java/com/becalm/android/ui/today/ | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "core.util.KST\|KST)" android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -rn "TimestampText\|relativeSinceKst" android/app/src/main/java/com/becalm/android/ | wc -l` ≥ 3
- [ ] **Unit test**: `RelativeTimeTest` — 7 boundary cases 전부 pass
- [ ] **Unit test**: `TodayViewModelTodayRangeTest.kt` — TZ=UTC 일 때도 KST day boundary 반환 pass
- [ ] **Compile gate**: `./gradlew :app:testDebugUnitTest :app:assembleDebug` 성공
- [ ] **Manual**: Today 스크린에서 Commitment row 하단에 "5분 전" / "오늘 14:32" 형태 텍스트 렌더

---

## 7. Out of Scope

- **DirectionBadge / CounterpartyText** — S5-A plan 담당.
- **Yesterday 이전 타임라인** — Today 는 KST 하루 window 만 보여줌. "어제 N시" 는 범위 밖.
- **절대시간 toggle (long-press → full datetime)** — 별도 plan.
- **Localization past 24h (오늘 HH:mm 가 아니라 MM/DD 로 자동 fallback)** — Today 는 KST 하루 window 이므로 불필요. 만약 쿼리 경계에서 off-by-one 으로 window 밖 데이터가 들어오면 `else` 분기 `today_since_today_at` 가 잘못 "오늘" 로 표시할 수 있으나 — VM 이 window 필터링 책임을 가짐.
- **`formatRelativeSinceInKst` 의 하루 이상 지원** — 위와 동일.
- **sortKey 가 미래인 edge case (샘플 clock 불일치)** — `diffMinutes < 0` 인 경우 `today_since_just_now` 로 fallback. Sentry breadcrumb 로 수집.

---

## 8. Dependencies

- **Blocked by**: 없음 (S5-B 는 #17 머지 이미 완료된 상태에 의존).
- **Blocks**: 없음.
- **Sequential with**: S5-A — 같은 `TodayTimelineScreen.kt` 와 같은 `TimelineItemRow` 를 건드림. **S5-A → S5-B 순서로 sequential commit** 권장 (S5-A 가 `when (item)` 분기 재구조화).

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

- revert 후 timestamp 렌더 사라짐 (본래 상태).
- `todayRange` KST 교정도 revert 되므로 기기 TZ 가 KST 일 경우 행동 무변화, 아닐 경우 regression 가능 — 사용자 KST 집중이므로 실제 regression 확률 낮음.
- 데이터 / schema 영향 0.

---

## Appendix — Session handoff notes

- **왜 `RelativeTime.kt` 를 `core/util/` 에 두나?** — Composable 없이 unit test 가능하게 하려는 목적 + Today 외 재사용 대비 (예: Person detail last-interaction card 에서 S5-C 가 같은 util 호출). Composable 에는 resource 해상도만 남김.
- **`Clock` 주입**: Composable 의 `rememberedClock` 는 `LocalClock.current` pattern 혹은 Hilt 주입 `provideClock()` 을 Composition Local 로 expose. 기존 `AppModule.provideClock` 는 `SystemClock`. 본 plan 에서는 간단히 `Clock.System` 에 의존해도 OK (테스트는 pure-function RelativeTimeTest 로 충분).
- **`todayRange()` 수정의 안전성**: 반환 window 가 하루 단위로 **KST day** 로 정렬되는 게 스펙. 테스트 먼저 (TZ=UTC 설정) 후 수정.
- **S5-A 와 파일 충돌**: 같은 `TimelineItemRow` 를 두 plan 이 건드림. S5-A 커밋 후 S5-B rebase 없이 바로 이어서 같은 브랜치에서 작업.
- **함정 1**: `kotlinx.datetime.Instant` 산술 — `-` 연산자로 `Duration` 반환 (이미 `inWholeMinutes` 사용). `Clock.System.now() - sortKey` 구조체 반환 여부 재확인.
- **함정 2**: `LocalDateTime.format(HH:mm)` — `kotlinx.datetime` 의 `format` API 가 버전 별 상이. 프로젝트 버전 확인 후 fallback 으로 `"${ldt.hour.toString().padStart(2,'0')}:${ldt.minute.toString().padStart(2,'0')}"`.
