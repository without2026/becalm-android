# DB / Auth / user-scoped-database — Room DB 파일 user_id 단위 격리 (AUTH-008)

**Branch**: `feat/db/auth/user-scoped-database`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1–2 (Auth / Room 초기화)
**Severity**: Critical
**Type**: Drift

---

## 1. Finding

현재 Room DB 는 전역 고정 파일명 `becalm.db` 한 개만을 사용한다
(`BeCalmDatabase.kt:86` `public const val DATABASE_NAME: String = "becalm.db"`).
스펙 AUTH-008 은 **로그인한 Supabase `user_id` 별로** DB 파일을 분리하도록 요구한다 —
파일명 규칙 `becalm_<sha256(user_id)[:16]>.db`. 현재 구조는 동일 디바이스에서 계정 A → 계정 B 로
전환 시 **이전 계정의 Room 데이터가 그대로 새 계정 세션에 보이는 data-leakage** 상태이며,
PIPA 제3자 제공·열람권 invariant 를 위반한다.

또한 DataStore (`becalm_user_prefs`, `becalm_sync_cursors`) 는 전역 파일 2개로 관리되고 있어
동일한 문제가 있다. 스펙은 `user_<hash>` 네임스페이스로 사용자별 cursor·온보딩 플래그를 분리
저장하라고 명시한다 (auth.spec.yml:73).

본 PR 은 (1) Room DB 파일 이름을 user-scoped 로 전환, (2) Hilt graph 에서 DB provider 를
`@Singleton` 전역 → 로그인 user scope 로 재설계, (3) DataStore key 에 `user_<hash>`
namespace 를 적용, (4) 로그인/로그아웃 시 DB 연결 lifecycle 을 명시적으로 관리하도록 하는
구조 변경을 제안한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/auth.spec.yml:71-78` — AUTH-008 (lifecycle)

> "Room DB 파일은 로그인한 Supabase user_id 단위로 분리된다 — 파일명 규칙
> `becalm_<sha256(user_id)[:16]>.db`. 동일 디바이스에서 계정 전환(예: 회사 → 개인) 시
> 이전 계정의 데이터가 새 계정 세션에 섞이지 않고, 동일 계정으로 재로그인 시 기존 DB 파일이
> 그대로 열려 로컬 데이터가 복원된다. DataStore의 사용자별 상태(cursor_*, contacts_permission_asked,
> pipa_consent_timestamp 등)는 동일하게 `user_<hash>` 네임스페이스로 분리 저장"

expected:
> "Application context에서 Room.databaseBuilder(ctx, AppDatabase::class.java,
> 'becalm_${user_id_hash}.db').build() 호출됨. 동일 계정 재로그인 시 기존 파일
> re-open(데이터 보존). 다른 계정 로그인 시 별도 파일 생성. 파일 리스트는
> ContextCompat.getDatabasesDir()에 복수 존재 가능. 계정 삭제(미래) 시에만 해당 파일
> Context.deleteDatabase(name) 호출"

### 2.2 `.spec/auth.spec.yml:84` — invariant

> "Room DB 파일은 user_id 기반으로 분리된다 — 한 디바이스에서 여러 계정 사용 시에도
> 데이터 격리가 보장된다 (AUTH-008)"

### 2.3 `.spec/auth.spec.yml:43-50` — AUTH-005 (보존)

> "로그아웃 시 Supabase Auth 세션이 종료되고 로컬 토큰 및 해당 user의 in-memory Room
> 연결이 해제된다. Room DB 파일(AUTH-008 규칙으로 user-scoped)은 디스크에 유지되어 동일
> 계정으로 재로그인 시 데이터가 복원된다."

AUTH-005 는 AUTH-008 의 **필연적 귀결** — "in-memory 연결만 해제, 파일은 유지" 가 성립하려면
파일명이 user 별이어야 한다.

### 2.4 `.spec/contracts/data-model.yml:476` — migration_note

> "Room DB 파일 분리 정책: DB 파일명은 `becalm_<sha256(supabase_user_id)[:16]>.db`
> 형식으로 user 단위 격리 (AUTH-008). 동일 디바이스에서 계정 전환·공유 시 RLS와 무관하게
> 로컬 레벨에서 데이터가 섞이지 않는다. 로그아웃은 해당 user의 DB 연결을 close()할 뿐
> 파일 삭제는 하지 않음 — 동일 계정 재로그인 시 데이터 복원"

### 2.5 PIPA 연결

ONT-PIPA (onboarding.spec.yml:17-25) 가 voice 오디오의 국외 이전 동의를 전제로 하는 만큼,
"계정 A 의 녹음 데이터가 계정 B 사용자에게 보이는" 상태는 개인정보보호법 17조 위반이다.
본 item 은 **법규 블로커** — MVP 출시 전 반드시 해결.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt:83-118`

```kotlin
public companion object {
    public const val DATABASE_NAME: String = "becalm.db"
    public const val DATABASE_VERSION: Int = 3

    public fun build(context: Context): BeCalmDatabase =
        Room.databaseBuilder(
            context,
            BeCalmDatabase::class.java,
            DATABASE_NAME,              // ← 전역 고정 이름
        )
            .addMigrations(*MIGRATIONS)
            .build()
}
```

— 파일명이 compile-time 상수. `user_id` 주입 경로가 아예 없음.

### 3.2 `android/app/src/main/java/com/becalm/android/core/di/DatabaseModule.kt:48-52`

```kotlin
@Provides
@Singleton
public fun provideBeCalmDatabase(
    @ApplicationContext context: Context,
): BeCalmDatabase = BeCalmDatabase.build(context)
```

— `@Singleton` 전역. 로그인 전에 `Application.onCreate()` 이후 아무 DAO 주입 요청에서나
`BeCalmDatabase` 가 lazy 하게 열릴 수 있는데, 이 시점엔 `user_id` 를 알 방법이 없음.

### 3.3 `android/app/src/main/java/com/becalm/android/core/di/DataStoreModule.kt:42-47`

```kotlin
private val Context.syncCursorsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_sync_cursors")

private val Context.userPrefsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_user_prefs")
```

— DataStore 파일 2개 모두 전역 고정. key 에도 namespace 없음.
`UserPrefsStore` 가 `observeCurrentUserId()` 를 제공하지만 이것은 "현재 로그인 user"
mirror 일 뿐, 저장된 값 자체가 user 별로 격리되지 않는다.

### 3.4 `android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt:72`

```kotlin
public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
```

— 기존 `becalm.db` (전역) 위에 쓰인 migration. user-scoped 로 전환하면 기존 사용자는
"per-user DB 파일로 onboarding 데이터 옮기는" 1회성 migration 이 추가로 필요 (§5.4).

### 3.5 `android/app/src/main/java/com/becalm/android/data/repository/AuthRepository.kt:132,192`

```kotlin
private val database: BeCalmDatabase,   // Hilt 가 전역 싱글톤 주입
...
add(NamedStep("databaseClearAll") { database.clearAllTables() })
```

— `signOut()` (PIPA wipe) 에서 `clearAllTables()` 를 호출. user-scoped 로 전환 후엔
"해당 user 의 DB 파일만 `Context.deleteDatabase(name)` 로 지우기" 가 되어야 한다.
`invalidateSession()` 은 DB 를 건드리지 않지만, in-memory 연결 close() 책임이 본 PR
범위에서 생긴다.

### 3.6 grep 검증

```bash
grep -rn "DATABASE_NAME\|becalm\.db\|becalm_sync_cursors\|becalm_user_prefs" \
  android/app/src/main/java/com/becalm/android/
# → DATABASE_NAME 4곳, becalm_sync_cursors 3곳, becalm_user_prefs 3곳 모두 고정 상수
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| DB 파일명 | `becalm_<sha256(user_id)[:16]>.db` | `becalm.db` 고정 | user_id hash suffix 추가 + build() 시그니처 변경 |
| DB lifecycle | 로그인 시 open, 로그아웃 시 close | `@Singleton` Application scope (process life) | Hilt scope 재설계 (UserSession-scoped) |
| DataStore 격리 | `user_<hash>` namespaced keys | 전역 파일, 전역 key | key prefix 전략 또는 per-user 파일 |
| 계정 전환 data leak | 격리 보장 | 이전 계정 Room 데이터 그대로 노출 | 본 PR 의 핵심 수정 |
| 로그아웃 wipe | 파일 보존 + 연결 close() | `clearAllTables()` (table truncate) + 파일 유지 | close() 경로 추가, PIPA wipe 는 `deleteDatabase(name)` |
| 계정 삭제 (미래) | `Context.deleteDatabase(name)` 만 호출 | 경로 없음 | 스텁 API 추가 (구현은 MVP 이후) |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만.**

### 5.1 핵심 아이디어

Hilt `SingletonComponent` 에서 `BeCalmDatabase` 를 직접 제공하는 대신,
**`UserDatabaseProvider`** 라는 `@Singleton` 게이트웨이를 둔다. 이 provider 는
"현재 열린 user DB instance 와 user_id hash" 를 atomically 보관하고, 로그인 완료 시
`openFor(userId)` / 로그아웃 시 `close()` 로 lifecycle 을 제어한다. DAO 는 `UserDatabaseProvider`
를 거쳐 lazy 하게 DAO reference 를 받는다. **로그인 전에는 DAO 사용 시점에서 명시적으로 IllegalStateException** —
"로그인 없이 Room 에 접근" 은 스펙 위반이므로 silent fail 금지.

### 5.2 Files to change

1. **`BeCalmDatabase.kt`**
   - `DATABASE_NAME` 상수 제거. `build(context, databaseName: String)` 으로 시그니처 확장.
   - 파일명 생성 helper 추가: `fun databaseNameFor(userId: String): String = "becalm_${sha256(userId).take(16)}.db"`.
   - `sha256` helper 는 `android.util.Base64` / `MessageDigest` 로 구현, `core/util/Hashing.kt` 에 분리.

2. **`core/di/DatabaseModule.kt`**
   - `provideBeCalmDatabase(@ApplicationContext)` 제거. 대신 `provideUserDatabaseProvider(@ApplicationContext): UserDatabaseProvider` 만 제공.
   - DAO provider 들은 `db: BeCalmDatabase` 인자 대신 `provider: UserDatabaseProvider` 를 받아
     `provider.requireDatabase().commitmentDao()` 형태로 조회. DAO 는 `@Singleton` 이 아니므로 매 주입마다 재평가 — login/logout 을 타고 정상적으로 새 DAO 를 얻는다.
   - 또는 DAO 자체를 `UserDatabaseProvider.dao{...}` 형태의 **lazy accessor** 로 변경.
     (구현 세션에서 두 방법 중 Hilt 구성에 자연스러운 쪽 선택.)

3. **신규 `data/local/db/UserDatabaseProvider.kt`**
   - 필드: `@Volatile private var current: Holder?` where `Holder(val userIdHash: String, val db: BeCalmDatabase)`.
   - `suspend fun openFor(userId: String): BeCalmDatabase` — `databaseNameFor(userId)` 로 파일명 생성, 동일 hash 면 현재 holder 재사용, 다른 hash 면 이전 holder `db.close()` 후 새 DB 오픈.
   - `suspend fun close()` — holder 가 있으면 `db.close()` 하고 null 로 리셋.
   - `fun requireDatabase(): BeCalmDatabase` — holder null 이면 IllegalStateException.
   - 동시성: `kotlinx.coroutines.sync.Mutex` 로 openFor/close 를 직렬화. 읽기(requireDatabase) 는 lock-free — `@Volatile` read.

4. **`AuthRepositoryImpl.kt`**
   - signIn 경로 (signInWithEmail / signInWithGoogle / refreshSession) 의 `onSuccess` 블록에서
     `userDatabaseProvider.openFor(session.userId)` 호출 (현재 `tokenProvider.primeCache()` 직후).
   - `invalidateSession()` 의 step 리스트에 `NamedStep("closeUserDatabase") { userDatabaseProvider.close() }` 를 `sessionStoreClear` **이전**에 추가 (worker cancel 직후, 서버 revoke 뒤).
   - `signOut()` (PIPA wipe) 의 `databaseClearAll` step 을 다음으로 대체:
     - `NamedStep("deleteUserDatabaseFile") { userDatabaseProvider.deleteFor(session.userId) }` — 파일명 계산 후 `context.deleteDatabase(name)`.
     - 그래도 "전체 wipe" 의도를 보존하려면 DataStore 파일(들)도 user-scoped 로 바뀐 뒤 해당 파일만 삭제.

5. **`core/di/DataStoreModule.kt` + `data/local/datastore/UserPrefsStore.kt` + `SyncCursorStore.kt`**
   - **옵션 A (선호)**: DataStore 파일은 전역 유지하되 key 에 `user_<hash>_` prefix 를 붙인다.
     - 장점: DataStore API 가 `Context` extension property 라 런타임 이름 변경이 까다로움. key prefix 가 리팩토링 비용 최소.
     - 구현: `UserPrefsStoreImpl`/`SyncCursorStoreImpl` 생성자에 `CurrentUserIdProvider` (신규 `@Singleton`) 를 주입.
       모든 key read/write 전에 `val prefix = "user_${sha256(currentUserIdProvider.require()).take(16)}_"` 로
       key 를 wrap. 로그인 전 read/write 시도는 IllegalStateException.
     - 전역 key (예: `terms_accepted`, `onboarding_completed` 의 "첫 실행 여부") 는 prefix 없이 유지 — AUTH-005 "글로벌 키는 초기화" 는
       **wipe 경로에서만** 명시적으로 처리.
   - **옵션 B**: `DataStore<Preferences>` 를 user-scoped provider 로 교체 (파일명 per-user).
     - `preferencesDataStore` delegate 는 Context 에 프로퍼티로 묶이는데, 런타임 이름 주입을 위해
       `PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("becalm_user_prefs_${hash}") })` 를 수동 호출.
     - `UserDatabaseProvider` 와 동일한 lifecycle (openFor/close) 에 맞춘 `UserPrefsStoreProvider` 추가.
     - 단점: 파일 수 폭증·migration 복잡도 증가. 옵션 A 권장.

6. **`BecalmApplication.kt`** (필요 시)
   - Application.onCreate 에서 `CoroutineScope(SupervisorJob()).launch { sessionStore.load()?.userId?.let { userDatabaseProvider.openFor(it) } }` 를 수행해
     **cold start 시 기 저장된 세션이 있으면 곧바로 해당 user DB 를 연다**. 이것이 AUTH-008 의
     "동일 계정으로 재로그인(=세션 유지 상태에서 앱 재실행) 시 데이터 복원" 경로.

### 5.3 Files to add

- `android/app/src/main/java/com/becalm/android/data/local/db/UserDatabaseProvider.kt` — §5.2.3
- `android/app/src/main/java/com/becalm/android/core/util/Hashing.kt` — `fun sha256Hex(input: String): String`
- `android/app/src/main/java/com/becalm/android/data/local/CurrentUserIdProvider.kt` — `@Singleton` wrapper around EncryptedTokenStore 의 userId (DataStore key namespacing 용)
- `android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/3.json` — 스키마 변경 없음이지만 새 파일명으로 열리는지 검증 필요. 기존 3.json 유지.
- `android/app/src/test/java/com/becalm/android/data/local/db/UserDatabaseProviderTest.kt`
- `android/app/src/androidTest/java/com/becalm/android/data/local/db/UserScopedDatabaseTest.kt` — 2개 가짜 user UUID 로 openFor → insert → close → 다른 user openFor → insert → 원 user 재 openFor 시 데이터 복원 검증.

### 5.4 Files to delete (dead code)

없음. 기존 `becalm.db` 사용 코드 경로는 전환되지만 dead code 는 아님.

### 5.5 Non-code changes

- **기존 사용자 1회성 migration**: MVP 전 alpha/beta 사용자가 이미 `becalm.db` 파일을 갖고 있을 수 있음.
  - `UserDatabaseProvider.openFor(userId)` 가 호출될 때 `context.getDatabasePath("becalm.db").exists()` 이고
    **그 파일이 이 user 가 최초로 로그인한 기록** 일 경우에만 `renameTo(databaseNameFor(userId))` 로 이동.
  - 판단 기준: DataStore `legacy_db_claimed` flag. 첫 openFor 에서 false 이면 rename + flag=true; 이후 false 로 돌아오면 rename 하지 않음. 타 user 가 같은 디바이스에서 로그인하면 이 경로를 건너뛰고 새 user-scoped 파일을 만든다 — 레거시 파일은 "최초 로그인 user" 의 것으로 귀속.
  - 이 경로는 alpha 단계에만 필요. Play Store production 은 신규 설치이므로 문제 없음.

- **스펙 반영**: `UserDatabaseProvider` 의 생성·해제 시점을 `docs/plans/` 에 따로 문서화할 필요는 없음 — 본 plan 자체가 referencable.

- **로깅**: `logger.i(TAG, "UserDatabase opened for user_hash=$prefix6")` 등 short hash 로깅만 허용 — full user_id 로그 금지 (PIPA).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "DATABASE_NAME\s*=\s*\"becalm\.db\"" android/app/src/main/java/ | wc -l` 이 0 이다.
- [ ] **Grep invariant**: `grep -rn "becalm\\.db" android/app/src/main/java/ | wc -l` 이 0 이다 (테스트 fixture 제외).
- [ ] **Unit test**: `UserDatabaseProviderTest — openFor twice with same userId returns same instance`.
- [ ] **Unit test**: `UserDatabaseProviderTest — openFor with different userId closes prior instance then opens new`.
- [ ] **Unit test**: `HashingTest — sha256Hex("known-input").take(16) == expectedHex`.
- [ ] **Integration test** (androidTest): `UserScopedDatabaseTest — 계정 A 로 insert → close → 계정 B 로 openFor → 계정 A 데이터 안 보임`.
- [ ] **Integration test**: `계정 A 재 openFor → 계정 A insert 데이터 복원`.
- [ ] **Manual (device)**: 계정 A 로그인 → commitment 1개 생성 → 로그아웃(invalidateSession) → 계정 B 로그인 → Today/Commitments 화면 empty 확인 → 로그아웃 → 계정 A 재 로그인 → commitment 복원.
- [ ] **Manual (device)**: PIPA wipe (Settings "로컬 데이터 전체 삭제") → `/data/data/.../databases/` 경로에 해당 user 파일 없음 확인.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공.

---

## 7. Out of Scope

- 다기기 동기화 (post-MVP Supabase 서버 상태 기반 rehydration).
- `UserPrefsStore` / `SyncCursorStore` key namespacing 중 "옵션 B per-file" 선택지 — 본 PR 은 옵션 A (prefix) 만 구현.
- 멀티 윈도우·멀티 프로세스 동시 로그인 — Android 표준 single-process 모델 가정.
- `persons_enrichment` 30일 retention sweep — 별도 PR.
- ForegroundService·Worker 의 user-scoped Room 재접근 — Worker 는 Hilt DAO 주입을 통해 동일 provider 를 공유하므로 자동 해결되지만, worker 실행 중 로그아웃 race 는 Worker 단에서 `requireDatabase()` IllegalStateException catch 로 graceful exit. 본 PR scope 안에서 해당 catch 를 추가하되, 별도 worker 재설계는 out of scope.

---

## 8. Dependencies

- **Blocked by**: 없음 — 독립 수행 가능. 다만 PR `repo/auth/gmail-oauth-provider` 와 파일 겹침 없음.
- **Blocks**:
  - `feat/ui/auth/google-signin-wiring` (AUTH-003) — 본 PR 머지 전 별도 branch 에서 진행 가능하지만, 로그인 플로우 테스트는 본 PR 이 먼저 들어가야 안정.
  - `refactor/domain/auth/signout-preserve-data` (AUTH-005) — AUTH-005 의 "파일은 유지" 가 본 PR 의 전제를 요구. **본 PR 이 선행**.
  - `feat/ui/onboarding/notifications-sentry` — 독립.
  - 모든 Room schema 후속 migration (`feat/db/commitment/due-at-hint-approximate`, `feat/db/commitment/edit-delete-dispute-supersede` 등) — 본 PR 은 schema 변경 없이 파일 naming 만 바꾸므로 선형 stack 에 영향 없음.
- **병렬 가능**: AUTH-003 wiring PR (ui 파일 겹침 없음).

---

## 9. Rollback plan

Revert 한 줄로는 **위험** — 구현 머지 후 사용자 device 에는 `becalm_<hash>.db` 파일들이 이미 생성된 상태.
Revert 시 앱은 다시 `becalm.db` 를 찾지만 파일이 없어 빈 DB 로 시작 → 기존 로컬 데이터 사실상 lost.

안전한 rollback 전략:
1. 긴급시엔 **forward migration** 으로 처리 — `becalm_<hash>.db` 를 `becalm.db` 로 rename 하는
   일회성 cold path 를 추가하는 follow-up PR.
2. 또는 production 배포 전 internal/alpha 단계에서만 rollback 가능.
3. schema 변경이 없으므로 Room version 차원의 문제는 발생하지 않음.

---

## Appendix — Session handoff notes

- **법규 블로커**. MVP 출시 전 반드시 머지. Play Store 승인 이후 발견 시 개인정보 유출 사고로 간주될 소지.
- `sha256(user_id).take(16)` 은 Supabase user_id (UUID v4) 의 엔트로피 128bit → SHA-256 → 64자 hex → 16자 자르기 = 64bit. user_id 자체를 파일명에 쓰지 않는 이유는 (1) 로컬 파일 이름에 PII 포함 방지, (2) 향후 custom auth provider 로 바뀌어도 hashing 계약 불변.
- Hilt 에서 `@UserSession`-scoped `@Subcomponent` 로 아예 scope 을 분리하는 방법도 있으나, **Hilt 는 런타임 scope 생성을 지원하지 않음** (컴파일 타임 defined subcomponent 만 허용). 따라서 `@Singleton` provider 내부에서 holder 를 교체하는 패턴이 현실적.
- DataStore option A (key prefix) 는 한 가지 함정 — `DataStore.edit { preferences -> preferences.clear() }` 호출이 **모든** user 의 key 를 날린다. PIPA wipe 경로에서는 그것이 맞지만 `invalidateSession` 에서는 금지. 구현자는 `UserPrefsStoreImpl.clearForCurrentUser()` 라는 새 메서드를 만들어 prefix 가 맞는 key 만 지우도록 한다.
- **Migration test helper 주의**: `MigrationTestHelper` 는 DB 파일명을 인자로 받으므로 기존 테스트 hardcode 된 `"becalm.db"` 를 모두 찾아 `databaseNameFor(TEST_USER_ID)` 로 교체해야 한다. `grep -rn "becalm\\.db" android/app/src/test android/app/src/androidTest` 로 확인.
- `clearAllTables()` 는 본 PR 이후에도 **테스트 cleanup** 에서 여전히 유용하므로 호출 자체를 제거하지는 않음 — prod 경로(AuthRepository signOut)에서만 `deleteDatabase(name)` 로 교체.
- `EncryptedTokenStore` 는 건드리지 않음 — 토큰은 per-device 하나의 session 만 유지하는 것이 현재 모델이며, AUTH-008 은 Room/DataStore 격리 범위에만 해당.
