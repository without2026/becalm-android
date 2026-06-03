package com.becalm.android.unit.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.SourceConnectionDao
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceConnectionDto
import com.becalm.android.data.remote.dto.SourceConnectionResponseDto
import com.becalm.android.data.remote.dto.SourceConnectionsResponseDto
import com.becalm.android.data.repository.SourceConnectionRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SourceConnectionRepositoryImplSpecTest {

    private val dao: SourceConnectionDao = mockk(relaxed = true)
    private val api: RailwayApi = mockk()
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `disconnect source connection mirrors disconnected row locally`() = runTest {
        val subject = subject()
        coEvery { api.disconnectSourceConnection("conn-gmail") } returns Response.success(
            SourceConnectionResponseDto(sourceConnection(status = "disconnected")),
        )

        val result = subject.disconnectConnection("user-123", "conn-gmail")

        assertTrue(result is BecalmResult.Success)
        assertEquals("disconnected", (result as BecalmResult.Success).value.status)
        coVerify(exactly = 1) {
            dao.upsert(match { it.id == "conn-gmail" && it.status == "disconnected" })
        }
        verifyGmailMirrorCursorsCleared()
    }

    @Test
    fun `delete source connection removes local mirror row after backend soft delete`() = runTest {
        val subject = subject()
        coEvery { api.deleteSourceConnection("conn-gmail") } returns Response.success(
            SourceConnectionResponseDto(sourceConnection(status = "disconnected")),
        )

        val result = subject.deleteConnection("user-123", "conn-gmail")

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { dao.deleteById("conn-gmail") }
        verifyGmailMirrorCursorsCleared()
    }

    @Test
    fun `refresh clears mirror cursors when a source connection becomes newly connected`() = runTest {
        val subject = subject()
        coEvery { api.getSourceConnections() } returns Response.success(
            SourceConnectionsResponseDto(listOf(sourceConnection(status = "connected"))),
        )
        coEvery { dao.findById("conn-gmail") } returns null

        val result = subject.refresh("user-123")

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) {
            dao.insertAll(match { rows -> rows.single().id == "conn-gmail" && rows.single().status == "connected" })
        }
        coVerify(exactly = 1) { dao.deleteMissingForUser("user-123", listOf("conn-gmail")) }
        verifyGmailMirrorCursorsCleared()
    }

    @Test
    fun `refresh clears mirror cursors when an existing source reconnects`() = runTest {
        val subject = subject()
        coEvery { api.getSourceConnections() } returns Response.success(
            SourceConnectionsResponseDto(listOf(sourceConnection(status = "connected"))),
        )
        coEvery { dao.findById("conn-gmail") } returns sourceConnectionEntity(status = "disconnected")

        val result = subject.refresh("user-123")

        assertTrue(result is BecalmResult.Success)
        verifyGmailMirrorCursorsCleared()
    }

    @Test
    fun `refresh keeps mirror cursors for already connected source connection`() = runTest {
        val subject = subject()
        coEvery { api.getSourceConnections() } returns Response.success(
            SourceConnectionsResponseDto(listOf(sourceConnection(status = "connected"))),
        )
        coEvery { dao.findById("conn-gmail") } returns sourceConnectionEntity(status = "connected")

        val result = subject.refresh("user-123")

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 0) { syncCursorStore.clearCursor(any()) }
    }

    private fun subject(): SourceConnectionRepositoryImpl =
        SourceConnectionRepositoryImpl(
            dao = dao,
            apiProvider = Provider { api },
            syncCursorStore = syncCursorStore,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun sourceConnection(status: String): SourceConnectionDto =
        SourceConnectionDto(
            id = "conn-gmail",
            userId = "user-123",
            provider = "google",
            capability = "mail",
            accountIdentifier = "work@example.com",
            accountDisplayName = "Work",
            ownership = "self",
            status = status,
        )

    private fun sourceConnectionEntity(status: String): SourceConnectionEntity =
        SourceConnectionEntity(
            id = "conn-gmail",
            userId = "user-123",
            provider = "google",
            capability = "mail",
            accountIdentifier = "work@example.com",
            accountDisplayName = "Work",
            ownership = "self",
            status = status,
            linkedSelfAnchorId = null,
            lastSyncAt = null,
            lastError = null,
        )

    private fun verifyGmailMirrorCursorsCleared() {
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:gmail") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:all") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitments_cursor") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitment_participants") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("schedule_event_links") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("raw_ingestion_events:gmail") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("raw_ingestion_events:all") }
    }
}
