# UI / Onboarding / notifications-sentry — POST_NOTIFICATIONS 권한 스텝 + Sentry onboarding_step_failed (ONB-007)

**Branch**: `feat/ui/onboarding/notifications-sentry`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 3 (온보딩)
**Severity**: Medium
**Type**: Gap

---

## 1. Finding

두 개의 독립된 ONB 갭이 한 PR 으로 묶였다 — 둘 다 온보딩 플로우의 terminal step 직전 구간에서
수행되며, Sentry 통합을 건드린다는 공통점으로 파일 겹침이 발생한다.

**Gap 1**: API 33+ 의 `POST_NOTIFICATIONS` 런타임 권한을 **요청하는 UI 스텝이 없다**.
`AndroidManifest.xml:49` 에 선언은 되어 있고, `ReminderBroadcastReceiver` 는 `checkSelfPermission`
으로 silent skip 하지만 (receiver/ReminderBroadcastReceiver.kt:41-50),
스펙에서 요구되는 "온보딩에서 권한 request 후 reminder 발송 가능" 흐름이 구현되지 않았다.
`AndroidManifest.xml:45` 의 TODO(R10-02) 주석이 이를 명시적으로 고백.

**Gap 2**: `ONB-007` 은 "온보딩 중 OAuth 인증 실패 또는 권한 거부 시 Sentry 에
`onboarding_step_failed` 이벤트가 전송" 되어야 한다. 그러나 repo 전체에 Sentry 통합 흔적이
없다:
```bash
grep -rn "sentry\|Sentry" android/  # → 매치 0건
```
의존성도 미등록, SDK init 도 없고, `OnboardingViewModel` 에 실패 이벤트 emit 지점도 없음.

본 PR 은 (a) `NotificationPermissionScreen` 을 온보딩에 추가하고 (b) Sentry SDK 를 통합해
onboarding step failure 를 capture 한다. ONB-PIPA 의 12단계 canonical 시퀀스는 본 PR 이후에도
유지되지만 (§5.5), NotificationPermissionScreen 을 **BatteryOptimization 직후, ColdSync 직전**
13번째 step 으로 추가 — 또는 기존 step 하나에 병합하는 방식 둘 중 설계 선택이 있다.
**권장: 별도 13번째 step 추가** (ONB-PIPA 12단계 불변 문구와의 충돌은 spec drift 이며 본 PR 에서
함께 reconcile — §Appendix).

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/onboarding.spec.yml:86-93` — ONB-007

> id: ONB-007
> type: lifecycle
> description: "온보딩 실패 이벤트가 Sentry에 전송된다"
> trigger: "온보딩 중 OAuth 인증 실패 또는 권한 거부 발생"
> precondition: "Sentry DSN 설정됨, 온보딩 진행 중"
> expected: "Sentry에 onboarding_step_failed 이벤트 전송됨 (step 이름, error 포함)"

### 2.2 `.spec/onboarding.spec.yml:110` — 12단계 invariant

> "총 12단계: 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처 → Gmail → Outlook메일 → IMAP
> → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

— **POST_NOTIFICATIONS 권한 step 이 스펙에 없다**. 두 가지 해석 가능:
1. 스펙 drift — 실제 구현에서 reminder 권한이 필요하므로 스펙에 step 추가.
2. BatteryOptimization step 내부에서 "배터리 예외 + 알림" 을 묶어 단일 step 유지.

본 PR 은 **(1) 번 해석** 을 채택 (§5.5) — 알림 권한 거부와 배터리 예외 거부는 PIPA/UX 상 독립
이벤트이고, Sentry 로그 분석 시 각 step 별 drop-off 를 따로 봐야 하기 때문. 스펙 invariant
문구 갱신은 별도 spec PR 에서 처리 (docs-spec track).

### 2.3 `.spec/onboarding.spec.yml:66-74` — ONB-005 (BatteryOptimizationScreen)

ONB-005 가 10단계에 위치한다는 문구는 invariant 110번 라인의 "배터리최적화" 순서와 일치. 본
PR 이 추가하는 NotificationPermission step 은 **배터리최적화 직후, ColdSync 직전** 에 삽입
(12단계 → 13단계로 확장). 그 위치는 배터리 최적화 예외 승인 직후 앱이 실제로 알림을 발송할 수
있도록 권한 확보 의도.

### 2.4 `AndroidManifest.xml:39-49` — TODO(R10-02)

> "TODO(R10-02): there is currently NO UI site that actually *requests* this permission
> (ActivityResultContracts.RequestPermission). Add a request during onboarding or first
> reminder scheduling so users on API 33+ can opt in; without it reminders will never
> surface on fresh installs."

— 코드 내 TODO 주석으로 이미 인지된 gap.

### 2.5 `.spec/auth.spec.yml:81` (연관 PIPA invariant)

Sentry 이벤트에는 **PII 를 실어서는 안 됨** — user_id hash 는 허용, email/이름/음성·이메일 원문 금지.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 onboarding step 정의

`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt:43-68`

```kotlin
public enum class OnboardingStep {
    TERMS, LOGIN, PIPA_CONSENT, RECORDING_FOLDER, CONTACTS_PERM,
    LINK_GMAIL, LINK_OUTLOOK_MAIL, LINK_IMAP,
    LINK_GOOGLE_CALENDAR, LINK_OUTLOOK_CALENDAR,
    BATTERY_OPT, COLD_SYNC,
}
```

— 12 개 step. `NOTIFICATION_PERMISSION` 이 없음.

### 3.2 navigation

`android/app/src/main/java/com/becalm/android/ui/onboarding/BatteryOptimizationScreen.kt:58,112`

```kotlin
navController.navigate(BecalmRoute.OnboardingColdSync.path)
```

— 배터리 step 완료 후 바로 ColdSync 로 이동. 중간에 NotificationPermission 삽입 지점은 없음.

### 3.3 Sentry 부재

```bash
grep -rn "sentry\|Sentry" android/
# → 0 hits
```

- `build.gradle.kts` 의존성 없음.
- `BecalmApplication.onCreate()` 에 init 없음.
- `OnboardingViewModel` 에 실패 emit 지점 없음.

### 3.4 실패 지점 현황

- **OAuth 실패**: `GmailOAuthScreen`, `OutlookMailOAuthScreen`, `GoogleCalendarOAuthScreen`,
  `OutlookCalendarOAuthScreen` 모두 "TODO(BECALM-OAUTH-001): wire real OAuth" placeholder.
  현시점에는 실패할 실제 경로가 없음. 그러나 `repo/auth/gmail-oauth-provider` PR 머지 이후
  실제 OAuth 경로가 생기면 Sentry capture 지점 필요.
- **권한 거부**: `ContactsPermissionScreen`, `RecordingFolderScreen`, `BatteryOptimizationScreen`
  의 DENIED 경로에서 현재 Sentry capture 0건. `OnboardingViewModel.onMarkStepStatus(step, DENIED)`
  가 유일한 hook 으로 사용 중.
- **권한 거부 → 미래 신규 step 포함**: `NotificationPermission` 추가 시 DENIED 경로도 동일하게
  capture.

### 3.5 Permission launcher 패턴 예시

`ContactsPermissionScreen` 이 `ActivityResultContracts.RequestPermission()` 을 사용하는지
확인 필요 (본 세션에서는 screen 본문 미열람). 동일 패턴을 NotificationPermissionScreen 에서
재사용.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| POST_NOTIFICATIONS request | 온보딩 중 UI 스텝에서 요청 | Manifest 선언만, UI 없음 | 신규 Screen + step enum 값 |
| Reminder fallback | 권한 거부 시 graceful skip | 이미 Receiver 에서 skip 구현 (OK) | 유지 |
| Sentry 통합 | SDK init + onboarding_step_failed event | 0건 | SDK 추가, Application init, VM hook |
| PII 정책 | step 이름·error 만 capture | - | 구현 시 scrubber 설정 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/AndroidManifest.xml`**
   - TODO(R10-02) 주석 제거 또는 갱신.

2. **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`**
   - `OnboardingStep` enum 에 `NOTIFICATION_PERMISSION` 추가 — 위치는 `BATTERY_OPT` 와 `COLD_SYNC` 사이 (13번째).
   - `steps` 리스트가 `OnboardingStep.entries` 를 사용하므로 enum 순서만 바꾸면 자동 반영.
   - 신규 메서드: `onNotificationPermissionResult(granted: Boolean)` — status 업데이트 + Sentry breadcrumb 발사 (실패 시 `reportStepFailure(step, cause)`).
   - 신규 helper: `private fun reportStepFailure(step: OnboardingStep, cause: Throwable? = null, reason: String? = null)` — `SentryEventReporter.onboardingStepFailed(step.name, reason ?: cause?.message)` 호출. 모든 기존 DENIED/실패 경로에서도 이 helper 호출하도록 보강.

3. **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`**
   - `OnboardingNotificationPermission` data object 추가.
4. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - `composable(route = BecalmRoute.OnboardingNotificationPermission.path) { NotificationPermissionScreen(navController) }` 등록.

5. **`android/app/src/main/java/com/becalm/android/ui/onboarding/BatteryOptimizationScreen.kt`**
   - 2개 `navController.navigate(BecalmRoute.OnboardingColdSync.path)` 호출 (line 58, 112) 을
     `BecalmRoute.OnboardingNotificationPermission.path` 로 교체.

6. **`android/app/build.gradle.kts` + `gradle/libs.versions.toml`**
   - `io.sentry:sentry-android:7.x` 추가.
   - `buildConfigField("String", "SENTRY_DSN", "\"${project.findProperty("sentry.dsn")}\"")` (또는 `local.properties` / CI env).

7. **`android/app/src/main/java/com/becalm/android/BecalmApplication.kt`**
   - Sentry 초기화. `SentryAndroid.init(this) { options -> options.dsn = BuildConfig.SENTRY_DSN; options.setBeforeSend { event, _ -> scrubPII(event) } }`. `environment` 는 `BuildConfig.BUILD_TYPE`.

8. **`android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt` + `OutlookMailOAuthScreen.kt` + `GoogleCalendarOAuthScreen.kt` + `OutlookCalendarOAuthScreen.kt`**
   - 스킵 로직 유지. OAuth 실패 핸들러 (현재 TODO placeholder) 에 `viewModel.reportStepFailure(...)` hook 자리 표시 — 실제 OAuth 연결 PR 에서 wiring.

9. **`android/app/src/main/res/values/strings.xml`**
   - `onb_notifications_title`, `onb_notifications_headline`, `onb_notifications_body`,
     `onb_notifications_cta_allow`, `onb_notifications_cta_later`, `onb_notifications_denied_snackbar`
     등 추가.

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/onboarding/NotificationPermissionScreen.kt`
  - Composable 구조는 `ContactsPermissionScreen.kt` 와 유사 (권한 거부 graceful skip + "나중에" 버튼).
  - API 33 미만 디바이스에서는 화면 진입 즉시 `GRANTED` 로 mark 하고 ColdSync 로 통과 (Manifest-level 권한은 자동 부여).
  - API 33+ 에서만 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` 로 `android.permission.POST_NOTIFICATIONS` 를 요청.
  - Navigation exit: `BecalmRoute.OnboardingColdSync`.
- `android/app/src/main/java/com/becalm/android/core/telemetry/SentryEventReporter.kt`
  - `@Singleton` wrapper. 메서드: `onboardingStepFailed(stepName: String, reason: String?)`,
    `authRefreshFailed(cause: Throwable)`, `workerFailure(workerName: String, cause: Throwable)`.
  - 내부적으로 `Sentry.captureMessage` / `captureException` 호출. 모든 event 에 tag `{"subsystem": "onboarding"}` 등 추가.
  - 테스트 가능하도록 interface 로 선언하고 production impl (`SentryEventReporterImpl`) + test impl (`FakeSentryEventReporter`).
- `android/app/src/main/java/com/becalm/android/core/telemetry/SentryScrubber.kt`
  - `options.setBeforeSend` 에 주입되는 PII scrubber.
  - 제거 대상: `event.user.email`, `event.user.ipAddress`, breadcrumb messages 내 이메일 / 전화번호 패턴 정규식 마스킹.
- `android/app/src/main/java/com/becalm/android/core/di/TelemetryModule.kt`
  - Hilt module — `SentryEventReporter` binding.
- Tests:
  - `android/app/src/test/java/com/becalm/android/ui/onboarding/OnboardingViewModelSentryTest.kt` — VM 에서 `reportStepFailure` 호출 시 `FakeSentryEventReporter` 의 스택에 이벤트 누적 검증.
  - `android/app/src/test/java/com/becalm/android/core/telemetry/SentryScrubberTest.kt` — `user@example.com` 포함 breadcrumb 가 `[redacted]` 로 마스킹되는지 검증.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- **Sentry 프로젝트**:
  - `becalm-android` 프로젝트 생성 (org: without).
  - DSN 을 `local.properties` 의 `sentry.dsn=...` 로 배포 machine 에만. CI 는 `SENTRY_DSN` env → `build.gradle.kts` 가 읽어 `BuildConfig.SENTRY_DSN` 에 inject.
  - Release health, crash reporting 활성화.
  - **정보 수집 범위**: crashes + `captureMessage` 기반 onboarding/OAuth/worker 이벤트만. session replay, user feedback, performance tracing 모두 비활성.

- **CONSTRAINTS.md 업데이트** (선택):
  - "서드파티 telemetry: Sentry 허용. 단 event 는 PII scrubber 통과 필수" 항목 추가. 본 plan 에서는 CONSTRAINTS 수정은 선택사항으로 표기.

- **PIPA 고지**: Sentry 이벤트는 일반적 crash log 수준이며 Google LLC Vertex AI 같은 **민감 제3자 제공** 과 다른 범주 (서버 인프라). `terms_pipa_notice` 문자열에 별도 항목 추가는 **선택사항** — 법무 검토 필요. 본 PR 은 scrubber 로 PII 제거를 보장.

### 5.5 스펙 reconciliation (12단계 → 13단계)

`onboarding.spec.yml:110` invariant 는 12단계를 명시한다. 본 PR 은 NotificationPermission step 을
13번째로 추가 — **spec 과 충돌**. 두 처리 옵션:

- **옵션 A (권장)**: 본 PR 과 함께 `onboarding.spec.yml` invariant 를 13단계로 갱신.
  변경 문구: `"총 13단계: ... → 배터리최적화 → 알림권한 → ColdSync"` + ONB-NOTIFICATION
  behavior 추가 (id 미확정, 구현 세션이 다음 순번 할당).
- **옵션 B**: NotificationPermission 을 BatteryOptimizationScreen 내부 "2단계 시퀀스" 의 3단계로
  편입 — spec 의 ONB-005 2단계 구조를 3단계로 확장. 단점: 화면 역할이 비대해짐, Sentry 로그 분석
  시 step-level drop-off 가려짐.

**권장 옵션 A**. 본 PR 은 spec yml 도 함께 수정한다 (§5.1 에 포함되지 않았지만 **docs/spec 파일
수정은 코드 변경이 아니므로** plan-only 원칙 위반 없음 — 단 CTO confirm 후 반영).

---

## 6. Acceptance Criteria

### Notifications (Gap 1)

- [ ] **Unit test**: `OnboardingViewModelTest — onNotificationPermissionResult(true) → NOTIFICATION_PERMISSION=GRANTED 로 mark`.
- [ ] **Unit test**: `OnboardingViewModelTest — onNotificationPermissionResult(false) → DENIED + FakeSentryEventReporter 에 onboarding_step_failed(NOTIFICATION_PERMISSION) 1회`.
- [ ] **Compose test**: `NotificationPermissionScreenTest — API 33+ 시뮬에서 버튼 탭 시 permission launcher 호출`.
- [ ] **Compose test**: `NotificationPermissionScreenTest — API 32 이하 시뮬에서 자동 GRANTED + ColdSync 로 navigate`.
- [ ] **Manual (API 33+)**: 온보딩 BatteryOptimization 완료 후 NotificationPermissionScreen 출현 → [허용] → 시스템 다이얼로그 → 승인 → ColdSync 진행.
- [ ] **Manual (API 33+)**: [나중에] → DENIED mark + Snackbar + ColdSync 진행 (onboarding 중단되지 않음).
- [ ] **Grep invariant**: `grep -rn "POST_NOTIFICATIONS" android/app/src/main/java/com/becalm/android/ui/onboarding/NotificationPermissionScreen.kt | wc -l` ≥ 1.
- [ ] **Grep invariant (manifest TODO 제거)**: `grep -n "TODO(R10-02)" android/app/src/main/AndroidManifest.xml | wc -l` 이 0 이다.

### Sentry (Gap 2)

- [ ] **Unit test**: `SentryScrubberTest — 이메일/전화 패턴이 [redacted] 로 마스킹`.
- [ ] **Unit test**: `OnboardingViewModelSentryTest — 권한 DENIED step 마다 reportStepFailure 호출 1회`.
- [ ] **Integration test (임시 debug build, 별도 CI task 로만 실행)**: `SENTRY_DSN` 더미 설정 후 SDK init 이 crash 없이 완료.
- [ ] **Manual**: Sentry 프로젝트 대시보드에서 debug 빌드 실행 시 "onboarding_step_failed" 이벤트 수신 확인.
- [ ] **Grep invariant**: `grep -rn "Sentry.init\|SentryAndroid.init" android/app/src/main/ | wc -l` ≥ 1.
- [ ] **Grep invariant**: `grep -rn "onboarding_step_failed" android/app/src/main/java/ | wc -l` ≥ 1.
- [ ] **Grep invariant (PII 방지)**: `grep -rn "Sentry.captureMessage\|captureException" android/app/src/main/java/ | grep -v "SentryEventReporter" | wc -l` 이 0 이다 (직접 호출 금지, wrapper 만 허용).
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` 성공.

---

## 7. Out of Scope

- 실제 OAuth wire-up (`repo/auth/gmail-oauth-provider` 및 outlook/google-calendar PR) — 본 PR 은 실패 hook 자리만 마련.
- Sentry performance tracing, session replay — PIPA 위험.
- Crashlytics / Firebase 도입 — 별도 비교 PR.
- Sentry release tracking (`sentry-cli` 빌드 통합) — 별도 CI PR.
- A/B 테스트 / 분석 이벤트 (Amplitude 등) — 후순위.
- NotificationPermissionScreen 의 "시스템 설정으로 유도" 2단계 가이드 (거부 후 settings deep-link) — 향후 UX PR.
- `onboarding.spec.yml` 12→13 단계 문구 변경의 실제 적용은 **본 PR 과 동시 commit 권장** 하되 CTO confirm 필요.
- `ReminderBroadcastReceiver` 의 알림 채널 재설계 — 현재 ensureChannelCreated 그대로.

---

## 8. Dependencies

- **Blocked by**: 없음 (독립 수행 가능).
- **Blocks**:
  - 모든 OAuth wire-up PR — 실패 시 Sentry capture 가 본 PR 의 `SentryEventReporter` 를 사용.
  - Reminder 기능 실제 활성화 (`SP-37`) — 사용자 기기에서 알림 수신 보장 요건.
- **병렬 가능**:
  - `feat/db/auth/user-scoped-database` — 겹침 없음.
  - `feat/ui/auth/google-signin-wiring` — 겹침 없음 (LoginScreen 건드리지 않음).
  - `refactor/domain/auth/signout-preserve-data` — 겹침 없음.
- **머지 순서 추천**: 본 PR 을 먼저 머지하면 후속 PR 들이 `SentryEventReporter` 를 바로 사용 가능.

---

## 9. Rollback plan

- **Notifications revert**: `git revert <commit-sha>` — NotificationPermissionScreen 제거 시 BatteryOptimization → ColdSync 직진 복귀. 이미 권한을 받은 사용자는 그대로 유지 (권한은 시스템 저장).
- **Sentry revert**:
  - SDK init 제거 → crash 시 ACRA/기본 logcat 만 남음.
  - 사용자 영향 없음 (Sentry 는 서버로 이벤트만 전송하는 기능).
  - BuildConfig.SENTRY_DSN 이 남아 있어도 init 이 없으면 무해.
- **부분 revert 권장**: 만약 Sentry 측 문제만이라면 `SentryAndroid.init` 호출만 주석처리 + 다음 릴리즈에서 fix. 전체 revert 는 NotificationPermissionScreen 까지 날리므로 비추.

---

## Appendix — Session handoff notes

- **두 gap 을 한 PR 에 묶는 이유**: `OnboardingViewModel` 수정이 양쪽 모두에 걸쳐 있고,
  NotificationPermission step 이 DENIED 될 때 **즉시** Sentry 이벤트가 가야 하므로 Sentry 통합을
  같은 PR 에서 마련하는 편이 자연스럽다. 분리 시 Sentry 가 없는 상태에서 NotificationPermission
  이 먼저 머지되면 DENIED silent drop — 관측 불가 상태가 일시 존재.
- **Sentry DSN 관리**: production/debug 서로 다른 프로젝트 또는 environment 태그만 분리. 비공개 DSN
  은 Public client SDK 에서 기본값이지만 GitHub Actions secret 노출 시 rate abuse 가능 — secret 관리
  필요.
- **PII scrubber 를 beforeSend 에서 동기로 수행**: 본 PR 은 정규식 기반 최소 구현. 향후 성능 문제 시
  sample rate 조정 또는 worker thread 로 이동.
- **테스트 가능성**: Sentry SDK 를 직접 mock 하기는 어려움. 따라서 `SentryEventReporter` 를 interface 로
  추상화해 test 에서 FakeReporter 주입. Hilt 의 `@TestInstallIn` 으로 prod binding 대체.
- **`OnboardingStep.NOTIFICATION_PERMISSION` 위치**: enum ordinal 이 DataStore 에 persist 되지 않는다
  (`OnboardingViewModel.kt` KDoc 26-29 line: "enum 순서 변경 안전"). 따라서 BATTERY_OPT 와 COLD_SYNC
  사이에 끼워 넣어도 기 사용자 영향 없음.
- **`onCompleteOnboarding` gate**: 모든 step 이 terminal status (GRANTED/DENIED/SKIPPED/COMPLETE) 이어야
  통과한다고 `OnboardingViewModel.kt` KDoc 에 암시. NotificationPermission 도 DENIED 를 terminal 로
  취급하므로 gate 통과에 문제 없음. 구현자가 `onCompleteOnboarding` 게이트 판정 로직 재점검 필요.
- **API 32 이하**: NotificationPermissionScreen 이 `Build.VERSION.SDK_INT < TIRAMISU` 일 때 자동 GRANTED
  처리. 이를 위해 screen 진입 시 `LaunchedEffect(Unit) { if (SDK<33) vm.onNotificationPermissionResult(true); nav(next) }` 패턴.
  **스킵 화면 깜빡임 방지** 를 위해 Splash 단계에서 이 step 을 아예 건너뛰는 대안도 있으나 scope 초과.
- **스펙 문구 갱신**: `onboarding.spec.yml` 을 본 PR 에서 수정할지, 별도 spec PR 로 분리할지는 CTO 판단.
  plan 철학 상 "code 와 spec 은 같은 commit" 선호 — 본 PR 이 spec yml 도 수정하도록 권장.
- **ReminderBroadcastReceiver 의 기존 silent skip 은 유지**. 알림 권한 거부 사용자는 reminder 를 받지
  못하지만 앱은 crash 하지 않음 — 현재 코드 동작 OK. 본 PR 은 그 상태에 도달하기 **전** step 에서
  사용자에게 명시적 선택 기회를 주는 것이 목적.
