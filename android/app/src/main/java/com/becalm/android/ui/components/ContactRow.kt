package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmColors

@Composable
public fun ContactRow(
    headline: String,
    metadata: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attentionLabel: String? = null,
    supportingText: String? = null,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.becalmColors.glassPanelFill,
                shape = MaterialTheme.shapes.medium,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.becalmColors.glassBorder,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.Top,
    ) {
        leading()
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!attentionLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    AttentionPill(text = attentionLabel)
                }
            }
            if (!attentionLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!metadata.isNullOrBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AttentionPill(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.becalmColors.actionStateReminded.fill)
            .border(1.dp, MaterialTheme.becalmColors.actionStateReminded.border, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.becalmColors.actionStateReminded.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
