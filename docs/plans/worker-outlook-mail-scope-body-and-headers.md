# Worker / Outlook-Mail / scope-body-and-headers — Inbox+SentItems 분리 delta + body/headers/attachments 수집

**Branch**: `feat/worker/outlook-mail`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Email ingestion (Outlook / Microsoft 365 provider)
**Severity**: Critical (global `/me/messages/delta` 로 모든 폴더 포함 = Drafts/Junk/Archive 도 indexing + SENT 힌트 없음 + EmailBody 없음)
**Type**: Drift (글로벌 delta, bodyPreview truncate, 단일 커서) + Gap (folder, attachments, headers, EmailBody insert)

---

## 1. Finding

ING-007 / EMAIL-001..007 은 Outlook provider worker 가 (a) `/me/mailFolders/inbox/messages/delta` 와 `/me/mailFolders/sentitems/messages/delta` **두 폴더 엔드포인트**를 각각 독립 deltaLink 커서로 순회하며, (b) 메시지 당 `body`, `hasAttachments`, `internetMessageHeaders`, `toRecipients`, `ccRecipients`, `bccRecipients`, `internetMessageId`, `conversationId`, `parentFolderId` 를 `$select` 로 요청하고 per-message `/me/messages/{id}/attachments?$select=name,contentType,size` 로 메타만 취득하며, (c) `raw_ingestion_events.folder` + folder-aware person_ref (INBOX→from, SENT→toRecipients[0], To>10→null+group_email) + JSON source_ref + EmailBody Room insert + Jsoup HTML 파싱 + parse_failed 를 수행할 것을 요구한다. 현재 `MsGraphClientImpl` 는 `/me/messages/delta` 글로벌 엔드포인트 하나만 사용 (`MsGraphClientImpl.kt:34-36`), `$select=id,internetMessageId,subject,from,bodyPreview,receivedDateTime` 여섯 필드만 회수, `GraphMessage` DTO 에 folder/toRecipients/body/attachments/headers 전부 없음 (`MsGraphClient.kt:34-49`). `OutlookMailWorker` 는 단일 cursor key `"outlook_mail_delta"` (`OutlookMailWorker.kt:226`) 만 사용, `toEntity` 는 항상 `personRef = fromEmail` (`:177`) + `eventSnippet = bodyPreview?.take(200)` (`:179`). 결과: Drafts / DeletedItems / JunkEmail / Archive 가 indexing 에 섞이고 SENT→give 방향이 구조적으로 반영되지 않으며 EMAIL-004/005/006/007 전부 작동 불가.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/data-ingestion.spec.yml:69-74` ING-007** — Outlook 스코프:
  > "인덱싱 범위는 'Inbox' + 'Sent Items' 두 메일 폴더만 대상. 'Drafts', 'Deleted Items', 'Junk Email', 'Archive'는 제외. Focused/Other 구분 없이 Inbox 전체 처리."
  > "Room raw_ingestion_events 1건(source_type='outlook_mail', person_ref=Inbox는 발신자/Sent Items는 수신자 이메일, event_title=이메일제목, event_snippet=본문앞200자, source_ref=Outlook messageId) + EmailBody 1건 INSERT됨."

- **`.spec/data-ingestion.spec.yml:125-128` ING-013** — MS Graph delta token 만료:
  > "커서 무효화(… MS Graph 410 deltaToken stale) 시 해당 소스 어댑터가 DataStore cursor를 초기화하고 제한된 전체 재동기화(최근 30일)를 수행한다"

- **`.spec/data-ingestion.spec.yml:159` 불변식**:
  > "이메일 인덱싱 범위는 INBOX(받은)과 SENT(보낸) 두 시스템 폴더/라벨로 제한된다 — Drafts·Trash·Spam·Archive·Promotions/Social/Updates/Forums 카테고리는 수집 금지 (ING-006..008)"

- **`.spec/email-pipeline.spec.yml:15-18` EMAIL-001**:
  > "direction 사전 힌트를 source_type에 붙이지 않고 raw event 레벨 메타(folder)로 전달"

- **`.spec/email-pipeline.spec.yml:22-27` EMAIL-002** — personRef:
  > "INBOX: From 헤더의 email_address를 lowercase 정규화. SENT: To 헤더 첫 번째 수신자(primary)를 lowercase 정규화 … 그룹 발송(To에 10명 이상)은 quarantine — person_ref=null로 Unassigned 섹션에 쌓음"

- **`.spec/email-pipeline.spec.yml:40-45` EMAIL-004** — attachments_meta:
  > "Microsoft Graph: attachments?$select=name,contentType,size"

- **`.spec/email-pipeline.spec.yml:49-54` EMAIL-005** — source_ref JSON:
  > "raw_ingestion_events.source_ref = JSON {message_id, in_reply_to?, references?}"

- **`.spec/email-pipeline.spec.yml:58-64` EMAIL-006** — EmailBody Room-only.

- **`.spec/email-pipeline.spec.yml:67-72` EMAIL-007** — HTML 파싱 실패 그레이스풀 디그레이드.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 글로벌 delta 엔드포인트 (폴더 분리 없음)

- **`android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClientImpl.kt:34-36`**:
  ```kotlin
  private const val INITIAL_MESSAGES_URL =
      "https://graph.microsoft.com/v1.0/me/messages/delta" +
          "?\$select=id,internetMessageId,subject,from,bodyPreview,receivedDateTime"
  ```
  → `/me/messages/delta` 는 사용자 메일박스 전체 (Inbox + Sent + Drafts + DeletedItems + JunkEmail + Archive + Focused/Other) 대상. Drafts/Junk/DeletedItems/Archive 제외 필터 없음.

- **`MsGraphClient.kt:34-49` GraphMessage DTO** — 다음 필드만:
  ```kotlin
  public data class GraphMessage(
      val id: String,
      val internetMessageId: String?,
      val subject: String?,
      val fromEmail: String?,
      val fromName: String?,
      val bodyPreview: String?,
      val receivedDateTime: Instant,
  )
  ```
  → `parentFolderId`, `toRecipients`, `ccRecipients`, `bccRecipients`, `body.content`, `body.contentType`, `hasAttachments`, `internetMessageHeaders`, `conversationId` 전부 없음.

### 3.2 단일 cursor key

- **`OutlookMailWorker.kt:226`**:
  ```kotlin
  public const val CURSOR_KEY: String = "outlook_mail_delta"
  ```
  → Inbox / SentItems 가 한 커서를 공유. 두 폴더를 독립 순회할 수 없음.

- **`OutlookMailWorker.kt:96-97`** — cursor read:
  ```kotlin
  var cursor = syncCursorStore.observeCursor(CURSOR_KEY).first()
  var totalFetched = 0
  ```

### 3.3 toEntity folder-독립

- **`OutlookMailWorker.kt:170-181`**:
  ```kotlin
  private fun GraphMessage.toEntity(userId: String, now: Instant): RawIngestionEventEntity =
      RawIngestionEventEntity(
          id = UUID.randomUUID().toString(),
          userId = userId,
          clientEventId = "outlook:$id",
          sourceType = SourceType.OUTLOOK_MAIL,
          sourceRef = id,                        // ← plain, JSON envelope 아님
          personRef = fromEmail,                 // ← 항상 from, folder 무관
          eventTitle = subject,
          eventSnippet = bodyPreview?.take(200), // ← body[:200] 아님, HTML strip 없음
          timestamp = receivedDateTime,
      )
  ```

### 3.4 EmailBody insert path 부재

- `rawIngestionRepository.insertLocalBatch(entities)` 한 번만 (`OutlookMailWorker.kt:122`). EmailBody 삽입 없음.

### 3.5 410 recovery 는 존재 (positive)

- **`OutlookMailWorker.kt:198-203`** + **`MsGraphClientImpl.kt:184-186`** — 410 → NotFound → `syncCursorStore.clearCursor(CURSOR_KEY)` → retry → full sync. 스펙 ING-013 의 "최근 30일 범위로 전체 재동기화" 바운드는 **미구현** (`/me/messages/delta` 는 시간 필터 미지원 → Inbox 전체 재수집).

### 3.6 MsGraphTokenProvider stub

- **`MsGraphClient.kt:102-108`**:
  ```kotlin
  public interface MsGraphTokenProvider {
      public suspend fun getAccessToken(): String?
  }
  ```
  → 실제 impl 없음. `feat/repo/auth` 에서 MSAL wiring 완성.

### 3.7 검증 grep

```bash
grep -rn "/me/messages/delta" android/app/src/main/java/
# → data/remote/msgraph/MsGraphClientImpl.kt:35

grep -rn "outlook_mail_delta\|outlook_mail_inbox_delta\|outlook_mail_sent_delta" android/app/src/main/java/
# → worker/ingestion/OutlookMailWorker.kt:226  (single key only)

grep -rn "mailFolders/inbox\|mailFolders/sentitems" android/app/src/main/java/
# → 0

grep -rn "toRecipients\|internetMessageHeaders" android/app/src/main/java/
# → 0
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| delta 엔드포인트 | `/me/mailFolders/inbox/messages/delta` + `/me/mailFolders/sentitems/messages/delta` | 글로벌 `/me/messages/delta` | 두 엔드포인트 분리 + 폴더 파라미터화 |
| cursor 개수 | Inbox / SentItems 독립 2 개 | 단일 `outlook_mail_delta` | Key 두 개 (`outlook_mail_inbox_delta`, `outlook_mail_sent_delta`) + migration |
| 폴더 제외 | Drafts / DeletedItems / JunkEmail / Archive 제외 | 글로벌 delta 가 모든 폴더 포함 | 엔드포인트 분리로 자연 배제 |
| `$select` | parentFolderId, toRecipients, ccRecipients, bccRecipients, body, hasAttachments, internetMessageHeaders, internetMessageId, conversationId | id, internetMessageId, subject, from, bodyPreview, receivedDateTime | `$select` 확장 |
| 첨부 메타 | `/me/messages/{id}/attachments?$select=name,contentType,size` | 미요청 | per-message 추가 호출 |
| folder 컬럼 | raw event + email_body | 없음 | PR #1 의 컬럼 사용 |
| person_ref | folder 분기 + To>10 quarantine | 항상 from | folder-aware |
| event_snippet | body_plain[:200] or Jsoup(html) or subject | bodyPreview?.take(200) | EmailSnippetBuilder 경유 (PR #10) |
| source_ref | JSON `{message_id, in_reply_to?, references?}` | plain id | Moshi envelope + internetMessageHeaders 파싱 |
| parse_failed | HTML 실패 시 body_html 원본 + flag + Sentry | 없음 | try/catch |
| EmailBody insert | 같은 트랜잭션 | 없음 | `emailBodyRepository.insert(...)` |
| 30일 재동기화 | ING-013 bound | global delta full resync (무제한) | `$filter=receivedDateTime ge <now-30d>` 쿼리 추가 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClient.kt`
  - `GraphMessage` 확장:
    - `folder: String` — `"INBOX"` 또는 `"SENT"` (client 가 엔드포인트 scope 를 주입; `parentFolderId` 도 추가로 보관하되 primary source 는 scope).
    - `toRecipients: List<String>` — recipient 의 `emailAddress.address` 원본 리스트 (lowercase 정규화는 worker 에서).
    - `ccRecipients: List<String>` — person_ref 로는 쓰지 않지만 EmailBody.raw_headers 에 포함 가능하도록 유지.
    - `bccRecipients: List<String>` — 동일.
    - `bodyHtml: String?` — `body.contentType == "html"` 일 때 `body.content`.
    - `bodyPlain: String?` — `body.contentType == "text"` 일 때 `body.content`, 또는 `bodyHtml` Jsoup 파싱 결과 (worker 에서).
    - `attachmentsMeta: List<GraphAttachmentMeta>` — 별도 fetch 로 채움 (per-message).
    - `inReplyTo: String?`, `references: String?` — `internetMessageHeaders` 에서 추출.
    - `rawHeadersJson: String` — `internetMessageHeaders` 전체 JSON.
    - `hasAttachments: Boolean` — 경량 flag (true 일 때만 attachments 엔드포인트 호출).
    - `conversationId: String?` — EmailBody 참고용 (미사용이나 후속 thread view PR 대비).
  - `MsGraphClient` 인터페이스:
    - `messagesDelta(deltaOrNextLink: String?)` 시그니처를 **폐기하지 않고 유지** (backward-compat). 새로 `messagesDeltaForFolder(folder: OutlookMailFolder, deltaOrNextLink: String?): BecalmResult<GraphDeltaResponse<GraphMessage>>` 추가. `OutlookMailFolder` enum `{ INBOX, SENT }`.
    - 새 API `messageAttachments(messageId: String): BecalmResult<List<GraphAttachmentMeta>>` — `/me/messages/{id}/attachments?$select=name,contentType,size`.
    - 기존 `messagesDelta` 는 `messagesDeltaForFolder(INBOX, cursor)` 로 위임하거나 `@Deprecated` 표시. 호출 사이트 (OutlookMailWorker) 는 새 API 로 완전 마이그레이션.

- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClientImpl.kt`
  - 기존 `INITIAL_MESSAGES_URL` 상수 제거. 대신 factory 함수 `initialMessagesUrl(folder: OutlookMailFolder): String`:
    - INBOX: `"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta"`
    - SENT: `"https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta"`
    - 공통 `?$select=id,internetMessageId,conversationId,parentFolderId,subject,from,toRecipients,ccRecipients,bccRecipients,body,hasAttachments,internetMessageHeaders,receivedDateTime`
    - 30-day bound (ING-013): delta 가 token 만료 후 full resync 시 `$filter=receivedDateTime ge ${now-30d}Z` 추가. **단 delta 초기 호출은 Graph 가 `$filter` + `$select` 조합을 제한적으로 지원** — 구현 세션이 Graph docs 확인 후 결정. 만약 `/messages/delta` 가 `$filter` 를 미지원하면 client-side 로 30일 cutoff 적용 (받은 페이지에서 receivedDateTime < cutoff 인 메시지 skip + 로그).
  - `messagesDeltaForFolder` 구현:
    - cursor != null → cursor URL 그대로 fetch (opaque URL).
    - cursor == null → `initialMessagesUrl(folder)` + 30d filter.
    - 응답 파싱 → `parseMessageMap` 확장.
  - `parseMessageMap` 수정:
    - `toRecipients`, `ccRecipients`, `bccRecipients` 각각 `List<Map<String, Any?>>` 에서 `emailAddress.address` 추출.
    - `body` 는 `{contentType: "html"|"text", content: "..."}` → `bodyHtml` / `bodyPlain` 분기.
    - `internetMessageHeaders` 는 `List<Map<String, Any?>>` → `{name, value}` 각 헤더.
    - `In-Reply-To`, `References` 추출 (헤더 이름은 case-insensitive).
    - `rawHeadersJson` — `moshi.adapter(Any::class.java).toJson(headers)` 로 원본 보존.
    - `folder` 는 `parseMessageMap` 시그니처에 인자 추가 (scope 주입).
  - `messageAttachments(messageId)`:
    - `hasAttachments = false` 인 메시지는 호출하지 않음 (최적화).
    - 응답 shape: `{ value: [{name, contentType, size}, ...] }` → `List<GraphAttachmentMeta>`.

- `android/app/src/main/java/com/becalm/android/data/local/datastore/SyncCursorStore.kt`
  - 새 key 2 개 추가: `outlook_mail_inbox_delta`, `outlook_mail_sent_delta`.
  - **Migration** — `observeCursor("outlook_mail_inbox_delta")` 의 첫 read 시:
    1. `outlook_mail_inbox_delta` 가 null 이고 `outlook_mail_delta` 가 non-null 이면 → inbox 키로 값 복사, `outlook_mail_delta` 삭제. Sent 키는 null 유지 (SENT 는 cold sync 필요).
    2. migration 실행 여부 flag 를 `outlook_mail_migration_v2_done` 에 기록하여 한 번만 실행.
    3. 또는 더 간단한 접근: app startup hook (예: `SyncCursorStoreImpl.init()` 또는 DI 초기화 시점 1 회)에서 migration 수행. 구현 세션 판단.
  - 기존 `observeCursor/setCursor(CURSOR_KEY)` API 는 유지 — 다른 provider (없는 경우 outlook 전용이면 제거 가능) 판단 후 scope.
  - 새 heat: `observeOutlookMailInboxDelta(): Flow<String?>`, `setOutlookMailInboxDelta(String?)` + sent 대응 (type-safe wrapper, 기존 Gmail/IMAP 패턴과 align).

- `android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt`
  - `doWork` 전체 재구성:
    1. userId + session 검증 (기존 유지).
    2. Inbox 패스: `runDeltaLoop(folder = INBOX, cursorKey = INBOX_CURSOR_KEY, userId)`.
    3. Sent 패스: `runDeltaLoop(folder = SENT, cursorKey = SENT_CURSOR_KEY, userId)`.
    4. 두 패스 중 실패가 있으면 해당 지점에서 반환 (Result 매핑은 기존 `mapErrorToResult` 재사용, cursorKey 인자 추가).
  - `runDeltaLoop(folder, cursorKey, userId): SyncOutcome` — 기존 pagination while-true 루프를 folder 인자로 재활용. 기존 `msGraphClient.messagesDelta(cursor)` 를 `msGraphClient.messagesDeltaForFolder(folder, cursor)` 로 전환. 각 페이지 insert 후 `syncCursorStore.setCursor(cursorKey, page.nextLink)` / `deltaLink`.
  - `toEntity` → folder 인자 추가. folder 별 personRef 결정 로직:
    - `INBOX` → `canonicalizeEmail(fromEmail)`.
    - `SENT` && `toRecipients.size <= 10` → `canonicalizeEmail(toRecipients[0])`.
    - `SENT` && `toRecipients.size > 10` → `personRef = null`, `groupEmail = true` (EmailBody 에만).
    - `SENT` && `toRecipients.isEmpty()` → personRef = null, groupEmail = false (비정상 메일은 그대로 저장, LLM extract 는 skip).
  - `sourceRef` = Moshi serialize `SourceRefEnvelope(messageId = internetMessageId ?: id, inReplyTo, references)`. `internetMessageId` 가 null 이면 Graph id 로 fallback (Gmail 과 달리 Outlook 은 Graph-id + RFC822 Message-Id 가 별도).
  - EmailBody insert: folder / subject / fromEmail / toRecipients JSON / bodyPlain / bodyHtml / attachmentsMeta JSON / rawHeaders / parseFailed / groupEmail / receivedAt. `rawEventId` 는 `insertLocalBatch` 반환값 (PR #2 의 transactional API 대응 필요).
  - 첨부 fetch: `message.hasAttachments == true` 인 경우에만 `msGraphClient.messageAttachments(message.id)` 호출. 응답을 `attachmentsMeta` 에 채움. 실패 시 빈 리스트 + warning log (body insert 는 계속 진행).
  - HTML 파싱 실패 분기 (EMAIL-007):
    - `bodyPlain` 없고 `bodyHtml` 있으면 `EmailHtmlParser.parseOrFail(bodyHtml)` (PR #10).
    - catch → parseFailed=true, bodyHtml 원본 보존, bodyPlain=null, Sentry breadcrumb `{error:"email_html_parse_failed", message_id, provider:"outlook"}`.
  - `mapErrorToResult` — 410 분기에서 `syncCursorStore.clearCursor(cursorKey)` (인자로 전달). 기존에 하드코딩된 `CURSOR_KEY` 참조 제거.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/OutlookMailFolder.kt` — `enum class OutlookMailFolder(val endpointPath: String) { INBOX("inbox"), SENT("sentitems") }`.
- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/GraphAttachmentMeta.kt` — `data class GraphAttachmentMeta(val name: String, val contentType: String, val sizeBytes: Long)` + Moshi `@JsonClass`.
- `android/app/src/main/java/com/becalm/android/data/remote/msgraph/SourceRefEnvelope.kt` — 또는 `feat/worker/gmail` 이 만든 공용 envelope 을 `data/remote/email/` 위치로 move 하여 두 provider 공유 (권장). 구현 세션이 둘 중 먼저 merge 되는 쪽에서 위치 결정.

### 5.3 Files to delete (dead code)

- `MsGraphClientImpl.kt:34-36` 의 기존 `INITIAL_MESSAGES_URL` const 는 새 factory 함수로 대체되므로 **삭제**.
- `OutlookMailWorker.CURSOR_KEY` const 는 새 INBOX/SENT 전용 키 두 개로 대체되므로 **삭제**. 단 DataStore migration 코드에서 임시로 참조 필요 → migration 완료 후 후속 cleanup PR 에서 제거.

### 5.4 Non-code changes

- SyncCursorStore `DataStore` migration: `outlook_mail_delta` → `outlook_mail_inbox_delta` 복사. 기존 값 보존으로 Inbox 는 cold-sync 회피 가능. SENT 는 첫 실행 시 null cursor 로 cold-sync (30 일 bound 적용).
- Gradle: 본 PR 단독으로는 신규 의존성 없음. Jsoup 은 PR #10 에서 추가.
- MSAL scope: `feat/repo/auth` 에서 `Mail.Read` scope 요청 (이미 spec 대로). 첨부 엔드포인트도 같은 scope.

### 5.5 Tests

- `android/app/src/test/java/com/becalm/android/data/remote/msgraph/MsGraphClientImplTest.kt`:
  - `messagesDeltaForFolder_inbox_usesInboxEndpoint` — MockWebServer `/me/mailFolders/inbox/messages/delta` path 매치 확인.
  - `messagesDeltaForFolder_sent_usesSentItemsEndpoint` — `/me/mailFolders/sentitems/messages/delta`.
  - `messagesDeltaForFolder_inbox_appliesThirtyDayFilter` — 첫 호출 URL 에 `$filter=receivedDateTime` 쿼리 포함 (Graph 가 delta+filter 를 허용할 때; 허용 안하면 client-side skip 검증).
  - `parseMessageMap_htmlBody_populatesBodyHtml` — fixture `{body: {contentType:"html", content:"<p>hi</p>"}}` → `bodyHtml = "<p>hi</p>"`, `bodyPlain = null`.
  - `parseMessageMap_toRecipients_extractsAddresses` — fixture `toRecipients: [{emailAddress:{address:"a@x.com"}}, {emailAddress:{address:"b@y.com"}}]` → `toRecipients == listOf("a@x.com", "b@y.com")`.
  - `parseMessageMap_internetMessageHeaders_extractsReplyHeaders` — fixture 에 `In-Reply-To`, `References` 포함 → DTO 필드 채워짐 + rawHeadersJson 에 원본 보존.
  - `messageAttachments_returnsMetaOnly` — fixture `{value: [{name:"a.pdf", contentType:"application/pdf", size:1024}]}` → 단일 리스트.
- `android/app/src/test/java/com/becalm/android/worker/ingestion/OutlookMailWorkerTest.kt`:
  - `doWork_inboxThenSent_twoCursorsAdvanced` — fake MsGraphClient 가 두 폴더 각각 1 페이지 반환. 최종 DataStore 에 `outlook_mail_inbox_delta` + `outlook_mail_sent_delta` 두 값 set.
  - `toEntity_sent_personRefFromTo0` — folder=SENT, toRecipients=`["first@ex.com", "second@ex.com"]` → personRef=`"first@ex.com"`.
  - `toEntity_sent_over10_groupEmailTrue` — toRecipients.size=11 → personRef=null, EmailBody.groupEmail=true.
  - `doWork_410_clearsCorrectCursorOnly` — Sent 패스에서 410 → Sent cursor 만 clear (Inbox 는 보존).
  - `doWork_htmlParseFailure_gracefulDegrade` — EmailHtmlParser stub 이 throw → parseFailed=true, eventSnippet=subject.take(200).
  - `sourceRef_jsonEnvelope_internetMessageIdFallback` — `internetMessageId = null` → `messageId = graphId` 사용, nested `in_reply_to`/`references` 가 null 이면 키 생략.
  - `migration_outlookMailDelta_copiedToInboxOnly` — pre-migration DataStore 에 `outlook_mail_delta = "https://..."` set → migration 후 `outlook_mail_inbox_delta = "https://..."`, `outlook_mail_sent_delta = null`, `outlook_mail_delta` 삭제.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "/me/messages/delta" android/app/src/main/java/` 가 0 (엔드포인트는 `/mailFolders/inbox/messages/delta` + `/mailFolders/sentitems/messages/delta` 만 존재)
- [ ] **Grep invariant**: `grep -rn "mailFolders/inbox/messages/delta\|mailFolders/sentitems/messages/delta" android/app/src/main/java/` 가 `>= 2`
- [ ] **Grep invariant**: `grep -rn "outlook_mail_inbox_delta\|outlook_mail_sent_delta" android/app/src/main/java/ | wc -l` 가 `>= 2`
- [ ] **Grep invariant**: `grep -rn "CURSOR_KEY = \"outlook_mail_delta\"" android/app/src/main/java/` 가 0 (단일 key 상수 제거)
- [ ] **Grep invariant**: `grep -rn "OutlookMailFolder" android/app/src/main/java/ | wc -l` 가 `>= 2`
- [ ] **Grep invariant**: `grep -rn "emailBodyRepository\.insert\|EmailBodyRepository\.insert" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt | wc -l` 가 `>= 1`
- [ ] **Grep invariant**: `grep -rn "toRecipients\|internetMessageHeaders" android/app/src/main/java/com/becalm/android/data/remote/msgraph/ | wc -l` 가 `>= 2`
- [ ] **Unit test**: `MsGraphClientImplTest.messagesDeltaForFolder_inbox_usesInboxEndpoint` 통과
- [ ] **Unit test**: `MsGraphClientImplTest.messagesDeltaForFolder_sent_usesSentItemsEndpoint` 통과
- [ ] **Unit test**: `MsGraphClientImplTest.parseMessageMap_htmlBody_populatesBodyHtml` 통과
- [ ] **Unit test**: `MsGraphClientImplTest.parseMessageMap_toRecipients_extractsAddresses` 통과
- [ ] **Unit test**: `MsGraphClientImplTest.parseMessageMap_internetMessageHeaders_extractsReplyHeaders` 통과
- [ ] **Unit test**: `MsGraphClientImplTest.messageAttachments_returnsMetaOnly` 통과
- [ ] **Unit test**: `OutlookMailWorkerTest.doWork_inboxThenSent_twoCursorsAdvanced` 통과
- [ ] **Unit test**: `OutlookMailWorkerTest.toEntity_sent_over10_groupEmailTrue` 통과
- [ ] **Unit test**: `OutlookMailWorkerTest.doWork_410_clearsCorrectCursorOnly` 통과
- [ ] **Unit test**: `OutlookMailWorkerTest.doWork_htmlParseFailure_gracefulDegrade` 통과
- [ ] **Unit test**: `OutlookMailWorkerTest.migration_outlookMailDelta_copiedToInboxOnly` 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual**: MSAL OAuth 연결 후 `feat/repo/auth` merge 된 상태에서 OutlookMailWorker 1 회 실행 → logcat 에 `folder=INBOX` 와 `folder=SENT` 로그 각 1 회 이상 + `email_body.folder` DISTINCT 값이 `{INBOX, SENT}` 포함

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:

- `EmailBodyEntity`, `EmailBodyDao`, `MIGRATION_3_4`, DB version bump — **PR #1** 담당.
- `EmailBodyRepository` — **PR #2** 담당.
- `EmailSnippetBuilder` + `EmailHtmlParser` + Jsoup 의존성 — **PR #10** 담당. 본 PR 은 import + 호출만.
- `CommitmentExtractionWorker` + `EmailPromptBuilder` — **PR #11** 담당. 본 PR 은 enqueue site 만.
- Gmail / IMAP provider — 병렬 PR 담당.
- `MsGraphTokenProviderImpl` + MSAL SDK 통합 — **`feat/repo/auth`** 담당. 본 PR 테스트는 fake token provider 사용.
- Outlook Calendar worker (`OutlookCalendarWorker`) — 본 PR 의 `MsGraphClient` 변경이 calendarViewDelta 에 영향 주지 않도록 주의. `$select` 확장은 messagesDelta 에만 적용.
- `RawIngestionEventEntity.folder` 컬럼 — PR #1 담당.
- `conversationId` 기반 thread grouping UI — post-MVP.
- Retention sweep — 별도 PR.

---

## 8. Dependencies

- **Blocked by**:
  - PR #1 `feat/db/email-schema` (EmailBodyEntity + folder 컬럼 + version 4)
  - PR #2 `feat/repo/email-body` (Repository + transactional insert API)
  - PR #10 `feat/domain/email/snippet-builder` (EmailSnippetBuilder + EmailHtmlParser + Jsoup)
  - PR #11 `feat/worker/extraction/email` (CommitmentExtractionWorker — enqueue site 만 본 PR)
  - `feat/repo/auth` (`MsGraphTokenProviderImpl` — 없으면 런타임은 Unauthorized)
- **Blocks**: 없음
- 병렬 가능: `feat/worker/gmail`, `feat/worker/imap` 와 파일 겹침 없음 (`SourceRefEnvelope` 위치만 조율 필요).

---

## 9. Rollback plan

1. `git revert <merge-sha>` → OutlookMailWorker 는 단일 cursor + 글로벌 delta 경로로 복귀.
2. DataStore 에 이미 저장된 `outlook_mail_inbox_delta` / `outlook_mail_sent_delta` 값은 revert 후 **사용되지 않지만 무해** (읽지 않음). 선택적으로 migration reversal 코드로 inbox key 값을 `outlook_mail_delta` 로 다시 복사. 단 SENT cursor 는 손실 (SENT 는 revert 후 재indexing 불가 — 다음 cold sync 까지 방치).
3. `email_body` row 는 forward-only (PR #1 rollback 필요).
4. 410 full resync 는 revert 후 글로벌 delta 로 돌아가므로 Drafts/Junk 다시 포함. P1 환자 report 시 긴급.

주의: revert 순서는 `feat/worker/outlook-mail` → PR #11 → PR #10 → PR #2 → PR #1 역순. **데이터 손실**: SENT 커서 + 이미 insert 된 email_body (로컬 only, Railway 미업로드 → 실제 사용자 영향 낮음).

---

## Appendix — Session handoff notes

- **`$filter` + delta 호환성**: MS Graph `/me/messages/delta` 와 `/me/mailFolders/{id}/messages/delta` 는 `$filter` 를 **초기 호출에만** 허용하고 후속 `@odata.nextLink` 에서는 쿼리 파라미터를 수정하면 token 무효화. 따라서 `$filter=receivedDateTime ge <30d ago>` 는 cursor == null 인 첫 호출에만 적용하고, 이후 nextLink 는 opaque URL 그대로. 구현 세션이 [Microsoft Graph delta query docs](https://learn.microsoft.com/en-us/graph/delta-query-messages) 확인 후 정확한 제약 반영.
- **`/mailFolders/inbox` vs `/mailFolders('Inbox')`**: Well-known folder 이름은 URL path segment 로 허용 (`inbox`, `sentitems`, `drafts`, `deleteditems`, `junkemail`, `archive`). 대소문자 무관하나 관례상 lowercase. `mailFolders` 뒤에 `('inbox')` quoted literal 도 허용되나 path segment 가 더 깔끔.
- **30일 bound 실현 방법**: `$filter=receivedDateTime ge 2026-03-21T00:00:00Z` 와 같은 ISO-8601 UTC. Client 에서 `(Clock.System.now() - 30.days).toString()` 로 생성 (기존 `MsGraphClientImpl` 의 calendar delta 코드 `calendarViewDelta` 가 같은 패턴 사용 — `.kt:94-97` 참고).
- **`body.contentType` 값**: `"html"` | `"text"`. `"text"` 일 때도 내용은 plain-text 로 안심 저장. `"html"` 일 때만 Jsoup parse 경로 진입.
- **`internetMessageHeaders` 는 Outlook licensing 에 따라 빈 배열**: 일부 테넌트에서 `$select=internetMessageHeaders` 를 포함하지 않으면 반환되지 않음. 포함 시에도 Exchange admin 이 header stripping 을 켜놓으면 `In-Reply-To` 부재 가능. `rawHeadersJson` 은 null-safe 처리 (`[]` 기본값).
- **`hasAttachments` 최적화**: per-message attachments 엔드포인트 호출은 추가 quota 소비 → `hasAttachments == true` 일 때만 호출. Inbox 평균 30% 에 첨부가 있으므로 페이지당 요청 수 70% 절감.
- **MSAL token refresh**: `MsGraphTokenProvider.getAccessToken()` 은 suspend — internally MSAL silent refresh. 본 PR 은 이 계약을 신뢰. 실패 시 null 반환 → Unauthorized.
- **Focused/Other**: spec ING-007 "Focused/Other 구분 없이 Inbox 전체 처리" — `inferenceClassification` 필드 미사용. `$select` 에 포함하지 않음.
- **`conversationId` 활용**: MVP 에서는 저장만. Post-MVP thread view 에서 `GROUP BY conversationId` 로 집계.
- **migration 이중실행 방지**: `outlook_mail_migration_v2_done: Boolean` flag 를 DataStore 에 저장. `SyncCursorStoreImpl` 생성자에서 1 회 체크 후 migration → flag set. Hilt singleton scope 이므로 프로세스 당 1 회 확실.
- **Graph 쿼리 파라미터 `$` 이스케이프**: Kotlin string `"$select"` 는 interpolation 충돌 → `"\$select"` 기존 패턴 유지 (`MsGraphClientImpl.kt:36` 참고).
- **테스트 fixture**: `android/app/src/test/resources/msgraph/` 에 Inbox / SentItems JSON sample 배치. `hasAttachments=true` + `/attachments` 후속 호출 fixture 쌍으로 준비.
- **OutlookMailWorker `hasExceededMaxRetries`** (`:76`) — max 5 retry. 두 폴더 각각 retry 카운트 공유. 한 폴더가 반복 실패 시 다른 폴더도 skip 될 수 있으나 이는 WorkManager 의 per-unique-work 재시도 모델 한계 — scope 외.
