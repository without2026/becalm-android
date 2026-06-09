package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

public enum class BecalmActionPillVariant {
    Secondary,
    Neutral,
    Destructive,
}

public enum class BecalmActionPillSize {
    Regular,
    Mini,
}

/**
 * Compact icon+label control for repeated row-level actions. Use this instead of
 * text buttons when the action is secondary, tool-like, or repeated in a list.
 */
@Composable
public fun BecalmActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingChevron: Boolean = false,
    expanded: Boolean? = null,
    variant: BecalmActionPillVariant = BecalmActionPillVariant.Secondary,
    size: BecalmActionPillSize = BecalmActionPillSize.Regular,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = actionPillColors(variant)
    val minHeight = when (size) {
        BecalmActionPillSize.Regular -> 36.dp
        BecalmActionPillSize.Mini -> 30.dp
    }
    val horizontalPadding = when (size) {
        BecalmActionPillSize.Regular -> 12.dp
        BecalmActionPillSize.Mini -> 9.dp
    }
    val verticalPadding = when (size) {
        BecalmActionPillSize.Regular -> 7.dp
        BecalmActionPillSize.Mini -> 5.dp
    }
    val iconSize = when (size) {
        BecalmActionPillSize.Regular -> 16.dp
        BecalmActionPillSize.Mini -> 14.dp
    }
    val labelStyle = when (size) {
        BecalmActionPillSize.Regular -> MaterialTheme.typography.labelMedium
        BecalmActionPillSize.Mini -> MaterialTheme.typography.labelSmall
    }
    val rotation = when (expanded) {
        true -> -90f
        false -> 90f
        null -> 0f
    }
    val interactive = enabled && !loading
    Surface(
        modifier = modifier
            .heightIn(min = minHeight)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        color = colors.content,
                        strokeWidth = 2.dp,
                    )
                }
                icon != null -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.content,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            Text(
                text = text,
                style = labelStyle,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingChevron || expanded != null) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier
                        .size(iconSize)
                        .rotate(rotation),
                )
            }
        }
    }
}

private data class ActionPillColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun actionPillColors(variant: BecalmActionPillVariant): ActionPillColors =
    when (variant) {
        BecalmActionPillVariant.Secondary -> ActionPillColors(
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            border = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        )
        BecalmActionPillVariant.Neutral -> ActionPillColors(
            container = MaterialTheme.colorScheme.surface,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            border = MaterialTheme.colorScheme.outlineVariant,
        )
        BecalmActionPillVariant.Destructive -> ActionPillColors(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.error,
            border = MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
        )
    }
