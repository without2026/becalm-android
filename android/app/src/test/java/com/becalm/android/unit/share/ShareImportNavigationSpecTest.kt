package com.becalm.android.unit.share

import androidx.test.core.app.ApplicationProvider
import com.becalm.android.MainActivity
import com.becalm.android.share.ShareImportNavigation
import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareImportNavigationSpecTest {
    @Test
    fun `share import success intent opens action first home with completed notice`() {
        val intent = ShareImportNavigation.mainAppIntent(
            context = ApplicationProvider.getApplicationContext(),
            route = BecalmRoute.Persons.path,
            importCompleted = true,
        )

        assertEquals(BecalmRoute.Persons.path, intent.getStringExtra(MainActivity.EXTRA_START_ROUTE))
        assertTrue(ShareImportNavigation.hasCompletedImport(intent))
    }
}
