package com.becalm.android.core.di

import com.becalm.android.core.observability.LoggerObservabilityClient
import com.becalm.android.core.observability.ObservabilityClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt binding for the [ObservabilityClient] abstraction (S6-E).
 *
 * Production builds receive [LoggerObservabilityClient]; test builds may replace the
 * binding with a Hilt test module (`@TestInstallIn`) or override it via `@BindValue`
 * to capture emitted events. Swapping in a Sentry / Firebase Crashlytics impl later
 * is a one-line change to this module — call sites continue to depend on the
 * interface only.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class ObservabilityModule {

    /**
     * Binds [ObservabilityClient] to the logger-fan-out production implementation.
     *
     * `@Binds` over `@Provides` because the target impl is `@Inject`-constructible
     * (rubric E2) and the binding carries no logic.
     */
    @Binds
    @Singleton
    public abstract fun bindObservabilityClient(
        impl: LoggerObservabilityClient,
    ): ObservabilityClient
}
