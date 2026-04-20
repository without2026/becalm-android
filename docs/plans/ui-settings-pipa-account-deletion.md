# UI / Settings / PIPA Account Deletion — 계정 삭제 (PIPA-005 삭제권)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행)
**Severity**: Critical (PIPA 제36조 삭제권 — MVP 법규 블로커, rollback 불가 경로)
**Type**: Gap (AccountDeletionScreen 부재, 2-step 확인 없음, Railway `DELETE /v1/users/me` 호출 경로 없음)

---

## 1. Finding

PIPA-005 는 **2-step confirmation (이메일 재입력 + '삭제' 타이핑) 후** Railway `DELETE /v1/users/me` 호출 → Room 파일 삭제 → DataStore clear → EncryptedSharedPreferences clear → LoginScreen 재시작 순서로 계정을 영구 삭제해야 한다.

현재:
- `AccountDeletionScreen` composable 부재.
- `Context.deleteDatabase("becalm_<user_id_hash>.db")` 호출 경로 부재.
- Railway `DELETE /v1/users/me` endpoint 가 Retrofit 인터페이스에 정의되지 않음 (`grep -rn "users/me" android/app/src/main/java/` 확인 필요).
- **기존 "Wipe Data" 버튼** (`SettingsSourcesSection.kt:43-48`) 은 로컬만 지우는 AUTH-005 경로 (`AuthRepository.signOut()`) 이므로 **서버 cascade 가 없다** — 삭제권 충족 X.

본 PR 은 **되돌릴 수 없는 경로** 를 MVP 에 도입한다. 구현 시 rollback 불가 invariant, 2-step gate, 사용자 재입력 검증, 서버 실패 시 rollback 없음 (재시도 유도) 을 spec 그대로 따라야 한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:56-64` — PIPA-005 전문
> "[계정 삭제] — 사용자 계정 및 모든 연관 데이터를 영구 삭제한다. 2-step confirmation + 명시적 영향 고지(되돌릴 수 없음). 실행 순서: (1) Supabase Auth Admin API로 auth.users row 삭제(cascade로 관련 테이블 RLS 기반 DELETE), (2) 로컬 Room DB 파일 Context.deleteDatabase(becalm_<user_id_hash>.db), (3) DataStore 해당 user 네임스페이스 clear, (4) EncryptedSharedPreferences clear, (5) 앱을 LoginScreen으로 복귀하며 processStart 재초기화"
>
> "AccountDeletionScreen 표시됨. 상단 경고 카드: '이 작업은 되돌릴 수 없습니다. 삭제 후 동일 이메일로 재가입해도 이전 데이터는 복구되지 않습니다. 약속 N건·연락처 보강 N건·이메일 N건이 함께 삭제됩니다.' (N은 Room SELECT COUNT로 실시간 집계). 첫 확인: 이메일 주소 재입력(사용자 계정 이메일과 일치). 두 번째 확인: '삭제'를 타이핑. 모두 통과 시 진행 표시기 → Railway DELETE /v1/users/me(서버가 Supabase Auth Admin delete + cascade) → 응답 200 시 Context.deleteDatabase + DataStore.edit { clear() } + EncryptedSharedPreferences.clearAll + Intent FLAG_ACTIVITY_CLEAR_TASK로 LoginScreen 재시작. 서버 호출 실패 시 rollback 없이 재시도 유도 Snackbar. ONB-PIPA 동의 기록 등 글로벌 키도 cascade"

### 2.2 `.spec/pipa-rights.spec.yml:90` — invariant
> "[계정 삭제]는 2-step confirmation(이메일 재입력 + '삭제' 타이핑) 통과 후에만 실행되며 rollback 불가"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 감사 로그
> "action: 'account_delete_initiated' … 계정 삭제 시 action_log 자체도 함께 삭제됨(local only 성격상 서버 보존 없음)"

### 2.4 PIPA-006 — 로그아웃과의 차별화 (`.spec/pipa-rights.spec.yml:68-72`)
> "SettingsScreen 로그아웃 버튼 옆에 툴팁 … 완전 삭제는 [개인정보 관리 > 계정 삭제]를 이용하세요"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 전용 경로 부재
```bash
grep -rn "AccountDeletionScreen\|DELETE /v1/users/me\|deleteUserAccount\|deleteDatabase(\"becalm" \
  android/app/src/main/java/
# → 0 hits
```

### 3.2 AuthRepository.signOut() — PIPA wipe 재사용 후보
`android/app/src/main/java/com/becalm/android/data/repository/AuthRepository.kt:190-192`:
```
add(NamedStep("syncCursorClear") { syncCursorStore.clearAll() })
add(NamedStep("userPrefsClearAll") { userPrefsStore.clearAll() })
add(NamedStep("databaseClearAll") { database.clearAllTables() })
```
로컬 wipe step 을 이미 가지고 있음. 본 PR 은 이 step list 를 **서버 DELETE 성공 후** 실행. 단 `database.clearAllTables()` 는 file 삭제가 아니라 **row 삭제** 이므로 spec "Context.deleteDatabase(…)" 와 다름 — 파일 자체 삭제가 더 강력. 구현 시 **file-delete 경로 추가** 필요.

### 3.3 EncryptedSharedPreferences wipe 경로
`EncryptedTokenStore` 에 `clearAll()` 유사 API 가 있는지 확인 필요 (본 plan 은 read 범위 밖). `AuthRepository.signOut()` 이 token 도 지우는 것으로 보이므로 signOut step list 재사용 가능 — 단 **순서** 가 spec 과 일치해야 함 (2단계 = server DELETE 먼저).

### 3.4 Railway API 인터페이스
`android/app/src/main/java/com/becalm/android/data/remote/api/RailwayApi.kt` (기존 파일 가정, `getSourceStatus` 참조 기반) — `@DELETE("v1/users/me")` 미선언. 본 PR 에서 추가.

### 3.5 FLAG_ACTIVITY_CLEAR_TASK 재시작
`grep -rn "FLAG_ACTIVITY_CLEAR_TASK\|finishAffinity" android/app/src/main/java/` — 확인 필요. 현재 sign-out 경로는 `navigate(BecalmRoute.Splash.path) popUpTo(0) inclusive=true` 사용 (`SettingsScreen.kt:77-83`) — **프로세스 재시작 아님**. spec 은 "processStart 재초기화" — `System.exit()` 는 안티패턴 → **Activity restart intent** 가 정석.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 2-step gate | 이메일 재입력 + '삭제' 타이핑 | 없음 | 신규 composable + ViewModel 검증 |
| Count 집계 | 약속 N · 연락처 N · 이메일 N | 없음 | DAO count 쿼리 × 3 |
| Railway DELETE | `DELETE /v1/users/me` | 없음 | Retrofit 인터페이스 추가 |
| Room file delete | `Context.deleteDatabase(...)` | `clearAllTables()` 만 | 경로 교체 |
| DataStore clear | `clearAll()` | 있음 | 재사용 |
| EncryptedSP clear | `EncryptedTokenStore.clearAll` | 있음(추정) | 재사용 |
| 프로세스 재시작 | `FLAG_ACTIVITY_CLEAR_TASK` LoginScreen | 현 nav 만 | restart intent |
| 실패 시 rollback 없음 | Snackbar 재시도 유도 | 해당 없음 (경로 부재) | 에러 처리 규약 |
| 감사 로그 | `account_delete_initiated` append | 없음 | `PipaActionLogStore` append |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - `PipaAccountDeletion` placeholder → 실체 `AccountDeletionScreen`.

2. **`android/app/src/main/java/com/becalm/android/data/remote/api/RailwayApi.kt`** (경로 가정)
   - `@DELETE("v1/users/me") suspend fun deleteCurrentUser(): Response<Unit>` 추가.

3. **`android/app/src/main/java/com/becalm/android/data/repository/AuthRepository.kt`**
   - 신규 메서드 `deleteAccount(): BecalmResult<Unit>`:
     - 1) `api.deleteCurrentUser()` — 실패 시 즉시 `BecalmResult.Failure` 반환 **(rollback 없이 Snackbar 유도 경로로 빠진다)**.
     - 2) 성공 시 순서대로: (a) `pipaActionLogStore.appendAction(ACCOUNT_DELETE_INITIATED)` (서버 ok 기록; 어차피 로컬도 곧 clear), (b) 기존 signOut step 재활용 + `Context.deleteDatabase(<db-name>)` 추가 step, (c) `EncryptedTokenStore.clearAll`, (d) `userPrefsStore.clearAll`, (e) `syncCursorStore.clearAll`, (f) `pipaActionLogStore.clearAll` (spec: "계정 삭제 시 action_log 자체도 함께 삭제됨").
   - 3) LoginScreen restart intent 는 ViewModel → Activity 에서 실행 (`AuthRepository` 는 Context 에 대한 강한 의존을 피하기 위해 signal 만 반환).

4. **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`**
   - `DATABASE_NAME` 상수 (단일 계정 모드로 현재 파일 하나) 를 공개 — `deleteDatabase(DATABASE_NAME)` 에서 참조.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/AccountDeletionScreen.kt`**
   - 역할: 경고 카드 + N건 집계 + 이메일 TextField + "삭제" 타이핑 TextField + [삭제] 버튼 (비활성 default).
   - ActivityResult 없음 — 모든 gate 는 로컬 validation.
   - 성공 후 LaunchedEffect: `(activity as MainActivity).restartApp()` 호출 → Activity finishAffinity + PendingIntent → AlarmManager 즉시 재시작 또는 간단히 `Intent(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK)` + `finishAffinity`.

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/AccountDeletionViewModel.kt`**
   - UiState: `currentEmail`, `enteredEmail`, `typedKeyword`, `commitmentCount`, `personCount`, `emailCount`, `phase` (`Idle | InFlight | Error | Done`).
   - 의존성: `AuthRepository`, `CommitmentDao`, `PersonEnrichmentDao`, RawIngestionEventDao + emailBodyDao (count), `PipaActionLogStore` (pre-server append optional), `Logger`, `@IoDispatcher`.
   - 검증:
     - `enteredEmail == currentEmail` (대소문자 ignore).
     - `typedKeyword == "삭제"` (exact).
     - 둘 다 만족해야 [삭제] 버튼 enabled.
   - 실행: `authRepository.deleteAccount()` → Failure 시 `phase = Error(message)` + Snackbar, Success 시 `phase = Done` → 화면 LaunchedEffect 가 restart 수행.

3. **DAO count 쿼리 (필요 시 신규 메서드)**:
   - `CommitmentDao.countForUser(userId): Int`
   - `PersonEnrichmentDao.countForUser(userId): Int`
   - `RawIngestionEventDao.countEmailEventsForUser(userId): Int` (source_type IN ('gmail','outlook_mail','naver_imap','daum_imap')).
   - 모두 **deleted_at IS NULL** 필터와 무관하게 **전수 count** (사용자에게 "삭제될 것" 을 보여주는 용도).

4. **`android/app/src/main/java/com/becalm/android/MainActivity.kt`** (기존 파일 가정)
   - `public fun restartApp()` — FLAG_ACTIVITY_CLEAR_TASK + finishAffinity + Intent relaunch. 본 PR 이 최초 사용자.

5. **strings.xml** — 경고 카드 문구, 이메일 placeholder, "삭제" hint, 버튼 라벨, 실패 Snackbar 문구.

6. **Tests**:
   - `AccountDeletionViewModelTest — 이메일 불일치 시 버튼 disabled`.
   - `AccountDeletionViewModelTest — "삭제" 외 키워드 시 버튼 disabled`.
   - `AccountDeletionViewModelTest — 서버 Failure 시 rollback 없이 Error phase` (로컬 clear 호출 0 검증).
   - `AuthRepositoryDeleteAccountTest — 서버 OK 후 step 순서 검증 (deleteDatabase → userPrefs.clearAll → sync clear → encrypted clear → action log clear)`.

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- **Railway backend** (`becalm-backend` 레포): `DELETE /v1/users/me` 엔드포인트 구현 — Supabase Auth Admin `auth.admin.deleteUser(user_id)` 호출. 본 PR 머지 전 backend 선행 배포.
- **Supabase RLS**: 모든 사용자 데이터 테이블이 `auth.uid()` 기반 CASCADE 설정 확인 (backend 책임).
- 본 PR 은 **production 배포 후 rollback 불가** — CTO 구두 승인 필요.

---

## 6. Acceptance Criteria

- [ ] **Unit test**: `AccountDeletionViewModelTest — 2-step gate 4 케이스 (email 일치/불일치 × 키워드 일치/불일치) 정확히 하나만 enable`.
- [ ] **Unit test**: `AuthRepositoryDeleteAccountTest — 서버 DELETE 실패 시 로컬 clear 메서드 mock 가 **하나도** invoke 되지 않음`.
- [ ] **Unit test**: 서버 성공 시 순서 검증 (mock ordered verify).
- [ ] **Instrumentation test**: `AccountDeletionIntegrationTest — 전체 경로 실행 후 Room DB 파일 존재하지 않음 (Context.getDatabasePath)`.
- [ ] **Grep invariant (Room 파일 삭제 경로)**: `grep -n "Context.deleteDatabase\|deleteDatabase(" android/app/src/main/java/com/becalm/android/data/repository/AuthRepository.kt | wc -l` ≥ 1.
- [ ] **Grep invariant (Retrofit DELETE)**: `grep -n "@DELETE(\"v1/users/me\"\\)" android/app/src/main/java/ | wc -l` = 1.
- [ ] **Grep invariant (action_log clear)**: `grep -n "pipaActionLogStore.clearAll\|clearActionLog" android/app/src/main/java/ | wc -l` ≥ 1.
- [ ] **Manual**: Railway staging 에서 실제 사용자 생성 → 삭제 → 재가입 → 이전 데이터 복구되지 않음 확인.

---

## 7. Out of Scope

- Supabase Auth Admin API 권한 부여 / backend endpoint 구현 — devops/backend.
- 다중 계정 지원 — 현재 single account 모델.
- "특정 기간 내에만 복구 가능" 같은 유예 기간 — spec 에 없음. rollback 불가가 spec invariant.
- Railway 응답이 202 (async) 인 경우의 poll — spec 은 200 즉시 성공 가정. 구현 시 backend 계약 재확인.

---

## 8. Dependencies

- **Blocked by**:
  - `ui-settings-privacy-management.md` umbrella (route placeholder).
  - `becalm-backend` 에 `DELETE /v1/users/me` 배포 선행.
- **Blocks**: 없음. (PIPA-006 툴팁은 umbrella 가 이미 포함.)
- **파일 겹침**:
  - `AuthRepository.kt` — 다른 sub-PR 과 겹치지 않음 (예상).
  - `RailwayApi.kt` — backend-sync / source-status 와 겹칠 수 있으나 method add only → 병합 충돌 낮음.
- **병렬 가능**: Export / Consent / Pause / Activity log 와 파일 겹침 없음.

---

## 9. Rollback plan

**핵심 invariant**: **rollback 없음**. 서버 측 사용자 삭제가 완료된 후에는 되돌릴 수 없다 — spec 명시.

배포 rollback 시나리오:
- 본 PR 이 프로덕션 배포 후 **사용자가 실제 삭제를 실행하기 전에** 발견된 버그라면 git revert → 다음 버전에서 수정.
- 사용자가 이미 삭제 경로를 완료했다면 복구 불가. 운영 책임.

오탐(false positive)으로 인한 잘못된 삭제는 **2-step gate 가 유일한 방어선**. gate 통과는 "사용자 명시 의사" 로 법적 해석. CTO / 법무 선행 리뷰 필요.

---

## Appendix — Session handoff notes

- **Context.deleteDatabase vs clearAllTables**: Room 이 open 된 상태에서 `deleteDatabase` 는 즉시 반환되지 않을 수 있음 (file lock). 안전 경로:
  1. `database.close()`
  2. `context.deleteDatabase(name)`
  Room 은 Hilt singleton 이라 `close()` 후 다음 접근 시 재생성되어 file 이 다시 생길 수 있음 — restart intent 전에 DAO 접근 금지.
- **진짜 "processStart 재초기화"** 원할 시 `Runtime.getRuntime().exit(0)` 은 안티패턴. 권장: `MainActivity.restartApp()` 이 새 Intent 로 앱을 재시작하고 현재 task 를 close. Hilt singleton graph 는 새 Activity 생성 시 자연 재생성 (Application scope 는 process 동일성 유지 — 이는 기대 동작과 반대). 필요 시 `killProcess(Process.myPid())` 고려하되 UX 관점 ANR 위험 — **구현 세션 결정** 후 CTO 승인.
- **Count 쿼리 성능**: 이메일 body 가 수만 건인 계정은 COUNT 쿼리가 순간 느림. 화면 진입 시 skeleton 또는 "계산 중..." 표시. 본 plan 은 최초 진입 시 1 회 COUNT 허용.
- **2-step gate UX**: "이메일 재입력" 은 **로그인된 계정의 이메일** (Supabase session.email) 과 **정확히 일치**. 공란 제거 + 대소문자 무시 비교 권장.
- **"삭제" 키워드의 i18n**: 한국어 "삭제" hard-coded. 영문 locale 추가 시 "delete" 등 대응 — strings.xml 의 `pipa_delete_keyword` 로 외부화.
- **서버 실패 분류**:
  - 401 → 세션 만료. re-login 유도 (ERR-008 경로 연계).
  - 429 → rate limit. Retry-After backoff.
  - 5xx → Snackbar "잠시 후 다시 시도" (spec "rollback 없이 재시도 유도").
  - 네트워크 오류 → 동일.
- Activity log store (`PipaActionLogStore`) 에 `account_delete_initiated` 를 **서버 호출 성공 후** 기록하고, 직후 `clearAll()` 로 log 자체 삭제. 둘의 간격은 ms 단위 — 운영 로그로 유실되는 것이 spec 의도("local only … 함께 삭제됨").
