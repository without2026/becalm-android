package com.becalm.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.becalm.android.ui.navigation.AppDeepLinks
import com.becalm.android.ui.theme.BecalmTheme
import dagger.hilt.android.AndroidEntryPoint

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
        enableEdgeToEdge()

        pendingDeepLinkRoute.value = intent?.let(AppDeepLinks::routeFrom)

        setContent {
            BecalmTheme {
                var deepLinkRoute by remember { pendingDeepLinkRoute }
                BecalmApp(
                    pendingDeepLinkRoute = deepLinkRoute,
                    onDeepLinkConsumed = { deepLinkRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppDeepLinks.routeFrom(intent)?.let { pendingDeepLinkRoute.value = it }
    }
}
