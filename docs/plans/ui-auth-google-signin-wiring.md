# UI / Auth / google-signin-wiring — Google Sign-In 버튼을 CredentialManager 로 LoginScreen 에 연결 (AUTH-003)

**Branch**: `feat/ui/auth/google-signin-wiring`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 (인증)
**Severity**: Medium
**Type**: Gap

---

## 1. Finding

`SupabaseAuthClientImpl.signInWithGoogleIdToken` (line 137-147) 과
`AuthRepositoryImpl.signInWithGoogle` (line 158-164), `AuthViewModel.onGoogleSignIn`
(line 116-129) 까지 **데이터 계층과 ViewModel 은 준비되어 있음**. 그러나 UI (`LoginScreen.kt`)
에는 Google Sign-In 을 트리거할 버튼과 Google ID token 을 획득하는 코드가 연결되어 있지
않다. 현재 LoginScreen 은 email/password 만 동작하고 스펙 AUTH-003 을 실행할 경로가 없다.

구체적으로 LoginScreen.kt:177-181 은 "Google 로 로그인" 문구를 단순 Text 로만 렌더링한다:
```kotlin
Text(
    text = stringResource(R.string.login_google_cta),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```
— 클릭 가능한 버튼조차 아니다. 스펙 AUTH-003 은 Google OAuth 로그인 성공 시 Supabase Auth
세션이 생성되고 토큰이 저장되어야 한다고 명시.

본 PR 은 Android CredentialManager 기반 Google Sign-In 흐름을 LoginScreen 에 연결한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/auth.spec.yml:25-32` — AUTH-003

> "Google OAuth 소셜 로그인 성공 시 Supabase Auth 세션이 생성되고 토큰이 저장된다"
>
> endpoint: `POST /auth/v1/token?grant_type=id_token`
>
> precondition: "Google OAuth client_id 설정됨, Google 계정으로 로그인 성공"
>
> expected: "HTTP 200, {access_token: string} — 세션 생성, 토큰 EncryptedSharedPreferences 저장됨"

### 2.2 `.spec/auth.spec.yml:81` — invariant

> "인증 토큰은 EncryptedSharedPreferences 또는 Android Keystore에만 저장된다 — plaintext 저장 금지"

CredentialManager 응답에서 나오는 ID token 은 메모리에서만 사용되고, 교환으로 얻은
Supabase access/refresh token 만 저장된다 (현재 `SupabaseAuthClientImpl.signInWithGoogleIdToken`
가 `sessionStore.save(session)` 수행).

### 2.3 `.spec/onboarding.spec.yml:56-64` — 연결된 흐름 (ONB-004 등)

로그인 이후 온보딩으로 전환되려면 Google 로그인도 email 로그인과 동일한 `AuthUiState.SignedIn`
상태를 만들어야 한다. `LoginScreen.kt:83-100` 의 `LaunchedEffect(state)` 는 `SignedIn` 일 때
자동으로 다음 화면으로 navigate 하므로, ViewModel 에서 onGoogleSignIn 을 호출하면 그 흐름이
그대로 재사용된다.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 UI 파일에 핸들러 없음

`android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt:113-118`

```kotlin
BecalmScaffold(
    title = stringResource(R.string.login_title),
    snackbarHost = { SnackbarHost(snackbarHostState) },
) { padding ->
    LoginForm(
        modifier = Modifier.padding(padding),
        isLoading = state is AuthUiState.Loading,
        onSignIn = { email, password -> viewModel.onEmailSignIn(email, password) },
    )
}
```
— `onGoogleSignIn` 콜백 자체가 `LoginForm` 에 전달되지 않음.

`android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt:121-183` LoginForm 본체도
google CTA 를 Text 로만 보여줄 뿐 Button 으로 구성하지 않음 (line 177-181).

### 3.2 ViewModel 은 준비됨

`android/app/src/main/java/com/becalm/android/ui/auth/AuthViewModel.kt:116-129`

```kotlin
public fun onGoogleSignIn(idToken: String) {
    viewModelScope.launch {
        _uiState.value = AuthUiState.Loading
        when (val result = authRepository.signInWithGoogle(idToken)) {
            ...
        }
    }
}
```
— **이미 구현 완료**. UI 측에서 호출만 해주면 동작.

### 3.3 Data 계층도 준비됨

`android/app/src/main/java/com/becalm/android/data/remote/supabase/SupabaseAuthClient.kt:137-147`:
```kotlin
override suspend fun signInWithGoogleIdToken(
    idToken: String,
): BecalmResult<SupabaseSession> = runCatchingAuth("signInWithGoogleIdToken") {
    client.auth.signInWith(IDToken) {
        provider = Google
        this.idToken = idToken
    }
    val session = requireCurrentSession()
    sessionStore.save(session)
    session
}
```

### 3.4 Google client_id 설정

```bash
grep -rn "google\|GOOGLE_WEB_CLIENT_ID\|defaultWebClientId" \
  android/app/src/main/ android/app/build.gradle.kts
# → 현재 없음
```

→ Google Sign-In client_id (server-side web client) 설정도 본 PR 에서 함께 추가 (§5.4 configuration).

### 3.5 Dependency 체크

```bash
grep -n "credentials\|google-id\|play-services-auth" android/app/build.gradle.kts \
  android/gradle/libs.versions.toml
# → 현재 없음. 라이브러리 추가 필요.
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| Sign-In 버튼 | Google OAuth 트리거 버튼 | Text 만 표시 (static) | BecalmButton + onClick |
| ID token 획득 | Google 계정 → ID token | 없음 | CredentialManager GetCredentialRequest |
| ID token → Supabase | signInWithGoogle(idToken) | Repo/VM 준비됨, UI 미연결 | UI → VM 호출 wire |
| client_id 등록 | manifest / BuildConfig | 미등록 | BuildConfig secret + strings.xml reference |
| 의존성 | androidx.credentials + google-id | 없음 | libs.versions.toml 추가 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt`**
   - `LoginScreen` composable 시그니처에 외부 action 으로 노출하지 않고 내부에서 CredentialManager 를 구성.
   - `LoginForm` 에 `onGoogleSignIn: () -> Unit` 파라미터 추가.
   - "Google 로 로그인" Text 를 **BecalmButton(variant = Secondary, leadingIcon = GoogleLogo)** 로 교체.
   - `onClick` 은 `rememberCoroutineScope().launch { runGoogleSignIn(context, viewModel, onError) }` 를 호출.
   - 에러 snackbar 를 기존 `AuthUiState.Error` 경로로 흘려 보내기 위해 `viewModel.onGoogleSignInError(message)` 스타일 VM entry 추가 또는 `_uiState.value = AuthUiState.Error(...)` 로 직접 세팅. 현재 `onErrorDismissed()` 가 있으니 대칭성 맞춤.

2. **`android/app/src/main/java/com/becalm/android/ui/auth/AuthViewModel.kt`**
   - `onGoogleSignIn` 는 유지.
   - 신규 `onGoogleSignInCancelled()` / `onGoogleSignInError(message: String)` 엔트리 추가 (사용자 다이얼로그 취소·NoCredentialException 처리).

3. **`android/app/build.gradle.kts` + `gradle/libs.versions.toml`**
   - `androidx.credentials:credentials:1.3.x` + `androidx.credentials:credentials-play-services-auth:1.3.x` + `com.google.android.libraries.identity.googleid:googleid:1.1.x` 의존성 추가.

4. **`android/app/src/main/res/values/strings.xml`**
   - `login_google_cta` 는 기존 존재, 추가로 `login_google_cta_cancelled`, `login_google_no_credentials`, `login_google_error` 문자열 추가.

5. **`android/app/src/main/AndroidManifest.xml`**
   - 변경 없음 (CredentialManager 는 별도 permission 불요).

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/auth/GoogleSignInLauncher.kt` — suspend 함수 `suspend fun launchGoogleIdTokenSignIn(context: Context, serverClientId: String, nonce: String?): Result<String>` 로 CredentialManager 요청을 wrapping. 반환 값은 Google ID token (raw JWT).
- `android/app/src/main/res/drawable/ic_google_logo.xml` — Google brand guidelines 준수하는 벡터 로고 (Google Sign-In SDK 가 제공하지 않으므로 자체 에셋).
- 테스트:
  - `android/app/src/test/java/com/becalm/android/ui/auth/AuthViewModelTest.kt` — 기존 테스트에 Google 경로 케이스 추가 (idToken 전달 시 SignedIn 진입, 실패 시 Error).
  - `android/app/src/androidTest/java/com/becalm/android/ui/auth/LoginScreenGoogleButtonTest.kt` — Compose UI test: 버튼 존재, click 시 VM 의 `onGoogleSignIn(fakeToken)` 이 호출됨 (CredentialManager 는 mock).

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- **Google Cloud Console**:
  - "OAuth 2.0 Client IDs" 에 Android application client (package name + SHA-1) + Web application client 둘 다 생성. **`signInWith(IDToken)` 에 전달되는 serverClientId 는 Web client ID** (Supabase 도 Web client 로 토큰을 검증).
  - scope: `openid`, `profile`, `email` 만.
- **Secret 주입**:
  - `GOOGLE_WEB_CLIENT_ID` 를 `local.properties` 의 key 로 관리. `build.gradle.kts` 에서 `buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$propertyValue\"")` 로 주입.
  - `BuildConfig.GOOGLE_WEB_CLIENT_ID` 를 `GoogleSignInLauncher` 에서 참조.
  - `secrets-gradle-plugin` 도입 여부는 다른 OAuth 작업과 합쳐 별도 PR.
- **Supabase 프로젝트 설정**:
  - Authentication > Providers > Google 활성화, Client ID / Client Secret 등록 (Web client 와 동일한 쌍).
  - Allowed Redirect URL 에 Supabase 프로젝트 URL 추가 (web flow 용; Android는 id_token grant 이므로 redirect 없음).
- **Play Integrity / SafetyNet**: 선택 — MVP 단계는 CredentialManager 의 기본 attestation 만 사용.
- **로깅 금지**:
  - Google ID token (JWT) 은 PII 포함. `logger.d(TAG, "got id token: $idToken")` 같은 코드 **금지**. token 길이 log 도 금지. 실패 시 exception class 이름만 로깅.

### 5.5 CredentialManager 플로우 (의사 코드, plan 설명용)

```
val cm = CredentialManager.create(context)
val option = GetGoogleIdOption.Builder()
    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    .setFilterByAuthorizedAccounts(false)          // 첫 로그인은 false
    .setAutoSelectEnabled(false)
    .build()
val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

// try: cm.getCredential(context, request)
// → credential.data 에서 GoogleIdTokenCredential 추출 → .idToken
// catch NoCredentialException → "Google 계정이 없어요" snackbar
// catch GetCredentialCancellationException → silent (사용자가 닫음)
// catch GetCredentialException → error snackbar
```

두 단계 재시도 (setFilterByAuthorizedAccounts(true) 먼저 → NoCredential 이면 false 로 재시도) 는
**MVP out of scope** — 첫 구현은 단일 request 만.

---

## 6. Acceptance Criteria

- [ ] **Compose UI test**: `LoginScreenGoogleButtonTest — Google 버튼이 렌더링된다 (content description 포함)`.
- [ ] **Compose UI test**: `LoginScreenGoogleButtonTest — 버튼 클릭 시 CredentialManager 가 invoke 된다 (mock)`.
- [ ] **Unit test**: `AuthViewModelTest — onGoogleSignIn(idToken) 성공 시 SignedIn 으로 전이`.
- [ ] **Unit test**: `AuthViewModelTest — onGoogleSignIn 실패 시 Error 상태로 전이`.
- [ ] **Grep invariant**: `grep -rn "onGoogleSignIn" android/app/src/main/java/com/becalm/android/ui/auth/ | wc -l` ≥ 3 (VM 선언 + LoginScreen launcher 사용 + 최소 1개 호출).
- [ ] **Grep invariant**: `grep -n "Text(" android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt | grep "login_google_cta" | wc -l` 이 0 이다 (버튼으로 교체되었는지 검증).
- [ ] **Grep invariant**: `grep -rn "id_token\|BuildConfig.GOOGLE_WEB_CLIENT_ID" android/app/src/main/java/com/becalm/android/ui/auth/ | wc -l` ≥ 1.
- [ ] **Manual (device)**: 로그인 전 LoginScreen 에서 Google 버튼 탭 → 계정 선택 다이얼로그 → 계정 선택 → 온보딩 PIPA 화면으로 진입.
- [ ] **Manual (device)**: 다이얼로그 취소 → 에러 없이 LoginScreen 에 머물러 있음.
- [ ] **PIPA log invariant**: `adb logcat | grep -i "idToken\|eyJ" ` 결과에 token 문자열이 찍히지 않음.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:assembleDebug` 성공.

---

## 7. Out of Scope

- 다른 OAuth provider (Naver, Kakao, Apple) — 포스트 MVP.
- Account chooser UX 최적화 (setFilterByAuthorizedAccounts 두 번 시도) — 별도 UX PR.
- Passkey / WebAuthn — OOS.
- ID token 갱신 (CredentialManager 는 ID token 을 직접 갱신하지 않음; refresh 는 Supabase 측 refresh_token 으로 수행 — AUTH-004 경로 재사용).
- Google 로그인 실패 → "비밀번호 로그인으로 대체" 유도 UI — 기존 email/password form 이 그대로 visible 이므로 별도 처리 불필요.

---

## 8. Dependencies

- **Blocked by**: 없음 (AUTH 데이터 계층은 준비 완료).
- **Blocks**: AUTH-003 관련 통합 테스트.
- **병렬 가능**:
  - `feat/db/auth/user-scoped-database` — 본 PR 과 파일 겹침 없음 (LoginScreen 은 건드리지 않음). 머지 순서 무관.
  - `refactor/domain/auth/signout-preserve-data` — 겹침 없음.
  - `feat/ui/onboarding/notifications-sentry` — 겹침 없음.
- **참고**: Google OAuth client_id 가 아직 발급 안 됐다면 그것을 받는 행정 작업이 선행해야 시험 가능.

---

## 9. Rollback plan

- Revert 한 줄: `git revert <commit-sha>`.
- Revert 후 영향: UI 에서 Google 버튼이 사라지고 email 로그인만 남음. 로컬 Room 데이터 영향 없음. 사용자 세션 영향 없음.
- Google Cloud 측 OAuth client 는 남겨둬도 무해.

---

## Appendix — Session handoff notes

- Google Sign-In legacy SDK (`com.google.android.gms:play-services-auth`) 는 **deprecated** (2024). 반드시 CredentialManager 를 사용 (AndroidX `androidx.credentials` + `com.google.android.libraries.identity.googleid:googleid`).
- CredentialManager 는 API 34 에서 native, API < 34 는 play-services-auth-providers shim 으로 동작 — 앱의 minSdk 가 26+ 이면 `credentials-play-services-auth` 의존성만 추가하면 됨.
- `SupabaseAuthClientImpl.signInWithGoogleIdToken` 는 이미 ID token 을 받아 Supabase 와 교환하는 경로를 구현. idToken 만 넘기면 됨.
- Nonce 는 MVP 에서 null 로 전달 (Supabase 측이 nonce 를 검증하지 않음). 향후 supabase-js / supabase-kt 가 nonce 검증을 요구하면 `GetGoogleIdOption.setNonce(nonce)` + Supabase signIn 의 nonce param 매칭.
- **PIPA 주의**: 버튼 라벨에 "Google 로 계속하기" 와 "선택한 계정의 이메일·이름이 앱에 표시된다" 고지 문구를 `login_google_disclosure` string 으로 함께 노출. PIPA Article 15 는 이 사전 고지를 요구.
- 구현자는 `LoginForm` preview (`@PreviewLightDark`) 에서 Google 버튼을 포함해 Light/Dark 둘 다 확인. Google 로고는 guideline 상 light 배경에서만 컬러, dark 배경에서는 별도 자산 사용. 현재 BecalmTheme 는 forced-dark 이므로 dark variant 자산 필수.
- `logger` 는 DO NOT log `idToken`. 실패 시에는 `logger.w(TAG, "google sign-in failed: ${e::class.simpleName}")` 정도로만.
