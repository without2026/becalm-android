package com.becalm.android.ui.onboarding

import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.Flow

/**
 * Onboarding step: IMAP provider selector (Naver / Daum) with app-password save (S6-H).
 *
 * Replaces the pre-S6-H freeform host form with a Material3
 * [SingleChoiceSegmentedButtonRow] that binds to [ImapProvider] presets. The username
 * placeholder swaps as the segment changes; the password field stays credential-only.
 * `[연결]` dispatches through [OnboardingViewModel.saveImapCredentials], which writes
 * to [com.becalm.android.data.local.secure.ImapCredentialStore] under the provider's
 * [com.becalm.android.data.remote.dto.SourceType] namespace. Failures surface as a
 * Snackbar and leave the user on-screen so they can retry without losing input.
 *
 * PIPA compliance: credentials screen — [PasswordVisualTransformation] masks input
 * and [WindowManager.LayoutParams.FLAG_SECURE] blocks screenshots (PIPA Article 29).
 *
 * spec: ONB-001, SMG-001, ING-011
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingImap]
 * Navigation exit: [BecalmRoute.OnboardingGoogleCalendar]
 */
@Composable
public fun ImapSetupScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    emailConnectEventsOverride: Flow<EmailConnectEvent>? = null,
    onSaveCredentials: ((ImapProvider, String, String) -> Unit)? = null,
    onSkipStep: (() -> Unit)? = null,
    onNavigateToGoogleCalendar: (() -> Unit)? = null,
) {
    val resolvedViewModel = if (
        emailConnectEventsOverride == null || onSaveCredentials == null || onSkipStep == null
    ) {
        viewModel ?: hiltViewModel()
    } else {
        viewModel
    }
    val navigateToGoogleCalendar = onNavigateToGoogleCalendar ?: {
        navController.navigate(BecalmRoute.OnboardingGoogleCalendar.path)
    }
    val saveCredentials = onSaveCredentials ?: { provider: ImapProvider, username: String, appPassword: String ->
        requireNotNull(resolvedViewModel).saveImapCredentials(
            sourceType = provider.sourceType,
            credentials = ImapCredentials(
                host = provider.host,
                port = provider.port,
                username = username,
                appPassword = appPassword,
            ),
        )
    }
    val skipStep = onSkipStep ?: {
        requireNotNull(resolvedViewModel).onSkipStep(OnboardingStep.LINK_IMAP)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE: credentials screen — prevent screenshot capture (PIPA Article 29).
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val errorCopyByCode = imapErrorStringMap(
        network = stringResource(R.string.onb_imap_error_network),
        unknown = stringResource(R.string.onb_imap_error_save_failed),
    )

    LaunchedEffect(viewModel) {
        (emailConnectEventsOverride ?: requireNotNull(resolvedViewModel).emailConnectEvents)
            .filter { it.provider in EmailPipaProvider.IMAP_GROUP }
            .collect { event ->
                when (event) {
                    is EmailConnectEvent.Connected -> navigateToGoogleCalendar()
                    is EmailConnectEvent.PendingIntentRequired -> Unit // IMAP never emits this.
                    is EmailConnectEvent.Failed -> snackbarHostState.showSnackbar(
                        errorCopyByCode[event.errorCode] ?: errorCopyByCode.getValue("unknown"),
                    )
                }
            }
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_imap_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ImapForm(
            modifier = Modifier.padding(padding),
            onSave = { provider, username, appPassword ->
                saveCredentials(provider, username, appPassword)
            },
            onSkip = {
                skipStep()
                navigateToGoogleCalendar()
            },
        )
    }
}

@Composable
internal fun ImapForm(
    modifier: Modifier = Modifier,
    onSave: (ImapProvider, username: String, appPassword: String) -> Unit,
    onSkip: () -> Unit,
) {
    var selectedProvider: ImapProvider by remember { mutableStateOf(ImapProvider.Naver) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSave by remember {
        derivedStateOf { username.isNotBlank() && password.isNotBlank() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onb_imap_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onb_imap_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        val providers = listOf(ImapProvider.Naver, ImapProvider.Daum)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            providers.forEachIndexed { index, provider ->
                val displayNameRes = provider.displayNameRes
                SegmentedButton(
                    selected = provider == selectedProvider,
                    onClick = { selectedProvider = provider },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = providers.size,
                    ),
                    modifier = Modifier.testTag(
                        when (provider) {
                            ImapProvider.Naver -> "imap-provider-naver"
                            ImapProvider.Daum -> "imap-provider-daum"
                        },
                    ),
                    label = { Text(stringResource(displayNameRes)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        BecalmTextField(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.onb_imap_email_label),
            placeholder = stringResource(selectedProvider.usernamePlaceholderRes),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("imap-username"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.onb_imap_password_label),
            placeholder = stringResource(R.string.onb_imap_password_placeholder),
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("imap-password"),
        )
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.onb_imap_cta),
            onClick = { onSave(selectedProvider, username, password) },
            variant = BecalmButtonVariant.Primary,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            variant = BecalmButtonVariant.Text,
            modifier = Modifier.testTag("imap-skip"),
        )
    }
}

/**
 * Maps the stable IMAP save-error codes emitted by [OnboardingViewModel] onto localised
 * Snackbar copy. Kept outside the composable so both production and preview paths share
 * the same defaults.
 */
internal fun imapErrorStringMap(
    network: String,
    unknown: String,
): Map<String, String> = mapOf(
    "network" to network,
    "save_failed" to unknown,
    "unknown_provider" to unknown,
    "unknown" to unknown,
)

@PreviewLightDark
@Composable
private fun PreviewImapSetupScreen() {
    BecalmTheme {
        ImapSetupScreen(navController = rememberNavController())
    }
}
