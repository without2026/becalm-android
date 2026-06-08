package com.becalm.android.unit.ui.onboarding

import android.app.Activity
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.MailOAuthStartResponse
import com.becalm.android.data.remote.dto.MailOAuthStatusResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.EmailOAuthConnector
import com.becalm.android.ui.onboarding.EmailOAuthProvider
import com.becalm.android.ui.onboarding.EmailOAuthResult
import com.becalm.android.ui.onboarding.OAuthBrowserLaunchResult
import com.becalm.android.ui.onboarding.OAuthBrowserLauncher
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class EmailOAuthConnectorSpecTest {

    private val api: RailwayApi = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()
    private val browserLauncher: OAuthBrowserLauncher = mockk(relaxed = true)
    private val connector = EmailOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
        productAnalytics = productAnalytics,
        browserLauncher = browserLauncher,
    )

    @Test
    fun `mail oauth start launches trusted browser launcher`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startMailOAuth(SourceType.GMAIL, null) } returns Response.success(
            MailOAuthStartResponse(
                provider = SourceType.GMAIL,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/mail/gmail:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(EmailOAuthProvider.GMAIL, activity)

        assertEquals(EmailOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startMailOAuth(SourceType.GMAIL, null) }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
        val openedEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_BROWSER_OPENED
        }
        assertEquals(SourceType.GMAIL, openedEvent.properties["source_type"])
    }

    @Test
    fun `mail oauth start fails when trusted browser is unavailable`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startMailOAuth(SourceType.GMAIL, null) } returns Response.success(
            MailOAuthStartResponse(
                provider = SourceType.GMAIL,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/mail/gmail:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns OAuthBrowserLaunchResult.Unavailable

        val result = connector.startSignIn(EmailOAuthProvider.GMAIL, activity)

        assertEquals(EmailOAuthResult.Failed("browser_unavailable"), result)
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
        val statusEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED
        }
        assertEquals("browser_unavailable", statusEvent.properties["result"])
    }

    @Test
    fun `mail oauth start passes scoped source connection id`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startMailOAuth(SourceType.GMAIL, "conn-gmail-1") } returns Response.success(
            MailOAuthStartResponse(
                provider = SourceType.GMAIL,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/mail/gmail:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(
            provider = EmailOAuthProvider.GMAIL,
            activity = activity,
            sourceConnectionId = "conn-gmail-1",
        )

        assertEquals(EmailOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startMailOAuth(SourceType.GMAIL, "conn-gmail-1") }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
    }

    @Test
    fun `outlook mail oauth start passes scoped source connection id`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=test"
        coEvery { api.startMailOAuth(SourceType.OUTLOOK_MAIL, "conn-outlook-mail-1") } returns Response.success(
            MailOAuthStartResponse(
                provider = SourceType.OUTLOOK_MAIL,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/mail/outlook_mail:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(
            provider = EmailOAuthProvider.OUTLOOK_MAIL,
            activity = activity,
            sourceConnectionId = "conn-outlook-mail-1",
        )

        assertEquals(EmailOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startMailOAuth(SourceType.OUTLOOK_MAIL, "conn-outlook-mail-1") }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
    }

    @Test
    fun `mail oauth status refresh returns connected without blocking on initial mail sync`() = runTest {
        coEvery { api.getMailOAuthStatus(SourceType.GMAIL) } returns Response.success(
            MailOAuthStatusResponse(
                provider = SourceType.GMAIL,
                connected = true,
                accountEmail = "tester@example.com",
                displayName = "Tester",
            ),
        )

        val result = connector.refreshConnectionStatus(EmailOAuthProvider.GMAIL)

        assertEquals(EmailOAuthResult.Connected, result)
        coVerify(exactly = 1) { api.getMailOAuthStatus(SourceType.GMAIL) }
        coVerify(exactly = 0) { api.syncMailSource(any(), any()) }
        val statusEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED
        }
        assertEquals(SourceType.GMAIL, statusEvent.properties["source_type"])
        assertEquals(true, statusEvent.properties["connected"])
        assertEquals(false, statusEvent.properties.containsKey("account_email"))
    }

    private class RecordingProductAnalyticsClient : ProductAnalyticsClient {
        val events: MutableList<ProductAnalyticsEvent> = mutableListOf()

        override fun track(event: ProductAnalyticsEvent) {
            events += event
        }

        override fun setUserScope(userId: String?) = Unit

        override fun resetUserScope() = Unit
    }
}
