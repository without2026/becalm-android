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
    /** One-shot and periodic MediaStore (voice) ingestion via [com.becalm.android.worker.ingestion.MediaStoreWorker]. */
    public const val MEDIA_STORE: String = "ingest.media_store"

    /** One-shot and periodic Gmail ingestion via [com.becalm.android.worker.ingestion.GmailWorker]. */
    public const val GMAIL: String = "ingest.gmail"

    /** One-shot and periodic Naver IMAP ingestion via [com.becalm.android.worker.ingestion.ImapNaverWorker]. */
    public const val NAVER_IMAP: String = "ingest.naver_imap"

    /** One-shot and periodic Daum IMAP ingestion via [com.becalm.android.worker.ingestion.ImapDaumWorker]. */
    public const val DAUM_IMAP: String = "ingest.daum_imap"

    /** One-shot and periodic Outlook Mail ingestion via [com.becalm.android.worker.ingestion.OutlookMailWorker]. */
    public const val OUTLOOK_MAIL: String = "ingest.outlook_mail"

    /** One-shot and periodic Google Calendar ingestion via [com.becalm.android.worker.ingestion.GoogleCalendarWorker]. */
    public const val GCAL: String = "ingest.gcal"

    /** One-shot and periodic Outlook Calendar ingestion via [com.becalm.android.worker.ingestion.OutlookCalendarWorker]. */
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

    /**
     * On-device commitment extraction via
     * [com.becalm.android.worker.extraction.CommitmentExtractionWorker].
     *
     * Each enqueue uses a per-event suffix so multiple raw ingestion events can be extracted
     * concurrently — one email's LLM inference must not block another's. Use
     * [commitmentExtractionKey] to generate the full unique-work name.
     *
     * Spec refs: EMAIL-001, EMAIL-008.
     */
    public const val COMMITMENT_EXTRACTION_PREFIX: String = "commitment-extraction-"

    /**
     * Returns the unique work name for a
     * [com.becalm.android.worker.extraction.CommitmentExtractionWorker] processing the raw
     * event identified by [rawEventId].
     *
     * Per-event stability means re-enqueueing with the same [rawEventId] collapses onto the
     * same unique-work name, and [androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE] in
     * [WorkSchedulerImpl.enqueueCommitmentExtraction] ensures either the in-flight job
     * completes or the new request replaces it — never two parallel extractors for the same
     * email.
     *
     * @param rawEventId UUID of the [com.becalm.android.data.local.db.entity.RawIngestionEventEntity].
     */
    public fun commitmentExtractionKey(rawEventId: String): String =
        COMMITMENT_EXTRACTION_PREFIX + rawEventId

    /**
     * Daily retention sweep via [com.becalm.android.worker.RetentionSweepWorker].
     *
     * Drives the 30-day rolling-window pruning contract mandated by EMAIL-006
     * (`.spec/email-pipeline.spec.yml:58-64`) and the cross-module invariant at
     * `.spec/data-ingestion.spec.yml:160` — "Room raw_ingestion_events와 EmailBody는
     * timestamp 기준 30일 rolling window로 자동 삭제된다 — 단 sync_status='synced' 조건을
     * 만족할 때만". FK CASCADE between the two tables is only a per-row safety net; the
     * timestamp-based sweep itself must be scheduled by this worker, hence a dedicated
     * stable unique-work name enrolled in [WorkSchedulerImpl.ALL_KEYS] so sign-out
     * `cancelAll` tears it down together with every other chain.
     *
     * Spec refs: EMAIL-006, data-ingestion invariant line 160.
     */
    public const val RETENTION_SWEEP: String = "retention.sweep"

    /**
     * Deprecated legacy key — cancelled on every cold start for one release after Wave 0
     * (#13 renamed MediaStore ingest from `ingest.sms_call` → [MEDIA_STORE]).
     *
     * Devices upgrading from the pre-#13 build still have WorkManager entries enqueued under
     * this name; [WorkScheduler.cleanupLegacyWorkNames] sweeps them at app start so the new
     * [MEDIA_STORE] scheduling does not run alongside the orphan.
     *
     * Intentionally NOT added to the live `ALL_KEYS` sweep in [WorkSchedulerImpl] — that list
     * is for currently-enqueued work. This constant only exists to be cancelled.
     *
     * TODO(wave-N+2): Remove this constant and [WorkScheduler.cleanupLegacyWorkNames] once the
     * install base has drained from pre-#13 builds (≥ two minor releases past Wave 0).
     */
    internal const val LEGACY_MEDIA_STORE_KEY: String = "ingest.sms_call"
}
