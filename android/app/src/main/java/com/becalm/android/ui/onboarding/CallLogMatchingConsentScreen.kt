package com.becalm.android.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Optional onboarding step for local CallLog-based call-recording person matching.
 *
 * This is intentionally separate from the voice-upload PIPA consent. The app only queries
 * CallLog when both this local matching consent and the Android READ_CALL_LOG permission
 * are granted. Decline/skip continues onboarding with filename-based fallback only.
 */
@Composable
public fun CallLogMatchingConsentScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    onGrantOverride: (() -> Unit)? = null,
    onSkipOverride: (() -> Unit)? = null,
) {
    val onboardingViewModel = if (onGrantOverride == null || onSkipOverride == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val context = LocalContext.current
    val navigateNext = { navController.navigate(BecalmRoute.OnboardingContacts.path) }
    val grant = {
        requireNotNull(onboardingViewModel).onCallLogMatchingConsentResult(true)
        navigateNext()
    }
    val skip = {
        requireNotNull(onboardingViewModel).onSkipCallLogMatching()
        navigateNext()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) grant() else {
            requireNotNull(onboardingViewModel).onCallLogMatchingConsentResult(false)
            navigateNext()
        }
    }

    BecalmScaffold(title = stringResource(R.string.onb_call_log_matching_title)) { padding ->
        CallLogMatchingConsentContent(
            onAgree = onGrantOverride ?: {
                if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    grant()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                }
            },
            onSkip = onSkipOverride ?: skip,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun CallLogMatchingConsentContent(
    onAgree: () -> Unit,
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
            text = stringResource(R.string.onb_call_log_matching_headline),
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
                text = stringResource(R.string.onb_call_log_matching_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onb_call_log_matching_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.onb_call_log_matching_agree),
            onClick = onAgree,
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
private fun PreviewCallLogMatchingConsentScreen() {
    BecalmTheme {
        CallLogMatchingConsentContent(onAgree = {}, onSkip = {})
    }
}
