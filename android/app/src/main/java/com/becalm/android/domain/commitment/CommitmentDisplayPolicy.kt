package com.becalm.android.domain.commitment

import com.becalm.android.data.local.db.entity.CommitmentItemType

public object CommitmentDisplayPolicy {
    public fun isPrimaryFeedItem(itemType: String): Boolean =
        itemType == CommitmentItemType.ACTION || itemType == CommitmentItemType.SCHEDULE

    public fun isDecisionContextItem(itemType: String?): Boolean =
        itemType == CommitmentItemType.DECISION

    public fun countsAsOpenPersonLoop(
        itemType: String?,
        status: String?,
    ): Boolean =
        !isDecisionContextItem(itemType) &&
            status?.lowercase() !in TERMINAL_WORK_STATUSES

    private val TERMINAL_WORK_STATUSES = setOf("completed", "cancelled")
}
