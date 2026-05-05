package com.becalm.android.data.remote.api

import com.becalm.android.data.auth.AuthFailureSessionInvalidator
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.IdempotencyInterceptor
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.becalm.android.data.remote.interceptor.RetryInterceptor
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher

/**
 * Pure factory object for constructing the Retrofit + OkHttp network stack.
 *
 * All functions are stateless and side-effect free. SP-06 (`NetworkModule`) calls these
 * functions from `@Provides` methods and holds the resulting singletons in the Hilt graph.
 * No Hilt annotations belong here.
 *
 * ## OkHttp interceptor chain (outermost → innermost)
 *
 * 1. [HttpLoggingInterceptor] — logs request/response details. `BASIC` in debug builds,
 *    `NONE` in release builds.
 * 2. [IdempotencyInterceptor] — converts the `X-BeCalm-Idempotent: 1` sentinel header into
 *    a real `Idempotency-Key: <uuid>` once per logical OkHttp call. It sits outside retry
 *    and auth so 5xx/408/429 retries and 401 refresh retries reuse the same key.
 * 3. [RetryInterceptor] — retries on IOException, HTTP 5xx (except 501), 408, 429 with
 *    exponential backoff. Sits above auth so a refreshed token is used on retry.
 * 4. [AuthInterceptor] — attaches `Authorization: Bearer` and handles 401 refresh-and-retry
 *    (AUTH-007). Host-guarded: passes Supabase Auth calls through unchanged.
 */
public object ApiFactory {

    /**
     * Constructs a configured [OkHttpClient] with the full interceptor chain.
     *
     * @param authProvider Token source and 401-refresh delegate. Injected by SP-06.
     * @param idempotencyProvider UUID generator for `Idempotency-Key` headers. Injected by SP-06.
     * @param railwayHost Hostname of the Railway backend, e.g. `"becalm-api.railway.app"`.
     *   Used by [AuthInterceptor] to restrict bearer-token injection to Railway calls only.
     * @param isDebug When `true` enables `BASIC` HTTP logging; `false` disables all logging.
     *   Pass `BuildConfig.DEBUG` from the application module.
     * @param timeouts Timeout configuration. Defaults to [HttpTimeouts.Default].
     *   Pass [HttpTimeouts.SourceExtraction] for source extraction calls.
     */
    public fun createOkHttpClient(
        authProvider: AuthTokenProvider,
        authFailureSessionInvalidator: AuthFailureSessionInvalidator,
        idempotencyProvider: IdempotencyKeyProvider,
        railwayHost: String,
        isDebug: Boolean,
        timeouts: HttpTimeouts = HttpTimeouts.Default,
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (isDebug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_PARALLEL_REQUESTS
                    maxRequestsPerHost = MAX_PARALLEL_REQUESTS_PER_HOST
                },
            )
            .connectTimeout(timeouts.connectSeconds, TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeouts.writeSeconds, TimeUnit.SECONDS)
            // Chain: outermost → innermost
            .addInterceptor(loggingInterceptor)
            .addInterceptor(IdempotencyInterceptor(idempotencyProvider))
            .addInterceptor(RetryInterceptor())
            .addInterceptor(AuthInterceptor(authProvider, authFailureSessionInvalidator, railwayHost))
            .build()
    }

    /**
     * Constructs a [Retrofit] instance with Moshi JSON conversion.
     *
     * @param baseUrl Full base URL including trailing slash, e.g. `"https://becalm-api.railway.app/"`.
     *   Sourced from `BuildConfig.BECALM_API_BASE_URL` in the application module.
     * @param okHttp Pre-configured [OkHttpClient] from [createOkHttpClient].
     * @param moshi Shared [Moshi] instance with all required adapters (Instant, LocalDate, etc.)
     *   configured by SP-06.
     */
    public fun createRetrofit(
        baseUrl: String,
        okHttp: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /**
     * Creates the [RailwayApi] Retrofit service proxy from a pre-built [Retrofit] instance.
     *
     * @param retrofit [Retrofit] instance from [createRetrofit].
     */
    public fun createRailwayApi(retrofit: Retrofit): RailwayApi =
        retrofit.create(RailwayApi::class.java)

    /**
     * Creates the [SourceExtractionApi] Retrofit service proxy from a pre-built [Retrofit] instance.
     *
     * The [Retrofit] instance must be built with an [OkHttpClient] that uses
     * [HttpTimeouts.SourceExtraction] (connect=30s, read=180s, write=180s) to accommodate
     * audio file uploads up to 60 MiB, transcript extraction, and Vertex AI inference latency (VOI-006).
     *
     * @param retrofit [Retrofit] instance from [createRetrofit], configured with extraction timeouts.
     */
    public fun createSourceExtractionApi(retrofit: Retrofit): SourceExtractionApi =
        retrofit.create(SourceExtractionApi::class.java)

    private const val MAX_PARALLEL_REQUESTS = 8
    private const val MAX_PARALLEL_REQUESTS_PER_HOST = 4
}
