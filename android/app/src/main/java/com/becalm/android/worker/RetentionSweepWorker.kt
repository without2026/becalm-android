package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.days

/**
 * Daily retention-sweep [CoroutineWorker] that prunes on-device `email_body` and
 * `raw_ingestion_events` rows older than 30 days with `sync_status = 'synced'`.
 *
 * ## Spec contract
 * - EMAIL-006 (`.spec/email-pipeline.spec.yml:58-64`) — "30일 경과 + sync_status='synced'
 *   조건 만족 시 RetentionSweepWorker가 EmailBody와 raw_ingestion_events를 함께 DELETE".
 * - Cross-module invariant at `.spec/data-ingestion.spec.yml:160` — commitments and
 *   calendar_events are **explicitly excluded** from this sweep. Those tables have
 *   independent lifecycles (soft-delete + user-driven edits); conflating them here
 *   would destroy user-confirmed state.
 * - `.spec/data-ingestion.spec.yml:151` — pending / failed / awaiting_consent raw events
 *   are never deleted because the server has not yet acknowledged receipt.
 *
 * ## Order of DELETEs
 * The two DAO queries run inside a single [BeCalmDatabase.withTransaction] so either
 * both succeed or neither is visible. The `email_body` DELETE fires **first**, then the
 * `raw_ingestion_events` DELETE — explicitly 2-step even though `ForeignKey.CASCADE`
 * on `email_body.raw_event_id` would co-delete the body automatically. Three reasons
 * motivate the explicit 2-step approach (see plan appendix):
 *
 * 1. **Observability** — each DAO returns the affected row count independently, so
 *    [Result.success] output data can surface `email_deleted` and `raw_deleted` as
 *    distinct metrics. Relying on CASCADE would leave `email_deleted = 0` even when
 *    real body rows disappeared via the FK path.
 * 2. **Regression defence** — should a future migration accidentally drop the CASCADE
 *    behaviour, the explicit DELETE keeps the contract intact without surprise orphans.
 * 3. **Predictability** — a SQL reviewer can see, at a glance, exactly which two
 *    tables this worker mutates and no others.
 *
 * ## Cutoff computation
 * `cutoffMillis = (clock.nowInstant() - 30.days).toEpochMilliseconds()`. Both DAO
 * queries receive the identical `cutoffMillis` so their eligibility windows are
 * aligned against the same wall-clock snapshot. The [Clock] abstraction is injected
 * rather than calling `kotlinx.datetime.Clock.System` directly so unit tests can
 * drive time deterministically with a fake (see [RetentionSweepWorkerTest]).
 *
 * ## Failure handling
 * Any exception out of [doWork]'s transaction returns [Result.retry], letting
 * WorkManager apply its default backoff. The sweep is fully idempotent (a successful
 * second run returns counts of zero), so a retry after partial progress is safe.
 *
 * ## Out of scope
 * - Voice transcript retention (separate storage, different policy).
 * - Sentry / metrics streaming — current output data only carries the two counts.
 * - Manual sweep trigger UI.
 * - Retention-duration changes (30-day value is spec-fixed).
 */
@HiltWorker
public class RetentionSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val emailBodyDaoProvider: Provider<EmailBodyDao>,
    private val clock: Clock,
    private val dbProvider: Provider<BeCalmDatabase>,
    private val userPrefsStore: UserPrefsStore,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        rawIngestionEventDao: RawIngestionEventDao,
        emailBodyDao: EmailBodyDao,
        clock: Clock,
        db: BeCalmDatabase,
        userPrefsStore: UserPrefsStore,
        processingPauseGate: ProcessingPauseGate,
        logger: Logger,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        rawIngestionEventDaoProvider = Provider { rawIngestionEventDao },
        emailBodyDaoProvider = Provider { emailBodyDao },
        clock = clock,
        dbProvider = Provider { db },
        userPrefsStore = userPrefsStore,
        processingPauseGate = processingPauseGate,
        logger = logger,
    )

    override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success()
        }
        if (userPrefsStore.observeCurrentUserId().first().isNullOrBlank()) {
            logger.d(TAG, "RetentionSweepWorker skipped — no authenticated user")
            return Result.success(
                workDataOf(
                    KEY_EMAIL_DELETED to 0,
                    KEY_RAW_DELETED to 0,
                ),
            )
        }
        val cutoffInstant = clock.nowInstant() - RETENTION_WINDOW
        val cutoffMillis = cutoffInstant.toEpochMilliseconds()
        val rawIngestionEventDao = rawIngestionEventDaoProvider.get()
        val emailBodyDao = emailBodyDaoProvider.get()
        val db = dbProvider.get()

        return runCatching {
            // Atomically delete email bodies first, then their parent raw events.
            // Both DAO calls share `cutoffMillis` so the two windows align.
            val (emailDeleted, rawDeleted) = db.withTransaction {
                val emails = emailBodyDao.deleteOlderThanForSynced(cutoffMillis)
                val raws = rawIngestionEventDao.deleteSyncedOlderThan(cutoffMillis)
                emails to raws
            }
            logger.d(
                TAG,
                "RetentionSweepWorker cutoffMillis=$cutoffMillis " +
                    "emailDeleted=$emailDeleted rawDeleted=$rawDeleted",
            )
            Result.success(
                workDataOf(
                    KEY_EMAIL_DELETED to emailDeleted,
                    KEY_RAW_DELETED to rawDeleted,
                ),
            )
        }.getOrElse { error ->
            // Retry on any failure — WorkManager applies default exponential backoff.
            // Sweep is idempotent (subsequent success path returns 0/0 when there is
            // nothing left to prune), so partial progress followed by retry is safe.
            logger.w(TAG, "RetentionSweepWorker failed: ${error.message}")
            Result.retry()
        }
    }

    public companion object {
        private const val TAG: String = "RetentionSweep"

        /** 30-day rolling retention window from EMAIL-006 and data-ingestion:160. */
        private val RETENTION_WINDOW = 30.days

        /**
         * Output data key for the number of `email_body` rows deleted by a given run.
         * Surfaced via [Result.success] for downstream observability (the Sentry
         * integration lands in a separate PR alongside EXTRACT-EMAIL-009 metrics).
         */
        public const val KEY_EMAIL_DELETED: String = "email_deleted"

        /** Output data key for the number of `raw_ingestion_events` rows deleted. */
        public const val KEY_RAW_DELETED: String = "raw_deleted"
    }
}
