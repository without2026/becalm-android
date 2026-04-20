# DB / Commitment / due-at-hint-approximate — `commitments.due_date` → `due_at` + `due_hint` + `due_is_approximate`

**Branch**: `feat/db/commitment/due-at-hint-approximate`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2–4 (DTO, Room, Railway 계약에 걸쳐 있음)
**Severity**: High (VOI-003 구조적 출력 스펙 미충족 — LLM이 추출한 `due_hint`를 저장할 컬럼이 아예 없음)
**Type**: Drift (스펙 확장과 Room/DTO 실제 스키마 불일치, 대형 ripple)

---

## 1. Finding

`CommitmentEntity.dueDate: LocalDate?` (날짜만, 시간 없음) 하나로 due 정보를 저장한다. 그러나 `.spec/contracts/data-model.yml:132-144` 는 commitments 테이블에 **3개의 컬럼**을 요구:

- `due_at`: `timestamptz`, nullable — ISO8601 KST 정규화 시점 (UTC 저장, KST 렌더)
- `due_hint`: `text`, nullable — LLM 원문 시점 표현 (예: "다음주", "월말") — `due_at` 유무 무관 보존
- `due_is_approximate`: `boolean`, NOT NULL DEFAULT false — `due_at` 이 명시 날짜 아닌 hint 추론 여부

`data-model.yml:471` 는 이 확장을 **명시적 migration 으로 지정**:
> commitments.due_date(date) → due_at(timestamptz) + due_hint(text) + due_is_approximate(bool) 세 컬럼으로 확장

현재 `CommitmentDraftDto` (Railway 응답 DTO) 에도 `due_hint` / `due_is_approximate` 가 없어 VOI-003 "structured output with due_hint" 가 래일웨이 → Android 경로 전체에서 손실됨.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/data-model.yml:132-144` — 3 컬럼 정의

```yaml
- name: due_at
  type: timestamptz
  nullable: true
- name: due_hint
  type: text
  nullable: true
- name: due_is_approximate
  type: boolean
  nullable: false
  default: "false"
```

### 2.2 `.spec/contracts/data-model.yml:212,217` — 인덱스 재정의

```yaml
- columns: [user_id, action_state, due_at]  # was due_date
- columns: [user_id, person_ref, due_at]    # was due_date
```

### 2.3 `.spec/contracts/data-model.yml:471` — 명시적 DDL (Room & Postgres 공통)

```sql
ALTER TABLE commitments ADD COLUMN due_at INTEGER;
UPDATE commitments SET due_at =
  strftime('%s', due_date || ' 00:00:00', '-9 hours') * 1000
  WHERE due_date IS NOT NULL;
ALTER TABLE commitments ADD COLUMN due_hint TEXT;
ALTER TABLE commitments ADD COLUMN due_is_approximate INTEGER NOT NULL DEFAULT 0;
ALTER TABLE commitments DROP COLUMN due_date;
-- 기존 index idx_commitments_user_action_due / idx_commitments_user_person_due 는 due_at 기준 재생성
```

### 2.4 `.spec/voice-pipeline.spec.yml` VOI-003 — LLM structured output

Vertex AI 응답 스키마에 `due_at`, `due_hint`, `due_is_approximate` 세 필드가 모두 포함되어 Android 측 DTO 가 이를 parse 해야 함.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Room 엔티티 — `CommitmentEntity.kt:113-114`
```kotlin
@ColumnInfo(name = "due_date")
val dueDate: LocalDate?,
```
→ 날짜 only, `due_hint` / `due_is_approximate` 컬럼 없음.

### 3.2 인덱스 — `CommitmentEntity.kt:73,77`
```kotlin
Index(value = ["user_id", "action_state", "due_date"], name = "idx_commitments_user_action_due"),
Index(value = ["user_id", "person_ref", "due_date"], name = "idx_commitments_user_person_due"),
```

### 3.3 Railway DTO — `CommitmentDraftDto` (`VoiceTranscribeDtos.kt:87-112`)
```kotlin
@field:Json(name = "due_at") val dueAt: Instant?,
// MISSING: due_hint, due_is_approximate
```

### 3.4 `BeCalmDatabase.kt:92`
```kotlin
public const val DATABASE_VERSION: Int = 3
```
→ version 4 로 bump 필요.

### 3.5 `Migrations.kt` — `MIGRATION_3_4` 미존재

### 3.6 Ripple (due_date / dueDate grep: **15 files, 33 references**)

**Main**:
- `data/local/db/entity/CommitmentEntity.kt` — 스키마 본체
- `data/local/db/dao/CommitmentDao.kt:190-191, 211` — `ORDER BY due_date` SQL
- `data/remote/dto/CommitmentDtos.kt:79` — `@Json(name = "due_date")`
- `data/remote/dto/CommitmentBatchDto.kt:77` — `@Json(name = "due_date")`
- `data/repository/internal/CommitmentBatchMapper.kt:44,83` — Entity ↔ DTO 매퍼
- `worker/VoiceUploadMappers.kt:85` — `dueDate = dueAt?.toLocalDate()` (손실 변환)
- `ui/components/CommitmentCard.kt:97,122-123,310,326` — D-N 배지
- `ui/commitments/CommitmentManagementViewModel.kt`
- `ui/commitments/CommitmentManagementScreen.kt`

**Tests**:
- `androidTest/data/local/db/MigrationTest.kt` — 마이그레이션 검증
- `test/data/repository/CommitmentRepositoryImplTest.kt`
- `test/worker/UploadWorkerTest.kt`
- `test/ui/today/TodayViewModelTest.kt`
- `test/ui/commitments/CommitmentManagementViewModelTest.kt`
- `test/ui/persons/PersonDetailViewModelTest.kt`

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 컬럼 수 | 3 (due_at + due_hint + due_is_approximate) | 1 (due_date) | +2 컬럼, 1 drop |
| due 시점 정밀도 | timestamptz (시·분·초) | date only | 시간 정보 유실 |
| LLM hint 보존 | due_hint 컬럼 저장 | 없음 | **VOI-003 위배** |
| 근사값 표시 | due_is_approximate 플래그 | 없음 | UI `~` prefix 불가 |
| 인덱스 | (user_id, action_state, due_at) | (user_id, action_state, due_date) | 재생성 |
| Railway DTO | `due_hint` + `due_is_approximate` 포함 | dueAt 만 | wire 스펙 어긋남 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt`**
   - `dueDate: LocalDate?` → `dueAt: Instant?`
   - 신규: `dueHint: String?`, `dueIsApproximate: Boolean = false`
   - `@ColumnInfo` 이름: `due_at`, `due_hint`, `due_is_approximate`
   - Index 컬럼 `due_date` → `due_at`
   - KDoc 갱신

2. **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`**
   - `DATABASE_VERSION: Int = 3` → `4`

3. **`android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt`**
   - 신규 `MIGRATION_3_4` 추가 — spec DDL 그대로:
     ```kotlin
     private val MIGRATION_3_4 = object : Migration(3, 4) {
         override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("ALTER TABLE commitments ADD COLUMN due_at INTEGER")
             db.execSQL("""
                 UPDATE commitments
                 SET due_at = strftime('%s', due_date || ' 00:00:00', '-9 hours') * 1000
                 WHERE due_date IS NOT NULL
             """.trimIndent())
             db.execSQL("ALTER TABLE commitments ADD COLUMN due_hint TEXT")
             db.execSQL("ALTER TABLE commitments ADD COLUMN due_is_approximate INTEGER NOT NULL DEFAULT 0")
             // SQLite 3.35+ 지원 — minSdk 확인 필요. 하위라면 table rebuild pattern.
             db.execSQL("ALTER TABLE commitments DROP COLUMN due_date")
             // Index 재생성
             db.execSQL("DROP INDEX IF EXISTS idx_commitments_user_action_due")
             db.execSQL("DROP INDEX IF EXISTS idx_commitments_user_person_due")
             db.execSQL("""
                 CREATE INDEX idx_commitments_user_action_due
                 ON commitments (user_id, action_state, due_at)
             """.trimIndent())
             db.execSQL("""
                 CREATE INDEX idx_commitments_user_person_due
                 ON commitments (user_id, person_ref, due_at)
             """.trimIndent())
         }
     }
     ```
   - `MIGRATIONS` 배열에 추가

4. **`android/app/src/main/java/com/becalm/android/data/remote/dto/VoiceTranscribeDtos.kt`**
   - `CommitmentDraftDto` 에 필드 추가:
     ```kotlin
     @field:Json(name = "due_hint") val dueHint: String? = null,
     @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,
     ```
   - `toDomain()` 도 확장 (domain type `CommitmentDraft` 에도 필드 추가 필요 — 별도 서브 변경)

5. **`android/app/src/main/java/com/becalm/android/data/remote/dto/CommitmentDtos.kt`**
   - `@Json(name = "due_date")` 라인 → `due_at` (Instant) + `due_hint` + `due_is_approximate`

6. **`android/app/src/main/java/com/becalm/android/data/remote/dto/CommitmentBatchDto.kt`**
   - 동일 확장.

7. **`android/app/src/main/java/com/becalm/android/data/repository/internal/CommitmentBatchMapper.kt:44,83`**
   - 매퍼 필드 3개로 확장.

8. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt:190-191,211`**
   - SQL `due_date` → `due_at`. 쿼리 의미 동일 (ORDER BY due_at IS NULL ASC, due_at ASC).

9. **`android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt:85`**
   - `dueDate = dueAt?.toLocalDate()` 삭제, 직접 `dueAt = dueAt` 전달. `dueHint`, `dueIsApproximate` 도 DTO → Entity 전달.

10. **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`**
    - `dueDate: LocalDate?` → `dueAt: Instant?` + `dueIsApproximate: Boolean` + (옵션) `dueHint: String?`
    - D-N 배지 계산: `dueAt.toLocalDateTime(TimeZone.of("Asia/Seoul")).date` 로 변환 후 today 와 비교.
    - `dueIsApproximate == true` → label 에 `~` prefix.
    - Preview 데이터 업데이트.

11. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`** + **`CommitmentManagementScreen.kt`**
    - state/binding 업데이트.

12. **Tests** (5 파일): 각 테스트 픽스처/matcher 를 `dueAt` 으로 마이그레이션.

13. **`android/app/src/androidTest/java/com/becalm/android/data/local/db/MigrationTest.kt`**
    - `MIGRATION_3_4` 검증 추가 — 기존 `due_date` 데이터가 `due_at` (epoch ms, KST→UTC) 으로 올바르게 backfill 되는지.

14. **`android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/4.json`**
    - KSP 자동 생성 — 빌드 후 커밋.

### 5.2 Files to add
- `4.json` 스키마 스냅샷 (KSP 산출물)

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- **Supabase migration**: `BeCalmv2/supabase/migrations/010_commitments_due_at.sql` (별도 PR — Railway/Supabase team 담당. 본 PR 은 Android-only).
- **Railway API 계약**: `api-contract.yml` 의 `CommitmentDraft` 도 세 필드 모두 포함되도록 이미 정합 확인 필요.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "due_date\|dueDate" android/app/src/main/java/ | wc -l` = 0 (test/androidTest 제외)
- [ ] **Grep invariant**: `grep -rn "dueAt\|due_at\|dueHint\|due_hint\|dueIsApproximate\|due_is_approximate" android/app/src/main/java/ | wc -l` ≥ 20
- [ ] **MigrationTest**: `MIGRATION_3_4` — 기존 due_date='2026-04-20' 행이 due_at = KST 00:00 UTC epoch ms 로 backfill
- [ ] **MigrationTest**: due_hint 컬럼 존재, due_is_approximate 컬럼 존재 + DEFAULT 0
- [ ] **MigrationTest**: 두 인덱스가 due_at 기준으로 재생성됨
- [ ] **Unit test**: `CommitmentDraftDtoTest` — JSON `{"due_at": null, "due_hint": "다음주", "due_is_approximate": true}` → 올바른 필드 파싱
- [ ] **Unit test**: `VoiceUploadMappersTest` — DTO 3 필드가 Entity 3 필드로 1:1 전달
- [ ] **Unit test**: `CommitmentCardTest` — `dueIsApproximate = true` 일 때 D-N 배지에 `~` prefix
- [ ] **Schema JSON**: `app/schemas/.../4.json` commit 포함
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공

---

## 7. Out of Scope

- Supabase `commitments` 테이블의 Postgres DDL — 별도 migration 파일 (BeCalmv2/supabase/migrations/…)
- Railway API 구현부 (Python 측) — `api-contract.yml` 확인만, 구현은 Railway 팀
- `VoiceUploadMappers` 의 source_type 상속 — PR #16 `fix/repo/voice/commitment-source-type-inherit` 담당
- D-N 배지의 KST 기반 재계산 최적화 (memoization 외)
- 기존 `due_date` 행에서 timezone 가정 (KST 00:00 으로 backfill — 이게 가장 "덜 틀린" 추정이라는 판단)

---

## 8. Dependencies

- **Blocks**:
  - Stage 3 VOI-003 LLM structured output 완전 이식 (Railway 측)
  - Stage 5 CommitmentManagement 의 due_hint 기반 자연어 display + `~` prefix UX
  - Stage 6 Today 타임라인의 KST 기준 D-N 배지
  - `fix/worker/voice/pipa-insert-status` 와는 파일 겹침 없음 — 병렬 가능

- **Blocked by**:
  - 없음 (독립). 단 도메인 타입 `CommitmentDraft` 도 세 필드로 확장 필요 — 동일 PR 내 처리.

- **병렬 가능**:
  - `feat/db/voice/call-recording-enum` (PR #12) — 파일 겹침 없음
  - `fix/worker/voice/pipa-insert-status` (PR7) — 겹침 없음
  - `fix/repo/voice/commitment-source-type-inherit` (PR #16) — `VoiceUploadMappers.kt` 파일 겹침 **있음** → 순차 merge 필요

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Room migration 은 단방향. 사용자 디바이스에서 이미 v4 로 올라간 DB 는 v3 APK 로 다운그레이드 시 `fallbackToDestructiveMigrationOnDowngrade` 미등록 상태라 open 에서 throw — 즉, **롤백은 서버 측 계약 되돌리기 + APK 핫픽스 배포 전 단계에서만 안전**. Beta 기간 중 실행 권장, 프로덕션 배포 후 revert 는 데이터 유실 위험.

---

## Appendix — Session handoff notes

- **가장 큰 PR**. 15 파일 + 1 신규 migration + 1 스키마 JSON. 쪼개기 어려움 (Entity/DTO/Mapper/UI/Test 가 한 번에 타입 정합이 맞아야 컴파일 통과).
- **순서 권장**: (1) domain `CommitmentDraft` 확장 → (2) Entity/Migration → (3) DTO → (4) Mapper → (5) DAO SQL → (6) UI → (7) Test/Fixture 일괄 갱신.
- `ALTER TABLE … DROP COLUMN` 은 SQLite 3.35.0+ 요구. Android minSdk 가 API 26 (Android 8.0, SQLite 3.19) 라면 **table rebuild pattern** 으로 fallback 필요:
  ```sql
  CREATE TABLE commitments_new (...);
  INSERT INTO commitments_new SELECT ... FROM commitments;
  DROP TABLE commitments;
  ALTER TABLE commitments_new RENAME TO commitments;
  ```
  → 구현 세션에서 minSdk 확인 후 어느 경로인지 결정.
- `CommitmentDraft` domain type 도 VOI-001/003 따라 `dueHint`, `dueIsApproximate` 받도록 확장. `VoiceExtractionService` 의 upstream 로직도 영향.
- KST timezone 처리는 단일 상수로 집중 — 현재는 각 callsite 에서 string literal 사용 중일 가능성. 필요시 `core/time/KoreanStandardTime.kt` 추가 고려.
- MigrationTest 는 `app/schemas/…/3.json` 과 `4.json` 양쪽 스냅샷을 사용 — 기존 3.json 이 누락 없이 commit 되어 있는지 확인.
