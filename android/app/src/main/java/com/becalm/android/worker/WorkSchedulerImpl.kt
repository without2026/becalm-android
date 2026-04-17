package com.becalm.android.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.becalm.android.core.util.redact
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.ingestion.GmailWorker
import com.becalm.android.worker.ingestion.GoogleCalendarWorker
import com.becalm.android.worker.ingestion.ImapNaverWorker
import com.becalm.android.worker.ingestion.MediaStoreWorker
import com.becalm.android.worker.ingestion.OutlookMailWorker
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
 *
 * ## Constraints policy
 * - **Expedited / one-shot**: [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] ensures
 *   Samsung's app-sleeping policy does not permanently nuke the request.
 * - **Periodic / non-expedited**: additionally requires [NetworkType.CONNECTED] and
 *   [Constraints.Builder.setRequiresBatteryNotLow] to be conservative on battery.
 * - All workers use [BackoffPolicy.EXPONENTIAL] with a 30-second base delay.
 *
 * ## SYNC-005 compliance
 * [ExistingWorkPolicy.REPLACE] / [ExistingPeriodicWorkPolicy.UPDATE] are used throughout
 * so that enqueueing a new request atomically replaces any pending or running instance.
 */
@Singleton
public class WorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkScheduler, ForegroundWorkScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    // ── Constraints ──────────────────────────────────────────────────────────

    /** Constraints for battery-aware periodic work. */
    private val periodicConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    // ── WorkScheduler ────────────────────────────────────────────────────────

    override fun enqueueOneTimeMediaStoreSync() {
        enqueueOneShotForKey(
            MediaStoreWorker::class.java,
            UniqueWorkKeys.SMS_CALL,
            "enqueueOneTimeMediaStoreSync",
        )
    }

    override fun enqueueExpedited(sourceKey: String) {
        val (workerClass, workKey) = resolveSource(sourceKey) ?: return
        val request = oneTimeExpedited(workerClass)
        workManager.enqueueUniqueWork(workKey, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueExpedited source=$sourceKey key=$workKey")
    }

    override fun enqueuePeriodic(sourceKey: String) {
        val (workerClass, workKey) = resolveSource(sourceKey) ?: return
        val request = periodicRequest(workerClass)
        workManager.enqueueUniquePeriodicWork(workKey, ExistingPeriodicWorkPolicy.UPDATE, request)
        Log.d(TAG, "enqueuePeriodic source=$sourceKey key=$workKey")
    }

    override fun enqueueUpload(attempt: Int) {
        val request = OneTimeWorkRequest.Builder(UploadWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_UPLOAD_ATTEMPT to attempt))
            .build()
        workManager.enqueueUniqueWork(UniqueWorkKeys.UPLOAD, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueUpload attempt=$attempt key=${UniqueWorkKeys.UPLOAD}")
    }

    override fun enqueueEnrichment() {
        enqueueOneShotForKey(
            EnrichmentWorker::class.java,
            UniqueWorkKeys.ENRICHMENT,
            "enqueueEnrichment",
        )
    }

    override fun enqueueVoiceUpload(rawEventId: String, audioUri: String) {
        val voiceConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequest.Builder(VoiceUploadWorker::class.java)
            .setConstraints(voiceConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    VoiceUploadWorker.KEY_RAW_EVENT_ID to rawEventId,
                    VoiceUploadWorker.KEY_AUDIO_URI to audioUri,
                ),
            )
            .addTag(TAG_VOICE_UPLOAD)
            .build()
        val workKey = UniqueWorkKeys.voiceUpload(rawEventId)
        workManager.enqueueUniqueWork(workKey, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueVoiceUpload rawEventId_hash=${redact(rawEventId)} key=$workKey")
    }

    override fun cancelVoiceUpload(rawEventId: String) {
        val workKey = UniqueWorkKeys.voiceUpload(rawEventId)
        workManager.cancelUniqueWork(workKey)
        Log.d(TAG, "cancelVoiceUpload rawEventId_hash=${redact(rawEventId)} key=$workKey")
    }

    override fun cancelAll() {
        for (key in ALL_KEYS) {
            workManager.cancelUniqueWork(key)
        }
        // Per-event voice uploads use dynamic keys not in ALL_KEYS; cancel by tag.
        workManager.cancelAllWorkByTag(TAG_VOICE_UPLOAD)
        Log.d(TAG, "cancelAll — cancelled ${ALL_KEYS.size} unique chains + voice uploads by tag")
    }

    // ── ForegroundWorkScheduler ──────────────────────────────────────────────

    override fun enqueueMediaStoreOneShotNow() {
        enqueueOneTimeMediaStoreSync()
    }

    override fun enqueueGmailOneShotNow() {
        enqueueOneShotForKey(
            GmailWorker::class.java,
            UniqueWorkKeys.GMAIL,
            "enqueueGmailOneShotNow",
        )
    }

    override fun enqueueImapNaverOneShotNow() {
        enqueueOneShotForKey(
            ImapNaverWorker::class.java,
            UniqueWorkKeys.NAVER_IMAP,
            "enqueueImapNaverOneShotNow",
        )
    }

    override fun enqueueImapDaumOneShotNow() {
        // TODO(SP-27b): ImapDaumWorker not yet implemented. Log and return.
        Log.w(TAG, "enqueueImapDaumOneShotNow: ImapDaumWorker pending SP-27b — skipped")
    }

    override fun enqueueOutlookMailOneShotNow() {
        enqueueOneShotForKey(
            OutlookMailWorker::class.java,
            UniqueWorkKeys.OUTLOOK_MAIL,
            "enqueueOutlookMailOneShotNow",
        )
    }

    override fun enqueueGCalOneShotNow() {
        enqueueOneShotForKey(
            GoogleCalendarWorker::class.java,
            UniqueWorkKeys.GCAL,
            "enqueueGCalOneShotNow",
        )
    }

    override fun enqueueOutlookCalOneShotNow() {
        // TODO(SP-27b): OutlookCalWorker not yet implemented. Log and return.
        Log.w(TAG, "enqueueOutlookCalOneShotNow: OutlookCalWorker pending SP-27b — skipped")
    }

    // ── Source dispatch ──────────────────────────────────────────────────────

    /**
     * Resolves a [sourceKey] string to the corresponding worker class and [UniqueWorkKeys]
     * constant.
     *
     * Returns `null` and logs when the source is pending a future SP or is unknown.
     */
    private fun resolveSource(sourceKey: String): Pair<Class<out androidx.work.ListenableWorker>, String>? =
        when (sourceKey) {
            SOURCE_SMS_CALL -> MediaStoreWorker::class.java to UniqueWorkKeys.SMS_CALL
            SourceType.GMAIL -> GmailWorker::class.java to UniqueWorkKeys.GMAIL
            SourceType.NAVER_IMAP -> ImapNaverWorker::class.java to UniqueWorkKeys.NAVER_IMAP
            SourceType.OUTLOOK_MAIL -> OutlookMailWorker::class.java to UniqueWorkKeys.OUTLOOK_MAIL
            SourceType.GOOGLE_CALENDAR -> GoogleCalendarWorker::class.java to UniqueWorkKeys.GCAL
            SourceType.OUTLOOK_CALENDAR -> {
                // TODO(SP-27b): OutlookCalWorker not yet implemented.
                Log.w(TAG, "resolveSource: outlook_calendar pending SP-27b — skipped")
                null
            }
            else -> {
                Log.w(TAG, "resolveSource: unknown sourceKey='$sourceKey' — skipped")
                null
            }
        }

    // ── Builder helpers ──────────────────────────────────────────────────────

    /**
     * 여섯 개 oneShot enqueue 메서드의 중복(build → enqueueUniqueWork → Log.d) 제거용 헬퍼.
     * unique-work 이름([uniqueKey])과 [ExistingWorkPolicy.REPLACE] 정책은 SYNC-006/SMG-005
     * 계약이므로 호출자 측에서 변경 금지.
     */
    private fun enqueueOneShotForKey(
        workerClass: Class<out androidx.work.ListenableWorker>,
        uniqueKey: String,
        label: String,
    ) {
        val request = oneTimeExpedited(workerClass)
        workManager.enqueueUniqueWork(uniqueKey, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "$label key=$uniqueKey")
    }

    /** Builds an expedited one-shot request for [workerClass] with EXPONENTIAL backoff. */
    private fun <W : androidx.work.ListenableWorker> oneTimeExpedited(
        workerClass: Class<W>,
    ): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(workerClass)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

    /** Builds a periodic request for [workerClass] with network + battery constraints. */
    private fun <W : androidx.work.ListenableWorker> periodicRequest(
        workerClass: Class<W>,
    ): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(workerClass, PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(periodicConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "WorkScheduler"

        /** Minimum periodic repeat interval — Android enforces a 15-minute floor. */
        private const val PERIODIC_INTERVAL_MINUTES: Long = 15L

        /** Base backoff delay in seconds for EXPONENTIAL policy (30s → 60s → 120s …). */
        private const val BACKOFF_DELAY_SECONDS: Long = 30L

        /** Tag applied to all per-event voice upload requests for bulk cancellation on sign-out. */
        private const val TAG_VOICE_UPLOAD: String = "voice_upload"

        /**
         * SMS/call-log dispatch key. Unlike the other source keys, this is NOT a wire
         * [com.becalm.android.data.remote.dto.SourceType] value — SMS ingestion has no
         * server-side counterpart. The other source keys (gmail / naver_imap / outlook_mail /
         * google_calendar / outlook_calendar) reference [SourceType] constants directly in
         * [resolveSource] to stay in lock-step with the DTO wire format.
         */
        private const val SOURCE_SMS_CALL: String = "sms_call"

        /**
         * Static work names — used by [cancelAll] to sweep the WorkManager queue.
         * Per-event voice upload keys are dynamic and covered by [TAG_VOICE_UPLOAD]
         * tag-based cancellation in [cancelAll].
         */
        private val ALL_KEYS: List<String> = listOf(
            UniqueWorkKeys.SMS_CALL,
            UniqueWorkKeys.GMAIL,
            UniqueWorkKeys.NAVER_IMAP,
            UniqueWorkKeys.OUTLOOK_MAIL,
            UniqueWorkKeys.GCAL,
            UniqueWorkKeys.OUTLOOK_CAL,
            UniqueWorkKeys.UPLOAD,
            UniqueWorkKeys.ENRICHMENT,
        )
    }
}

// ── Hilt binding module ──────────────────────────────────────────────────────

/**
 * Hilt module that binds [WorkSchedulerImpl] to all scheduler interfaces.
 *
 * A single implementation satisfies three interfaces:
 * - [WorkScheduler] — primary API consumed by feature code (SP-38, SP-53, etc.)
 * - [ForegroundWorkScheduler] — consumed by [ForegroundCatchUpScheduler] and
 *   [ContentObserverBootstrap] (SP-22 / SP-28)
 *
 * Note: [com.becalm.android.worker.ingestion.WorkSchedulerCompat] is satisfied transitively
 * because [ForegroundWorkScheduler] extends it; no separate binding is needed.
 *
 * Co-located with [WorkSchedulerImpl] (not [WorkScheduler]) to keep the scope of SP-32
 * disjoint from WorkerModule (SP-06).
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class WorkSchedulerModule {

    /** Binds [WorkSchedulerImpl] as the singleton [WorkScheduler]. */
    @Binds
    @Singleton
    public abstract fun bindWorkScheduler(impl: WorkSchedulerImpl): WorkScheduler

    /** Binds [WorkSchedulerImpl] as the singleton [ForegroundWorkScheduler]. */
    @Binds
    @Singleton
    public abstract fun bindForegroundWorkScheduler(impl: WorkSchedulerImpl): ForegroundWorkScheduler
}
