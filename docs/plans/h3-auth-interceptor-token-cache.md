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

### 3.3 Write points (three, exhaustive)

1. **`refresh()`** — on success, `cachedAccessToken.set(result.session.accessToken)` **after**
   `sessionStore.save(...)` completes. On `null` return (refresh failed), cache is **not**
   invalidated; the stale token stays until explicit logout, matching current semantics.
2. **`SupabaseSessionStore.save(...)`** — called from login and from `refresh()`. We'll
   extend the provider (not the store) so the store contract is untouched.
3. **`SupabaseSessionStore.clear()`** — called from logout. Provider must observe and call
   `cachedAccessToken.set(null)`.

Options for (2)+(3):

- **(a) Observe `SupabaseSessionStore`'s `Flow<SupabaseSession?>`** — if the store already
  exposes one. Subscribe in `DefaultAuthTokenProvider`'s init block with a `SupervisorJob`
  scope. Self-healing if any code path writes to the store without going through the provider.
- **(b) Make `DefaultAuthTokenProvider` the write funnel** — every writer (AuthRepository,
  refresh) calls into the provider, which forwards to the store and updates the cache. Simpler
  concurrency model but requires auditing all `sessionStore.save/clear` call sites.

**Recommendation:** (a) if the store already exposes a `Flow`. Decide after reading
`SupabaseSessionStore.kt`.

### 3.4 Refresh-and-retry on 401 (AUTH-007) stays byte-identical

`refresh()` is still the only path that mutates the token during a request cycle. The
interceptor's sequence is unchanged: receive 401 → buffer body → call `refresh()` →
retry with new token. The cache update happens **inside** `refresh()` before it returns, so
the retry request gets the fresh token via the same mechanism as today.

---

## 4. Concurrency scenarios

### S1. Two workers fire parallel 401s

- Worker A and Worker B both see 401.
- Both call `runBlocking { refresh() }`.
- `refresh()` is **not** currently guarded by a mutex — both calls will hit
  `authClient.refresh(...)` in parallel with the same refresh token.
- Supabase typically rejects the second refresh (refresh tokens rotate) → B's refresh fails →
  B returns `bufferedResponse` (401) to the caller. A's retry succeeds.
- **With cache**: same outcome. B's failed refresh does **not** invalidate A's cache update,
  because we only `set` on success.
- **Open question:** should we serialize `refresh()` with a `Mutex`? Out of scope for H3
  (existing behaviour) but worth flagging.

### S2. Logout during in-flight request

- User taps Logout → `AuthRepository.signOut()` → `sessionStore.clear()`.
- Cache observer (option 3.3a) sets `cachedAccessToken` to null.
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

---

## 5. AUTH-007 impact checklist

- [ ] Host guard (L54): unchanged.
- [ ] `currentAccessToken()` contract: still synchronous, still returns `String?`. Caller code
      is byte-identical.
- [ ] `refresh()` contract: still `suspend`, still returns `String?` on success, `null` on
      failure. Caller code is byte-identical.
- [ ] 401 buffer-and-rebuild (R1-01): unchanged.
- [ ] `runBlocking` on 401 path (L79): unchanged.
- [ ] Single-retry guarantee: unchanged.
- [ ] IOException propagation: unchanged.

---

## 6. Test plan

**New unit tests** (`DefaultAuthTokenProviderTest`):

- `currentAccessToken returns null when no session and cache empty`
- `currentAccessToken loads from store on cold path`
- `currentAccessToken returns cached value on hot path without touching store`
- `refresh success updates cache`
- `refresh failure preserves prior cache value`
- `logout/clear resets cache to null`
- `concurrent currentAccessToken calls from cold state all return same token` (2 threads)

**Instrumentation smoke** (unchanged flow, verifies no regression):

- Login → fire 10 parallel commitment reads → assert only 1 DataStore read via spy.
- Force 401 (mock server) → assert single refresh call and retry with new token.

No new tests for AuthInterceptor itself — the interceptor is unchanged.

---

## 7. Open questions for CTO

1. **Should `refresh()` be mutex-guarded?** Current code allows parallel refreshes. H3 does
   not change this. Flag as M-tier tech debt?
2. **Observer vs funnel (3.3a vs 3.3b)?** Depends on whether `SupabaseSessionStore` already
   exposes a Flow. Pending a file read.
3. **Rollout gate.** This change affects 100% of authenticated traffic. Merge behind the
   existing `feat/becalm-mvp` staging deploy? Or behind a Firebase Remote Config kill-switch?
4. **Metrics.** Do we want to emit a `auth.token.cache_hit` / `miss` counter for the first
   week post-merge?

---

## 8. Out of scope

- Mutex-guarded refresh (tracked separately if CTO agrees in Q7.1).
- `SupabaseSessionStore` Flow API (if it doesn't already exist).
- Replacing `runBlocking` on the cold path (requires re-architecting the
  `AuthTokenProvider` interface to be suspendable; large blast radius).

---

## 9. Implementation sequence (post-approval)

1. Read `SupabaseSessionStore.kt` end-to-end; decide 3.3a vs 3.3b.
2. Add `cachedAccessToken: AtomicReference<String?>` to `DefaultAuthTokenProvider`.
3. Wire writes (observer or funnel).
4. Add unit tests listed in §6.
5. Run `./gradlew :app:testDebugUnitTest` (CTO on Windows — Claude cannot run gradle in WSL2).
6. Open PR against `feat/becalm-mvp`. Reference this plan doc.
