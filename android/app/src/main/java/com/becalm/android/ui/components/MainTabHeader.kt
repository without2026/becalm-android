package com.becalm.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    IconButton(onClick = onOpenSettings, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.label_settings),
        )
    }
}

@Composable
public fun MainTabStatusHeader(
    state: MainTabHeaderState,
    modifier: Modifier = Modifier,
) {
    val sourceChips = buildChips(state.sourceStatus)
    val sourceAttention = buildSourceStatusAttention(state.sourceStatus)

    OverallSyncIndicator(state = state.overall)
    if (sourceAttention.hasWarning) {
        MainTabSourceAttentionBanner(
            disconnectedCount = sourceAttention.disconnectedCount,
            failedCount = sourceAttention.failedCount,
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
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
