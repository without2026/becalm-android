package com.becalm.android.ui.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Persons list screen — enriched display names and interaction counts.
 *
 * spec: SRC-001, SRC-002
 *
 * Primary VM: [PersonsViewModel]
 * Navigation entry: [BecalmRoute.Persons]
 * Navigation exit: [BecalmRoute.PersonDetail] on person tap
 */
@Composable
public fun PersonsScreen(
    navController: NavHostController,
    viewModel: PersonsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSnackbarMessage(state.error, snackbarHostState, viewModel::onErrorDismissed)

    PersonsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onPersonClick = { personRef ->
            navController.navigate(BecalmRoute.PersonDetail(personRef).path)
        },
    )
}

@Composable
public fun PersonsScreenContent(
    state: PersonsUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.persons_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.showOfflineBadge) {
                OfflineBadge(lastSyncAt = state.offlineLastSyncAt)
            }
            BecalmTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.persons_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.people.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.persons_empty_title),
                        message = stringResource(R.string.persons_empty_message),
                        icon = Icons.Filled.Person,
                    )
                }
                else -> {
                    PersonList(
                        state = state,
                        onPersonClick = onPersonClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonList(
    state: PersonsUiState,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("persons-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.personSections.forEach { section ->
            personSection(
                key = section.kind.key,
                titleRes = section.kind.titleRes,
                people = section.people,
                onPersonClick = onPersonClick,
            )
        }
        if (state.unassignedEvents.isNotEmpty()) {
            item(key = "unassigned-spacer") {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        if (state.unassignedEvents.isNotEmpty()) {
            item(key = "unassigned-header") {
                PersonListSectionHeader(text = stringResource(R.string.persons_unassigned_title))
            }
            items(items = state.unassignedEvents, key = { it.id }) { event ->
                UnassignedEventRow(
                    event = event,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

private val PersonSectionKind.key: String
    get() = when (this) {
        PersonSectionKind.PENDING_COMMITMENTS -> "pending-people"
        PersonSectionKind.RECENT_CONTACTS -> "recent-people"
    }

private val PersonSectionKind.titleRes: Int
    get() = when (this) {
        PersonSectionKind.PENDING_COMMITMENTS -> R.string.persons_section_pending_commitments
        PersonSectionKind.RECENT_CONTACTS -> R.string.persons_section_recent_contacts
    }

private fun androidx.compose.foundation.lazy.LazyListScope.personSection(
    key: String,
    titleRes: Int,
    people: List<PersonRow>,
    onPersonClick: (String) -> Unit,
) {
    if (people.isEmpty()) return
    item(key = "$key-header") {
        PersonListSectionHeader(text = stringResource(titleRes))
    }
    items(items = people, key = { "$key-${it.personRef}" }) { person ->
        PersonRowItem(
            person = person,
            onClick = { onPersonClick(person.personRef) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun PersonListSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun PersonRowItem(
    person: PersonRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(person = person)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildDisplayHeadline(person),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.pendingCommitmentCount > 0) {
                PendingCommitmentBadge(count = person.pendingCommitmentCount)
            }
            if (!person.lastInteractionSnippet.isNullOrBlank()) {
                Text(
                    text = person.lastInteractionSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (person.interactionCount > 0) {
                Text(
                    text = stringResource(R.string.persons_interactions_count, person.interactionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PersonAvatar(person: PersonRow) {
    val seed = person.displayLabel.ifBlank { person.personRef }
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val index = (seed.hashCode() and Int.MAX_VALUE) % colors.size
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(colors[index])
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(seed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PendingCommitmentBadge(count: Int) {
    Text(
        text = stringResource(R.string.persons_pending_commitments_fmt, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun UnassignedEventRow(
    event: UnassignedEventSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title ?: stringResource(R.string.raw_event_detail_no_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = event.sourceType,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineBadge(lastSyncAt: Instant?) {
    val copy = if (lastSyncAt == null) {
        stringResource(R.string.persons_offline_badge_no_sync)
    } else {
        stringResource(R.string.persons_offline_badge_fmt, lastSyncAt.toHourMinuteLabel())
    }
    Text(
        text = copy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun avatarInitial(seed: String): String =
    seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

private fun buildDisplayHeadline(person: PersonRow): String {
    val parts = buildList {
        add(person.displayLabel)
        person.companyName?.takeIf { it.isNotBlank() }?.let(::add)
        person.jobTitle?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.joinToString(separator = " · ")
}

private fun Instant.toHourMinuteLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

@PreviewLightDark
@Composable
private fun PreviewPersonsScreenPopulated() {
    BecalmTheme {
        BecalmScaffold(title = "People") { padding ->
            Column(modifier = Modifier.padding(padding)) {
                BecalmTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = "Search people…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(
                        listOf(
                            PersonRow(
                                personRef = "ref1",
                                displayName = "Alice Kim",
                                lastInteractionAt = null,
                                interactionCount = 12,
                            ),
                            PersonRow(
                                personRef = "ref2",
                                displayName = "Bob Lee",
                                lastInteractionAt = null,
                                interactionCount = 5,
                            ),
                            PersonRow(
                                personRef = "ref3",
                                displayName = "Carol Park",
                                lastInteractionAt = null,
                                interactionCount = 3,
                            ),
                        ),
                    ) { person ->
                        PersonRowItem(
                            person = person,
                            onClick = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
