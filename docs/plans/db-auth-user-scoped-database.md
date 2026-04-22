# DB / Auth / user-scoped-database — Per-user Room DB 파일로 교차 계정 데이터 누출 차단

**Branch**: `feat/db/auth/user-scoped-database`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 1 — Auth (post-sign-in DB scope resolution)
**Severity**: **Critical** (PIPA 교차 계정 데이터 누출 리스크 — 현재 `invalidateSession()` 경로가 Room 을 보존하므로 동일 기기의 다른 사용자가 직전 사용자의 약속·음성 이력을 볼 수 있다)
**Type**: Gap (user-scoping 미구현)

---

## 1. Finding

`BeCalmDatabase.DATABASE_NAME` 은 단일 문자열 `becalm.db` 로 하드코드되어 있다 (`BeCalmDatabase.kt:133`). `BeCalmDatabase.build(context)` 는 user identity 를 받지 않고 Hilt 가 `@Singleton` 으로 1개 인스턴스를 고정한다 (`DatabaseModule.kt:53`). 이로 인해:

1. 사용자 A 가 `invalidateSession()` (routine sign-out, `AuthRepository.kt:215-255`) 으로 Room 을 보존한 채 로그아웃 →
2. 동일 기기에서 사용자 B 가 로그인 → 동일 `becalm.db` 파일이 로드됨 → B 가 A 의 commitment/person/raw_ingestion_events 를 본다.

`signOut()` 경로 (`AuthRepository.kt:174-213`) 는 `database.clearAllTables()` 로 Room 을 비우므로 누출이 없지만, 이는 "로컬 데이터 전체 삭제" UX 이지 기본 로그아웃이 아니다. spec invariant "**로그아웃 시 Room DB 데이터는 삭제하지 않는다**" (AuthRepository KDoc line 84) 는 preservation 을 요구하므로 file-name level user scoping 이 필수.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/auth.spec.yml` AUTH-005 + AuthRepository.invalidateSession KDoc
> "로그아웃 시 Room DB 데이터는 삭제하지 않는다"

→ 로그아웃 후 **다른 사용자가 로그인**하면 그 사용자의 Room 은 **비어 있어야** 한다 (혹은 본인의 과거 데이터만 보여야 한다).

### 2.2 `.spec/pipa-rights.spec.yml` PIPA 원칙
> "정보주체별·목적별 동의" / "제3자 제공받기" — 데이터는 동의 주체에게만 종속되어야 하며, 다른 정보주체가 접근할 수 없어야 한다.

### 2.3 Wave plan P0 재배치 결정 (`docs/plans/_wave-plan.md:395-410`)
> "이 plan 은 `BeCalmDatabase.kt` build() 시그니처 자체를 바꿈 (user_id hash 기반 파일명)"
> "계정 전환 leakage 법규 리스크가 P0"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 단일 파일명
- **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt:133`**:
  ```kotlin
  public const val DATABASE_NAME: String = "becalm.db"
  ```
- **`BeCalmDatabase.kt:157-164`**:
  ```kotlin
  public fun build(context: Context): BeCalmDatabase =
      Room.databaseBuilder(context, BeCalmDatabase::class.java, DATABASE_NAME)
          .addMigrations(*MIGRATIONS)
          .build()
  ```

### 3.2 Hilt Singleton scope
- **`android/app/src/main/java/com/becalm/android/core/di/DatabaseModule.kt:53`**:
  ```kotlin
  @Provides @Singleton
  fun provideDatabase(@ApplicationContext context: Context): BeCalmDatabase =
      BeCalmDatabase.build(context)
  ```
  → 동일 process 에서 user 가 바뀌어도 **같은 인스턴스가 재사용**됨.

### 3.3 `currentUserId` 는 이미 persisted
- **`UserPrefsStore.kt:44-46, 234-249`** — `observeCurrentUserId(): Flow<String?>` + `setCurrentUserId(userId)` 가 이미 존재. 본 plan 의 key 는 이 값에서 파생.

### 3.4 AuthRepository 가 signIn 시 userId persist
- **`AuthRepository.kt:159-160, 169-170`** — signInWithEmail/signInWithGoogle 성공 시 `userPrefsStore.setCurrentUserId(value.userId)` 호출됨.

검증 grep:
```bash
grep -rn "DATABASE_NAME\|becalm\\.db" android/app/src/main/
grep -rn "BeCalmDatabase.build\|provideDatabase" android/app/src/main/
grep -rn "observeCurrentUserId\|setCurrentUserId" android/app/src/main/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| DB 파일명 | user-id hash 기반 (`becalm-<sha256-8>.db`) | 고정 `becalm.db` | 파생 + atomic rename 전략 |
| build() 시그니처 | `build(context, userIdHash)` | `build(context)` | 1 arg 추가 + 기존 caller 전체 refactor |
| Hilt scope | user-session scope 내 singleton (재생성 가능) | `@Singleton` (process lifetime) | user 바뀌면 close + rebuild |
| 로그인 경로 | signIn 성공 후 DB open | DB 가 항상 열려 있음 | sign-in hook 에서 DB swap |
| 로그아웃 경로 | `invalidateSession` 후 DB close | DB 가 그대로 열려 있음 | `invalidateSession` 에 `database.close()` 단계 추가 |
| Room schema bump | 불필요 (파일명만 변경) | — | v6 유지 |
| Pre-migration (익명 `becalm.db`) | 현 alpha 단계엔 없음 (설치 후 첫 로그인 전엔 DB 미생성) | — | 단, 기존 알파 사용자 기기엔 `becalm.db` legacy 가 존재할 수 있음 → 1회 rename or delete |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`BeCalmDatabase.kt`** —
  - `DATABASE_NAME` 제거, 대신 `fun filename(userIdHash: String): String = "becalm-${userIdHash}.db"` + `private const val LEGACY_DATABASE_NAME = "becalm.db"`.
  - `build(context: Context, userIdHash: String)` 시그니처로 교체. `userIdHash` 는 base16 lowercase SHA-256 앞 8바이트 (16문자).
  - sign-in 전용 helper `fun deriveUserIdHash(userId: String): String` 를 companion 에 추가 (pure hash, 테스트 가능).
- **`core/di/DatabaseModule.kt`** —
  - `@Provides @Singleton` 을 **제거**하고, `@Provides @Singleton fun provideDatabaseProvider(...): BeCalmDatabaseProvider` 로 교체. Provider 는 `currentUserId` Flow 를 observe 해 user 가 바뀌면 `close()` 후 `build(context, newHash)`.
  - `@Provides fun provideDatabase(provider: BeCalmDatabaseProvider): BeCalmDatabase = provider.current()` — unscoped, 매 호출마다 provider.current() 가 최신 인스턴스 반환. 단 DAO 주입 지점이 많으므로 **DAO provider 들도 provider 경유** 해야 함.
- **`core/di/DatabaseModule.kt` 내 DAO provides** — 각 `@Provides fun provideCommitmentDao(db: BeCalmDatabase)` 를 `provideCommitmentDao(provider: BeCalmDatabaseProvider): CommitmentDao = provider.current().commitmentDao()` 로.
- **`data/repository/AuthRepository.kt`** —
  - signInWithEmail / signInWithGoogle 성공 블록에 `databaseProvider.ensureOpenFor(userIdHash)` 추가 (currentUserId persist 직후).
  - `invalidateSession()` / `signOut()` 의 step list 에 `databaseClose` 추가 (signOut 은 `clearAllTables` 뒤에 `close`).
- **`ui/auth/AuthViewModel.kt`** — 변경 없음 (viewmodel 은 repository 만 호출).

### 5.2 Files to add
- **`data/local/db/BeCalmDatabaseProvider.kt`** — Hilt `@Singleton`. 책임:
  - `fun ensureOpenFor(userIdHash: String)` — 기존 인스턴스가 있고 hash 가 다르면 `close()` 후 `BeCalmDatabase.build(context, userIdHash)`.
  - `fun close()` — 기존 인스턴스 close + null 로 세팅.
  - `fun current(): BeCalmDatabase` — 열려 있지 않으면 `error("DB not opened — sign in first")` (fail-loudly).
  - 내부 lock 으로 concurrent open/close race 방지 (`java.util.concurrent.locks.ReentrantLock`).
- **tests**:
  - `BeCalmDatabaseTest.deriveUserIdHash_stablePrefix16Chars` (순수 hash 테스트).
  - `BeCalmDatabaseProviderTest.ensureOpenFor_closesPriorWhenUserChanges` — Room in-memory fake 로 두 user 시뮬레이션, 파일 경로 check.
  - `AuthRepositoryImplTest.invalidateSession_closesDatabase` — mock Provider 의 close() 호출 검증.

### 5.3 Files to delete (dead code)
- 없음. `DATABASE_NAME` const 제거는 in-file. 기존 `becalm.db` legacy 파일은 **첫 `ensureOpenFor`** 호출 시 `context.deleteDatabase(LEGACY_DATABASE_NAME)` 로 1회 cleanup (bootstrapping 주석 명시).

### 5.4 Non-code changes
- 없음. Android 매니페스트 / permission / gradle 변경 없음.
- Room autoMigration 불필요 (파일명만 변경 — 동일 schema).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "DATABASE_NAME\\s*=\\s*\"becalm\\.db\"" android/app/src/main/ | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "becalm-\\${" android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -rn "BeCalmDatabaseProvider" android/app/src/main/ | wc -l` ≥ 2 (선언 + 사용)
- [ ] **Unit test**: `BeCalmDatabaseTest.deriveUserIdHash_stablePrefix16Chars` 통과
- [ ] **Unit test**: `BeCalmDatabaseProviderTest.ensureOpenFor_closesPriorWhenUserChanges` 통과
- [ ] **Unit test**: `AuthRepositoryImplTest.invalidateSession_closesDatabase` 통과
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: 두 개 계정으로 순차 로그인 후 데이터 상호 노출 없음

---

## 7. Out of Scope

- **기존 `becalm.db` legacy 파일 데이터 보존**: alpha 단계이므로 첫 user-scoped 로그인 시 **삭제**. 데이터 마이그레이션 불필요.
- **멀티 세션 동시 사용자**: BeCalm 은 single-account-per-process 전제. 동시 두 계정 지원은 post-MVP.
- **암호화**: Room 파일 자체 암호화 (SQLCipher) 는 별도 plan. 본 plan 은 user-scoping 만.
- **PIPA action log append**: sign-in/out 시 per-user DB swap 이벤트 기록은 W7 (PIPA umbrella) 에서.

---

## 8. Dependencies

- **Blocked by**: 없음. `observeCurrentUserId` 는 이미 존재.
- **Blocks**:
  - `docs/plans/domain-auth-signout-preserve-data.md` (S6-B) — `invalidateSession()` 이 `databaseProvider.close()` 를 호출하려면 본 plan 선행 필요.
  - W7 PIPA account-deletion plan — DB wipe 경로 재정의.

merge 순서: **본 plan → S6-B**. 같은 브랜치에 sequential 커밋도 허용.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
1. `becalm-<hash>.db` 파일들은 orphan 으로 남음. 기기에서 app 재설치 또는 수동 clear-app-data 로 정리.
2. 기존 `becalm.db` 파일은 `deleteDatabase` 가 이미 실행됐으면 영구 소실. alpha 단계이므로 영향 최소.
3. 교차 계정 누출 리스크가 복귀되므로, 복귀 시 `signOut()` (full wipe) 경로만 UI 에 노출 권장.

---

## Appendix — Session handoff notes

- **왜 파일명 hash**: DataStore key/별도 DB 테이블로 user 를 가드하는 것보다 OS file system level 에서 **isolation** 이 훨씬 강함. 파일이 물리적으로 다름 → Room/SQLite 가 혼동할 가능성 제로.
- **Hash 선택**: SHA-256 앞 8바이트 (16 hex 문자) — collision 확률 2^-64 수준. user id 자체를 파일명에 넣으면 UUID 36자 → 파일 시스템 경로 길이 취약. Hash 가 안전.
- **DatabaseProvider 가 @Singleton 인 이유**: 한 process 안에서는 최대 1 user 활성. Provider 상태가 global 이므로 Singleton 적합.
- **Hilt DAO provides 전면 변경이 필요한 이유**: Hilt 는 `@Singleton BeCalmDatabase` 를 `@Inject DAO` 인자로 캐시한 시점에 고정. user swap 시 DAO 도 invalid 됨 → provides 함수 경유가 필수.
- **close() race**: Provider 내부에 `ReentrantLock`. current() 호출이 open 중인 close() 와 겹치지 않도록.
- **tests**: Robolectric 없이도 Room in-memory 빌더 + 별도 JVM unit test 에서 file-based builder 실행 가능. 파일 경로 비교는 `context.getDatabasePath(name)` 이용.
- **왜 Room autoMigration 없음**: 파일명만 변경 — 동일 schema 이므로 Room 은 새 파일을 "신규 DB" 로 간주 → migration 경로 타지 않음.
- **규모 추정**: Provider 1 파일 신규, BeCalmDatabase 수정, DatabaseModule 대폭 수정, AuthRepository 훅 추가. 테스트 3개. 1-1.5 세션.
