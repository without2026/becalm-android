# UI / Person / cards-detail-render — `PersonDetailScreen` 에 `PersonHeader` 추가 + `InteractionHistoryRow` 표준화 + v5 action_state 파티션 교정

**Branch**: `feat/ui/wave-5` (umbrella; S5-C commit)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — PersonDetail drill-down (ENR-006 fallback, ui-map.yml § PersonDetail)
**Severity**: High — ui-map.yml 이 `[PersonHeader, CommitmentCard, InteractionHistoryRow, RawEventRow]` 4 component 를 요구하나 실제 화면은 `PersonHeader` 부재 + `InteractionHistoryRow` 부재 + commitment 파티션이 v5 `action_state` 가 아닌 legacy `commitment_state` 기반 (lifecycle regression 위험).
**Type**: Gap (`PersonHeader` 신규) + Drift (commitment 파티션이 v5 컬럼 무시)

---

## 1. Finding

`PersonDetailScreen` (`ui/persons/PersonDetailScreen.kt`) 는 scaffold title 에 `displayName` 만 노출, 본문은 LazyColumn 3-section. 핵심 문제 3 개:

1. **`PersonHeader` 누락** (ui-map.yml 필수) — enrichment 의 `company` / `title` (job title) 이 VM UiState 에 있으나 UI 가 전혀 렌더 안 함.
2. **`InteractionHistoryRow` 표준 컴포넌트 부재** — 화면은 현재 private `InteractionRowItem` 하나로 처리, "약속 추출 N건" 배지 (SRC-008) 없음.
3. **Commitment 파티션이 legacy 컬럼 기반** — `PersonDetailViewModel.kt:243-246` 은 `commitmentState` ("DONE"/"DISMISSED" → completed) 로 분할. 그러나 wave-1 #20 merge 후 origin-of-truth 는 `action_state` (`COMPLETED`, `CANCELLED`, `PENDING` 등). legacy 컬럼이 남아있더라도 v5 action_state 와 어긋날 수 있음 (edit/dispute 경로).

본 plan 은 이 세 가지만 수정. 아바타·alias·heatmap 류는 **스펙에 없음** → 범위 밖.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/ui-map.yml:106-111` — PersonDetail
> `screen: PersonDetailScreen / components: [PersonHeader, CommitmentCard, InteractionHistoryRow, RawEventRow]`
> "3-section body: 미이행 약속 / 이행 완료 / 상호작용 히스토리 (SRC-002, SRC-008)"

### 2.2 `.spec/contracts/ui-map.yml:206-210` — InteractionHistoryRow
> "InteractionHistoryRow: source-icon + title + snippet + timestamp + '약속 추출 N건' 배지"

### 2.3 `.spec/contracts/ui-map.yml:217-223` — PersonCard
> "표시명: enrichment.display_name (없으면 person_ref 원본). company/title 선택적으로 subtitle 렌더."

### 2.4 `.spec/person-enrichment.spec.yml` — ENR-006 fallback
> "매칭 실패 fallback — persons_enrichment 에 해당 person_ref 레코드가 없으면 UI 에서 person_ref 원본값 (전화번호/이메일/표시이름) 을 그대로 표시한다"

### 2.5 `.spec/commitment-edit.spec.yml` — v5 lifecycle
> "commitment 의 완료/취소 판정은 `action_state` 컬럼 기반 (v5). legacy `commitment_state` 는 down-migration safety 를 위해 컬럼으로만 존재하며 UI 분기에 사용 금지."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `PersonHeader` 부재

`ui/persons/PersonDetailScreen.kt:74-77`:
```kotlin
BecalmScaffold(
    title = state.displayName ?: personId.take(16),
    ...
)
```

scaffold title 이 전부. `companyName`, `jobTitle` 은 UiState 에 있으나 body 어디에도 렌더 없음.

`ui/persons/PersonDetailViewModel.kt:90-100`:
```kotlin
data class PersonDetailUiState(
    val displayName: String? = null,
    val companyName: String? = null,
    val jobTitle: String? = null,
    val pendingCommitments: List<CommitmentEntity> = emptyList(),
    val completedCommitments: List<CommitmentEntity> = emptyList(),
    val interactionHistory: List<InteractionUi> = emptyList(),
)
```

### 3.2 `InteractionHistoryRow` private + "약속 추출 N건" 배지 없음

`ui/persons/PersonDetailScreen.kt:212-253` 의 `InteractionRowItem` 은 private. `commitmentsExtractedCount` 배지 렌더 없음. grep:
```bash
grep -rn "commitmentsExtractedCount\|약속 추출" android/app/src/main/java/com/becalm/android/ui/persons/ | wc -l
# → 0
```

### 3.3 Commitment 파티션이 legacy

`ui/persons/PersonDetailViewModel.kt:243-246`:
```kotlin
val (completed, pending) = commitments.partition { c ->
    c.commitmentState == "DONE" || c.commitmentState == "DISMISSED"
}
```

v5 컬럼 `actionState` 는 무시. `CommitmentRepositoryImpl.kt:194-196` 주석: "lifecycle 는 action_state 로 이전 완료".

### 3.4 CommitmentDao 는 이미 `deleted_at IS NULL` 필터

`CommitmentDao.kt:440-449`:
```kotlin
@Query("... WHERE person_ref = :personRef AND deleted_at IS NULL ORDER BY ...")
```
→ 본 plan 은 DAO 수정 불필요, VM 의 파티션 기준만 교정.

### 3.5 검증 grep

```bash
grep -rn "PersonHeader" android/app/src/main/java/com/becalm/android/ui/
# → 0 (부재)

grep -rn "InteractionHistoryRow" android/app/src/main/java/com/becalm/android/ui/
# → 0 (부재 — 현재 private InteractionRowItem 만)

grep -n "commitmentState\|actionState" android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt
# → commitmentState 2 matches, actionState 0 matches → drift 확증
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| PersonHeader | ui-map.yml:106-111 필수 component | 부재 | 신규 Composable |
| company/title subtitle | ui-map.yml:217-223 | UiState 에만 있음 | Header 에 렌더 |
| InteractionHistoryRow 표준 | ui-map.yml:206-210 | private row, 배지 없음 | public component + 배지 |
| 약속 추출 N건 배지 | SRC-008 | 없음 | `CommitmentsExtractedBadge` (S5-D 에서 생성) 재사용 |
| 파티션 키 | v5 `action_state` | `commitment_state` (legacy) | Drift 교정 |
| ENR-006 fallback | display_name null → personRef | 이미 구현 (`state.displayName ?: personId.take(16)`) | 유지 |

---

## 5. Proposed Fix

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt`**
  - scaffold body 최상단에 `PersonHeader(displayName, companyName, jobTitle, personRef)` 배치. LazyColumn 내부 먼저 item { PersonHeader(...) } 로.
  - 기존 private `InteractionRowItem` 제거 (`InteractionHistoryRow` 로 대체).
  - 3-section 의 InteractionHistory 섹션에서 `InteractionHistoryRow(item)` 호출.

- **`android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt`**
  - `partition { commitmentState == "DONE" || commitmentState == "DISMISSED" }` → `partition { actionState in setOf("COMPLETED", "CANCELLED") }`.
  - `InteractionUi` data class 에 `commitmentsExtractedCount: Int = 0` 필드 추가, `mapToInteractionUi` 에서 `entity.commitmentsExtractedCount` 를 전달 (voice 이벤트는 이미 0 인 경우 많음 → graceful).

- **`android/app/src/main/res/values/strings.xml`** + `values-ko/`
  - `person_header_job_subtitle` = "%1$s @ %2$s" (jobTitle @ companyName)
  - `person_header_company_only` = "%1$s"
  - `person_header_job_only` = "%1$s"
  - `person_interaction_snippet_placeholder` = "(No preview)" / "(미리보기 없음)"

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/persons/PersonHeader.kt`** — `@Composable internal fun PersonHeader(displayName: String?, companyName: String?, jobTitle: String?, personRef: String)`. Column 내부:
  - Line 1: `displayName ?: personRef` (ENR-006 fallback — UI 가 null safety 담당), `titleLarge`, `semantics.heading()`
  - Line 2: `job@company` / `job only` / `company only` / omitted — 4 case
  - 좌측에 initials avatar? → **아바타는 spec 에 없음. 범위 밖**
- **`android/app/src/main/java/com/becalm/android/ui/persons/InteractionHistoryRow.kt`** — `@Composable internal fun InteractionHistoryRow(item: PersonDetailViewModel.InteractionUi)`. Row 내부 source-icon + Column(title, snippet) + Spacer + trailing timestamp + (count>0 이면) `CommitmentsExtractedBadge`. source-icon 은 `EventSourceBadge` 재사용 가능하나 PersonDetail 맥락에선 icon only 가 더 간결 → 내부에서 icon resolver 만 추출 or 새 `SourceTypeIcon` 컴포넌트 추출. **소결**: `EventSourceBadge` 의 icon 결정 로직을 private helper 로 공유 가능하게 리팩토링 (S5-C 와 같은 커밋). 단 별도 공개 composable 은 만들지 않음.
- **Tests**:
  - `android/app/src/test/java/com/becalm/android/ui/persons/PersonDetailViewModelActionStateTest.kt`:
    - `partitions_by_actionState_completed` — fixture 에 actionState="COMPLETED" 1개 / "PENDING" 2개 → completedCommitments.size == 1.
    - `partitions_by_actionState_cancelled` — "CANCELLED" → completed 로.
    - `legacy_commitmentState_DONE_but_actionState_PENDING_stays_pending` — drift 방어 검증.
  - `android/app/src/androidTest/java/com/becalm/android/ui/persons/PersonHeaderTest.kt` (optional, Compose UI test):
    - 4 fixture (job+company / job only / company only / neither) → 각 렌더 label 존재/부재 assertion.

### 5.3 Files to delete

- **`InteractionRowItem` private composable** — `PersonDetailScreen.kt:212-253` 의 private 함수. 새 `InteractionHistoryRow` 로 대체. dead code 제거.

### 5.4 Non-code changes
- DB / migration / manifest / permission: 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "PersonHeader\|InteractionHistoryRow" android/app/src/main/java/com/becalm/android/ui/persons/ | wc -l` ≥ 4 (component 선언 + screen 에서 호출)
- [ ] **Grep invariant**: `grep -n "commitmentState == \"DONE\"\|commitmentState == \"DISMISSED\"" android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "actionState in\|actionState == " android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt | wc -l` ≥ 1
- [ ] **Unit test**: `PersonDetailViewModelActionStateTest` — 3 case 전부 pass
- [ ] **Compile gate**: `./gradlew :app:testDebugUnitTest :app:assembleDebug` 성공
- [ ] **Manual**: PersonDetail 진입 시 최상단에 이름/직함/회사 + 본문에 상호작용 row 들이 약속 추출 배지를 포함하여 렌더

---

## 7. Out of Scope

- **아바타 / initials 원형 이미지** — spec 에 없음.
- **Aliases row (여러 이메일/전화)** — spec 에 없음. PersonEnrichmentEntity 에 `aliases_json` 컬럼 부재 → 별도 plan 필요.
- **Frequency heatmap** — spec 에 없음.
- **Last-interaction summary card** — spec 에 없음. 이미 history section 의 첫 row 가 실질적으로 이 역할.
- **CommitmentCard 재배치** — 현재 `InteractionLabelAndBody` 사용. `CommitmentCard` 공식 composable 도입은 별도 plan.
- **`commitment_state` 컬럼 완전 삭제** — down-migration safety 이유로 컬럼은 유지. 본 plan 은 UI 분기만 교정.
- **Worker periodic scheduling** — `worker-person-enrichment-periodic-observer` 는 별도 plan. `EnrichmentWorker` 클래스는 이미 존재.

---

## 8. Dependencies

- **Blocked by**:
  - #20 (v5 `action_state` 컬럼) — 이미 main 에 머지.
  - S5-D (`ui-raw-event-email-rendering`) — `CommitmentsExtractedBadge` composable 을 거기서 먼저 생성. 본 plan 은 재사용. **merge 순서**: S5-D 가 본 plan 앞.
- **Blocks**: 없음.
- **병렬 가능**: S5-A / S5-B 는 `TodayTimelineScreen.kt` 에서만 작업, 본 plan 은 `ui/persons/` 에서만 작업 → 파일 겹침 0. 같은 session 내 합쳐 진행 가능.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

- revert 후 PersonDetail 은 `displayName` title 만 있는 상태로 회귀. company/title 정보 사라지나 데이터 무손실 (enrichment 테이블 그대로).
- Commitment 파티션 키가 legacy 로 되돌아감 → edit/dispute 된 commitment 가 잘못된 섹션에 보일 수 있음 (pre-plan 상태).
- DB 영향 0.

---

## Appendix — Session handoff notes

- **왜 `InteractionHistoryRow` 를 `ui/persons/` 에 두나?** — Today 의 `TimelineItemRow` 와 목적 상이 (Today 는 give/take direction 강조, Person history 는 source-icon + 배지 강조). 공통 추출은 premature abstraction.
- **`SourceTypeIcon` 공유**: `EventSourceBadge` (S5-D) 의 icon resolver 와 `InteractionHistoryRow` 의 source-icon 이 비슷. S5-D 에서 `private fun sourceIcon(sourceType)` 으로 둔 로직을 `ui/components/SourceTypeIcons.kt` 같은 internal util 로 추출 가능 — 단 refactor 패스에서 결정 (implementation-first, refactor-second 원칙).
- **actionState 값의 캐노니컬 set**: `.spec/commitment-edit.spec.yml` 에서 "PENDING, REMINDED, FOLLOWED_UP, COMPLETED, OVERDUE, CANCELLED" 정의. "완료" 섹션 = {COMPLETED, CANCELLED}. OVERDUE 는 pending 섹션 (아직 대응 가능). REMINDED / FOLLOWED_UP 도 pending.
- **legacy `commitmentState` 값과의 매핑**: "DONE" ≈ "COMPLETED", "DISMISSED" ≈ "CANCELLED". 두 컬럼이 같은 row 에서 다른 값을 가질 수 있는 가 — 원칙적으로 migration 시점에 정렬됐으나 dispute 이후 edit path 는 `actionState` 만 업데이트. 따라서 `actionState` 가 유일한 원천 (CommitmentRepositoryImpl 주석 일치).
- **"약속 추출 N건" 배지 재사용**: S5-D 가 생성한 `ui/components/CommitmentsExtractedBadge` 를 그대로 import. 새 composable 금지.
- **VM 테스트 stability**: 기존 `PersonDetailViewModelTest` 는 legacy `commitmentState` fixture 를 사용. 본 plan 구현 시 fixture 를 `actionState` 기반으로 **수정 not 추가** — test drift 도 동시 교정.
