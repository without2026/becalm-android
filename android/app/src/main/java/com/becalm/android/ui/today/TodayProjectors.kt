package com.becalm.android.ui.today

import com.becalm.android.R
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkRelationType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkStatus
import com.becalm.android.data.repository.SourceConnectionStatus
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
        val confirmedLinks = scheduleLinks.filter {
            it.relationType == ScheduleEventLinkRelationType.CONFIRMS &&
                it.status in setOf(ScheduleEventLinkStatus.AUTO_LINKED, ScheduleEventLinkStatus.APPROVED) &&
                it.commitmentId != null &&
                it.calendarEventId != null
        }
        val confirmedCommitmentIds = confirmedLinks.mapNotNullTo(mutableSetOf()) { it.commitmentId }
        val sourceTypesByCalendarId = confirmedLinks
            .groupBy { it.calendarEventId.orEmpty() }
            .mapValues { (_, rows) -> rows.map { it.sourceType }.distinct() }
        val visibleCommitments = commitments.filterNot { row -> row.id in confirmedCommitmentIds }
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
                sortKey = startAt,
            )
        } else {
            TimelineItem.CalendarEvent(
                id = id,
                title = title,
                relatedSourceTypes = relatedSourceTypes,
                sortKey = startAt,
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
            sourceStatus = statusMap,
            overallSyncing = snapshot.sourceStatuses.any { it.status == SourceConnectionStatus.SYNCING },
            overall = deriveOverallState(snapshot.sourceStatuses),
            processingPaused = snapshot.processingPaused,
            refreshing = refreshing,
            error = null,
        )
    }
}
