package com.becalm.android.unit.domain.commitment

import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.domain.commitment.CommitmentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentDisplayPolicySpecTest {
    @Test
    fun mainActionSurfaceAllowsOnlyActivePrimaryRows() {
        assertTrue(
            CommitmentDisplayPolicy.shouldShowOnMainActionSurface(
                itemType = CommitmentItemType.ACTION,
                status = CommitmentState.PENDING.wireValue,
                title = "Send the revised proposal",
                sourceTitle = "Proposal follow-up",
                counterpartyDisplayName = "Jane Kim",
            ),
        )
        assertTrue(
            CommitmentDisplayPolicy.shouldShowOnMainActionSurface(
                itemType = CommitmentItemType.SCHEDULE,
                status = CommitmentState.PENDING.wireValue,
                title = "Tuesday check-in",
                sourceTitle = "Tuesday check-in",
                counterpartyDisplayName = "Jane Kim",
            ),
        )
        assertFalse(
            CommitmentDisplayPolicy.shouldShowOnMainActionSurface(
                itemType = CommitmentItemType.ACTION,
                status = CommitmentState.COMPLETED.wireValue,
                title = "Completed item",
                sourceTitle = "Completed item",
                counterpartyDisplayName = "Jane Kim",
            ),
        )
        assertFalse(
            CommitmentDisplayPolicy.shouldShowOnMainActionSurface(
                itemType = CommitmentItemType.DECISION,
                status = CommitmentState.PENDING.wireValue,
                title = "Decision context",
                sourceTitle = "Decision context",
                counterpartyDisplayName = "Jane Kim",
            ),
        )
    }

    @Test
    fun mainActionSurfaceHidesServiceLifecycleNotifications() {
        assertFalse(
            CommitmentDisplayPolicy.shouldShowOnMainActionSurface(
                itemType = CommitmentItemType.ACTION,
                status = CommitmentState.PENDING.wireValue,
                title = "Verify your email address",
                sourceTitle = "Google account verification",
                counterpartyDisplayName = "Google",
            ),
        )
    }
}
