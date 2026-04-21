package com.becalm.android.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Give/take pill rendering the direction of a commitment relationship for Today
 * timeline rows (TDY-001: "Commitment 에는 give/take 배지 + 'lee@corp.com' 표시됨").
 *
 * `direction` is the raw wire value from `CommitmentEntity.direction` — "give"
 * (I promised to do something) or "take" (someone promised me). Unknown values
 * render a generic dash so a future extension of the direction vocabulary
 * degrades safely instead of throwing.
 *
 * Spec: TDY-001, `.spec/contracts/ui-map.yml § TodayTimelineRow`.
 */
@Composable
public fun DirectionBadge(
    direction: String,
    modifier: Modifier = Modifier,
) {
    val (labelRes, containerColor, contentColor) = when (direction) {
        DIRECTION_GIVE -> Triple(
            R.string.today_direction_badge_give,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DIRECTION_TAKE -> Triple(
            R.string.today_direction_badge_take,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Triple(
            R.string.today_direction_badge_unknown,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DirectionBadgePill(
        labelRes = labelRes,
        container = containerColor,
        content = contentColor,
        modifier = modifier,
    )
}

@Composable
private fun DirectionBadgePill(
    labelRes: Int,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Wire value for "I promised to do something." */
internal const val DIRECTION_GIVE: String = "give"

/** Wire value for "Someone promised to do something for me." */
internal const val DIRECTION_TAKE: String = "take"

@PreviewLightDark
@Composable
private fun PreviewDirectionBadgeGive() {
    BecalmTheme { DirectionBadge(direction = DIRECTION_GIVE) }
}

@PreviewLightDark
@Composable
private fun PreviewDirectionBadgeTake() {
    BecalmTheme { DirectionBadge(direction = DIRECTION_TAKE) }
}
