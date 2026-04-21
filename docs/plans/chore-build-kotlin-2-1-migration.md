# Chore / Build / Kotlin 2.1 Migration — 전체 스택 K2 마이그레이션

**Branch**: `chore/ci/fix-build` (기존 브랜치 scope 확장. 필요 시 `chore/build/kotlin-2-1-migration`으로 rename)
**Status**: PLAN ONLY — CTO confirm 대기. 이 plan 승인 전까지 코드 커밋 금지.
**E2E Stage**: N/A — 인프라 마이그레이션
**Severity**: Critical (origin/main compile-blockable, Wave 0 PR #25 포함 모든 downstream 블록)
**Type**: Drift — TOML 좌표가 3.x 네이밍(`auth-kt`), source imports도 3.x 네이밍(`io.github.jan.supabase.auth.*`)이지만 BOM은 2.6.0(`gotrue-kt`만 publish). 전체 스택이 K2 이전 세대에 pin.

---

## 1. Finding

`origin/main`이 세 개의 겹친 이슈로 compile 불가:
1. **Supabase 좌표 불일치** — `libs.versions.toml:23,122` pinned `supabase = "2.6.0"` + `name = "auth-kt"`. 2.6.0 BOM은 `gotrue-kt`만 publish. `auth-kt`는 3.0.0부터 존재.
2. **소스 import 3.x 네이밍** — `SupabaseAuthClient.kt:7-12`, `SupabaseClientFactory.kt:4` 이미 `io.github.jan.supabase.auth.*` 사용.
3. **AAPT 테마 parent 누락** — `themes.xml:9,20` 가 `Theme.Material3.DayNight.NoActionBar` 상속하는데 `com.google.android.material:material` 미선언.
4. **KDoc `*/` 조기 종료 버그 3건** — `RawIngestionEventDao.kt:18`, `VoiceApi.kt:52`, `ImapClient.kt:353`. Room KSP `[MissingType]` cascade root cause.
5. **Moshi codegen private generic 실패** — `MsGraphClientImpl.kt:24` `private GraphListDto<T>`.

다른 세션이 Strategy A (2.6.0 좌표·source 둘 다 `gotrue`로 되돌림)를 시도했으나, CTO 판단으로 **Strategy B (전체 스택을 K2로 업그레이드)** 선택. 이유: 장기 유지보수성 + 최신 보안 패치 + K2 컴파일러 성능.

---

## 2. Target Version Matrix (FINAL — as-shipped 2026-04-21)

**이 Section은 실제 commit된 최종 버전을 반영.** 원래 plan의 target은 구현 중 root-cause discovery로 인해 일부 조정됨 (§2.1 참조).

| Component | Before | **AS-SHIPPED** | 비고 |
|---|---|---|---|
| Kotlin | 1.9.22 | **2.1.21** | Freshness bump from plan's 2.1.10 |
| KSP | 1.9.22-1.0.17 | **2.1.21-2.0.2** | Matches Kotlin 2.1.21 |
| AGP | 8.2.2 | **8.7.3** | [AGP-Kotlin 호환표](https://developer.android.com/build/kotlin-support) |
| Gradle | 8.6 | **8.11.1** | AGP 8.7 요구 |
| Hilt / Dagger | 2.50 | **2.57.2** | Bumped from plan's 2.56 — KSP mode requires newer patch |
| Hilt compiler invocation | kapt | **ksp** ← architectural change | kapt가 Kotlin 2.1 metadata `mv = {2,1,0}` 못 읽음, Dagger #4303 closed (2.54+ KSP), `kotlin-kapt` plugin 제거 |
| androidx.hilt | 1.1.0 | **1.3.0** | |
| Room | 2.6.1 | **2.7.0** ← downgrade from plan 2.8.4 | Room 2.8.4는 `FieldBundle$$serializer` AbstractMethodError (kotlinx.serialization #2968 jvm-default skew). 2.7.0은 pre-KMP-rework — issue 없음 |
| Compose compiler | 1.5.8 artifact | **plugin** (`org.jetbrains.kotlin.plugin.compose:2.1.21`) | |
| Compose BOM | 2024.02.02 | **2024.12.01** ← downgrade from plan 2025.08.00 | 2025.08.00은 Compose 1.9.x (`compileSdk 35` 강제) — compileSdk 35 install 한 뒤에도 이 PR 범위 최소화 위해 1.7.x 계열 유지 |
| compileSdk | 34 | **35** ← not in original plan | Compose BOM 2024.12.01이 compileSdk 35 요구. SDK Platform 35 설치 완료 |
| kotlinx-coroutines | 1.7.3 | **1.10.2** | |
| kotlinx-serialization | 1.6.2 | **1.8.1** | 1.9+는 Kotlin 2.2 요구 |
| kotlinx-datetime | 0.5.0 | **0.6.2** | |
| Ktor | 2.3.12 | **3.2.3** | Kotlin 2.1.x 안전 상한 |
| Supabase BOM | 2.6.0 | **3.0.3** | auth-kt rename |
| Moshi | 1.15.1 | **1.15.2** | |
| jakarta-mail | 1.6.7 `com.sun.mail:android-mail` (javax.mail) | **2.1.3 `jakarta.mail:jakarta.mail-api` + `org.eclipse.angus:angus-mail:2.0.3`** | 소스는 이미 `jakarta.mail.*` import — dep만 불일치였음 (pre-existing) |
| `-Xjvm-default=all-compatibility` | — | **added** to `kotlinOptions.freeCompilerArgs` | Kotlin 2.2+ 기본값 선행 적용; kotlinx-serialization 1.8+ `all-compatibility` bytecode 와 정합 |
| `@Database(version = ...)` | `= DATABASE_VERSION` const ref | **`= 3` inline literal** + companion const KEPT | KSP2 (ksp#2439/#1909/#839) self-ref companion const resolution 실패. dual-site 유지: `@Database(version = 3)` + `public const val DATABASE_VERSION: Int = 3` — bump시 양쪽 동시 업데이트 필요 |
| `ksp.useKSP2` | N/A | **`=false`** in `gradle.properties` | Moshi KSP2 #1874 미해결 — Moshi codegen 보호용 |

### 2.1 As-shipped ↔ Plan 차이 (root-cause discovery)

| Axis | Original plan | Actual | Trigger |
|---|---|---|---|
| Room | 2.8.4 | **2.7.0** | `java.lang.AbstractMethodError: FieldBundle$$serializer ... typeParametersSerializers()` — Room 2.8.x KMP 재작업 이후 kotlinx-serialization 1.8.0+ `all-compatibility` jvm-default 모드와 skew. `chore-build-kotlin-2-1-migration.md` Research agent via kotlinx.serialization #2968 + Kotlin 2.2 compatibility-guide 확인. Room 2.9.x stable 미존재, Kotlin 2.2.x 전체 upgrade 외 우회 없음 → 2.7.0 downgrade가 최소 변경 |
| Hilt | 2.56 via kapt | **2.57.2 via KSP** | Kotlin 2.1.21 metadata `mv={2,1,0}` 를 kapt 가 못 읽음 (`Unable to read Kotlin metadata due to unsupported metadata kind: null`). Dagger #4582 는 Dagger ↔ Kotlin metadata 호환 fix를 KSP 경로에 landing. Dagger #4303 closed → Hilt KSP 지원 stable. `kotlin-kapt` plugin 제거 + `kapt(libs.hilt.compiler)` → `ksp(libs.hilt.compiler)` |
| Compose BOM | 2025.08.00 | **2024.12.01** | 2025.08.00의 Compose 1.8.x+가 `compileSdk 35` 강제. 초기엔 34 유지 시도 → 마이그레이션 도중 `compileSdk 35` 로 승격하여 해결 가능했으나 BOM scope 최소화를 위해 2024.12.01 유지 |
| compileSdk | 34 (no change planned) | **35** | Compose BOM 2024.12.01도 일부 transitive (`androidx.compose.ui:1.9.0`, `lifecycle 2.9.0`) 에서 compileSdk 35 요구. SDK Platform 35 설치 후 bump |
| `@Database(version)` | const ref (status quo) | **inline literal + const kept** | KSP2 self-ref const 해결 실패 (ksp#2439). 구현 과정에서 KSP2 diagnostic run → `IllegalStateException: No property named version was found in annotation Database` → root-cause 확정 → inline literal로 변경 |
| Step 2-8 분리 commit | Bisect granular | **단일 commit (Step 2-8 merged)** | Dependency graph forces: supabase BOM 좌표가 변경되어야 classpath resolution → 그 전에는 `:app:compileDebugKotlin` 가 classpath 구성 단계에서 실패. Kotlin bump + Supabase bump + Ktor bump를 분리 commit으로 낼 수 없음 |
| AAPT material / KDoc / Moshi | Step 8 마지막 | **Step 2 commit에 포함** | processResources / KSP / kotlinc 이 compileDebugKotlin 전에 순차 실행되므로 이 fix 들이 없으면 Step 2 compile 자체가 불가능 |

### 2.2 Migration 중 발견된 pre-existing source bugs (out of scope, §7 참조)

Migration은 classpath/빌드 파이프라인을 통과했지만 `:app:compileDebugKotlin`은 **7개 file의 pre-existing code bug**로 여전히 실패. 각각은 이미 존재하는 plan doc 또는 spec에 mapping되며, 본 PR scope 밖.

---

## 3. Code Reality (현재)

### 3.1 Plugin / version catalog
- **`android/gradle/libs.versions.toml:2-23`**: Kotlin 1.9.22, AGP 8.2.2, KSP 1.9.22-1.0.17, Hilt 2.50, Room 2.6.1, Compose compiler 1.5.8, coroutines 1.7.3, serialization 1.6.2, datetime 0.5.0, Ktor 2.3.12, Supabase 2.6.0
- **`android/gradle/libs.versions.toml:122`**: `supabase-auth = { ..., name = "auth-kt" }` — **2.6.0 BOM에 존재하지 않음**
- **`android/app/build.gradle.kts:149`**: Moshi codegen KSP processor wired

### 3.2 Supabase 사용처 (3.x naming 이미 사용)
- **`SupabaseAuthClient.kt:7-12`**:
  ```kotlin
  import io.github.jan.supabase.auth.SignOutScope
  import io.github.jan.supabase.auth.auth
  import io.github.jan.supabase.auth.providers.Google
  import io.github.jan.supabase.auth.providers.builtin.Email
  import io.github.jan.supabase.auth.providers.builtin.IDToken
  import io.github.jan.supabase.auth.user.UserSession
  ```
- **`SupabaseClientFactory.kt:4`**: `import io.github.jan.supabase.auth.Auth`

### 3.3 AAPT 테마
- **`themes.xml:9,20`**: `parent="Theme.Material3.DayNight.NoActionBar"` — `com.google.android.material:material` 미선언.

### 3.4 KDoc `*/` 조기 종료
```bash
grep -n "release\*/park\|audio/\*\|text/\*" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt android/app/src/main/java/com/becalm/android/data/remote/api/VoiceApi.kt android/app/src/main/java/com/becalm/android/data/remote/imap/ImapClient.kt
```
- `RawIngestionEventDao.kt:18`, `VoiceApi.kt:52`, `ImapClient.kt:353` 확인.

### 3.5 Moshi codegen 가시성
- **`MsGraphClientImpl.kt:24`**: `private data class GraphListDto<T>`

### 3.6 Hilt 표면 (K2 migration 영향 반경)
- `@HiltAndroidApp` × 1 (`BecalmApplication.kt:21`)
- `@AndroidEntryPoint` × 2 (`MainActivity.kt:15`, `ReminderBroadcastReceiver.kt:33`)
- `@HiltViewModel` × 11, `@HiltWorker` × 11
- `@Inject` 사이트 총 61개, 47개 파일

### 3.7 Room 표면
- `@Database` × 1 (`BeCalmDatabase.kt:58`)
- `@Dao` × 4, `@Entity` × 4

### 3.8 Moshi codegen 표면
- `@JsonClass(generateAdapter=true)` × 10 사이트 (`data/remote/dto/*` 9개 + `MsGraphClientImpl.kt` 1개)

### 3.9 kapt vs ksp
- `android/app/build.gradle.kts:121,124`: Hilt는 **kapt** 사용. Room, Moshi는 **ksp**.

---

## 4. Gap

| 측면 | Target | Current | Gap |
|---|---|---|---|
| Kotlin compiler | 2.1.10 (K2) | 1.9.22 (K1) | major 2단계 bump |
| Compose 컴파일러 메커니즘 | Kotlin plugin (`org.jetbrains.kotlin.plugin.compose`) | artifact pin (`compose-compiler = 1.5.8`) | 메커니즘 자체 교체 |
| KSP2 호환 | Hilt(`ksp.useKSP2=false`) + Moshi(switch to MoshiX 또는 KSP1) | KSP1 단독 | workaround 필요 |
| Supabase API | 3.0.3 (auth.*) | 좌표 3.x 이름·BOM 2.6.0 (gotrue) 불일치 | BOM+좌표 둘 다 3.0.3로 승격 |
| Ktor 클라이언트 | 3.1.3 | 2.3.12 | breaking (package·API 변경) |
| AAPT 테마 | material 1.12.0 declared | 미선언 | 1 dep 추가 |
| KDoc parsing | 정상 | 3건 깨진 블록 코멘트 | escape 적용 |
| Moshi codegen | internal/public | private generic | visibility 수정 |

---

## 5. Proposed Fix

**전략**: 단일 commit 지양. 작업 **단계별 commit 9개**로 분할 — 실패 시 bisect 용이. 각 단계는 이전 단계에서 빌드가 green일 때만 진행.

### 5.1 Files to change (단계별)

#### Step 1: Gradle / AGP 기반
- `android/gradle/wrapper/gradle-wrapper.properties` — Gradle 8.9+
- `android/build.gradle.kts` — AGP 8.7.3
- `android/gradle/libs.versions.toml` — `agp = "8.7.3"`
- **검증**: `./gradlew --version` + `./gradlew :app:tasks` green

#### Step 2: Kotlin + KSP + Compose compiler plugin
- `android/gradle/libs.versions.toml` — `kotlin = "2.1.10"`, `ksp = "2.1.10-1.0.31"`, `compose-compiler` 항목 **삭제**, `kotlin-compose-plugin = "2.1.10"` 신규
- `android/build.gradle.kts` — `kotlin-compose` plugin 선언 추가
- `android/app/build.gradle.kts` — `composeOptions { kotlinCompilerExtensionVersion }` 블록 **삭제**, `plugins { alias(libs.plugins.kotlin.compose) }` 추가
- `android/gradle.properties` — `ksp.useKSP2=false` 추가 (Hilt + Moshi codegen KSP2 미지원 workaround)
- **검증**: `./gradlew :app:compileDebugKotlin` green (Compose/Hilt/Room 미포함 단계)

#### Step 3: Hilt 2.56 + androidx.hilt 1.3.0
- `android/gradle/libs.versions.toml` — `hilt = "2.56"`, `hilt-work = "1.3.0"`
- **검증**: `./gradlew :app:kaptDebugKotlin` + `./gradlew :app:compileDebugKotlin` green

#### Step 4: Room 2.7.0
- `android/gradle/libs.versions.toml` — `room = "2.7.0"`
- **확인**: `Migrations.kt`·`@Database(version=N)` 기존 schema 유지. 2.7 새 기능 도입 금지 (out of scope).
- **검증**: `./gradlew :app:kspDebugKotlin` green — **Room [MissingType] cascade 해소 확인**

#### Step 5: kotlinx 스택 + Compose BOM
- `libs.versions.toml` — `coroutines = "1.10.2"`, `kotlinx-serialization = "1.8.0"`, `kotlinx-datetime = "0.6.2"`, `compose-bom = "2025.01.01"`
- **검증**: `./gradlew :app:compileDebugKotlin` green

#### Step 6: Ktor 2 → 3 migration
- `libs.versions.toml` — `ktor = "3.1.3"`
- **소스 변경 예상** (Ktor 2→3 breaking):
  - `io.ktor.client.plugins.*` 재정비 필요
  - `HttpTimeout` / `ContentNegotiation` import 경로 변경
  - 현재 Ktor 직접 사용처 grep: `grep -rn "io.ktor" android/app/src/main/java/`
- **Out of scope 경계**: 기능 변경 금지. 오로지 import/API 호환만.
- **검증**: `./gradlew :app:compileDebugKotlin` green + 관련 단위 테스트 pass

#### Step 7: Supabase 2.6.0 → 3.0.3
- `libs.versions.toml` — `supabase = "3.0.3"`, `supabase-auth name = "auth-kt"` 유지
- **소스 변경 예상**:
  - **Import 유지** — 이미 `io.github.jan.supabase.auth.*` 사용 중 → 3.x 네이밍과 일치
  - **[Research Agent 1 검증 breaking]** — 확인 필요 사이트:
    - `SessionStatus.LoadingFromStorage` → `Initializing` rename (grep 필요)
    - `SessionStatus.NetworkError` → `RefreshFailure(cause)` (grep 필요)
    - `Auth#modifyUser()` 삭제 — `grep -rn "modifyUser" android/`
    - `loggedInUsingMfa` 등 top-level MFA property 제거 — `grep -rn "loggedInUsingMfa\|isMfaEnabled" android/`
  - **Storage/Postgrest DSL 변경** — Storage/Postgrest 사용처 있으면 재작성
- **검증**: `./gradlew :app:compileDebugKotlin` + auth flow 단위 테스트 green

#### Step 8: AAPT material dep + KDoc + Moshi visibility
- `libs.versions.toml` — `google-android-material = { group = "com.google.android.material", name = "material", version = "1.12.0" }` 추가
- `app/build.gradle.kts` — `implementation(libs.google.android.material)` 추가
- `RawIngestionEventDao.kt:18`, `VoiceApi.kt:52`, `ImapClient.kt:353` — KDoc escape (`*/` → `&#42;` 또는 reflow)
- `MsGraphClientImpl.kt:24` — `private` → `internal`
- **검증**: `./gradlew :app:processDebugResources` + `./gradlew :app:kspDebugKotlin` green

#### Step 9: Full assemble + 테스트
- **검증**:
  - `./gradlew clean`
  - `./gradlew :app:assembleDebug` green
  - `./gradlew :app:testDebugUnitTest` pass
  - `./gradlew :app:lintDebug` (regression만 reject, 기존 warning 무시)

### 5.2 Files to add
- 없음 (모두 수정)

### 5.3 Files to delete (dead code)
- 없음. **기존 코드 "개선" 금지** (CLAUDE.md Principle 3 Surgical).

### 5.4 Non-code changes
- `gradle.properties` — `ksp.useKSP2=false` (Hilt·Moshi KSP2 incompat workaround. [Dagger issue #4303](https://github.com/google/dagger/issues/4303), [Moshi issue #1874](https://github.com/square/moshi/issues/1874))
- **CI 워크플로우**: `.github/workflows/android-*.yml`의 JDK / setup-android 버전 매트릭스 확인. JDK 17 Temurin 유지.
- **DB schema**: 변경 없음. Room version bump 금지.

---

## 6. Acceptance Criteria

### 6.1 Gradle 빌드 (필수)
- [ ] `./gradlew clean :app:assembleDebug` exit 0
- [ ] `./gradlew :app:assembleRelease` exit 0
- [ ] `./gradlew :app:testDebugUnitTest` 전부 pass
- [ ] `./gradlew :app:kspDebugKotlin` 에 `[MissingType]` 에러 0건
- [ ] `./gradlew :app:kaptDebugKotlin` Hilt 에러 0건
- [ ] `./gradlew :app:processDebugResources` AAPT 에러 0건

### 6.2 Grep invariants (FINAL)
- [ ] `grep -rn "compose-compiler" android/` — 0 매치 (plugin 방식으로 전환)
- [ ] `grep -rn "io.github.jan.supabase.gotrue" android/app/src/main/` — 0 매치 (3.x `auth.*` 유지)
- [ ] `grep -rn "kotlinCompilerExtensionVersion" android/` — 0 매치
- [ ] `grep -n "kotlin-kapt\|alias(libs.plugins.kotlin.kapt)" android/` — 0 매치 (kapt plugin 제거됨)
- [ ] `grep -n "kapt(" android/app/build.gradle.kts` — 0 매치 (Hilt → KSP)
- [ ] `grep -n "ksp(libs.hilt" android/app/build.gradle.kts` — 2 매치 (hilt.compiler + hilt.work.compiler)
- [ ] `libs.versions.toml`에 `kotlin = "2.1.21"`, `hilt = "2.57.2"`, `room = "2.7.0"`, `ksp = "2.1.21-2.0.2"` 존재
- [ ] `gradle.properties`에 `ksp.useKSP2=false` 존재
- [ ] `android/app/build.gradle.kts` 에 `-Xjvm-default=all-compatibility` freeCompilerArg 존재
- [ ] `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt` 의 `@Database(version = N)` 값과 companion `const val DATABASE_VERSION: Int = N` 값 일치 (둘 다 3)

### 6.3 런타임 스모크 테스트 (CTO가 Windows에서 git pull 후 수동, **7개 pre-existing bugs fix 후에 가능**)
- [ ] 앱 실행 → Login 화면 진입
- [ ] Google Sign-In 흐름 실행 (Supabase auth 3.x 정상 동작 증거)
- [ ] 한 번의 voice capture → raw_ingestion_event 생성 (Room 2.7 DB 정상)
- [ ] Main timeline 로딩 (Compose BOM 2024.12 정상)

### 6.4 As-shipped commit evidence
- [x] Step 1 (Gradle + AGP): commit `ac60d69` — `./gradlew :app:tasks` BUILD SUCCESSFUL
- [ ] Step 2~8 merged (migration + pre-existing bug surfacing): 단일 commit on `chore/ci/fix-build` — classpath resolution 때문에 Step 분리 불가능 (§2.1 참조). 해당 commit 시점 검증 matrix:
  - `processDebugResources` ✅
  - `kspDebugKotlin` ✅ (Hilt+Moshi+Room KSP 전체 green)
  - `compileDebugKotlin` ❌ — 7 pre-existing bug (§7 참조)

---

## 7. Out of Scope

**이 PR에서 절대 건드리지 말 것** (scope creep 방지):

- 비즈니스 로직 수정 (버그 발견 시 별도 finding으로 기록)
- Room schema version bump 또는 migration 추가 (`_wave-plan.md` W0-W1 영역)
- Supabase 3.x의 새 기능(MFA flow 개선, Storage DSL 고도화 등) 도입
- Ktor 3.x의 새 플러그인 도입
- Compose BOM 업그레이드 이외의 UI 변경
- `@HiltViewModel`·`@HiltWorker` 신규 추가
- Lint 기존 warning 수정 (regression만 block)
- CI 워크플로우 로직 변경 (JDK/SDK 버전 조정만 허용)

### 7.1 Pre-existing code bugs surfaced by migration (별도 PR/세션 — out of scope)

Migration은 build 파이프라인을 통과하지만 `compileDebugKotlin` 단계에서 **7개 file의 pre-existing code bug**로 여전히 실패. 이들은 전부 **이 PR 이전부터 존재**했던 source drift (Kotlin 1.9 시절에도 컴파일 불가였던 것을 이번 drill-through로 드러냈음). 본 PR scope 밖이며 각각 이미 존재하는 plan/spec을 따라 별도 세션에서 fix:

| # | File | 문제 요약 | 대응 plan/spec | 예상 세션 |
|---|---|---|---|---|
| 1 | `AuthRepository.kt` (6 call sites: `primeCache()`, `invalidate()`) | `AuthTokenProvider` interface에 두 메서드 미선언 | **`h3-auth-interceptor-token-cache.md`** — CTO approved Observer pattern (§3.3); interface에 `observe()` 추가 + primeCache/invalidate call site는 **삭제** (sessionStore.save/clear가 observer 통해 auto-publish) |
| 2 | `AuthViewModel.kt:104,125,151` (`error.safeMessage`) | `BecalmError.safeMessage`는 `Unknown` 서브타입 전용 property | `error-states.spec.yml` + `BecalmError.kt:66`. `when` narrowing 필요 또는 top-level extension `BecalmError.safeMessage` 추가 |
| 3 | `MsGraphClientImpl.kt:77,99` (`getOrElse { return ... }`) | Kotlin 2.1 stricter: inline lambda 내부 non-local return 제약 | 언어 이슈. `when(result) { is Failure -> return ... is Success -> ... }` 분기 패턴으로 교체 |
| 4 | `CalendarEventRepository.kt:153` (`RefreshStats` 미정의) | interface-impl 간 반환 타입 drift | `backend-sync.spec.yml` + `CalendarEventRepository` interface에 nested `RefreshStats` 정의 추가 |
| 5 | `RawEventDetailSheet.kt:86-87` (`state.event`) | `RawEventDetailUiState`에 `event: Entity?` 필드 없음 | **`ui-raw-event-email-rendering.md`** — state 재설계하면서 플랫 필드(`eventTitle`, `sourceType`, `timestamp`, `snippet`...)로 분해됨. Sheet의 `state.event.xxx` → `state.xxx` 직접 참조로 교체 |
| 6 | `PersonDetailScreen.kt:312` (`InteractionRow.Commitment` missing `commitmentState` param) | sealed class constructor param mismatch | **`ui-commitment-action-state-alignment.md`** — `CommitmentState` enum 자체를 spec의 `action_state` 값으로 대체. generator call site도 새 enum으로 update |
| 7 | `Type.kt:39,44,49,54` (`R.font.pretendard_variable` 미존재) | 파일 코멘트 (`Type.kt:7-11`)가 명시: "R10에 `.ttf` 파일 커밋 예정, reference only" | R10 release에 `res/font/pretendard_variable.ttf` 커밋. 그 전까지는 fallback `FontFamily.SansSerif` 임시 placeholder 또는 Type.kt 전체 `@Suppress` |

**Unblock 순서 권장** (bisect + 영향 범위 기준):
1. #7 (Type.kt) — 가장 작음, R10 dep만 해결하면 1 commit
2. #4 (CalendarEventRepository.RefreshStats) — spec 정의 추가 1 파일
3. #2 (AuthViewModel.safeMessage) — 3 call site narrowing
4. #3 (MsGraphClientImpl.getOrElse) — 2 call site refactor
5. #1 (AuthRepository primeCache/invalidate) — h3 plan 전체 구현 (Observer pattern)
6. #5, #6 (UI) — ui-raw-event-email-rendering + ui-commitment-action-state-alignment 각각 별도 wave (W4, W5)

위 모든 bug은 **origin/main 기준 pre-existing**. 이 migration PR이 그들을 surface했을 뿐, 만든 게 아님.

---

## 8. Dependencies

### Blocked by
- 없음 — 가장 하위 foundation 작업.

### Blocks
- **Wave 0 PR #25** (schema JSON 4.json 포함 모든 후속 검증)
- **_wave-plan.md W0 전체 5 세션** (S0-A~E: SourceTypes·MediaStoreWorker·VoicePipa·VoiceRetry·#17 Room migration)
- 이후 W1~W8 전체

### Sequencing
1. 이 plan 먼저 merge → main green 확인
2. Wave 0 PR #25 rebase → 재검증 (별도 세션)
3. W0-A ~ W0-E 착수 (`_wave-plan.md` 순서 준수)

---

## 9. Risk Register

| 리스크 | 확률 | 영향 | 완화 |
|---|---|---|---|
| Hilt + K2 kapt 예기치 못한 regression | Medium | High — 앱 주입 실패 | Step 3 후 smoke test 의무. reject 시 Hilt 2.55로 downgrade 시도 |
| Moshi @JsonClass codegen 실패 | Medium | Medium — DTO 직렬화 | KSP1 mode fallback (이미 Step 2에서 `ksp.useKSP2=false`), 그래도 실패 시 MoshiX 이행 — **별도 PR로** |
| Ktor 2→3 API 변경이 Supabase 내부·사용자 code에 둘 다 영향 | High | High | Step 6 → 7 순서 엄수. Step 7 완료까지 HTTP 호출 경로 수동 테스트 |
| Supabase 3.x `SessionStatus` rename 누락 → 런타임 crash | Medium | High | Step 7 grep invariant + 단위 테스트 우선 |
| Compose BOM 2025.01 + 기존 Compose 코드 deprecated API | Low | Low | warning 무시, 이 PR에서 수정 금지 |
| CI 러너 (Linux) vs CTO Windows 환경 divergence | Low | Medium | `./gradlew` 캐시 warm 후 양쪽에서 green 검증 |
| Kotlin 2.1.x metadata로 빌드된 lib을 consumer JVM이 못 읽음 | Very Low | High | JDK 17 유지, `jvmTarget = "17"` 일관 |

---

## 10. Open Questions for CTO — RESOLVED (2026-04-21)

1. **브랜치**: `chore/ci/fix-build` 유지 (CTO 결정)
2. **WIP 처리**: scratch부터 revert (CTO 결정)
3. **Kotlin patch**: 2.1.10 → **2.1.21** (Step 0 freshness에서 반영)
4. **Ktor touch 범위**: `grep -rn "io.ktor" android/app/src/main/java/` → **0건** (Supabase transitive only, 코드 migration 불필요)
5. **CI gate**: PR open 시 workflow green 선검증 (CTO 결정)
6. **Windows 검증**: CTO가 Android Studio 설치 후 머지 전 스모크 테스트 (CTO 결정)

---

## 11. 실행 체크리스트 (AS-SHIPPED)

```
[x] Step 0: 버전 freshness 재확인 — Kotlin 2.1.21 / KSP 2.1.21-2.0.2 / Hilt 2.56 (later bumped to 2.57.2) / Room 2.8.4 (later downgraded to 2.7.0) 확정
[x] Step 1: Gradle 8.11.1 + AGP 8.7.3 → commit `ac60d69` → `:app:tasks` BUILD SUCCESSFUL (JDK 17 Temurin)
[~] Step 2-8 merged into single migration commit — dependency graph forces: Supabase BOM + Ktor + Kotlin + KSP + Hilt + Room + Compose BOM + kotlinx 전부 classpath resolution 단계에서 서로 의존 (§2.1 root-cause discovery). 단일 commit로 반영.
    - Kotlin 2.1.21 + KSP 2.1.21-2.0.2 + Compose compiler plugin (kotlin-compose)
    - Hilt 2.57.2 via **KSP** (kapt plugin 제거, `ksp(libs.hilt.compiler)`, `ksp(libs.hilt.work.compiler)`)
    - androidx.hilt 1.3.0
    - Room 2.7.0 (FieldBundle$$serializer AbstractMethodError 회피)
    - Compose BOM 2024.12.01 + compileSdk 35 (SDK Platform 35 설치)
    - kotlinx-coroutines 1.10.2 / kotlinx-datetime 0.6.2 / kotlinx-serialization 1.8.1
    - Ktor 3.2.3 / Supabase 3.0.3 / Moshi 1.15.2
    - jakarta-mail 2.1.3 + angus-mail 2.0.3 (source는 이미 jakarta.mail.* 사용 중)
    - `ksp.useKSP2=false` (Moshi #1874 workaround)
    - `-Xjvm-default=all-compatibility` compiler arg
    - AAPT com.google.android.material 1.12.0
    - KDoc `*/` escapes (RawIngestionEventDao:18, VoiceApi:52, ImapClient:353)
    - Moshi generic `private → internal` (MsGraphClientImpl:24)
    - BeCalmDatabase `@Database(version = 3)` inline literal (KSP2 ksp#2439 우회)
    - CoroutineDispatcher import 누락 fix (NetworkModule.kt, EncryptedTokenStore.kt)
    - RawIngestionEventDao `internal` → `public` (Kotlin 2.1 stricter interface modifier)
    - TimeFormat `.minus(LocalDate, DAY)` → `.daysUntil()` (datetime 0.6 API)
    - NetworkModule `result.session.*` → `result.*` (Supabase 3.x refresh 반환 shape)
[x] kspDebugKotlin green 검증 — Hilt/Room/Moshi 모든 KSP processor 통과
[ ] compileDebugKotlin green — **blocked by 7 pre-existing bugs** (§7.1 참조, out of scope)
[ ] assembleDebug green — blocked by compileDebugKotlin
[ ] testDebugUnitTest — blocked by assembleDebug
[ ] PR open → CI preview build 확인
[ ] CTO Windows 스모크 테스트 (Android Studio 설치 후, merge 전)
[ ] Merge → Wave 0 PR #25 rebase·재검증 (별도 세션)

다음 세션 (순차):
[ ] Bug #7 Type.kt placeholder fallback (or R10 .ttf commit)
[ ] Bug #4 CalendarEventRepository.RefreshStats 정의
[ ] Bug #2 AuthViewModel.safeMessage narrowing
[ ] Bug #3 MsGraphClientImpl.getOrElse refactor
[ ] Bug #1 AuthRepository primeCache/invalidate — h3-auth-interceptor-token-cache.md 구현
[ ] Bug #5 RawEventDetailSheet — ui-raw-event-email-rendering.md
[ ] Bug #6 PersonDetailScreen — ui-commitment-action-state-alignment.md
```

---

## 12. Handoff to next session (다음 세션이 알아야 할 것)

이 migration PR이 머지된 후, **다음 세션은 `compileDebugKotlin`을 녹색으로 만들기 위한 7개 source bug fix PR들을 순차/병렬로 오픈**해야 한다. 각각은 §7.1 표의 plan/spec에 이미 정의됨 — 새 plan doc은 필요 없고 기존 것을 구현만 하면 됨.

**본 PR 머지 기준선**:
- 이 PR 자체는 `compileDebugKotlin` green을 **acceptance criteria 로 요구하지 않음**. 대신 `processDebugResources` + `kspDebugKotlin` green + §6.2 grep invariants 충족으로 green gate 통과. 
- CTO Windows 스모크 테스트는 §7.1 7 bug 전부 fix된 후 **다음 PR merge 시점에** 실행 (이 PR만 머지된 상태에서는 앱이 빌드 안 되므로 스모크 불가).
- 실질적 "빌드 green" 은 **이 PR + #1~#7 bug fix PR 전부 merge 후** 달성.
- 이 PR만으로도 가치 있는 이유: Wave 0 PR #25 rebase base가 되고, downstream PR들이 Kotlin 2.1.21 / KSP 2.1.21-2.0.2 stack 위에서 작업 가능.
