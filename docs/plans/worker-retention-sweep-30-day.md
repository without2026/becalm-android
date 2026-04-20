# Worker / Retention / sweep-30-day — `RetentionSweepWorker` 신규 + 30일 롤링 윈도우 pruning

**Branch**: `feat/worker/retention`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 4 — Background worker (로컬 유지보수)
**Severity**: High (EMAIL-006 30일 retention 의 timestamp-driven pruning 이 부재 — FK CASCADE 는 개별 delete safety net 일 뿐, 시간 기준 sweep 은 workers/ 전역에 누락)
**Type**: Gap (`RetentionSweep*` 심볼 0건, WorkScheduler 진입점 전무)

---

## 1. Finding

`grep -rn "Retention\|Sweep" android/app/src/main/java/worker/ | grep -i retention` 결과 0건. EMAIL-006 "30일 경과 + sync_status='synced' 조건 만족 시 RetentionSweepWorker 가 EmailBody 와 raw_ingestion_events 를 함께 DELETE" 와 data-ingestion:160 "Room raw_ingestion_events 와 EmailBody 는 timestamp 기준 30일 rolling window 로 자동 삭제된다 — 단 sync_status='synced' 조건을 만족할 때만" 은 **둘 다 sweep 트리거 주체가 부재**하므로 실행되지 않음.

`app/src/main/java/com/becalm/android/worker/` 에는 `UploadWorker`, `VoiceUploadWorker`, `EnrichmentWorker`, `CommitmentUploader`, `RawEventUploader`, `ForegroundCatchUpScheduler`, `ingestion/*` 만 존재. `UniqueWorkKeys` 에도 RETENTION 키 없음. `WorkSchedulerImpl.ALL_KEYS`(298-308) 9개 entry 는 retention 누락.

FK CASCADE(#1) 은 "raw event 개별 DELETE 시 email_body 동시 DELETE" 만 보장 — **시간 기준 pruning 자체는 worker 가 주도**해야 한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/email-pipeline.spec.yml:58-64` — EMAIL-006

> "30일 경과 + sync_status='synced' 조건 만족 시 RetentionSweepWorker가 EmailBody와 raw_ingestion_events를 함께 DELETE"

### 2.2 `.spec/data-ingestion.spec.yml:160` — cross-module invariant

> "Room raw_ingestion_events와 EmailBody는 timestamp 기준 30일 rolling window로 자동 삭제된다 — 단 sync_status='synced' 조건을 만족할 때만. commitments·calendar_events는 자체 lifecycle을 따르며 리텐션 삭제 대상 아님 (data-model.yml migration_notes 참조)"

→ 중요 제약: `commitments` + `calendar_events` 는 **retention 대상 아님** (별도 lifecycle). Worker 는 이 두 테이블을 건드리지 않는다.

### 2.3 `.spec/data-ingestion.spec.yml:151`

> "raw_ingestion_events는 Railway ack(sync_status='synced') 확인 전까지 Room에서 DELETE되지 않는다"

→ sync_status 필터는 **필수** — pending 행은 절대 pruning 대상이 아니다.

### 2.4 `.spec/contracts/data-model.yml:329` — room-only 배경

> "PIPA invariant: 이메일 원문은 온디바이스 전용. Railway/Supabase로 전송 절대 금지. 30일 retention sweep 대상."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Worker 부재

```bash
ls android/app/src/main/java/com/becalm/android/worker/
# Upload, VoiceUpload, Enrichment, CommitmentUploader, RawEventUploader,
# ForegroundCatchUpScheduler, WorkSchedulerImpl, WorkScheduler, UniqueWorkKeys,
# ingestion/ — RetentionSweepWorker 부재
```

### 3.2 `android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt:11-58`

```kotlin
public object UniqueWorkKeys {
    public const val SMS_CALL: String = "ingest.sms_call"
    public const val GMAIL: String = "ingest.gmail"
    public const val NAVER_IMAP: String = "ingest.naver_imap"
    public const val DAUM_IMAP: String = "ingest.daum_imap"
    public const val OUTLOOK_MAIL: String = "ingest.outlook_mail"
    public const val GCAL: String = "ingest.gcal"
    public const val OUTLOOK_CAL: String = "ingest.outlook_cal"
    public const val UPLOAD: String = "sync.upload"
    public const val ENRICHMENT: String = "enrichment"
    public const val VOICE_UPLOAD_PREFIX: String = "voice.upload"
}
```

→ `RETENTION_SWEEP` 부재.

### 3.3 `android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt:298-308`

```kotlin
private val ALL_KEYS: List<String> = listOf(
    UniqueWorkKeys.SMS_CALL, UniqueWorkKeys.GMAIL,
    UniqueWorkKeys.NAVER_IMAP, UniqueWorkKeys.DAUM_IMAP,
    UniqueWorkKeys.OUTLOOK_MAIL, UniqueWorkKeys.GCAL,
    UniqueWorkKeys.OUTLOOK_CAL, UniqueWorkKeys.UPLOAD,
    UniqueWorkKeys.ENRICHMENT,
)
```

→ 9개. RETENTION_SWEEP 누락. `cancelAll` 경로에서 sweep worker 가 취소되지 않는 위험 (sign-out 시).

### 3.4 Scheduling 진입점 부재

`WorkSchedulerImpl` 에 `scheduleRetentionSweep()` 가 없다. `BecalmApplication.onCreate` (예상) / `SessionBootstrap` 에서 이 함수를 불러 하루 한 번 PeriodicWorkRequest 를 enqueue 하지 않고 있다.

### 3.5 검증 grep

```bash
grep -rn "RetentionSweep" android/app/src/main/java/ | wc -l   # → 0
grep -rn "RETENTION_SWEEP\|retention_sweep" android/app/src/main/java/ | wc -l   # → 0
grep -rn "deleteOlderThan\|cutoffMillis" android/app/src/main/java/ | wc -l   # → 0
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Worker 클래스 | `RetentionSweepWorker: CoroutineWorker` | 부재 | 신규 파일 |
| 실행 주기 | 하루 1회 (periodic) | N/A | `PeriodicWorkRequestBuilder(1, DAYS)` |
| 대상 테이블 | email_body + raw_ingestion_events | N/A | 두 DELETE 쿼리 (순서 중요) |
| 커트오프 | now - 30일 | N/A | `Clock.System.now() - 30.days` |
| 필터 | sync_status = 'synced' AND timestamp < cutoff | N/A | WHERE 절 |
| 제외 대상 | commitments, calendar_events 미영향 | N/A | 쿼리에서 제외 |
| WorkScheduler 진입점 | `scheduleRetentionSweep()` + `cancelAll` 포함 | 부재 | 함수 추가 + ALL_KEYS 등재 |
| UniqueWorkKey | `RETENTION_SWEEP` 상수 | 부재 | 신규 상수 |
| Existing work policy | KEEP (중복 enqueue 시 기존 유지) | N/A | `ExistingPeriodicWorkPolicy.KEEP` |
| 테스트 | Robolectric `TestListenableWorkerBuilder` + `Clock` fake | 없음 | 신규 unit test |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt`**
   - 신규 상수 추가:
     ```kotlin
     /**
      * Daily retention sweep via [com.becalm.android.worker.RetentionSweepWorker].
      * Spec refs: EMAIL-006, data-ingestion invariant line 160.
      */
     public const val RETENTION_SWEEP: String = "retention.sweep"
     ```

2. **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**
   - `WorkScheduler` interface (별도 파일) 에 `fun scheduleRetentionSweep()` 시그니처 추가.
   - Impl:
     ```kotlin
     override fun scheduleRetentionSweep() {
         val request = PeriodicWorkRequestBuilder<RetentionSweepWorker>(
             repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS,
         )
             .setConstraints(Constraints.Builder()
                 .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                 .setRequiresBatteryNotLow(true)
                 .build())
             .build()
         workManager.enqueueUniquePeriodicWork(
             UniqueWorkKeys.RETENTION_SWEEP,
             ExistingPeriodicWorkPolicy.KEEP,
             request,
         )
     }
     ```
   - `ALL_KEYS` list 에 `UniqueWorkKeys.RETENTION_SWEEP` 추가 (10 entry) — sign-out `cancelAll` 에 포함되도록.

3. **`android/app/src/main/java/com/becalm/android/<App/Bootstrap>.kt`** — 구현 세션에서 실제 진입점 확인. 후보:
   - `BecalmApplication.onCreate` 또는 `SessionBootstrap` 초기화 경로에서 `workScheduler.scheduleRetentionSweep()` 1회 호출.
   - `enqueueUniquePeriodicWork(... KEEP)` 가 중복 call 에 안전하므로 매 앱 시작마다 호출 OK.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/worker/RetentionSweepWorker.kt`** — `@HiltWorker class RetentionSweepWorker @AssistedInject constructor(...) : CoroutineWorker(...)`
   - 의존성: `RawIngestionEventDao`, `EmailBodyDao`, `Clock` (kotlinx.datetime), (선택) `BeCalmDatabase` for transaction.
   - `doWork()` 로직:
     ```kotlin
     val cutoffMillis = (clock.now() - 30.days).toEpochMilliseconds()
     db.withTransaction {
         val emailDeleted = emailBodyDao.deleteOlderThanForSynced(cutoffMillis)
         val rawDeleted = rawIngestionEventDao.deleteSyncedOlderThan(cutoffMillis)
     }
     Result.success(workDataOf("email_deleted" to emailDeleted, "raw_deleted" to rawDeleted))
     ```
   - **순서 중요**: email_body 를 먼저 DELETE. 그렇지 않으면 raw_ingestion_events 가 먼저 DELETE 되어 CASCADE 로 email_body 가 이미 사라진 뒤 카운트가 0 이 되어 observability 손실 (CASCADE 가 email_body 카운트를 대신 지우는 것은 의도대로지만, metric 추적이 어렵다). 본 worker 는 **명시적 2단계** 로 진행.
   - 실패 처리: 예외 시 `Result.retry()` (최대 3회) — WorkManager default backoff.
   - `Result.success()` 데이터에 카운트 기록 (Sentry breadcrumb 는 별도 PR).

2. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt`** (편집 — 실제 파일은 기존 존재)
   - 신규 suspend fun 추가:
     ```kotlin
     @Query("DELETE FROM raw_ingestion_events WHERE sync_status = 'synced' AND timestamp < :cutoffMillis")
     suspend fun deleteSyncedOlderThan(cutoffMillis: Long): Int
     ```
   - Note: `#1` 의 `EmailBodyDao.deleteOlderThanForSynced` 는 `email_body.raw_event_id IN (SELECT id FROM raw_ingestion_events WHERE sync_status='synced' AND timestamp < :cutoff)` — 같은 커트오프를 양쪽에 전달.

3. **`android/app/src/test/java/com/becalm/android/worker/RetentionSweepWorkerTest.kt`** — Robolectric + `TestListenableWorkerBuilder`:
   - **Test 1** `sweepDeletesOnlySyncedRowsOlderThan30Days()`:
     - in-memory Room DB 에 4행 seed:
       - A: sync_status=synced, timestamp = now - 31d → **삭제 대상**
       - B: sync_status=synced, timestamp = now - 29d → 유지
       - C: sync_status=pending, timestamp = now - 40d → 유지 (pending 은 never delete)
       - D: sync_status=synced, timestamp = now - 31d + email_body row 1건 → 양쪽 삭제
     - Fake `Clock` 주입 (kotlinx.datetime.Clock 인터페이스 fake 가능).
     - `doWork()` 호출 → Result.success
     - dao.count: 3행 남음 (B, C, — A/D 삭제). email_body: 0 (D 의 body 삭제됨).
   - **Test 2** `sweepIsIdempotent()`: 같은 시각에 두 번 실행 → 두 번째는 `email_deleted=0, raw_deleted=0`.
   - **Test 3** `sweepPreservesCommitmentsAndCalendarEvents()`: seed commitment + calendar_event (timestamp 31d 이전) → `doWork()` 후 두 테이블 각 1행 유지.
   - **Test 4** `sweepHandlesEmptyDb()`: 빈 DB → Result.success, counts all 0.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes

- **`app/build.gradle.kts`**: Hilt Worker 는 이미 사용 중 (VoiceUploadWorker 등 선례). 추가 의존성 불필요.
- **Supabase / Railway**: 변경 없음. Retention 은 클라이언트 로컬 정책.
- **사용자 알림**: 불필요 (retention 은 silent pruning, 이미 synced 이므로 서버에 복사본 존재).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "RetentionSweepWorker" android/app/src/main/java/ | wc -l` ≥ 2 (클래스 정의 + WorkSchedulerImpl 참조)
- [ ] **Grep invariant**: `grep -rn "RETENTION_SWEEP" android/app/src/main/java/ | wc -l` ≥ 3 (UniqueWorkKeys 정의 + scheduleRetentionSweep + ALL_KEYS)
- [ ] **Grep invariant**: `grep -rn "deleteSyncedOlderThan\|deleteOlderThanForSynced" android/app/src/main/java/ | wc -l` ≥ 4 (DAO 정의 + worker 호출 2개)
- [ ] **ALL_KEYS**: `WorkSchedulerImpl.ALL_KEYS` 에 `UniqueWorkKeys.RETENTION_SWEEP` 포함 — sign-out `cancelAll` 에서 sweep 취소 보장
- [ ] **Unit test**: `RetentionSweepWorkerTest#sweepDeletesOnlySyncedRowsOlderThan30Days` — Robolectric clock advance, seeded 4 rows → 3 rows remain + 0 email_body
- [ ] **Unit test**: `RetentionSweepWorkerTest#sweepPreservesCommitmentsAndCalendarEvents` — 두 테이블 미영향 확인 (spec invariant 160)
- [ ] **Unit test**: `RetentionSweepWorkerTest#sweepIsIdempotent` — 두 번째 실행은 카운트 0
- [ ] **Unit test**: `RetentionSweepWorkerTest#sweepHandlesEmptyDb` — 빈 DB 에서 Result.success
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual**: WorkManager inspector 로 앱 런칭 후 `retention.sweep` PeriodicWorkRequest 가 enqueued 상태 확인 (Android Studio → App Inspection → Background Task Inspector)
- [ ] **SQL safety**: DAO 쿼리 2개 모두 `sync_status = 'synced'` 필터 포함. `grep -n "sync_status" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt | grep -c "synced"` ≥ 1

---

## 7. Out of Scope

- **`EmailBodyEntity` / `EmailBodyDao` 의 테이블·컬럼·마이그레이션** → #1 `feat/db/email` 담당. 본 PR 은 DAO `deleteOlderThanForSynced` 함수가 이미 존재한다는 전제로 호출만.
- **`EmailBodyRepository` 를 통한 삭제 경로** → 본 worker 는 DAO 를 **직접 사용** (worker 의 일반 관례). Repository 는 insert/getByRawEventId 책임 (#2 참조).
- **Retention 정책 duration 변경 (30d → other)** — spec 고정 상수. 변경 시 별도 spec 수정 PR 필요.
- **commitments / calendar_events retention** — spec invariant 160 은 이 두 테이블을 **명시적으로 제외**. 향후 다른 retention 스펙이 생기면 별도 worker.
- **Sentry breadcrumb / metric 전송** — 본 PR 은 Result.success output data 에 카운트만 기록. Sentry 통합은 EXTRACT-EMAIL-009 (DataStore metric email_subject_only_skipped) 와 묶어 별도 PR.
- **Manual trigger UI** — 설정 화면의 "캐시 비우기" 같은 manual button 은 UX PR 별도.
- **Voice transcript retention** — voice 의 `transcript` 는 별도 저장소이며 retention 정책이 다를 수 있음 — 본 PR 범위 밖.
- **WorkManager 제약 튜닝** — 배터리 전용, 네트워크 없음은 보수적 기본값. 향후 성능 프로파일 후 조정.

---

## 8. Dependencies

- **Blocked by**:
  - **#1 `feat/db/email`** — `EmailBodyEntity`, `EmailBodyDao.deleteOlderThanForSynced`, FK CASCADE 가 있어야 worker 의 두 DELETE 가 컴파일 + 의미상 안전.
  - **#2 `feat/repo/email`** — 직접적 컴파일 의존은 없지만, room-only 가드 (DtoInvariantTest) 가 먼저 merge 되어야 retention 이 활성화되었을 때 "sweep 이 의도한 것만 지운다" 가 invariant 로 고정. 순서: #1 → #2 → #3.
- **Blocks**:
  - ADAPT-EMAIL-001..010 워커 묶음이 실제로 EmailBody 를 채우기 시작하면 본 PR 이 있어야 30일 초과 row 가 무한 축적되지 않음 — **프로덕션 배포 전에 merge 필수**.
- **병렬 가능**:
  - 다른 DB 관련 PR (`db-commitment-due-at-hint-approximate`) — 파일 겹침은 migration 배열만, ALTER TABLE 타이밍만 유의.
  - UI PR (UI-EMAIL-0xx) — 완전 독립.

---

## 9. Rollback plan

```bash
git revert <merge-commit-sha>
```

- Worker 자체는 revert 로 제거. 이미 실행되어 삭제된 email_body / raw_ingestion_events(synced) 데이터는 **복구 불가** — 단 `sync_status='synced'` 는 Railway 에 이미 복사본이 있다는 뜻이므로 raw_ingestion_events 는 재-sync 로 복원 가능. `email_body` 는 room-only 이므로 로컬 삭제 시 **영구 손실**이지만 이는 spec 의 30일 retention 의도 그 자체 — 롤백 사유가 "삭제가 너무 많이 일어남" 이라면 **다음 PR 에서 커트오프 조정** 으로 fix forward 권장.
- Revert 후에도 `UniqueWorkKeys.RETENTION_SWEEP` / `ALL_KEYS` 에 해당 entry 가 남으면 WorkManager DB 에 고아 entry 존재 가능 — 한 번 실행된 PeriodicWork 는 앱 업데이트 후에도 유지되므로, revert 와 **함께 `WorkManager.cancelUniqueWork("retention.sweep")` 를 호출하는 one-shot migration** 이 필요할 수 있음. Beta 기간에는 앱 재설치로 해결.

---

## Appendix — Session handoff notes

- **왜 Repository 를 경유하지 않고 DAO 직접 사용?** — Worker 는 프로젝트 내 선례 (`VoiceUploadWorker` 등) 에서 DAO 직접 사용이 표준. Repository 의 역할은 UI/domain 레이어의 "도메인 조작" 이고, worker 의 bulk pruning 은 SQL 효율이 중요. Repository 에 `deleteOlderThanForSynced` 를 노출하면 실수로 UI 에서 호출 가능.
- **FK CASCADE 가 있으면 email_body 먼저 지울 필요 없지 않나?** — 맞다. raw_ingestion_events 를 먼저 지우면 email_body 가 자동 삭제된다. 그러나 **명시적 2단계**로 하는 이유는:
  1. Observability — 각 테이블의 삭제 카운트를 개별 측정 (Result output data).
  2. 방어 — FK 가 실수로 CASCADE 가 아닌 형태로 변경되는 regression 방어.
  3. 예측 가능성 — SQL 리뷰어가 "이 worker 는 정확히 이 두 테이블을 건드린다" 고 한눈에 파악.
- **`Clock` 주입 방법** — kotlinx.datetime 의 `Clock` 인터페이스를 Hilt `@Provides` 로 주입. prod: `Clock.System`, test: `Clock.Fixed(instant)` 혹은 mutable fake. 기존 프로젝트에 `Clock` binding 이 없으면 본 PR 에서 추가 (간단한 Hilt provider).
- **PeriodicWork 의 첫 실행 지연** — WorkManager 는 PeriodicWorkRequest 의 첫 실행을 제약 조건 만족 즉시 수행. BATTERY_NOT_LOW 외 다른 제약 없음 → 앱 설치/업데이트 후 수 분 내 실행될 가능성 높음. 초기화 직후 sweep 이 실행되어 기존 synced+30d 초과 데이터가 즉시 삭제됨 — **의도된 동작**.
- **`withTransaction`** — Room KTX `androidx.room.withTransaction`. 두 DELETE 를 원자성 보장. DB 가 worker 에 주입되어야 함 (`BeCalmDatabase` bind).
- **30일 경계 정확도** — `Clock.now() - 30.days`. 시간대 고려? — timestamp 가 UTC epoch millis 저장이므로 UTC 기준 30일. spec 이 KST 기준 30일을 요구한다면 별도 PR 에서 조정 — 현재 spec text 는 timezone 미명시이므로 UTC 로 해석.
- **Robolectric clock fake** — `TestListenableWorkerBuilder.from(context, RetentionSweepWorker::class.java).setWorkerFactory(...)` 패턴으로 `Clock` 주입. 기존 `UploadWorkerTest` 참조.
- **구현 순서 권장**: (1) UniqueWorkKeys 상수 추가 → (2) DAO 함수 추가 (#1 이 머지된 후) → (3) Worker 본체 작성 → (4) WorkScheduler interface/impl 편집 → (5) ALL_KEYS 갱신 → (6) Application bootstrap 에 scheduleRetentionSweep() 호출 wiring → (7) 4개 unit test 작성.
