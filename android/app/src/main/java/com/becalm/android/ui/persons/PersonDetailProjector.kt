package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity

internal object PersonDetailProjector {
    private const val ACTION_STATE_COMPLETED: String = "completed"
    private const val SNIPPET_PREVIEW_CHAR_LIMIT: Int = 200

    fun buildState(
        personRef: String,
        enrichment: PersonEnrichmentEntity?,
        rawEvents: List<RawIngestionEventEntity>,
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
        completedExpanded: Boolean,
    ): PersonDetailUiState {
        val sections = buildInteractions(rawEvents, commitments, calendarEvents)
        val resolvedDisplayName = enrichment?.displayName
            ?: enrichment?.nickname
            ?: personRef
        return PersonDetailUiState(
            personRef = personRef,
            displayName = resolvedDisplayName,
            nickname = enrichment?.nickname,
            companyName = enrichment?.company,
            jobTitle = enrichment?.title,
            eventCount = rawEvents.size,
            pendingCommitmentCount = sections.pendingCommitments.size,
            channelSources = (rawEvents.map { it.sourceType } + calendarEvents.map { it.sourceType }).toSet(),
            completedExpanded = completedExpanded,
            pendingCommitments = sections.pendingCommitments,
            completedCommitments = sections.completedCommitments,
            interactionHistory = sections.interactionHistory,
            loading = false,
            error = null,
        )
    }

    private fun buildInteractions(
        rawEvents: List<RawIngestionEventEntity>,
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
    ): InteractionSections {
        val eventRows: List<InteractionRow> = rawEvents.map(::toEventRow)
        val commitmentRows: List<InteractionRow.Commitment> = commitments.map(::toCommitmentRow)
        val calendarRows: List<InteractionRow> = calendarEvents.map(::toCalendarMeetingRow)
        val (completed, pending) = commitmentRows.partition { commitment ->
            commitment.itemType == CommitmentItemType.ACTION &&
                commitment.actionState.equals(ACTION_STATE_COMPLETED, ignoreCase = true)
        }
        val history = (eventRows + calendarRows)
            .sortedByDescending { row ->
                when (row) {
                    is InteractionRow.Event -> row.timestamp
                    is InteractionRow.CalendarMeeting -> row.timestamp
                    else -> error("history must not contain ${row::class.simpleName}")
                }
            }
        return InteractionSections(
            pendingCommitments = pending.sortedByDescending { it.timestamp },
            completedCommitments = completed.sortedByDescending { it.timestamp },
            interactionHistory = history,
        )
    }

    private fun toEventRow(event: RawIngestionEventEntity): InteractionRow.Event =
        InteractionRow.Event(
            id = event.id,
            timestamp = event.timestamp,
            source = event.sourceType,
            summary = event.eventTitle,
            snippet = event.eventSnippet?.take(SNIPPET_PREVIEW_CHAR_LIMIT),
            commitmentsExtractedCount = event.commitmentsExtractedCount,
        )

    private fun toCommitmentRow(commitment: CommitmentEntity): InteractionRow.Commitment =
        InteractionRow.Commitment(
            timestamp = commitment.sourceEventOccurredAt,
            title = commitment.title,
            itemType = commitment.itemType,
            direction = commitment.direction,
            actionState = commitment.actionState,
            scheduleStatus = commitment.scheduleStatus,
            decisionStatus = commitment.decisionStatus,
        )

    private fun toCalendarMeetingRow(meeting: CalendarEventEntity): InteractionRow.CalendarMeeting =
        InteractionRow.CalendarMeeting(
            timestamp = meeting.startAt,
            title = meeting.title,
        )

    private data class InteractionSections(
        val pendingCommitments: List<InteractionRow.Commitment>,
        val completedCommitments: List<InteractionRow.Commitment>,
        val interactionHistory: List<InteractionRow>,
    )
}
