package com.becalm.android.core.di

import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.SystemClock
import com.becalm.android.core.util.TimberLogger
import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-scoped infrastructure singletons.
 *
 * Responsible for:
 * - [Moshi]: JSON serialisation with custom kotlinx-datetime adapters (SP-02) registered
 *   before [com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory] so that the
 *   strongly-typed adapters take precedence over reflective fallback.
 * - [Clock]: wall-clock abstraction for deterministic testing.
 * - [Logger]: Timber-backed logging abstraction, decoupled from global Timber state.
 */
@Module
@InstallIn(SingletonComponent::class)
public object AppModule {

    /**
     * Constructs the application-scoped [Moshi] instance.
     *
     * Custom kotlinx-datetime adapters ([com.becalm.android.core.util.InstantAdapter],
     * [com.becalm.android.core.util.LocalDateAdapter], [com.becalm.android.core.util.LocalDateTimeAdapter])
     * are registered first via [addBecalmAdapters]. The reflective [KotlinJsonAdapterFactory]
     * is appended last so it only handles types not covered by the custom adapters.
     */
    @Provides
    @Singleton
    public fun provideMoshi(): Moshi =
        Moshi.Builder()
            .addBecalmAdapters()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

    /**
     * Binds the production [SystemClock] as the singleton [Clock] implementation.
     *
     * Tests can override this binding in a `@TestInstallIn` module to supply a
     * deterministic fake without patching global state.
     */
    @Provides
    @Singleton
    public fun provideClock(): Clock = SystemClock

    /**
     * Binds [TimberLogger] as the singleton [Logger] implementation.
     *
     * [timber.log.Timber.plant] is NOT called here; the Application class (Round 10)
     * is responsible for planting the appropriate tree before any logging occurs.
     */
    @Provides
    @Singleton
    public fun provideLogger(): Logger = TimberLogger
}
