# Worker / Commitment / overdue-sweep — CMT-011 자동 `overdue` 전이 워커 누락

**Branch**: `feat/worker/commitment/overdue-sweep`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 5 (CommitmentManagement lifecycle)
**Severity**: High (CMT-011 invariant "overdue 전이는 시스템 자동" 위반 — 사용자가 직접 '놓침' 카드를 볼 수 없음)
**Type**: Gap (`OverdueSweepWorker` 클래스 자체가 없음)

---

## 1. Finding

CMT-011 은 "due_at 경과 후 24시간이 지나도 completed/cancelled 로 전환되지 않은 pending/reminded/followed_up 약속을 주기적 워커가 `action_state='overdue'` 로 자동 UPDATE 한다" 를 요구한다.

현재 Android 코드베이스에는 **`OverdueSweepWorker` 가 존재하지 않는다** — `grep -rn "overdue" android/app/src/main/java/com/becalm/android/worker/` 가 0 건이며, `CommitmentDao` 에도 batch-transition-to-overdue 쿼리가 없다. 결과적으로 `due_at` 이 2일 지난 commitment 가 여전히 `action_state='pending'` 으로 남아 UI 의 '놓침' 배지(`CMT-011` invariant) 가 영원히 표시되지 않는다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/commitment-management.spec.yml:106-113` — CMT-011 행동

```yaml
- id: CMT-011
  type: lifecycle
  description: "시스템 자동 'overdue' 전이 — due_at 경과 후 24시간이 지나도 completed/cancelled로 전환되지 않은
    pending/reminded/followed_up 약속은 주기적 워커가 action_state='overdue'로 자동 UPDATE한다. 24시간 grace period는
    당일 마감 직후 사용자가 즉시 completed 처리할 시간을 보장하기 위함"
  trigger: "WorkManager OverdueSweepWorker 실행 (6시간 주기, KEEP policy)"
  precondition: "Room에 due_at IS NOT NULL AND due_at < now() - 24h AND action_state IN
    ('pending','reminded','followed_up') commitment 존재"
  expected: "해당 commitments에 대해 Room UPDATE action_state='overdue'. Railway PATCH /v1/commitments/{id}
    {action_state:'overdue'} 배치 호출 (네트워크 가용 시). OverdueSweepWorker는 사용자 수동 action 없이 system-derived.
    UI는 'overdue'를 기존 D+N 빨간색 외에 '놓침' 배지로 구분. 사용자는 overdue 상태에서도 [팔로업]·[완료]·[취소] 가능
    (재전이 경로)"
```

### 2.2 `.spec/commitment-management.spec.yml:142` — invariant

> "overdue 전이는 시스템 자동(CMT-011)이며 사용자 수동으로 overdue 설정할 수 없다 — 사용자는 cancelled로만 명시적 폐기 가능"

### 2.3 주기 · 정책

- 주기: **6시간** (KEEP policy, 즉 동일 이름으로 재enqueue 되어도 기존 대기 중 항목 유지)
- WorkManager periodic floor (15분) 충족
- Constraints: 네트워크 필요 없음 (Room UPDATE 는 오프라인 수행, Railway PATCH 는 업로드 큐에 위임)

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `overdue` 문자열 검색 — 워커/DAO 레벨 0 건

```bash
grep -rn "overdue" android/app/src/main/java/com/becalm/android/worker/
# → empty
grep -rn "overdue" android/app/src/main/java/com/becalm/android/data/local/db/dao/
# → empty
```

UI 레벨 `BecalmColors.kt:121` 와 `CommitmentManagementScreen.kt:43` 에 "overdue" 라는 *단어* 는 있으나, 이는 D+N 빨간색 렌더링 관련 주석일 뿐 실제 `action_state='overdue'` 값을 writer 하는 코드 경로가 없다.

### 3.2 `UniqueWorkKeys.kt` — `OVERDUE_SWEEP` 상수 없음

`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt:11-58` 의 `UniqueWorkKeys` object 에는 SMS_CALL / GMAIL / NAVER_IMAP / DAUM_IMAP / OUTLOOK_MAIL / GCAL / OUTLOOK_CAL / UPLOAD / ENRICHMENT / VOICE_UPLOAD_PREFIX 10 개 상수만 존재. `OVERDUE_SWEEP` 해당 없음.

### 3.3 `WorkSchedulerImpl.kt:298-308` — `ALL_KEYS` 목록에도 overdue-sweep 누락

```kotlin
private val ALL_KEYS: List<String> = listOf(
    UniqueWorkKeys.SMS_CALL,
    UniqueWorkKeys.GMAIL,
    UniqueWorkKeys.NAVER_IMAP,
    UniqueWorkKeys.DAUM_IMAP,
    UniqueWorkKeys.OUTLOOK_MAIL,
    UniqueWorkKeys.GCAL,
    UniqueWorkKeys.OUTLOOK_CAL,
    UniqueWorkKeys.UPLOAD,
    UniqueWorkKeys.ENRICHMENT,
)
```

→ sign-out 시 `cancelAll()` 루프에서도 sweeping 되지 않음 (본 PR 에서 추가 필요).

### 3.4 `CommitmentDao.kt` — batch transition 쿼리 없음

line 78-85 `updateActionState(id, newState, updatedAt)` 만 존재 — 단건 UPDATE. CMT-011 이 요구하는 multi-row `WHERE due_at < :cutoff AND action_state IN (...)` 전이 쿼리는 부재.

### 3.5 `due_at` 컬럼 자체가 아직 Room 에 없음

`CommitmentEntity.kt:113-114` 기준:
```kotlin
@ColumnInfo(name = "due_date")
val dueDate: LocalDate?,
```

→ PR #17 (`feat/db/commitment/due-at-hint-approximate`) 머지 후에 `dueAt: Instant?` 로 전환. **본 PR 은 PR #17 에 block 됨**.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Worker 클래스 | `OverdueSweepWorker` (CoroutineWorker) | 없음 | 신규 클래스 1 |
| 주기 스케줄 | 6h PeriodicWork KEEP policy | 없음 | `WorkScheduler.enqueueOverdueSweep()` 신규 |
| DAO 전이 쿼리 | batch UPDATE WHERE due_at < cutoff AND action_state IN (pending,reminded,followed_up) | 없음 | `markOverdueBatch(cutoff, now): List<String>` DAO 메서드 신규 |
| Unique work 키 | 상수 `OVERDUE_SWEEP = "commitment.overdue_sweep"` | 없음 | 1 상수 |
| Railway PATCH 반영 | 전이된 row sync_status='pending' 으로 강등 → UploadWorker 가 다음 run 에서 batch PATCH | 없음 | DAO 쿼리가 동일 UPDATE 내 `sync_status='pending'` 함께 set |
| DI 등록 | `@HiltWorker` + WorkerFactory 자동 주입 | 없음 | 기존 Hilt 설정 재사용만 하면 OK |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt`**
   - `public const val OVERDUE_SWEEP: String = "commitment.overdue_sweep"` 추가.

2. **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`**
   - 인터페이스에 `fun enqueueOverdueSweep()` 추가. KDoc 에 6h 주기, KEEP policy 명시.

3. **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**
   - `enqueueOverdueSweep()` 구현 — `PeriodicWorkRequest.Builder(OverdueSweepWorker::class.java, 6, TimeUnit.HOURS)` + `setConstraints(periodicConstraints)` + `setBackoffCriteria(EXPONENTIAL, 30s)` → `enqueueUniquePeriodicWork(UniqueWorkKeys.OVERDUE_SWEEP, KEEP, request)`.
   - **주의**: KEEP 정책 (SYNC-005 는 REPLACE 를 강제하지 않음 — overdue sweep 은 idempotent 이므로 KEEP 으로 충분. 기존 대기 중인 periodic 을 교체할 필요 없음).
   - `ALL_KEYS` 목록에 `UniqueWorkKeys.OVERDUE_SWEEP` 추가 → sign-out 시 `cancelAll()` 함께 cancel.

4. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`**
   - 신규 `@Query` 메서드:
     ```kotlin
     /**
      * CMT-011: due_at 경과 24h + pending/reminded/followed_up 상태인 commitment 들을
      * 한 트랜잭션에서 action_state='overdue' 로 전이시키고 sync_status='pending' 으로 강등한다.
      *
      * @return 전이된 row 개수. 0 이면 이 run 에서 transition 대상 없음.
      */
     @Query("""
         UPDATE commitments
         SET action_state = 'overdue',
             sync_status  = 'pending',
             updated_at   = :now
         WHERE user_id = :userId
           AND due_at IS NOT NULL
           AND due_at < :cutoffMillis
           AND action_state IN ('pending', 'reminded', 'followed_up')
     """)
     public suspend fun markOverdueBatch(userId: String, cutoffMillis: Long, now: Instant): Int
     ```
   - **PR #17 이후** `due_at` 컬럼명 기준. `deleted_at IS NULL` 필터는 PR `feat/db/commitment/edit-delete-dispute-supersede` 머지 이후 별도 follow-up 에서 추가 (본 PR 시점에는 해당 컬럼이 존재하지 않음).

5. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`** + `CommitmentRepositoryImpl.kt`
   - 인터페이스: `suspend fun markOverdueBatch(userId: String, cutoff: Instant): BecalmResult<Int>` 추가.
   - Impl: `dao.markOverdueBatch(userId, cutoff.toEpochMilliseconds(), Clock.System.now())` 위임.

6. **`android/app/src/main/java/com/becalm/android/BeCalmApp.kt`** (또는 기존 warm-up 지점)
   - App 프로세스 초기화 시 `workScheduler.enqueueOverdueSweep()` 를 `enqueuePeriodic(*)` 들 옆에 호출. `ExistingPeriodicWorkPolicy.KEEP` 덕분에 프로세스 재시작마다 중복 enqueue 하더라도 첫 등록만 유효.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/worker/OverdueSweepWorker.kt`** — 신규 `@HiltWorker`.
   - Lifecycle (suspend `doWork()`):
     1. `authRepository.currentSession()?.userId ?: return Result.failure()` — 세션 없으면 no-op.
     2. `val cutoff = Clock.System.now().minus(24.hours)`.
     3. `val updated = commitmentRepository.markOverdueBatch(userId, cutoff)` — BecalmResult 처리.
     4. Success 시: `workScheduler.enqueueUpload(attempt = 0)` 로 Railway PATCH 배치 업로드 트리거 (CMT-011 의 "네트워크 가용 시 배치 PATCH" 부분을 기존 UPLOAD 파이프라인에 위임, 재발명 금지).
     5. `Result.success(Data.Builder().putInt("transitioned", updated).build())`.
   - 주의: periodic worker 내부에서 동일 work chain 을 다시 enqueue 하지 않음 (WorkManager 가 6h 후 자동 재실행).
   - PII 정책: `userId` 는 `redact()`, transitioned count 만 INFO 로그.

2. **`android/app/src/test/java/com/becalm/android/worker/OverdueSweepWorkerTest.kt`**
   - fakes 로 `CommitmentRepository.markOverdueBatch` 호출 검증.
   - edge case: transitioned=0 → Result.success + enqueueUpload 호출 여부 (0 이어도 호출하거나 생략 — 구현자 판단 후 테스트 반영).

3. **`android/app/src/test/java/com/becalm/android/data/local/db/dao/CommitmentDaoOverdueTest.kt`**
   - Room in-memory DB.
   - 사례: (a) due_at=null → skip, (b) due_at > cutoff → skip, (c) action_state='completed' → skip, (d) action_state='pending' + due_at < cutoff → transitioned to overdue + sync_status='pending'.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- **DB 스키마 변경 없음** — 기존 `commitments.action_state` TEXT 컬럼에 `overdue` 값 사용 (data-model.yml 의 action_state enum 에 이미 포함).
- 문서: `docs/plans/worker-commitment-overdue-sweep.md` (본 파일) 외 추가 문서 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "OverdueSweepWorker" android/app/src/main/java/ | wc -l` ≥ 3 (클래스 선언 + WorkScheduler 참조 + KDoc 참조)
- [ ] **Grep invariant**: `grep -rn "'overdue'" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "OVERDUE_SWEEP" android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt | wc -l` ≥ 1
- [ ] **DAO test**: `CommitmentDaoOverdueTest — pending commitment with due_at < now()-24h transitions to overdue with sync_status='pending'` 통과
- [ ] **DAO test**: `completed commitment is NOT transitioned to overdue` 통과
- [ ] **Worker test**: `OverdueSweepWorkerTest — Result.success + enqueueUpload 호출 검증` 통과
- [ ] **Manual**: 로컬 Room 에 due_at=2일전, action_state='pending' row 시드 → `adb shell cmd jobscheduler run` 후 확인 → action_state='overdue' 로 UPDATE 확인
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- UI '놓침' 배지 렌더링 (`action_state='overdue'` 표시) — 별도 PR `feat/ui/commitment/overdue-badge`
- 24h grace period 외의 파라미터화 — **고정 24h**. 설정으로 노출 금지 (YAGNI).
- AlarmManager 통합 — CMT-011 은 local push 를 요구하지 않음. 알람은 CMT-008 (due-1h) 가 담당.
- `action_state='overdue'` 전이 후 재전이 경로 (overdue→followed_up 등) — 기존 `updateActionState(id, newState, updatedAt)` 가 처리.
- Railway 서버쪽 /v1/commitments:batch-overdue 엔드포인트 신설 — **하지 않음**. 본 PR 은 `sync_status='pending'` 으로 강등만 하고 기존 `/v1/commitments/{id}` PATCH 단건 경로에 위임.
- `deleted_at IS NULL` 필터 — PR `feat/db/commitment/edit-delete-dispute-supersede` 머지 이후 별도 follow-up PR.

---

## 8. Dependencies

- **Blocked by**: PR #17 (`feat/db/commitment/due-at-hint-approximate`) — `due_date: LocalDate?` → `due_at: Instant?` 전환이 선행되어야 DAO 쿼리의 `due_at < :cutoffMillis` 가 성립. PR #17 의 Room migration 4 → 5 이 끝나야 본 PR 구현 가능.
- **Blocks**:
  - `feat/ui/commitment/overdue-badge` — action_state='overdue' 카드 렌더링 스펙
- **병렬 가능**:
  - `fix/worker/sync/foreground-upload-trigger` (본 플랜 문서 2 번) — 파일 겹침 없음
  - `feat/worker/sync/cursor-invalidation` (본 플랜 문서 4 번) — 파일 겹침 없음

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 는 단순 — `OverdueSweepWorker` 신규 클래스 + `UniqueWorkKeys.OVERDUE_SWEEP` + DAO 쿼리 1건 + WorkScheduler 메서드 1건이 전부. 이미 production 에 enqueue 된 PeriodicWork 는 `cancelAll()` 또는 앱 재설치로 정리 가능. Room schema 에는 변화 없으므로 migration rollback 불필요.

주의: revert 후에도 `action_state='overdue'` 로 이미 전이된 commitment row 는 그대로 남는다 — 데이터 무결성 문제 아님 (서버도 동일 값 허용). UI 가 overdue 상태를 처리하지 못하면 D+N 빨간 배지로 fallback 표시 (기존 TimeFormat.kt:71-74 경로).

---

## Appendix — Session handoff notes

- **tiny PR 은 아님, 하지만 Medium size**: `OverdueSweepWorker` + DAO 메서드 + scheduler wiring + 2 테스트 파일 = ~350 LOC.
- PR #17 에서 `due_at: Instant?` 가 nullable 이므로 DAO 쿼리에 `due_at IS NOT NULL` 필터 반드시 포함 (CMT-011 precondition 과 일치).
- `sync_status='pending'` 으로 강등하면 다음 UploadWorker run (주기적 또는 SYNC-006 foreground trigger) 이 자동으로 Railway PATCH 호출. 별도 push 경로 추가하지 않아 기존 batch invariant 재사용.
- `markOverdueBatch` 가 반환하는 `Int` 는 단순 카운트. 전이된 *id 목록* 을 돌려주지 않는 이유: Railway PATCH 경로는 이미 `sync_status='pending'` 폴링으로 작동하므로 id 가 필요 없음. Room/Backend 단일 source of truth 는 `sync_status` 컬럼.
- **KEEP vs REPLACE**: CMT-011 trigger 는 "주기적" 이고 멱등이므로 KEEP 이 정답. REPLACE 로 하면 프로세스 재시작마다 다음 실행 시점이 reset 되어 24h 이상 sweep 이 미뤄질 수 있음 (Samsung 디바이스 특성상 process kill 이 빈번).
- 구현자 선 확인 커맨드:
  ```bash
  grep -c "action_state" android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt
  grep -n "enqueuePeriodic\|enqueueUniquePeriodicWork" android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt
  ```
- `24.hours` literal 은 `kotlin.time.Duration.Companion.hours` 사용 (`kotlinx.datetime.Instant.minus(duration: Duration)` 호환). `kotlinx.datetime` 의 `DateTimeUnit` 사용 시 timezone 변환이 필요하여 불필요한 복잡성 증가.
