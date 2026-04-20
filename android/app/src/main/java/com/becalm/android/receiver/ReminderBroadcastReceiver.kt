package com.becalm.android.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.becalm.android.MainActivity
import com.becalm.android.core.util.redact
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

/**
 * Receives alarm broadcasts from [com.becalm.android.domain.reminder.ReminderScheduler]
 * and posts a notification to the user.
 *
 * ## PIPA
 * Notification content is intentionally generic — the body never contains commitment
 * text, names, or any personally identifiable information. Only the commitment ID is
 * carried in the tap deep-link intent extra so MainActivity can open the correct item.
 *
 * ## Channel lifecycle
 * [NotificationManager.createNotificationChannel] is idempotent on API 26+; calling it
 * on every broadcast is safe and avoids a separate channel-initialisation step.
 */
@AndroidEntryPoint
public class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val commitmentId = intent.getStringExtra(EXTRA_COMMITMENT_ID) ?: return

        // Finding 4 (security-auditor): POST_NOTIFICATIONS is a runtime permission on API 33+.
        // Skip notification silently rather than crash; log WARN with redacted ID for diagnostics.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — notify skipped for commitmentId_hash=${redact(commitmentId)}")
                return
            }
        }

        ensureChannelCreated(context)

        val notificationId = commitmentIdToNotificationId(commitmentId)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COMMITMENT_ID, commitmentId)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_BODY)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureChannelCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        /** Intent extra key carrying the opaque commitment identifier. */
        public const val EXTRA_COMMITMENT_ID: String = "commitment_id"

        private const val TAG = "ReminderBroadcastReceiver"
        private const val CHANNEL_ID = "reminders"
        private const val CHANNEL_NAME = "리마인더"
        private const val NOTIFICATION_TITLE = "BeCalm 약속 알림"
        private const val NOTIFICATION_BODY = "확인할 약속이 있습니다."

        /**
         * Derives a stable notification ID from [commitmentId] matching the request code used
         * in [com.becalm.android.domain.reminder.ReminderScheduler.commitmentIdToRequestCode].
         *
         * Production IDs are UUIDs; use top 31 bits of the most-significant long.
         * Non-UUID IDs fall back to [String.hashCode] masked to 31 bits.
         */
        private fun commitmentIdToNotificationId(commitmentId: String): Int =
            try {
                val uuid = UUID.fromString(commitmentId)
                (uuid.mostSignificantBits ushr 33).toInt()
            } catch (_: IllegalArgumentException) {
                commitmentId.hashCode() and 0x7FFFFFFF
            }
    }
}
