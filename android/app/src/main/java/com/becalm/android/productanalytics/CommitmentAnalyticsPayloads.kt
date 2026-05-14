package com.becalm.android.productanalytics

import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.commitments.CommitmentDetailActionState
import com.becalm.android.ui.commitments.CommitmentSheetAction

public object CommitmentAnalyticsPayloads {
    public fun stateLabel(state: CommitmentState): String = state.name

    public fun actionName(action: CommitmentSheetAction): String =
        when (action) {
            CommitmentSheetAction.REMIND -> "remind"
            CommitmentSheetAction.FOLLOW_UP -> "follow_up"
            CommitmentSheetAction.COMPLETE -> "complete"
            CommitmentSheetAction.CANCEL -> "cancel"
        }

    public fun availableActionsForState(state: CommitmentState, editEnabled: Boolean = true): List<String> {
        val actions = buildList {
            if (state == CommitmentState.PENDING) add("remind")
            if (state == CommitmentState.PENDING || state == CommitmentState.REMINDED) add("follow_up")
            if (
                state == CommitmentState.PENDING ||
                state == CommitmentState.REMINDED ||
                state == CommitmentState.FOLLOWED_UP ||
                state == CommitmentState.OVERDUE
            ) {
                add("complete")
                add("cancel")
            }
            if (editEnabled && state != CommitmentState.CANCELLED) add("edit")
        }
        return actions
    }

    public fun availableActions(actionState: CommitmentDetailActionState): List<String> =
        buildList {
            actionState.availableActions
                .map(::actionName)
                .sorted()
                .forEach(::add)
            if (actionState.editEnabled) add("edit")
        }
}
