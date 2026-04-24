package com.becalm.android.core.di

import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CalendarEventRepositoryImpl
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.data.repository.UserProfileRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class TimelineRepositoryModule {

    @Binds
    @Singleton
    public abstract fun bindCommitmentRepository(
        impl: CommitmentRepositoryImpl,
    ): CommitmentRepository

    @Binds
    @Singleton
    public abstract fun bindCalendarEventRepository(
        impl: CalendarEventRepositoryImpl,
    ): CalendarEventRepository

    @Binds
    @Singleton
    public abstract fun bindPersonEnrichmentRepository(
        impl: PersonEnrichmentRepositoryImpl,
    ): PersonEnrichmentRepository

    @Binds
    @Singleton
    public abstract fun bindUserProfileRepository(
        impl: UserProfileRepositoryImpl,
    ): UserProfileRepository
}
