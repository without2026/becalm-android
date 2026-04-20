# E2E Verification — `auth` 모듈

Spec: `becalm-android/.spec/auth.spec.yml` (version 1, platform=android)

> **목적**: CTO가 AUTH-001 ~ AUTH-007 각 behavior 가 실제 코드 상에서 어떤 파일을 타고 흐르는지 한눈에 보고, grep 한 번으로 검증할 수 있게 한다.

---

## 0. 전체 흐름 (High-level wiring)

```
UI (Compose Screen)
  ├─ LoginScreen                  ui/auth/LoginScreen.kt
  ├─ TermsScreen                  ui/auth/TermsScreen.kt
  └─ SplashScreen                 ui/auth/SplashScreen.kt
        │  (collect AuthUiState)
        ▼
ViewModel
  └─ AuthViewModel                ui/auth/AuthViewModel.kt
        │  onEmailSignIn / onGoogleSignIn / onSignOut / onObserveSession
        ▼
Repository (interface + impl)
  └─ AuthRepository(Impl)         data/repository/AuthRepository.kt
        │  signInWithEmail / signInWithGoogle / signOut / refreshSession
        ▼
Remote clients
  ├─ SupabaseAuthClient(Impl)     data/remote/supabase/SupabaseAuthClient.kt
  └─ AuthInterceptor              data/remote/interceptor/AuthInterceptor.kt  (401 → refresh → single retry)
        ▼
Local secure storage
  ├─ EncryptedTokenStore          data/local/secure/EncryptedTokenStore.kt
  ├─ SupabaseSessionStore         data/remote/supabase/SupabaseSessionStore.kt
  └─ EncryptedPrefsFactory        data/local/secure/EncryptedPrefsFactory.kt  (EncryptedSharedPreferences 생성)
```

Contract refs:
- API: `.spec/contracts/api-contract.yml` — `POST /auth/v1/token` / `POST /auth/v1/logout`
- UI: `.spec/contracts/ui-map.yml` — `LoginScreen`, `TermsScreen`, `SplashScreen`

---

## AUTH-001 — 이메일/비밀번호 로그인 성공 → 토큰을 EncryptedSharedPreferences에 저장

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/auth/LoginScreen.kt` | form submit → `AuthViewModel.onEmailSignIn` |
| VM | `ui/auth/AuthViewModel.kt:94` | `onEmailSignIn(email, password)` |
| Repo | `data/repository/AuthRepository.kt:142` | `AuthRepositoryImpl.signInWithEmail` |
| Remote | `data/remote/supabase/SupabaseAuthClient.kt:124` | `signInWithEmail` (`supabase.auth.signInWith(Email)`) |
| Persist | `data/remote/supabase/SupabaseSessionStore.kt` | `save(session)` — EncryptedSharedPreferences 래퍼 `EncryptedTokenStore` |

**Verify**:
```
grep -n "signInWithEmail" becalm-android/android/app/src/main/java/com/becalm/android/**/*.kt
grep -n "EncryptedSharedPreferences" becalm-android/android/app/src/main/java/com/becalm/android/data/local/secure/*.kt
```
- [ ] 로그인 성공 시 `SupabaseSessionStore.save()` 호출 경로가 `AuthRepositoryImpl.signInWithEmail` success branch에서 invariant("토큰은 EncryptedSharedPreferences 또는 Keystore에만 저장") 를 위반하지 않는지 확인.
- [ ] Unit test: `android/app/src/test/.../ui/auth/AuthViewModelTest.kt` — `SignedIn` state 전이 테스트 존재.

---

## AUTH-002 — 잘못된 비밀번호 → 400 → LoginScreen 에러 메시지

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Remote 실패 매핑 | `data/remote/supabase/SupabaseAuthClient.kt:230` | `mapRestException(e: RestException)` → `BecalmError` |
| Repo | `AuthRepository.kt:142` | `signInWithEmail` returns `BecalmResult.Failure` |
| VM | `AuthViewModel.kt:94` | Failure → `AuthUiState.Error(message)` |
| UI | `ui/auth/LoginScreen.kt` | `AuthUiState.Error` collect → 에러 메시지 렌더 |

**Verify**:
- [ ] `mapRestException` 의 400 분기가 사용자 메시지 "이메일 또는 비밀번호가 올바르지 않습니다" 로 귀결되는지 확인 (LoginScreen resource key + VM mapping).
- [ ] invariant check: LoginScreen 은 raw exception message 를 그대로 노출하지 않는다.

---

## AUTH-003 — Google OAuth (id_token grant) 로그인

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/auth/LoginScreen.kt` | Google 버튼 → `AuthViewModel.onGoogleSignIn(idToken)` |
| VM | `AuthViewModel.kt:116` | `onGoogleSignIn` |
| Repo | `AuthRepository.kt:152` | `signInWithGoogle(idToken)` |
| Remote | `SupabaseAuthClient.kt:137` | `signInWithGoogleIdToken` |

**Verify**:
- [ ] Supabase client 가 `IDToken` provider 로 호출되고 access/refresh 토큰이 `SupabaseSessionStore` 에 저장되는지.
- [ ] Google client_id 가 `BuildConfig` 또는 strings resource 로 주입되는지 (`core/di/NetworkModule.kt` 검토).

---

## AUTH-004 — access_token 만료 시 refresh_token 으로 자동 갱신

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Repo | `AuthRepository.kt:274` | `refreshSession()` |
| Remote | `SupabaseAuthClient.kt:149` | `refresh(refreshToken)` |
| Interceptor | `data/remote/interceptor/AuthInterceptor.kt:50` | `intercept` — 401 감지 시 `refreshSession` 후 1회 재시도 (AUTH-007 참조) |

**Verify**:
```
grep -n "refreshSession\|refresh(" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/interceptor/AuthInterceptor.kt
```
- [ ] `AuthInterceptor` 가 401 시 `refreshSession → 같은 request.rebuild()` 순서로 1회만 재시도하는지 (루프 가드).

---

## AUTH-005 — 로그아웃

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `AuthViewModel.kt:140` | `onSignOut()` |
| Repo | `AuthRepository.kt:159` | `signOut()` — 원격 logout → 세션 invalidate → local token clear 순서 |
| Remote | `SupabaseAuthClient.kt:173` | `signOut(accessToken)` → `POST /auth/v1/logout` |
| Local | `SupabaseSessionStore` + `EncryptedTokenStore` | `clear()` — invariant: Room DB 는 유지 |

**Verify**:
- [ ] `signOut` 경로에서 `database.clearAllTables()` 같은 호출이 **없는지** 확인 (Room 보존 invariant).
- [ ] `UserPrefsStore.setCurrentUserId(null)` 는 호출되지만 enrichment / raw_ingestion 테이블은 건드리지 않는지.

---

## AUTH-006 — 이용약관 미동의 시 앱 종료

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/auth/TermsScreen.kt` | [미동의] 버튼 → `activity.finish()` 또는 `finishAndRemoveTask()` |
| DataStore | `data/local/datastore/UserPrefsStore.kt:169` | `setTermsAccepted(false)` — onboarding_completed=false 유지 |

**Verify**:
- [ ] TermsScreen [미동의] 콜백이 실제로 Activity finish 경로로 연결되는지 (`MainActivity.kt` or Nav exit).
- [ ] DataStore `terms_accepted` 키는 동의 탭에서만 `true` 로 flip.

---

## AUTH-007 — 401 수신 시 refresh → 재시도 1회 → 실패 시 LoginScreen 이동

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Interceptor | `data/remote/interceptor/AuthInterceptor.kt:50` | `intercept()` — 401 분기 |
| Refresh call | `AuthRepository.kt:274` | `refreshSession()` |
| Fallback | `AuthViewModel.kt:165` | `onObserveSession()` collect → `Unauthenticated` 상태 시 Navigation 에서 Login 으로 재진입 |
| Nav | `ui/navigation/BecalmNavHost.kt:83` | `BecalmRoute.Login` |

**Verify**:
- [ ] 재시도 실패 경로가 `AuthRepository.invalidateSession()` → `AuthState.Unauthenticated` flow 로 연결되는지 (`AuthRepositoryImpl.kt:218`).
- [ ] Retry loop guard: 동일 요청이 두 번째 401 을 받으면 더 이상 refresh 하지 않음 (interceptor 단 1회).

---

## Invariants — 자동 검증 후크 제안

| Invariant | 검증 grep |
| --- | --- |
| 토큰은 EncryptedSharedPreferences / Keystore 에만 | `grep -rn "getSharedPreferences\|datastore" becalm-android/android/app/src/main/java \| grep -i token` 결과가 오직 `EncryptedPrefsFactory` / `DeviceKeyStore` 경로로만 나오는지 |
| 로그아웃 시 Room 데이터 유지 | `grep -rn "clearAllTables\|deleteAll" becalm-android/android/app/src/main/java/com/becalm/android/data/repository/AuthRepository*.kt` → **히트 0건** |
| Bearer 헤더 일관성 | `grep -n "Authorization" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/interceptor/AuthInterceptor.kt` → 모든 Railway 요청 경로에 `Bearer` prefix |

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `android/app/src/test/java/com/becalm/android/ui/auth/AuthViewModelTest.kt` | AUTH-001/002/003/005 상태 전이 |
| (없음) | AUTH-004/006/007 — **spec 파일의 `tests: []` 가 비어 있음 → CTO 확인 후 테스트 추가 지시 필요** |
