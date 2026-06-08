package com.becalm.android.unit.navigation

import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.DeepLinkNavigationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeepLinkNavigationPolicySpecTest {
    private val oauthResultRoute = BecalmRoute.SettingsSources.oauthResultPath(
        result = "success",
        provider = "gmail",
        family = "mail",
    )

    @Test
    fun `oauth completion waits until splash resolves the actual app route`() {
        assertTrue(DeepLinkNavigationPolicy.shouldDeferNavigation(oauthResultRoute, currentRoute = null))
        assertTrue(
            DeepLinkNavigationPolicy.shouldDeferNavigation(
                oauthResultRoute,
                currentRoute = BecalmRoute.Splash.path,
            ),
        )
    }

    @Test
    fun `oauth completion is consumed on onboarding so current setup screen can refresh in place`() {
        listOf(
            BecalmRoute.OnboardingSetupCalendar.path,
            BecalmRoute.OnboardingSetupEmail.path,
            BecalmRoute.OnboardingSources.path,
            BecalmRoute.OnboardingGmail.path,
            BecalmRoute.OnboardingGoogleCalendar.path,
        ).forEach { currentRoute ->
            assertTrue(
                "expected OAuth completion to stay on $currentRoute",
                DeepLinkNavigationPolicy.shouldConsumeWithoutNavigation(
                    oauthResultRoute,
                    currentRoute = currentRoute,
                ),
            )
        }
    }

    @Test
    fun `oauth completion can still route authenticated main app users to sources`() {
        assertFalse(
            DeepLinkNavigationPolicy.shouldDeferNavigation(
                oauthResultRoute,
                currentRoute = BecalmRoute.Persons.path,
            ),
        )
        assertFalse(
            DeepLinkNavigationPolicy.shouldConsumeWithoutNavigation(
                oauthResultRoute,
                currentRoute = BecalmRoute.Persons.path,
            ),
        )
    }

    @Test
    fun `non oauth deep links still navigate immediately from onboarding`() {
        val commitmentRoute = BecalmRoute.CommitmentDetail("commitment-1").path

        assertFalse(
            DeepLinkNavigationPolicy.shouldDeferNavigation(
                commitmentRoute,
                currentRoute = BecalmRoute.OnboardingSetupEmail.path,
            ),
        )
        assertFalse(
            DeepLinkNavigationPolicy.shouldConsumeWithoutNavigation(
                commitmentRoute,
                currentRoute = BecalmRoute.OnboardingSetupEmail.path,
            ),
        )
    }
}
