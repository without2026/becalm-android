package com.becalm.android.unit.data.repository

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Checkpoint4SourceExtractionInputSpecTest {

    @Test
    fun `E2E-039 Gmail message enters extraction through normalized metadata body and participants`() = runTest {
        val raw = rawEvent(
            sourceType = SourceType.GMAIL,
            sourceRef = """
                {
                  "message_id": "<gmail-message@example.com>",
                  "in_reply_to": "<gmail-parent@example.com>",
                  "references": "<gmail-root@example.com> <gmail-parent@example.com>"
                }
            """.trimIndent(),
            folder = "INBOX",
        )
        val adapter = SourceExtractionInputAdapter(
            emailBodyRepository = FakeEmailBodyRepository(
                body = emailBody(
                    rawEventId = raw.id,
                    folder = "INBOX",
                    fromAddress = "Sankari <sankari@getrecall.ai>",
                    toAddresses = """["me@example.com"]""",
                    bodyPlain = "금요일까지 계약서 초안을 보내 주세요.",
                ),
            ),
        )

        val dto = adapter.toUploadDto(raw)

        assertEquals(SourceType.GMAIL, dto.sourceType)
        assertEquals("금요일까지 계약서 초안을 보내 주세요.", dto.emailBodyPlain)
        assertEquals("<gmail-message@example.com>", dto.messageIdHeader)
        assertEquals("<gmail-parent@example.com>", dto.inReplyToHeader)
        assertEquals("<gmail-root@example.com> <gmail-parent@example.com>", dto.referencesHeader)
        assertEquals("counterparty", dto.participants?.single { it.role == "sender" }?.relationToUser)
        assertEquals("self", dto.participants?.single { it.role == "recipient" }?.relationToUser)
    }

    @Test
    fun `E2E-040 Naver IMAP message uses same normalized extraction input as Gmail`() = runTest {
        val raw = rawEvent(
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = """{"message_id":"<naver-message@example.com>"}""",
            folder = "SENT",
        )
        val adapter = SourceExtractionInputAdapter(
            emailBodyRepository = FakeEmailBodyRepository(
                body = emailBody(
                    rawEventId = raw.id,
                    folder = "SENT",
                    fromAddress = "Me <me@example.com>",
                    toAddresses = """[{"email":"partner@acme.kr","name":"Partner"}]""",
                    bodyPlain = "오늘 오후 5시까지 리뷰 결과를 공유하겠습니다.",
                ),
            ),
        )

        val dto = adapter.toUploadDto(raw)

        assertEquals(SourceType.NAVER_IMAP, dto.sourceType)
        assertEquals("오늘 오후 5시까지 리뷰 결과를 공유하겠습니다.", dto.emailBodyPlain)
        assertEquals("<naver-message@example.com>", dto.messageIdHeader)
        assertEquals("self", dto.participants?.single { it.role == "sender" }?.relationToUser)
        assertEquals("counterparty", dto.participants?.single { it.role == "recipient" }?.relationToUser)
        assertEquals("partner@acme.kr", dto.participants?.single { it.role == "recipient" }?.email)
    }

    @Test
    fun `E2E-041 bot notification email keeps identity hints but removes body from extraction rendering path`() = runTest {
        val raw = rawEvent(sourceType = SourceType.GMAIL, folder = "INBOX")
        val adapter = SourceExtractionInputAdapter(
            emailBodyRepository = FakeEmailBodyRepository(
                body = emailBody(
                    rawEventId = raw.id,
                    folder = "INBOX",
                    fromAddress = "notifications@service.example",
                    toAddresses = """["me@example.com"]""",
                    bodyPlain = "새 댓글이 달렸습니다. 지금 확인하세요.",
                    groupEmail = true,
                ),
            ),
        )

        val dto = adapter.toUploadDto(raw)

        assertNull(dto.emailBodyPlain)
        assertEquals("notifications@service.example", dto.participants?.single { it.role == "sender" }?.email)
        assertEquals("counterparty", dto.participants?.single { it.role == "sender" }?.relationToUser)
    }

    private fun rawEvent(
        sourceType: String,
        sourceRef: String? = "source-1",
        folder: String? = null,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = sourceType,
            sourceRef = sourceRef,
            eventTitle = "title",
            folder = folder,
            timestamp = NOW,
        )

    private fun emailBody(
        rawEventId: String,
        folder: String,
        fromAddress: String,
        toAddresses: String,
        bodyPlain: String,
        groupEmail: Boolean = false,
    ): EmailBodyEntity =
        EmailBodyEntity(
            id = "body-1",
            rawEventId = rawEventId,
            providerMessageId = "message@example.com",
            folder = folder,
            fromAddress = fromAddress,
            toAddresses = toAddresses,
            bodyPlain = bodyPlain,
            groupEmail = groupEmail,
            receivedAt = NOW,
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
