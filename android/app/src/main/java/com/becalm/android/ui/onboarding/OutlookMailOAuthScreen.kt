package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Onboarding step: Outlook Mail OAuth connection (S6-G).
 *
 * Mirrors the Gmail launcher pattern; the underlying provider
 * ([com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl]) drives MSAL's
 * `acquireTokenInteractive(Mail.Read, offline_access)` behind
 * [OnboardingViewModel.onConnectEmailProvider]. No PendingIntent is expected — MSAL
 * launches its own browser tab — but the screen still handles the branch defensively
 * so a future provider change doesn't silently regress the consent flow.
 *
 * spec: AUTH-002, ONB-004, ONB-007, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingOutlookMail]
 * Navigation exit:  [BecalmRoute.OnboardingEmailPipa] (imap slug).
 */
@Composable
public fun OutlookMailOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    eventsOverride: Flow<EmailConnectEvent>? = null,
    onConnect: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onNavigateDownstream: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    val activity = LocalContext.current as? Activity
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val downstream = BecalmRoute.OnboardingEmailPipa("imap").path
    val onboardingViewModel = if (eventsOverride == null || onConnect == null || onSkip == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val navigateDownstream = onNavigateDownstream ?: { navController.navigate(downstream) }

    val errorCopyByCode = oauthErrorStringMap(
        network = stringResource(R.string.onb_outlook_error_network),
        permission = stringResource(R.string.onb_outlook_error_permission_denied),
        unknown = stringResource(R.string.onb_outlook_error_unknown),
    )

    val pendingIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        activity?.let { requireNotNull(onboardingViewModel).onConnectEmailProvider(EmailPipaProvider.OUTLOOK_MAIL, it) }
    }
    val launchPendingIntent = onLaunchPendingIntent ?: { request -> pendingIntentLauncher.launch(request) }

    LaunchedEffect(eventsOverride, onboardingViewModel) {
        (eventsOverride ?: requireNotNull(onboardingViewModel).emailConnectEvents)
            .filter { it.provider == EmailPipaProvider.OUTLOOK_MAIL }
            .collect { event ->
                when (event) {
                    is EmailConnectEvent.Connected -> navigateDownstream()
                    is EmailConnectEvent.PendingIntentRequired -> {
                        launchPendingIntent(IntentSenderRequest.Builder(event.pendingIntent).build())
                    }
                    is EmailConnectEvent.Failed -> {
                        if (event.errorCode != "user_cancelled") {
                            snackbarHostState.showSnackbar(
                                errorCopyByCode[event.errorCode] ?: errorCopyByCode.getValue("unknown"),
                            )
                        }
                    }
                }
            }
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_outlook_mail_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        OutlookMailOAuthContent(
            modifier = Modifier.padding(padding),
            onConnect = onConnect ?: {
                val hostActivity = activity
                if (hostActivity == null) {
                    scope.launch { snackbarHostState.showSnackbar(errorCopyByCode.getValue("unknown")) }
                } else {
                    requireNotNull(onboardingViewModel).onConnectEmailProvider(EmailPipaProvider.OUTLOOK_MAIL, hostActivity)
                }
                Unit
            },
            onSkip = onSkip ?: {
                requireNotNull(onboardingViewModel).onSkipStep(OnboardingStep.LINK_OUTLOOK_MAIL)
                navigateDownstream()
                Unit
            },
        )
    }
}

@Composable
internal fun OutlookMailOAuthContent(
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OAuthPlaceholderContent(
        modifier = modifier,
        headline = stringResource(R.string.onb_outlook_mail_headline),
        body = stringResource(R.string.onb_outlook_mail_body),
        connectLabel = stringResource(R.string.action_connect),
        onConnect = onConnect,
        onSkip = onSkip,
    )
}

@PreviewLightDark
@Composable
private fun PreviewOutlookMailOAuthScreen() {
    BecalmTheme {
        OutlookMailOAuthContent(
            onConnect = {},
            onSkip = {},
        )
    }
}
