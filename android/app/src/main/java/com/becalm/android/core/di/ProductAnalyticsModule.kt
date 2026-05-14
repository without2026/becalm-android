package com.becalm.android.core.di

import com.becalm.android.productanalytics.ProductAnalyticsClient
import com.becalm.android.productanalytics.ProductAnalyticsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class ProductAnalyticsModule {
    @Binds
    @Singleton
    public abstract fun bindProductAnalyticsClient(
        impl: ProductAnalyticsRepository,
    ): ProductAnalyticsClient
}
