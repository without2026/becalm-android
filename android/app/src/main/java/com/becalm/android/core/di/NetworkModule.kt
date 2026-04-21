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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
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
 * ## Access-token cache (H3)
 * [currentAccessToken] is called once per authenticated Railway request on the OkHttp
 * dispatcher thread. Reading the session from [SupabaseSessionStore] each time would hit
 * `EncryptedSharedPreferences` (disk I/O + AES decryption) under a DataStore mutex and
 * serialize every dispatcher thread. This class keeps an in-memory [AtomicReference] cache
 * of the access token and updates it by observing [SupabaseSessionStore.observe]. The hot
 * read path is a single atomic load — no lock, no disk I/O, no `runBlocking`.
 *
 * Cold start: the observer subscribes in [init]; the first `currentAccessToken()` call may
 * precede the first emission. A fallback `runBlocking { sessionStore.load() }` seeds the
 * cache via `compareAndSet(null, ...)` on that first call.
 *
 * Observer death (subscription throws) is caught and logged via Timber. The cache freezes
 * at its last value — safe for read-mostly workloads — but logins/logouts after this point
 * will not propagate to the cache until the process restarts.
 *
 * ## Refresh coalescing (H3)
 * [refresh] is guarded by [refreshMutex]. When N parallel 401s trigger N parallel refresh
 * calls, the first acquires the lock, performs the Supabase refresh, and updates the cache.
 * Subsequent holders double-check whether the cache has advanced past `previousAccessToken`
 * and, if so, return the cached token without a second Supabase call. Supabase rotates
 * refresh tokens on every successful refresh, so this coalescing is correctness-preserving:
 * without it N-1 of N parallel callers would receive 401 at the Supabase refresh endpoint
 * and propagate the original 401 to callers.
 *
 * Thread-safety note: [currentAccessToken] is called on an OkHttp dispatcher thread, never
 * on the main thread. The hot path performs no blocking work; the cold-start fallback uses
 * [runBlocking] only.
 */
@Singleton
public class DefaultAuthTokenProvider @Inject constructor(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : AuthTokenProvider {

    private val cachedAccessToken = AtomicReference<String?>(null)
    private val refreshMutex = Mutex()

    /**
     * Scope that owns the [sessionStore] observer subscription.
     *
     * Dispatcher rationale: the collector does a trivial [AtomicReference.set] and must run
     * inline on the emitter thread to avoid races where `refresh()` returns before the observer
     * has updated the cache. Emitters run inside `EncryptedTokenStore.save/clear`
     * ([Dispatchers.IO]), so [Dispatchers.Unconfined] simply resumes the collector on IO.
     */
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        observerScope.launch {
            sessionStore.observe()
                .catch { e ->
                    Timber.e(e, "DefaultAuthTokenProvider: session observer died — cache will stale")
                }
                .collect { session ->
                    cachedAccessToken.set(session?.accessToken)
                }
        }
    }

    /**
     * Returns the current access token without suspending.
     *
     * Hot path: returns the cached token from [cachedAccessToken] with no lock or I/O.
     * Cold path (first call after process start, before the observer has emitted): loads
     * from [sessionStore] via [runBlocking] and seeds the cache with `compareAndSet(null, ...)`
     * so a concurrent observer emission does not overwrite a real token.
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
        cachedAccessToken.get()?.let { return it }
        val loaded = runBlocking { sessionStore.load()?.accessToken }
        loaded?.let { cachedAccessToken.compareAndSet(null, it) }
        return loaded
    }

    /**
     * Attempts a token refresh via [SupabaseAuthClient] and persists the new session.
     *
     * Serialized by [refreshMutex] so at most one Supabase refresh call is in flight at a
     * time. Before calling Supabase, double-checks whether another coroutine has already
     * advanced the cache past [previousAccessToken]; if so, returns the cached token without
     * hitting the network.
     *
     * Returns the new access token on success, or `null` if the refresh token is expired
     * or the network is unavailable. The `AuthInterceptor` propagates the original HTTP 401
     * to callers when this returns `null`.
     */
    override suspend fun refresh(previousAccessToken: String): String? = refreshMutex.withLock {
        val current = sessionStore.load() ?: return@withLock null

        // Coalesce: did another coroutine refresh while I was waiting for the lock?
        val cached = cachedAccessToken.get()
        if (cached != null && cached != previousAccessToken) {
            return@withLock cached
        }

        val result = authClient.refresh(current.refreshToken).getOrNull() ?: return@withLock null
        sessionStore.save(result)
        // Belt-and-suspenders: the observer will also update the cache on the save() emit.
        // Setting here guarantees the cache is updated before refresh() returns, removing any
        // window where a caller sees the new token from refresh() before observing it via
        // currentAccessToken().
        cachedAccessToken.set(result.accessToken)
        result.accessToken
    }
}
