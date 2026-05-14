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
import com.becalm.android.productanalytics.ProductAnalyticsClient
import com.becalm.android.productanalytics.ProductAnalyticsNames
import com.becalm.android.productanalytics.ProductSessionTracker
import com.becalm.android.receiver.ReminderBroadcastReceiver
import com.becalm.android.ui.navigation.AppDeepLinks
import com.becalm.android.ui.theme.BecalmTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
    public lateinit var productSessionTracker: ProductSessionTracker

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

        handleIncomingIntent(intent)

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
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val route = intent?.let(AppDeepLinks::routeFrom)
        if (route != null) {
            val entrySource = intent.getStringExtra(ReminderBroadcastReceiver.EXTRA_ENTRY_SOURCE)
            productSessionTracker.markNextEntrySource(
                if (entrySource == ProductSessionTracker.ENTRY_NOTIFICATION) {
                    ProductSessionTracker.ENTRY_NOTIFICATION
                } else {
                    ProductSessionTracker.ENTRY_DEEP_LINK
                },
            )
            pendingDeepLinkRoute.value = route
            if (entrySource == ProductSessionTracker.ENTRY_NOTIFICATION) {
                productAnalytics.track(
                    ProductAnalyticsNames.COMMITMENT_NOTIFICATION_OPENED,
                    properties = mapOf(
                        "commitment_id" to intent.data?.lastPathSegment.orEmpty(),
                        "commitment_state_at_open" to intent.getStringExtra(
                            ReminderBroadcastReceiver.EXTRA_COMMITMENT_STATE_AT_OPEN,
                        ).orEmpty(),
                        "available_actions_at_open" to (
                            intent.getStringArrayListExtra(
                                ReminderBroadcastReceiver.EXTRA_AVAILABLE_ACTIONS_AT_OPEN,
                            ) ?: emptyList<String>()
                            ),
                    ),
                )
            }
        }
    }
}
