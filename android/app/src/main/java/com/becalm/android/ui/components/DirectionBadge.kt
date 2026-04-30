package com.becalm.android.ui.components

import androidx.compose.foundation.border
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
import com.becalm.android.ui.theme.becalmColors

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
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme
    val (labelRes, fill, border) = when (direction) {
        DIRECTION_GIVE -> Triple(
            R.string.today_direction_badge_give,
            becalmColors.directionGive.fill,
            becalmColors.directionGive.border,
        )
        DIRECTION_TAKE -> Triple(
            R.string.today_direction_badge_take,
            becalmColors.directionTake.fill,
            becalmColors.directionTake.border,
        )
        else -> Triple(
            R.string.today_direction_badge_unknown,
            colorScheme.surfaceVariant,
            colorScheme.outline,
        )
    }
    DirectionBadgePill(
        labelRes = labelRes,
        fill = fill,
        border = border,
        modifier = modifier,
    )
}

@Composable
private fun DirectionBadgePill(
    labelRes: Int,
    fill: Color,
    border: Color,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    Surface(
        modifier = modifier.border(width = 1.dp, color = border, shape = pillShape),
        shape = pillShape,
        color = fill,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Wire value for "I promised to do something." */
private const val DIRECTION_GIVE: String = "give"

/** Wire value for "Someone promised to do something for me." */
private const val DIRECTION_TAKE: String = "take"

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
