package com.becalm.android.worker

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
 * [com.becalm.android.worker.ingestion.WorkSchedulerCompat]. A single Hilt binding
 * therefore satisfies [WorkScheduler], [ForegroundWorkScheduler], and [WorkSchedulerCompat].
 */
public interface WorkScheduler {

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

    /** Enrolls the periodic redundancy upload chain used by ING-002. */
    public fun scheduleUploadRedundancy()

    /** Enrolls the 15-minute backend-managed Gmail / Outlook Mail sync chain. */
    public fun scheduleBackendMailSync()

    /**
     * Enqueues a one-shot [EnrichmentWorker] to refresh on-device contact enrichment.
     */
    public fun enqueueEnrichment()

    /**
     * Rebuilds the local person identity/interaction index from current source rows.
     *
     * This is coalesced under one stable unique work key. Source sync callers should use the
     * default debounce so Gmail/calendar/upload bursts collapse into one pass; user-driven
     * repair flows can pass `0` for immediate feedback.
     */
    public fun enqueuePersonInteractionIndex(initialDelaySeconds: Long = PERSON_INDEX_DEBOUNCE_SECONDS)

    /** Retries backend mirrors for source participant manual/self match decisions. */
    public fun enqueueSourceParticipantMirrorRetry(initialDelaySeconds: Long = 0L)

    /**
     * Regenerates and mirrors the compact markdown memory for [personId].
     *
     * The unique-work key is person-scoped so repeated source/interaction changes for the same
     * person coalesce without blocking memory updates for other people.
     */
    public fun enqueueProfileMemory(
        personId: String,
        initialDelaySeconds: Long = PROFILE_MEMORY_DEBOUNCE_SECONDS,
    )

    /** Enrolls the daily periodic contacts enrichment sweep. */
    public fun scheduleEnrichmentSweep()

    /** Cancels the periodic contacts enrichment sweep when READ_CONTACTS is unavailable. */
    public fun cancelEnrichmentSweep()

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
    public fun enqueueVoiceUpload(
        rawEventId: String,
        audioUri: String,
        selfSpeakerId: String? = null,
        speakerMappingsJson: String? = null,
        speakerPreviewId: String? = null,
    )

    public fun enqueueMessageScreenshotUpload(rawEventId: String)

    /**
     * Enqueues a one-shot [VoiceUploadWorker] that waits at least [initialDelaySec] seconds
     * before its first run.
     *
     * Used by [VoiceUploadWorker] itself on HTTP 429 to honor a server-supplied `Retry-After`
     * hint — the worker returns [androidx.work.ListenableWorker.Result.success] for the
     * current run and re-enqueues via this method so that [UploadBackoff.nextDelaySeconds]
     * (not WorkManager's static [androidx.work.BackoffPolicy.EXPONENTIAL]) governs the wait.
     *
     * The same unique key as [enqueueVoiceUpload] is used with
     * [androidx.work.ExistingWorkPolicy.REPLACE], so the new delayed request atomically
     * supersedes the completed in-flight attempt and avoids a double-retry.
     *
     * @param rateLimitedAttempt Logical 429 retry counter threaded through [androidx.work.Data]
     *   so the [VoiceUploadWorker.handleRateLimited] quarantine guard survives across
     *   delayed re-enqueues. Each 429 response produces a brand-new [androidx.work.OneTimeWorkRequest]
     *   whose native `runAttemptCount` resets to 0, so without this persistent counter a
     *   persistent 429 would loop forever. Callers outside the worker (fresh enqueues from
     *   user action) should pass `0`.
     *
     * Spec refs: VOI-006, api-contract.yml 429 (Retry-After 존중).
     */
    public fun enqueueVoiceUploadWithDelay(
        rawEventId: String,
        audioUri: String,
        initialDelaySec: Long,
        rateLimitedAttempt: Int = 0,
        selfSpeakerId: String? = null,
        speakerMappingsJson: String? = null,
        speakerPreviewId: String? = null,
    )

    /**
     * Enqueues a daily [RetentionSweepWorker] periodic request under
     * [UniqueWorkKeys.RETENTION_SWEEP] to prune `email_body` and `raw_ingestion_events`
     * rows older than 30 days that have `sync_status = 'synced'`.
     *
     * Idempotent by design: the implementation uses
     * [androidx.work.ExistingPeriodicWorkPolicy.KEEP] so repeated calls on every cold
     * start do not reset the period timer. Safe to invoke from
     * [com.becalm.android.BecalmApplication.onCreate] unconditionally.
     *
     * Spec refs: EMAIL-006 (`.spec/email-pipeline.spec.yml:58-64`),
     * data-ingestion invariant line 160.
     */
    public fun scheduleRetentionSweep()

    /**
     * Enqueues the periodic CMT-011 overdue sweep under a stable unique-work name.
     *
     * The implementation must use a 6-hour repeat interval and KEEP semantics so
     * repeated cold-start enrollment does not reset the timer.
     */
    public fun scheduleOverdueSweep()

    /** Hands an in-progress Stage 1 cold sync off to WorkManager after [나중에 하기]. */
    public fun enqueueDeferredColdSyncStage1()

    /** Enqueues the non-blocking Stage 2 cold-sync backfill. */
    public fun enqueueColdSyncStage2()

    /** Cancels the non-blocking Stage 2 cold-sync backfill chain. */
    public fun cancelColdSyncStage2()

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

    public fun cancelMessageScreenshotUpload(rawEventId: String)

    /**
     * Cancels all uniquely-named work managed by this scheduler.
     *
     * Call on user sign-out to prevent orphaned workers from running against a stale session.
     */
    public fun cancelAll()

    /**
     * One-release compat shim — cancels WorkManager unique-work entries enqueued under names
     * that are no longer live. Currently sweeps the pre-#13 MediaStore key
     * ([UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY]) so that devices upgrading from the prior
     * `origin/main` build do not run duplicate MediaStore scans (old legacy key + new
     * [UniqueWorkKeys.MEDIA_STORE]).
     *
     * Must be called once per cold start (see [BecalmApplication.onCreate]). Cancelling a
     * non-existent unique-work name is a no-op, so repeated invocation is safe.
     *
     * TODO: Remove after Wave N+2 once the upgrade base has drained.
     */
    public fun cleanupLegacyWorkNames()

    public companion object {
        public const val PERSON_INDEX_DEBOUNCE_SECONDS: Long = 10L
        public const val PROFILE_MEMORY_DEBOUNCE_SECONDS: Long = 5L
    }
}
