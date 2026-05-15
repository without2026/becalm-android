package com.becalm.android.core.di

import com.becalm.android.BuildConfig
import com.becalm.android.core.analytics.CompositeProductAnalyticsClient
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object ProductAnalyticsModule {

    @Provides
    @Singleton
    public fun provideProductAnalyticsClient(
        composite: CompositeProductAnalyticsClient,
        noop: NoopProductAnalyticsClient,
    ): ProductAnalyticsClient =
        if (BuildConfig.TELEMETRY_ENABLED && BuildConfig.AMPLITUDE_API_KEY.isNotBlank()) composite else noop
}
