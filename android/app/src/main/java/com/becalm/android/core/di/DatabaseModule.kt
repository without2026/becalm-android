package com.becalm.android.core.di

import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [BeCalmDatabase] singleton and all DAO bindings.
 *
 * ## Scoping
 * [BeCalmDatabase] is resolved through the `@Singleton` [BeCalmDatabaseProvider] so that
 * the on-disk SQLite file is keyed on the signed-in user's identity (S6-A, PIPA
 * cross-account leak defence). `provideBeCalmDatabase` itself is **not** `@Singleton`
 * — it proxies to [BeCalmDatabaseProvider.current] on every injection so that a
 * downstream user-scope swap routed through [BeCalmDatabaseProvider.ensureOpenFor]
 * is observable without rebuilding Hilt's graph. For alpha, repositories scoped
 * `@Singleton` still cache the reference they receive at construction time; the
 * recommended UX is a process restart on user swap and a follow-up refactor will
 * migrate repos to `Provider<Dao>` injection.
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
 */
@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {

    /**
     * Provides the currently-open user-scoped [BeCalmDatabase] instance.
     *
     * Delegates to [BeCalmDatabaseProvider.current], which lazily builds the SQLite
     * file keyed on the signed-in user's id hash and throws if no user is authenticated.
     * Intentionally unscoped so that each injection observes the provider's latest
     * instance — see the module-level KDoc for the alpha user-swap caveat.
     *
     * @param provider Application-scoped [BeCalmDatabaseProvider] supplied by Hilt.
     * @return The user-scoped [BeCalmDatabase] currently held by the provider.
     */
    @Provides
    public fun provideBeCalmDatabase(
        provider: BeCalmDatabaseProvider,
    ): BeCalmDatabase = provider.current()

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

    /**
     * Provides the room-only [EmailBodyDao] from the singleton [BeCalmDatabase].
     *
     * Required by [com.becalm.android.data.repository.EmailBodyRepositoryImpl] —
     * the repository owns the EMAIL-006 PIPA invariant boundary and never lets
     * email body fields reach a wire DTO.
     */
    @Provides
    public fun provideEmailBodyDao(db: BeCalmDatabase): EmailBodyDao =
        db.emailBodyDao()
}
