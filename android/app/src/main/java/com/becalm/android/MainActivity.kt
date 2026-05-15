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
import androidx.lifecycle.lifecycleScope
import com.becalm.android.core.analytics.ProductAnalyticsContext
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.receiver.ReminderBroadcastReceiver
import com.becalm.android.ui.navigation.AppDeepLinks
import com.becalm.android.ui.theme.BecalmTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    @Inject
    public lateinit var productAnalyticsContext: ProductAnalyticsContext

    @Inject
    public lateinit var observability: ObservabilityClient

    @Inject
    public lateinit var commitmentDao: CommitmentDao

    @Inject
    public lateinit var userPrefsStore: UserPrefsStore

    @Inject
    @IoDispatcher
    public lateinit var ioDispatcher: CoroutineDispatcher

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
        handleDebugCrashDeepLink(intent)?.let { return null }
        val route = AppDeepLinks.routeFrom(intent) ?: return null
        if (route.startsWith("commitments/")) {
            trackCommitmentNotificationOpened(intent, route)
        }
        return route
    }

    private fun trackCommitmentNotificationOpened(intent: Intent, route: String) {
        val now = Clock.System.now()
        val commitmentId = intent.getStringExtra(ReminderBroadcastReceiver.EXTRA_COMMITMENT_ID)
            ?: intent.data?.pathSegments?.lastOrNull { it.isNotBlank() }
            ?: route.removePrefix("commitments/")
        val notificationInstanceId = intent.getStringExtra(ReminderBroadcastReceiver.EXTRA_NOTIFICATION_INSTANCE_ID)
        productAnalyticsContext.markNotificationOpened(
            notificationInstanceId = notificationInstanceId,
            commitmentId = commitmentId,
            openedAt = now,
        )
        lifecycleScope.launch {
            val properties = withContext(ioDispatcher) {
                val userId = userPrefsStore.observeCurrentUserId().first()
                val entity = userId?.let { commitmentDao.findByIdForUser(it, commitmentId) }
                val state = entity?.let { CommitmentState.fromWire(it.actionState) }
                val availableActions = availableActionsAtOpen(state, entity?.deletedAt != null)
                mapOf(
                    "route" to route,
                    "entry_source" to ProductAnalyticsContext.ENTRY_SOURCE_NOTIFICATION_OPEN,
                    "commitment_id" to commitmentId,
                    "notification_instance_id" to notificationInstanceId,
                    "commitment_state_at_open" to (state?.wireValue ?: "unknown"),
                    "state_mix_at_open" to (state?.wireValue ?: "unknown"),
                    "available_actions_at_open" to availableActions,
                    "eligible_action_count" to availableActions.size,
                    "item_type" to (entity?.itemType ?: "unknown"),
                    "source_type" to (entity?.sourceType ?: "unknown"),
                    "due_bucket_at_open" to dueBucket(entity?.dueAt, now),
                ).filterValues { it != null }
            }
            productAnalytics.track(
                ProductAnalyticsEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventName = ProductAnalyticsEvents.COMMITMENT_NOTIFICATION_OPENED,
                    occurredAt = now,
                    properties = properties,
                ),
            )
        }
    }

    private fun handleDebugCrashDeepLink(intent: Intent): Unit? {
        if (!BuildConfig.DEBUG || intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "becalm" || uri.host != "debug" || uri.pathSegments != listOf("fake_crash")) return null
        val mode = uri.getQueryParameter("mode") ?: "nonfatal"
        val error = RuntimeException("qa_fake_crash:$mode")
        observability.addBreadcrumb("qa", "fake_crash_requested", mapOf("mode" to mode))
        if (mode == "fatal") {
            throw error
        }
        observability.captureException(error, mapOf("mode" to mode, "source" to "debug_deeplink"))
        return Unit
    }

    private companion object {
        private fun availableActionsAtOpen(state: CommitmentState?, isDeleted: Boolean): List<String> {
            if (state == null || isDeleted) return emptyList()
            val actions = when (state) {
                CommitmentState.PENDING -> listOf("remind", "follow_up", "complete", "cancel")
                CommitmentState.REMINDED -> listOf("follow_up", "complete", "cancel")
                CommitmentState.FOLLOWED_UP, CommitmentState.OVERDUE -> listOf("complete", "cancel")
                CommitmentState.COMPLETED, CommitmentState.CANCELLED -> emptyList()
            }
            return if (state != CommitmentState.CANCELLED) actions + "edit" else actions
        }

        private fun dueBucket(dueAt: kotlinx.datetime.Instant?, now: kotlinx.datetime.Instant): String =
            when {
                dueAt == null -> "none"
                dueAt < now -> "past_due"
                dueAt.toEpochMilliseconds() - now.toEpochMilliseconds() <= 86_400_000L -> "next_24h"
                else -> "future"
            }
    }
}
