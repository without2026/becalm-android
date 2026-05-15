package com.becalm.android.unit.ui.onboarding

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
    private val connector = CalendarOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
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
    }
}
