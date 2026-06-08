package com.becalm.android.unit.ui.onboarding

import android.app.Activity
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarOAuthStartResponse
import com.becalm.android.data.remote.dto.CalendarOAuthStatusResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.CalendarOAuthConnector
import com.becalm.android.ui.onboarding.CalendarOAuthProvider
import com.becalm.android.ui.onboarding.CalendarOAuthResult
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

class CalendarOAuthConnectorSpecTest {

    private val api: RailwayApi = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()
    private val browserLauncher: OAuthBrowserLauncher = mockk(relaxed = true)
    private val connector = CalendarOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
        productAnalytics = productAnalytics,
        browserLauncher = browserLauncher,
    )

    @Test
    fun `calendar oauth start launches trusted browser launcher`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startCalendarOAuth(SourceType.GOOGLE_CALENDAR, null) } returns Response.success(
            CalendarOAuthStartResponse(
                provider = SourceType.GOOGLE_CALENDAR,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/calendar/google_calendar:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(CalendarOAuthProvider.GOOGLE_CALENDAR, activity)

        assertEquals(CalendarOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startCalendarOAuth(SourceType.GOOGLE_CALENDAR, null) }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
        val openedEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_BROWSER_OPENED
        }
        assertEquals(SourceType.GOOGLE_CALENDAR, openedEvent.properties["source_type"])
    }

    @Test
    fun `calendar oauth start fails when trusted browser is unavailable`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startCalendarOAuth(SourceType.GOOGLE_CALENDAR, null) } returns Response.success(
            CalendarOAuthStartResponse(
                provider = SourceType.GOOGLE_CALENDAR,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/calendar/google_calendar:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns OAuthBrowserLaunchResult.Unavailable

        val result = connector.startSignIn(CalendarOAuthProvider.GOOGLE_CALENDAR, activity)

        assertEquals(CalendarOAuthResult.Failed("browser_unavailable"), result)
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
        val statusEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED
        }
        assertEquals("browser_unavailable", statusEvent.properties["result"])
    }

    @Test
    fun `calendar oauth start passes scoped source connection id`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        coEvery { api.startCalendarOAuth(SourceType.GOOGLE_CALENDAR, "conn-calendar-1") } returns Response.success(
            CalendarOAuthStartResponse(
                provider = SourceType.GOOGLE_CALENDAR,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/calendar/google_calendar:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(
            provider = CalendarOAuthProvider.GOOGLE_CALENDAR,
            activity = activity,
            sourceConnectionId = "conn-calendar-1",
        )

        assertEquals(CalendarOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startCalendarOAuth(SourceType.GOOGLE_CALENDAR, "conn-calendar-1") }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
    }

    @Test
    fun `outlook calendar oauth start passes scoped source connection id`() = runTest {
        val activity: Activity = mockk(relaxed = true)
        val authorizationUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=test"
        coEvery { api.startCalendarOAuth(SourceType.OUTLOOK_CALENDAR, "conn-outlook-calendar-1") } returns Response.success(
            CalendarOAuthStartResponse(
                provider = SourceType.OUTLOOK_CALENDAR,
                authorizationUrl = authorizationUrl,
                redirectUri = "https://dev-dev-7309.up.railway.app/v1/oauth/calendar/outlook_calendar:callback",
                state = "state",
            ),
        )
        every { browserLauncher.launch(activity, authorizationUrl) } returns
            OAuthBrowserLaunchResult.Launched(packageName = "com.android.chrome", customTabs = true)

        val result = connector.startSignIn(
            provider = CalendarOAuthProvider.OUTLOOK_CALENDAR,
            activity = activity,
            sourceConnectionId = "conn-outlook-calendar-1",
        )

        assertEquals(CalendarOAuthResult.NotConnected, result)
        coVerify(exactly = 1) { api.startCalendarOAuth(SourceType.OUTLOOK_CALENDAR, "conn-outlook-calendar-1") }
        verify(exactly = 1) { browserLauncher.launch(activity, authorizationUrl) }
    }

    @Test
    fun `calendar oauth status refresh returns connected without blocking on initial calendar sync`() = runTest {
        coEvery { api.getCalendarOAuthStatus(SourceType.GOOGLE_CALENDAR) } returns Response.success(
            CalendarOAuthStatusResponse(
                provider = SourceType.GOOGLE_CALENDAR,
                connected = true,
                accountEmail = "tester@example.com",
                displayName = "Tester",
            ),
        )

        val result = connector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)

        assertEquals(CalendarOAuthResult.Connected, result)
        coVerify(exactly = 1) { api.getCalendarOAuthStatus(SourceType.GOOGLE_CALENDAR) }
        coVerify(exactly = 0) { api.syncCalendarEvents() }
        val statusEvent = productAnalytics.events.single {
            it.eventName == ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED
        }
        assertEquals(SourceType.GOOGLE_CALENDAR, statusEvent.properties["source_type"])
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
