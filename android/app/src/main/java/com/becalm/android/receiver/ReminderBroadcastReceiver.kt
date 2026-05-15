package com.becalm.android.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.becalm.android.MainActivity
import com.becalm.android.R
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.domain.commitment.CommitmentState
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

public data class ReminderNotificationSpec(
    val commitmentId: String,
    val notificationInstanceId: String,
    val channelId: String,
    val deepLinkUri: String,
    val title: String,
    val body: String,
)

/**
 * Receives alarm broadcasts from [com.becalm.android.domain.reminder.ReminderScheduler]
 * and posts a `commitment_due_soon` notification — after re-querying Room to confirm the
 * commitment is still actionable (CMT-008).
 *
 * ## Silent-drop (CMT-008 MUST-NOT)
 * The receiver re-queries [CommitmentDao.findById] on each broadcast and drops
 * (no notification posted) when:
 *  - the row is missing (deleted / soft-deleted), or
 *  - `action_state ∈ (completed, cancelled)`.
 *
 * This prevents ghost reminders after the user has already acted on the commitment
 * since the alarm was set.
 *
 * ## PIPA
 * The notification body only surfaces the commitment title (already user-visible in
 * the app UI). Quote, person, and counterparty PII are not passed through the
 * notification. The tap deep link carries only the commitment ID.
 *
 * ## Channel lifecycle
 * The `commitment_due_soon` channel is registered in
 * [com.becalm.android.BecalmApplication.onCreate] at process start.
 */
@AndroidEntryPoint
public open class ReminderBroadcastReceiver : BroadcastReceiver() {

    @Inject
    public lateinit var commitmentDao: CommitmentDao

    @Inject
    public lateinit var logger: Logger

    @Inject
    public lateinit var productAnalytics: ProductAnalyticsClient

    @Inject
    @ApplicationScope
    public lateinit var applicationScope: CoroutineScope

    @Inject
    @IoDispatcher
    public lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        val commitmentId = intent.getStringExtra(EXTRA_COMMITMENT_ID) ?: return
        val scheduledUserId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()

        // Finding 4 (security-auditor): POST_NOTIFICATIONS is a runtime permission on API 33+.
        // Skip notification silently rather than crash; log WARN with redacted ID for diagnostics.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                logger.w(
                    TAG,
                    "POST_NOTIFICATIONS not granted — notify skipped for " +
                        "commitmentId_hash=${redact(commitmentId)}",
                )
                return
            }
        }

        // Extend the receiver's lifetime up to 10 seconds (Android platform cap) so we
        // can re-query Room on IO and post the notification on the main thread.
        val pending = goAsync()
        applicationScope.launch(ioDispatcher) {
            try {
                handle(context, commitmentId, scheduledUserId)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Business logic extracted for unit testability. Suspend so tests can drive it
     * inside `runTest { }` without a live [goAsync]/`PendingResult` pair — the real
     * `onReceive` wires it to goAsync().
     *
     * Re-queries Room for the live [commitmentId]; silently drops the broadcast when
     * the row is missing or in a terminal state (CMT-008). Otherwise posts the
     * `commitment_due_soon` notification.
     */
    internal suspend fun handle(
        context: Context,
        commitmentId: String,
        scheduledUserId: String,
    ) {
        if (scheduledUserId.isBlank()) {
            // An alarm without a captured owner predates the user-scoping fix
            // (data-model.yml:476). Silently drop rather than query unscoped —
            // a stale alarm is cheap; surfacing another account's commitment is
            // not.
            logger.w(
                TAG,
                "silent drop: alarm missing scheduled user_id for " +
                    "commitmentId_hash=${redact(commitmentId)}",
            )
            return
        }
        val entity = commitmentDao.findByIdForUser(scheduledUserId, commitmentId)
        if (entity == null) {
            logger.d(
                TAG,
                "silent drop: entity missing for commitmentId_hash=${redact(commitmentId)}",
            )
            return
        }
        val state = CommitmentState.fromWire(entity.actionState)
        if (state in TERMINAL_STATES) {
            logger.d(
                TAG,
                "silent drop: state=$state for commitmentId_hash=${redact(commitmentId)}",
            )
            return
        }
        if (entity.dueAt == null) {
            logger.d(
                TAG,
                "silent drop: due_at missing for commitmentId_hash=${redact(commitmentId)}",
            )
            return
        }

        postNotification(
            context = context,
            spec = buildNotificationSpec(
                context = context,
                commitmentId = commitmentId,
                title = entity.title,
                direction = requireNotNull(entity.direction) { "Reminder notifications require action direction" },
            ),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Open + internal so MockK spy-tests can override it to avoid booting the
     * real Resources / NotificationCompat path — the [handle] contract is
     * what CMT-008 specifies; the notification composition itself is covered
     * by instrumented tests when available.
     */
    @SuppressLint("MissingPermission")
    internal open fun postNotification(
        context: Context,
        spec: ReminderNotificationSpec,
    ) {
        if (!canPostNotifications(context)) return
        val notificationId = commitmentIdToNotificationId(spec.commitmentId)

        val deepLink = Uri.parse(spec.deepLinkUri)
        val tapIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
            setClass(context, MainActivity::class.java)
            putExtra(EXTRA_COMMITMENT_ID, spec.commitmentId)
            putExtra(EXTRA_NOTIFICATION_INSTANCE_ID, spec.notificationInstanceId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.COMMITMENT_NOTIFICATION_POSTED,
                occurredAt = Clock.System.now(),
                properties = mapOf(
                    "commitment_id" to spec.commitmentId,
                    "notification_instance_id" to spec.notificationInstanceId,
                    "channel_id" to spec.channelId,
                ),
            ),
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        /** Intent extra key carrying the opaque commitment identifier. */
        public const val EXTRA_COMMITMENT_ID: String = "commitment_id"
        public const val EXTRA_NOTIFICATION_INSTANCE_ID: String = "notification_instance_id"

        /**
         * Intent extra key carrying the user id that owned the commitment at
         * schedule time. Captured by [com.becalm.android.domain.reminder.ReminderScheduler]
         * when the alarm is armed; the receiver passes it to the user-scoped
         * `findByIdForUser` DAO query to rule out cross-account leaks
         * (data-model.yml:476). A missing / blank value causes a silent drop.
         */
        public const val EXTRA_USER_ID: String = "user_id"

        private fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Notification channel ID used by the `commitment_due_soon` channel. Registered
         * in [com.becalm.android.BecalmApplication.onCreate]. Also referenced by
         * `NotificationManager.IMPORTANCE_HIGH` in the channel definition.
         */
        public const val CHANNEL_ID: String = "commitment_due_soon"

        private const val TAG = "ReminderBroadcastReceiver"

        private val TERMINAL_STATES =
            setOf(CommitmentState.COMPLETED, CommitmentState.CANCELLED)

        /**
         * Derives a stable notification ID from [commitmentId] matching the request code used
         * in [com.becalm.android.domain.reminder.ReminderScheduler.commitmentIdToRequestCode].
         */
        private fun commitmentIdToNotificationId(commitmentId: String): Int =
            try {
                val uuid = UUID.fromString(commitmentId)
                (uuid.mostSignificantBits ushr 33).toInt()
            } catch (_: IllegalArgumentException) {
                commitmentId.hashCode() and 0x7FFFFFFF
            }

        /** Pure builder for CMT-008 receiver unit tests. */
        public fun buildNotificationSpec(
            context: Context,
            commitmentId: String,
            title: String,
            direction: String,
        ): ReminderNotificationSpec {
            val bodyResId = when (direction) {
                "give" -> R.string.commitment_alarm_body_give_fmt
                else -> R.string.commitment_alarm_body_take_fmt
            }
            return ReminderNotificationSpec(
                commitmentId = commitmentId,
                notificationInstanceId = UUID.randomUUID().toString(),
                channelId = CHANNEL_ID,
                deepLinkUri = "becalm://commitments/$commitmentId",
                title = context.getString(R.string.commitment_alarm_title),
                body = context.getString(bodyResId, title),
            )
        }
    }
}
