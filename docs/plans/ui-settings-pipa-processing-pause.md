# UI / Settings / PIPA Processing Pause — 데이터 처리 일시중단 (PIPA-004 처리정지권)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행)
**Severity**: Critical (PIPA 제37조 처리정지권 — MVP 법규 블로커)
**Type**: Gap (ProcessingPauseScreen 부재, DataStore key 없음, 6 worker 의 doWork early-return 없음, 영구 배너 없음)

---

## 1. Finding

PIPA-004 는 **사용자가 일시적으로 모든 백그라운드 처리를 중단** 할 수 있어야 하며, 모든 주기 worker 의 `doWork()` 초반에서 `processing_paused` 를 확인해 즉시 `Result.success()` 로 빠져야 한다.

현재:
```bash
grep -rn "processing_paused\|processingPaused" android/app/src/main/java/
# → 0 hits
```
**DataStore key 도, Switch UI 도, worker early-return 도 일체 없음**. spec invariant ("hard stop 이 아닌 worker-level skip … 재개 시 catch-up sync 자동 트리거", `.spec/pipa-rights.spec.yml:89`) 정면 위반.

본 PR 은 **큰 범위** 의 sub-PR 이다 — 7 개 worker (+ ContentObserver) 전수 수정이 필요. 영향 범위가 넓으므로 애초 우려한 "PIPA umbrella 가 너무 크다" 의 주요 원인. 따라서 별도 sub-doc 으로 분리했다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:46-54` — PIPA-004 전문
> "[데이터 처리 일시중단] — 사용자가 일시적으로 모든 백그라운드 처리를 중단한다 (ex: 휴직·휴가 기간). DataStore processing_paused=true 설정 시 WorkManager의 모든 주기 worker가 건너뛰고 ContentObserver는 등록 유지하되 이벤트를 큐잉하지 않는다 (REGISTRATION만 유지). Railway로의 신규 업로드도 차단. 재개 토글 off 시 catch-up sync 자동 트리거"
>
> "Switch on 시 AlertDialog '처리를 일시중단하면 새 녹음·이메일·캘린더 이벤트가 자동으로 수집되지 않습니다. 기존 데이터는 유지됩니다. 재개까지 얼마나 걸릴지 추정치를 알려주세요(선택).' → 확인 시 DataStore processing_paused=true, pause_started_at=now(). 모든 주기 worker(VoiceUploadWorker·EmailPollingWorker·CalendarPollingWorker·OverdueSweepWorker·RetentionSweepWorker·CommitmentExtractionWorker)의 doWork() 진입부에서 processing_paused 체크 → Result.success() 즉시 반환. ContentObserver(RecordingFileObserver)는 등록 유지하되 onChange()에서 early return. SettingsScreen·TodayTimelineScreen 상단에 영구 배너 '처리 일시중단 중 — N일째'. off 시 DataStore processing_paused=false + ING-011 catch-up sync 즉시 트리거"

### 2.2 `.spec/pipa-rights.spec.yml:89` — invariant
> "[데이터 처리 일시중단]은 hard stop이 아닌 worker-level skip이며 재개 시 catch-up sync가 자동 트리거된다"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 감사 로그
> "action: 'processing_pause' | 'processing_resume' … DataStore pipa_action_log append"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `processing_paused` 키 + early-return 전수 부재
```bash
grep -rn "processing_paused\|processingPaused\|PauseState\|ProcessingPauseScreen" \
  android/app/src/main/java/
# → 0 hits
```

### 3.2 대상 worker 인벤토리 (spec 명시와 실제 코드베이스 대조)
spec 나열: `VoiceUploadWorker` / `EmailPollingWorker` / `CalendarPollingWorker` / `OverdueSweepWorker` / `RetentionSweepWorker` / `CommitmentExtractionWorker`.
실제 코드:
- `android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt` ✓
- `android/app/src/main/java/com/becalm/android/worker/UploadWorker.kt` (Railway 업로드) — spec `CommitmentExtractionWorker` 역할 ≈ 이 파일.
- `android/app/src/main/java/com/becalm/android/worker/EnrichmentWorker.kt`
- `android/app/src/main/java/com/becalm/android/worker/ingestion/GmailWorker.kt`
- `android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt`
- `android/app/src/main/java/com/becalm/android/worker/ingestion/ImapNaverWorker.kt` / `ImapDaumWorker.kt`
- `android/app/src/main/java/com/becalm/android/worker/ingestion/GoogleCalendarWorker.kt` / `OutlookCalendarWorker.kt`
- `android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt`

spec `EmailPollingWorker` = Gmail / Outlook / IMAP Naver / IMAP Daum 4 파일. `CalendarPollingWorker` = Google Calendar + Outlook Calendar 2 파일. **총 early-return 추가 파일 수 ≈ 9 개**. `OverdueSweepWorker` / `RetentionSweepWorker` 는 현 코드에 없음 — MVP 범위 밖 (post-MVP).

### 3.3 ContentObserver
`android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt` 존재. spec "RecordingFileObserver" 대응: `android/app/src/main/java/com/becalm/android/worker/ingestion/ContentObserverSms.kt` + `VoiceMediaStoreProbe.kt` 가 실질.
"등록 유지 + onChange early return" 경로 없음 — 본 PR 에서 추가.

### 3.4 영구 배너
TodayTimelineScreen 상단 배너 (`PersistentFailureBanner` 유사) — `grep` 결과 `PersistentFailureBanner` 자체가 0 hit. 본 PR 은 별도 `ProcessingPausedBanner` 추가.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| DataStore key | `processing_paused`, `pause_started_at` | 없음 | 2 key 추가 |
| 화면 | ProcessingPauseScreen + Switch + 확인 dialog | 없음 | 신규 composable |
| Worker early-return | 9 worker doWork() 상단 | 없음 | 9 파일 수정 |
| ContentObserver early-return | onChange() 조기 반환 | 없음 | 2 파일 수정 |
| 영구 배너 | Today + Settings 상단 "N일째" | 없음 | 신규 composable |
| 재개 시 catch-up | ING-011 자동 트리거 | 없음 | resume flow 에 `ForegroundCatchUpScheduler` 호출 |
| 감사 로그 | pause / resume action append | 없음 | `PipaActionLogStore` append |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`**
   - `processingPausedKey = booleanPreferencesKey("processing_paused")` + `pauseStartedAtKey = longPreferencesKey("pause_started_at")`.
   - `observeProcessingPaused(): Flow<Boolean>` + `setProcessingPaused(paused: Boolean, at: Instant)` — `paused=true` 시 timestamp 동시 기록, `false` 시 key 제거.

2. **9 개 worker 파일 (위 3.2 목록)**
   - `doWork()` 진입부에:
     ```kotlin
     if (userPrefsStore.observeProcessingPaused().first()) {
         logger.d(TAG, "processing_paused — skip")
         return@withContext Result.success()
     }
     ```
   - 단, `UploadWorker` 는 "Railway 신규 업로드 차단" 에 해당하므로 포함. `EnrichmentWorker` 는 spec 명시 밖이지만 사용자 관점에서 "처리 일시중단" 이므로 동일하게 skip 하는 것이 일관적 — **CTO 확인 필요**. 본 doc 권고: 포함.

3. **`android/app/src/main/java/com/becalm/android/worker/ingestion/ContentObserverSms.kt` + `VoiceMediaStoreProbe.kt`**
   - `onChange()` 초반에 동일 check. "REGISTRATION 유지" invariant 는 `unregister` 호출 금지 의미.

4. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - `PipaProcessingPause` placeholder → 실체 `ProcessingPauseScreen`.

5. **`android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt`**
   - 상단에 `ProcessingPausedBanner` 슬롯 추가 (paused=true 일 때만 렌더).

6. **`android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt`**
   - 동일 배너 슬롯 추가.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ProcessingPauseScreen.kt`**
   - 단일 Switch + 설명 + 확인 AlertDialog. on 시 spec 문구 ("처리를 일시중단하면 …"). 입력 안 받음 (spec 의 "추정치 … (선택)" 은 MVP 범위 외 — 본 PR 은 toggle 만; 필요 시 후속).

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ProcessingPauseViewModel.kt`**
   - UiState: `paused: Boolean`, `pauseStartedAt: Instant?`, `error: String?`.
   - 의존성: `UserPrefsStore`, `ForegroundWorkScheduler` (catch-up), `PipaActionLogStore`, `Clock` (테스트용 시간 주입).
   - `onToggle(paused)`:
     - on: `setProcessingPaused(true, Clock.now())` + log append `PROCESSING_PAUSE`.
     - off: `setProcessingPaused(false, null)` + log append `PROCESSING_RESUME` + `foregroundWorkScheduler.enqueueGmailOneShotNow()` 등 6 soure catch-up 모두 트리거 (또는 `WorkScheduler.enqueueExpedited(SourceType.*)` 6 호출). 실제 catch-up 은 `ForegroundCatchUpScheduler.kickOffAll()` 단일 API 로 묶는 것을 권장.

3. **`android/app/src/main/java/com/becalm/android/ui/components/ProcessingPausedBanner.kt`**
   - 상단 배너 composable. `pausedSince: Instant` → "처리 일시중단 중 — N일째". `daysSince` 유틸 필요 (kotlinx.datetime).
   - Today / Settings 공용.

4. **`android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt`**
   - 이미 존재하는 것으로 보임 (`grep` 결과 SP-22 언급). **없다면** 본 PR 에서 `ForegroundWorkScheduler.enqueueAll(): Unit` 추가.
   - 기존 구조 그대로 사용해도 되면 `ProcessingPauseViewModel` 이 단순 위임.

5. **Tests**:
   - 각 worker `*ProcessingPausedSkipTest` (9 testsuite, 하나씩 — 또는 공통 abstract base).
   - `ProcessingPauseViewModelTest — resume 시 6 catch-up 전체 트리거 호출 확인`.
   - `ProcessingPausedBannerTest — N 일 계산` (Clock fake).

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- 본 PR 머지 후 Samsung 기기 QA: ContentObserver "REGISTRATION 유지" 가 Doze 상태에서도 유지되는지 확인 (AUTH-008 / ONB-005 관련).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant (early-return 전수)**: `grep -rn "observeProcessingPaused\|processing_paused" android/app/src/main/java/com/becalm/android/worker/ | wc -l` ≥ 9.
- [ ] **Worker tests**: 9 worker 각각에 대해 `processing_paused=true 시 Result.success + side effect 0` 통과.
- [ ] **ContentObserver tests**: `onChange` 가 paused 상태에서 DAO insert 를 트리거하지 않음 (Robolectric).
- [ ] **Unit test**: `ProcessingPauseViewModelTest — on 시 pause_started_at 기록, off 시 6 source catch-up 호출`.
- [ ] **Compose test**: `ProcessingPausedBannerTest — paused since now - 3 days → "3일째" 문구`.
- [ ] **감사 로그**: `grep -n "PROCESSING_PAUSE\|PROCESSING_RESUME\|processing_pause\|processing_resume" android/app/src/main/java/` ≥ 2 (enum + action call sites).
- [ ] **Manual**: WSL2 Windows 빌드 + Samsung 실기기에서 on → off 시 15 분 내 catch-up 이 **자동** 동작(사용자 개입 없음).

---

## 7. Out of Scope

- "재개까지 얼마나 걸릴지 추정치" 입력 UI (spec "(선택)") — post-MVP. 본 PR 은 toggle 만.
- `OverdueSweepWorker` / `RetentionSweepWorker` 구현 — 해당 worker 가 현 코드에 없음. 본 PR 은 존재하는 worker 만 대상.
- Pause 상태에서 user 가 수동 trigger (`[지금 동기화]`) 시 행동 — spec 모호 (본 plan 해석: 사용자 명시 의사 → paused 라도 허용). 확정 필요 — CTO 문의.
- 재개 시 catch-up 의 정확한 범위/윈도우 — ING-011 스펙 따름 (cold-sync 완료 이후부터).

---

## 8. Dependencies

- **Blocked by**: `ui-settings-privacy-management.md` umbrella (route placeholder 선행).
- **Blocks**: 없음.
- **파일 겹침**:
  - `UserPrefsStore.kt` — `ui-settings-pipa-consent-withdraw.md` 와 동일 파일. 두 PR 이 각자 key 추가하므로 3-way merge 자연. 모듈 브랜치 정책 덕분에 linear stack.
  - `worker/ingestion/*.kt` 6 파일 — `consent-withdraw` 의 per-source consent check 와 **동일 파일 상단에 skip block**. **같은 helper 로 통합** 권장 (Appendix).
  - `TodayTimelineScreen.kt` — `ui-error-global-banners.md` 가 여러 배너 추가. **동일 Scaffold top slot 공유** — 배너들을 `LazyColumn` 또는 `Column` 으로 쌓도록 컨테이너 설계. merge 순서 주의 또는 선행 "TodayScaffoldBannersContainer" 구조 선도 도입 권장.

- **병렬 가능**: Export / Activity log / Account deletion 과는 파일 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시 `processing_paused` key 가 DataStore 에 남아 있어도 읽는 코드가 없어져 무시됨. worker early-return 도 revert 되어 정상 동작. 사용자가 pause 상태로 revert 되면 모든 worker 가 즉시 재활성 — **기대 동작과 반대** 이지만 spec 은 "일시중단" 이 사용자 현행 의사이므로, **revert 는 기능 자체를 제거** 함을 CTO 가 알아야 함 (프로덕션 배포 전 revert 만 안전).

---

## Appendix — Session handoff notes

- **"skip check" helper 통합**: `consent-withdraw` 의 per-source consent + `processing-pause` 의 global pause 체크를 worker 상단에서 매번 두 번 쓰면 drift 위험. 제안:
  ```kotlin
  // file: worker/SkipCheck.kt
  suspend fun shouldSkip(sourceType: String, userPrefs: UserPrefsStore): SkipReason?
  ```
  각 worker 는 `shouldSkip(...)?.also { logger.d(TAG, "skip: $it"); return Result.success() }`.
  본 PR 에서 helper 를 도입하거나, consent PR 과 pause PR 중 먼저 머지되는 쪽이 도입. **합의 필요** — 두 plan doc 의 구현 세션 간 조율.
- **`enabledSources`** (UserPrefsStore:182-184) 는 현재 stub (empty set). `processing_paused` 를 `enabledSources` 의 확장으로 볼지는 별개 — 본 PR 은 독립 key 로 유지 (의미가 다름).
- **pause_started_at 타입**: Long(epoch ms) vs Instant. DataStore Preferences 는 Instant 직접 지원 X → Long 저장 + 읽을 때 `Instant.fromEpochMilliseconds`. 이미 `SourceStatusRepositoryImpl` 이 같은 패턴 사용 (참고).
- **배너 N 일 계산**: `Clock.System.now() - pauseStartedAt` 을 `inWholeDays`. 0일인 경우 "오늘부터" — UX 카피 세션에서 확정.
- **resume 시 즉시 catch-up** 이 **user 가 paused 인 기간동안 받은 raw data 를 모두 가져온다** 는 의미인지, 아니면 "다음 주기 worker 를 그냥 expedited 로 한번 당긴다" 인지 애매. spec "ING-011 catch-up sync 즉시 트리거" 는 후자 — `ForegroundCatchUpScheduler.kickOffAll()` 로 충분.
- worker early-return 이 추가되면 worker dependency graph 순서상 `UploadWorker` 도 넣어야 Railway 전송 차단됨. spec 문구 "Railway 신규 업로드도 차단" 재확인.
