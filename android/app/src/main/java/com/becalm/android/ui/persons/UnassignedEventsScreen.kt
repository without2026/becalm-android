package com.becalm.android.ui.persons

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Unassigned events screen — raw events where `person_ref IS NULL`.
 *
 * Reuses [PersonsViewModel] which provides the enrichment list. Unassigned events
 * are events without a resolved person_ref — currently shown as an empty state
 * until a dedicated DAO query (SRC-008 extension) is available.
 *
 * spec: SRC-008
 *
 * Primary VM: [PersonsViewModel]
 * Navigation entry: [BecalmRoute.PersonsUnassigned]
 * Navigation exit: back to [BecalmRoute.Persons]
 */
@Composable
public fun UnassignedEventsScreen(
    navController: NavHostController,
    viewModel: PersonsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    BecalmScaffold(
        title = stringResource(R.string.persons_unassigned_title),
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // Unassigned events have personRef with no display name match.
                // Filter those without a displayName as a proxy until SRC-008 DAO is available.
                val unassigned = state.people.filter { it.displayName == null }
                if (unassigned.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.persons_unassigned_empty_title),
                        message = stringResource(R.string.persons_unassigned_empty_message),
                        icon = Icons.Filled.List,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        items(items = unassigned, key = { it.personRef }) { person ->
                            Text(
                                text = stringResource(R.string.persons_unidentified),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .glassPanel(MaterialTheme.shapes.medium)
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewUnassignedEventsEmpty() {
    BecalmTheme {
        BecalmScaffold(
            title = "Unassigned Events",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            EmptyState(
                title = "No unassigned events",
                message = "All events have been matched to a person.",
                icon = Icons.Filled.List,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
