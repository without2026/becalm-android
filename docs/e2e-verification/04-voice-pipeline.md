# E2E Verification — `voice-pipeline` 모듈

Spec: `becalm-android/.spec/voice-pipeline.spec.yml` (version 2)

> **Architecture lock (2026-04-24)**: Android → Railway `POST /v1/voice/transcribe_extract` → Vertex AI Gemini 2.5 Flash (`us-central1`) → Android Room/Supabase mirror. On-device STT 제거. Audio 는 ONB-PIPA 동의 시에만 device 를 떠남.

---

## 0. 전체 흐름

```
MediaStore (Samsung Voice Recorder .m4a)
   └─ ContentObserver         worker/ContentObserverBootstrap.kt:73
         │ + polling catch-up
         ▼
MediaStoreWorker                worker/ingestion/MediaStoreWorker.kt:95
   └─ insertVoiceRow → RawIngestionEventDao.insert(source_type='voice', sync_status='pending'|'awaiting_consent')
   └─ enqueueVoice(rawEventId)   (L504) → WorkManager unique name `UniqueWorkKeys.voiceUpload(rawEventId)`
         │
         ▼
VoiceUploadWorker                worker/VoiceUploadWorker.kt:81
   ├─ check PIPA consent (parkIfConsentWithdrawn L263) ─── VOI-004
   ├─ buildAudioPart(uri)  (L351) multipart streaming ──── VOI-007
   ├─ VoiceApi.transcribeExtract(multipart) ─────────────── VOI-001/002/003
   │        data/remote/api/VoiceApi.kt:64
   ├─ handleVoice502 (L313) → schema_violation → quarantine ─ VOI-003
   └─ handleFailure (L382) → WorkerRetry + backoff ──────── VOI-006
         │
         ▼
Railway `/v1/voice/transcribe_extract`  (contract: `.spec/contracts/api-contract.yml`)
   → Gemini 2.5 Flash (us-central1, responseSchema=VoiceExtractItem[])
   → HTTP 200 { raw_event_id, items[], model, region, raw_model_text? }
   → Android가 action item만 Room commitments로 투영

Mirror path (raw event ack):
   UploadWorker.kt:54 → `/v1/raw_ingestion_events:batch` (ING-004/SYNC-001)
```

---

## VOI-001 — `pending` voice raw event 를 Railway 업로드 → action items를 commitments로 투영

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Trigger | `worker/ingestion/MediaStoreWorker.kt:460/504` | `insertVoiceRow` → `enqueueVoice` |
| Worker | `worker/VoiceUploadWorker.kt:93` | `doWork()` |
| API | `data/remote/api/VoiceApi.kt:64` | `transcribeExtract(multipart)` |
| Mappers | `worker/VoiceUploadMappers.kt` | action projection DTO → CommitmentEntity 변환 |
| Persist | `data/repository/CommitmentRepositoryImpl.kt:298` | `CommitmentDto.toEntity` |
| Mirror | `worker/UploadWorker.kt:121` | `flushRawIngestion` — raw event ack 별도 경로 |

**Verify**:
```
grep -n "transcribeExtract" becalm-android/android/app/src/main/java/com/becalm/android
```
- [ ] 업로드 성공 후 Room `RawIngestionEventDao` 의 `sync_status` 가 `pending` **유지** (synced 아님) — UploadWorker 가 별도로 ack 하는 구조.

---

## VOI-002 — 오디오 전체를 단일 Gemini 호출로 처리 (chunk 없음, ≤120분)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| 업로드 | `VoiceUploadWorker.kt:351` | `buildAudioPart` — 64KB 버퍼 스트리밍 |
| duration gate | `VoiceUploadWorker.kt:93` (doWork 내부) | duration > 7200s → 클라이언트 선차단 기대 |
| Error 502 handling | `VoiceUploadWorker.kt:313` | `handleVoice502` — `output_truncated` 시 quarantine |

**Verify invariant**:
```
grep -rn "chunk\|split" becalm-android/android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt
```
→ chunking 로직 **없어야** 한다 (spec invariant: 분할 없음).

---

## VOI-003 — structured output (responseSchema VoiceExtractItem[])

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| DTO | `data/remote/dto/VoiceTranscribeDtos.kt` | `TranscribeExtractResponse`, `VoiceExtractItemDto`, action compatibility projection |
| Domain | `domain/voice/CommitmentDraft.kt` | current action-only compatibility domain type |
| Schema test | `android/app/src/test/.../schema/VoiceResponseSchemaTest.kt` | 스키마 위반 → 502 mapping |
| Contract test | `android/app/src/test/.../contract/VoiceApiContractTest.kt` | api-contract.yml 과 shape 일치 |

**Verify**: 스키마 위반 시 Railway 502 + `{error: 'schema_violation'}` → worker `handleVoice502` → `sync_status` 유지(pending) / quarantine 분기.

---

## VOI-004 — PIPA 동의 미수락 시 `awaiting_consent` 대기

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Enqueue gate | `MediaStoreWorker.kt:460` (`insertVoiceRow`) | consent=false 인 경우 `sync_status='awaiting_consent'` 로 insert |
| Worker gate | `VoiceUploadWorker.kt:263` | `parkIfConsentWithdrawn` — 실행 중 토글 OFF 시 park |
| DAO | `RawIngestionEventDao.kt:215/229/246/263` | `findVoiceAwaitingConsent` / `flipAwaitingConsentVoiceToPending` / `releaseAwaitingConsentVoiceAndReturnIds` |
| Release flow | `flow/PipaConsentReleaseFlowTest.kt` (test) | 동의 ON 전환 시 자동 pending 복귀 |
| DataStore | `UserPrefsStore.kt:142` | `observeThirdPartyProvisionConsent` |

**Verify invariant (soft delete 금지)**:
- [ ] 동의 OFF 전환이 이미 Railway 업로드 완료된 commitments 를 삭제하지 않는지 (`CommitmentRepositoryImpl.deleteAllForUser` 는 logout 경로에서만 호출되는지).

---

## VOI-005 — READ_MEDIA_AUDIO 미부여 시 observer 미등록

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Bootstrap | `worker/ContentObserverBootstrap.kt:73` | `start()` — 권한 체크 후 register |
| Perm check | `MediaStoreWorker.kt:380` | `isMissing(perm: String)` |

**Verify**: `grep -n "READ_MEDIA_AUDIO" becalm-android/android/app/src/main/java` → observer 등록 전 permission guard 존재.

---

## VOI-006 — exponential backoff 3회 후 quarantine

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Retry logic | `worker/VoiceUploadWorker.kt:382` | `handleFailure` |
| Retry helper | `worker/WorkerRetry.kt` | backoff 계산 (30s → 60s → fail) |
| 401 분기 | `VoiceUploadWorker.kt:93` | AuthInterceptor refresh 후 1회 재시도 |
| 413/422 | `VoiceUploadWorker.kt:382` | 즉시 quarantine (retryable=false) |
| Sentry event | (GAP 가능) | `voice_upload_quarantined` — 실제 호출부 존재 여부 확인 필요 |

**Verify**:
```
grep -rn "voice_upload_quarantined\|Sentry" becalm-android/android/app/src/main/java/com/becalm/android/worker
```

---

## VOI-007 — 원본 오디오는 복사·이동·삭제하지 않음 (streaming only)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Streaming body | `VoiceUploadWorker.kt:351-380` | `buildAudioPart` → `ContentResolver.openInputStream(uri)` + `RequestBody.writeTo(sink)` 직접 |
| 64KB 버퍼 | `VoiceUploadWorker.kt:361` | `writeTo(sink: BufferedSink)` |

**Verify invariant**:
```
grep -rn "filesDir\|cacheDir\|copyTo\|delete" becalm-android/android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt
```
→ `filesDir/cacheDir` 히트 **0건**. `delete` 히트 0건.

---

## Invariants — 자동 후크 제안

| Invariant | 검증 방법 |
| --- | --- |
| Audio leaves device only when PIPA=true | `VoiceUploadWorker.doWork` 에서 consent check 분기 필수. `parkIfConsentWithdrawn` 가 모든 업로드 시도 앞에 위치 |
| Transcript 영속 금지 | `grep -rn "transcript" becalm-android/android/app/src/main/java/com/becalm/android/data/local` → Room entity 에 transcript 필드 **없어야** 한다 |
| Vertex ZDR region=us-central1 | 클라이언트는 확인 불가. Railway 측 contract + 서버 로그에서 검증 |
| WiFi/unmetered constraint | `worker/WorkSchedulerImpl.kt` 에 `setRequiredNetworkType(UNMETERED)` 존재 확인 |

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `worker/VoiceUploadWorkerTest.kt` | VOI-001/006/007 |
| `worker/VoiceUploadWorkerIntegrationTest.kt` | VOI-001/004 integration |
| `schema/VoiceResponseSchemaTest.kt` | VOI-003 |
| `contract/VoiceApiContractTest.kt` | api-contract shape |
| `di/VoiceModuleWiringTest.kt` | DI wiring regression |
| `flow/PipaConsentReleaseFlowTest.kt` | VOI-004 release |
