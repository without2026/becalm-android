package com.becalm.android.ui.commitments

import com.becalm.android.core.result.BecalmError
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentEditDraft
import kotlinx.datetime.Instant

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

    fun toSaveError(error: BecalmError): String = when (error) {
        is BecalmError.NotFound -> "삭제된 약속입니다"
        is BecalmError.Unauthorized -> "로그인이 필요합니다"
        is BecalmError.Validation -> error.message
        else -> "저장 실패 — 다시 시도해주세요"
    }

    private fun buildSourceLabel(entity: CommitmentEntity): String =
        if (entity.sourceType == SourceType.MANUAL) {
            "manual:${CommitmentDetailFormatter.formatShortKst(entity.createdAt)}"
        } else {
            "${entity.sourceEventTitle ?: entity.sourceType}:${CommitmentDetailFormatter.formatShortKst(entity.sourceEventOccurredAt)}"
        }
}
