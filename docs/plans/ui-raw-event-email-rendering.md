# UI / Raw-event / email-rendering — RawEventDetailSheet 에 이메일 전용 렌더(EmailBody 조인, `📎 첨부 N건`, CommitmentsExtractedBadge)

**Branch**: `feat/ui/raw-event`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — PersonDetail → RawEventDetail 드릴다운
**Severity**: **High** (SRC-004 의 email 분기 — `event_snippet` / `EmailBody 전문` / `attachments_meta` — 세 필드 모두 UI 에 노출 안 됨. 사용자는 이메일 이벤트를 탭해도 `sourceType=email` 과 timestamp + 제목만 보게 됨. 추출된 commitment 수 배지도 없음.)
**Type**: Gap (ui-map.yml:116 6 components 중 4 개 누락) + Drift (`RawEventDetailViewModel` 가 EmailBody JOIN 을 수행하지 않음)

---

## 1. Finding

`.spec/contracts/ui-map.yml:113-118` 은 `RawEventDetailSheet` 의 components 를 6 개로 명시:

```
components: [EventSourceBadge, EventTitleText, EventSnippetText, DurationText,
             LocationText, CommitmentsExtractedBadge, IngestionTimestamp, BackButton]
```

현재 `ui/persons/RawEventDetailSheet.kt:86-118` 은 이 중 **`EventTitleText` + `IngestionTimestamp` 만** 구현 (그마저도 `DetailRow` 라는 generic composable 로). 나머지 `EventSourceBadge` / `EventSnippetText` / `CommitmentsExtractedBadge` 는 **존재하지 않음**. `DurationText` (voice) / `LocationText` (calendar) 는 본 plan 범위 밖이지만 email-specific 3 요소는 필수.

이메일 고유 요구 (SRC-004, EMAIL-004):
- **`EmailBody 전문` 표시** (`SRC-004`: "email(subject=event_title, event_snippet, **EmailBody 전문**)"). 현재 VM 은 `EmailBody` 테이블을 JOIN 하지 않음 — `RawEventDetailViewModel.kt:96-106` 은 `rawIngestionRepository.findById` 만 호출.
- **`📎 첨부 N건` 라벨** (`EMAIL-004`: "UI는 '📎 첨부 N건' 표시만"). 현재 `attachments_meta` JSON 을 파싱하거나 갯수를 세는 코드 0 matches.
  ```bash
  grep -rn "attachment\|첨부\|📎" android/app/src/main/java/com/becalm/android/ | wc -l
  # → 0
  ```
- **`CommitmentsExtractedBadge`** (ui-map.yml:116, SRC-008 "commitments_extracted_count >= 1 시 '약속 추출 N건' 배지 표시"). `RawIngestionEventEntity.commitments_extracted_count` 컬럼 (`:140-141`) 은 존재하나 UI 어디에서도 읽지 않음.

또한 `EmailBody.body_html` only 케이스 (EMAIL-007: HTML 파싱 실패 후 `body_plain=null, body_html=원본`) 에 대한 **원문보기 WebView** 는 post-MVP (EMAIL-007 의 "post-MVP"). 본 plan 은 MVP 로 **body_plain 표시 + "원문보기" 버튼은 TODO 마커로 남김** (구현은 후속 PR).

본 plan 은 **UI + VM JOIN 2 가지** 수정 + **3 신규 component** 추가 + **EmailAttachmentMeta JSON 파싱** 으로 구성.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/source-viewer.spec.yml:37-45` — SRC-004
> "이벤트 항목 탭 시 원시 이벤트 상세 시트가 표시된다 — 소스 타입별 상세: voice(event_title, duration_seconds, transcript, commitments_extracted_count), **email(subject=event_title, event_snippet, EmailBody 전문)**, calendar(title=event_title, location, attendees_raw), 수집 타임스탬프"

### 2.2 `.spec/source-viewer.spec.yml:76-83` — SRC-008
> "PersonDetailScreen 본문 3-섹션 구조 … **commitments_extracted_count >= 1인 히스토리 항목에 배지 표시됨** … 섹션 3 '상호작용 히스토리': voice 이벤트 1건 + '약속 추출 2건' 배지 표시됨"

### 2.3 `.spec/email-pipeline.spec.yml:40-47` — EMAIL-004
> "첨부파일은 메타데이터만 보존하고 바이트는 다운로드하지 않는다 — EmailBody.attachments_meta에 `[{filename, mime, size_bytes}]` JSON으로 저장. PDF/DOCX OCR·내용 추출은 post-MVP. **UI는 '📎 첨부 N건' 표시만**"

### 2.4 `.spec/email-pipeline.spec.yml:67-74` — EMAIL-007
> "HTML 파싱 실패(잘못된 HTML / charset mismatch / Jsoup timeout)는 raw event를 quarantine으로 넘기지 않고 event_snippet=subject로 graceful degrade한다 … 사용자는 원문보기 UI에서 body_html을 WebView로 렌더링 가능(**post-MVP**)"

### 2.5 `.spec/email-pipeline.spec.yml:76-82` — invariants
> "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)"
> "첨부파일 바이트는 다운로드하지 않는다 — 메타데이터만 보존"

### 2.6 `.spec/contracts/ui-map.yml:113-118`
> "- path: /persons/{person_id}/events/{event_id} / screen: RawEventDetailSheet / data: [] / components: [**EventSourceBadge, EventTitleText, EventSnippetText, DurationText, LocationText, CommitmentsExtractedBadge, IngestionTimestamp, BackButton**] / auth: required / # Bottom sheet — raw event drill-down with extended fields. Data loaded from Room directly."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 RawEventDetailSheet — 4 component 누락, email 분기 없음

`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailSheet.kt:86-118`:
```kotlin
state.event != null -> {
    val event = state.event
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            DetailRow(
                label = stringResource(R.string.raw_event_detail_source),
                value = event.sourceType,                 // ← raw string, no EventSourceBadge
            )
            Spacer(modifier = Modifier.height(12.dp))
            DetailRow(
                label = stringResource(R.string.raw_event_detail_timestamp),
                value = event.timestamp.toString(),       // ← raw Instant.toString(), no IngestionTimestamp
            )
            if (event.eventTitle != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = event.eventTitle,              // ← generic Text, no EventTitleText
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // ← EventSnippetText 없음
            // ← 본문(EmailBody.body_plain) 없음
            // ← 첨부 pill 없음
            // ← CommitmentsExtractedBadge 없음
        }
    }
}
```

### 3.2 RawEventDetailViewModel — EmailBody JOIN 부재

`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailViewModel.kt:96-114`:
```kotlin
val entity = rawIngestionRepository.findById(id = eventId, userId = userId)
// ...
_uiState.value = if (entity != null) {
    RawEventDetailUiState(
        eventId = entity.id,
        sourceType = entity.sourceType,
        eventTitle = entity.eventTitle,
        timestamp = entity.timestamp,
        snippet = entity.eventSnippet?.take(200),   // ← raw event snippet only
        loading = false,
        // ← emailBody: EmailBodyUi? 필드 없음
        // ← commitmentsExtractedCount: Int 필드 없음
        // ← attachmentCount: Int 필드 없음
    )
}
```

`RawEventDetailUiState` (`:33-41`) 에 email-specific 필드 전무.

### 3.3 commitments_extracted_count 컬럼은 존재하나 읽지 않음

`data/local/db/entity/RawIngestionEventEntity.kt:140-141`:
```kotlin
@ColumnInfo(name = "commitments_extracted_count")
val commitmentsExtractedCount: Int = 0,
```
→ VM / UI 어디에서도 접근 없음.

### 3.4 첨부 관련 코드 0 matches

```bash
grep -rn "attachment\|첨부\|📎\|attachments_meta" android/app/src/main/java/com/becalm/android/
# → 0 matches (main tree)
```

### 3.5 EmailBody 테이블 자체가 없음 (PR #1 blocker)

```bash
grep -rn "EmailBodyEntity\|EmailBodyDao\|EmailBodyRepository" android/app/src/main/java/com/becalm/android/
# → 0 matches
```
→ `db-email-schema.md` (PR #1) 및 `repo-email-body.md` (PR #2) 가 선행 blocker. 본 plan 은 그 산출물을 소비한다.

검증 grep:
```bash
grep -rn "EventSourceBadge\|EventSnippetText\|CommitmentsExtractedBadge\|EmailAttachmentCountPill" \
  android/app/src/main/java/com/becalm/android/
# → 0 matches (현 시점)

grep -rn "raw_event_attachments_count" android/app/src/main/res/
# → 0 matches
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| `EventSourceBadge` | ui-map.yml:116 component | 없음 | 신규 Composable |
| `EventTitleText` | ui-map.yml:116 component | generic `Text` 재사용 | 세만틱 래퍼 신규 (a11y heading) |
| `EventSnippetText` | ui-map.yml:116 component / SRC-004 "event_snippet" | 없음 | 신규 Composable (3 줄 ellipsize) |
| body 전문 | SRC-004 "EmailBody 전문" | 없음 | `EmailBody.body_plain` lazy 확장 UI ("전체 보기") |
| `📎 첨부 N건` | EMAIL-004 | 없음 | `EmailAttachmentCountPill` + `attachments_meta` JSON 파싱 |
| `CommitmentsExtractedBadge` | ui-map.yml:116 / SRC-008 | 없음 | 신규 Composable + `commitmentsExtractedCount` 읽기 |
| `IngestionTimestamp` | ui-map.yml:116 component | `event.timestamp.toString()` raw | KST HH:mm 포맷 |
| email 분기 | SRC-004 "소스 타입별 상세" | 단일 generic layout | `when (sourceType in EMAIL_TYPES)` 분기 |
| body_html fallback | EMAIL-007 "원문보기 WebView (post-MVP)" | — | 버튼만 두고 `TODO` 마커 (post-MVP) |
| Room JOIN | SRC-004 "EmailBody 전문" | `findById` (raw_ingestion_events 단독) | VM 에 EmailBody LEFT JOIN query |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailSheet.kt`**
  - `DetailRow` 3 호출 제거. 대신 `when (event.sourceType in EmailSourceTypes)` 분기:
    - **email 분기 → `EmailEventDetailSection` 호출** (5.2 신규 파일). props: `sourceType`, `subject: String?` (= eventTitle), `snippet: String?`, `body: EmailBodyUi?`, `commitmentsExtractedCount: Int`, `timestamp: Instant`.
    - **non-email (voice/calendar) 분기**: 기존 렌더 유지 (본 plan 범위 밖. 단 `EventSourceBadge` / `IngestionTimestamp` 는 공통 컴포넌트로 호출).
  - 상단 공통 영역에 `EventSourceBadge(sourceType)` + `IngestionTimestamp(timestamp)` 렌더 (sourceType 무관).
  - sheet title 은 그대로 `R.string.raw_event_detail_title` 사용.

- **`android/app/src/main/java/com/becalm/android/ui/persons/RawEventDetailViewModel.kt`**
  - `RawEventDetailUiState` 필드 추가:
    - `emailBody: EmailBodyUi?` (nullable; email 이 아닌 경우 null)
    - `commitmentsExtractedCount: Int` (default 0)
    - `attachmentCount: Int` (default 0)
  - `loadEvent()` 에서 entity 로드 후, `entity.sourceType in EMAIL_TYPES` 일 때 `emailBodyRepository.findByRawEventId(eventId)` 호출 (PR #2 산출물). 결과로 `EmailBody.body_plain`, `attachments_meta` 를 `EmailBodyUi` 로 매핑.
  - `attachmentCount = parseAttachmentsMeta(emailBody?.attachmentsMeta).size` — `parseAttachmentsMeta` 는 5.2 신규 유틸.
  - `commitmentsExtractedCount = entity.commitmentsExtractedCount`.
  - Hilt inject: `EmailBodyRepository` (PR #2 의 interface).

- **`android/app/src/main/res/values/strings.xml`** (및 `values-ko/` 동일 — 앱이 ko-only)
  - `raw_event_attachments_count = "📎 첨부 %1$d건"`
  - `raw_event_commitments_extracted = "약속 추출 %1$d건"`
  - `raw_event_body_expand = "전체 보기"`
  - `raw_event_body_collapse = "접기"`
  - `raw_event_view_html_original = "원문보기"` (post-MVP 버튼 label)
  - `raw_event_view_html_original_todo_toast = "원문보기는 다음 업데이트에서 제공됩니다"` (TODO toast)
  - `raw_event_source_badge_email_gmail`, `..._outlook_mail`, `..._naver_imap`, `..._daum_imap` — badge 내 표시명 (공통 매퍼 `sourceDisplayName` 을 재사용해도 OK, 본 plan 은 별도 라벨 제안).

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/persons/EmailEventDetailSection.kt`** — `@Composable internal fun EmailEventDetailSection(state: RawEventDetailUiState)`. Column 내부에 순서대로:
  1. `EventSourceBadge(state.sourceType)` — (5.2 공용 컴포넌트, `ui/components/`)
  2. `EventTitleText(state.eventTitle)` — subject (null 이면 placeholder `stringResource(R.string.raw_event_detail_no_title)`)
  3. `EventSnippetText(state.snippet)` — 3 줄 ellipsize, `MaterialTheme.typography.bodySmall`
  4. body 영역: `var expanded by remember { mutableStateOf(false) }`. 초기 렌더는 `body_plain.take(500)` 까지. `body_plain.length > 500` 이면 `[전체 보기]` text button 표시 → 탭 시 `expanded=true` → 전체 본문 렌더 + `[접기]`. `body_plain=null && body_html!=null` 인 경우 (EMAIL-007 degrade) placeholder `stringResource(R.string.raw_event_body_html_only_notice)` + `[원문보기]` 버튼 — **post-MVP TODO** marker로 `Toast.makeText(...)` 나 Snackbar 로 "다음 업데이트에서 제공" 안내만.
  5. `if (state.attachmentCount > 0) EmailAttachmentCountPill(state.attachmentCount)`
  6. `if (state.commitmentsExtractedCount > 0) CommitmentsExtractedBadge(state.commitmentsExtractedCount)`
  7. `IngestionTimestamp(state.timestamp)` — 마지막
- **`android/app/src/main/java/com/becalm/android/ui/components/EventSourceBadge.kt`** — `@Composable fun EventSourceBadge(sourceType: String)`. Pill 형태 (작은 rounded corner surface). Icon + 짧은 라벨 (Gmail/Outlook/네이버/다음/Calendar 아이콘 + 텍스트). 아이콘은 `Icons.Outlined.Email` 기반 공통 + sourceType 별 accent color. Preview 는 6 source 전부 포함.
- **`android/app/src/main/java/com/becalm/android/ui/components/EventTitleText.kt`** — `@Composable fun EventTitleText(title: String?)`. `MaterialTheme.typography.titleMedium`, `maxLines = 2`, `overflow = Ellipsis`, `semantics { heading() }` (a11y heading role).
- **`android/app/src/main/java/com/becalm/android/ui/components/EventSnippetText.kt`** — `@Composable fun EventSnippetText(snippet: String?)`. `bodySmall`, `onSurfaceVariant`, `maxLines = 3`, `overflow = Ellipsis`. null 이면 미렌더.
- **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentsExtractedBadge.kt`** — `@Composable fun CommitmentsExtractedBadge(count: Int)`. `AssistChip` 류 — 아이콘 (`Icons.Outlined.CheckCircle`) + `stringResource(R.string.raw_event_commitments_extracted, count)`. count=0 이면 caller 가 호출 안 함 (본 Composable 자체는 `require(count > 0)` assert).
- **`android/app/src/main/java/com/becalm/android/ui/components/EmailAttachmentCountPill.kt`** — `@Composable fun EmailAttachmentCountPill(count: Int)`. 텍스트 only pill, `stringResource(R.string.raw_event_attachments_count, count)`. `📎` 이모지는 string resource 에 포함되어 있으므로 Composable 내부 이모지 하드코딩 금지.
- **`android/app/src/main/java/com/becalm/android/ui/components/IngestionTimestamp.kt`** — `@Composable fun IngestionTimestamp(timestamp: Instant)`. KST `yyyy-MM-dd HH:mm` 포맷. `labelSmall`, `onSurfaceVariant`.
- **`android/app/src/main/java/com/becalm/android/ui/persons/EmailBodyUi.kt`** — `data class EmailBodyUi(val bodyPlain: String?, val bodyHtml: String?, val attachmentsMeta: String?)` — VM → UI 간 DTO (Repository 의 domain entity 와 분리). PII 노출 최소화: `attachments_meta` 는 파싱 전 raw JSON 문자열 보관 → 필요 시 `parseAttachmentsMeta` 로 count 만 추출.
- **`android/app/src/main/java/com/becalm/android/ui/persons/AttachmentMetaParser.kt`** — `internal fun parseAttachmentsMeta(json: String?): List<AttachmentMeta>` + `data class AttachmentMeta(val filename: String, val mime: String, val sizeBytes: Long)`. kotlinx.serialization `Json { ignoreUnknownKeys = true }`. 파싱 실패 시 empty list 반환 (graceful — UI 는 count=0 표시). `Sentry.addBreadcrumb("attachment_meta_parse_failed")` 만 남김.
- **`android/app/src/main/java/com/becalm/android/ui/persons/EmailSourceTypes.kt`** — `internal val EMAIL_SOURCE_TYPES: Set<String> = setOf(SourceType.GMAIL, SourceType.OUTLOOK_MAIL, SourceType.NAVER_IMAP, SourceType.DAUM_IMAP)`. 화면과 VM 이 공유.
- **Tests**:
  - `android/app/src/test/java/com/becalm/android/ui/persons/RawEventDetailViewModelEmailTest.kt`:
    - `loadEvent_emailSourceType_joinsEmailBody` — Fake `EmailBodyRepository` 반환값이 UiState 에 채워지는지.
    - `loadEvent_voiceSourceType_doesNotCallEmailBodyRepository` — email 이 아닐 때 JOIN 건너뛰기.
    - `loadEvent_attachmentsMetaParsed_countMatches` — JSON `[{filename,...}, {...}]` 입력 → count=2.
    - `loadEvent_attachmentsMetaInvalidJson_countZero` — 깨진 JSON → count=0, 크래시 없음.
    - `loadEvent_commitmentsExtractedCountPropagated` — entity.commitmentsExtractedCount=3 → UiState.commitmentsExtractedCount=3.
  - `android/app/src/test/java/com/becalm/android/ui/persons/AttachmentMetaParserTest.kt`:
    - 유효 JSON 2 건 / 빈 배열 / null / malformed 4 case.
  - `android/app/src/androidTest/java/com/becalm/android/ui/persons/RawEventDetailSheetEmailSnapshotTest.kt` (Roborazzi):
    - fixture: Gmail 이벤트 with body_plain 300 chars + 2 attachments + commitmentsExtractedCount=2 → snapshot.
    - fixture: IMAP 이벤트 no attachments + commitmentsExtractedCount=0 → snapshot (배지 미렌더 확인).
    - fixture: body_html-only degrade case → `[원문보기]` 버튼 + notice 표시.

### 5.3 Files to delete (dead code)

없음. 기존 `DetailRow` private composable (`RawEventDetailSheet.kt:130-145`) 는 voice/calendar 분기에서 재사용 가능하므로 유지.

### 5.4 Non-code changes

- DB 마이그레이션: 없음. `EmailBody` 테이블은 PR #1 산출물, `commitments_extracted_count` 컬럼은 이미 존재.
- Config/manifest: 변경 없음.
- Permission: 변경 없음. body_plain 읽기는 Room 내부 — 추가 권한 없음.
- 이모지 렌더: `📎` 이모지는 시스템 기본 폰트에서 렌더. 별도 font asset 불필요.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증**할 수 있는 항목.

- [ ] **Grep invariant**: `grep -rn "EventSourceBadge\|EventSnippetText\|CommitmentsExtractedBadge\|EmailAttachmentCountPill" android/app/src/main/java/com/becalm/android/ui/components/ | wc -l` ≥ 4 (4 신규 파일 존재)
- [ ] **Grep invariant**: `grep -n "raw_event_attachments_count" android/app/src/main/res/values/strings.xml | wc -l` = 1
- [ ] **Grep invariant**: `grep -n "📎 첨부" android/app/src/main/res/values/strings.xml | wc -l` = 1 (이모지 + 한글 label)
- [ ] **Grep invariant**: `grep -rn "EMAIL_SOURCE_TYPES\|EmailSourceTypes" android/app/src/main/java/com/becalm/android/ui/persons/ | wc -l` ≥ 2 (VM + sheet 모두 상수 사용)
- [ ] **Grep invariant**: `grep -rn "commitmentsExtractedCount" android/app/src/main/java/com/becalm/android/ui/persons/ | wc -l` ≥ 2 (VM + state 필드)
- [ ] **Unit test**: `RawEventDetailViewModelEmailTest.loadEvent_emailSourceType_joinsEmailBody` 통과 — Fake `EmailBodyRepository.findByRawEventId` 가 정확히 1 회 호출되고 결과가 UiState.emailBody 에 반영
- [ ] **Unit test**: `RawEventDetailViewModelEmailTest.loadEvent_voiceSourceType_doesNotCallEmailBodyRepository` 통과 — voice 이벤트 시 `findByRawEventId` 호출 0 회
- [ ] **Unit test**: `RawEventDetailViewModelEmailTest.loadEvent_attachmentsMetaParsed_countMatches` 통과 — 2 첨부 JSON → UiState.attachmentCount=2
- [ ] **Unit test**: `RawEventDetailViewModelEmailTest.loadEvent_attachmentsMetaInvalidJson_countZero` 통과 — malformed JSON → crash 없음, count=0
- [ ] **Unit test**: `RawEventDetailViewModelEmailTest.loadEvent_commitmentsExtractedCountPropagated` 통과 — entity 3 → state 3
- [ ] **Unit test**: `AttachmentMetaParserTest` — 4 case 전부 통과 (valid / empty / null / malformed)
- [ ] **Snapshot test**: `RawEventDetailSheetEmailSnapshotTest.gmail_with_attachments_and_commitments` — `📎 첨부 2건` pill + `약속 추출 2건` badge 가 snapshot 에 포함 (이미지 diff baseline 통과)
- [ ] **Snapshot test**: `RawEventDetailSheetEmailSnapshotTest.imap_no_attachments_no_commitments` — 두 배지 모두 미렌더
- [ ] **Snapshot test**: `RawEventDetailSheetEmailSnapshotTest.html_only_degrade_shows_view_original_button` — `[원문보기]` 버튼 + notice 텍스트 렌더
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:verifyRoborazziDebug` 성공
- [ ] **Manual**: Gmail 이벤트 탭 → body_plain 300+ chars 이벤트에서 `[전체 보기]` 탭 → 전체 본문 expand → `[접기]` 로 재축소
- [ ] **Manual**: `attachments_meta = "[{filename:\"report.pdf\",...},{filename:\"data.xlsx\",...}]"` 인 이벤트에서 `📎 첨부 2건` 렌더

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **`EmailBodyEntity` / DAO / Repository 구현** — PR #1 (`db-email-schema.md`) + PR #2 (`repo-email-body.md`) 범위. 본 plan 은 `EmailBodyRepository.findByRawEventId(rawEventId): EmailBody?` suspending API 를 **소비만**.
- **Voice 분기 (`DurationText`, `transcript` 렌더)** — 별도 plan (ui-raw-event-voice-rendering.md, 미작성). 본 plan 의 분기 구조는 voice/calendar 분기를 `TODO` 주석으로만 남기고 기존 `DetailRow` 재사용.
- **Calendar 분기 (`LocationText`, `attendees_raw`)** — 동일하게 별도 plan.
- **`body_html` WebView 원문보기** — EMAIL-007 "post-MVP" 명시. 본 plan 은 `[원문보기]` 버튼 UI 만 두고 탭 시 "다음 업데이트에서 제공" Snackbar/Toast. 실제 WebView 는 후속 plan.
- **PIPA 본문 hidden/redact 기능** — EmailBody 전문 표시는 SRC-004 가 요구하므로 UI 에 그대로 노출. 민감정보 redaction 은 별도 compliance plan.
- **Attachment 다운로드 / 미리보기** — EMAIL-004 명시적으로 "바이트 다운로드 안 함, 메타데이터만". 본 plan 은 count pill 외 추가 UI 없음.
- **Thread view (Re:/Fwd:)** — EMAIL-005 "MVP는 thread grouping 없이 각 메시지를 독립 raw event 로 저장". 본 plan 도 동일.
- **RawEventDetailViewModel 의 non-email 파트 리팩토링** — UiState 필드 추가는 하되 기존 snippet/timestamp/title 로드 로직은 **수정하지 않음**.
- **Sentry attachment parse_failure 이벤트 전송** — `AttachmentMetaParser` 는 `breadcrumb` 만 남기고 Sentry capture 는 안 함 (모든 failed parse 가 Sentry 로 가면 noise). 필요 시 별도 plan.

---

## 8. Dependencies

- **Blocked by**:
  - PR #1 (`feat/db/email` — `db-email-schema.md`) — `EmailBodyEntity` + DAO 필요. `attachments_meta: TEXT` 컬럼이 존재해야 VM 이 JSON 파싱 가능.
  - PR #2 (`feat/repo/email` — `repo-email-body.md`) — `EmailBodyRepository.findByRawEventId` suspending API 필요.
- **Blocks**:
  - 없음 (UI 말단 leaf).

merge 순서: **#1 → #2 → 본 plan**. 본 plan 은 다른 UI 브랜치 (`feat/ui/onboarding`, `feat/ui/sources`) 와 **완전 독립** — 겹치는 파일 0 개. 병렬 가능.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
1. `RawEventDetailSheet` 은 다시 `DetailRow` 3 호출 버전으로 회귀. 사용자는 이메일 이벤트에서 sourceType + timestamp + title 만 보게 됨 (본래 상태).
2. 신규 component 파일 (`EventSourceBadge.kt` 등) 은 revert 로 함께 사라지므로 dangling import 없음.
3. `EmailBodyRepository` 주입은 `RawEventDetailViewModel` 에서 사라지지만, Repository 자체는 PR #2 에 남아 있어 Hilt graph 무결.
4. DataStore / Room 데이터 무손상 — 본 plan 은 **읽기만** 수행. 마이그레이션 없음.

revert 체크리스트:
- [ ] `./gradlew :app:assembleDebug` 성공
- [ ] `RawEventDetailSheet` preview 렌더 성공
- [ ] 기존 voice/calendar 이벤트 drill-down 동작 무변화 (본 plan 이 해당 분기를 건드리지 않음)

schema 변경 없으므로 데이터 복구 전략 불필요.

---

## Appendix — Session handoff notes

구현 세션에게 전달할 추가 컨텍스트.

- **왜 `EmailBodyUi` 를 VM/UI 간 DTO 로 분리하는가**: Repository 가 반환하는 domain entity 에는 `raw_headers`, `parse_failed` 등 UI 노출 불필요 필드가 포함될 수 있음. DTO 변환으로 **invariant "EmailBody 는 Room only"** 을 UI 컴포넌트가 실수로 Railway DTO 로 직렬화할 가능성을 차단. (`repo-email-body.md` 의 guard test 와 이중 방벽.)
- **`parseAttachmentsMeta` 의 실패 허용 정책**: EMAIL-007 의 graceful degrade 철학 적용 — 첨부 메타 JSON 이 깨져도 이벤트 자체는 표시되어야 함. 파싱 실패 → count=0 → pill 미렌더 → 사용자는 "첨부 없음" 으로 인식 (false negative). 이는 "메일 이벤트 자체가 안 보이는 것" 보다 낫다.
- **이모지 vs 아이콘 선택**: `📎` 은 EMAIL-004 spec 원문에 이모지로 명시. Material Icon `Icons.Outlined.AttachFile` 로 대체 시 spec 위배. 이모지는 string resource 에 넣고 Composable 은 하드코딩 금지 — 이후 localization / 이모지 폰트 교체가 용이.
- **body_plain 500 chars 미리보기 컷오프**: spec 은 `event_snippet` = 200 chars, `body` 는 전문. UX 상 sheet 초기 진입에서 3000 chars 본문을 한꺼번에 렌더하면 scroll 경험이 나빠짐. 500 chars 는 "한 화면 내 대부분 가시" 휴리스틱. 스펙 명시값 아니므로 제품 팀과 최종 합의 필요 (본 plan 은 MVP 제안값).
- **`CommitmentsExtractedBadge` 는 `AssistChip` 유사하게 작게**: SRC-008 의 "약속 추출 N건 배지" 는 label + count 구조. M3 `AssistChip` 또는 `SuggestionChip` 이 적합. 탭 인터랙션 없음 (display-only).
- **post-MVP `[원문보기]` TODO**: 현재 plan 은 Toast/Snackbar 로 "다음 업데이트" 안내만 렌더. 버튼 자체는 enabled true. 이유는 (a) 사용자가 기능 존재를 인지하게, (b) 실제 구현 시 UI diff 최소화. 후속 plan 에서 `WebView` 또는 custom HTML parser 렌더링 추가.
- **Snapshot test 주의**: Roborazzi 는 이모지 렌더가 시스템 emoji font 의존 → CI 환경 (Linux) 과 로컬 (macOS) 간 미세 다름. `maxDifferenceRatio = 0.01` 정도 허용 + emoji 부분은 bounding box 비교만 수행하도록 설정.
- **전체 규모 추정**: ~2 파일 변경 (Sheet + VM), ~8 파일 신규 (6 component + 1 parser + 1 DTO), ~7 테스트 추가 (5 unit + 2 parser + 3 snapshot). 1.5 세션 분량.
- **함정 1**: `Dispatchers.IO` 로 `parseAttachmentsMeta` 수행. JSON 이 매우 긴 경우 main thread 에서 파싱하면 프레임 드롭. VM 의 `loadEvent()` 내부 `viewModelScope.launch` 는 이미 suspend context 이므로 `withContext(Dispatchers.Default)` 로 감쌀 것.
- **함정 2**: `attachments_meta` 가 null (없는 이메일) vs `"[]"` (빈 배열) 구분. Parser 는 둘 다 empty list 반환. UI 는 count>0 check 로 pill 미렌더 — 안전.
- **함정 3**: `body_plain` 이 아주 짧은 경우 (50 chars 미만) `snippet` 과 동일. 중복 표시하지 않도록 `if (body != snippet)` 체크 또는 항상 body 표시하고 snippet 은 email 분기에서 생략. 본 plan 은 **snippet 을 항상 먼저, body 는 아래 expandable** 로 유지 (spec SRC-004 가 event_snippet 과 body 전문을 **별개 항목** 으로 열거).
