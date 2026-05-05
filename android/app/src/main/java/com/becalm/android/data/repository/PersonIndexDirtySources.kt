package com.becalm.android.data.repository

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.domain.person.SourceInteractionKind
import java.util.UUID
import kotlinx.datetime.Instant

internal object PersonIndexDirtySources {
    fun forSourceParticipants(
        participants: List<SourceEventParticipantEntity>,
        reason: String,
        now: Instant,
    ): List<PersonIndexDirtySourceEntity> =
        participants
            .distinctBy { "${it.userId}|${it.sourceType}|${it.sourceEventId}" }
            .map { participant ->
                rawEvent(
                    userId = participant.userId,
                    sourceType = participant.sourceType,
                    sourceEventId = participant.sourceEventId,
                    reason = reason,
                    now = now,
                )
            }

    fun forCommitmentParticipants(
        participants: List<com.becalm.android.data.local.db.entity.CommitmentParticipantEntity>,
        reason: String,
        now: Instant,
    ): List<PersonIndexDirtySourceEntity> =
        participants
            .distinctBy { "${it.userId}|${it.commitmentId}" }
            .map { participant ->
                commitment(
                    userId = participant.userId,
                    commitmentId = participant.commitmentId,
                    reason = reason,
                    now = now,
                )
            }

    fun forCommitments(
        commitments: List<CommitmentEntity>,
        reason: String,
        now: Instant,
    ): List<PersonIndexDirtySourceEntity> =
        commitments
            .distinctBy { "${it.userId}|${it.id}" }
            .map { commitment ->
                commitment(
                    userId = commitment.userId,
                    commitmentId = commitment.id,
                    reason = reason,
                    now = now,
                )
            }

    fun rawEvent(
        userId: String,
        sourceType: String,
        sourceEventId: String,
        reason: String,
        now: Instant,
    ): PersonIndexDirtySourceEntity =
        row(
            userId = userId,
            sourceType = sourceType,
            sourceRef = "raw:$sourceEventId",
            interactionKind = SourceInteractionKind.forSourceType(sourceType),
            reason = reason,
            now = now,
        )

    fun commitment(
        userId: String,
        commitmentId: String,
        reason: String,
        now: Instant,
    ): PersonIndexDirtySourceEntity =
        row(
            userId = userId,
            sourceType = SourceInteractionKind.COMMITMENT,
            sourceRef = "commitment:$commitmentId",
            interactionKind = SourceInteractionKind.COMMITMENT,
            reason = reason,
            now = now,
        )

    private fun row(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        reason: String,
        now: Instant,
    ): PersonIndexDirtySourceEntity {
        val id = UUID.nameUUIDFromBytes(
            "person-index-dirty:$userId:$sourceType:$sourceRef:$interactionKind".toByteArray(Charsets.UTF_8),
        ).toString()
        return PersonIndexDirtySourceEntity(
            id = id,
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            interactionKind = interactionKind,
            reason = reason,
            updatedAt = now,
        )
    }
}
