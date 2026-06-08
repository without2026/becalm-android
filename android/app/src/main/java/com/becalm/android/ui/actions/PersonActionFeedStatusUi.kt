package com.becalm.android.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import kotlinx.datetime.Instant

public enum class PersonActionFeedStatusKind {
    PENDING,
    STALE,
    DEGRADED,
    QUOTA_DELAY,
}

public data class PersonActionFeedStatusUi(
    val kind: PersonActionFeedStatusKind,
    val backlogLagSeconds: Int? = null,
    val lastCaughtUpAt: Instant? = null,
)

public fun personActionFeedStatusFor(syncState: PersonActionSyncStateEntity?): PersonActionFeedStatusUi? {
    if (syncState == null) return null
    val recomputeState = syncState.recomputeState?.trim()?.lowercase()
    val capacityState = syncState.capacityState?.trim()?.lowercase()
    val kind = when {
        capacityState == "quota_degraded" -> PersonActionFeedStatusKind.QUOTA_DELAY
        capacityState == "backlog" || capacityState == "degraded" -> PersonActionFeedStatusKind.DEGRADED
        recomputeState == "degraded" -> PersonActionFeedStatusKind.DEGRADED
        recomputeState == "stale" -> PersonActionFeedStatusKind.STALE
        recomputeState == "pending" -> PersonActionFeedStatusKind.PENDING
        else -> return null
    }
    return PersonActionFeedStatusUi(
        kind = kind,
        backlogLagSeconds = syncState.capacityBacklogLagSeconds,
        lastCaughtUpAt = syncState.capacityLastCaughtUpAt,
    )
}

@Composable
public fun PersonActionFeedStatusLine(
    status: PersonActionFeedStatusUi,
    onOpenProcessingStatus: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "person-action-feed-statusline",
    actionTestTag: String = "$testTag-action",
) {
    val message = personActionFeedStatusMessage(status)
    val dotColor = when (status.kind) {
        PersonActionFeedStatusKind.PENDING -> InkMistTake
        PersonActionFeedStatusKind.STALE -> InkMistGive
        PersonActionFeedStatusKind.DEGRADED,
        PersonActionFeedStatusKind.QUOTA_DELAY,
        -> InkMistWarn
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(InkMistLine2, RoundedCornerShape(12.dp))
            .border(1.dp, InkMistLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = InkMistGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
        )
        TextButton(
            onClick = onOpenProcessingStatus,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .testTag(actionTestTag),
        ) {
            Text(
                text = stringResource(R.string.persons_action_feed_status_action),
                color = InkMistTake,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
public fun personActionFeedStatusMessage(status: PersonActionFeedStatusUi): String {
    val textRes = when (status.kind) {
        PersonActionFeedStatusKind.PENDING -> R.string.persons_action_feed_status_pending
        PersonActionFeedStatusKind.STALE -> R.string.persons_action_feed_status_stale
        PersonActionFeedStatusKind.DEGRADED -> R.string.persons_action_feed_status_degraded
        PersonActionFeedStatusKind.QUOTA_DELAY -> R.string.persons_action_feed_status_quota
    }
    val lagLabel = status.backlogLagSeconds
        ?.takeIf { it >= 60 }
        ?.let { seconds ->
            stringResource(R.string.persons_action_feed_status_lag_minutes_fmt, (seconds / 60).coerceAtLeast(1))
        }
    return listOfNotNull(stringResource(textRes), lagLabel)
        .joinToString(separator = " · ")
}

@Composable
public fun personActionFeedCompactStatusMessage(status: PersonActionFeedStatusUi): String {
    val textRes = when (status.kind) {
        PersonActionFeedStatusKind.PENDING -> R.string.persons_action_feed_status_pending_compact
        PersonActionFeedStatusKind.STALE -> R.string.persons_action_feed_status_stale_compact
        PersonActionFeedStatusKind.DEGRADED -> R.string.persons_action_feed_status_degraded_compact
        PersonActionFeedStatusKind.QUOTA_DELAY -> R.string.persons_action_feed_status_quota_compact
    }
    val lagLabel = status.backlogLagSeconds
        ?.takeIf { it >= 60 }
        ?.let { seconds ->
            stringResource(R.string.persons_action_feed_status_lag_minutes_fmt, (seconds / 60).coerceAtLeast(1))
        }
    return listOfNotNull(stringResource(textRes), lagLabel)
        .joinToString(separator = " · ")
}

private val InkMistLine = Color(0xFFE0E6EF)
private val InkMistLine2 = Color(0xFFF0F3F8)
private val InkMistGray = Color(0xFF7A869A)
private val InkMistWarn = Color(0xFFBC8071)
private val InkMistGive = Color(0xFFC79272)
private val InkMistTake = Color(0xFF6589A1)
