# UI / Onboarding / notifications-crashlytics — POST_NOTIFICATIONS 권한 스텝 + Firebase Crashlytics `onboarding_step_failed` 인프라

**Branch**: `feat/ui/onboarding/notifications-crashlytics`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 2 — Onboarding (POST_NOTIFICATIONS 권한, Firebase Crashlytics 관측 인프라)
**Severity**: **High** (ONB-007 onboarding_step_failed 인프라 부재 → Gmail/Outlook/IMAP OAuth 실패가 관측되지 않음. Android 13+ POST_NOTIFICATIONS 미요청 → 알림·알람 실패)
**Type**: Gap (스펙 요구 인프라 미구현)

---

## 1. Finding

### 1.1 Firebase Crashlytics 부재
- `grep -rn "Firebase Crashlytics SDK\\|Firebase Crashlytics\\." android/app/src/main/` 은 comment 1줄 외 0 matches. Firebase Crashlytics SDK 미의존, 초기화 코드 없음.
- S6-F/G 의 `onboarding_step_failed` event 를 emit 할 타겟이 존재하지 않음. `Observability` 또는 유사 래퍼 클래스 미존재.

### 1.2 POST_NOTIFICATIONS 권한 스텝 부재
- Android 13 (API 33) 부터 런타임 권한이지만 OnboardingStep enum 에 해당 스텝 없음 (`OnboardingViewModel.kt:43-68`).
- `AndroidManifest.xml` 에 `POST_NOTIFICATIONS` 선언도 없음 (grep 0).

### 1.3 알람·알림 UX 영향
- `ReminderWorker` / commitment reminder notification 이 post Android 13 기기에서 silently dropped. 현재 alpha 범위에서는 알림이 동작하지 않는 user 는 reminder 실패를 인지 못 함.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/onboarding.spec.yml` ONB-007
> "온보딩 실패 이벤트가 Firebase Crashlytics 에 전송된다. OAuth 인증 실패 또는 권한 거부 발생 시 Firebase Crashlytics 에 `onboarding_step_failed` 이벤트 전송됨 (step 이름, error 포함)"

### 2.2 `.spec/onboarding.spec.yml` POST_NOTIFICATIONS
> (스펙 현재 버전에는 명시 없음 — 본 plan 이 amendment 를 트리거한다. invariant "총 12단계" 는 POST_NOTIFICATIONS 포함 시 13단계가 된다. spec amendment 는 **본 plan 범위 밖**, 별도 PR `spec/amend-onboarding-notifications.md` 로 기록.)

### 2.3 PIPA invariant (`.spec/pipa-rights.spec.yml`)
> Firebase Crashlytics 에 전송되는 payload 에 이메일·토큰·본문 같은 PII 가 포함돼서는 안 됨.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Firebase Crashlytics 미의존
- `app/build.gradle.kts` 에 `Firebase Crashlytics SDK` 없음.
- `BecalmApplication.onCreate` 에 Firebase Crashlytics.init 없음.

### 3.2 Observability 래퍼 부재
- `core/observability/` 패키지 자체가 없음.
- `Logger` (`core/util/Logger.kt`) 는 local logcat 전용 — crash/event reporting 경로 아님.

### 3.3 POST_NOTIFICATIONS 권한 미선언
- `AndroidManifest.xml` 에 permission 줄 없음.
- `NotificationPermissionScreen` composable 없음.

### 3.4 Reminder worker 는 이미 존재
- `commitment` 알림 경로는 `ReminderWorker` / `NotificationManagerCompat.notify` 로 이미 구현. POST_NOTIFICATIONS 미승인 시 notify 가 no-op 됨.

검증 grep:
```bash
grep -rn "Firebase Crashlytics\\|io\\.crashlytics" android/app/
grep -rn "POST_NOTIFICATIONS" android/app/src/main/
grep -rn "Observability\\|AnalyticsClient" android/app/src/main/java/com/becalm/android/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| Firebase Crashlytics SDK | 초기화됨 | 미의존 | build.gradle + init |
| Observability 래퍼 | `ObservabilityClient` (captureEvent, addBreadcrumb) 인터페이스 + Firebase Crashlytics impl | 없음 | 신규 |
| DI | `@Inject ObservabilityClient` | — | 추가 |
| onboarding_step_failed emit | `ObservabilityClient.captureOnboardingStepFailed(step, errorCode)` | — | 헬퍼 신규 |
| POST_NOTIFICATIONS 권한 | manifest + runtime request step | 둘 다 없음 | permission + Compose step |
| OnboardingStep enum | `NOTIFICATION_PERM` 추가 | 12 스텝 | enum 확장 |
| ColdSync 전 알림 허가 | 있음 | 없음 | 순서 편입 (Battery 앞) |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`app/build.gradle.kts`** — `implementation("Firebase Crashlytics SDK:7.+")` (버전은 lib 매뉴얼 검증). `Firebase Crashlytics configuration follows google-services.json`. Debug 빌드는 optional (DSN empty 면 `ObservabilityClient.Noop` 사용).
- **`BecalmApplication.kt`** — `onCreate` 에서 `Firebase Crashlytics is initialized by Firebase at app startup`. PIPA: `beforeSend` callback 에 email/token pattern regex strip.
- **`core/di/ObservabilityModule.kt`** — `@Provides @Singleton fun provideObservabilityClient(...): ObservabilityClient` — DSN 존재 시 `Firebase CrashlyticsObservabilityClient`, 아니면 `NoopObservabilityClient`.
- **`AndroidManifest.xml`** — `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` (SDK 33+ 자동 적용).
- **`ui/onboarding/OnboardingViewModel.kt`** —
  - enum `OnboardingStep` 에 `NOTIFICATION_PERM` 추가 (BATTERY_OPT 앞).
  - `@Inject ObservabilityClient` DI.
  - 신규 `fun reportOnboardingStepFailed(step: OnboardingStep, errorCode: String)` — `observability.captureMessage("onboarding_step_failed", mapOf("step" to step.name, "error_code" to errorCode))`.
  - Downstream S6-F/G/H 가 이 메서드를 호출.
- **`ui/navigation/BecalmNavHost.kt`** + **`Routes.kt`** — `OnboardingNotificationPerm` route 추가, navigation 순서에 편입.

### 5.2 Files to add
- **`core/observability/ObservabilityClient.kt`** — interface. 4 메서드:
  ```
  fun captureMessage(message: String, tags: Map<String, String>)
  fun captureException(t: Throwable, tags: Map<String, String>)
  fun addBreadcrumb(category: String, message: String, data: Map<String, String>)
  fun setUserScope(userId: String?)  // pseudonymous only
  ```
- **`core/observability/Firebase CrashlyticsObservabilityClient.kt`** — impl wrapping `Firebase Crashlytics SDK.Firebase Crashlytics`.
- **`core/observability/NoopObservabilityClient.kt`** — debug/DSN 미설정 fallback.
- **`ui/onboarding/NotificationPermissionScreen.kt`** — Compose. Android 13+ 에서 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` 로 POST_NOTIFICATIONS. SDK 32 이하는 skip 자동 처리 (step status=SKIPPED).
- **tests**:
  - `OnboardingViewModelTest.reportOnboardingStepFailed_capturesMessageWithTags` — Fake ObservabilityClient.
  - `Firebase CrashlyticsObservabilityClientTest` — `beforeSend` 가 email 문자열을 strip 하는지 unit 테스트.

### 5.3 Files to delete (dead code)
- 없음.

### 5.4 Non-code changes
- **Spec amendment** (별도 PR): `.spec/onboarding.spec.yml:110` invariant 에 NOTIFICATION_PERM 추가 → "총 13단계".
- **CI secret**: Firebase Crashlytics google-services config — CTO 가 CI 에 등록 (본 plan 범위 밖).
- **Privacy policy**: Firebase Crashlytics 사용 고지 — 본 plan 범위 밖 (법무팀).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "ObservabilityClient" android/app/src/main/java/com/becalm/android/core/observability/ | wc -l` ≥ 3 (interface + 2 impl)
- [ ] **Grep invariant**: `grep -rn "POST_NOTIFICATIONS" android/app/src/main/ | wc -l` ≥ 2 (manifest + screen)
- [ ] **Grep invariant**: `grep -n "NOTIFICATION_PERM" android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt | wc -l` ≥ 1
- [ ] **Unit test**: `OnboardingViewModelTest.reportOnboardingStepFailed_capturesMessageWithTags` 통과
- [ ] **Unit test**: `Firebase CrashlyticsObservabilityClientTest.beforeSend_stripsEmailPattern` 통과
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual** (Android 13+): Notification permission step 에서 system dialog 출현. 허용 / 거부 모두 다음 스텝으로 진행.
- [ ] **Manual** (Android 12-): Step 자동 SKIPPED 마킹.

---

## 7. Out of Scope

- **PIPA action log 에 Firebase Crashlytics event 동시 append** — W7 범위.
- **Crash reporting 자동 업로드 동의 UI** — 약관 화면 / spec amendment 범위.
- **Firebase Crashlytics** — Firebase Crashlytics 대체재. 선택은 이미 Firebase Crashlytics 로 확정 (ONB-007).
- **Gmail/Outlook/IMAP 실패 실제 호출** — S6-F/G/H 각 plan. 본 plan 은 **인프라** 만.
- **Battery optimization step 동기 로직** — 본 plan 의 NOTIFICATION_PERM 은 BATTERY_OPT 앞에 삽입되나 BATTERY_OPT 자체 변경 없음.

---

## 8. Dependencies

- **Blocked by**: 없음.
- **Blocks**:
  - S6-D/F/G/H (onboarding screens) — Firebase Crashlytics breadcrumb / captureMessage 를 본 plan 의 `ObservabilityClient` 로 dispatch.

merge 순서: **본 plan → S6-D → S6-F → S6-G → S6-H**. 같은 `feat/ui/onboarding` 브랜치에 순차 커밋.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
- POST_NOTIFICATIONS permission 선언은 manifest 에서 제거 — Android 13+ 에서 reminder notification 이 조용히 dropped.
- Firebase Crashlytics 의존성 제거 — 이미 수집된 event 는 Firebase Crashlytics 서버에 남음 (rollback 으로 삭제되지 않음).

---

## Appendix — Session handoff notes

- **왜 `ObservabilityClient` 인터페이스로 감싸는가**: (1) DSN 미설정 debug 빌드에서도 compile 가능, (2) 테스트에서 Fake 주입 용이, (3) Firebase Crashlytics → Firebase Crashlytics 로의 교체 비용 최소화. 간접비용은 class 1개.
- **`beforeSend` 에서 strip 해야 할 패턴**: `\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b` (email), Authorization header, JWT pattern `ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+`.
- **`setUserScope`**: Supabase userId UUID 는 고유하지만 PII 아님. Firebase Crashlytics scope 에 기록해도 PIPA 무위반 — 단 `accountEmail` 은 **금지**.
- **NOTIFICATION_PERM step 위치**: 권고 위치는 ColdSync 직전 (BATTERY_OPT 앞). "로그인 직후" 는 거부 시 온보딩 이탈률 증가 위험.
- **Android 12 이하 자동 SKIP**: `android.os.Build.VERSION.SDK_INT < 33` 에서 screen 가 `LaunchedEffect` 로 `viewModel.onMarkStepStatus(NOTIFICATION_PERM, SKIPPED); viewModel.onNext()` 즉시 호출.
- **spec amendment 필요성**: invariant "총 12단계" 를 13 으로 수정해야 test 가 통과. 본 plan 의 구현 후 spec PR 을 별도 진행.
- **규모 추정**: core/observability 3 파일 신규, Observability module 신규, BecalmApplication 수정, OnboardingViewModel 수정, NotificationPermissionScreen 신규, 테스트 2개. 1-1.5 세션.
