package com.becalm.android.ui.onboarding

import android.view.WindowManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step: IMAP / App Password manual email setup.
 *
 * PIPA compliance note: This screen contains a password field.
 * [PasswordVisualTransformation] is applied to mask credentials.
 * [WindowManager.LayoutParams.FLAG_SECURE] is set on the Activity window while
 * this screen is visible, per PIPA Article 29.
 *
 * No `Log.*` calls capture user-entered credentials.
 *
 * spec: ONB-001, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingImap]
 * Navigation exit: [BecalmRoute.OnboardingGoogleCalendar]
 */
@Composable
public fun ImapSetupScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE: credentials screen — prevent screenshot capture (PIPA Article 29)
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_imap_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ImapForm(
            modifier = Modifier.padding(padding),
            onSave = {
                // TODO(BECALM-IMAP-001): persist credentials via ImapCredentialStore — deferred to next sprint
                viewModel.onMarkStepStatus(OnboardingStep.LINK_IMAP, StepStatus.COMPLETE)
                navController.navigate(BecalmRoute.OnboardingGoogleCalendar.path)
            },
            onSkip = {
                viewModel.onSkipStep()
                navController.navigate(BecalmRoute.OnboardingGoogleCalendar.path)
            },
        )
    }
}

@Composable
private fun ImapForm(
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
        Spacer(modifier = Modifier.height(24.dp))
        BecalmTextField(
            value = host,
            onValueChange = { host = it },
            label = stringResource(R.string.onb_imap_host_label),
            placeholder = stringResource(R.string.onb_imap_host_placeholder),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(R.string.onb_imap_email_label),
            placeholder = stringResource(R.string.onb_imap_email_placeholder),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.onb_imap_cta),
            onClick = onSave,
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
private fun PreviewImapSetupScreen() {
    BecalmTheme {
        BecalmScaffold(title = "IMAP") { padding ->
            ImapForm(
                modifier = Modifier.padding(padding),
                onSave = {},
                onSkip = {},
            )
        }
    }
}
