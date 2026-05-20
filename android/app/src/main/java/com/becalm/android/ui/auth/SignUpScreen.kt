package com.becalm.android.ui.auth

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.OnboardingViewModel
import com.becalm.android.ui.theme.BecalmTheme

@Composable
public fun SignUpScreen(
    navController: NavHostController,
    viewModel: AuthViewModel? = null,
    onboardingViewModel: OnboardingViewModel? = null,
    stateOverride: AuthUiState? = null,
    onEmailSignUp: ((String, String) -> Unit)? = null,
    onNavigateToLogin: (() -> Unit)? = null,
    onSignedInNavigate: ((String) -> Unit)? = null,
    onMarkLoginGranted: (() -> Unit)? = null,
    applySecureFlag: Boolean = true,
) {
    val needsAuthViewModel = stateOverride == null || onEmailSignUp == null
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
    val authErrorMessage = (state as? AuthUiState.Error)?.message?.let { uiMessageStringResource(it) }
    val context = LocalContext.current
    var showFormAfterConfirmation by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(applySecureFlag) {
        if (!applySecureFlag) return@DisposableEffect onDispose {}
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val signedInState = state as? AuthUiState.SignedIn
    if (signedInState != null) {
        AuthSignedInNavigationEffect(
            signedIn = signedInState,
            navController = navController,
            onboardingViewModel = onboardingViewModel,
            onMarkLoginGranted = onMarkLoginGranted,
            onSignedInNavigate = onSignedInNavigate,
            authRouteToRemove = BecalmRoute.SignUp.path,
        )
    }

    LaunchedEffect(authErrorMessage) {
        if (!authErrorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(authErrorMessage)
        }
    }

    BecalmScaffold(
        title = stringResource(R.string.signup_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val confirmationState = state as? AuthUiState.SignUpEmailConfirmationRequired
        val navigateToLogin = onNavigateToLogin ?: {
            navController.navigate(BecalmRoute.Login.path) {
                popUpTo(BecalmRoute.SignUp.path) { inclusive = true }
                launchSingleTop = true
            }
        }
        if (confirmationState != null && !showFormAfterConfirmation) {
            SignUpEmailConfirmationContent(
                email = confirmationState.email,
                onNavigateToLogin = navigateToLogin,
                onTryDifferentEmail = { showFormAfterConfirmation = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            SignUpForm(
                modifier = Modifier.padding(padding),
                isLoading = state is AuthUiState.Loading,
                authErrorMessage = authErrorMessage,
                onSignUp = { email, password ->
                    showFormAfterConfirmation = false
                    onEmailSignUp?.invoke(email, password)
                        ?: requireNotNull(authViewModel).onEmailSignUp(email, password)
                },
                onNavigateToLogin = navigateToLogin,
            )
        }
    }
}

@Composable
internal fun SignUpEmailConfirmationContent(
    email: String,
    onNavigateToLogin: () -> Unit,
    onTryDifferentEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SignUpFormMaxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.signup_confirmation_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.signup_confirmation_body, email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(20.dp))
            BecalmButton(
                text = stringResource(R.string.signup_confirmation_login_cta),
                onClick = onNavigateToLogin,
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.signup_confirmation_retry_cta),
                onClick = onTryDifferentEmail,
                variant = BecalmButtonVariant.Text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun SignUpForm(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    authErrorMessage: String? = null,
    onSignUp: (String, String) -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var validationErrors by remember { mutableStateOf(emptySet<SignUpInputValidationError>()) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SignUpFormMaxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.signup_framing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            SignUpStatusMessages(
                isLoading = isLoading,
                authErrorMessage = authErrorMessage,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SignUpFields(
                email = email,
                password = password,
                confirmPassword = confirmPassword,
                validationErrors = validationErrors,
                enabled = !isLoading,
                onEmailChange = { email = it; validationErrors = emptySet() },
                onPasswordChange = { password = it; validationErrors = emptySet() },
                onConfirmPasswordChange = { confirmPassword = it; validationErrors = emptySet() },
            )
            Spacer(modifier = Modifier.height(20.dp))
            BecalmButton(
                text = stringResource(R.string.signup_cta),
                onClick = {
                    val nextErrors = LoginInputValidator.validateSignUp(email, password, confirmPassword)
                    validationErrors = nextErrors
                    if (nextErrors.isEmpty()) {
                        onSignUp(email.trim(), password)
                    }
                },
                variant = BecalmButtonVariant.Primary,
                loading = isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.signup_login_cta),
                onClick = onNavigateToLogin,
                variant = BecalmButtonVariant.Text,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SignUpStatusMessages(
    isLoading: Boolean,
    authErrorMessage: String?,
) {
    if (isLoading) {
        Text(
            text = stringResource(R.string.signup_loading_status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!authErrorMessage.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = authErrorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignUpFields(
    email: String,
    password: String,
    confirmPassword: String,
    validationErrors: Set<SignUpInputValidationError>,
    enabled: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
) {
    val emailError = SignUpInputValidationError.EmptyFields in validationErrors ||
        SignUpInputValidationError.InvalidEmail in validationErrors
    val passwordError = SignUpInputValidationError.EmptyFields in validationErrors ||
        SignUpInputValidationError.ShortPassword in validationErrors
    val confirmError = SignUpInputValidationError.EmptyFields in validationErrors ||
        SignUpInputValidationError.PasswordMismatch in validationErrors
    BecalmTextField(
        value = email,
        onValueChange = onEmailChange,
        label = stringResource(R.string.login_email_label),
        placeholder = stringResource(R.string.login_email_placeholder),
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        isError = emailError,
        supportingText = when {
            SignUpInputValidationError.InvalidEmail in validationErrors -> stringResource(R.string.login_error_invalid_email)
            else -> null
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("signup-email"),
    )
    Spacer(modifier = Modifier.height(12.dp))
    BecalmTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.login_password_label),
        placeholder = stringResource(R.string.login_password_placeholder),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Next,
        visualTransformation = PasswordVisualTransformation(),
        isError = passwordError,
        supportingText = when {
            SignUpInputValidationError.EmptyFields in validationErrors -> stringResource(R.string.signup_error_empty_fields)
            SignUpInputValidationError.ShortPassword in validationErrors -> stringResource(R.string.login_error_short_password)
            else -> null
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("signup-password"),
    )
    Spacer(modifier = Modifier.height(12.dp))
    BecalmTextField(
        value = confirmPassword,
        onValueChange = onConfirmPasswordChange,
        label = stringResource(R.string.signup_password_confirm_label),
        placeholder = stringResource(R.string.signup_password_confirm_placeholder),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        visualTransformation = PasswordVisualTransformation(),
        isError = confirmError,
        supportingText = when {
            SignUpInputValidationError.PasswordMismatch in validationErrors -> stringResource(R.string.signup_error_password_mismatch)
            else -> null
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("signup-password-confirm"),
    )
}

private val SignUpFormMaxContentWidth: androidx.compose.ui.unit.Dp = 480.dp

@PreviewLightDark
@Composable
private fun PreviewSignUpScreen() {
    BecalmTheme {
        BecalmScaffold(title = "계정 만들기") { padding ->
            SignUpForm(
                modifier = Modifier.padding(padding),
                isLoading = false,
                onSignUp = { _, _ -> },
                onNavigateToLogin = {},
            )
        }
    }
}
