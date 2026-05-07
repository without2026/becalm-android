package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmColors

public enum class StatusTone {
    Success,
    Progress,
    Attention,
    Error,
    Muted,
    Neutral,
}

private val StatusPillShape = RoundedCornerShape(100.dp)

@Composable
public fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val becalmColors = MaterialTheme.becalmColors
    val dotColor = statusToneDotColor(tone)
    val containerColor = when (tone) {
        StatusTone.Error -> colors.errorContainer.copy(alpha = 0.45f)
        StatusTone.Success -> colors.primaryContainer.copy(alpha = 0.45f)
        StatusTone.Progress,
        StatusTone.Attention,
        StatusTone.Muted,
        StatusTone.Neutral,
        -> becalmColors.glassPanelFill
    }
    Row(
        modifier = modifier
            .semantics { contentDescription = label }
            .background(color = containerColor, shape = StatusPillShape)
            .border(width = 1.dp, color = becalmColors.glassBorder, shape = StatusPillShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        if (!compact) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
internal fun statusToneDotColor(tone: StatusTone): Color {
    val colors = MaterialTheme.colorScheme
    val becalmColors = MaterialTheme.becalmColors
    return when (tone) {
        StatusTone.Success -> becalmColors.sourceStatusOk
        StatusTone.Progress -> colors.primary
        StatusTone.Attention -> becalmColors.sourceStatusStale
        StatusTone.Error -> becalmColors.sourceStatusError
        StatusTone.Muted -> colors.outlineVariant
        StatusTone.Neutral -> colors.outline
    }
}
