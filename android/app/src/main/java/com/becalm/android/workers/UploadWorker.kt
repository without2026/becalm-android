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
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// spec: ING-002 — WorkManager UploadWorker — primary upload path
// spec: ING-003 — HTTP response differential handling
// spec: ING-014 — 413: chunk events to 50 and retry
// spec: SYNC-001 — batch upload pending records
// spec: SYNC-002 — idempotency (server-side by client_event_id)
// spec: SYNC-003 — 5xx → retry_count++, exponential backoff
// spec: SYNC-005 — 429: Retry-After header
// spec: SYNC-006 — enqueueUniqueWork('sync-all-upload', REPLACE)
// Business logic lives in UploadBatchHandler for unit testability

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
        val handler = UploadBatchHandler(rawIngestionEventDao, api, apiCaller)
        return when (handler.execute()) {
            UploadBatchHandler.UploadResult.Success -> Result.success()
            UploadBatchHandler.UploadResult.Empty -> Result.success()
            UploadBatchHandler.UploadResult.Retry -> Result.retry()
            UploadBatchHandler.UploadResult.Failure -> Result.failure()
            UploadBatchHandler.UploadResult.Unauthorized -> Result.failure()
        }
    }
}
