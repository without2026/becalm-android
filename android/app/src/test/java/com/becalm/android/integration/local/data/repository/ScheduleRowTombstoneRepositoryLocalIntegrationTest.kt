package com.becalm.android.integration.local.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.ScheduleRowTombstoneEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ScheduleRowTombstoneDto
import com.becalm.android.data.remote.dto.ScheduleRowTombstoneRequestDto
import com.becalm.android.data.remote.dto.ScheduleRowTombstoneResponseDto
import com.becalm.android.data.repository.ScheduleRowTombstoneRepositoryImpl
import com.becalm.android.domain.schedule.ScheduleRowRef
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
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
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ScheduleRowTombstoneRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val repository = ScheduleRowTombstoneRepositoryImpl(
        dao = db.scheduleRowTombstoneDao(),
        apiProvider = Provider { api },
        logger = RecordingLogger(),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upload sends one ten hundred tombstone requests with source identity`() = runTest {
        for (scale in listOf(1, 10, 100)) {
            val requestSlot = slot<ScheduleRowTombstoneRequestDto>()
            coEvery {
                api.upsertScheduleRowTombstone(request = capture(requestSlot))
            } returns Response.success(
                ScheduleRowTombstoneResponseDto(data = tombstoneDto(scale)),
            )

            val result = repository.upload(tombstoneEntity(scale))

            assertTrue(result is BecalmResult.Success)
            assertEquals("calendar_event", requestSlot.captured.rowType)
            assertEquals("calendar-$scale", requestSlot.captured.sourceEventId)
            assertEquals("google_calendar", requestSlot.captured.sourceType)
            assertEquals("provider-$scale", requestSlot.captured.sourceRef)
        }
    }

    @Test
    fun `tombstone writes local row and marks it synced after remote success`() = runTest {
        coEvery {
            api.upsertScheduleRowTombstone(request = any())
        } returns Response.success(
            ScheduleRowTombstoneResponseDto(data = tombstoneDto(1)),
        )

        val result = repository.tombstone(
            userId = USER_ID,
            rowRef = ScheduleRowRef.CalendarEvent(
                id = "calendar-1",
                sourceType = "google_calendar",
                sourceRef = "provider-1",
            ),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(emptyList<ScheduleRowTombstoneEntity>(), repository.findPendingSync(USER_ID, limit = 10))
    }

    private fun tombstoneEntity(scale: Int): ScheduleRowTombstoneEntity =
        ScheduleRowTombstoneEntity(
            id = "$USER_ID:calendar-$scale",
            userId = USER_ID,
            rowType = "calendar_event",
            sourceEventId = "calendar-$scale",
            sourceType = "google_calendar",
            sourceRef = "provider-$scale",
            deletedAt = Instant.parse("2026-06-03T01:00:00Z"),
            createdAt = Instant.parse("2026-06-03T01:00:00Z"),
            updatedAt = Instant.parse("2026-06-03T01:00:00Z"),
        )

    private fun tombstoneDto(scale: Int): ScheduleRowTombstoneDto =
        ScheduleRowTombstoneDto(
            id = "server-tombstone-$scale",
            userId = USER_ID,
            rowType = "calendar_event",
            sourceEventId = "calendar-$scale",
            sourceType = "google_calendar",
            sourceRef = "provider-$scale",
            deletedAt = Instant.parse("2026-06-03T01:00:00Z"),
            createdAt = Instant.parse("2026-06-03T01:00:00Z"),
            updatedAt = Instant.parse("2026-06-03T01:00:00Z"),
        )

    private companion object {
        private const val USER_ID = "user-1"
    }
}
