package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmFocusRing
import com.becalm.android.R
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.buildChips
import com.becalm.android.ui.main.buildSourceStatusAttention
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
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    val sourceChips = buildChips(state.sourceStatus)
    val sourceAttention = buildSourceStatusAttention(state.sourceStatus)

    OverallSyncIndicator(state = state.overall)
    if (sourceAttention.hasWarning) {
        MainTabSourceAttentionBanner(
            disconnectedCount = sourceAttention.disconnectedCount,
            failedCount = sourceAttention.failedCount,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }
    if (sourceChips.isNotEmpty()) {
        SourceStatusStrip(sources = sourceChips)
    }
}

@Composable
private fun MainTabSourceAttentionBanner(
    disconnectedCount: Int,
    failedCount: Int,
    onOpenSettings: (() -> Unit)?,
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
    // Color is not the only carrier of information: a leading 8dp error dot
    // signals the alert state, body text reads in onSurface with stable
    // contrast on the warm glass panel.
    //
    // The Text takes Modifier.weight(1f) so long messages wrap inside the Row
    // instead of being clipped — preserving the original Text(fillMaxWidth)
    // wrapping semantics from the pre-refactor single-Text banner.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onOpenSettings != null) {
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.today_source_attention_action))
            }
        }
    }
}
