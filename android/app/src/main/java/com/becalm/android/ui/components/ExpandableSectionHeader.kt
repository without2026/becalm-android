package com.becalm.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Expandable section header used by [com.becalm.android.ui.commitments.CommitmentManagementScreen]
 * to group the `이행 완료` / `취소된 약속` sections per spec CMT-009.
 *
 * Renders a tap-to-toggle row: title + chevron that rotates 180° when [expanded].
 * Clicking anywhere on the row invokes [onToggle]. The chevron rotation animates so users get
 * an affordance signal separate from the title text.
 *
 * The count is formatted into [title] by the caller (`commitment_section_*_fmt` resources own
 * the "제목 (N)" shape). Keeping the title pre-formatted lets this composable stay a pure
 * presenter with no locale-specific formatting logic.
 *
 * @param title    Localized title string including the count, e.g. "이행 완료 (3)".
 * @param expanded Whether the section is currently expanded. Drives chevron rotation only;
 *                 actual item visibility is owned by the caller.
 * @param onToggle Tap handler. Flipping the expanded boolean is the caller's responsibility.
 * @param modifier Optional modifier applied to the Row root.
 */
@Composable
public fun ExpandableSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "SectionHeaderChevronRotation",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics { role = Role.Button }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.commitment_section_expand_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewExpandableSectionHeader() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                title = "이행 완료 (3)",
                expanded = false,
                onToggle = {},
            )
        }
    }
}
