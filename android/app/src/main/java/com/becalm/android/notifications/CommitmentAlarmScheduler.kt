package com.becalm.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.becalm.android.data.local.entities.Commitment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// spec: CMT-008 — schedule exact alarm at 09:00 local on commitment due_date

class CommitmentAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? = context.getSystemService()

    // spec: CMT-008 — schedule 09:00 local alarm on commitment.due_date
    fun schedule(commitment: Commitment) {
        val dueDate = commitment.dueDate ?: return
        val alarmTimeMs = parse09AmLocal(dueDate) ?: return

        // spec: CMT-008 — API 31+ check canScheduleExactAlarms() before setExactAndAllowWhileIdle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager?.canScheduleExactAlarms() != true) return
        }

        val intent = Intent(context, CommitmentDueReceiver::class.java).apply {
            putExtra(CommitmentDueReceiver.EXTRA_COMMITMENT_ID, commitment.id)
            putExtra(CommitmentDueReceiver.EXTRA_COMMITMENT_TITLE, commitment.title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            commitment.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTimeMs, pendingIntent)
    }

    fun cancel(commitment: Commitment) {
        val intent = Intent(context, CommitmentDueReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            commitment.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager?.cancel(pendingIntent)
    }

    // Parse ISO date "yyyy-MM-dd" and return epoch millis at 09:00 local time
    internal fun parse09AmLocal(dateStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val date = sdf.parse(dateStr) ?: return null
            val cal = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
