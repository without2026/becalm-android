/**
 * SP-49: Full-screen empty-state and error-state placeholder composables.
 *
 * Both composables center their content vertically and share the same visual
 * rhythm (24 dp spacing between icon, title, message, and action).
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme

// ─── EmptyStateAction ─────────────────────────────────────────────────────────

/**
 * Describes the optional call-to-action button rendered by [EmptyState].
 *
 * @param label   Button label text (localized by caller).
 * @param onClick Invoked when the action button is tapped.
 */
@Stable
public data class EmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

// ─── EmptyState ───────────────────────────────────────────────────────────────

/**
 * Centered empty-state placeholder with an optional icon, title, message, and
 * action button.
 *
 * Spacing between each element is 24 dp. The icon is tinted [onSurfaceVariant];
 * title uses [titleMedium] in [onSurface]; message uses [bodyMedium] in
 * [onSurfaceVariant].
 *
 * @param title    Primary heading displayed below the icon (localized by caller).
 * @param message  Optional descriptive message below the title.
 * @param icon     Optional icon displayed above the title at 48 dp. Pass `null` to omit.
 * @param action   Optional [EmptyStateAction] rendered as a secondary [BecalmButton].
 * @param modifier Optional [Modifier] applied to the root column.
 */
@Composable
public fun EmptyState(
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    action: EmptyStateAction? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            BecalmButton(
                text = action.label,
                onClick = action.onClick,
                variant = BecalmButtonVariant.Secondary,
            )
        }
    }
}

// ─── ErrorState ───────────────────────────────────────────────────────────────

/**
 * Centered error-state placeholder with a fixed [Icons.Filled.Warning] icon,
 * title, optional message, and optional retry button.
 *
 * The error icon and title are tinted [error]; message uses [bodyMedium] in
 * [onSurfaceVariant].
 *
 * @param title      Primary heading describing the error (localized by caller).
 * @param message    Optional detail message below the title.
 * @param onRetry    Optional retry callback. When non-null, a [BecalmButton] with
 *                   [retryLabel] is rendered.
 * @param retryLabel Label for the retry button; defaults to `"Retry"`.
 * @param modifier   Optional [Modifier] applied to the root column.
 */
@Composable
public fun ErrorState(
    title: String,
    message: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = stringResource(R.string.error_state_retry),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(24.dp))
            BecalmButton(
                text = retryLabel,
                onClick = onRetry,
                variant = BecalmButtonVariant.Secondary,
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewEmptyStateWithAction() {
    BecalmTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = "No commitments yet",
                message = "Add your first commitment to start tracking what matters.",
                icon = Icons.Filled.Star,
                action = EmptyStateAction(label = "Add commitment", onClick = {}),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewEmptyStateMinimal() {
    BecalmTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(title = "Nothing here")
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewErrorStateWithRetry() {
    BecalmTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            ErrorState(
                title = "Something went wrong",
                message = "Could not load commitments. Check your connection and try again.",
                onRetry = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewErrorStateMinimal() {
    BecalmTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            ErrorState(title = "Failed to load")
        }
    }
}
