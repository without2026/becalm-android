package com.becalm.android.integration.local.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarEventDto
import com.becalm.android.data.remote.dto.CalendarEventListResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CalendarEventRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CalendarEventRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val api = mockk<RailwayApi>(relaxed = true)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `refreshSince discards stored cursor when local calendar mirror is empty`() = runTest {
        val cursorStore = SyncCursorStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("calendar-mirror-empty-cursor-reset"),
        )
        cursorStore.setCursor("calendar_events:v2_user:user-1", "ks1:stale")
        val repository = CalendarEventRepositoryImpl(
            dao = db.calendarEventDao(),
            api = api,
            cursorStore = cursorStore,
            logger = RecordingLogger(),
        )
        coEvery {
            api.getCalendarEvents(cursor = null, since = null, limit = null)
        } returns Response.success(
            CalendarEventListResponse(
                data = listOf(calendarEventDto()),
                cursor = "ks1:calendar-1",
                hasMore = false,
            ),
        )

        val result = repository.refreshSince(userId = USER_ID, since = null)

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, (result as BecalmResult.Success).value.fetched)
        assertEquals("ks1:calendar-1", cursorStore.observeCursor("calendar_events:v2_user:user-1").first())
        assertEquals(listOf("calendar-1"), db.calendarEventDao().findAllForUser(USER_ID).map { it.id })
        coVerify(exactly = 1) {
            api.getCalendarEvents(cursor = null, since = null, limit = null)
        }
        coVerify(exactly = 0) {
            api.getCalendarEvents(cursor = "ks1:stale", since = null, limit = null)
        }
    }

    private fun calendarEventDto(): CalendarEventDto =
        CalendarEventDto(
            id = "calendar-1",
            userId = USER_ID,
            sourceType = SourceType.GOOGLE_CALENDAR,
            sourceRef = "gcal-event-1",
            title = "Planning",
            startAt = Instant.parse("2026-04-28T01:00:00Z"),
            endAt = Instant.parse("2026-04-28T02:00:00Z"),
        )

    private companion object {
        const val USER_ID = "user-1"
    }
}
