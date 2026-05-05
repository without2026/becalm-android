package com.becalm.android.ui.commitments

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.domain.commitment.CommitmentState

internal object CommitmentDetailProjector {
    private const val COUNTERPARTY_DISPLAY_MAX = 30

    fun buildLoadedState(
        entity: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): DetailUiState = DetailUiState(
        entity = entity,
        quote = entity.quote,
        counterpartyDisplayName = resolveCounterpartyDisplay(entity, enrichment),
        actionState = CommitmentState.fromWire(entity.actionState),
        source = CommitmentDetailFormatter.buildSourcePresentation(entity),
        actionButtons = buildActionButtonState(entity),
        history = CommitmentDetailFormatter.buildHistoryPresentation(entity),
        loading = false,
        error = null,
    )

    fun buildMissingState(): DetailUiState = DetailUiState(
        entity = null,
        counterpartyDisplayName = null,
        loading = false,
        error = CommitmentDetailViewModel.EMPTY_ERROR_KEY,
    )

    private fun resolveCounterpartyDisplay(
        commitment: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): String? {
        val ref = commitment.counterpartyRef
        return if (ref != null) {
            val hit = enrichment[ref]
            hit?.displayName ?: hit?.nickname ?: ref
        } else {
            commitment.counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)
        }
    }

    private fun buildActionButtonState(entity: CommitmentEntity): CommitmentDetailActionState {
        if (entity.itemType != CommitmentItemType.ACTION) {
            return CommitmentDetailActionState(
                availableActions = emptySet(),
                editEnabled = false,
            )
        }
        val state = CommitmentState.fromWire(entity.actionState)
        val available = buildSet {
            if (state == CommitmentState.PENDING) add(CommitmentSheetAction.REMIND)
            if (state == CommitmentState.PENDING || state == CommitmentState.REMINDED) {
                add(CommitmentSheetAction.FOLLOW_UP)
            }
            if (
                state == CommitmentState.PENDING ||
                state == CommitmentState.REMINDED ||
                state == CommitmentState.FOLLOWED_UP ||
                state == CommitmentState.OVERDUE
            ) {
                add(CommitmentSheetAction.COMPLETE)
                add(CommitmentSheetAction.CANCEL)
            }
        }
        return CommitmentDetailActionState(
            availableActions = available,
            editEnabled = state != CommitmentState.CANCELLED && entity.deletedAt == null,
        )
    }
}
