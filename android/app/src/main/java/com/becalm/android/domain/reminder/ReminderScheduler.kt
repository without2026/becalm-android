package com.becalm.android.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.becalm.android.core.util.Logger
import com.becalm.android.receiver.ReminderBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Schedules and cancels exact-alarm reminders for commitments.
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle] when the SCHEDULE_EXACT_ALARM
 * permission is granted (API 31+); falls back to [AlarmManager.setAndAllowWhileIdle]
 * otherwise, logging a WARN so operators are aware of reduced accuracy.
 *
 * ## PIPA note
 * Commitment content is never passed to the alarm or notification payloads.
 * Only the commitment ID (redacted in logs) is carried as an intent extra.
 *
 * @param context Application context for system service access and intent construction.
 * @param logger Structured log sink.
 */
@Singleton
public class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedules an exact-alarm reminder for the commitment identified by [commitmentId]
     * to fire at [triggerAt].
     *
     * If the app does not hold SCHEDULE_EXACT_ALARM on API 31+, the scheduler falls
     * back to [AlarmManager.setAndAllowWhileIdle] and logs a WARN.
     *
     * @param commitmentId Opaque commitment identifier (redacted in logs).
     * @param triggerAt    Wall-clock instant at which the alarm should fire.
     */
    public fun schedule(commitmentId: String, triggerAt: Instant) {
        val requestCode = commitmentIdToRequestCode(commitmentId)
        val pi = buildPendingIntent(commitmentId, requestCode)
        // AlarmManager accepts a Long epoch-ms at the boundary; we convert here so that
        // ReminderScheduler's own signature stays on kotlinx.datetime.Instant.
        val triggerMs = triggerAt.toEpochMilliseconds()

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            logger.d(TAG, "Exact alarm scheduled for commitment %08x".format(commitmentId.hashCode()))
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            logger.w(
                TAG,
                "SCHEDULE_EXACT_ALARM not granted — falling back to inexact alarm for " +
                    "commitment %08x".format(commitmentId.hashCode()),
            )
        }
    }

    /**
     * Cancels a previously scheduled reminder for [commitmentId].
     *
     * Reconstructs the [PendingIntent] with identical flags and request code so
     * [AlarmManager.cancel] can match it correctly.
     *
     * @param commitmentId Opaque commitment identifier whose alarm should be cancelled.
     */
    public fun cancel(commitmentId: String) {
        val requestCode = commitmentIdToRequestCode(commitmentId)
        val pi = buildPendingIntent(commitmentId, requestCode)
        alarmManager.cancel(pi)
        pi.cancel()
        logger.d(TAG, "Alarm cancelled for commitment %08x".format(commitmentId.hashCode()))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPendingIntent(commitmentId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_COMMITMENT_ID, commitmentId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "ReminderScheduler"

        /**
         * Derives a stable 31-bit int from [commitmentId] for use as a [PendingIntent]
         * request code.
         *
         * Production commitment IDs are UUIDs; we use the top 31 bits of the most-significant
         * long (always positive, low collision probability). For non-UUID IDs — e.g. in tests —
         * we fall back to [String.hashCode] masked to 31 bits, which is acceptable since only
         * production IDs are UUIDs.
         */
        fun commitmentIdToRequestCode(commitmentId: String): Int =
            try {
                val uuid = UUID.fromString(commitmentId)
                (uuid.mostSignificantBits ushr 33).toInt()
            } catch (_: IllegalArgumentException) {
                commitmentId.hashCode() and 0x7FFFFFFF
            }
    }
}
