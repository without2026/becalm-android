# Domain / Auth / signout-preserve-data — 기본 로그아웃 UX 를 `invalidateSession` 으로 교체 + AuthRepository 스펙 정합

**Branch**: `refactor/domain/auth/signout-preserve-data`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 1 — Auth (sign-out UX)
**Severity**: **High** (기본 로그아웃이 Room 전체 삭제 → user 가 재로그인 시 로컬 이력 소실; spec invariant 직접 위반)
**Type**: Drift (spec 요구 ≠ 구현 기본값)

---

## 1. Finding

`AuthViewModel.onSignOut()` 이 `authRepository.signOut()` (full PIPA wipe) 를 호출한다 (`AuthViewModel.kt:141-156`). 그러나 AuthRepository KDoc (`AuthRepository.kt:82-96`) 과 AUTH-005 spec invariant "**로그아웃 시 Room DB 데이터는 삭제하지 않는다**" 는 routine sign-out 이 Room 을 보존해야 함을 명시. 현재 구조:

- `signOut()` → Room + 모든 store wipe (PIPA 전체 삭제 UX 전용)
- `invalidateSession()` → 세션·토큰·IMAP cred 만 clear, Room/prefs 보존 (routine UX)

`SettingsViewModel.kt:280` 은 `invalidateSession()` 을 이미 올바르게 호출한다. 그러나 AuthViewModel 의 기본 `onSignOut()` 은 여전히 full wipe 를 부른다. 이 불일치가 (1) 스펙 위반 (2) "로그아웃 했더니 내 약속이 다 사라짐" UX regression 을 낳는다.

S6-A (user-scoped DB) 선행 후 본 plan 을 적용하면 per-user DB 파일 + session invalidate 의 조합이 spec intent 를 완전히 충족한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `AuthRepository.invalidateSession` KDoc line 82-96
> "로그아웃 시 Room DB 데이터는 삭제하지 않는다, routine sign-out must only clear authentication state"

### 2.2 `.spec/auth.spec.yml` AUTH-005
> "정상 로그아웃은 세션만 무효화하고 로컬 데이터는 보존해야 한다. '로컬 데이터 전체 삭제' 는 Settings > Privacy 의 별도 액션으로만 제공된다."
> (인용: AuthRepository invalidateSession KDoc 에 동일 문구가 존재하여 spec 의 실질적 contract 역할을 함)

### 2.3 `.spec/onboarding.spec.yml` AUTH-005 ↔ ONB 연계
> "재로그인 시 onboarding 재진입 조건: `onboarding_completed=false` 일 때만" — onboarding 이 이미 완료됐다면 재로그인 시 곧바로 홈 화면. Room 보존이 이를 가능하게 함.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 AuthViewModel 기본 sign-out → full wipe
- **`ui/auth/AuthViewModel.kt:141-156`**:
  ```kotlin
  public fun onSignOut() {
      viewModelScope.launch {
          _uiState.value = AuthUiState.Loading
          when (val result = authRepository.signOut()) { ... }
      }
  }
  ```

### 3.2 SettingsViewModel 은 둘 다 분기
- **`ui/settings/SettingsViewModel.kt:280`** — `invalidateSession()` (routine)
- **`ui/settings/SettingsViewModel.kt:303`** — `signOut()` (full wipe, "로컬 데이터 전체 삭제" CTA)

### 3.3 AuthRepository 은 이미 두 경로 구현됨
- `signOut()` / `invalidateSession()` 모두 구현 완료 — 본 plan 은 **호출측 교정** 만 한다.

검증 grep:
```bash
grep -rn "authRepository\\.signOut\\(\\)\\|authRepository\\.invalidateSession\\(\\)" android/app/src/main/
grep -rn "onSignOut\\b" android/app/src/main/java/com/becalm/android/ui/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| AuthViewModel.onSignOut | `invalidateSession()` 호출 | `signOut()` 호출 | 1-line swap |
| Repository API | 이미 양분됨 | OK | 변경 없음 |
| DB close on sign-out | `databaseProvider.close()` (S6-A 전제) | DB 열려있음 | S6-A 블로커 |
| Tests | `AuthViewModelTest.onSignOut_preservesRoom` | 없음 | 신규 |
| AUTH-005 spec 명시 | 별도 spec 항목 | invariant 문구만 KDoc 에 | spec 문서 amendment (out of scope) |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`ui/auth/AuthViewModel.kt:141-156`** — `authRepository.signOut()` → `authRepository.invalidateSession()`. KDoc 갱신 ("routine sign-out preserves Room data per AUTH-005").
- **`ui/auth/AuthViewModel.kt` 주석** — 기존 KDoc 의 "PIPA-compliant local data wipe" 문구 제거 (사실과 불일치).
- **`ui/settings/SettingsViewModel.kt`** — 변경 없음 (이미 정확).

### 5.2 Files to add
- **tests**: `android/app/src/test/java/com/becalm/android/ui/auth/AuthViewModelTest.kt` 에
  - `onSignOut_callsInvalidateSessionNotSignOut` — mockk verify 로 repository 의 `invalidateSession()` 만 호출되고 `signOut()` 은 호출되지 않음을 증명.
  - `onSignOut_doesNotCallDatabaseClearAllTables` — Fake Repository 가 내부 database mock 의 `clearAllTables` 미호출을 검증.

### 5.3 Files to delete (dead code)
- 없음. AuthRepository 의 `signOut()` 은 Settings 의 "로컬 데이터 전체 삭제" CTA 에서 여전히 사용.

### 5.4 Non-code changes
- `.spec/auth.spec.yml` 에 AUTH-005 의 "routine vs full-wipe 구분" 을 명시화 — 별도 spec amendment PR (**out of scope**).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "authRepository\\.signOut" android/app/src/main/java/com/becalm/android/ui/auth/AuthViewModel.kt | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "authRepository\\.invalidateSession" android/app/src/main/java/com/becalm/android/ui/auth/AuthViewModel.kt | wc -l` ≥ 1
- [ ] **Unit test**: `AuthViewModelTest.onSignOut_callsInvalidateSessionNotSignOut` 통과
- [ ] **Unit test**: `AuthViewModelTest.onSignOut_doesNotCallDatabaseClearAllTables` 통과
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: 동일 계정으로 로그인→로그아웃→로그인 후 이전 약속·person 목록이 유지됨

---

## 7. Out of Scope

- **`signOut()` 구현 변경** — 이미 정확하게 PIPA wipe 담당. 본 plan 은 호출측만.
- **Settings UI 변경** — SettingsViewModel / SettingsScreen 은 이미 올바른 CTA 양분. W7 PIPA umbrella 가 별도로 정비.
- **S6-A user-scoped DB 실제 구현** — 전제. 본 plan 은 S6-A 의 BeCalmDatabaseProvider 가 이미 존재한다는 전제.
- **Spec 문서 amendment** — `.spec/auth.spec.yml` 에 AUTH-005 routine vs full-wipe 이분화 명시. 별도 PR.

---

## 8. Dependencies

- **Blocked by**: `docs/plans/db-auth-user-scoped-database.md` (S6-A) — 본 plan 의 "routine sign-out 후 재로그인 시 데이터 유지" invariant 가 의미 있으려면 user-scoped DB 가 먼저 존재해야 함. (코드적 strict blocker 아님 — `invalidateSession` 자체는 S6-A 없이도 실행되나, S6-A 없이는 누출 리스크가 남음.)
- **Blocks**: 없음.

merge 순서: **S6-A → 본 plan**.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 `onSignOut()` 이 다시 `signOut()` 호출 → routine 로그아웃 시 Room wipe 로 UX regression. 단 보안적 누출 리스크는 없음. 재적용은 trivial.

---

## Appendix — Session handoff notes

- **왜 `onSignOut` 이름을 바꾸지 않는가**: 공개 API 로 UI 가 직접 호출 (AuthScreen, Settings 등). 이름 변경은 UI-wide 파괴. 본 plan 은 구현만 교체.
- **Settings 의 "로컬 데이터 전체 삭제" 와 중복되지 않는가**: Settings 경로는 "full wipe" 를 명시적으로 동의받은 뒤 호출. routine sign-out 과는 의미 다름 (즉 UX 측면에서는 2 개 CTA 모두 필요).
- **PIPA 감사 로그**: routine sign-out 도 `pipa_action_log` 에 "session_invalidated" event 를 남겨야 할 수 있음. W7 PIPA umbrella 에서 다룸 — 본 plan 범위 아님.
- **규모 추정**: 1 line 코드 변경 + 2 테스트. 30 분.
