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
            BecalmRoute.Persons.path,
            AppDeepLinks.routeFrom(Uri.parse(AppDeepLinks.PERSONS_URI)),
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

    @Test
    fun `e2e 071 commitment deep link routes valid ids and rejects invalid ids`() {
        val validIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("becalm://commitments/cmt-123"),
        )

        assertEquals(
            BecalmRoute.CommitmentDetail("cmt-123").path,
            AppDeepLinks.routeFrom(validIntent),
        )
        assertNull(AppDeepLinks.routeFrom(Uri.parse("becalm://commitments/")))
        assertNull(AppDeepLinks.routeFrom(Uri.parse("becalm://commitments")))
        assertNull(AppDeepLinks.routeFrom(Uri.parse("becalm://unknown/cmt-123")))
    }
}
