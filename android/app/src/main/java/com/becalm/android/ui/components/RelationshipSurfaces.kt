package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmColors

@Composable
public fun RelationshipCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val becalmColors = MaterialTheme.becalmColors
    RoleSurface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = becalmColors.glassPanelFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, becalmColors.glassBorder),
        shadowElevation = 1.dp,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
public fun EvidenceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val becalmColors = MaterialTheme.becalmColors
    RoleSurface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = becalmColors.glassPanelFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, becalmColors.glassBorder),
        shadowElevation = 0.dp,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
public fun RecommendationPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val becalmColors = MaterialTheme.becalmColors
    RoleSurface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = becalmColors.actionStateReminded.fill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, becalmColors.actionStateReminded.border),
        shadowElevation = 0.dp,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
public fun QuietPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val becalmColors = MaterialTheme.becalmColors
    RoleSurface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = becalmColors.glassPanelFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, becalmColors.glassBorder),
        shadowElevation = 0.dp,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun RoleSurface(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color,
    border: BorderStroke,
    shadowElevation: Dp,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = contentColor,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
