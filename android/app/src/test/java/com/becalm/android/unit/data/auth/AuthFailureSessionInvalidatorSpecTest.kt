package com.becalm.android.unit.data.auth

import com.becalm.android.core.analytics.AmplitudeProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsAttributionStore
import com.becalm.android.core.analytics.ProductAnalyticsEventQueue
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.auth.AuthFailureSessionInvalidatorImpl
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthFailureSessionInvalidatorSpecTest {

    private val sessionStore: SupabaseSessionStore = mockk(relaxed = true)
    private val tokenProvider: AuthTokenProvider = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val contentObserverBootstrap: ContentObserverBootstrap = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val deviceKeyStore: DeviceKeyStore = mockk(relaxed = true)
    private val imapCredentialStore: ImapCredentialStore = mockk(relaxed = true)
    private val oauthCredentialStore: OAuthCredentialStore = mockk(relaxed = true)
    private val runtimeBootstrap: AuthenticatedRuntimeBootstrap = mockk(relaxed = true)
    private val productAnalyticsEventQueue: ProductAnalyticsEventQueue = mockk(relaxed = true)
    private val productAnalyticsAttributionStore: ProductAnalyticsAttributionStore = mockk(relaxed = true)
    private val amplitudeAnalytics: AmplitudeProductAnalyticsClient = mockk(relaxed = true)
    private val observability: ObservabilityClient = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `permanent auth failure clears every local auth runtime surface`() = runTest {
        invalidator().invalidate()

        verify(exactly = 1) { workScheduler.cancelAll() }
        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 1) { runtimeBootstrap.resetForAuthBoundary() }
        coVerify(exactly = 1) { imapCredentialStore.clearAll() }
        coVerify(exactly = 1) { oauthCredentialStore.clearGoogle() }
        coVerify(exactly = 1) { sessionStore.clear() }
        verify(exactly = 1) { tokenProvider.invalidate() }
        coVerify(exactly = 1) { deviceKeyStore.clear() }
        coVerify(exactly = 1) { productAnalyticsEventQueue.clearAll() }
        verify(exactly = 1) { productAnalyticsAttributionStore.clearNotificationOpen() }
        coVerify(exactly = 1) { userPrefsStore.setCurrentUserId(null) }
        verify(exactly = 1) { amplitudeAnalytics.resetUserScope() }
        verify(exactly = 1) { observability.setUserScope(null) }
    }

    @Test
    fun `permanent auth cleanup attempts remaining steps after an individual failure`() = runTest {
        coEvery { sessionStore.clear() } throws IOException("token store unavailable")
        every { logger.e(any(), any(), any()) } returns Unit

        invalidator().invalidate()

        coVerify(exactly = 1) { sessionStore.clear() }
        verify(exactly = 1) { tokenProvider.invalidate() }
        coVerify(exactly = 1) { deviceKeyStore.clear() }
        coVerify(exactly = 1) { userPrefsStore.setCurrentUserId(null) }
        verify(exactly = 1) { amplitudeAnalytics.resetUserScope() }
        verify(exactly = 1) { observability.setUserScope(null) }
    }

    private fun invalidator(): AuthFailureSessionInvalidatorImpl =
        AuthFailureSessionInvalidatorImpl(
            sessionStore = sessionStore,
            tokenProvider = tokenProvider,
            workScheduler = workScheduler,
            contentObserverBootstrap = contentObserverBootstrap,
            userPrefsStore = userPrefsStore,
            deviceKeyStore = deviceKeyStore,
            imapCredentialStore = imapCredentialStore,
            oauthCredentialStore = oauthCredentialStore,
            runtimeBootstrap = runtimeBootstrap,
            productAnalyticsEventQueue = productAnalyticsEventQueue,
            productAnalyticsAttributionStore = productAnalyticsAttributionStore,
            amplitudeAnalytics = amplitudeAnalytics,
            observability = observability,
            ioDispatcher = Dispatchers.Unconfined,
            logger = logger,
        )
}
