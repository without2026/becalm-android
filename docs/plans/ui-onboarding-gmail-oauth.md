# UI / Onboarding / gmail-oauth — GmailOAuthScreen 을 실제 Google Identity Services 흐름으로 교체 + 실패 시 Sentry `onboarding_step_failed`

**Branch**: `feat/ui/onboarding`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 2 — Onboarding (Gmail OAuth step, 온보딩 6단계)
**Severity**: **Critical** (사용자가 [연결] 탭 하면 실제 OAuth 없이 `LINK_GMAIL=COMPLETE` 가 기록됨 → 터미널 게이트 통과 → `gmail_connected=false` 인 채로 온보딩 완료. 이후 Gmail 어댑터는 토큰 없이 401 만 반복.)
**Type**: Gap (UI placeholder — 실제 구현 없음) + Drift (성공 경로가 실패 경로와 동일)

---

## 1. Finding

`GmailOAuthScreen.kt` 는 ONB-004 / SMG-001 의 `GmailOAuthScreen` 역할을 담당하지만 [연결] 버튼이 Google Identity Services (GIS) 호출 없이 바로 `onMarkStepStatus(LINK_GMAIL, COMPLETE)` 를 호출한다 (`GmailOAuthScreen.kt:52-56`). 주석 `// TODO(BECALM-OAUTH-001): wire real Gmail OAuth` 가 같은 위치에 명시돼 있다. 결과적으로:

1. **실제 `gmail.readonly` 스코프 토큰이 발급되지 않는다** — Keystore 에 저장될 `access_token`/`refresh_token` 이 없고, 온보딩 뒤 Gmail 어댑터가 실행되면 401 만 수신.
2. **터미널 게이트가 가짜 COMPLETE 로 통과한다** — `OnboardingViewModel.isTerminalGatePassed` (`OnboardingViewModel.kt:370-379`) 는 COMPLETE 를 유효 터미널 상태로 간주. 실패 경로도 현재 없음.
3. **ONB-007 의 Sentry `onboarding_step_failed` 가 발생하지 않는다** — 거부·실패 자체가 코드 경로에 존재하지 않기 때문.

이 plan 은 **UI 계층 wiring** 만 다룬다. 실제 토큰 공급자(`GoogleAuthTokenProviderImpl`) 구현은 `docs/plans/repo-auth-gmail-oauth-provider.md` (PR #3) 가 담당. 이 plan 은 #3 이 공개하는 suspending API 를 Compose 쪽에서 launch 하는 책임을 갖는다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/onboarding.spec.yml:56-64` — ONB-004
> "Gmail OAuth 연결 화면에서 [스킵] 탭 시 다음 온보딩 단계로 이동한다" / precondition: "온보딩 5단계 (GmailOAuthScreen), Gmail 미연결" / expected: "OutlookMailOAuthScreen으로 이동됨. DataStore **gmail_connected=false** 유지"

[연결] 성공 시의 대칭 규칙: `gmail_connected=true` 는 **OAuth 성공 시에만** 기록해야 한다 (ONB-004 의 부정 명제). 현재 코드는 이 대칭을 위반 — 가짜 COMPLETE 로 `gmail_connected` 는 기록되지 않지만 LINK_GMAIL 은 COMPLETE 로 기록된다.

### 2.2 `.spec/onboarding.spec.yml:86-93` — ONB-007
> "온보딩 실패 이벤트가 Sentry에 전송된다 … trigger: 온보딩 중 OAuth 인증 실패 또는 권한 거부 발생 … expected: Sentry에 `onboarding_step_failed` 이벤트 전송됨 (step 이름, error 포함)"

### 2.3 `.spec/onboarding.spec.yml:106` — invariants
> "온보딩은 순차 진행이며 **건너뛴 OAuth 소스는 설정 화면에서 재연결 가능**하다"

→ SKIPPED 는 터미널이되 `gmail_connected` 는 false 로 남아야 하고, Settings/Sources 에서 재연결 가능해야 한다 (재연결은 #5 `ui-sources-detail-actions-and-localization.md` 범위).

### 2.4 `.spec/onboarding.spec.yml:110` — invariants
> "총 12단계: 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처 → **Gmail** → Outlook메일 → IMAP → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

### 2.5 `.spec/contracts/ui-map.yml:44-48`
> "- path: /onboarding/gmail / screen: GmailOAuthScreen / data: [] / components: [**OAuthConnectButton**, SkipButton] / auth: required"

components 계약은 "OAuthConnectButton" — 실제 OAuth 런처와 묶인 버튼 의미. 현재의 placeholder 버튼은 이 계약을 지키지 못한다.

### 2.6 `.spec/email-pipeline.spec.yml:79` — invariant (간접)
> "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)"

→ 본문을 local-only 로 유지하려면 클라이언트가 Gmail API 를 **직접** 호출해야 함 → `gmail.readonly` scope 토큰을 디바이스에서 확보하는 플로우가 필수.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Placeholder connect 경로
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt:52-56`**:
  ```kotlin
  onConnect = {
      // TODO(BECALM-OAUTH-001): wire real Gmail OAuth
      viewModel.onMarkStepStatus(OnboardingStep.LINK_GMAIL, StepStatus.COMPLETE)
      navController.navigate(BecalmRoute.OnboardingOutlookMail.path)
  },
  ```

- **`GmailOAuthScreen.kt:33`** — class KDoc: `// TODO(BECALM-OAUTH-001): wire real Gmail OAuth flow via Google Identity Services SDK.`

### 3.2 ViewModel 에 OAuth callback 없음
- **`OnboardingViewModel.kt:370-379`** — `isTerminalGatePassed` 는 COMPLETE|SKIPPED|DENIED|GRANTED 를 전부 통과로 간주 → 가짜 COMPLETE 도 gate 통과. VM 에 `onGmailConnected(token)` / `onGmailFailed(error)` 공개 메서드 없음 (grep 0).
- `OnboardingViewModel` 은 `GoogleAuthTokenProvider` / `OAuthCredentialStore` 를 injection 하지 않음 (grep 0).

### 3.3 의존 모듈 부재
- `com.becalm.android.data.auth.GoogleAuthTokenProvider` / `OAuthCredentialStore` — 현재 코드베이스에 존재하지 않음 (grep 0). → **#3 repo-auth-gmail-oauth-provider 의 산출물**.
- `androidx.credentials:credentials` / `com.google.android.libraries.identity.googleid` — `app/build.gradle.kts` 에 의존성 미등록. → #3 plan 의 build-gradle 변경이 선행 필요.

### 3.4 Sentry 호출 지점
- `grep -rn "onboarding_step_failed" android/app/src/main/` = 0 matches.
- `grep -rn "io.sentry" android/app/src/main/` 은 core/observability 계층에 있으나 OnboardingViewModel 에서는 호출 없음.

검증 grep:
```bash
grep -n "BECALM-OAUTH-001" android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt
grep -rn "CredentialManager\|AuthorizationClient\|GoogleIdOption" android/app/src/main/java/com/becalm/android/ui/onboarding/
grep -rn "onboarding_step_failed" android/app/src/main/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| [연결] 탭 결과 | 실제 GIS + `gmail.readonly` 토큰 발급 → Keystore | `onMarkStepStatus(COMPLETE)` 즉시 호출 | 실제 OAuth 플로우 신규 추가 |
| 성공 후 상태 | `gmail_connected=true`, LINK_GMAIL=COMPLETE, 토큰 저장 | LINK_GMAIL=COMPLETE 만 | `gmail_connected` + 토큰 저장 추가 |
| 실패/거부 시 | Sentry `onboarding_step_failed` + LINK_GMAIL=SKIPPED | 실패 경로 자체 없음 | 실패 콜백 + Sentry breadcrumb 추가 |
| Connect 버튼 활성 조건 | OAuth client ID 구성돼 있을 때만 | 항상 활성 | client-id 존재 검사 gating 추가 |
| ONB-004 대칭 | [스킵] 시 `gmail_connected=false` 유지 | 현재도 false (SKIPPED → false) | 변경 없음 (regression 안 나게 테스트) |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt`** — placeholder `onConnect` 람다 제거. 대신 새 `GmailOAuthLauncher` (5.2) 를 `remember` 로 호출하고, launcher 가 emit 하는 `GmailAuthResult` (Success(token)/Denied(code)/Error(throwable)) 를 `LaunchedEffect` 로 받아 VM 메서드 dispatch. Connect 버튼은 `enabled = gmailOAuthAvailable` — `gmailOAuthAvailable` 은 `BuildConfig.GMAIL_OAUTH_CLIENT_ID` 가 empty 가 아닌지로 계산 (misconfigured debug 빌드 보호).
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`** —
  - 신규 `onGmailConnected(token: OAuthToken)`: viewModelScope 내에서 `oauthCredentialStore.save(SourceType.GMAIL, token)` → `userPrefsStore.setGmailConnected(true)` → `onMarkStepStatus(LINK_GMAIL, COMPLETE)`.
  - 신규 `onGmailFailed(error: GmailAuthError)`: `sentryClient.addBreadcrumb("onboarding_step_failed", mapOf("step" to "LINK_GMAIL", "error" to error.code))` + `sentryClient.captureMessage("onboarding_step_failed")` → `onMarkStepStatus(LINK_GMAIL, SKIPPED)`.
  - constructor 에 `OAuthCredentialStore`, `SentryClient` (또는 이미 있는 `Observability` 래퍼) inject.
- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`** — `onb_gmail_error_network`, `onb_gmail_error_permission_denied`, `onb_gmail_error_unknown` — 실패 스낵바용. (본 plan 에서는 스낵바 레이아웃은 건드리지 않고 string resource 만 준비. 실제 스낵바 연결은 5.1 의 Composable 에서 `SnackbarHostState` 로.)

### 5.2 Files to add
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthLauncher.kt`** — `@Composable fun rememberGmailOAuthLauncher(...): GmailLauncherHandle`. 내부적으로
  - `rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult())` 로 `AuthorizationClient.authorize(Scope("https://www.googleapis.com/auth/gmail.readonly"))` 의 PendingIntent 를 발사.
  - 결과 `ActivityResult` → `AuthorizationClient.getAuthorizationResultFromIntent(...)` → `serverAuthCode` + `grantedScopes` → `GoogleAuthTokenProvider.exchangeAuthCode(code)` (이 provider 는 #3 산출물) 호출.
  - 결과를 `MutableSharedFlow<GmailAuthResult>` 로 emit. 호출측은 `LaunchedEffect(handle) { handle.results.collect { ... } }`.
  - `GmailAuthResult` sealed: `Success(token: OAuthToken)` / `Denied(code: String)` / `Error(throwable: Throwable)`.
  - Credentials API 대안: `androidx.credentials` + `googleid` 기반 `GetGoogleIdOption` 은 OIDC token 만 주고 `gmail.readonly` 스코프는 불가 → **AuthorizationClient 경로가 필수** (Karpathy simplicity: 대안 1개만 채택).
- **tests**: `android/app/src/test/java/com/becalm/android/ui/onboarding/OnboardingViewModelTest.kt` 에 두 개의 케이스 추가
  - `onGmailConnected_persistsTokenAndMarksComplete`
  - `onGmailFailed_marksSkippedAndEmitsSentryBreadcrumb`
  Fake `OAuthCredentialStore` + Fake `SentryClient` 사용. (kotest/junit4 + MockK 는 이미 프로젝트 convention.)

### 5.3 Files to delete (dead code)
없음. `OAuthPlaceholderContent` 는 **OutlookMailOAuthScreen.kt 도 사용**하므로 유지. #13 구현 후 두 화면 모두 real launcher 로 전환되면 후속 cleanup PR 에서 helper 이름을 `OAuthConsentContent` 로 rename 고려 (본 plan 범위 아님).

### 5.4 Non-code changes
- `app/build.gradle.kts` — `com.google.android.gms:play-services-auth` (AuthorizationClient) 의존성 추가. **#3 plan 의 build.gradle 변경과 중복될 수 있음 → 실제 구현 세션은 #3 merge 후 diff 확인**.
- `AndroidManifest.xml` — AuthorizationClient 는 신규 Activity 등록 불필요 (standard PendingIntent 패턴).
- `BuildConfig.GMAIL_OAUTH_CLIENT_ID` — Gradle `buildConfigField` 로 주입. debug/release 분리. 실제 값은 CI secret.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "BECALM-OAUTH-001" android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt | wc -l` = 0 (TODO 제거됨)
- [ ] **Grep invariant**: `grep -rn "onMarkStepStatus(OnboardingStep.LINK_GMAIL, StepStatus.COMPLETE)" android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthScreen.kt` = 0 (화면이 직접 COMPLETE 마킹하지 않음 — VM 경유만)
- [ ] **Grep invariant**: `grep -n "AuthorizationClient\|gmail.readonly" android/app/src/main/java/com/becalm/android/ui/onboarding/GmailOAuthLauncher.kt | wc -l` ≥ 2
- [ ] **Unit test**: `OnboardingViewModelTest.onGmailConnected_persistsTokenAndMarksComplete` 통과 — OAuthCredentialStore.save 호출 + UserPrefsStore.setGmailConnected(true) + LINK_GMAIL=COMPLETE
- [ ] **Unit test**: `OnboardingViewModelTest.onGmailFailed_marksSkippedAndEmitsSentryBreadcrumb` 통과 — Sentry breadcrumb `onboarding_step_failed` (step="LINK_GMAIL") emit + LINK_GMAIL=SKIPPED + `setGmailConnected` 호출 **안 됨**
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: misconfigured debug 빌드 (BuildConfig.GMAIL_OAUTH_CLIENT_ID 빈 문자열) 에서 Connect 버튼 disabled + [스킵] 만 동작

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**.

- **Outlook MSAL 구현** — 별도 plan `docs/plans/ui-onboarding-outlook-oauth.md` (같은 `feat/ui/onboarding` 브랜치에 후속 커밋).
- **IMAP provider selector** — 별도 plan `docs/plans/ui-onboarding-imap-provider-selector.md`.
- **PIPA per-provider consent 화면** — 별도 plan `docs/plans/ui-onboarding-pipa-email-consent.md`. 본 plan 은 PIPA 동의가 이미 수집된 상태를 전제.
- **Settings/Sources 의 [다시 연결]** — 별도 plan `docs/plans/ui-sources-detail-actions-and-localization.md`. 본 plan 의 onGmailConnected/Failed 시그니처는 SourceDetail 에서도 재사용되도록 설계하되 wiring 은 그쪽에서.
- **`GoogleAuthTokenProviderImpl` 실제 구현** — PR #3 (`docs/plans/repo-auth-gmail-oauth-provider.md`). 본 plan 은 그 인터페이스의 suspending API 를 소비만 함.
- **`OAuthCredentialStore` (Keystore-backed) 신규 모듈** — 역시 PR #3 범위.
- **Gmail API 호출 (adapter, worker)** — adapter-worker 레이어 plan 들 (`docs/plans/worker-*`, `docs/plans/repo-*`) 범위.

---

## 8. Dependencies

- **Blocked by**: PR #3 — `feat/repo/auth` 브랜치 (`docs/plans/repo-auth-gmail-oauth-provider.md`). `GoogleAuthTokenProvider` 인터페이스 + `OAuthCredentialStore` 가 공개돼 있어야 VM 이 inject 가능.
- **Blocks**:
  - `docs/plans/ui-sources-detail-actions-and-localization.md` 의 Gmail [다시 연결] 경로 — 같은 launcher 를 재사용해야 하므로 본 plan 의 `GmailOAuthLauncher` 가 먼저 존재해야 함.
  - `docs/plans/ui-onboarding-pipa-email-consent.md` — PIPA 동의 화면이 Gmail OAuth 진입 직전에 삽입되도록 navigation 재구성. PIPA plan 이 본 plan 뒤에 들어와도 OK (PIPA screen 은 navigation graph edit 만).

merge 순서: **#3 → 본 plan → #13 (Outlook) → #14 (IMAP) → #15 (PIPA) → #5 sources-detail**. 같은 `feat/ui/onboarding` 브랜치에 commit 을 순차 stack 하므로 자연스러운 linear history.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 `GmailOAuthScreen` 은 다시 placeholder 가 되고 Gmail OAuth 는 기능 불능. 단 `OAuthCredentialStore` / `UserPrefsStore.gmail_connected` 기록이 이미 남은 사용자는 무결함 — revert 는 **UI 쓰기 경로만** 되돌리고, 이미 저장된 토큰·flag 는 손상되지 않는다 (Keystore/DataStore 는 멱등).

revert 후 체크:
1. 기존 SKIP 경로 (`onSkipStep`) 는 정상 동작 — 이미 `gmail_connected=false` 로 invariant 유지.
2. compile 성공 (revert 가 남기는 dangling import 없는지 grep 으로 확인).
3. `OAuthCredentialStore` / `SentryClient` inject 는 VM 에서 제거 필요 (revert 자동 처리).

---

## Appendix — Session handoff notes

구현 세션에게 전달할 추가 컨텍스트.

- **왜 `AuthorizationClient` 이고 `CredentialManager` 가 아닌가**: `CredentialManager` + `GetGoogleIdOption` 은 OIDC id-token 만 반환 → `gmail.readonly` 같은 API scope 는 불가능. Google 이 별도 제공하는 `AuthorizationClient.authorize(AuthorizationRequest.Builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build())` 가 유일한 non-deprecated 경로. 세션 시작 시 공식 문서 (`developers.google.com/identity/authorization`) 재확인 권장.
- **serverAuthCode vs accessToken**: AuthorizationClient 는 `serverAuthCode` (1회용) 를 리턴 — 이를 백엔드에 보내 교환하거나 **디바이스에서 직접 OAuth2 token endpoint** 로 교환. 본 plan 은 디바이스 교환 경로를 전제 (Karpathy "simpler path", EmailBody 가 local-only 인 것과 일관). 교환 로직은 #3 의 `GoogleAuthTokenProviderImpl.exchangeAuthCode` 에 캡슐화.
- **Sentry breadcrumb vs captureMessage**: ONB-007 의 "이벤트 전송" 문구는 breadcrumb 만으론 부족 — `captureMessage("onboarding_step_failed", level=WARNING)` 도 동반 emit 해야 실제 event 로 Sentry UI 에 남음. 두 호출 모두 필수.
- **테스트 `SentryClient` 주입**: 프로젝트에 이미 `Observability` 또는 유사 래퍼가 있다면 그걸 inject. Fake 는 `recordedEvents: MutableList<String>` 로 단순 검증.
- **Connect 버튼 enabled 조건**: `BuildConfig.GMAIL_OAUTH_CLIENT_ID.isNotBlank()` 를 `remember { ... }` 으로. compile-time static 이므로 성능 걱정 없음.
- **전체 규모 추정**: ~4 파일 변경, 1 파일 신규 (launcher), 2 개 테스트 추가. 1 세션 내 완주 가능.
- **함정**: AuthorizationClient 의 결과 intent 는 cold start 에서 null 가능 (시스템이 process 를 kill 한 경우). `rememberSaveable` 로 request state 보존 필요. 이 디테일은 구현 세션이 AuthorizationClient 문서 재확인 후 대응.
