# UI / Auth / google-signin-wiring — LoginScreen 의 Google 로그인 텍스트를 실제 CredentialManager 흐름으로 교체

**Branch**: `feat/ui/auth/google-signin-wiring`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 1 — Auth (AUTH-002 Google sign-in 경로)
**Severity**: **High** (AUTH-002 스펙에 따르면 Google 로그인이 동작해야 하나 화면에 CTA 자체가 없음 — placeholder Text. Repository 경로 `signInWithGoogle(idToken)` 은 구현돼 있으나 UI 에서 호출하는 경로가 부재)
**Type**: Gap (UI CTA 미구현) + Drift (의도된 기능 ≠ 구현)

---

## 1. Finding

`LoginScreen.kt:177-181` 은 Google 로그인을 안내 텍스트로만 렌더링한다:
```kotlin
Text(
    text = stringResource(R.string.login_google_cta),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```
클릭 불가, `AuthViewModel.onGoogleSignIn(idToken)` 을 호출하는 UI 경로 없음. `SupabaseAuthClient.signInWithGoogleIdToken` 과 `AuthRepositoryImpl.signInWithGoogle` (AuthRepository.kt:166-172) 는 이미 존재하므로 UI 하단의 placeholder 만 실제 `CredentialManager` + `GetGoogleIdOption` 흐름으로 교체하면 된다.

`GoogleAuthTokenProviderImpl` (Gmail scope) 와는 **분리**된 작업이다 — 본 plan 은 **OIDC id-token** (Supabase 인증용) 만 다루며 Gmail scope 는 S6-F 가 담당.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/auth.spec.yml` AUTH-002
> "사용자가 Google 로그인 버튼 탭 시 Google Identity Services 로 id-token 을 획득하고, 이를 Supabase `signInWithIdToken` 에 전달해 세션을 발급받는다."

### 2.2 `.spec/contracts/ui-map.yml`
> "path: /auth/login / screen: LoginScreen / components: [EmailField, PasswordField, SubmitButton, **GoogleSignInButton**] / auth: none"

components 계약에 `GoogleSignInButton` 이 명시되나 현 UI 는 Text 만 존재 — 계약 위반.

### 2.3 `.spec/onboarding.spec.yml` ONB-002
> "Google 로그인 성공 후 `onboarding_completed=false` 면 PIPA 제3자 제공 화면으로 이동한다" — 이미 `LoginScreen.kt:83-99` 의 `LaunchedEffect` 가 이를 수행.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Google CTA 는 텍스트만
- **`ui/auth/LoginScreen.kt:177-181`** — Text composable, no interaction.

### 3.2 AuthViewModel.onGoogleSignIn 은 이미 구현됨
- **`ui/auth/AuthViewModel.kt:117-130`** — `onGoogleSignIn(idToken: String)` signature 완비.

### 3.3 Credential Manager 미의존
- `grep -rn "androidx.credentials\\|CredentialManager" android/app/src/main/` = 0
- `app/build.gradle.kts` 에 `androidx.credentials:credentials` 미등록.

### 3.4 Web OAuth client id 구성 없음
- `BuildConfig.GOOGLE_WEB_CLIENT_ID` 또는 유사 상수 미정의. `strings.xml` 의 `default_web_client_id` 도 없음.

검증 grep:
```bash
grep -n "onGoogleSignIn\\|login_google_cta" android/app/src/main/java/com/becalm/android/ui/auth/
grep -rn "CredentialManager\\|GetGoogleIdOption\\|GoogleIdTokenCredential" android/app/src/main/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| Google CTA | Button, CredentialManager 호출 | Text only | Button + launcher |
| id-token 획득 | `GetGoogleIdOption(serverClientId)` + `CredentialManager.getCredential` | 없음 | 신규 |
| 실패 처리 | 사용자 취소 / 네트워크 실패 / Supabase 매핑 실패 분리 | 경로 없음 | sealed result + snackbar |
| BuildConfig | `GOOGLE_WEB_CLIENT_ID` (Gradle `buildConfigField`) | 없음 | 추가 |
| 의존성 | `androidx.credentials:credentials` + `androidx.credentials:credentials-play-services-auth` + `com.google.android.libraries.identity.googleid:googleid` | 없음 | build.gradle.kts 추가 |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`ui/auth/LoginScreen.kt`** —
  - `LoginForm` 의 Text 부분을 `BecalmButton(text=..., variant=Secondary, leadingIcon=GoogleLogo, onClick=onGoogleSignInRequested)` 로 교체.
  - LoginScreen 상위에서 `rememberGoogleSignInLauncher(viewModel::onGoogleSignIn)` 으로 handle 생성, `onGoogleSignInRequested = { handle.launch() }`.
  - disabled 조건: `BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()` (misconfigured debug 빌드 방어).
  - 실패 결과는 snackbar 에 stringResource.
- **`ui/auth/AuthViewModel.kt`** — 변경 없음 (이미 `onGoogleSignIn(idToken)` 존재).
- **`res/values/strings.xml`** + **`values-ko/strings.xml`** —
  - `login_google_cta` 문구 갱신 (기존 "Google 로 계속하기" 유지 가능).
  - `login_google_error_user_cancelled`, `login_google_error_no_credentials`, `login_google_error_unknown`.
- **`app/build.gradle.kts`** —
  - dependency: `androidx.credentials:credentials:1.3.0`, `androidx.credentials:credentials-play-services-auth:1.3.0`, `com.google.android.libraries.identity.googleid:googleid:1.1.1` (버전은 lib 매뉴얼 검증).
  - `buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${env.GOOGLE_WEB_CLIENT_ID ?: ""}\"")` — debug/release 분리, CI secret.

### 5.2 Files to add
- **`ui/auth/GoogleSignInLauncher.kt`** — `@Composable fun rememberGoogleSignInLauncher(onIdToken: (String) -> Unit, onError: (GoogleSignInError) -> Unit): GoogleSignInHandle`.
  - 내부: `CredentialManager.create(context)` + `GetCredentialRequest(listOf(GetGoogleIdOption.Builder().setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID).setFilterByAuthorizedAccounts(false).build()))`.
  - 성공 → `GoogleIdTokenCredential.createFrom(response.credential.data).idToken` → `onIdToken`.
  - 사용자 취소 → `GetCredentialCancellationException` → `onError(UserCancelled)`.
  - 자격 증명 없음 → `NoCredentialException` → `onError(NoCredentials)`.
  - 기타 → `onError(Unknown(e))`.
  - `sealed class GoogleSignInError { UserCancelled; NoCredentials; Unknown(val t: Throwable) }` 노출.
- **tests**:
  - `AuthViewModelTest.onGoogleSignIn_success_observesSessionTransition` (이미 있을 수 있음 — 없으면 추가).
  - `GoogleSignInLauncherTest` — Credential Manager 는 system API 라 unit 테스트는 flow mapping (Credential → idToken) 만 검증, integration 은 instrumentation 으로 별도 skeleton 만.

### 5.3 Files to delete (dead code)
- 없음.

### 5.4 Non-code changes
- **Gradle `buildConfigField`** — `GOOGLE_WEB_CLIENT_ID`. CI secret 에 등록 필요 (본 plan 범위 아님 — CTO 가 CI 에서 설정).
- **Play Services Auth** (기존 Gmail OAuth 가 추가한 것 재사용 가능) — `AuthorizationClient` 와는 **다른 API** (CredentialManager) 이므로 버전 충돌 여부 확인 필요.
- **AndroidManifest.xml** — CredentialManager 는 별도 activity 등록 불필요.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "GetGoogleIdOption\\|CredentialManager" android/app/src/main/java/com/becalm/android/ui/auth/GoogleSignInLauncher.kt | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -n "onGoogleSignIn\\b" android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt | wc -l` ≥ 1 (호출 경로 존재)
- [ ] **Grep invariant**: `grep -n "BuildConfig\\.GOOGLE_WEB_CLIENT_ID" android/app/src/main/java/com/becalm/android/ui/auth/ | wc -l` ≥ 1
- [ ] **Unit test**: `AuthViewModelTest.onGoogleSignIn_success_observesSessionTransition` 통과
- [ ] **Unit test**: `GoogleSignInLauncherTest.mapCredentialToIdToken` 통과 (CredentialResponse→idToken 매핑 단순 단위 테스트)
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: Google 계정이 하나 이상 등록된 디바이스에서 CTA 탭 → account picker → 선택 → Supabase 세션 생성

---

## 7. Out of Scope

- **Gmail readonly scope** — S6-F (`docs/plans/ui-onboarding-gmail-oauth.md`). 본 plan 은 OIDC id-token 만.
- **Google Calendar OAuth** — 별도 plan (`docs/plans/worker-calendar-*.md` 혹은 후속).
- **`setFilterByAuthorizedAccounts(true)` 로 silent sign-in** — 첫 로그인은 interactive, silent refresh 는 session persistence 가 담당하므로 범위 밖.
- **Apple / Facebook / 기타 OAuth** — 현재 spec 미정의.

---

## 8. Dependencies

- **Blocked by**: 없음. `AuthRepository.signInWithGoogle(idToken)` 경로는 이미 구현.
- **Blocks**: 없음. 독립.

merge 순서: 본 plan 은 W6 의 다른 세션들과 **병렬** 가능. 단 `feat/ui/wave-6` 와 같은 통합 브랜치에 포함한다면 다른 onboarding 작업과 충돌 없음 (LoginScreen 은 OnboardingViewModel 과 분리).

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 Google CTA 가 다시 Text 로 복귀. Supabase 세션 데이터는 영향 없음. build.gradle 의 dependency 는 unused 로 남을 수 있으나 compile 통과.

---

## Appendix — Session handoff notes

- **왜 `CredentialManager` 가 Gmail 과 달리 OK 인가**: OIDC id-token 은 `CredentialManager` + `GetGoogleIdOption` 경로의 정식 반환 값. Gmail API 스코프는 `CredentialManager` 에서 얻을 수 없어 S6-F 가 별도로 `AuthorizationClient` 를 사용한다. **두 API 는 동시에 사용 가능** — Play Services Auth 와 credentials lib 은 공존.
- **Web client id**: Supabase 의 Google OAuth provider 에 등록된 "Web Client" 의 client id. Android client id 가 **아님**. `.env` 또는 CI secret 으로 주입.
- **테스트 부담 최소화**: `CredentialManager` 는 system service 라 Robolectric 도 제한. Launcher 테스트는 **credential → idToken 파싱** 만 단위 테스트, actual API 는 instrumentation smoke 만.
- **FLAG_SECURE**: LoginScreen 이 이미 flag 설정 중 — CredentialManager UI 가 별도 activity 를 띄울 때 flag 가 전달되는지 시각 확인 (플랫폼 차이 가능).
- **규모 추정**: 1 신규 파일 (launcher), 1 파일 수정 (LoginScreen), 의존성 3개, string 3개, 테스트 1-2개. 0.5-1 세션.
