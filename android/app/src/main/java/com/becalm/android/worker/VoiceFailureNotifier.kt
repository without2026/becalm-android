package com.becalm.android.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.becalm.android.R
import javax.inject.Inject

public data class VoiceFailureNotificationSpec(
    val channelId: String,
    val title: String,
    val body: String,
    val rawEventId: String,
)

public class VoiceFailureNotifier @Inject constructor() {

    @SuppressLint("MissingPermission")
    fun notifyFailure(
        context: Context,
        rawEventId: String,
        eventTitle: String?,
        reasonCode: String?,
    ) {
        if (!canPostNotifications(context)) return
        val spec = buildNotificationSpec(
            context = context,
            rawEventId = rawEventId,
            eventTitle = eventTitle,
            reasonCode = reasonCode,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_becalm)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(), notification)
    }

    fun buildNotificationSpec(
        context: Context,
        rawEventId: String,
        eventTitle: String?,
        reasonCode: String?,
    ): VoiceFailureNotificationSpec {
        val title = context.getString(R.string.voice_failure_notification_title)
        val body = when (reasonCode) {
            "output_truncated" -> context.getString(R.string.voice_failure_notification_body_output_truncated)
            else -> context.getString(R.string.voice_failure_notification_body_generic)
        }
        return VoiceFailureNotificationSpec(
            channelId = CHANNEL_ID,
            title = title,
            body = body,
            rawEventId = rawEventId,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "voice_processing_failed"
        const val NOTIFICATION_ID: Int = 0x0BEECA1

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.voice_failure_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.voice_failure_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        fun notificationId(): Int = NOTIFICATION_ID

        private fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
