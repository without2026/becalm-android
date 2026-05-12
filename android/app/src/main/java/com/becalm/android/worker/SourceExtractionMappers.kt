package com.becalm.android.worker

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceExtractedParticipantDto
import com.becalm.android.data.remote.dto.SourceExtractedItemDto
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.domain.person.PersonIdentityResolver
import kotlinx.datetime.Instant
import java.util.UUID

// 파일 분리 시 sync_status 리터럴 문자열을 재사용하기 위해 mapper 파일로 이동.
// 동작 변경 없음 — 기존과 동일하게 "pending" 리터럴을 사용한다.
internal const val STATUS_PENDING: String = "pending"

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
): SourceEventParticipantEntity {
    val anchor = email ?: phone
    val resolved = PersonIdentityResolver.resolve(userId, anchor)
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
        relationToUser = relationToUser.takeIf { it in RELATION_TO_USER_VALUES } ?: relationToUserForRole(role),
        identityType = identityType ?: resolved?.identityType,
        normalizedValue = normalized ?: displayName ?: organization ?: rawValue,
        displayNameRaw = displayName,
        emailRaw = email,
        phoneRaw = phone,
        organizationRaw = organization,
        titleRaw = title,
        evidence = evidence,
        confidence = confidence.coerceIn(0.0, 1.0),
        resolutionStatus = if (resolved == null) "unresolved" else "resolved",
        createdAt = now,
    )
}

internal fun CommitmentEntity.toCommitmentParticipantEntity(
    userId: String,
    index: Int,
    fallbackPersonId: String? = null,
    now: Instant,
): CommitmentParticipantEntity? {
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

private val RELATION_TO_USER_VALUES = setOf("self", "counterparty", "participant", "referenced", "unknown")

private fun relationToUserForRole(role: String): String =
    when (role.lowercase()) {
        "sender", "recipient", "counterparty", "caller", "receiver" -> "counterparty"
        "organizer", "attendee", "speaker" -> "participant"
        "self" -> "self"
        else -> "referenced"
    }
