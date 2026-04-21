package com.becalm.android.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.receiver.ReminderBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant

/**
 * Schedules and cancels exact-alarm reminders for commitments.
 *
 * Spec CMT-005: alarm must fire at `due_at − 1h` via
 * [AlarmManager.setExactAndAllowWhileIdle] with [PendingIntent.FLAG_IMMUTABLE]. The
 * scheduler silently skips when `dueAt == null` or when the computed trigger has
 * already passed, so callers can pass the raw `due_at` Instant without a pre-check.
 *
 * On API 31+ without SCHEDULE_EXACT_ALARM the scheduler degrades to
 * [AlarmManager.setWindow] (10-minute window) and logs a WARN.
 *
 * ## PIPA note
 * Commitment content is never passed to the alarm or notification payloads.
 * Only the commitment ID (redacted in logs) is carried as an intent extra.
 *
 * @param context Application context for system service access and intent construction.
 * @param clock   Injected [Clock] so tests can freeze "now" deterministically.
 * @param logger  Structured log sink.
 */
@Singleton
public class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val logger: Logger,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedules an exact-alarm reminder for [commitmentId] at `dueAt − 1h` (CMT-005).
     *
     * Skips (no-op + log) when:
     *  - [dueAt] is `null` (commitment has no deadline), or
     *  - the computed trigger already lies in the past.
     *
     * If the app does not hold SCHEDULE_EXACT_ALARM on API 31+, the scheduler falls
     * back to [AlarmManager.setWindow] with a 10-minute window and logs a WARN.
     *
     * @param commitmentId Opaque commitment identifier (redacted in logs).
     * @param dueAt        Commitment deadline. The actual alarm fires 1 hour before this.
     */
    public fun schedule(commitmentId: String, dueAt: Instant?) {
        if (dueAt == null) {
            logger.d(TAG, "schedule skipped: dueAt null for %08x".format(commitmentId.hashCode()))
            return
        }
        val triggerAt = dueAt.minus(REMINDER_LEAD_TIME)
        val now = clock.nowInstant()
        if (triggerAt <= now) {
            logger.d(
                TAG,
                "schedule skipped: triggerAt=$triggerAt already past now=$now for " +
                    "commitment %08x".format(commitmentId.hashCode()),
            )
            return
        }

        val requestCode = commitmentIdToRequestCode(commitmentId)
        val pi = buildPendingIntent(commitmentId, requestCode)
        val triggerMs = triggerAt.toEpochMilliseconds()

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            logger.d(TAG, "Exact alarm scheduled for commitment %08x".format(commitmentId.hashCode()))
        } else {
            // Degraded path: 10-minute window around the intended trigger.
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerMs,
                INEXACT_WINDOW_MS,
                pi,
            )
            logger.w(
                TAG,
                "SCHEDULE_EXACT_ALARM not granted — falling back to inexact window alarm for " +
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

        /** CMT-005: alarm fires 1 hour before `due_at`. */
        private val REMINDER_LEAD_TIME = 1.hours

        /** 10-minute window used when the exact-alarm permission is missing. */
        private const val INEXACT_WINDOW_MS: Long = 10 * 60 * 1000L

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
