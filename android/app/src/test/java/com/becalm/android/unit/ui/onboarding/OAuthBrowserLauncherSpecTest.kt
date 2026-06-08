package com.becalm.android.unit.ui.onboarding

import android.app.Activity
import com.becalm.android.ui.onboarding.OAuthBrowserLaunchResult
import com.becalm.android.ui.onboarding.OAuthBrowserLauncher
import com.becalm.android.ui.onboarding.TrustedOAuthBrowserPolicy
import com.becalm.android.ui.onboarding.oauthErrorStringMap
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthBrowserLauncherSpecTest {

    @Test
    fun `oauth browser launcher rejects non https authorization urls`() {
        val launcher = OAuthBrowserLauncher()
        val activity: Activity = mockk(relaxed = true)

        val result = launcher.launch(activity, "http://accounts.google.com/o/oauth2/v2/auth")

        assertEquals(OAuthBrowserLaunchResult.Unavailable, result)
    }

    @Test
    fun `oauth browser policy rejects disallowed in-app browsers`() {
        val selected = TrustedOAuthBrowserPolicy.selectTrustedBrowserPackage(
            viewPackages = setOf("com.nhn.android.search"),
            customTabsPackages = setOf("com.nhn.android.search"),
        )

        assertNull(selected)
    }

    @Test
    fun `oauth browser policy selects trusted custom tabs over disallowed browsers`() {
        val selected = TrustedOAuthBrowserPolicy.selectTrustedBrowserPackage(
            viewPackages = setOf("com.nhn.android.search", "com.android.chrome"),
            customTabsPackages = setOf("com.android.chrome"),
        )

        assertEquals("com.android.chrome", selected)
    }

    @Test
    fun `oauth browser policy falls back to trusted browsable browser when custom tabs are unavailable`() {
        val selected = TrustedOAuthBrowserPolicy.selectTrustedBrowserPackage(
            viewPackages = setOf("com.nhn.android.search", "com.sec.android.app.sbrowser"),
            customTabsPackages = emptySet(),
        )

        assertEquals("com.sec.android.app.sbrowser", selected)
    }

    @Test
    fun `oauth error copy map keeps browser unavailable distinct`() {
        val copyByCode = oauthErrorStringMap(
            network = "network",
            permission = "permission",
            browserUnavailable = "browser",
            unknown = "unknown",
        )

        assertEquals("browser", copyByCode["browser_unavailable"])
    }
}
