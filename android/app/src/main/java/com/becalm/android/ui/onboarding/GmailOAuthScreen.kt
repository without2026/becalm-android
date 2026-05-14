package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Onboarding step: Gmail OAuth connection (S6-F).
 *
 * Replaces the pre-S6-F placeholder — the `[연결]` CTA now drives
 * [OnboardingViewModel.onConnectEmailProvider] which wraps
 * backend-managed Gmail OAuth via [EmailOAuthConnector].
 *
 * Result handling:
 * - [EmailConnectEvent.Connected] — navigate forward to the next PIPA disclosure.
 * - [EmailConnectEvent.PendingIntentRequired] — launch the returned intent via
 *   [ActivityResultContracts.StartIntentSenderForResult] and re-call the VM so the
 *   AuthorizationClient second pass can claim the token after the user grants consent.
 * - [EmailConnectEvent.Failed] — show a localised Snackbar; the VM has already
 *   marked [OnboardingStep.LINK_GMAIL] [StepStatus.SKIPPED] + emitted the
 *   `onboarding_step_failed` observability event so the
 *   terminal gate still accepts the flow.
 *
 * spec: AUTH-002, ONB-004, ONB-007, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingGmail]
 * Navigation exit:  [BecalmRoute.OnboardingEmailPipa] (outlook_mail slug).
 */
@Composable
public fun GmailOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    eventsOverride: Flow<EmailConnectEvent>? = null,
    onConnect: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onNavigateDownstream: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    val activity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingOAuthResumeRefresh by remember { mutableStateOf(false) }
    val downstream = BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.OUTLOOK_MAIL.storageKey).path
    val onboardingViewModel = if (eventsOverride == null || onConnect == null || onSkip == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val navigateDownstream = onNavigateDownstream ?: { navController.navigateAfterSourceReconnectOr(downstream) }

    val errorCopyByCode = oauthErrorStringMap(
        network = stringResource(R.string.onb_gmail_error_network),
        permission = stringResource(R.string.onb_gmail_error_permission_denied),
        unknown = stringResource(R.string.onb_gmail_error_unknown),
    )

    val pendingIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // AuthorizationClient's first-run flow completes asynchronously — re-run the
        // sign-in so the provider observes the now-granted scope. A cancelled dialog
        // surfaces as USER_CANCELLED on the second call, which the VM handles.
        activity?.let { requireNotNull(onboardingViewModel).onConnectEmailProvider(EmailPipaProvider.GMAIL, it) }
    }
    val launchPendingIntent = onLaunchPendingIntent ?: { request -> pendingIntentLauncher.launch(request) }

    DisposableEffect(lifecycleOwner, onboardingViewModel, eventsOverride, pendingOAuthResumeRefresh) {
        if (!pendingOAuthResumeRefresh || eventsOverride != null || onboardingViewModel == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    onboardingViewModel.refreshEmailProviderConnection(EmailPipaProvider.GMAIL)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(eventsOverride, onboardingViewModel) {
        (eventsOverride ?: requireNotNull(onboardingViewModel).emailConnectEvents)
            .filter { it.provider == EmailPipaProvider.GMAIL }
            .collect { event ->
                when (event) {
                    is EmailConnectEvent.Connected -> {
                        pendingOAuthResumeRefresh = false
                        navigateDownstream()
                    }
                    is EmailConnectEvent.PendingIntentRequired -> {
                        launchPendingIntent(IntentSenderRequest.Builder(event.pendingIntent).build())
                    }
                    is EmailConnectEvent.Failed -> {
                        pendingOAuthResumeRefresh = false
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
        title = stringResource(R.string.onb_gmail_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        GmailOAuthContent(
            modifier = Modifier.padding(padding),
            onConnect = onConnect ?: {
                val hostActivity = activity
                if (hostActivity == null) {
                    scope.launch { snackbarHostState.showSnackbar(errorCopyByCode.getValue("unknown")) }
                } else {
                    pendingOAuthResumeRefresh = true
                    requireNotNull(onboardingViewModel).onConnectEmailProvider(EmailPipaProvider.GMAIL, hostActivity)
                }
                Unit
            },
            onSkip = onSkip ?: {
                requireNotNull(onboardingViewModel).onSkipStep(OnboardingStep.LINK_GMAIL)
                navigateDownstream()
                Unit
            },
        )
    }
}

@Composable
internal fun GmailOAuthContent(
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OAuthPlaceholderContent(
        modifier = modifier,
        headline = stringResource(R.string.onb_gmail_headline),
        body = stringResource(R.string.onb_gmail_body),
        connectLabel = stringResource(R.string.action_connect),
        onConnect = onConnect,
        onSkip = onSkip,
    )
}

@PreviewLightDark
@Composable
private fun PreviewGmailOAuthScreen() {
    BecalmTheme {
        GmailOAuthContent(
            onConnect = {},
            onSkip = {},
        )
    }
}

// ─── Shared OAuth placeholder layout ──────────────────────────────────────────

@Composable
internal fun OAuthPlaceholderContent(
    headline: String,
    body: String,
    connectLabel: String,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    connectLoading: Boolean = false,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        QuietPanel(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = connectLabel,
            onClick = onConnect,
            variant = BecalmButtonVariant.Primary,
            enabled = !connectLoading,
            loading = connectLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            variant = BecalmButtonVariant.Text,
            enabled = !connectLoading,
        )
    }
}

/**
 * Helper that maps the stable OAuth error codes emitted by [OnboardingViewModel] onto
 * localised Snackbar copy. Extracted so GmailOAuthScreen and OutlookMailOAuthScreen
 * can share the same dispatch without duplicating the switch.
 */
internal fun oauthErrorStringMap(
    network: String,
    permission: String,
    unknown: String,
): Map<String, String> = mapOf(
    "network" to network,
    "scope_denied" to permission,
    "play_services_unavailable" to unknown,
    "unknown" to unknown,
    "save_failed" to unknown,
)
