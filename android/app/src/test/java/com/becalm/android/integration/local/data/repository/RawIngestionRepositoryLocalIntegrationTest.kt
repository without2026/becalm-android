package com.becalm.android.integration.local.data.repository

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import com.becalm.android.data.remote.dto.RawIngestionEventsResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.mockk
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
class RawIngestionRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val repository = RawIngestionRepositoryImpl(
        dao = db.rawIngestionEventDao(),
        api = api,
        logger = RecordingLogger(),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertLocalBatch returns existing id and stores one row for duplicate client event ids`() = runTest {
        val first = rawEvent(id = "raw-1", clientEventId = "client-event-1")
        val duplicate = rawEvent(id = "raw-2", clientEventId = "client-event-1")

        val result = repository.insertLocalBatch(listOf(first, duplicate))

        assertTrue(result is BecalmResult.Success)
        val ids = (result as BecalmResult.Success).value
        assertEquals(listOf("raw-1", "raw-1"), ids)
        db.rawIngestionEventDao()
            .observeRecentForSourceType(USER_ID, SourceType.NAVER_IMAP, limit = 10)
            .test {
                assertEquals(listOf("raw-1"), awaitItem().map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `refreshSince mirrors backend raw mail rows as synced local events`() = runTest {
        coEvery {
            api.getRawIngestionEvents(
                cursor = null,
                limit = any(),
                since = null,
                sourceType = SourceType.GMAIL,
            )
        } returns Response.success(
            RawIngestionEventsResponse(
                data = listOf(
                    RawIngestionEventDto(
                        id = "server-raw-1",
                        clientEventId = "gmail-client-1",
                        sourceType = SourceType.GMAIL,
                        sourceRef = "gmail-message-1",
                        personRef = "customer@example.com",
                        eventTitle = "제안서 요청",
                        eventSnippet = "내일까지 제안서 보내주세요.",
                        folder = "inbox",
                        commitmentsExtractedCount = 1,
                        timestamp = Instant.parse("2026-04-28T01:00:00Z"),
                    ),
                ),
                cursor = "cursor-1",
                hasMore = false,
            ),
        )

        val result = repository.refreshSince(userId = USER_ID, sourceType = SourceType.GMAIL, since = null)

        assertTrue(result is BecalmResult.Success)
        val row = db.rawIngestionEventDao().findByClientEventId(USER_ID, "gmail-client-1")
        requireNotNull(row)
        assertEquals("server-raw-1", row.id)
        assertEquals("synced", row.syncStatus)
        assertEquals("customer@example.com", row.personRef)
        assertEquals(1, row.commitmentsExtractedCount)
    }

    private fun rawEvent(id: String, clientEventId: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = clientEventId,
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = "source-$id",
            eventTitle = "subject",
            timestamp = Instant.parse("2026-04-28T00:00:00Z"),
        )

    private companion object {
        const val USER_ID = "user-1"
    }
}
