# Worker / Voice / retry-after-honor — VoiceUploadWorker 가 HTTP 429 Retry-After 헤더를 무시한다

**Branch**: `fix/worker/voice/retry-after-honor`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 3 (Railway + Vertex AI)
**Severity**: Medium (Vertex AI quota 고갈 상황에서 클라이언트가 서버 제시 대기시간을 존중하지 않아 quota burst 재발 및 앱 단위 차단 위험)
**Type**: Drift (spec 명시 "Retry-After 존중" 과 코드의 WorkManager default backoff 불일치)

---

## 1. Finding

`VoiceUploadWorker.handleFailure` (VoiceUploadWorker.kt:351-361) 는 모든 transient 실패를 `Result.retry()` 로 처리한다. `Result.retry()` 는 `WorkSchedulerImpl.enqueueVoiceUpload` 가 설정한 `BackoffPolicy.EXPONENTIAL(init=30s)` 만 적용할 뿐, HTTP 429 응답의 `Retry-After` 헤더 값을 읽지 않는다.

그러나 `.spec/contracts/api-contract.yml:136` 은 명시:
> 429: Vertex AI quota 한도 도달 — **Retry-After 존중**

그리고 Android 코드베이스에는 이미 이 패턴이 다른 소스에서 구현돼 있다:
- `UploadBackoff.nextDelaySeconds` (UploadBackoff.kt:61-75) — Retry-After 가 있으면 그 값을 우선 적용
- `RawEventUploader.kt:189-199` — `BecalmError.RateLimited` → `UploadBackoff.nextDelaySeconds(attempt+1, error.retryAfterSeconds)` → `setInitialDelay` 로 재스케줄
- `CommitmentRepositoryImpl.kt:354-355`, `CalendarEventRepository.kt:310`, `RawIngestionRepository.kt:353-354` 모두 `headers()["Retry-After"]?.toLongOrNull()` 파싱

즉, voice 경로만 이 invariant 에서 떨어져 나와 있음.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/api-contract.yml:136`
> `429: { error: string, message: string } # Vertex AI quota 한도 도달 — Retry-After 존중`

### 2.2 `.spec/voice-pipeline.spec.yml` VOI-006
> 429/500/503 은 transient — exponential backoff, but 429 는 server hint 우선.

### 2.3 Cross-source invariant (implicit)
- `RawEventUploader`, `Gmail/Outlook/GoogleCalendar Workers` 모두 Retry-After 를 `UploadBackoff` 를 통해 honor. Voice 만 다른 패턴을 쓸 이유가 없음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 VoiceUploadWorker.kt:242-245
```kotlin
429, 500, 503 -> {
    logger.w(TAG, "HTTP ${response.code()} transient id=${redact(rawEventId)} attempt=$runAttemptCount")
    handleFailure(entity)
}
```
→ 429 를 500/503 과 동일하게 처리. `response.headers()["Retry-After"]` 읽지 않음.

### 3.2 VoiceUploadWorker.kt:351-361 — handleFailure
```kotlin
private suspend fun handleFailure(entity: RawIngestionEventEntity): Result {
    return when (VoiceUploadStateMachine.decideRetryAction(runAttemptCount, MAX_ATTEMPTS)) {
        RetryAction.Quarantine -> { ... }
        RetryAction.Retry -> Result.retry()
    }
}
```
→ 단순 `Result.retry()`. WorkManager 의 `BackoffPolicy.EXPONENTIAL(30s)` 만 적용.

### 3.3 WorkSchedulerImpl.kt:105-123 — enqueueVoiceUpload
```kotlin
.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
```
→ 정적. Per-response Retry-After 주입 경로 없음.

### 3.4 이미 존재하는 재사용 가능한 helper
- `UploadBackoff.nextDelaySeconds(attempt, retryAfterSec)` — 완벽히 그대로 재사용 가능
- `WorkSchedulerImpl` 에 `enqueueVoiceUploadWithDelay(rawEventId, audioUri, initialDelaySec)` 추가 필요

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 429 Retry-After parse | 필수 | 안 함 | `response.headers()["Retry-After"]?.toLongOrNull()` 추가 |
| 429 delay 계산 | 서버 힌트 우선 | 정적 EXPONENTIAL(30s) | `UploadBackoff.nextDelaySeconds(attempt, retryAfterSec)` 사용 |
| 재스케줄 메커니즘 | setInitialDelay 기반 | WorkManager default retry | `enqueueVoiceUploadWithDelay` 호출로 전환 |
| 429 / 500·503 구분 | 분리 권장 | merge 됨 | 429 별도 분기 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt`**
   - `429, 500, 503 ->` 를 **`429 ->`** 와 **`500, 503 ->`** 두 분기로 분리
   - 429 분기:
     ```kotlin
     429 -> {
         val retryAfterSec = response.headers()["Retry-After"]?.toLongOrNull()
         logger.w(TAG, "HTTP 429 id=${redact(rawEventId)} retryAfter=${retryAfterSec}s attempt=$runAttemptCount")
         handleRateLimited(entity, rawEventId, audioUriString, retryAfterSec)
     }
     ```
   - 500/503 분기는 기존 `handleFailure(entity)` 유지.
   - 신규 private method `handleRateLimited(entity, rawEventId, audioUri, retryAfterSec)`:
     - `runAttemptCount >= MAX_ATTEMPTS - 1` → `markFailed` + `Result.success()`
     - 그 외 → `UploadBackoff.nextDelaySeconds(runAttemptCount + 1, retryAfterSec)` 계산
     - `workScheduler.enqueueVoiceUploadWithDelay(rawEventId, audioUri, delaySec)` 호출
     - 현재 worker 는 `Result.success()` 반환 (새 work 가 대기 중이므로 WorkManager 가 중복 retry 하지 않도록)

2. **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`** (interface)
   - 신규 메서드 추가:
     ```kotlin
     public fun enqueueVoiceUploadWithDelay(
         rawEventId: String,
         audioUri: String,
         initialDelaySec: Long,
     )
     ```

3. **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**
   - 신규 메서드 구현 — 기존 `enqueueVoiceUpload` 를 복제하되 `.setInitialDelay(initialDelaySec, TimeUnit.SECONDS)` 추가
   - 또는 `enqueueVoiceUpload` 자체에 optional `initialDelaySec: Long = 0L` 파라미터 추가 (backward compatible)
   - `ExistingWorkPolicy.REPLACE` 로 같은 unique key 에 대해 새 request 가 기존 것을 대체 → 추가 동시성 이슈 없음

4. **`android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt`** KDoc 업데이트
   - Lifecycle 섹션 7번 에 "429: server-supplied Retry-After honored via re-enqueue with initial delay" 명시

### 5.2 Files to add
없음.

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "Retry-After" android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt` ≥ 1
- [ ] **Grep invariant**: `grep -n "UploadBackoff\.nextDelaySeconds" android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt` ≥ 1
- [ ] **Unit test**: `VoiceUploadWorkerTest — 429 with Retry-After=60 schedules upload with initial delay ≥ 60s`
- [ ] **Unit test**: `VoiceUploadWorkerTest — 429 without Retry-After falls back to exponential (30s base)`
- [ ] **Unit test**: `VoiceUploadWorkerTest — 429 at MAX_ATTEMPTS-1 marks failed`
- [ ] **Unit test**: `VoiceUploadWorkerTest — 500/503 unchanged behavior (exponential via Result.retry)`
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- 500/503 에 대한 Retry-After — spec 은 429 만 명시. 500/503 은 현재 동작 유지.
- WorkManager 의 `BackoffPolicy.EXPONENTIAL` 튜닝 — 다른 소스와 정합 유지.
- Vertex AI 측 quota alerting — 서버 측 관심사.
- 502 envelope 내부 Retry-After — spec 에 없음.

---

## 8. Dependencies

- **Blocked by**: 없음 (완전 독립)
- **Blocks**: Stage 3 voice pipeline 의 quota-handling 견고성 향상
- **병렬 가능**:
  - PR #12, #13, #14, #15, #16, #17, #18 모두 파일 겹침 없음 (VoiceUploadWorker.kt + WorkScheduler.kt + WorkSchedulerImpl.kt)
  - 단 `feat/worker/voice/call-recording` (PR #15) 이 VoiceUploadWorker.kt 에 터치할 가능성 존재 → 구현 시점에 rebase 확인

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후 429 는 다시 WorkManager default exponential(30s) 로 처리됨. 사용자에게는 quota 상황에서 약간 더 공격적인 재시도 발생 가능 (서버 부담 증가). 기능 회귀 없음.

---

## Appendix — Session handoff notes

- 가장 pragmatic 한 구현은 `UploadBackoff` + `WorkScheduler.enqueueVoiceUploadWithDelay` 재사용. Voice 만을 위한 새 helper 는 만들지 말 것.
- `Result.success()` 반환 vs `Result.retry()` + 재스케줄의 trade-off: `REPLACE` policy 덕분에 동일 unique key 의 새 request 가 기존 retry 를 대체. `Result.success()` 가 더 안전하나 구현자가 WorkManager 동작 verify 할 것.
- Retry-After 헤더 포맷은 spec 이 "초 단위 정수" 로 한정 (`.toLongOrNull()`). HTTP-date 형식 지원은 out-of-scope (다른 워커들도 초만 파싱).
- `UploadBackoff.MAX_ATTEMPTS = 6` 과 VoiceUploadWorker 의 `MAX_ATTEMPTS = 3` 차이에 주의 — voice 는 오디오 크기 때문에 더 보수적. 429 처리 시에도 voice 의 `MAX_ATTEMPTS` 를 사용.
- Test fixture: `okhttp3.Response.headers(okhttp3.Headers.headersOf("Retry-After", "60"))` 로 stub 가능.
