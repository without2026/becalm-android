package com.becalm.android.core.di

import com.becalm.android.core.observability.CompositeObservabilityClient
import com.becalm.android.core.observability.FirebaseCrashlyticsPort
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.observability.RealFirebaseCrashlyticsPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt binding for the [ObservabilityClient] abstraction (S6-E).
 *
 * Production builds receive [CompositeObservabilityClient]; test builds may replace the
 * binding with a Hilt test module (`@TestInstallIn`) or override it via `@BindValue`
 * to capture emitted events. Swapping in the planned Firebase Crashlytics binding
 * later is a one-line change to this module — call sites continue to depend on
 * the interface only.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class ObservabilityModule {

    /**
     * Binds [ObservabilityClient] to the composite production implementation.
     *
     * `@Binds` over `@Provides` because the target impl is `@Inject`-constructible
     * (rubric E2) and the binding carries no logic.
     */
    @Binds
    @Singleton
    public abstract fun bindObservabilityClient(
        impl: CompositeObservabilityClient,
    ): ObservabilityClient

    @Binds
    @Singleton
    public abstract fun bindFirebaseCrashlyticsPort(
        impl: RealFirebaseCrashlyticsPort,
    ): FirebaseCrashlyticsPort
}
