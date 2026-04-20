# Worker / Person / enrichment-periodic-observer — EnrichmentWorker가 실제로 주기 실행되도록 + ContactsContract ContentObserver 연결

**Branch**: `feat/worker/person/enrichment-periodic-observer`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Person Enrichment (ContactsContract → persons_enrichment)
**Severity**: High
**Type**: Gap + Drift

---

## 1. Finding

`EnrichmentWorker`는 KDoc에서 "periodic CoroutineWorker"라고 선언하고 (`EnrichmentWorker.kt:38-40`), spec ENR-005는 "1일 주기 PeriodicWorkRequest + foreground 진입 시 ContentObserver 재실행"을 MUST로 요구하나, 현실은:

1. **주기 실행 0건** — `WorkSchedulerImpl.enqueueEnrichment()` (`WorkSchedulerImpl.kt:97-103`)는 **OneTimeWorkRequest**로 enqueue하고 (`oneTimeExpedited` 헬퍼 사용), **`enqueuePeriodic("enrichment")` 경로도, PeriodicWorkRequest 경로도 존재하지 않음**.
2. **호출자 0건** — `grep -rn "enqueueEnrichment" android/app/src/main/` → `WorkSchedulerImpl.kt:97-103` (선언 본체) + `WorkScheduler.kt:51` (인터페이스) 두 곳뿐. **ContactsPermissionScreen에서 READ_CONTACTS GRANT 직후 호출하는 코드가 없다** — ENR-003 전제("권한 부여 직후 EnrichmentWorker가 ContactsContract.Data를 읽어 persons_enrichment Room 테이블을 채운다")가 무너진다. 사용자가 온보딩에서 [허용]을 눌러도 `persons_enrichment`는 비어 있다.
3. **ContactsContract ContentObserver 0건** — `ContentObserverBootstrap`은 `Telephony.Sms.CONTENT_URI`와 `CallLog.Calls.CONTENT_URI`만 감시 (`ContentObserverBootstrap.kt:119-125, 149-155`). `ContactsContract.Contacts.CONTENT_URI` 관찰자는 어디에도 등록되지 않아 ENR-005의 "ContentObserver로 연락처 변경을 실시간 감지"가 구현되지 않았다.

결과: spec이 요구하는 "grant 즉시 enrichment + 1일 주기 재동기화 + 실시간 변경 감지" 삼중 트리거 중 **3/3이 동작하지 않음**. 사용자 기기에는 persons_enrichment 행이 거의 생성되지 않으며, SRC-001/002의 display_name 렌더링이 항상 fallback(원본 person_ref redacted prefix)으로 빠진다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/person-enrichment.spec.yml:29-36` — ENR-003 권한 grant 직후 실행

> "id: ENR-003
>  type: lifecycle
>  description: \"CONTACTS 권한 부여 직후 EnrichmentWorker가 ContactsContract.Data를 읽어 persons_enrichment Room 테이블을 채운다 — 전화번호(E.164)/이메일 기준으로 기존 person_ref와 매칭\"
>  trigger: \"READ_CONTACTS 권한 부여됨 (온보딩 또는 설정에서)\"
>  precondition: \"Room에 person_ref='010-1234-5678' raw_ingestion_events 존재. 기기 연락처에 전화번호 '010-1234-5678'을 가진 연락처(display_name='김철수', company='ABC Corp', title='팀장') 존재\"
>  expected: \"persons_enrichment에 {person_ref='+821012345678', display_name='김철수', company='ABC Corp', title='팀장', last_synced_at=now()} 레코드 INSERT됨. source_contact_id: ContactsContract 조회 ID. Railway/Supabase로 전송 없음\""

### 2.2 `.spec/person-enrichment.spec.yml:47-54` — ENR-005 1일 주기 + ContentObserver

> "id: ENR-005
>  type: lifecycle
>  description: \"EnrichmentWorker — 1일 주기 WorkManager PeriodicWorkRequest로 연락처 변경 사항을 재동기화한다. 앱 foreground 진입 시 ContentObserver로 연락처 변경을 실시간 감지하여 즉시 재실행한다\"
>  trigger: \"WorkManager EnrichmentWorker 실행 (1일 주기) 또는 ContentObserver 연락처 변경 감지\"
>  precondition: \"READ_CONTACTS 권한 부여됨, persons_enrichment에 기존 레코드 존재\"
>  expected: \"변경된 연락처 정보 persons_enrichment에 UPSERT됨. last_synced_at 갱신됨. 삭제된 연락처: persons_enrichment 레코드 삭제됨. Railway/Supabase 전송 없음\""

### 2.3 `.spec/person-enrichment.spec.yml:87` — ContactsContract 범위 invariant

> "EnrichmentWorker는 ContactsContract.Data만 읽는다 — SMS, 통화 기록, 기타 민감 데이터 접근 없음"

ContentObserver 등록 시 `ContactsContract.Contacts.CONTENT_URI` 하나만 감시해야 함. `ContactsContract.Data.CONTENT_URI`를 감시하면 descendant notify가 더 빈번하나 범위는 같다 — 구현 선택.

### 2.4 `.spec/person-enrichment.spec.yml:85` — PIPA 온디바이스 invariant

> "persons_enrichment 데이터는 절대 Railway 또는 Supabase로 전송되지 않는다 — 온디바이스 전용 (PIPA 한국 개인정보보호법 준수)"

ContentObserver가 트리거하는 enqueue 경로도 반드시 `workScheduler.enqueueEnrichment()`(혹은 새 periodic 경로)만 호출하고 업로드 경로와 섞이지 않아야 한다.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt:97-103` — OneTime 경로

```kotlin
override fun enqueueEnrichment() {
    enqueueOneShotForKey(
        EnrichmentWorker::class.java,
        UniqueWorkKeys.ENRICHMENT,
        "enqueueEnrichment",
    )
}
```

`enqueueOneShotForKey`는 `oneTimeExpedited` 빌더를 쓴다 (`WorkSchedulerImpl.kt:229-237` → `249-259`). **PeriodicWorkRequest 경로가 없음**.

### 3.2 `android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt:51` — 인터페이스

인터페이스에 `enqueueEnrichment()` (paramless) 하나뿐. `enqueuePeriodic("enrichment")`를 호출하는 경로는 `resolveSource` (`WorkSchedulerImpl.kt:207-220`)에 "enrichment" 분기가 없어 `logger.w` 후 no-op.

### 3.3 `android/app/src/main/java/com/becalm/android/ui/onboarding/ContactsPermissionScreen.kt:47-53` — grant 콜백에서 enqueue 안 함

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    val status = if (granted) StepStatus.GRANTED else StepStatus.DENIED
    viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, status)
    navController.navigate(BecalmRoute.OnboardingGmail.path)
}
```

`granted == true` 분기에서 **`workScheduler.enqueueEnrichment()` 호출 없음**. `OnboardingViewModel.onMarkStepStatus` (`OnboardingViewModel.kt:210-215`)도 상태 플래그만 DataStore에 저장할 뿐 Worker enqueue를 하지 않음.

### 3.4 `android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt:98-156` — Contacts observer 부재

SMS/CallLog 관찰자만 등록. `ContactsContract.Contacts.CONTENT_URI` / `ContactsContract.Data.CONTENT_URI` 등록 경로 0곳:

```bash
grep -rn "ContactsContract.*CONTENT_URI\|ContactsContract.Contacts.CONTENT_URI" android/app/src/main/java/
# → EnrichmentWorker.kt 내부 lookup URI 사용만 매치. ContentObserver 등록 매치 0건.
```

### 3.5 `android/app/src/main/java/com/becalm/android/worker/EnrichmentWorker.kt:38-40` — KDoc vs 실제 스케줄링 드리프트

```kotlin
 * ## ENR-002 — Worker identity and scheduling
 * Registered as a periodic [CoroutineWorker] by the WorkScheduler (SP-32). Runs
 * on the injected IO dispatcher inside [doWork].
```

KDoc은 periodic을 주장하나 실제 enqueue 경로는 OneTime. KDoc drift.

### Grep 검증

```bash
# 호출자 0건 (본 발견 항목)
grep -rn "enqueueEnrichment" android/app/src/main/java/ | grep -v WorkScheduler
# → empty

# PeriodicWorkRequest 내 Enrichment 참조 0건
grep -rn "PeriodicWorkRequest.*Enrichment\|Enrichment.*PeriodicWorkRequest" android/app/src/main/java/
# → empty

# ContactsContract ContentObserver 등록 0건
grep -rn "registerContentObserver" android/app/src/main/java/ | grep -i "Contacts\|People"
# → empty
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 주기 실행 | PeriodicWorkRequest 1일 주기 (ENR-005) | OneTime enqueue 경로만 존재, 호출자 0 | PeriodicWorkRequest 래퍼 추가 + 앱 시작/로그인 시 enqueue |
| grant 직후 실행 | READ_CONTACTS 부여 즉시 enqueue (ENR-003) | ContactsPermissionScreen이 enqueue 호출 안 함 | grant 분기에서 `workScheduler.enqueueEnrichment()` 한 줄 추가 |
| ContentObserver | ContactsContract 변경 시 즉시 재실행 (ENR-005) | SMS/CallLog observer만 존재 | `ContactsContract.Contacts.CONTENT_URI` 감시 등록 + foreground에서만 활성 |
| KDoc 정합 | "periodic"이라고 선언 | OneTime으로 스케줄됨 | KDoc 수정 또는 구현 일치 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`**
   - 인터페이스에 명시적 주기 경로 추가:
     - `enqueueEnrichment()` — OneTime (권한 grant 직후, ContentObserver가 호출) 유지
     - `enqueueEnrichmentPeriodic()` — 신규, 1일 주기 PeriodicWorkRequest
   - 두 경로 분리 이유: OneTime은 즉시성이 필요한 trigger(권한 grant, ContentObserver 변경)가 쓰고, Periodic은 BeCalmApp.onCreate 또는 로그인 완료 시점에 1회 등록 후 OS가 자동 주기 실행.

2. **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**
   - `enqueueEnrichmentPeriodic()` 구현: `PeriodicWorkRequest.Builder(EnrichmentWorker::class.java, 1, TimeUnit.DAYS)` + `periodicConstraints` (이미 선언됨 `WorkSchedulerImpl.kt:62-65`, 단 Enrichment는 `NetworkType.NOT_REQUIRED`로 override — 연락처 조회는 네트워크 불필요) + `EXPONENTIAL backoff`.
   - `workManager.enqueueUniquePeriodicWork(UniqueWorkKeys.ENRICHMENT_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)`.
   - `UniqueWorkKeys`에 `ENRICHMENT_PERIODIC` 상수 추가 (기존 `ENRICHMENT`는 OneTime용으로 유지 — 두 요청이 동일 유니크 키를 공유하면 서로 취소함).
   - `ALL_KEYS`에 `ENRICHMENT_PERIODIC` 추가(logout 시 cancel 스윕 대상).

3. **`android/app/src/main/java/com/becalm/android/worker/UniqueWorkKeys.kt`**
   - `public const val ENRICHMENT_PERIODIC: String = "enrichment_periodic"` 추가.

4. **`android/app/src/main/java/com/becalm/android/ui/onboarding/ContactsPermissionScreen.kt`**
   - `launcher` 콜백에서 `granted == true` 분기에 `workScheduler.enqueueEnrichment()` 호출 추가. Compose-friendly하게 `OnboardingViewModel`에 위임:
     - `OnboardingViewModel.onContactsPermissionGranted()` 신규 메서드 — `workScheduler.enqueueEnrichment()` 호출 후 기존 `onMarkStepStatus(CONTACTS_PERM, GRANTED)` 수행.
   - `WorkScheduler`를 `OnboardingViewModel`에 주입 (Hilt).

5. **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`**
   - `WorkScheduler` 생성자 주입.
   - `onContactsPermissionGranted()` 신규 — `viewModelScope.launch { workScheduler.enqueueEnrichment() }` + `onMarkStepStatus(CONTACTS_PERM, GRANTED)`. 기존 `onMarkStepStatus`는 범용 write path이므로 behavior 보존.

6. **`android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt`**
   - `contactsObserver: UriFilteredObserver?` 필드 추가.
   - `registerContactsObserver()` 신규 — `READ_CONTACTS` 권한 체크 후 `ContactsContract.Contacts.CONTENT_URI`에 `notifyForDescendants=true`로 등록. `onChange` 콜백에서 `workScheduler.enqueueEnrichment()` 호출.
   - 기존 `start()`/`stop()`에 contacts observer 추가.
   - PII: SMS observer와 동일 정책 — personRef / display_name은 절대 로깅 금지, URI prefix만 매치.

7. **`android/app/src/main/java/com/becalm/android/worker/EnrichmentWorker.kt`**
   - KDoc `## ENR-002` 문단 수정 — OneTime + Periodic 두 경로 설명으로 명시 (drift 제거).

8. **`android/app/src/main/java/com/becalm/android/BecalmApp.kt`** (또는 `Application.onCreate` 책임 클래스)
   - 앱 프로세스 시작 시 (혹은 로그인 완료 후 `AuthRepository.observeSession()` 구독에서) `workScheduler.enqueueEnrichmentPeriodic()` 1회 호출. `ExistingPeriodicWorkPolicy.UPDATE` 덕분에 재호출 idempotent.

### 5.2 Files to add

없음. 기존 파일 확장으로 충분.

### 5.3 Files to delete (dead code)

- `WorkSchedulerImpl.kt`의 `enqueueEnrichment()` OneTime 경로는 **유지** — ContactsPermissionScreen과 ContentObserver가 사용. 호출자 0 상태였던 dead state는 본 PR에서 호출자가 생기면서 해소.

### 5.4 Non-code changes

- **권한**: `READ_CONTACTS` 이미 `AndroidManifest.xml:20`에 선언됨 (기존). 변경 불필요.
- **WorkManager 제약**: Periodic 경로는 `NetworkType.NOT_REQUIRED`로 override (ContactsContract는 온디바이스). `setRequiresBatteryNotLow(true)` 유지.
- **주기**: Android WorkManager는 최소 15분 floor. ENR-005 "1일" = `1, TimeUnit.DAYS` 로 선언.

---

## 6. Acceptance Criteria

- [ ] **Unit test**: `WorkSchedulerImplTest — enqueueEnrichmentPeriodic() schedules PeriodicWorkRequest with 1-day interval` — `PeriodicWorkRequest.workSpec.intervalDuration == 1.days` 검증.
- [ ] **Unit test**: `WorkSchedulerImplTest — enqueueEnrichmentPeriodic uses ENRICHMENT_PERIODIC unique key with UPDATE policy` — `enqueueUniquePeriodicWork` 인자 검증.
- [ ] **Integration test (robolectric)**: `ContactsPermissionScreenTest — launcher grant callback invokes workScheduler.enqueueEnrichment()` — MockK verify.
- [ ] **Unit test**: `ContentObserverBootstrapTest — contacts observer registers on ContactsContract.Contacts.CONTENT_URI when READ_CONTACTS granted`.
- [ ] **Unit test**: `ContentObserverBootstrapTest — contacts observer onChange triggers enqueueEnrichment()`.
- [ ] **Grep invariant**: `grep -rn "enqueueEnrichment()" android/app/src/main/java/ | wc -l` ≥ 3 (ContactsPermissionScreen 또는 ViewModel, ContentObserverBootstrap, WorkSchedulerImpl 선언 본체).
- [ ] **Grep invariant**: `grep -rn "enqueueEnrichmentPeriodic" android/app/src/main/java/ | wc -l` ≥ 2 (Application onCreate + WorkSchedulerImpl).
- [ ] **Grep invariant**: `grep -rn "ContactsContract.*CONTENT_URI" android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt | wc -l` ≥ 1.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공.
- [ ] **Manual**: 신규 설치 → 온보딩에서 Contacts 허용 → 수 초 내 `persons_enrichment` 행 생성 확인 (adb shell `sqlite3` 또는 DebugPanel).

---

## 7. Out of Scope

- **ENR-004 many-to-one**: 동일 연락처의 phone+email 각각 별도 enrichment 행 생성 — 이미 `EnrichmentWorker`가 personRef별 loop를 돌므로 암묵 지원. 별도 수정 불필요.
- **ENR-006 fallback UI 정책**: `ui-person-cards-detail-render.md` PR에서 처리.
- **ENR-007 로그아웃 wipe**: 이미 `PersonEnrichmentRepository.deleteAll()`이 존재 (`PersonEnrichmentRepository.kt:94`). 호출자(AuthRepository sign-out)는 별도 PR.
- **ENR-008 SourcesList Contacts row 권한 반영**: `ui-sources-contacts-permission.md` PR에서 처리.
- **persons_enrichment 스키마 확장** (avatar_uri / starred 등): post-MVP.
- **Bulk enrichment 배치 API**: 현재 `upsertAll` 존재 (`PersonEnrichmentRepository.kt:86`), 본 PR 범위 아님.
- **Railway `/v1/persons*` 엔드포인트 pull-to-refresh** (SRC-006): 해당 엔드포인트가 🪦 상태이므로 별도 PR에서 결정.

---

## 8. Dependencies

- **Blocked by**: 없음. 독립적으로 진행 가능.
- **Blocks**:
  - `ui-person-cards-detail-render.md` — enrichment 행이 생성되어야 display_name/company/title이 렌더링됨. 이 PR이 머지되지 않으면 해당 PR의 manual acceptance가 불가 (항상 fallback 표시).
  - `ui-sources-contacts-permission.md`와 **병렬 가능** — 파일 겹침 없음.
- **merge 순서**: 이 PR을 먼저 머지하고, 이후 UI PR 2개를 병렬 진행.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후:
- `ExistingPeriodicWorkPolicy.UPDATE` 덕에 기존 사용자의 periodic 등록이 남아 있을 수 있음 → 다음 앱 실행 시 `enqueueEnrichmentPeriodic` 호출이 사라졌으므로 자연 소멸. 명시적 정리는 `WorkManager.cancelUniqueWork(UniqueWorkKeys.ENRICHMENT_PERIODIC)`를 다음 릴리즈에 1회 포함.
- ContactsContract observer는 `stop()` 호출 시 자동 해제되므로 ghost callback 위험 없음.
- Room 스키마 변경 없음 → DB 롤백 불필요.

---

## Appendix — Session handoff notes

- **Periodic 주기 결정**: spec ENR-005는 "1일"을 명시. WorkManager periodic 최소 간격은 15분이므로 `1.days`로 명시 안전. TTL 가드 (`EnrichmentWorker.kt:267` `ENRICHMENT_TTL = 7.days`)와 별개 — TTL은 per-personRef 스킵 가드, periodic은 worker 재실행 주기. 두 가드가 중첩되어 "매일 실행되나 7일 이내 synced된 ref는 스킵" = spec 의도와 일치.
- **ContentObserver foreground vs background**: spec은 "앱 foreground 진입 시"라고 적었으나 `ContentObserverBootstrap`은 이미 `BeCalmApp.onCreate`에서 start()되어 process 생존 동안 항상 관찰. SMS/CallLog와 대칭 유지하여 process-wide 관찰이 운영상 단순함. Foreground-only가 꼭 필요하면 `ProcessLifecycleOwner` 구독으로 start/stop을 묶는 방법이 있으나, 본 MVP에서는 배터리 영향 미미하므로 process-wide 유지 권장.
- **`ContactsContract.Contacts.CONTENT_URI` vs `Data.CONTENT_URI`**: Contacts는 per-contact row 변경, Data는 per-datum(phone/email/org) 변경. Contacts로 `notifyForDescendants=true`를 쓰면 Data 변경도 함께 감지됨. 단일 observer로 양쪽 커버 가능 → `Contacts.CONTENT_URI` 권장.
- **Permission 재체크**: observer 등록 시점 이후 사용자가 설정에서 READ_CONTACTS를 revoke할 수 있음. `EnrichmentWorker`는 이미 권한 가드 (`EnrichmentWorker.kt:91-99`)가 있어 권한 소실 시 `Result.failure()` 반환. observer 등록 자체는 유지해도 무해하나, PIPA 문서화를 위해 foreground 재진입 시 권한 재확인 후 observer를 해제하는 방어적 로직 추가 검토 (본 PR 선택사항).
- **Worker 주입 이슈**: `OnboardingViewModel`에 `WorkScheduler`를 주입할 때 Hilt 모듈 `WorkSchedulerModule` (`WorkSchedulerImpl.kt:328-341`)이 이미 `@Singleton` 바인딩 — 그대로 주입 가능. 추가 모듈 불필요.
- **OneTime enqueue 경로의 존재 이유**: Periodic만 둬서는 grant 직후 "즉시 실행" 보장을 WorkManager 15분 floor가 깨뜨림. OneTime(`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`)이 즉시성 보장. 두 경로 공존이 ENR-003/005를 모두 충족.
- **WorkManager PeriodicWorkRequest의 첫 실행 지연**: 최초 enqueue 후 interval만큼 지연 후 첫 실행. 사용자가 권한을 grant하기 전 앱 시작 시 periodic만 걸어두면 첫 실행이 1일 뒤. → Application.onCreate에서 periodic을 걸어도, ContactsPermissionScreen의 OneTime enqueue가 별도로 즉시 실행을 보장해야 함. 두 경로 필수.
- **테스트 fixture**: `EnrichmentWorkerTest.kt`는 이미 권한 guard / no-session / personRef 스캔 테스트를 보유. 본 PR은 `WorkSchedulerImplTest`와 `ContentObserverBootstrapTest` 에 새 테스트 추가 중심. Worker 자체 로직은 변경 없음 (호출되기만 하면 동작).
- **주의**: `ContentObserverBootstrap.onChange`에서 `workScheduler`를 부를 때 `ForegroundWorkScheduler` 인터페이스에 `enqueueEnrichment()`가 포함되어 있는지 확인 — 현 코드는 `WorkScheduler`에만 있음. `ForegroundWorkScheduler`를 확장하거나, bootstrap 주입 타입을 `WorkScheduler`로 바꾸거나, 별도 `enqueueEnrichmentNow()`를 `ForegroundWorkScheduler`에 추가 — 3안 중 구현 시 선택.
