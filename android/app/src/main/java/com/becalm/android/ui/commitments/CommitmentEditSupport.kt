package com.becalm.android.ui.commitments

import com.becalm.android.core.result.BecalmError
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentEditDraft

internal object CommitmentEditProjector {
    fun seed(entity: CommitmentEntity): EditUiState = EditUiState(
        loading = false,
        saving = false,
        notFound = false,
        readOnly = EditReadOnly(
            quote = entity.quote,
            quoteDisputed = entity.quoteDisputed,
            sourceLabel = buildSourceLabel(entity),
            sourceType = entity.sourceType,
            sourceTitle = entity.sourceEventTitle,
            sourceOccurredAt = if (entity.sourceType == SourceType.MANUAL) {
                entity.createdAt
            } else {
                entity.sourceEventOccurredAt
            },
            isManual = entity.sourceType == SourceType.MANUAL,
            originalTitle = entity.title,
            originalDueAtMillis = entity.dueAt?.toEpochMilliseconds(),
            originalDueIsApproximate = entity.dueIsApproximate,
            originalDueHint = entity.dueHint.orEmpty(),
            originalCounterpartyRef = entity.counterpartyRef.orEmpty(),
            originalDirection = entity.direction.orEmpty(),
        ),
        title = entity.title,
        dueAtMillis = entity.dueAt?.toEpochMilliseconds(),
        dueIsApproximate = entity.dueIsApproximate,
        dueHint = entity.dueHint.orEmpty(),
        counterpartyRef = entity.counterpartyRef.orEmpty(),
        direction = entity.direction.orEmpty(),
        fieldErrors = emptyMap(),
        saveError = null,
    )

    fun toDraft(state: EditUiState): CommitmentEditDraft = CommitmentEditDraft(
        title = state.title,
        dueAtMillis = state.dueAtMillis,
        dueHint = state.dueHint.ifBlank { null },
        dueIsApproximate = state.dueIsApproximate,
        counterpartyRef = state.counterpartyRef.ifBlank { null },
        direction = state.direction,
    )

    fun toSaveError(error: BecalmError): CommitmentText = CommitmentSaveErrorFormatter.format(error)

    private fun buildSourceLabel(entity: CommitmentEntity): CommitmentText =
        CommitmentDetailFormatter.buildCompactSourceLabel(entity)
}
