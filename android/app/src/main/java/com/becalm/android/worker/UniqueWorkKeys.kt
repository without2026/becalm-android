package com.becalm.android.worker

/**
 * Stable unique-work-name constants used with [WorkScheduler.enqueueUniqueWork] and
 * [WorkScheduler.enqueueUniquePeriodicWork].
 *
 * Stability guarantee (SYNC-005): names must never contain random suffixes. WorkManager's
 * REPLACE / UPDATE policies depend on a predictable name to locate and swap the in-flight
 * request.
 */
public object UniqueWorkKeys {
    /** One-shot and periodic SMS / call-log ingestion via [com.becalm.android.worker.ingestion.MediaStoreWorker]. */
    public const val SMS_CALL: String = "ingest.sms_call"

    /** One-shot and periodic Gmail ingestion via [com.becalm.android.worker.ingestion.GmailWorker]. */
    public const val GMAIL: String = "ingest.gmail"

    /** One-shot and periodic Naver IMAP ingestion via [com.becalm.android.worker.ingestion.ImapNaverWorker]. */
    public const val NAVER_IMAP: String = "ingest.naver_imap"

    /** One-shot and periodic Outlook Mail ingestion via [com.becalm.android.worker.ingestion.OutlookMailWorker]. */
    public const val OUTLOOK_MAIL: String = "ingest.outlook_mail"

    /** One-shot and periodic Google Calendar ingestion via [com.becalm.android.worker.ingestion.GoogleCalendarWorker]. */
    public const val GCAL: String = "ingest.gcal"

    /**
     * Outlook Calendar ingestion key. Reserved for SP-27b; no worker is enqueued under this
     * key until that SP lands.
     */
    public const val OUTLOOK_CAL: String = "ingest.outlook_cal"

    /** Batch upload to Railway backend via [com.becalm.android.worker.UploadWorker]. */
    public const val UPLOAD: String = "sync.upload"

    /** Contact enrichment via [com.becalm.android.worker.EnrichmentWorker]. */
    public const val ENRICHMENT: String = "enrichment"

    /**
     * Voice upload via [com.becalm.android.worker.VoiceUploadWorker].
     *
     * Each enqueue uses a per-event suffix to allow concurrent uploads for different
     * recordings. Use [voiceUpload] to generate the full work name.
     *
     * Spec refs: VOI-001.
     */
    public const val VOICE_UPLOAD_PREFIX: String = "voice.upload"

    /**
     * Returns the unique work name for a [com.becalm.android.worker.VoiceUploadWorker]
     * processing the raw event identified by [rawEventId].
     *
     * Stable per event — re-enqueueing for the same [rawEventId] replaces any in-flight
     * work (SYNC-005 compliance).
     *
     * @param rawEventId UUID of the [com.becalm.android.data.local.db.entity.RawIngestionEventEntity].
     */
    public fun voiceUpload(rawEventId: String): String = "$VOICE_UPLOAD_PREFIX.$rawEventId"
}
