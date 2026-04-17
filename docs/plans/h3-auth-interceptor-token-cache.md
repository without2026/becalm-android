# H3 — AuthInterceptor Token Cache

**Status:** PLAN ONLY. Awaiting CTO review. No code changes in this branch yet.
**Scope:** `fix/android-auth-interceptor-token-cache`
**Spec touched:** AUTH-006 (DefaultAuthTokenProvider), AUTH-007 (AuthInterceptor)
**Risk tier:** HIGH — every authenticated Railway request flows through this path.

---

## 1. Current state (as of 4055379)

### Call sites

- `android/app/src/main/java/com/becalm/android/data/remote/interceptor/AuthInterceptor.kt:59`
  ```kotlin
  val token = authTokenProvider.currentAccessToken().orEmpty()
  ```
  Runs on every OkHttp dispatcher thread before each Railway request.

- `android/app/src/main/java/com/becalm/android/data/remote/interceptor/AuthInterceptor.kt:79`
  ```kotlin
  val newToken = runBlocking { authTokenProvider.refresh() }
  ```
  Runs only on 401.

### Provider implementation

- `android/app/src/main/java/com/becalm/android/core/di/NetworkModule.kt:248-249`
  ```kotlin
  override fun currentAccessToken(): String? =
      runBlocking { sessionStore.load()?.accessToken }
  ```
- `android/app/src/main/java/com/becalm/android/core/di/NetworkModule.kt:258-263` — `refresh()` calls
  `sessionStore.load()` → `authClient.refresh(...)` → `sessionStore.save(result.session)` → returns
  the new access token.

### Storage layer

`SupabaseSessionStore.load()` is a `suspend` function that reads `EncryptedSharedPreferences` via
DataStore. Each call:

1. Acquires the DataStore mutex.
2. Performs AES decryption on the backing prefs file.
3. Deserializes the `SupabaseSession` Moshi JSON.

### Observed behaviour

- **Every** Railway request performs a `runBlocking { sessionStore.load() }` on the OkHttp
  dispatcher thread, even when the in-memory session has not changed since the previous call.
- Dispatcher threads serialize on the DataStore mutex. Under a burst of parallel requests
  (e.g. `UploadWorker` flushing + foreground refresh calling commitments + calendar paginated
  refresh), this forms a latent bottleneck.
- `runBlocking` keeps the dispatcher thread parked; OkHttp has to spin up more threads to
  absorb concurrent work, inflating thread count.

---

## 2. Problem statement

`currentAccessToken()` is **read-heavy** (N authenticated requests × per-session) and
**write-rare** (login, refresh, logout). Yet every read performs disk I/O + AES decryption
under a coroutine mutex.

The correct primitive is an in-memory cache that is (a) lock-free on the read path and
(b) updated at the three write points (`save`, `clear`, `refresh`).

---

## 3. Proposed design

### 3.1 Primitive

```kotlin
private val cachedAccessToken = AtomicReference<String?>(null)
```

- `AtomicReference<String?>` — lock-free, memory-fence guaranteed publication (volatile-like).
- Null sentinel means "not loaded yet" **and** "no session". We disambiguate via
  [SupabaseSessionStore.load] on the slow path (see 3.2).

### 3.2 Read path

```kotlin
override fun currentAccessToken(): String? {
    cachedAccessToken.get()?.let { return it }
    // Cold start / post-process-death: fall back to the original synchronous read
    // so the interceptor can still attach Bearer on the very first request.
    val loaded = runBlocking { sessionStore.load()?.accessToken }
    loaded?.let { cachedAccessToken.compareAndSet(null, it) }
    return loaded
}
```

- Hot path: single atomic read. No mutex, no disk I/O, no `runBlocking`.
- Cold path: identical to current behaviour — preserves AUTH-007 semantics on first call.
- `compareAndSet(null, loaded)` avoids clobbering a token that a concurrent `refresh()` may
  have just published.

### 3.3 Write points — Observer pattern (decided)

**Current write sites** (verified via grep 2026-04-17):

- `sessionStore.save` — 4 call sites
  - `SupabaseAuthClient.kt:159` / `:171` / `:195`
  - `NetworkModule.kt:261` (DefaultAuthTokenProvider.refresh)
- `sessionStore.clear` — 2 call sites
  - `AuthRepository.kt:209` / `:260` (signOut wipe)

**Decision: Observer (Flow subscription).** Funnel pattern was considered and rejected
because (1) it introduces a Hilt circular dependency (`SupabaseAuthClient` injects
`SupabaseSessionStore`; funneling would require `SupabaseAuthClient` to inject
`AuthTokenProvider` while `DefaultAuthTokenProvider` already injects `SupabaseAuthClient`);
(2) it cannot compile-enforce "no direct writes" since `SupabaseSessionStore.save/clear` stay
public; (3) it bleeds Network-layer dependency upward into Storage callers, violating the
current one-way `Network → Storage` directionality.

**Contract extension on `SupabaseSessionStore`:**

```kotlin
public interface SupabaseSessionStore {
    public suspend fun save(session: SupabaseSession)
    public suspend fun load(): SupabaseSession?
    public suspend fun clear()
    public fun observe(): Flow<SupabaseSession?>   // NEW
}
```

**Implementation (`EncryptedTokenStore`):** back the store with a `MutableStateFlow<SupabaseSession?>`
seeded by the first `load()` (lazy initial read on `observe()` subscription). `save()` and
`clear()` both `emit` after the encrypted write completes so subscribers never see a value
that isn't persisted.

**Provider wiring:**

```kotlin
@Singleton
public class DefaultAuthTokenProvider @Inject constructor(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthTokenProvider {

    private val cachedAccessToken = AtomicReference<String?>(null)
    private val observerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        observerScope.launch {
            sessionStore.observe()
                .catch { e -> Log.e(TAG, "session observer died — cache will stale", e) }
                .collect { session -> cachedAccessToken.set(session?.accessToken) }
        }
    }
    // ...
}
```

Key invariants:
- `.catch { }` MUST be present. A dead collector silently stales the cache; the log line is
  the only detection surface (no thrown exception reaches callers).
- `observerScope` uses `SupervisorJob` so one child failure doesn't tear down the scope.
- Dispatcher is injected (not `Dispatchers.IO` hard-coded) for test substitution.

### 3.4 Refresh-and-retry on 401 (AUTH-007) stays byte-identical

`refresh()` is still the only path that mutates the token during a request cycle. The
interceptor's sequence is unchanged: receive 401 → buffer body → call `refresh()` →
retry with new token. The cache update happens via the Observer (§3.3) after
`sessionStore.save(result.session)` completes.

### 3.5 Refresh coalescing via Mutex (in-scope)

**Problem.** Supabase rotates refresh tokens — each RT is single-use. If N workers see 401
simultaneously and call `refresh()` in parallel with the same RT_v1, only the first wins;
the rest get `null` back and propagate the 401 to callers, even though a valid new session
was persisted.

**Solution.** Guard `refresh()` with a `kotlinx.coroutines.sync.Mutex` using the
double-checked pattern:

```kotlin
private val refreshMutex = Mutex()

override suspend fun refresh(): String? = refreshMutex.withLock {
    val current = sessionStore.load() ?: return@withLock null

    // Double-check: did another coroutine refresh while I was waiting for the lock?
    val cached = cachedAccessToken.get()
    if (cached != null && cached != tokenThatCausedMy401(current)) {
        return@withLock cached
    }

    val result = authClient.refresh(current.refreshToken).getOrNull() ?: return@withLock null
    sessionStore.save(result.session)   // observer updates cache
    result.session.accessToken
}
```

Note on `tokenThatCausedMy401`: we can't easily pass the old token through the interceptor's
`runBlocking { refresh() }` call without changing `AuthTokenProvider.refresh()`'s signature.
Two options:

- **(i) Compare against a captured "last refresh completed at" timestamp.** If another
  refresh finished after this caller observed their 401 (i.e., after `chain.proceed` returned),
  return the cached token. Requires passing a timestamp through the interceptor call —
  signature change.
- **(ii) Compare against the RT in `current`.** If the RT in `sessionStore.load()` is
  different from what we expected, someone refreshed. But interceptor doesn't know the
  "expected" RT either.
- **(iii) Keep it simple: if `cachedAccessToken` != the access token on the retry request,
  skip the Supabase call and return the cache.** The interceptor passes the old token to
  `refresh()` as a parameter.

**Recommended:** (iii). Minimal signature change (`refresh(previousAccessToken: String)`),
and the check is precise: "is the cache strictly newer than what caused the 401?"

**Mutex trade-off note.** Since `runBlocking { refresh() }` runs on the OkHttp dispatcher
thread, the mutex `suspend` does park the coroutine but `runBlocking` keeps the OS thread
blocked. During a refresh burst this means OkHttp's thread pool may grow to absorb pending
work. Acceptable — refresh is rare (cache hit covers steady state).

### 3.6 Cold-start read path

The init-block observer subscribes asynchronously. The very first `currentAccessToken()` call
after process start may fire **before** the observer has emitted its first value. The hot-path
`cachedAccessToken.get()` returns null → falls through to the cold-path `runBlocking { sessionStore.load() }`
→ `compareAndSet(null, loaded)` seeds the cache. Once the observer catches up, its emission
is identical to what was seeded, so the `set()` is a no-op race.

---

## 4. Concurrency scenarios

### S1. Two workers fire parallel 401s (with mutex §3.5)

- Worker A and Worker B both see 401 simultaneously, both call `runBlocking { refresh(oldToken) }`.
- A acquires `refreshMutex`, B suspends waiting.
- A loads current session, calls `authClient.refresh(RT_v1)` → Supabase issues AT_v2 / RT_v2,
  `sessionStore.save()` triggers observer → cache = AT_v2. A releases mutex, returns AT_v2.
- B acquires mutex. Double-check: `cachedAccessToken.get() = AT_v2`, which differs from
  `oldToken` (AT_v1 that caused B's 401) → return AT_v2 without a Supabase call.
- Both workers retry with AT_v2 and succeed. **Supabase refresh calls: 1 (was N).**

### S2. Logout during in-flight request

- User taps Logout → `AuthRepository.signOut()` → `sessionStore.clear()`.
- Cache observer (via `.observe()` Flow) emits `null` → `cachedAccessToken.set(null)`.
- An in-flight request that already attached the pre-logout Bearer completes. Server returns
  200 (token still valid server-side for its TTL) or 401 (revoked). Either way behaviour is
  identical to current: we don't retry with the (now-null) refreshed token because `refresh()`
  would fail (no session).

### S3. Cold start from process death

- Hot-path read returns null → fallback to `runBlocking { sessionStore.load() }`.
- If a concurrent coroutine is also doing the fallback, `compareAndSet(null, ...)` means the
  first to finish wins; the second's `set` is a no-op. Both return the same loaded token.

### S4. Token mutation via `refresh()` while a request is being built

- Thread A: reads cache, attaches Bearer $TOKEN_v1.
- Thread B: 401 on a different request, calls `refresh()`, cache now has $TOKEN_v2.
- Thread A's request hits server with $TOKEN_v1 — may still be valid (rotation overlap) or may
  401. If 401, Thread A's own refresh-and-retry path fires; `refresh()` sees the freshly saved
  v2 session and immediately returns the v2 token. No user-visible regression.

### S5. Observer collector dies

- `sessionStore.observe()` emits an exception (e.g., `IllegalStateException` from DataStore
  corruption).
- `.catch { Log.e(TAG, "session observer died — cache will stale", e) }` fires.
- Cache freezes at its last value. Subsequent logins/logouts silently fail to update cache.
- Detection: Logcat warning. No thrown exception surfaces to UI.
- Mitigation (future, out of scope): restart the collector via retry operator, or surface a
  metric to Firebase Crashlytics. For now we rely on the log.

---

## 5. AUTH-007 impact checklist

- [ ] Host guard (L54): unchanged.
- [ ] `currentAccessToken()` contract: still synchronous, still returns `String?`. Caller code
      is byte-identical.
- [ ] `refresh()` signature: extended to `refresh(previousAccessToken: String): String?`
      (§3.5 (iii)). Interceptor L79 call site updated; no other callers.
- [ ] `refresh()` return contract: still `String?` on success, `null` on failure. Caller
      branching byte-identical.
- [ ] 401 buffer-and-rebuild (R1-01): unchanged.
- [ ] `runBlocking` on 401 path (L79): unchanged.
- [ ] Single-retry guarantee: unchanged.
- [ ] IOException propagation: unchanged.
- [ ] `SupabaseSessionStore` interface: extended with `observe(): Flow<SupabaseSession?>`.
      Existing `save`/`load`/`clear` contracts byte-identical.

---

## 6. Test plan

**New unit tests** (`DefaultAuthTokenProviderTest`):

- `currentAccessToken returns null when no session and cache empty`
- `currentAccessToken seeds cache via cold-path load`
- `currentAccessToken returns cached value on hot path without touching store`
- `observer updates cache when store emits new session`
- `observer clears cache when store emits null`
- `observer death is caught and logged, cache freezes` (verify no uncaught exception)
- `refresh success persists new session and observer propagates to cache`
- `refresh failure preserves prior cache value`
- `concurrent currentAccessToken calls from cold state all return same token` (2 threads,
  verify `compareAndSet` race is benign)

**Refresh coalescing tests** (new suite `RefreshCoalescingTest`):

- `N parallel refresh calls result in exactly 1 SupabaseAuthClient.refresh invocation`
- `second caller receives the freshly cached token without a network call`
- `all parallel callers see success when first refresh succeeds`
- `second caller retries its own refresh when first refresh fails`

**`SupabaseSessionStore` contract tests:**

- `observe emits null on first subscription when no session persisted`
- `observe emits saved session after save(...)`
- `observe emits null after clear(...)`
- `observe is a hot flow — late subscribers see the latest value`

**Instrumentation smoke** (unchanged flow, verifies no regression):

- Login → fire 10 parallel commitment reads → assert only 1 DataStore read via spy on
  `sessionStore.load`.
- Force 401 burst (mock server, 5 concurrent requests) → assert exactly 1 Supabase refresh
  call, all 5 requests succeed on retry.

No new tests for AuthInterceptor itself — the interceptor is unchanged apart from passing
the old token into `refresh(previousAccessToken)`.

---

## 7. CTO decisions (2026-04-17)

1. **`refresh()` signature change** — **APPROVED.**
   `AuthTokenProvider.refresh()` → `AuthTokenProvider.refresh(previousAccessToken: String): String?`.
   `AuthInterceptor.kt:79` passes the token it attached at L59.
2. **Rollout gate** — **DEFERRED.** Revisit at merge time. Default if not revisited:
   direct merge to `feat/becalm-mvp`, no runtime gate.
3. **Cache hit/miss telemetry** — **DEFERRED.** Not blocking implementation. Add post-merge
   only if diagnostic data is needed.

---

## 8. Out of scope

- Deduplicating the dual `sessionStore.save` between `SupabaseAuthClient.kt:195` and
  `NetworkModule.kt:261`. Pre-existing tech debt. Observer pattern tolerates both call sites.
- Replacing `runBlocking` on the cold path (requires re-architecting the
  `AuthTokenProvider` interface to be suspendable; large blast radius).
- Auto-recovering from observer death (§S5). Relies on Logcat warning + Crashlytics for now.

---

## 9. Implementation sequence (post-approval)

1. Extend `SupabaseSessionStore` interface with `observe(): Flow<SupabaseSession?>`.
2. Implement `observe()` in `EncryptedTokenStore` via `MutableStateFlow` seeded lazily on
   first subscription.
3. Add `cachedAccessToken: AtomicReference<String?>` + `refreshMutex: Mutex` +
   `observerScope: CoroutineScope(SupervisorJob())` to `DefaultAuthTokenProvider`.
4. Wire init-block observer with `.catch { Log.e(...) }`.
5. Rewrite `currentAccessToken()` with hot-path + cold-path fallback.
6. Change `AuthTokenProvider.refresh()` signature to `refresh(previousAccessToken: String): String?`.
   Implement double-checked mutex pattern in `DefaultAuthTokenProvider.refresh()`.
7. Update `AuthInterceptor.kt:79` to pass `token` (the value attached at L59) into `refresh(...)`.
8. Add unit tests listed in §6.
9. Run `./gradlew :app:testDebugUnitTest` + `:app:lintDebug` (CTO on Windows — Claude cannot
   run gradle in WSL2).
10. Open PR against `feat/becalm-mvp`. Reference this plan doc.
