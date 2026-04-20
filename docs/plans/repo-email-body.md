# Repo / Email / body — `EmailBodyRepository` 신규 + room-only invariant 구조적 강제

**Branch**: `feat/repo/email`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Repository 계층 (Room ↔ domain 경계)
**Severity**: High (EMAIL-006 PIPA invariant 가 "현재 테이블이 없어서 우연히 만족" 상태 — 테이블이 생기는 순간 구조적 가드 없이는 upload 경로로 유실될 위험)
**Type**: Gap (Repository / Hilt binding / DTO invariant test 모두 부재)

---

## 1. Finding

`email_body` 테이블(#1)이 도입되면 EMAIL-006 "`body_plain·body_html·attachments_meta·raw_headers`는 절대 Railway/Supabase 로 전송되지 않음" invariant 를 **구조적으로 강제하는 레이어가 필요**. 현재 코드에는:

1. `EmailBodyRepository` interface + impl 부재 — 워커가 DAO 를 직접 주입받으면 실수로 DTO 경로에 노출 가능.
2. `RawIngestionEventDto` (`IngestionDtos.kt:21-85`) 에 body 관련 필드가 **우연히** 없음 (F-08 가 지적한 accidental compliance). 이후 누군가가 "편의상" body_plain 필드를 추가할 가능성을 컴파일 타임에 막을 가드 없음.
3. Hilt `DataModule` / `RepositoryModule` 에 `EmailBody*` binding 부재.

본 PR 은 **Repository 인터페이스 하나 + impl 하나 + Hilt binding + DTO reflection guard 테스트** 만 담당. DAO/Entity 는 #1 이 제공, 워커 호출은 ADAPT-EMAIL-001..010 별도 PR 에서 주입. "Room 만 쓰고 Railway 는 못 쓴다" 가 타입/테스트로 명시되게 만드는 것이 목표.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/email-pipeline.spec.yml:58-64` — EMAIL-006

> "EmailBody는 Railway/Supabase에 절대 업로드되지 않는다 (data-ingestion invariants 중복 선언). Railway는 raw_ingestion_events.event_snippet(앞 200자)만 받음. 본문 전체는 Room에만 저장되고 30일 retention 정책(4-2)에 따라 synced 이후 자동 삭제"
> "Railway payload에는 event_snippet(≤200자)만 포함. body_plain·body_html·attachments_meta·raw_headers는 절대 전송되지 않음."

### 2.2 `.spec/email-pipeline.spec.yml:79` — invariant (중복 선언)

> "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)"

### 2.3 `.spec/data-ingestion.spec.yml:152` — cross-module invariant

> "Transcript 및 EmailBody는 Railway 또는 Supabase로 업로드되지 않는다 (로컬 only)"

### 2.4 `.spec/contracts/data-model.yml:327-328` — room-only 선언

> "- name: email_body
>     room_only: true
>     # PIPA invariant: 이메일 원문은 온디바이스 전용. Railway/Supabase로 전송 절대 금지."

→ Repository 는 `EmailBodyEntity` 를 받고 돌려주기만 하며, **DTO 로의 매퍼 함수를 노출하지 않음**. DTO↔Entity 매퍼를 아예 작성하지 않는 것이 가드.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Repository 부재

- `android/app/src/main/java/com/becalm/android/data/repository/` 에 `EmailBody*` 파일 **전무**. 현재 구성:
  - `CommitmentRepository(.kt)` + `internal/CommitmentRepositoryImpl.kt`
  - `RawIngestionRepository(.kt)` + `internal/RawIngestionRepositoryImpl.kt`
  - `CalendarEventRepository`, `PersonEnrichmentRepository`, `SourceStatusRepository`

### 3.2 Hilt module

- `android/app/src/main/java/com/becalm/android/core/di/RepositoryModule.kt` (예상 경로) — `EmailBody*` binding 없음.

### 3.3 DTO 우연 compliance

`android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt:21-85` 의 `RawIngestionEventDto` 필드 집합 (F-08):

```kotlin
id, clientEventId, userId, sourceType, sourceRef, personRef,
eventTitle, eventSnippet, durationSeconds, location,
commitmentsExtractedCount, timestamp
```

→ body_plain / body_html / attachments_meta / raw_headers / parse_failed / from_address / to_addresses / raw_email_body 등 EmailBody 에 속하는 어떤 필드도 **현재 없음**. 그러나 컴파일 타임 guard 가 없어 PR 리뷰 누락 시 regression 가능.

### 3.4 검증 grep

```bash
# EmailBody repository 부재 확인
grep -rln "EmailBodyRepository" android/app/src/main/java/   # → 0

# Body 관련 필드가 DTO 에 올라가지 않았는지 확인
grep -rn "body_plain\|body_html\|attachments_meta\|raw_headers\|bodyPlain\|bodyHtml\|attachmentsMeta\|rawHeaders" \
  android/app/src/main/java/com/becalm/android/data/remote/   # → 0 (현재)

# 해당 grep 이 0 을 계속 유지해야 함 (invariant test 가 강제)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| EmailBodyRepository interface | 존재 — insert / getByRawEventId 만 노출 | 부재 | 신규 interface |
| EmailBodyRepositoryImpl | DAO wrap, suspend, dispatcher injection | 부재 | 신규 impl |
| Hilt binding | `@Binds` EmailBodyRepository → Impl | 부재 | RepositoryModule 편집 |
| DTO → Entity 매퍼 | 존재 금지 (room-only) | 현재 없음 (우연) | 영원히 없는 상태 가드 |
| RawIngestionEventDto body 필드 | 부재 — event_snippet 만 허용 | 부재 (accidental) | 컴파일/런타임 invariant test |
| 구조적 room-only 보장 | Repository 가 DTO 타입을 노출·임포트하지 않음 | N/A | architecture enforcement |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/core/di/RepositoryModule.kt`** (또는 현재 `DataModule.kt` — 구현 세션에서 실제 경로 확인)
   - `@Binds @Singleton fun bindEmailBodyRepository(impl: EmailBodyRepositoryImpl): EmailBodyRepository` 추가
   - 다른 Repository binding 스타일과 정렬

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/data/repository/EmailBodyRepository.kt`** — public interface:
   ```kotlin
   interface EmailBodyRepository {
       suspend fun insert(entity: EmailBodyEntity)
       suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity?
       suspend fun markParseFailed(id: String)
   }
   ```
   - **중요**: DTO 타입 (`RawIngestionEventDto` 등) 을 import 하지 않는다. 이것이 room-only 의 아키텍처 가드.
   - KDoc 에 EMAIL-006 / data-ingestion:152 invariant 인용 + "No DTO mapper. This repository must not expose `body_plain`, `body_html`, `attachments_meta`, `raw_headers` to any wire layer." 명시.

2. **`android/app/src/main/java/com/becalm/android/data/repository/internal/EmailBodyRepositoryImpl.kt`** — `@Singleton class` Hilt-injected:
   ```kotlin
   @Singleton
   class EmailBodyRepositoryImpl @Inject constructor(
       private val dao: EmailBodyDao,
       @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
   ) : EmailBodyRepository { ... }
   ```
   - 각 함수는 `withContext(ioDispatcher) { dao.xxx() }` 로 래핑.
   - 다른 Repository impl 과 동일 스타일 (CommitmentRepositoryImpl 참고).
   - **deleteOlderThanForSynced 는 여기서 노출하지 않음** — #3 RetentionSweepWorker 가 DAO 를 직접 사용 (worker 는 원래 DAO 접근이 허용됨 — `VoiceUploadWorker`, `EnrichmentWorker` 등 선례). interface 표면적 최소화.

3. **`android/app/src/test/java/com/becalm/android/data/remote/dto/DtoInvariantTest.kt`** — 반영 기반 unit test (JVM `src/test/`, Robolectric 불필요):
   - **Test 1** `rawIngestionEventDto_doesNotExposeEmailBodyFields()`:
     - `RawIngestionEventDto::class.memberProperties` 에서 `@Json(name=...)` annotation 수집
     - forbidden set: `{"body_plain", "body_html", "attachments_meta", "raw_headers", "from_address", "to_addresses", "parse_failed", "group_email"}`
     - 어느 JSON 키도 forbidden set 과 교집합 ≠ ∅ 이면 `fail()`
   - **Test 2** `rawIngestionEventDto_exactFieldSet()`:
     - allowed set: `{id, client_event_id, source_type, source_ref, person_ref, event_title, event_snippet, duration_seconds, location, commitments_extracted_count, timestamp, folder}` (folder 는 #1 에서 추가)
     - 실제 `@Json(name=...)` 모음이 allowed set 와 정확히 일치하지 않으면 fail (새 필드 추가 시 의도적 리뷰 강제)
   - **Test 3** `emailBodyRepository_doesNotImportDtoTypes()` (선택):
     - `EmailBodyRepository.kt` + Impl 파일을 reflection 이 아닌 파일 텍스트로 읽어 `data.remote.dto` import 가 포함되어 있으면 fail. 이는 런타임 reflection 이 아니라 소스 텍스트 스캔 — 대안: ArchUnit. 단순하게는 resource 로 `.kt` 파일 text 를 읽어 `import com.becalm.android.data.remote.dto` 부재 assert.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes
- Railway `api-contract.yml` **변경 없음**. 본 PR 은 클라이언트 room-only 가드만 추가.
- Supabase DDL 변경 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "EmailBodyRepository" android/app/src/main/java/ | wc -l` ≥ 3 (interface + impl 클래스 선언 + Hilt binding)
- [ ] **Grep invariant**: `grep -rn "com.becalm.android.data.remote.dto" android/app/src/main/java/com/becalm/android/data/repository/EmailBodyRepository.kt android/app/src/main/java/com/becalm/android/data/repository/internal/EmailBodyRepositoryImpl.kt | wc -l` = 0 (DTO import 금지)
- [ ] **Grep invariant**: `grep -rnE "body_plain|body_html|attachments_meta|raw_headers|bodyPlain|bodyHtml|attachmentsMeta|rawHeaders" android/app/src/main/java/com/becalm/android/data/remote/ | wc -l` = 0
- [ ] **Unit test**: `DtoInvariantTest#rawIngestionEventDto_doesNotExposeEmailBodyFields` 통과
- [ ] **Unit test**: `DtoInvariantTest#rawIngestionEventDto_exactFieldSet` 통과 (allowed set 와 완전 일치)
- [ ] **Unit test**: `EmailBodyRepositoryImplTest` — (a) `insert` 호출 시 DAO.insert 1회 호출, (b) `getByRawEventId` 가 DAO 결과를 그대로 반환, (c) `markParseFailed` 가 DAO.markParseFailed(id) 호출
- [ ] **Hilt**: `@HiltAndroidTest` 혹은 `HiltAndroidTestRule` 없이도 DI graph 컴파일 성공 (`./gradlew :app:assembleDebug` 성공)
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Architectural gate (선택)**: `DtoInvariantTest#emailBodyRepository_doesNotImportDtoTypes` — Repository 파일 텍스트에 `import com.becalm.android.data.remote.dto` 없음

---

## 7. Out of Scope

- **`EmailBodyEntity` / `EmailBodyDao`** → #1 `feat/db/email` 담당. 본 PR 은 둘을 참조만 함.
- **Room `email_body` 테이블의 MIGRATION / KSP snapshot** → #1 담당.
- **`RetentionSweepWorker` 의 DELETE 호출** → #3 `feat/worker/retention` 담당. 본 PR 은 `deleteOlderThanForSynced` 를 Repository interface 에 노출시키지 않음 (worker 가 DAO 직접 사용).
- **이메일 워커 (Gmail / OutlookMail / ImapNaver / ImapDaum) 에서 EmailBodyRepository.insert 호출 wiring** → ADAPT-EMAIL-001..010 워커 PR 묶음 담당. 본 PR 은 interface 만 제공.
- **`EmailHtmlParser` (Jsoup) / parse_failed 브랜치 로직** → EXTRACT-EMAIL-005 별도 PR.
- **`RawIngestionEventDto.folder` 필드 추가** → #1 담당 (본 PR 의 DtoInvariantTest allowed set 에 folder 포함은 #1 이 먼저 merge 되었다는 전제).
- **`event_snippet` 필드 의미 변경 (body_plain[:200] 로 converge)** → EXTRACT-EMAIL-004 별도 PR.
- **UI 에서 EmailBody 조회** → UI-EMAIL-010 별도 PR.

---

## 8. Dependencies

- **Blocked by**: #1 `feat/db/email` — `EmailBodyEntity`, `EmailBodyDao` 가 classpath 에 없으면 Repository 컴파일 실패. `RawIngestionEventDto.folder` 필드도 #1 이 추가해야 invariant test 의 allowed set 이 맞음.
- **Blocks**:
  - #3 `feat/worker/retention` — 직접 blocking 은 약하지만 (worker 가 DAO 를 쓰므로 기술적으로는 #1 만 있어도 동작) room-only 가드 없이 worker 가 merge 되면 invariant test 가 regression 에 취약. 순서로는 #1 → #2 → #3 권장.
  - ADAPT-EMAIL-001..010 워커/클라이언트 PR 묶음 — 워커가 `EmailBodyRepository.insert` 를 호출해야 하므로 본 PR 먼저 merge.
- **병렬 가능**: `feat/db/commitment/due-at-hint-approximate` 와 파일 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <merge-commit-sha>
```

Repository 레이어만 추가 — Room 스키마·마이그레이션·DTO wire format 변경 없음. DI graph 는 revert 후 #1 만 남은 상태로 정상 동작 (EmailBody 관련 기능이 아예 비활성). 사용자 데이터 영향 없음. 안전.

---

## Appendix — Session handoff notes

- **왜 Repository 가 필요한가? DAO 직접 쓰면 안 되나?** — 워커는 DAO 직접 사용이 허용된 선례가 있지만 (`VoiceUploadWorker`), **인터페이스를 통해 "DTO 매퍼가 없다" 는 구조적 신호**를 주는 것이 본 PR 의 핵심. 단순 wrapper 처럼 보여도 이는 PIPA invariant 의 compile-time 경계선. 주니어 개발자가 이후 "body_plain 을 Railway 에 보내자" 고 결정했을 때 Repository 에 매퍼가 없고 DTO 에 필드가 없고 invariant test 가 fail 하는 **세 단계 방어선**을 쌓는 것.
- **`@IoDispatcher` qualifier** — 프로젝트 표준 확인 필요. `CommitmentRepositoryImpl` 의 주입 패턴 그대로 복사.
- **`markParseFailed` 는 Repository 에 노출 vs DAO 직접?** — spec EMAIL-007 의 트리거는 `EmailHtmlParser` (EXTRACT-EMAIL-005 별도 PR). 해당 parser 가 Repository 를 받는지 DAO 를 받는지는 별도 결정. 본 PR 에서는 **Repository 에 노출** 하여 "EmailBody 수명주기는 전부 Repository 경유" 일관성 유지 권장. DAO 는 worker (#3) 전용.
- **DtoInvariantTest 를 unit test 가 아닌 androidTest 로 두면?** — reflection 은 JVM 에서 동작하므로 `src/test/` 충분. 실행 빠름.
- **`exactFieldSet` test 가 과도하게 brittle 한가?** — allowed set 이 바뀌는 건 Railway wire 계약 변경이고 이는 **의도적 행위**. 테스트 실패 자체가 리뷰 트리거로 작동 — 이것이 목적.
- **ArchUnit 도입?** — 과도. 단순 파일 텍스트 assert 로 충분. ArchUnit 은 본 프로젝트에 없다 — 새 의존성 추가는 CONSTRAINTS.md 에 따라 정당화 필요.
- **이름 suffix "Impl"**: `CommitmentRepositoryImpl`, `RawIngestionRepositoryImpl` 와 정합. 다른 suffix (`Default`, `Room`) 금지.
- **구현 순서 권장**: (1) interface 작성 → (2) Impl 작성 → (3) Hilt binding → (4) DtoInvariantTest 작성 (실패 상태에서 시작해야 guard 가 의미 있음) → (5) #1 이 merge 되어 folder 필드 추가되면 allowed set 재조정.
