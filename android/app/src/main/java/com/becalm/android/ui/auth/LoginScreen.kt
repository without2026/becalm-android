package com.becalm.android.ui.auth

import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.OnboardingViewModel
import com.becalm.android.ui.onboarding.StepStatus
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.launch

/**
 * Email/password login screen.
 *
 * PIPA compliance note: This screen displays a password field.
 * [PasswordVisualTransformation] is applied to mask entered characters.
 * [WindowManager.LayoutParams.FLAG_SECURE] is set on the host Activity window
 * while this screen is visible to prevent password capture via screenshot or
 * screen recorder, per PIPA Article 29.
 *
 * No `Log.*` calls reference user-entered text (email/password).
 *
 * spec: AUTH-001 (email sign-in), AUTH-002 (Google sign-in placeholder)
 *
 * Primary VM: [AuthViewModel]
 * Navigation entry: [BecalmRoute.Login]
 * Navigation exit: [BecalmRoute.OnboardingPipaConsent] (new user) | [BecalmRoute.Today] (existing session via VM)
 */
@Composable
public fun LoginScreen(
    navController: NavHostController,
    viewModel: AuthViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // FLAG_SECURE: prevent screenshot capture of password screen (PIPA Article 29)
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Navigate away when session is established
    LaunchedEffect(state) {
        if (state is AuthUiState.SignedIn) {
            val signedIn = state as AuthUiState.SignedIn
            // Mark the onboarding LOGIN step terminal before leaving this screen so the
            // onCompleteOnboarding() gate can ever pass (LOGIN is step 2 of the canonical 12).
            onboardingViewModel.onMarkStepStatus(OnboardingStep.LOGIN, StepStatus.GRANTED)
            val destination = if (signedIn.onboardingCompleted) {
                BecalmRoute.Today.path
            } else {
                // ONB-PIPA: first post-login screen must be the PIPA 제3자 제공 consent screen
                // (finding #3 fix). RecordingFolder is reached only after the user acts on
                // the PIPA disclosure — the OnboardingPipaConsent composable handles that hop.
                BecalmRoute.OnboardingPipaConsent.path
            }
            navController.navigate(destination) {
                popUpTo(BecalmRoute.Login.path) { inclusive = true }
            }
        }
        if (state is AuthUiState.Error) {
            scope.launch {
                snackbarHostState.showSnackbar((state as AuthUiState.Error).message)
                viewModel.onErrorDismissed()
            }
        }
    }

    // Google sign-in: CredentialManager + GetGoogleIdOption issues the OIDC id-token
    // that Supabase expects at signInWithGoogleIdToken. Gmail API scopes (gmail.readonly)
    // are acquired separately in S6-F through AuthorizationClient — the two APIs coexist.
    val googleErrorUnknown = stringResource(R.string.login_google_error_unknown)
    val googleErrorNoCreds = stringResource(R.string.login_google_error_no_credentials)
    val googleLauncher = rememberGoogleSignInLauncher(
        onResult = { result ->
            when (result) {
                is GoogleSignInResult.Success -> viewModel.onGoogleSignIn(result.idToken)
                is GoogleSignInResult.UserCancelled -> Unit // User dismissed the picker — silent.
                is GoogleSignInResult.NoCredentials -> scope.launch {
                    snackbarHostState.showSnackbar(googleErrorNoCreds)
                }
                is GoogleSignInResult.Error -> scope.launch {
                    snackbarHostState.showSnackbar(googleErrorUnknown)
                }
            }
        },
    )

    BecalmScaffold(
        title = stringResource(R.string.login_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LoginForm(
            modifier = Modifier.padding(padding),
            isLoading = state is AuthUiState.Loading,
            googleSignInEnabled = googleLauncher.isConfigured,
            onSignIn = { email, password -> viewModel.onEmailSignIn(email, password) },
            onGoogleSignIn = { googleLauncher.launch() },
        )
    }
}

@Composable
private fun LoginForm(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    googleSignInEnabled: Boolean,
    onSignIn: (String, String) -> Unit,
    onGoogleSignIn: () -> Unit,
) {
    // Local UI state only — no PII stored in remembered state
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showEmptyError by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BecalmTextField(
            value = email,
            onValueChange = { email = it; showEmptyError = false },
            label = stringResource(R.string.login_email_label),
            placeholder = stringResource(R.string.login_email_placeholder),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        BecalmTextField(
            value = password,
            onValueChange = { password = it; showEmptyError = false },
            label = stringResource(R.string.login_password_label),
            placeholder = stringResource(R.string.login_password_placeholder),
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            visualTransformation = PasswordVisualTransformation(),
            isError = showEmptyError && password.isBlank(),
            supportingText = if (showEmptyError && password.isBlank()) {
                stringResource(R.string.login_error_empty_fields)
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.login_cta),
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    showEmptyError = true
                } else {
                    onSignIn(email, password)
                }
            },
            variant = BecalmButtonVariant.Primary,
            loading = isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        BecalmButton(
            text = stringResource(R.string.login_google_cta),
            onClick = onGoogleSignIn,
            variant = BecalmButtonVariant.Secondary,
            enabled = googleSignInEnabled && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewLoginScreen() {
    BecalmTheme {
        // Preview uses static form only — no ViewModel wired
        BecalmScaffold(title = "Sign In") { padding ->
            LoginForm(
                modifier = Modifier.padding(padding),
                isLoading = false,
                googleSignInEnabled = true,
                onSignIn = { _, _ -> },
                onGoogleSignIn = {},
            )
        }
    }
}
