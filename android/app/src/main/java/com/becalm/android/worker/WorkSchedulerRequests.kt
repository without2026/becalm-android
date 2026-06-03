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
import com.becalm.android.worker.ingestion.BackendMailSyncWorker
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
    const val UPLOAD_DEBOUNCE_SECONDS: Long = 10L
    const val TAG_VOICE_UPLOAD: String = "voice_upload"
    const val TAG_MEETING_SPEAKER_PREVIEW: String = "meeting_speaker_preview"
    const val TAG_MESSAGE_SCREENSHOT_UPLOAD: String = "message_screenshot_upload"
    const val TAG_PROFILE_MEMORY: String = "profile_memory"
    const val LEGACY_TAG_COMMITMENT_EXTRACTION: String = "commitment_extraction"

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

    val processDoneConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .build()

    val coldSyncStage2Constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun resolveSource(sourceKey: String): SourceWorkSpec? =
        when (sourceKey) {
            SourceType.VOICE -> SourceWorkSpec(MediaStoreWorker::class.java, UniqueWorkKeys.MEDIA_STORE)
            SourceType.CALL_RECORDING -> SourceWorkSpec(MediaStoreWorker::class.java, UniqueWorkKeys.MEDIA_STORE)
            SourceType.MEETING -> SourceWorkSpec(MediaStoreWorker::class.java, UniqueWorkKeys.MEDIA_STORE)
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

    fun uploadRequest(attempt: Int): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(UploadWorker::class.java)
            .setConstraints(uploadConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(UploadWorker.INPUT_KEY_ATTEMPT to attempt))
        if (attempt == 0) {
            builder.setInitialDelay(UPLOAD_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
        } else {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    fun voiceUploadRequest(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int,
        selfSpeakerId: String? = null,
        speakerMappingsJson: String? = null,
        speakerPreviewId: String? = null,
        extractionJobId: String? = null,
        extractionJobPollAttempt: Int = 0,
    ): OneTimeWorkRequest {
        val input = workDataOf(
            VoiceUploadWorker.KEY_RAW_EVENT_ID to rawEventId,
            VoiceUploadWorker.KEY_AUDIO_URI to audioUri,
            VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT to rateLimitedAttempt,
        ).let { data ->
            androidx.work.Data.Builder()
                .putAll(data)
                .apply {
                    if (!selfSpeakerId.isNullOrBlank()) putString(VoiceUploadWorker.KEY_SELF_SPEAKER_ID, selfSpeakerId)
                    if (!speakerMappingsJson.isNullOrBlank()) putString(VoiceUploadWorker.KEY_SPEAKER_MAPPINGS_JSON, speakerMappingsJson)
                    if (!speakerPreviewId.isNullOrBlank()) putString(VoiceUploadWorker.KEY_SPEAKER_PREVIEW_ID, speakerPreviewId)
                    if (!extractionJobId.isNullOrBlank()) putString(VoiceUploadWorker.KEY_EXTRACTION_JOB_ID, extractionJobId)
                    if (extractionJobPollAttempt > 0) {
                        putInt(VoiceUploadWorker.KEY_EXTRACTION_JOB_POLL_ATTEMPT, extractionJobPollAttempt)
                    }
                }
                .build()
        }
        val builder = OneTimeWorkRequest.Builder(VoiceUploadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (speakerPreviewId.isNullOrBlank() && extractionJobId.isNullOrBlank()) {
                            NetworkType.UNMETERED
                        } else {
                            NetworkType.CONNECTED
                        },
                    )
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(input)
            .addTag(TAG_VOICE_UPLOAD)
        if (initialDelaySec > 0L) {
            builder.setInitialDelay(initialDelaySec, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun messageScreenshotUploadRequest(rawEventId: String): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(MessageScreenshotUploadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(MessageScreenshotUploadWorker.KEY_RAW_EVENT_ID to rawEventId),
            )
            .addTag(TAG_MESSAGE_SCREENSHOT_UPLOAD)
            .build()

    fun meetingSpeakerPreviewRequest(rawEventId: String, audioUri: String): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(MeetingSpeakerPreviewWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    MeetingSpeakerPreviewWorker.KEY_RAW_EVENT_ID to rawEventId,
                    MeetingSpeakerPreviewWorker.KEY_AUDIO_URI to audioUri,
                ),
            )
            .addTag(TAG_MEETING_SPEAKER_PREVIEW)
            .build()

    fun allStaticKeys(): List<String> = listOf(
        UniqueWorkKeys.MEDIA_STORE,
        UniqueWorkKeys.NAVER_IMAP,
        UniqueWorkKeys.DAUM_IMAP,
        UniqueWorkKeys.GCAL,
        UniqueWorkKeys.OUTLOOK_CAL,
        UniqueWorkKeys.BACKEND_MAIL,
        UniqueWorkKeys.PERSON_INDEX,
        UniqueWorkKeys.SOURCE_PARTICIPANT_MIRROR,
        UniqueWorkKeys.sourceRelationRefresh(SourceType.GMAIL),
        UniqueWorkKeys.sourceRelationRefresh(SourceType.OUTLOOK_MAIL),
        UniqueWorkKeys.sourceRelationRefresh(SourceType.GOOGLE_CALENDAR),
        UniqueWorkKeys.sourceRelationRefresh(SourceType.OUTLOOK_CALENDAR),
        UniqueWorkKeys.sourceRelationRefresh(UploadWorker.SOURCE_TYPE),
        UniqueWorkKeys.ENRICHMENT,
        UniqueWorkKeys.UPLOAD,
        UniqueWorkKeys.UPLOAD_PERIODIC,
        UniqueWorkKeys.ENRICHMENT_PERIODIC,
        UniqueWorkKeys.RETENTION_SWEEP,
        UniqueWorkKeys.OVERDUE_SWEEP,
        UniqueWorkKeys.PROCESS_DONE,
        UniqueWorkKeys.PROCESS_DONE_PERIODIC,
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

    fun backendMailPeriodicPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.BACKEND_MAIL,
            policy = ExistingPeriodicWorkPolicy.UPDATE,
            request = periodicRequest(
                BackendMailSyncWorker::class.java,
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ),
            logMessage = "scheduleBackendMailSync key=${UniqueWorkKeys.BACKEND_MAIL}",
        )

    fun personIndexRequest(initialDelaySeconds: Long): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(PersonInteractionIndexWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun personIndexPlan(initialDelaySeconds: Long): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.PERSON_INDEX,
            policy = ExistingWorkPolicy.REPLACE,
            request = personIndexRequest(initialDelaySeconds.coerceAtLeast(0L)),
            logMessage = "enqueuePersonInteractionIndex key=${UniqueWorkKeys.PERSON_INDEX} delaySec=$initialDelaySeconds",
        )

    fun sourceParticipantMirrorRequest(initialDelaySeconds: Long): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(SourceParticipantMirrorWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun sourceParticipantMirrorPlan(initialDelaySeconds: Long): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.SOURCE_PARTICIPANT_MIRROR,
            policy = ExistingWorkPolicy.REPLACE,
            request = sourceParticipantMirrorRequest(initialDelaySeconds.coerceAtLeast(0L)),
            logMessage = "enqueueSourceParticipantMirrorRetry key=${UniqueWorkKeys.SOURCE_PARTICIPANT_MIRROR} delaySec=$initialDelaySeconds",
        )

    fun sourceRelationRefreshRequest(
        sourceType: String,
        initialDelaySeconds: Long,
        resetBeforeRefresh: Boolean,
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(SourceRelationRefreshWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    SourceRelationRefreshWorker.KEY_SOURCE_TYPE to sourceType,
                    SourceRelationRefreshWorker.KEY_RESET_MIRROR_CURSOR to resetBeforeRefresh,
                ),
            )
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun sourceRelationRefreshPlan(
        sourceType: String,
        initialDelaySeconds: Long,
        resetBeforeRefresh: Boolean = false,
    ): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.sourceRelationRefresh(sourceType),
            policy = ExistingWorkPolicy.REPLACE,
            request = sourceRelationRefreshRequest(sourceType, initialDelaySeconds.coerceAtLeast(0L), resetBeforeRefresh),
            logMessage = "enqueueSourceRelationRefresh source=$sourceType key=${UniqueWorkKeys.sourceRelationRefresh(sourceType)} delaySec=$initialDelaySeconds reset=$resetBeforeRefresh",
        )

    fun profileMemoryRequest(personId: String, initialDelaySeconds: Long): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(ProfileMemoryWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(ProfileMemoryWorker.KEY_PERSON_ID to personId))
            .addTag(TAG_PROFILE_MEMORY)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun profileMemoryPlan(personId: String, initialDelaySeconds: Long): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.profileMemory(personId),
            policy = ExistingWorkPolicy.REPLACE,
            request = profileMemoryRequest(
                personId = personId,
                initialDelaySeconds = initialDelaySeconds.coerceAtLeast(0L),
            ),
            logMessage = "enqueueProfileMemory personId=$personId " +
                "key=${UniqueWorkKeys.profileMemory(personId)} delaySec=$initialDelaySeconds",
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

    fun processDoneRequest(initialDelaySeconds: Long): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(ProcessDoneWorker::class.java)
            .setConstraints(processDoneConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    fun processDonePlan(initialDelaySeconds: Long): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.PROCESS_DONE,
            policy = ExistingWorkPolicy.REPLACE,
            request = processDoneRequest(initialDelaySeconds.coerceAtLeast(0L)),
            logMessage = "enqueueProcessDone key=${UniqueWorkKeys.PROCESS_DONE} delaySec=$initialDelaySeconds",
        )

    fun processDoneSweepPlan(): UniquePeriodicWorkPlan =
        UniquePeriodicWorkPlan(
            uniqueKey = UniqueWorkKeys.PROCESS_DONE_PERIODIC,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            request = PeriodicWorkRequest.Builder(ProcessDoneWorker::class.java, 6, TimeUnit.HOURS)
                .setConstraints(processDoneConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build(),
            logMessage = "scheduleProcessDoneSweep key=${UniqueWorkKeys.PROCESS_DONE_PERIODIC}",
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

    fun mediaStoreContinuationPlan(lookbackDays: Int?): UniqueOneTimeWorkPlan =
        UniqueOneTimeWorkPlan(
            uniqueKey = UniqueWorkKeys.MEDIA_STORE,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            request = oneTimeExpedited(
                workerClass = MediaStoreWorker::class.java,
                lookbackDays = lookbackDays,
            ),
            logMessage = "enqueueMediaStoreContinuation key=${UniqueWorkKeys.MEDIA_STORE} lookbackDays=${lookbackDays ?: "default"}",
        )
}
