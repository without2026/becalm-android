package com.becalm.android.core.di

import android.content.Context
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [BeCalmDatabase] singleton and all DAO bindings.
 *
 * ## Scoping
 * [BeCalmDatabase] is `@Singleton` because Room caches prepared statements and
 * the WAL journal internally. Multiple instances sharing the same file would risk
 * write contention and cache invalidation bugs.
 *
 * DAOs are **not** scoped to `@Singleton`. Room generates DAO implementations as
 * lightweight wrappers that delegate directly to the database instance; they carry
 * no mutable state of their own. Re-creating them on each injection site is safe and
 * avoids holding unnecessary references beyond a ViewModel's lifetime.
 *
 * ## Separation from [NetworkModule] and [AppModule]
 * Each infrastructure concern has its own Hilt module to keep the Hilt graph
 * readable and to allow test modules to replace just the database layer without
 * disturbing the network or logging bindings.
 *
 * ## Testing
 * Override this module in instrumented tests with `@TestInstallIn`:
 * ```kotlin
 * @TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
 * @Module
 * object TestDatabaseModule {
 *     @Provides @Singleton
 *     fun provideBeCalmDatabase(@ApplicationContext context: Context): BeCalmDatabase =
 *         Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
 *             .allowMainThreadQueries()
 *             .build()
 *     // ... DAO providers identical to DatabaseModule
 * }
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {

    /**
     * Provides the application-scoped [BeCalmDatabase] instance.
     *
     * Delegates construction to [BeCalmDatabase.build] which configures migrations,
     * downgrade fallback, and the WAL journal mode. The [ApplicationContext]-qualified
     * [Context] prevents Activity context leaks.
     *
     * @param context Application context supplied by Hilt.
     * @return The singleton [BeCalmDatabase].
     */
    @Provides
    @Singleton
    public fun provideBeCalmDatabase(
        @ApplicationContext context: Context,
    ): BeCalmDatabase = BeCalmDatabase.build(context)

    /** Provides [RawIngestionEventDao] from the singleton [BeCalmDatabase]. */
    @Provides
    public fun provideRawIngestionEventDao(db: BeCalmDatabase): RawIngestionEventDao =
        db.rawIngestionEventDao()

    /** Provides [CommitmentDao] from the singleton [BeCalmDatabase]. */
    @Provides
    public fun provideCommitmentDao(db: BeCalmDatabase): CommitmentDao =
        db.commitmentDao()

    /** Provides [CalendarEventDao] from the singleton [BeCalmDatabase]. */
    @Provides
    public fun provideCalendarEventDao(db: BeCalmDatabase): CalendarEventDao =
        db.calendarEventDao()

    /** Provides [PersonEnrichmentDao] from the singleton [BeCalmDatabase]. */
    @Provides
    public fun providePersonEnrichmentDao(db: BeCalmDatabase): PersonEnrichmentDao =
        db.personEnrichmentDao()
}
