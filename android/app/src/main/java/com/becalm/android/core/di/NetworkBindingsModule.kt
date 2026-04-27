package com.becalm.android.core.di

import com.becalm.android.data.auth.AuthFailureSessionInvalidator
import com.becalm.android.data.auth.AuthFailureSessionInvalidatorImpl
import com.becalm.android.data.local.secure.EncryptedTokenStore
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapClientImpl
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.DefaultIdempotencyKeyProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.becalm.android.data.remote.network.AndroidNetworkMonitor
import com.becalm.android.data.remote.network.NetworkMonitor
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseAuthClientImpl
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class NetworkBindingsModule {

    @Binds
    @Singleton
    public abstract fun bindSupabaseAuthClient(impl: SupabaseAuthClientImpl): SupabaseAuthClient

    @Binds
    @Singleton
    public abstract fun bindSupabaseSessionStore(impl: EncryptedTokenStore): SupabaseSessionStore

    @Binds
    @Singleton
    public abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    public abstract fun bindAuthTokenProvider(impl: DefaultAuthTokenProvider): AuthTokenProvider

    @Binds
    @Singleton
    public abstract fun bindAuthFailureSessionInvalidator(
        impl: AuthFailureSessionInvalidatorImpl,
    ): AuthFailureSessionInvalidator

    @Binds
    @Singleton
    public abstract fun bindIdempotencyKeyProvider(
        impl: DefaultIdempotencyKeyProvider,
    ): IdempotencyKeyProvider

    @Binds
    @Singleton
    public abstract fun bindImapClient(impl: ImapClientImpl): ImapClient

}
