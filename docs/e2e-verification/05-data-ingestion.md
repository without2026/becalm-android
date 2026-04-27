# E2E Verification — `data-ingestion` 모듈

Spec: `becalm-android/.spec/data-ingestion.spec.yml`

> **Sync architecture note**
> - **PRIMARY (100%-arrival)**: ING-011 foreground catch-up (ON_START / pull-to-refresh) + Room-as-source-of-truth + SYNC-006 즉시 업로드
> - **SECONDARY**: WorkManager 주기 job (ING-006~010)
> - **ContentObserver (ING-001)**: 프로세스 alive 동안만. 사망 중 발생 이벤트는 ING-011 의 MediaStore cursor 로 복구.

---

## 0. 전체 흐름 (7 user-facing sources + batch upload)

```
[Source owners]
Android local adapters:
   ├─ MediaStoreWorker.kt            voice
   └─ ImapNaverWorker.kt / ImapDaumWorker.kt

Backend-managed adapters (Railway):
   ├─ Gmail OAuth + sync             gmail
   ├─ Outlook Mail OAuth + sync      outlook_mail
   ├─ Google Calendar OAuth + sync   google_calendar
   └─ Outlook Calendar OAuth + sync  outlook_calendar
        │
        ▼
Railway / device both converge on raw_ingestion_events + source_status
        │
        ▼
Android consumes mirrored Room state / backend APIs as the source-of-truth UI surface
```

Schedulers:
- `worker/ForegroundCatchUpScheduler.kt:107` — LifecycleObserver `onStart` → local fan-out
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

## ING-006 — Gmail backend-managed sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Android connect | `ui/onboarding/EmailOAuthConnector.kt` | `startSignIn(GMAIL)` |
| Backend start/callback | `becalm-backend/app/api/v1.py` | `/v1/oauth/mail/gmail:*` |
| Backend sync | `becalm-backend/app/services/mail_sync.py` | Gmail fetch + raw mirror |
| Android consume | `data/remote/api/RailwayApi.kt` | `syncMailSource("gmail")` |

**Verify**: Railway `source_status.gmail = synced`, `raw_ingestion_events(source_type='gmail')` mirror exists.

---

## ING-007 — Outlook Mail backend-managed sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Android connect | `ui/onboarding/EmailOAuthConnector.kt` | `startSignIn(OUTLOOK_MAIL)` |
| Backend start/callback | `becalm-backend/app/api/v1.py` | `/v1/oauth/mail/outlook_mail:*` |
| Backend sync | `becalm-backend/app/services/mail_sync.py` | Graph fetch + raw mirror |
| Android consume | `data/remote/api/RailwayApi.kt` | `syncMailSource("outlook_mail")` |

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
| Android connect | `ui/onboarding/CalendarOAuthConnector.kt` | `startSignIn(GOOGLE_CALENDAR)` |
| Worker | `worker/ingestion/GoogleCalendarWorker.kt` | `doWork` — Railway sync trigger + mirror refresh |
| Backend sync | `becalm-backend/app/services/calendar_sync.py` | provider fetch + canonical mirror |
| Android consume | `data/repository/CalendarEventRepository.kt` | `refreshSince` |

---

## ING-010 — Outlook Calendar sync

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Android connect | `ui/onboarding/CalendarOAuthConnector.kt` | `startSignIn(OUTLOOK_CALENDAR)` |
| Worker | `worker/ingestion/OutlookCalendarWorker.kt` | `doWork` — Railway sync trigger + mirror refresh |
| Backend sync | `becalm-backend/app/services/calendar_sync.py` | provider fetch + canonical mirror |
| Android consume | `data/repository/CalendarEventRepository.kt` | `refreshSince` |

---

## ING-011 — PRIMARY foreground catch-up

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Lifecycle trigger | `worker/ForegroundCatchUpScheduler.kt:121` | `onStart(owner: LifecycleOwner)` |
| Entry | `ForegroundCatchUpScheduler.kt:107` | `start()` — 앱 초기화 시 `ProcessLifecycleOwner` 에 register |
| Fan-out | `ForegroundCatchUpScheduler.kt:177` | `enqueueForSources(sources)` |
| Per-source triggers | `ForegroundCatchUpScheduler.kt` | Calendar mirror workers + IMAP + voice one-shots |
| 후속 upload kick | `ForegroundCatchUpScheduler.kt:177` → `WorkSchedulerImpl` | `enqueueUniqueWork('sync-all-upload', REPLACE)` (SYNC-006) |

**Verify**: 
```
grep -n "enqueue.*OneShotNow\|REPLACE" becalm-android/android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt
```

---

## ING-012 — DataStore per-source cursor

| Cursor 종류 | DataStore 키 / 메서드 |
| --- | --- |
| gmail historyId | legacy compatibility only — backend-managed flow no longer advances a local cursor |
| outlook_mail deltaLink | legacy compatibility only — backend-managed flow no longer advances a local cursor |
| naver_imap | `observeImapState("naver_imap")` (UIDVALIDITY+UID) |
| daum_imap | `observeImapState("daum_imap")` |
| google_calendar syncToken | `observeCursor("google_calendar")` |
| outlook_calendar deltaLink | `observeCursor("outlook_calendar")` |
| voice mediastore | `SyncCursorStore.kt:154-163` `observeMediaStoreLastSeen("voice")` |

---

## ING-013 — Cursor invalidation (local IMAP only; Gmail/Outlook Mail now backend-owned)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| IMAP | `worker/ingestion/ImapNaverWorker.kt` | UIDVALIDITY mismatch 분기 |

**Verify**: `grep -rn "UIDVALIDITY" becalm-android/android/app/src/main/java/com/becalm/android/app/src/main/java/com/becalm/android/worker/ingestion`

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
