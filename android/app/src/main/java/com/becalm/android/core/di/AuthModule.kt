package com.becalm.android.core.di

import com.becalm.android.BuildConfig
import com.becalm.android.data.remote.gmail.AuthorizationClientGateway
import com.becalm.android.data.remote.gmail.AuthorizationClientGatewayImpl
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProvider
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.remote.msgraph.MsGraphTokenProvider
import com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
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

    /**
     * Binds [MsGraphTokenProviderImpl] as the singleton [MsGraphTokenProvider] consumed
     * by [com.becalm.android.data.remote.msgraph.MsGraphClientImpl].
     *
     * ING-007 (spec `.spec/data-ingestion.spec.yml:71`) — Outlook / MS Graph periodic
     * sync. Before this binding every `MsGraphClientImpl` call returned Unauthorized
     * because the interface was stub-only (see plan
     * `docs/plans/repo-auth-msgraph-oauth-provider.md` § 1). With the binding in place,
     * [com.becalm.android.worker.ingestion.OutlookMailWorker] resolves a real MSAL-backed
     * token.
     */
    @Binds
    @Singleton
    public abstract fun bindMsGraphTokenProvider(
        impl: MsGraphTokenProviderImpl,
    ): MsGraphTokenProvider

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

    // ─── Provides ────────────────────────────────────────────────────────────────

    /**
     * Companion nested module — Hilt does not allow `@Binds` and `@Provides` in the same
     * concrete class (the @Binds owner must be abstract). The standard workaround is the
     * nested object seen in [NetworkModule.NetworkModuleProvides].
     */
    @Module
    @InstallIn(SingletonComponent::class)
    public object AuthModuleProvides {

        /**
         * Provides the MSAL Microsoft Entra application (client) ID sourced from
         * [BuildConfig.MSAL_CLIENT_ID]. Wired via `@Named("msalClientId")` so the raw
         * [String] does not collide with other string providers in the graph.
         *
         * Values originate from `local.properties` → `build.gradle.kts`
         * `buildConfigField` → [BuildConfig] at build time. The placeholder
         * `"00000000-0000-0000-0000-000000000000"` is substituted in CI builds and
         * overridden in developer builds via the `msal.client.id` Gradle property — see
         * `android/app/build.gradle.kts` and
         * `docs/plans/repo-auth-msgraph-oauth-provider.md` § 5.4 for the wiring.
         */
        @Provides
        @Singleton
        @Named("msalClientId")
        public fun provideMsalClientId(): String = BuildConfig.MSAL_CLIENT_ID
    }
}
