package com.becalm.android.core.di

import com.becalm.android.BuildConfig
import com.becalm.android.data.remote.api.ApiFactory
import com.becalm.android.data.remote.api.HttpTimeouts
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.becalm.android.data.remote.supabase.SupabaseConfig
import com.becalm.android.data.remote.supabase.createSupabaseClient
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object NetworkProvidersModule {

    @Provides
    @Singleton
    public fun provideBecalmApiConfig(): BecalmApiConfig =
        BecalmApiConfig(baseUrl = BuildConfig.BECALM_API_BASE_URL)

    @Provides
    @Singleton
    public fun provideSupabaseConfig(): SupabaseConfig =
        SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
        )

    @Provides
    @Singleton
    public fun provideSupabaseClient(config: SupabaseConfig): SupabaseClient =
        createSupabaseClient(config)

    @Provides
    @Singleton
    public fun provideOkHttpClient(
        authProvider: AuthTokenProvider,
        authFailureSessionInvalidator: com.becalm.android.data.auth.AuthFailureSessionInvalidator,
        idempotencyProvider: IdempotencyKeyProvider,
        config: BecalmApiConfig,
    ): OkHttpClient = ApiFactory.createOkHttpClient(
        authProvider = authProvider,
        authFailureSessionInvalidator = authFailureSessionInvalidator,
        idempotencyProvider = idempotencyProvider,
        railwayHost = config.baseUrl.toHttpUrlOrNull()?.host.orEmpty(),
        isDebug = BuildConfig.DEBUG,
    )

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

    @Provides
    @Singleton
    public fun provideRailwayApi(retrofit: Retrofit): RailwayApi =
        ApiFactory.createRailwayApi(retrofit)

    @Provides
    @Singleton
    public fun provideVoiceApi(
        authProvider: AuthTokenProvider,
        authFailureSessionInvalidator: com.becalm.android.data.auth.AuthFailureSessionInvalidator,
        idempotencyProvider: IdempotencyKeyProvider,
        moshi: Moshi,
        config: BecalmApiConfig,
    ): VoiceApi {
        val voiceOkHttp = ApiFactory.createOkHttpClient(
            authProvider = authProvider,
            authFailureSessionInvalidator = authFailureSessionInvalidator,
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

public data class BecalmApiConfig(val baseUrl: String)
