package com.becalm.android.worker

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import java.util.concurrent.TimeUnit

internal class WorkSchedulerOneShotEnqueuer(
    private val planRunner: WorkSchedulerPlanRunner,
) {
    fun enqueueForKey(
        workerClass: Class<out ListenableWorker>,
        uniqueKey: String,
        label: String,
        lookbackDays: Int? = null,
    ) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = uniqueKey,
                policy = oneShotPolicyFor(lookbackDays),
                request = WorkSchedulerRequests.oneTimeExpedited(
                    workerClass = workerClass,
                    lookbackDays = lookbackDays,
                ),
                logMessage = "$label key=$uniqueKey lookbackDays=${lookbackDays ?: "default"}",
            ),
        )
    }

    private fun oneShotPolicyFor(lookbackDays: Int?): ExistingWorkPolicy =
        if (lookbackDays == null) {
            ExistingWorkPolicy.KEEP
        } else {
            ExistingWorkPolicy.REPLACE
        }

    fun enqueueVoiceUpload(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int,
        selfSpeakerId: String? = null,
        speakerMappingsJson: String? = null,
        speakerPreviewId: String? = null,
        extractionJobId: String? = null,
        extractionJobPollAttempt: Int = 0,
    ) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = UniqueWorkKeys.voiceUpload(rawEventId),
                policy = ExistingWorkPolicy.REPLACE,
                request = WorkSchedulerRequests.voiceUploadRequest(
                    rawEventId = rawEventId,
                    audioUri = audioUri,
                    initialDelaySec = initialDelaySec,
                    rateLimitedAttempt = rateLimitedAttempt,
                    selfSpeakerId = selfSpeakerId,
                    speakerMappingsJson = speakerMappingsJson,
                    speakerPreviewId = speakerPreviewId,
                    extractionJobId = extractionJobId,
                    extractionJobPollAttempt = extractionJobPollAttempt,
                ),
                logMessage = "enqueueVoiceUpload rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.voiceUpload(rawEventId)} delaySec=$initialDelaySec " +
                    "rateLimitedAttempt=$rateLimitedAttempt jobPollAttempt=$extractionJobPollAttempt",
            ),
        )
    }

    fun enqueueMessageScreenshotUpload(rawEventId: String) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = UniqueWorkKeys.messageScreenshotUpload(rawEventId),
                policy = ExistingWorkPolicy.REPLACE,
                request = WorkSchedulerRequests.messageScreenshotUploadRequest(rawEventId),
                logMessage = "enqueueMessageScreenshotUpload rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.messageScreenshotUpload(rawEventId)}",
            ),
        )
    }

    fun enqueueMeetingSpeakerPreview(rawEventId: String, audioUri: String) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = UniqueWorkKeys.meetingSpeakerPreview(rawEventId),
                policy = ExistingWorkPolicy.REPLACE,
                request = WorkSchedulerRequests.meetingSpeakerPreviewRequest(
                    rawEventId = rawEventId,
                    audioUri = audioUri,
                ),
                logMessage = "enqueueMeetingSpeakerPreview rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.meetingSpeakerPreview(rawEventId)}",
            ),
        )
    }
}
