package com.becalm.android.unit.data.repository

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceExtractionInputAdapterSpecTest {
    @Test
    fun `normalizes email metadata into transient body and participant hints`() = runTest {
        val raw = rawEvent(
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = """
                {
                  "message_id": "<message@example.com>",
                  "in_reply_to": "<parent@example.com>",
                  "references": "<root@example.com> <parent@example.com>"
                }
            """.trimIndent(),
            folder = "SENT",
        )
        val adapter = SourceExtractionInputAdapter(
            emailBodyRepository = FakeEmailBodyRepository(
                body = EmailBodyEntity(
                    id = "body-1",
                    rawEventId = raw.id,
                    providerMessageId = "message@example.com",
                    folder = "SENT",
                    fromAddress = "Me <me@example.com>",
                    toAddresses = """[{"email":"customer@example.com","name":"Customer"}]""",
                    bodyPlain = "내일까지 제안서를 보내겠습니다.",
                    receivedAt = NOW,
                ),
            ),
        )

        val dto = adapter.toUploadDto(raw)

        assertEquals("내일까지 제안서를 보내겠습니다.", dto.emailBodyPlain)
        assertEquals("<message@example.com>", dto.messageIdHeader)
        assertEquals("<parent@example.com>", dto.inReplyToHeader)
        assertEquals("<root@example.com> <parent@example.com>", dto.referencesHeader)
        assertEquals("self", dto.participants?.single { it.role == "sender" }?.relationToUser)
        assertEquals("counterparty", dto.participants?.single { it.role == "recipient" }?.relationToUser)
    }

    @Test
    fun `omits noisy email body while keeping participant hints`() = runTest {
        val raw = rawEvent(sourceType = SourceType.GMAIL, folder = "INBOX")
        val adapter = SourceExtractionInputAdapter(
            emailBodyRepository = FakeEmailBodyRepository(
                body = EmailBodyEntity(
                    id = "body-1",
                    rawEventId = raw.id,
                    providerMessageId = "message@example.com",
                    folder = "INBOX",
                    fromAddress = "sender@example.com",
                    toAddresses = """["me@example.com"]""",
                    bodyPlain = "안녕하세요.",
                    groupEmail = true,
                    receivedAt = NOW,
                ),
            ),
        )

        val dto = adapter.toUploadDto(raw)

        assertNull(dto.emailBodyPlain)
        assertEquals("counterparty", dto.participants?.single { it.role == "sender" }?.relationToUser)
        assertEquals("self", dto.participants?.single { it.role == "recipient" }?.relationToUser)
    }

    @Test
    fun `normalizes non email counterparty into participant hint`() = runTest {
        val raw = rawEvent(
            sourceType = SourceType.CALL_RECORDING,
            counterpartyRef = "+821012341234",
        )
        val adapter = SourceExtractionInputAdapter(emailBodyRepository = FakeEmailBodyRepository())

        val dto = adapter.toUploadDto(raw)

        val participant = dto.participants?.single()
        assertEquals("counterparty", participant?.role)
        assertEquals("phone", participant?.identityType)
        assertEquals("+821012341234", participant?.phone)
    }

    @Test
    fun `builds direct extraction request parts from the same normalized source`() = runTest {
        val raw = rawEvent(
            sourceType = SourceType.MEETING,
            counterpartyRef = "Customer",
        ).copy(durationSeconds = 60)
        val adapter = SourceExtractionInputAdapter(emailBodyRepository = FakeEmailBodyRepository())

        val dto = adapter.toUploadDto(raw)
        val parts = adapter.toRequestParts(
            event = raw,
            rawEventId = raw.id,
        )

        assertEquals(dto.sourceType, parts.sourceType.readUtf8())
        assertEquals(dto.clientEventId, parts.clientEventId.readUtf8())
        assertEquals(raw.id, parts.rawEventId.readUtf8())
        assertEquals(dto.durationSeconds.toString(), parts.durationSeconds?.readUtf8())
        assertEquals(dto.timestamp.toString(), parts.timestamp.readUtf8())
        assertEquals(dto.counterpartyRef, parts.counterpartyRef?.readUtf8())
        assertEquals(dto.eventTitle, parts.eventTitle?.readUtf8())
    }

    private fun rawEvent(
        sourceType: String,
        sourceRef: String? = "source-1",
        folder: String? = null,
        counterpartyRef: String? = null,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = sourceType,
            sourceRef = sourceRef,
            counterpartyRef = counterpartyRef,
            eventTitle = "title",
            folder = folder,
            timestamp = NOW,
        )

    private class FakeEmailBodyRepository(
        private val body: EmailBodyEntity? = null,
    ) : EmailBodyRepository {
        override suspend fun insert(entity: EmailBodyEntity) = Unit
        override suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity? = body
        override suspend fun findByProviderMessage(
            userId: String,
            sourceType: String,
            folder: String,
            providerMessageId: String,
        ): EmailBodyEntity? = null
        override suspend fun markParseFailed(id: String) = Unit
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
    }
}

private fun okhttp3.RequestBody.readUtf8(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
