package com.becalm.android.ui.components

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType

@StringRes
internal fun commitmentItemTypeLabelRes(itemType: String): Int = when (itemType.lowercase()) {
    CommitmentItemType.SCHEDULE -> R.string.commitment_item_type_schedule
    CommitmentItemType.DECISION -> R.string.commitment_item_type_decision
    else -> R.string.commitment_item_type_action
}

@StringRes
internal fun commitmentActionLabelRes(direction: String?): Int = when (direction?.lowercase()) {
    "give" -> R.string.commitment_action_label_give
    "take" -> R.string.commitment_action_label_take
    else -> R.string.commitment_action_label_unknown
}

internal fun commitmentActionSemanticsLabel(direction: String?): String = when (direction?.lowercase()) {
    "give" -> "my action"
    "take" -> "waiting on them"
    else -> "action"
}
