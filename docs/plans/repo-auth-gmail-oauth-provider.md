# Repo / Auth / gmail-oauth-provider — GoogleAuthTokenProvider 구현체가 없어 Gmail 워커가 전원 Unauthorized 로 실패한다

**Branch**: `feat/repo/auth`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 (Email ingestion — Gmail)
**Severity**: Critical (ING-006 전체가 현재 비기능 상태 — `authTokenProvider.currentToken()` 이 항상 `null` → 모든 Gmail 요청 401)
**Type**: Gap (interface 는 선언됐으나 impl / Keystore 저장소 / Hilt binding 모두 부재)

---

## 1. Finding

`GmailClient.kt:27-30` 에 `GoogleAuthTokenProvider` interface 가 **stub 으로만** 선언되어 있고 어떤 구현체도 존재하지 않는다. 결과:

- `GmailClientImpl` 이 요청 헤더를 구성할 때 `currentToken()` 이 항상 `null` 을 반환 → 모든 Gmail API 호출이 `BecalmError.Unauthorized` (GmailClient.kt:336-337 경로).
- `GmailOAuthScreen.kt` 은 placeholder (TODO BECALM-OAUTH-001) — 실제 OAuth flow 가 wire 되지 않음.
- `EncryptedTokenStore.kt:78-82` 는 Supabase 세션 필드만 보관 — Google access/refresh token 을 저장할 키가 전혀 없음.

이로 인해 `data-ingestion.spec.yml:153` invariant ("Gmail/Outlook OAuth 토큰 및 IMAP 앱 비밀번호는 Android Keystore에만 저장") 는 **vacuously 위배** — 저장 자체가 일어나지 않기 때문에 조문은 깨지지 않았지만, 저장 경로가 없으므로 ING-006 전체가 실현 불가.

본 플랜은 ADAPT-CRED-001 의 **Gmail portion** 만 다룬다. Microsoft Graph / MSAL 부분은 동일 브랜치 `feat/repo/auth` 의 다음 스택 커밋 `repo-auth-msgraph-oauth-provider.md` 에서 처리.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/data-ingestion.spec.yml:62` — ING-006
> "[주기적 보조 경로] Gmail OAuth WorkManager periodic sync가 신규 이메일을 로컬 파싱하여 Room EmailBody + RawIngestionEvent로 저장한다. … Gmail 자격증명은 Android Keystore에 보관되며 Railway로 전송되지 않는다"

### 2.2 `.spec/data-ingestion.spec.yml:64` — ING-006 precondition
> "Gmail OAuth 토큰 유효(Keystore 저장), Room DB 초기화, 마지막 sync 이후 INBOX 또는 SENT 라벨에 신규 메시지 1건 존재"

### 2.3 `.spec/data-ingestion.spec.yml:153` — invariant
> "Gmail/Outlook OAuth 토큰 및 IMAP 앱 비밀번호는 Android Keystore에만 저장되며 Railway 서버로 전송되지 않는다"

### 2.4 `.spec/onboarding.spec.yml:58-63` — ONB-004 (현재 [스킵] 만 명시)
> "Gmail OAuth 연결 화면에서 [스킵] 탭 시 다음 온보딩 단계로 이동한다 … DataStore gmail_connected=false 유지"

→ 스킵 경로는 이미 명세됨. 본 플랜은 **연결 성공 경로**를 뒷받침하는 provider 계층만 다룬다 (UI wiring 은 out-of-scope — PR#12 참조).

### 2.5 Read-only scope (MVP 제약)
`.spec/data-ingestion.spec.yml:62` ING-006 의 인덱싱 범위는 `INBOX` + `SENT` **읽기 전용**. 따라서 요청하는 OAuth scope 는 반드시 `https://www.googleapis.com/auth/gmail.readonly` **한 개로 고정** — Gmail modify/send scope 요청 금지.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/remote/gmail/GmailClient.kt:27-30`
```kotlin
public interface GoogleAuthTokenProvider {
    /** Returns the current Google OAuth2 access token, or `null` when not signed in. */
    public fun currentToken(): String?
}
```
→ interface only. KDoc 19-21 line: "The concrete implementation is delivered in SP-38 (AuthViewModel / Google Sign-In integration)." — SP-38 은 deliver 되지 않았다.

### 3.2 Hilt binding 부재
```bash
grep -rn "GoogleAuthTokenProvider" android/app/src/main/java/
# → 선언 + GmailClientImpl ctor 에서의 inject 만 나오고, @Binds / @Provides 어디에도 없음
```

### 3.3 `EncryptedTokenStore.kt` — Supabase 전용
- 이 파일은 Supabase access/refresh token 만 보관한다. Google access token 을 저장할 필드/키가 없음.
- Invariant 유지: **본 플랜은 EncryptedTokenStore 를 수정하지 않는다** — Google 자격증명은 별도 스토어로 분리한다.

### 3.4 `GmailOAuthScreen.kt` placeholder
- `ui/onboarding/GmailOAuthScreen.kt` 는 TODO(BECALM-OAUTH-001) — 버튼 tap → provider 호출 경로가 붙어있지 않음.
- 본 플랜에서 **UI 는 건드리지 않음** (별도 PR).

### 3.5 검증 grep
```bash
grep -rn "GoogleIdTokenCredential\|AuthorizationClient\|com.google.android.gms:play-services-auth\|androidx.credentials" android/app/
# → 예상 결과: 0 (의존성 미도입)
grep -rn "class GoogleAuthTokenProviderImpl" android/app/src/main/java/
# → 예상 결과: 0 (impl 미존재)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| OAuth 인증 flow | Android 기기에서 Google 계정 인증 후 access token 획득 | Interface 만 존재, impl 없음 | `GoogleAuthTokenProviderImpl` 신규 |
| Token 영속화 | Android Keystore (ING invariant 153) | Supabase 전용 `EncryptedTokenStore` 만 존재 | `OAuthCredentialStore` 신규 (Keystore 기반 `EncryptedSharedPreferences`) |
| Scope | `gmail.readonly` (ING-006 읽기 전용) | 미정의 | scope 상수 `GMAIL_READONLY_SCOPE` 로 하드코딩 |
| Refresh 전략 | Access token 만료 시 silent refresh; 불가 시 re-auth 유도 | 없음 | AuthorizationClient 재발급 + `OAuthTokenState.ReauthRequired` 상태 노출 |
| DI | `@Binds GoogleAuthTokenProvider` | 없음 | `core/di/AuthModule.kt` 에 binding 추가 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술한다. 구현은 후속 세션.

### 5.1 라이브러리 선택 — Credential Manager + AuthorizationClient

Android 14+ 권장 패턴인 **Credential Manager** (`androidx.credentials:credentials` + `androidx.credentials:credentials-play-services-auth`) 와 **Google Identity Services** (`com.google.android.libraries.identity.googleid:googleid`) 조합을 사용한다.

- 사용자 식별(ID token): `GetGoogleIdOption` + `CredentialManager.getCredential()` → `GoogleIdTokenCredential`
- Gmail API 접근용 access token: **`AuthorizationClient`** (com.google.android.gms.auth.api.identity) 의 `authorize(AuthorizationRequest.Builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"))).build())` → `AuthorizationResult.accessToken`

이유:
- 구(old) `GoogleSignIn` (com.google.android.gms.auth.api.signin) 은 2024 년 기준 Credential Manager 로 이관됨 — 신규 코드는 Credential Manager 사용 권장.
- AuthorizationClient 는 offline refresh token 발급을 **직접 노출하지 않는다** — 대신 silent `authorize()` 재호출로 재인증 처리 (Android 가 device 내에서 관리). 이로 인해 본 구현은 refresh token 을 직접 저장하지 않고 access token + 만료 시각 + "last successful authorize timestamp" 만 저장한다.

### 5.2 CRITICAL constraint — scope

OAuth scope **반드시** `"https://www.googleapis.com/auth/gmail.readonly"` 한 개로 고정. `gmail.modify`, `gmail.send`, `gmail.labels` 등 상위 권한 요청 금지 (ING-006 MVP 범위 제약).

구현자 가이드:
- 상수는 `GoogleAuthTokenProviderImpl.kt` 내부 `companion object` 에 `GMAIL_READONLY_SCOPE: String = "https://www.googleapis.com/auth/gmail.readonly"` 로 고정 선언.
- `AuthorizationRequest.Builder` 에 scope 리스트를 넘길 때 해당 상수 외의 값을 추가하지 말 것.
- 향후 확장 시에도 이 파일에서 scope 를 추가하지 말고 신규 provider 를 분리할 것 (원칙: 하나의 provider = 하나의 scope 집합).

### 5.3 Files to add

- **`data/remote/gmail/GoogleAuthTokenProviderImpl.kt`** (신규)
  - `@Singleton` 클래스. `GoogleAuthTokenProvider` 인터페이스 구현.
  - 의존성: `ApplicationContext`, `OAuthCredentialStore`, `@IoDispatcher CoroutineDispatcher`, `Clock`, `Logger`.
  - 공개 API:
    - `override fun currentToken(): String?` — 저장된 access token 을 반환하되, 만료 60 초 전이면 `null` 반환 (호출자는 Unauthorized 흐름으로 fallthrough → UI 가 `OAuthTokenState.ReauthRequired` 감지 후 재인증 프롬프트).
    - `suspend fun startSignIn(activity: Activity): OAuthSignInResult` — Credential Manager 로 ID token 획득 후 AuthorizationClient 로 Gmail scope access token 획득 → `OAuthCredentialStore.saveGoogle(...)` 에 저장.
    - `suspend fun refreshSilently(activity: Activity): Boolean` — `AuthorizationClient.authorize()` 재호출. 성공 시 저장소 갱신 후 true, 실패 시 `OAuthCredentialStore` 를 clear 하고 `OAuthTokenState.ReauthRequired` 를 발행 후 false.
    - `fun observeTokenState(): Flow<OAuthTokenState>` — UI 가 재인증 필요 여부를 구독.
  - 내부 상태 enum `OAuthTokenState { Unauthenticated, Authenticated, ReauthRequired }`.
  - 에러 모델: 네트워크/Play Services 미설치/사용자 취소 등은 모두 `OAuthSignInResult.Failure(reason)` 로 매핑. 콜러에게 예외 누출 금지.

- **`data/local/secure/OAuthCredentialStore.kt`** (신규)
  - `@Singleton` 클래스. `EncryptedSharedPreferences` 기반. 파일명 `becalm_oauth_credentials`, master key alias `becalm_oauth_credential_store_master_key` (기존 `EncryptedTokenStore` / `ImapCredentialStore` 와 **구분**).
  - **PER-PROVIDER namespaced keys** (Google prefix):
    - `google_access_token`
    - `google_refresh_token` — AuthorizationClient 가 refresh token 을 제공하지 않으면 null 저장 (저장 안 함). 필드 자체는 향후 호환성 차원에서 정의하되 읽기 시 null-safe.
    - `google_token_expires_at` — epoch millis
    - `google_scope` — 저장 시 항상 `gmail.readonly` 단일 값. 읽을 때 mismatch 면 `clear` 후 `null` 반환 (정합성 방어).
  - API:
    - `suspend fun loadGoogle(): GoogleOAuthCredential?` — 만료 여부는 caller 가 판단.
    - `suspend fun saveGoogle(credential: GoogleOAuthCredential): Unit`
    - `suspend fun clearGoogle(): Unit` — Google 관련 키만 삭제 (다른 provider namespace 영향 없음).
  - 향후 `repo-auth-msgraph-oauth-provider.md` 가 `ms_graph_*` 키를 같은 파일에 추가 — namespace 격리로 동시 존재 가능.
  - 데이터 클래스: `data class GoogleOAuthCredential(val accessToken: String, val refreshToken: String?, val expiresAtEpochMillis: Long, val scope: String)`.

- **`data/remote/gmail/OAuthSignInResult.kt`** (신규, 작은 파일)
  - `sealed interface OAuthSignInResult { object Success; data class Failure(reason: FailureReason, throwable: Throwable?) : OAuthSignInResult }`
  - `FailureReason` enum: `USER_CANCELLED`, `PLAY_SERVICES_UNAVAILABLE`, `NETWORK`, `SCOPE_DENIED`, `UNKNOWN`.
  - (선택) 구현자가 `OAuthTokenState` 도 같은 파일에 둘 수 있음 — 결정은 구현 세션에게 위임. 파일 쪼개기는 의미 구분에만 기여하므로 1 파일/2 파일 모두 허용.

### 5.4 Files to change

- **`core/di/AuthModule.kt`** (편집)
  - `@Binds @Singleton fun bindGoogleAuthTokenProvider(impl: GoogleAuthTokenProviderImpl): GoogleAuthTokenProvider`
  - `OAuthCredentialStore` 는 `@Singleton` + `@Inject constructor` 로 Hilt 가 자동 provision (별도 module 불필요).

- **`data/remote/gmail/GmailClient.kt`** (최소 편집 only — interface 유지)
  - `GoogleAuthTokenProvider` interface 에 **optional** 확장 메서드 `fun signalReauthRequired(): Unit { /* no-op default */ }` 를 **추가하지 않는다**. 대신 UI 층이 `observeTokenState()` 를 구독하여 `ReauthRequired` 상태를 받는다. 즉 `GmailClient.kt` 본 파일은 건드리지 않는다 (변경 0 줄).
  - 구현자 주의: KDoc 은 유지. interface 에 새 메서드 추가 금지 (기존 caller 전부 재컴파일 유발 방지).

### 5.5 Files to add — Gradle

- `android/app/build.gradle.kts` (편집 — 의존성 추가)
  - `implementation("androidx.credentials:credentials:1.3.x")`
  - `implementation("androidx.credentials:credentials-play-services-auth:1.3.x")`
  - `implementation("com.google.android.libraries.identity.googleid:googleid:1.1.x")`
  - `implementation("com.google.android.gms:play-services-auth:21.x")` — `AuthorizationClient` 제공.
  - 버전은 구현 세션에서 `context7` 로 최신 stable 조회. 본 플랜은 major 버전만 고정.

### 5.6 Files to add — Tests

- **`data/remote/gmail/GoogleAuthTokenProviderImplTest.kt`** (신규, unit)
  - Mock `OAuthCredentialStore`, mock `AuthorizationClient` (`com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient` 는 Activity 의존이므로 wrapper interface 추상화 후 mock).
  - 테스트 케이스:
    1. `currentToken returns null when store empty`
    2. `currentToken returns token when not expired`
    3. `currentToken returns null 60s before expiry`
    4. `refreshSilently succeeds → store updated + state = Authenticated`
    5. `refreshSilently fails → store cleared + state = ReauthRequired`
    6. `startSignIn scope mismatch → Failure(SCOPE_DENIED)`

- **`data/local/secure/OAuthCredentialStoreTest.kt`** (신규, instrumented or Robolectric)
  - `saveGoogle → loadGoogle` 라운드트립
  - `clearGoogle` 만 호출 시 `ms_graph_*` 키가 남아있으면 그대로 유지 (격리 확인 — PR#2 랜딩 후 의미있는 테스트지만 본 PR 에서 미리 plumbing 가능. Namespace prefix 기반으로 `edit().remove(key)` 개별 호출을 구현하면 자연스럽게 통과).

### 5.7 Non-code changes

- **DB migration**: 없음. EncryptedSharedPreferences 는 DB 와 무관.
- **Manifest**: Credential Manager 는 런타임 활동 기반 — manifest 변경 불필요.
- **Google Cloud Console**: OAuth client ID (Android, package name + SHA-1) 등록 필요 — 이는 CTO 가 별도 작업. `strings.xml` 또는 BuildConfig 에 `googleOAuthClientId` 상수 주입 필요. 본 플랜은 **key 를 환경변수로 빼고 `GoogleAuthTokenProviderImpl` 는 `@Named("googleOAuthServerClientId") String` 을 inject** 하도록 설계 — 실 값은 repo 바깥.
- **Sentry**: `Failure(UNKNOWN)` 분기에서 throwable 을 Sentry 로 전송 (crash-free OAuth UX).

---

## 6. Acceptance Criteria

기계적으로 검증 가능한 항목만 나열.

- [ ] **Grep invariant**: `grep -rn "class GoogleAuthTokenProviderImpl" android/app/src/main/java/ | wc -l` 가 1 이상.
- [ ] **Grep invariant**: `grep -rn "gmail\.readonly\|gmail.readonly" android/app/src/main/java/com/becalm/android/data/remote/gmail/ | wc -l` 가 1 이상 (scope 상수 존재).
- [ ] **Grep invariant forbidden**: `grep -rn "gmail\.modify\|gmail\.send\|gmail\.compose\|gmail\.labels" android/app/src/main/java/ | wc -l` 가 0 이다 (read-only scope 외 금지).
- [ ] **Grep invariant forbidden**: `grep -n "google_access_token\|google_refresh_token" android/app/src/main/java/com/becalm/android/data/local/secure/EncryptedTokenStore.kt | wc -l` 가 0 이다 (EncryptedTokenStore 오염 금지).
- [ ] **Hilt binding**: `grep -n "bindGoogleAuthTokenProvider\|GoogleAuthTokenProviderImpl" android/app/src/main/java/com/becalm/android/core/di/AuthModule.kt | wc -l` 가 2 이상.
- [ ] **Unit test**: `GoogleAuthTokenProviderImplTest — currentToken returns null when store empty` 통과.
- [ ] **Unit test**: `GoogleAuthTokenProviderImplTest — currentToken returns null 60s before expiry` 통과.
- [ ] **Unit test**: `GoogleAuthTokenProviderImplTest — refreshSilently fails → state transitions to ReauthRequired` 통과.
- [ ] **Unit test**: `OAuthCredentialStoreTest — saveGoogle then loadGoogle roundtrip` 통과.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공.
- [ ] **Invariant compliance**: `EncryptedTokenStore.kt` 은 diff 에서 변경되지 않음 (Supabase-only 유지). `git diff main -- android/app/src/main/java/com/becalm/android/data/local/secure/EncryptedTokenStore.kt` 가 empty.

---

## 7. Out of Scope

이 PR (스택 커밋) 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **MSAL / Microsoft Graph token provider** — 같은 브랜치 `feat/repo/auth` 의 다음 커밋 `repo-auth-msgraph-oauth-provider.md` 에서 처리.
- **Gmail OAuth UI wiring** (`GmailOAuthScreen.kt` 의 실제 버튼 핸들러 연결) — 별도 PR `feat/ui/onboarding/oauth-wiring` (audit 의 PR #12 계열).
- **Gmail API body/header/folder 확장** — 본 PR 은 token 제공자만. `ADAPT-GMAIL-001/002`, `ADAPT-EMAIL-002..007` 은 별도 플랜.
- **`EncryptedTokenStore` 확장** — **절대 금지**. Supabase-only 유지. 위반 시 invariant 153 과 blast-radius 원칙 동시 위배.
- **Google Cloud Console OAuth client ID 발급** — CTO 운영 작업.
- **Refresh token 장기 저장 전략** — Credential Manager + AuthorizationClient 는 long-lived refresh token 을 직접 노출하지 않는다. 필요 시 post-MVP 에서 re-architect.
- **Gmail API `scope` 확장** (modify/send 등) — MVP 범위 외.
- **오프라인 모드** (네트워크 없을 때 queue) — 별도 resilience 플랜.

---

## 8. Dependencies

- **Blocked by**: 없음 (독립 출발 가능).
- **Blocks**:
  - `repo-auth-msgraph-oauth-provider.md` (PR#2, 같은 브랜치 `feat/repo/auth` 에 스택) — `OAuthCredentialStore` 를 공유하므로 본 커밋이 먼저 merge 되어야 함.
  - `feat/ui/onboarding/oauth-wiring` (PR#12 계열) — `startSignIn` / `observeTokenState` API 소비자.
  - `ADAPT-GMAIL-*` 후속 플랜들 — Unauthorized 가 해소되어야 downstream 검증 가능.

### 8.1 Branch linearization (CLAUDE.md convention)

`feat/repo/auth` 브랜치에 2 개의 **로직 커밋**이 순서대로 쌓인다:
1. **commit #1 (본 플랜)**: `feat(repo/auth): gmail-oauth-provider — Google Credential Manager + AuthorizationClient 기반 GoogleAuthTokenProviderImpl 도입`
2. **commit #2 (PR#2)**: `feat(repo/auth): msgraph-oauth-provider — MSAL PublicClientApplication 기반 MsGraphTokenProviderImpl 도입`

두 커밋 모두 PR 1 개에 쌓여 열린 상태로 유지. CTO merge 는 둘 다 준비된 뒤 한 번에 수행.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시:
- `GoogleAuthTokenProvider` interface 는 원래 stub 상태로 복귀 → Gmail 워커는 다시 전원 Unauthorized (기존 상태와 동일).
- `OAuthCredentialStore` 파일이 사라져도 EncryptedSharedPreferences 파일은 디스크에 남아있을 수 있음 → 다음 앱 launch 에서 해당 파일은 읽히지 않으므로 무해.
- 데이터 복구 전략 불필요 (MVP 단계, 유저 token 손실은 재로그인 요구로 복구 가능).

---

## Appendix — Session handoff notes

- **Credential Manager 가 Android 13 (API 33) 이하에서 제한적** — compileSdk 34+ 필요. 현재 repo 의 `compileSdk` 확인 후 호환 안 되면 `androidx.credentials:credentials-play-services-auth` fallback 경로가 자동으로 커버하지만, minSdk 가 24 (Android 7) 까지 내려가면 Credential Manager 가 GMS 에 위임하므로 Play Services 필수. 온보딩 체크 필요.
- **AuthorizationClient 는 Activity 컨텍스트 필요** — `GoogleAuthTokenProviderImpl` 는 Activity reference 를 받지 않고 `startSignIn(activity)` 파라미터로 받는다. ViewModel 층은 `Activity` 를 직접 보유하지 않으므로 UI 층(Composable) 에서 `LocalActivity.current` (혹은 `LocalContext.current as Activity`) 를 넘기도록 계약.
- **Silent refresh 실패 시** AuthorizationClient 는 예외가 아니라 `ResolvableApiException` 을 던질 수 있음 — `PendingIntent` 로 사용자 확인 다이얼로그 재표시 가능하나 본 MVP 에서는 `ReauthRequired` 로 격하하여 UI 에게 재인증을 요청 (간결성 우선).
- **대안 검토**:
  - 구 `GoogleSignIn` API — deprecated 경로이므로 거부.
  - 서버-매개 OAuth (client → Railway → Google) — PIPA invariant 153 위반 (refresh token 이 서버를 경유). 거부.
  - `net.openid:appauth-android` (AppAuth) — 순수 OAuth 2.0 구현. 고려 가능하나 Credential Manager 쪽이 Android 권장 + One-Tap UX 우위. 미선택.
- **함정 — `googleOAuthServerClientId` 값 관리**: Google Cloud Console 의 "Web client" client_id (Android client 가 아니라) 를 server_client_id 로 전달해야 ID token audience 가 일치한다. 구현자 주의.
- **context7 조회 권장**: `androidx.credentials` 최신 stable 버전 + `AuthorizationRequest` API shape. 훈련 데이터 시점과 다를 수 있음.
