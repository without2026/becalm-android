package com.becalm.android.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.workDataOf
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.extraction.CommitmentExtractionWorker
import com.becalm.android.worker.ingestion.GoogleCalendarWorker
import com.becalm.android.worker.ingestion.ImapDaumWorker
import com.becalm.android.worker.ingestion.ImapNaverWorker
import com.becalm.android.worker.ingestion.MediaStoreWorker
import com.becalm.android.worker.ingestion.OutlookCalendarWorker
import java.util.concurrent.TimeUnit

internal data class SourceWorkSpec(
    val workerClass: Class<out ListenableWorker>,
    val uniqueKey: String,
)

internal object WorkSchedulerRequests {
    const val PERIODIC_INTERVAL_MINUTES: Long = 15L
    const val BACKOFF_DELAY_SECONDS: Long = 30L
    const val TAG_VOICE_UPLOAD: String = "voice_upload"

    val uploadConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val retentionConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .build()

    val overdueConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .build()

    val coldSyncStage2Constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun resolveSource(sourceKey: String): SourceWorkSpec? =
        when (sourceKey) {
            SourceType.NAVER_IMAP -> SourceWorkSpec(ImapNaverWorker::class.java, UniqueWorkKeys.NAVER_IMAP)
            SourceType.DAUM_IMAP -> SourceWorkSpec(ImapDaumWorker::class.java, UniqueWorkKeys.DAUM_IMAP)
            SourceType.GOOGLE_CALENDAR -> SourceWorkSpec(GoogleCalendarWorker::class.java, UniqueWorkKeys.GCAL)
            SourceType.OUTLOOK_CALENDAR -> SourceWorkSpec(OutlookCalendarWorker::class.java, UniqueWorkKeys.OUTLOOK_CAL)
            else -> null
        }

    fun <W : ListenableWorker> oneTimeExpedited(
        workerClass: Class<W>,
        constraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build(),
        lookbackDays: Int? = null,
    ): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(workerClass).apply {
            setConstraints(constraints)
            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            if (lookbackDays != null) {
                setInputData(workDataOf(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS to lookbackDays))
            }
        }.build()

    fun <W : ListenableWorker> periodicRequest(
        workerClass: Class<W>,
        constraints: Constraints,
    ): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(workerClass, PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

    fun uploadRequest(attempt: Int): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(UploadWorker::class.java)
            .setConstraints(uploadConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(UploadWorker.INPUT_KEY_ATTEMPT to attempt))
            .build()

    fun voiceUploadRequest(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int,
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(VoiceUploadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    VoiceUploadWorker.KEY_RAW_EVENT_ID to rawEventId,
                    VoiceUploadWorker.KEY_AUDIO_URI to audioUri,
                    VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT to rateLimitedAttempt,
                ),
            )
            .addTag(TAG_VOICE_UPLOAD)
        if (initialDelaySec > 0L) {
            builder.setInitialDelay(initialDelaySec, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun commitmentExtractionRequest(rawEventId: String): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(CommitmentExtractionWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    CommitmentExtractionWorker.KEY_RAW_EVENT_ID to rawEventId,
                ),
            )
            .build()

    fun allStaticKeys(): List<String> = listOf(
        UniqueWorkKeys.MEDIA_STORE,
        UniqueWorkKeys.NAVER_IMAP,
        UniqueWorkKeys.DAUM_IMAP,
        UniqueWorkKeys.GCAL,
        UniqueWorkKeys.OUTLOOK_CAL,
        UniqueWorkKeys.ENRICHMENT,
        UniqueWorkKeys.UPLOAD,
        UniqueWorkKeys.UPLOAD_PERIODIC,
        UniqueWorkKeys.ENRICHMENT_PERIODIC,
        UniqueWorkKeys.RETENTION_SWEEP,
        UniqueWorkKeys.OVERDUE_SWEEP,
        UniqueWorkKeys.COLD_SYNC_STAGE1_DEFERRED,
        UniqueWorkKeys.COLD_SYNC_STAGE2,
    )

    fun uploadPlan(attempt: Int): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.UPLOAD,
            policy = ExistingWorkPolicy.REPLACE,
            request = uploadRequest(attempt),
            logMessage = "enqueueUpload attempt=$attempt key=${UniqueWorkKeys.UPLOAD}",
        )

    fun uploadPeriodicPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.UPLOAD_PERIODIC,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            request = PeriodicWorkRequest.Builder(
                UploadWorker::class.java,
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).setConstraints(uploadConstraints)
                .setInputData(workDataOf(UploadWorker.INPUT_KEY_ATTEMPT to 0))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build(),
            logMessage = "scheduleUploadRedundancy key=${UniqueWorkKeys.UPLOAD_PERIODIC}",
        )

    fun enrichmentPeriodicPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.ENRICHMENT_PERIODIC,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            request = PeriodicWorkRequest.Builder(EnrichmentWorker::class.java, 1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build(),
            logMessage = "scheduleEnrichmentSweep key=${UniqueWorkKeys.ENRICHMENT_PERIODIC}",
        )

    fun cancelEnrichmentPeriodicPlan(): CancelUniqueWorkPlan =
        CancelUniqueWorkPlan(
            uniqueKey = UniqueWorkKeys.ENRICHMENT_PERIODIC,
            logMessage = "cancelEnrichmentSweep key=${UniqueWorkKeys.ENRICHMENT_PERIODIC}",
        )

    fun retentionSweepPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.RETENTION_SWEEP,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            request = PeriodicWorkRequest.Builder(RetentionSweepWorker::class.java, 1, TimeUnit.DAYS)
                .setConstraints(retentionConstraints)
                .build(),
            logMessage = "scheduleRetentionSweep key=${UniqueWorkKeys.RETENTION_SWEEP}",
        )

    fun overdueSweepPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.OVERDUE_SWEEP,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            request = PeriodicWorkRequest.Builder(OverdueSweepWorker::class.java, 6, TimeUnit.HOURS)
                .setConstraints(overdueConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build(),
            logMessage = "scheduleOverdueSweep key=${UniqueWorkKeys.OVERDUE_SWEEP}",
        )

    fun deferredColdSyncStage1Plan(): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.COLD_SYNC_STAGE1_DEFERRED,
            policy = ExistingWorkPolicy.REPLACE,
            request = oneTimeExpedited(ColdSyncStage1DeferredWorker::class.java),
            logMessage = "enqueueDeferredColdSyncStage1 key=${UniqueWorkKeys.COLD_SYNC_STAGE1_DEFERRED}",
        )

    fun coldSyncStage2Plan(): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.COLD_SYNC_STAGE2,
            policy = ExistingWorkPolicy.REPLACE,
            request = oneTimeExpedited(
                workerClass = ColdSyncStage2Worker::class.java,
                constraints = coldSyncStage2Constraints,
            ),
            logMessage = "enqueueColdSyncStage2 key=${UniqueWorkKeys.COLD_SYNC_STAGE2}",
        )

    fun cancelColdSyncStage2Plan(): CancelUniqueWorkPlan =
        CancelUniqueWorkPlan(
            uniqueKey = UniqueWorkKeys.COLD_SYNC_STAGE2,
            logMessage = "cancelColdSyncStage2 key=${UniqueWorkKeys.COLD_SYNC_STAGE2}",
        )
}
