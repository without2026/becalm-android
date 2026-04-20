package com.becalm.android.core.di

import com.becalm.android.BuildConfig
import com.becalm.android.core.result.getOrNull
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.secure.EncryptedTokenStore
import com.becalm.android.data.remote.api.ApiFactory
import com.becalm.android.data.remote.api.HttpTimeouts
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.DefaultIdempotencyKeyProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.becalm.android.data.remote.network.AndroidNetworkMonitor
import com.becalm.android.data.remote.network.NetworkMonitor
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseAuthClientImpl
import com.becalm.android.data.remote.supabase.SupabaseConfig
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.remote.supabase.createSupabaseClient
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt module wiring the entire network stack for the BeCalm Android app.
 *
 * ## Structure
 * Hilt does not allow `@Binds` and `@Provides` in the same concrete class. The standard
 * workaround is the abstract-class + nested-object pattern used here:
 * - This abstract class holds `@Binds` declarations (zero-cost at runtime — they compile to
 *   direct assignments in the generated component).
 * - The nested [NetworkModuleProvides] companion object holds `@Provides` methods that require
 *   imperative construction logic.
 *
 * ## AUTH-006 wiring
 * [DefaultAuthTokenProvider] bridges [SupabaseSessionStore] (token storage) and
 * [SupabaseAuthClient] (token refresh) into the single [AuthTokenProvider] interface that
 * the OkHttp [com.becalm.android.data.remote.interceptor.AuthInterceptor] (SP-05) depends on.
 * This keeps the interceptor layer free of Supabase SDK knowledge.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class NetworkModule {

    /** Binds [SupabaseAuthClientImpl] (SP-04) as the singleton [SupabaseAuthClient]. */
    @Binds
    @Singleton
    public abstract fun bindSupabaseAuthClient(impl: SupabaseAuthClientImpl): SupabaseAuthClient

    /** Binds [EncryptedTokenStore] (SP-15) as the singleton [SupabaseSessionStore]. */
    @Binds
    @Singleton
    public abstract fun bindSupabaseSessionStore(impl: EncryptedTokenStore): SupabaseSessionStore

    /** Binds [AndroidNetworkMonitor] (SP-05) as the singleton [NetworkMonitor]. */
    @Binds
    @Singleton
    public abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    /** Binds [DefaultAuthTokenProvider] as the singleton [AuthTokenProvider] consumed by SP-05's interceptor. */
    @Binds
    @Singleton
    public abstract fun bindAuthTokenProvider(impl: DefaultAuthTokenProvider): AuthTokenProvider

    /** Binds [DefaultIdempotencyKeyProvider] (SP-05) as the singleton [IdempotencyKeyProvider]. */
    @Binds
    @Singleton
    public abstract fun bindIdempotencyKeyProvider(impl: DefaultIdempotencyKeyProvider): IdempotencyKeyProvider

    // ─── Provides ────────────────────────────────────────────────────────────────

    @Module
    @InstallIn(SingletonComponent::class)
    public object NetworkModuleProvides {

        /**
         * Provides the [BecalmApiConfig] populated from [BuildConfig.BECALM_API_BASE_URL].
         *
         * Wrapping the raw URL in a typed config object avoids collisions with any other
         * `@Provides fun provideString(): String` that Hilt would reject as an ambiguous binding.
         */
        @Provides
        @Singleton
        public fun provideBecalmApiConfig(): BecalmApiConfig =
            BecalmApiConfig(baseUrl = BuildConfig.BECALM_API_BASE_URL)

        /**
         * Provides the [SupabaseConfig] populated from [BuildConfig.SUPABASE_URL] and
         * [BuildConfig.SUPABASE_ANON_KEY]. Values are injected at build time — never hardcoded.
         */
        @Provides
        @Singleton
        public fun provideSupabaseConfig(): SupabaseConfig =
            SupabaseConfig(
                url = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )

        /**
         * Creates the application-scoped [SupabaseClient] via SP-04's factory function.
         *
         * The client is configured with `autoLoadFromStorage = false` and
         * `alwaysAutoRefresh = false` — see [createSupabaseClient] KDoc for rationale.
         */
        @Provides
        @Singleton
        public fun provideSupabaseClient(config: SupabaseConfig): SupabaseClient =
            createSupabaseClient(config)

        /**
         * Creates the application-scoped [OkHttpClient] via SP-05's [ApiFactory].
         *
         * Interceptors (auth, idempotency, logging) are attached inside [ApiFactory];
         * this method only supplies the dependencies they require.
         */
        @Provides
        @Singleton
        public fun provideOkHttpClient(
            authProvider: AuthTokenProvider,
            idempotencyProvider: IdempotencyKeyProvider,
            config: BecalmApiConfig,
        ): OkHttpClient = ApiFactory.createOkHttpClient(
            authProvider = authProvider,
            idempotencyProvider = idempotencyProvider,
            railwayHost = config.baseUrl.toHttpUrlOrNull()?.host.orEmpty(),
            isDebug = BuildConfig.DEBUG,
        )

        /**
         * Creates the application-scoped [Retrofit] instance via SP-05's [ApiFactory].
         *
         * Moshi converter and base URL are wired here; individual service interfaces are
         * created by [provideRailwayApi].
         */
        @Provides
        @Singleton
        public fun provideRetrofit(
            okHttp: OkHttpClient,
            moshi: Moshi,
            config: BecalmApiConfig,
        ): Retrofit = ApiFactory.createRetrofit(
            baseUrl = config.baseUrl,
            okHttp = okHttp,
            moshi = moshi,
        )

        /**
         * Creates the [RailwayApi] Retrofit service interface (SP-05).
         *
         * Retrofit caches service implementations; this singleton ensures a single proxy
         * object is shared across all repositories.
         */
        @Provides
        @Singleton
        public fun provideRailwayApi(retrofit: Retrofit): RailwayApi =
            ApiFactory.createRailwayApi(retrofit)

        /**
         * Creates the [VoiceApi] Retrofit service interface.
         *
         * Uses a dedicated [OkHttpClient] configured with [HttpTimeouts.Voice]
         * (connect=30s, read=180s, write=180s) to handle audio uploads up to 60 MiB
         * and server-side Vertex AI inference latency (VOI-006, api-contract.yml).
         *
         * The same base URL and authentication interceptor chain as [RailwayApi] are used.
         */
        @Provides
        @Singleton
        public fun provideVoiceApi(
            authProvider: AuthTokenProvider,
            idempotencyProvider: IdempotencyKeyProvider,
            moshi: Moshi,
            config: BecalmApiConfig,
        ): VoiceApi {
            val voiceOkHttp = ApiFactory.createOkHttpClient(
                authProvider = authProvider,
                idempotencyProvider = idempotencyProvider,
                railwayHost = config.baseUrl.toHttpUrlOrNull()?.host.orEmpty(),
                isDebug = BuildConfig.DEBUG,
                timeouts = HttpTimeouts.Voice,
            )
            val voiceRetrofit = ApiFactory.createRetrofit(
                baseUrl = config.baseUrl,
                okHttp = voiceOkHttp,
                moshi = moshi,
            )
            return ApiFactory.createVoiceApi(voiceRetrofit)
        }
    }
}

/**
 * Thin typed wrapper around the Railway API base URL string.
 *
 * Prevents Hilt from treating this as an unqualified [String] binding that could clash with
 * other `@Provides`-annotated string values in the graph.
 *
 * @param baseUrl The full base URL of the BeCalm Railway backend, e.g. `"https://api.becalm.app"`.
 */
public data class BecalmApiConfig(val baseUrl: String)

/**
 * Production [AuthTokenProvider] implementation — the AUTH-006 bridge.
 *
 * Combines [SupabaseSessionStore] (encrypted local token storage, SP-15) and
 * [SupabaseAuthClient] (Supabase SDK token refresh, SP-04) into the single interface
 * consumed by SP-05's `AuthInterceptor`.
 *
 * ## Concurrency invariants (Round 6A.4)
 *
 * 1. **Hot-path: no disk on every request.** [currentAccessToken] reads a `@Volatile`
 *    in-memory cache. Disk is touched at most once per process lifetime (or after an
 *    explicit [invalidate]). The first cold read is guarded by [primeMutex] so that N
 *    concurrent "cold" hits coalesce to a single [SupabaseSessionStore.load] call.
 * 2. **Refresh dedup.** [refresh] is serialised by [refreshMutex] with an in-flight
 *    [Deferred] cache ([inFlight]). A thundering herd of N simultaneous 401s produces
 *    exactly one upstream refresh; every waiter observes the same result.
 * 3. **Owned scope.** The in-flight `async { ... }` runs on a self-owned
 *    [CoroutineScope] wrapping a [SupervisorJob] and the injected `@IoDispatcher`.
 *    We never leak into `GlobalScope`.
 * 4. **Cancellation.** Refresh failures rethrow [kotlinx.coroutines.CancellationException]
 *    via [rethrowIfCancellation]; other errors are logged and mapped to `null` so the
 *    [com.becalm.android.data.remote.interceptor.AuthInterceptor] can propagate the 401.
 *
 * [currentAccessToken] itself remains synchronous — it performs a `@Volatile` read on
 * the fast path, and only falls back to [runBlocking] when the cache is cold. The
 * OkHttp interceptor that calls it is already on a dispatcher thread per OkHttp's
 * sync contract, so `runBlocking` there is safe.
 */
@Singleton
public class DefaultAuthTokenProvider @Inject constructor(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : AuthTokenProvider {

    private companion object {
        const val TAG = "AuthTokenProvider"
    }

    // In-memory cache of the access token. `null` means either no session exists OR the
    // cache has never been warmed. The [cacheWarmed] flag disambiguates.
    @Volatile private var cachedToken: String? = null

    // True once the cache has been populated from storage at least once since the last
    // [invalidate] call. Enables a fast return from [currentAccessToken] without
    // re-hitting disk to confirm a `null` session.
    @Volatile private var cacheWarmed: Boolean = false

    // Serializes the first disk read so concurrent cold reads coalesce to one
    // [sessionStore.load] call.
    private val primeMutex = Mutex()

    // Serializes refresh entry so only one `async { doRefresh() }` is in flight at a time.
    private val refreshMutex = Mutex()

    // The currently in-flight refresh, or `null` when no refresh is active. Read/written
    // only while holding [refreshMutex].
    @Volatile private var inFlight: Deferred<String?>? = null

    // Self-owned scope for the in-flight refresh coroutine. SupervisorJob so a single
    // refresh failure does not cancel siblings; bound to @IoDispatcher since the underlying
    // work is network + disk I/O.
    private val refreshScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Returns the current access token synchronously.
     *
     * Fast path: `@Volatile` read of the in-memory cache — no disk, no lock, no coroutine
     * hop. Hit on every request after the first.
     *
     * Cold path (first call after process start or [invalidate]): bridges into a
     * [runBlocking] that acquires [primeMutex] and loads from [SupabaseSessionStore].
     * Called on an OkHttp dispatcher thread (never the main thread), which is already
     * a blocking-I/O-safe pool, so we run the suspend body on the current thread rather
     * than dispatching to [ioDispatcher] — that would add an unnecessary thread hop.
     */
    override fun currentAccessToken(): String? {
        if (cacheWarmed) return cachedToken
        return runBlocking { primeCache() }
    }

    /**
     * Loads the access token from [SupabaseSessionStore] into the in-memory cache.
     *
     * Safe to call from any thread. Concurrent callers coalesce via [primeMutex] so disk
     * is touched at most once per cold window.
     */
    override suspend fun primeCache(): String? {
        if (cacheWarmed) return cachedToken
        return primeMutex.withLock {
            // Double-checked: another coroutine may have warmed the cache while we
            // were waiting for the lock.
            if (cacheWarmed) return@withLock cachedToken
            val token = try {
                sessionStore.load()?.accessToken
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                logger.e(TAG, "primeCache: sessionStore.load failed", e)
                null
            }
            cachedToken = token
            cacheWarmed = true
            token
        }
    }

    /**
     * Attempts a token refresh via [SupabaseAuthClient] and persists the new session.
     *
     * Deduplicates concurrent callers: only one upstream refresh runs at a time; all
     * waiters observe the same result. The cached token is updated on success.
     */
    override suspend fun refresh(): String? {
        val deferred: Deferred<String?>
        var owned = false
        refreshMutex.withLock {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) {
                // Piggy-back on an existing in-flight refresh; we do NOT own the slot.
                deferred = existing
            } else {
                deferred = refreshScope.async { doRefresh() }
                inFlight = deferred
                owned = true
            }
        }
        return try {
            deferred.await()
        } finally {
            // Only the owner clears the slot. A cancelled waiter must not wipe `inFlight`
            // while the shared refresh is still running — doing so would let the next
            // caller spawn a second upstream refresh, violating the dedup invariant.
            if (owned) {
                refreshMutex.withLock {
                    if (inFlight === deferred) inFlight = null
                }
            }
        }
    }

    /**
     * Clears the in-memory cache so the next [currentAccessToken] call consults storage.
     * Does NOT clear [SupabaseSessionStore]; the caller (AuthRepository) is responsible
     * for that wipe.
     */
    override fun invalidate() {
        cachedToken = null
        cacheWarmed = false
    }

    /**
     * Executes the actual refresh round-trip against [SupabaseAuthClient] and persists
     * the new session. Runs exactly once per thundering-herd burst thanks to the
     * [inFlight] cache in [refresh].
     */
    private suspend fun doRefresh(): String? {
        return try {
            val current = sessionStore.load() ?: run {
                logger.w(TAG, "refresh: no session in store")
                return null
            }
            val session = authClient.refresh(current.refreshToken).getOrNull() ?: run {
                logger.w(TAG, "refresh: authClient.refresh returned failure")
                return null
            }
            sessionStore.save(session)
            cachedToken = session.accessToken
            cacheWarmed = true
            logger.d(TAG, "refresh: succeeded")
            session.accessToken
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            logger.e(TAG, "refresh: unexpected error", e)
            null
        }
    }
}
