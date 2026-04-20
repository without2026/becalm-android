# UI / Today / counterparty-direction — TimelineItemRow 가 counterpartyDisplayName·direction·cancelled 정보를 모두 버림

**Branch**: `feat/ui/today`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 5–6 (Today Timeline UI 렌더)
**Severity**: Medium (TDY-001 acceptance 직접 위반. ViewModel 에서는 이미 계산되지만 Composable 에서 화면에 전혀 표시되지 않음 → 사용자는 누가 / give·take 여부 / 취소 상태를 알 수 없음)
**Type**: Drift (Spec TDY-001 과 `TimelineItemRow` 렌더 집합 불일치)

---

## 1. Finding

`TodayViewModel` 은 TDY-001 요구에 따라 각 commitment 에 대해 `direction` 과 `counterpartyDisplayName` 을 이미 계산해 `TimelineItem.Commitment` 에 담아 방출한다 (`TodayViewModel.kt:52-58, 312-322`). 그러나 실제 Composable `TimelineItemRow` 는 섹션 라벨 + 제목 **단 두 줄**만 렌더하고, 이 두 필드를 전혀 사용하지 않는다 (`TodayTimelineScreen.kt:196-230`). 결과적으로:

1. 사용자는 약속 카드에서 "give / take" 방향 배지를 볼 수 없다 — TDY-001 의 `"give/take 배지 + 'lee@corp.com' 표시됨"` 요구 위반.
2. 사용자는 상대방이 누구인지 알 수 없다 (`counterpartyDisplayName` 미표시) — 같은 요구 위반.
3. cancelled 캘린더 이벤트도 일반 이벤트와 **동일한 렌더** 로 표시된다 — TDY-001 의 `"strike-through + dim"` 요구 위반 (단, 이 항목은 DB 측 status 컬럼이 존재해야 렌더 가능하므로 `feat/db/calendar/status-recurring` (PR) merge 에 blocked).

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/today-timeline.spec.yml:7-14` — TDY-001 렌더 요구

> description: "TodayTimelineScreen 진입 시 오늘 캘린더 이벤트(status='confirmed')와 due_at이 오늘 KST range에 속하는 commitment가 시간순으로 표시된다. 취소된 캘린더 이벤트(status='cancelled')는 strike-through + dim으로 함께 표시됨. commitment 카드에 counterparty 표시 시 person_ref 기반 표시명 사용"

> expected: "통합 타임라인에 3개 항목이 시간순으로 표시됨. CalendarEvent에는 캘린더 아이콘, **Commitment에는 give/take 배지 + 'lee@corp.com' 표시됨**. 같은 날 status='cancelled' 캘린더 이벤트가 있으면 **strike-through + 회색**으로 추가 표시"

### 2.2 `.spec/today-timeline.spec.yml:105-110` — invariants

> "TodayTimelineScreen은 Room(로컬) 데이터를 primary source로 사용한다"
>
> "TodayTimelineScreen에는 사이드 드로어(HamburgerMenu)가 없다 — 설정 접근은 우상단 아이콘 탭"

(본 PR 은 primary source 및 navigation 은 변경하지 않음 — 순수 렌더 강화.)

### 2.3 참조: `.spec/today-timeline.spec.yml` TDY-002 빈 상태 / TDY-006 pull-to-refresh — 현 Composable 이 이미 충족

본 PR 에서 TDY-002 / TDY-006 경로는 건드리지 않는다. 렌더 내부 `TimelineItemRow` 만 surgical 하게 확장.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt:52-58` — Commitment 타입이 이미 두 필드 보유

```kotlin
public data class Commitment(
    val id: String,
    val title: String,
    val direction: String,
    val counterpartyDisplayName: String?,
    override val sortKey: Instant,
) : TimelineItem()
```

### 3.2 `android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt:312-322` — 실제 매핑

```kotlin
private fun CommitmentEntity.toTimelineItem(
    enrichment: Map<String, PersonEnrichmentEntity>,
): TimelineItem.Commitment =
    TimelineItem.Commitment(
        id = id,
        title = title,
        direction = direction,
        counterpartyDisplayName = resolveCounterpartyDisplay(this, enrichment),
        sortKey = sourceEventOccurredAt,
    )
```
→ VM 계약상 두 값은 항상 전달됨.

### 3.3 `android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt:196-230` — Composable 이 버림

```kotlin
@Composable
private fun TimelineItemRow(
    item: TimelineItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val sectionLabel = when (item) {
            is TimelineItem.Commitment -> stringResource(R.string.today_section_commitments)
            is TimelineItem.CalendarEvent -> stringResource(R.string.today_section_events)
            is TimelineItem.Meeting -> stringResource(R.string.today_section_meetings)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = sectionLabel, ...)
        }
        Spacer(modifier = Modifier.height(4.dp))
        val title = when (item) {
            is TimelineItem.Commitment -> item.title
            is TimelineItem.CalendarEvent -> item.title
            is TimelineItem.Meeting -> item.title
        }
        Text(text = title, ...)
    }
}
```
→ `is TimelineItem.Commitment` 분기에서 `item.direction`, `item.counterpartyDisplayName` 를 모두 무시. cancelled 처리는 `TimelineItem.CalendarEvent` 에 status 필드가 없어서 지금은 불가능.

### 3.4 검증 grep

```bash
grep -n "direction\|counterpartyDisplayName" \
  android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt
# → 0 matches (Composable 에서 두 필드를 한 번도 참조하지 않음)

grep -n "LineThrough\|TextDecoration" \
  android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt
# → 0 matches
```

### 3.5 `TimelineItem.CalendarEvent` 에 status 없음

`TimelineItem.CalendarEvent` (VM:60-69) 및 `TimelineItem.Meeting` (VM:71-82) 에는 `status` 필드가 없다. strike-through 렌더를 하려면 이 UI 모델에도 `status: String` 또는 `isCancelled: Boolean` 을 추가해야 하며, VM 의 `CalendarEventEntity.toTimelineItem()` (`TodayViewModel.kt:350-364`) 이 entity.status 를 전달해야 한다. 즉, 이 부분은 `feat/db/calendar/status-recurring` 이 선행되어야 함.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| give/take 배지 | TDY-001 "give/take 배지" | 미렌더 | Composable 에서 `item.direction` 기반 배지 신설 |
| counterparty 표시 | TDY-001 "'lee@corp.com' 표시됨" | 미렌더 | `item.counterpartyDisplayName` 줄 추가 |
| cancelled strike-through | TDY-001 "strike-through + 회색" | 불가능 (모델·렌더 모두 없음) | UI 모델에 status 추가 + `TextDecoration.LineThrough` + `MaterialTheme.colorScheme.onSurfaceVariant` dim |
| 캘린더 아이콘 | TDY-001 "CalendarEvent에는 캘린더 아이콘" | 섹션 라벨 텍스트만 | `Icons.Filled.Event` 등 leading icon 추가 (경량 surgical) |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt`**
   - `TimelineItem.CalendarEvent` 와 `TimelineItem.Meeting` 에 `val status: String = "confirmed"` 추가 (default 로 두어 테스트 fixture 폭발 방지).
   - `CalendarEventEntity.toTimelineItem()` (line 350-364) 에서 `status = status` 전달. **Dependency**: `CalendarEventEntity.status` 필드가 존재해야 함 → `feat/db/calendar/status-recurring` 선행.
   - (선택) `TimelineItem.Commitment.direction` 을 enum 으로 좁히는 refactor 는 out-of-scope.

2. **`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt`**
   - `TimelineItemRow` 확장:
     - `is TimelineItem.Commitment` 분기에 give/take 배지 Composable (`DirectionBadge(direction = item.direction)`) 추가. 배지 컴포넌트는 `ui/components/DirectionBadge.kt` 로 신설(§5.2) 또는 기존 `CommitmentCard` 의 give/take 렌더 헬퍼 재사용 여부 확인 후 결정.
     - counterpartyDisplayName 이 non-null 일 때 작은 보조 Text (`style = MaterialTheme.typography.bodySmall`, color `onSurfaceVariant`) 로 렌더.
     - `is TimelineItem.CalendarEvent` / `is TimelineItem.Meeting` 분기에서 `item.status == "cancelled"` 일 때:
       - `textDecoration = TextDecoration.LineThrough`
       - `color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)` — dim
       - 섹션 라벨도 동일 톤으로 어둡게
     - CalendarEvent 분기에 `Icons.Filled.Event` leading icon (섹션 라벨과 함께) — TDY-001 "캘린더 아이콘" 요구.

3. **`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt` (Preview)**
   - 기존 `PreviewTodayTimelineScreenWithItems` 가 `EmptyState` 만 보여주고 있음. 신규 Preview 추가:
     - `PreviewCommitmentRow` — direction="give"/"take" 두 개 + counterparty 있는 케이스.
     - `PreviewCancelledCalendarRow` — status="cancelled" strike-through 렌더 확인.
   - ViewModel 없이 `TimelineItemRow` 를 직접 호출하는 preview.

4. **`android/app/src/main/res/values/strings.xml`** (+ `values-ko/strings.xml` 있으면 동일)
   - `today_badge_give` ("받을 약속" 또는 product 결정 문구), `today_badge_take` ("해 줄 약속") 문자열 리소스 신설. KDoc 에 TDY-001 링크.

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/components/DirectionBadge.kt`** (파일 분리 가치가 있을 때만. 한 화면만 쓴다면 `TodayTimelineScreen.kt` 내부 private Composable 로 유지. "200→50 lines 판단" 적용.)

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- Compose UI 테스트: `TodayTimelineScreenTest`
  - Commitment 카드에 direction 배지 + counterparty 텍스트 렌더 검증 (`onNodeWithText("받을 약속")` / `onNodeWithText("lee@corp.com")`).
  - Cancelled CalendarEvent 카드 strike-through 적용 검증 (`SemanticsProperties.TextStyle` 또는 커스텀 tag).
- 스크린샷 테스트 (프로젝트에 도입되어 있다면) — 4 케이스 (give/take × confirmed/cancelled).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "item\.direction\|item\.counterpartyDisplayName" android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -n "TextDecoration\.LineThrough\|LineThrough" android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "Icons\.Filled\.Event\|Event," android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt | wc -l` ≥ 1 (캘린더 아이콘 렌더)
- [ ] **Compose test**: `TodayTimelineScreenTest — commitment row renders give/take badge and counterparty display name`
- [ ] **Compose test**: `TodayTimelineScreenTest — cancelled calendar event applies strike-through and dimmed color`
- [ ] **Preview**: 신규 Preview 4 개 렌더 성공 (give/take × confirmed/cancelled)
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- VM 쪽 direction/ counterpartyDisplayName 계산 로직 변경 — 이미 TDY-001 에 맞게 구현됨, 건드리지 않음.
- TDY-003 `SourceStatusStrip` 칩 렌더 — 별도 PR 범위.
- TDY-004/005 API since 파라미터 교체 — `fix/ui/today/since-kst` (`docs/plans/ui-today-since-kst.md`) 에서 처리.
- ColdSyncScreen 전환 contract (TDY-010) — cold-sync.spec.yml 소유.
- `CommitmentCard` (Person detail / management) 의 배지 렌더 — 별도 plan (`ui-commitment-dn-badge-kst.md` 등).
- recurring event grouping UI — post-MVP (`recurring_event_id` 는 DB 에만 저장).

---

## 8. Dependencies

- **Blocked by (partial)**:
  - `feat/db/calendar/status-recurring` (본 PR 과 동일 시리즈의 다른 plan) — **strike-through 렌더에만 필요**. counterparty / direction 배지는 DB 컬럼 없이도 구현 가능. 따라서:
    - Option A: 두 PR 을 합쳐 하나로 — scope 가 커짐
    - Option B (권장): 본 PR 은 direction + counterparty + 캘린더 아이콘만 먼저 구현, strike-through 는 DB PR 머지 후 별도 follow-up commit. brench 는 `feat/ui/today` 단일 유지.
- **Blocks**:
  - TDY-001 full acceptance.
- **병렬 가능**:
  - `fix/ui/today/since-kst` — 같은 파일(`TodayTimelineScreen.kt` / `TodayViewModel.kt`) 편집 가능성 있음. 충돌 주의:
    - 본 PR 은 Composable 렌더 (line 196-230 근처) + VM 의 `TimelineItem` sealed class 확장
    - since-kst PR 은 `onPullRefresh` (line 283-300) 와 `todayIso()` / `todayRange()` (line 366-377)
    - 서로 다른 구간이므로 text-level 충돌 가능성 낮음. merge 순서만 주의.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

순수 UI 렌더 변경이므로 revert 는 안전. 데이터 레이어 불변. 다만:
- `TimelineItem.CalendarEvent`/`Meeting` 에 `status` 필드를 추가한 경우, fixture 가 default=`"confirmed"` 를 사용하고 있으면 revert 시 fixture 호환. default 없이 필수 인자로 추가했다면 revert 시점에 fixture 복구 추가 커밋 필요.

---

## Appendix — Session handoff notes

- **direction 문자열 값 확인**: `CommitmentEntity.direction: String` 의 정확한 enum 값("give"/"take" / "GIVE"/"TAKE" / locale 문자열?) 구현자가 확인 후 배지 문구 결정. 필요하면 `CommitmentEntityKdoc` 또는 테스트 fixture 에서 실제 값 확인.
- **배지 스타일**: `CommitmentCard` 에 이미 direction 배지가 있다면 그 style 과 정렬. 일관성이 TDY-001 구현 이상으로 중요.
- **counterpartyDisplayName 길이 제한**: VM 에서 이미 `COUNTERPARTY_DISPLAY_MAX = 30` 으로 take(30) 처리 (line 128, 342). Composable 에서는 추가 ellipsis (`maxLines = 1`, `overflow = TextOverflow.Ellipsis`) 로 좁은 화면 안전장치.
- **strike-through 접근성**: TalkBack 이 strike-through 를 명시적으로 읽지 않으므로 `contentDescription = "취소된 일정: $title"` 형식으로 보강. accessibility 는 TDY-001 명시는 아니지만 프로젝트 표준.
- **ProjectionFlag**: VM 에서 이미 `counterpartyRaw`, `quote`, `body` 같은 민감 필드는 projection 에서 제외 중 (line 45-50 KDoc). 본 PR 은 그 경계를 건드리지 않는다 — Composable 은 `counterpartyDisplayName` (이미 enrich 된 값) 만 사용하고, 원시 `personRef` 나 `counterpartyRaw` 를 UI 에 노출하지 않음.
- **Preview 데이터**: 이메일 주소나 실제 이름 노출 금지 — TDY-001 의 `"lee@corp.com"` 은 스펙 예시에 한함. Preview 에서는 `"김동료"` / `"홍길동 <sample@example.com>"` 같은 가공된 값 사용.
