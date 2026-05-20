package com.becalm.android.data.auth

import com.becalm.android.core.analytics.AmplitudeProductAnalyticsClient
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "AuthFailureInvalidator"

/**
 * Collapses the local auth state after a permanent Supabase session failure.
 *
 * This is intentionally smaller than user-driven sign-out:
 * - no server-side revoke call,
 * - no Room wipe,
 *
 * The only goal is to make the app converge on a consistent signed-out state when
 * the backend has definitively rejected the session and further retries are pointless.
 */
public interface AuthFailureSessionInvalidator {
    public suspend fun invalidate()
}

@Singleton
public class AuthFailureSessionInvalidatorImpl @Inject constructor(
    private val sessionStore: SupabaseSessionStore,
    private val tokenProvider: AuthTokenProvider,
    private val workScheduler: WorkScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val userPrefsStore: UserPrefsStore,
    private val deviceKeyStore: DeviceKeyStore,
    private val imapCredentialStore: ImapCredentialStore,
    private val oauthCredentialStore: OAuthCredentialStore,
    private val amplitudeAnalytics: AmplitudeProductAnalyticsClient,
    private val observability: ObservabilityClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : AuthFailureSessionInvalidator {

    override suspend fun invalidate() {
        withContext(ioDispatcher) {
            runStep("cancelAllWorkers") { workScheduler.cancelAll() }
            runStep("stopContentObservers") { contentObserverBootstrap.stop() }
            runStep("imapCredentialClear") { imapCredentialStore.clearAll() }
            runStep("googleOAuthCleanup") { oauthCredentialStore.clearGoogle() }
            runStep("sessionStoreClear") { sessionStore.clear() }
            runStep("tokenProviderInvalidate") { tokenProvider.invalidate() }
            runStep("deviceKeyClear") { deviceKeyStore.clear() }
            runStep("currentUserIdClear") { userPrefsStore.setCurrentUserId(null) }
            runStep("analyticsScopeReset") {
                amplitudeAnalytics.resetUserScope()
                observability.setUserScope(null)
            }
        }
        logger.w(TAG, "collapsed local auth state after permanent auth failure")
    }

    private suspend fun runStep(name: String, block: suspend () -> Unit) {
        try {
            block()
            logger.d(TAG, "$name completed")
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            logger.e(TAG, "$name failed during permanent auth cleanup", e)
        }
    }
}
