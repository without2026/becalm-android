package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.ui.components.isTakeDirection

internal object PersonDetailProjector {
    private const val SNIPPET_PREVIEW_CHAR_LIMIT: Int = 200

    fun buildIndexedState(
        personId: String,
        identities: List<PersonIdentityEntity>,
        enrichmentRows: List<PersonEnrichmentEntity>,
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
    ): PersonDetailUiState {
        val enrichment = findEnrichment(identities, enrichmentRows)
        val displayFallback = identities
            .mapNotNull { sanitizeDisplayName(it.displayNameHint) ?: sanitizeDisplayName(it.rawValue) }
            .firstOrNull()
            ?: UNKNOWN_PERSON_DISPLAY_NAME
        val sourceEventCards = buildIndexedSourceEventCards(
            interactions = interactions,
            rawEvents = rawEvents,
            scheduleLinks = scheduleLinks,
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
            pendingCommitmentCount = interactions.count { it.isOpenCommitmentLoop() },
            channelSources = interactions.map { it.sourceType }.toSet(),
            sourceEventCards = sourceEventCards,
            loading = false,
            error = null,
        )
    }

    private fun buildIndexedSourceEventCards(
        interactions: List<PersonInteractionEntity>,
        rawEvents: List<RawIngestionEventEntity>,
        scheduleLinks: List<ScheduleEventLinkEntity>,
    ): List<SourceEventCardProjection> {
        val rawById = rawEvents.associateBy { it.id }
        val rawBySource = rawEvents
            .filter { !it.sourceRef.isNullOrBlank() }
            .associateBy { it.sourceType to it.sourceRef }
        val linksByRawEventId = scheduleLinks.groupBy { it.rawEventId }
        val linksByCalendarSource = scheduleLinks.groupBy { it.calendarSourceType to it.calendarSourceRef }
        val buckets = linkedMapOf<String, MutableSourceEventCard>()
        interactions
            .filterNot { it.interactionKind == "commitment" }
            .forEach { interaction ->
                val key = interaction.sourceEventKey()
                val rawEventId = interaction.sourceEventId
                    ?: interaction.sourceRef.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
                val raw = rawEventId?.let(rawById::get)
                    ?: rawBySource[interaction.sourceType to interaction.sourceRef]
                val navigableRawEventId = raw?.id ?: rawEventId
                val bucket = buckets.getOrPut(key) {
                    val rawTitle = interaction.title ?: raw?.eventTitle
                    val rawSnippet = interaction.snippet ?: raw?.eventSnippet
                    val displayTitle = displayTitle(title = rawTitle, snippet = rawSnippet)
                    MutableSourceEventCard(
                        sourceEventKey = key,
                        sourceType = interaction.sourceType,
                        rawEventId = navigableRawEventId,
                        occurredAt = interaction.occurredAt,
                        title = displayTitle,
                        snippet = displaySnippet(
                            title = rawTitle,
                            snippet = rawSnippet,
                            displayTitle = displayTitle,
                        ),
                        commitmentsExtractedCount = raw?.commitmentsExtractedCount ?: 0,
                        sourceRef = interaction.sourceRef,
                        firstMemoryOrigin = interaction.firstMemoryOrigin(),
                    )
                }
                bucket.applyScheduleLinks(
                    linksByRawEventId[rawEventId].orEmpty() +
                        linksByCalendarSource[interaction.sourceType to interaction.sourceRef].orEmpty(),
                )
                val rawTitle = interaction.title ?: raw?.eventTitle
                val rawSnippet = interaction.snippet ?: raw?.eventSnippet
                val displayTitle = displayTitle(title = rawTitle, snippet = rawSnippet)
                bucket.applySourceEvidence(
                    rawEventId = navigableRawEventId,
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
                if (interaction.isDecisionCommitment()) return@forEach
                val key = interaction.sourceEventKey()
                val rawEventId = interaction.sourceEventId
                    ?: interaction.sourceRef.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
                val bucket = buckets[key] ?: return@forEach
                bucket.applyScheduleLinks(
                    linksByRawEventId[rawEventId].orEmpty() +
                        linksByCalendarSource[interaction.sourceType to interaction.sourceRef].orEmpty(),
                )
                bucket.addCommitment(interaction.toSummary())
            }
        return buckets.values
            .map(MutableSourceEventCard::toProjection)
            .sortedByDescending { it.occurredAt }
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
        val occurredAt: kotlinx.datetime.Instant,
        var title: String?,
        var snippet: String?,
        var commitmentsExtractedCount: Int = 0,
        val sourceRef: String?,
        var firstMemoryOrigin: String? = null,
        val myActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val theirActions: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val schedules: MutableList<PersonDetailCommitmentSummary> = mutableListOf(),
        val relatedSourceTypes: MutableSet<String> = linkedSetOf(),
        var linkedCalendarEventId: String? = null,
    ) {
        fun applySourceEvidence(
            rawEventId: String?,
            title: String?,
            snippet: String?,
            commitmentsExtractedCount: Int?,
        ) {
            this.rawEventId = rawEventId ?: this.rawEventId
            this.title = title ?: this.title
            this.snippet = snippet ?: this.snippet
            this.commitmentsExtractedCount = commitmentsExtractedCount ?: this.commitmentsExtractedCount
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

    private fun PersonInteractionEntity.isOpenCommitmentLoop(): Boolean =
        interactionKind == "commitment" &&
            CommitmentDisplayPolicy.countsAsOpenPersonLoop(
                itemType = role,
                status = status,
            )

    private fun PersonInteractionEntity.isDecisionCommitment(): Boolean =
        interactionKind == "commitment" && CommitmentDisplayPolicy.isDecisionContextItem(role)

}
