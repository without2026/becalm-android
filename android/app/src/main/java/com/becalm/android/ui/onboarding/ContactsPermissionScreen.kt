package com.becalm.android.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.coroutines.flow.Flow

/**
 * Onboarding step: READ_CONTACTS permission with PIPA notice.
 *
 * Graceful skip — refusal does not block functionality (ENR-001).
 * PIPA disclosure is shown inline before the system permission dialog launches.
 *
 * spec: ONB-003, ENR-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingContacts]
 * Navigation exit: compatibility [BecalmRoute.OnboardingSources]. The first-run setup
 * screen requests contacts inline instead of navigating here.
 */
@Composable
public fun ContactsPermissionScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    effectsOverride: Flow<ContactsPermissionEffect>? = null,
    onGrant: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onLaunchSystemPermission: (() -> Unit)? = null,
    onNavigateToSources: (() -> Unit)? = null,
) {
    val onboardingViewModel = if (
        effectsOverride == null || onGrant == null || onSkip == null || onLaunchSystemPermission == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requireNotNull(onboardingViewModel).onContactsPermissionResult(granted)
    }
    val requestSystemPermission = onLaunchSystemPermission ?: {
        launcher.launch(Manifest.permission.READ_CONTACTS)
    }
    val navigateToSources = onNavigateToSources ?: {
        navController.navigate(BecalmRoute.OnboardingSources.path)
    }

    LaunchedEffect(effectsOverride, onboardingViewModel) {
        (effectsOverride ?: requireNotNull(onboardingViewModel).contactsPermissionEffects).collect { effect ->
            when (effect) {
                ContactsPermissionEffect.RequestSystemPermission -> requestSystemPermission()
                ContactsPermissionEffect.NavigateToSources -> navigateToSources()
            }
        }
    }

    BecalmScaffold(title = stringResource(R.string.onb_contacts_title)) { padding ->
        ContactsPermissionContent(
            onGrant = onGrant ?: requireNotNull(onboardingViewModel)::onAllowContacts,
            onSkip = onSkip ?: requireNotNull(onboardingViewModel)::onSkipContacts,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun ContactsPermissionContent(
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onb_contacts_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onb_contacts_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onb_contacts_pipa),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.action_grant),
            onClick = onGrant,
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            variant = BecalmButtonVariant.Text,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewContactsPermissionScreen() {
    BecalmTheme {
        ContactsPermissionContent(
            onGrant = {},
            onSkip = {},
        )
    }
}
