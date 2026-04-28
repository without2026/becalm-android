package com.becalm.android.ui.today

import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus

internal object TodayTimelineProjector {
    private const val COUNTERPARTY_DISPLAY_MAX = 30

    fun buildTimeline(
        commitments: List<TodayCommitmentRow>,
        calendarEvents: List<CalendarEventEntity>,
    ): List<TimelineItem> {
        val scheduleCommitmentKeys = commitments
            .filter { row ->
                row.itemType == CommitmentItemType.SCHEDULE &&
                    row.sourceType in CALENDAR_SOURCE_TYPES &&
                    !row.sourceRef.isNullOrBlank()
            }
            .mapTo(mutableSetOf()) { row -> row.sourceType to row.sourceRef }
        val visibleCalendarEvents = calendarEvents.filterNot { event ->
            event.sourceRef != null && (event.sourceType to event.sourceRef) in scheduleCommitmentKeys
        }
        return (commitments.map { it.toTimelineItem() } + visibleCalendarEvents.map { it.toTimelineItem() })
            .sortedBy { it.sortKey }
    }

    private fun TodayCommitmentRow.toTimelineItem(): TimelineItem.Commitment =
        TimelineItem.Commitment(
            id = id,
            itemType = itemType,
            title = title,
            direction = direction,
            scheduleStatus = scheduleStatus,
            counterpartyDisplayName = counterpartyDisplayName?.take(COUNTERPARTY_DISPLAY_MAX),
            sortKey = sortKey,
        )

    private fun CalendarEventEntity.toTimelineItem(): TimelineItem =
        if (!attendeesRaw.isNullOrBlank()) {
            TimelineItem.Meeting(
                id = id,
                title = title,
                attendeesRaw = attendeesRaw,
                sortKey = startAt,
            )
        } else {
            TimelineItem.CalendarEvent(
                id = id,
                title = title,
                sortKey = startAt,
            )
        }

    private val CALENDAR_SOURCE_TYPES = setOf(
        SourceType.GOOGLE_CALENDAR,
        SourceType.OUTLOOK_CALENDAR,
    )
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
                error = "not authenticated",
            )
        }
        val statusMap = snapshot.sourceStatuses.associate { status ->
            status.sourceType to SourceStatusUi(
                syncing = status.status == SourceConnectionStatus.SYNCING,
                statusLabel = status.status.name,
                errorMessage = status.errorMessage,
                lastSyncedAt = status.lastSyncedAt,
            )
        }
        val timeline = TodayTimelineProjector.buildTimeline(
            commitments = snapshot.commitments,
            calendarEvents = snapshot.calendarEvents,
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
