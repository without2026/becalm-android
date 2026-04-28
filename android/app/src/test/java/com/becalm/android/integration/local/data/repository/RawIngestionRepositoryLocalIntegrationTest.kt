package com.becalm.android.integration.local.data.repository

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RawIngestionRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val repository = RawIngestionRepositoryImpl(
        dao = db.rawIngestionEventDao(),
        api = mockk<RailwayApi>(relaxed = true),
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
