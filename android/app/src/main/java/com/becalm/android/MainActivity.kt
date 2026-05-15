package com.becalm.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.ui.navigation.AppDeepLinks
import com.becalm.android.ui.theme.BecalmTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.datetime.Clock

/**
 * Single-activity entry point for the BeCalm Android app.
 *
 * Enables edge-to-edge display and delegates all UI to [BecalmApp]. Also handles the
 * `becalm://commitments/{id}` deep link posted by
 * [com.becalm.android.receiver.ReminderBroadcastReceiver] (CMT-008) — the incoming
 * [Intent.getData] is parsed and routed to [com.becalm.android.ui.navigation.BecalmRoute.CommitmentDetail].
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    @Inject
    public lateinit var productAnalytics: ProductAnalyticsClient

    /**
     * Holds the most recent incoming deep-link route so the Compose root can
     * navigate once the nav graph is initialized. Cleared by the composable after
     * consumption.
     */
    private val pendingDeepLinkRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch-time splash theme for the real app theme BEFORE super.onCreate
        // so the first Compose frame renders against Theme.Becalm, not Theme.Becalm.Splash.
        setTheme(R.style.Theme_Becalm)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        pendingDeepLinkRoute.value = intent?.let(::routeAndTrackDeepLink)

        setContent {
            BecalmTheme {
                var deepLinkRoute by remember { pendingDeepLinkRoute }
                BecalmApp(
                    pendingDeepLinkRoute = deepLinkRoute,
                    onDeepLinkConsumed = { deepLinkRoute = null },
                    productAnalytics = productAnalytics,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeAndTrackDeepLink(intent)?.let { pendingDeepLinkRoute.value = it }
    }

    private fun routeAndTrackDeepLink(intent: Intent): String? {
        val route = AppDeepLinks.routeFrom(intent) ?: return null
        if (route.startsWith("commitments/")) {
            productAnalytics.track(
                ProductAnalyticsEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventName = ProductAnalyticsEvents.COMMITMENT_NOTIFICATION_OPENED,
                    occurredAt = Clock.System.now(),
                    properties = mapOf("route" to route),
                ),
            )
        }
        return route
    }
}
