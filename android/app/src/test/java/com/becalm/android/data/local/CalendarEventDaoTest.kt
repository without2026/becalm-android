package com.becalm.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.entities.CalendarEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// spec: ING-009, ING-010 — calendar event upsert
// spec: TDY-005 — today's calendar events

@RunWith(RobolectricTestRunner::class)
class CalendarEventDaoTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: CalendarEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.calendarEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // spec: ING-009 — upsert google_calendar event
    @Test
    fun `upsert creates google calendar event`() = runTest {
        val event = CalendarEvent(
            id = "cal-001",
            sourceType = "google_calendar",
            sourceRef = "gcal_ext_001",
            title = "Sprint Review",
            startAt = 1713200000000L,
            endAt = 1713203600000L
        )
        dao.upsert(event)
        assertEquals(1, dao.count())
    }

    // spec: ING-009 — upsert replaces same source_ref event
    @Test
    fun `upsert replaces existing event with same id`() = runTest {
        val event = CalendarEvent(
            id = "cal-dup",
            sourceType = "google_calendar",
            sourceRef = "gcal_ext_dup",
            title = "Old Title",
            startAt = 1713200000000L,
            endAt = 1713203600000L
        )
        dao.upsert(event)
        dao.upsert(event.copy(title = "New Title"))
        assertEquals(1, dao.count())
    }

    // spec: TDY-005 — observeTodayEvents returns events starting in range
    @Test
    fun `observeTodayEvents returns events within day range`() = runTest {
        val startOfDay = 1713196800000L   // 2026-04-16 00:00 UTC
        val endOfDay = 1713283200000L     // 2026-04-17 00:00 UTC

        dao.upsert(CalendarEvent(
            id = "today-event",
            sourceType = "google_calendar",
            sourceRef = "ref-today",
            title = "Today's Meeting",
            startAt = startOfDay + 3600000, // 01:00 today
            endAt = startOfDay + 7200000
        ))
        dao.upsert(CalendarEvent(
            id = "tomorrow-event",
            sourceType = "google_calendar",
            sourceRef = "ref-tomorrow",
            title = "Tomorrow's Meeting",
            startAt = endOfDay + 3600000, // tomorrow
            endAt = endOfDay + 7200000
        ))

        val todayEvents = dao.observeTodayEvents(startOfDay, endOfDay).first()
        assertEquals(1, todayEvents.size)
        assertEquals("Today's Meeting", todayEvents[0].title)
    }

    // spec: ING-010 — outlook_calendar event upsert
    @Test
    fun `upsert creates outlook calendar event`() = runTest {
        val event = CalendarEvent(
            id = "cal-out",
            sourceType = "outlook_calendar",
            sourceRef = "oc_ext_001",
            title = "Customer Call",
            startAt = 1713200000000L,
            endAt = 1713203600000L
        )
        dao.upsert(event)
        assertEquals(1, dao.count())
    }
}
