package com.becalm.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.theme.glassPanel

@Composable
internal fun RequiredSetupSummary() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.onb_setup_required_terms),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onb_setup_required_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SetupConnectionRow(
    item: OnboardingSetupItemUi,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.detail != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SourceConnectionStatusPill(state = item.state)
        }
        if (!item.state.isTerminal) {
            SourceConnectionActions(
                primaryLabel = connectLabel(item.state, requiresConsent = false),
                onPrimary = onConnect,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
        }
    }
}

internal fun LazyListScope.sourceSection(
    title: String,
    items: List<SourceConnectionItemUi>,
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    skipLabel: String,
) {
    item(key = "$title-title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(items = items, key = { item -> item.provider.name }) { item ->
        SourceConnectionRow(
            item = item,
            onConnect = { onConnect(item.provider) },
            onSkip = { onSkip(item.provider) },
            skipLabel = skipLabel,
        )
    }
}

@Composable
private fun SourceConnectionRow(
    item: SourceConnectionItemUi,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SourceConnectionStatusPill(state = item.state)
        }
        if (item.consentCopy != null && item.state == SourceConnectionState.ConsentRequired) {
            Text(
                text = item.consentCopy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.state.isTerminal) {
            SourceConnectionActions(
                primaryLabel = connectLabel(item.state, item.consentCopy != null),
                onPrimary = onConnect,
                primaryEnabled = item.state != SourceConnectionState.Connecting &&
                    item.state != SourceConnectionState.PendingExternalAuth,
                primaryLoading = item.state == SourceConnectionState.Connecting ||
                    item.state == SourceConnectionState.PendingExternalAuth,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
        }
    }
}

@Composable
private fun SourceConnectionActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
    primaryEnabled: Boolean = true,
    primaryLoading: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BecalmButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            loading = primaryLoading,
            modifier = Modifier.weight(1f),
        )
        BecalmButton(
            text = skipLabel,
            onClick = onSkip,
            variant = BecalmButtonVariant.Text,
        )
    }
}

@Composable
private fun SourceConnectionStatusPill(state: SourceConnectionState) {
    val colors = MaterialTheme.colorScheme
    val dot = when (state) {
        SourceConnectionState.Connected -> colors.primary
        SourceConnectionState.Connecting,
        SourceConnectionState.PendingExternalAuth,
        -> colors.tertiary
        SourceConnectionState.Failed -> colors.error
        SourceConnectionState.Skipped -> colors.outline
        SourceConnectionState.ConsentRequired -> colors.secondary
        SourceConnectionState.Idle -> colors.outlineVariant
    }
    val container = when (state) {
        SourceConnectionState.Failed -> colors.errorContainer.copy(alpha = 0.45f)
        SourceConnectionState.Connected -> colors.primaryContainer.copy(alpha = 0.45f)
        else -> colors.surfaceVariant.copy(alpha = 0.5f)
    }
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = container,
        contentColor = colors.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dot, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stateLabel(state),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun connectLabel(state: SourceConnectionState, requiresConsent: Boolean): String =
    when {
        state == SourceConnectionState.Failed -> stringResource(R.string.onb_sources_retry)
        requiresConsent -> stringResource(R.string.onb_sources_connect_with_consent)
        else -> stringResource(R.string.action_connect)
    }

@Composable
private fun stateLabel(state: SourceConnectionState): String =
    stringResource(
        when (state) {
            SourceConnectionState.Idle -> R.string.onb_sources_status_ready
            SourceConnectionState.ConsentRequired -> R.string.onb_sources_status_consent
            SourceConnectionState.Connecting -> R.string.onb_sources_status_connecting
            SourceConnectionState.PendingExternalAuth -> R.string.onb_sources_status_waiting
            SourceConnectionState.Connected -> R.string.onb_sources_status_connected
            SourceConnectionState.Skipped -> R.string.onb_sources_status_skipped
            SourceConnectionState.Failed -> R.string.onb_sources_status_failed
        },
    )

private val SourceConnectionState.isTerminal: Boolean
    get() = this == SourceConnectionState.Connected || this == SourceConnectionState.Skipped
