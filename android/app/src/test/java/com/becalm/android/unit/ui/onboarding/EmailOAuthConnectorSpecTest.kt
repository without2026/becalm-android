package com.becalm.android.unit.ui.onboarding

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
    private val connector = EmailOAuthConnector(
        railwayApiProvider = Provider { api },
        moshi = Moshi.Builder().build(),
        logger = logger,
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
    }
}
