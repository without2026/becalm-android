package com.becalm.android.core.di

import com.becalm.android.data.remote.gmail.AuthorizationClientGateway
import com.becalm.android.data.remote.gmail.AuthorizationClientGatewayImpl
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProvider
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module wiring the third-party OAuth provider graph (Gmail today; Microsoft
 * Graph in a follow-up PR).
 *
 * ## Bindings
 * - [GoogleAuthTokenProvider] ← [GoogleAuthTokenProviderImpl] — the concrete Credential
 *   Manager + [com.google.android.gms.auth.api.identity.AuthorizationClient]
 *   implementation that replaces the prior stub-only interface consumed by
 *   [com.becalm.android.data.remote.gmail.GmailClientImpl].
 * - [AuthorizationClientGateway] ← [AuthorizationClientGatewayImpl] — thin production
 *   bridge over [com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient]
 *   that translates the returned [com.google.android.gms.tasks.Task] into a suspending
 *   function via [kotlinx.coroutines.suspendCancellableCoroutine]. The gateway abstraction
 *   exists exclusively so that [GoogleAuthTokenProviderImpl] can be unit-tested without
 *   loading Google Play Services classes.
 *
 * ## Spec refs
 * - `.spec/data-ingestion.spec.yml:62` (ING-006) — Gmail OAuth periodic sync.
 * - `.spec/data-ingestion.spec.yml:153` — Gmail/Outlook OAuth tokens must live in the
 *   Keystore only; never sent to Railway. [GoogleAuthTokenProviderImpl] writes
 *   exclusively to [com.becalm.android.data.local.secure.OAuthCredentialStore], a
 *   dedicated [androidx.security.crypto.EncryptedSharedPreferences] file disjoint from
 *   the Supabase-only [com.becalm.android.data.local.secure.EncryptedTokenStore].
 *
 * ## Plan doc
 * `docs/plans/repo-auth-gmail-oauth-provider.md` — design and acceptance criteria.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class AuthModule {

    /**
     * Binds [GoogleAuthTokenProviderImpl] as the singleton [GoogleAuthTokenProvider]
     * consumed by [com.becalm.android.data.remote.gmail.GmailClientImpl].
     */
    @Binds
    @Singleton
    public abstract fun bindGoogleAuthTokenProvider(
        impl: GoogleAuthTokenProviderImpl,
    ): GoogleAuthTokenProvider

    /**
     * Binds [AuthorizationClientGatewayImpl] as the singleton [AuthorizationClientGateway]
     * consumed by [GoogleAuthTokenProviderImpl]. Tests override this binding with a
     * hand-written fake in unit test fixtures.
     */
    @Binds
    @Singleton
    public abstract fun bindAuthorizationClientGateway(
        impl: AuthorizationClientGatewayImpl,
    ): AuthorizationClientGateway
}
