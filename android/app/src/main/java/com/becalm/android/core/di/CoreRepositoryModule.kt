package com.becalm.android.core.di

import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthRepositoryImpl
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.EmailBodyRepositoryImpl
import com.becalm.android.data.repository.PersonManualMatchRepository
import com.becalm.android.data.repository.PersonManualMatchRepositoryImpl
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourcePersonCandidateRepository
import com.becalm.android.data.repository.SourcePersonCandidateRepositoryImpl
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.SourceStatusRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class CoreRepositoryModule {

    @Binds
    @Singleton
    public abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    public abstract fun bindRawIngestionRepository(
        impl: RawIngestionRepositoryImpl,
    ): RawIngestionRepository

    @Binds
    @Singleton
    public abstract fun bindSourceStatusRepository(
        impl: SourceStatusRepositoryImpl,
    ): SourceStatusRepository

    @Binds
    @Singleton
    public abstract fun bindSourcePersonCandidateRepository(
        impl: SourcePersonCandidateRepositoryImpl,
    ): SourcePersonCandidateRepository

    @Binds
    @Singleton
    public abstract fun bindPersonManualMatchRepository(
        impl: PersonManualMatchRepositoryImpl,
    ): PersonManualMatchRepository

    @Binds
    @Singleton
    public abstract fun bindEmailBodyRepository(
        impl: EmailBodyRepositoryImpl,
    ): EmailBodyRepository
}
