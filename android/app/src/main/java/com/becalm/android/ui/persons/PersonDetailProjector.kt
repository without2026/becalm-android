package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity

internal object PersonDetailProjector {
    private const val ACTION_STATE_COMPLETED: String = "completed"
    private const val ACTION_STATE_CANCELLED: String = "cancelled"
    private const val SNIPPET_PREVIEW_CHAR_LIMIT: Int = 200

    fun buildIndexedState(
        personId: String,
        identities: List<PersonIdentityEntity>,
        enrichmentRows: List<PersonEnrichmentEntity>,
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
    ): PersonDetailUiState {
        val enrichment = findEnrichment(identities, enrichmentRows)
        val displayFallback = identities.firstOrNull { !it.displayNameHint.isNullOrBlank() }?.displayNameHint
            ?: identities.firstOrNull()?.rawValue
            ?: personId
        val commitmentRows = interactions
            .filter { it.interactionKind == "commitment" }
            .map(::toIndexedCommitmentRow)
        val history = interactions
            .filterNot { it.interactionKind == "commitment" }
            .collapseDuplicateHistoryInteractions()
            .map(::toIndexedHistoryRow)
        val timeline = (commitmentRows + history)
            .sortedByDescending { it.timestamp() }
        val sourceEventCards = buildIndexedSourceEventCards(
            interactions = interactions,
            rawEvents = rawEvents,
        )
        return PersonDetailUiState(
            personId = personId,
            displayName = enrichment?.displayName ?: enrichment?.nickname ?: displayFallback,
            nickname = enrichment?.nickname,
            companyName = enrichment?.company,
            jobTitle = enrichment?.title,
            eventCount = interactions.count { it.interactionKind != "commitment" },
            emailInteractionCount = interactions.count { it.interactionKind == "email" },
            callInteractionCount = interactions.count { it.interactionKind == "call" },
            meetingCount = interactions.count { it.interactionKind == "calendar" },
            pendingCommitmentCount = commitmentRows.count { !it.isTerminalCommitment() },
            channelSources = interactions.map { it.sourceType }.toSet(),
            timeline = timeline,
            sourceEventCards = sourceEventCards,
            loading = false,
            error = null,
        )
    }

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
            val rawEventId = interaction.sourceRef
                .takeIf { it.startsWith("raw:") }
                ?.removePrefix("raw:")
            InteractionRow.Event(
                id = interaction.sourceRef,
                rawEventId = rawEventId,
                timestamp = interaction.occurredAt,
                source = interaction.sourceType,
                summary = interaction.title,
                snippet = interaction.snippet?.take(SNIPPET_PREVIEW_CHAR_LIMIT),
                commitmentsExtractedCount = 0,
            )
        }

    private fun buildIndexedSourceEventCards(
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
    ): List<SourceEventCardProjection> {
        val rawById = rawEvents.associateBy { it.id }
        val buckets = linkedMapOf<String, MutableSourceEventCard>()
        interactions.forEach { interaction ->
            val key = interaction.sourceEventKey()
            val raw = interaction.sourceEventId?.let(rawById::get)
                ?: interaction.sourceRef.takeIf { it.startsWith("raw:") }
                    ?.removePrefix("raw:")
                    ?.let(rawById::get)
            val bucket = buckets.getOrPut(key) {
                MutableSourceEventCard(
                    sourceEventKey = key,
                    sourceType = interaction.sourceType,
                    rawEventId = raw?.id,
                    occurredAt = interaction.occurredAt,
                    title = interaction.title ?: raw?.eventTitle,
                    snippet = interaction.snippet ?: raw?.eventSnippet?.take(SNIPPET_PREVIEW_CHAR_LIMIT),
                    commitmentsExtractedCount = raw?.commitmentsExtractedCount ?: 0,
                    sourceRef = interaction.sourceRef,
                )
            }
            if (interaction.interactionKind == "commitment") {
                bucket.addCommitment(interaction.toSummary())
            }
        }
        return buckets.values
            .map(MutableSourceEventCard::toProjection)
            .sortedByDescending { it.occurredAt }
            .withNextActions()
    }

    private fun PersonInteractionEntity.toSummary(): PersonDetailCommitmentSummary =
        PersonDetailCommitmentSummary(
            title = title.orEmpty(),
            itemType = role,
            direction = direction,
            status = status,
        )

    private fun PersonInteractionEntity.sourceEventKey(): String =
        sourceEventId?.let { "raw:$it" }
            ?: sourceRef.takeIf { it.startsWith("raw:") || it.startsWith("calendar:") }
            ?: "$sourceType:$sourceRef"

    private fun List<SourceEventCardProjection>.withNextActions(): List<SourceEventCardProjection> {
        val ascending = sortedBy { it.occurredAt }
        val nextByKey = mutableMapOf<String, PersonDetailNextAction>()
        ascending.zipWithNext { current, next ->
            inferNextAction(current, next)?.let { nextByKey[current.sourceEventKey] = it }
        }
        return map { card -> card.copy(nextAction = nextByKey[card.sourceEventKey]) }
    }

    private fun inferNextAction(
        current: SourceEventCardProjection,
        next: SourceEventCardProjection,
    ): PersonDetailNextAction? {
        val text = listOfNotNull(current.title, current.snippet)
            .joinToString(" ")
            .lowercase()
        val nextLabel = when {
            next.sourceType.isCallSource() && (text.contains("전화") || text.contains("call")) -> "전화"
            next.sourceType.isEmailSource() && (
                text.contains("답장") ||
                    text.contains("답신") ||
                    text.contains("메일") ||
                    text.contains("email") ||
                    text.contains("reply")
                ) -> "이메일 답신"
            next.sourceType.isCalendarSource() && (
                text.contains("미팅") ||
                    text.contains("회의") ||
                    text.contains("일정") ||
                    text.contains("meeting") ||
                    text.contains("schedule")
                ) -> "일정"
            else -> null
        } ?: return null
        return PersonDetailNextAction(label = nextLabel, nextSourceEventKey = next.sourceEventKey)
    }

    private data class MutableSourceEventCard(
        val sourceEventKey: String,
        val sourceType: String,
        val rawEventId: String?,
        val occurredAt: kotlinx.datetime.Instant,
        val title: String?,
        val snippet: String?,
        val commitmentsExtractedCount: Int = 0,
        val sourceRef: String?,
        val myActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val theirActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val schedules: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
    ) {
        fun addCommitment(summary: PersonDetailCommitmentSummary) {
            when {
                summary.itemType == CommitmentItemType.SCHEDULE -> schedules += summary
                summary.direction == "take" -> theirActions += summary
                else -> myActions += summary
            }
        }

        fun toProjection(): SourceEventCardProjection =
            SourceEventCardProjection(
                sourceEventKey = sourceEventKey,
                sourceType = sourceType,
                rawEventId = rawEventId,
                occurredAt = occurredAt,
                title = title,
                snippet = snippet,
                commitmentsExtractedCount = commitmentsExtractedCount,
                myActions = myActions.toList(),
                theirActions = theirActions.toList(),
                schedules = schedules.toList(),
            )
    }

    private fun List<PersonInteractionEntity>.collapseDuplicateHistoryInteractions(): List<PersonInteractionEntity> =
        groupBy { it.timelineDedupeKey() ?: "unique:${it.id}" }
            .values
            .map { group ->
                group.minWith(
                    compareBy<PersonInteractionEntity> { it.isCalendarLikeInteraction() }
                        .thenByDescending { it.confidence }
                        .thenByDescending { it.occurredAt },
                )
            }

    private fun PersonInteractionEntity.timelineDedupeKey(): String? {
        val normalizedTitle = title
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
        val sourceEventKey = sourceRef
            .takeIf { it.startsWith("raw:") }
            ?: sourceRef.takeIf { it.startsWith("calendar:") }
        return when {
            normalizedTitle != null -> "title:$normalizedTitle:${occurredAt.toEpochMilliseconds() / 60_000L}"
            sourceEventKey != null -> "source:$sourceEventKey"
            else -> null
        }
    }

    private fun PersonInteractionEntity.isCalendarLikeInteraction(): Boolean =
        interactionKind == "calendar" || sourceType.isCalendarSource()

    private fun InteractionRow.timestamp(): kotlinx.datetime.Instant = when (this) {
        is InteractionRow.Event -> timestamp
        is InteractionRow.Commitment -> timestamp
        is InteractionRow.CalendarMeeting -> timestamp
    }

    private fun InteractionRow.Commitment.isTerminalCommitment(): Boolean =
        actionState.equals(ACTION_STATE_COMPLETED, ignoreCase = true) ||
            actionState.equals(ACTION_STATE_CANCELLED, ignoreCase = true)

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

    private fun String.isCalendarSource(): Boolean =
        equals("google_calendar", ignoreCase = true) ||
            equals("outlook_calendar", ignoreCase = true) ||
            contains("calendar", ignoreCase = true)

}
