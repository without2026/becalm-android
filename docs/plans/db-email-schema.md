# DB / Email / schema — `email_body` 테이블 신규 + `raw_ingestion_events.folder` 컬럼 추가 (v3→v4)

**Branch**: `feat/db/email`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Room schema (로컬 persistence 계층)
**Severity**: Critical (EMAIL-001..007 전체와 ING-006..008 모두 저장 대상 테이블 부재로 구조적으로 실행 불가)
**Type**: Gap (테이블·컬럼·migration·KSP snapshot 모두 부재)

---

## 1. Finding

스펙은 이메일 파이프라인을 위해 **(a) `email_body` room-only 테이블 14개 컬럼**과 **(b) `raw_ingestion_events.folder` 컬럼(INBOX/SENT 힌트)** 을 요구한다. 현재 Android 코드에는 두 가지가 모두 부재 — `BeCalmDatabase.kt:58-64` 의 `@Database.entities` 에 `EmailBodyEntity` 가 등록되지 않았고, `RawIngestionEventEntity` 에도 `folder` 컬럼이 없다. `Migrations.kt:72` 는 MIGRATION_2_3 까지만 보유하고 MIGRATION_3_4 는 존재하지 않으며 `app/schemas/` KSP snapshot 디렉토리 자체가 부재이다.

결과적으로 EMAIL-001 (folder hint), EMAIL-002 (person_ref = INBOX→From / SENT→To[0]), EMAIL-003 (body_plain 200자), EMAIL-004 (attachments_meta), EMAIL-005 (raw_headers), EMAIL-006 (room-only invariant), EMAIL-007 (parse_failed degrade) 가 **저장소 차원에서 실행 불가능**. 본 PR 은 스펙의 데이터 모델만 Room 에 착지시키고, 저장/조회 로직과 워커 변경은 범위 밖 (#2, #3 에서 처리).

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/data-model.yml:327-390` — `email_body` 테이블 정의

```yaml
- name: email_body
  room_only: true
  # PIPA invariant: 이메일 원문은 온디바이스 전용. Railway/Supabase로 전송 절대 금지. 30일 retention sweep 대상.
  columns:
    - name: id
      type: uuid
      primary: true
      nullable: false
      default: gen_random_uuid()
    - name: raw_event_id
      type: uuid
      nullable: false
      # Room raw_ingestion_events.id FK (로컬 내 FK). Supabase 미러에는 존재하지 않음
    - name: provider_message_id
      type: text
      nullable: false
      # Gmail messageId / Microsoft Graph id / IMAP UIDVALIDITY+UID
    - name: folder
      type: text
      nullable: false
      # enum: INBOX | SENT. EMAIL-002의 person_ref 결정 및 EMAIL-001의 direction 힌트에 사용
    - name: subject         # nullable text
    - name: from_address    # lowercase normalized
    - name: to_addresses    # JSON array of {email, name?}
    - name: body_plain
    - name: body_html
    - name: attachments_meta   # JSON [{filename, mime, size_bytes}]
    - name: raw_headers        # In-Reply-To, References 원형 보존
    - name: parse_failed       # boolean, default false
    - name: group_email        # boolean, default false (To>10)
    - name: received_at        # timestamptz, NOT NULL
  indexes:
    - columns: [raw_event_id]
      type: btree
    - columns: [provider_message_id]
      type: btree
```

### 2.2 `.spec/email-pipeline.spec.yml:15-18` — EMAIL-001 folder hint

> "direction 사전 힌트를 source_type에 붙이지 않고 raw event 레벨 메타(folder)로 전달한다. …
> EmailBody.folder IN ('INBOX','SENT'), raw_ingestion_events.sync_status='pending'"

→ `raw_ingestion_events.folder` 컬럼이 필요 (email source_types 에만 의미 있음, 다른 source 에서는 NULL).

### 2.3 `.spec/email-pipeline.spec.yml:58-64` — EMAIL-006 room-only

> "EmailBody는 Railway/Supabase에 절대 업로드되지 않는다 … body_plain·body_html·attachments_meta·raw_headers는 절대 전송되지 않음. 30일 경과 + sync_status='synced' 조건 만족 시 RetentionSweepWorker가 EmailBody와 raw_ingestion_events를 함께 DELETE"

→ FK `onDelete = CASCADE` 가 spec 의 co-delete 요구를 구조적으로 뒷받침.

### 2.4 `.spec/data-ingestion.spec.yml:152, 160` — invariants

> (152) "Transcript 및 EmailBody는 Railway 또는 Supabase로 업로드되지 않는다 (로컬 only)"
> (160) "Room raw_ingestion_events와 EmailBody는 timestamp 기준 30일 rolling window로 자동 삭제된다 — 단 sync_status='synced' 조건을 만족할 때만."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt:58-67`

```kotlin
@Database(
    entities = [
        RawIngestionEventEntity::class,
        CommitmentEntity::class,
        CalendarEventEntity::class,
        PersonEnrichmentEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
```

→ `EmailBodyEntity::class` 부재. DAO accessor `emailBodyDao()` 부재.

### 3.2 `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt:92`

```kotlin
public const val DATABASE_VERSION: Int = 3
```

→ version 4 로 bump 필요.

### 3.3 `android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt:72`

```kotlin
public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
```

→ `MIGRATION_3_4` 부재.

### 3.4 `android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt:40-50`

`@Entity(tableName = "raw_ingestion_events", indices = [...])` — `folder` 컬럼 없음. 3개 인덱스만 선언.

### 3.5 `android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt:21-85`

`RawIngestionEventDto` — 12 필드, `folder` 없음. email worker 가 Room 에 folder 를 쓴다고 해도 Railway payload 에는 포함시켜 upload 해야 direction 힌트 전파 (EMAIL-001).

### 3.6 `app/schemas/` — 디렉토리 자체 부재

`app/build.gradle.kts` 의 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` 설정 존재 여부도 검증 필요 — 없다면 4.json 이 생성되지 않는다.

### 3.7 검증 grep

```bash
# EmailBody 관련 심볼 0건 확인
grep -rn "EmailBody\|email_body" android/app/src/main/java/ | wc -l   # → 0

# folder 컬럼 0건 확인
grep -rn "\"folder\"\|@ColumnInfo(name = \"folder\")" android/app/src/main/java/ | wc -l   # → 0

# MIGRATION_3_4 부재 확인
grep -rn "MIGRATION_3_4" android/app/src/main/java/   # → 0
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| `email_body` 테이블 | 14 컬럼 + 2 btree index + room_only | 부재 | 신규 테이블 |
| FK `raw_event_id` | `raw_ingestion_events.id` 로컬 FK, onDelete 암시 (EMAIL-006 co-delete) | 부재 | `@ForeignKey(onDelete=CASCADE)` |
| `raw_ingestion_events.folder` | email source 에 INBOX\|SENT 힌트 | 부재 | 컬럼 추가 (NULL 허용, app-level NOT NULL for email) |
| `RawIngestionEventDto.folder` | email upload 시 Railway 로 힌트 전파 | 부재 | DTO 필드 추가 |
| Room DATABASE_VERSION | ≥ 4 | 3 | bump (both const AND `@Database(version=...)` inline literal — KSP2 ksp#2439) |
| MIGRATION_3_4 | CREATE TABLE email_body + 2 CREATE INDEX + ALTER raw_ingestion_events ADD folder | 부재 | 신규 Migration 객체 |
| KSP schema snapshot | `app/schemas/.../4.json` | 디렉토리 자체 부재 | 빌드 후 커밋 |
| FK co-delete | EMAIL-006 "EmailBody와 raw_ingestion_events를 함께 DELETE" | N/A (테이블 없음) | CASCADE 로 선제 보장 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`**
   - `@Database.entities` 에 `EmailBodyEntity::class` 추가
   - `abstract fun emailBodyDao(): EmailBodyDao` 추가
   - `DATABASE_VERSION: Int = 3` → `4` **AND** the `@Database(..., version = 3, ...)` inline literal → `4` (⚠ Kotlin 2.1.21 + KSP2 cannot resolve self-ref companion const at annotation site — ksp#2439. Both must be bumped in lockstep.)
   - import 문 2개 추가 (`EmailBodyEntity`, `EmailBodyDao`)
   - KDoc 테이블 표에 `EmailBodyEntity` row 추가

2. **`android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt`**
   - `MIGRATION_3_4` 신규. 두 부분:
     - `CREATE TABLE email_body (...)` — 14 컬럼, `parse_failed INTEGER NOT NULL DEFAULT 0`, `group_email INTEGER NOT NULL DEFAULT 0`, FK `raw_event_id REFERENCES raw_ingestion_events(id) ON DELETE CASCADE`
     - `CREATE INDEX index_email_body_raw_event_id ON email_body(raw_event_id)`
     - `CREATE INDEX index_email_body_provider_message_id ON email_body(provider_message_id)`
     - `ALTER TABLE raw_ingestion_events ADD COLUMN folder TEXT` (NULL 허용 — 앱 레이어에서 email source 일 때만 NOT NULL 보장)
   - `MIGRATIONS` 배열에 append

3. **`android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt`**
   - 신규 `@ColumnInfo(name = "folder") val folder: String? = null` — `EMAIL-001` KDoc 주석 (INBOX | SENT, email source 에서만 non-null)

4. **`android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt`**
   - `RawIngestionEventDto` 에 `@field:Json(name = "folder") val folder: String? = null` 추가. KDoc: "email source 의 INBOX|SENT 힌트. 다른 source 는 null."

5. **`android/app/src/androidTest/java/com/becalm/android/data/local/db/MigrationTest.kt`**
   - 신규 `testMigrate3To4()` — `MigrationTestHelper` 로 v3 DB 열고 MIGRATION_3_4 적용 → (a) email_body 테이블 존재 + 14 컬럼 검증, (b) 2 index 존재, (c) raw_ingestion_events 에 folder 컬럼 NULL 허용으로 추가, (d) FK CASCADE 검증 (raw event DELETE 시 email_body row co-delete)

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/data/local/db/entity/EmailBodyEntity.kt`** — 14 컬럼 `@Entity(tableName = "email_body", foreignKeys = [ForeignKey(entity = RawIngestionEventEntity::class, parentColumns = ["id"], childColumns = ["raw_event_id"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["raw_event_id"]), Index(value = ["provider_message_id"])])`. `@PrimaryKey val id: String`, `@ColumnInfo(name = "received_at") val receivedAt: Instant`. `room_only: true` 주석 + PIPA invariant KDoc.

- **`android/app/src/main/java/com/becalm/android/data/local/db/dao/EmailBodyDao.kt`** — `@Dao interface`:
  - `@Insert(onConflict = REPLACE) suspend fun insert(entity: EmailBodyEntity)`
  - `@Query("SELECT * FROM email_body WHERE raw_event_id = :rawEventId LIMIT 1") suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity?`
  - `@Query("UPDATE email_body SET parse_failed = 1, body_plain = NULL WHERE id = :id") suspend fun markParseFailed(id: String)`
  - `@Query("DELETE FROM email_body WHERE raw_event_id IN (SELECT id FROM raw_ingestion_events WHERE sync_status = 'synced' AND timestamp < :cutoffMillis)") suspend fun deleteOlderThanForSynced(cutoffMillis: Long): Int`
  - Note: `deleteOlderThanForSynced` 는 #3 RetentionSweepWorker 가 호출. CASCADE 에도 불구하고 email_body 먼저 명시적으로 지우는 이유는 raw_ingestion_events 의 30일 sweep 이 아직 구현 안 됐기 때문 — #3 에서 두 단계 일관된 커트오프로 처리.

- **`android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/4.json`** — KSP 빌드 산출물. `./gradlew :app:kspDebugKotlin` 실행 후 생성된 JSON 을 commit. 별도 손수정 금지.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes

- **Supabase 측 DDL 불필요** — `email_body` 는 `room_only: true`. Supabase mirror 에 존재하지 않음.
- **`app/build.gradle.kts`** — `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` 가 없으면 추가 (`Migrations.kt` KDoc 참조). 있으면 건드리지 않음.
- **Railway API 계약** — `api-contract.yml` 의 `RawIngestionEvent` 스키마에 `folder?: string` 필드 추가 문서화 (별도 Railway PR — 본 PR scope 밖. Android 클라이언트는 null 일 때 필드 생략, 서버는 선택적으로 수신).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "EmailBodyEntity\|email_body" android/app/src/main/java/ | wc -l` ≥ 8 (Entity 본체 + DAO + DB 등록 + Migration SQL)
- [ ] **Grep invariant**: `grep -rn "MIGRATION_3_4" android/app/src/main/java/ | wc -l` ≥ 2 (정의 + MIGRATIONS 배열)
- [ ] **Grep invariant**: `grep -rn "ForeignKey.CASCADE" android/app/src/main/java/com/becalm/android/data/local/db/entity/EmailBodyEntity.kt | wc -l` ≥ 1
- [ ] **Schema JSON**: `ls android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/4.json` 존재 + git tracked
- [ ] **androidTest**: `MigrationTest#testMigrate3To4` 통과 — (a) email_body 14 컬럼 존재, (b) 2 index 존재, (c) parse_failed DEFAULT 0, group_email DEFAULT 0, (d) raw_ingestion_events.folder 컬럼 NULL 허용으로 추가됨
- [ ] **androidTest**: FK cascade 검증 — v4 에서 raw_ingestion_events row DELETE → 연결된 email_body row 도 함께 DELETE
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공
- [ ] **KSP gate**: `./gradlew :app:kspDebugKotlin` 성공 후 4.json diff 가 spec 의 14 컬럼과 1:1 매칭 (수동 확인 가능)
- [ ] **Grep invariant (DTO)**: `grep -rn "folder" android/app/src/main/java/com/becalm/android/data/remote/dto/IngestionDtos.kt | wc -l` ≥ 1

---

## 7. Out of Scope

이 PR 은 **스키마만** 담당. 아래는 **전부 별도 PR**:

- **`EmailBodyRepository` + impl + Hilt binding** → #2 `feat/repo/email` 담당. 본 PR 은 Entity/DAO 만 제공, Repository 레이어는 건드리지 않음.
- **`RetentionSweepWorker`** → #3 `feat/worker/retention` 담당.
- **이메일 워커 (GmailWorker / OutlookMailWorker / ImapNaverWorker / ImapDaumWorker) 의 `EmailBody` INSERT 호출** → ADAPT-EMAIL-001..010 별도 PR 묶음. 본 PR 은 워커 코드에 손대지 않음.
- **이메일 remote client (GmailClient / MsGraphClient / ImapClient) 의 body_plain/body_html/attachments_meta/raw_headers fetch 확장** → ADAPT-EMAIL-004..006 별도 PR.
- **`EmailHtmlParser` (Jsoup)** → EXTRACT-EMAIL-005 별도 PR. Jsoup 의존성 추가도 본 PR 범위 밖.
- **`CommitmentExtractionWorker`** → EXTRACT-EMAIL-001 별도 PR.
- **OAuth 토큰 저장소** (ADAPT-CRED-001), **UI layer** (UI-EMAIL-001..015) → 완전 별도 모듈.
- **Railway `api-contract.yml` folder 필드 문서화** — Railway 팀 PR.
- **Supabase migration 파일** — `email_body` 는 room_only 라 불필요. `folder` 는 Supabase 에도 optional 로 추가할지 Railway 팀이 결정.

---

## 8. Dependencies

- **Blocked by**: 없음 (독립).
- **Blocks**:
  - #2 `feat/repo/email` — `EmailBodyEntity` + `EmailBodyDao` 가 먼저 merge 되어야 Repository 레이어가 참조 가능.
  - #3 `feat/worker/retention` — `email_body` 테이블과 `raw_ingestion_events.folder` 가 없으면 worker 의 DELETE SQL 이 컴파일 실패.
  - ADAPT-EMAIL-001..010 워커/클라이언트 변경 묶음 — 저장 대상 없이 EMAIL-003/004/005/006/007 구현 불가.
- **병렬 가능**: `feat/db/commitment/due-at-hint-approximate` (PR #... `db-commitment-due-at-hint-approximate.md`) — 겹치는 Entity 없음. 단 **둘 다 DATABASE_VERSION 을 bump 하려 하므로 merge 순서 상** 먼저 merge 되는 쪽이 v4, 뒤 merge 는 v5 로 rebase 필요. 본 PR 을 먼저 merge 권장 (email 이 EMAIL-001..007 전체 기반).

---

## 9. Rollback plan

```bash
git revert <merge-commit-sha>
```

Room migration 은 단방향. 사용자 디바이스에서 이미 v4 로 올라간 DB 는 v3 APK 설치 시 `Room downgrade attempted` 로 open 단계에서 throw (BeCalmDatabase.kt:48-52 주석 참조 — destructive downgrade fallback 미등록). 따라서:

- **Beta 기간**: revert 후 다음 APK 배포로 해결. v4 에 이미 올라간 사용자는 앱 재설치 필요 (데이터 손실). `email_body` 는 room-only 이므로 데이터 손실 임팩트 제한적 — raw_ingestion_events 는 Railway 미러에서 re-sync 가능.
- **프로덕션 롤아웃 후**: 단순 revert 금지. forward-only hotfix 로 대응.

---

## Appendix — Session handoff notes

- **`raw_ingestion_events.folder` 는 왜 NULL 허용?** — voice/calendar/sms_call source 에는 folder 개념이 없다. SQLite `ALTER TABLE ADD COLUMN` 이 NOT NULL + DEFAULT NULL 조합을 허용하지 않으므로, 스키마는 NULL 허용으로 두고 **앱 레이어(GmailWorker / OutlookMailWorker / ImapNaverWorker / ImapDaumWorker 의 `toEntity` 함수)에서 email source 일 때만 NOT NULL 을 강제**. 이 강제는 #2 범위도 아니고 ADAPT-EMAIL-002 PR 담당 (본 PR 은 컬럼 shape 만 제공).
- **FK CASCADE 로 EMAIL-006 co-delete 가 "자동"으로 처리되는가?** — 부분적으로만. CASCADE 는 raw event 가 DELETE 될 때 email_body co-delete 를 보장하지만, spec 은 "30일 경과 + sync_status='synced' 시 RetentionSweepWorker 가 둘 다 DELETE" 를 요구. 즉 **timestamp 기반 pruning trigger 는 여전히 worker 책임** (#3). CASCADE 는 개별 raw event 수동 삭제 시 safety net.
- **Room 은 FK CASCADE 를 기본 활성화하나?** — SQLite 는 `PRAGMA foreign_keys = ON` 이 기본값이지만 Room 이 빌드 시 이를 명시적으로 설정한다. 별도 configure 불필요. 단 androidTest `MigrationTestHelper` 는 DB open 직후 수동으로 `execSQL("PRAGMA foreign_keys = ON")` 필요할 수 있음 — 구현 세션에서 검증.
- **to_addresses JSON 저장**: Room TypeConverter 신규? — 아니다. `to_addresses` 는 `TEXT` 로 저장되고 JSON serialize/deserialize 는 **Repository 레이어 (Moshi)** 가 담당. Entity 필드 타입은 그냥 `String?`. 이는 #2 의 Repository impl 가 관장.
- **Moshi 의 이름 충돌 주의**: IngestionDtos.kt 에 `@field:Json(name = "folder")` 추가 시 `SourceType` 과의 충돌 여부 검증 불필요 (folder 는 별도 필드). 단 Moshi codegen 재실행 (`./gradlew :app:kspDebugKotlin`) 필요.
- **KSP schema snapshot 최초 생성**: `app/schemas/` 디렉토리가 현재 **부재**이므로 MIGRATION_2_3 기준의 `3.json` 도 부재일 가능성. `MigrationTestHelper.createDatabase(name, 3)` 가 3.json 을 요구 → 먼저 v3 schema snapshot 을 강제 생성해야 할 수 있음. 구현 세션에서 `ksp { arg("room.schemaLocation", ...) }` 설정 + v3 baseline 빌드부터 시작. 3.json 이 깨진 채 commit 되면 MigrationTest 전체가 실패하므로 주의.
- **구현 순서 권장**: (1) build.gradle.kts KSP 설정 확인/추가 → (2) v3 baseline schema snapshot 생성 및 commit → (3) EmailBodyEntity + DAO + DB 등록 + version bump → (4) MIGRATION_3_4 작성 → (5) RawIngestionEventEntity + DTO folder 필드 추가 → (6) KSP 재빌드로 4.json 생성 → (7) MigrationTest 작성.
