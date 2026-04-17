package com.becalm.android.core.di

import android.content.Context
import com.becalm.android.BuildConfig
import com.becalm.android.core.result.getOrNull
import com.becalm.android.data.local.secure.EncryptedTokenStore // SP-15 — unresolved at R1 ship time; lands in Round 2
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.runBlocking
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
 *
 * ## Unresolved imports at R1 ship time
 * [com.becalm.android.data.local.secure.EncryptedTokenStore] is owned by SP-15 and lands in
 * Round 2. The binding `bindSupabaseSessionStore` will not compile until that class exists.
 * All other imports resolve within R1. This is intentional — the full Hilt graph compiles for
 * the first time in Round 10.
 *
 * Similarly, [SupabaseAuthClient], [SupabaseAuthClientImpl], [ApiFactory], [RailwayApi],
 * [AndroidNetworkMonitor], and [NetworkMonitor] are owned by SP-04/SP-05 and land in R1 in
 * parallel. Cross-SP symbols are expected to be absent until each SP ships.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class NetworkModule {

    /** Binds [SupabaseAuthClientImpl] (SP-04) as the singleton [SupabaseAuthClient]. */
    @Binds
    @Singleton
    public abstract fun bindSupabaseAuthClient(impl: SupabaseAuthClientImpl): SupabaseAuthClient

    /**
     * Binds [EncryptedTokenStore] (SP-15) as the singleton [SupabaseSessionStore].
     *
     * UNRESOLVED at R1 ship time — [EncryptedTokenStore] lands in Round 2 (SP-15).
     */
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
         * Creates the [AndroidNetworkMonitor] with the application [Context].
         *
         * Returned as the concrete type so Hilt can satisfy the [bindNetworkMonitor] binding
         * without requiring a separate provider for the interface.
         */
        @Provides
        @Singleton
        public fun provideAndroidNetworkMonitor(
            @ApplicationContext context: Context,
        ): AndroidNetworkMonitor = AndroidNetworkMonitor(context)

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
 * Thread-safety note: [currentAccessToken] uses [runBlocking] and is called on an OkHttp
 * dispatcher thread, never on the main thread. This is the standard pattern for bridging
 * suspend storage reads into OkHttp's synchronous interceptor contract.
 */
@Singleton
public class DefaultAuthTokenProvider @Inject constructor(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
) : AuthTokenProvider {

    /**
     * Returns the current access token synchronously by blocking the calling OkHttp thread.
     *
     * Returns `null` when no session is persisted (user is signed out or first launch).
     */
    override fun currentAccessToken(): String? =
        runBlocking { sessionStore.load()?.accessToken }

    /**
     * Attempts a token refresh via [SupabaseAuthClient] and persists the new session.
     *
     * Returns the new access token on success, or `null` if the refresh token is expired
     * or the network is unavailable. The `AuthInterceptor` propagates the original HTTP 401
     * to callers when this returns `null`.
     */
    override suspend fun refresh(): String? {
        val current = sessionStore.load() ?: return null
        val result = authClient.refresh(current.refreshToken).getOrNull() ?: return null
        sessionStore.save(result.session)
        return result.session.accessToken
    }
}
