package com.becalm.android.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ReminderScheduler] after the CMT-005 realignment.
 *
 * The scheduler now owns the `dueAt − 1h` calculation and the null / past-trigger
 * gates; these tests pin the new contract:
 *  - `dueAt == null` → no alarm is scheduled.
 *  - `dueAt − 1h ≤ now` → no alarm is scheduled.
 *  - `dueAt − 1h > now` → `setExactAndAllowWhileIdle` fires with the correct
 *    trigger epoch-millis and an immutable PendingIntent.
 *
 * Robolectric provides a real [Context] shell; [AlarmManager] is fully mocked so we can
 * verify the exact API calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var logger: Logger
    private lateinit var clock: Clock
    private lateinit var userPrefsStore: com.becalm.android.data.local.datastore.UserPrefsStore
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        clock = mockk(relaxed = true)
        userPrefsStore = mockk(relaxed = true)

        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { clock.nowInstant() } returns NOW
        every { userPrefsStore.observeCurrentUserId() } returns
            kotlinx.coroutines.flow.flowOf("user-1")

        scheduler = ReminderScheduler(context, clock, userPrefsStore, logger)
    }

    // ── schedule() gate cases ─────────────────────────────────────────────────

    /** Case A: dueAt is null → no alarm is scheduled. */
    @Test
    fun `schedule with dueAt null is a no-op`() {
        scheduler.schedule(COMMITMENT_ID, dueAt = null)

        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { alarmManager.setWindow(any(), any(), any(), any<PendingIntent>()) }
    }

    /** Case B: triggerAt (dueAt - 1h) is in the past → no alarm. */
    @Test
    fun `schedule with dueAt in the past is a no-op`() {
        val pastDueAt = NOW.minus(1.hours) // trigger would be NOW - 2h, clearly past

        scheduler.schedule(COMMITMENT_ID, dueAt = pastDueAt)

        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { alarmManager.setWindow(any(), any(), any(), any<PendingIntent>()) }
    }

    /** Case B': triggerAt exactly equals now → still skipped (≤ not <). */
    @Test
    fun `schedule with dueAt exactly one hour from now is a no-op`() {
        val edgeDueAt = NOW.plus(1.hours)

        scheduler.schedule(COMMITMENT_ID, dueAt = edgeDueAt)

        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    // ── schedule() happy path ─────────────────────────────────────────────────

    /** Case C: dueAt is 2h in the future → setExactAndAllowWhileIdle fires. */
    @Test
    fun `schedule with dueAt in the future fires exact alarm at dueAt minus 1h`() {
        val dueAt = NOW.plus(2.hours)
        val expectedTriggerMs = dueAt.minus(1.hours).toEpochMilliseconds()

        val triggerSlot = slot<Long>()
        every {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                capture(triggerSlot),
                any(),
            )
        } returns Unit

        scheduler.schedule(COMMITMENT_ID, dueAt = dueAt)

        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                expectedTriggerMs,
                any(),
            )
        }
        verify(exactly = 0) { alarmManager.setWindow(any(), any(), any(), any<PendingIntent>()) }
        assert(triggerSlot.captured == expectedTriggerMs) {
            "Expected triggerMs=$expectedTriggerMs, was ${triggerSlot.captured}"
        }
    }

    /** When the exact-alarm permission is missing, scheduler degrades to setWindow + WARN. */
    @Test
    fun `schedule with canScheduleExactAlarms false falls back to setWindow and logs WARN`() {
        every { alarmManager.canScheduleExactAlarms() } returns false
        val dueAt = NOW.plus(2.hours)

        scheduler.schedule(COMMITMENT_ID, dueAt = dueAt)

        verify(exactly = 1) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                dueAt.minus(1.hours).toEpochMilliseconds(),
                any(),
                any<PendingIntent>(),
            )
        }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }

        val warnSlot = slot<String>()
        verify(exactly = 1) { logger.w(any(), capture(warnSlot)) }
        val msg = warnSlot.captured
        assert(msg.contains("SCHEDULE_EXACT_ALARM")) {
            "Expected WARN to mention SCHEDULE_EXACT_ALARM, was: $msg"
        }
        assert(!msg.contains(COMMITMENT_ID)) {
            "WARN message must not contain raw commitmentId (PIPA). Was: $msg"
        }
    }

    // ── cancel() ──────────────────────────────────────────────────────────────

    @Test
    fun `cancel invokes alarmManager cancel`() {
        scheduler.cancel(COMMITMENT_ID)

        verify(exactly = 1) { alarmManager.cancel(any<PendingIntent>()) }
    }

    // ── PendingIntent flags ───────────────────────────────────────────────────

    /**
     * Verifies that the scheduler always sets FLAG_IMMUTABLE on the broadcast
     * PendingIntent (security hardening required on API 31+).
     */
    @Test
    fun `PendingIntent includes FLAG_IMMUTABLE`() {
        mockkStatic(PendingIntent::class)
        val dummyPi = mockk<PendingIntent>(relaxed = true)
        every {
            PendingIntent.getBroadcast(any(), any(), any(), any())
        } returns dummyPi

        scheduler.schedule(COMMITMENT_ID, dueAt = NOW.plus(2.hours))

        verify {
            PendingIntent.getBroadcast(
                any(),
                any(),
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

        /** Frozen "now" — 2026-04-22T10:00+09:00 in UTC. */
        private val NOW: Instant = Instant.parse("2026-04-22T01:00:00Z")
    }
}
