package com.becalm.android.unit.navigation

import android.content.Intent
import android.net.Uri
import com.becalm.android.ui.navigation.AppDeepLinks
import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDeepLinksSpecTest {

    @Test
    fun `routeFrom maps commitment and matching links to nav routes`() {
        assertEquals(
            BecalmRoute.CommitmentDetail("commitment-1").path,
            AppDeepLinks.routeFrom(Uri.parse("becalm://commitments/commitment-1")),
        )
        assertEquals(
            BecalmRoute.PersonsUnassigned.path,
            AppDeepLinks.routeFrom(Uri.parse(AppDeepLinks.PERSONS_UNASSIGNED_URI)),
        )
    }

    @Test
    fun `routeFrom ignores unsupported intents`() {
        assertNull(AppDeepLinks.routeFrom(Uri.parse("https://example.com/persons/unassigned")))
        assertNull(AppDeepLinks.routeFrom(Intent(Intent.ACTION_SEND)))
    }
}
