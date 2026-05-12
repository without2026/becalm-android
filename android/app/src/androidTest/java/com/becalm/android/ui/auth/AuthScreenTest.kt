package com.becalm.android.ui.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import androidx.navigation.compose.rememberNavController

@RunWith(AndroidJUnit4::class)
class AuthScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun splash_screen_routes_error_state_to_terms() {
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SplashScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.Error(UiMessage.resource(R.string.auth_error_session_restore_failed)),
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Terms.path, destination)
        }
    }

    @Test
    fun login_form_shows_empty_validation_and_disabled_google_cta() {
        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = false,
                    onSignIn = { _, _ -> },
                    onSignUp = { _, _ -> },
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
    fun login_form_blocks_invalid_email_and_short_password() {
        var submitted = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { _, _ -> submitted += 1 },
                    onSignUp = { _, _ -> submitted += 1 },
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
                    onSignUp = { _, _ -> },
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
    fun login_form_forwards_entered_credentials_to_signup() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { _, _ -> },
                    onSignUp = { email, password ->
                        submittedEmail = email
                        submittedPassword = password
                    },
                    onGoogleSignIn = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("login-email").performTextInput("new@example.com")
        composeTestRule.onNodeWithTag("login-password").performTextInput("ValidPass1!")
        composeTestRule.onNodeWithText(string(R.string.login_signup_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("new@example.com", submittedEmail)
            assertEquals("ValidPass1!", submittedPassword)
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
                    onEmailSignUp = { _, _ -> },
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
                    onEmailSignUp = { _, _ -> },
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

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.Error(UiMessage.resource(R.string.auth_error_unknown)),
                    onEmailSignIn = { _, _ -> },
                    onEmailSignUp = { _, _ -> },
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

        composeTestRule.onNodeWithText("bad credentials").assertIsDisplayed()
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
                    onEmailSignUp = { _, _ -> },
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
    fun login_screen_mounts_with_secure_flag_enabled() {
        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    onEmailSignUp = { _, _ -> },
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

        composeTestRule.onNodeWithText(string(R.string.login_title)).assertIsDisplayed()
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

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
