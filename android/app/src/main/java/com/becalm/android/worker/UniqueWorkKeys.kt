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

    /** Voice transcription via [com.becalm.android.worker.VoiceTranscriptionWorker]. */
    public const val VOICE: String = "voice.transcribe"
}
