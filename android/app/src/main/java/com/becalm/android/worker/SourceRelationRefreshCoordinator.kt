package com.becalm.android.worker

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import kotlinx.datetime.Instant

/**
 * Common post-sync refresh path for every source family.
 *
 * Trigger ownership still differs by source: local IMAP uploads raw rows, backend-managed
 * mail/calendar asks Railway to sync provider APIs, and voice writes extraction results
 * directly. After that source-specific step, this coordinator pulls the same relation
 * mirrors and enqueues the same person-index rebuild so People/Today/Commitment cards
 * are updated through one path.
 */
internal class SourceRelationRefreshCoordinator(
    private val rawIngestionRepository: RawIngestionRepository? = null,
    private val calendarEventRepository: CalendarEventRepository? = null,
    private val commitmentRepository: CommitmentRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) {

    suspend fun refresh(
        userId: String,
        plan: SourceRelationRefreshPlan,
    ): BecalmResult<SourceRelationRefreshStats> {
        var rawUpserted = 0
        var calendarUpserted = 0
        var sourceParticipantUpserted = 0
        var commitmentUpserted = 0
        var commitmentParticipantUpserted = 0

        plan.rawSourceType?.let { sourceType ->
            val repository = rawIngestionRepository
                ?: return missingRepository("RawIngestionRepository")
            when (val result = repository.refreshSince(userId = userId, sourceType = sourceType, since = null)) {
                is BecalmResult.Success -> {
                    rawUpserted = result.value.upserted
                    logger.d(
                        TAG,
                        "raw refresh source=$sourceType fetched=${result.value.fetched} upserted=${result.value.upserted}",
                    )
                }
                is BecalmResult.Failure -> return result
            }
        }

        plan.calendarRefresh?.let { calendarPlan ->
            val repository = calendarEventRepository
                ?: return missingRepository("CalendarEventRepository")
            when (
                val result = repository.refreshSince(
                    userId = userId,
                    since = null,
                    rangeStart = calendarPlan.rangeStart,
                    rangeEnd = calendarPlan.rangeEnd,
                )
            ) {
                is BecalmResult.Success -> {
                    calendarUpserted = result.value.upserted
                    logger.d(
                        TAG,
                        "calendar refresh source=${plan.sourceType} fetched=${result.value.fetched} upserted=${result.value.upserted}",
                    )
                }
                is BecalmResult.Failure -> return result
            }
        }

        when (plan.sourceParticipantRefreshScope) {
            SourceParticipantRefreshScope.SOURCE -> {
                when (
                    val result = sourceEventParticipantRepository.refreshSince(
                        userId = userId,
                        sourceType = plan.sourceType,
                        since = null,
                    )
                ) {
                    is BecalmResult.Success -> {
                        sourceParticipantUpserted = result.value.upserted
                        logger.d(
                            TAG,
                            "source participant refresh source=${plan.sourceType} fetched=${result.value.fetched} upserted=${result.value.upserted}",
                        )
                    }
                    is BecalmResult.Failure -> return result
                }
            }
            SourceParticipantRefreshScope.ALL -> {
                when (val result = sourceEventParticipantRepository.refreshSince(userId = userId, since = null)) {
                    is BecalmResult.Success -> {
                        sourceParticipantUpserted = result.value.upserted
                        logger.d(
                            TAG,
                            "source participant refresh all fetched=${result.value.fetched} upserted=${result.value.upserted}",
                        )
                    }
                    is BecalmResult.Failure -> return result
                }
            }
        }

        when (val result = commitmentRepository.refreshSince(userId = userId, since = null)) {
            is BecalmResult.Success -> {
                commitmentUpserted = result.value.upserted
                logger.d(
                    TAG,
                    "commitment refresh source=${plan.sourceType} fetched=${result.value.fetched} upserted=${result.value.upserted}",
                )
            }
            is BecalmResult.Failure -> return result
        }

        when (val result = commitmentParticipantRepository.refreshSince(userId = userId, since = null)) {
            is BecalmResult.Success -> {
                commitmentParticipantUpserted = result.value.upserted
                logger.d(
                    TAG,
                    "commitment participant refresh source=${plan.sourceType} fetched=${result.value.fetched} upserted=${result.value.upserted}",
                )
            }
            is BecalmResult.Failure -> return result
        }

        val stats = SourceRelationRefreshStats(
            rawUpserted = rawUpserted,
            calendarUpserted = calendarUpserted,
            sourceParticipantUpserted = sourceParticipantUpserted,
            commitmentUpserted = commitmentUpserted,
            commitmentParticipantUpserted = commitmentParticipantUpserted,
            localWriteCount = plan.localWriteCount,
        )
        if (stats.changedCount > 0) {
            workScheduler.enqueuePersonInteractionIndex()
        }
        return BecalmResult.Success(stats)
    }

    private fun missingRepository(name: String): BecalmResult.Failure =
        BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("$name is required for this refresh plan")))

    private companion object {
        private const val TAG = "SourceRelationRefresh"
    }
}

internal data class SourceRelationRefreshPlan(
    val sourceType: String,
    val rawSourceType: String? = null,
    val calendarRefresh: CalendarRelationRefresh? = null,
    val sourceParticipantRefreshScope: SourceParticipantRefreshScope = SourceParticipantRefreshScope.SOURCE,
    val localWriteCount: Int = 0,
)

internal enum class SourceParticipantRefreshScope {
    SOURCE,
    ALL,
}

internal data class CalendarRelationRefresh(
    val rangeStart: Instant? = null,
    val rangeEnd: Instant? = null,
)

internal data class SourceRelationRefreshStats(
    val rawUpserted: Int,
    val calendarUpserted: Int,
    val sourceParticipantUpserted: Int,
    val commitmentUpserted: Int,
    val commitmentParticipantUpserted: Int,
    val localWriteCount: Int,
) {
    val changedCount: Int =
        rawUpserted +
            calendarUpserted +
            sourceParticipantUpserted +
            commitmentUpserted +
            commitmentParticipantUpserted +
            localWriteCount
}
