package com.becalm.android.core.di

import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.CommitmentProgressEventDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.PersonActionDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.ScheduleEventLinkDao
import com.becalm.android.data.local.db.dao.ScheduleRowTombstoneDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.dao.SourceConnectionDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.dao.SourceArtifactDao
import com.becalm.android.data.local.db.dao.UserCorrectionDao
import com.becalm.android.data.local.db.dao.UserProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
 * is observable without rebuilding Hilt's graph.
 *
 * DAOs are **not** scoped to `@Singleton`. To keep pre-auth startup safe, Hilt receives
 * lazy proxy instances that defer `BeCalmDatabaseProvider.current()` until the first DAO
 * method call. This prevents worker/repository construction from opening Room before an
 * authenticated user exists, while preserving the same DAO API at call sites.
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

    /** Provides a lazy [RawIngestionEventDao] proxy backed by the current user-scoped DB. */
    @Provides
    public fun provideRawIngestionEventDao(
        provider: BeCalmDatabaseProvider,
    ): RawIngestionEventDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::rawIngestionEventDao)

    /** Provides a lazy [CommitmentDao] proxy backed by the current user-scoped DB. */
    @Provides
    public fun provideCommitmentDao(
        provider: BeCalmDatabaseProvider,
    ): CommitmentDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::commitmentDao)

    /** Provides a lazy [CalendarEventDao] proxy backed by the current user-scoped DB. */
    @Provides
    public fun provideCalendarEventDao(
        provider: BeCalmDatabaseProvider,
    ): CalendarEventDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::calendarEventDao)

    /** Provides a lazy [PersonEnrichmentDao] proxy backed by the current user-scoped DB. */
    @Provides
    public fun providePersonEnrichmentDao(
        provider: BeCalmDatabaseProvider,
    ): PersonEnrichmentDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::personEnrichmentDao)

    /** Provides a lazy [PersonIndexDao] proxy backed by the current user-scoped DB. */
    @Provides
    public fun providePersonIndexDao(
        provider: BeCalmDatabaseProvider,
    ): PersonIndexDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::personIndexDao)

    /**
     * Provides the room-only [EmailBodyDao] from the singleton [BeCalmDatabase].
     *
     * Required by [com.becalm.android.data.repository.EmailBodyRepositoryImpl] —
     * the repository owns the EMAIL-006 PIPA invariant boundary and never lets
     * email body fields reach a wire DTO.
     */
    @Provides
    public fun provideEmailBodyDao(
        provider: BeCalmDatabaseProvider,
    ): EmailBodyDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::emailBodyDao)

    @Provides
    public fun provideSourceArtifactDao(
        provider: BeCalmDatabaseProvider,
    ): SourceArtifactDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::sourceArtifactDao)

    @Provides
    public fun provideUserProfileDao(
        provider: BeCalmDatabaseProvider,
    ): UserProfileDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::userProfileDao)

    @Provides
    public fun provideScheduleEventLinkDao(
        provider: BeCalmDatabaseProvider,
    ): ScheduleEventLinkDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::scheduleEventLinkDao)

    @Provides
    public fun provideScheduleRowTombstoneDao(
        provider: BeCalmDatabaseProvider,
    ): ScheduleRowTombstoneDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::scheduleRowTombstoneDao)

    @Provides
    public fun provideSelfIdentityAnchorDao(
        provider: BeCalmDatabaseProvider,
    ): SelfIdentityAnchorDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::selfIdentityAnchorDao)

    @Provides
    public fun provideSourceConnectionDao(
        provider: BeCalmDatabaseProvider,
    ): SourceConnectionDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::sourceConnectionDao)

    @Provides
    public fun provideMeetingSpeakerPreviewDao(
        provider: BeCalmDatabaseProvider,
    ): MeetingSpeakerPreviewDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::meetingSpeakerPreviewDao)

    @Provides
    public fun provideMeetingSpeakerAliasDao(
        provider: BeCalmDatabaseProvider,
    ): MeetingSpeakerAliasDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::meetingSpeakerAliasDao)

    @Provides
    public fun provideCommitmentProgressEventDao(
        provider: BeCalmDatabaseProvider,
    ): CommitmentProgressEventDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::commitmentProgressEventDao)

    @Provides
    public fun provideSourceEventAnchorDao(
        provider: BeCalmDatabaseProvider,
    ): SourceEventAnchorDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::sourceEventAnchorDao)

    @Provides
    public fun provideUserCorrectionDao(
        provider: BeCalmDatabaseProvider,
    ): UserCorrectionDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::userCorrectionDao)

    @Provides
    public fun providePersonActionDao(
        provider: BeCalmDatabaseProvider,
    ): PersonActionDao =
        lazyDaoProxy(dbProvider = provider, eager = null, accessor = BeCalmDatabase::personActionDao)

    private inline fun <reified T : Any> lazyDaoProxy(
        dbProvider: BeCalmDatabaseProvider?,
        eager: BeCalmDatabase?,
        noinline accessor: (BeCalmDatabase) -> T,
    ): T {
        val daoType = T::class.java
        val invocationHandler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "toString" -> "LazyDaoProxy(${daoType.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    val database = eager ?: requireNotNull(dbProvider).current()
                    val target = accessor(database)
                    try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (error: InvocationTargetException) {
                        throw (error.targetException ?: error)
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            daoType.classLoader,
            arrayOf(daoType),
            invocationHandler,
        ) as T
    }
}
