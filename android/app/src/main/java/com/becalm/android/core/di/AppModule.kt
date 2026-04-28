package com.becalm.android.core.di

import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.SystemClock
import com.becalm.android.core.util.TimberLogger
import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.BINARY

@Qualifier
@Retention(BINARY)
public annotation class ApplicationScope

/**
 * Hilt module providing application-scoped infrastructure singletons.
 *
 * - [Moshi]: JSON serialisation with custom kotlinx-datetime adapters (SP-02) registered
 *   before [KotlinJsonAdapterFactory] so that strongly-typed adapters take precedence.
 * - [Clock]: wall-clock abstraction for deterministic testing.
 * - [Logger]: Timber-backed logging abstraction, decoupled from global Timber state.
 * - [CoroutineScope]: process-lifetime scope used by [com.becalm.android.worker.ForegroundCatchUpScheduler]
 *   to debounce foreground catch-up requests. `SupervisorJob` isolates child failures so a
 *   single dispatched coroutine cannot tear down the shared scope.
 */
@Module
@InstallIn(SingletonComponent::class)
public object AppModule {

    @Provides
    @Singleton
    public fun provideMoshi(): Moshi =
        Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()

    @Provides
    @Singleton
    public fun provideClock(): Clock = SystemClock

    @Provides
    @Singleton
    public fun provideLogger(): Logger = TimberLogger

    @Provides
    @Singleton
    @ApplicationScope
    public fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
