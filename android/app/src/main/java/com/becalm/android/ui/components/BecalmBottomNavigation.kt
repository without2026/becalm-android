package com.becalm.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.becalm.android.R
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.becalmColors

/**
 * Bottom navigation bar for the three main tabs: Today, Persons, Commitments.
 *
 * Only rendered when [currentRoute] is one of the tab routes (enforced by caller).
 *
 * @param currentRoute  The active route string from the back stack entry.
 * @param navController The nav controller used to navigate between tabs.
 */
@Composable
public fun BecalmBottomNavigation(
    currentRoute: String?,
    navController: NavController,
) {
    val becalmColors = MaterialTheme.becalmColors

    NavigationBar(
        containerColor = becalmColors.glassPanelFill,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = currentRoute == BecalmRoute.Today.path,
            onClick = { navController.navigateToTab(BecalmRoute.Today.path) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Today,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.nav_today)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = becalmColors.glassPanelFillElevated,
                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        NavigationBarItem(
            selected = currentRoute == BecalmRoute.Persons.path,
            onClick = { navController.navigateToTab(BecalmRoute.Persons.path) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.nav_persons)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = becalmColors.glassPanelFillElevated,
                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        NavigationBarItem(
            selected = currentRoute == BecalmRoute.Commitments.path,
            onClick = { navController.navigateToTab(BecalmRoute.Commitments.path) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Checklist,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.nav_commitments)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = becalmColors.glassPanelFillElevated,
                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

private fun NavController.navigateToTab(route: String) {
    // Pop to the Today tab (the first post-splash destination) rather than the graph's
    // literal start destination, which is Splash and is removed from the back stack by
    // SplashScreen's popUpTo(inclusive = true). Without this, every tab tap would push a
    // new instance because popUpTo cannot find a matching entry and restoreState is skipped.
    navigate(route) {
        popUpTo(com.becalm.android.ui.navigation.BecalmRoute.Today.path) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
