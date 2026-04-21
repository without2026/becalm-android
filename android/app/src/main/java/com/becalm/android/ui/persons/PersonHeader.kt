package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R

/**
 * Top header composable for [PersonDetailScreen] — renders the person's display
 * name plus an optional role/company subtitle.
 *
 * Respects ENR-006 fallback: when [displayName] is null / blank the raw
 * [personRef] (email / phone / display string) is shown verbatim so a contact
 * still has a human handle before enrichment has run.
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
    companyName: String?,
    jobTitle: String?,
    personRef: String,
    modifier: Modifier = Modifier,
) {
    val nameLine = displayName?.takeIf { it.isNotBlank() } ?: personRef
    val subtitle = composeSubtitle(jobTitle = jobTitle, companyName = companyName)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
