package com.becalm.android.ui.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import com.becalm.android.ui.theme.glassPanel

/**
 * Top header composable for [PersonDetailScreen] — renders the person's display
 * name plus an optional role/company subtitle.
 *
 * Respects ENR-006 fallback: when [displayName] is null / blank, [personRef] is
 * rendered through the UI-only display-label formatter so email refs still have
 * a human handle before enrichment has run.
 *
 * The subtitle composes [jobTitle] and [companyName] into one of four shapes:
 * both → `"Engineer · Acme"`, job only → `"Engineer"`, company only → `"Acme"`,
 * neither → subtitle omitted. The string resources carry the separator so
 * locales can customize it (English uses `@`, Korean uses `·`).
 *
 * Spec: `.spec/contracts/ui-map.yml:106-111 § PersonDetail.components § PersonHeader`,
 * `.spec/person-enrichment.spec.yml:57-63 § ENR-006` (fallback).
 */
@Composable
internal fun PersonHeader(
    displayName: String?,
    nickname: String?,
    companyName: String?,
    jobTitle: String?,
    personRef: String,
    eventCount: Int = 0,
    emailInteractionCount: Int = 0,
    callInteractionCount: Int = 0,
    meetingCount: Int = 0,
    pendingCommitmentCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val nameLine = personDisplayLabel(
        personRef = personRef,
        displayName = displayName,
        nickname = nickname,
    )
    val subtitle = composeSubtitle(jobTitle = jobTitle, companyName = companyName)
    val metaLine = composeMetaLine(
        nickname = nickname,
        eventCount = eventCount,
        pendingCommitmentCount = pendingCommitmentCount,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .glassPanel(MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
