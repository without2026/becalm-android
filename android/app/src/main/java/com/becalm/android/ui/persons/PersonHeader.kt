package com.becalm.android.ui.persons

import androidx.compose.foundation.background
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
import com.becalm.android.core.util.KST
import com.becalm.android.domain.person.PersonIdentityResolver
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

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
    relationshipStartedAt: Instant? = null,
    lastInteractionAt: Instant? = null,
) {
    val nameLine = listOf(displayName, nickname)
        .firstOrNull { isDisplayNameValue(it) }
        ?: stringResource(R.string.persons_unidentified)
    val subtitle = composeSubtitle(jobTitle = jobTitle, companyName = companyName)
    val metaLine = composeRelationshipMetaLine(
        relationshipStartedAt = relationshipStartedAt,
        lastInteractionAt = lastInteractionAt,
        eventCount = eventCount,
        pendingCommitmentCount = pendingCommitmentCount,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderAvatar(seed = nameLine)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics { heading() },
                )
                if (subtitle != null) {
                    Text(
                        text = " · $subtitle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (metaLine != null) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
private fun composeRelationshipMetaLine(
    relationshipStartedAt: Instant?,
    lastInteractionAt: Instant?,
    eventCount: Int,
    pendingCommitmentCount: Int,
): String? {
    val now = Clock.System.now()
    val parts = buildList {
        relationshipStartedAt?.let {
            val days = it.toLocalDateTime(KST).date
                .daysUntil(now.toLocalDateTime(KST).date)
                .coerceAtLeast(0) + 1
            add(stringResource(R.string.person_header_relationship_started_days_fmt, days))
        }
        if (eventCount > 0) add(stringResource(R.string.person_header_interaction_count_fmt, eventCount))
        lastInteractionAt?.let {
            add(stringResource(R.string.person_header_last_interaction_fmt, it.shortMonthDay()))
        }
        if (pendingCommitmentCount > 0) {
            add(stringResource(R.string.person_header_pending_count_fmt, pendingCommitmentCount))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun Instant.shortMonthDay(): String {
    val date = toLocalDateTime(KST).date
    return "${date.monthNumber}/${date.dayOfMonth}"
}
