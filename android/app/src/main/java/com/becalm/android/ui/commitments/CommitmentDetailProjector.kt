package com.becalm.android.ui.commitments

import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.domain.reminder.CommitmentReminderReconciler
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.UiMessage

internal object CommitmentDetailProjector {
    private const val COUNTERPARTY_DISPLAY_MAX = 30

    fun buildLoadedState(
        entity: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
        meetingTranscript: MeetingTranscriptPresentation? = null,
        disabledReminderIds: Set<String> = emptySet(),
    ): DetailUiState = DetailUiState(
        entity = entity,
        quote = entity.quote,
        counterpartyDisplayName = resolveCounterpartyDisplay(entity, enrichment),
        actionState = CommitmentState.fromWire(entity.actionState),
        source = CommitmentDetailFormatter.buildSourcePresentation(entity),
        actionButtons = buildActionButtonState(entity, disabledReminderIds),
        history = CommitmentDetailFormatter.buildHistoryPresentation(entity),
        meetingTranscript = meetingTranscript,
        loading = false,
        error = null,
    )

    fun buildMissingState(): DetailUiState = DetailUiState(
        entity = null,
        counterpartyDisplayName = null,
        loading = false,
        error = UiMessage.resource(R.string.commitment_detail_empty_error),
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

    private fun buildActionButtonState(
        entity: CommitmentEntity,
        disabledReminderIds: Set<String>,
    ): CommitmentDetailActionState {
        val reminderEligible = CommitmentReminderReconciler.isReminderEligible(entity)
        if (entity.itemType != CommitmentItemType.ACTION) {
            return CommitmentDetailActionState(
                availableActions = emptySet(),
                editEnabled = false,
                reminderToggleVisible = reminderEligible,
                reminderEnabled = reminderEligible && entity.id !in disabledReminderIds,
            )
        }
        val state = CommitmentState.fromWire(entity.actionState)
        val available = buildSet {
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
            reminderToggleVisible = reminderEligible,
            reminderEnabled = reminderEligible && entity.id !in disabledReminderIds,
        )
    }
}
