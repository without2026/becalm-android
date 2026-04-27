package com.becalm.android.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module wiring authentication-scoped runtime helpers.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class AuthModule {

    /**
     * Binds [com.becalm.android.data.auth.ProcessRestarterImpl] as the singleton
     * [com.becalm.android.data.auth.ProcessRestarter] invoked by
     * [com.becalm.android.data.repository.AuthRepositoryImpl] when an account-swap
     * sign-in would otherwise leak `@Singleton`-captured DAO references across users
     * (AUTH-008, `.spec/auth.spec.yml:73`). Tests replace it with a fake that records
     * the call instead of terminating the process.
     */
    @Binds
    @Singleton
    public abstract fun bindProcessRestarter(
        impl: com.becalm.android.data.auth.ProcessRestarterImpl,
    ): com.becalm.android.data.auth.ProcessRestarter
}
