package com.becalm.android.integration.local.ui.auth

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
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.auth.LoginForm
import com.becalm.android.ui.auth.LoginScreen
import com.becalm.android.ui.auth.SplashContent
import com.becalm.android.ui.auth.SplashScreen
import com.becalm.android.ui.auth.TermsContent
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AuthUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `splash content shows branding and tagline`() {
        composeRule.setContent {
            BecalmTheme {
                SplashContent()
            }
        }

        composeRule.onNodeWithText(string(R.string.splash_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.splash_tagline)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.splash_loading)).assertIsDisplayed()
    }

    @Test
    fun `splash screen routes signed out user without terms to terms`() {
        assertSplashRoute(AuthUiState.SignedOut(termsAccepted = false), BecalmRoute.Terms.path)
    }

    @Test
    fun `splash screen routes signed out user with terms to login`() {
        assertSplashRoute(AuthUiState.SignedOut(termsAccepted = true), BecalmRoute.Login.path)
    }

    @Test
    fun `splash screen routes signed in unfinished user to onboarding setup`() {
        assertSplashRoute(
            AuthUiState.SignedIn(userId = "user-1", onboardingCompleted = false),
            BecalmRoute.OnboardingSetup.path,
        )
    }

    @Test
    fun `splash screen routes signed in finished user to today`() {
        assertSplashRoute(
            AuthUiState.SignedIn(userId = "user-1", onboardingCompleted = true),
            BecalmRoute.Today.path,
        )
    }

    @Test
    fun `splash screen keeps loading state on splash`() {
        var route: String? = "sentinel"

        composeRule.setContent {
            val navController = rememberNavController()
            BecalmTheme {
                SplashScreen(
                    navController = navController,
                    stateOverride = AuthUiState.Loading,
                    onNavigate = { route = it },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals("sentinel", route)
        }
        composeRule.onNodeWithText(string(R.string.splash_loading)).assertIsDisplayed()
    }

    @Test
    fun `splash screen routes error state to terms`() {
        assertSplashRoute(
            AuthUiState.Error(UiMessage.resource(R.string.auth_error_session_restore_failed)),
            BecalmRoute.Terms.path,
        )
    }

    @Test
    fun `login form shows empty validation and disabled google cta`() {
        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeRule.onNodeWithText(string(R.string.login_error_empty_fields)).assertIsDisplayed()
        composeRule.onNodeWithTag("google-sign-in-button").assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.login_google_cta)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.login_google_setup_required)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_email_section_label)).assertIsDisplayed()
    }

    @Test
    fun `login form explains trusted source approval loading and inline auth error`() {
        composeRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = true,
                    googleSignInEnabled = false,
                    authErrorMessage = string(R.string.auth_error_network),
                    onSignIn = { _, _ -> },
                    onSignUp = { _, _ -> },
                    onGoogleSignIn = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.login_trust_note)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_loading_status)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.auth_error_network)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_google_setup_required)).assertIsDisplayed()
    }

    @Test
    fun `login form blocks invalid email and short password before submit`() {
        var submitted = 0

        composeRule.setContent {
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

        composeRule.onNodeWithTag("login-email").performTextInput("not-an-email")
        composeRule.onNodeWithTag("login-password").performTextInput("short")
        composeRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeRule.onNodeWithText(string(R.string.login_error_invalid_email)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_error_short_password)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, submitted)
        }
    }

    @Test
    fun `login form forwards entered credentials`() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null

        composeRule.setContent {
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

        composeRule.onNodeWithTag("login-email").performTextInput("user@example.com")
        composeRule.onNodeWithTag("login-password").performTextInput("ValidPass1!")
        composeRule.onNodeWithText(string(R.string.login_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals("user@example.com", submittedEmail)
            assertEquals("ValidPass1!", submittedPassword)
        }
    }

    @Test
    fun `login form forwards entered credentials for account creation`() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null

        composeRule.setContent {
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

        composeRule.onNodeWithTag("login-email").performTextInput("new@example.com")
        composeRule.onNodeWithTag("login-password").performTextInput("ValidPass1!")
        composeRule.onNodeWithText(string(R.string.login_signup_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals("new@example.com", submittedEmail)
            assertEquals("ValidPass1!", submittedPassword)
        }
    }

    @Test
    fun `login screen signed out shell renders without onboarding owner callback`() {
        composeRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    onEmailSignUp = { _, _ -> },
                    googleSignInEnabledOverride = false,
                    onGoogleSignInLaunch = {},
                    onSignedInNavigate = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    applySecureFlag = false,
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.login_email_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_google_cta)).assertIsNotEnabled()
    }

    @Test
    fun `terms content gates continue behind checkbox and exposes decline`() {
        var acceptedCount = 0
        var declinedCount = 0

        composeRule.setContent {
            BecalmTheme {
                TermsContent(
                    accepted = false,
                    onAcceptedChange = {},
                    onContinue = { acceptedCount += 1 },
                    onDecline = { declinedCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.terms_pipa_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.terms_cta)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.terms_decline_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals(0, acceptedCount)
            assertEquals(1, declinedCount)
        }
    }

    @Test
    fun `terms content enables continue when accepted`() {
        var accepted by mutableStateOf(false)
        var continueCount = 0

        composeRule.setContent {
            BecalmTheme {
                TermsContent(
                    accepted = accepted,
                    onAcceptedChange = { accepted = it },
                    onContinue = { continueCount += 1 },
                    onDecline = {},
                )
            }
        }

        composeRule.onNodeWithTag("terms-checkbox").performClick()
        composeRule.onNodeWithTag("terms-checkbox").assertIsOn()
        composeRule.onNodeWithText(string(R.string.terms_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, continueCount)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun assertSplashRoute(state: AuthUiState, expectedRoute: String) {
        var route: String? = null

        composeRule.setContent {
            val navController = rememberNavController()
            BecalmTheme {
                SplashScreen(
                    navController = navController,
                    stateOverride = state,
                    onNavigate = { route = it },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(expectedRoute, route)
        }
    }
}
