package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
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

/**
 * Legacy retention-sweep [CoroutineWorker].
 *
 * Source originals are now retained local-first until the user explicitly deletes archived
 * originals from Privacy Management. This worker remains scheduled as a stable lifecycle hook,
 * but it no longer deletes `email_body`, `raw_ingestion_events`, or source archive files.
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
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return Result.failure()

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
        logger.d(TAG, "RetentionSweepWorker no-op — source originals are user-retained")
        return Result.success(
            workDataOf(
                KEY_EMAIL_DELETED to 0,
                KEY_RAW_DELETED to 0,
            ),
        )
    }

    public companion object {
        private const val TAG: String = "RetentionSweep"

        private const val MAX_RETRIES: Int = 5

        /**
         * Output data key for the number of `email_body` rows deleted by a given run.
         * Surfaced via [Result.success] for downstream observability; remote
         * analytics wiring is tracked separately alongside EXTRACT-EMAIL-009 metrics.
         */
        public const val KEY_EMAIL_DELETED: String = "email_deleted"

        /** Output data key for the number of `raw_ingestion_events` rows deleted. */
        public const val KEY_RAW_DELETED: String = "raw_deleted"
    }
}
