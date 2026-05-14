package com.becalm.android.unit.navigation

import com.becalm.android.shouldDispatchPendingDeepLink
import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BecalmAppDeepLinkDispatchSpecTest {

    @Test
    fun `authenticated deep links wait until splash resolves to a signed-in surface`() {
        val commitmentRoute = BecalmRoute.CommitmentDetail("cmt-1").path

        assertFalse(shouldDispatchPendingDeepLink(commitmentRoute, null))
        assertFalse(shouldDispatchPendingDeepLink(commitmentRoute, BecalmRoute.Splash.path))
        assertFalse(shouldDispatchPendingDeepLink(commitmentRoute, BecalmRoute.Terms.path))
        assertFalse(shouldDispatchPendingDeepLink(commitmentRoute, BecalmRoute.Login.path))
        assertFalse(shouldDispatchPendingDeepLink(commitmentRoute, BecalmRoute.OnboardingSetup.path))
        assertTrue(shouldDispatchPendingDeepLink(commitmentRoute, BecalmRoute.Today.path))
    }

    @Test
    fun `public auth links can dispatch before authenticated database setup`() {
        assertTrue(shouldDispatchPendingDeepLink(BecalmRoute.Terms.path, BecalmRoute.Splash.path))
        assertTrue(shouldDispatchPendingDeepLink(BecalmRoute.Login.path, BecalmRoute.Terms.path))
    }
}
