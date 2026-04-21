package com.becalm.android.core.di

import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthRepositoryImpl
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CalendarEventRepositoryImpl
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.EmailBodyRepositoryImpl
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.SourceStatusRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds all R3 repository interfaces to their implementations.
 *
 * All six sibling [Binds] declarations live here so that the DI graph resolves
 * every repository in a single module without scattering bindings across files.
 * The impl classes (SP-17 through SP-21) are dispatched in parallel; this file
 * will not compile until all six impl classes are present.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class RepositoryModule {

    /** Binds [AuthRepositoryImpl] (SP-16). */
    @Binds
    @Singleton
    public abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    /** Binds [RawIngestionRepositoryImpl] (SP-17). */
    @Binds
    @Singleton
    public abstract fun bindRawIngestionRepository(impl: RawIngestionRepositoryImpl): RawIngestionRepository

    /** Binds [CommitmentRepositoryImpl] (SP-18). */
    @Binds
    @Singleton
    public abstract fun bindCommitmentRepository(impl: CommitmentRepositoryImpl): CommitmentRepository

    /** Binds [CalendarEventRepositoryImpl] (SP-19). */
    @Binds
    @Singleton
    public abstract fun bindCalendarEventRepository(impl: CalendarEventRepositoryImpl): CalendarEventRepository

    /** Binds [PersonEnrichmentRepositoryImpl] (SP-20). */
    @Binds
    @Singleton
    public abstract fun bindPersonEnrichmentRepository(impl: PersonEnrichmentRepositoryImpl): PersonEnrichmentRepository

    /** Binds [SourceStatusRepositoryImpl] (SP-21). */
    @Binds
    @Singleton
    public abstract fun bindSourceStatusRepository(impl: SourceStatusRepositoryImpl): SourceStatusRepository

    /**
     * Binds [EmailBodyRepositoryImpl] — owns the EMAIL-006 PIPA invariant boundary
     * (room-only `email_body` table; never serialised to any wire DTO).
     */
    @Binds
    @Singleton
    public abstract fun bindEmailBodyRepository(impl: EmailBodyRepositoryImpl): EmailBodyRepository
}
