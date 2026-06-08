package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.becalmFocusRing
import com.becalm.android.R
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.SourceStatusAttention
import com.becalm.android.ui.main.buildChips
import com.becalm.android.ui.main.buildSourceStatusAttention
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.glassPanel

@Composable
public fun MainTabHeaderActions(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Sync state is communicated by [OverallSyncIndicator] (text banner under
    // the app bar) and by [SourceStatusStrip] dots. The action slot stays
    // quiet so the app bar never shows ambient process motion. See DESIGN.md
    // Process-Hidden Rule.
    val source = remember { MutableInteractionSource() }
    val buttonSize = if (compact) 36.dp else 48.dp
    val iconSize = if (compact) 18.dp else 24.dp
    IconButton(
        onClick = onOpenSettings,
        modifier = modifier
            .size(buttonSize)
            .becalmFocusRing(MaterialTheme.shapes.small, source),
        interactionSource = source,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.label_settings),
            modifier = Modifier.size(iconSize),
            tint = if (compact) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
public fun MainTabCompactSourceAttentionLine(
    state: MainTabHeaderState,
    onOpenSources: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenSource: ((String) -> Unit)? = null,
    supportingStatusText: String? = null,
    onOpenSupportingStatus: (() -> Unit)? = null,
    testTagPrefix: String = "main-tab-source",
) {
    val attention = buildSourceStatusAttention(state.sourceStatus)
    if (!attention.hasWarning) return
    val primaryFailedSource = state.primaryFailedSourceForCompactLine()
    if (primaryFailedSource != null) {
        val reconnect: ((String) -> Unit)? = when {
            onOpenSource != null -> onOpenSource
            onOpenSources != null -> ({ _: String -> onOpenSources() })
            else -> null
        }
        MainTabCompactSourceStatusLine(
            sourceType = primaryFailedSource,
            onReconnect = reconnect,
            modifier = modifier,
            supportingStatusText = supportingStatusText,
            onOpenSupportingStatus = onOpenSupportingStatus,
            testTagPrefix = testTagPrefix,
        )
    } else {
        MainTabCompactSourceSummaryLine(
            attention = attention,
            onOpenSources = onOpenSources,
            modifier = modifier,
            supportingStatusText = supportingStatusText,
            onOpenSupportingStatus = onOpenSupportingStatus,
            testTagPrefix = testTagPrefix,
        )
    }
}

@Composable
public fun MainTabCompactSourceStatusLine(
    sourceType: String,
    onReconnect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    supportingStatusText: String? = null,
    onOpenSupportingStatus: (() -> Unit)? = null,
    testTagPrefix: String = "main-tab-source",
) {
    val sourceName = stringResource(sourcePresentationFor(sourceType).labelRes)
    val hasSupportingStatus = supportingStatusText?.isNotBlank() == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("$testTagPrefix-statusline-$sourceType"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            text = buildAnnotatedString {
                val supportingText = supportingStatusText?.takeIf { it.isNotBlank() }
                if (supportingText != null) {
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append(stringResource(R.string.persons_source_status_delayed_prefix_fmt, sourceName))
                    }
                    append(" · ")
                    append(supportingText)
                    append(" · ")
                    append(stringResource(R.string.main_tab_source_status_last_snapshot_short))
                } else {
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append(stringResource(R.string.persons_source_status_delayed_prefix_fmt, sourceName))
                    }
                    append(stringResource(R.string.persons_source_status_delayed_suffix))
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
        )
        if (hasSupportingStatus && onOpenSupportingStatus != null) {
            TextButton(
                onClick = onOpenSupportingStatus,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .testTag("$testTagPrefix-supporting-status-action"),
            ) {
                Text(
                    text = stringResource(R.string.persons_action_feed_status_action),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        if (onReconnect != null) {
            TextButton(
                onClick = { onReconnect(sourceType) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .testTag("$testTagPrefix-reconnect-$sourceType"),
            ) {
                Text(
                    text = stringResource(R.string.action_reconnect),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun MainTabCompactSourceSummaryLine(
    attention: SourceStatusAttention,
    onOpenSources: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supportingStatusText: String?,
    onOpenSupportingStatus: (() -> Unit)?,
    testTagPrefix: String,
) {
    val hasSupportingStatus = supportingStatusText?.isNotBlank() == true
    val message = when {
        attention.disconnectedCount > 0 && attention.failedCount > 0 ->
            stringResource(
                R.string.today_source_attention_mixed_fmt,
                attention.disconnectedCount,
                attention.failedCount,
            )
        attention.failedCount > 0 ->
            stringResource(R.string.today_source_attention_failed_fmt, attention.failedCount)
        else ->
            stringResource(R.string.today_source_attention_disconnected_fmt)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("$testTagPrefix-summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (attention.failedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                ),
        )
        Text(
            text = buildAnnotatedString {
                val supportingText = supportingStatusText?.takeIf { it.isNotBlank() }
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(message)
                }
                append(" · ")
                if (supportingText != null) {
                    append(supportingText)
                    append(" · ")
                    append(stringResource(R.string.main_tab_source_status_last_snapshot_short))
                } else {
                    append(stringResource(R.string.main_tab_source_status_last_snapshot))
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
        )
        if (hasSupportingStatus && onOpenSupportingStatus != null) {
            TextButton(
                onClick = onOpenSupportingStatus,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .testTag("$testTagPrefix-supporting-status-action"),
            ) {
                Text(
                    text = stringResource(R.string.persons_action_feed_status_action),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        if (onOpenSources != null) {
            TextButton(
                onClick = onOpenSources,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .testTag("$testTagPrefix-summary-action"),
            ) {
                Text(
                    text = stringResource(R.string.today_source_attention_action),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

public fun MainTabHeaderState.primaryFailedSourceForCompactLine(): String? {
    val attention = buildSourceStatusAttention(sourceStatus)
    return attention.failedSources.singleOrNull()
        ?.takeIf { attention.disconnectedSources.isEmpty() }
}

public fun MainTabHeaderState.hasSourceWarningForCompactLine(): Boolean =
    buildSourceStatusAttention(sourceStatus).hasWarning

@Composable
public fun MainTabStatusHeader(
    state: MainTabHeaderState,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = onOpenSettings,
    onOpenSource: ((String) -> Unit)? = null,
    showOverallIndicator: Boolean = true,
    showSourceAttentionBanner: Boolean = true,
    showSourceStatusStrip: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val sourceChips = buildChips(state.sourceStatus)
    val sourceAttention = buildSourceStatusAttention(state.sourceStatus)

    if (showOverallIndicator) {
        OverallSyncIndicator(state = state.overall)
    }
    if (showSourceAttentionBanner && sourceAttention.hasWarning) {
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
    if (showSourceStatusStrip && sourceChips.isNotEmpty()) {
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
