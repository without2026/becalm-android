# Domain / Email / snippet-builder — event_snippet 폴백 체인 + Jsoup HTML→plain + subject-only 메트릭

**Branch**: `feat/domain/email` (첫 번째 commit — logic slug `snippet-builder`)
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Email ingestion (Gmail/Outlook/IMAP → Room)
**Severity**: High (4개 이메일 워커가 전부 snippet 스펙 위반, LLM 추출 선행조건 깨짐)
**Type**: Gap (fallback chain + Jsoup dep 부재) + Drift (KDoc promises email-body semantics not delivered)

---

## 1. Finding

이메일 파이프라인의 `event_snippet` 은 스펙상 **body_plain[:200] → Jsoup.parse(body_html).text()[:200] → subject[:200]** 의 3단 폴백 체인을 거쳐야 하며, 연속 공백 1개 정리 후 200자 truncate, subject fallback 시 `email_subject_only_skipped` DataStore 메트릭을 +1 증가시켜야 한다. 현재 4개 이메일 워커(Gmail / Outlook / Naver IMAP / Daum IMAP)는 각기 다른 임시 구현을 쓴다 — Gmail은 Google-generated `snippet` 원문을 그대로 저장하고, IMAP 계열은 `ImapClientImpl.extractBodyPreview` 의 200자 truncate 만 수행하며 (a) HTML stripping 없음 (b) 공백 정리 없음 (c) subject fallback 없음 (d) metric 없음. Jsoup 의존성도 `libs.versions.toml` · `app/build.gradle.kts` 양쪽 모두 부재. 이로 인해 EMAIL-003 / EMAIL-007(parse failure degrade) / ADAPT-EMAIL-008 모두 구조적으로 위반 상태이고, `RawIngestionEventEntity.kt:116-118` 및 `IngestionDtos.kt:66-68` KDoc 은 "Email: first 200 chars of body" 라고 주장하지만 실제 동작과 일치하지 않는 drift 를 포함한다.

이 PR 은 **snippet 생성 로직을 단일 domain 유닛으로 추출** + **Jsoup 의존성 추가** + **subject-only metric store** 까지만 다룬다. 각 워커의 호출부 교체는 `feat/worker/email-*` 후속 PR 이 담당한다.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/email-pipeline.spec.yml:31-37`** — `EMAIL-003`:
  > "event_snippet은 body_plain 앞 200자(공백 정리 후)로 고정한다. HTML-only 메일은 Jsoup으로 stripping → plain 추출 → 200자 truncate. body_plain/body_html 모두 비어 있는 경우(제목-only 메일) event_snippet은 subject 앞 200자로 대체 — LLM 추출은 건너뛰고 raw event만 mirror (commitments_extracted_count=0)"

- **`.spec/email-pipeline.spec.yml:36`** — `EMAIL-003` expected:
  > "body_plain이 있으면 사용. 없고 body_html만 있으면 Jsoup.parse(html).text() 결과 사용. 둘 다 비면 subject로 fallback 후 CommitmentExtractionWorker 호출 생략 — DataStore metric email_subject_only_skipped +=1로 모니터링. 모든 경우 앞 200자로 truncate + 연속 공백 1개로 정리"

- **`.spec/email-pipeline.spec.yml:67-73`** — `EMAIL-007` (parse failure graceful degrade):
  > "HTML 파싱 실패(잘못된 HTML / charset mismatch / Jsoup timeout)는 raw event를 quarantine으로 넘기지 않고 event_snippet=subject로 graceful degrade한다. Sentry에만 parse_failure 이벤트 기록"
  > "raw_ingestion_events 정상 INSERT(event_snippet=subject 앞 200자), EmailBody.body_plain=null + body_html=원본 보존 + parse_failed=true 플래그"

- **`.spec/data-ingestion.spec.yml:65`** — `ING-006` Gmail expected:
  > "Room raw_ingestion_events 1건(source_type='gmail', …, event_snippet=본문앞200자, …) + EmailBody 1건 INSERT됨"

- **`.spec/data-ingestion.spec.yml:74`** — `ING-007` Outlook expected: `event_snippet=본문앞200자` (동일 계약).

- **`.spec/data-ingestion.spec.yml:83`** — `ING-008` Naver IMAP expected: `event_snippet=본문앞200자` (동일 계약).

- **`.spec/contracts/api-contract.yml`** — 관련 없음. `/v1/email_*` 또는 `/v1/email_extract` 엔드포인트 부재 (on-device 추출이므로 snippet builder 는 완전 client-local 로직).

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Jsoup 의존성 부재

- **`android/gradle/libs.versions.toml`** (175 lines) — `grep -i jsoup` → 0 matches. 근접 자료로 `jakarta-mail = "1.6.7"` 과 `angus-mail` (내부 org.jvnet 의 `com.sun.mail:android-mail`) 정도가 전부 — HTML 파싱 라이브러리 없음.

- **`android/app/build.gradle.kts`** (187 lines) — `grep -i jsoup` → 0 matches.

검증 grep:
```bash
grep -rn "jsoup\|Jsoup" android/gradle/libs.versions.toml android/app/build.gradle.kts
# 0 matches
```

### 3.2 snippet 생성 로직의 4가지 divergent 구현

- **`android/app/src/main/java/com/becalm/android/worker/email/GmailWorker.kt:306`** — `eventSnippet = snippet` 으로 Google-generated preview 를 그대로 저장. body_plain → subject fallback chain 없음, HTML strip 없음, whitespace collapse 없음.

- **`android/app/src/main/java/com/becalm/android/worker/email/ImapNaverWorker.kt:245`** — `eventSnippet = bodyPreview`. `bodyPreview` 는 `ImapClientImpl.extractBodyPreview:342-349` 에서 생성되며 200자 plaintext truncate 만 수행.

- **`android/app/src/main/java/com/becalm/android/data/remote/email/ImapClientImpl.kt:342-349, 364-367`** — text/plain 부분만 substring 하고, text/html 부분은 raw HTML 태그 포함하여 그대로 반환. whitespace collapse 없음, subject fallback 없음, parse_failed 플래그 없음.

- **`android/app/src/main/java/com/becalm/android/worker/email/OutlookMailWorker.kt:179`** — `eventSnippet = bodyPreview?.take(200)`. Microsoft Graph `bodyPreview` 에 의존 — HTML/plain 구분 없음, 공백 정리 없음.

### 3.3 DataStore metric 부재

- `android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt` 및 `SyncCursorStore.kt`, `DataStoreEdit.kt` 어디에도 `email_subject_only_skipped` 키 없음.

검증 grep:
```bash
grep -rn "email_subject_only_skipped\|subjectOnlySkipped" android/app/src/main/java/
# 0 matches
```

### 3.4 KDoc drift (현재 문구가 실제 동작과 어긋남)

- **`android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt:116-118`** — KDoc: "Email: first 200 chars of body" (실제: Gmail 은 `snippet`, Outlook 은 `bodyPreview?.take(200)`, IMAP 은 text/html 원문도 섞인 200자).

- **`android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt:66-68`** — 동일한 KDoc 문구 drift.

검증 grep:
```bash
grep -rn "first 200 chars of body" android/app/src/main/java/
# 2 matches (RawIngestionEventEntity.kt, IngestionDtos.kt)
```

### 3.5 테스트 부재

- `android/app/src/test/java/com/becalm/android/domain/email/` 디렉토리 없음. `grep -rn "EmailSnippet\|SnippetResult" android/app/src/test/` → 0 matches.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| body_plain → body_html → subject 폴백 체인 | 3단 폴백 필수 | 각 워커가 단일 경로만 사용 (Gmail=snippet, IMAP=bodyPreview, Outlook=bodyPreview?.take(200)) | 공통 builder 로 추출 필요 |
| HTML stripping | Jsoup.parse(html).text() 사용 | Jsoup dep 부재, text/html 원문이 snippet 에 유입 가능 | Jsoup 추가 + builder 내 사용 |
| Whitespace collapse | `\s+` → 단일 공백 | 없음 | `replace(Regex("\\s+"), " ").trim()` 필수 |
| Truncate | 앞 200자 고정 | Gmail 은 제한 없이 Google preview 사용 (200자 보장 안 됨) | take(200) 통일 |
| subject-only 경로 | metric `email_subject_only_skipped += 1` | metric 키 자체 없음 | DataStore preference key 추가 |
| parse 실패 degrade | subject fallback + parse_failed=true | 예외 시 워커 중단 가능성 있음 (snippet builder 없으므로 각 워커가 다르게 처리) | `SnippetResult.parseFailed` 반환 + 호출부가 EmailBody 에 반영 |
| KDoc 정확성 | "event_snippet = body_plain 앞 200자(공백 정리 후)" 기술 | "first 200 chars of body" (공백 정리·HTML strip 미언급) | builder 랜딩 이후 KDoc 업데이트 |
| 테스트 | table-driven 경계 케이스 | 0건 | 신규 `EmailSnippetBuilderTest` |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 이 PR 은 builder 유닛 + dep + metric store 까지만 다룬다.

### 5.1 Files to change

- **`android/gradle/libs.versions.toml`**
  - `[versions]` 섹션에 `jsoup = "1.17.2"` 추가 (기존 `jakarta-mail` 근처에 묶는 것을 권장 — 이메일 섹션 응집도 유지).
  - `[libraries]` 섹션에 `jsoup = { group = "org.jsoup", name = "jsoup", version.ref = "jsoup" }` 추가 (Jakarta Mail 라이브러리 블록 아래에 같은 `# ─── Email …` 섹션 주석 추가하여 일관성 유지).

- **`android/app/build.gradle.kts`**
  - `dependencies { }` 블록에 `implementation(libs.jsoup)` 한 줄 추가 (기존 `implementation(libs.jakarta.mail)` 근처). Instrumentation / test 의존성으로는 추가하지 않음 — runtime 용만 필요.

- **`android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt:116-118`**
  - KDoc 문구를 실제 builder 계약에 맞게 업데이트: "Email: body_plain 앞 200자 (없으면 Jsoup으로 stripped body_html, 없으면 subject 앞 200자), 연속 공백 1개로 정리. See `EmailSnippetBuilder` & spec `EMAIL-003`."

- **`android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt:66-68`**
  - 위와 동일한 KDoc 문구로 converge. Entity ↔ DTO 간 drift 없애기.

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/domain/email/EmailSnippetBuilder.kt`** — 순수 domain 유닛 (Hilt DI 필요 없음, `object` 또는 `class` + `@Inject` 중 기존 유사 util 패턴을 따른다; `QuotedBlockSplitter`(#11) 와 네이밍 / 접근자 합의).
  - 공개 API:
    - `public data class SnippetResult(val snippet: String, val sourceKind: SourceKind, val parseFailed: Boolean)`
    - `public enum class SourceKind { PLAIN, HTML_STRIPPED, SUBJECT_FALLBACK }`
    - `public fun buildSnippet(bodyPlain: String?, bodyHtml: String?, subject: String?): SnippetResult`
  - 알고리즘 (정확히 이 순서):
    1. `bodyPlain` 이 non-null 이고 `isNotBlank()` → `PLAIN` 경로.
    2. 그렇지 않고 `bodyHtml` 이 non-null 이고 `isNotBlank()` → `Jsoup.parse(html).text()` 결과 사용 (`HTML_STRIPPED`). 예외(`Throwable`) 발생 시 `parseFailed=true` 로 기록하고 subject fallback 으로 낙하.
    3. 그렇지 않으면 `subject.orEmpty()` 을 사용 → `SUBJECT_FALLBACK`.
  - 정리:
    - `.replace(Regex("\\s+"), " ").trim()` 적용 (3개 경로 모두 공통).
    - `.take(200)` 으로 truncate (byte 아님 — code-unit 기준 Kotlin String).
  - 반환: 항상 non-null `snippet` (최악의 경우 빈 문자열 `""`; 호출부가 empty 처리 결정).
  - 예외 처리: `Jsoup.parse(...)` 외의 어떤 예외도 이 클래스가 먹지 않음 — builder 는 side-effect-free.
  - 메트릭 증가는 builder 내부에서 하지 **않음** — `SnippetResult.sourceKind == SUBJECT_FALLBACK` 을 caller 가 보고 `MetricsStore.incrementSubjectOnlySkipped()` 호출. 이유: Android Context·DataStore 주입을 builder 에 섞으면 단위 테스트가 어려워진다 (domain 순수성 보존).

- **`android/app/src/main/java/com/becalm/android/data/local/datastore/MetricsStore.kt`** (신규) OR `UserPrefsStore.kt` 확장 — 택1.
  - 이 PR 은 **신규 `MetricsStore` 를 권장**. 이유: `UserPrefsStore` 는 `phone_e164_self` / `display_name_override` 등 **사용자가 쓴 값** holder 로 의미가 고착되어 있는 반면, metric 은 시스템 카운터. 혼재 방지.
  - 공개 API (interface + impl 쌍, 기존 `UserPrefsStore` 스타일에 맞춤):
    - `interface MetricsStore { suspend fun incrementSubjectOnlySkipped(); fun observeSubjectOnlySkipped(): Flow<Int> }`
    - `class MetricsStoreImpl @Inject constructor(private val dataStore: DataStore<Preferences>) : MetricsStore` — 키 `intPreferencesKey("email_subject_only_skipped")`, 초기값 0, `edit { prefs -> prefs[KEY] = (prefs[KEY] ?: 0) + 1 }` 패턴 (이미 `SyncCursorStore` 에 유사 패턴 존재할 것 — 일관성 확인 필요).
    - Hilt `@Binds` 는 기존 `DataStoreModule` (또는 해당 모듈 이름) 에 등록.
  - `observeSubjectOnlySkipped()` 는 debug surface (추후 settings 화면 또는 Sentry breadcrumb) 용. 이 PR 에서 UI 소비자는 추가하지 않음.

- **`android/app/src/test/java/com/becalm/android/domain/email/EmailSnippetBuilderTest.kt`** — JUnit4 + table-driven. Robolectric 불필요 (순수 domain; Jsoup 은 JVM 에서 동작).
  - 테스트 케이스 테이블(모든 이름은 backtick Kotlin fun 이름):
    - `` `body_plain present → PLAIN source, truncated to 200` ``
    - `` `body_plain null, body_html present → HTML_STRIPPED source, tags removed` ``
    - `` `body_plain blank (whitespace only), body_html present → HTML_STRIPPED` ``
    - `` `body_plain null, body_html null, subject present → SUBJECT_FALLBACK` ``
    - `` `all inputs null or blank → SUBJECT_FALLBACK with empty string, sourceKind=SUBJECT_FALLBACK` ``
    - `` `whitespace collapse: multiple spaces, tabs, newlines → single spaces` ``
    - `` `whitespace collapse happens BEFORE truncate at 200-char boundary` `` — 입력: 공백이 많은 250자 plain → 결과 길이 = 200 (collapse 된 결과 기준).
    - `` `truncate exact 200-char boundary (plain at 200 chars stays intact)` ``
    - `` `truncate exact 201-char boundary → length 200` ``
    - `` `malformed HTML in body_html → parseFailed=true, falls through to SUBJECT_FALLBACK` `` — Jsoup 이 실제로 예외를 던지도록 하려면 `Jsoup.parse(null as String)` 같은 NPE 를 유발하는 입력을 준비하거나, mockk 로 `Jsoup.parse` 정적 호출을 가로채서 throw 강제. mockk 정적 목 방식이 안정적.
    - `` `HTML with <script> and <style> tags → text only, no JS/CSS content` ``
    - `` `HTML entities (&amp;, &lt;) → decoded in output` ``
    - `` `nested quoted HTML blockquote → text extracted regardless (splitter handles quoted separation later)` ``

### 5.3 Files to delete (dead code)

없음. 현재 워커 호출부(`Gmail/ImapNaver/ImapDaum/Outlook` 의 snippet 생성 라인들)는 이 PR 에서 **건드리지 않는다** — 후속 워커 PR 이 builder 호출로 교체. 이 PR 내에서 dead 가 되는 코드 없음.

### 5.4 Non-code changes

- **DB migration**: 없음. `raw_ingestion_events.event_snippet` 컬럼은 이미 `TEXT` 이고 길이/제약 변경 없음.
- **Permission**: 없음. Jsoup 은 순수 Java/Kotlin 라이브러리, manifest 권한 요구 없음.
- **Config / manifest**: 없음.
- **Proguard / R8**: Jsoup 1.17.2 는 내부적으로 reflection 을 거의 쓰지 않으나, 만약 릴리즈 빌드에서 `org.jsoup.**` 경고가 나오면 `android/app/proguard-rules.pro` 에 `-keep class org.jsoup.** { *; }` 는 **보류** — 실제 R8 에러가 발생할 때만 후속 PR 에서 처리. 이 PR 은 rule 추가하지 않음.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증** 할 수 있는 항목.

- [ ] **Grep invariant**: `grep -rn 'jsoup' android/gradle/libs.versions.toml` 이 최소 2 matches (versions alias 1 + library alias 1).
- [ ] **Grep invariant**: `grep -rn 'libs.jsoup' android/app/build.gradle.kts` 가 최소 1 match.
- [ ] **Grep invariant**: `grep -rn 'class EmailSnippetBuilder\|object EmailSnippetBuilder' android/app/src/main/java/` 가 1 match.
- [ ] **Grep invariant**: `grep -rn 'email_subject_only_skipped' android/app/src/main/java/` 가 최소 2 matches (DataStore key 정의 1 + 참조 1).
- [ ] **Grep invariant**: `grep -rn 'SUBJECT_FALLBACK\|HTML_STRIPPED\|PLAIN' android/app/src/main/java/com/becalm/android/domain/email/EmailSnippetBuilder.kt` 가 최소 3 matches (enum 상수 3종).
- [ ] **Grep invariant**: `grep -rn 'first 200 chars of body' android/app/src/main/java/` 가 **0** (드리프트 문구 제거 확인 — KDoc 교체됨).
- [ ] **Grep invariant**: `grep -rn 'Jsoup.parse' android/app/src/main/java/com/becalm/android/domain/email/EmailSnippetBuilder.kt` 가 1 match.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin` 성공.
- [ ] **Lint gate**: `./gradlew :app:lintDebug` 에서 신규 경고 없음.
- [ ] **Unit test — exists**: `EmailSnippetBuilderTest` 파일이 `android/app/src/test/java/com/becalm/android/domain/email/EmailSnippetBuilderTest.kt` 경로에 존재.
- [ ] **Unit test — count**: `grep -c "^\s*@Test" android/app/src/test/java/com/becalm/android/domain/email/EmailSnippetBuilderTest.kt` 가 11 이상 (위 테이블 케이스 수).
- [ ] **Unit test — 실행**: `./gradlew :app:testDebugUnitTest --tests "*EmailSnippetBuilderTest*"` 전원 green.
- [ ] **Unit test — metric**: `MetricsStoreTest` (또는 `UserPrefsStoreTest` 확장) 에 `incrementSubjectOnlySkipped twice → observeSubjectOnlySkipped emits 2` 테스트 통과.
- [ ] **Cross-ref**: `EmailSnippetBuilder.buildSnippet(bodyPlain=null, bodyHtml=null, subject="x")` 가 `SnippetResult(snippet="x", sourceKind=SUBJECT_FALLBACK, parseFailed=false)` 반환 (단위 테스트로 검증).
- [ ] **Cross-ref**: `EMAIL-003` 의 "앞 200자 + 연속 공백 1개" 규칙이 `whitespace collapse happens BEFORE truncate` 테스트로 입증.
- [ ] **KDoc drift 제거**: `RawIngestionEventEntity.kt:116-118` 과 `IngestionDtos.kt:66-68` KDoc 내용이 일치 (diff 가 0). `diff <(sed -n '116,118p' android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt) <(sed -n '66,68p' android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt)` 는 완전 동일 혹은 spec reference 만 유사 (최소 "EmailSnippetBuilder" 문자열 공통).

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **워커 호출부 교체** — `GmailWorker`, `OutlookMailWorker`, `ImapNaverWorker`, `ImapDaumWorker` 에서 기존 snippet 생성 라인을 `EmailSnippetBuilder.buildSnippet(...)` 호출로 교체하는 작업. 이는 후속 PR `feat/worker/email-*` 시리즈 (PR#7/#8/#9) 가 담당. 이유: 워커 교체는 EmailBody entity (#1) 에 의존 — `bodyPlain` / `bodyHtml` 을 어디서 읽어올 것인지는 entity 스키마 landing 이후 결정.
- **`CommitmentExtractionWorker` 호출 gate** — subject-only 메일은 LLM 추출을 건너뛰는 로직. 이는 #11 (CommitmentExtractionWorker PR) 이 담당.
- **`EmailBody.parse_failed` 플래그 저장** — EmailBody entity (#1 `feat/db/email` PR) 가 선행. 이 PR 은 builder 가 `parseFailed` 를 반환하기만 함; 저장은 워커 교체 PR 몫.
- **Sentry / Crashlytics** 에 `email_html_parse_failed` 이벤트 기록 — observability PR (별도 backlog).
- **`QuotedBlockSplitter`** — `feat/domain/email` 브랜치 같은 stack 의 다음 commit (#11 CommitmentExtractor plan 참조).
- **`EmailPromptBuilder`** — 동상. #11 에서 다룸.
- **`MANUAL` / `CALL_RECORDING` 등 다른 enum 상수** — `feat/db/voice/call-recording-enum` 와 별도 PR.
- **R8 / Proguard rules** — Jsoup 릴리즈 빌드 회귀가 실제로 발생한 이후 별도 PR.
- **`/v1/email_extract` API 신설** — api-contract.yml 이 on-device 추출을 전제하므로 이 PR 은 server 측 변경 없음.
- **Daum IMAP 관련 신규 코드** — Daum 은 현재 Naver 와 동일한 `ImapClientImpl` 을 공유하므로 이 PR 의 builder 는 자동으로 Daum 도 커버 (호출부 교체는 후속 PR).

---

## 8. Dependencies

- **Blocked by**: 없음. `main` 에서 바로 분기 가능 — 이 PR 은 어떤 entity · worker · DAO 에도 의존하지 않는 순수 domain unit.
- **Blocks**:
  - **PR#11 `feat/domain/email` — commit `commitment-extractor`** (같은 브랜치 다음 commit): `CommitmentExtractionWorker` 는 `EmailSnippetBuilder` 가 반환하는 `SnippetResult.sourceKind == SUBJECT_FALLBACK` 을 LLM 호출 skip gate 로 사용. 또한 `MetricsStore.incrementSubjectOnlySkipped()` 도 동일한 caller 에서 호출.
  - **PR#7 `feat/worker/email-gmail`**: `GmailWorker.toEntity:306` 이 `eventSnippet = EmailSnippetBuilder.buildSnippet(...).snippet` 로 교체 필요.
  - **PR#8 `feat/worker/email-outlook`**: `OutlookMailWorker.kt:179` 교체.
  - **PR#9 `feat/worker/email-imap`**: `ImapNaverWorker.kt:245`, `ImapDaumWorker` 교체.

Merge 순서 권장:
1. 이 PR (brach `feat/domain/email` commit `snippet-builder`) merge 또는 review 대기 중에도 #11 의 구현은 동일 브랜치에서 stack 가능 (commit 단위).
2. #1 `feat/db/email` (EmailBody entity) 와 **병렬 가능** — 파일 겹침 없음.
3. #7/#8/#9 워커 PR 들은 이 PR merge **이후** 진행.

병렬 가능 여부: `feat/db/email`, `feat/db/email-folder`, `refactor/worker/email-person-ref` 와 파일 겹침 없음 → 완전 병렬 가능.

---

## 9. Rollback plan

단순 revert 로 원복 가능:

```bash
git revert <commit-sha>
```

부작용:
- `EmailSnippetBuilder` / `MetricsStore` 삭제 시, 이 PR 의뢰에 따라 호출부가 없으므로 회귀 없음.
- `libs.versions.toml` / `build.gradle.kts` 에서 jsoup alias 제거 시 `./gradlew :app:assembleDebug` 성공 (미사용 의존성 제거).
- KDoc revert 는 drift 를 되살리지만 기능 영향 없음.

데이터 복구 전략: 없음 (schema 변경 없음).

---

## Appendix — Session handoff notes

구현 세션 (`feat/domain/email` 브랜치 snippet-builder commit 담당자) 에게 전달할 컨텍스트.

### 결정 근거

- **왜 `MetricsStore` 를 새로 만들고 `UserPrefsStore` 에 안 섞는가?**
  `UserPrefsStore.kt` 는 `phone_e164_self`, `display_name_override` 같이 사용자가 settings 에서 편집하는 값의 저장소로 의미가 굳어져 있다. Metric 은 시스템 카운터 — write 주체가 다르다. 혼재 시 settings 초기화 vs metric 리셋의 의미 분리가 어려워진다. 기존 `SyncCursorStore` 가 sync 전용으로 분리되어 있는 선례와 같은 이유.

- **왜 builder 내부에서 metric 증가를 하지 않는가?**
  `EmailSnippetBuilder` 를 pure domain (Android Context 주입 불필요) 로 유지해야 단위 테스트가 간단 (Robolectric 불필요). 메트릭 증가는 caller (CommitmentExtractionWorker) 가 `SnippetResult.sourceKind` 를 보고 결정 — 테스트 복잡도가 Worker 쪽으로 이동하지만, Worker 는 어차피 Robolectric 테스트이므로 부담 증가 없음.

- **왜 Jsoup 1.17.2?**
  1.17.2 는 2024-01 릴리즈로 CVE-2022-36033 이후 안정. Android minSdk 이슈 없음 (Jsoup 은 순수 Java, Android support library 의존성 없음). 대안 검토: (a) Android 내장 `Html.fromHtml()` — text 추출 품질 불충분 + Android framework 결합 → 단위 테스트 어려움. (b) KSoup — Kotlin 포팅이지만 maintenance 불확실. Jsoup upstream 이 최선.

- **왜 `parseFailed` 를 `SnippetResult` 에 포함시키는가?**
  EMAIL-007 은 HTML parse 실패 시 (1) subject fallback 적용, (2) `EmailBody.parse_failed=true` 저장을 요구한다. builder 가 `parseFailed` 를 bool 로 반환하면 caller (worker) 가 EmailBody INSERT 시 그대로 복사 가능 — 예외를 builder 밖으로 throw 하지 않아도 호출부가 정확한 side-effect 를 수행할 수 있다.

### 함정

- **Jsoup `parse(null)` 동작** — `Jsoup.parse(null as String?)` 는 `IllegalArgumentException` 을 던진다. builder 는 `bodyHtml.isNullOrBlank()` 로 사전 차단하지만 race/NPE 방어 차원에서 `try/catch (Throwable)` 을 Jsoup 호출 주변에만 좁게 두는 것을 권장. `Throwable` 까지 잡는 이유: Jsoup 1.17.2 가 `StackOverflowError` 를 던진 사례가 있었음 (deeply nested HTML).

- **`take(200)` vs `substring(0, 200)`** — `take(200)` 은 길이가 200 미만이어도 예외 없음. `substring` 은 out-of-bounds 위험. `take` 를 쓸 것.

- **공백 정리 순서** — 반드시 whitespace collapse → trim → take(200). 순서 바뀌면 끝부분에 공백이 남거나 최종 길이가 200 이하로 떨어짐.

- **`Regex("\\s+")` 는 Kotlin raw string 가독성을 위해 `Regex("""\s+""")` 로 써도 동등** — 스타일만 기존 코드베이스 패턴에 맞출 것.

- **KDoc drift 단독 수정 유혹** — EMAIL-010 (KDoc drift) 을 이 PR 에서 먼저 고치려면 "실제 behavior 가 builder landing 이후에 맞춰진다" 는 문구 주석으로 TODO 남겨야 함. 그러나 이 PR 이 builder 를 함께 도입하므로 KDoc 을 **`EmailSnippetBuilder` 참조 형식** 으로 바로 정확하게 쓸 수 있다 (예: `* Email: see [EmailSnippetBuilder.buildSnippet]. body_plain[:200] → Jsoup(html).text()[:200] → subject[:200], whitespace collapsed.`).

### 검토한 대안

1. **`UserPrefsStore` 확장** — 기각. 의미 경계 흐려짐.
2. **builder 를 singleton object** — 채택 고려. 기존 domain util 중 `object` 형태가 있으면 따르고, `class @Inject` 가 다수면 Hilt class 로. 두 스타일 모두 domain 순수성 유지 가능.
3. **`Html.fromHtml`** 을 써서 Jsoup dep 회피 — 기각. android.text.Html 은 `Context` 가 필요 없지만 Android framework 결합 + JVM 단위 테스트 난도 증가 + HTML 태그 처리 품질 하락.
4. **HTML parser 없이 regex 로 태그 strip** — 기각. `<script>`, CDATA, entity decoding 을 regex 로 완벽히 처리 불가 — spec EMAIL-007 의 "charset mismatch / malformed HTML" 모두 커버하려면 DOM parser 필요.
5. **Builder 가 `SnippetResult` 대신 `String` 만 반환** — 기각. subject-fallback 여부를 caller 가 판별해야 하는데 string 만으로는 불가 (빈 문자열로 encode 하면 empty-subject 와 구분 불가). Sealed result type 이 가장 명확.
6. **Builder 내부에서 Sentry breadcrumb emit** — 기각. Sentry 인프라 자체가 현재 연결되어 있지 않으며, domain unit 이 observability 레이어를 건드리면 테스트 순수성 훼손.

### 추가 참고 사항

- **Jsoup API 선택**: `Jsoup.parse(html)` 은 `Document` 를 반환하고 `.text()` 는 전체 텍스트 노드를 공백으로 연결하여 반환. 이후 `replace(Regex("\\s+"), " ").trim()` 으로 normalize. Jsoup 자체가 newline 을 space 로 변환하지만 tab · NBSP 는 그대로 남을 수 있으므로 명시적 regex 가 필요.
- **Kotlin NBSP (`\u00A0`)**: `\s` 메타문자는 JDK regex 에서 NBSP 를 매치 **하지 않는다** (Unicode flag 없을 경우). 한국어 이메일에 `&nbsp;` 가 많으므로 테스트 케이스 하나를 추가할 것을 권장 — `` `NBSP in body_html → collapsed to single space` ``. 필요 시 `Regex("[\\s\\u00A0]+")` 로 확장.
- **DataStore 경로 재사용**: `MetricsStore` 는 기존 `DataStore<Preferences>` 인스턴스를 공유 (`DataStoreModule` 에서 `@Singleton` 제공되는 것). 새 DataStore 인스턴스를 만들지 말 것 — 파일 충돌 위험.
- **테스트에서 Jsoup 정적 목**: `mockk` 의 `mockkStatic(Jsoup::class)` 를 `@Before` 에서 등록하고 `@After` 에서 `unmockkStatic` 으로 해제. 특정 케이스(`malformed HTML`)에서만 `every { Jsoup.parse(any<String>()) } throws RuntimeException()` 으로 대체.
- **Verification helper**: 구현 세션은 `./gradlew :app:dependencies --configuration implementation | grep jsoup` 으로 jsoup 이 실제 runtime classpath 에 포함되는지 검증 가능.

### 워커 교체 PR 에 남기는 인계 사항

`feat/worker/email-*` PR 담당자는 이 PR merge 후 다음을 수행:
1. `GmailWorker.toEntity:306` 의 `eventSnippet = snippet` 을 `eventSnippet = EmailSnippetBuilder.buildSnippet(bodyPlain=<from EmailBody>, bodyHtml=<from EmailBody>, subject=subject).snippet` 로 교체.
2. 동일한 치환을 `OutlookMailWorker.kt:179`, `ImapNaverWorker.kt:245`, `ImapDaumWorker.kt` 에 적용.
3. Worker 는 snippet 생성 후 `SnippetResult.sourceKind == SUBJECT_FALLBACK` 을 `raw_ingestion_events` 저장 외에 `CommitmentExtractionWorker` enqueue gate 로 사용 (#11 PR 참조).
4. `SnippetResult.parseFailed == true` 이면 `EmailBody.parse_failed = true` 로 복사.
이 인계는 본 PR 의 AC 가 아님 (out of scope) — 후속 PR 의 AC 에 포함.
