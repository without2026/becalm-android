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
            sourceTitle = entity.sourceEventTitle,
            sourceOccurredAt = if (entity.sourceType == SourceType.MANUAL) {
                entity.createdAt
            } else {
                entity.sourceEventOccurredAt
            },
            isManual = entity.sourceType == SourceType.MANUAL,
        ),
        title = entity.title,
        dueAtMillis = entity.dueAt?.toEpochMilliseconds(),
        dueIsApproximate = entity.dueIsApproximate,
        dueHint = entity.dueHint.orEmpty(),
        personRef = entity.personRef.orEmpty(),
        direction = requireNotNull(entity.direction) { "Action commitment edit requires direction" },
        fieldErrors = emptyMap(),
        saveError = null,
    )

    fun toDraft(state: EditUiState): CommitmentEditDraft = CommitmentEditDraft(
        title = state.title,
        dueAtMillis = state.dueAtMillis,
        dueHint = state.dueHint.ifBlank { null },
        dueIsApproximate = state.dueIsApproximate,
        personRef = state.personRef.ifBlank { null },
        direction = state.direction,
    )

    fun toSaveError(error: BecalmError): String = CommitmentSaveErrorFormatter.format(error)

    private fun buildSourceLabel(entity: CommitmentEntity): String =
        CommitmentDetailFormatter.buildCompactSourceLabel(entity)
}
