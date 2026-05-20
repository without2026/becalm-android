package com.becalm.android.core.di

import com.becalm.android.BuildConfig
import com.becalm.android.core.analytics.CompositeProductAnalyticsClient
import com.becalm.android.core.analytics.FileProductAnalyticsEventQueue
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsAttributionStore
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEventQueue
import com.becalm.android.core.analytics.SharedPreferencesProductAnalyticsAttributionStore
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
        if (BuildConfig.TELEMETRY_ENABLED) composite else noop

    @Provides
    @Singleton
    public fun provideProductAnalyticsEventQueue(
        queue: FileProductAnalyticsEventQueue,
    ): ProductAnalyticsEventQueue = queue

    @Provides
    @Singleton
    public fun provideProductAnalyticsAttributionStore(
        store: SharedPreferencesProductAnalyticsAttributionStore,
    ): ProductAnalyticsAttributionStore = store
}
