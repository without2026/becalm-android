package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.CommitmentsExtractedBadge
import com.becalm.android.ui.components.EmailAttachmentCountPill
import com.becalm.android.ui.components.EventSnippetText
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.EventTitleText
import com.becalm.android.ui.components.IngestionTimestamp

/**
 * Email-specific branch of [RawEventDetailSheet].
 *
 * Renders — in order — the source-provider badge, subject line, snippet preview,
 * expandable plain-text body, attachment count pill, "약속 추출 N건" badge, and
 * the KST-formatted ingestion timestamp. Each subcomponent renders itself or
 * collapses gracefully, so the section never produces empty rows when optional
 * fields (snippet / body / attachments / extracted commitments) are absent.
 *
 * HTML-only degrade — when `body_plain` is null but `body_html` is present
 * (EMAIL-007 graceful-degrade case), the section surfaces a localized notice
 * plus a placeholder "원문보기" button whose click shows the post-MVP
 * [onViewOriginalRequested] hook (currently a toast / snackbar rendered by the
 * host Sheet).
 *
 * Spec: SRC-004, EMAIL-003, EMAIL-004, EMAIL-007,
 * `.spec/contracts/ui-map.yml:113-118 § RawEventDetailSheet.components`.
 *
 * @param state Fully hydrated UI state; caller guarantees
 *   `state.sourceType in EMAIL_SOURCE_TYPES`.
 * @param onViewOriginalRequested Called when the post-MVP "원문보기" button is
 *   tapped on the body-html-only degrade path. Host Sheet typically renders a
 *   "coming soon" toast.
 */
@Composable
internal fun EmailEventDetailSection(
    state: RawEventDetailUiState,
    onViewOriginalRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceType = state.sourceType ?: return
    val timestamp = state.timestamp ?: return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EventSourceBadge(sourceType = sourceType)

        EventTitleText(title = state.eventTitle)

        EventSnippetText(snippet = state.snippet)

        EmailBodyBlock(
            body = state.emailBody,
            onViewOriginalRequested = onViewOriginalRequested,
        )

        if (state.attachmentCount > 0 || state.commitmentsExtractedCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.attachmentCount > 0) {
                    EmailAttachmentCountPill(count = state.attachmentCount)
                }
                if (state.commitmentsExtractedCount > 0) {
                    CommitmentsExtractedBadge(count = state.commitmentsExtractedCount)
                }
            }
        }

        IngestionTimestamp(timestamp = timestamp)
    }
}

// ─── Internals ────────────────────────────────────────────────────────────────

private const val BODY_COLLAPSED_CHAR_LIMIT: Int = 500

/**
 * Renders the email body with progressive disclosure:
 * - `body_plain` present & length ≤ limit → full text, no toggle.
 * - `body_plain` present & length > limit → collapsed preview + expand toggle.
 * - `body_plain` null & `body_html` present → degrade notice + "원문보기" button.
 * - Both null → no row (the parent spacedBy gap collapses too).
 */
@Composable
private fun EmailBodyBlock(
    body: EmailBodyUi?,
    onViewOriginalRequested: () -> Unit,
) {
    if (body == null) return
    val bodyPlain = body.bodyPlain
    val bodyHtml = body.bodyHtml

    when {
        bodyPlain != null -> ExpandableBodyText(bodyPlain = bodyPlain)
        bodyHtml != null -> HtmlOnlyDegradeRow(onViewOriginalRequested = onViewOriginalRequested)
        else -> Unit
    }
}

@Composable
private fun ExpandableBodyText(bodyPlain: String) {
    val isLong = remember(bodyPlain) { bodyPlain.length > BODY_COLLAPSED_CHAR_LIMIT }
    var expanded by remember(bodyPlain) { mutableStateOf(false) }

    val visibleText = remember(bodyPlain, expanded, isLong) {
        if (isLong && !expanded) bodyPlain.take(BODY_COLLAPSED_CHAR_LIMIT) else bodyPlain
    }

    Column {
        Text(
            text = visibleText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isLong) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(all = 0.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.raw_event_body_collapse
                        else R.string.raw_event_body_expand,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HtmlOnlyDegradeRow(onViewOriginalRequested: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.raw_event_body_html_only_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = onViewOriginalRequested,
            contentPadding = PaddingValues(all = 0.dp),
        ) {
            Text(text = stringResource(R.string.raw_event_view_html_original))
        }
    }
}
