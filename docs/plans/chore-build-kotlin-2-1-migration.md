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

## 2. Target Version Matrix (Step 0 freshness 완료, 2026-04-21 기준)

**Kotlin 2.1.21 기준 최신 안정판** — Hilt 2.56은 2.1.10으로 빌드되었으나 Kotlin patch 레벨(2.1.10→2.1.21) binary 호환 기대. KSP 2.1.21-2.0.2가 매칭 릴리스. Room 2.8.4 stable (minSdk 28 ≥ 23 요구 통과).

| Component | Current | Target | 변경 근거 / confidence |
|---|---|---|---|
| Kotlin | 1.9.22 | **2.1.21** | [releases](https://kotlinlang.org/docs/releases.html) H |
| KSP | 1.9.22-1.0.17 | **2.1.21-2.0.2** | [KSP 릴리스](https://github.com/google/ksp/releases/tag/2.1.21-2.0.2) M — 버전 컨벤션 변경(1.0.x → 2.0.x) |
| AGP | 8.2.2 | **8.7.3** | [AGP-Kotlin 호환표](https://developer.android.com/build/kotlin-support) H |
| Gradle wrapper | 현재 값 확인 필요 | **8.11.1** | AGP 8.7 + Kotlin 2.1.21 |
| Hilt / Dagger | 2.50 | **2.56** | [Dagger 2.56](https://github.com/google/dagger/releases) H — 2.57+는 AGP 9 호환 확정 안 됨 |
| androidx.hilt | 1.1.0 | **1.3.0** | [androidx.hilt 1.3.0](https://developer.android.com/jetpack/androidx/releases/hilt) H |
| Room | 2.6.1 | **2.8.4** | [Room 2.8.4](https://developer.android.com/jetpack/androidx/releases/room) H — minSdk 28 ≥ 23 통과 |
| Compose compiler | 1.5.8 artifact | **plugin** (`org.jetbrains.kotlin.plugin.compose:2.1.21`) | [Compose Gradle plugin](https://developer.android.com/develop/ui/compose/compiler) H |
| Compose BOM | 2024.02.02 | **2025.08.00** | conservative 중간값 (2026.04.00 존재하나 Kotlin 2.1 호환 미검증) M |
| kotlinx-coroutines | 1.7.3 | **1.10.2** | [coroutines 1.10.0 = Kotlin 2.1.0](https://github.com/Kotlin/kotlinx.coroutines/releases) H |
| kotlinx-serialization | 1.6.2 | **1.8.1** | [serialization 1.8.1](https://github.com/Kotlin/kotlinx.serialization/releases) H — 1.9+는 Kotlin 2.2 요구 |
| kotlinx-datetime | 0.5.0 | **0.6.2** | 0.7.x breaking (dayOfMonth→day) → 이 PR out of scope |
| Ktor | 2.3.12 | **3.2.3** | [Ktor 3.2.x](https://github.com/ktorio/ktor/releases) M — 3.3+는 Kotlin 2.2 요구, 3.2.3이 2.1.x 안전 상한 |
| Supabase BOM | 2.6.0 | **3.0.3** | [Supabase-kt 3.0.3](https://github.com/supabase-community/supabase-kt/releases) — Ktor 3.0.2 known-pair, 신버전은 추후 별도 PR |
| Moshi | 1.15.1 | **1.15.2** | [Moshi 1.15.2](https://github.com/square/moshi/tags) H |
| supabase-auth 좌표 | `auth-kt` (broken at 2.6.0) | **`auth-kt`** (3.0.0부터 존재) | rename source 변경 불필요 — 이미 `io.github.jan.supabase.auth.*` 사용 중 |

### Step 0 완료 증거
- `grep -rn "io.ktor" android/app/src/main/java/` → **0건** — Ktor direct usage 없음. Supabase transitive만. **Step 6 코드 migration 없음**.
- `minSdk = 28` (app/build.gradle.kts:39) — Room 2.8.4 허용.
- Dagger #4303 (Hilt+KSP2) CLOSED, Hilt 2.56 KSP2 지원. 단 Moshi KSP2 (#1874)는 미해결 → `ksp.useKSP2=false` 유지 (Moshi 사유).

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

### 6.2 Grep invariants
- [ ] `grep -rn "compose-compiler" android/` — 0 매치 (plugin 방식으로 전환)
- [ ] `grep -rn "io.github.jan.supabase.gotrue" android/app/src/main/` — 0 매치 (3.x `auth.*` 유지)
- [ ] `grep -rn "kotlinCompilerExtensionVersion" android/` — 0 매치
- [ ] `libs.versions.toml`에 `kotlin = "2.1.10"`, `hilt = "2.56"`, `room = "2.7.0"` 존재
- [ ] `gradle.properties`에 `ksp.useKSP2=false` 존재

### 6.3 런타임 스모크 테스트 (CTO가 Windows에서 git pull 후 수동)
- [ ] 앱 실행 → Login 화면 진입
- [ ] Google Sign-In 흐름 실행 (Supabase auth 3.x 정상 동작 증거)
- [ ] 한 번의 voice capture → raw_ingestion_event 생성 (Room 2.7 DB 정상)
- [ ] Main timeline 로딩 (Compose BOM 2025.01 정상)

### 6.4 Bisect evidence
- [ ] 각 Step 1~9 별로 **별도 commit** 존재. Step N 커밋 시점에서 `./gradlew :app:compileDebugKotlin` green이었던 것을 commit body에 명시.

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

## 10. Open Questions for CTO (confirm 전 답변 필요)

1. **브랜치 이름**: `chore/ci/fix-build`을 계속 사용할지, `chore/build/kotlin-2-1-migration`로 rename할지?
2. **현재 stash 후 WIP (8 파일)**: Strategy A용이므로 대부분 revert 필요 (특히 gotrue 좌표·import). Step 8의 AAPT/KDoc/Moshi fix만 재활용. → `git checkout .` 후 새로 시작 vs 재활용 선택.
3. **Kotlin patch level**: 2.1.10 고정 vs 2.1.20으로 올림? (2.1.20은 Ktor 3.1.2가 명시적 지원. Hilt 2.56은 2.1.10에 빌드되었음.) → **권장: 2.1.10** (Hilt 빌드 타겟과 정확히 일치)
4. **Ktor migration 실제 touch 범위**: Supabase 내부에서만 Ktor 쓰는지, 우리 소스도 직접 쓰는지 선확인 필요. → Step 6 진입 전 `grep -rn "io.ktor" android/app/src/main/java/` 결과 플래그.
5. **CI gate 타이밍**: 이 PR이 main 머지되기 전에 `.github/workflows/android-*.yml`이 new stack으로 통과하는지 선검증? (PR preview build로 증거 확보)
6. **Windows 검증**: CTO가 Windows에서 Step 9 완료 후 `git pull` → `./gradlew.bat assembleDebug` 확인 타이밍 — PR 머지 전 vs 후?

---

## 11. 실행 체크리스트 (CTO confirm 후)

```
[ ] Step 0: 버전 freshness 재확인 (Kotlin 2.1 line 내 최신 patch 탐색)
[ ] Step 1: Gradle 8.9 + AGP 8.7.3 → commit → build green
[ ] Step 2: Kotlin 2.1.10 + KSP + Compose compiler plugin → commit → compile green
[ ] Step 3: Hilt 2.56 + androidx.hilt 1.3.0 → commit → kapt green
[ ] Step 4: Room 2.7.0 → commit → ksp green ([MissingType] 소멸 증거)
[ ] Step 5: kotlinx 스택 + Compose BOM → commit → compile green
[ ] Step 6: Ktor 3.1.3 → commit → compile green + 기존 테스트 pass
[ ] Step 7: Supabase 3.0.3 + API breaking 대응 → commit → compile + auth 테스트 green
[ ] Step 8: AAPT material + KDoc escape + Moshi visibility → commit → assembleDebug green
[ ] Step 9: clean full assemble + testDebugUnitTest + lintDebug → commit
[ ] PR open → CI 전 워크플로우 green
[ ] CTO Windows 스모크 테스트
[ ] Merge → Wave 0 PR #25 rebase·재검증 (다른 세션)
```
