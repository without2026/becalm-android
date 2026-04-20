# Big Tech-Grade Android/Kotlin Code Review Rubric

> Target reviewer: staff+ engineer at Google / Meta / Square-Block / Netflix / Uber.
> Each item is a pass/fail check verifiable from the diff alone.
> Opinionated defaults follow Google Android team + Now in Android reference + Square/Block engineering conventions.
> This rubric gates the becalm-android refactor. Any single failure blocks merge.

## A. Architecture & Layering

**A1.** Dependency direction is strictly `ui -> domain -> data`. No `:data` imports from `:ui` or `:feature`. Block on any upward/sideways import.
**A2.** UI layer never touches a data source directly. No `Retrofit`, `RoomDatabase`, `DataStore`, `SharedPreferences` inside `ui/`.
**A3.** Every data type has exactly one SSOT repository, even for single-source data. No DAO injection into ViewModels.
**A4.** DTOs do not cross layer boundaries. `@SerializedName`, `@Json`, `@Entity`, `@ColumnInfo` stay in `:data`.
**A5.** `ViewModel` used at screen scope only. Reusable composables take plain state-holder classes.
**A6.** A `:feature` module never depends on another `:feature` module. Shared logic lives in `:core:*` or `:domain`.

## B. Kotlin Idioms

**B1.** `val` unless mutation is required and documented.
**B2.** Public APIs expose `List`, `Set`, `Map` — never `MutableList`, `ArrayList`, `HashSet`.
**B3.** Finite state unions are `sealed interface`/`sealed class`, exhausted with `when` (no `else -> {}` swallow).
**B4.** `data class` for pure value objects only. No `var` property on a `data class`.
**B5.** No `!!` in production code. Use `requireNotNull { "..." }`, `checkNotNull`, `?: error(...)`.
**B6.** No wildcard imports (detekt `WildcardImport`).
**B7.** Backing `MutableStateFlow`/`MutableList` uses `_name` private + public non-mutable supertype.
**B8.** Scope functions chosen by intent (`apply`=builder, `also`=side effect, `let`=nullable transform, `run`/`with`=block compute). No nested `apply { apply { } }`.

## C. Coroutines & Flow

**C1.** Every class doing blocking work injects a `CoroutineDispatcher`. Hard-coded `Dispatchers.IO`/`Default` in business logic fails.
**C2.** Every `suspend` function is main-safe (wraps its own `withContext(ioDispatcher)`).
**C3.** `ViewModel` exposes `StateFlow`/`SharedFlow`, not `suspend fun` to the UI. Actions are fire-and-forget.
**C4.** UI state is `StateFlow<UiState>` started with `SharingStarted.WhileSubscribed(5_000)`. No `LiveData`, no raw `MutableState` exposed from VM.
**C5.** One-shot events use `Channel.receiveAsFlow()` or event-in-state, not `SharedFlow(replay>0)` as event bus.
**C6.** No `GlobalScope`. Long-lived work uses an injected `@ApplicationScope CoroutineScope` bound to `SupervisorJob`.
**C7.** `CancellationException` is never caught generically — must rethrow or catch narrower type.
**C8.** Long CPU loops call `ensureActive()` or use cooperative suspending APIs.

## D. Jetpack Compose

**D1.** State hoisted — `fun Screen(state: T, onEvent: (E) -> Unit)`. Stateful + stateless pair.
**D2.** UI state collected with `collectAsStateWithLifecycle()`, never `collectAsState()`.
**D3.** `remember`/`LaunchedEffect` keys are complete. `LaunchedEffect(Unit)` fails unless nothing external is captured.
**D4.** `DisposableEffect` has a complete `onDispose { }` (listener removed, observer detached).
**D5.** `derivedStateOf` only to throttle recomposition from frequently-changing state.
**D6.** `LazyColumn`/`LazyRow` items declare stable `key = { ... }`.
**D7.** Composable params are stable types — `ImmutableList` or `@Immutable`-annotated classes.
**D8.** Composables returning `Unit` are `PascalCase`; others `camelCase`.

## E. Dependency Injection (Hilt)

**E1.** Constructor injection everywhere possible. Field injection reserved for Android entry points.
**E2.** `@Binds` preferred over `@Provides` when binding an interface to a `@Inject`-constructible impl.
**E3.** No service-locator anti-pattern. No static `AppContainer`, no `getInstance()` on DI-eligible classes.
**E4.** Scopes match intended lifetime, no broader. Default unscoped.
**E5.** Tests use `@TestInstallIn` for multi-test fakes; `@BindValue` for single-test. `@UninstallModules` last resort.

## F. Persistence

**F1.** Every multi-table DAO mutation wrapped in `@Transaction`.
**F2.** DAO reads return `Flow<T>` (reactive) or `suspend fun` (one-shot). No blocking `List<T>` returns.
**F3.** Every schema change bumps DB version + ships migration (or `autoMigration`).
**F4.** Room entities stay inside `:data`. Repos map entities → domain models.
**F5.** User prefs use `DataStore`, not `SharedPreferences` (except `EncryptedSharedPreferences` for secrets).

## G. Background Work (WorkManager)

**G1.** Long-running background uses `CoroutineWorker`, not `Worker`.
**G2.** Every `WorkRequest` declares explicit `Constraints` (network, battery, storage).
**G3.** Workers are idempotent — `doWork()` can run twice safely.
**G4.** Retry: `BackoffPolicy.EXPONENTIAL` with sane initial delay, not linear default.
**G5.** Unique work declares `ExistingWorkPolicy`/`ExistingPeriodicWorkPolicy` explicitly.

## H. Networking

**H1.** Retrofit endpoints are `suspend fun` returning DTO or `Response<T>`. No `Call<T>` without justification.
**H2.** OkHttp has explicit `connectTimeout`, `readTimeout`, `writeTimeout`, `callTimeout`.
**H3.** Auth token injection via `Interceptor`, not per-endpoint `@Header`.
**H4.** Logging interceptor is `BuildConfig.DEBUG`-gated and never logs bodies in release.

## I. Error Handling

**I1.** Public repo/use-case APIs return a sealed `Result<T>`, not throw. Expected failures modeled as `Result.Error`.
**I2.** No `catch (e: Exception) { }` empty body or bare `Log.e` and continue. Either transform to `Result.Error`, rethrow, or record to crash reporter. Detekt `TooGenericExceptionCaught` on.
**I3.** Preconditions use `require`/`check`/`error` with actionable messages, not bare `throw IllegalStateException()`.

## J. Testing

**J1.** Every new ViewModel / UseCase / Repository has a unit test in the same module.
**J2.** Coroutine tests use `runTest { }` with injected `TestDispatcher`, not `runBlocking` + `Dispatchers.setMain`.
**J3.** `Flow`/`StateFlow` assertions use Turbine (`flow.test { awaitItem(); ... }`).
**J4.** Test doubles are hand-written fakes implementing the production interface. Mocks only for framework/third-party boundaries.
**J5.** `androidTest` reserved for what the JVM can't exercise (Room migrations, WorkManager, Compose UI).

## K. Code Hygiene

**K1.** File ≤ 400 LOC, function ≤ 50 LOC, cyclomatic complexity ≤ 15 (detekt `LongMethod`, `LargeClass`, `CyclomaticComplexMethod`).
**K2.** KDoc required on every `public`/`protected` type & member. `internal`/`private` only when non-obvious.
**K3.** No `TODO`/`FIXME` without a linked issue ID (`// TODO(#1234): ...`).
**K4.** No dead code added. Pre-existing dead code is reported, not silently deleted.
**K5.** Magic numbers are named `const val`. Detekt `MagicNumber` on; exceptions for `0, 1, -1, 2`.

## L. Security & Privacy

**L1.** Auth tokens stored via `EncryptedSharedPreferences` or keystore-wrapped `DataStore`. Plain `putString("token", ...)` fails.
**L2.** Crypto keys generated in `AndroidKeyStore` via `KeyGenParameterSpec`, never exported. No hard-coded `SecretKeySpec(byteArrayOf(...))`.
**L3.** Biometric-gated keys declare `setUserAuthenticationParameters(...)` with duration + `AUTH_BIOMETRIC_STRONG`.
**L4.** PII never in logs. `Log.d(..., user.email/token)` fails. Logging interceptor redacts `Authorization`, `Cookie`, `Set-Cookie`.

---

## Enforcement

- Detekt + ktlint run on every PR; any rule referenced above with a detekt backing is CI-blocking.
- Reviewer goes A → L in order on every diff. A single failing item blocks merge.
- Exceptions require an inline comment with the approving reviewer's handle.

---

## Sources

- [Android Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- [Android App Architecture](https://developer.android.com/topic/architecture)
- [Architecture Recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [Android Coroutines Best Practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Compose Lifecycle & Recomposition](https://developer.android.com/develop/ui/compose/lifecycle)
- [Compose Side-Effects](https://developer.android.com/develop/ui/compose/side-effects)
- [Hilt Android Guide](https://developer.android.com/training/dependency-injection/hilt-android)
- [Hilt Testing Guide](https://developer.android.com/training/dependency-injection/hilt-testing)
- [Room](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Now in Android](https://github.com/android/nowinandroid)
- [Turbine (Cash App)](https://github.com/cashapp/turbine)
- [Detekt Style Rules](https://detekt.dev/docs/rules/style)
- [Retrofit](https://square.github.io/retrofit/)
- [OkHttp](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-interceptor/)
