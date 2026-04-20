# Repo / Auth / msgraph-oauth-provider — MsGraphTokenProvider 구현체가 없어 Outlook 워커가 전원 Unauthorized 로 실패한다

**Branch**: `feat/repo/auth` (SAME branch as `repo-auth-gmail-oauth-provider.md`, stacked commit)
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 (Email ingestion — Outlook / Microsoft 365)
**Severity**: Critical (ING-007 전체가 현재 비기능 상태 — `MsGraphTokenProvider.getAccessToken()` 이 항상 `null` → 모든 MS Graph 요청 401)
**Type**: Gap (interface 는 선언됐으나 impl / MSAL 설정 / Hilt binding 모두 부재)

---

## 1. Finding

`MsGraphClient.kt:102-108` 에 `MsGraphTokenProvider` interface 가 stub 으로만 선언되어 있고 어떤 구현체도 존재하지 않는다:

```kotlin
public interface MsGraphTokenProvider {
    public suspend fun getAccessToken(): String?
}
```

KDoc (`MsGraphClient.kt:95-101`) 은 "This is a stub interface — the real implementation will be provided by `AuthViewModel` in Round 6 once the MSAL / Microsoft Identity integration lands" 로 Round 6 을 지목하지만 Round 6 은 delivered 되지 않았다. 결과:

- `MsGraphClientImpl` 이 모든 delta 엔드포인트 호출에서 `Authorization` 헤더를 구성할 수 없어 401 (또는 NullPointerException 직전까지의 guard 로 Unauthorized 매핑).
- `OutlookMailOAuthScreen.kt:19` 는 placeholder.
- `EncryptedTokenStore` 는 Supabase 전용 → MS Graph token 저장소 없음.

본 플랜은 ADAPT-CRED-001 의 **Outlook / MS Graph portion** 을 다룬다. Gmail / Google Identity Services 부분은 동일 브랜치의 선행 커밋 `repo-auth-gmail-oauth-provider.md` 에서 처리. 본 플랜은 해당 커밋이 선행 merge 됐다고 **가정**하고 기술한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/data-ingestion.spec.yml:71` — ING-007
> "[주기적 보조 경로] Outlook Mail Microsoft Graph WorkManager periodic sync가 신규 이메일을 로컬 파싱하여 Room에 저장한다. … Outlook OAuth 토큰은 Keystore에 보관되며 Railway로 전송되지 않는다"

### 2.2 `.spec/data-ingestion.spec.yml:73` — ING-007 precondition
> "Microsoft Graph OAuth 토큰 유효(Keystore 저장), Inbox 또는 Sent Items에 신규 메시지 1건 존재"

### 2.3 `.spec/data-ingestion.spec.yml:153` — invariant
> "Gmail/Outlook OAuth 토큰 및 IMAP 앱 비밀번호는 Android Keystore에만 저장되며 Railway 서버로 전송되지 않는다"

### 2.4 `.spec/onboarding.spec.yml:110` — 12-단계 플로우
> "총 12단계: 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처 → Gmail → Outlook메일 → IMAP → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

→ Outlook 메일은 7번째 단계. 본 플랜은 해당 단계를 뒷받침하는 provider 계층만 다룬다 (UI wiring 은 out-of-scope).

### 2.5 Scope (MVP)
`ING-007` 인덱싱 범위는 `Inbox` + `Sent Items` **읽기 전용**. 따라서 요청 scope 는 `Mail.Read` + `offline_access` (silent refresh 위함) **두 개로 고정**. `Mail.ReadWrite` / `Mail.Send` / 상위 권한 금지.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/remote/msgraph/MsGraphClient.kt:102-108`
```kotlin
public interface MsGraphTokenProvider {
    public suspend fun getAccessToken(): String?
}
```
→ interface only. 구현체 부재.

### 3.2 검증 grep
```bash
grep -rn "class MsGraphTokenProviderImpl" android/app/src/main/java/
# → 예상 결과: 0

grep -rn "com\.microsoft\.identity\.client\|PublicClientApplication\|msal" android/app/
# → 예상 결과: 0 (MSAL 의존성 미도입)

grep -rn "msal_config\|msal_redirect" android/app/src/main/res/
# → 예상 결과: 0 (설정 파일 부재)
```

### 3.3 `OutlookMailOAuthScreen.kt:19`
- placeholder. 실제 MSAL `acquireTokenInteractive` 호출 경로 없음.
- 본 플랜에서는 UI 를 수정하지 않는다 (별도 PR).

### 3.4 `OAuthCredentialStore.kt` (선행 커밋 #1 에서 신규 생성)
- Gmail 커밋이 먼저 merge 되면 `OAuthCredentialStore` 가 존재하며 `google_*` 키만 보유.
- 본 플랜은 그 파일에 **ms_graph 네임스페이스 키**를 추가 (기존 google 키와 충돌 없음).

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| MS Graph 인증 flow | MSAL 기반 interactive + silent refresh | Interface stub only | `MsGraphTokenProviderImpl` 신규 |
| Token 영속화 | Android Keystore (invariant 153) | MS Graph 전용 저장소 없음 | `OAuthCredentialStore` 에 `ms_graph_*` 키 추가 |
| Scope | `Mail.Read` + `offline_access` | 미정의 | 상수로 고정 |
| Silent refresh | 만료 전 자동 갱신 | 없음 | MSAL `acquireTokenSilent` |
| MSAL 설정 | `msal_config.json` + manifest redirect intent | 없음 | 신규 asset + manifest 편집 |
| DI | `@Binds MsGraphTokenProvider` | 없음 | `AuthModule` 편집 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 구현은 후속 세션.

### 5.1 라이브러리 선택 — MSAL (Microsoft Authentication Library)

공식 Android MSAL: `com.microsoft.identity.client:msal:5.x`.

- `PublicClientApplication` **single-account** 모드 (`SingleAccountPublicClientApplication`) — BeCalm 은 1 유저 = 1 MS 계정 전제.
- 첫 로그인: `acquireTokenInteractive(AcquireTokenParameters.Builder()...)` — Chrome Custom Tabs 기반 interactive flow.
- Silent refresh: `acquireTokenSilentAsync(...)` — MSAL 이 cache 에서 access token 이 만료됐으면 자동으로 refresh token 으로 갱신.
- MSAL 이 **자체 token cache** 를 유지하지만, BeCalm 은 invariant 153 준수 + 다른 provider 와 일관성 위해 `OAuthCredentialStore` 에도 **중복 저장** (Graph client 의 token 조회 경로 단일화).
  - 대안 검토: MSAL cache 만 신뢰하면 저장소 중복 없음. 단 MSAL cache 는 Android Account Manager 에 저장되므로 "Android Keystore" 정의에 포함되는지 법무 검토 필요. 본 플랜은 **방어적으로 `OAuthCredentialStore` 에도 저장** 하여 invariant 충족 명확화.
- `AuthenticationCallback` (onSuccess / onError / onCancel) wiring 은 MSAL 표준.

### 5.2 CRITICAL constraint — scope

Scope **반드시** `["Mail.Read", "offline_access"]` 두 개로 고정. `Mail.ReadWrite`, `Mail.Send`, `Mail.ReadBasic.All` 등 상위/변형 scope 요청 금지 (ING-007 MVP 제약 + 최소권한 원칙).

- `offline_access` 는 silent refresh 를 위한 표준 OpenID scope.
- 상수는 `MsGraphTokenProviderImpl.kt` 의 `companion object` 에 `MAIL_READ_SCOPE: List<String> = listOf("Mail.Read", "offline_access")` 로 고정.
- `res/raw/msal_config.json` 의 `"authorities"` + `"required_broker_protocol_version"` 외에 scope 하드코딩은 Kotlin 쪽에만 둔다 (JSON 에 scope 를 쓰면 실수로 추가되기 쉬움).

### 5.3 Files to add

- **`data/remote/msgraph/MsGraphTokenProviderImpl.kt`** (신규)
  - `@Singleton` 클래스. `MsGraphTokenProvider` 인터페이스 구현.
  - 의존성: `ApplicationContext`, `OAuthCredentialStore`, `@IoDispatcher CoroutineDispatcher`, `Clock`, `Logger`, `@Named("msalClientId") String`.
  - 내부 필드: `ISingleAccountPublicClientApplication` (lazily initialized via `PublicClientApplication.createSingleAccountPublicClientApplication(context, R.raw.msal_config, callback)`).
  - 공개 API:
    - `override suspend fun getAccessToken(): String?` — 먼저 `OAuthCredentialStore.loadMsGraph()` 확인 → 만료 60 초 전이면 `acquireTokenSilent()` 호출 → 성공 시 저장소 갱신 후 반환, 실패 시 저장소 clear + null 반환.
    - `suspend fun startSignIn(activity: Activity): OAuthSignInResult` — `acquireTokenInteractive(activity, scopes=MAIL_READ_SCOPE)` → `IAuthenticationResult` 수신 후 `OAuthCredentialStore.saveMsGraph(...)` 저장.
    - `suspend fun signOut(): Unit` — `signOut()` + `OAuthCredentialStore.clearMsGraph()`.
    - `fun observeTokenState(): Flow<OAuthTokenState>` — Gmail provider 와 동일한 state enum 재사용 (`data/remote/gmail/OAuthSignInResult.kt` 로부터 import).
  - 에러 매핑: `MsalUiRequiredException` → `OAuthTokenState.ReauthRequired`. `MsalDeclinedScopeException` → `Failure(SCOPE_DENIED)`. 기타 `MsalException` → `Failure(UNKNOWN, throwable)`.
  - **Thread dispatch**: MSAL callback 은 main thread 에 attached — coroutine 변환 시 `suspendCancellableCoroutine` 으로 감싸고 `resumeWith` 을 ioDispatcher 로 넘기지 말 것 (MSAL 내부에서 해결). 단 후속 store 쓰기만 `withContext(ioDispatcher)`.

- **`res/raw/msal_config.json`** (신규 asset)
  - 예시 골격 (구현자가 실제 `client_id` / `authority` 채움):
    ```json
    {
      "client_id": "<will-be-injected-from-BuildConfig-at-test-time-but-MSAL-requires-literal>",
      "authorization_user_agent": "DEFAULT",
      "redirect_uri": "msauth://com.becalm.android/<keyhash>",
      "account_mode": "SINGLE",
      "broker_redirect_uri_registered": false,
      "authorities": [
        {
          "type": "AAD",
          "audience": { "type": "AzureADandPersonalMicrosoftAccount", "tenant_id": "common" }
        }
      ]
    }
    ```
  - `client_id` / `redirect_uri` 의 실제 값은 Microsoft Entra 포털 앱 등록에서 발급.
  - redirect_uri 의 `<keyhash>` 는 앱 서명 인증서 SHA-1 의 base64url 변환 — debug / release 빌드별 다름. **debug 전용 config** + **release 전용 config** 두 파일로 분기하거나 build variant 기반 sourceSet 분리 권장.

- **Tests**: `MsGraphTokenProviderImplTest.kt` (신규, unit)
  - MSAL 은 native Android 의존성이 강해 순수 JVM unit test 가 까다로움. 접근법:
    - `IPublicClientApplication` 을 직접 mock (MSAL 이 interface 제공).
    - 혹은 provider 내부에 `msalClientFactory: () -> ISingleAccountPublicClientApplication` 을 inject 하여 테스트에서 fake 주입.
  - 케이스:
    1. `getAccessToken returns cached token when not expired`
    2. `getAccessToken refreshes silently when expired`
    3. `acquireTokenSilent throws MsalUiRequiredException → state = ReauthRequired, store cleared`
    4. `startSignIn success → store updated with ms_graph_* keys only (google_* untouched)`
    5. `scope requested = [Mail.Read, offline_access] exactly (no extras)`

### 5.4 Files to change

- **`data/local/secure/OAuthCredentialStore.kt`** (편집 — namespace 확장)
  - 선행 커밋 #1 에서 `google_*` 키 스토어로 존재. 본 커밋은 `ms_graph_*` 키 추가:
    - `ms_graph_access_token`
    - `ms_graph_refresh_token`
    - `ms_graph_token_expires_at` (epoch millis)
    - `ms_graph_scope` (저장 시 `"Mail.Read offline_access"` 단일 값, mismatch 면 clear)
    - `ms_graph_account_identifier` (MSAL `IAccount.identifier` — silent refresh 에 필수)
  - 신규 API:
    - `suspend fun loadMsGraph(): MsGraphOAuthCredential?`
    - `suspend fun saveMsGraph(credential: MsGraphOAuthCredential): Unit`
    - `suspend fun clearMsGraph(): Unit` — MS 키만 삭제, `google_*` 영향 없음 (prefix 기반 individual remove).
  - 데이터 클래스: `data class MsGraphOAuthCredential(val accessToken: String, val refreshToken: String?, val expiresAtEpochMillis: Long, val scope: String, val accountIdentifier: String)`.

- **`core/di/AuthModule.kt`** (편집)
  - `@Binds @Singleton fun bindMsGraphTokenProvider(impl: MsGraphTokenProviderImpl): MsGraphTokenProvider`
  - `@Provides @Named("msalClientId") fun provideMsalClientId(): String = BuildConfig.MSAL_CLIENT_ID` (BuildConfig 는 build.gradle.kts 에서 주입).

- **`android/app/src/main/AndroidManifest.xml`** (편집)
  - MSAL 에서 요구하는 `BrowserTabActivity` 선언 추가:
    ```xml
    <activity
        android:name="com.microsoft.identity.client.BrowserTabActivity"
        android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="msauth"
              android:host="com.becalm.android"
              android:path="/<keyhash>"/>
      </intent-filter>
    </activity>
    ```
  - `<keyhash>` 치환은 build variant 별로 별도 manifest merger 사용 권장.

- **`android/app/build.gradle.kts`** (편집)
  - `implementation("com.microsoft.identity.client:msal:5.x.x")` (context7 로 최신 stable 확인).
  - `buildConfigField("String", "MSAL_CLIENT_ID", "\"...\"")` — 값은 환경 변수 or local.properties 에서 주입.

### 5.5 Files to delete (dead code)

없음.

### 5.6 Non-code changes

- **Microsoft Entra 포털 앱 등록**: Android platform + redirect URI `msauth://com.becalm.android/<keyhash>` + 허용 scope `Mail.Read`, `offline_access`. CTO 운영 작업.
- **DB migration**: 없음.
- **Permission**: MSAL 내부적으로 browser intent 사용. 추가 Android permission 불필요 (INTERNET 은 이미 선언됨).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "class MsGraphTokenProviderImpl" android/app/src/main/java/ | wc -l` 가 1 이상.
- [ ] **Grep invariant**: `grep -rn "Mail\.Read" android/app/src/main/java/com/becalm/android/data/remote/msgraph/ | wc -l` 가 1 이상.
- [ ] **Grep invariant forbidden**: `grep -rn "Mail\.ReadWrite\|Mail\.Send\|Mail\.ReadBasic" android/app/src/main/java/ | wc -l` 가 0 이다.
- [ ] **Grep invariant forbidden**: `grep -n "ms_graph_access_token\|ms_graph_refresh_token" android/app/src/main/java/com/becalm/android/data/local/secure/EncryptedTokenStore.kt | wc -l` 가 0 이다 (EncryptedTokenStore 오염 금지).
- [ ] **Grep namespace isolation**: `grep -n "google_access_token\|ms_graph_access_token" android/app/src/main/java/com/becalm/android/data/local/secure/OAuthCredentialStore.kt | wc -l` 가 2 이상 (두 네임스페이스 공존).
- [ ] **Hilt binding**: `grep -n "bindMsGraphTokenProvider\|MsGraphTokenProviderImpl" android/app/src/main/java/com/becalm/android/core/di/AuthModule.kt | wc -l` 가 2 이상.
- [ ] **Asset**: `ls android/app/src/main/res/raw/msal_config.json` 존재.
- [ ] **Manifest**: `grep -n "BrowserTabActivity" android/app/src/main/AndroidManifest.xml | wc -l` 가 1 이상.
- [ ] **Unit test**: `MsGraphTokenProviderImplTest — getAccessToken refreshes silently when expired` 통과.
- [ ] **Unit test**: `MsGraphTokenProviderImplTest — acquireTokenSilent throws MsalUiRequiredException → state = ReauthRequired` 통과.
- [ ] **Unit test**: `MsGraphTokenProviderImplTest — scope requested = [Mail.Read, offline_access] exactly` 통과.
- [ ] **Unit test**: `OAuthCredentialStoreTest — clearMsGraph does not affect google_* keys` 통과.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공.
- [ ] **Invariant compliance**: `EncryptedTokenStore.kt` 은 diff 에서 변경되지 않음.

---

## 7. Out of Scope

- **Gmail / Google Identity provider** — 선행 커밋 `repo-auth-gmail-oauth-provider.md` 에서 이미 완료.
- **Outlook OAuth UI wiring** (`OutlookMailOAuthScreen.kt` 의 실제 버튼 핸들러 연결) — 별도 PR `feat/ui/onboarding/oauth-wiring`.
- **MS Graph API 본문/헤더/폴더 확장** — `ADAPT-OUT-001/002/003`, `ADAPT-EMAIL-*` 별도 플랜.
- **Outlook Calendar token** — 동일 MS Graph token 을 재사용할 계획이나 scope 확장 (`Calendars.Read`) 결정은 **별도 PR**. 본 PR 은 Mail.Read 만.
- **`EncryptedTokenStore` 수정** — **절대 금지**. Supabase-only 유지.
- **MSAL broker (Microsoft Authenticator app) 활용** — `broker_redirect_uri_registered:false` 로 고정, broker 미사용.
- **Multi-tenant enterprise 전용 인증** — `audience:common` 으로 consumer + work 계정 모두 허용. B2B tenant-locked 는 post-MVP.
- **Refresh token rotation 감사 로그** — post-MVP.

---

## 8. Dependencies

- **Blocked by**: `repo-auth-gmail-oauth-provider.md` (PR#1, 같은 브랜치 `feat/repo/auth` 의 선행 커밋).
  - 이유: `OAuthCredentialStore` 파일이 #1 에서 생성됨. 본 PR 은 그 파일을 확장.
- **Blocks**:
  - `feat/ui/onboarding/oauth-wiring` (PR#12 계열) — `startSignIn` / `observeTokenState` API 소비자.
  - `ADAPT-OUT-*` 후속 플랜들 — Unauthorized 해소되어야 downstream 검증 가능.
  - `feat/repo/calendar/outlook-calendar-token-scope-extension` (가상 미래 PR) — 동일 MSAL 인스턴스 재사용 필요.

### 8.1 Branch linearization (CLAUDE.md convention)

`feat/repo/auth` 브랜치는 선행 PR#1 의 로직 커밋 위에 본 PR 의 로직 커밋이 누적된다:
1. `feat(repo/auth): gmail-oauth-provider — …` (PR#1)
2. **`feat(repo/auth): msgraph-oauth-provider — MSAL PublicClientApplication 기반 MsGraphTokenProviderImpl + OAuthCredentialStore ms_graph namespace 확장`** (본 PR)

두 commit 이 쌓인 같은 PR 을 CTO merge.

### 8.2 Merge 순서

- 동일 브랜치 내 **linear stack** 이므로 merge 자체는 1 번. 단 구현 순서는 반드시 #1 → #2 (파일 dependency: `OAuthCredentialStore.kt`).
- 다른 모듈 (`fix/repo/imap`) 과 파일 겹침 없음 → 병렬 진행 가능.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시:
- `MsGraphTokenProvider` interface 는 stub 으로 복귀 → Outlook 워커 다시 전원 Unauthorized.
- `OAuthCredentialStore` 파일은 유지되지만 `ms_graph_*` 키 읽기/쓰기 경로가 사라짐 → 디스크에 남은 stale MS token 은 다음 앱 clear 시 제거.
- `msal_config.json` asset + Manifest 변경은 revert 에 포함됨 → 앱 실행 자체에 영향 없음 (MSAL 미초기화).
- 사용자에게는 MS 재로그인 요구가 최대 영향. 데이터 손실 없음.

---

## Appendix — Session handoff notes

- **MSAL 의 token cache 와 `OAuthCredentialStore` 이중 저장** — 법무 invariant 153 방어를 위함. 만약 법무 검토에서 MSAL 내부 cache 가 "Android Keystore 저장" 정의 충족으로 판단되면 `OAuthCredentialStore` 쪽 중복 저장은 제거 가능 (단순화). 본 플랜은 **방어적** 경로.
- **MSAL 5.x vs 4.x** — 5.x 는 AndroidX + targetSdk 34 지원. 4.x 는 legacy. context7 로 최신 stable 확인 후 선정.
- **`redirect_uri` keyhash 주의** — debug / release 빌드별 서명 인증서 SHA-1 다름 → redirect_uri 다름 → `msal_config.json` 도 다름. build variant sourceSet (`src/debug/res/raw/msal_config.json` + `src/release/res/raw/msal_config.json`) 로 분기 권장. 대안: `manifestPlaceholders` 로 주입.
- **Silent refresh 실패 UX** — `MsalUiRequiredException` 은 일반적 (refresh token 만료/revoke/conditional access 정책 변경 등). 반드시 UI 가 `observeTokenState()` 의 `ReauthRequired` 를 받아 persistent notification / banner 로 재인증 유도. 구현자는 UI PR 과의 인터페이스 확정 필요.
- **대안 검토**:
  - `net.openid:appauth-android` — scope/redirect 유연하나 MSAL 이 Microsoft endpoint 에 특화되어 edge case (conditional access, MFA, broker) 처리 우수 → MSAL 선정.
  - 서버 경유 OAuth — invariant 위반. 거부.
- **함정 — `acquireTokenSilentAsync` 는 Activity 불필요** — 따라서 백그라운드 worker (OutlookMailWorker) 에서 호출 가능. `getAccessToken()` 은 activity 없이 동작 가능하도록 설계되어야 함 (단 첫 로그인 `startSignIn` 만 Activity 필요).
- **테스트 팁** — MSAL 은 Robolectric 에서도 제한적 동작. 가장 실용적인 테스트는 `IPublicClientApplication` interface mock + callback 수동 호출. instrumented test (MSAL 실제 HTTP 스텁 불가) 는 smoke 용도로만.
- **Outlook Calendar** 와 token 을 공유할 때 scope 확장이 필요 — 현재 `Mail.Read` 만으로는 Calendar API 호출 불가. 별도 PR 에서 `MAIL_READ_SCOPE` → `MAIL_AND_CALENDAR_READ_SCOPE` 로 교체 또는 두 개의 scope set 을 구분해 provider API 확장. 본 PR 은 Mail 만.
- **context7 조회 권장**: `com.microsoft.identity.client:msal` 최신 버전 + `SingleAccountPublicClientApplication` API shape. 훈련 데이터와 차이 가능성.
