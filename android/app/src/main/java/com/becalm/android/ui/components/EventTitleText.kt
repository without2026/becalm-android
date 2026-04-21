package com.becalm.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.becalm.android.R

/**
 * Semantic title renderer for a raw ingestion event (email subject, voice title,
 * calendar event title, …).
 *
 * Rendered at `titleMedium` size, capped at two lines with ellipsis overflow so
 * long subjects do not push the rest of the sheet off-screen. Exposes a heading
 * role for TalkBack so screen-reader users can navigate between the title and the
 * body sections with header jumps.
 *
 * When [title] is null (messages without a subject per RFC 5322) a localized
 * placeholder is rendered in the same style — the layout must not collapse.
 *
 * Spec: `.spec/contracts/ui-map.yml:113-118 § EventTitleText`.
 */
@Composable
public fun EventTitleText(
    title: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title ?: stringResource(R.string.raw_event_detail_no_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}
