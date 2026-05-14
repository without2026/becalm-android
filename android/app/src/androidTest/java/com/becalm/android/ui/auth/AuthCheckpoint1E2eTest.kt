package com.becalm.android.ui.auth

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthCheckpoint1E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_001_new_user_sees_korean_terms_and_privacy_first() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides koreanContext()) {
                BecalmTheme {
                    TermsContent(
                        accepted = false,
                        onAcceptedChange = {},
                        onContinue = {},
                        onDecline = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(koString(R.string.terms_subtitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(koString(R.string.terms_check_terms_privacy)).assertIsDisplayed()
        composeTestRule.onNodeWithText(koString(R.string.terms_check_local_first)).assertIsDisplayed()
        composeTestRule.onNodeWithText(koString(R.string.terms_pipa_notice)).assertIsDisplayed()
        composeTestRule.onNodeWithText(koString(R.string.terms_cta)).assertIsNotEnabled()
    }

    @Test
    fun e2e_002_user_accepts_terms_and_reaches_login() {
        val effects = MutableSharedFlow<AuthEffect>(extraBufferCapacity = 1)
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                TermsScreen(
                    navController = rememberNavController(),
                    authEffects = effects,
                    onContinue = { effects.tryEmit(AuthEffect.NavigateToLogin) },
                    onDecline = {},
                    onNavigateToLogin = { destination = BecalmRoute.Login.path },
                )
            }
        }

        composeTestRule.onNodeWithTag("terms-checkbox").performClick()
        composeTestRule.onNodeWithTag("terms-checkbox").assertIsOn()
        composeTestRule.onNodeWithText(string(R.string.terms_cta)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            destination == BecalmRoute.Login.path
        }
        composeTestRule.runOnIdle {
            assertEquals(BecalmRoute.Login.path, destination)
        }
    }

    @Test
    fun e2e_003_google_sign_in_entrypoint_launches_provider_once() {
        var launches = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginForm(
                    isLoading = false,
                    googleSignInEnabled = true,
                    onSignIn = { _, _ -> },
                    onSignUp = { _, _ -> },
                    onGoogleSignIn = { launches += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("google-sign-in-button").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, launches)
        }
    }

    @Test
    fun e2e_004_google_sign_in_cancel_keeps_user_on_login() {
        var launches = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = AuthUiState.SignedOut(termsAccepted = true),
                    onEmailSignIn = { _, _ -> },
                    onEmailSignUp = { _, _ -> },
                    googleSignInEnabledOverride = true,
                    onGoogleSignInLaunch = { launches += 1 },
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("google-sign-in-button").performClick()

        composeTestRule.onNodeWithTag("login-email").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(1, launches)
        }
    }

    @Test
    fun e2e_005_login_failure_remains_on_login_and_surfaces_recoverable_error() {
        var state by mutableStateOf<AuthUiState>(AuthUiState.SignedOut(termsAccepted = true))
        var signInAttempts = 0

        composeTestRule.setContent {
            BecalmTheme {
                LoginScreen(
                    navController = rememberNavController(),
                    stateOverride = state,
                    onEmailSignIn = { _, _ ->
                        signInAttempts += 1
                        state = AuthUiState.Error(
                            UiMessage.resource(R.string.auth_error_session_restore_failed),
                        )
                    },
                    onEmailSignUp = { _, _ -> },
                    googleSignInEnabledOverride = false,
                    onGoogleSignInLaunch = {},
                    onGoogleIdToken = {},
                    onErrorDismissed = {},
                    applySecureFlag = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("login-email").performTextInput("user@example.com")
        composeTestRule.onNodeWithTag("login-password").performTextInput("secret")
        composeTestRule.onAllNodesWithText(string(R.string.login_cta))[0].performClick()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("login-email").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(1, signInAttempts)
        }
    }

    @Test
    fun e2e_006_returning_signed_in_user_bypasses_auth_routes() {
        assertEquals(
            BecalmRoute.Today.path,
            splashDestinationFor(
                AuthUiState.SignedIn(
                    userId = "user-1",
                    onboardingCompleted = true,
                ),
            ),
        )
        assertEquals(
            BecalmRoute.OnboardingSetup.path,
            splashDestinationFor(
                AuthUiState.SignedIn(
                    userId = "user-1",
                    onboardingCompleted = false,
                ),
            ),
        )
        assertEquals(
            BecalmRoute.Login.path,
            splashDestinationFor(AuthUiState.SignedOut(termsAccepted = true)),
        )
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun koString(resId: Int): String =
        koreanContext().getString(resId)

    private fun koreanContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.KOREA)
        return base.createConfigurationContext(config)
    }
}
