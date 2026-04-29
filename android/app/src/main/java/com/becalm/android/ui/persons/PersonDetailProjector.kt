package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity

internal object PersonDetailProjector {
    private const val ACTION_STATE_COMPLETED: String = "completed"
    private const val ACTION_STATE_CANCELLED: String = "cancelled"
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
            emailInteractionCount = rawEvents.count { it.sourceType.isEmailSource() },
            callInteractionCount = rawEvents.count { it.sourceType.isCallSource() },
            meetingCount = calendarEvents.size,
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

    fun buildIndexedState(
        personId: String,
        identities: List<PersonIdentityEntity>,
        enrichmentRows: List<PersonEnrichmentEntity>,
        interactions: List<PersonInteractionEntity>,
        completedExpanded: Boolean,
    ): PersonDetailUiState {
        val enrichment = findEnrichment(identities, enrichmentRows)
        val displayFallback = identities.firstOrNull { !it.displayNameHint.isNullOrBlank() }?.displayNameHint
            ?: identities.firstOrNull()?.rawValue
            ?: personId
        val commitmentRows = interactions
            .filter { it.interactionKind == "commitment" }
            .map(::toIndexedCommitmentRow)
        val (completed, pending) = commitmentRows.partition { row ->
            row.actionState.equals(ACTION_STATE_COMPLETED, ignoreCase = true) ||
                row.actionState.equals(ACTION_STATE_CANCELLED, ignoreCase = true)
        }
        val history = interactions
            .filterNot { it.interactionKind == "commitment" }
            .map(::toIndexedHistoryRow)
            .sortedByDescending { row ->
                when (row) {
                    is InteractionRow.Event -> row.timestamp
                    is InteractionRow.CalendarMeeting -> row.timestamp
                    is InteractionRow.Commitment -> row.timestamp
                }
            }
        return PersonDetailUiState(
            personRef = personId,
            displayName = enrichment?.displayName ?: enrichment?.nickname ?: displayFallback,
            nickname = enrichment?.nickname,
            companyName = enrichment?.company,
            jobTitle = enrichment?.title,
            eventCount = interactions.count { it.interactionKind != "commitment" },
            emailInteractionCount = interactions.count { it.interactionKind == "email" },
            callInteractionCount = interactions.count { it.interactionKind == "call" },
            meetingCount = interactions.count { it.interactionKind == "calendar" },
            pendingCommitmentCount = pending.size,
            channelSources = interactions.map { it.sourceType }.toSet(),
            completedExpanded = completedExpanded,
            pendingCommitments = pending.sortedByDescending { it.timestamp },
            completedCommitments = completed.sortedByDescending { it.timestamp },
            interactionHistory = history,
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

    private fun toIndexedCommitmentRow(interaction: PersonInteractionEntity): InteractionRow.Commitment =
        InteractionRow.Commitment(
            timestamp = interaction.occurredAt,
            title = interaction.title.orEmpty(),
            itemType = interaction.role,
            direction = interaction.direction,
            actionState = interaction.status,
            scheduleStatus = interaction.status.takeIf { interaction.role == CommitmentItemType.SCHEDULE },
            decisionStatus = interaction.status.takeIf { interaction.role == CommitmentItemType.DECISION },
        )

    private fun toIndexedHistoryRow(interaction: PersonInteractionEntity): InteractionRow =
        if (interaction.interactionKind == "calendar") {
            InteractionRow.CalendarMeeting(
                timestamp = interaction.occurredAt,
                title = interaction.title.orEmpty(),
            )
        } else {
            InteractionRow.Event(
                id = interaction.sourceRef.removePrefix("raw:"),
                timestamp = interaction.occurredAt,
                source = interaction.sourceType,
                summary = interaction.title,
                snippet = interaction.snippet?.take(SNIPPET_PREVIEW_CHAR_LIMIT),
                commitmentsExtractedCount = 0,
            )
        }

    private fun findEnrichment(
        identities: List<PersonIdentityEntity>,
        enrichmentRows: List<PersonEnrichmentEntity>,
    ): PersonEnrichmentEntity? {
        if (identities.isEmpty() || enrichmentRows.isEmpty()) return null
        val enrichmentByRef = enrichmentRows.associateBy { it.personRef }
        return identities.firstNotNullOfOrNull { identity ->
            enrichmentByRef[identity.rawValue]
                ?: enrichmentByRef[identity.identityKey.substringAfter(':', identity.identityKey)]
        }
    }

    private fun String.isEmailSource(): Boolean =
        equals("gmail", ignoreCase = true) ||
            equals("outlook_mail", ignoreCase = true) ||
            equals("naver_imap", ignoreCase = true) ||
            equals("daum_imap", ignoreCase = true) ||
            contains("email", ignoreCase = true) ||
            contains("mail", ignoreCase = true) ||
            contains("imap", ignoreCase = true)

    private fun String.isCallSource(): Boolean =
        equals("call_recording", ignoreCase = true) ||
            contains("call", ignoreCase = true)

    private data class InteractionSections(
        val pendingCommitments: List<InteractionRow.Commitment>,
        val completedCommitments: List<InteractionRow.Commitment>,
        val interactionHistory: List<InteractionRow>,
    )
}
