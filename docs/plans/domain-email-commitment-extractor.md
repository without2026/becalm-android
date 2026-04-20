# Domain / Email / commitment-extractor — CommitmentExtractionWorker + GeminiNano 실구현 + PromptBuilder + QuotedBlockSplitter

**Branch**: `feat/domain/email` (두 번째 commit — logic slug `commitment-extractor`; **첫 commit 은 `snippet-builder`**)
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 → 3 경계 — Email ingestion (Stage 2) → on-device LLM extraction (Stage 3)
**Severity**: Critical (EMAIL-001 · EMAIL-005 · EMAIL-008 · ADAPT-EMAIL-010 전원 구조적 부재 — 이메일 commitment 파이프라인 자체가 결여된 상태)
**Type**: Gap (Worker, PromptBuilder, QuotedBlockSplitter 모두 부재) + Dead-code (`GeminiNanoExtractor` 는 28-line stub 으로 never invoked)

---

## 1. Finding

스펙 `EMAIL-001` 은 이메일 어댑터(ING-006..008)가 `EmailBody` + `raw_ingestion_events` INSERT 직후 on-device `CommitmentExtractionWorker` 를 호출해야 한다고 규정한다. Worker 는 Gemini Nano(AICore) 에 system context (`source: email, folder: INBOX|SENT, user.phone_e164_self=..., user.display_name_override=...`) + per-event context(`subject, from, to, snippet, commitment_text, quoted_text`) 를 담은 프롬프트를 전달하고, 반환된 `CommitmentDraft[]` 을 Room `commitments` 테이블에 기록해야 한다. 현실은 (a) `CommitmentExtractionWorker` 파일 부재, (b) `domain/extractor/GeminiNanoExtractor.kt` 는 28-line stub 으로 `BecalmResult.Success(emptyList())` 만 반환하며 어떤 email 워커에서도 import 되지 않음, (c) 프롬프트 빌더 부재, (d) quoted-block 분리기 부재, (e) `phone_e164_self` / `display_name_override` 는 `UserPrefsStore` 에 저장되지만 어떤 LLM 프롬프트에도 read-through 되지 않음, (f) INBOX/SENT 폴더 힌트를 raw event 에 싣는 경로도 없음. 결과적으로 EMAIL-001 · EMAIL-005(quoted-block 분리) · EMAIL-008(프롬프트 구성) · ADAPT-EMAIL-010(worker handoff) 모두 **구현 0%** 상태.

이 PR 은 같은 `feat/domain/email` 브랜치의 snippet-builder commit 을 토대로 (1) `CommitmentExtractionWorker` 신설, (2) `EmailPromptBuilder` 신설, (3) `QuotedBlockSplitter` 신설, (4) `GeminiNanoExtractor` stub 교체 (파일 경로 유지, 바디 재작성), (5) `WorkScheduler` 에 enqueue 메서드 추가, (6) 프롬프트 템플릿을 `res/raw/email_system_prompt.txt` 로 version-pinned asset 화까지 다룬다.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/email-pipeline.spec.yml:13-19`** — `EMAIL-001`:
  > "이메일을 voice/commitment 추출 파이프라인에 넘길 때 direction 사전 힌트를 source_type에 붙이지 않고 raw event 레벨 메타(folder)로 전달한다. LLM 프롬프트에 'inbox' | 'sent' 힌트를 주고 최종 direction은 LLM 추론에 맡김 — 단, 기본값은 inbox→take, sent→give 가정"
  > "ING-006..008 어댑터가 EmailBody + raw event INSERT 직후 CommitmentExtractionWorker 호출"
  > "CommitmentExtractionWorker가 Gemini 프롬프트에 'source: email, folder: INBOX|SENT, user.phone_e164_self=..., user.display_name_override=...'를 system context로 전달. LLM 출력 CommitmentDraft[]의 direction은 inbox→take, sent→give 기본 가정이지만 본문 증거가 반대일 경우 override 가능 (예: INBOX에 '제가 월요일에 보내드리겠습니다' = give). 최종 direction 결정 근거는 quote 필드에 보존"

- **`.spec/email-pipeline.spec.yml:49-54`** — `EMAIL-005`:
  > "raw_ingestion_events.source_ref = JSON {message_id, in_reply_to?, references?}. body_plain의 quoted block(`^>`로 시작하는 라인 또는 'On ... wrote:' 이후)은 CommitmentExtractionWorker가 LLM 프롬프트에 'quoted_text' 섹션으로 분리 전달. LLM은 본 메시지 영역에서만 commitment 추출 — quoted 영역은 context 용도"

- **`.spec/email-pipeline.spec.yml:67-73`** — `EMAIL-007` (parse failure degrade — extractor 영향):
  > "raw_ingestion_events 정상 INSERT(event_snippet=subject 앞 200자), EmailBody.body_plain=null + body_html=원본 보존 + parse_failed=true 플래그."
  >
  > (이 PR 의 영향: `parse_failed=true` + `body_plain=null` 케이스는 Worker 가 LLM skip 하거나 `body_html` 텍스트화 실패 케이스로 graceful.)

- **`.spec/email-pipeline.spec.yml:76-82`** — invariants (관련 3개):
  > "INBOX→take, SENT→give는 direction 기본 가정이며 LLM이 본문 증거로 override 가능하다 — 최종 근거는 commitments.quote에 보존된다"
  > "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)"
  > "회신 메일의 quoted block은 LLM 프롬프트에서 quoted_text context로 분리 — 인용 영역에서는 commitment 추출하지 않는다"

- **`.spec/data-ingestion.spec.yml:60-67`** — `ING-006` Gmail expected: `EmailBody 1건 INSERT됨` (Worker 의 선행조건).

- **`.spec/data-ingestion.spec.yml:69-76`** — `ING-007` Outlook: 동일.

- **`.spec/data-ingestion.spec.yml:78-85`** — `ING-008` Naver IMAP: 동일.

- **`.spec/contracts/api-contract.yml`** — 관련 없음. `/v1/email_extract`, `/v1/commitments/extract` 엔드포인트 **부재**. 이메일 extraction 은 반드시 on-device 로 수행.

- **`.spec/contracts/data-model.yml`** (referenced from db-voice-call-recording-enum.md:19-32 exemplar):
  > `commitments.source_type` enum 에 `gmail, outlook_mail, naver_imap, daum_imap` 포함 — Worker 가 INSERT 할 때 email source_type 이 그대로 전파.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `CommitmentExtractionWorker` 파일 자체가 없음

- `android/app/src/main/java/com/becalm/android/worker/` 트리에 `extraction/` 서브디렉토리 부재, `CommitmentExtractionWorker` 클래스 부재.

검증 grep:
```bash
grep -rn "CommitmentExtractionWorker\|ExtractionWorker\|EmailExtractor" android/app/src/main/java/
# 0 matches
```

### 3.2 `GeminiNanoExtractor` 는 28-line no-op stub

- **`android/app/src/main/java/com/becalm/android/domain/extractor/GeminiNanoExtractor.kt:1-28`**:
  ```kotlin
  public class GeminiNanoExtractor {
      public suspend fun extract(entity: RawIngestionEventEntity): BecalmResult<List<CommitmentDraft>> {
          return BecalmResult.Success(emptyList())
      }
  }
  ```
  - KDoc (line 7-15): "In the current MVP, AICore integration is not yet linked, so [extract] always returns an empty list. This stub exists so that the extraction pipeline and tests can compile…"
  - 어떤 email 워커 / voice 워커에서도 **import 되지 않음** (dead code — grep 으로 호출부 0건 확인).

검증 grep:
```bash
grep -rn "GeminiNanoExtractor" android/app/src/main/java/
# 1 match (파일 자체의 선언만)
grep -rn "import com.becalm.android.domain.extractor.GeminiNanoExtractor" android/app/src/
# 0 matches
```

### 3.3 프롬프트 구축 경로 부재

- **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`** — `phone_e164_self`, `display_name_override` 키는 **저장 전용**. 어떤 LLM 프롬프트 문자열에도 read-through 되지 않음.
- `android/app/src/main/res/raw/` 에 `email_system_prompt.txt` 부재.
- `android/app/src/main/assets/prompts/` 디렉토리 부재.

검증 grep:
```bash
grep -rn "phone_e164_self\|display_name_override" android/app/src/main/
# 1 file hits (UserPrefsStore only — key definition)
grep -rn "EmailPromptBuilder\|PromptTemplates\|systemPrompt" android/app/src/main/
# 0 matches
```

### 3.4 Quoted-block splitter 부재

- `grep -rE 'quoted_text|On .* wrote|\^>' android/app/src/main/java/` → 0 matches.
- 현재 이메일 본문은 quoted block 그대로 LLM 에 들어가도 무방할 곳이 없음(LLM 호출 자체가 없음).

### 3.5 `VoiceTranscribeDtos.CommitmentDraftDto` 는 reusable

- **`android/app/src/main/java/com/becalm/android/data/remote/dto/VoiceTranscribeDtos.kt`** — `CommitmentDraftDto(direction, commitment_text, person_ref, due_at, confidence, quote)` 모양이 이미 존재. 음성 파이프라인이 서버 측 `/v1/voice/transcribe_extract` 로부터 받는 shape 과 동일. 이메일 on-device 추출 결과도 같은 shape 으로 수렴하면 downstream Room INSERT 경로 재사용 가능 (`CommitmentRepository` 가 이미 소비).

### 3.6 `WorkScheduler` 에 extraction enqueue 메서드 부재

- `android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt` interface 에 `enqueueCommitmentExtraction(rawEventId: String)` 부재.
- `WorkSchedulerImpl.kt` 에 구현 부재.
- `UniqueWorkKeys.kt` 에 extraction prefix 키 부재.

검증 grep:
```bash
grep -rn "enqueueCommitmentExtraction\|COMMITMENT_EXTRACTION" android/app/src/main/java/
# 0 matches
```

### 3.7 AICore 의존성은 이미 선언되어 있음 (구현만 안 되어 있음)

- **`android/gradle/libs.versions.toml:32,150-151`** — `gemini-nano-aicore = "0.0.1-exp01"` 버전 alias + `gemini-nano-aicore = { group = "com.google.ai.edge.aicore", name = "aicore", version.ref = "gemini-nano-aicore" }` library alias 존재.
- `android/app/build.gradle.kts` 에 `implementation(libs.gemini.nano.aicore)` 가 이미 있거나 없을 수 있음 — 구현 세션이 확인 후 필요 시 추가. (이 PR 의 acceptance 로 확인.)

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| `CommitmentExtractionWorker` 존재 | EMAIL-001 에 의해 필수 | 파일 부재 | 신규 CoroutineWorker |
| Worker input data | `rawEventId: String` | N/A | `Data.Builder().putString("rawEventId", id)` 계약 |
| GeminiNano 실제 호출 | AICore SDK 로 prompt 전달 + parse JSON | 28-line stub `Success(emptyList())` | 파일 내용 재작성 |
| system context in prompt | `source: email, folder, user.phone_e164_self, user.display_name_override` | 프롬프트 자체 없음 | PromptBuilder 신설 + UserPrefsStore read-through |
| per-event context | `subject, from, to, snippet, commitment_text, quoted_text` | 없음 | PromptBuilder |
| `^>` / `On ... wrote:` splitter | 본문 → commitment + quoted | 없음 | QuotedBlockSplitter 신설 |
| Default direction rule | INBOX=take, SENT=give, LLM override 가능 | 없음 | 프롬프트에 default rule 명시 + `EMAIL-001` quote preservation |
| WorkScheduler hook | ING-006..008 호출부에서 `enqueueCommitmentExtraction(id)` | 없음 | scheduler 메서드 추가 (호출부 교체는 out of scope — 후속 워커 PR) |
| UniqueWorkKey 패턴 | extraction 단위별 key | 없음 | `COMMITMENT_EXTRACTION_{rawEventId}` prefix |
| Prompt asset | version 관리 가능해야 | 없음 | `res/raw/email_system_prompt.txt` |
| 테스트 | unit: prompt/splitter table-driven, worker Robolectric | 0건 | 3개 테스트 파일 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 이 PR 은 `feat/domain/email` 브랜치의 **두 번째 commit** 이며 첫 commit(`snippet-builder`) 의 `EmailSnippetBuilder` / `SnippetResult` / `MetricsStore` 가 존재한다고 가정한다.

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/domain/extractor/GeminiNanoExtractor.kt`** (rewrite, 파일 경로 유지)
  - 기존 stub body 전부 삭제, class 선언부만 유지하되 다음으로 교체:
    - 생성자 `@Inject constructor(@ApplicationContext private val context: Context)` (Hilt — AICore 는 Context 필요).
    - `public suspend fun extract(systemContext: String, userContext: String): BecalmResult<List<CommitmentDraftDto>>` — signature 변경 (entity 대신 prompt 문자열 2개를 받음). 반환 타입도 `List<CommitmentDraftDto>` 로 통일 (VoiceTranscribeDtos 재사용).
    - AICore SDK 호출: `com.google.ai.edge.aicore.GenerativeModel` 초기화 → `generateContent(prompt)` 호출 → JSON body 를 Moshi `CommitmentDraftDtoList` adapter 로 파싱.
    - 에러 경로:
      - AICore 미지원 기기 (Feature Not Supported) → `BecalmResult.Failure(ExtractorUnavailable(reason="AICORE_NOT_AVAILABLE"))` — Timber warn 로그 + 호출부가 graceful skip.
      - JSON parse 실패 → `BecalmResult.Failure(ExtractorUnavailable(reason="LLM_JSON_PARSE_FAILED"))`.
      - 일반 예외 → `BecalmResult.Failure(ExtractorUnavailable(reason="AICORE_ERROR", cause=e))`.
    - KDoc 교체: MVP stub 문구 전부 삭제, 실제 계약 기술.
  - `BecalmResult.Failure` sealed 계층 확장 필요 시 **`core/result/BecalmResult.kt`** 의 `sealed class Failure` 에 `ExtractorUnavailable(reason: String, cause: Throwable? = null)` 추가.

- **`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt`**
  - 상수 2개 추가:
    - `public const val COMMITMENT_EXTRACTION_PREFIX: String = "commitment-extraction-"`
    - helper `public fun commitmentExtractionKey(rawEventId: String): String = COMMITMENT_EXTRACTION_PREFIX + rawEventId`
  - 기존 스타일(이미 파일에 여러 UniqueWorkKey 상수가 있을 것) 그대로 따름.

- **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`** (interface)
  - 신규 메서드: `public fun enqueueCommitmentExtraction(rawEventId: String)`
  - 주석으로 `ExistingWorkPolicy.APPEND_OR_REPLACE` 사용 의도 기술 (동일 rawEventId 에 대한 중복 방지 + 재시도 가능).

- **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`** (impl)
  - `enqueueCommitmentExtraction` 구현:
    - `OneTimeWorkRequestBuilder<CommitmentExtractionWorker>()`
    - `.setInputData(workDataOf("rawEventId" to rawEventId))`
    - `.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` — AICore 는 background 에서도 동작 가능하므로 expedited 를 선호하되 quota 부족 시 fallback.
    - `.setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())` — AICore 는 배터리 소모 큼, low battery 회피.
    - `workManager.enqueueUniqueWork(commitmentExtractionKey(rawEventId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)`.

- **`android/app/src/main/java/com/becalm/android/core/result/BecalmResult.kt`** (if not already extensible)
  - `sealed class Failure` 밑에 `public data class ExtractorUnavailable(val reason: String, override val cause: Throwable? = null) : Failure()` 추가.
  - 기존 failure 타입 (NetworkFailure, ParseFailure 등) 스타일 맞출 것.

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/worker/extraction/CommitmentExtractionWorker.kt`**
  - `@HiltWorker class CommitmentExtractionWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, private val rawEventDao: RawIngestionEventDao, private val emailBodyDao: EmailBodyDao, private val commitmentDao: CommitmentDao, private val userPrefsStore: UserPrefsStore, private val metricsStore: MetricsStore, private val promptBuilder: EmailPromptBuilder, private val quotedBlockSplitter: QuotedBlockSplitter, private val geminiNanoExtractor: GeminiNanoExtractor) : CoroutineWorker(context, params)`.
  - `override suspend fun doWork(): Result`:
    1. `val rawEventId = inputData.getString("rawEventId") ?: return Result.failure()`.
    2. `rawEventDao.getById(rawEventId)` → null 이면 `Result.failure()` (로그만).
    3. `emailBodyDao.getByRawEventId(rawEventId)` → null 이면 non-email 이므로 `Result.success()` (no-op — defensive).
    4. `SnippetResult` 는 이미 `rawEvent.event_snippet` 으로 저장되어 있으므로, 여기서는 **snippet 생성 반복 아님**. Worker 는 `emailBody.body_plain` / `body_html` 로부터 **프롬프트용 본문** 을 결정한다:
       - `body_plain` 이 있으면 splitter 에 통과.
       - 없고 `body_html` 이 있으면 `EmailSnippetBuilder.buildSnippet(null, body_html, null)` 를 호출해 plain 으로 풀고 그 결과를 splitter 에 통과 (note: snippet builder 가 200자 cap 을 하므로 LLM 에 풀-바디를 주려면 splitter 가 body_html 을 stripping 하는 별도 경로가 필요 — **Out of Scope** 처리, MVP 에서는 snippet builder 를 재사용하여 200자 제한을 수용. 향후 PR 에서 확장).
       - 둘 다 없으면 `Result.success()` (subject-only → LLM skip; `metricsStore.incrementSubjectOnlySkipped()` 호출 후 종료).
    5. `val (commitmentBody, quoted) = quotedBlockSplitter.split(bodyPlain)`.
    6. `val folder = rawEvent.folder ?: "INBOX"` — folder 컬럼은 별도 PR (`feat/db/email-folder`) 에서 추가되므로 여기서는 `rawEvent.folder` 접근 후 null 이면 기본 INBOX 가정. **Out of Scope** 의존성 참고.
    7. `val systemContext = promptBuilder.buildSystemContext(folder, userPrefsStore.phoneE164Self(), userPrefsStore.displayNameOverride())`.
    8. `val userContext = promptBuilder.buildUserContext(subject=rawEvent.eventTitle, from=emailBody.fromAddress, to=emailBody.toAddress, snippet=rawEvent.eventSnippet, commitmentText=commitmentBody, quotedText=quoted)`.
    9. `val drafts = geminiNanoExtractor.extract(systemContext, userContext)`.
    10. `drafts` 결과 처리:
        - `Success(list)` → 각 draft 을 `CommitmentEntity` 로 매핑하여 `commitmentDao.insertAll(list)` + `rawEventDao.updateCommitmentsExtractedCount(rawEventId, list.size)`.
        - `Failure(ExtractorUnavailable)` → Timber warn + `Result.retry()` (WorkManager backoff) — 단 AICore_NOT_AVAILABLE reason 이면 `Result.success()` (영구적 비가용, retry 무의미).
  - Return `Result.success()` 또는 `Result.retry()` / `Result.failure()` 에 따라.
  - `@AssistedFactory interface Factory : ChildWorkerFactory` — 기존 Hilt WorkerFactory 패턴 따를 것.

- **`android/app/src/main/java/com/becalm/android/domain/email/EmailPromptBuilder.kt`**
  - 공개 API:
    - `public class EmailPromptBuilder @Inject constructor()` — 순수 함수 holder (Context 없음, 단위 테스트 친화적).
    - `public fun buildSystemContext(folder: String, phoneE164Self: String?, displayNameOverride: String?): String`
    - `public fun buildUserContext(subject: String?, from: String?, to: String?, snippet: String?, commitmentText: String, quotedText: String?): String`
  - system context 형태 (EMAIL-001 스펙 인용 텍스트 그대로 반영):
    ```
    source: email
    folder: {INBOX|SENT}
    default_direction: {take|give}  # INBOX→take, SENT→give
    user.phone_e164_self: {phoneE164Self ?? "(not_set)"}
    user.display_name_override: {displayNameOverride ?? "(not_set)"}
    rules:
    - Extract commitments only from the primary message body, NOT from quoted_text.
    - default_direction is a HINT; override from body evidence when clear.
    - Preserve override evidence as `quote` field.
    - Return a JSON array of CommitmentDraftDto: {direction, commitment_text, person_ref, due_at, confidence, quote}.
    ```
  - user context 형태:
    ```
    subject: {subject}
    from: {from}
    to: {to}
    snippet: {snippet}
    ---
    commitment_text:
    {commitmentText}
    ---
    quoted_text:
    {quotedText ?? "(none)"}
    ```
  - 템플릿 로딩: 두 가지 방식 중 택 — (a) 문자열 상수 (constant) 로 인라인, (b) `res/raw/email_system_prompt.txt` 파일 로드 후 placeholder 치환. **권장: (b)** — 프롬프트 version-pin + git diff 로 변경 추적 + Moshi schema 와 분리.
  - Placeholder syntax: `{folder}`, `{default_direction}`, `{phone_e164_self}`, `{display_name_override}`.
  - `null` 또는 empty 입력 → 모두 `(not_set)` / `(none)` 문자열로 substituted (LLM 이 missing context 를 알 수 있도록).

- **`android/app/src/main/java/com/becalm/android/domain/email/QuotedBlockSplitter.kt`**
  - 공개 API:
    - `public class QuotedBlockSplitter @Inject constructor()` (or `object`).
    - `public data class SplitResult(val commitment: String, val quoted: String?)`
    - `public fun split(bodyPlain: String): SplitResult`
  - 알고리즘 (정확한 순서):
    1. **센티넬 스캔** — 정규식 `(?m)^On\s+.+\s+wrote:\s*$` 로 첫 번째 매치를 찾는다 (multi-line 모드). 매치 시: 매치 이전 전부를 `commitment`, 매치 이후(매치 라인 포함) 전부를 `quoted`. 단, `>` 라인까지 포함되도록 끝까지.
    2. **Gmail/Outlook 변종 센티넬** — 정규식 목록으로 확장: `(?m)^On\s+.+\s+wrote:\s*$`, `(?m)^-----Original Message-----\s*$`, `(?m)^From:.*\nSent:.*\nTo:.*\nSubject:.*$` (Outlook "original message" block). 첫 match 우선.
    3. **`^>` 라인 스캔** — 센티넬이 없을 경우 `(?m)^>.*$` 패턴으로 연속된 quoted 라인 블록을 추출. 구체적으로:
       - 각 라인을 순회하며 `line.trimStart().startsWith(">")` 인 라인들의 최대 연속 블록을 찾는다 (nested `>>`, `>>>` 도 포함 — `startsWith(">")` 만족).
       - 블록 상단부터 파일 끝까지를 `quoted`, 블록 이전을 `commitment` 로 분류. 단, 블록 이후에 `>` 가 아닌 라인이 나오면 그 지점부터 다시 commitment 로 간주. **첫 번째 연속 `>` 블록만 분리**, 이후는 quoted 에 포함.
    4. 아무것도 매치 안 되면 `SplitResult(commitment=bodyPlain, quoted=null)`.
  - 정규식 상수는 top-level private `val` (컴파일 1회) 로 보관.
  - Whitespace 처리: commitment 와 quoted 각각 `trim()` 적용. 빈 문자열이 되면 `quoted = null` 로 정규화.

- **`android/app/src/main/res/raw/email_system_prompt.txt`**
  - 위의 system context 템플릿을 그대로 파일로 저장 (placeholder 포함). `PromptBuilder` 는 앱 시작 시 1회 read → 캐시 후 placeholder 치환.
  - **이유**: 프롬프트 변경은 LLM 출력 품질에 직접 영향 → PR diff 로 reviewer 가 변경 추적. `git log res/raw/email_system_prompt.txt` 로 버전 감사 가능.

- **`android/app/src/test/java/com/becalm/android/domain/email/EmailPromptBuilderTest.kt`**
  - 테스트 케이스:
    - `` `buildSystemContext: INBOX folder → default_direction=take` ``
    - `` `buildSystemContext: SENT folder → default_direction=give` ``
    - `` `buildSystemContext: phoneE164Self null → (not_set) placeholder` ``
    - `` `buildSystemContext: displayNameOverride null → (not_set) placeholder` ``
    - `` `buildSystemContext: both user fields present → values interpolated` ``
    - `` `buildUserContext: quotedText null → (none) placeholder` ``
    - `` `buildUserContext: all fields present → subject/from/to/snippet/commitment_text/quoted_text present in output` ``
    - `` `buildUserContext: empty commitmentText still produces output with empty commitment_text section` ``

- **`android/app/src/test/java/com/becalm/android/domain/email/QuotedBlockSplitterTest.kt`**
  - 테스트 케이스 (table-driven):
    - `` `no quoted block → entire body is commitment, quoted is null` ``
    - `` `single > prefix line at end → split correctly` ``
    - `` `multiple consecutive > lines → all go to quoted` ``
    - `` `nested >> >>> lines → all recognized as quoted` ``
    - `` `'On Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:' sentinel → everything after is quoted` ``
    - `` `'-----Original Message-----' Outlook sentinel → everything after is quoted` ``
    - `` `'From: ... \n Sent: ... \n To: ... \n Subject: ...' Outlook header block → quoted` ``
    - `` `commitment text only, no quoted → quoted=null` ``
    - `` `blank body → commitment="" quoted=null` ``
    - `` `body_plain starts with > (full quote reply) → commitment="" quoted=fullBody` ``
    - `` `sentinel takes precedence over ^> when both present` ``
    - `` `Korean 'On' variant: '2023년 12월 18일 오후 3:45, John <john@x.com> 님이 작성:' → NOT matched (MVP English-only)` `` — Korean variant 는 out-of-scope, assert 로 문서화.

- **`android/app/src/test/java/com/becalm/android/worker/extraction/CommitmentExtractionWorkerTest.kt`**
  - Robolectric + WorkManager TestDriver. `@RunWith(AndroidJUnit4::class)`.
  - Fakes:
    - `FakeRawIngestionEventDao` — in-memory map.
    - `FakeEmailBodyDao` — in-memory map.
    - `FakeCommitmentDao` — records insertAll calls.
    - `FakeGeminiNanoExtractor` — programmable return (`Success(list)` / `Failure(ExtractorUnavailable(...))`).
    - `FakeUserPrefsStore`, `FakeMetricsStore`.
    - Real `EmailPromptBuilder`, `QuotedBlockSplitter`, `EmailSnippetBuilder` (pure domain).
  - 테스트 케이스:
    - `` `rawEventId missing from inputData → Result.failure()` ``
    - `` `rawEvent not found → Result.failure()` ``
    - `` `emailBody not found (non-email source_type) → Result.success() (no-op)` ``
    - `` `bodyPlain present, splitter returns commitment+quoted, extractor returns 2 drafts → commitmentDao.insertAll called with 2, updateCommitmentsExtractedCount=2` ``
    - `` `bodyPlain null, bodyHtml present → EmailSnippetBuilder falls back to HTML_STRIPPED, LLM called` ``
    - `` `both bodies null → metricsStore.incrementSubjectOnlySkipped() called once, Result.success()` ``
    - `` `extractor returns ExtractorUnavailable(AICORE_NOT_AVAILABLE) → Result.success() (no retry)` ``
    - `` `extractor returns ExtractorUnavailable(AICORE_ERROR) → Result.retry()` ``
    - `` `folder=SENT → systemContext contains default_direction=give` `` — 간접 검증 (PromptBuilder 통해).

### 5.3 Files to delete (dead code)

- `GeminiNanoExtractor.kt` 의 기존 **body 만** 삭제 (파일 자체는 유지 — rewrite). 이는 git 관점에서 large diff 로 보임.
- 이 PR 로 인해 새로 dead 가 되는 코드 없음.

### 5.4 Non-code changes

- **DB migration**: 없음. `commitments` 테이블은 이미 존재, `raw_ingestion_events.commitments_extracted_count` 컬럼도 이미 존재 (가정 — 구현 세션이 확인). `rawEvent.folder` 컬럼은 **별도 PR (`feat/db/email-folder`)** — 이 PR 은 folder 값이 null 일 때 INBOX default 를 사용하여 진행.
- **Permission**: 없음. AICore 는 런타임 권한 불필요.
- **Config / manifest**: `AndroidManifest.xml` 에 AICore 서비스 선언 필요 여부 확인 — 보통 필요 없지만 SDK 문서 재검증. `<uses-feature>` 태그로 AICore 지원 기기 필터링은 옵션 (이 PR 은 적용하지 않음 — 미지원 기기도 앱 실행은 되어야 함).
- **Proguard**: AICore SDK 가 reflection 을 쓰면 `-keep class com.google.ai.edge.aicore.** { *; }` 필요 가능. 릴리즈 빌드에서 실제 crash 확인 후 별도 PR 에서 처리.
- **BuildConfig flag**: `BUILDCONFIG.ENABLE_EMAIL_EXTRACTION` 같은 kill-switch 를 `build.gradle.kts` 에 추가할지 논의 — MVP 에서는 **추가하지 않음** (단순화). 문제 발생 시 revert.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증** 할 수 있는 항목.

### 6.1 파일 존재

- [ ] `find android/app/src/main/java/com/becalm/android/worker/extraction -name 'CommitmentExtractionWorker.kt' | wc -l` 가 1.
- [ ] `find android/app/src/main/java/com/becalm/android/domain/email -name 'EmailPromptBuilder.kt' | wc -l` 가 1.
- [ ] `find android/app/src/main/java/com/becalm/android/domain/email -name 'QuotedBlockSplitter.kt' | wc -l` 가 1.
- [ ] `find android/app/src/main/res/raw -name 'email_system_prompt.txt' | wc -l` 가 1.

### 6.2 Grep invariants (구현 완료 지표)

- [ ] `grep -rn 'class CommitmentExtractionWorker' android/app/src/main/java/` 가 1.
- [ ] `grep -rn 'enqueueCommitmentExtraction' android/app/src/main/java/` 가 최소 2 (interface 선언 + impl 메서드).
- [ ] `grep -rn 'COMMITMENT_EXTRACTION_PREFIX\|commitmentExtractionKey' android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt` 가 최소 2.
- [ ] `grep -rn 'GenerativeModel\|com.google.ai.edge.aicore' android/app/src/main/java/com/becalm/android/domain/extractor/GeminiNanoExtractor.kt` 가 최소 1.
- [ ] `grep -n 'Success(emptyList())' android/app/src/main/java/com/becalm/android/domain/extractor/GeminiNanoExtractor.kt` 가 0 (stub body 제거됨).
- [ ] `grep -n 'AICore integration is not yet linked' android/app/src/main/java/com/becalm/android/domain/extractor/GeminiNanoExtractor.kt` 가 0 (stub KDoc 제거됨).
- [ ] `grep -rn 'ExtractorUnavailable' android/app/src/main/java/com/becalm/android/core/result/BecalmResult.kt` 가 최소 1.
- [ ] `grep -rn 'phone_e164_self\|phoneE164Self' android/app/src/main/java/com/becalm/android/domain/email/EmailPromptBuilder.kt` 가 최소 1.
- [ ] `grep -rn 'display_name_override\|displayNameOverride' android/app/src/main/java/com/becalm/android/domain/email/EmailPromptBuilder.kt` 가 최소 1.

### 6.3 Regex 패턴 invariants (QuotedBlockSplitter)

- [ ] `grep -n 'On.*wrote:' android/app/src/main/java/com/becalm/android/domain/email/QuotedBlockSplitter.kt` 가 최소 1 — sentinel 정규식 존재.
- [ ] `grep -n '\^>' android/app/src/main/java/com/becalm/android/domain/email/QuotedBlockSplitter.kt` 가 최소 1 — `^>` 패턴 존재.
- [ ] `grep -n -- '-----Original Message-----' android/app/src/main/java/com/becalm/android/domain/email/QuotedBlockSplitter.kt` 가 최소 1.

### 6.4 Prompt asset

- [ ] `grep -c 'source: email' android/app/src/main/res/raw/email_system_prompt.txt` 가 최소 1.
- [ ] `grep -c '{folder}\|{default_direction}' android/app/src/main/res/raw/email_system_prompt.txt` 가 최소 2 (placeholder 존재).
- [ ] `grep -c 'quoted_text' android/app/src/main/res/raw/email_system_prompt.txt` 가 최소 1.

### 6.5 Tests

- [ ] `grep -c '^\s*@Test' android/app/src/test/java/com/becalm/android/domain/email/EmailPromptBuilderTest.kt` 가 8 이상.
- [ ] `grep -c '^\s*@Test' android/app/src/test/java/com/becalm/android/domain/email/QuotedBlockSplitterTest.kt` 가 11 이상.
- [ ] `grep -c '^\s*@Test' android/app/src/test/java/com/becalm/android/worker/extraction/CommitmentExtractionWorkerTest.kt` 가 8 이상.
- [ ] `./gradlew :app:testDebugUnitTest --tests "*EmailPromptBuilderTest*"` 전원 green.
- [ ] `./gradlew :app:testDebugUnitTest --tests "*QuotedBlockSplitterTest*"` 전원 green.
- [ ] `./gradlew :app:testDebugUnitTest --tests "*CommitmentExtractionWorkerTest*"` 전원 green.

### 6.6 Compile & lint

- [ ] `./gradlew :app:compileDebugKotlin` 성공.
- [ ] `./gradlew :app:lintDebug` 신규 경고 없음.
- [ ] `grep -rn 'libs.gemini.nano.aicore' android/app/build.gradle.kts` 가 최소 1 (AICore library alias 가 `implementation` 으로 declare 됨 — 기존에 없으면 이 PR 에서 추가).

### 6.7 Cross-ref

- [ ] `EmailPromptBuilder.buildSystemContext(folder="INBOX", ...)` 반환 문자열이 `default_direction: take` 포함.
- [ ] `EmailPromptBuilder.buildSystemContext(folder="SENT", ...)` 반환 문자열이 `default_direction: give` 포함.
- [ ] `QuotedBlockSplitter.split("hello\nOn Mon Dec 18 John wrote:\n> earlier text")` 의 `commitment` 가 `"hello"` (trim 됨), `quoted` 가 `"On Mon Dec 18 John wrote:\n> earlier text"` (또는 trim 된 동등 문자열).
- [ ] `QuotedBlockSplitter.split("> reply body\n> continued")` 의 `commitment` 가 `""`, `quoted` 가 비어있지 않음.
- [ ] `CommitmentExtractionWorker` 가 `EmailBody` 부재 시 `Result.success()` (crash 아님).

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **각 이메일 워커 호출부 교체** — `GmailWorker`, `OutlookMailWorker`, `ImapNaverWorker`, `ImapDaumWorker` 에 `workScheduler.enqueueCommitmentExtraction(rawEventId)` 호출 추가하는 작업. 후속 PR `feat/worker/email-gmail`, `feat/worker/email-outlook`, `feat/worker/email-imap` (#7/#8/#9) 가 담당.
- **`EmailBody` entity** — `body_plain`, `body_html`, `from_address`, `to_address`, `parse_failed`, `attachments_meta`, `raw_headers` 컬럼 정의 및 Room entity 파일 생성은 **`feat/db/email` PR(#1)** 담당. 이 PR 은 entity 가 존재한다고 가정하고 `EmailBodyDao.getByRawEventId()` 호출.
- **`raw_ingestion_events.folder` 컬럼 추가** — `feat/db/email-folder` 담당. 이 PR 은 `rawEvent.folder ?: "INBOX"` 기본값 사용.
- **person_ref 룰 (SENT=To[0], >10=null)** — `refactor/worker/email-person-ref` PR 담당. 이 PR 은 LLM override 를 통해 person_ref 가 재조정되는 경로만 열어둠.
- **AICore 서버 사이드 API (`/v1/email_extract`)** — 스펙상 on-device 고수. api-contract.yml 변경 **없음**.
- **실제 AICore SDK 통합 smoke-test on real device** — Robolectric fake 로 단위 테스트까지만. 실기기 QA 는 별도 manual verification PR.
- **`CommitmentRepository` INSERT 경로 리팩토링** — 기존 `CommitmentDao.insertAll` 사용하여 투명하게 반영. 중복 dedupe / upsert 정책 변경은 별도 PR.
- **Korean sentinel variant** (`'2023년 12월 18일 ... 님이 작성:'`) — QuotedBlockSplitter 는 MVP English-only. Korean 지원은 post-MVP.
- **Prompt A/B 테스트 infra** — 1개 prompt 고정. 여러 prompt 비교 infra 는 post-MVP.
- **LLM output validation / schema guard** — Moshi parse 실패만 Failure 로 처리. 추가 validation (due_at ISO format, direction enum whitelist 등) 은 후속 PR.
- **Sentry / Crashlytics** 통합 — `Timber.w(...)` 로그만. Sentry 연결은 별도 observability PR.
- **Prompt caching (Anthropic-style)** — AICore 는 자체 cache 가 있을 수 있으나 이 PR 에서는 다루지 않음.
- **Privacy / PIPA consent re-check** — AICore 는 on-device 이므로 PIPA invariant `EmailBody 로컬 only` 위반 없음. 별도 PR 없음.

---

## 8. Dependencies

- **Blocked by**:
  - **PR#1 `feat/db/email`** — `EmailBodyEntity` + `EmailBodyDao` 선행. 없으면 Worker 컴파일 불가.
  - **PR#2 `feat/repo/email-body`** (optional) — `EmailBodyRepository` 추상화가 있으면 Worker 가 그쪽을 주입. 없으면 DAO 직접 주입 (이 PR 은 DAO 직접).
  - **이 브랜치의 snippet-builder commit** (`domain-email-snippet-builder.md` 플랜) — `EmailSnippetBuilder`, `MetricsStore` 선행. **같은 브랜치 이전 commit** 이므로 branch 만 올바르면 자동 충족.
- **Blocks**:
  - **PR#7 `feat/worker/email-gmail`** — `GmailWorker` 가 `workScheduler.enqueueCommitmentExtraction(rawEventId)` 호출하려면 이 PR 의 scheduler 메서드가 있어야 함.
  - **PR#8 `feat/worker/email-outlook`** — 동상.
  - **PR#9 `feat/worker/email-imap`** — 동상 (Naver + Daum).
  - **PR#12 `feat/db/email-folder`** — 병렬이지만 folder 컬럼 landing 후에 Worker 의 `rawEvent.folder ?: "INBOX"` fallback 이 제거될 수 있음 (small cleanup PR).

Merge 순서:
1. **PR#1 (EmailBodyEntity)** → merge.
2. **이 브랜치 `feat/domain/email`**:
   - commit 1: `snippet-builder` (domain-email-snippet-builder.md).
   - commit 2: `commitment-extractor` (이 플랜). 둘을 stack 후 단일 PR 로 merge.
3. **PR#7/#8/#9 (이메일 워커 PR)** — 순차 또는 병렬 (서로 다른 워커 파일 → conflict 없음).

병렬 가능 여부:
- `feat/db/email-folder` 와 **병렬 가능** — folder 컬럼은 nullable 확장이므로 기존 코드 호환.
- `refactor/worker/email-person-ref` 와 **병렬 가능** — person_ref 는 LLM override 로 처리되므로 raw event person_ref 개선 PR 과 독립.
- `feat/repo/email-body` 와 **병렬 가능** — 이 PR 은 DAO 직접 주입.

---

## 9. Rollback plan

Git revert 시나리오:

```bash
git revert <commit-sha-commitment-extractor>
```

부작용:
- `CommitmentExtractionWorker` 삭제 → enqueue 호출부가 있는 경우 (후속 PR merge 된 상태) 컴파일 에러. 해결: 후속 PR 도 revert 또는 enqueue 호출을 `// TODO` 로 주석 처리.
- `GeminiNanoExtractor.kt` revert 시 이전 stub 으로 돌아감 — stub 은 `Success(emptyList())` 반환하므로 파이프라인은 "추출 없음" 상태로 graceful degrade. 사용자 관점에서 commitment 가 줄어들지만 crash 없음.
- `res/raw/email_system_prompt.txt` 삭제 — PromptBuilder 도 같이 revert 되므로 자연스러움.
- Room schema 변경 없음 → 데이터 복구 불필요.
- 이미 추출되어 `commitments` 테이블에 저장된 row 는 그대로 유지 (revert 로 삭제하지 않음). 사용자 데이터 손실 없음.

Kill-switch (MVP 에서 미구현):
- 향후 `BuildConfig.ENABLE_EMAIL_EXTRACTION=false` 로 Worker 를 no-op 화할 수 있도록 설계해도 좋으나 이 PR 은 단순화 원칙상 미포함.

---

## Appendix — Session handoff notes

구현 세션 (`feat/domain/email` 브랜치 `commitment-extractor` commit 담당자) 에게 전달할 컨텍스트.

### 결정 근거

- **왜 `GeminiNanoExtractor` 의 signature 를 `extract(entity)` → `extract(systemContext, userContext)` 로 변경하는가?**
  기존 signature 는 RawIngestionEventEntity 에서 직접 prompt 를 유도해야 하는 결합을 만든다. Voice / Email / 미래 source 가 같은 extractor 를 쓸 것이라면 prompt 생성은 caller (source-specific Worker) 에 속하고 Extractor 는 "문자열 → JSON" 변환만 담당하는 게 올바른 책임 분리. 단점: voice 쪽에서 기존 경로가 있다면 break — 현재 voice 는 서버 `/v1/voice/transcribe_extract` 를 쓰므로 on-device extractor 를 voice 에도 쓸 계획이 있다면 voice prompt builder 를 별도 PR 에서 만들면 됨. 이 PR 은 email 만 커버.

- **왜 `CoroutineWorker` 인가 (일반 Worker 가 아닌)?**
  AICore 호출은 suspend fn 이며 long-running (수 초 단위) → CoroutineWorker 가 자연. DAO 호출도 suspend. `setExpedited` + `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` 로 quota 부족 시 자동 fallback.

- **왜 `ExistingWorkPolicy.APPEND_OR_REPLACE` 인가?**
  동일 `rawEventId` 에 대한 재시도는 idempotent 여야 함 (commitments INSERT 는 `client_event_id + rawEventId` 조합으로 서버에서 dedupe). 중복 enqueue 시 이전 작업 완료까지 대기 (APPEND) 또는 교체 (REPLACE). REPLACE 단독은 이전 진행중 작업을 잃을 위험 → APPEND_OR_REPLACE 가 안전.

- **왜 sentinel-first, `^>`-second 인가?**
  `On ... wrote:` sentinel 은 명확한 마커로 false positive 가 거의 없다. `^>` 는 잘못 포맷된 본문에서도 나타날 수 있어 잘못 분리할 위험. sentinel 이 먼저 매칭되면 그 아래 `^>` 는 모두 quoted 에 자연스럽게 포함 → 순서 상 올바름.

- **왜 `MANUAL` / `CALL_RECORDING` enum 상수를 건드리지 않는가?**
  별도 PR (`feat/db/voice/call-recording-enum` 과 유사) 이 enum 정리 담당. 이 PR 은 email commitment 추출 기능만.

- **왜 `res/raw/email_system_prompt.txt` 로 template 을 외부화하는가?**
  프롬프트 문구는 LLM 출력 품질에 결정적 — `git log -p res/raw/email_system_prompt.txt` 로 audit 가능해야 regression 원인 추적이 가능. 코드 상수로 두면 diff 가 Kotlin raw string 안에 섞여 PR 리뷰가 불편. 대안: `assets/` 하위 → 제거. `res/raw/` 선택 이유: Android 리소스 compile 로 runtime lookup 이 간단 (`context.resources.openRawResource(R.raw.email_system_prompt)`), ProGuard 안정.

### 함정

- **AICore SDK 가 minSdk / device-specific gated** — 모든 Pixel 또는 Samsung 기기에서 동작하지 않음. `AICore.isAvailable()` (정확한 API 명은 SDK 문서 확인) 호출 후 unavailable 이면 `ExtractorUnavailable(AICORE_NOT_AVAILABLE)` 즉시 반환. Worker 는 `Result.success()` 로 no-op 처리 (retry 무의미).

- **`GenerativeModel.generateContent(prompt)` 는 streaming 과 non-streaming 두 모드** — 이 PR 은 **non-streaming** (`generateContent` suspend fn, 전체 응답 대기). streaming 은 UI 피드백이 필요한 곳에서만. Worker 에서는 batch 처리가 자연.

- **AICore response format** — SDK 가 plain string 을 반환하므로 JSON parse 를 caller 가 수행. 프롬프트에 "Return a JSON array" 를 명시해도 LLM 이 prefix/suffix 를 덧붙일 수 있음 → Moshi parse 실패 시 `substring` 으로 `[` … `]` 구간만 추출하는 방어 로직 고려 (out of scope 이지만 함정 인지).

- **Gmail `snippet` 에는 quoted text 가 이미 포함된 경우가 많음** — 이 PR 은 `rawEvent.eventSnippet` 을 프롬프트에 그대로 넣으므로 quoted 오염 가능. 그러나 `quoted_text` 섹션에 별도 분리된 quoted 가 들어가므로 LLM 은 context 우선순위를 학습 가능. 초기 품질 문제가 발생하면 `snippet` 필드 자체를 omit 하는 변형 PR.

- **Worker 가 `folder=null` 인 raw event 를 만났을 때** — INBOX default 가정으로 진행. `feat/db/email-folder` merge 후에는 null 이 불가능해짐. 이 PR 에서는 `rawEvent.folder ?: "INBOX"` 한 줄.

- **`EmailSnippetBuilder.buildSnippet` 을 Worker 에서 재호출하는 것은 200자 cap 적용** — 즉 LLM 이 풀 바디가 아닌 200자만 본다. 이는 MVP 품질 한계 — EMAIL-003 이 snippet 을 200자로 정의한 것과 별개로 LLM 프롬프트 본문은 더 길어야 함. 향후 `EmailSnippetBuilder.stripHtmlOnly(bodyHtml)` 같은 non-capped variant 를 추가하는 후속 PR 필요. 이 PR 에서는 200자 cap 을 수용 (문서화만).

- **`FakeGeminiNanoExtractor` 테스트용 구현** — 인터페이스를 추출하는 것을 권장: `interface IGeminiNanoExtractor { suspend fun extract(...): BecalmResult<...> }`. 그러나 현 코드베이스가 인터페이스-없는 `class` 패턴을 쓰면 (기존 Extractor 스타일) `open class` + override 로 fake 만들기. 기존 스타일 확인 후 결정.

### 검토한 대안

1. **Server-side `/v1/email_extract` endpoint** — 기각. PIPA invariant (EmailBody 로컬 only) 위반. api-contract.yml 변경 필요 → 앱 혼자 풀 수 없음.
2. **Gemini API (cloud) via Google AI Studio** — 기각. 본문 업로드 필연 → PIPA 위반.
3. **On-device TFLite + 직접 prompt 처리** — 보류. AICore 가 미지원 기기에서의 backup 으로 이후 PR 에서 검토 가능. MVP 는 AICore 만.
4. **Worker 가 EmailSnippetBuilder 를 재호출하지 않고 EmailBody.body_plain 을 그대로 LLM 에 전달** — 채택 고려. 단, body_html 케이스를 처리하려면 별도 HTML-strip 경로 필요 → snippet builder 재호출로 200자 cap 수용이 MVP 단순화에 유리. 이 PR 은 snippet builder 재호출.
5. **Prompt template 을 Kotlin 상수** — 기각. 위의 "왜 res/raw 인가" 참조.
6. **`QuotedBlockSplitter` 를 `EmailSnippetBuilder` 에 병합** — 기각. 책임이 다름 (snippet=200자 preview, splitter=LLM context 분리). 단위 테스트 독립성도 유지.
