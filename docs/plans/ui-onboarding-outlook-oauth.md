# UI / Onboarding / outlook-oauth — OutlookMailOAuthScreen 을 실제 MSAL `acquireTokenInteractive` 흐름으로 교체 + 실패 시 Firebase Crashlytics `onboarding_step_failed`

**Branch**: `feat/ui/onboarding` (Gmail OAuth plan 과 **같은 브랜치 — stack**)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 2 — Onboarding (Outlook Mail OAuth step, 온보딩 7단계)
**Severity**: **Critical** (Gmail 과 동일 구조 — 가짜 COMPLETE 가 터미널 게이트 통과)
**Type**: Gap (UI placeholder) + Drift (성공/실패 경로 미분리)

---

## 1. Finding

`OutlookMailOAuthScreen.kt` 는 Gmail 화면과 동일 패턴의 placeholder 다. [연결] 버튼이 MSAL 호출 없이 `onMarkStepStatus(LINK_OUTLOOK_MAIL, COMPLETE)` 를 즉시 호출 (`OutlookMailOAuthScreen.kt:38-46`). 주석 `// TODO(BECALM-OAUTH-001): wire real Outlook Mail OAuth via MSAL`. 결과:

1. `Mail.Read` + `offline_access` 스코프 토큰이 발급되지 않음 → Microsoft Graph 어댑터는 영구 401.
2. `outlook_mail_connected` (DataStore) 같은 flag 가 존재하지도 않음 (grep 0) — ONB-004 대칭성 미확보.
3. ONB-007 의 `onboarding_step_failed` 이벤트 미발생 — 실패 경로 자체 없음.

이 plan 은 Gmail 용 plan (`ui-onboarding-gmail-oauth.md`) 과 **같은 설계 패턴**을 MSAL 에 적용. UI launcher 는 별도지만, VM 의 콜백 naming / Firebase Crashlytics / UserPrefsStore 쓰기 패턴은 동일하다. 토큰 공급자 구현은 PR #4 (`docs/plans/repo-auth-msgraph-token-provider.md`, 별도 작성 예정) 가 담당 — 본 plan 은 UI wiring 만.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/onboarding.spec.yml:110` — invariant
> "총 12단계: 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처 → Gmail → **Outlook메일** → IMAP → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

### 2.2 `.spec/onboarding.spec.yml:86-93` — ONB-007
> "온보딩 실패 이벤트가 Firebase Crashlytics에 전송된다 … 온보딩 중 OAuth 인증 실패 또는 권한 거부 발생 … Firebase Crashlytics에 `onboarding_step_failed` 이벤트 전송됨 (step 이름, error 포함)"

### 2.3 `.spec/onboarding.spec.yml:106` — invariant
> "온보딩은 순차 진행이며 건너뛴 OAuth 소스는 설정 화면에서 재연결 가능하다"

### 2.4 `.spec/source-management.spec.yml:30-37` — SMG-003 (재인증 플로우 계약)
> "미연결 또는 오류 상태 소스 탭 시 해당 소스의 재연결 플로우로 이동한다 — OAuth 소스는 OAuth 재인증 … expected: [다시 연결] 탭 시 **Microsoft OAuth 재인증 플로우 실행됨**. 재인증 성공 시 DataStore Outlook 토큰 업데이트됨"

→ 온보딩의 acquireTokenInteractive 경로와 Settings 의 [다시 연결] 경로가 같은 MSAL launcher 를 재사용해야 한다 (본 plan 은 launcher 를 재사용 가능한 형태로 설계).

### 2.5 `.spec/contracts/ui-map.yml:50-54`
> "- path: /onboarding/outlook-mail / screen: OutlookMailOAuthScreen / data: [] / components: [**OAuthConnectButton**, SkipButton] / auth: required"

### 2.6 `.spec/email-pipeline.spec.yml:79-80` — invariants (간접)
> "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)" / "첨부파일 바이트는 다운로드하지 않는다 — 메타데이터만 보존"

→ 본문 로컬 보관 + 메타데이터만 보존 = 디바이스가 Graph API 를 직접 호출. Mail.Read + offline_access 토큰이 디바이스에 필요.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Placeholder connect 경로
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OutlookMailOAuthScreen.kt:38-46`**:
  ```kotlin
  onConnect = {
      // TODO(BECALM-OAUTH-001): wire real Outlook Mail OAuth via MSAL
      viewModel.onMarkStepStatus(OnboardingStep.LINK_OUTLOOK_MAIL, StepStatus.COMPLETE)
      navController.navigate(BecalmRoute.OnboardingImap.path)
  },
  ```

- **`OutlookMailOAuthScreen.kt:19`** — class KDoc: `// TODO(BECALM-OAUTH-001): wire real Outlook OAuth via MSAL Android SDK.`

### 3.2 MSAL 미의존
- `grep -rn "com.microsoft.identity.client" android/app/` = 0 (SDK 미포함)
- `app/build.gradle.kts` 에 `com.microsoft.identity.client:msal` 없음
- `res/raw/msal_config.json` 파일 없음 (`ls android/app/src/main/res/raw/` → 0)

### 3.3 VM callback 부재
- `OnboardingViewModel` 에 `onOutlookConnected` / `onOutlookFailed` / `setOutlookMailConnected` 메서드 없음 (grep 0).
- `UserPrefsStore` 에 `outlook_mail_connected` key 없음 (Gmail 은 `gmail_connected` 가 PR #3 에서 추가됨을 전제).

### 3.4 OAuthPlaceholderContent 재사용
- `OutlookMailOAuthScreen.kt:33-47` 는 `OAuthPlaceholderContent` (`GmailOAuthScreen.kt:76-123`) 를 재활용. 본 plan 도 이 helper 를 유지 — connect/skip lambda 만 런처 기반으로 교체.

검증 grep:
```bash
grep -n "BECALM-OAUTH-001" android/app/src/main/java/com/becalm/android/ui/onboarding/OutlookMailOAuthScreen.kt
grep -rn "com.microsoft.identity\|PublicClientApplication" android/app/src/main/
grep -rn "onOutlookConnected\|onOutlookFailed\|outlook_mail_connected" android/app/src/main/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| [연결] 탭 결과 | MSAL `acquireTokenInteractive(Mail.Read + offline_access)` | `onMarkStepStatus(COMPLETE)` 즉시 호출 | MSAL 런처 신규 추가 |
| 성공 후 상태 | `outlook_mail_connected=true`, 토큰 Keystore 저장, LINK_OUTLOOK_MAIL=COMPLETE | LINK_OUTLOOK_MAIL=COMPLETE 만 | DataStore + Keystore 쓰기 추가 |
| 실패/거부 시 | Firebase Crashlytics `onboarding_step_failed` + SKIPPED | 실패 경로 없음 | 실패 콜백 + Firebase Crashlytics 추가 |
| msal_config.json | 필수 (redirect_uri, authorities) | 없음 | **#4 범위** (본 plan 은 단지 존재를 전제) |
| Connect 버튼 활성 조건 | MSAL client ID 구성됨 + 네트워크 연결 | 항상 활성 | gating 추가 |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OutlookMailOAuthScreen.kt`** — placeholder `onConnect` 제거. `MsalOutlookLauncher` (5.2) 의 handle 을 `rememberMsalOutlookLauncher(...)` 로 획득하고 `LaunchedEffect` 로 결과 collect. Connect 버튼 `enabled = msalAvailable` — `msalAvailable = BuildConfig.MSAL_CLIENT_ID.isNotBlank()`.
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`** —
  - 신규 `onOutlookConnected(token: OAuthToken)`: `oauthCredentialStore.save(SourceType.OUTLOOK_MAIL, token)` → `userPrefsStore.setOutlookMailConnected(true)` → `onMarkStepStatus(LINK_OUTLOOK_MAIL, COMPLETE)`.
  - 신규 `onOutlookFailed(error: OutlookAuthError)`: Firebase Crashlytics breadcrumb `onboarding_step_failed` (step="LINK_OUTLOOK_MAIL") + `onMarkStepStatus(LINK_OUTLOOK_MAIL, SKIPPED)`. (5.1 Gmail plan 과 동일 패턴 — VM 쪽 `observabilityClient` inject 는 #12 에서 이미 완료된 상태 전제.)
  - `MsGraphTokenProvider` 는 DI 생성자에 inject (PR #4 산출물).
- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`** — `onb_outlook_error_network`, `onb_outlook_error_permission_denied`, `onb_outlook_error_unknown`.

### 5.2 Files to add
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/MsalOutlookLauncher.kt`** — `@Composable fun rememberMsalOutlookLauncher(...): OutlookLauncherHandle`. 책임:
  - `MsGraphTokenProvider.getPublicClientApplication()` (suspend, #4 산출물) 로 `ISingleAccountPublicClientApplication` 획득.
  - `pca.acquireTokenInteractive(AcquireTokenParameters.Builder().withScopes(listOf("Mail.Read", "offline_access")).withCallback(callback).build())` 호출.
  - MSAL `AuthenticationCallback` 는 plain Java callback → `suspendCancellableCoroutine` 로 래핑 후 `MutableSharedFlow<OutlookAuthResult>` 로 emit.
  - `OutlookAuthResult` sealed: `Success(token: OAuthToken)` / `Denied(code: String)` / `Error(throwable: Throwable)`.
  - MSAL 은 redirect Activity 를 **AndroidManifest 에 등록된 BrowserTabActivity** 로 자동 오픈 → `rememberLauncherForActivityResult` 불필요. 대신 `DisposableEffect` 로 callback 등록·해제.
- **tests**: `OnboardingViewModelTest` 에 두 케이스 추가
  - `onOutlookConnected_persistsTokenAndMarksComplete`
  - `onOutlookFailed_marksSkippedAndEmitsFirebase CrashlyticsBreadcrumb`
- **(옵션) instrumentation test skeleton**: `android/app/src/androidTest/java/com/becalm/android/ui/onboarding/OutlookOAuthLauncherSmokeTest.kt` — MSAL 은 실제 Microsoft 서버에 의존하므로 smoke test 는 CI 에서 skip (MSAL mock server 는 본 plan 범위 아님). 구조만 마련.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes
- `app/build.gradle.kts` — `implementation("com.microsoft.identity.client:msal:5.+")` 추가. **#4 plan 과 중복 가능 → 구현 세션은 #4 merge 후 diff 확인**.
- `res/raw/msal_config.json` 파일 생성은 **#4 범위** (redirect_uri, authorities, client_id 포함). 본 plan 은 파일 존재를 assert 만 (instrumentation test 에서 파일 resource id 조회).
- `AndroidManifest.xml` — `BrowserTabActivity` intent-filter 는 **#4 범위**.
- `BuildConfig.MSAL_CLIENT_ID` — Gradle `buildConfigField`. debug/release 분리.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "BECALM-OAUTH-001" android/app/src/main/java/com/becalm/android/ui/onboarding/OutlookMailOAuthScreen.kt` = 0
- [ ] **Grep invariant**: `grep -rn "onMarkStepStatus(OnboardingStep.LINK_OUTLOOK_MAIL, StepStatus.COMPLETE)" android/app/src/main/java/com/becalm/android/ui/onboarding/OutlookMailOAuthScreen.kt` = 0 (화면이 직접 COMPLETE 마킹 안 함)
- [ ] **Grep invariant**: `grep -n "acquireTokenInteractive\|Mail.Read\|offline_access" android/app/src/main/java/com/becalm/android/ui/onboarding/MsalOutlookLauncher.kt | wc -l` ≥ 3
- [ ] **Unit test**: `OnboardingViewModelTest.onOutlookConnected_persistsTokenAndMarksComplete` 통과 — OAuthCredentialStore.save + setOutlookMailConnected(true) + LINK_OUTLOOK_MAIL=COMPLETE
- [ ] **Unit test**: `OnboardingViewModelTest.onOutlookFailed_marksSkippedAndEmitsFirebase CrashlyticsBreadcrumb` 통과 — Firebase Crashlytics `onboarding_step_failed` (step="LINK_OUTLOOK_MAIL") + SKIPPED + `setOutlookMailConnected` 미호출
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: misconfigured debug 빌드 (BuildConfig.MSAL_CLIENT_ID 빈) 에서 Connect 버튼 disabled + [스킵] 동작

---

## 7. Out of Scope

- **Gmail OAuth** — `docs/plans/ui-onboarding-gmail-oauth.md` (#12).
- **IMAP provider selector** — `docs/plans/ui-onboarding-imap-provider-selector.md` (#14).
- **PIPA per-provider consent 화면** — `docs/plans/ui-onboarding-pipa-email-consent.md` (#15). 본 plan 은 PIPA 동의가 이미 수집된 상태 전제.
- **Settings [다시 연결]** — `docs/plans/ui-sources-detail-actions-and-localization.md` (#5). launcher 는 재사용 가능하도록 설계했지만 wiring 은 그쪽에서.
- **`MsGraphTokenProvider` / `msal_config.json` / BrowserTabActivity manifest** — PR #4 (`docs/plans/repo-auth-msgraph-token-provider.md`, 별도 작성 예정).
- **Microsoft Graph 호출 (어댑터/워커)** — adapter-worker 레이어.

---

## 8. Dependencies

- **Blocked by**: PR #4 — `feat/repo/auth` 브랜치, `MsGraphTokenProvider` 인터페이스 + MSAL config asset 배포. 같은 브랜치에 Gmail plan (#3) 과 stack.
- **Blocked by (soft)**: #12 Gmail plan — 같은 `OnboardingViewModel` 의 observabilityClient / oauthCredentialStore inject 를 먼저 도입. #13 이 혼자 실행되면 DI 추가가 중복됨.
- **Blocks**:
  - `docs/plans/ui-sources-detail-actions-and-localization.md` 의 Outlook [다시 연결] — 본 plan 의 launcher 재사용.

merge 순서: **#4 → #12 (Gmail OAuth UI) → 본 plan → #14 (IMAP) → #15 (PIPA) → #5**. 같은 `feat/ui/onboarding` 브랜치에 순차 커밋.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 Outlook OAuth 는 placeholder 로 복귀. Keystore/DataStore 는 멱등 — 기존 사용자 토큰 손상 없음. Gmail 경로 (#12) 는 별도 커밋이므로 영향 없음.

체크:
1. `msal_config.json` / BrowserTabActivity 는 revert 대상 **아님** (PR #4 산출물).
2. compile 성공 — revert 가 남긴 import (`com.microsoft.identity.client.*`) 잔존 여부 grep 확인.
3. `OnboardingViewModelTest` 의 두 케이스만 제거됨, 나머지 테스트 통과.

---

## Appendix — Session handoff notes

- **MSAL vs ADAL**: ADAL 은 deprecated. MSAL v5+ 만 사용.
- **acquireTokenInteractive vs acquireTokenSilent**: 첫 로그인은 interactive 필수 — 사용자가 브라우저에서 Microsoft 로그인 + 동의. 두 번째부턴 silent 로 refresh — 이건 #5 (Settings [다시 연결]) 와 TokenRefreshInterceptor (역시 #5) 쪽에서 사용.
- **Single-account mode**: BeCalm 은 1명의 Microsoft 계정만 연결 → `ISingleAccountPublicClientApplication` 사용. multi-account 는 향후 고려.
- **AuthenticationCallback → suspendCoroutine**: MSAL callback 은 3개 메서드 (onSuccess/onError/onCancel). 이를 `suspendCancellableCoroutine` + `Result<OAuthToken>` 로 변환 시 **onCancel 과 onError 의 구분**을 잃지 않도록 sealed `OutlookAuthResult` 로 분기. `Result.failure(Throwable)` 로 flatten 하면 UI 가 cancel/error 를 구분 못 함 — Gmail plan 과 동일 원칙.
- **Firebase Crashlytics breadcrumb payload**: error code 는 MSAL 의 `MsalException.errorCode` 를 문자열로 넘김. 그 외 필드는 PIPA 상 사용자 식별자 포함 금지 (email 등).
- **Outlook 아이콘**: `onb_outlook_mail_title` string 은 이미 존재. 아이콘 리소스만 추가 필요 여부는 구현 세션 판단 — 본 plan 은 카피·스코프 불변 전제.
- **msal_config.json 포맷**: authorities=AzureADandPersonalMicrosoftAccount, redirect_uri=`msauth://com.becalm.android/<keyhash>`. keyhash 는 debug/release 별도 — Gradle 빌드 스크립트에서 동적 주입은 **#4** 가 다룬다.
- **전체 규모 추정**: UI 3 파일 변경, 1 신규 launcher, 2 테스트. 1 세션 완주 가능 (MSAL 콜백 래핑이 약간 tricky 하지만 공식 샘플 충분).
