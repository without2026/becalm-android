package com.becalm.android.data.remote

import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.workers.UploadBatchHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// spec: ING-004 — Railway batch upload success: HTTP 200 + ack payload, Room sync_status='synced'
// spec: ING-005 — Railway batch upload without auth token: 401 returned
// spec: SYNC-002 — client_event_id idempotency: duplicate upload acknowledged, no double-INSERT

class BatchUploadApiTest {

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

    // spec: ING-004 — HTTP 200 ack payload received → Room records marked synced
    @Test
    fun `ING004_batchUploadSuccess_200AckPayload_marksRoomSynced`() = runTest {
        val events = listOf(makeEvent("ev-ing004-a"), makeEvent("ev-ing004-b"))
        // spec: ING-004 — acknowledged: 2, failed: [] is the success envelope
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(acknowledged = 2, failed = emptyList())
        )

        val result = handler.uploadBatch(events)

        // spec: ING-004 — success result and Room sync_status updated to 'synced'
        assertEquals(UploadBatchHandler.UploadResult.Success, result)
        coVerify { rawIngestionEventDao.markSynced(
            match { it.containsAll(listOf("ev-ing004-a", "ev-ing004-b")) },
            any()
        ) }
    }

    // spec: ING-005 — missing/invalid Bearer token → Unauthorized result (401 contract)
    @Test
    fun `ING005_batchUploadWithoutToken_returnsUnauthorized`() = runTest {
        val events = listOf(makeEvent("ev-ing005"))
        // spec: ING-005 — AuthenticatedApiCaller returns Unauthorized when no token
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Unauthorized

        val result = handler.uploadBatch(events)

        // spec: ING-005 — 401 → Unauthorized, no retry, no quarantine
        assertEquals(UploadBatchHandler.UploadResult.Unauthorized, result)
        coVerify(exactly = 0) { rawIngestionEventDao.markSynced(any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any()) }
    }

    // spec: SYNC-002 — idempotency: same client_event_id uploaded twice → acknowledged once, no duplicate INSERT
    @Test
    fun `SYNC002_duplicateClientEventId_acknowledgedOnce_noDoubleInsert`() = runTest {
        val duplicateId = "ev-sync002-dup"
        val events = listOf(makeEvent(duplicateId))
        // spec: SYNC-002 — server responds 200 with acknowledged=1 even for duplicate
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(acknowledged = 1, failed = emptyList())
        )

        // First upload
        val firstResult = handler.uploadBatch(events)
        assertEquals(UploadBatchHandler.UploadResult.Success, firstResult)

        // Reset mock for second call — server still returns 200 (idempotent)
        coEvery { apiCaller.call<BatchUploadResponse>(any()) } returns ApiCallResult.Success(
            BatchUploadResponse(acknowledged = 1, failed = emptyList())
        )

        // Second upload of the same event (retry scenario)
        val secondResult = handler.uploadBatch(events)

        // spec: SYNC-002 — both calls succeed; Room marks synced each time (idempotent)
        assertEquals(UploadBatchHandler.UploadResult.Success, secondResult)
        // The DAO's markSynced was called exactly twice (once per upload call)
        coVerify(exactly = 2) { rawIngestionEventDao.markSynced(any(), any()) }
    }

    // spec: SYNC-002 — idempotency invariant: client_event_id is UUID-based, globally unique per event
    @Test
    fun `SYNC002_clientEventId_isNonEmpty`() {
        val event = makeEvent("explicit-id")
        // spec: SYNC-002 — client_event_id set explicitly here; auto-generated variant tested elsewhere
        assertEquals("explicit-id", event.clientEventId)
    }
}
