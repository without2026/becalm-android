package com.becalm.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * Compact preview of the raw event body (the 200-char `event_snippet` stored on
 * `raw_ingestion_events`).
 *
 * Rendered at `bodySmall` in `onSurfaceVariant`, capped at three lines with
 * ellipsis so short subjects plus long snippets still fit inside one sheet card.
 * When [snippet] is null or blank the composable omits itself — callers rely on
 * this to collapse the layout cleanly without a conditional on their side.
 *
 * Spec: `.spec/contracts/ui-map.yml:113-118 § EventSnippetText`,
 * SRC-004 (`.spec/source-viewer.spec.yml:37-45`).
 */
@Composable
public fun EventSnippetText(
    snippet: String?,
    modifier: Modifier = Modifier,
) {
    if (snippet.isNullOrBlank()) return
    Text(
        text = snippet,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
