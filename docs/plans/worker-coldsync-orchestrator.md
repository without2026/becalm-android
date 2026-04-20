# Worker / ColdSync / orchestrator — 2 단계 cold sync(ColdSyncOrchestrator + ColdSyncStage2Worker) 신규

**Branch**: `feat/worker/coldsync`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1–3 (온보딩 완료 → TodayTimeline 진입, cold-path 초기화)
**Severity**: High (COLD-001..008 전체 누락 — 현재 ColdSync UI 가 `SourceStatusRepository` 를 passive observe 만 함. 자체 orchestration 부재. 30초 UX 보장 안 됨)
**Type**: Gap (umbrella — 클래스 2개 신규 + DataStore 필드 4개 + 라우팅 + UI 배너)

---

## 1. Finding

cold-sync.spec.yml 은 기존 단일-페이즈 "cold sync 완료 전 TodayTimeline 차단" UX 를 **2 단계** 로 쪼개는 스펙 업데이트 (TDY-010 대체):

- **Stage 1** — `Today-usable` 최소 데이터 세트 (calendar ±7d + 이메일 7d + user_profile bootstrap) — 목표 <30 초, 전체화면 블로킹.
- **Stage 2** — `Full history` 백필 (이메일 30d + 녹음 폴더 full scan + cursor 안정화) — 백그라운드, 비블로킹.

현재 구현은 **단일 `ColdSyncViewModel`** 하나만 존재 (`android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt:48-80`) 하며, 이는 `SourceStatusRepository.observeAll()` 을 `map` 해 진행률만 표시하는 **passive observer**. orchestration 로직은 존재하지 않는다 — 즉 **Stage 1 실행, Stage 2 백그라운드 트리거, DataStore 플래그 셋, 앱 재시작 라우팅, [나중에 하기] deferred 경로** 모두 미구현.

스펙이 요구하는 구체적 공백:

- COLD-001: 7 소스 (calendar×2 + email×4 + user_profile) 병렬 Stage 1 코루틴
- COLD-003: Stage 1 완료 시 DataStore `onboarding_completed=true` + `cold_sync_stage1_completed_at` + Stage 2 `enqueueUniqueWork('cold-sync-stage2', REPLACE)` 트리거
- COLD-004: `ColdSyncStage2Worker` 신규 — 30d 이메일 백필 + 녹음 풀 스캔 + cursor 안정화
- COLD-006: [나중에 하기] — Stage 1 을 WorkManager OneTime 으로 승격해 백그라운드 완료
- COLD-007: 재시작 시 DataStore 3 scenario 분기 라우팅 (A: ColdSyncScreen, B: Timeline + Stage 2 재enqueue, C: Timeline + stage2_deferred 유지)
- COLD-008: `onboarding_completed` 게이팅 — Stage 1 완료 또는 [나중에 하기] 중 먼저 도래하는 쪽

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/cold-sync.spec.yml:16-22` — COLD-001 Stage 1 병렬 실행

```yaml
- id: COLD-001
  type: lifecycle
  description: "ColdSyncScreen 진입 직후 Stage 1 실행 — 6개 소스 어댑터 중 high-priority 서브셋
    (google_calendar ±7일, outlook_calendar ±7일, gmail 7일, outlook_mail 7일,
    naver_imap 7일, daum_imap 7일, user_profile bootstrap)을 병렬 코루틴으로 실행.
    voice/call_recording은 Stage 1에 포함되지 않음(녹음 파일은 대용량·시간 오래 걸림)"
  expected: "Stage 1 코루틴 Scope 시작. … user_profile 어댑터: Railway GET /v1/user_profile 호출
    → Room user_profile 테이블에 UPSERT(최초 빈 row 포함). 동시에 SYNC-006 즉시 업로드도 큐잉 …
    어떤 소스도 실패해도 Stage 1 전체 실패 처리하지 않음"
```

### 2.2 `.spec/cold-sync.spec.yml:35-42` — COLD-003 Stage 1 완료 + Stage 2 enqueue

```yaml
- id: COLD-003
  expected: "DataStore onboarding_completed=true, cold_sync_stage1_completed_at=ISO8601 저장.
    ColdSyncScreen finish() + TodayTimelineScreen으로 navigate(replace).
    Stage 2 Worker(ColdSyncStage2Worker) enqueueUniqueWork('cold-sync-stage2', REPLACE) 호출.
    Stage 2 Worker는 백그라운드 상수 제약(네트워크+배터리 충분)으로 실행 …"
```

### 2.3 `.spec/cold-sync.spec.yml:44-50` — COLD-004 Stage 2

```yaml
- id: COLD-004
  description: "Stage 2 백그라운드 실행 — ColdSyncStage2Worker가 (a) 이메일 30일 백필
    (b) 녹음 폴더 전체 스캔(MediaStore에서 지난 30일 .m4a 파일 목록화 + raw_ingestion_events INSERT)
    (c) 각 소스의 cursor 안정화 순차 수행"
  expected: "이메일 어댑터(gmail·outlook_mail·naver_imap·daum_imap 순차): since=now()-30d …
    녹음 폴더: MediaStore.Audio.Media CONTENT_URI 에서 RELATIVE_PATH LIKE 'Recordings/%'인 파일들을
    DATE_MODIFIED 역순 스캔 → source_type='voice'|'call_recording' raw_ingestion_events INSERT
    (sync_status='awaiting_consent' if pipa_third_party_consent=false, else 'pending')."
```

### 2.4 `.spec/cold-sync.spec.yml:63-71` — COLD-006 [나중에 하기]

```yaml
- id: COLD-006
  expected: "ColdSyncScreen finish() + TodayTimelineScreen으로 navigate.
    DataStore onboarding_completed=true, cold_sync_stage1_deferred=true, cold_sync_deferred_at=now() 저장.
    Stage 1 코루틴은 취소되지 않고 WorkManager OneTimeWorkRequest('cold-sync-stage1-deferred')로 래핑되어
    백그라운드에서 완료될 때까지 계속 실행."
```

### 2.5 `.spec/cold-sync.spec.yml:73-80` — COLD-007 재시작 라우팅

```yaml
- id: COLD-007
  expected: "Scenario A — onboarding_completed=false, cold_sync_stage1_completed_at=null:
      ColdSyncScreen 재표시, Stage 1 처음부터 재실행
    Scenario B — onboarding_completed=true, cold_sync_stage2_completed_at=null, cold_sync_stage2_deferred=false:
      TodayTimelineScreen 정상 진입 + ColdSyncStage2Worker 자동 재enqueue
    Scenario C — cold_sync_stage2_deferred=true: TodayTimelineScreen 정상 진입, Stage 2 재시작 없음"
```

### 2.6 `.spec/cold-sync.spec.yml:82-88` — COLD-008 onboarding_completed 플래그 정의

### 2.7 `.spec/cold-sync.spec.yml:91-98` — invariants

- "Stage 1 은 7 개 작업 … voice/call_recording 은 포함되지 않음"
- "Stage 1 완료 시점 또는 [나중에 하기] 시점에 `onboarding_completed=true` 가 기록됨 — 이후 ColdSyncScreen 은 재표시되지 않음"
- "Stage 2 는 완전 백그라운드 …"
- "Stage 1·Stage 2 모두 client_event_id 멱등성 …"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `ColdSyncViewModel.kt` — passive observer only

`android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt:57-80`:
```kotlin
public val state: StateFlow<ColdSyncUiState> = sourceStatusRepository.observeAll()
    .map { statuses -> … }
    .stateIn(…)
```

→ orchestration 함수 (Stage 1 시작, Stage 2 enqueue, deferred 처리) 없음.

### 3.2 ColdSync orchestrator 클래스 자체가 없음

```bash
grep -rn "class ColdSync\|ColdSyncOrchestrator\|ColdSyncStage" android/app/src/main/java/
# → ColdSyncViewModel 만 발견
```

### 3.3 `UserPrefsStore.kt` — cold-sync 관련 플래그 부재

```bash
grep -n "cold_sync\|stage1\|stage2" android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt
# → empty
```

현재 `onboarding_completed` 한 개만 존재 (line 212, 228, 230). COLD-003/006/007 이 요구하는 4 개 신규 키 모두 없음:
- `cold_sync_stage1_completed_at: Instant?`
- `cold_sync_stage1_deferred: Boolean`
- `cold_sync_stage2_completed_at: Instant?`
- `cold_sync_stage2_deferred: Boolean`

### 3.4 앱 진입 라우팅 — 2 개 플래그만 사용

`LoginScreen.kt:89`, `SplashScreen.kt:58`:
```kotlin
val destination = if (signedIn.onboardingCompleted) {
    Routes.Today
} else {
    Routes.Onboarding
}
```

→ COLD-007 scenario B (onboarding_completed=true + stage2 재enqueue) 경로 자체가 없음.

### 3.5 Stage 2 worker 상수 / unique work key 부재

`UniqueWorkKeys.kt:11-58` 에 `COLD_SYNC_STAGE1_DEFERRED`, `COLD_SYNC_STAGE2` 모두 없음.

### 3.6 `SYNC-006 foreground trigger` 누락 (문서 2) — COLD-001 의 "동시에 SYNC-006 즉시 업로드" 경로 의존

본 PR 은 `fix/worker/sync/foreground-upload-trigger` (문서 2) 머지를 전제 — Stage 1 이 source adapter 실행 후 upload trigger 를 reuse 하기 때문.

### 3.7 `user_profile` 테이블 / 어댑터 부재

```bash
grep -rn "user_profile\|UserProfileEntity" android/app/src/main/java/com/becalm/android/data/local/
# → 결과 확인 필요
```

COLD-001 은 user_profile bootstrap 을 Stage 1 필수 워크로 규정. 현재 `user_profile` Room table 미존재 시 **별도 plan 문서** 로 분리 (본 PR 에서는 Stage 1 7 번째 작업으로 "user_profile adapter" 를 선언하되, adapter 구현은 no-op stub 으로 두고 후속 PR 에서 hook).

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Stage 1 orchestrator | `ColdSyncOrchestrator` 클래스, 7 소스 병렬 | 없음 | 신규 클래스 1 |
| Stage 2 worker | `ColdSyncStage2Worker` CoroutineWorker, 이메일 30d + 녹음 scan + cursor 안정화 | 없음 | 신규 @HiltWorker 1 |
| DataStore 플래그 | 4 개 신규 (completed_at × 2, deferred × 2) | 0 | UserPrefsStore 확장 |
| Unique work keys | `COLD_SYNC_STAGE1_DEFERRED`, `COLD_SYNC_STAGE2` | 없음 | 2 상수 |
| 재시작 라우팅 | Splash/LoginScreen 에서 3 scenario 분기 | onboarding_completed 2-way 분기만 | ViewModel 확장 |
| ColdSyncScreen trigger | ViewModel 이 Stage 1 kick-off | passive observe only | orchestrator 호출 추가 |
| [나중에 하기] | Stage 1 → WorkManager OneTime 승격 | 없음 | 신규 OneTimeWork + key |
| onboarding_completed 게이팅 | Stage 1 완료 OR [나중에 하기] 중 먼저 | 현재 `OnboardingViewModel.onCompleteOnboarding` 이 전체 완료 시 | 게이팅 로직 교체 |
| Stage 2 배너 UI | TodayTimeline / Settings 상단 | 없음 | 별도 UI PR 로 분리 |

---

## 5. Proposed Fix

**대형 PR** — 스테이지 1 (orchestrator + DataStore + 라우팅) 과 스테이지 2 (Stage2Worker) 의 **논리 분리** 가 PR 리뷰의 핵심.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`**
   - 4 개 플래그 접근자 추가 (Boolean + Instant? pattern):
     ```kotlin
     public fun observeColdSyncStage1CompletedAt(): Flow<Instant?>
     public suspend fun setColdSyncStage1CompletedAt(at: Instant?)
     public fun observeColdSyncStage1Deferred(): Flow<Boolean>
     public suspend fun setColdSyncStage1Deferred(deferred: Boolean)
     public fun observeColdSyncStage2CompletedAt(): Flow<Instant?>
     public suspend fun setColdSyncStage2CompletedAt(at: Instant?)
     public fun observeColdSyncStage2Deferred(): Flow<Boolean>
     public suspend fun setColdSyncStage2Deferred(deferred: Boolean)
     ```
   - Preference key 6 개 (epoch millis long 2 + boolean 2) + 구현.
   - **주의**: `onboarding_completed` 는 기존 유지, 본 PR 에서 write 타이밍만 COLD-008 기준으로 교체.

2. **`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt`**
   - 2 개 상수 추가:
     ```kotlin
     public const val COLD_SYNC_STAGE1_DEFERRED: String = "cold_sync.stage1_deferred"
     public const val COLD_SYNC_STAGE2: String = "cold_sync.stage2"
     ```
   - `WorkSchedulerImpl.ALL_KEYS` 에 둘 다 추가 (sign-out cancel 대상).

3. **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`** + `WorkSchedulerImpl.kt`
   - 신규 메서드:
     ```kotlin
     /** COLD-006 [나중에 하기] — Stage 1 을 WorkManager 로 승격. REPLACE. */
     public fun enqueueColdSyncStage1Deferred()

     /** COLD-003 Stage 1 완료 후 Stage 2 백그라운드 트리거. REPLACE.
      *  Constraints: 네트워크 unmetered 아님 (스펙은 "네트워크+배터리 충분"). */
     public fun enqueueColdSyncStage2()
     ```
   - Impl: `NetworkType.CONNECTED + setRequiresBatteryNotLow(true)` 제약 (COLD-004 배터리 충분 요구).
   - Stage 2 는 `OneTimeWorkRequest`, EXPONENTIAL backoff.

4. **`android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt`** → **`.../ui/onboarding/ColdSyncViewModel.kt`** 로 이동 (onboarding 스크린에 더 적합).
   - passive observer → **actively driven orchestration**:
     - `init { coldSyncOrchestrator.startStage1(viewModelScope) }`
     - `onDeferClicked()` → orchestrator.deferToBackground()
     - `fun state: StateFlow<ColdSyncUiState>` 에 deferEnabled (5 초 경과 여부) 파생.

5. **`android/app/src/main/java/com/becalm/android/ui/auth/SplashScreen.kt`** + `LoginScreen.kt`
   - 라우팅 로직 교체:
     ```kotlin
     val destination = when {
         !onboardingCompleted -> Routes.Onboarding
         stage2CompletedAt == null && !stage2Deferred -> {
             // scenario B: re-enqueue stage 2 + go to Today
             workScheduler.enqueueColdSyncStage2()
             Routes.Today
         }
         else -> Routes.Today  // scenario C or fully complete
     }
     ```
   - (Composable 내부 `LaunchedEffect` 에서 DataStore 첫 emit 대기 후 분기.)

6. **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt:362`**
   - 기존 `onCompleteOnboarding` 가 `onboarding_completed=true` 를 직접 set 하는 로직 제거 (COLD-008 에 따라 `ColdSyncOrchestrator` 만 이 플래그를 set).
   - 단순 `navigate(Routes.ColdSync)` 로 전환.

7. **`android/app/src/main/java/com/becalm/android/ui/onboarding/ColdSyncScreen.kt`**
   - UI: Stage 1 진행 표시 + [나중에 하기] 버튼 (5 초 경과 후 enabled).
   - `LaunchedEffect(state.done) { if (state.done) navigate(Routes.Today) }` 와 `LaunchedEffect(state.deferClicked) { … }`.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/cold/ColdSyncOrchestrator.kt`** (신규 `cold/` package)
   - `@Singleton class ColdSyncOrchestrator @Inject constructor(...)` :
     ```kotlin
     suspend fun startStage1(scope: CoroutineScope): ColdSyncProgress {
         // 7 작업 병렬 launch:
         //   gmail7d, outlook_mail7d, naver_imap7d, daum_imap7d,
         //   google_calendar±7d, outlook_calendar±7d, user_profile_bootstrap
         // 각 Deferred 결과 집계, 실패 허용 (어떤 하나도 Stage 1 실패로 extrapolate 하지 않음)
         // 완료 시: markStage1Completed() + enqueueColdSyncStage2()
     }

     suspend fun deferToBackground() {
         // cold_sync_stage1_deferred=true + cold_sync_deferred_at + onboarding_completed=true
         // workScheduler.enqueueColdSyncStage1Deferred()
     }

     private suspend fun markStage1Completed() {
         userPrefsStore.setColdSyncStage1CompletedAt(Clock.System.now())
         userPrefsStore.setOnboardingCompleted(true)
         workScheduler.enqueueColdSyncStage2()
     }
     ```
   - 의존성: `WorkScheduler`, `UserPrefsStore`, `SourceStatusRepository`, 7 개 adapter interface (재사용: 기존 ingestion worker 들은 WorkManager 경유 — orchestrator 는 `workScheduler.enqueueExpedited(sourceKey)` 로 호출하고 `sourceStatusRepository.observeAll()` 로 완료 시점 관찰).
   - **주의**: orchestrator 는 **adapter 를 직접 호출하지 않음** — 기존 `ForegroundCatchUpScheduler` 경로를 재사용하여 중복 구현 회피. Stage 1 특유의 "7 일 cutoff" 는 각 ingestion worker 가 DataStore cursor=null 일 때 사용하는 기본 since 값을 7d 로 설정하거나, worker 에 Data input 으로 `stage = "cold1"` 을 전달해 since 계산 분기. 본 플랜에서는 후자 (input Data) 권장.
   - `user_profile_bootstrap` 은 **신규 stub** — `Railway GET /v1/user_profile` 호출 → Room `user_profile` UPSERT. user_profile 테이블 미존재 시 no-op Deferred 반환 + `user_profile` 테이블 생성은 별도 PR.

2. **`android/app/src/main/java/com/becalm/android/worker/ColdSyncStage2Worker.kt`**
   - `@HiltWorker class ColdSyncStage2Worker : CoroutineWorker`
   - Lifecycle (순차):
     1. **이메일 30d 백필**: gmail / outlook_mail / naver_imap / daum_imap 순차 호출. 각 adapter 에 `since = now() - 30d` input 전달. client_event_id 멱등 → Stage 1 과 겹치는 7d 는 중복 INSERT 없음.
     2. **녹음 폴더 풀 스캔**: `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` 에서 `RELATIVE_PATH LIKE 'Recordings/%' AND DATE_MODIFIED > (now()-30d)` 쿼리 → `source_type` 은 `Voice Recorder/` (voice) vs `Call/` (call_recording) 로 분기 → `raw_ingestion_events` INSERT. `sync_status`: PIPA third-party consent flag 에 따라 `awaiting_consent` 또는 `pending`.
     3. **Cursor 안정화**: 각 소스의 최신 cursor (historyId / UIDVALIDITY / deltaLink / syncToken / DATE_MODIFIED millis) 를 `SyncCursorStore` 에 저장. 이미 Stage 1 및 개별 워커가 cursor 를 저장하고 있으므로, 이 단계는 단순히 현재 값 유효성 검증 + no-op 인 경우가 대부분.
     4. **완료 마킹**: `userPrefsStore.setColdSyncStage2CompletedAt(Clock.System.now())`.
   - Constraints: `NetworkType.CONNECTED` + `setRequiresBatteryNotLow(true)`.
   - Data output: `stage2_emails_count`, `stage2_recordings_count`, `stage2_cursors_saved` (logging).
   - Cancellation: 사용자 `[취소하고 나중에]` 탭 시 `workManager.cancelUniqueWork(COLD_SYNC_STAGE2)` 호출. Worker 는 `isStopped` 확인 후 중단.

3. **`android/app/src/main/java/com/becalm/android/cold/ColdSyncProgress.kt`**
   - `data class ColdSyncProgress(val total: Int = 7, val completed: Int, val failed: Int, val deferEnabled: Boolean)`

4. **`android/app/src/test/java/com/becalm/android/cold/ColdSyncOrchestratorTest.kt`**
   - 케이스:
     - happy path: 7 소스 모두 성공 → onboarding_completed=true + stage1_completed_at 기록 + Stage 2 enqueued
     - 1 소스 실패: Stage 1 전체 failed 처리 X — 완료 플래그 set 유지
     - deferToBackground: onboarding_completed=true + stage1_deferred=true + Stage 1 OneTime enqueued

5. **`android/app/src/test/java/com/becalm/android/worker/ColdSyncStage2WorkerTest.kt`**
   - 이메일 30d since 파라미터 검증
   - 녹음 스캔 PIPA consent=false → awaiting_consent
   - 녹음 스캔 PIPA consent=true → pending
   - Cancel 시 isStopped 분기

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- Railway `GET /v1/user_profile` 엔드포인트 — 서버 확인. 미존재 시 본 PR 은 placeholder 로 남기고 별도 PR 진행.
- MediaStore query 용 `READ_MEDIA_AUDIO` 권한은 이미 보유 (VoiceMediaStoreProbe). 추가 권한 없음.
- DataStore 마이그레이션 — preference key 추가만이므로 자동 (DataStore 는 schema migration 이 없음).

---

## 6. Acceptance Criteria

### Stage 1

- [ ] **Grep invariant**: `grep -rn "class ColdSyncOrchestrator" android/app/src/main/java/ | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "COLD_SYNC_STAGE1_DEFERRED\|COLD_SYNC_STAGE2" android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -n "setColdSyncStage1CompletedAt\|setColdSyncStage2CompletedAt" android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt | wc -l` ≥ 2
- [ ] **Unit test**: `ColdSyncOrchestratorTest — happy path sets onboarding_completed + stage1_completed_at + enqueues Stage 2` 통과
- [ ] **Unit test**: `ColdSyncOrchestratorTest — single source failure does NOT mark overall Stage 1 failed` 통과
- [ ] **Unit test**: `ColdSyncOrchestratorTest — deferToBackground enqueues OneTimeWork + sets stage1_deferred` 통과
- [ ] **Routing test**: `SplashRoutingTest — scenario B (onboarding_completed=true, stage2=null, not deferred) → Today + Stage 2 re-enqueued` 통과
- [ ] **Routing test**: `SplashRoutingTest — scenario C (stage2_deferred=true) → Today, no re-enqueue` 통과

### Stage 2

- [ ] **Grep invariant**: `grep -rn "class ColdSyncStage2Worker" android/app/src/main/java/ | wc -l` ≥ 1
- [ ] **Unit test**: `ColdSyncStage2WorkerTest — email adapters called with since=now()-30d` 통과
- [ ] **Unit test**: `ColdSyncStage2WorkerTest — MediaStore voice file with PIPA consent=false → sync_status='awaiting_consent'` 통과
- [ ] **Unit test**: `ColdSyncStage2WorkerTest — MediaStore voice file with PIPA consent=true → sync_status='pending'` 통과
- [ ] **Unit test**: `ColdSyncStage2WorkerTest — cancel triggers isStopped handling` 통과

### End-to-end

- [ ] **Manual**: fresh install → 온보딩 완료 → ColdSyncScreen 진입 → Stage 1 7 작업 진행률 표시 → 완료 후 Timeline 진입 → Stage 2 백그라운드 실행 확인 (`adb shell cmd jobscheduler run`)
- [ ] **Manual**: Stage 2 실행 중 앱 kill → 재시작 → scenario B 확인 → Timeline 직행 + Stage 2 재enqueue
- [ ] **Manual**: [나중에 하기] 탭 → Timeline 직행 + 백그라운드에서 Stage 1 deferred OneTimeWork 실행 확인
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- **UI 배너** (TodayTimeline / Settings 상단 "초기 데이터를 가져오는 중 N%") — **별도 PR** `feat/ui/today/cold-sync-stage2-banner`. 본 PR 은 DataStore 플래그 작성까지만.
- `ColdSyncProgressSheet` 바텀시트 (COLD-005) — 별도 UI PR
- `user_profile` Room table + 엔티티 + 어댑터 — 별도 PR `feat/db/user-profile-bootstrap`. 본 PR 은 orchestrator 내 stub 만 유지.
- Railway `GET /v1/user_profile` 엔드포인트 — 서버 PR
- PIPA 3rd-party consent UI 흐름 변경 — 기존 `pipa_third_party_consent` 플래그 재사용만
- 녹음 폴더 스캔의 Samsung One UI 특수 경로 대응 — 기존 `VoiceMediaStoreProbe` 로직 재사용
- Stage 2 진행률 fine-grained percentage (이메일/녹음 각 퍼센트) — 로컬 카운터로 simplified 버전만
- overdue-sweep / quarantine / foreground-trigger — 본 PR 과 별개 (문서 1, 2, 3)

---

## 8. Dependencies

- **Blocked by**:
  - `fix/worker/sync/foreground-upload-trigger` (문서 2) — COLD-001 의 "동시에 SYNC-006 즉시 업로드" 경로가 `ForegroundCatchUpScheduler.enqueueUpload` 추가에 의존.
  - (권장) `feat/worker/sync/cursor-invalidation` (문서 4) — Stage 2 의 "cursor 안정화" 가 30d 공통 상수 `CURSOR_INVALIDATION_RESYNC_DAYS` 를 재사용하면 DRY.
  - (선택) `feat/db/user-profile-bootstrap` — 본 PR 의 user_profile stub 을 실제 동작으로 전환하려면 필수. 없어도 본 PR 의 6 작업 병렬 Stage 1 은 기능함 (7 번째는 no-op).
- **Blocks**:
  - `feat/ui/today/cold-sync-stage2-banner` — 본 PR 의 DataStore 플래그를 읽어 배너 표시
  - TDY-010 (기존 단일-페이즈 cold sync) 를 참조하는 모든 E2E 테스트 업데이트
- **병렬 가능**:
  - `feat/worker/commitment/overdue-sweep` (문서 1) — 파일 겹침 없음
  - `fix/worker/sync/quarantine-chunk-split` (문서 3) — 파일 겹침 없음 (WorkScheduler 는 `ALL_KEYS` 리스트 추가만 겹침, merge 순서로 해결)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 리스크:

1. **DataStore 플래그 writer 삭제 후 reader 잔존 문제** — 본 PR 전에는 4 개 플래그를 아무도 읽지 않았으므로 writer/reader 세트 단위로 깨끗이 revert 됨. 단 이미 production 에 쓰인 값은 DataStore 에 남음 (forward-only but harmless).
2. **onboarding_completed 플래그 의미 변경** — 본 PR 전: 전체 온보딩 완료. 본 PR 후: Stage 1 완료 or 나중에하기. Revert 하면 이미 `onboarding_completed=true` 지만 `cold_sync_stage1_completed_at=null` 인 사용자가 ColdSyncScreen 을 다시 보지 못함. 이 경우 전체 프로덕션 재설치 가이드 또는 `onboarding_completed=false` 강제 리셋 서버 플래그가 필요 — **revert 전 주의**.
3. **Stage 2 worker enqueue 중이던 작업** — `workManager.cancelUniqueWork(COLD_SYNC_STAGE2)` 를 앱 버전업 시 한번 호출하는 migration 스텝으로 정리.
4. 이미 INSERT 된 raw_ingestion_events (30d 백필) 는 client_event_id 멱등으로 중복 없이 남음 — 데이터 무결성 문제 없음.

즉 본 PR 은 **production 배포 이후 revert 어려움** 이 존재. staged rollout + 충분한 테스트 후 full rollout 필수.

---

## Appendix — Session handoff notes

### Stage 1 vs Stage 2 의 논리적 경계

- **Stage 1** = "Today 가 의미 있게 보이려면 최소 이 만큼" = 오늘/향후 1 주 캘린더 + 최근 1 주 이메일 (7 * 6 = 42 작업에 해당할 수도 있는데, 스펙은 소스당 1 작업으로 규정). voice/call_recording **완전 제외** (스펙 invariant).
- **Stage 2** = "완전한 30 일 역사 + 녹음 풀 스캔 + cursor 안정화" = 백그라운드, 비가역 UX 차단 없음.
- 두 스테이지의 **테스트 분리** 가 PR 리뷰의 핵심. `ColdSyncOrchestratorTest` 는 Stage 1 만, `ColdSyncStage2WorkerTest` 는 Stage 2 만 검증.

### 7 작업 병렬 실행의 structured concurrency

```kotlin
suspend fun startStage1() = coroutineScope {
    val jobs = listOf(
        async { runGmail7d() },
        async { runOutlookMail7d() },
        async { runNaverImap7d() },
        async { runDaumImap7d() },
        async { runGoogleCalendar7d() },
        async { runOutlookCalendar7d() },
        async { runUserProfileBootstrap() },
    )
    jobs.awaitAll()  // 한 job 이 예외 throw 해도 다른 job 계속 — supervisorScope 활용
}
```
→ `supervisorScope` 필수 (한 job 실패가 전체 cancel 유발하지 않도록).

### COLD-007 scenario 라우팅의 atomic 성

DataStore 3 플래그 (`onboarding_completed`, `cold_sync_stage2_completed_at`, `cold_sync_stage2_deferred`) 를 동시에 읽어야 scenario 결정 가능. `combine(flow1, flow2, flow3) { … }` 으로 single snapshot 확보 후 분기.

### [나중에 하기] 의 worker 전환

- 옵션 A: orchestrator 의 async job 들을 cancel 하지 않고 그대로 남긴 채 `enqueueColdSyncStage1Deferred()` 로 **별도 OneTime worker** 실행 → 2 중 실행 (in-process coroutine + worker). 스펙은 "cancel 되지 않음" 명시하므로 옵션 A 가 맞음.
- 옵션 B: coroutine cancel 후 worker 로 완전 위임.

**스펙 COLD-006**: "Stage 1 코루틴은 취소되지 않고 WorkManager OneTimeWorkRequest 로 래핑되어 백그라운드에서 완료될 때까지 계속 실행" — **옵션 A 채택**. 단 2 중 실행으로 인한 중복 INSERT 는 client_event_id 멱등이 방어 (invariant 재확인).

### 구현자 선 확인

```bash
# user_profile 테이블 존재 여부
grep -rn "user_profile\|UserProfile" android/app/src/main/java/com/becalm/android/data/local/db/
# foreground-upload-trigger PR 머지 여부
grep -n "enqueueUpload" android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt
# PIPA consent 플래그 접근자
grep -n "pipaThirdPartyConsent\|pipa_third_party" android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt
# SourceStatusRepository.observeAll 시그니처
grep -n "observeAll\|recordSyncStart" android/app/src/main/java/com/becalm/android/data/repository/SourceStatusRepository.kt
```

### "7 소스" vs 이전 스펙의 "6 소스"

기존 ING-011 은 "6 소스" (voice 포함, user_profile 제외). COLD-001 은 "7 소스" (voice 제외, user_profile 포함, calendar ±7d 는 google/outlook 각 1 작업). 총 매트릭스:

| 소스 | ING-011 | COLD-001 Stage 1 | COLD-004 Stage 2 |
|------|---------|------------------|------------------|
| google_calendar | ✓ | ✓ ±7d | - |
| outlook_calendar | ✓ | ✓ ±7d | - |
| gmail | ✓ | ✓ 7d | ✓ 30d backfill |
| outlook_mail | ✓ | ✓ 7d | ✓ 30d backfill |
| naver_imap | ✓ | ✓ 7d | ✓ 30d backfill |
| daum_imap | ✗ (ING-011 는 6 소스) | ✓ 7d | ✓ 30d backfill |
| voice | ✓ | ✗ | ✓ 녹음 풀 스캔 |
| user_profile | ✗ | ✓ bootstrap | ✗ |

→ ING-011 은 `6 소스` 표현이지만 실제로는 daum_imap 까지 포함 시 7 소스. 스펙 문서 내부 numbering 약간 혼선이 있음 — 구현 시 COLD-001 expected 의 7 작업 목록을 literal 로 따르면 safe.
