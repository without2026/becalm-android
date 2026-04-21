package com.becalm.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
     * Holds the most recent incoming commitment deep-link id so the Compose root can
     * navigate once the nav graph is initialized. Cleared by the composable after
     * consumption.
     */
    private val pendingDeepLinkId = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch-time splash theme for the real app theme BEFORE super.onCreate
        // so the first Compose frame renders against Theme.Becalm, not Theme.Becalm.Splash.
        setTheme(R.style.Theme_Becalm)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingDeepLinkId.value = intent?.let(::parseCommitmentDeepLink)

        setContent {
            BecalmTheme {
                var deepLinkId by remember { pendingDeepLinkId }
                BecalmApp(
                    pendingCommitmentDeepLinkId = deepLinkId,
                    onDeepLinkConsumed = { deepLinkId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseCommitmentDeepLink(intent)?.let { pendingDeepLinkId.value = it }
    }

    /**
     * Returns the commitment id embedded in `becalm://commitments/{id}`, or `null` for
     * any other intent. The last non-blank path segment is treated as the id so a
     * trailing slash is tolerated.
     */
    private fun parseCommitmentDeepLink(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        if (data.scheme != "becalm" || data.host != "commitments") return null
        return data.pathSegments?.lastOrNull { it.isNotBlank() }
    }
}
