package com.becalm.android.integration.local.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import com.becalm.android.ui.components.BecalmBottomNavigation
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BecalmBottomNavigationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bottom nav renders prototype tab labels and urgent action badge`() {
        composeRule.setContent {
            BecalmTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    BecalmBottomNavigation(
                        currentRoute = BecalmRoute.Persons.path,
                        navController = rememberNavController(),
                        personActionBadgeCount = 3,
                    )
                }
            }
        }

        composeRule.onNodeWithText("인물").assertExists()
        composeRule.onNodeWithText("일정").assertExists()
        composeRule.onNodeWithText("약속").assertExists()
        composeRule.onNodeWithTag("bottom-nav-persons-badge", useUnmergedTree = true)
            .assertExists()
            .assertTextEquals("3")
    }

    @Test
    fun `bottom nav caps prototype person badge at nine plus`() {
        composeRule.setContent {
            BecalmTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    BecalmBottomNavigation(
                        currentRoute = BecalmRoute.Persons.path,
                        navController = rememberNavController(),
                        personActionBadgeCount = 10,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("bottom-nav-persons-badge", useUnmergedTree = true)
            .assertExists()
            .assertTextEquals("9+")
    }
}
