package com.becalm.android.data.auth

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.becalm.android.core.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Restart contract invoked by [com.becalm.android.data.repository.AuthRepositoryImpl]
 * when an account swap is detected at sign-in time (R4 fix).
 *
 * ## Why a real restart is required
 * [com.becalm.android.data.local.db.BeCalmDatabaseProvider] swaps the underlying
 * SQLite file when a different user signs in, but `@Singleton` repositories
 * (`CommitmentRepositoryImpl`, `PersonEnrichmentRepository`, and others) captured
 * their DAO / [com.becalm.android.data.local.db.BeCalmDatabase] references when
 * the previous user's session first landed on them. Reusing those references after
 * a swap reads from a closed SQLite handle (best case: `IllegalStateException`;
 * worst case: stale data during the split-second before Room notices). Tearing the
 * process down and re-launching guarantees Hilt rebuilds the whole graph against
 * the new user's DB.
 *
 * ## Contract
 * Implementations MUST:
 *  - schedule a re-launch of the app's main Activity before the process dies, so
 *    the user lands back in the UI instead of on their home screen;
 *  - tear the process down synchronously (never return from [restart]);
 *  - be idempotent on repeat calls (alarm scheduling and `exitProcess` are both safe).
 */
public interface ProcessRestarter {
    /** See [ProcessRestarter] class-level KDoc — never returns. */
    public fun restart(): Nothing
}

/**
 * Production [ProcessRestarter] that schedules a near-immediate re-launch of the
 * app's main Activity via [AlarmManager] and terminates the current process.
 *
 * Uses `ELAPSED_REALTIME` rather than wall-clock alarms so a device clock change
 * cannot stall the restart. The relaunch delay is deliberately tiny (100 ms) —
 * enough for the alarm to fire after `exitProcess`, but short enough that the
 * user perceives a flash rather than an outage.
 */
@Singleton
public class ProcessRestarterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : ProcessRestarter {

    override fun restart(): Nothing {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
        if (launch != null) {
            val pending = PendingIntent.getActivity(
                context,
                RESTART_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
                pending,
            )
        }
        logger.i(TAG, "process restart requested (account swap detected)")
        exitProcess(0)
    }

    private companion object {
        private const val TAG: String = "ProcessRestarter"
        private const val RESTART_DELAY_MILLIS: Long = 100L
        private const val RESTART_REQUEST_CODE: Int = 1_729
    }
}
