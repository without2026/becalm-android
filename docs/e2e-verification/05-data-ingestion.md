# E2E Verification — `data-ingestion` 모듈

Spec: `becalm-android/.spec/data-ingestion.spec.yml`

> **Sync architecture note**
> - **PRIMARY (100%-arrival)**: ING-011 foreground catch-up (ON_START / pull-to-refresh) + Room-as-source-of-truth + SYNC-006 즉시 업로드
> - **SECONDARY**: WorkManager 주기 job (ING-006~010)
> - **ContentObserver (ING-001)**: 프로세스 alive 동안만. 사망 중 발생 이벤트는 ING-011 의 MediaStore cursor 로 복구.

---

## 0. 전체 흐름 (6 소스 + batch upload)

```
[Source adapters — parallel coroutines]
worker/ingestion/
   ├─ MediaStoreWorker.kt:84         voice (ING-001/011)
   ├─ GmailWorker.kt:59              gmail (ING-006/011)
   ├─ OutlookMailWorker.kt           outlook_mail (ING-007/011)
   ├─ ImapNaverWorker.kt             naver_imap/daum_imap (ING-008/011)
   ├─ GoogleCalendarWorker.kt        google_calendar (ING-009/011)
   └─ OutlookCalendarWorker.kt       outlook_calendar (ING-010/011)
        │
        ▼
Room: raw_ingestion_events (sync_status='pending')
   data/local/db/dao/RawIngestionEventDao.kt
        │
        ▼
UploadWorker.kt:54 → POST /v1/raw_ingestion_events:batch  (ING-002/003/004/014/015)
        │
        ▼
Railway → Supabase (service_role) — 멱등 (user_id, client_event_id) UNIQUE
```

Schedulers:
- `worker/ForegroundCatchUpScheduler.kt:107` — LifecycleObserver `onStart` → ING-011 fan-out
- `worker/WorkSchedulerImpl.kt` — periodic jobs
- `worker/UniqueWorkKeys.kt:11` — `UPLOAD`, `sync-<source>` 키
- `worker/UploadBackoff.kt` — exponential backoff 표

---

## ING-001 — ContentObserver → Room INSERT (voice)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Register | `worker/ContentObserverBootstrap.kt:73` | `start()` — URI filtered observer |
| Observer body | `ContentObserverBootstrap.kt:176` | `UriFilteredObserver.onChange` |
| Worker kick | `MediaStoreWorker.kt:95` | `doWork` → `ingestVoiceRecordings` (L222) |
| Insert | `MediaStoreWorker.kt:460` | `insertVoiceRow` → Dao `insert` |
| DAO | `data/local/db/dao/RawIngestionEventDao.kt:41` | `insert(entity)` |

**Verify**: `event_title` = MediaStore TITLE, `duration_seconds` = DURATION/1000, `sync_status='pending'`, `retry_count=0`.

---

## ING-002 — UploadWorker 가 batch 업로드 후 `sync_status='synced'` UPDATE

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/UploadWorker.kt:64` | `doWork()` |
| Flush | `UploadWorker.kt:121` | `flushRawIngestion(userId, attempt)` |
| Partition | `UploadWorker.kt:178` | `partitionAndAckBatch` — success → `markSynced`, failed → status/retry |
| DAO | `RawIngestionEventDao.kt:104` | `markSynced(ids)` |
| API | `data/remote/api/RailwayApi.kt:72` | `batchUploadRawEvents` |

**Verify invariant**: synced 후에도 Room **DELETE 없음** (`grep -n "delete" RawIngestionEventDao.kt` → `deleteAllForUser` 만, logout 경로만).

---

## ING-003 — 응답 코드별 차등 처리

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Error mapper | `UploadWorker.kt:278` | `mapErrorToOutcome` |
| Retry decision | `UploadWorker.kt:350` | `logAndRetryable` |
| Backoff | `worker/UploadBackoff.kt` | (5xx/429/408/503 retryable) |
| DAO | `RawIngestionEventDao.kt:124` | `markFailed(id, retryCount, lastAttemptAt)` |
| Retry interceptor | `data/remote/interceptor/RetryInterceptor.kt:121` | `isRetryableStatus` — 408/429/5xx/503 true |
| 400/413/422 | `UploadWorker.kt:278` | `quarantined` 분기 |

**Verify**:
```
grep -n "quarantined\|retryable" becalm-android/android/app/src/main/java/com/becalm/android/worker/UploadWorker.kt
```

---

## ING-004 — Railway batch upload 성공 (200 + ack)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| API | `RailwayApi.kt:72` | `batchUploadRawEvents(body: BatchUploadRequest)` |
| DTO | `data/remote/dto/IngestionDtos.kt` | `BatchUploadRequest` / `BatchUploadResponse.acknowledged` / `failed` |

---

## ING-005 — 401 unauthenticated

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Interceptor | `data/remote/interceptor/AuthInterceptor.kt:50` | Bearer 부착 / refresh flow |
| DTO | `data/remote/dto/ErrorEnvelopeDto.kt` | error envelope 매핑 |

---

## ING-006 — Gmail 주기 sync (보조 경로)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/ingestion/GmailWorker.kt:70` | `doWork` |
| Incremental | `GmailWorker.kt:158` | `runIncrementalSync(startHistoryId)` |
| Full sync | `GmailWorker.kt:192` | `runFullSync` |
| Fetch | `GmailWorker.kt:261` | `fetchAndInsert(userId, messageId)` |
| Cursor | `data/local/datastore/SyncCursorStore.kt:108-116` | `observeGmailHistoryId` / `setGmailHistoryId` |
| OAuth creds | `data/local/secure/EncryptedTokenStore.kt` + `data/remote/gmail/GmailClient.kt` |

**Verify PIPA invariant**: Gmail OAuth 토큰은 Keystore-backed EncryptedPrefs 에만 저장, Railway 로 **전송 금지**. `grep -rn "gmail.*token\|gmailToken" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/api` → 히트 0건.

---

## ING-007 — Outlook Mail 주기 sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/ingestion/OutlookMailWorker.kt` | `doWork` |
| Graph client | `data/remote/msgraph/MsGraphClient.kt` + `MsGraphClientImpl.kt` | `/messages/delta` |
| Cursor | `SyncCursorStore.kt:68-77` | `observeCursor("outlook_mail")` / `setCursor` |

---

## ING-008 — 네이버/다음 IMAP sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/ingestion/ImapNaverWorker.kt` | `doWork` |
| IMAP client | `data/remote/imap/ImapClient.kt` | JavaMail wrapper |
| Cursor | `SyncCursorStore.kt:131-140` | `observeImapState(mailbox)` / `setImapState` (UIDVALIDITY + lastUid) |
| Creds | `data/local/secure/ImapCredentialStore.kt` | Keystore 저장 |

---

## ING-009 — Google Calendar sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/ingestion/GoogleCalendarWorker.kt` | `doWork` |
| DAO | `data/local/db/dao/CalendarEventDao.kt` | UPSERT |
| Cursor | `SyncCursorStore.observeCursor("google_calendar")` | syncToken |

---

## ING-010 — Outlook Calendar sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `worker/ingestion/OutlookCalendarWorker.kt` | `doWork` |
| Test | `worker/ingestion/OutlookCalendarWorkerTest.kt` | 존재 |

---

## ING-011 — PRIMARY foreground catch-up (6 adapters parallel)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Lifecycle trigger | `worker/ForegroundCatchUpScheduler.kt:121` | `onStart(owner: LifecycleOwner)` |
| Entry | `ForegroundCatchUpScheduler.kt:107` | `start()` — 앱 초기화 시 `ProcessLifecycleOwner` 에 register |
| Fan-out | `ForegroundCatchUpScheduler.kt:177` | `enqueueForSources(sources)` |
| Per-source triggers | `ForegroundCatchUpScheduler.kt:25-40` | `enqueueGmailOneShotNow` / `enqueueImapNaverOneShotNow` / … |
| 후속 upload kick | `ForegroundCatchUpScheduler.kt:177` → `WorkSchedulerImpl` | `enqueueUniqueWork('sync-all-upload', REPLACE)` (SYNC-006) |

**Verify**: 
```
grep -n "enqueue.*OneShotNow\|REPLACE" becalm-android/android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt
```

---

## ING-012 — DataStore per-source cursor

| Cursor 종류 | DataStore 키 / 메서드 |
| --- | --- |
| gmail historyId | `SyncCursorStore.kt:108-116` `observeGmailHistoryId` |
| outlook_mail deltaLink | `observeCursor("outlook_mail")` |
| naver_imap | `observeImapState("naver_imap")` (UIDVALIDITY+UID) |
| daum_imap | `observeImapState("daum_imap")` |
| google_calendar syncToken | `observeCursor("google_calendar")` |
| outlook_calendar deltaLink | `observeCursor("outlook_calendar")` |
| voice mediastore | `SyncCursorStore.kt:154-163` `observeMediaStoreLastSeen("voice")` |

---

## ING-013 — Cursor invalidation (Gmail 410 / IMAP UIDVALIDITY / MS Graph 410)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Gmail | `GmailWorker.kt:354` | `HistoryExpired` outcome → `seedHistoryIdCursor()` (L224) + 30일 full sync (L192) |
| IMAP | `worker/ingestion/ImapNaverWorker.kt` | UIDVALIDITY mismatch 분기 |
| MS Graph | `worker/ingestion/OutlookMailWorker.kt` | 410 → reset cursor |

**Verify**: `grep -rn "HistoryExpired\|UIDVALIDITY\|410" becalm-android/android/app/src/main/java/com/becalm/android/worker/ingestion`

---

## ING-014 — 413 body too large → client 50 단위 chunk 재전송

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Chunk | `UploadWorker.kt:121` / `partitionAndAckBatch` (L178) | batch 분할 로직 |
| Constraints (server contract) | `.spec/contracts/api-contract.yml` | `max_batch_size=100`, `max_body_bytes=1 MiB` |

---

## ING-015 — 멱등 재전송 (동일 client_event_id → 200, INSERT 없음)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Key provider | `data/remote/interceptor/IdempotencyKeyProvider.kt` | UUID v4 |
| Interceptor | `data/remote/interceptor/IdempotencyInterceptor.kt` | `Idempotency-Key` 헤더 |
| Body field | `data/remote/dto/IngestionDtos.kt` | `client_event_id` per event |
| Server 계약 | `.spec/contracts/api-contract.yml` | `idempotency: client_event_id` |

**Verify**:
```
grep -n "client_event_id\|Idempotency-Key" becalm-android/android/app/src/main/java/com/becalm/android/data/remote
```

---

## Invariants → CI 후크 제안

| Invariant | grep |
| --- | --- |
| Room 레코드는 ack 전 DELETE 금지 | `grep -n "delete\|DELETE FROM raw_ingestion" becalm-android/android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt` → `deleteAllForUser` (logout) 만 허용 |
| EmailBody/transcript 업로드 금지 | `grep -rn "email_body\|transcript" becalm-android/android/app/src/main/java/com/becalm/android/data/remote` → Railway DTO 에 존재 **금지** |
| 6 adapter parallel, 격리 | `ForegroundCatchUpScheduler.enqueueForSources` 가 try/catch 로 개별 보호 |
| 4xx(401 제외) 영구 실패 | `UploadWorker.mapErrorToOutcome` 가 400/413/422 를 quarantine 으로 매핑 |
| 동일 client_event_id 재전송 200 | Retrofit 요청 본문에 `client_event_id` 필수 |
