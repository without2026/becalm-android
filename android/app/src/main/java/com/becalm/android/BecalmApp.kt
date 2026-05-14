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
import com.becalm.android.productanalytics.NoopProductAnalyticsClient
import com.becalm.android.productanalytics.ProductAnalyticsClient
import com.becalm.android.productanalytics.ProductAnalyticsNames
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
 * @param pendingDeepLinkRoute Pre-resolved nav route from app-owned deep links such as
 *   `becalm://commitments/{id}`, `becalm://persons`, or `becalm://persons/unassigned`.
 * @param onDeepLinkConsumed Callback invoked once the deep-link navigation has been
 *   dispatched. No-op when no pending route is present.
 */
@Composable
public fun BecalmApp(
    pendingDeepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient,
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

    DisposableEffect(currentRoute) {
        val screenName = analyticsScreenName(currentRoute)
        val startedAt = System.currentTimeMillis()
        if (screenName != null) {
            productAnalytics.track(
                ProductAnalyticsNames.SCREEN_VIEWED,
                properties = mapOf("screen" to screenName),
            )
        }
        onDispose {
            if (screenName != null) {
                productAnalytics.track(
                    ProductAnalyticsNames.SCREEN_EXITED,
                    properties = mapOf(
                        "screen" to screenName,
                        "duration_seconds" to ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1_000L).toInt(),
                        "is_core_screen" to true,
                    ),
                )
            }
        }
    }

    LaunchedEffect(pendingDeepLinkRoute) {
        if (!pendingDeepLinkRoute.isNullOrBlank()) {
            navController.navigate(pendingDeepLinkRoute)
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

private fun analyticsScreenName(route: String?): String? =
    when (route) {
        BecalmRoute.Today.path -> "today"
        BecalmRoute.Persons.path -> "persons"
        BecalmRoute.PersonDetail.PATH -> "person_detail"
        BecalmRoute.RawEventDetail.PATH -> "raw_event_detail"
        BecalmRoute.Commitments.path -> "commitments"
        BecalmRoute.CommitmentDetail.PATH -> "commitment_detail"
        else -> null
    }
