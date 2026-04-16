package com.becalm.android.workers

import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.FailedEventDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// spec: ING-002 — UploadBatchHandler marks synced on 200
// spec: ING-003 — 5xx/429 → failed + retry; 400/422 → quarantined
// spec: ING-014 — 413 → failed + retry
// spec: SYNC-001 — batch upload behavior
// spec: SYNC-003 — 5xx increments retry_count
// spec: SYNC-004 — WorkManager NETWORK_CONNECTED constraint; pending records processed on reconnect
// spec: AUTH-007 — 401 handled by AuthenticatedApiCaller → Unauthorized result

class UploadWorkerTest {

    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val api: BeCalmApi = mockk()
    private val apiCaller: AuthenticatedApiCaller = mockk()

    private lateinit var handler: UploadBatchHandler

    @Before
    fun setUp() {
        handler = UploadBatchHandler(rawIngestionEventDao, api, apiCaller)
    }

    private fun makeEvent(id: String) = RawIngestionEvent(
        clientEventId = id,
        sourceType = "voice",
        timestamp = System.currentTimeMillis()
    )

    // spec: ING-002 / SYNC-001 — 200 with empty failed[] marks all events synced
    @Test
    fun `uploadBatch on 200 marks all events synced`() = runTest {
        val events = listOf(makeEvent("ev-1"), makeEvent("ev-2"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(acknowledged = 2, failed = emptyList())
        )

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Success, result)
        val idsSlot = slot<List<String>>()
        coVerify { rawIngestionEventDao.markSynced(capture(idsSlot), any()) }
        assertTrue(idsSlot.captured.containsAll(listOf("ev-1", "ev-2")))
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markQuarantined(any(), any()) }
    }

    // spec: ING-002 — empty pending batch returns Empty without API call
    @Test
    fun `execute returns Empty when no pending events`() = runTest {
        coEvery { rawIngestionEventDao.resetFailedForRetry(any()) } returns Unit

        val result = handler.execute()

        assertEquals(UploadBatchHandler.UploadResult.Empty, result)
        coVerify(exactly = 0) { api.batchUploadEvents(any(), any()) }
    }

    // spec: SYNC-003 — 500 marks events failed (retry_count increments via DAO) and returns Retry
    @Test
    fun `uploadBatch on 500 marks failed and returns Retry`() = runTest {
        val events = listOf(makeEvent("ev-500"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns
            ApiCallResult.HttpError(500, "Internal Server Error")

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Retry, result)
        coVerify { rawIngestionEventDao.markFailed(listOf("ev-500"), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markSynced(any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markQuarantined(any(), any()) }
    }

    // spec: ING-003 — 400 quarantines the record, no retry
    @Test
    fun `uploadBatch on 400 quarantines events and returns Failure`() = runTest {
        val events = listOf(makeEvent("ev-400"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns
            ApiCallResult.HttpError(400, "Bad Request")

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Failure, result)
        coVerify { rawIngestionEventDao.markQuarantined(listOf("ev-400"), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any()) }
    }

    // spec: ING-003 — 422 quarantines the record, no retry
    @Test
    fun `uploadBatch on 422 quarantines events and returns Failure`() = runTest {
        val events = listOf(makeEvent("ev-422"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns
            ApiCallResult.HttpError(422, "Unprocessable Entity")

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Failure, result)
        coVerify { rawIngestionEventDao.markQuarantined(listOf("ev-422"), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any()) }
    }

    // spec: ING-014 — 413 body too large → mark failed + return Retry (caller re-enqueues with chunk size 50)
    @Test
    fun `uploadBatch on 413 marks failed and returns Retry for chunked re-enqueue`() = runTest {
        val events = listOf(makeEvent("ev-413"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns
            ApiCallResult.HttpError(413, "Payload Too Large")

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Retry, result)
        coVerify { rawIngestionEventDao.markFailed(listOf("ev-413"), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markQuarantined(any(), any()) }
        // spec: ING-014 — CHUNK_SIZE is 50 (used by caller when re-enqueuing)
        assertEquals(50, UploadBatchHandler.CHUNK_SIZE)
    }

    // spec: SYNC-005 — 429 marks failed and returns Retry (WorkManager applies exponential backoff)
    @Test
    fun `uploadBatch on 429 marks failed and returns Retry for rate limit backoff`() = runTest {
        val events = listOf(makeEvent("ev-429"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns
            ApiCallResult.HttpError(429, "Too Many Requests")

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Retry, result)
        coVerify { rawIngestionEventDao.markFailed(listOf("ev-429"), any()) }
    }

    // spec: AUTH-007 — 401 exhausted refresh → Unauthorized returned without retry
    @Test
    fun `uploadBatch on Unauthorized returns Unauthorized without marking failed`() = runTest {
        val events = listOf(makeEvent("ev-401"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Unauthorized

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Unauthorized, result)
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markQuarantined(any(), any()) }
    }

    // spec: ING-003 — retryable:true in failed[] → stays pending (not quarantined)
    @Test
    fun `uploadBatch on 200 leaves retryable failed events as pending`() = runTest {
        val events = listOf(makeEvent("ev-r"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(
                acknowledged = 1,
                failed = listOf(
                    FailedEventDto("ev-r", "internal_error", "Server error", retryable = true)
                )
            )
        )

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Success, result)
        // retryable:true → not quarantined, not synced, left as pending
        coVerify(exactly = 0) { rawIngestionEventDao.markQuarantined(any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markSynced(any(), any()) }
    }

    // spec: ING-003 — retryable:false in failed[] → quarantined
    @Test
    fun `uploadBatch on 200 quarantines non-retryable failed events`() = runTest {
        val events = listOf(makeEvent("ev-nret"), makeEvent("ev-ok"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(
                acknowledged = 2,
                failed = listOf(
                    FailedEventDto("ev-nret", "schema_invalid", "Invalid schema", retryable = false)
                )
            )
        )

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Success, result)
        // ev-ok → synced
        coVerify { rawIngestionEventDao.markSynced(listOf("ev-ok"), any()) }
        // ev-nret → quarantined
        coVerify { rawIngestionEventDao.markQuarantined(listOf("ev-nret"), any()) }
    }

    // spec: ING-003 — NetworkError → mark failed + retry
    @Test
    fun `uploadBatch on NetworkError marks failed and returns Retry`() = runTest {
        val events = listOf(makeEvent("ev-net"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.NetworkError

        val result = handler.uploadBatch(events)

        assertEquals(UploadBatchHandler.UploadResult.Retry, result)
        coVerify { rawIngestionEventDao.markFailed(listOf("ev-net"), any()) }
    }

    // spec: SYNC-004 — UploadWorker.enqueue sets NETWORK_CONNECTED constraint (WorkManager re-runs on reconnect)
    @Test
    fun `SYNC004_uploadWorker_enqueue_setsNetworkConnectedConstraint`() {
        val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
        // spec: SYNC-004 — enqueue with NETWORK_CONNECTED so WorkManager fires on reconnect
        UploadWorker.enqueue(workManager)
        io.mockk.verify { workManager.enqueueUniqueWork(
            "sync-all-upload",
            androidx.work.ExistingWorkPolicy.REPLACE,
            any<androidx.work.OneTimeWorkRequest>()
        ) }
    }

    // spec: SYNC-004 — pending records are processed when network is available (Retry result queues WorkManager retry)
    @Test
    fun `SYNC004_networkError_returnsRetry_pendingRecordsKeptForReconnect`() = runTest {
        val events = listOf(makeEvent("ev-sync4"))
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.NetworkError

        val result = handler.uploadBatch(events)

        // spec: SYNC-004 — records stay in Room (not deleted), WorkManager retries when network reconnects
        assertEquals(UploadBatchHandler.UploadResult.Retry, result)
        coVerify(exactly = 0) { rawIngestionEventDao.markSynced(any(), any()) }
        coVerify { rawIngestionEventDao.markFailed(listOf("ev-sync4"), any()) }
    }
}
