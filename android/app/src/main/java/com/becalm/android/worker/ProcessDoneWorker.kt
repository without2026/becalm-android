package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.CommitmentProgressEventDao
import com.becalm.android.data.local.db.dao.CompletionMatchCandidateRow
import com.becalm.android.data.local.db.entity.CommitmentProgressEventEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Applies structured completion evidence to local action commitments.
 *
 * The worker does not run LLM/raw-text judgment. It only consumes rows already persisted in
 * `commitment_progress_events` and auto-applies high-confidence, same-person candidates.
 * Upload mirroring stays local-first: [CommitmentDao.completeActionIfEligible] marks the row
 * pending, then [UploadWorker] syncs it to Railway.
 */
@HiltWorker
public class ProcessDoneWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val progressEventDaoProvider: Provider<CommitmentProgressEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val reminderScheduler: ReminderScheduler,
    private val clock: Clock,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        authRepository: AuthRepository,
        progressEventDao: CommitmentProgressEventDao,
        commitmentDao: CommitmentDao,
        workScheduler: WorkScheduler,
        reminderScheduler: ReminderScheduler,
        clock: Clock,
        processingPauseGate: ProcessingPauseGate,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        authRepositoryProvider = Provider { authRepository },
        progressEventDaoProvider = Provider { progressEventDao },
        commitmentDaoProvider = Provider { commitmentDao },
        workSchedulerProvider = Provider { workScheduler },
        reminderScheduler = reminderScheduler,
        clock = clock,
        processingPauseGate = processingPauseGate,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        if (processingPauseGate.shouldSkip(TAG)) {
            return@withContext success(0, 0, 0)
        }
        val userId = authRepositoryProvider.get().currentSession()?.userId
            ?: return@withContext success(0, 0, 0)

        try {
            var totalCandidates = 0
            var totalMarked = 0
            var totalReview = 0
            val progressEventDao = progressEventDaoProvider.get()
            val commitmentDao = commitmentDaoProvider.get()

            while (true) {
                val signals = progressEventDao.findPendingCompletionSignals(
                    userId = userId,
                    limit = BATCH_SIZE,
                )
                if (signals.isEmpty()) break
                totalCandidates += signals.size

                for (signal in signals) {
                    when (
                        processSignal(
                            userId = userId,
                            signal = signal,
                            progressEventDao = progressEventDao,
                            commitmentDao = commitmentDao,
                        )
                    ) {
                        SignalOutcome.AUTO_APPLIED -> totalMarked += 1
                        SignalOutcome.NEEDS_REVIEW -> totalReview += 1
                    }
                }

                if (signals.size < BATCH_SIZE) break
            }

            if (totalMarked > 0) {
                workSchedulerProvider.get().enqueueUpload()
            }
            logger.d(
                TAG,
                "ProcessDoneWorker complete candidates=$totalCandidates marked=$totalMarked review=$totalReview",
            )
            success(totalCandidates, totalMarked, totalReview)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            logger.w(TAG, "ProcessDoneWorker failed: ${error.message}")
            Result.retry()
        }
    }

    private suspend fun processSignal(
        userId: String,
        signal: CommitmentProgressEventEntity,
        progressEventDao: CommitmentProgressEventDao,
        commitmentDao: CommitmentDao,
    ): SignalOutcome {
        val now = clock.nowInstant()
        if (signal.confidence < AUTO_APPLY_CONFIDENCE) {
            progressEventDao.markNeedsReview(signal.id, REASON_LOW_CONFIDENCE, now)
            return SignalOutcome.NEEDS_REVIEW
        }
        val personId = signal.personId?.trim()?.takeIf { it.isNotEmpty() }
        if (personId == null) {
            progressEventDao.markNeedsReview(signal.id, REASON_PERSON_NOT_RESOLVED, now)
            return SignalOutcome.NEEDS_REVIEW
        }

        val candidates = commitmentDao.findCompletionMatchCandidates(
            userId = userId,
            personId = personId,
            sourceEventId = signal.sourceEventId,
            conversationRef = signal.conversationRef.normalizedRef(),
            limit = CANDIDATE_LIMIT,
        )
        val match = selectMatch(signal, candidates)
        if (match == null) {
            progressEventDao.markNeedsReview(signal.id, REASON_NO_HIGH_CONFIDENCE_MATCH, now)
            return SignalOutcome.NEEDS_REVIEW
        }

        val updated = commitmentDao.completeActionIfEligible(
            userId = userId,
            id = match.row.id,
            updatedAt = now,
        )
        if (updated <= 0) {
            progressEventDao.markNeedsReview(signal.id, REASON_COMMITMENT_NOT_UPDATED, now)
            return SignalOutcome.NEEDS_REVIEW
        }
        progressEventDao.markAutoApplied(
            id = signal.id,
            commitmentId = match.row.id,
            reason = match.reason,
            appliedAt = now,
        )
        reminderScheduler.cancel(match.row.id)
        return SignalOutcome.AUTO_APPLIED
    }

    private fun selectMatch(
        signal: CommitmentProgressEventEntity,
        candidates: List<CompletionMatchCandidateRow>,
    ): SelectedMatch? {
        val conversationRef = signal.conversationRef.normalizedRef()
        if (conversationRef != null) {
            candidates.firstOrNull { it.conversationRef.normalizedRef() == conversationRef }?.let { row ->
                return SelectedMatch(row, REASON_SAME_THREAD)
            }
        }

        val evidenceTokens = signal.evidenceQuote.completionTokens()
        return candidates
            .asSequence()
            .mapNotNull { row ->
                val candidateTokens = "${row.title} ${row.quote}".completionTokens()
                val shared = evidenceTokens.intersect(candidateTokens).size
                val similarity = tokenSimilarity(evidenceTokens, candidateTokens)
                row.takeIf {
                    shared >= FALLBACK_MIN_SHARED_TOKENS &&
                        similarity >= FALLBACK_TEXT_SIMILARITY
                }?.let { SelectedMatch(it, REASON_TEXT_SIMILARITY) }
            }
            .firstOrNull()
    }

    private enum class SignalOutcome {
        AUTO_APPLIED,
        NEEDS_REVIEW,
    }

    private data class SelectedMatch(
        val row: CompletionMatchCandidateRow,
        val reason: String,
    )

    public companion object {
        private const val TAG: String = "ProcessDone"
        public const val BATCH_SIZE: Int = 100
        private const val CANDIDATE_LIMIT: Int = 20
        private const val MAX_RETRIES: Int = 5
        private const val AUTO_APPLY_CONFIDENCE: Double = 0.85
        private const val FALLBACK_TEXT_SIMILARITY: Double = 0.4
        private const val FALLBACK_MIN_SHARED_TOKENS: Int = 2
        private const val REASON_SAME_THREAD: String = "same_thread_high_confidence"
        private const val REASON_TEXT_SIMILARITY: String = "text_similarity_high_confidence"
        private const val REASON_LOW_CONFIDENCE: String = "low_confidence"
        private const val REASON_PERSON_NOT_RESOLVED: String = "person_not_resolved"
        private const val REASON_NO_HIGH_CONFIDENCE_MATCH: String = "no_high_confidence_match"
        private const val REASON_COMMITMENT_NOT_UPDATED: String = "commitment_not_updated"
        public const val KEY_CANDIDATE_COUNT: String = "candidate_count"
        public const val KEY_MARKED_COUNT: String = "marked_count"
        public const val KEY_REVIEW_COUNT: String = "review_count"

        private fun success(candidates: Int, marked: Int, review: Int): Result =
            Result.success(
                workDataOf(
                    KEY_CANDIDATE_COUNT to candidates,
                    KEY_MARKED_COUNT to marked,
                    KEY_REVIEW_COUNT to review,
                ),
            )
    }
}

private fun String?.normalizedRef(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.completionTokens(): Set<String> =
    TOKEN_REGEX.findAll(lowercase())
        .map { it.value }
        .filter { it.length >= 2 }
        .toSet()

private fun tokenSimilarity(left: Set<String>, right: Set<String>): Double {
    val denominator = minOf(left.size, right.size)
    if (denominator == 0) return 0.0
    return left.intersect(right).size.toDouble() / denominator.toDouble()
}

private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
