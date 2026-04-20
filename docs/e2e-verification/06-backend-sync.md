# E2E Verification — `backend-sync` 모듈

Spec: `becalm-android/.spec/backend-sync.spec.yml`

> Renamed from `supabase-sync`: 업로드 타겟은 Railway FastAPI, Supabase REST 직접 호출 없음. **raw_ingestion_events 도달률 100%** 가 목표.

---

## 0. 전체 흐름

```
Room (pending)
   raw_ingestion_events / commitments
        │
        ▼
UploadWorker.kt:54
  ├─ flushRawIngestion()      (L121) → RailwayApi.batchUploadRawEvents (POST /v1/raw_ingestion_events:batch)
  ├─ flushCommitments()       (L233) → RailwayApi.patchCommitment (or bulk endpoint)
  └─ mapErrorToOutcome        (L278) — 상태코드 분기
        │
        ▼
Room: markSynced() / markFailed() (retry_count++)
```

Scheduling:
- **SYNC-004** periodic: `WorkSchedulerImpl` `enqueuePeriodic` (NETWORK_CONNECTED constraint)
- **SYNC-006** immediate: `ForegroundCatchUpScheduler` 완료 직후 `enqueueUniqueWork('sync-all-upload', REPLACE)`

Unique work keys: `worker/UniqueWorkKeys.kt:11`

---

## SYNC-001 — batch 업로드 성공 → sync_status='synced'

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/UploadWorker.kt:121` | `flushRawIngestion` |
| API | `data/remote/api/RailwayApi.kt:72` | `batchUploadRawEvents` |
| Mark | `data/local/db/dao/RawIngestionEventDao.kt:104` | `markSynced(ids)` |
| Test | (없음) — 통합 테스트 추가 필요 |

---

## SYNC-002 — client_event_id 기반 멱등

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Request DTO | `data/remote/dto/IngestionDtos.kt` | `BatchUploadRequest.events[].client_event_id` |
| Interceptor | `data/remote/interceptor/IdempotencyInterceptor.kt` + `IdempotencyKeyProvider.kt` | 헤더 + 본문 idempotency key |
| 계약 | `.spec/contracts/api-contract.yml` (L?) | `idempotency: client_event_id` |

**Verify**:
```
grep -rn "client_event_id" becalm-android/android/app/src/main/java/com/becalm/android/data
```
→ 생성 site (UUID v4) 는 **단 1 곳** (Entity insert 시점) 이어야 한다. 재전송에서 새 UUID 생성하면 멱등성 깨짐.

---

## SYNC-003 — 5xx → retry_count++, sync_status='failed'

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Mapper | `UploadWorker.kt:278` | `mapErrorToOutcome` |
| Mark | `RawIngestionEventDao.kt:124` | `markFailed(id, retryCount, lastAttemptAt)` |
| Retry scheduler | WorkManager backoff criteria | `UploadBackoff.kt` |

**Verify invariant**: 레코드 DELETE 호출 경로 없음.

---

## SYNC-004 — BACKGROUND 전환 → 네트워크 연결 시 WorkManager 재실행

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Scheduler | `worker/WorkSchedulerImpl.kt` | `enqueuePeriodic(UNMETERED? + NETWORK_CONNECTED)` |
| Lifecycle | `worker/ForegroundCatchUpScheduler.kt:121` | 앱 background 시 observer 는 유지되고 background WorkManager 가 네트워크 복귀 시 실행 |
| Network monitor | `data/remote/network/NetworkMonitor.kt` | `NetworkMonitor` flow (재연결 감지) |

---

## SYNC-005 — 429 + Retry-After 존중

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Interceptor | `data/remote/interceptor/RetryInterceptor.kt:109` | `retryAfterOverrideMs(response)` |
| Backoff override | `RetryInterceptor.kt:95` | `backoffDelayMs(attempt)` — Retry-After 값 우선 |

**Verify**:
```
grep -n "Retry-After" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/interceptor/RetryInterceptor.kt
```

---

## SYNC-006 — foreground 진입 직후 즉시 업로드

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Trigger | `worker/ForegroundCatchUpScheduler.kt:177` | `enqueueForSources` 완료 후 |
| Enqueue | `worker/WorkSchedulerImpl.kt` | `enqueueUniqueWork(UniqueWorkKeys.UPLOAD, REPLACE, OneTimeWorkRequest<UploadWorker>)` |
| Worker | `UploadWorker.kt:54/64` | `doWork` |

**Verify invariant**: periodic schedule 을 **대체하지 않음** — 양쪽 모두 살아있어야 한다. `grep -rn "cancelUniqueWork\|cancelAll" becalm-android/android/app/src/main/java/com/becalm/android/worker` → periodic 제거 경로 없음.

---

## Invariants

| Invariant | grep / 검증 |
| --- | --- |
| raw_ingestion_events 도달률 100% | synced 확인 전 DELETE 금지 — `RawIngestionEventDao.markSynced` 만 허용 |
| 업로드 대상 = raw_ingestion_events batch + commitments | `RailwayApi.kt` 의 write 엔드포인트가 `batchUploadRawEvents`, `patchCommitment` 로 한정 |
| transcripts/email_bodies 절대 업로드 금지 | `data/remote/dto` 디렉토리에 `Transcript`/`EmailBody` DTO 존재 **금지** |
| 무음 실패 금지 | `markFailed` 가 호출되거나 Sentry 이벤트 발행 — `last_attempt_at` 기록됨 |

---

## Tests (존재 여부)

| spec | test |
| --- | --- |
| SYNC-001 | `worker/VoiceUploadWorkerIntegrationTest.kt` 가 일부 cover (voice path) — raw batch 전용 UploadWorker 테스트 추가 필요 |
| SYNC-002 | 존재하지 않음 → CI 추가 필요 (멱등 contract test) |
| SYNC-003/005 | 존재하지 않음 → RetryInterceptor unit test 추가 필요 |
| SYNC-006 | `di/VoiceModuleWiringTest.kt` 가 DI 부분만 cover |
