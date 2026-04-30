package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Inline badge rendering "약속 추출 N건" for a raw ingestion event whose
 * `commitments_extracted_count` is ≥ 1.
 *
 * Callers MUST guard on count > 0 (a zero-count badge would be both noisy and
 * spec-violating per SRC-008) — an assertion enforces the precondition rather
 * than silently rendering "0건".
 *
 * Spec: SRC-008 (`.spec/source-viewer.spec.yml:76-83`),
 * `.spec/contracts/ui-map.yml:113-118 § CommitmentsExtractedBadge`.
 */
@Composable
public fun CommitmentsExtractedBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    require(count > 0) { "CommitmentsExtractedBadge must only render when count > 0 (got $count)" }
    // Neutral surfaceVariant (not tertiaryContainer / amber) — Single Voice
    // Rule reserves the amber accent for D-0 / reminded / source-stale only.
    // "X commitments extracted" is informational, not urgency-coded.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(size = 14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.raw_event_commitments_extracted, count),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewCommitmentsExtractedBadge() {
    BecalmTheme { CommitmentsExtractedBadge(count = 2) }
}
