package com.becalm.android.worker

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.PersonCandidateDto
import com.becalm.android.data.remote.dto.SourceExtractedItemDto
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.NormalizedSourceEvent
import com.becalm.android.domain.person.PersonIdentityResolver
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

// 파일 분리 시 sync_status 리터럴 문자열을 재사용하기 위해 mapper 파일로 이동.
// 동작 변경 없음 — 기존과 동일하게 "pending" 리터럴을 사용한다.
internal const val STATUS_PENDING: String = "pending"

/**
 * commitment extraction 멀티파트 폼 필드 생성을 한 곳으로 통합한다.
 * 각 필드의 값과 content type("text/plain") 을 원본 인라인 코드와 byte-identical 하게 보존한다.
 */
internal data class SourceExtractionRequestParts(
    val sourceType: RequestBody,
    val clientEventId: RequestBody,
    val rawEventId: RequestBody,
    val durationSeconds: RequestBody?,
    val timestamp: RequestBody,
    val counterpartyRef: RequestBody?,
    val eventTitle: RequestBody?,
    val folder: RequestBody?,
    val bodyText: RequestBody?,
)

internal fun RawIngestionEventEntity.toSourceExtractionRequestParts(
    rawEventId: String,
    bodyText: String? = null,
): SourceExtractionRequestParts {
    val dto = NormalizedSourceEvent.from(rawEvent = this, emailBody = null).toDto()
    return SourceExtractionRequestParts(
        sourceType = dto.sourceType.toPlainRequestBody(),
        clientEventId = dto.clientEventId.toPlainRequestBody(),
        rawEventId = rawEventId.toPlainRequestBody(),
        durationSeconds = dto.durationSeconds?.toString()?.toPlainRequestBody(),
        timestamp = dto.timestamp.toString().toPlainRequestBody(),
        counterpartyRef = dto.counterpartyRef?.toPlainRequestBody(),
        eventTitle = dto.eventTitle?.toPlainRequestBody(),
        folder = dto.folder?.toPlainRequestBody(),
        bodyText = bodyText?.toPlainRequestBody(),
    )
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

internal fun PersonCandidateDto.toSourceEventParticipantEntity(
    userId: String,
    sourceEventId: String,
    sourceType: String,
    sourceRef: String?,
    index: Int,
    now: Instant,
): SourceEventParticipantEntity {
    val anchor = email ?: phone ?: name ?: organization
    val resolved = PersonIdentityResolver.resolve(userId, anchor)
    val normalized = resolved?.identityKey?.substringAfter(':', missingDelimiterValue = resolved.rawValue)
    val participantId = UUID.nameUUIDFromBytes(
        "source-participant:$userId:$sourceEventId:$index:${role}:${anchor.orEmpty()}".toByteArray(Charsets.UTF_8),
    ).toString()
    return SourceEventParticipantEntity(
        id = participantId,
        userId = userId,
        sourceEventId = sourceEventId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        personId = resolved?.personId,
        role = role,
        relationToUser = when (role.lowercase()) {
            "sender", "recipient", "counterparty", "speaker", "caller", "receiver" -> "counterparty"
            "attendee" -> "participant"
            else -> "referenced"
        },
        identityType = resolved?.identityType,
        normalizedValue = normalized,
        displayNameRaw = name,
        emailRaw = email,
        phoneRaw = phone,
        organizationRaw = organization,
        evidence = evidence,
        confidence = confidence.coerceIn(0.0, 1.0),
        resolutionStatus = if (resolved == null) "unresolved" else "resolved",
        createdAt = now,
    )
}

internal fun CommitmentEntity.toCommitmentParticipantEntity(
    userId: String,
    index: Int,
    now: Instant,
): CommitmentParticipantEntity? {
    val resolved = PersonIdentityResolver.resolve(userId, counterpartyRef) ?: return null
    val participantId = UUID.nameUUIDFromBytes(
        "commitment-participant:$userId:$id:${resolved.personId}:$index".toByteArray(Charsets.UTF_8),
    ).toString()
    return CommitmentParticipantEntity(
        id = participantId,
        userId = userId,
        commitmentId = id,
        personId = resolved.personId,
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

/** Convenience: creates a plain-text [RequestBody] from this String. */
internal fun String.toPlainRequestBody(): RequestBody =
    toRequestBody("text/plain".toMediaTypeOrNull())
