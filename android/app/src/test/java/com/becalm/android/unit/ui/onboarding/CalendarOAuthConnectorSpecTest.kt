package com.becalm.android.unit.ui.onboarding

import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarOAuthStatusResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.CalendarOAuthConnector
import com.becalm.android.ui.onboarding.CalendarOAuthProvider
import com.becalm.android.ui.onboarding.CalendarOAuthResult
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class CalendarOAuthConnectorSpecTest {

    private val api: RailwayApi = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()
    private val connector = CalendarOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
        productAnalytics = productAnalytics,
    )

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
