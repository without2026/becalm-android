# Worker / Sync / quarantine-chunk-split — ING-003 `quarantined` 상태 누락 + ING-014 413 chunk 분할 재전송 미구현

**Branch**: `fix/worker/sync/quarantine-chunk-split`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 3 (Data ingestion → Backend sync 실패 처리)
**Severity**: High (ING-003 invariant "4xx(401 제외)는 sync_status='quarantined' 로 격리" + ING-014 chunk 분할 둘 다 전무 — 데이터 손실 가능성)
**Type**: Gap

---

## 1. Finding

두 개의 연쇄 Gap:

### Gap A — `quarantined` enum 과 DAO 메서드 부재

ING-003 은 "400/413/422 → `sync_status='quarantined'` 로 UPDATE, 재시도 중단" 을 요구하고, invariant 는 "Railway 응답 4xx(401 제외)는 영구 실패로 간주 — `sync_status='quarantined'` 로 격리, 재시도하지 않음" 를 MUST 로 강제한다.

현재 코드는 `RawEventUploader.kt:114` 주석이 인정하듯이 **`markFailed` 로 대체** 하고 있다:

```kotlin
// TODO: Add a dedicated `updateQuarantineStatus(id, reason)` DAO method
//   so the quarantine reason code from the server is preserved.
//   Today we reuse markFailed which only records sync_status="failed".
rawIngestionRepository.markFailed(event.id, now)
```

→ `sync_status='failed'` 는 **retry_count 증가 + 다음 UploadWorker run 에서 재선택 가능**. `quarantined` 는 **재시도 중단(terminal)**. 둘을 구분하지 않으면 서버가 영구 reject 한 이벤트가 무한 재시도 루프에 빠진다.

### Gap B — 413 chunk 분할 재전송 미구현

ING-014 는 "Railway batch upload 시 body 가 `max_batch_size(100)` 또는 `max_body_bytes(1 MiB)` 를 초과하면 413 반환. 클라이언트는 `events[]` 를 **50 건씩 chunk** 하여 재전송한다" 를 요구.

현재 `RawIngestionRepositoryImpl.kt:346-347`:

```kotlin
413 -> BecalmResult.Failure(BecalmError.Validation(field = null, message = "batch too large"))
```

→ 413 을 **permanent validation failure** 로 취급. Retry 경로도 chunk split 경로도 없음. `RawEventUploader` 는 이 값을 받아 `mapErrorToOutcome` 에서 `BecalmError.Validation` → `FlushOutcome.PermanentFailure` (`RawEventUploader.kt:237-243` 의 else branch) 로 변환. 즉 100 건이 한 번에 413 을 맞으면 **전체 배치가 Result.failure** 로 종결되어 영영 업로드되지 않음.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/data-ingestion.spec.yml:33-40` — ING-003 (원문)

```yaml
- id: ING-003
  type: lifecycle
  description: "UploadWorker는 HTTP 응답 코드에 따라 Room 레코드를 차등 처리한다:
    (1) 네트워크 없음 또는 5xx/429/408/503 → retry_count 증가, exponential backoff 재시도
    (2) 401 → AUTH-007 refresh 후 1회 재시도, 실패 시 재시도
    (3) 400/413/422 → sync_status='quarantined'로 UPDATE, 재시도 중단
    (4) 200 + failed[]에 포함된 개별 이벤트 → retryable:true면 pending 유지, retryable:false면 quarantined."
  expected: "응답 코드별 분기: … 400/413/422 → sync_status='quarantined', retry_count 동결.
    레코드 DELETE 없음. 200 failed[] 이벤트는 retryable 플래그에 따라 pending 유지 또는 quarantined로 UPDATE."
```

### 2.2 `.spec/data-ingestion.spec.yml:157` — invariant (원문)

> "Railway 응답 4xx(401 제외)는 영구 실패로 간주 — sync_status='quarantined'로 격리, 재시도하지 않음"

### 2.3 `.spec/data-ingestion.spec.yml:132-139` — ING-014 (원문)

```yaml
- id: ING-014
  type: api
  description: "Railway batch upload 시 body가 max_batch_size(100) 또는 max_body_bytes(1 MiB)를
    초과하면 413 반환. 클라이언트는 events[]를 50건씩 chunk하여 재전송한다"
  endpoint: "POST /v1/raw_ingestion_events:batch"
  precondition: "events[] 길이 > 100 또는 payload > 1 MiB"
  expected: "HTTP 413. UploadWorker가 events[]를 50건 단위로 분할하여 순차 재호출.
    Room sync_status는 분할 재전송 성공 이벤트만 synced로 UPDATE됨"
```

### 2.4 413 동작 정합성 — ING-003 vs ING-014 해석

ING-003 은 413 을 quarantine 으로, ING-014 는 chunk split 재전송으로 지정. **두 스펙은 모순이 아닌 순차적 절차** 로 해석:

1. 첫 413 → **chunk split 재전송 시도** (ING-014)
2. chunk 50 건이 다시 413 → **더 줄여 시도 (예: 25 건)** 또는 개별 이벤트 단위 → 그래도 413 이면 **개별 이벤트 quarantine** (ING-003)

즉 413 은 "batch size 가 문제" 이거나 "특정 이벤트가 너무 큼" 둘 중 하나이며, chunk 로 좁혀서 **원인 이벤트만 quarantine** 하는 것이 데이터 무결성 최적.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `RawIngestionEventEntity.kt:150-161` — sync_status 주석에 `quarantined` 값 언급 없음

```kotlin
/**
 * …
 * - "pending"           — newly inserted; awaiting upload.
 * - "synced"            — Railway acknowledged successfully.
 * - "failed"            — upload exhausted max retries; event quarantined.
 * - "awaiting_consent"  — voice event parked pending PIPA consent.
 */
@ColumnInfo(name = "sync_status")
val syncStatus: String = "pending",
```

→ 주석이 `"failed"` 와 `quarantined` 를 혼동 정의. 실제 스펙은 둘을 별개 상태로 취급.

### 3.2 `RawIngestionEventDao.kt` — quarantined 관련 메서드 없음

```bash
grep -rn "quarantined" android/app/src/main/java/com/becalm/android/data/local/db/
# → empty
```

`markFailed` (line 117-120) 만 존재:
```sql
SET sync_status = 'failed', retry_count = retry_count + 1, last_attempt_at = :now
WHERE id = :id
```

`findPendingForUpload` (line 80-85) 쿼리:
```sql
SELECT * FROM raw_ingestion_events
WHERE user_id = :userId
  AND sync_status = 'pending'
…
```

→ `'failed'` row 는 다음 주기적 run 에서 `findPendingForUpload` 의 필터에 의해 제외되는 것처럼 보이나, 실제 periodic sync 가 failed row 를 pending 으로 복구시키는 경로가 있는 경우 (재시도 로직) 재선택 가능. `quarantined` 분리가 깔끔.

### 3.3 `RawIngestionRepositoryImpl.kt:342-365` — `toBecalmResult` 분기

```kotlin
return when (code()) {
    401 -> BecalmResult.Failure(BecalmError.Unauthorized)
    413 -> BecalmResult.Failure(BecalmError.Validation(field = null, message = "batch too large"))
    422 -> {
        val msg = errorBody()?.string() ?: "validation error"
        BecalmResult.Failure(BecalmError.Validation(field = null, message = msg))
    }
    429 -> { … RateLimited … }
    in 500..599 -> { … ServerError … }
    else -> { … Network … }
}
```

- 400 코드 → 명시 분기 없음. `else` 로 떨어져 `BecalmError.Network` 가 됨 — ING-003 이 요구하는 "quarantine" 이 아니라 "retry" 로 잘못 취급.
- 413 → `BecalmError.Validation` — PermanentFailure. **chunk split 경로 0 곳**.
- 422 → `BecalmError.Validation` — PermanentFailure. ING-003 이 요구하는 quarantine 매핑 일부만 충족 (Permanent 이지만 sync_status 가 'failed' 로 잘못 기록).

### 3.4 `RawEventUploader.kt:70-82` — Failure 시 markFailed 호출

```kotlin
is BecalmResult.Failure -> {
    val now = Clock.System.now()
    pending.forEach { rawIngestionRepository.markFailed(it.id, now) }
    return mapErrorToOutcome(…)
}
```

→ Validation(413/422) 실패에서도 전체 batch 에 대해 `markFailed` 호출. `quarantined` 로 기록하지 않음.

### 3.5 `BecalmError` sealed hierarchy — `BatchTooLarge` 변종 없음

```bash
grep -rn "BatchTooLarge\|PermanentBadRequest" android/app/src/main/java/
# → empty
```

현재 `BecalmError.Validation` 하나로 400/413/422 를 모두 흡수 — ING-003 의 3-way 분기를 세분화하려면 신규 error 타입 필요.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| `sync_status` enum | `quarantined` 값 명시 (data-model.yml) | 없음 (주석에서만 언급, 실제 값 미사용) | enum + entity 주석 + DAO markQuarantined 추가 |
| 400 response | quarantined | `BecalmError.Network` (retry) | `toBecalmResult` 400 분기 추가 + BecalmError 타입 |
| 413 response | chunk split 재전송 후 최후 quarantined | Permanent failure (Validation) | chunk split 루틴 신규 + 최종 quarantine fallback |
| 422 response | quarantined | Permanent failure + markFailed (잘못된 상태) | `toBecalmResult` 유지, flush 경로에서 quarantine 호출로 전환 |
| Retryable failed[] | retryable=false → quarantined | markFailed (현 주석에서 인정) | `partitionAndAckBatch` 의 failure 분기 교정 |
| DAO 쿼리 | `findPendingForUpload` 가 quarantined 를 확실히 제외 | 현재도 `sync_status='pending'` 필터이므로 자연스럽게 제외 | 보완 필요 없음, 검증만 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/core/result/BecalmError.kt`** (path 확인 필요)
   - 신규 `BecalmError.BatchTooLarge(batchSize: Int, payloadBytes: Long?)` 추가 — 413 전용.
   - 신규 `BecalmError.Quarantine(code: Int, reason: String?)` 추가 — 400/422 전용 (retry 아님).
   - 기존 `Validation` 는 field-level validation (LLM 추출 결과 등) 용도로 보존. 서버 4xx quarantine 은 새 타입으로 분리.

2. **`android/app/src/main/java/com/becalm/android/data/repository/RawIngestionRepository.kt`** + `RawIngestionRepositoryImpl.kt`
   - 인터페이스: `suspend fun markQuarantined(ids: List<String>, reason: String?): BecalmResult<Unit>` 추가.
   - Impl: DAO 의 `markQuarantined(ids, reason)` 위임.
   - `toBecalmResult` 매핑 교체:
     ```kotlin
     400 -> BecalmResult.Failure(BecalmError.Quarantine(code = 400, reason = errorBody()?.string()))
     413 -> BecalmResult.Failure(BecalmError.BatchTooLarge(batchSize = ?, payloadBytes = null))
     422 -> BecalmResult.Failure(BecalmError.Quarantine(code = 422, reason = errorBody()?.string()))
     ```
   - 주의: `uploadBatch(events)` 가 `batchSize = events.size` 를 알 수 있도록 local 변수 전달.

3. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt`**
   - 신규 쿼리:
     ```kotlin
     @Query("""
         UPDATE raw_ingestion_events
         SET sync_status = 'quarantined',
             last_attempt_at = :now
         WHERE id IN (:ids)
     """)
     public suspend fun markQuarantined(ids: List<String>, now: Instant)
     ```
   - `findPendingForUpload` 쿼리는 이미 `sync_status='pending'` 만 필터하므로 quarantined row 는 자연히 제외됨 (변경 불필요). 단 existing `IN ('pending', 'queued', 'failed_retryable')` 조건 (line 285) 이 있는 parkCancellable 관련 쿼리는 quarantined 포함 여부 재확인 — **quarantined 는 제외** 유지.

4. **`android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt`**
   - sync_status KDoc 보완 (line 150-159):
     ```
     - "pending"           — newly inserted; awaiting upload.
     - "synced"            — Railway acknowledged successfully.
     - "failed"            — transient upload failure; retry_count advanced, will be retried.
     - "quarantined"       — server deterministically rejected (400/413/422/retryable=false);
                             retry 중단, 수동 개입 필요. (ING-003)
     - "awaiting_consent"  — voice event parked pending PIPA consent.
     ```

5. **`android/app/src/main/java/com/becalm/android/worker/RawEventUploader.kt`**
   - `partitionAndAckBatch` 의 `!failure.retryable` 분기 (line 109-116) — `markFailed` → `markQuarantined` 교체:
     ```kotlin
     !failure.retryable -> {
         rawIngestionRepository.markQuarantined(listOf(event.id), failure.reason)
         quarantinedCount++
     }
     ```
     (bulk 최적화는 후속; 일단 per-event 호출로 semantic 맞춤. 단건 markQuarantined 는 위 DAO 쿼리가 `IN (:ids)` 이므로 size=1 리스트 허용.)
   - `flushRawIngestion` 의 `BecalmResult.Failure` 분기 (line 70-82) — 에러 타입별 분기:
     ```kotlin
     is BecalmResult.Failure -> {
         val now = Clock.System.now()
         when (val err = uploadResult.error) {
             is BecalmError.BatchTooLarge -> {
                 // ING-014: chunk split 재전송
                 return flushChunked(pending, userId, attempt)
             }
             is BecalmError.Quarantine -> {
                 // ING-003 400/422: 전체 배치 quarantine
                 rawIngestionRepository.markQuarantined(
                     ids = pending.map { it.id },
                     reason = err.reason,
                 )
                 // 퀘런틴은 permanent 지만 worker Result 는 success — pending 에 남은 다른 소스는 계속 drain
                 continue  // 다음 findPendingSync 로 재시도
             }
             else -> {
                 pending.forEach { rawIngestionRepository.markFailed(it.id, now) }
                 return mapErrorToOutcome(logger, err, attempt, domain = "rawIngestion")
             }
         }
     }
     ```
   - 신규 private `suspend fun flushChunked(pending: List<RawIngestionEventEntity>, userId: String, attempt: Int): FlushOutcome`:
     - `CHUNK_SIZE = 50` (ING-014 요구치)
     - `pending.chunked(50)` 각 chunk 마다 재업로드. 개별 chunk 도 413 이면 `chunked(25)`, `chunked(1)` 까지 drill-down. 1 건 chunk 도 413 이면 해당 이벤트만 `markQuarantined`.
     - 전체 chunk loop 후 `FlushOutcome.Success(total synced)` 반환.
   - 상수 추가:
     ```kotlin
     private companion object {
         private const val TAG = "UploadWorker"
         private const val CHUNK_SIZE_INITIAL: Int = 50   // ING-014
         private const val CHUNK_SIZE_MIN: Int = 1        // drill-down floor
     }
     ```

6. **`android/app/src/main/java/com/becalm/android/worker/CommitmentUploader.kt`**
   - 동일한 quarantine 분기 교정 (commitments 경로도 ING-003 대상). `CommitmentRepository.markQuarantined` 필요.
   - `CommitmentDao.markFailed` (`'failed'` set) 는 그대로 두고, 신규 `markQuarantined(ids, now)` 추가.
   - 413 chunk split 은 commitment batch 에도 대칭 적용.

### 5.2 Files to add

1. **`android/app/src/test/java/com/becalm/android/worker/RawEventUploaderQuarantineTest.kt`**
   - 케이스:
     - 413 + 100 건 → chunk 50 으로 재시도 → 1 chunk success, 1 chunk 413 → chunk 25 로 drill → 모두 성공 → markSynced 호출 검증
     - 413 + 1 건 → markQuarantined 호출 + Success
     - 400 + 50 건 → 전체 markQuarantined
     - 422 + 50 건 → 전체 markQuarantined
     - 200 + failed[] retryable=false 2 건 → markQuarantined 2 건 + synced 48 건

2. **`android/app/src/test/java/com/becalm/android/data/local/db/dao/RawIngestionEventDaoQuarantineTest.kt`**
   - Room in-memory: markQuarantined 후 `findPendingForUpload` 가 해당 row 제외하는지 검증.
   - sync_status 값이 literal `'quarantined'` 로 기록되는지 검증.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- Room schema JSON — `sync_status` 컬럼 타입은 기존 TEXT 그대로. 값 변경만이므로 migration 불필요.
- Supabase/Railway 스키마 — raw_ingestion_events.sync_status enum 에 `quarantined` 가 이미 존재해야 함 (data-model.yml 확인). Railway 쪽은 **본 PR 범위 밖**.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "'quarantined'" android/app/src/main/java/com/becalm/android/data/local/db/dao/ | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "BatchTooLarge\|Quarantine" android/app/src/main/java/com/becalm/android/core/result/BecalmError.kt | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -rn "markQuarantined" android/app/src/main/java/ | wc -l` ≥ 4 (DAO + repo interface + repo impl + uploader 호출)
- [ ] **Grep invariant**: `grep -rn "chunked(" android/app/src/main/java/com/becalm/android/worker/RawEventUploader.kt | wc -l` ≥ 1
- [ ] **DAO test**: `RawIngestionEventDaoQuarantineTest — markQuarantined → findPendingForUpload excludes` 통과
- [ ] **Upload test**: `RawEventUploaderQuarantineTest — 413 triggers chunk split 50→25→1 drill-down` 통과
- [ ] **Upload test**: `RawEventUploaderQuarantineTest — 400 marks entire batch quarantined` 통과
- [ ] **Upload test**: `RawEventUploaderQuarantineTest — failed[] retryable=false marks individual events quarantined` 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- Railway 서버쪽 payload bytes 계산 정확도 — client 는 size 를 추정만 함, 서버 413 응답을 신뢰
- `max_body_bytes(1 MiB)` 계산 로직 — 413 받은 후에야 chunk split 으로 대응 (preemptive byte counting 금지, YAGNI)
- Quarantine reason UI 노출 — 본 PR 은 data 저장만. 관리자 UI 는 별도
- Voice event 의 413 처리 — VoiceUploadWorker 경로는 단일 파일 업로드로 batch 가 없음, 본 PR 대상 아님
- CommitmentUploader 의 chunk split 은 본 PR 에 포함 (대칭 필수)
- Quarantine 수동 unpark (관리자가 다시 pending 으로 되돌리는 경로) — 본 PR 범위 밖

---

## 8. Dependencies

- **Blocked by**: 없음. `BecalmError` 확장은 독립적.
- **Blocks**:
  - 없음 (운영 안정성 fix. 다른 PR 이 이 쿼런틴 상태를 읽지 않음).
- **병렬 가능**:
  - `fix/worker/sync/foreground-upload-trigger` (문서 2) — 파일 겹침 없음
  - `feat/worker/sync/cursor-invalidation` (문서 4) — 파일 겹침 없음
  - `feat/worker/commitment/overdue-sweep` (문서 1) — CommitmentDao 는 서로 다른 쿼리 (단 Impl 은 동일 파일 — merge 순서 주의)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 전 운영 주의:

1. Production 에 이미 `sync_status='quarantined'` 로 기록된 row 가 존재하면, revert 후 `findPendingForUpload` 쿼리는 여전히 `sync_status='pending'` 만 select 하므로 데이터 손실은 없음 — quarantined row 는 Room 에 그대로 남아있다가 다음 PR 에서 다시 처리.
2. `BecalmError.BatchTooLarge` / `BecalmError.Quarantine` 사용처가 revert 에서 자동 정리됨.
3. Migration 없음 → DB rollback 불필요.

단, `RawEventUploader.flushChunked` 내부에서 발생한 부분 synced/quarantined 상태는 forward-only (revert 후에도 그대로 남음). 데이터 integrity 문제 없음 — 해당 이벤트들은 이미 서버에 성공 또는 명시적 격리 상태.

---

## Appendix — Session handoff notes

- **중간 크기 PR** (~300 LOC). BecalmError 확장 + Repo API + DAO + Uploader flush 경로 + Commitment 대칭 + 테스트.
- ING-003 의 4xx(401 제외) 분기 원자 단위를 스펙 문면 그대로 준수 — 400/413/422 세 코드 각각 별도 분기. "4xx 는 전부 quarantine" 같은 과도한 일반화 금지 (402/403/404 등은 없는 사례이므로 기존 `else` 분기 유지).
- chunk drill-down floor 는 **1**. 1 건 chunk 도 413 이면 해당 이벤트가 본질적으로 너무 큼 → quarantine. 이 경로는 실전에서는 voice event(대용량) 에서만 발생할 가능성이 있으나, voice 는 batch 경로가 아니라 `VoiceUploadWorker` 단건 업로드를 쓰므로 사실상 발생하지 않음. 그래도 안전망.
- Retryable 플래그 semantics (`FailedEventDto.retryable`, `IngestionDtos.kt:145`) 는 그대로 사용 — 본 PR 은 flag 정의 변경 없음.
- `markQuarantined` 는 `retry_count` 를 증가시키지 않음 — 스펙 "retry_count 동결" 명시. DAO 쿼리에서 `retry_count = retry_count` (no-op) 로 두거나 생략.
- chunk split 은 **per-flush run** 내부에서 수행. WorkManager 의 외부 retry 경로와 독립. 즉 chunk split 이 실패해도 worker 는 `Result.retry` 반환해 다음 run 에서 전체 flush 를 다시 시도 (단 이번에는 pending 목록이 이미 일부 synced/quarantined 상태이므로 더 작은 배치로 진행됨). 멱등성 유지.
- 구현자 선 확인:
  ```bash
  grep -n "sealed\|sealed class\|data class\|data object" android/app/src/main/java/com/becalm/android/core/result/BecalmError.kt
  grep -c "sync_status =" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt
  grep -n "findPendingForUpload" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt
  ```
