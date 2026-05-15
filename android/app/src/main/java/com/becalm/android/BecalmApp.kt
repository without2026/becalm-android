package com.becalm.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.ui.components.BecalmBottomNavigation
import com.becalm.android.ui.navigation.BecalmNavHost
import com.becalm.android.ui.navigation.BecalmRoute
import java.util.UUID
import kotlinx.datetime.Clock

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
    productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    TrackScreenTelemetry(currentRoute, productAnalytics)

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

@Composable
private fun TrackScreenTelemetry(
    currentRoute: String?,
    productAnalytics: ProductAnalyticsClient,
) {
    val previousRoute = androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val routeEnteredAtMillis = androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(currentRoute) {
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val prior = previousRoute.value
        if (prior != null && prior != currentRoute) {
            val durationSeconds = routeEnteredAtMillis.value
                ?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
                ?: 0L
            productAnalytics.track(
                screenEvent(
                    eventName = ProductAnalyticsEvents.SCREEN_EXITED,
                    route = prior,
                    extra = mapOf(
                        "duration_seconds" to durationSeconds,
                        "meaningful_usage" to isMeaningfulRoute(prior),
                    ),
                ),
            )
        }
        if (currentRoute != null && currentRoute != prior) {
            productAnalytics.track(screenEvent(ProductAnalyticsEvents.SCREEN_VIEWED, currentRoute))
            routeEnteredAtMillis.value = nowMillis
        }
        previousRoute.value = currentRoute
    }
}

private fun screenEvent(
    eventName: String,
    route: String,
    extra: Map<String, Any?> = emptyMap(),
): ProductAnalyticsEvent =
    ProductAnalyticsEvent(
        eventId = UUID.randomUUID().toString(),
        eventName = eventName,
        occurredAt = Clock.System.now(),
        properties = mapOf(
            "route" to route,
            "screen_group" to screenGroup(route),
        ) + extra,
    )

private fun isMeaningfulRoute(route: String): Boolean =
    screenGroup(route) in setOf("today", "people", "commitments", "source_detail", "detail")

private fun screenGroup(route: String): String =
    when {
        route == BecalmRoute.Today.path -> "today"
        route == BecalmRoute.Persons.path -> "people"
        route == BecalmRoute.Commitments.path -> "commitments"
        route.startsWith("settings/sources") -> "source_detail"
        route.contains("detail") || route.startsWith("persons/") || route.startsWith("commitments/") -> "detail"
        route.startsWith("onboarding") -> "onboarding"
        route == BecalmRoute.Settings.path -> "settings"
        else -> "other"
    }
