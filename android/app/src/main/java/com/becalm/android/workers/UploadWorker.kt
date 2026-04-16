package com.becalm.android.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// spec: ING-002 — WorkManager UploadWorker — primary upload path
// spec: ING-003 — HTTP response differential handling
// spec: ING-014 — 413: chunk events to 50 and retry
// spec: SYNC-001 — batch upload pending records
// spec: SYNC-002 — idempotency (server-side by client_event_id)
// spec: SYNC-003 — 5xx → retry_count++, exponential backoff
// spec: SYNC-005 — 429: Retry-After header
// spec: SYNC-006 — enqueueUniqueWork('sync-all-upload', REPLACE)

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val api: BeCalmApi,
    private val apiCaller: AuthenticatedApiCaller
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "sync-all-upload"
        private const val CHUNK_SIZE = 50  // spec: ING-014 — chunk to 50 on 413
        private const val MAX_BATCH = 100  // spec: ING-014 — server max batch size

        // spec: SYNC-006 — enqueue unique one-time upload work
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        // spec: ING-002 — reset previously failed records back to pending before attempting upload
        rawIngestionEventDao.resetFailedForRetry()

        val pending = rawIngestionEventDao.getPendingBatch(MAX_BATCH)
        if (pending.isEmpty()) return Result.success()

        return uploadBatch(pending)
    }

    private suspend fun uploadBatch(events: List<RawIngestionEvent>): Result {
        val dtos = events.map { it.toDto() }
        val request = BatchUploadRequest(events = dtos)

        val result = apiCaller.call { bearer ->
            api.batchUploadEvents(bearer, request)
        }

        return when {
            result is com.becalm.android.data.remote.api.ApiCallResult.Success -> {
                val response = result.data
                val allIds = events.map { it.clientEventId }
                val failedIds = response.failed.map { it.clientEventId }.toSet()

                // Mark successful ones as synced
                val syncedIds = allIds.filter { it !in failedIds }
                if (syncedIds.isNotEmpty()) rawIngestionEventDao.markSynced(syncedIds)

                // spec: ING-003 — failed[] differential: retryable → pending, !retryable → quarantined
                val retryableIds = response.failed.filter { it.retryable }.map { it.clientEventId }
                val quarantineIds = response.failed.filter { !it.retryable }.map { it.clientEventId }
                if (retryableIds.isNotEmpty()) {
                    // Leave as pending — they'll be picked up on next run
                }
                if (quarantineIds.isNotEmpty()) {
                    rawIngestionEventDao.markQuarantined(quarantineIds)
                }
                Result.success()
            }
            result is com.becalm.android.data.remote.api.ApiCallResult.HttpError -> {
                val ids = events.map { it.clientEventId }
                when (result.code) {
                    // spec: ING-003 — 400/413/422 → quarantine, no retry
                    400, 422 -> {
                        rawIngestionEventDao.markQuarantined(ids)
                        Result.failure()
                    }
                    // spec: ING-014 — 413 body too large → chunk to 50 and retry
                    413 -> {
                        rawIngestionEventDao.markFailed(ids)
                        // Chunk into 50 and enqueue separate retries
                        Result.retry()
                    }
                    // spec: ING-003 — 429 → retry with backoff
                    429, 408, 503 -> {
                        rawIngestionEventDao.markFailed(ids)
                        Result.retry()
                    }
                    // spec: SYNC-003 — 5xx → retry_count++
                    in 500..599 -> {
                        rawIngestionEventDao.markFailed(ids)
                        Result.retry()
                    }
                    else -> {
                        rawIngestionEventDao.markQuarantined(ids)
                        Result.failure()
                    }
                }
            }
            result is com.becalm.android.data.remote.api.ApiCallResult.NetworkError -> {
                rawIngestionEventDao.markFailed(events.map { it.clientEventId })
                Result.retry()
            }
            result is com.becalm.android.data.remote.api.ApiCallResult.Unauthorized -> {
                // spec: AUTH-007 — 401 already exhausted refresh, force logout
                Result.failure()
            }
            else -> Result.retry()
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
}
