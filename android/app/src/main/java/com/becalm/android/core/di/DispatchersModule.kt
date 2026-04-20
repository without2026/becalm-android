package com.becalm.android.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.BINARY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// ─── Qualifier annotations ───────────────────────────────────────────────────

/**
 * Selects [Dispatchers.IO] — dedicated to blocking I/O work (Room, file system, disk DataStore,
 * network sync-boundary calls inside OkHttp interceptors). Callers must not use this dispatcher
 * for CPU-bound work.
 */
@Qualifier
@Retention(BINARY)
public annotation class IoDispatcher

/**
 * Selects [Dispatchers.Default] — for CPU-bound work (parsing, diffing, in-memory transforms).
 * Do not schedule blocking I/O here.
 */
@Qualifier
@Retention(BINARY)
public annotation class DefaultDispatcher

/**
 * Selects [Dispatchers.Main] — for UI-thread interaction. Business-logic classes should avoid
 * depending on this dispatcher directly; prefer letting ViewModels default to the main
 * dispatcher via `viewModelScope`.
 */
@Qualifier
@Retention(BINARY)
public annotation class MainDispatcher

// ─── Hilt module ─────────────────────────────────────────────────────────────

/**
 * Wires the three canonical [CoroutineDispatcher]s behind qualifier annotations so that
 * business-logic classes never hard-code `Dispatchers.IO` / `Dispatchers.Default` /
 * `Dispatchers.Main`.
 *
 * Swapping the production bindings for `TestDispatcher` variants in tests is a single-site
 * module replacement — see `docs/round6-plan.md` § 6A.2 for rationale.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DispatchersModule {

    @Provides
    @Singleton
    @IoDispatcher
    public fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    public fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @MainDispatcher
    public fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
