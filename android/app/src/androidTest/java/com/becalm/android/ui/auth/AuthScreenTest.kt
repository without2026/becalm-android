package com.becalm.android.ui.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthScreenTest {
    // spec: AUTH-012

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun splash_content_shows_branding_and_tagline() {
        composeTestRule.setContent {
            BecalmTheme {
                SplashContent()
            }
        }

        composeTestRule.onNodeWithText(string(R.string.splash_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.splash_tagline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.splash_loading)).assertIsDisplayed()
    }

    @Test
    // spec: ONB-001
    fun splash_screen_navigates_to_terms_for_signed_out_without_terms() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = false),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Terms.path, destination)
        }
    }

    @Test
    fun splash_screen_navigates_to_today_for_completed_session() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedIn(
                        userId = "user-1",
                        onboardingCompleted = true,
                    ),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Today.path, destination)
        }
    }

    @Test
    fun splash_screen_navigates_to_login_for_signed_out_with_accepted_terms() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Login.path, destination)
        }
    }

    @Test
    // spec: ONB-SETUP
    fun splash_screen_navigates_to_onboarding_pipa_for_incomplete_session() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedIn(
                        userId = "user-1",
                        onboardingCompleted = false,
                    ),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.OnboardingSetup.path, destination)
        }
    }

    @Test
    fun splash_screen_does_not_navigate_while_loading() {
        var destination: String? = "unexpected"

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.Loading,
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals("unexpected", destination)
        }
        composeTestRule.onNodeWithText(string(R.string.splash_loading)).assertIsDisplayed()
    }

    @Test
    fun splash_screen_routes_recovery_state_to_auth_recovery() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.RecoveryRequired(
                        message = UiMessage.resource(R.string.auth_error_session_restore_failed),
                        termsAccepted = true,
                    ),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.AuthRecovery(termsAccepted = true).path, destination)
        }
    }

    @Test
    fun auth_recovery_content_returns_to_login_and_supports_retry() {
        var returned = 0
        var retried = 0

        composeTestRule.setContent {
            BecalmTheme {
                AuthRecoveryContent(
                    termsAccepted = true,
                    onReturnToAuthShell = { returned += 1 },
                    onRetry = { retried += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.auth_recovery_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.auth_recovery_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.auth_recovery_login_cta)).performClick()
        composeTestRule.onNodeWithText(string(R.string.auth_recovery_retry_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, returned)
            assertEquals(1, retried)
        }
    }

    @Test
    // spec: AUTH-003A
    // spec: AUTH-012
    // spec: RUX-001
    // spec: RUX-005
    fun login_form_shows_empty_validation_and_disabled_google_cta() {
        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = false,
                    onSignIn = { _, _ -> },
                    onSignUp = {},
                    onGoogleSignIn = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeTestRule.onNodeWithText(string(R.string.login_error_empty_fields)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("google-sign-in-button").assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.login_google_cta)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.login_google_setup_required)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.login_email_section_label)).assertIsDisplayed()
    }

    @Test
    // spec: AUTH-012
    // spec: RUX-004
    // spec: RUX-010
    fun login_form_blocks_invalid_email_and_short_password() {
        var submitted = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { _, _ -> submitted += 1 },
                    onSignUp = { submitted += 1 },
                    onGoogleSignIn = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("login-email").performTextInput("not-an-email")
        composeTestRule.onNodeWithTag("login-password").performTextInput("short")
        composeTestRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeTestRule.onNodeWithText(string(R.string.login_error_invalid_email)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.login_error_short_password)).assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(0, submitted)
        }
    }

    @Test
    fun login_form_forwards_entered_credentials() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { email, password ->
                        submittedEmail = email
                        submittedPassword = password
                    },
                    onSignUp = {},
                    onGoogleSignIn = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("login-email").performTextInput("user@example.com")
        composeTestRule.onNodeWithTag("login-password").performTextInput("ValidPass1!")
        composeTestRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("user@example.com", submittedEmail)
            assertEquals("ValidPass1!", submittedPassword)
        }
    }

    @Test
    fun login_form_opens_separate_account_creation_intent_without_validating_email_fields() {
        var createAccountCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { _, _ -> },
                    onSignUp = { createAccountCount += 1 },
                    onGoogleSignIn = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.login_signup_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, createAccountCount)
        }
    }

    @Test
    fun signup_form_validates_confirmation_password_before_submit() {
        var submitted = 0

        composeTestRule.setContent {
            BecalmTheme {
                SignUpForm(
                    isLoading = false,
                    onSignUp = { _, _ -> submitted += 1 },
                    onNavigateToLogin = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("signup-email").performTextInput("new@example.com")
        composeTestRule.onNodeWithTag("signup-password").performTextInput("ValidPass1!")
        composeTestRule.onNodeWithTag("signup-password-confirm").performTextInput("Different1!")
        composeTestRule.onNodeWithText(string(R.string.signup_cta)).performClick()

        composeTestRule.onNodeWithText(string(R.string.signup_error_password_mismatch)).assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(0, submitted)
        }
    }

    @Test
    fun signup_form_forwards_trimmed_email_and_password() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SignUpForm(
                    isLoading = false,
                    onSignUp = { email, password ->
                        submittedEmail = email
                        submittedPassword = password
                    },
                    onNavigateToLogin = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("signup-email").performTextInput(" new@example.com ")
        composeTestRule.onNodeWithTag("signup-password").performTextInput("ValidPass1!")
        composeTestRule.onNodeWithTag("signup-password-confirm").performTextInput("ValidPass1!")
        composeTestRule.onNodeWithText(string(R.string.signup_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("new@example.com", submittedEmail)
            assertEquals("ValidPass1!", submittedPassword)
        }
    }

    @Test
    fun signup_confirmation_content_separates_email_verification_from_failure() {
        var loginCount = 0
        var retryCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SignUpEmailConfirmationContent(
                    email = "new@example.com",
                    onNavigateToLogin = { loginCount += 1 },
                    onTryDifferentEmail = { retryCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.signup_confirmation_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.signup_confirmation_body, "new@example.com")).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.signup_confirmation_login_cta)).performClick()
        composeTestRule.onNodeWithText(string(R.string.signup_confirmation_retry_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, loginCount)
            assertEquals(1, retryCount)
        }
    }

    @Test
    fun login_screen_navigates_after_sign_in_and_marks_login_granted() {
        var destination: String? = null
        var grantedCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedIn(
                        userId = "user-1",
                        onboardingCompleted = false,
                    ),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = {},
                    onSignedInNavigate = { destination = it },
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    onMarkLoginGranted = { grantedCount += 1 },
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.OnboardingSetup.path, destination)
            assertEquals(1, grantedCount)
        }
    }

    @Test
    fun login_screen_navigates_to_today_for_completed_signed_in_session() {
        var destination: String? = null
        var grantedCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedIn(
                        userId = "user-1",
                        onboardingCompleted = true,
                    ),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = {},
                    onSignedInNavigate = { destination = it },
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    onMarkLoginGranted = { grantedCount += 1 },
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Today.path, destination)
            assertEquals(1, grantedCount)
        }
    }

    @Test
    fun login_screen_shows_error_snackbar_and_consumes_error() {
        var dismissCount = 0
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.Error(UiMessage.resource(R.string.auth_error_unknown)),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = {},
                    onSignedInNavigate = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = { dismissCount += 1 },
                    onMarkLoginGranted = {},
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun login_screen_launches_google_sign_in_when_cta_clicked() {
        var launchCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = { launchCount += 1 },
                    onSignedInNavigate = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    onMarkLoginGranted = {},
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.login_google_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, launchCount)
        }
    }

    @Test
    fun login_screen_opens_signup_route_when_create_account_clicked() {
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = {},
                    onNavigateToSignUp = { navigateCount += 1 },
                    onSignedInNavigate = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    onMarkLoginGranted = {},
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.login_signup_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, navigateCount)
        }
    }

    @Test
    fun signup_screen_navigates_after_signed_in_and_marks_login_granted() {
        var destination: String? = null
        var grantedCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SignUpScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedIn(
                        userId = "user-1",
                        onboardingCompleted = false,
                    ),
                    onEmailSignUp = { _, _ -> },
                    onSignedInNavigate = { destination = it },
                    onMarkLoginGranted = { grantedCount += 1 },
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.OnboardingSetup.path, destination)
            assertEquals(1, grantedCount)
        }
    }

    @Test
    fun login_screen_mounts_with_secure_flag_enabled() {
        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = {},
                    onSignedInNavigate = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    onMarkLoginGranted = {},
                    applySecureFlag = true,
                )
            }
        }

        composeTestRule.onAllNodesWithText(string(R.string.login_title)).onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.login_google_cta)).assertIsDisplayed()
    }

    @Test
    fun terms_content_gates_continue_behind_checkbox_and_exposes_decline() {
        var acceptedCount = 0
        var declinedCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TermsContent(
                    accepted = false,
                    onAcceptedChange = {},
                    onContinue = { acceptedCount += 1 },
                    onDecline = { declinedCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.terms_pipa_notice)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.terms_cta)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.terms_decline_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(0, acceptedCount)
            assertEquals(1, declinedCount)
        }
    }

    @Test
    fun terms_content_enables_continue_when_accepted() {
        var accepted by mutableStateOf(false)
        var continueCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TermsContent(
                    accepted = accepted,
                    onAcceptedChange = { accepted = it },
                    onContinue = { continueCount += 1 },
                    onDecline = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("terms-checkbox").performClick()
        composeTestRule.onNodeWithTag("terms-checkbox").assertIsOn()
        composeTestRule.onNodeWithText(string(R.string.terms_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, continueCount)
        }
    }

    @Test
    fun terms_screen_consumes_navigation_and_finish_effects() {
        val authEffects = MutableSharedFlow<AuthEffect>(extraBufferCapacity = 1)
        var navigateCount = 0
        var finishCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TermsScreen(
                    navController = rememberNavController(),
                    authEffects = authEffects,
                    onContinue = {},
                    onDecline = {},
                    onNavigateToLogin = { navigateCount += 1 },
                    onFinishApp = { finishCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            authEffects.tryEmit(AuthEffect.NavigateToLogin)
            authEffects.tryEmit(AuthEffect.FinishApp)
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, navigateCount)
            assertEquals(1, finishCount)
        }
    }

    private fun string(resId: Int, vararg formatArgs: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *formatArgs)

}
