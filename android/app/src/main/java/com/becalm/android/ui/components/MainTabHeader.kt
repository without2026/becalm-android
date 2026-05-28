package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmFocusRing
import com.becalm.android.R
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.buildChips
import com.becalm.android.ui.main.buildSourceStatusAttention
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.glassPanel

@Composable
public fun MainTabHeaderActions(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sync state is communicated by [OverallSyncIndicator] (text banner under
    // the app bar) and by [SourceStatusStrip] dots. The action slot stays
    // quiet so the app bar never shows ambient process motion. See DESIGN.md
    // Process-Hidden Rule.
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onOpenSettings,
        modifier = modifier.becalmFocusRing(MaterialTheme.shapes.small, source),
        interactionSource = source,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.label_settings),
        )
    }
}

@Composable
public fun MainTabStatusHeader(
    state: MainTabHeaderState,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = onOpenSettings,
    onOpenSource: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sourceChips = buildChips(state.sourceStatus)
    val sourceAttention = buildSourceStatusAttention(state.sourceStatus)

    OverallSyncIndicator(state = state.overall)
    if (sourceAttention.hasWarning) {
        MainTabSourceAttentionBanner(
            disconnectedCount = sourceAttention.disconnectedCount,
            failedCount = sourceAttention.failedCount,
            disconnectedSources = sourceAttention.disconnectedSources,
            failedSources = sourceAttention.failedSources,
            onOpenSources = onOpenSources,
            onOpenSource = onOpenSource,
            modifier = modifier,
        )
    }
    if (sourceChips.isNotEmpty()) {
        SourceStatusStrip(
            sources = sourceChips,
            onSourceClick = onOpenSource,
        )
    }
}

@Composable
private fun MainTabSourceAttentionBanner(
    disconnectedCount: Int,
    failedCount: Int,
    disconnectedSources: List<String>,
    failedSources: List<String>,
    onOpenSources: (() -> Unit)?,
    onOpenSource: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val text = when {
        disconnectedCount > 0 && failedCount > 0 ->
            stringResource(R.string.today_source_attention_mixed_fmt, disconnectedCount, failedCount)
        failedCount > 0 ->
            stringResource(R.string.today_source_attention_failed_fmt, failedCount)
        else ->
            stringResource(R.string.today_source_attention_disconnected_fmt, disconnectedCount)
    }
    val attentionColor = if (failedCount > 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val expandable = disconnectedSources.isNotEmpty() || failedSources.isNotEmpty()
    var expanded by rememberSaveable(disconnectedCount, failedCount) { mutableStateOf(false) }
    val expandedLabel = if (expanded) {
        stringResource(R.string.today_source_attention_collapse)
    } else {
        stringResource(R.string.today_source_attention_expand)
    }
    val headerA11y = listOf(
        text,
        expandedLabel.takeIf { expandable },
    ).filterNotNull().joinToString(separator = ", ")
    // Color is not the only carrier of information: a leading 8dp error dot
    // signals the alert state, body text reads in onSurface with stable
    // contrast on the warm glass panel.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) {
                        Modifier
                            .clickable(
                                role = Role.Button,
                                onClick = { expanded = !expanded },
                            )
                            .semantics {
                                contentDescription = headerA11y
                                stateDescription = expandedLabel
                            }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = if (expandable) 4.dp else 0.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(attentionColor),
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (expandable) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                failedSources.forEach { sourceType ->
                    SourceAttentionRow(
                        sourceType = sourceType,
                        status = SourceSyncStatus.Error,
                        onOpenSource = onOpenSource,
                    )
                }
                disconnectedSources.forEach { sourceType ->
                    SourceAttentionRow(
                        sourceType = sourceType,
                        status = SourceSyncStatus.Disconnected,
                        onOpenSource = onOpenSource,
                    )
                }
            }
        }
        if (onOpenSources != null) {
            BecalmButton(
                text = stringResource(R.string.today_source_attention_action),
                onClick = onOpenSources,
                variant = BecalmButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (expanded) 4.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun SourceAttentionRow(
    sourceType: String,
    status: SourceSyncStatus,
    onOpenSource: ((String) -> Unit)?,
) {
    val presentation = sourcePresentationFor(sourceType)
    val sourceName = stringResource(presentation.labelRes)
    val statusLabel = stringResource(sourceStatusLabelRes(status))
    val actionLabel = stringResource(R.string.sources_status_open_source_detail_a11y)
    val rowDescription = listOf(sourceName, statusLabel, actionLabel.takeIf { onOpenSource != null })
        .filterNotNull()
        .joinToString(separator = ", ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpenSource != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = { onOpenSource(sourceType) },
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = rowDescription
                stateDescription = statusLabel
                if (onOpenSource != null) role = Role.Button
            },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.becalmColors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = presentation.accentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
