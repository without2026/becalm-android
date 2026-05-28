package com.becalm.android.unit.navigation

import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class BecalmNavigationDefaultsSpecTest {
    @Test
    fun `authenticated home is people first`() {
        assertEquals(BecalmRoute.Persons.path, BecalmNavigationDefaults.authenticatedHomeRoute)
    }

    @Test
    fun `main tabs are ordered by product priority`() {
        assertEquals(
            listOf(
                BecalmRoute.Persons.path,
                BecalmRoute.Today.path,
                BecalmRoute.Commitments.path,
            ),
            BecalmNavigationDefaults.mainTabRoutes,
        )
    }
}
