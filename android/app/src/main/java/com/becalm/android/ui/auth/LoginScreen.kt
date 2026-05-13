package com.becalm.android.ui.auth

import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.GoogleSignInButton
import com.becalm.android.ui.components.uiMessageStringResource
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
 * spec: AUTH-001 (email sign-in), AUTH-002 (Google sign-in)
 *
 * Primary VM: [AuthViewModel]
 * Navigation entry: [BecalmRoute.Login]
 * Navigation exit: [BecalmRoute.OnboardingSetup] (new user) | [BecalmRoute.Today] (existing session via VM)
 */
@Composable
public fun LoginScreen(
    navController: NavHostController,
    viewModel: AuthViewModel? = null,
    onboardingViewModel: OnboardingViewModel? = null,
    stateOverride: AuthUiState? = null,
    onEmailSignIn: ((String, String) -> Unit)? = null,
    onEmailSignUp: ((String, String) -> Unit)? = null,
    googleSignInEnabledOverride: Boolean? = null,
    onGoogleSignInLaunch: (() -> Unit)? = null,
    onSignedInNavigate: ((String) -> Unit)? = null,
    onGoogleIdToken: ((String) -> Unit)? = null,
    onErrorDismissed: (() -> Unit)? = null,
    onMarkLoginGranted: (() -> Unit)? = null,
    applySecureFlag: Boolean = true,
) {
    val needsAuthViewModel = stateOverride == null ||
        onEmailSignIn == null ||
        onEmailSignUp == null ||
        onGoogleIdToken == null ||
        onErrorDismissed == null ||
        (onGoogleSignInLaunch == null && googleSignInEnabledOverride == null)
    val authViewModel = if (needsAuthViewModel) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<AuthViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(authViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // FLAG_SECURE: prevent screenshot capture of password screen (PIPA Article 29)
    val context = LocalContext.current
    DisposableEffect(applySecureFlag) {
        if (!applySecureFlag) return@DisposableEffect onDispose {}
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Navigate away when session is established. The onboarding VM is resolved only
    // after SignedIn so the public Login shell can render pre-auth without runtime owners.
    val signedInState = state as? AuthUiState.SignedIn
    val authErrorMessage = (state as? AuthUiState.Error)?.message?.let { uiMessageStringResource(it) }
    if (signedInState != null) {
        LoginSignedInNavigationEffect(
            signedIn = signedInState,
            navController = navController,
            onboardingViewModel = onboardingViewModel,
            onMarkLoginGranted = onMarkLoginGranted,
            onSignedInNavigate = onSignedInNavigate,
        )
    }

    LaunchedEffect(state) {
        if (state is AuthUiState.Error) {
            scope.launch {
                snackbarHostState.showSnackbar(requireNotNull(authErrorMessage))
                onErrorDismissed?.invoke() ?: requireNotNull(authViewModel).onErrorDismissed()
            }
        }
    }

    // Google sign-in: CredentialManager + GetGoogleIdOption issues the OIDC id-token
    // that Supabase expects at signInWithGoogleIdToken. Gmail API scopes (gmail.readonly)
    // are acquired separately in S6-F through AuthorizationClient — the two APIs coexist.
    val googleErrorUnknown = stringResource(R.string.login_google_error_unknown)
    val googleErrorNoCreds = stringResource(R.string.login_google_error_no_credentials)
    val googleLauncher = if (onGoogleSignInLaunch == null || googleSignInEnabledOverride == null) {
        rememberGoogleSignInLauncher(
            onResult = { result ->
                when (result) {
                    is GoogleSignInResult.Success ->
                        onGoogleIdToken?.invoke(result.idToken)
                            ?: requireNotNull(authViewModel).onGoogleSignIn(result.idToken)
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
    } else {
        null
    }
    val googleEnabled = googleSignInEnabledOverride ?: requireNotNull(googleLauncher).isConfigured
    val launchGoogleSignIn = onGoogleSignInLaunch ?: { requireNotNull(googleLauncher).launch() }

    BecalmScaffold(
        title = stringResource(R.string.login_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LoginForm(
            modifier = Modifier.padding(padding),
            isLoading = state is AuthUiState.Loading,
            googleSignInEnabled = googleEnabled,
            onSignIn = { email, password ->
                onEmailSignIn?.invoke(email, password)
                    ?: requireNotNull(authViewModel).onEmailSignIn(email, password)
            },
            onSignUp = { email, password ->
                onEmailSignUp?.invoke(email, password)
                    ?: requireNotNull(authViewModel).onEmailSignUp(email, password)
            },
            onGoogleSignIn = launchGoogleSignIn,
        )
    }
}

@Composable
private fun LoginSignedInNavigationEffect(
    signedIn: AuthUiState.SignedIn,
    navController: NavHostController,
    onboardingViewModel: OnboardingViewModel?,
    onMarkLoginGranted: (() -> Unit)?,
    onSignedInNavigate: ((String) -> Unit)?,
) {
    val resolvedOnboardingViewModel = if (onMarkLoginGranted == null) {
        onboardingViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        onboardingViewModel
    }

    LaunchedEffect(signedIn) {
        // Mark LOGIN terminal before leaving so setup completion can trust durable step state.
        onMarkLoginGranted?.invoke() ?: requireNotNull(resolvedOnboardingViewModel)
            .onMarkStepStatus(OnboardingStep.LOGIN, StepStatus.GRANTED)
        val destination = if (signedIn.onboardingCompleted) {
            BecalmRoute.Today.path
        } else {
            BecalmRoute.OnboardingSetup.path
        }
        (onSignedInNavigate ?: { target ->
            navController.navigate(target) {
                popUpTo(BecalmRoute.Login.path) { inclusive = true }
            }
        })(destination)
    }
}

@Composable
internal fun LoginForm(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    googleSignInEnabled: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onGoogleSignIn: () -> Unit,
) {
    // Local UI state only — no PII stored in remembered state
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validationErrors by remember { mutableStateOf(emptySet<LoginInputValidationError>()) }

    // Box centres the form on tablets / foldables; the inner Column caps at
    // 480dp so email + password fields stay at a comfortable reading width
    // and never stretch edge-to-edge on wide viewports. Phones (<480dp) pass
    // through unchanged.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = LoginFormMaxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoginProviderSection(
                googleSignInEnabled = googleSignInEnabled && !isLoading,
                onGoogleSignIn = onGoogleSignIn,
            )
            LoginDivider(modifier = Modifier.padding(vertical = 20.dp))
            LoginEmailFields(
                email = email,
                password = password,
                validationErrors = validationErrors,
                onEmailChange = { email = it; validationErrors = emptySet() },
                onPasswordChange = { password = it; validationErrors = emptySet() },
            )
            Spacer(modifier = Modifier.height(24.dp))
            LoginActionButtons(
                isLoading = isLoading,
                onSignIn = {
                    val nextErrors = LoginInputValidator.validate(email, password)
                    validationErrors = nextErrors
                    if (nextErrors.isEmpty()) {
                        onSignIn(email, password)
                    }
                },
                onSignUp = {
                    val nextErrors = LoginInputValidator.validate(email, password)
                    validationErrors = nextErrors
                    if (nextErrors.isEmpty()) {
                        onSignUp(email, password)
                    }
                },
            )
        }
    }
}

@Composable
private fun LoginProviderSection(
    googleSignInEnabled: Boolean,
    onGoogleSignIn: () -> Unit,
) {
    Text(
        text = stringResource(R.string.login_framing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(20.dp))
    GoogleSignInButton(
        text = stringResource(R.string.login_google_cta),
        onClick = onGoogleSignIn,
        enabled = googleSignInEnabled,
        loading = false,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!googleSignInEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.login_google_setup_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LoginEmailFields(
    email: String,
    password: String,
    validationErrors: Set<LoginInputValidationError>,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    val emailError = validationErrors.any { it == LoginInputValidationError.EmptyFields || it == LoginInputValidationError.InvalidEmail }
    val passwordError = validationErrors.any { it == LoginInputValidationError.EmptyFields || it == LoginInputValidationError.ShortPassword }
    BecalmTextField(
        value = email,
        onValueChange = onEmailChange,
        label = stringResource(R.string.login_email_label),
        placeholder = stringResource(R.string.login_email_placeholder),
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        isError = emailError,
        supportingText = when {
            LoginInputValidationError.InvalidEmail in validationErrors -> stringResource(R.string.login_error_invalid_email)
            else -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("login-email"),
    )
    Spacer(modifier = Modifier.height(16.dp))
    BecalmTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.login_password_label),
        placeholder = stringResource(R.string.login_password_placeholder),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        visualTransformation = PasswordVisualTransformation(),
        isError = passwordError,
        supportingText = when {
            LoginInputValidationError.EmptyFields in validationErrors -> stringResource(R.string.login_error_empty_fields)
            LoginInputValidationError.ShortPassword in validationErrors -> stringResource(R.string.login_error_short_password)
            else -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("login-password"),
    )
}

@Composable
private fun LoginActionButtons(
    isLoading: Boolean,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
) {
    BecalmButton(
        text = stringResource(R.string.login_cta),
        onClick = onSignIn,
        variant = BecalmButtonVariant.Primary,
        loading = isLoading,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    BecalmButton(
        text = stringResource(R.string.login_signup_cta),
        onClick = onSignUp,
        variant = BecalmButtonVariant.Text,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LoginDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.login_email_section_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** Reading-width cap for the login form on tablets / foldables. Matches the
 *  spirit of the 600dp Today timeline cap and 480dp state-view cap; sized
 *  smaller because login fields are denser than reading content. */
private val LoginFormMaxContentWidth: androidx.compose.ui.unit.Dp = 480.dp
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
                onSignUp = { _, _ -> },
                onGoogleSignIn = {},
            )
        }
    }
}
