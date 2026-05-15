package com.becalm.android.unit.ui.onboarding

import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.MailOAuthStatusResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.EmailOAuthConnector
import com.becalm.android.ui.onboarding.EmailOAuthProvider
import com.becalm.android.ui.onboarding.EmailOAuthResult
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class EmailOAuthConnectorSpecTest {

    private val api: RailwayApi = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()
    private val connector = EmailOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
        productAnalytics = productAnalytics,
    )

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
        coVerify(exactly = 0) { api.syncMailSource(any()) }
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
