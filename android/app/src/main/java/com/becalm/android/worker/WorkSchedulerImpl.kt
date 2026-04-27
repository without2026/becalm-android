package com.becalm.android.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.worker.ingestion.MediaStoreWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [WorkScheduler].
 *
 * Also implements [ForegroundWorkScheduler] so that a single Hilt binding satisfies
 * [ContentObserverBootstrap] (which depends on [ForegroundWorkScheduler]) and
 * [ForegroundCatchUpScheduler] without requiring an adapter class.
 */
@Singleton
public class WorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : WorkScheduler, ForegroundWorkScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val planRunner: WorkSchedulerPlanRunner by lazy {
        WorkSchedulerPlanRunner(workManager = workManager, logger = logger)
    }
    private val oneShotEnqueuer: WorkSchedulerOneShotEnqueuer by lazy {
        WorkSchedulerOneShotEnqueuer(planRunner = planRunner)
    }

    /** Constraints for battery-aware periodic work. */
    private val periodicConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    override fun enqueueExpedited(sourceKey: String) {
        val spec = resolveSource(sourceKey) ?: return
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = spec.uniqueKey,
                policy = androidx.work.ExistingWorkPolicy.REPLACE,
                request = WorkSchedulerRequests.oneTimeExpedited(spec.workerClass),
                logMessage = "enqueueExpedited source=$sourceKey key=${spec.uniqueKey}",
            ),
        )
    }

    override fun enqueuePeriodic(sourceKey: String) {
        val spec = resolveSource(sourceKey) ?: return
        planRunner.run(
            UniquePeriodicWorkPlan(
                uniqueKey = spec.uniqueKey,
                policy = androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request = WorkSchedulerRequests.periodicRequest(spec.workerClass, periodicConstraints),
                logMessage = "enqueuePeriodic source=$sourceKey key=${spec.uniqueKey}",
            ),
        )
    }

    override fun enqueueUpload(attempt: Int) {
        planRunner.run(WorkSchedulerRequests.uploadPlan(attempt))
    }

    override fun scheduleUploadRedundancy() {
        planRunner.run(WorkSchedulerRequests.uploadPeriodicPlan())
    }

    override fun enqueueEnrichment() {
        oneShotEnqueuer.enqueueForKey(
            EnrichmentWorker::class.java,
            UniqueWorkKeys.ENRICHMENT,
            "enqueueEnrichment",
        )
    }

    override fun scheduleEnrichmentSweep() {
        planRunner.run(WorkSchedulerRequests.enrichmentPeriodicPlan())
    }

    override fun cancelEnrichmentSweep() {
        planRunner.run(WorkSchedulerRequests.cancelEnrichmentPeriodicPlan())
    }

    override fun enqueueVoiceUpload(rawEventId: String, audioUri: String) {
        enqueueVoiceUploadInternal(
            rawEventId = rawEventId,
            audioUri = audioUri,
            initialDelaySec = 0L,
            rateLimitedAttempt = 0,
        )
    }

    override fun enqueueVoiceUploadWithDelay(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int,
    ) {
        enqueueVoiceUploadInternal(
            rawEventId = rawEventId,
            audioUri = audioUri,
            initialDelaySec = initialDelaySec.coerceAtLeast(0L),
            rateLimitedAttempt = rateLimitedAttempt.coerceAtLeast(0),
        )
    }

    private fun enqueueVoiceUploadInternal(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int,
    ) {
        oneShotEnqueuer.enqueueVoiceUpload(
            rawEventId = rawEventId,
            audioUri = audioUri,
            initialDelaySec = initialDelaySec,
            rateLimitedAttempt = rateLimitedAttempt,
        )
    }

    override fun cancelVoiceUpload(rawEventId: String) {
        val workKey = UniqueWorkKeys.voiceUpload(rawEventId)
        planRunner.run(
            CancelUniqueWorkPlan(
                uniqueKey = workKey,
                logMessage = "cancelVoiceUpload rawEventId_hash=${redact(rawEventId)} key=$workKey",
            ),
        )
    }

    override fun enqueueCommitmentExtraction(rawEventId: String) {
        planRunner.run(
            UniqueOneTimeWorkPlan(
                uniqueKey = UniqueWorkKeys.commitmentExtractionKey(rawEventId),
                policy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                request = WorkSchedulerRequests.commitmentExtractionRequest(rawEventId),
                logMessage = "enqueueCommitmentExtraction rawEventId_hash=${redact(rawEventId)} " +
                    "key=${UniqueWorkKeys.commitmentExtractionKey(rawEventId)}",
            ),
        )
    }

    override fun scheduleRetentionSweep() {
        planRunner.run(WorkSchedulerRequests.retentionSweepPlan())
    }

    override fun scheduleOverdueSweep() {
        planRunner.run(WorkSchedulerRequests.overdueSweepPlan())
    }

    override fun enqueueDeferredColdSyncStage1() {
        planRunner.run(WorkSchedulerRequests.deferredColdSyncStage1Plan())
    }

    override fun enqueueColdSyncStage2() {
        planRunner.run(WorkSchedulerRequests.coldSyncStage2Plan())
    }

    override fun cancelColdSyncStage2() {
        planRunner.run(WorkSchedulerRequests.cancelColdSyncStage2Plan())
    }

    override fun cancelAll() {
        for (key in ALL_KEYS) {
            workManager.cancelUniqueWork(key)
        }
        workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_VOICE_UPLOAD)
        logger.d(TAG, "cancelAll — cancelled ${ALL_KEYS.size} unique chains + voice uploads by tag")
    }

    override fun cleanupLegacyWorkNames() {
        workManager.cancelUniqueWork(UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY)
        workManager.cancelUniqueWork(UniqueWorkKeys.LEGACY_UPLOAD_KEY)
        logger.d(
            TAG,
            "cleanupLegacyWorkNames — cancelled legacy keys=" +
                "${UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY}, ${UniqueWorkKeys.LEGACY_UPLOAD_KEY}",
        )
    }

    override fun enqueueMediaStoreOneShotNow(lookbackDays: Int?) {
        oneShotEnqueuer.enqueueForKey(
            MediaStoreWorker::class.java,
            UniqueWorkKeys.MEDIA_STORE,
            "enqueueMediaStoreOneShotNow",
            lookbackDays = lookbackDays,
        )
    }

    override fun enqueueImapNaverOneShotNow(lookbackDays: Int?) {
        oneShotEnqueuer.enqueueForKey(
            requireSource(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP).workerClass,
            UniqueWorkKeys.NAVER_IMAP,
            "enqueueImapNaverOneShotNow",
            lookbackDays = lookbackDays,
        )
    }

    override fun enqueueImapDaumOneShotNow(lookbackDays: Int?) {
        oneShotEnqueuer.enqueueForKey(
            requireSource(com.becalm.android.data.remote.dto.SourceType.DAUM_IMAP).workerClass,
            UniqueWorkKeys.DAUM_IMAP,
            "enqueueImapDaumOneShotNow",
            lookbackDays = lookbackDays,
        )
    }

    override fun enqueueGCalOneShotNow(lookbackDays: Int?) {
        oneShotEnqueuer.enqueueForKey(
            requireSource(com.becalm.android.data.remote.dto.SourceType.GOOGLE_CALENDAR).workerClass,
            UniqueWorkKeys.GCAL,
            "enqueueGCalOneShotNow",
            lookbackDays = lookbackDays,
        )
    }

    override fun enqueueOutlookCalOneShotNow(lookbackDays: Int?) {
        oneShotEnqueuer.enqueueForKey(
            requireSource(com.becalm.android.data.remote.dto.SourceType.OUTLOOK_CALENDAR).workerClass,
            UniqueWorkKeys.OUTLOOK_CAL,
            "enqueueOutlookCalOneShotNow",
            lookbackDays = lookbackDays,
        )
    }

    private fun resolveSource(sourceKey: String): SourceWorkSpec? =
        WorkSchedulerRequests.resolveSource(sourceKey)
            ?: run {
                logger.w(TAG, "resolveSource: unknown sourceKey='$sourceKey' — skipped")
                null
            }

    private fun requireSource(sourceKey: String): SourceWorkSpec =
        requireNotNull(WorkSchedulerRequests.resolveSource(sourceKey)) {
            "No worker mapping for sourceKey=$sourceKey"
        }

    private companion object {
        private const val TAG = "WorkScheduler"
        private val ALL_KEYS: List<String> = WorkSchedulerRequests.allStaticKeys()
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class WorkSchedulerModule {

    @Binds
    @Singleton
    public abstract fun bindWorkScheduler(impl: WorkSchedulerImpl): WorkScheduler

    @Binds
    @Singleton
    public abstract fun bindForegroundWorkScheduler(impl: WorkSchedulerImpl): ForegroundWorkScheduler
}
