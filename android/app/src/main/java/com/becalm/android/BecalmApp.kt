package com.becalm.android

import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
 *
 * @param pendingCommitmentDeepLinkId Commitment id parsed from an incoming
 *   `becalm://commitments/{id}` deep link (CMT-008). When non-null, triggers a
 *   navigate to [BecalmRoute.CommitmentDetail] as soon as the nav graph is ready,
 *   then calls [onDeepLinkConsumed] so the parent activity can clear the pending
 *   state and avoid re-navigating on recomposition.
 * @param onDeepLinkConsumed Callback invoked once the deep-link navigation has been
 *   dispatched. No-op when [pendingCommitmentDeepLinkId] is null.
 */
@Composable
public fun BecalmApp(
    pendingCommitmentDeepLinkId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            Log.d("NavDebug", "destination=${destination.route}")
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }

    LaunchedEffect(pendingCommitmentDeepLinkId) {
        val id = pendingCommitmentDeepLinkId
        if (!id.isNullOrBlank()) {
            navController.navigate(BecalmRoute.CommitmentDetail(id).path)
            onDeepLinkConsumed()
        }
    }

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
