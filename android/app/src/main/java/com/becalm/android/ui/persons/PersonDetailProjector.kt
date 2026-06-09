package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkRelationType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.ui.components.isTakeDirection
import kotlinx.datetime.Instant

internal object PersonDetailProjector {
    private const val SNIPPET_PREVIEW_CHAR_LIMIT: Int = 200

    fun buildIndexedState(
        personId: String,
        identities: List<PersonIdentityEntity>,
        enrichmentRows: List<PersonEnrichmentEntity>,
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
        commitments: List<CommitmentEntity> = emptyList(),
        calendarEvents: List<CalendarEventEntity> = emptyList(),
    ): PersonDetailUiState {
        val commitmentsById = commitments.associateBy { it.id }
        val calendarEventsById = calendarEvents.associateBy { it.id }
        val enrichment = findEnrichment(identities, enrichmentRows)
        val displayFallback = identities
            .mapNotNull { sanitizeDisplayName(it.displayNameHint) ?: sanitizeDisplayName(it.rawValue) }
            .firstOrNull()
            ?: UNKNOWN_PERSON_DISPLAY_NAME
        val sourceEventCards = buildIndexedSourceEventCards(
            interactions = interactions,
            rawEvents = rawEvents,
            scheduleLinks = scheduleLinks,
            commitmentsById = commitmentsById,
            calendarEventsById = calendarEventsById,
        )
        val timelineItems = buildTimelineItems(
            sourceEventCards = sourceEventCards,
            scheduleLinks = scheduleLinks,
            interactions = interactions,
            commitmentsById = commitmentsById,
            calendarEventsById = calendarEventsById,
        )
        return PersonDetailUiState(
            personId = personId,
            displayName = listOfNotNull(
                sanitizeDisplayName(enrichment?.displayName),
                sanitizeDisplayName(enrichment?.nickname),
                displayFallback,
            ).firstOrNull(),
            nickname = enrichment?.nickname,
            companyName = enrichment?.company,
            jobTitle = enrichment?.title,
            eventCount = interactions.count { it.interactionKind != "commitment" },
            emailInteractionCount = interactions.count { it.interactionKind == "email" },
            callInteractionCount = interactions.count { it.interactionKind == "call" },
            meetingCount = interactions.count {
                it.interactionKind == "calendar" || it.interactionKind == "meeting"
            },
            pendingCommitmentCount = interactions.count { it.isOpenCommitmentLoop(commitmentsById) },
            channelSources = interactions.map { it.sourceType }.toSet(),
            sourceEventCards = sourceEventCards,
            timelineItems = timelineItems,
            relationshipStartedAt = sourceEventCards.minOfOrNull { it.occurredAt },
            lastInteractionAt = sourceEventCards.maxOfOrNull { it.occurredAt },
            loading = false,
            error = null,
        )
    }

    private fun buildTimelineItems(
        sourceEventCards: List<SourceEventCardProjection>,
        scheduleLinks: List<ScheduleEventLinkEntity>,
        interactions: List<PersonInteractionEntity>,
        commitmentsById: Map<String, CommitmentEntity>,
        calendarEventsById: Map<String, CalendarEventEntity>,
    ): List<PersonTimelineItem> {
        val commitmentTitlesById = interactions
            .filter { it.interactionKind == "commitment" && !it.commitmentId.isNullOrBlank() }
            .associate { interaction ->
                val commitmentId = requireNotNull(interaction.commitmentId)
                commitmentId to (commitmentsById[commitmentId]?.title ?: interaction.title)
            }
        val sourceItems = sourceEventCards.map { PersonTimelineItem.SourceEvent(it) }
        val scheduleCandidates = scheduleLinks
            .asSequence()
            .filter { it.calendarEventId.isNullOrBlank() }
            .filter { it.proposedStartAt != null }
            .filter {
                it.status == ScheduleEventLinkStatus.NEEDS_REVIEW ||
                    it.relationType == ScheduleEventLinkRelationType.CREATES_CANDIDATE
            }
            .distinctBy {
                listOf(
                    it.rawEventId.orEmpty(),
                    it.commitmentId.orEmpty(),
                    it.proposedStartAt?.toEpochMilliseconds()?.toString().orEmpty(),
                    it.proposedTitle.orEmpty().trim(),
                )
            }
            .mapNotNull { link ->
                val proposedStartAt = link.proposedStartAt ?: return@mapNotNull null
                PersonTimelineItem.ScheduleCandidate(
                    key = "schedule-candidate:${link.id}",
                    sortAt = proposedStartAt,
                    proposedEndAt = link.proposedEndAt,
                    title = link.proposedTitle?.trim()?.takeIf { it.isNotEmpty() }
                        ?: link.commitmentId?.let(commitmentTitlesById::get)?.trim()?.takeIf { it.isNotEmpty() },
                    sourceType = link.calendarSourceType?.trim()?.takeIf { it.isNotEmpty() }
                        ?: SourceType.GOOGLE_CALENDAR,
                    rawEventId = link.rawEventId?.trim()?.takeIf { it.isNotEmpty() },
                    commitmentId = link.commitmentId?.trim()?.takeIf { it.isNotEmpty() },
                    status = link.status,
                    relationType = link.relationType,
                )
            }
            .toList()
        val confirmedSchedules = scheduleLinks
            .asSequence()
            .mapNotNull { link ->
                val calendarEventId = link.calendarEventId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val calendarEvent = calendarEventsById[calendarEventId] ?: return@mapNotNull null
                PersonTimelineItem.ConfirmedSchedule(
                    key = "confirmed-schedule:$calendarEventId",
                    sortAt = calendarEvent.startAt,
                    endAt = calendarEvent.endAt,
                    title = calendarEvent.title,
                    sourceType = calendarEvent.sourceType,
                    calendarEventId = calendarEventId,
                    rawEventId = link.rawEventId?.trim()?.takeIf { it.isNotEmpty() },
                    commitmentId = link.commitmentId?.trim()?.takeIf { it.isNotEmpty() },
                    status = calendarEvent.status,
                )
            }
            .distinctBy { it.calendarEventId }
            .toList()
        return (sourceItems + scheduleCandidates + confirmedSchedules)
            .sortedWith(
                compareByDescending<PersonTimelineItem> { it.sortAt }
                    .thenBy { it.key },
            )
    }

    private fun buildIndexedSourceEventCards(
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity>,
        commitmentsById: Map<String, CommitmentEntity>,
        calendarEventsById: Map<String, CalendarEventEntity>,
    ): List<SourceEventCardProjection> {
        val rawById = rawEvents.associateBy { it.id }
        val rawBySource = rawEvents
            .filter { !it.sourceRef.isNullOrBlank() }
            .associateBy { it.sourceType to it.sourceRef }
        val threadEventsByKey = rawEvents
            .asSequence()
            .filter { it.sourceType in EMAIL_SOURCE_TYPES }
            .mapNotNull { raw ->
                val conversationRef = raw.conversationRef?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rawTitle = raw.eventTitle
                val displayTitle = displayTitle(title = rawTitle, snippet = raw.eventSnippet)
                "thread:${raw.sourceType}:$conversationRef" to PersonTimelineThreadEvent(
                    rawEventId = raw.id,
                    sourceType = raw.sourceType,
                    occurredAt = raw.timestamp,
                    title = displayTitle,
                    snippet = displaySnippet(
                        title = rawTitle,
                        snippet = raw.eventSnippet,
                        displayTitle = displayTitle,
                    ),
                )
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.distinctBy { it.rawEventId }.sortedBy { it.occurredAt } }
        val linksByRawEventId = scheduleLinks.groupBy { it.rawEventId }
        val linksByCalendarSource = scheduleLinks.groupBy { it.calendarSourceType to it.calendarSourceRef }
        val buckets = linkedMapOf<String, MutableSourceEventCard>()
        interactions
            .filterNot { it.interactionKind == "commitment" }
            .forEach { interaction ->
                val rawEventId = interaction.rawEventId()
                val raw = interaction.rawEvent(rawEventId, rawById, rawBySource)
                val calendarEvent = interaction.calendarEvent(calendarEventsById)
                val navigableRawEventId = raw?.id ?: rawEventId
                val key = interaction.sourceEventKey(raw)
                val bucket = buckets.getOrPut(key) {
                    val rawTitle = calendarEvent?.title ?: interaction.title ?: raw?.eventTitle
                    val rawSnippet = interaction.snippet ?: raw?.eventSnippet
                    val displayTitle = displayTitle(title = rawTitle, snippet = rawSnippet)
                    MutableSourceEventCard(
                        sourceEventKey = key,
                        sourceType = calendarEvent?.sourceType ?: interaction.sourceType,
                        rawEventId = navigableRawEventId,
                        occurredAt = calendarEvent?.startAt ?: interaction.occurredAt,
                        title = displayTitle,
                        snippet = displaySnippet(
                            title = rawTitle,
                            snippet = rawSnippet,
                            displayTitle = displayTitle,
                        ),
                        sourceRef = interaction.sourceRef,
                        firstMemoryOrigin = interaction.firstMemoryOrigin(),
                        isEmailThread = key.startsWith("thread:"),
                    )
                }
                bucket.applyScheduleLinks(
                    linksByRawEventId[rawEventId].orEmpty() +
                        linksByCalendarSource[interaction.sourceType to interaction.sourceRef].orEmpty(),
                )
                val rawTitle = calendarEvent?.title ?: interaction.title ?: raw?.eventTitle
                val rawSnippet = interaction.snippet ?: raw?.eventSnippet
                val displayTitle = displayTitle(title = rawTitle, snippet = rawSnippet)
                bucket.applySourceEvidence(
                    rawEventId = navigableRawEventId,
                    sourceEventKey = interaction.sourceEventKey(),
                    occurredAt = calendarEvent?.startAt ?: interaction.occurredAt,
                    title = displayTitle,
                    snippet = displaySnippet(
                        title = rawTitle,
                        snippet = rawSnippet,
                        displayTitle = displayTitle,
                    ),
                    commitmentsExtractedCount = raw?.commitmentsExtractedCount,
                )
            }
        interactions
            .filter { it.interactionKind == "commitment" }
            .forEach { interaction ->
                if (interaction.hasMissingLiveCommitment(commitmentsById)) return@forEach
                if (interaction.isDecisionCommitment(commitmentsById)) return@forEach
                val rawEventId = interaction.rawEventId()
                val raw = interaction.rawEvent(rawEventId, rawById, rawBySource)
                val key = interaction.sourceEventKey(raw)
                val bucket = buckets[key] ?: return@forEach
                bucket.applyScheduleLinks(
                    linksByRawEventId[rawEventId].orEmpty() +
                        linksByCalendarSource[interaction.sourceType to interaction.sourceRef].orEmpty(),
                )
                bucket.addCommitment(interaction.toSummary(commitmentsById))
            }
        buckets.forEach { (key, bucket) ->
            threadEventsByKey[key]?.let(bucket::applyThreadEvents)
        }
        return buckets.values
            .map(MutableSourceEventCard::toProjection)
            .sortedByDescending { it.occurredAt }
    }

    private fun PersonInteractionEntity.toSummary(
        commitmentsById: Map<String, CommitmentEntity>,
    ): PersonDetailCommitmentSummary {
        val commitment = commitmentId?.let(commitmentsById::get)
        val itemType = commitment?.itemType ?: role
        return PersonDetailCommitmentSummary(
            id = commitment?.id ?: commitmentId,
            title = commitment?.title ?: title.orEmpty(),
            itemType = itemType,
            direction = commitment?.direction ?: direction,
            status = when (itemType) {
                CommitmentItemType.SCHEDULE -> commitment?.scheduleStatus ?: status
                CommitmentItemType.DECISION -> commitment?.decisionStatus ?: status
                else -> commitment?.actionState ?: status
            },
            dueAt = commitment?.dueAt,
            dueHint = commitment?.dueHint,
            dueIsApproximate = commitment?.dueIsApproximate ?: false,
        )
    }

    private fun PersonInteractionEntity.sourceEventKey(): String =
        sourceEventId?.let { "raw:$it" }
            ?: sourceRef.takeIf { it.startsWith("raw:") || it.startsWith("calendar:") }
            ?: "$sourceType:$sourceRef"

    private fun PersonInteractionEntity.sourceEventKey(raw: RawIngestionEventEntity?): String {
        val conversationRef = raw?.conversationRef?.trim()?.takeIf { it.isNotEmpty() }
        return if (conversationRef != null && sourceType in EMAIL_SOURCE_TYPES) {
            "thread:$sourceType:$conversationRef"
        } else {
            sourceEventKey()
        }
    }

    private fun PersonInteractionEntity.rawEventId(): String? =
        sourceEventId
            ?: sourceRef.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")

    private fun PersonInteractionEntity.rawEvent(
        rawEventId: String?,
        rawById: Map<String, RawIngestionEventEntity>,
        rawBySource: Map<Pair<String, String?>, RawIngestionEventEntity>,
    ): RawIngestionEventEntity? =
        rawEventId?.let(rawById::get)
            ?: rawBySource[sourceType to sourceRef]

    private fun PersonInteractionEntity.calendarEvent(
        calendarEventsById: Map<String, CalendarEventEntity>,
    ): CalendarEventEntity? {
        val calendarEventId = sourceRef
            .takeIf { it.startsWith("calendar:") }
            ?.removePrefix("calendar:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: sourceEventId
                ?.takeIf { sourceType == SourceType.GOOGLE_CALENDAR || sourceType == SourceType.OUTLOOK_CALENDAR }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return calendarEventId?.let(calendarEventsById::get)
    }

    private fun PersonInteractionEntity.displayTitle(): String? =
        displayTitle(title = title, snippet = snippet)

    private fun PersonInteractionEntity.displaySnippet(): String? =
        displaySnippet(title = title, snippet = snippet, displayTitle = displayTitle())

    private fun displayTitle(title: String?, snippet: String?): String? {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSnippet = snippet?.trim()?.takeIf { it.isNotBlank() }
        return if (normalizedTitle != null && normalizedTitle.looksLikeSourceArtifactName()) {
            normalizedSnippet?.take(SNIPPET_PREVIEW_CHAR_LIMIT)
        } else {
            normalizedTitle
        }
    }

    private fun displaySnippet(
        title: String?,
        snippet: String?,
        displayTitle: String?,
    ): String? {
        val normalizedSnippet = snippet?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalizedTitle = title?.trim()
        return if (
            normalizedTitle != null &&
            normalizedTitle.looksLikeSourceArtifactName() &&
            displayTitle == normalizedSnippet.take(SNIPPET_PREVIEW_CHAR_LIMIT)
        ) {
            null
        } else {
            normalizedSnippet.take(SNIPPET_PREVIEW_CHAR_LIMIT)
        }
    }

    private fun String.looksLikeSourceArtifactName(): Boolean {
        val value = trim()
        if (value.contains('/')) return true
        return SOURCE_ARTIFACT_FILE_NAME_REGEX.matches(value)
    }

    private fun sanitizeDisplayName(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (PersonIdentityResolver.isSpeakerLabelValue(value)) return null
        if (PersonIdentityResolver.normalizeEmailAnchor(value) != null) return null
        if (PersonIdentityResolver.normalizePhoneAnchor(value) != null) return null
        return value
    }

    private const val UNKNOWN_PERSON_DISPLAY_NAME = "아직 이름을 모르는 연락처"

    private data class MutableSourceEventCard(
        val sourceEventKey: String,
        val sourceType: String,
        var rawEventId: String?,
        var occurredAt: Instant,
        var title: String?,
        var snippet: String?,
        var commitmentsExtractedCount: Int = 0,
        val sourceRef: String?,
        var firstMemoryOrigin: String? = null,
        val isEmailThread: Boolean = false,
        var threadEvents: List<PersonTimelineThreadEvent> = emptyList(),
        val myActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val theirActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val schedules: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val relatedSourceTypes: MutableSet<String> = linkedSetOf(),
        val relatedSourceEventKeys: MutableSet<String> = linkedSetOf(),
        var linkedCalendarEventId: String? = null,
    ) {
        fun applySourceEvidence(
            rawEventId: String?,
            sourceEventKey: String,
            occurredAt: Instant,
            title: String?,
            snippet: String?,
            commitmentsExtractedCount: Int?,
        ) {
            val relatedKey = rawEventId?.let { "raw:$it" } ?: sourceEventKey
            val newlySeenSource = relatedSourceEventKeys.add(relatedKey)
            if (newlySeenSource) {
                this.commitmentsExtractedCount += commitmentsExtractedCount ?: 0
            }
            if (occurredAt >= this.occurredAt) {
                this.rawEventId = rawEventId ?: this.rawEventId
                this.occurredAt = occurredAt
                this.title = title ?: this.title
                this.snippet = snippet ?: this.snippet
            }
        }

        fun addCommitment(summary: PersonDetailCommitmentSummary) {
            when {
                CommitmentDisplayPolicy.isDecisionContextItem(summary.itemType) -> Unit
                summary.itemType == CommitmentItemType.SCHEDULE -> schedules += summary
                isTakeDirection(summary.direction) -> theirActions += summary
                else -> myActions += summary
            }
        }

        fun applyScheduleLinks(links: List<ScheduleEventLinkEntity>) {
            links.forEach { link ->
                linkedCalendarEventId = linkedCalendarEventId ?: link.calendarEventId
                if (link.sourceType != sourceType) {
                    relatedSourceTypes += link.sourceType
                }
            }
        }

        fun applyThreadEvents(events: List<PersonTimelineThreadEvent>) {
            threadEvents = events
            relatedSourceEventKeys += events.map { "raw:${it.rawEventId}" }
        }

        fun toProjection(): SourceEventCardProjection =
            SourceEventCardProjection(
                sourceEventKey = sourceEventKey,
                sourceType = sourceType,
                rawEventId = rawEventId,
                occurredAt = occurredAt,
                title = title,
                snippet = snippet,
                commitmentsExtractedCount = commitmentsExtractedCount.takeIf { it > 0 }
                    ?: (myActions.size + theirActions.size + schedules.size),
                myActions = myActions.toList(),
                theirActions = theirActions.toList(),
                schedules = schedules.toList(),
                linkedCalendarEventId = linkedCalendarEventId,
                relatedSourceTypes = relatedSourceTypes.toList(),
                firstMemoryOrigin = firstMemoryOrigin,
                isEmailThread = isEmailThread,
                threadMessageCount = if (isEmailThread) {
                    maxOf(relatedSourceEventKeys.size, threadEvents.size, 1)
                } else {
                    1
                },
                threadEvents = threadEvents,
                relatedSourceEventKeys = relatedSourceEventKeys.toList(),
            )
    }

    private fun PersonInteractionEntity.firstMemoryOrigin(): String? =
        status?.takeIf { sourceType == SourceType.MANUAL && sourceRef.startsWith("manual_memory:") }

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

    private val SOURCE_ARTIFACT_FILE_NAME_REGEX =
        Regex("(?i).+\\.(txt|m4a|mp3|wav|aac|mp4|pdf|docx?|xlsx?|pptx?|eml|html?)$")

    private val EMAIL_SOURCE_TYPES = setOf(
        SourceType.GMAIL,
        SourceType.OUTLOOK_MAIL,
        SourceType.NAVER_IMAP,
        SourceType.DAUM_IMAP,
    )

    private fun PersonInteractionEntity.isOpenCommitmentLoop(
        commitmentsById: Map<String, CommitmentEntity>,
    ): Boolean {
        if (hasMissingLiveCommitment(commitmentsById)) return false
        val commitment = commitmentId?.let(commitmentsById::get)
        val itemType = commitment?.itemType ?: role
        val loopStatus = when (itemType) {
            CommitmentItemType.SCHEDULE -> commitment?.scheduleStatus ?: status
            CommitmentItemType.DECISION -> commitment?.decisionStatus ?: status
            else -> commitment?.actionState ?: status
        }
        return interactionKind == "commitment" &&
            CommitmentDisplayPolicy.countsAsOpenPersonLoop(
                itemType = itemType,
                status = loopStatus,
            )
    }

    private fun PersonInteractionEntity.isDecisionCommitment(
        commitmentsById: Map<String, CommitmentEntity>,
    ): Boolean =
        interactionKind == "commitment" &&
            CommitmentDisplayPolicy.isDecisionContextItem(commitmentId?.let(commitmentsById::get)?.itemType ?: role)

    private fun PersonInteractionEntity.hasMissingLiveCommitment(
        commitmentsById: Map<String, CommitmentEntity>,
    ): Boolean {
        val id = commitmentId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return !commitmentsById.containsKey(id)
    }

}
