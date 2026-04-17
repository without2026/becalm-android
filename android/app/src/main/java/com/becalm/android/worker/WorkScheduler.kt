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

// ── Input data keys ──────────────────────────────────────────────────────────

/** [WorkScheduler.enqueueUpload] input data key carrying the 0-based attempt index. */
public const val KEY_UPLOAD_ATTEMPT: String = "attempt"

// ── WorkScheduler interface ──────────────────────────────────────────────────

/**
 * Central facade for enqueuing all background workers in BeCalm Android.
 *
 * Every call uses [WorkManager.enqueueUniqueWork] / [WorkManager.enqueueUniquePeriodicWork]
 * with a stable name from [UniqueWorkKeys] so that a new request always replaces any
 * in-flight duplicate (SYNC-005 guarantee).
 *
 * The implementation ([WorkSchedulerImpl]) is a Hilt singleton. Consumers depend on this
 * interface rather than the concrete class so that tests can substitute a fake.
 *
 * ## Interface hierarchy
 * [WorkSchedulerImpl] also implements [ForegroundWorkScheduler] (declared in
 * [ForegroundCatchUpScheduler]), which extends
 * [com.becalm.android.worker.ingestion.WorkSchedulerCompat] (declared in
 * [com.becalm.android.worker.ingestion.ContentObserverSms]). A single Hilt binding
 * therefore satisfies [WorkScheduler], [ForegroundWorkScheduler], and [WorkSchedulerCompat].
 */
public interface WorkScheduler {

    /**
     * Enqueues a one-time [MediaStoreWorker] as a one-shot expedited request (SMS/call-log
     * initial scan or content-observer-triggered catch-up).
     */
    public fun enqueueOneTimeMediaStoreSync()

    /**
     * Enqueues a one-shot expedited worker for [sourceKey].
     *
     * [sourceKey] must be one of the [com.becalm.android.data.remote.dto.SourceType] string
     * constants. Unknown values are logged at WARN and silently dropped.
     */
    public fun enqueueExpedited(sourceKey: String)

    /**
     * Enqueues a recurring periodic worker for [sourceKey] (15-minute minimum interval).
     *
     * [sourceKey] must be one of the [com.becalm.android.data.remote.dto.SourceType] string
     * constants. Unknown values are logged at WARN and silently dropped.
     */
    public fun enqueuePeriodic(sourceKey: String)

    /**
     * Enqueues a one-shot [UploadWorker] to push pending rows to the Railway backend.
     *
     * @param attempt 0-based attempt index forwarded to [UploadWorker] as input data. Used
     *   by [UploadBackoff] to compute the next delay on a retry cycle.
     */
    public fun enqueueUpload(attempt: Int = 0)

    /**
     * Enqueues a one-shot [EnrichmentWorker] to refresh on-device contact enrichment.
     */
    public fun enqueueEnrichment()

    /**
     * Enqueues a one-shot [VoiceUploadWorker] to upload a voice recording to Railway and
     * extract commitments via Vertex AI Gemini 2.5 Flash.
     *
     * Requires Wi-Fi / unmetered network (SYNC-005 + spec invariant: mobile data
     * auto-upload prohibited for audio files).
     *
     * The unique work name is [UniqueWorkKeys.voiceUpload](rawEventId) — per-event stability
     * ensures re-enqueue for the same recording atomically replaces any in-flight request.
     *
     * @param rawEventId UUID of the [com.becalm.android.data.local.db.entity.RawIngestionEventEntity]
     *   to process.
     * @param audioUri   Content URI of the audio file (read-only SAF access; VOI-007).
     *
     * Spec refs: VOI-001, VOI-005, VOI-007.
     */
    public fun enqueueVoiceUpload(rawEventId: String, audioUri: String)

    /**
     * Cancels the [VoiceUploadWorker] unique-work entry for [rawEventId], if one is enqueued
     * or running.
     *
     * Called when PIPA consent is withdrawn to prevent an already-enqueued job from
     * transmitting audio after consent is revoked (finding #1 fix — closes the in-queue
     * race window).
     *
     * @param rawEventId UUID of the [com.becalm.android.data.local.db.entity.RawIngestionEventEntity]
     *   whose upload should be cancelled.
     *
     * Spec refs: VOI-004.
     */
    public fun cancelVoiceUpload(rawEventId: String)

    /**
     * Cancels all uniquely-named work managed by this scheduler.
     *
     * Call on user sign-out to prevent orphaned workers from running against a stale session.
     */
    public fun cancelAll()
}

// ── WorkSchedulerImpl ────────────────────────────────────────────────────────

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
        val request = oneTimeExpedited(MediaStoreWorker::class.java)
        workManager.enqueueUniqueWork(
            UniqueWorkKeys.SMS_CALL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.d(TAG, "enqueueOneTimeMediaStoreSync key=${UniqueWorkKeys.SMS_CALL}")
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
        val request = oneTimeExpedited(EnrichmentWorker::class.java)
        workManager.enqueueUniqueWork(UniqueWorkKeys.ENRICHMENT, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueEnrichment key=${UniqueWorkKeys.ENRICHMENT}")
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
        val request = oneTimeExpedited(GmailWorker::class.java)
        workManager.enqueueUniqueWork(UniqueWorkKeys.GMAIL, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueGmailOneShotNow key=${UniqueWorkKeys.GMAIL}")
    }

    override fun enqueueImapNaverOneShotNow() {
        val request = oneTimeExpedited(ImapNaverWorker::class.java)
        workManager.enqueueUniqueWork(UniqueWorkKeys.NAVER_IMAP, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueImapNaverOneShotNow key=${UniqueWorkKeys.NAVER_IMAP}")
    }

    override fun enqueueImapDaumOneShotNow() {
        // TODO(SP-27b): ImapDaumWorker not yet implemented. Log and return.
        Log.w(TAG, "enqueueImapDaumOneShotNow: ImapDaumWorker pending SP-27b — skipped")
    }

    override fun enqueueOutlookMailOneShotNow() {
        val request = oneTimeExpedited(OutlookMailWorker::class.java)
        workManager.enqueueUniqueWork(UniqueWorkKeys.OUTLOOK_MAIL, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueOutlookMailOneShotNow key=${UniqueWorkKeys.OUTLOOK_MAIL}")
    }

    override fun enqueueGCalOneShotNow() {
        val request = oneTimeExpedited(GoogleCalendarWorker::class.java)
        workManager.enqueueUniqueWork(UniqueWorkKeys.GCAL, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "enqueueGCalOneShotNow key=${UniqueWorkKeys.GCAL}")
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
            SOURCE_GMAIL -> GmailWorker::class.java to UniqueWorkKeys.GMAIL
            SOURCE_NAVER_IMAP -> ImapNaverWorker::class.java to UniqueWorkKeys.NAVER_IMAP
            SOURCE_OUTLOOK_MAIL -> OutlookMailWorker::class.java to UniqueWorkKeys.OUTLOOK_MAIL
            SOURCE_GOOGLE_CALENDAR -> GoogleCalendarWorker::class.java to UniqueWorkKeys.GCAL
            SOURCE_OUTLOOK_CALENDAR -> {
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns an 8-char hex surrogate to prevent PII from appearing in logcat. */
    private fun redact(value: String): String = "%08x".format(value.hashCode())

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "WorkScheduler"

        /** Minimum periodic repeat interval — Android enforces a 15-minute floor. */
        private const val PERIODIC_INTERVAL_MINUTES: Long = 15L

        /** Base backoff delay in seconds for EXPONENTIAL policy (30s → 60s → 120s …). */
        private const val BACKOFF_DELAY_SECONDS: Long = 30L

        /** Tag applied to all per-event voice upload requests for bulk cancellation on sign-out. */
        private const val TAG_VOICE_UPLOAD: String = "voice_upload"

        // Source key string constants matching SourceType wire values.
        private const val SOURCE_SMS_CALL: String = "sms_call"
        private const val SOURCE_GMAIL: String = "gmail"
        private const val SOURCE_NAVER_IMAP: String = "naver_imap"
        private const val SOURCE_OUTLOOK_MAIL: String = "outlook_mail"
        private const val SOURCE_GOOGLE_CALENDAR: String = "google_calendar"
        private const val SOURCE_OUTLOOK_CALENDAR: String = "outlook_calendar"

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
 * This module is co-located in WorkScheduler.kt (not a new file) to keep the scope of
 * SP-32 disjoint from WorkerModule (SP-06).
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
