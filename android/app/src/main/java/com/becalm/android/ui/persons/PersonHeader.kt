package com.becalm.android.ui.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.ui.components.RelationshipCard
import com.becalm.android.ui.theme.becalmColors

/**
 * Top header composable for [PersonDetailScreen] — renders the person's display
 * name plus an optional role/company subtitle.
 *
 * Respects ENR-006 fallback: when [displayName] is null / blank, a
 * user-facing unknown-contact label is shown so raw technical identity values are
 * not exposed.
 */
@Composable
internal fun PersonHeader(
    displayName: String?,
    nickname: String?,
    companyName: String?,
    jobTitle: String?,
    personId: String,
    modifier: Modifier = Modifier,
    eventCount: Int = 0,
    emailInteractionCount: Int = 0,
    callInteractionCount: Int = 0,
    meetingCount: Int = 0,
    pendingCommitmentCount: Int = 0,
) {
    val nameLine = listOf(displayName, nickname)
        .firstOrNull { isDisplayNameValue(it) }
        ?: stringResource(R.string.persons_unidentified)
    val subtitle = composeSubtitle(jobTitle = jobTitle, companyName = companyName)
    val metaLine = composeMetaLine(
        nickname = nickname,
        eventCount = eventCount,
        pendingCommitmentCount = pendingCommitmentCount,
    )

    RelationshipCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderAvatar(seed = nameLine)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = nameLine,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metaLine != null) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    label = stringResource(R.string.person_detail_stat_email),
                    count = emailInteractionCount,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.person_detail_stat_call),
                    count = callInteractionCount,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.person_detail_stat_meeting),
                    count = meetingCount,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.person_detail_stat_commitment),
                    count = pendingCommitmentCount,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun isDisplayNameValue(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (PersonIdentityResolver.isSpeakerLabelValue(value)) return false
    if (PersonIdentityResolver.normalizeEmailAnchor(value) != null) return false
    if (PersonIdentityResolver.normalizePhoneAnchor(value) != null) return false
    return true
}

@Composable
private fun HeaderAvatar(seed: String) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatTile(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .height(52.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.becalmColors.glassBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun composeSubtitle(jobTitle: String?, companyName: String?): String? {
    val job = jobTitle?.takeIf { it.isNotBlank() }
    val company = companyName?.takeIf { it.isNotBlank() }
    return when {
        job != null && company != null ->
            stringResource(R.string.person_header_job_subtitle, job, company)
        job != null -> stringResource(R.string.person_header_job_only, job)
        company != null -> stringResource(R.string.person_header_company_only, company)
        else -> null
    }
}

@Composable
private fun composeMetaLine(
    nickname: String?,
    eventCount: Int,
    pendingCommitmentCount: Int,
): String? {
    val parts = buildList {
        nickname?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.person_header_nickname_fmt, it))
        }
        if (eventCount > 0) add(stringResource(R.string.person_header_event_count_fmt, eventCount))
        if (pendingCommitmentCount > 0) {
            add(stringResource(R.string.person_header_pending_count_fmt, pendingCommitmentCount))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
