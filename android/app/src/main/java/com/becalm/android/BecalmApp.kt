package com.becalm.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.becalm.android.ui.components.BecalmBottomNavigation
import com.becalm.android.ui.navigation.BecalmNavHost
import com.becalm.android.ui.navigation.BecalmRoute

/** Routes where the bottom navigation bar is visible. */
private val TAB_ROUTES = setOf(
    BecalmRoute.Today.path,
    BecalmRoute.Persons.path,
    BecalmRoute.Commitments.path,
)

/**
 * Root composable that hosts the nav controller, scaffold, and bottom navigation.
 *
 * Navigation always starts at [BecalmRoute.Splash]; the Splash screen itself
 * handles the onboarding-completed / auth-state routing decision.
 */
@Composable
public fun BecalmApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in TAB_ROUTES) {
                BecalmBottomNavigation(
                    currentRoute = currentRoute,
                    navController = navController,
                )
            }
        },
    ) { padding ->
        BecalmNavHost(
            navController = navController,
            startDestination = BecalmRoute.Splash.path,
            modifier = Modifier.padding(padding),
        )
    }
}
