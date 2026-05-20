package com.becalm.android.ui.today

import com.becalm.android.R
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkRelationType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.local.db.entity.ScheduleEventLinkStatus
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.isActive
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.isCalendarSource
import com.becalm.android.ui.main.buildSourceStatusUiMap
import com.becalm.android.ui.main.deriveOverallState

internal object TodayTimelineProjector {
    private const val COUNTERPARTY_DISPLAY_MAX = 30

    fun buildTimeline(
        commitments: List<TodayCommitmentRow>,
        calendarEvents: List<CalendarEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
    ): List<TimelineItem> {
        val scheduleCommitmentKeys = commitments
            .filter { row ->
                row.itemType == CommitmentItemType.SCHEDULE &&
                    row.sourceType?.isCalendarSource() == true &&
                    !row.sourceRef.isNullOrBlank()
            }
            .mapTo(mutableSetOf()) { row -> row.sourceType to row.sourceRef }
        val sameScheduleLinks = scheduleLinks.filter { it.isSameScheduleLink() }
        val absorbedCommitmentIds = sameScheduleLinks.mapNotNullTo(mutableSetOf()) { it.commitmentId }
        val sourceTypesByCalendarId = sameScheduleLinks
            .groupBy { it.calendarEventId.orEmpty() }
            .mapValues { (_, rows) -> rows.map { it.sourceType }.distinct() }
        val visibleCommitments = commitments.filterNot { row ->
            row.id in absorbedCommitmentIds ||
                CommitmentDisplayPolicy.shouldHideNonPersonLifecycleItem(
                    itemType = row.itemType,
                    title = row.title,
                    sourceTitle = row.sourceTitle,
                    counterpartyDisplayName = row.counterpartyDisplayName,
                )
        }
        val visibleCalendarEvents = calendarEvents.filterNot { event ->
            event.sourceRef != null && (event.sourceType to event.sourceRef) in scheduleCommitmentKeys
        }
        return (visibleCommitments.map { it.toTimelineItem() } + visibleCalendarEvents.map { it.toTimelineItem(sourceTypesByCalendarId[it.id].orEmpty()) })
            .sortedWith(
                compareBy<TimelineItem> { !it.isTimed }
                    .thenBy { it.timelineAt ?: it.sortKey }
                    .thenBy { it.title },
            )
    }

    private fun TodayCommitmentRow.toTimelineItem(): TimelineItem.Commitment =
        TimelineItem.Commitment(
            id = id,
            itemType = itemType,
            title = title,
            direction = direction,
            sourceType = sourceType,
            scheduleStatus = scheduleStatus,
            rowTreatment = if (itemType == CommitmentItemType.SCHEDULE) {
                TodayCommitmentRowTreatment.SCHEDULE
            } else {
                TodayCommitmentRowTreatment.ACTION
            },
            counterpartyDisplayName = counterpartyDisplayName?.take(COUNTERPARTY_DISPLAY_MAX),
            dueAt = dueAt,
            dueIsApproximate = dueIsApproximate,
            dueHint = dueHint,
            sortKey = sortKey,
            timelineAt = dueAt?.takeUnless { dueIsApproximate },
            isTimed = dueAt != null && !dueIsApproximate,
        )

    private fun CalendarEventEntity.toTimelineItem(relatedSourceTypes: List<String>): TimelineItem =
        if (!attendeesRaw.isNullOrBlank()) {
            TimelineItem.Meeting(
                id = id,
                title = title,
                attendeesRaw = attendeesRaw,
                relatedSourceTypes = relatedSourceTypes,
                location = location,
                status = status,
                availability = availability,
                isAllDay = isAllDay,
                sortKey = startAt,
            )
        } else {
            TimelineItem.CalendarEvent(
                id = id,
                title = title,
                relatedSourceTypes = relatedSourceTypes,
                location = location,
                status = status,
                availability = availability,
                isAllDay = isAllDay,
                sortKey = startAt,
            )
        }

    private fun ScheduleEventLinkEntity.isSameScheduleLink(): Boolean {
        if (commitmentId == null || calendarEventId == null) return false
        if (status == ScheduleEventLinkStatus.AUTO_LINKED) {
            return relationType in setOf(ScheduleEventLinkRelationType.CONFIRMS, ScheduleEventLinkRelationType.ENRICHES)
        }
        return status == ScheduleEventLinkStatus.APPROVED &&
            resolutionChoice in setOf(
                ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
                ScheduleEventLinkResolutionChoice.CALENDAR,
                ScheduleEventLinkResolutionChoice.SOURCE,
            )
    }
}

internal object TodaySyncProjector {
    fun buildUiState(
        snapshot: TodaySnapshot,
        refreshing: Boolean,
    ): TodayUiState {
        if (snapshot.userId == null) {
            return TodayUiState(
                loading = false,
                processingPaused = snapshot.processingPaused,
                refreshing = refreshing,
                error = UiMessage.resource(R.string.today_error_sign_in_required),
            )
        }
        val statusMap = buildSourceStatusUiMap(snapshot.sourceStatuses)
        val timeline = TodayTimelineProjector.buildTimeline(
            commitments = snapshot.commitments,
            calendarEvents = snapshot.calendarEvents,
            scheduleLinks = snapshot.scheduleLinks,
        )
        return TodayUiState(
            loading = false,
            timeline = timeline,
            personFocus = buildTodayPersonFocus(timeline),
            scheduleConflictReviewItems = buildScheduleConflictReviewItems(
                commitments = snapshot.commitments,
                calendarEvents = snapshot.calendarEvents,
                scheduleLinks = snapshot.scheduleLinks,
            ),
            sourceStatus = statusMap,
            overallSyncing = snapshot.sourceStatuses.any { it.status == SourceConnectionStatus.SYNCING },
            overall = deriveOverallState(snapshot.sourceStatuses),
            processingStatus = buildProcessingStatus(snapshot.processingStates, snapshot.now),
            processingPaused = snapshot.processingPaused,
            refreshing = refreshing,
            error = null,
        )
    }

    private fun buildScheduleConflictReviewItems(
        commitments: List<TodayCommitmentRow>,
        calendarEvents: List<CalendarEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity>,
    ): List<ScheduleConflictReviewItem> {
        val commitmentsById = commitments.associateBy { it.id }
        val calendarEventsById = calendarEvents.associateBy { it.id }
        return scheduleLinks
            .filter {
                it.relationType == ScheduleEventLinkRelationType.CONFLICTS &&
                    it.status == ScheduleEventLinkStatus.NEEDS_REVIEW
            }
            .mapNotNull { link ->
                val calendar = link.calendarEventId?.let(calendarEventsById::get)
                val source = link.commitmentId?.let(commitmentsById::get)
                if (calendar == null && source == null) return@mapNotNull null
                ScheduleConflictReviewItem(
                    linkId = link.id,
                    calendarTitle = calendar?.title ?: link.calendarSourceRef.orEmpty(),
                    calendarStartAt = calendar?.startAt,
                    calendarStatus = calendar?.status,
                    sourceTitle = link.proposedTitle ?: source?.title ?: "",
                    sourceStartAt = link.proposedStartAt ?: source?.dueAt,
                    sourceStatus = source?.scheduleStatus,
                    sourceType = link.sourceType,
                    evidence = link.evidence,
                )
            }
    }

    private fun buildProcessingStatus(
        states: List<ProcessingSourceState>,
        now: kotlinx.datetime.Instant,
    ): TodayProcessingStatusUi {
        val visibleStates = states.filter { it.shouldShowOnToday(now) }
        val activeStates = visibleStates.filter { it.phase.isActive }
        val actionStates = visibleStates.filter {
            it.phase == ProcessingPhase.BLOCKED || it.phase == ProcessingPhase.ERROR
        }
        val latestState = visibleStates.maxByOrNull { state ->
            state.updatedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE
        }
        return TodayProcessingStatusUi(
            activeCount = activeStates.size,
            actionCount = actionStates.size,
            activeItemCount = activeStates.sumOf { it.itemCount },
            latestPhase = latestState?.phase,
            latestUpdatedAt = latestState?.updatedAt,
        )
    }

    private fun ProcessingSourceState.shouldShowOnToday(now: kotlinx.datetime.Instant): Boolean =
        when {
            phase == ProcessingPhase.IDLE -> false
            phase == ProcessingPhase.BLOCKED || phase == ProcessingPhase.ERROR -> true
            phase.isActive -> wasUpdatedWithin(now, ACTIVE_STATUS_STALE_AFTER_MS)
            else -> wasUpdatedWithin(now, RECENT_TERMINAL_STATUS_VISIBLE_MS)
        }

    private fun ProcessingSourceState.wasUpdatedWithin(
        now: kotlinx.datetime.Instant,
        windowMs: Long,
    ): Boolean {
        val updatedAtMs = updatedAt?.toEpochMilliseconds() ?: return false
        val ageMs = now.toEpochMilliseconds() - updatedAtMs
        return ageMs in 0L..windowMs
    }

    private const val ACTIVE_STATUS_STALE_AFTER_MS = 30L * 60L * 1_000L
    private const val RECENT_TERMINAL_STATUS_VISIBLE_MS = 10L * 60L * 1_000L
}
