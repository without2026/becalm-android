package com.becalm.android.workers

import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// spec: ING-002 — extracted from UploadWorker for unit-testability
// spec: ING-003 — HTTP response differential handling
// spec: ING-014 — 413 → chunk + retry
// spec: SYNC-001 — batch upload pending records
// spec: SYNC-003 — 5xx → retry_count++
class UploadBatchHandler(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val api: BeCalmApi,
    private val apiCaller: AuthenticatedApiCaller
) {
    // Returns UploadResult — Worker maps this to WorkManager Result
    sealed class UploadResult {
        object Success : UploadResult()
        object Retry : UploadResult()
        object Failure : UploadResult()
        object Unauthorized : UploadResult()
        object Empty : UploadResult()
    }

    // spec: ING-002 — reset failed → pending, then upload next batch
    suspend fun execute(maxBatch: Int = MAX_BATCH): UploadResult {
        rawIngestionEventDao.resetFailedForRetry()
        val pending = rawIngestionEventDao.getPendingBatch(limit = maxBatch)
        if (pending.isEmpty()) return UploadResult.Empty
        return uploadBatch(pending)
    }

    suspend fun uploadBatch(events: List<RawIngestionEvent>): UploadResult {
        val request = BatchUploadRequest(events = events.map { it.toDto() })
        val result = apiCaller.call { bearer -> api.batchUploadEvents(bearer, request) }
        val ids = events.map { it.clientEventId }

        return when (result) {
            is ApiCallResult.Success -> {
                val response = result.data
                val failedIds = response.failed.map { it.clientEventId }.toSet()
                val syncedIds = ids.filter { it !in failedIds }
                if (syncedIds.isNotEmpty()) rawIngestionEventDao.markSynced(syncedIds)

                // spec: ING-003 — retryable:true → leave as pending; retryable:false → quarantine
                val quarantineIds = response.failed.filter { !it.retryable }.map { it.clientEventId }
                if (quarantineIds.isNotEmpty()) rawIngestionEventDao.markQuarantined(quarantineIds)

                UploadResult.Success
            }
            is ApiCallResult.HttpError -> when (result.code) {
                // spec: ING-003 — 400/422 → quarantine (no retry)
                400, 422 -> {
                    rawIngestionEventDao.markQuarantined(ids)
                    UploadResult.Failure
                }
                // spec: ING-014 — 413 body too large → mark failed, retry with smaller chunk
                413 -> {
                    rawIngestionEventDao.markFailed(ids)
                    UploadResult.Retry
                }
                // spec: ING-003, SYNC-005 — 429/408/503 → retry with backoff
                429, 408, 503 -> {
                    rawIngestionEventDao.markFailed(ids)
                    UploadResult.Retry
                }
                // spec: SYNC-003 — 5xx → retry_count++
                in 500..599 -> {
                    rawIngestionEventDao.markFailed(ids)
                    UploadResult.Retry
                }
                else -> {
                    rawIngestionEventDao.markQuarantined(ids)
                    UploadResult.Failure
                }
            }
            is ApiCallResult.NetworkError -> {
                rawIngestionEventDao.markFailed(ids)
                UploadResult.Retry
            }
            is ApiCallResult.Unauthorized -> {
                // spec: AUTH-007 — 401 exhausted refresh → force logout
                UploadResult.Unauthorized
            }
            else -> UploadResult.Retry
        }
    }

    private fun RawIngestionEvent.toDto(): RawIngestionEventDto {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return RawIngestionEventDto(
            clientEventId = clientEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personRef = personRef,
            eventTitle = eventTitle,
            eventSnippet = eventSnippet,
            durationSeconds = durationSeconds,
            location = location,
            commitmentsExtractedCount = commitmentsExtractedCount,
            timestamp = sdf.format(Date(timestamp))
        )
    }

    companion object {
        const val MAX_BATCH = 100
        const val CHUNK_SIZE = 50  // spec: ING-014
    }
}
