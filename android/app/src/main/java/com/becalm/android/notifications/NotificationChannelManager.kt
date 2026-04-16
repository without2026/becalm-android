package com.becalm.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

// spec: CMT-008 — notification channels; called from BeCalmApplication.onCreate()

object NotificationChannelManager {

    const val CHANNEL_COMMITMENT_DUE = "commitment_due"

    // spec: CMT-008 — create notification channels on app start
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_COMMITMENT_DUE,
            "Commitment Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders for commitments with a due date"
        }
        nm.createNotificationChannel(channel)
    }
}
