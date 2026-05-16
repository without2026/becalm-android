package com.becalm.android.worker

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceExtractedParticipantDto
import com.becalm.android.data.remote.dto.SourceExtractedItemDto
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonIdentityTypes
import kotlinx.datetime.Instant
import java.util.Locale
import java.util.UUID

// 파일 분리 시 sync_status 리터럴 문자열을 재사용하기 위해 mapper 파일로 이동.
// 동작 변경 없음 — 기존과 동일하게 "pending" 리터럴을 사용한다.
internal const val STATUS_PENDING: String = "pending"

internal fun List<SourceExtractedItemDto>.filterUserRelevantItems(
    rawCounterpartyRef: String?,
    participants: List<SourceExtractedParticipantDto>,
    selfIdentityAnchors: List<SelfIdentityAnchorEntity> = emptyList(),
): List<SourceExtractedItemDto> {
    val allowedRefs = buildSet {
        addAll(rawCounterpartyRef.normalizedPersonRefValues())
        participants
            .filter { it.relationToUser.equals("counterparty", ignoreCase = true) }
            .forEach { participant ->
                addAll(participant.email.normalizedPersonRefValues())
                addAll(participant.phone.normalizedPersonRefValues())
                addAll(participant.normalizedValue.normalizedPersonRefValues())
                addAll(participant.rawValue.normalizedPersonRefValues())
                addAll(participant.displayName.normalizedPersonRefValues())
                addAll(participant.organization.normalizedPersonRefValues())
            }
    }
    val cleaned = map { item ->
        if (item.counterpartyRef.matchSelfIdentityAnchor(selfIdentityAnchors) != null) {
            item.copy(counterpartyRef = null)
        } else {
            item
        }
    }
    if (allowedRefs.isEmpty()) return cleaned
    return cleaned.filter { item ->
        val ref = item.counterpartyRef
        ref.isNullOrBlank() || ref.normalizedPersonRefValues().any { it in allowedRefs }
    }
}

internal fun SourceExtractedItemDto.toTrackableCommitmentEntity(
    rawEventId: String,
    index: Int,
    userId: String,
    sourceRef: String?,
    sourceType: String,
    sourceEventTitle: String?,
    sourceEventOccurredAt: Instant,
    now: Instant,
): CommitmentEntity = CommitmentEntity(
    id = UUID.nameUUIDFromBytes(
        "commitment:$rawEventId:$index".toByteArray(Charsets.UTF_8),
    ).toString(),
    userId = userId,
    itemType = type,
    direction = direction?.lowercase(),
    scheduleStatus = scheduleStatus,
    decisionStatus = decisionStatus,
    counterpartyRaw = null,
    counterpartyRef = counterpartyRef,
    title = text.take(500),
    description = null,
    quote = quote,
    sourceEventTitle = sourceEventTitle,
    sourceEventOccurredAt = sourceEventOccurredAt,
    dueAt = dueAt,
    dueHint = dueHint,
    dueIsApproximate = dueIsApproximate,
    actionState = "pending",
    sourceType = sourceType,
    sourceRef = sourceRef,
    confidence = confidence.toDouble(),
    commitmentState = CommitmentLifecycleLegacy.DRAFT,
    syncStatus = STATUS_PENDING,
    createdAt = now,
    updatedAt = now,
)

internal fun SourceExtractedParticipantDto.toSourceEventParticipantEntity(
    userId: String,
    sourceEventId: String,
    sourceType: String,
    sourceRef: String?,
    index: Int,
    now: Instant,
    selfIdentityAnchors: List<SelfIdentityAnchorEntity> = emptyList(),
): SourceEventParticipantEntity {
    val anchor = email ?: phone
    val selfMatch = matchSelfIdentityAnchor(selfIdentityAnchors)
    val selfResolved = selfMatch?.resolutionStatus == RESOLUTION_SELF_RESOLVED
    val resolved = if (selfMatch != null) null else PersonIdentityResolver.resolve(userId, anchor)
    val normalized = normalizedValue ?: resolved?.identityKey?.substringAfter(':', missingDelimiterValue = resolved.rawValue)
    val participantAnchor = anchor ?: normalizedValue ?: displayName ?: organization ?: rawValue
    val participantId = UUID.nameUUIDFromBytes(
        "source-participant:$userId:$sourceEventId:$index:${role}:${participantAnchor.orEmpty()}".toByteArray(Charsets.UTF_8),
    ).toString()
    return SourceEventParticipantEntity(
        id = participantId,
        userId = userId,
        sourceEventId = sourceEventId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        personId = resolved?.personId,
        role = role,
        relationToUser = if (selfResolved) {
            "self"
        } else {
            relationToUser.takeIf { it in RELATION_TO_USER_VALUES } ?: relationToUserForRole(role)
        },
        identityType = identityType ?: resolved?.identityType,
        normalizedValue = normalized ?: displayName ?: organization ?: rawValue,
        displayNameRaw = displayName,
        emailRaw = email,
        phoneRaw = phone,
        organizationRaw = organization,
        titleRaw = title,
        evidence = evidence,
        confidence = confidence.coerceIn(0.0, 1.0),
        resolutionStatus = when {
            selfMatch != null -> selfMatch.resolutionStatus
            resolved == null -> "unresolved"
            else -> "resolved"
        },
        createdAt = now,
    )
}

internal fun CommitmentEntity.toCommitmentParticipantEntity(
    userId: String,
    index: Int,
    fallbackPersonId: String? = null,
    now: Instant,
    selfIdentityAnchors: List<SelfIdentityAnchorEntity> = emptyList(),
): CommitmentParticipantEntity? {
    if (counterpartyRef.matchSelfIdentityAnchor(selfIdentityAnchors) != null) return null
    val resolved = PersonIdentityResolver.resolve(userId, counterpartyRef)
    val personId = resolved?.personId ?: fallbackPersonId ?: return null
    val participantId = UUID.nameUUIDFromBytes(
        "commitment-participant:$userId:$id:$personId:$index".toByteArray(Charsets.UTF_8),
    ).toString()
    return CommitmentParticipantEntity(
        id = participantId,
        userId = userId,
        commitmentId = id,
        personId = personId,
        role = when (itemType) {
            CommitmentItemType.ACTION -> direction ?: "owner"
            CommitmentItemType.SCHEDULE -> "attendee"
            CommitmentItemType.DECISION -> "decision_maker"
            else -> "owner"
        },
        evidence = quote,
        confidence = confidence,
        createdAt = now,
    )
}

internal fun List<SourceEventParticipantEntity>.singleSourceCounterpartyPersonId(): String? {
    val counterpartyPersonIds = asSequence()
        .filter { participant ->
            participant.personId != null &&
                participant.relationToUser == "counterparty" &&
                participant.role.lowercase() != "self"
        }
        .mapNotNull { it.personId }
        .distinct()
        .take(2)
        .toList()
    return counterpartyPersonIds.singleOrNull()
}

private const val RESOLUTION_SELF_RESOLVED = "self_resolved"
private const val RESOLUTION_SUGGESTED_SELF = "suggested_self"
private val RELATION_TO_USER_VALUES = setOf("self", "counterparty", "participant", "referenced", "unknown")
private val STRONG_SELF_EMAIL_TYPES = setOf("auth_email", "provider_email", "email")

private data class LocalSelfIdentityMatch(
    val resolutionStatus: String,
)

private fun relationToUserForRole(role: String): String =
    when (role.lowercase()) {
        "sender", "recipient", "counterparty", "caller", "receiver" -> "counterparty"
        "organizer", "attendee", "speaker" -> "participant"
        "self" -> "self"
        else -> "referenced"
    }

private fun SourceExtractedParticipantDto.matchSelfIdentityAnchor(
    anchors: List<SelfIdentityAnchorEntity>,
): LocalSelfIdentityMatch? =
    anchors.firstNotNullOfOrNull { anchor ->
        when (anchor.anchorType) {
            in STRONG_SELF_EMAIL_TYPES ->
                participantEmails().firstOrNull { value ->
                    normalizedEquals(value, anchor.normalizedValue, PersonIdentityResolver::normalizeEmailAnchor)
                }?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            "phone" ->
                participantPhones().firstOrNull { value ->
                    normalizedEquals(value, anchor.normalizedValue, PersonIdentityResolver::normalizePhoneAnchor)
                }?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            "alias" ->
                participantAliases().firstOrNull { value ->
                    normalizedEquals(value, anchor.normalizedValue, PersonIdentityResolver::normalizeAlias)
                }?.let { LocalSelfIdentityMatch(RESOLUTION_SUGGESTED_SELF) }

            PersonIdentityTypes.SPEAKER_LABEL ->
                participantSpeakerLabels().firstOrNull { value ->
                    normalizedEquals(value, anchor.normalizedValue, ::normalizeSourceLocalIdentity)
                }?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            else -> null
        }
    }

private fun SourceExtractedParticipantDto.participantEmails(): List<String?> =
    listOf(email, normalizedValue.takeIf { identityType == "email" }, rawValue.takeIf { identityType == "email" })

private fun SourceExtractedParticipantDto.participantPhones(): List<String?> =
    listOf(phone, normalizedValue.takeIf { identityType == "phone" }, rawValue.takeIf { identityType == "phone" })

private fun SourceExtractedParticipantDto.participantAliases(): List<String?> =
    listOf(displayName, normalizedValue.takeIf { identityType == "name" }, rawValue.takeIf { identityType == "name" })

private fun SourceExtractedParticipantDto.participantSpeakerLabels(): List<String?> =
    listOf(
        normalizedValue.takeIf { identityType == PersonIdentityTypes.SPEAKER_LABEL },
        rawValue.takeIf { identityType == PersonIdentityTypes.SPEAKER_LABEL },
        displayName.takeIf { identityType == PersonIdentityTypes.SPEAKER_LABEL || role.equals("speaker", ignoreCase = true) },
    )

private fun String?.matchSelfIdentityAnchor(anchors: List<SelfIdentityAnchorEntity>): LocalSelfIdentityMatch? =
    anchors.firstNotNullOfOrNull { anchor ->
        when (anchor.anchorType) {
            in STRONG_SELF_EMAIL_TYPES ->
                normalizedEquals(this, anchor.normalizedValue, PersonIdentityResolver::normalizeEmailAnchor)
                    .takeIf { it }
                    ?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            "phone" ->
                normalizedEquals(this, anchor.normalizedValue, PersonIdentityResolver::normalizePhoneAnchor)
                    .takeIf { it }
                    ?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            "alias" ->
                normalizedEquals(this, anchor.normalizedValue, PersonIdentityResolver::normalizeAlias)
                    .takeIf { it }
                    ?.let { LocalSelfIdentityMatch(RESOLUTION_SUGGESTED_SELF) }

            PersonIdentityTypes.SPEAKER_LABEL ->
                normalizedEquals(this, anchor.normalizedValue, ::normalizeSourceLocalIdentity)
                    .takeIf { it }
                    ?.let { LocalSelfIdentityMatch(RESOLUTION_SELF_RESOLVED) }

            else -> null
        }
    }

private fun normalizedEquals(
    left: String?,
    right: String?,
    normalize: (String?) -> String?,
): Boolean {
    val normalizedLeft = normalize(left)
    val normalizedRight = normalize(right)
    return normalizedLeft != null && normalizedRight != null && normalizedLeft == normalizedRight
}

private fun String?.normalizedPersonRefValues(): Set<String> {
    val raw = this?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
    return buildSet {
        PersonIdentityResolver.normalizeEmailAnchor(raw)?.let(::add)
        PersonIdentityResolver.normalizePhoneAnchor(raw)?.let(::add)
        PersonIdentityResolver.normalizeAlias(raw)?.let(::add)
        add(raw.lowercase(Locale.ROOT))
    }
}

private fun normalizeSourceLocalIdentity(value: String?): String? =
    value
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.length >= 2 }
