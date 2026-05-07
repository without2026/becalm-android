package com.becalm.android.unit.ui.auth

import com.becalm.android.R
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.auth.splashDestinationFor
import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashRouteResolverSpecTest {

    @Test
    fun `loading stays on splash`() {
        assertEquals(null, splashDestinationFor(AuthUiState.Loading))
    }

    @Test
    fun `signed out without accepted terms routes to terms`() {
        assertEquals(
            BecalmRoute.Terms.path,
            splashDestinationFor(AuthUiState.SignedOut(termsAccepted = false)),
        )
    }

    @Test
    fun `signed out with accepted terms routes to login`() {
        assertEquals(
            BecalmRoute.Login.path,
            splashDestinationFor(AuthUiState.SignedOut(termsAccepted = true)),
        )
    }

    @Test
    fun `signed in without completed onboarding routes to onboarding setup`() {
        assertEquals(
            BecalmRoute.OnboardingSetup.path,
            splashDestinationFor(
                AuthUiState.SignedIn(userId = "user-1", onboardingCompleted = false),
            ),
        )
    }

    @Test
    fun `signed in without completed onboarding routes to persisted resume route`() {
        assertEquals(
            BecalmRoute.OnboardingSetup.path,
            splashDestinationFor(
                AuthUiState.SignedIn(
                    userId = "user-1",
                    onboardingCompleted = false,
                    onboardingResumeRoute = BecalmRoute.OnboardingSetup.path,
                ),
            ),
        )
    }

    @Test
    fun `signed in with completed onboarding routes to today`() {
        assertEquals(
            BecalmRoute.Today.path,
            splashDestinationFor(
                AuthUiState.SignedIn(userId = "user-1", onboardingCompleted = true),
            ),
        )
    }

    @Test
    fun `error routes to terms fallback`() {
        assertEquals(
            BecalmRoute.Terms.path,
            splashDestinationFor(AuthUiState.Error(UiMessage.resource(R.string.auth_error_session_restore_failed))),
        )
    }
}
