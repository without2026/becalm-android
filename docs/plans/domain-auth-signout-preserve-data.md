# Domain / Auth / signout-preserve-data — 일반 로그아웃이 Room 데이터를 지우지 않도록 경로 정리 (AUTH-005)

**Branch**: `refactor/domain/auth/signout-preserve-data`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 (인증 / 세션 lifecycle)
**Severity**: High
**Type**: Drift

---

## 1. Finding

스펙 AUTH-005 는 "로그아웃 시 Supabase Auth 세션이 종료되고 로컬 토큰 및 해당 user의 in-memory
Room 연결이 해제된다. Room DB 파일은 디스크에 유지" 라고 명시한다.
코드에는 두 경로가 **이미** 공존한다:

- `AuthRepositoryImpl.signOut()` (line 166-201) — **PIPA 전체 wipe**. `database.clearAllTables()`
  (line 192) + `userPrefsStore.clearAll()` (line 191) + `syncCursorStore.clearAll()` (line 190)
  까지 호출.
- `AuthRepositoryImpl.invalidateSession()` (line 203-239) — **세션만 정리**. Room/cursor/userPrefs 보존.

즉 두 함수의 역할 분리 자체는 올바르다. **문제는**:

1. **AuthViewModel.onSignOut()** (line 140-155) 이 `authRepository.signOut()` 를 호출 —
   즉 Top bar / 시스템적 로그아웃이 일반 로그아웃 경로를 태우면 PIPA wipe 가 발생.
   `AuthViewModel` 의 `onSignOut` 은 현재 호출처가 **SettingsScreen 이 아닌** 화면에서
   사용될 수 있는 "공통 로그아웃" 엔트리 — 어느 UI 가 이걸 호출하는지 감사 필요.
2. **SettingsViewModel.onSignOut()** (line 277-286) 는 정상적으로 `invalidateSession()` 호출
   — OK. 그러나 `AuthViewModel.onSignOut` 와 이름이 같아 혼동을 유발.
3. **TermsScreen 이용약관 "[동의 안 함]"** (TermsScreen.kt:117-122) 는 현재 `Activity.finish()`
   로 처리 — `AUTH-006` expected 와 부합. 그러나 로그인 이후 Terms 로 돌아오는 경로는 없다.
   AUTH-005 의 "TermsScreen → LoginScreen 순으로 복귀" expected 는 **로그아웃 후 재진입 시**
   해석인데, 현재 Splash/Nav 흐름이 로그아웃 후 Terms 로 재노출되는지 재점검 필요.

본 PR 은 (a) `AuthViewModel.onSignOut()` 이 `invalidateSession()` 을 호출하도록 변경하고,
(b) `signOut()` (PIPA wipe) 엔트리는 **SettingsViewModel.onWipeLocalData** 단일 경로로 수렴,
(c) 로그아웃 후 navigation 이 TermsScreen 을 거쳐 LoginScreen 으로 가는지 검증 + nav 필요 시
정리를 수행한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/auth.spec.yml:43-50` — AUTH-005

> description: "로그아웃 시 Supabase Auth 세션이 종료되고 로컬 토큰 및 해당 user의
> in-memory Room 연결이 해제된다. Room DB 파일(AUTH-008 규칙으로 user-scoped)은 디스크에
> 유지되어 동일 계정으로 재로그인 시 데이터가 복원된다. DataStore에서 사용자별 cursor·온보딩
> 플래그도 보존 — 단 글로벌 키(PIPA 동의 timestamp 등 current session state)는 초기화"
>
> expected: "HTTP 204 — EncryptedSharedPreferences의 access_token/refresh_token 삭제됨,
> 현재 user의 Room DB 연결은 close()되지만 DB 파일(becalm_<user_id_hash>.db)은 삭제되지 않음,
> DataStore의 user-scoped key들은 보존됨. TermsScreen → LoginScreen 순으로 복귀"

### 2.2 `.spec/auth.spec.yml:82` — invariant

> "로그아웃 시 Room DB 데이터는 삭제하지 않는다 (로컬 데이터 보존)"

### 2.3 `.spec/pipa-rights.spec.yml` (참고)

`signOut()` (full wipe) 는 PIPA 열람권·파기권 행사 시에만 트리거되어야 함. 일반 로그아웃은
이에 해당하지 않음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `AuthViewModel.kt:140-155` — 잘못된 경로

```kotlin
// spec: AUTH-005
public fun onSignOut() {
    viewModelScope.launch {
        _uiState.value = AuthUiState.Loading
        when (val result = authRepository.signOut()) {    // ← PIPA wipe 경로
            is BecalmResult.Success -> {
                logger.d(TAG, "sign-out completed")
            }
            is BecalmResult.Failure -> {
                logger.w(TAG, "sign-out failed")
                _uiState.value = AuthUiState.Error(result.error.safeMessage)
            }
        }
    }
}
```

— `// spec: AUTH-005` 주석이 달려 있지만, AUTH-005 의 본문은 "Room 데이터 보존" 이다.
`authRepository.signOut()` 는 `database.clearAllTables()` 를 호출하므로 **정반대**.

### 3.2 `AuthRepositoryImpl.kt:192` — PIPA wipe step

```kotlin
add(NamedStep("databaseClearAll") { database.clearAllTables() })
```

— 본 경로를 `AuthViewModel.onSignOut` 가 태우면 AUTH-005 위반.

### 3.3 `SettingsViewModel.kt:277-286` — 올바른 경로

```kotlin
public fun onSignOut() {
    runAuthOp(
        failureLogPrefix = "sign-out failed",
        op = { authRepository.invalidateSession() },      // ← 보존 경로 OK
        onSuccess = {
            logger.d(TAG, "sign-out completed (session invalidated, Room preserved)")
            _uiState.update { it.copy(loading = false, signedOut = true) }
        },
    )
}
```

### 3.4 `AuthViewModel.onSignOut()` 호출처 감사

```bash
grep -rn "viewModel\.onSignOut\|authViewModel\.onSignOut\|AuthViewModel.*onSignOut" \
  android/app/src/main/java/com/becalm/android/ui/
# → 본 세션에서는 명시적 호출처 확인 못 함. 구현 세션에서 반드시 전수 조회.
```

SettingsScreen.kt:107 에서 호출하는 것은 `SettingsViewModel.onSignOut` (올바른 경로).
`AuthViewModel.onSignOut` 가 실제로 어느 UI 버튼과 연결되어 있는지 구현 세션이 `grep` 으로 확인 필요 — 만약 **아무 UI 도 호출하지 않는다면** dead path 로 정리.

### 3.5 `AuthRepository.invalidateSession` 의 userPrefsStore 처리 (line 225-226)

```kotlin
add(NamedStep("currentUserIdClear") { userPrefsStore.setCurrentUserId(null) })
```

— `currentUserIdClear` 만 호출. "user-scoped key 보존, 글로벌 session state 초기화" 라는
스펙 문구와 부합한다. 다만 **`user-scoped key namespacing`** 자체가 `feat/db/auth/user-scoped-database`
PR 이후에야 의미를 가진다. 두 PR 의 순서는 §8 Dependencies 참조.

### 3.6 로그아웃 후 navigation

```bash
grep -rn "BecalmRoute.Login\|BecalmRoute.Terms\|popUpTo" \
  android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt \
  android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt
```

`SettingsScreen` 의 signOut 성공 처리에서 어디로 navigate 하는지 확인해야 함. AUTH-005 expected
는 "TermsScreen → LoginScreen 순" 이라고 지정. 재진입 시 `terms_accepted = true` 여도 다시
Terms 를 한 번 더 보여주는 의도라면 nav graph 조정 필요. 그러나 현재 `SplashScreen` 은
`terms_accepted=true && signed_in` 이면 Today 로 바로 보낸다 — 로그아웃 후에는 signed_in=false
이므로 Splash 가 Login 으로 보낼 가능성이 높다. 이 부분은 **구현 세션에서 재확인** 후 필요 시
`BecalmNavHost` 의 post-signout nav 를 Login 으로 고정.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 일반 로그아웃 | Room 데이터 보존 | `AuthViewModel.onSignOut` → PIPA wipe 호출 | VM 을 `invalidateSession()` 로 교체 |
| PIPA wipe 진입점 | 사용자 의도한 "전체 삭제" 만 | `AuthViewModel.onSignOut` + `SettingsViewModel.onWipeLocalData` 두 곳 | `AuthViewModel.onSignOut` 이 wipe 를 타지 않도록 단일화 |
| Nav after logout | TermsScreen → LoginScreen | Splash → Login (Terms skip) 가능성 | BecalmNavHost 점검 후 필요 시 수정 |
| dead path | AuthViewModel.onSignOut 가 호출처 없으면 제거 | 현재 호출처 미검증 | 구현 세션이 grep 후 결정 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/auth/AuthViewModel.kt`**
   - `onSignOut()` 의 `authRepository.signOut()` 호출을 `authRepository.invalidateSession()` 로 교체.
   - KDoc 의 "PIPA-compliant local data wipe" 문구를 제거하고 "세션 정리, Room 보존" 으로 재서술.
   - `// spec: AUTH-005` 주석을 정확히 갱신. 추가로 "PIPA wipe 경로는 SettingsViewModel.onWipeLocalData" 를 `@see` 로 연결.
   - Hilt dependency 변경 없음.

2. **`android/app/src/main/java/com/becalm/android/data/repository/AuthRepository.kt`**
   - `signOut()` KDoc 의 "routine sign-out 이 아닌 deliberate PIPA wipe" 를 더 강하게 명시.
   - `AuthRepository.signOut` 의 함수 이름을 **차후 PR 에서** `wipeLocalData()` 로 rename 할 여지 — 본 PR 에서는 rename 하지 않음 (scope creep). 대신 KDoc `@deprecated` 는 달지 않고 "Intended for PIPA Article 30 flows only" 문구만 강화.

3. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - 로그아웃 성공 이후 SplashScreen 이 Login 으로 보내는지 확인 후, `observeAuthState` 가
     `Unauthenticated` 를 emit 할 때 `terms_accepted=false` 면 Terms 로, `true` 면 Login 으로
     가도록 분기 (Splash 로직 재사용). 현재 그대로 맞다면 no-op.

4. **`android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt`**
   - `SettingsUiState.signedOut == true` 에서 `navController.navigate(BecalmRoute.Login.path) { popUpTo(0) }` 로 루트 리셋. 기존 구현과 일치하는지 확인 후 필요 시 정리.

### 5.2 Files to add

- `android/app/src/test/java/com/becalm/android/ui/auth/AuthViewModelSignOutTest.kt`
  - VM 의 `onSignOut()` 호출 시 `authRepository.invalidateSession()` 이 정확히 1회 호출되고, `authRepository.signOut()` 은 **0회** 임을 Mockk 로 검증.
- `android/app/src/test/java/com/becalm/android/data/repository/AuthRepositoryInvalidateSessionTest.kt` (이미 있다면 보강)
  - invalidateSession 의 step 목록이 다음을 **포함** 하고 `databaseClearAll`/`userPrefsClearAll`/`syncCursorClear` 를 **포함하지 않음** 을 검증:
    - cancelAllWorkers, stopContentObservers, serverRevoke, imapCredentialClear, sessionStoreClear, tokenProviderInvalidate, deviceKeyClear, currentUserIdClear
  - 기존 persons_enrichment.deleteAll 포함 여부는 현재 repo 구현 그대로 유지 — AUTH-005 스펙은 persons_enrichment 의 명시적 처리를 언급하지 않음. 오히려 `data-model.yml:474` 는 "persons_enrichment: 로그아웃 시 전체 삭제" 라고 적혀 있어 **스펙 간 미세 충돌** 이 있음 — §7 Out of Scope 참조.
- `android/app/src/androidTest/java/com/becalm/android/ui/auth/SignOutFlowTest.kt` — 간단 통합 테스트: 로그인 → 로그아웃 후 Room commitments count > 0 유지.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- `Logger` 에 새 문구 추가 요 없음.
- Sentry breadcrumb (별도 PR 로 추가 예정) 에 `auth_sign_out` / `auth_wipe_local_data` 분리 이벤트 태그를 남길 수 있도록 이 PR 은 **함수명 유지**.

### 5.5 Navigation 흐름 (재정비 체크리스트)

로그아웃 후:
1. `AuthState.Unauthenticated` emit → `AuthViewModel` 의 `observeAuthState` collector 가 `AuthUiState.SignedOut(termsAccepted=<DataStore>)` 발행.
2. Nav graph: `SplashScreen` 또는 현재 화면 (SettingsScreen) 에서 Login 으로 이동.
3. `terms_accepted=true` 면 Terms 스킵. AUTH-005 의 "TermsScreen → LoginScreen 순" 은 **"한 번 더 약관을 제시" 가 아니라 Splash 단계에서 Terms 경유하지 않고 Login 으로 직행** 을 말한다고 해석 (구현자 재확인). 근거: ONB-006 은 onboarding_completed=true 면 Terms 도 skip 한다고 명시 — AUTH-005 와 혼합시 "세션 소멸 시 Login 으로" 가 자연스러운 해석.
   - 만약 "터미널 약관 재노출" 이 진짜 의도라면 구현 전 CTO 확인 필요 (CommunicationProtocol 의 "불명확 시 질문").

---

## 6. Acceptance Criteria

- [ ] **Unit test**: `AuthViewModelSignOutTest — onSignOut 호출 시 invalidateSession 1회, signOut 0회`.
- [ ] **Unit test**: `AuthRepositoryInvalidateSessionTest — databaseClearAll 이 step 리스트에 없다`.
- [ ] **Unit test**: `AuthRepositoryInvalidateSessionTest — userPrefsClearAll, syncCursorClearAll 이 step 리스트에 없다`.
- [ ] **Integration test (androidTest)**: `SignOutFlowTest — 로그인 → commitment 1개 insert → invalidateSession → Room commitments count == 1`.
- [ ] **Integration test**: `SignOutFlowTest — 재로그인 (동일 계정) 후 commitment 1개 그대로 보임`.
- [ ] **Grep invariant**:
   `grep -n "authRepository\\.signOut()" android/app/src/main/java/com/becalm/android/ui/ | wc -l` 이 1 이다 (SettingsViewModel 의 `onWipeLocalData` 한 곳만).
- [ ] **Grep invariant**:
   `grep -n "authRepository\\.invalidateSession()" android/app/src/main/java/com/becalm/android/ui/ | wc -l` ≥ 2 (SettingsViewModel.onSignOut + AuthViewModel.onSignOut).
- [ ] **Manual**: 로그인 → 설정 로그아웃 → 재로그인 시 commitments/today timeline 복원.
- [ ] **Manual**: "로컬 데이터 전체 삭제" 버튼 → 확인 다이얼로그 → 재로그인 시 empty state.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` 성공.

---

## 7. Out of Scope

- `persons_enrichment.deleteAll()` 이 일반 로그아웃에서도 삭제되어야 하는지 여부 — data-model.yml:474
  note 와 auth.spec.yml:82 invariant 간 미세 충돌. 본 PR 은 현재 코드 동작 (invalidateSession 은 삭제 안 함) 을 유지.
  별도 resolution PR 에서 CTO 가 확정. 현 시점 스펙 문구를 엄격히 보면 "Room DB 데이터" 범주에 persons_enrichment 도 포함 → 보존이 맞음.
- `AuthRepository.signOut()` 의 함수명 rename (`wipeLocalData()`) — scope creep.
- Settings UI 에서 "로그아웃" 과 "로컬 데이터 전체 삭제" 의 시각적 구분 강화 — UX PR 분리.
- Sentry / analytics 이벤트 추가 — `feat/ui/onboarding/notifications-sentry` PR 에서 cover.
- `terms_accepted` 재노출 여부 nav 정책 — 현재 동작 유지. 만약 수정 필요하면 별도 PR.
- AUTH-004 토큰 갱신 경로 — 건드리지 않음.

---

## 8. Dependencies

- **Blocked by**:
  - `feat/db/auth/user-scoped-database` (AUTH-008) — AUTH-005 의 "현재 user 의 Room 연결 close(), DB 파일 유지" 라는 **파일 단위** invariant 는 AUTH-008 이 선행되어야 성립. 본 PR 이 AUTH-008 없이 먼저 머지되어도 코드 동작 (DAO 주입된 싱글톤 DB 를 close 하지 않음) 은 바뀌지 않지만, AUTH-008 후에 `invalidateSession` 에 `userDatabaseProvider.close()` step 추가가 필요해진다. 머지 순서: AUTH-008 → 본 PR.
- **Blocks**:
  - `feat/ui/onboarding/notifications-sentry` (Sentry) 의 `onboarding_step_failed` 이벤트와 별도로 `auth_sign_out` / `auth_wipe_local_data` breadcrumb 추가 PR.
- **병렬 가능**: `feat/ui/auth/google-signin-wiring` — 겹침 없음.

---

## 9. Rollback plan

- Revert 한 줄로 안전: `git revert <commit-sha>`.
- Revert 후 영향: `AuthViewModel.onSignOut` 이 다시 `authRepository.signOut()` (wipe) 을 호출 — 이것이 오히려 기존 drift 상태로의 복귀. 사용자 영향 없음 (rollback 시점에 이미 로그아웃 된 사용자는 없으므로).
- 주의: 본 PR 머지 후 새 테스트들이 추가되므로 revert 시 해당 테스트 파일도 함께 제거 (`git revert` 가 자동 처리).

---

## Appendix — Session handoff notes

- 본 PR 은 **Behavior change 가 작지만 이름·KDoc 정정·테스트 추가 범위가 중요**. 작은 코드 diff 로 큰 안정성 이득.
- `AuthViewModel.onSignOut()` 이 실제로 어느 UI 에서 호출되는지 구현 세션이 먼저 확정해야 한다. SettingsScreen 외 경로가 없다면 `AuthViewModel.onSignOut` 자체를 삭제하고 AuthViewModel 은 로그인 전용 VM 으로 축소하는 편이 설계상 깔끔. 그러나 그 결정은 scope creep — **본 PR 은 "invalidateSession() 을 호출한다" 로만 수정** 하고 삭제는 후속 리팩토링 PR 로.
- `userPrefsStore.clearAll()` (signOut/wipe 경로) vs `setCurrentUserId(null)` (invalidateSession 경로) 의 분리는 이미 코드에 정확히 반영되어 있음. 본 PR 은 해당 분리를 **강화** 만 한다.
- `AuthRepositoryImpl.invalidateSession()` 의 step 순서 (worker → observer → revoke → imap → session → tokenProvider → deviceKey → currentUserId) 는 유지. 추가 step 제안: `feat/db/auth/user-scoped-database` 머지 이후 `userDatabaseProvider.close()` 가 `sessionStoreClear` **이전** 에 들어가야 한다 — 세션 store 가 먼저 비어 있으면 observer 가 cached access token 을 날려 in-flight worker 의 race 가능. 본 PR 은 이 순서 변경을 위한 TODO 만 기록.
- 테스트: `AuthRepositoryImpl` 은 이미 `runAllSteps` / `runStepNamed` 헬퍼로 step 을 분리 운영 — Mockk 로 각 dependency 호출 수 검증이 깔끔.
- `Logger` 의 실 구현은 Timber 기반일 가능성이 높음 (`EncryptedTokenStore.kt:116` 에서 Timber 직접 사용). `AuthRepositoryImpl.logger` 는 인젝션된 wrapper — stdout capture 로 테스트하지 말고 Mockk 로 검증.
- AUTH-005 expected 의 "TermsScreen → LoginScreen 순으로 복귀" 문구가 **Splash 화면에서 terms_accepted 판단 후** 로 가는 경로를 의미하는지는 구현자가 `SplashScreen` 로직을 열어 보고 단언할 것. 만약 실제 nav 가 Login 으로 직행하고 있어 스펙 문구와 미세 괴리가 있다면 **스펙 드래프트 재확인** 필요 — CommunicationProtocol 의 "불명확 시 AskUserQuestion".
