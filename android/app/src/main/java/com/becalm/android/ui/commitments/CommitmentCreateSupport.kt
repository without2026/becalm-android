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

    fun saveError(error: BecalmError): String =
        CommitmentSaveErrorFormatter.format(error)
}
