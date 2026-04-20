# Worker / Sync / foreground-upload-trigger — `ForegroundCatchUpScheduler` 가 SYNC-006 UploadWorker 를 trigger 하지 않는다

**Branch**: `fix/worker/sync/foreground-upload-trigger`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 3 (Data ingestion → Backend sync handoff)
**Severity**: Medium (ING-011 + SYNC-006 100% arrival invariant 의 절반만 구현 — 6 소스 fan-out 은 됨, 후속 upload trigger 누락)
**Type**: Drift (스펙은 2 단계: fan-out → upload; 구현은 fan-out 만)

---

## 1. Finding

SYNC-006 은 "앱 foreground 진입(ING-011 완료 직후) 시 UploadWorker 를 `enqueueUniqueWork('sync-all-upload', REPLACE, OneTimeWorkRequest)` 로 즉시 트리거한다" 를 MUST 로 요구한다. ING-011 은 "6 개 소스 어댑터 병렬 실행 … 이후 SYNC-006 즉시 업로드 워커를 `enqueueUniqueWork('sync-all-upload', REPLACE)` 로 트리거한다" 로 연쇄 계약을 명시한다.

현재 `ForegroundCatchUpScheduler.kt:149-166` 의 `onStart` 는 6 소스 fan-out 까지만 수행하고, 후속 `workScheduler.enqueueUpload(attempt = 0)` 호출이 없다. 결과적으로 fan-out 한 각 어댑터가 Room 에 신규 `raw_ingestion_events(sync_status='pending')` 를 INSERT 하더라도, 다음 **주기적** UploadWorker 실행(15분 주기) 전까지는 Railway 로 업로드되지 않음. SYNC-006 이 약속한 "즉시 업로드" 가 최대 15분 지연됨.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/backend-sync.spec.yml:53-59` — SYNC-006 (원문)

```yaml
- id: SYNC-006
  type: lifecycle
  description: "앱 foreground 진입(ING-011 완료 직후) 시 UploadWorker를
    enqueueUniqueWork('sync-all-upload', REPLACE, OneTimeWorkRequest)로 즉시 트리거한다.
    다음 주기적 WorkManager 인터벌을 기다리지 않는다"
  trigger: "ING-011 병렬 어댑터 실행 완료 후 (ON_START 또는 pull-to-refresh 경로)"
  precondition: "Room에 sync_status='pending' 레코드 존재(raw_ingestion_events, commitments 포함),
    Supabase JWT 유효, 네트워크 연결됨"
  expected: "enqueueUniqueWork('sync-all-upload', REPLACE) 호출됨. 이전에 동일 태그로 대기 중인
    워커가 있으면 교체됨. 모든 pending 레코드 즉시 Railway batch 업로드 시작됨"
```

### 2.2 `.spec/backend-sync.spec.yml:66` — invariant

> "foreground 진입 시 즉시 업로드(SYNC-006)는 주기적 WorkManager를 대체하지 않는다 — 양쪽 모두 작동한다"

### 2.3 `.spec/data-ingestion.spec.yml:105-112` — ING-011 체인 요구

```yaml
- id: ING-011
  type: lifecycle
  description: "[PRIMARY 100%-arrival 경로] 앱 foreground 진입(ON_START) 또는 pull-to-refresh 시
    6개 소스 어댑터를 병렬 코루틴으로 실행하여 catch-up sync를 수행한다. …
    이후 SYNC-006 즉시 업로드 워커를 enqueueUniqueWork('sync-all-upload', REPLACE)로 트리거한다"
  expected: "… UploadWorker enqueueUniqueWork('sync-all-upload', REPLACE) 호출됨. …"
```

### 2.4 Work name 명명

스펙이 `'sync-all-upload'` 라는 논리적 태그명을 제시. Android 측 `UniqueWorkKeys.UPLOAD = "sync.upload"` 가 이미 존재 (`UniqueWorkKeys.kt:34`). **둘 다 동일 unique-work key** 로 통일 — 스펙의 문자열 `sync-all-upload` 는 요구사항이 아닌 예시로 해석. 코드는 `UniqueWorkKeys.UPLOAD` 재사용. (Refactor 불필요.)

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `ForegroundCatchUpScheduler.kt:149-166` — `onStart` 가 fan-out 만 수행

```kotlin
override fun onStart(owner: LifecycleOwner) {
    scope.launch {
        try {
            val enabledSources: Set<String> = userPrefsStore.observeEnabledSources().first()

            if (enabledSources.isEmpty()) {
                logger.d(TAG, "onStart: no enabled sources — skipping catch-up enqueue")
                return@launch
            }

            logger.d(TAG, "onStart: enqueueing catch-up for sources=$enabledSources")
            enqueueForSources(enabledSources)
            // ← 여기 SYNC-006 UploadWorker trigger 가 빠져있음
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e(TAG, "onStart: failed to enqueue catch-up work", e)
        }
    }
}
```

### 3.2 `ForegroundCatchUpScheduler.kt:121-138` — `triggerCatchUp` (pull-to-refresh) 도 동일 누락

```kotlin
public fun triggerCatchUp() {
    scope.launch {
        try {
            val enabledSources: Set<String> = userPrefsStore.observeEnabledSources().first()

            if (enabledSources.isEmpty()) {
                logger.d(TAG, "triggerCatchUp: no enabled sources — skipping")
                return@launch
            }

            logger.d(TAG, "triggerCatchUp: enqueueing for sources=$enabledSources")
            enqueueForSources(enabledSources)
            // ← 여기도 SYNC-006 trigger 누락
        } catch (e: Exception) { … }
    }
}
```

### 3.3 `WorkScheduler.kt` + `WorkSchedulerImpl.kt:83-95` — `enqueueUpload(attempt)` 는 이미 존재

```kotlin
override fun enqueueUpload(attempt: Int) {
    val uploadConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequest.Builder(UploadWorker::class.java)
        .setConstraints(uploadConstraints)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .setInputData(workDataOf(UploadWorker.INPUT_KEY_ATTEMPT to attempt))
        .build()
    workManager.enqueueUniqueWork(UniqueWorkKeys.UPLOAD, ExistingWorkPolicy.REPLACE, request)
    logger.d(TAG, "enqueueUpload attempt=$attempt key=${UniqueWorkKeys.UPLOAD}")
}
```

→ **SYNC-006 이 요구하는 `REPLACE` 정책 이미 구현**. 호출만 하면 됨. 1 줄 수정.

### 3.4 `ForegroundWorkScheduler` 인터페이스 — enqueueUpload 를 노출하지 않음

`ForegroundCatchUpScheduler.kt:22-41` 의 `ForegroundWorkScheduler` 는 6 소스 expedited enqueue 메서드만 선언. 그러나 **`WorkSchedulerImpl` 는 `WorkScheduler` + `ForegroundWorkScheduler` 를 동시 구현**하므로 (`WorkSchedulerImpl.kt:55`), `ForegroundCatchUpScheduler` 에 주입되는 `workScheduler: ForegroundWorkScheduler` 를 `WorkScheduler` 로 교체하거나 인터페이스 확장 중 택 1.

**권장**: `ForegroundWorkScheduler` 인터페이스에 `fun enqueueUpload(attempt: Int = 0)` 메서드 추가 — `WorkScheduler` 에 이미 있는 시그니처를 그대로 복제 (Kotlin interface 에서는 같은 시그니처 중복 선언이 문제 없음, `WorkSchedulerImpl` 의 단일 `override` 가 양쪽 모두 구현).

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| fan-out | 6 소스 병렬 expedited | 구현됨 (ForegroundCatchUpScheduler.onStart) | - |
| Upload trigger | 후속 `enqueueUniqueWork(UPLOAD, REPLACE)` 호출 | **누락** | 1 줄 추가 (onStart + triggerCatchUp 각 1 줄) |
| REPLACE policy | 이전 대기 중 upload 교체 | `enqueueUpload` 내부에 이미 REPLACE | - |
| Interface 노출 | `ForegroundWorkScheduler` 에 `enqueueUpload` | 없음 | 1 메서드 시그니처 추가 |

---

## 5. Proposed Fix

**전체 변경 규모: ~5 LOC.** "tiny PR" 범주.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt`**
   - `ForegroundWorkScheduler` 인터페이스에 `enqueueUpload` 추가:
     ```kotlin
     /**
      * Triggers SYNC-006 immediate upload after foreground catch-up fan-out.
      * Matches [WorkScheduler.enqueueUpload] — WorkSchedulerImpl satisfies both.
      */
     public fun enqueueUpload(attempt: Int = 0)
     ```
   - `onStart` 의 `enqueueForSources(enabledSources)` 직후 1 줄 추가:
     ```kotlin
     enqueueForSources(enabledSources)
     workScheduler.enqueueUpload(attempt = 0)  // SYNC-006 + ING-011 chain
     logger.d(TAG, "onStart: SYNC-006 upload trigger enqueued after fan-out")
     ```
   - `triggerCatchUp` (pull-to-refresh) 에도 동일 1 줄 추가 (대칭).
   - **주의**: fan-out 과 upload trigger 의 순서는 **병렬 무관** — `enqueueUniqueWork` 는 즉시 반환하고, 각 소스 worker 가 Room INSERT 를 완료하기 전에 UploadWorker 가 시작될 수 있음. 그러나 UploadWorker 는 `findPendingSync` 로 시점 snapshot 을 읽으므로 source worker 완료 후 다시 REPLACE 로 re-trigger 되면 됨 — 이미 ingestion worker 가 `workScheduler.enqueueUpload` 를 각자 호출하거나 다음 주기적 run 이 보완 (invariant 3 "양쪽 모두 작동한다").
   - 실제로 source worker 완료 후 re-trigger 를 보장하려면 `WorkQuery` 로 fan-out 대기를 해야 하는데, **스펙은 fan-out 완료 대기를 요구하지 않음** — fire-and-forget 으로 충분.

2. **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**
   - 변경 없음. `WorkScheduler.enqueueUpload` 가 이미 `ForegroundWorkScheduler.enqueueUpload` 시그니처를 만족. Kotlin compiler 가 단일 `override fun enqueueUpload` 로 bridge.

3. **`android/app/src/test/java/com/becalm/android/worker/ForegroundCatchUpSchedulerTest.kt`**
   - 기존 테스트 갱신: `onStart` 호출 시 `workScheduler.enqueueUpload(0)` 가 정확히 1회 호출되는지 mock 검증 추가.
   - `triggerCatchUp` 호출 시 동일 검증.
   - `enabledSources.isEmpty()` 분기에서는 `enqueueUpload` 가 호출되지 않는지 검증 (아무것도 INSERT 된 바 없으므로 Railway call 을 트리거할 이유 없음).

### 5.2 Files to add

없음.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "workScheduler.enqueueUpload" android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt | wc -l` ≥ 2 (onStart + triggerCatchUp)
- [ ] **Grep invariant**: `grep -n "enqueueUpload" android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt | wc -l` ≥ 3 (interface 선언 + 2 호출)
- [ ] **Unit test**: `ForegroundCatchUpSchedulerTest — onStart triggers enqueueUpload after fan-out` 통과
- [ ] **Unit test**: `ForegroundCatchUpSchedulerTest — triggerCatchUp triggers enqueueUpload after fan-out` 통과
- [ ] **Unit test**: `ForegroundCatchUpSchedulerTest — no enabled sources: enqueueUpload NOT called` 통과
- [ ] **Manual**: SYNC-006 acceptance 절차 — 앱을 background 후 Gmail 어댑터 fake 서버로 신규 메일 1 건 Room INSERT → app foreground 진입 → Room `commitments + raw_ingestion_events` 의 sync_status 가 15분 기다리지 않고 즉시 synced 로 전이되는지 확인
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- 주기적 UploadWorker 주기 변경 (15분 floor) — 본 PR 과 무관
- UploadWorker 내부 batch / chunk 로직 — 본 PR 은 trigger 경로만 추가
- SYNC-006 precondition 의 "Supabase JWT 유효" 검증 — UploadWorker.doWork() 내부에서 이미 수행 (`UploadWorker.kt:90-96`)
- fan-out 완료 대기 후 upload trigger — 스펙이 요구하지 않음 (fire-and-forget)
- Unique work name 문자열 변경 (`sync.upload` → `sync-all-upload`) — 스펙 문자열은 예시로 해석, 기존 `UniqueWorkKeys.UPLOAD` 재사용

---

## 8. Dependencies

- **Blocked by**: 없음. `enqueueUpload(attempt)` 는 이미 `WorkScheduler` 에 구현됨.
- **Blocks**:
  - `feat/worker/coldsync` (본 플랜 문서 5 번) — COLD-001 의 "동시에 SYNC-006 즉시 업로드도 큐잉" 경로가 본 PR 을 전제.
- **병렬 가능**:
  - 다른 모든 worker 플랜과 파일 겹침 없음. fix/worker/sync/quarantine-chunk-split 는 RawEventUploader 만 건드리므로 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

1 줄 변경 revert. `ForegroundCatchUpScheduler.onStart` 가 이전처럼 fan-out 만 수행하는 상태로 돌아감. 데이터 손실 없음 — 주기적 UploadWorker 가 15분 이내 fallback 처리 (SYNC-004).

---

## Appendix — Session handoff notes

- **1 줄 수정 tiny PR** — 리뷰 부담 최소. 하지만 finding 의 블로커 영향(SYNC-006 미충족) 은 Medium severity.
- SYNC-006 스펙이 `REPLACE` 정책을 명시 → `WorkScheduler.enqueueUpload` 가 이미 REPLACE 로 enqueue 하므로 추가 작업 불필요.
- fan-out 과 upload trigger 가 **동일 scope.launch 블록** 안에 있어 실행 순서 보장. 한 쪽 실패해도 다른 쪽은 영향 없음 (각자 WorkManager queue 에 enqueue 후 반환).
- `enqueueUpload(attempt = 0)` 의 `attempt=0` 은 SP-32 의 논리적 attempt 카운터 (`UploadWorker.kt:165-167` 참조). foreground trigger 는 항상 fresh retry 이므로 0 이 정답.
- **테스트 fake 에서 주의**: `FakeWorkScheduler` 가 `ForegroundWorkScheduler` 인터페이스를 구현하는지 확인. 현재 test fake 가 `WorkScheduler` 만 구현한다면 `enqueueUpload` 는 이미 있음 — 단순히 `ForegroundWorkScheduler.enqueueUpload` override 가 동일 구현을 가리키도록 보장.
- pull-to-refresh (TDY-009) 경로는 `CommitmentManagementViewModel` / `TodayViewModel` 이 `foregroundCatchUpScheduler.triggerCatchUp()` 를 호출 — 이 경로도 본 PR 로 SYNC-006 chain 이 닫힘.
- **주의**: SYNC-006 이 "pending 레코드 존재" 를 precondition 으로 명시하지만, `ForegroundCatchUpScheduler` 에서는 Room 을 사전 조회하지 않고 fire-and-forget. `UploadWorker.doWork()` 가 `findPendingSync` 로 0 건이면 조용히 Success 반환하므로 over-trigger 비용 무시 가능 (Railway 호출 없음).
