package com.becalm.android.notifications

import android.content.Context
import com.becalm.android.data.local.entities.Commitment
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// spec: CMT-008 — CommitmentAlarmScheduler schedules 09:00 local on due_date

class CommitmentAlarmSchedulerTest {

    private val context: Context = mockk(relaxed = true)
    private val scheduler = CommitmentAlarmScheduler(context)

    // spec: CMT-008 — alarm scheduled at 09:00 local time on the due_date
    @Test
    fun `alarmScheduler_schedules09AMLocalOnDueDate`() {
        val dueDate = "2026-04-20"
        val alarmMs = scheduler.parse09AmLocal(dueDate)

        assertNotNull(alarmMs)

        val cal = Calendar.getInstance().apply {
            timeInMillis = alarmMs!!
        }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))

        // Verify date is correct
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        assertEquals(dueDate, sdf.format(cal.time))
    }

    // schedule() is a no-op when due_date is null — no crash
    @Test
    fun `schedule_withNullDueDate_doesNotCrash`() {
        val commitment = makeCommitment(dueDate = null)
        scheduler.schedule(commitment) // should not throw
    }

    // parse09AmLocal returns null for invalid date strings
    @Test
    fun `parse09AmLocal_invalidDate_returnsNull`() {
        assertNull(scheduler.parse09AmLocal("not-a-date"))
        assertNull(scheduler.parse09AmLocal(""))
    }

    // parse09AmLocal on valid date always returns 09:00:00.000
    @Test
    fun `parse09AmLocal_validDate_returns09_00_00`() {
        val ms = scheduler.parse09AmLocal("2026-12-31")
        assertNotNull(ms)
        val cal = Calendar.getInstance().apply { timeInMillis = ms!! }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    private fun makeCommitment(dueDate: String?) = Commitment(
        id = "cmt-test-id",
        direction = "give",
        title = "Test commitment",
        quote = "verbatim",
        sourceEventOccurredAt = System.currentTimeMillis(),
        sourceType = "voice",
        dueDate = dueDate
    )
}
