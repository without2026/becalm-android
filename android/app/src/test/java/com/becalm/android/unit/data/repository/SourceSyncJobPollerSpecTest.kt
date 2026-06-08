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

    @Test
    fun `llm processing retry stays pending with safe backend message`() = runTest {
        val api = mockk<RailwayApi>(relaxed = true)

        val result = poller(api).awaitTerminal(
            "gmail",
            SourceSyncJobSnapshot(
                jobId = "job-1",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 20,
                synced = 0,
                errorCode = "llm_processing_retrying",
                errorMessage = "Vertex AI returned 403: permission denied",
                stage = "retry_waiting",
                progress = 0.55,
                message = "LLM processing is temporarily unavailable; retrying automatically",
            ),
        )

        assertTrue(result is SourceSyncJobPollResult.Pending)
        val pending = result as SourceSyncJobPollResult.Pending
        assertEquals("llm_processing_retrying", pending.reasonCode)
        assertEquals("LLM processing is temporarily unavailable; retrying automatically", pending.message)
        assertEquals(20L, pending.retryAfterSeconds)
    }

    @Test
    fun `terminal llm processing failure uses message code instead of raw backend body`() = runTest {
        val api = mockk<RailwayApi>(relaxed = true)

        val result = poller(api).awaitTerminal(
            "gmail",
            SourceSyncJobSnapshot(
                jobId = "job-1",
                status = "failed",
                accepted = false,
                retryAfterSeconds = null,
                synced = 0,
                errorCode = "llm_processing_failed",
                errorMessage = "Vertex AI returned 403: permission denied for internal project",
            ),
        )

        assertTrue(result is SourceSyncJobPollResult.Failed)
        val failed = result as SourceSyncJobPollResult.Failed
        assertEquals(ProcessingStatusMessages.LLM_PROCESSING_FAILED, failed.message)
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
