package com.becalm.android.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.ui.navigation.AppDeepLinks
import kotlinx.coroutines.flow.first

public data class MatchingRequiredNotificationSpec(
    val channelId: String,
    val title: String,
    val body: String,
    val unmatchedCount: Int,
)

public object MatchingRequiredNotifier {
    public const val CHANNEL_ID: String = "person_matching_required"
    private const val NOTIFICATION_ID: Int = 4929

    public suspend fun update(
        context: Context,
        userPrefsStore: UserPrefsStore,
        unmatchedCount: Int,
        logger: Logger,
    ) {
        if (unmatchedCount <= 0 || !userPrefsStore.observeNotificationsEnabled().first()) {
            cancel(context)
            return
        }
        if (!canPostNotifications(context)) {
            logger.w(TAG, "POST_NOTIFICATIONS not granted — matching notification skipped")
            return
        }

        ensureChannel(context)
        val spec = buildNotificationSpec(context, unmatchedCount)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            logger.w(TAG, "matching notification failed: ${error::class.simpleName}")
        }
    }

    public fun buildNotificationSpec(
        context: Context,
        unmatchedCount: Int,
    ): MatchingRequiredNotificationSpec =
        MatchingRequiredNotificationSpec(
            channelId = CHANNEL_ID,
            title = context.getString(R.string.person_matching_required_notification_title),
            body = context.resources.getQuantityString(
                R.plurals.person_matching_required_notification_body,
                unmatchedCount,
                unmatchedCount,
            ),
            unmatchedCount = unmatchedCount,
        )

    public fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.person_matching_required_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.person_matching_required_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(AppDeepLinks.PERSONS_UNASSIGNED_URI)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private const val TAG = "MatchingRequiredNotifier"
}
