# UI / Today / counterparty-direction — `TimelineItemRow` 에 direction 배지 + counterparty 이름 렌더

**Branch**: `feat/ui/wave-5` (umbrella; S5-A commit)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — Today timeline row (TDY-001 expected-output 일부)
**Severity**: High — TDY-001 의 expected output ("Commitment 에는 give/take 배지 + 'lee@corp.com' 표시됨") 이 VM 에는 완전히 존재하나 Composable 이 무시하고 있어 **사용자에게는 0 픽셀 노출**. 실스크린에서 give/take 구분 불가 → 약속 방향성 인지 자체가 불가능.
**Type**: Drift (VM 가 전달하는 `direction`, `counterpartyDisplayName` 을 `TimelineItemRow` 가 렌더하지 않음)

---

## 1. Finding

`TodayViewModel` 은 이미 `TimelineItem.Commitment(direction: String, counterpartyDisplayName: String?, ...)` 을 빌드하고 `resolveCounterpartyDisplay()` 로 `PersonEnrichmentEntity` fallback chain (`display_name → nickname → personRef → counterpartyRaw.take(30)`) 까지 구현. 그러나 화면 측 `TimelineItemRow` (`ui/today/TodayTimelineScreen.kt:197-230`) 는 `title` 만 렌더. 즉 VM 레이어의 데이터는 완전한데 **UI 가 드롭**하고 있음.

`TodayViewModelTest.kt` 의 TDY-VM-09..12 는 counterparty fallback chain 을 VM 레벨에서 검증 중이지만, 실제 사용자 눈에 보이는 픽셀로 land 되지 않음. 본 plan 은 **Composable 레이어만 수정** — VM/Repo/DB 는 전부 이미 정상 동작.

Calendar/Meeting 분기의 direction 은 `db-calendar-status-recurring` (미-merged) 블로커 때문에 본 plan 범위 **밖** — TimelineItem.CalendarEvent / Meeting 은 당장 렌더 개선 없음.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/today-timeline.spec.yml` — TDY-001
> "MUST: commitment 카드에 counterparty 표시 시 person_ref 기반 표시명 사용 … expected: Commitment 에는 give/take 배지 + 'lee@corp.com' 표시됨"

### 2.2 `.spec/contracts/ui-map.yml § TodayTimelineRow` — components
> `components: [DirectionBadge, CounterpartyText, EventTitleText, SourceTypeIcon, TimestampText]`

현재 Row 는 이 다섯 중 `EventTitleText` 하나만 렌더 (generic `Text`). 본 plan 은 `DirectionBadge` + `CounterpartyText` 두 개만 담당 (`SourceTypeIcon` / `TimestampText` 는 S5-B plan).

### 2.3 `.spec/today-timeline.spec.yml` — TDY-001 expected output (원문)
> "give/take 배지"

give = 내가 상대에게 약속한 것 (I owe). take = 상대가 나에게 약속한 것 (they owe me). 배지는 이 두 관계를 구분.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `TimelineItemRow` — direction/counterparty 렌더 부재

`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt:197-230`:
```kotlin
@Composable
private fun TimelineItemRow(item: TimelineItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(text = sectionLabel(item), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(text = item.title, style = MaterialTheme.typography.bodyMedium)
        // ↑ direction 배지, counterpartyDisplayName 전부 드롭
    }
}
```

### 3.2 VM 쪽은 이미 완전

`android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt:54-60`:
```kotlin
sealed interface TimelineItem {
    data class Commitment(
        val id: String,
        val title: String,
        val direction: String,                  // "give" | "take"
        val counterpartyDisplayName: String?,   // enrichment/nickname/personRef fallback
        val sortKey: Instant,
    ) : TimelineItem
    ...
}
```

`ui/today/TodayViewModel.kt:335-346`:
```kotlin
private fun resolveCounterpartyDisplay(
    personRef: String?,
    counterpartyRaw: String?,
    enrichment: Map<String, PersonEnrichmentEntity>,
): String? {
    if (personRef == null) return counterpartyRaw?.take(30)
    val e = enrichment[personRef]
    return e?.displayName?.takeIf { it.isNotBlank() }
        ?: e?.nickname?.takeIf { it.isNotBlank() }
        ?: personRef
}
```

### 3.3 검증 grep

```bash
# Row 가 direction 에 접근하는지
grep -n "\.direction\|direction " android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt
# → 0 matches

# Row 가 counterpartyDisplayName 에 접근하는지
grep -n "counterpartyDisplayName\|counterparty" android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt
# → 0 matches
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| give/take 배지 | TDY-001 expected | 렌더 안 됨 | 신규 `DirectionBadge` Composable |
| counterparty 표시명 | TDY-001 expected | 렌더 안 됨 | `CounterpartyText` Composable + TimelineItemRow 배선 |
| direction 값의 origin-of-truth | `CommitmentEntity.direction` ("give"/"take") | 이미 VM 이 하류로 흘림 | 변경 없음 |
| Calendar 분기 direction | `db-calendar-status-recurring` 선결 | 미-merged | **본 plan 범위 밖** (deferred) |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만**.

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt`**
  - `TimelineItemRow` 를 `when (item)` 분기로 재구성. Commitment 분기에서 `DirectionBadge(direction)` + `CounterpartyText(counterpartyDisplayName)` 추가 렌더.
  - CalendarEvent / Meeting 분기는 변경 없음 (title 만 렌더 유지 — blocker).

- **`android/app/src/main/res/values/strings.xml`** + `values-ko/strings.xml`
  - `today_direction_badge_give` = "I give" / "내가 약속"
  - `today_direction_badge_take` = "I take" / "상대가 약속"
  - `today_counterparty_unknown` = "Unknown" / "미매칭"
  - `today_direction_badge_unknown` = generic label — direction 값이 "give"/"take" 가 아닌 이상값 방어

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/components/DirectionBadge.kt`** — `@Composable fun DirectionBadge(direction: String)`. 2-색 pill (give = primary container, take = secondary container). label 은 resource. 낯선 direction 값은 `today_direction_badge_unknown` 로 fallback + Firebase Crashlytics breadcrumb.
- **`android/app/src/main/java/com/becalm/android/ui/components/CounterpartyText.kt`** — `@Composable fun CounterpartyText(name: String?)`. null 이면 `today_counterparty_unknown` resource; single-line, `bodySmall`, `onSurfaceVariant`, ellipsis.
- **Tests**:
  - `android/app/src/test/java/com/becalm/android/ui/today/DirectionBadgeTest.kt` — "give"/"take"/unknown 3 case label resolver 단위 테스트 (순수 함수).
  - `android/app/src/androidTest/java/com/becalm/android/ui/today/TimelineItemRowTest.kt` — Compose UI test (`createComposeRule`). Fixture: `Commitment("...", direction="give", counterpartyDisplayName="lee@corp.com")` → onNodeWithText("I give") + onNodeWithText("lee@corp.com") both exist.

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- DB migration: 없음. `CommitmentEntity.direction` 컬럼은 이미 존재.
- Permission: 없음.
- Config/manifest: 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "DirectionBadge\|CounterpartyText" android/app/src/main/java/com/becalm/android/ui/ | wc -l` ≥ 3 (2 component 파일 + Row 에서 호출)
- [ ] **Grep invariant**: `grep -n "item\.direction\|item\.counterpartyDisplayName" android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt | wc -l` ≥ 2
- [ ] **Unit test**: `DirectionBadgeTest` — give/take/unknown label 3 case 통과
- [ ] **UI test**: `TimelineItemRowTest.givesBadgeAndCounterpartyRendered` — give/take fixture 둘 다 pass
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: Today 스크린에서 기존 commitment row 하나라도 give/take 배지 + 상대방 표시명이 한 줄에 함께 렌더

---

## 7. Out of Scope

- **Calendar / Meeting direction 렌더** — `db-calendar-status-recurring` 미-merged 블로커. 본 plan 은 Commitment 분기만.
- **TDY-001 cancelled-event strike-through** — 별도 plan.
- **`SourceTypeIcon` / `TimestampText`** — S5-B plan 담당.
- **`resolveCounterpartyDisplay` 로직 수정** — VM 레이어는 손대지 않음. 현재 동작 정확.
- **DirectionBadge tap behaviour** — display-only, no click.
- **Attendee chip (Meeting 분기)** — `TimelineItem.Meeting.attendeesRaw` 렌더는 별도 plan.

---

## 8. Dependencies

- **Blocked by**: 없음 (S5-A 는 commitment data 만 의존, #20 & commitmentState v5 는 이미 main).
- **Blocks**:
  - S5-B (`ui-today-since-kst`) — 같은 `TimelineItemRow` / `TodayTimelineScreen.kt` 수정 → 같은 umbrella branch 에 sequential commit.
- **병렬 불가**: S5-B 와 같은 파일을 건드림. 같은 session 내 순차 commit.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
1. `TimelineItemRow` 는 `title` 만 렌더 상태로 회귀. direction/counterparty 은 사용자에게 보이지 않음 (본래 상태).
2. `DirectionBadge` / `CounterpartyText` 파일은 revert 와 함께 사라짐 → dangling import 없음.
3. Data integrity / migration 영향 0.

---

## Appendix — Session handoff notes

- **왜 composable 을 ui/components/ 에 두나?** — `DirectionBadge` 는 Today 외에도 Person detail 의 commitment 섹션 (S5-C) 과 CommitmentCard 재사용 가능. Today 전용 폴더 금지.
- **"give"/"take" 리터럴 하드코딩** — `CommitmentEntity.direction` 이 이미 이 두 문자열로 저장됨 (`CommitmentDao.kt`, `CommitmentRepositoryImpl.kt`). sealed enum 으로 대체하는 refactor 는 본 plan 범위 밖 — 문자열 비교만 수행.
- **TDY-VM-09..12 테스트**: 이미 VM 레벨에서 fallback chain 검증 중. UI 테스트는 rendering 만 확인하면 됨 — VM 로직 재검증 금지.
- **`today_counterparty_unknown` 은 왜?** — `counterpartyDisplayName` 이 `null` 인 경우 (personRef 와 counterpartyRaw 모두 null — 드물지만 가능) 빈 영역으로 레이아웃이 찌그러지지 않도록.
- **direction 이 "give"/"take" 가 아닌 값** — `BecalmTheme` 색상 시스템에서 `tertiary` 를 써서 눈에 띄게 하고 Firebase Crashlytics `addBreadcrumb("unknown_direction_rendered")` 로 수집. 이는 데이터 품질 모니터링 용도.
