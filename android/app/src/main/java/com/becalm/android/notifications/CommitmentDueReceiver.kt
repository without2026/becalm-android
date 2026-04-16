package com.becalm.android.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

// spec: CMT-008 — BroadcastReceiver for commitment due-date alarms
// Registered in AndroidManifest with android:exported="false"

class CommitmentDueReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_COMMITMENT_ID = "commitment_id"
        const val EXTRA_COMMITMENT_TITLE = "commitment_title"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val commitmentId = intent.getStringExtra(EXTRA_COMMITMENT_ID) ?: return
        val title = intent.getStringExtra(EXTRA_COMMITMENT_TITLE) ?: "Commitment due today"

        val nm = context.getSystemService<NotificationManager>() ?: return
        val notification = NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_COMMITMENT_DUE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Commitment due")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // spec: CMT-008 — use commitmentId hashCode as stable notification ID
        nm.notify(commitmentId.hashCode(), notification)
    }
}
