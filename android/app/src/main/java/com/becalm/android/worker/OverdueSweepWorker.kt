package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Periodic worker that applies the CMT-011 system-derived overdue transition.
 *
 * The worker exposes a narrow, unit-testable surface:
 * - [graceCutoff] for the 24-hour grace-period boundary
 * - [BATCH_SIZE] / output keys for deterministic assertions
 * - local-first Room writes via [CommitmentRepository.markOverdue]
 *
 * Railway mirroring is intentionally delegated to the existing pending-sync upload path:
 * the sweep writes `sync_status='pending'` and UploadWorker drains those rows when
 * network is available.
 */
@HiltWorker
public class OverdueSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val clock: Clock,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        authRepository: AuthRepository,
        commitmentRepository: CommitmentRepository,
        clock: Clock,
        processingPauseGate: ProcessingPauseGate,
        logger: Logger,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        authRepositoryProvider = Provider { authRepository },
        commitmentRepositoryProvider = Provider { commitmentRepository },
        clock = clock,
        processingPauseGate = processingPauseGate,
        logger = logger,
    )

    override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success(
                workDataOf(
                    KEY_CANDIDATE_COUNT to 0,
                    KEY_MARKED_COUNT to 0,
                ),
            )
        }
        val userId = authRepositoryProvider.get().currentSession()?.userId
            ?: return Result.success(
                workDataOf(
                    KEY_CANDIDATE_COUNT to 0,
                    KEY_MARKED_COUNT to 0,
                ),
            )

        val now = clock.nowInstant()
        val cutoff = graceCutoff(now)
        var totalCandidates = 0
        var totalMarked = 0
        val commitmentRepository = commitmentRepositoryProvider.get()

        while (true) {
            val candidates = commitmentRepository.findOverdueCandidates(
                userId = userId,
                cutoff = cutoff,
                limit = BATCH_SIZE,
            )
            if (candidates.isEmpty()) break

            totalCandidates += candidates.size

            when (val marked = commitmentRepository.markOverdue(candidates.map { it.id }, now)) {
                is BecalmResult.Success -> totalMarked += marked.value
                is BecalmResult.Failure -> {
                    logger.w(TAG, "OverdueSweepWorker failed marking overdue rows: ${marked.error}")
                    return Result.retry()
                }
            }

            if (candidates.size < BATCH_SIZE) break
        }

        logger.d(
            TAG,
            "OverdueSweepWorker complete candidates=$totalCandidates marked=$totalMarked cutoff=$cutoff",
        )
        return Result.success(
            workDataOf(
                KEY_CANDIDATE_COUNT to totalCandidates,
                KEY_MARKED_COUNT to totalMarked,
            ),
        )
    }

    public companion object {
        private const val TAG: String = "OverdueSweep"

        /** 24-hour grace window mandated by CMT-011. */
        public val GRACE_PERIOD = 24.hours

        /** Worker page size; kept aligned with other batch-style background workers. */
        public const val BATCH_SIZE: Int = 100

        /** Result output key for the number of candidate rows seen in this run. */
        public const val KEY_CANDIDATE_COUNT: String = "candidate_count"

        /** Result output key for the number of rows actually marked overdue. */
        public const val KEY_MARKED_COUNT: String = "marked_count"

        /** Public helper so unit tests can lock the 24-hour cutoff contract directly. */
        public fun graceCutoff(now: Instant): Instant = now - GRACE_PERIOD
    }
}
