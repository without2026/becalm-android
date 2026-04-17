package com.becalm.android.worker

// ── Input data keys ──────────────────────────────────────────────────────────

/** [WorkScheduler.enqueueUpload] input data key carrying the 0-based attempt index. */
public const val KEY_UPLOAD_ATTEMPT: String = "attempt"

// ── WorkScheduler interface ──────────────────────────────────────────────────

/**
 * Central facade for enqueuing all background workers in BeCalm Android.
 *
 * Every call uses [androidx.work.WorkManager.enqueueUniqueWork] /
 * [androidx.work.WorkManager.enqueueUniquePeriodicWork] with a stable name from
 * [UniqueWorkKeys] so that a new request always replaces any in-flight duplicate
 * (SYNC-005 guarantee).
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
     * Enqueues a one-time [com.becalm.android.worker.ingestion.MediaStoreWorker] as a
     * one-shot expedited request (SMS/call-log initial scan or content-observer-triggered
     * catch-up).
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
