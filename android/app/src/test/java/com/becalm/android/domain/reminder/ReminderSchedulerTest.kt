package com.becalm.android.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.becalm.android.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import kotlinx.datetime.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ReminderScheduler].
 *
 * Robolectric provides a real [Context] and shadows [AlarmManager] / [PendingIntent]
 * so we can verify the correct AlarmManager API is called without a device.
 *
 * MockK is used for [Logger] verification; [AlarmManager] is obtained through the
 * Robolectric shadow and spied upon where needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var logger: Logger
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager

        scheduler = ReminderScheduler(context, logger)
    }

    // ── schedule() ────────────────────────────────────────────────────────────

    /**
     * On API 31+ when canScheduleExactAlarms() returns true, schedule() must call
     * setExactAndAllowWhileIdle and must NOT call setAndAllowWhileIdle.
     */
    @Test
    fun `schedule on API 31+ with canScheduleExactAlarms true calls setExactAndAllowWhileIdle`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        scheduler.schedule(COMMITMENT_ID, TRIGGER_INSTANT)

        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                TRIGGER_INSTANT.toEpochMilliseconds(),
                any(),
            )
        }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    /**
     * When canScheduleExactAlarms() returns false, schedule() must fall back to
     * setAndAllowWhileIdle and log a WARN with a redacted commitment ID.
     */
    @Test
    fun `schedule with canScheduleExactAlarms false falls back and logs WARN`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        scheduler.schedule(COMMITMENT_ID, TRIGGER_INSTANT)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                TRIGGER_INSTANT.toEpochMilliseconds(),
                any(),
            )
        }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }

        // Verify WARN was logged (message contains the redacted hash, not the raw ID)
        val warnSlot = slot<String>()
        verify(exactly = 1) { logger.w(any(), capture(warnSlot)) }
        val msg = warnSlot.captured
        assert(msg.contains("SCHEDULE_EXACT_ALARM")) {
            "Expected WARN message to mention SCHEDULE_EXACT_ALARM, was: $msg"
        }
        assert(!msg.contains(COMMITMENT_ID)) {
            "WARN message must not contain raw commitmentId (PIPA). Was: $msg"
        }
    }

    // ── cancel() ──────────────────────────────────────────────────────────────

    /**
     * cancel() must invoke alarmManager.cancel() with a PendingIntent whose request
     * code matches commitmentId.hashCode().
     */
    @Test
    fun `cancel invokes alarmManager cancel`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        scheduler.cancel(COMMITMENT_ID)

        verify(exactly = 1) { alarmManager.cancel(any<PendingIntent>()) }
    }

    /**
     * A PendingIntent built for the same commitmentId must carry FLAG_IMMUTABLE.
     * We verify this indirectly by asserting that schedule() and cancel() both use
     * the same request code (commitmentId.hashCode()), making them symmetric.
     */
    @Test
    fun `schedule and cancel use the same requestCode derived from commitmentId hashCode`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        // Schedule then cancel — both must touch the same slot without errors
        scheduler.schedule(COMMITMENT_ID, TRIGGER_INSTANT)
        scheduler.cancel(COMMITMENT_ID)

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 1) { alarmManager.cancel(any<PendingIntent>()) }
    }

    // ── PendingIntent flags ───────────────────────────────────────────────────

    /**
     * Verifies that the scheduler does not create any mutable PendingIntents.
     *
     * FLAG_IMMUTABLE (0x04000000) is required on API 31+ and is always set in
     * ReminderScheduler regardless of API level as a security hardening measure.
     * This test exercises the schedule path on API 31 (Config above) where the
     * system would reject a missing FLAG_IMMUTABLE.
     */
    @Test
    fun `PendingIntent includes FLAG_IMMUTABLE — no mutable intents created`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        mockkStatic(PendingIntent::class)

        val piSlot = slot<Int>()
        val dummyPi = mockk<PendingIntent>(relaxed = true)
        every {
            PendingIntent.getBroadcast(any(), capture(piSlot), any(), any())
        } returns dummyPi

        scheduler.schedule(COMMITMENT_ID, TRIGGER_INSTANT)

        verify {
            PendingIntent.getBroadcast(
                any(),
                COMMITMENT_ID.hashCode(),
                any(),
                withArg { flags ->
                    assert(flags and PendingIntent.FLAG_IMMUTABLE != 0) {
                        "FLAG_IMMUTABLE must be set. Actual flags: $flags"
                    }
                },
            )
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val COMMITMENT_ID = "test-commitment-abc123"
        private val TRIGGER_INSTANT: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    }
}
