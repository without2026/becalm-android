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
                ),
                logMessage = "enqueueVoiceUpload rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.voiceUpload(rawEventId)} delaySec=$initialDelaySec " +
                    "rateLimitedAttempt=$rateLimitedAttempt",
            ),
        )
    }

    fun enqueueMeetingTranscriptUpload(rawEventId: String) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = UniqueWorkKeys.meetingTranscriptUpload(rawEventId),
                policy = ExistingWorkPolicy.REPLACE,
                request = WorkSchedulerRequests.meetingTranscriptUploadRequest(rawEventId),
                logMessage = "enqueueMeetingTranscriptUpload rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.meetingTranscriptUpload(rawEventId)}",
            ),
        )
    }
}
