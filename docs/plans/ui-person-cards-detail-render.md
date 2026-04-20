# UI / Person / cards-detail-render — PersonRow/PersonDetail/RawEventDetail 렌더링 스펙 정렬

**Branch**: `feat/ui/person/cards-detail-render`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Person Enrichment 소비자(UI)
**Severity**: High
**Type**: Drift + Bug fix

---

## 1. Finding

Source viewer UI가 spec SRC-001/002/004/008의 요구 사항과 크게 어긋나 있으며, `PersonDetailScreen`에는 **동일 문자열 헤더가 두 섹션에 렌더되는 UX 버그**까지 있다.

구체적으로:

1. **SRC-001 PersonRow 누락 필드** — `PersonRowItem` (`PersonsScreen.kt:142-177`)은 name + interactionCount만 표시. spec이 요구하는 `company · title`, 채널 아이콘 클러스터, 미이행 약속 DNBadge, 최근 이벤트 미리보기, 타임스탬프가 전부 없다.
2. **SRC-001 stub aggregate** — `PersonsViewModel.toPersonRow()` (`PersonsViewModel.kt:151-156`)가 `lastInteractionAt = null, interactionCount = 0` 하드코딩. KDoc (`PersonsViewModel.kt:141-150`)이 "DAO aggregate 대기 중"이라고 시인. most_recent_event_time 내림차순 정렬도 불가능 (모든 행이 null).
3. **SRC-002 PersonDetail header 미구현** — `PersonDetailScreen` (`PersonDetailScreen.kt:74-86`)이 타이틀에 `displayName ?: personId.take(16)`만. spec의 `김철수(철수) · ABC Corp · 팀장 · 이벤트 2건 · 미이행 약속 1건` 헤더가 없음. ViewModel은 `companyName / jobTitle`까지 모았으나 (`PersonDetailViewModel.kt:176-178`) UI가 소비하지 않음.
4. **SRC-008 3-섹션 헤더 버그** — `PersonDetailScreen.kt:134-147`:
   ```kotlin
   val commitmentsHeader = stringResource(R.string.person_detail_commitments_section)
   // ...
   interactionSection(header = commitmentsHeader, headerKey = "header-pending", ...)
   interactionSection(header = commitmentsHeader, headerKey = "header-completed", ...)
   ```
   **pending과 completed 두 섹션이 동일한 "약속" 문자열**. spec SRC-008은 `섹션 1 '미이행 약속 (1)'` + `섹션 2 '이행 완료 (1)'` 분리 헤더 + completed 접힘 기본 + count suffix 요구. 이 구현은 사용자가 두 섹션을 구분하지 못하는 명백한 UX 버그이자 spec drift.
5. **SRC-004 RawEventDetail source_type 분기 부재** — `RawEventDetailSheet.kt:100-116`은 source / timestamp / eventTitle만 표시. spec은 voice→duration/transcript/commitments_extracted_count, email→subject/snippet/body, calendar→location/attendees 분기별 상세를 요구.
6. **SRC-008 commitments_extracted_count 배지** — `grep -rn "commitments_extracted_count" android/app/src/main/java/` → `RawIngestionEventEntity`/`IngestionDtos`에는 존재하나 `InteractionRow.Event`에는 필드 없음 → 히스토리 아이템에 배지 미렌더링.

ENR-006 redaction 정책(`PersonsViewModel.kt:42-43`의 `"***"` + `take(3)+"***"` fallback)은 엔드유저 친화성과 충돌 소지. Session handoff에서 결정 포인트로 남김.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/source-viewer.spec.yml:7-15` — SRC-001 PersonRow 렌더링

> "id: SRC-001
>  type: ui_interaction
>  description: \"PersonsScreen 진입 시 인터랙션이 있는 인물 목록이 most_recent_event_time 내림차순으로 표시된다. 각 카드에 persons_enrichment 기반 표시명(display_name 또는 nickname, 없으면 person_ref 원본값) + company/title(있을 경우) + 채널 아이콘 클러스터 + 미이행 약속 건수 DNBadge + 최근 이벤트 미리보기 + 타임스탬프 표시됨\"
>  precondition: \"인증 완료, Room에 person_ref='+821012345678' raw_ingestion_events 2건(voice 1, gmail 1) + persons_enrichment {display_name='김철수', company='ABC Corp', title='팀장'} + 미이행 Commitment 2건 존재\"
>  expected: \"인물 카드 표시됨. 표시명: '김철수 · ABC Corp · 팀장'. 채널 아이콘: 음성+이메일. DNBadge: '미이행 2'. 더 최근 이벤트를 가진 인물이 상단. 커서 기반 페이지네이션(limit=20)\""

### 2.2 `.spec/source-viewer.spec.yml:17-25` — SRC-002 PersonDetail 헤더

> "id: SRC-002
>  description: \"인물 카드 탭 시 PersonDetailScreen으로 이동하며 헤더에 enriched 정보(full_name, nickname, company, title, 이벤트 수, 미이행 약속 수)가 표시되고, 본문에 3개 섹션(미이행 약속 / 이행 완료 / 상호작용 히스토리)이 표시된다\"
>  expected: \"PersonDetailScreen 표시됨. 헤더: '김철수(철수) · ABC Corp · 팀장 · 이벤트 2건 · 미이행 약속 1건'. 섹션 1 '미이행 약속': pending Commitment 1건. 섹션 2 '이행 완료': completed Commitment 1건. 섹션 3 '상호작용 히스토리': voice + gmail 이벤트 event_time 내림차순\""

### 2.3 `.spec/source-viewer.spec.yml:37-45` — SRC-004 RawEventDetail source 분기

> "id: SRC-004
>  description: \"이벤트 항목 탭 시 원시 이벤트 상세 시트가 표시된다 — 소스 타입별 상세: voice(event_title, duration_seconds, transcript, commitments_extracted_count), email(subject=event_title, event_snippet, EmailBody 전문), calendar(title=event_title, location, attendees_raw), 수집 타임스탬프\"
>  expected: \"바텀 시트 열림. '팀 회의' 제목, '30분', transcript.text 전문, '약속 추출 2건' 표시됨. ingestion timestamp 표시됨\""

### 2.4 `.spec/source-viewer.spec.yml:76-84` — SRC-008 3-섹션 구조 + 접힘 + 배지

> "id: SRC-008
>  description: \"PersonDetailScreen 본문 3-섹션 구조 — 섹션 1: 미이행 약속(action_state != 'completed'), 섹션 2: 이행 완료(action_state='completed', 접힌 상태 기본), 섹션 3: 상호작용 히스토리(raw_ingestion_events, event_time 내림차순). commitments_extracted_count >= 1인 히스토리 항목에 배지 표시됨\"
>  expected: \"섹션 1 '미이행 약속 (1)': pending commitment 1건. 섹션 2 '이행 완료 (1)': 접힌 상태. 탭 시 completed commitment 1건 펼쳐짐. 섹션 3 '상호작용 히스토리': voice 이벤트 1건 + '약속 추출 2건' 배지 표시됨\""

### 2.5 `.spec/person-enrichment.spec.yml:56-63` — ENR-006 fallback

> "id: ENR-006
>  description: \"매칭 실패 fallback — persons_enrichment에 해당 person_ref 레코드가 없으면 UI에서 person_ref 원본값(전화번호/이메일/표시이름)을 그대로 표시한다\"
>  expected: \"UI에서 'unknown@domain.com' 그대로 표시됨. 크래시 없음. 앱 기능 정상 동작\""

### 2.6 `.spec/source-viewer.spec.yml:86-91` — 온디바이스 join invariants

> "원문보기(PersonDetailScreen) 데이터는 Room에서 primary 조회한다. Railway GET /v1/persons는 새로고침 시에만 호출됨"
> "person_ref 그룹핑은 Room 쿼리 레벨에서 수행됨 — 별도 persons 테이블 없음 (MVP)"
> "persons_enrichment 조인은 순수 온디바이스 Room LEFT JOIN — Railway/Supabase로 연락처 정보 전송 없음"
> "enrichment 없는 person_ref는 원본값(전화번호/이메일/표시이름)으로 fallback 표시됨 — 크래시 없음"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/persons/PersonsViewModel.kt:33-44, 151-156` — PersonRow 데이터 계약 부족

```kotlin
public data class PersonRow(
    val personRef: String,
    val displayName: String?,
    val lastInteractionAt: Instant?,
    val interactionCount: Int,
) {
    val displayLabel: String get() = displayName ?: if (personRef.length <= 3) "***" else personRef.take(3) + "***"
}
// ...
private fun PersonEnrichmentEntity.toPersonRow(): PersonRow = PersonRow(
    personRef = personRef,
    displayName = displayName,
    lastInteractionAt = null,      // STUB
    interactionCount = 0,          // STUB
)
```

- `company / title / channelsSeen / pendingCommitmentCount / recentEventSummary` 필드 전무.
- `lastInteractionAt`/`interactionCount`가 스텁 → SRC-001 정렬·배지 불가.

### 3.2 `android/app/src/main/java/com/becalm/android/ui/persons/PersonsScreen.kt:141-177` — Row 렌더링 단순화

```kotlin
Column(modifier = Modifier.weight(1f)) {
    Text(text = person.displayLabel, ...)
    if (person.interactionCount > 0) {
        Text(text = stringResource(R.string.persons_interactions_count, person.interactionCount), ...)
    }
}
```

- 채널 아이콘 클러스터 없음.
- 미이행 약속 DNBadge 없음 (컴포넌트는 존재 — `grep` 결과 `DNBadge`가 4개 파일에 있음, 재사용 가능).
- 최근 이벤트 미리보기/타임스탬프 없음.

### 3.3 `android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt:74-86, 127-169` — 헤더 + 섹션 버그

헤더 부재:
```kotlin
val displayName = state.displayName ?: personId.take(16)
BecalmScaffold(title = displayName, ...) { ... }
```
`state.companyName` / `state.jobTitle` (`PersonDetailViewModel.kt:177-178`)가 있어도 UI 소비 0.

섹션 헤더 중복 버그:
```kotlin
val commitmentsHeader = stringResource(R.string.person_detail_commitments_section)
val historyHeader = stringResource(R.string.person_detail_history_section)
LazyColumn(...) {
    interactionSection(header = commitmentsHeader, headerKey = "header-pending",  rows = pendingCommitments,   ...)
    interactionSection(header = commitmentsHeader, headerKey = "header-completed", rows = completedCommitments, ...)
    interactionSection(header = historyHeader,      headerKey = "header-history",   rows = interactionHistory,    ...)
}
```

`commitmentsHeader`가 두 호출에 동일 전달 → 렌더 시 "약속" / "약속" / "상호작용 기록". 스펙은 `"미이행 약속 (N)"` / `"이행 완료 (N)"` / `"상호작용 히스토리"`.

완료 섹션 접힘 상태 / tap-to-expand 없음. `commitments_extracted_count` 배지 없음.

### 3.4 `android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailSheet.kt:100-116` — source 분기 부재

```kotlin
DetailRow(label = stringResource(R.string.raw_event_detail_source), value = event.sourceType)
Spacer(modifier = Modifier.height(12.dp))
DetailRow(label = stringResource(R.string.raw_event_detail_timestamp), value = event.timestamp.toString())
if (event.eventTitle != null) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = event.eventTitle, ...)
}
```

`sourceType == "voice"` / `"gmail"` / `"google_calendar"` 분기 없음. `eventSnippet`은 `RawEventDetailViewModel`에서 200자로 truncate하여 `state.snippet`에 실림(`RawEventDetailViewModel.kt:104`). UI가 소비 0. `duration_seconds` / `commitments_extracted_count` / `attendees_raw` / `location` 필드는 Entity에 존재하나 UI 표시 없음.

### 3.5 `android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt:222-227` — InteractionRow.Event commitmentsExtractedCount 미포함

```kotlin
val eventRows: List<InteractionRow> = rawEvents.map { e ->
    InteractionRow.Event(
        timestamp = e.timestamp,
        source = e.sourceType,
        summary = e.eventTitle,
    )
}
```

`e.commitmentsExtractedCount`(존재함 — RawIngestionEventEntity)를 `InteractionRow.Event`에 전달하지 않음 → SRC-008 "약속 추출 N건" 배지 미지원.

### Grep 검증

```bash
# DNBadge 재사용 가능 확인
grep -rn "DNBadge" android/app/src/main/java/com/becalm/android/ui/
# → components/ 아래 구현 존재

# PersonRowItem 필드 현황
grep -n "company\|title\|channelsSeen\|pendingCommitmentCount\|lastInteractionAt" \
  android/app/src/main/java/com/becalm/android/ui/persons/PersonsScreen.kt
# → 매치 0

# 3-섹션 헤더 중복 버그 확정
grep -n "commitmentsHeader\|header-pending\|header-completed" \
  android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| PersonRow 필드 | name+company/title+channels+DNBadge+preview+timestamp | name+count | 5개 필드 + 렌더러 추가 |
| Row 정렬 | most_recent_event_time 내림차순 | lastInteractionAt=null (정렬 불가) | DAO aggregate 쿼리 필요 |
| PersonDetail 헤더 | `김철수(철수) · ABC Corp · 팀장 · 이벤트 N · 미이행 M` | 타이틀만 displayName | 헤더 Composable 신규 + count 필드 |
| 3-섹션 헤더 | "미이행 약속 (N)" / "이행 완료 (N)" / "상호작용 히스토리" | 두 섹션 모두 "약속"(버그) + count suffix 없음 | 문자열 3개 분리 + count 포맷팅 |
| 완료 섹션 접힘 | 기본 접힘, 탭 시 펼침 | 항상 전개 | ExpandableSection 컴포넌트 |
| commitmentsExtractedCount 배지 | 히스토리 항목에 배지 | Event row에 필드 없음 | InteractionRow.Event 확장 |
| RawEventDetail source 분기 | voice/email/calendar별 상세 | 공통 필드만 | sourceType switch + 필드 매핑 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 상당한 범위 — 섹션 단위로 나눠 구현.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt`**
   - 신규 `@Query`:
     ```
     SELECT person_ref AS personRef,
            COUNT(*)   AS interactionCount,
            MAX(timestamp) AS lastInteractionAt,
            GROUP_CONCAT(DISTINCT source_type) AS channelsCsv
     FROM raw_ingestion_events
     WHERE user_id = :userId AND person_ref IS NOT NULL
     GROUP BY person_ref
     ORDER BY lastInteractionAt DESC
     LIMIT :limit OFFSET :offset
     ```
   - 반환 타입: 신규 `PersonAggregateRow` POJO (`personRef`, `interactionCount`, `lastInteractionAt`, `channelsCsv`).
   - 기존 `idx_raw_events_user_person_time` 인덱스(`RawIngestionEventDao.kt:17`)가 cover. 추가 인덱스 불필요.
   - `Flow<List<PersonAggregateRow>>` 로 observe.

2. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`**
   - 신규 `@Query` — `person_ref`별 action_state가 완료 아닌 commitment 개수:
     ```
     SELECT person_ref AS personRef, COUNT(*) AS pendingCount
     FROM commitments
     WHERE user_id = :userId AND action_state != 'completed' AND deleted_at IS NULL
     GROUP BY person_ref
     ```
   - `deleted_at IS NULL` 필터는 `feat/db/commitment/edit-delete-dispute-supersede` 머지 후 활성화 (Dependency 절 참조).

3. **`android/app/src/main/java/com/becalm/android/ui/persons/PersonsViewModel.kt`**
   - `PersonRow`에 필드 추가: `company: String?`, `title: String?`, `nickname: String?`, `channels: List<String>` (`voice`/`gmail`/`sms` 등), `pendingCommitmentCount: Int`, `recentEventSummary: String?`.
   - `displayLabel` 재구성: `displayName ?: nickname ?: personRef` (ENR-006: 원본 그대로, redaction 완화 — Session handoff 참조).
   - `subtitleLabel` 추가: `listOfNotNull(company, title).joinToString(" · ")`.
   - `observePeople()`가 `PersonEnrichmentRepository.observeEnrichmentMap()` + `RawIngestionEventDao.observePersonAggregates(userId)` + `CommitmentDao.observePersonPendingCounts(userId)` 3 Flow `combine`. enrichment Map을 기반으로 aggregate 행과 outer-join → enrichment 없는 personRef도 포함 (unassigned와 구분: aggregate는 non-null personRef만이므로 자연 분리).
   - 사용자 ID는 `UserPrefsStore.observeCurrentUserId()`에서 reactive 획득 (PersonDetailViewModel 패턴 참조: `PersonDetailViewModel.kt:162-195`).
   - 기존 debounce/query 필터 로직 보존.

4. **`android/app/src/main/java/com/becalm/android/ui/persons/PersonsScreen.kt`**
   - `PersonRowItem`을 3-줄 레이아웃으로 확장:
     - 1줄: `displayName [· company][· title]` (company/title null이면 생략)
     - 2줄: 채널 아이콘 클러스터(`Row` + 아이콘 icons.Filled.Mic / Email / Sms) + `recentEventSummary` ellipsis
     - 3줄: `lastInteractionAt`의 상대 시간 ("2시간 전") + `DNBadge(pendingCommitmentCount)` tail
   - 빈 aggregate(interactionCount=0) 행은 목록에서 제외 (SRC-001 "인터랙션이 있는 인물").

5. **`android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailViewModel.kt`**
   - `PersonDetailUiState`에 `eventCount: Int`, `pendingCount: Int` 추가. `buildInteractions` 반환값에서 계산.
   - `InteractionRow.Event`에 `commitmentsExtractedCount: Int` 추가, `buildInteractions`에서 `e.commitmentsExtractedCount` 매핑.
   - `PersonDetailUiState.nickname` (이미 있음 — 없다면 추가) 소비를 header에 노출.

6. **`android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt`**
   - **헤더 Composable 추가** (`PersonDetailHeader`): `displayName[(nickname)] · company · title · 이벤트 N · 미이행 M` 포맷. `state`에서 4필드 소비.
   - **3-섹션 헤더 문자열 분리**:
     - `R.string.person_detail_pending_commitments_section` 신규 ("미이행 약속 (%1$d)")
     - `R.string.person_detail_completed_commitments_section` 신규 ("이행 완료 (%1$d)")
     - 기존 `person_detail_history_section` 유지 → "상호작용 히스토리"로 rename. 
     - 기존 `person_detail_commitments_section`(단순 "약속")은 사용처 제거 후 **삭제** (dead string).
   - **ExpandableSection 도입**: completed 섹션을 기본 접힘 상태로. Compose `remember { mutableStateOf(false) }` + `if (expanded) items(...)`. Header는 tappable — `onClick` toggle.
   - **히스토리 배지**: `InteractionRowItem`의 `Event` 브랜치에서 `row.commitmentsExtractedCount >= 1`이면 `DNBadge` (또는 파생 `CommitmentExtractedBadge`) 옆 렌더.

7. **`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailViewModel.kt`**
   - `RawEventDetailUiState`에 source_type별 필드 추가: `durationSeconds: Int?` (voice), `attendeesRaw: String?`, `location: String?` (calendar), `bodyFull: String?` (email — eventSnippet 200자 제한 해제 또는 별도 bodyFull 필드 로드).
   - `loadEvent()`에서 `entity.sourceType`에 따라 매핑.
   - email body 전문: `RawIngestionEventEntity.eventSnippet`이 이미 전체(MVP) — snippet 200자 truncate를 제거하고 `RawEventDetailViewModel.kt:104`의 `.take(200)` 제거. PII: 본 화면은 "자세히 보기" intent이므로 full body 허용.

8. **`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailSheet.kt`**
   - `when (state.sourceType)` 분기 렌더러:
     - `"voice"` → 제목 + `"$(durationSeconds / 60)분"` + transcript 전문 (`state.bodyFull`) + `"약속 추출 N건"`
     - `"gmail"`/`"outlook_mail"`/`"naver_imap"`/`"daum_imap"` → subject(=eventTitle) + snippet(=bodyFull) + attendees 없음
     - `"google_calendar"`/`"outlook_calendar"` → title + location + attendees list
   - `SourceType` 문자열 상수 사용 (`SourceType.VOICE` 등 — `SourceTypes.kt:14`).

9. **Strings** (`android/app/src/main/res/values/strings.xml` + `values-ko/strings.xml`)
   - 신규: `person_detail_pending_commitments_section`, `person_detail_completed_commitments_section`, `person_detail_header_format` (ICU 인자 4~5), `raw_event_voice_duration_minutes`, `raw_event_commitments_extracted_badge`, `raw_event_calendar_location`, `raw_event_calendar_attendees`.
   - 삭제: `person_detail_commitments_section` (dead after this PR).

10. **`android/app/src/main/java/com/becalm/android/ui/components/`**
    - 기존 `DNBadge` 재사용.
    - 신규 `ExpandableSection` Composable (if not exists) — `LazyListScope.item(header, expanded, onToggle)` + items when expanded.
    - 신규 `ChannelIconCluster` — `List<String>` → icons row.

### 5.2 Files to add

- `PersonAggregateRow.kt` — Room POJO (DAO 쿼리 결과 매핑).
- `ExpandableSection.kt` — 재사용 composable (이미 없다면).
- `ChannelIconCluster.kt` — 아이콘 클러스터 컴포넌트.

### 5.3 Files to delete (dead code)

- 없음. 본 PR로 생기는 dead code는 위 string `person_detail_commitments_section` 1건 — Files to change 9번에서 처리.

### 5.4 Non-code changes

- DB migration: **없음** — 기존 `raw_ingestion_events` / `commitments` 인덱스가 신규 aggregate 쿼리를 cover.
- Railway `/v1/persons*` 엔드포인트 🪦 상태 → pull-to-refresh(SRC-006)는 **Out of Scope**.

---

## 6. Acceptance Criteria

- [ ] **Unit test (Room)**: `RawIngestionEventDaoTest — observePersonAggregates groups by person_ref with COUNT/MAX/GROUP_CONCAT` — 3 personRef / 5 events fixture로 정확한 aggregate + 채널 CSV 검증.
- [ ] **Unit test (Room)**: `CommitmentDaoTest — observePersonPendingCounts excludes completed and deleted_at IS NOT NULL` (deleted_at 필터는 의존 PR 머지 후 활성).
- [ ] **Unit test (VM)**: `PersonsViewModelTest — combines enrichment + aggregate + pending into PersonRow list sorted by lastInteractionAt DESC`.
- [ ] **Unit test (VM)**: `PersonDetailViewModelTest — eventCount and pendingCount reflect filtered commitments and events`.
- [ ] **Compose test**: `PersonDetailScreenTest — pending and completed sections render distinct headers with count suffix "(N)"`.
- [ ] **Compose test**: `PersonDetailScreenTest — completed section is collapsed by default; tap header expands items`.
- [ ] **Compose test**: `RawEventDetailSheetTest — source_type=voice shows duration + transcript + commitmentsExtractedCount badge`.
- [ ] **Compose test**: `RawEventDetailSheetTest — source_type=google_calendar shows location and attendees`.
- [ ] **Grep invariant**: `grep -c "commitmentsHeader" android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt` = 0 (버그 제거 확인).
- [ ] **Grep invariant**: `grep -c "person_detail_pending_commitments_section\|person_detail_completed_commitments_section" android/app/src/main/res/values/strings.xml` = 2.
- [ ] **Grep invariant**: `grep -rn "interactionCount = 0" android/app/src/main/java/com/becalm/android/ui/persons/PersonsViewModel.kt | wc -l` = 0 (스텁 제거 확인).
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공.

---

## 7. Out of Scope

- **SRC-003 검색 필터 확장** (이메일/전화번호 substring): 현재 `PersonsViewModel.kt:122-127`이 displayName/personRef substring만 커버. SRC-003는 이미 기본 동작하나 company/title substring 확장은 별도 PR.
- **SRC-005 Unassigned 섹션**: `UnassignedEventsScreen.kt`가 이미 별도 화면. PersonsScreen 내부 인라인 섹션화는 별도 UX PR.
- **SRC-006 pull-to-refresh**: Railway `/v1/persons*` 엔드포인트가 🪦 상태 — 별도 PR에서 endpoint 재생 여부 결정 후 진행.
- **SRC-007 offline 배지**: network observer 주입 별도 PR.
- **커서 페이지네이션 limit=20**: `LazyColumn` + Paging 3 도입 별도 PR. MVP는 단일 limit로.
- **`enqueueEnrichment()` 미연결**: `worker-person-enrichment-periodic-observer.md` PR에서 처리.
- **persons_enrichment avatar/starred 필드**: 엔티티 확장 post-MVP.

---

## 8. Dependencies

- **Blocked by**:
  - `feat/db/commitment/edit-delete-dispute-supersede` (PR #TBD) — `CommitmentDao.observePersonPendingCounts` 쿼리에 `deleted_at IS NULL` 필터 추가. 해당 컬럼이 없으면 `AND deleted_at IS NULL` 생략 버전으로 머지 후 후속 작은 PR로 필터 추가 가능. 병렬 진행 허용.
  - `feat/worker/person/enrichment-periodic-observer` — 이 PR이 없으면 `persons_enrichment` 행이 생성되지 않아 manual acceptance가 모두 fallback 경로만 검증. **먼저 머지 권장**이나 코드 의존은 아님.
- **Blocks**:
  - `ui-sources-contacts-permission.md`과 **병렬 가능** (파일 겹침 없음).
- **merge 순서**: (1) worker PR 머지 → (2) 본 PR + sources contacts PR 병렬 → (3) EDIT 컬럼 PR이 선행/후행 무관. EDIT 컬럼이 나중에 머지되면 본 PR의 DAO 쿼리에 `AND deleted_at IS NULL` 붙이는 1-line follow-up PR.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

영향:
- UI 변경만이며 DB 스키마/마이그레이션 없음 → Revert 안전.
- 신규 aggregate DAO 쿼리는 순수 SELECT — revert 시 컴파일 에러 없이 VM 레벨에서만 소비 제거.
- 신규 strings 제거 시 references도 함께 제거되므로 revert 단일 커밋으로 무결.
- Compose test만 실패 — manual regression 없음.

---

## Appendix — Session handoff notes

- **ENR-006 redaction vs 원본 표시 정책 — 결정 포인트 (CTO 확인 필요)**:
  현재 `PersonsViewModel.kt:42-43`:
  ```kotlin
  val displayLabel: String get() = displayName ?: if (personRef.length <= 3) "***" else personRef.take(3) + "***"
  ```
  은 enrichment miss 시 `+821***` / `kim***` 형태로 redact. 하지만 spec ENR-006은:
  > "UI에서 'unknown@domain.com' 그대로 표시됨"
  즉 **원본을 그대로 표시**하라고 요구. 두 접근법의 trade-off:
  - **원본 표시 (spec 준수)**: 사용자가 연락처 동기화 전에도 누구인지 식별 가능. PIPA는 "on-device"만 제약 — UI 표시는 PIPA 적용 대상 아님.
  - **redaction 유지 (현재)**: screenshot/화면 공유 시 개인정보 노출 최소화. 그러나 UX 최악 — 사용자가 누구의 약속인지 판별 불가.

  **권장 결정**: spec 준수 = 원본 표시로 전환. PersonRow.displayLabel = `displayName ?: personRef` (`take(3)+"***"` 제거). Screenshot 보호는 별도 레이어(예: `FLAG_SECURE`)로 해결.

  **CTO 확인 없이 기본값 변경은 금지** — 본 PR 구현 세션은 이 노트를 읽고 결정 질문 후 진행.

- **DAO aggregate 쿼리 성능**: `raw_ingestion_events`의 `idx_raw_events_user_person_time (user_id, person_ref, timestamp)` 인덱스가 `GROUP BY person_ref` 를 cover. 디바이스에 years of history (~10K events) 기준 ~50ms 수준 예상.
- **`GROUP_CONCAT` SQLite 지원**: 기본 지원. 중복 제거는 `GROUP_CONCAT(DISTINCT source_type)`. Kotlin 쪽 파싱은 `split(",")`.
- **채널 아이콘 매핑**: `SourceType.VOICE` → Mic, `GMAIL` / `OUTLOOK_MAIL` / `NAVER_IMAP` / `DAUM_IMAP` → Email, `GOOGLE_CALENDAR` / `OUTLOOK_CALENDAR` → Event, SMS → Sms. `ChannelIconCluster`에 매핑 함수 내장.
- **`DNBadge`**: 기존 컴포넌트 재사용. `pendingCommitmentCount=0`이면 렌더링 skip.
- **`ExpandableSection` LazyListScope 제약**: Compose `LazyListScope.item(key)` 안에서는 state hoisting이 까다로움. `rememberSaveable` in parent + pass `expanded` into `item` 콘텐트 블록 권장.
- **RawEventDetail `eventSnippet` full body**: 현재 VM에서 `.take(200)`하는 이유 미상(PII 보수?). Spec SRC-004는 `EmailBody 전문` 요구. 전문 렌더링으로 전환하되 PII는 이미 Room에 있는 상태 — 새로운 노출 없음. `.take(200)`은 PersonDetailScreen의 Event row summary 용도로만 유지(잘림 미리보기), RawEventDetailSheet는 full.
- **`commitments_extracted_count` 필드 존재 확인**: `grep`에서 `RawIngestionEventEntity` / `IngestionDtos` / `MigrationTest`에 모두 존재. `InteractionRow.Event`에만 missing → 전달만 하면 됨.
- **Pending count에 deleted_at 필터**: EDIT PR 머지 전이면 생략 — 해당 컬럼이 entity에 없어 쿼리 컴파일 실패. 본 PR 초기 구현은 `AND deleted_at IS NULL` **없이** 작성하고, EDIT PR 머지 후 별도 1-line PR로 필터 추가.
- **Pagination**: spec SRC-001 "커서 기반 limit=20". 본 PR에서는 Paging 3 도입 비용이 커서 MVP는 `LIMIT 200` 하드코딩 + 무한 스크롤 없음. spec 완전 충족은 post-MVP PR.
- **`InteractionRow.Event.summary`**: 이벤트 제목만 담는 현 설계 유지. 배지는 별도 필드 `commitmentsExtractedCount`.
- **Compose 접근성**: `semantics { role = Role.Button }`은 기존 코드 패턴(`PersonsScreen.kt:152`) 따라 모든 새 tappable에 부여.
