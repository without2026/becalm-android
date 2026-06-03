package com.becalm.android.data.repository

import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.remote.api.RailwayApi
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SourceSyncJobPollerSpecTest {

    @Test
    fun `retryable http poll failure keeps running job pending instead of failed`() = runTest {
        val api = mockk<RailwayApi>()
        coEvery { api.getSourceSyncJob("job-1") } returns Response.error(
            503,
            """{"error":"source_sync_status_unavailable"}""".toResponseBody("application/json".toMediaType()),
        )

        val result = poller(api).awaitTerminal("gmail", runningSnapshot())

        assertTrue(result is SourceSyncJobPollResult.Pending)
        val pending = result as SourceSyncJobPollResult.Pending
        assertEquals("source_sync_status_unavailable", pending.reasonCode)
        assertEquals(2, pending.synced)
    }

    @Test
    fun `network exception while polling keeps running job pending instead of failed`() = runTest {
        val api = mockk<RailwayApi>()
        coEvery { api.getSourceSyncJob("job-1") } throws IOException("Broken pipe")

        val result = poller(api).awaitTerminal("gmail", runningSnapshot())

        assertTrue(result is SourceSyncJobPollResult.Pending)
        val pending = result as SourceSyncJobPollResult.Pending
        assertEquals("source_sync_status_unavailable", pending.reasonCode)
        assertEquals(2, pending.synced)
    }

    @Test
    fun `non retryable poll failure still fails terminally`() = runTest {
        val api = mockk<RailwayApi>()
        coEvery { api.getSourceSyncJob("job-1") } returns Response.error(
            400,
            """{"error":"bad_request"}""".toResponseBody("application/json".toMediaType()),
        )

        val result = poller(api).awaitTerminal("gmail", runningSnapshot())

        assertTrue(result is SourceSyncJobPollResult.Failed)
        val failed = result as SourceSyncJobPollResult.Failed
        assertEquals("HTTP 400", failed.message)
        assertEquals(false, failed.retryable)
    }

    private fun poller(api: RailwayApi): SourceSyncJobPoller =
        SourceSyncJobPoller(
            api = api,
            logger = RecordingLogger(),
            maxPollAttempts = 2,
            delayMillis = {},
        )

    private fun runningSnapshot(): SourceSyncJobSnapshot =
        SourceSyncJobSnapshot(
            jobId = "job-1",
            status = "running",
            accepted = true,
            retryAfterSeconds = 1,
            synced = 2,
        )
}
