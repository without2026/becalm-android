# Worker / IMAP / scope-body-and-bounds — INBOX+Sent 2폴더 pass + 30일 SEARCH bound + body/headers/attachments

**Branch**: `feat/worker/imap`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Email ingestion (Naver + Daum IMAP providers)
**Severity**: Critical (INBOX 단일 폴더 하드코딩 + UIDVALIDITY 재동기화가 30일 bound 를 무시 + EmailBody 미저장)
**Type**: Drift (단일 폴더, 100-count cap, bodyPreview 200 char) + Gap (folder listing/special-use, body_html, attachments, raw_headers, parse_failed, per-provider credentials integration)

---

## 1. Finding

ING-008 / EMAIL-001..007 은 Naver/Daum IMAP provider 가 (a) IMAP `LIST "" "*"` 로 폴더 목록 획득 후 `\Inbox` / `\Sent` special-use flag 로 INBOX 와 Sent 폴더 동적 식별 (naver 기본값 `보낸메일함`, daum 기본값 `보낸편지함`), (b) 각 폴더 별 독립 UIDVALIDITY + lastSeenUid 커서를 유지, (c) UIDVALIDITY mismatch 시 ING-013 "최근 30일" 바운드로 `SEARCH SINCE` 를, (d) 메시지 당 BODYSTRUCTURE walk 로 `body_plain` / `body_html` / `attachments_meta` 추출 + Message-Id / In-Reply-To / References 헤더 회수 + JSON source_ref + EmailBody Room insert + Jsoup HTML 파싱 + parse_failed 플래그를, (e) 임시보관함/스팸메일함/휴지통/전체메일 (naver) 및 동등 daum 폴더 배제, (f) 같은 시간에 Naver 와 Daum 이 병렬 실행 가능하도록 per-provider credentials 를 각각 저장할 것을 요구한다.

현재 `ImapClientImpl.fetchSince` (`ImapClient.kt:131-206`) 는 (1) 하드코딩 `store.getFolder("INBOX")` (`:150`) — 단일 폴더, LIST 쿼리 없음, special-use flag 미탐지, (2) UIDVALIDITY mismatch 시 `getMessagesByUID(1L, UIDFolder.LASTUID)` 로 메일박스 전체 회수 후 `capTail(maxMessages = 100)` (`:166-169`) — 30일 bound 무시, (3) `FetchProfile` 에 ENVELOPE/UID/CONTENT_INFO 만 (`:173-177`) — BODYSTRUCTURE 에서 attachment part 미회수, (4) `toImapMessage` (`:259-292`) 는 Message-Id 1 개만 read (`:278`) + `extractBodyPreview` 가 plain 200 char truncate (`:342-369`), body_html 별도 저장 없음. `ImapNaverWorker.toEntity` (`:236-247`) 와 `ImapDaumWorker.toEntity` (`:251-262`) 는 각각 MAILBOX_NAVER / MAILBOX_DAUM 단일 커서만 사용, folder 무관 `personRef = fromEmail`. `ImapDaumWorker` KDoc (`:41-45, 43-45`) 는 "shared ImapCredentialStore file holds exactly one IMAP credential tuple at a time" 를 명시 → Naver+Daum 병렬 실행 시 credential 충돌. 결과: 두 provider 모두 SENT 폴더 미indexing + UIDVALIDITY rebuild 가 ING-013 위반 + EmailBody 미구현으로 EMAIL-003..007 전체 비동작.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/data-ingestion.spec.yml:78-85` ING-008** — Naver IMAP 스코프:
  > "인덱싱 범위는 IMAP folder 'INBOX'(받은 메일) + '보낸메일함'(또는 서버 반환 SENT special-use flag가 있는 폴더) 두 개만 대상. '임시보관함', '스팸메일함', '휴지통', '전체메일'은 제외. IMAP 앱 비밀번호는 Android Keystore에 보관되며 Railway로 전송되지 않는다"
  > "Room raw_ingestion_events 1건(source_type='naver_imap', person_ref=INBOX는 발신자/보낸메일함은 수신자 이메일, event_title=이메일제목, event_snippet=본문앞200자, source_ref=UIDVALIDITY+UID) + EmailBody 1건 INSERT됨."

- **`.spec/data-ingestion.spec.yml:123-128` ING-013** — UIDVALIDITY 재동기화 30일 bound:
  > "커서 무효화(Gmail 410 historyId 만료 / IMAP UIDVALIDITY 변경 / MS Graph 410 deltaToken stale) 시 해당 소스 어댑터가 DataStore cursor를 초기화하고 제한된 전체 재동기화(최근 30일)를 수행한다"

- **`.spec/data-ingestion.spec.yml:155` 불변식**:
  > "6개 소스 어댑터는 병렬 실행되며 한 어댑터의 실패가 다른 어댑터의 실행을 중단시키지 않는다"

- **`.spec/data-ingestion.spec.yml:153` 불변식**:
  > "Gmail/Outlook OAuth 토큰 및 IMAP 앱 비밀번호는 Android Keystore에만 저장되며 Railway 서버로 전송되지 않는다"

- **`.spec/data-ingestion.spec.yml:159`** — INBOX+SENT 한정.

- **`.spec/email-pipeline.spec.yml:22-27` EMAIL-002** — personRef folder-aware.

- **`.spec/email-pipeline.spec.yml:40-45` EMAIL-004** — IMAP attachments:
  > "IMAP: BODYSTRUCTURE 파싱만, BODY[part] FETCH 안 함"

- **`.spec/email-pipeline.spec.yml:49-54` EMAIL-005** — source_ref JSON (`{message_id, in_reply_to?, references?}`).

- **`.spec/email-pipeline.spec.yml:58-72` EMAIL-006/007** — EmailBody Room-only + parse_failed graceful degrade.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 단일 폴더 INBOX 하드코딩

- **`android/app/src/main/java/com/becalm/android/data/remote/imap/ImapClient.kt:149-151`**:
  ```kotlin
  return@withContext try {
      folder = store.getFolder("INBOX")
      folder.open(Folder.READ_ONLY)
  ```
  → INBOX 단일. `listFolders` / `LIST` 커맨드 / `\Sent` special-use 탐지 없음.

- **`ImapClient.kt:87-115` interface `fetchSince`** — mailbox 파라미터 없음. 단일 INBOX 계약.

### 3.2 UIDVALIDITY rebuild 는 100-count cap (30일 bound 아님)

- **`ImapClient.kt:159-169`**:
  ```kotlin
  val fetchFromUid: Long = when {
      uidValidity == null -> 1L                    // first run
      uidValidity != serverUidValidity -> 1L       // resync after UIDVALIDITY change
      else -> uidNext ?: 1L                        // normal incremental
  }
  val rawMessages = uidFolder.getMessagesByUID(fetchFromUid, UIDFolder.LASTUID)
  val capped = rawMessages.capTail(maxMessages)   // ← 100 newest cap, 시간 bound 아님
  ```
  → 메일박스에 5 년치 데이터가 있어도 서버에서 메시지 전체를 내려 받고 클라이언트에서 100개 cap. 네트워크 낭비 + ING-013 "최근 30일" 시맨틱 위반.

- **`ImapNaverWorker.kt:274`** + **`ImapDaumWorker.kt:291`**: `MAX_MESSAGES_PER_RUN = 100`.

### 3.3 FetchProfile 에 BODYSTRUCTURE / 첨부 분석 없음

- **`ImapClient.kt:173-177`**:
  ```kotlin
  val fp = FetchProfile().apply {
      add(FetchProfile.Item.ENVELOPE)
      add(UIDFolder.FetchProfileItem.UID)
      add(FetchProfile.Item.CONTENT_INFO)
  }
  ```
  → `CONTENT_INFO` 는 body type 정보만 제한적. 재귀 walk 없음. `Multipart` 순회 시 attachment part 의 `filename`/`contentType`/`size` 수집 안 됨.

### 3.4 toImapMessage — Message-Id 만, body_html 없음

- **`ImapClient.kt:278`**:
  ```kotlin
  val messageId = getHeader("Message-Id")?.firstOrNull()
  ```
  → In-Reply-To / References 헤더 read 없음.

- **`ImapClient.kt:342-369` extractBodyPreview**:
  ```kotlin
  private fun extractBodyPreview(msg: jakarta.mail.Message): String? = runCatching {
      val content = msg.content ?: return null
      when {
          content is String -> content.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
          content is jakarta.mail.Multipart -> extractFromMultipart(content)
          else -> null
      }
  }.getOrNull()

  private fun extractFromMultipart(multipart: jakarta.mail.Multipart): String? {
      var textFallback: String? = null
      for (i in 0 until multipart.count) {
          ...
          if (CONTENT_TYPE_PLAIN in contentType) {
              return text.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
          }
          if (textFallback == null && contentType.startsWith(CONTENT_TYPE_TEXT_PREFIX)) {
              textFallback = text.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
          }
      }
      return textFallback
  }
  ```
  → text/plain 또는 text/html 중 하나만 200 char 로 truncate 하여 `bodyPreview` 단일 필드에 저장. body_plain / body_html 분리 없음. Jsoup HTML→plain 변환 없음.

### 3.5 worker 단일 커서 + folder 무관 personRef

- **`ImapNaverWorker.kt:98, 111-127, 236-247`** — `MAILBOX_NAVER = "naver"` 단일 cursor key, `fetchSince` 단일 호출, `personRef = fromEmail` 항상.
- **`ImapDaumWorker.kt:111, 124-132, 251-262`** — `MAILBOX_DAUM = "daum"` 단일, 동일.

### 3.6 Credentials 단일 tuple (Naver+Daum 병렬 불가)

- **`ImapDaumWorker.kt:41-45` KDoc**:
  > "shared ImapCredentialStore file holds exactly one IMAP credential tuple at a time (`host` column distinguishes provider) — callers must re-save the correct credential before enqueuing this worker"
- **`ImapDaumWorker.kt:134` + `ImapNaverWorker.kt:134`** — 두 워커 모두 `imapCredentialStore.getCredentials()` 파라미터 없이 호출 → 같은 파일 read. ING-011 "6개 소스 어댑터 병렬 실행" 불변식 직접 위반.

### 3.7 검증 grep

```bash
grep -rn 'getFolder("INBOX")\|store\.getFolder' android/app/src/main/java/
# → data/remote/imap/ImapClient.kt:150

grep -rn "UIDFolder\.LASTUID" android/app/src/main/java/
# → data/remote/imap/ImapClient.kt:166

grep -rn "capTail(maxMessages)" android/app/src/main/java/
# → data/remote/imap/ImapClient.kt:169

grep -rn "MAILBOX_NAVER\|MAILBOX_DAUM" android/app/src/main/java/
# → worker/ingestion/ImapNaverWorker.kt:98,202,267 + ImapDaumWorker.kt:111,216,282

grep -rn "naver_inbox\|naver_sent\|daum_inbox\|daum_sent" android/app/src/main/java/
# → 0

grep -rn "body_html\|bodyHtml\|attachments_meta" android/app/src/main/java/
# → 0
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 폴더 선택 | LIST + `\Sent` special-use → INBOX + Sent 두 폴더 | `getFolder("INBOX")` 하드코딩 | `listFolders()` 추가 + per-folder fetch |
| 폴더 배제 | 임시보관함/스팸메일함/휴지통/전체메일 (naver), 동등 daum 폴더 | 검사 없음 | allowlist (\Inbox + \Sent flag) or 명시 denylist |
| 커서 | Naver(inbox/sent) + Daum(inbox/sent) 4개 | 2 개 (`MAILBOX_NAVER`, `MAILBOX_DAUM` 각 단일) | 4 개 키로 확장 |
| UIDVALIDITY rebuild | 최근 30일 SEARCH bound | `getMessagesByUID(1, LASTUID) + capTail(100)` | `SEARCH SINCE` 로 전환 |
| 본문 | body_plain + body_html 분리 | bodyPreview 단일 (plain 또는 html 한쪽) | BODYSTRUCTURE walk + 두 필드 저장 |
| 첨부 | `[{filename, mime, size_bytes}]` JSON (BODYSTRUCTURE only, BODY[part] fetch 금지) | 파싱 안 함 | Part recursion + JSON |
| 헤더 | Message-Id + In-Reply-To + References | Message-Id only | `getHeader` 확장 |
| person_ref | folder-aware | fromEmail 항상 | folder 분기 |
| source_ref | JSON `{message_id, in_reply_to?, references?}` | plain messageId | envelope |
| EmailBody insert | 같은 트랜잭션 | 없음 | repository 호출 |
| parse_failed | Jsoup 실패 그레이스풀 | 없음 | try/catch + flag |
| credentials | per-provider | 단일 tuple 공유 | PR #6 가 `ImapCredentialStore.load(provider)` 네임스페이싱 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/data/remote/imap/ImapClient.kt` + `ImapClientImpl.kt`
  - 새 값 타입 `ImapFolder`:
    - `data class ImapFolder(val name: String, val specialUse: ImapSpecialUse?, val attributes: List<String>)` 또는 minimal `{name, isInbox, isSent}`.
  - 새 API `listFolders(host, port, user, password): BecalmResult<List<ImapFolder>>`:
    - `store.defaultFolder.list("*")` 순회, 각 폴더의 `Folder.getAttributes()` 에서 `\Inbox`, `\Sent`, `\Drafts`, `\Junk`, `\Trash`, `\All` 등 RFC 6154 special-use flag 탐지.
    - Naver 기본은 `\Sent` 없음일 수 있으므로 fallback: 이름이 `보낸메일함` (정확 일치) 이면 SENT 로 간주. Daum 은 `보낸편지함` 일치.
    - denylist 이름: `임시보관함`, `스팸메일함`, `휴지통`, `전체메일` (naver); Daum 은 `임시보관함`, `스팸함`, `휴지통`, `모든메일` (구현 세션이 Daum 정확 명칭 확인 — 사용자 디바이스 LIST 결과로 실증). denylist 일치 시 결과에서 제외.
    - 연결/인증 실패 매핑은 기존 `connectStore` 와 동일 (`BecalmError.Unauthorized`, `Network`, `Unknown`).
  - `fetchSince` 시그니처 변경:
    - 기존: `fetchSince(host, port, user, password, uidValidity, uidNext, maxMessages)` — INBOX 하드코딩.
    - 신규: `fetchSince(host, port, user, password, mailbox: String, uidValidity, uidNext, maxMessages, sinceDays: Int = 30)`.
    - `folder = store.getFolder(mailbox)` — INBOX 대신 `mailbox` 인자.
    - UIDVALIDITY mismatch 시 기존 `getMessagesByUID(1, LASTUID)` + `capTail` 경로를 **제거**. 대신:
      ```
      // 의사코드
      val sinceDate = Date.from(Instant.now().minus(sinceDays.days).toJavaInstant())
      val searchTerm = ReceivedDateTerm(ComparisonTerm.GE, sinceDate)
      val rawMessages = folder.search(searchTerm)
      ```
      `jakarta.mail.search.ReceivedDateTerm` 과 `SearchTerm` API 사용 (Jakarta Mail 2.1 표준). 서버가 SEARCH 지원 안 하면 fallback 으로 `capTail(maxMessages)` (로그 warning).
    - 정상 incremental (UIDVALIDITY 일치) 경로는 기존 `getMessagesByUID(fetchFromUid, LASTUID)` 유지.
    - `maxMessages` 는 정상 incremental 경로의 상한으로만 적용. 30일 SEARCH 결과도 매우 많으면 `capTail(maxMessages)` 추가 적용 (memory 보호).
  - `FetchProfile` 에 `FetchProfile.Item.CONTENT_INFO` 유지 + 헤더 직접 `getHeader("In-Reply-To")`, `getHeader("References")` 추가 호출 (CONTENT_INFO 는 헤더를 가져오지 않음; `FetchProfile.Item.HEADERS` 또는 `IMAPFolder.FetchProfileItem.HEADERS` 필요. 구현 세션이 Jakarta Mail docs 확인).
  - 새 helper `walkBodyStructure(msg: jakarta.mail.Message): ImapBodyExtract`:
    ```
    data class ImapBodyExtract(
        val bodyPlain: String?,     // 원본 (truncate 안 함; snippet 은 worker 에서 생성)
        val bodyHtml: String?,      // 원본
        val attachmentsMeta: List<ImapAttachmentMeta>,
    )
    ```
    - `msg.content` 가 `Multipart` 면 재귀 순회:
      - `mimeType == "text/plain"` + `Part.DISPOSITION == INLINE (or null)` → bodyPlain 누적.
      - `mimeType == "text/html"` + INLINE → bodyHtml 누적.
      - `filename != null` 또는 `disposition == ATTACHMENT` → `ImapAttachmentMeta(filename, mime = contentType, sizeBytes = part.size.toLong())` push. **BODY[part] FETCH 는 하지 않음** (스펙 EMAIL-004). `part.size` 는 BODYSTRUCTURE 에 이미 포함되므로 추가 FETCH 없음.
    - `msg.content` 가 `String` 이고 `msg.isMimeType("text/html")` → bodyHtml.
    - `msg.content` 가 `String` 이고 `msg.isMimeType("text/plain")` → bodyPlain.
  - `ImapMessage` 확장:
    - `folder: String` — 인자로 주입.
    - `toAddresses: List<String>` — `msg.getRecipients(Message.RecipientType.TO)` 순회.
    - `bodyPlain: String?`, `bodyHtml: String?`
    - `attachmentsMeta: List<ImapAttachmentMeta>`
    - `inReplyTo: String?`, `references: String?`
    - `rawHeadersJson: String` — `msg.allHeaders` 순회하여 `{name, value}` JSON 배열.
    - 기존 `bodyPreview` 필드는 **제거** (EmailSnippetBuilder 가 대체; body_plain 원본 + html → snippet 생성은 worker 에서).
  - `toImapMessage` — 위 확장 매핑 반영.

- `android/app/src/main/java/com/becalm/android/data/local/datastore/SyncCursorStore.kt`
  - 기존 `observeImapState(mailbox)` / `setImapState(mailbox, state)` API 는 유지 (mailbox 문자열 확장).
  - 새 mailbox key 4 개: `"naver_inbox"`, `"naver_sent"`, `"daum_inbox"`, `"daum_sent"`. DataStore key 는 `imapValidityKey("naver_inbox")` → `"imap_naver_inbox_uidvalidity"`, `imapUidKey("naver_inbox")` → `"imap_naver_inbox_uid"` (기존 naming 패턴 따라).
  - **Migration**:
    - 기존 `"naver"` mailbox 의 값을 `"naver_inbox"` 로 복사 (Inbox 는 cold-sync 회피). `"naver_sent"` 는 null 유지 (첫 실행 시 30일 bound cold-sync).
    - Daum 도 동일: `"daum"` → `"daum_inbox"`, `"daum_sent"` 는 null.
    - 마이그레이션 flag `imap_cursor_migration_v2_done`.

- `android/app/src/main/java/com/becalm/android/worker/ingestion/ImapNaverWorker.kt`
  - `doWork`:
    1. credentials 로드 — `imapCredentialStore.load(provider = "naver")` (PR #6 의 per-provider API). 없으면 Result.failure().
    2. 폴더 discovery: `imapClient.listFolders(host, port, user, password)` 1 회. 결과에서 `\Inbox` flag 있는 폴더 → inbox 이름, `\Sent` flag 있는 폴더 → sent 이름. Sent 가 없으면 이름 fallback `"보낸메일함"`. 결과에 denylist 일치 폴더는 이미 걸러짐.
    3. Inbox 패스: 기존 `fetchSince` 호출을 `mailbox = inboxName, mailboxKey = "naver_inbox"` 로 호출. `persistFetchedAndSucceed` 내부의 `MAILBOX_NAVER` 참조는 `"naver_inbox"` 로.
    4. Sent 패스: 동일 로직 `mailbox = sentName, mailboxKey = "naver_sent"`.
    5. 두 패스 중 하나라도 `Result.retry()` 면 해당 지점 반환. 성공은 두 패스 모두 완료 후 `Result.success()`.
  - `toEntity(userId, folder)`:
    - `clientEventId = "naver:${folder.lowercase()}:$uid:$uidValidity"` — folder 포함하여 Inbox↔Sent 간 UID 충돌 방지 (이론상 UIDVALIDITY 자체가 folder-scoped 이나 안전성).
    - `personRef`:
      - folder = INBOX → `canonicalizeEmail(fromEmail)`.
      - folder = SENT && toAddresses.size <= 10 → `canonicalizeEmail(toAddresses[0])`.
      - folder = SENT && toAddresses.size > 10 → `personRef = null`, `groupEmail = true`.
    - `sourceRef` = JSON `SourceRefEnvelope(messageId = messageId ?: "$uidValidity:$uid", inReplyTo, references)` — Message-Id 헤더가 없으면 UIDVALIDITY:UID fallback (기존 스펙 `source_ref=UIDVALIDITY+UID` 와 호환).
    - `folder` 컬럼 set (PR #1 의 RawIngestionEventEntity.folder).
    - EmailBody insert: subject, fromAddress, toAddresses JSON, bodyPlain (원본, truncate 안 함), bodyHtml (원본), attachmentsMeta JSON, rawHeadersJson, parseFailed, groupEmail, receivedAt, folder.
    - HTML 파싱 실패 분기 (EMAIL-007): bodyPlain 없고 bodyHtml 있을 때만 Jsoup parse → 실패 시 parseFailed=true + Sentry + eventSnippet=subject.take(200) (PR #10 EmailHtmlParser 경유).

- `android/app/src/main/java/com/becalm/android/worker/ingestion/ImapDaumWorker.kt`
  - ImapNaverWorker 와 byte-identical 패턴 (KDoc 이미 명시 — clone of Naver). credentials loader 는 `imapCredentialStore.load(provider = "daum")`, mailbox keys `"daum_inbox"` / `"daum_sent"`, Sent 이름 fallback `"보낸편지함"`.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/data/remote/imap/ImapFolder.kt` — `data class ImapFolder(val name: String, val specialUse: ImapSpecialUse?)` + `enum class ImapSpecialUse { INBOX, SENT, DRAFTS, JUNK, TRASH, ALL, OTHER }`.
- `android/app/src/main/java/com/becalm/android/data/remote/imap/ImapAttachmentMeta.kt` — `data class ImapAttachmentMeta(val filename: String, val mime: String, val sizeBytes: Long)`.
- `android/app/src/main/java/com/becalm/android/data/remote/imap/ImapProviderDenylist.kt` — per-provider 폴더 denylist 이름 상수. Naver = `setOf("임시보관함", "스팸메일함", "휴지통", "전체메일")`. Daum = 구현 세션이 실증 확인 (초기값: `setOf("임시보관함", "스팸함", "휴지통", "모든메일")` — 정확치 않으면 샘플 계정 LIST 출력 확인).

### 5.3 Files to delete (dead code)

없음. `BODY_PREVIEW_LENGTH` (`ImapClient.kt:372`) 상수는 `bodyPreview` 필드 제거 후 dead 가 되지만 **PR #10 EmailSnippetBuilder** 에서 `BODY_PREVIEW_LENGTH = 200` 을 재사용할 가능성 → 제거 여부는 후속 cleanup PR.

### 5.4 Non-code changes

- IMAP SEARCH 는 `jakarta.mail.search.ReceivedDateTerm` + `javax.mail.search.ComparisonTerm.GE` 조합 사용. Angus Mail 2.0.3 이 IMAP `SEARCH SINCE` 를 내부적으로 발행 (jakarta.mail.search.SearchTerm → IMAP SEARCH 번역). 추가 의존성 없음.
- Jsoup 의존성은 **PR #10** 가 `libs.versions.toml` 에 추가.
- `ImapCredentialStore` per-provider API 는 **PR #6 `fix/repo/imap`** 담당. 본 PR 은 `load("naver")` / `load("daum")` 호출 + 테스트 fake 만.
- Permission: 변경 없음 (IMAP 은 network 만).

### 5.5 Tests

- `android/app/src/test/java/com/becalm/android/data/remote/imap/ImapClientImplTest.kt` — MockIMAP 서버 (GreenMail 또는 Angus Mail test util) fixture 기반:
  - `listFolders_detectsInboxAndSentBySpecialUse` — GreenMail 서버에 Inbox + Sent (`\Sent` attribute) 생성 → `listFolders` 결과에 두 폴더 포함 + denylist 이름 폴더는 제외.
  - `listFolders_navarKoreanSentFallback` — `\Sent` flag 없는 `보낸메일함` 폴더 → 이름 fallback 로 SENT 로 식별.
  - `fetchSince_usesSpecifiedMailboxParameter` — `mailbox = "Sent"` 로 호출 시 Sent 폴더 내용만 반환.
  - `fetchSince_uidValidityMismatch_usesSearchSince30Days` — fake server UIDVALIDITY 바뀜 + `SEARCH SINCE` 쿼리가 발행됨 검증 (mock capture). 반환 메시지도 30일 이내만.
  - `walkBodyStructure_multipartAltText_returnsBoth` — multipart/alternative (text/plain + text/html) → bodyPlain + bodyHtml 둘 다 채워짐.
  - `walkBodyStructure_multipartMixed_extractsAttachmentMeta` — text/plain + PDF 첨부 → attachmentsMeta 1건 (`filename, mime="application/pdf", sizeBytes`). BODY[part] FETCH 가 호출되지 않음 (mock capture).
  - `walkBodyStructure_nestedMultipart_recursesCorrectly` — multipart/mixed (multipart/alternative + attachment) → 모두 회수.
  - `toImapMessage_readsReplyHeaders` — In-Reply-To / References 헤더 포함 메시지 → DTO 필드 채워짐 + rawHeadersJson 에 전체 헤더 보존.
  - `fetchSince_denylistedFolder_notFetched` — (간접) worker 테스트에서 `임시보관함` 폴더가 존재해도 listFolders 결과에 포함되지 않음.
- `android/app/src/test/java/com/becalm/android/worker/ingestion/ImapNaverWorkerTest.kt`:
  - `doWork_inboxThenSent_twoPasses_fourCursorKeys` — fake client 가 두 폴더 각각 1 메시지 반환. DataStore 에 `naver_inbox` + `naver_sent` 각 UIDVALIDITY/lastSeenUid 저장.
  - `toEntity_sent_personRefFromTo0` / `toEntity_sent_over10_groupEmail` / `toEntity_inbox_personRefFromFrom`.
  - `sourceRef_jsonEnvelope_uidFallbackWhenMessageIdNull`.
  - `doWork_htmlParseFailure_gracefulDegrade`.
  - `doWork_usesNaverProviderCredentials` — fake ImapCredentialStore 에서 `load("naver")` 호출 검증.
  - `migration_naverMailbox_copiedToNaverInbox` — pre-migration `"naver"` state → post-migration `"naver_inbox"`.
- `ImapDaumWorkerTest` — 위와 대칭 (daum).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn 'getFolder("INBOX")' android/app/src/main/java/` 가 0 (하드코딩 제거)
- [ ] **Grep invariant**: `grep -rn "UIDFolder\.LASTUID" android/app/src/main/java/` 가 0 또는 `getMessagesByUID(fetchFromUid, LASTUID)` 정상 incremental 경로로만 제한 (UIDVALIDITY mismatch rebuild 경로에서는 SEARCH 사용)
- [ ] **Grep invariant**: `grep -rn "SearchTerm\|ReceivedDateTerm\|SEARCH SINCE" android/app/src/main/java/ | wc -l` 가 `>= 1`
- [ ] **Grep invariant**: `grep -rn "naver_inbox\|naver_sent\|daum_inbox\|daum_sent" android/app/src/main/java/ | wc -l` 가 `>= 4`
- [ ] **Grep invariant**: `grep -rn "listFolders" android/app/src/main/java/com/becalm/android/data/remote/imap/ | wc -l` 가 `>= 2`
- [ ] **Grep invariant**: `grep -rn "ImapAttachmentMeta\|attachmentsMeta" android/app/src/main/java/ | wc -l` 가 `>= 3`
- [ ] **Grep invariant**: `grep -rn "bodyHtml\b" android/app/src/main/java/com/becalm/android/data/remote/imap/ | wc -l` 가 `>= 1`
- [ ] **Grep invariant**: `grep -rn 'imapCredentialStore\.load\|ImapCredentialStore\.load' android/app/src/main/java/com/becalm/android/worker/ingestion/ | wc -l` 가 `>= 2` (Naver + Daum)
- [ ] **Grep invariant**: `grep -rn "BODY\[part\]\|FetchProfile\.Item\.BODY\b" android/app/src/main/java/com/becalm/android/data/remote/imap/ | wc -l` 가 0 (attachment bytes 다운로드 금지)
- [ ] **Unit test**: `ImapClientImplTest.listFolders_detectsInboxAndSentBySpecialUse` 통과
- [ ] **Unit test**: `ImapClientImplTest.fetchSince_uidValidityMismatch_usesSearchSince30Days` 통과
- [ ] **Unit test**: `ImapClientImplTest.walkBodyStructure_multipartAltText_returnsBoth` 통과
- [ ] **Unit test**: `ImapClientImplTest.walkBodyStructure_multipartMixed_extractsAttachmentMeta` 통과
- [ ] **Unit test**: `ImapClientImplTest.toImapMessage_readsReplyHeaders` 통과
- [ ] **Unit test**: `ImapNaverWorkerTest.doWork_inboxThenSent_twoPasses_fourCursorKeys` 통과
- [ ] **Unit test**: `ImapNaverWorkerTest.toEntity_sent_over10_groupEmail` 통과
- [ ] **Unit test**: `ImapNaverWorkerTest.migration_naverMailbox_copiedToNaverInbox` 통과
- [ ] **Unit test**: `ImapDaumWorkerTest.doWork_usesDaumProviderCredentials` 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual (Naver)**: 테스트 계정 로그인 후 워커 1 회 실행 → logcat 에 `folder=INBOX` 와 `folder=SENT` 각 1 회 이상 + `email_body.folder DISTINCT` 에 `{INBOX, SENT}` 포함 + denylist 폴더의 메시지는 indexing 안 됨
- [ ] **Manual (parallel)**: Naver 워커와 Daum 워커 동시 enqueue → 두 워커 모두 성공 (credential 충돌 없음 — PR #6 의존)

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:

- `EmailBodyEntity` / DAO / `MIGRATION_3_4` / DB version bump — **PR #1**.
- `EmailBodyRepository` — **PR #2**.
- `ImapCredentialStore` per-provider 네임스페이싱 (API `load(provider)` + 별도 SharedPreferences 파일 또는 key prefix) — **PR #6 `fix/repo/imap`**. 본 PR 은 `load("naver")` / `load("daum")` 호출 site 만 추가하고 fake 로 테스트.
- `EmailSnippetBuilder` + `EmailHtmlParser` + Jsoup 의존성 — **PR #10**. 본 PR 은 호출 site 만.
- `CommitmentExtractionWorker` + `EmailPromptBuilder` + `QuotedBlockSplitter` — **PR #11**. 본 PR 은 enqueue hook 만.
- Gmail / Outlook provider — 병렬 PR 담당.
- `RawIngestionEventEntity.folder` 컬럼 — PR #1.
- IMAP IDLE push (즉시 감지) — post-MVP. 본 PR 은 periodic WorkManager 유지.
- GreenMail fixture 인프라 대규모 도입이 필요하면 **`feat/test/imap-fixture`** 별도 PR 로 분리 권장 (본 PR 의 테스트 기반).
- Retention sweep (30일 rolling delete) — 별도 PR.

---

## 8. Dependencies

- **Blocked by**:
  - PR #1 `feat/db/email-schema` (EmailBodyEntity + RawIngestionEventEntity.folder + DB version 4)
  - PR #2 `feat/repo/email-body` (Repository + 트랜잭셔널 insert API)
  - PR #6 `fix/repo/imap` (ImapCredentialStore per-provider) — **병렬 실행 invariant (ING-011) 충족을 위해 필수**. 본 PR merge 전에 #6 merge 권장. #6 없이 merge 되면 Naver+Daum 동시 enqueue 시 credential 덮어쓰기 위험.
  - PR #10 `feat/domain/email/snippet-builder` (EmailSnippetBuilder + EmailHtmlParser + Jsoup)
  - PR #11 `feat/worker/extraction/email` (CommitmentExtractionWorker)
- **Blocks**: 없음.
- 병렬 가능: `feat/worker/gmail`, `feat/worker/outlook-mail` 와 파일 겹침 없음. (`SourceRefEnvelope` 공용 위치만 조율.)

---

## 9. Rollback plan

1. `git revert <merge-sha>` → `ImapClient` / `ImapNaverWorker` / `ImapDaumWorker` 가 INBOX 단일 폴더 + 100-count rebuild 경로로 복귀.
2. DataStore 에 저장된 `naver_inbox` / `naver_sent` / `daum_inbox` / `daum_sent` 커서 값은 revert 후 **사용되지 않음** (무해). 선택적으로 migration 반대 방향으로 `"naver"` 키에 inbox 값 복사 (LIVE 데이터 유지) — but not mandatory.
3. SENT 폴더에 대해 indexing 된 `raw_ingestion_events` + `email_body` row 는 로컬 only → Railway 미업로드 → revert 후 고아화되나 사용자 영향 없음 (retention sweep 가 30일 후 삭제, 단 sweep 도 PR #1 이후).
4. PR #6 의 per-provider credentials 는 별도 스코프 — 본 PR revert 와 무관하게 유지.

주의: revert 순서는 `feat/worker/imap` → PR #11 → PR #10 → PR #6 → PR #2 → PR #1 의 역순. **데이터 손실**: SENT 커서 + SENT indexed rows (local only, 유저 가시 영향 낮음).

---

## Appendix — Session handoff notes

- **LIST `*` vs `LIST "" "*"`**: Jakarta Mail `store.defaultFolder.list("*")` 은 IMAP `LIST "" "*"` 를 발행. `list("%")` 는 루트 한 단계만. 사용자 서브폴더까지 포함해야 하므로 `"*"`.
- **`\Sent` 탐지는 RFC 6154 "SPECIAL-USE" 요구**. Naver/Daum 이 실제로 제공하는지는 서버 응답에 의존. 실증 필요:
  - Naver: 일부 테넌트는 `\Sent` 를 반환하지 않음 → 이름 fallback 필수.
  - Daum: 마찬가지. 구현 세션이 테스트 계정에서 `telnet imap.naver.com 993` → `A1 LIST "" "*"` 직접 확인 권장.
- **SEARCH SINCE 날짜 단위**: IMAP SEARCH 의 SINCE 는 day granularity (e.g. `1-Jan-2026`). Jakarta Mail `ReceivedDateTerm(ComparisonTerm.GE, Date)` 가 내부적으로 date-only 로 잘라서 보냄. 30-day bound 가 +-1일 slack 수용 — spec "최근 30일" 과 일치.
- **Angus Mail FetchProfile** — `FetchProfile.Item.ENVELOPE` 는 From/To/Cc/Bcc/Reply-To/Subject/Date/Message-Id 회수. `CONTENT_INFO` 는 body type. 헤더 전체가 필요하면 `FetchProfile.Item.HEADERS` 또는 `IMAPFolder.FetchProfileItem.HEADERS`. In-Reply-To / References 수집이 ENVELOPE 로 충분한지 확인 필요 (대부분 구현체는 Message-Id 만). 안전하게 `HEADERS` 추가 권장.
- **BODYSTRUCTURE only vs BODY[part] FETCH**: EMAIL-004 는 BODY[part] FETCH 금지 (attachment bytes 다운로드 금지). Jakarta Mail 의 `part.content` 를 **attachment part 에서 호출하면** BODY[part] FETCH 가 트리거됨. 따라서 attachment part 는 `part.fileName` / `part.contentType` / `part.size` 만 읽고 `part.content` 접근 안 함. Unit test 에서 `BODY[1]` 류 fetch command 가 발행되지 않음을 mock 으로 검증.
- **`part.size` vs BODYSTRUCTURE size**: Jakarta Mail `BodyPart.getSize()` 는 IMAP BODYSTRUCTURE 의 `SIZE` 값을 반환하며 BODY[part] FETCH 없이 획득 가능. 정확도는 RFC822 인코딩 후 바이트 수 (일반적으로 base64 포함).
- **Daum 폴더 이름 불확실성**: `보낸편지함` 외 `발송함`, `보낸 메일` 등 로케일 변형 존재 가능. 실증 전까지 `\Sent` special-use 우선 + 이름 fallback 2 단계. Denylist 이름도 실제 확인 필요 (`스팸함` vs `스팸메일`).
- **Test infra**: Jakarta Mail / Angus Mail 테스트는 보통 **GreenMail** (`com.icegreen:greenmail:2.0.x`) 을 사용. `build.gradle.kts` 에 `testImplementation("com.icegreen:greenmail:2.0.1")` 추가 필요 — 본 PR scope 에 포함. 또는 더 lightweight 하게 `ImapClient` 를 interface 화하고 fake 로 대체 (이미 interface — `ImapClient.kt:86`). unit test 는 fake 로 충분, integration test 만 GreenMail.
- **UIDVALIDITY 는 folder-scoped**: Inbox 와 Sent 가 각각 독립 UIDVALIDITY. 한 folder 만 rebuild 되어도 다른 folder 는 incremental 유지. 따라서 4 cursor 설계는 필수.
- **KDoc drift**: `ImapNaverWorker.kt:44-52` 의 KDoc "Incremental sync is driven by the UIDVALIDITY + UIDNEXT cursor pair persisted in [SyncCursorStore] under mailbox [MAILBOX_NAVER]" 는 단일 cursor 전제. 본 PR 에서 "Inbox / Sent 각각 독립 cursor" 로 업데이트.
- **Sentry breadcrumb provider 문자열**: Naver 는 `"naver_imap"`, Daum 은 `"daum_imap"` 으로 통일 (기존 SourceType 값과 일치).
- **Retry 정책**: 두 folder pass 중 한 쪽만 retryable 실패 시 `Result.retry()` 반환 → WorkManager 가 전체 워커 재실행. 다음 실행에서 Inbox 는 이미 진행한 지점부터 재개 (cursor 기반 idempotent). 비효율적이지만 정확성 우선.
- **polling cadence**: 기존 15 분 주기는 Gmail/Outlook 과 동일 유지. 폴더 2 개로 확장해도 동일 cadence.
