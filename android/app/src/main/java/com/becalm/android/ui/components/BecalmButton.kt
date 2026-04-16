/**
 * SP-46: Reusable button component for BeCalm Android.
 *
 * Provides three visual variants — Primary, Secondary, and Text — through a
 * single entry point driven by [BecalmButtonVariant].
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.dimens
import com.becalm.android.ui.theme.glassPanel
import com.becalm.android.ui.theme.glassPanelElevated

// ─── Variant enum ──────────────────────────────────────────────────────────────

/**
 * Defines the three visual variants of [BecalmButton].
 *
 * - [Primary]: filled button with `colorScheme.primary` background and glass-elevated shape.
 * - [Secondary]: outlined glass-panel button for secondary actions.
 * - [Text]: no-background text-only button for tertiary / inline actions.
 */
public enum class BecalmButtonVariant {
    Primary,
    Secondary,
    Text,
}

// ─── BecalmButton ─────────────────────────────────────────────────────────────

/**
 * Unified button component that renders one of three visual variants based on
 * [variant], with built-in loading and disabled states.
 *
 * @param text         Label displayed inside the button. Supply a localized string.
 * @param onClick      Invoked when the button is tapped. No-op while [loading] is true.
 * @param modifier     Optional [Modifier] applied to the outer container.
 * @param variant      Visual style — [BecalmButtonVariant.Primary], [Secondary], or [Text].
 * @param enabled      When `false`, the button is non-interactive and rendered at 0.38 alpha.
 * @param loading      When `true`, replaces the label with a 16 dp [CircularProgressIndicator]
 *                     and disables interaction.
 * @param leadingIcon  Optional icon drawn to the left of the label (not shown during loading).
 */
@Composable
public fun BecalmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BecalmButtonVariant = BecalmButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val isInteractive = enabled && !loading
    val effectiveModifier = modifier
        .defaultMinSize(minHeight = MaterialTheme.dimens.buttonHeight)
        .then(if (!enabled) Modifier.alpha(0.38f) else Modifier)
        .semantics { role = Role.Button }

    when (variant) {
        BecalmButtonVariant.Primary -> {
            Button(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier.glassPanelElevated(MaterialTheme.shapes.medium),
                enabled = isInteractive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading)
            }
        }

        BecalmButtonVariant.Secondary -> {
            Button(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier.glassPanel(MaterialTheme.shapes.small),
                enabled = isInteractive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading)
            }
        }

        BecalmButtonVariant.Text -> {
            TextButton(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier,
                enabled = isInteractive,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading)
            }
        }
    }
}

// ─── Private helpers ──────────────────────────────────────────────────────────

@Composable
private fun ButtonContent(
    text: String,
    leadingIcon: ImageVector?,
    loading: Boolean,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = LocalContentColor.current,
            strokeWidth = 2.dp,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                androidx.compose.material3.Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonPrimary() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Confirm", onClick = {}, variant = BecalmButtonVariant.Primary)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonSecondary() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Cancel", onClick = {}, variant = BecalmButtonVariant.Secondary)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonText() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Skip", onClick = {}, variant = BecalmButtonVariant.Text)
        }
    }
}

@Preview
@Composable
private fun PreviewBecalmButtonLoading() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Loading", onClick = {}, loading = true)
        }
    }
}

@Preview
@Composable
private fun PreviewBecalmButtonDisabled() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}
