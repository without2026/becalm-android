package com.becalm.android.integration.local.data.repository

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import com.becalm.android.data.remote.dto.RawIngestionEventsResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.slot
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
import javax.inject.Provider

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
    fun `e2e 072 retry after process death does not duplicate source events`() = runTest {
        val firstAttempt = rawEvent(id = "raw-process-1", clientEventId = "client-process-1")
        val resumedAttempt = rawEvent(id = "raw-process-2", clientEventId = "client-process-1")

        val firstResult = repository.insertLocal(firstAttempt)
        val resumedResult = repository.insertLocal(resumedAttempt)

        assertTrue(firstResult is BecalmResult.Success)
        assertTrue(resumedResult is BecalmResult.Success)
        assertEquals("raw-process-1", (firstResult as BecalmResult.Success).value)
        assertEquals("raw-process-1", (resumedResult as BecalmResult.Success).value)
        db.rawIngestionEventDao()
            .observeRecentForSourceType(USER_ID, SourceType.NAVER_IMAP, limit = 10)
            .test {
                assertEquals(listOf("raw-process-1"), awaitItem().map { it.id })
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
                        counterpartyRef = "customer@example.com",
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
        assertEquals("customer@example.com", row.counterpartyRef)
        assertEquals(1, row.commitmentsExtractedCount)
    }

    @Test
    fun `uploadBatch sends source participants for non-email counterparty seed`() = runTest {
        val requestSlot = slot<BatchUploadRequest>()
        coEvery { api.batchUploadRawEvents(request = capture(requestSlot)) } returns Response.success(
            BatchUploadResponse(acknowledged = 1, failed = emptyList()),
        )
        val event = rawEvent(id = "call-raw-1", clientEventId = "call-client-1").copy(
            sourceType = SourceType.CALL_RECORDING,
            counterpartyRef = "+821012341234",
        )

        val result = repository.uploadBatch(listOf(event))

        assertTrue(result is BecalmResult.Success)
        val participant = requestSlot.captured.events.single().participants?.single()
        requireNotNull(participant)
        assertEquals("counterparty", participant.role)
        assertEquals("counterparty", participant.relationToUser)
        assertEquals("phone", participant.identityType)
        assertEquals("+821012341234", participant.phone)
    }

    @Test
    fun `uploadBatch sends email sender and recipient participants from email body metadata`() = runTest {
        val requestSlot = slot<BatchUploadRequest>()
        coEvery { api.batchUploadRawEvents(request = capture(requestSlot)) } returns Response.success(
            BatchUploadResponse(acknowledged = 1, failed = emptyList()),
        )
        val raw = rawEvent(id = "email-raw-1", clientEventId = "email-client-1").copy(
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = """
                {
                  "message_id": "<message-1@example.com>",
                  "in_reply_to": "<parent@example.com>",
                  "references": "<root@example.com> <parent@example.com>"
                }
            """.trimIndent(),
            folder = "SENT",
        )
        val emailAwareRepository = RawIngestionRepositoryImpl(
            dao = db.rawIngestionEventDao(),
            apiProvider = Provider { api },
            emailBodyRepositoryProvider = Provider {
                object : EmailBodyRepository {
                    override suspend fun insert(entity: EmailBodyEntity) = Unit
                    override suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity? =
                        EmailBodyEntity(
                            id = "body-1",
                            rawEventId = rawEventId,
                            providerMessageId = "message-1",
                            folder = "SENT",
                            fromAddress = "me@example.com",
                            toAddresses = "[\"customer@example.com\"]",
                            bodyPlain = "내일까지 제안서 보내드리겠습니다.",
                            receivedAt = Instant.parse("2026-04-28T00:00:00Z"),
                        )
                    override suspend fun findByProviderMessage(
                        userId: String,
                        sourceType: String,
                        folder: String,
                        providerMessageId: String,
                    ): EmailBodyEntity? = null
                    override suspend fun markParseFailed(id: String) = Unit
                }
            },
            logger = RecordingLogger(),
        )

        val result = emailAwareRepository.uploadBatch(listOf(raw))

        assertTrue(result is BecalmResult.Success)
        val participants = requestSlot.captured.events.single().participants.orEmpty()
        assertEquals("me@example.com", participants.single { it.role == "sender" }.email)
        assertEquals("self", participants.single { it.role == "sender" }.relationToUser)
        assertEquals("customer@example.com", participants.single { it.role == "recipient" }.email)
        assertEquals("counterparty", participants.single { it.role == "recipient" }.relationToUser)
        val uploaded = requestSlot.captured.events.single()
        assertEquals("내일까지 제안서 보내드리겠습니다.", uploaded.emailBodyPlain)
        assertEquals("<message-1@example.com>", uploaded.messageIdHeader)
        assertEquals("<parent@example.com>", uploaded.inReplyToHeader)
        assertEquals("<root@example.com> <parent@example.com>", uploaded.referencesHeader)
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
