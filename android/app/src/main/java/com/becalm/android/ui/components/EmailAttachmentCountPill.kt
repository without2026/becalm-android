package com.becalm.android.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Compact pill rendering "📎 첨부 N건" for an email event whose
 * `attachments_meta` JSON parses to at least one entry (EMAIL-004).
 *
 * The 📎 glyph lives inside the localized string resource, not inline in the
 * Composable, so locale / icon swaps happen at resource granularity without
 * touching the UI tree. Callers MUST guard on count > 0; a zero-count pill
 * communicates the wrong thing.
 *
 * Spec: EMAIL-004 (`.spec/email-pipeline.spec.yml:40-47`).
 */
@Composable
public fun EmailAttachmentCountPill(
    count: Int,
    modifier: Modifier = Modifier,
) {
    require(count > 0) { "EmailAttachmentCountPill must only render when count > 0 (got $count)" }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            text = stringResource(R.string.raw_event_attachments_count, count),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewEmailAttachmentCountPill() {
    BecalmTheme { EmailAttachmentCountPill(count = 2) }
}
