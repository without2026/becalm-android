# DB / Commitment / edit-delete-dispute-supersede — 편집 · 이의제기 · 소프트삭제 · 승계 6 컬럼 누락

**Branch**: `feat/db/commitment/edit-delete-dispute-supersede`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 4–5 (Room 스키마 / CommitmentManagement UI 준비)
**Severity**: Critical (commitment-edit.spec.yml EDIT-001..008, manual-commitment.spec.yml MAN-001..006 의 전제. Stage 5 UI 가 이 컬럼들을 요구)
**Type**: Drift (data-model.yml 의 6 개 컬럼 + 2 개 인덱스 + deleted_at 필터 invariant 가 Room 측에 아예 존재하지 않음)

---

## 1. Finding

`CommitmentEntity` (현 스키마 version 3, PR #17 이후 4) 에 다음 6 컬럼이 빠져 있다:

1. `last_edited_by` (uuid, nullable, FK auth.users.id)
2. `last_edited_at` (timestamptz, nullable)
3. `quote_disputed` (boolean, NOT NULL, default false)
4. `quote_disputed_at` (timestamptz, nullable)
5. `deleted_at` (timestamptz, nullable) — **soft delete marker**
6. `supersedes_commitment_id` (uuid, nullable, FK commitments.id)

그리고 다음 2 인덱스도 빠져 있다:
- `idx_commitments_user_deleted` on `(user_id, deleted_at)`
- `idx_commitments_supersedes` on `(supersedes_commitment_id)`

결정적 invariant (`.spec/contracts/data-model.yml:204-205`):
> "Soft delete marker (EDIT-006). **All client queries MUST include `WHERE deleted_at IS NULL` filter**."

현재 `CommitmentDao` 의 모든 SELECT 가 이 필터를 포함하지 않음 — 스펙이 MUST 로 강제한 데이터 안전 invariant 를 위반.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/data-model.yml:188-210` — 6 개 컬럼 field-level 정의

```yaml
- name: last_edited_by
  type: uuid
  nullable: true
  fk: "auth.users.id"
- name: last_edited_at
  type: timestamptz
  nullable: true
- name: quote_disputed
  type: boolean
  nullable: false
  default: "false"
- name: quote_disputed_at
  type: timestamptz
  nullable: true
- name: deleted_at
  type: timestamptz
  nullable: true
- name: supersedes_commitment_id
  type: uuid
  nullable: true
  fk: "commitments.id"
```

### 2.2 `.spec/contracts/data-model.yml:219-225` — 2 인덱스

```yaml
- columns: [user_id, deleted_at]
  type: btree  # idx_commitments_user_deleted
- columns: [supersedes_commitment_id]
  type: btree  # idx_commitments_supersedes
```

### 2.3 `.spec/contracts/data-model.yml:484` — 명시적 Room migration DDL

```sql
ALTER TABLE commitments ADD COLUMN last_edited_by TEXT;
ALTER TABLE commitments ADD COLUMN last_edited_at INTEGER;
ALTER TABLE commitments ADD COLUMN quote_disputed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE commitments ADD COLUMN quote_disputed_at INTEGER;
ALTER TABLE commitments ADD COLUMN deleted_at INTEGER;
ALTER TABLE commitments ADD COLUMN supersedes_commitment_id TEXT
  REFERENCES commitments(id) ON DELETE SET NULL;
CREATE INDEX idx_commitments_user_deleted ON commitments(user_id, deleted_at);
CREATE INDEX idx_commitments_supersedes ON commitments(supersedes_commitment_id);
```

### 2.4 `.spec/contracts/data-model.yml:204-205` — deleted_at 필터 invariant

> All client queries MUST include `WHERE deleted_at IS NULL` filter.

### 2.5 `.spec/commitment-edit.spec.yml` EDIT-001..008, `.spec/manual-commitment.spec.yml` MAN-001..006 — 이 스키마를 전제

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `CommitmentEntity.kt` — 6 컬럼 모두 없음
현재 필드: id, userId, direction, counterpartyRaw, personRef, title, description, quote, sourceEventTitle, sourceEventOccurredAt, dueDate (PR#17 후 dueAt/dueHint/dueIsApproximate), actionState, sourceType, sourceRef, confidence, commitmentState, syncStatus, createdAt, updatedAt — **끝**.

### 3.2 `CommitmentDao.kt` — `deleted_at IS NULL` 필터 0 곳
```bash
grep -rn "deleted_at IS NULL\|deletedAt" android/app/src/main/java/com/becalm/android/data/local/db/
# → empty
```

### 3.3 `Migrations.kt` — `MIGRATION_4_5` 없음 (PR#17 이 3→4 까지)

### 3.4 `BeCalmDatabase.kt:92` — version 3 (PR#17 이후 4, 본 PR 에서 5 로 bump)

### 3.5 EDIT-* / MAN-* 관련 코드 0건
```bash
grep -rn "EDIT-00\|quote_disputed\|supersedes_commitment" android/app/src/main/
# → empty (stage 5 UI 미착수)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 편집 추적 컬럼 | last_edited_by, last_edited_at | 없음 | 2 컬럼 추가 |
| 이의제기 컬럼 | quote_disputed, quote_disputed_at | 없음 | 2 컬럼 추가 |
| 소프트삭제 | deleted_at + 모든 쿼리에 IS NULL 필터 | 없음 | 1 컬럼 + DAO 전수 수정 |
| 승계 | supersedes_commitment_id + FK | 없음 | 1 컬럼 추가 |
| 인덱스 | idx_commitments_user_deleted, idx_commitments_supersedes | 없음 | 2 인덱스 추가 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt`**
   - 6 컬럼 필드 추가 (아래 상세)
   - `indices = [...]` 에 2 인덱스 추가
   - KDoc 확장

   ```kotlin
   @ColumnInfo(name = "last_edited_by")
   val lastEditedBy: String? = null,

   @ColumnInfo(name = "last_edited_at")
   val lastEditedAt: Instant? = null,

   @ColumnInfo(name = "quote_disputed", defaultValue = "0")
   val quoteDisputed: Boolean = false,

   @ColumnInfo(name = "quote_disputed_at")
   val quoteDisputedAt: Instant? = null,

   @ColumnInfo(name = "deleted_at")
   val deletedAt: Instant? = null,

   @ColumnInfo(name = "supersedes_commitment_id")
   val supersedesCommitmentId: String? = null,
   ```
   ```kotlin
   indices = [
     Index(value = ["user_id", "action_state", "due_at"], name = "idx_commitments_user_action_due"),
     Index(value = ["user_id", "sync_status"]),
     Index(value = ["user_id", "person_ref", "due_at"], name = "idx_commitments_user_person_due"),
     Index(value = ["user_id", "deleted_at"], name = "idx_commitments_user_deleted"),
     Index(value = ["supersedes_commitment_id"], name = "idx_commitments_supersedes"),
   ]
   ```

2. **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`**
   - `DATABASE_VERSION: Int = 4` → `5` **AND** the `@Database(..., version = 4, ...)` inline literal → `5`
   - ⚠ Migration Impact (Kotlin 2.1.21 + KSP2, ksp#2439): `@Database(version = DATABASE_VERSION)` fails — KSP2 cannot resolve self-referential companion const refs at annotation sites. Both sites must be bumped together. See `chore-build-kotlin-2-1-migration.md` Migration Impact section for rationale.

3. **`android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt`**
   - 신규 `MIGRATION_4_5` — spec DDL 그대로:
     ```kotlin
     private val MIGRATION_4_5 = object : Migration(4, 5) {
         override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("ALTER TABLE commitments ADD COLUMN last_edited_by TEXT")
             db.execSQL("ALTER TABLE commitments ADD COLUMN last_edited_at INTEGER")
             db.execSQL("ALTER TABLE commitments ADD COLUMN quote_disputed INTEGER NOT NULL DEFAULT 0")
             db.execSQL("ALTER TABLE commitments ADD COLUMN quote_disputed_at INTEGER")
             db.execSQL("ALTER TABLE commitments ADD COLUMN deleted_at INTEGER")
             // SQLite 에서는 ALTER TABLE ADD COLUMN 에 FK 직접 명시 불가 — REFERENCES 절 포함.
             // Room 은 실제 FK enforcement 를 @ForeignKey 선언으로만 처리하므로 아래 DDL 은 순수 컬럼 정의로 충분.
             db.execSQL("ALTER TABLE commitments ADD COLUMN supersedes_commitment_id TEXT")
             db.execSQL("CREATE INDEX idx_commitments_user_deleted ON commitments(user_id, deleted_at)")
             db.execSQL("CREATE INDEX idx_commitments_supersedes ON commitments(supersedes_commitment_id)")
         }
     }
     ```
   - `MIGRATIONS` 배열에 추가.

4. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`**
   - **모든 SELECT 쿼리에 `AND deleted_at IS NULL` 추가** (data-model.yml:204 MUST)
   - 예시 (line 190-191 기존):
     ```sql
     AND (due_date IS NULL OR due_date <= :todayIso)
     ```
     → PR#17 후 + 본 PR:
     ```sql
     AND (due_at IS NULL OR due_at <= :todayMs)
     AND deleted_at IS NULL
     ```
   - `findById`, `observeByUser`, `observeByPerson`, 모든 list/query 메서드에 필터 추가.
   - 신규 soft-delete/undelete/dispute-flag 관련 write 쿼리 추가는 본 PR 에서 최소만 (EDIT-* 구현 PR 에서 본격 추가). 본 PR 은 스키마 + SELECT 필터 범위로 한정.

5. **`android/app/src/main/java/com/becalm/android/data/remote/dto/CommitmentDtos.kt`**
   - 6 필드 추가 (서버 ↔ 클라이언트 정합):
     ```kotlin
     @field:Json(name = "last_edited_by") val lastEditedBy: String? = null,
     @field:Json(name = "last_edited_at") val lastEditedAt: Instant? = null,
     @field:Json(name = "quote_disputed") val quoteDisputed: Boolean = false,
     @field:Json(name = "quote_disputed_at") val quoteDisputedAt: Instant? = null,
     @field:Json(name = "deleted_at") val deletedAt: Instant? = null,
     @field:Json(name = "supersedes_commitment_id") val supersedesCommitmentId: String? = null,
     ```
   - `CommitmentBatchDto` 에는 상황별로 추가 (upload 시 필요한지 여부 구현 시 판단).

6. **`android/app/src/main/java/com/becalm/android/data/repository/internal/CommitmentBatchMapper.kt`**
   - Entity ↔ DTO 매퍼에 6 필드 전달.

7. **`android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt`**
   - LLM extraction 으로 생성되는 신규 commitment 는 6 필드 모두 default (null / false) — 매퍼가 기본값 그대로 두면 됨. 변경 불필요 (필드 생성자 default 값 덕분).

8. **`android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/5.json`**
   - KSP 산출물 — 빌드 후 커밋.

9. **`android/app/src/androidTest/java/com/becalm/android/data/local/db/MigrationTest.kt`**
   - `MIGRATION_4_5` 검증 — 6 컬럼 존재, 2 인덱스 생성, default 값 검증.

10. **테스트 파일들**: Entity fixture / DAO query 테스트 에 `deleted_at = null` 기본값 반영 + 필터 테스트 추가 (deleted row 가 SELECT 결과에서 제외되는지).

### 5.2 Files to add
- `5.json` 스키마 스냅샷

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- Supabase Postgres DDL 은 별도 migration file (BeCalmv2/supabase/migrations/011_commitments_edit_dispute_supersede.sql) — Railway/Supabase 팀.
- EDIT-* / MAN-* UI 구현은 Stage 5 PR 로 별도 진행 (이 PR 은 스키마만).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "last_edited_by\|quote_disputed\|deleted_at\|supersedes_commitment_id" android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt | wc -l` ≥ 8 (선언 + ColumnInfo 각각)
- [ ] **Grep invariant**: `grep -rn "deleted_at IS NULL" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt | wc -l` ≥ N (N = SELECT 쿼리 개수)
- [ ] **MigrationTest**: `MIGRATION_4_5` 후 6 컬럼 존재 + default 값 검증
- [ ] **MigrationTest**: 2 인덱스 존재 검증 (`SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='commitments'`)
- [ ] **Unit test**: `CommitmentDaoTest — soft-deleted rows (deleted_at NOT NULL) 는 모든 list 쿼리에서 제외된다`
- [ ] **Schema JSON**: `app/schemas/.../5.json` commit 포함
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공

---

## 7. Out of Scope

- EDIT-001..008 UI 흐름 (편집/이의제기/삭제/승계 사용자 액션) — 별도 PR `feat/ui/commitment/edit-dispute-delete-supersede`
- MAN-001..006 manual commitment 생성 UI — 별도 PR `feat/ui/commitment/manual-create`
- SourceType.MANUAL 상수 추가 — 별도 PR `feat/repo/commitment/source-type-manual` (본 PR 과 병렬)
- Supabase Postgres DDL — Railway/Supabase 세션
- Railway `/v1/commitments` 엔드포인트가 6 필드 응답에 포함하도록 하는 서버 변경 — 별도

---

## 8. Dependencies

- **Blocked by**: `feat/db/commitment/due-at-hint-approximate` (PR #17) — PR#17 이 3→4 migration. 본 PR 은 4→5. Room migration 은 선형이므로 반드시 PR#17 머지 후 진행.
- **Blocks**:
  - Stage 5 CommitmentManagementScreen 의 편집/삭제/이의제기/승계 UI
  - `commitment-edit.spec.yml` EDIT-001..008 구현 PRs
  - `manual-commitment.spec.yml` MAN-001..006 구현 PRs
- **병렬 가능**:
  - `feat/repo/commitment/source-type-manual` (MANUAL enum) — 파일 겹침 없음
  - 모든 다른 열린 PR (#12, #13, #14, #15, #16, #18, #19) 과 파일 겹침 없음

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후 Room 사용자 디바이스는 schema v5 상태 — v4 APK 다운그레이드 시 open 실패 (destructive fallback 미등록). 따라서 rollback 은 **프로덕션 배포 전**에만 안전. 이미 v5 로 upgrade 된 beta 사용자 는 forward-only.

혹시 급한 롤백이 필요하면:
1. 앱 버전 bump
2. CommitmentEntity 에서 6 필드를 모두 nullable default 로 유지 (이미 그렇게 설계)
3. DAO 에서 `deleted_at IS NULL` 필터만 제거 (스키마는 유지)
즉, 코드 레벨 부분 롤백 가능. 마이그레이션 자체는 되돌리지 않음.

---

## Appendix — Session handoff notes

- **두 번째로 큰 PR** (PR#17 에 이어). 이유: DAO 쿼리 전수 `deleted_at IS NULL` 필터 추가 + 기존 테스트 fixture 전수 갱신.
- **반드시 PR#17 머지 후 시작**. Room migration 순차 의존.
- `ALTER TABLE … ADD COLUMN` 에 REFERENCES 절 포함 SQLite 3.26+ 요구 — Android minSdk 확인 필요. Room 은 보통 FK enforcement 를 `@ForeignKey` 에서만 처리하므로, REFERENCES 절 없이 순수 TEXT 컬럼으로 둬도 무방. 구현 시 확정.
- `CommitmentDao` 의 SELECT 개수 구현자 선 확인:
  ```bash
  grep -c "@Query(\"SELECT\|^\s*SELECT" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt
  ```
- CommitmentBatchDto 에 포함시킬지 여부: `/v1/commitments:batch` 요청 payload 에 user-edited 필드들이 필요한 경우에만. LLM-only extraction 경로는 서버에 PATCH 해야 할 필요가 없음. api-contract.yml 배치 엔드포인트 스키마 확인 후 결정.
- `supersedes_commitment_id` 의 `ON DELETE SET NULL` FK 는 Postgres 에서만 의미. Room 은 `@ForeignKey(onDelete = ForeignKey.SET_NULL)` 로 선언 가능하나, commitments 테이블의 self-reference FK 는 Room 에서 순환 참조로 validator 가 warning 을 낼 수 있음. 구현 시 warning 허용 여부 결정 (suppressWarnings 또는 FK 선언 생략 후 application-level enforcement).
