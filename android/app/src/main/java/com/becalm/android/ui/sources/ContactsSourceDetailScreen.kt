package com.becalm.android.ui.sources

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchContactsSourceDetailEffect
import com.becalm.android.ui.theme.glassPanel

/**
 * Settings detail screen for the contacts pseudo-source.
 */
@Composable
public fun ContactsSourceDetailScreen(
    navController: NavHostController,
    viewModel: ContactsSourceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    CollectFlowEffect(viewModel.effects) { effect ->
        dispatchContactsSourceDetailEffect(effect, navController, context)
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_contacts_title),
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        ContactsSourceDetailContent(
            state = state,
            onPermissionAction = viewModel::onPermissionAction,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun ContactsSourceDetailContent(
    state: ContactsSourceDetailUiState,
    onPermissionAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onb_contacts_headline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(
                R.string.contacts_source_connection_state_fmt,
                state.connectionState,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.lastSyncAt?.let { at ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.source_detail_last_sync_fmt,
                    at.toString().substringAfter("T").take(5),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.enrichedCount?.let { count ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.contacts_source_enriched_count_fmt, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onb_contacts_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onb_contacts_pipa),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        BecalmButton(
            text = if (state.showPermissionRevokeButton) {
                stringResource(R.string.action_revoke_permission)
            } else {
                stringResource(R.string.action_grant)
            },
            onClick = onPermissionAction,
            variant = if (state.showPermissionRevokeButton) {
                BecalmButtonVariant.Text
            } else {
                BecalmButtonVariant.Secondary
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
