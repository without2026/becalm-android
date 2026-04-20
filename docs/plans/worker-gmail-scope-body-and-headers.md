# Worker / Gmail / scope-body-and-headers — INBOX+SENT 스코프 정상화 + body/headers/attachments 수집 파이프라인

**Branch**: `feat/worker/gmail`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Email ingestion (Gmail provider)
**Severity**: Critical (INBOX-only 스코프로 SENT→give direction이 구조적으로 도달 불가 + EmailBody 미기록으로 EMAIL-003..007 전체 비동작)
**Type**: Drift (INBOX-only, format=metadata, plain From-only personRef) + Gap (EmailBody insert 경로, attachments/headers/body parsing 미존재)

---

## 1. Finding

ING-006 / EMAIL-001..007 은 Gmail provider worker 가 (a) INBOX + SENT 두 시스템 라벨만 스코프로 잡고 Promotions/Social/Updates/Forums/Draft/Trash/Spam 를 제외하며, (b) 메시지 당 `body_plain`/`body_html`/`attachments_meta`/`raw_headers` (Message-Id/In-Reply-To/References) 를 추출해 Room `email_body` 테이블에 저장하고, (c) `raw_ingestion_events.folder` 와 folder-aware `person_ref` (INBOX→From, SENT→To[0], To>10→null + group_email=true), JSON source_ref `{message_id, in_reply_to?, references?}` 를 기록할 것을 요구한다. 현재 `GmailClient.listMessagesFullSync` 는 `labelIds=INBOX` 하드코딩 (`GmailClient.kt:271`), `GmailClient.getMessage` 는 `format=metadata&metadataHeaders=From&metadataHeaders=Subject` 만 요청 (`GmailClient.kt:284-288`), `GmailWorker.toEntity` 는 Gmail 서버 제공 `snippet` 과 `From` 만 사용 (`GmailWorker.kt:298-310`), `canonicalizeEmail` 은 From 전용 (`GmailWorker.kt:327-337`). 결과적으로 SENT 라벨은 영구 미indexing → EMAIL-001 direction 힌트 (inbox→take, sent→give) 는 절반만 작동하고, EMAIL-003 event_snippet 결정론 / EMAIL-004 attachments_meta / EMAIL-005 source_ref JSON / EMAIL-006 EmailBody 로컬-전용 본문 저장 / EMAIL-007 parse_failed 플래그는 전부 구조적으로 도달 불가.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/data-ingestion.spec.yml:62-65` ING-006** — Gmail 스코프 + personRef 방향:
  > "인덱싱 범위는 사용자 주소록 기반 대화 메일로 한정: 시스템 라벨 'INBOX'(받은 편지함) + 'SENT'(보낸 편지함) 두 라벨만 대상. 'DRAFT', 'TRASH', 'SPAM', 'CATEGORY_PROMOTIONS', 'CATEGORY_SOCIAL', 'CATEGORY_UPDATES', 'CATEGORY_FORUMS'는 제외. person_ref 정규화 방향은 direction에 따라 다름: INBOX 메일은 발신자(From) 이메일, SENT 메일은 수신자(To 첫 번째) 이메일."

- **`.spec/data-ingestion.spec.yml:65` ING-006** — expected row 모양:
  > "Room raw_ingestion_events 1건(source_type='gmail', person_ref=INBOX는 발신자/SENT는 수신자 이메일, event_title=이메일제목, event_snippet=본문앞200자, source_ref=Gmail messageId) + EmailBody 1건 INSERT됨. Promotions/Social/Updates/Forums 카테고리 및 Draft/Trash/Spam 메일은 INSERT되지 않음"

- **`.spec/data-ingestion.spec.yml:159` 불변식** — INBOX+SENT 한정:
  > "이메일 인덱싱 범위는 INBOX(받은)과 SENT(보낸) 두 시스템 폴더/라벨로 제한된다 — Drafts·Trash·Spam·Archive·Promotions/Social/Updates/Forums 카테고리는 수집 금지 (ING-006..008)"

- **`.spec/email-pipeline.spec.yml:15-18` EMAIL-001** — folder 힌트 전달:
  > "direction 사전 힌트를 source_type에 붙이지 않고 raw event 레벨 메타(folder)로 전달한다. … ING-006..008 어댑터가 EmailBody + raw event INSERT 직후 CommitmentExtractionWorker 호출"

- **`.spec/email-pipeline.spec.yml:22-27` EMAIL-002** — personRef 결정 규칙:
  > "person_ref 추출은 folder에 따라 다르게 지정한다. INBOX: From 헤더의 email_address를 lowercase 정규화. SENT: To 헤더 첫 번째 수신자(primary)를 lowercase 정규화 — 여러 수신자는 MVP에서 하나로 축약(primary만 person_ref). CC/BCC는 person_ref로 사용하지 않음. 그룹 발송(To에 10명 이상)은 quarantine — person_ref=null로 Unassigned 섹션에 쌓음"
  > "To가 10명 초과면 person_ref=null(group_email=true 플래그는 EmailBody에만 저장)"

- **`.spec/email-pipeline.spec.yml:40-45` EMAIL-004** — attachments_meta 메타만:
  > "Gmail API: message.parts[].filename + mimeType + body.size만 수집. … 모든 경우 EmailBody.attachments_meta JSON에 저장되며 body_plain/body_html 파싱에는 영향 없음. 네트워크 대역·저장 공간 절약 + PIPA 리스크 최소화"

- **`.spec/email-pipeline.spec.yml:49-54` EMAIL-005** — source_ref JSON envelope:
  > "raw_ingestion_events.source_ref = JSON {message_id, in_reply_to?, references?}"

- **`.spec/email-pipeline.spec.yml:58-64` EMAIL-006** — EmailBody Room-only:
  > "EmailBody는 Railway/Supabase에 절대 업로드되지 않는다 … Railway는 raw_ingestion_events.event_snippet(앞 200자)만 받음"

- **`.spec/email-pipeline.spec.yml:67-72` EMAIL-007** — parse_failed 그레이스풀 디그레이드:
  > "HTML 파싱 실패(잘못된 HTML / charset mismatch / Jsoup timeout)는 raw event를 quarantine으로 넘기지 않고 event_snippet=subject로 graceful degrade한다. Sentry에만 parse_failure 이벤트 기록 … EmailBody.body_plain=null + body_html=원본 보존 + parse_failed=true 플래그. Sentry에 {error:'email_html_parse_failed', message_id, provider} 이벤트"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 INBOX-only 스코프 (SENT 미indexing)

- **`android/app/src/main/java/com/becalm/android/data/remote/gmail/GmailClient.kt:269-282`**:
  ```kotlin
  override suspend fun listMessagesFullSync(pageToken: String?): BecalmResult<GmailMessagePage> {
      val url = buildString {
          append("$GMAIL_BASE_URL/messages?labelIds=INBOX")  // ← INBOX 하드코딩
          if (pageToken != null) append("&pageToken=$pageToken")
      }
      ...
  }
  ```
- **`GmailClient.kt:249-267`** — `listHistory` 는 labelIds 필터 없음 → 반환되는 history record 에 Promotions/Social/Updates/Forums/Spam/Trash 도 포함 가능. 워커가 필터링하지 않으므로 모든 라벨 메시지가 insert 될 잠재성.
- **`GmailWorker.kt:194-216` runFullSync** — `gmailClient.listMessagesFullSync(pageToken)` 만 호출. SENT 분기 없음.

### 3.2 getMessage 는 format=metadata + From/Subject 만

- **`GmailClient.kt:284-301`**:
  ```kotlin
  override suspend fun getMessage(messageId: String): BecalmResult<GmailMessage> {
      val url = "$GMAIL_BASE_URL/messages/$messageId" +
          "?format=metadata" +
          "&metadataHeaders=From" +
          "&metadataHeaders=Subject"
      ...
  }
  ```
  → Body/parts/attachments/Message-Id/In-Reply-To/References/To 헤더를 요청하지 않음.

- **`GmailClient.kt:158-164` GmailMessage DTO** — `messageId, subject, from, snippet, internalDate` 다섯 필드만. folder/to/bodyPlain/bodyHtml/attachmentsMeta/inReplyTo/references 전부 없음.

### 3.3 toEntity 는 From 전용 + snippet 사용

- **`GmailWorker.kt:298-310`**:
  ```kotlin
  private fun GmailMessage.toEntity(userId: String): RawIngestionEventEntity {
      return RawIngestionEventEntity(
          ...
          sourceType = SourceType.GMAIL,
          sourceRef = messageId,           // ← plain string, JSON envelope 아님
          eventTitle = subject,
          eventSnippet = snippet,          // ← Gmail-server snippet, body[:200] 아님
          personRef = from?.let { canonicalizeEmail(it) },  // ← folder 무관, 항상 From
          timestamp = Instant.fromEpochMilliseconds(internalDate),
      )
  }
  ```
- **`GmailWorker.kt:327-337` canonicalizeEmail** — From 전용 파싱. To[0] 경로 없음.

### 3.4 EmailBody insert path 부재

- `insertMessages` (`GmailWorker.kt:250-256`) → `fetchAndInsert` (`263-277`) → `rawIngestionRepository.insertLocal(entity)` 한 번만 호출. `emailBodyRepository.insert(...)` 호출 없음 (그 repository 자체가 존재하지 않음 — F-07 참조).

### 3.5 Auth short-circuit

- **`GmailClient.kt:27-30, 336-337`**:
  ```kotlin
  public interface GoogleAuthTokenProvider {
      public fun currentToken(): String?
  }
  ...
  val token = authTokenProvider.currentToken()
      ?: return BecalmResult.Failure(BecalmError.Unauthorized)
  ```
  → 현재 `GoogleAuthTokenProviderImpl` 가 존재하지 않아 매 워커 실행마다 Unauthorized 반환. 이 PR 의 모든 변경은 `feat/repo/auth` 에서 실제 impl 이 bind 되어야 런타임 검증이 가능.

### 3.6 검증 grep

```bash
# INBOX 하드코딩 위치
grep -rn "labelIds=INBOX" android/app/src/main/java/
# → data/remote/gmail/GmailClient.kt:271

# format=metadata 위치
grep -rn "format=metadata" android/app/src/main/java/
# → data/remote/gmail/GmailClient.kt:286

# EmailBody 참조 (0 이 나와야 현재 상태)
grep -rn "EmailBody\|email_body\|body_plain\|body_html" android/app/src/main/java/ | wc -l
# → 0

# attachments_meta 참조
grep -rn "attachments_meta\|attachmentsMeta" android/app/src/main/java/ | wc -l
# → 0
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 인덱싱 스코프 | INBOX + SENT 시스템 라벨 | INBOX 단일 라벨 (`labelIds=INBOX`) | SENT 라벨 두 번째 패스 추가 |
| 카테고리 배제 | Promotions/Social/Updates/Forums + Spam/Trash/Drafts 제외 | 필터 없음 (history.list + getMessage 모두 label 체크 없음) | `q=` 쿼리 파라미터 기반 제외 또는 `messages.get` 에서 labelIds 회수 후 필터 |
| 메시지 포맷 | body_plain + body_html + attachments (+headers) | `format=metadata` + From/Subject 헤더만 | `format=full` 로 전환, `payload.parts[]` MIME 트리 walk |
| folder 컬럼 | raw event 레벨에 INBOX/SENT 기록 | 없음 | RawIngestionEventEntity.folder + EmailBodyEntity.folder |
| person_ref | INBOX=From, SENT=To[0], To>10→null + group_email=true | 항상 From | folder 분기 + 10명 threshold |
| event_snippet | body_plain[:200] (공백 정리, HTML Jsoup strip, subject fallback) | Gmail `snippet` 서버 값 | `EmailSnippetBuilder` 경유 (PR #10) |
| source_ref | JSON `{message_id, in_reply_to?, references?}` | plain messageId 문자열 | Moshi adapter + Message-Id/In-Reply-To/References 헤더 요청 |
| attachments_meta | `[{filename, mime, size_bytes}]` JSON | 파싱 없음 | parts[] walk + JSON serialize |
| raw_headers | EmailBody.raw_headers 저장 | 없음 | `payload.headers` 전체 JSON 직렬화 |
| parse_failed | HTML 파싱 실패 시 body_html 원본 보존 + parse_failed=true + Sentry | 브랜치 자체 없음 | try/catch Jsoup + flag + Sentry |
| EmailBody 저장 | 같은 트랜잭션에서 insert | 호출 없음 | `emailBodyRepository.insert(body)` 연결 (PR #2) |
| CommitmentExtractionWorker | 직후 호출 | 없음 | PR #11 handoff |
| OAuth token | Android Keystore + real impl | interface stub, 항상 null | `feat/repo/auth` 선행 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/data/remote/gmail/GmailClient.kt`
  - 기존 `listMessagesFullSync(pageToken)` 시그니처를 유지하되, 새로 `listMessagesFullSyncForLabel(label: GmailLabelScope, pageToken: String?): BecalmResult<GmailMessagePage>` 를 추가. `GmailLabelScope` 는 `INBOX` / `SENT` 두 값 enum. 기존 메서드는 `listMessagesFullSyncForLabel(INBOX, pageToken)` 로 위임하거나 deprecated 표기 후 제거.
  - URL 구성 전략: 카테고리 배제를 위해 `labelIds=` 대신 **`q=` 쿼리 파라미터**로 전환.
    - INBOX 패스: `q=label:inbox -category:promotions -category:social -category:updates -category:forums -in:spam -in:trash -in:drafts`
    - SENT 패스: `q=label:sent -in:trash -in:drafts`
    - 이유: Gmail API 의 `labelIds` 파라미터는 AND 세만트릭이라 CATEGORY 배제가 불가능하나 `q=` 는 Gmail search syntax 를 그대로 따르므로 negative 필터 가능.
  - `listHistory(startHistoryId)` — 응답의 `history[].messagesAdded[].message.labelIds` 도 같이 디코딩하도록 DTO 확장. 워커 단에서 라벨 교집합 확인으로 CATEGORY 배제 (history.list 에는 `q=` 가 없음). 또는 `getMessage` 단에서 labelIds 회수 후 필터 — 어느 쪽이든 **한 경로**로만 결정 (구현 세션이 판단; 권장은 getMessage 단 필터링 — history.list 응답 크기 줄이지 못하나 단일 진입점).
- `GmailClient.getMessage(messageId)` URL 변경:
  - `format=metadata` → `format=full` (본문 parts 획득).
  - 또는 절충: `format=metadata` 유지 + `metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Message-Id&metadataHeaders=In-Reply-To&metadataHeaders=References` 로 확장. 단 본문을 얻으려면 결국 `format=full` 이 필요하므로 **`format=full` 단일 경로** 권장.
  - 응답 파싱: `payload.parts[]` 를 재귀 walk 하여:
    1. `mimeType == "text/plain"` part 의 `body.data` (base64url) 를 디코딩 → `bodyPlain` 누적.
    2. `mimeType == "text/html"` part 의 `body.data` → `bodyHtml` 누적.
    3. `filename` 이 non-empty 인 part → `{filename, mime: mimeType, size_bytes: body.size}` 를 `attachmentsMeta` 리스트에 push.
    4. `multipart/*` part 는 재귀.
  - `headers` 에서 `To` (콤마 분리 → `List<String>`), `Message-Id`, `In-Reply-To`, `References` 회수.
  - 모든 헤더 배열을 그대로 직렬화하여 `rawHeadersJson` 으로도 보존 (EmailBody.raw_headers 용).
- `GmailMessage` (`GmailClient.kt:158-164`) — 확장 필드:
  - `folder: String` (`"INBOX"` | `"SENT"`) — `listMessagesFullSyncForLabel` 의 scope 를 워커가 주입 (getMessage 응답의 labelIds 로 재확인 가능하나 primary source 는 scope).
  - `toAddresses: List<String>` — 콤마 분리 후 trim, lowercase 는 유지하지 말고 원본 유지 (canonicalization 은 worker 에서).
  - `bodyPlain: String?`, `bodyHtml: String?`
  - `attachmentsMeta: List<GmailAttachmentMeta>` — `{filename, mime, sizeBytes}`
  - `messageId: String?` (RFC 2822 Message-Id 헤더 값), `inReplyTo: String?`, `references: String?`
  - `rawHeadersJson: String` — 원본 헤더 배열 JSON.
  - 기존 `snippet` 필드는 제거하지 말고 유지 (backward compat + 서버 snippet 은 EmailSnippetBuilder 가 fallback 으로 참고 가능). 단 GmailWorker 는 이 필드를 더 이상 `eventSnippet` 에 직접 넣지 않는다.
- `android/app/src/main/java/com/becalm/android/worker/ingestion/GmailWorker.kt`
  - `runFullSync` — INBOX 와 SENT 두 패스를 순차 실행:
    1. 루프: `while(true) listMessagesFullSyncForLabel(INBOX, pageToken)` → insert.
    2. 루프: `while(true) listMessagesFullSyncForLabel(SENT, pageToken)` → insert.
    3. 두 패스 중 어느 하나에서 `Retryable` 이면 해당 지점에서 반환 (부분 진행은 idempotent — 다음 run 에서 `q=` + historyId 로 재개).
  - `runIncrementalSync` — `listHistory` 결과를 순회할 때 각 messageAdded 에 대해 `getMessage` 응답의 labelIds 를 검사하여:
    - `INBOX` 포함 && 카테고리 배제 라벨 (`CATEGORY_PROMOTIONS`, `CATEGORY_SOCIAL`, `CATEGORY_UPDATES`, `CATEGORY_FORUMS`) 비포함 → folder="INBOX" 로 처리.
    - `SENT` 포함 → folder="SENT" 로 처리.
    - 그 외 (DRAFT/TRASH/SPAM 등) → skip (카운트만 로그).
  - `fetchAndInsert(userId, messageId, folder)` — folder 를 인자로 추가. `toEntity` 호출 시 전달.
  - `toEntity(userId, folder)` — folder 에 따라 personRef 결정:
    - `folder == "INBOX"` → `canonicalizeEmail(from)`.
    - `folder == "SENT"` && `toAddresses.size <= 10` → `canonicalizeEmail(toAddresses[0])`.
    - `folder == "SENT"` && `toAddresses.size > 10` → `personRef = null`, `groupEmail = true` (EmailBody 에만 저장).
    - 빈 toAddresses 로 SENT → personRef = null, groupEmail = false (빈 자체는 group 이 아님).
  - `sourceRef` 구성: Moshi `JsonAdapter<SourceRefEnvelope>` 로 `SourceRefEnvelope(messageId, inReplyTo, references)` 를 직렬화. `inReplyTo` 나 `references` 가 null 이면 JSON 에서 생략 (`@Json(omitDefault=true)` 또는 null 필터).
  - EmailBody 저장: `insertLocal(entity)` 성공 후 반환된 rawEventId 를 사용하여 `EmailBodyEntity` 생성:
    - `rawEventId`, `providerMessageId = messageId`, `folder`, `subject`, `fromAddress`, `toAddresses` (JSON list), `bodyPlain`, `bodyHtml`, `attachmentsMeta` (JSON), `rawHeaders`, `parseFailed`, `groupEmail`, `receivedAt`.
    - `emailBodyRepository.insert(body)` 호출. Room transaction 내부에서 raw event + email_body 를 묶는 것은 `RawIngestionRepository` 쪽이 transactional API 를 노출해야 함 (PR #2 의 `@Transaction` 데코레이션). 본 PR 은 repository 의 기존 시그니처를 따르되 "같은 transaction" 은 PR #2 가 보장 — 본 PR 은 순차 호출까지.
  - HTML 파싱 실패 분기 (EMAIL-007):
    - `bodyHtml` 이 있고 `bodyPlain` 이 null 일 때만 `EmailHtmlParser.parseOrFail(bodyHtml)` 호출 (PR #10 의 helper).
    - `try { Jsoup.parse(bodyHtml).text() } catch (e: Throwable)` → `parseFailed = true`, `bodyPlain = null`, `bodyHtml = 원본 유지`, Sentry breadcrumb `{error:"email_html_parse_failed", message_id: messageId, provider: "gmail"}`, `eventSnippet = subject.take(200)` (graceful degrade).
    - `parse_failed = true` 인 경우에도 raw event 는 정상 INSERT (quarantine 아님).
  - `canonicalizeEmail` — 기존 시그니처 유지. To[0] 에도 그대로 재사용 가능 (입력이 `"Name <addr>"` 또는 `addr` 양식 동일).

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/data/remote/gmail/GmailLabelScope.kt` — `enum class GmailLabelScope { INBOX, SENT }` + `val queryString: String` property (`q=` 값 생성).
- `android/app/src/main/java/com/becalm/android/data/remote/gmail/GmailAttachmentMeta.kt` — `data class GmailAttachmentMeta(val filename: String, val mime: String, val sizeBytes: Long)` (moshi `@JsonClass`).
- `android/app/src/main/java/com/becalm/android/data/remote/gmail/SourceRefEnvelope.kt` — `data class SourceRefEnvelope(val messageId: String, val inReplyTo: String?, val references: String?)` + Moshi adapter. **대안**: PR #1 이 이미 공용 envelope 을 만들 예정이면 본 PR 은 import 만. PR #1 plan 확인 필수.

### 5.3 Files to delete (dead code)

없음. `snippet` 필드를 GmailMessage 에서 제거하고 싶을 수 있으나 **본 PR 의 scope 가 아님** — `feat/refactor/worker/gmail/snippet-cleanup` 후속 PR 에서 처리.

### 5.4 Non-code changes

- Gradle: Jsoup 의존성은 PR #10 (`feat/domain/email/snippet-builder`) 이 `libs.versions.toml` + `build.gradle.kts` 에 추가. 본 PR 은 import 만.
- Moshi: `SourceRefEnvelope` + `GmailAttachmentMeta` 는 KSP 어댑터 생성 대상 → `@JsonClass(generateAdapter = true)` 만 붙이면 KSP 가 처리 (기존 패턴).
- Permission / Manifest: 변경 없음 (Gmail API 는 OAuth scope 기반 — `feat/repo/auth` 가 `https://www.googleapis.com/auth/gmail.readonly` scope 를 요청).

### 5.5 Tests

- `android/app/src/test/java/com/becalm/android/data/remote/gmail/GmailClientImplTest.kt` — 기존 테스트 확장:
  - `listMessagesFullSyncForLabel(INBOX, null)` 호출 시 URL 에 `q=label:inbox+-category:promotions+-category:social+-category:updates+-category:forums+-in:spam+-in:trash+-in:drafts` (URL-encoded) 가 포함됨 확인.
  - `listMessagesFullSyncForLabel(SENT, null)` 은 `q=label:sent+-in:trash+-in:drafts`.
  - `getMessage(...)` 가 `format=full` URL 을 사용함을 MockWebServer 로 검증.
  - Multipart payload (text/plain + text/html + attachment) fixture 를 만들어 `bodyPlain`, `bodyHtml`, `attachmentsMeta` 가 각각 기대 값으로 파싱됨.
  - Message-Id / In-Reply-To / References 헤더가 모두 포함된 응답 → DTO 필드 채워짐.
- `android/app/src/test/java/com/becalm/android/worker/ingestion/GmailWorkerTest.kt`:
  - `runFullSync_inboxThenSent_twoPasses` — Gmail client fake 가 INBOX 먼저 `["m1", "m2"]`, SENT `["m3"]` 반환. 최종 insert 4건 (`insertLocal` 2 + 2 호출).
  - `toEntity_inbox_personRefFromFrom` — folder=INBOX, from=`"Alice <a@x.com>"` → personRef=`"a@x.com"`.
  - `toEntity_sent_personRefFromTo0` — folder=SENT, toAddresses=`["b@y.com", "c@z.com"]` → personRef=`"b@y.com"`.
  - `toEntity_sent_over10_groupEmail` — folder=SENT, toAddresses.size=11 → personRef=null, EmailBodyEntity.groupEmail=true.
  - `sourceRef_jsonEnvelope` — messageId + inReplyTo + references 셋 있는 경우 JSON shape `{"message_id":"...","in_reply_to":"...","references":"..."}`. inReplyTo 만 null 인 경우 키 생략.
  - `htmlParseFailure_parseFailedTrue` — Jsoup parse throwable simulate (가짜 `EmailHtmlParser` stub) → EmailBody.parseFailed=true, body_html=원본, body_plain=null, Sentry breadcrumb enqueue 호출.
  - `subjectOnlyMail_fallback` — bodyPlain + bodyHtml 둘 다 null, subject 존재 → eventSnippet=subject.take(200), commitmentExtractionWorker 호출 안 됨 (PR #11 의 handoff 검증).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "labelIds=INBOX" android/app/src/main/java/` 가 0 (새 `q=` 경로로 대체됨)
- [ ] **Grep invariant**: `grep -rn "format=metadata" android/app/src/main/java/` 가 0 또는 comment-only
- [ ] **Grep invariant**: `grep -rn '"gmail:"\|clientEventId = "gmail' android/app/src/main/java/com/becalm/android/worker/ingestion/GmailWorker.kt | wc -l` 가 `>= 1` (idempotency key 패턴 유지)
- [ ] **Grep invariant**: `grep -rn "GmailLabelScope" android/app/src/main/java/ | wc -l` 가 `>= 2` (worker + client 양쪽에서 참조)
- [ ] **Grep invariant**: `grep -rn "emailBodyRepository\.insert\|EmailBodyRepository\.insert" android/app/src/main/java/com/becalm/android/worker/ingestion/GmailWorker.kt | wc -l` 가 `>= 1`
- [ ] **Unit test**: `GmailClientImplTest.listMessagesFullSyncForLabel_inbox_excludesCategories` 통과
- [ ] **Unit test**: `GmailClientImplTest.listMessagesFullSyncForLabel_sent_excludesDrafts` 통과
- [ ] **Unit test**: `GmailClientImplTest.getMessage_parsesMultipartBodyAndAttachments` 통과
- [ ] **Unit test**: `GmailClientImplTest.getMessage_readsReplyHeaders` 통과
- [ ] **Unit test**: `GmailWorkerTest.runFullSync_inboxThenSent_twoPasses` 통과
- [ ] **Unit test**: `GmailWorkerTest.toEntity_sent_personRefFromTo0` 통과
- [ ] **Unit test**: `GmailWorkerTest.toEntity_sent_over10_groupEmail` 통과
- [ ] **Unit test**: `GmailWorkerTest.sourceRef_jsonEnvelope_omitsNullFields` 통과
- [ ] **Unit test**: `GmailWorkerTest.htmlParseFailure_parseFailedTrue_gracefulDegrade` 통과
- [ ] **Unit test**: `GmailWorkerTest.subjectOnlyMail_skipsExtractionWorker` 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual**: Google OAuth 연결 후 (`feat/repo/auth` merge 완료 상태) 15 분 주기 GmailWorker 1 회 실행 → logcat 에서 `folder=INBOX` 와 `folder=SENT` 로그 각 1 회 이상 관찰 + `email_body` 테이블 row 수 `>= raw_ingestion_events(source_type='gmail')` row 수

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:

- `EmailBodyEntity` / `EmailBodyDao` / `MIGRATION_3_4` / `BeCalmDatabase.kt` version bump — **PR #1 (`feat/db/email-schema`)** 담당. 본 PR 은 entity import 만.
- `EmailBodyRepository` 인터페이스 + impl — **PR #2 (`feat/repo/email-body`)** 담당. 본 PR 은 DI 주입 + 호출만.
- `EmailSnippetBuilder` (body_plain/Jsoup(html)/subject fallback + 공백 정리 + 200 truncate) — **PR #10 (`feat/domain/email/snippet-builder`)** 담당. 본 PR 은 builder 호출 site 만.
- `CommitmentExtractionWorker` + `EmailPromptBuilder` + `QuotedBlockSplitter` + `GeminiNanoExtractor` 실제 구현 — **PR #11 (`feat/worker/extraction/email`)** 담당. 본 PR 은 worker 에서 enqueue site 추가 시점만.
- Outlook/IMAP provider 대응 변경 — 병렬 PR (`feat/worker/outlook-mail`, `feat/worker/imap`) 담당.
- `RawIngestionEventEntity.folder` 컬럼 추가 자체 (DB 측) — PR #1 담당. 본 PR 은 entity 필드 set 만 (PR #1 merge 후 컴파일 성공).
- `GoogleAuthTokenProviderImpl` + MSAL/Google Sign-In 통합 — **`feat/repo/auth`** 담당. 본 PR 의 테스트는 fake token provider 사용.
- Retention sweep (30일 삭제 정책) — **PR `feat/worker/retention-sweep`** 담당.
- `snippet` 필드 GmailMessage DTO 에서 제거 — 후속 cleanup PR.

---

## 8. Dependencies

- **Blocked by**:
  - PR #1 `feat/db/email-schema` (EmailBodyEntity + EmailBodyDao + MIGRATION_3_4 + BeCalmDatabase version 4 + RawIngestionEventEntity.folder 컬럼)
  - PR #2 `feat/repo/email-body` (EmailBodyRepository + impl + DI)
  - PR #10 `feat/domain/email/snippet-builder` (EmailSnippetBuilder + EmailHtmlParser + Jsoup 의존성)
  - PR #11 `feat/worker/extraction/email` (CommitmentExtractionWorker — 본 PR 은 enqueue hook site 만 추가)
  - `feat/repo/auth` (`GoogleAuthTokenProviderImpl` 실제 구현 — 없으면 런타임은 전부 Unauthorized 로 조기 반환)
- **Blocks**:
  - 없음 (Outlook / IMAP provider PR 과 병렬 가능)

병렬 가능 여부:
- `feat/worker/outlook-mail`, `feat/worker/imap` 와 **병렬 가능** — 파일 겹침 없음.
- PR #1/#2/#10/#11 과 **순차 진행 필수** — 선행 PR merge 후 본 PR rebase.

---

## 9. Rollback plan

1. `git revert <merge-sha>` → Gmail worker + client 가 INBOX-only + `format=metadata` 경로로 복귀. Email body 는 새로 insert 되지 않음.
2. 이미 insert 된 `email_body` row 는 잔존. PR #1 의 migration 은 revert 하지 않음 (schema 는 forward-only). `email_body` row 가 worker 없이도 harmless (UI 가 join 으로만 사용).
3. SyncCursorStore 의 `gmail_history_id` 값은 유지 — revert 후에도 incremental sync 는 동일 historyId 에서 재개.
4. OAuth 토큰은 별도 PR 관리 → 본 PR revert 와 무관.

주의: revert 순서는 `feat/worker/gmail` → PR #11 → PR #10 → PR #2 → PR #1 의 역순. 중간만 revert 하면 컴파일 깨짐. **데이터 손실 없음** (email_body 는 로컬 only, Railway 에는 업로드되지 않음).

---

## Appendix — Session handoff notes

- **`q=` vs `labelIds`** 선택 이유: Gmail API 의 `labelIds` 는 교집합 세만트릭이라 `labelIds=INBOX&labelIds=-CATEGORY_PROMOTIONS` 같은 배제가 불가 (negative label prefix 미지원). `q=` 는 Gmail search 문법을 풀 지원하므로 `-category:` / `-in:` 로 배제 가능. 단점: Gmail `q=` 는 full-text search 백엔드를 거치므로 레이턴시가 약간 더 높을 수 있음 (정량 벤치 필요시 별도 P3 조사).
- **`format=full` vs `raw`**: `raw` 는 RFC822 전체 메시지 base64url 반환 (파싱 전부 클라이언트). `full` 은 payload.parts 재귀 구조 + 헤더 array 를 이미 분해. MVP 는 `full` 로 충분. `raw` 로 전환 필요 시 Apache James Mime4j / Jakarta Mail MIME 파서 필요 — scope 증가.
- **MIME walk re-entrancy**: Gmail `multipart/alternative` 는 text/plain + text/html 를 형제로 두므로 둘 다 수집. `multipart/mixed` 는 첨부 + body parent. `multipart/related` 는 inline 이미지 등 — 본 PR 은 **첫 번째 text/plain 과 첫 번째 text/html 만 수집**하고 나머지는 무시 (EMAIL-004 는 body 만 요구).
- **`q=` URL encoding**: `q=label:inbox -category:promotions` 의 공백과 `-` 는 반드시 URL-encode. 기존 `GmailClient` 는 `buildString + append` 패턴이므로 `URLEncoder.encode(rawQuery, "UTF-8")` 을 통과시켜야 함. 테스트에 encoding 검증 케이스 포함.
- **parse_failed Sentry breadcrumb**: 현재 프로젝트에 Sentry 통합이 있는지 `grep -rn "Sentry\." android/app/src/main/java/` 로 확인 필요. 없으면 본 PR 은 Sentry 호출을 stub 으로 두고 실제 wiring 은 별도 PR (`feat/infra/sentry`) 담당. `Logger.e(TAG, "email_html_parse_failed ...")` 로 local log 만 남기는 degrade 전략 OK.
- **Transaction boundary**: raw event insert + email_body insert 를 atomic 하게 만들려면 `RawIngestionRepository` 가 `insertLocalWithBody(raw, body)` 같은 Room `@Transaction` 메서드를 노출해야 함. **PR #2** 가 이 API 를 추가. 본 PR 이 해당 API 가 없는 상태에서 merge 되면 "raw insert 성공 + body insert 실패" 시 orphan raw event 가 될 수 있음. PR #2 merge 전에는 본 PR 을 merge 하지 말 것.
- **Message-Id 헤더는 provider 별로 일관성이 다름** — 일부 Gmail 메시지는 `Message-ID` 대문자 변형, 일부는 `Message-Id`. `headers.firstOrNull { it.name.equals("Message-Id", ignoreCase = true) }` 패턴 유지. `In-Reply-To`, `References` 도 동일.
- **`References` 헤더는 공백 구분 다중 Message-Id** — 저장 시 원본 문자열 그대로 보관. 파싱은 LLM extraction 단에서 처리. 본 PR 은 raw 저장만.
- **Quota/rate-limit**: Gmail API 는 사용자 당 250 quota-units/second (`messages.get` 5 units each). 100 messages/run 이면 500 units, 2초 소요. SENT 까지 포함하면 2배. 현재 worker 는 rate-limit backoff 를 GmailClient 단에서 처리 (429 → RateLimited). 변경 없음.
- **label 확장 차후**: 이후 `IMPORTANT`, `STARRED` 라벨까지 확대 요청이 들어오면 `GmailLabelScope` enum 에 값 추가 + worker runFullSync 에 패스 추가로 확장 가능. MVP 는 INBOX+SENT 두 값만.
- **Test fixture 위치**: `android/app/src/test/resources/gmail/` 에 multipart sample JSON 배치 권장. 기존 프로젝트에 fixture 디렉토리 convention 이 있는지 구현 세션이 먼저 `grep -rn "src/test/resources" android/app/` 로 확인.
