package com.becalm.android.ui.components

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType

internal object CommitmentWire {
    const val DIRECTION_GIVE: String = "give"
    const val DIRECTION_TAKE: String = "take"

    const val ACTION_PENDING: String = "pending"
    const val ACTION_REMINDED: String = "reminded"
    const val ACTION_FOLLOWED_UP: String = "followed_up"
    const val ACTION_COMPLETED: String = "completed"
    const val ACTION_OVERDUE: String = "overdue"
    const val ACTION_CANCELLED: String = "cancelled"

    const val ACTION_PENDING_UPPER: String = "PENDING"
    const val ACTION_REMINDED_UPPER: String = "REMINDED"
    const val ACTION_FOLLOWED_UPPER: String = "FOLLOWED_UP"
    const val ACTION_COMPLETED_UPPER: String = "COMPLETED"
    const val ACTION_OVERDUE_UPPER: String = "OVERDUE"
    const val ACTION_CANCELLED_UPPER: String = "CANCELLED"

    const val SCHEDULE_CONFIRMED: String = "confirmed"
    const val SCHEDULE_CHANGED: String = "changed"
    const val SCHEDULE_POSTPONED: String = "postponed"
    const val SCHEDULE_CANCELLED: String = "cancelled"
    const val SCHEDULE_FOLLOW_UP: String = "follow_up"
}

internal fun isGiveDirection(direction: String?): Boolean =
    direction.equals(CommitmentWire.DIRECTION_GIVE, ignoreCase = true)

internal fun isTakeDirection(direction: String?): Boolean =
    direction.equals(CommitmentWire.DIRECTION_TAKE, ignoreCase = true)

internal fun isTerminalActionState(status: String?): Boolean =
    status.equals(CommitmentWire.ACTION_COMPLETED, ignoreCase = true) ||
        status.equals(CommitmentWire.ACTION_CANCELLED, ignoreCase = true) ||
        status.equals(CommitmentWire.ACTION_COMPLETED_UPPER, ignoreCase = true) ||
        status.equals(CommitmentWire.ACTION_CANCELLED_UPPER, ignoreCase = true)

@StringRes
internal fun commitmentItemTypeLabelRes(itemType: String): Int = when (itemType.lowercase()) {
    CommitmentItemType.SCHEDULE -> R.string.commitment_item_type_schedule
    CommitmentItemType.DECISION -> R.string.commitment_item_type_decision
    else -> R.string.commitment_item_type_action
}

@StringRes
internal fun commitmentActionLabelRes(direction: String?): Int = when (direction?.lowercase()) {
    CommitmentWire.DIRECTION_GIVE -> R.string.commitment_action_label_give
    CommitmentWire.DIRECTION_TAKE -> R.string.commitment_action_label_take
    else -> R.string.commitment_action_label_unknown
}

internal fun commitmentActionSemanticsLabel(direction: String?): String = when (direction?.lowercase()) {
    CommitmentWire.DIRECTION_GIVE -> "my action"
    CommitmentWire.DIRECTION_TAKE -> "waiting on them"
    else -> "action"
}

@StringRes
internal fun commitmentActionStateLabelRes(status: String?): Int? = when (status?.lowercase()) {
    CommitmentWire.ACTION_PENDING -> R.string.commitment_state_pending
    CommitmentWire.ACTION_REMINDED -> R.string.commitment_state_reminded
    CommitmentWire.ACTION_FOLLOWED_UP -> R.string.commitment_state_followed_up
    CommitmentWire.ACTION_COMPLETED -> R.string.commitment_state_completed
    CommitmentWire.ACTION_OVERDUE -> R.string.commitment_state_overdue
    CommitmentWire.ACTION_CANCELLED -> R.string.commitment_state_cancelled
    else -> null
}

@StringRes
internal fun commitmentScheduleStatusLabelRes(status: String?): Int? = when (status?.lowercase()) {
    CommitmentWire.SCHEDULE_CONFIRMED -> R.string.commitment_subtype_schedule_confirmed
    CommitmentWire.SCHEDULE_CHANGED -> R.string.commitment_subtype_schedule_changed
    CommitmentWire.SCHEDULE_POSTPONED -> R.string.commitment_subtype_schedule_postponed
    CommitmentWire.SCHEDULE_CANCELLED -> R.string.commitment_subtype_schedule_cancelled
    CommitmentWire.SCHEDULE_FOLLOW_UP -> R.string.commitment_subtype_schedule_follow_up
    else -> null
}

@StringRes
internal fun commitmentDecisionStatusLabelRes(status: String?): Int? = when (status?.lowercase()) {
    "approved" -> R.string.commitment_subtype_decision_approved
    "rejected" -> R.string.commitment_subtype_decision_rejected
    "chosen" -> R.string.commitment_subtype_decision_chosen
    "deferred" -> R.string.commitment_subtype_decision_deferred
    "ongoing" -> R.string.commitment_subtype_decision_ongoing
    else -> null
}
