package com.becalm.android.ui.commitments

import com.becalm.android.core.result.BecalmError
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.domain.commitment.ManualCommitmentDraft

internal object CommitmentCreateProjector {
    fun applySupersedeSource(
        state: CreateUiState,
        entity: CommitmentEntity,
    ): CreateUiState = state.copy(
        supersedeSource = entity,
        draft = state.draft.copy(quote = entity.quote),
    )

    fun effectiveDraft(state: CreateUiState): ManualCommitmentDraft? =
        if (state.mode == CommitmentCreateMode.MANUAL) {
            state.draft
        } else {
            state.supersedeSource?.let { source ->
                state.draft.copy(quote = source.quote)
            }
        }

    fun saveError(error: BecalmError): String = when (error) {
        is BecalmError.Unauthorized -> "로그인이 필요합니다"
        is BecalmError.NotFound -> "삭제된 약속입니다"
        is BecalmError.Validation -> error.message
        else -> "저장 실패 — 다시 시도해주세요"
    }
}
