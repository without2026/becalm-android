package com.becalm.android.domain.extractor

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.domain.voice.CommitmentDraft
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [GeminiNanoExtractor] (SP-33, KTR-GEMINI-NANO).
 *
 * Verifies the stub contract:
 * 1. [GeminiNanoExtractor.extract] returns [BecalmResult.Success].
 * 2. The success value is an empty list (no commitments while AICore is unlinked).
 * 3. The contract holds for any input entity (no NPE / exception on arbitrary inputs).
 */
class GeminiNanoExtractorTest {

    private lateinit var extractor: GeminiNanoExtractor

    @Before
    fun setUp() {
        extractor = GeminiNanoExtractor()
    }

    // ── SP-33-T1: empty-list contract ─────────────────────────────────────────

    @Test
    fun `extract returns Success with empty list for typical email entity`() = runTest {
        val entity = buildEntity(
            eventTitle = "내일까지 보고서 보내드릴게요",
            eventSnippet = "네, 알겠습니다. 내일 오전까지 자료 정리해서 공유하겠습니다.",
        )

        val result = extractor.extract(entity)

        assertIsSuccessWithEmptyList(result)
    }

    @Test
    fun `extract returns Success with empty list for null title and snippet`() = runTest {
        val entity = buildEntity(eventTitle = null, eventSnippet = null)

        val result = extractor.extract(entity)

        assertIsSuccessWithEmptyList(result)
    }

    @Test
    fun `extract returns Success with empty list for calendar event entity`() = runTest {
        val entity = buildEntity(
            sourceType = "outlook_calendar",
            eventTitle = "팀 주간 회의",
            eventSnippet = null,
        )

        val result = extractor.extract(entity)

        assertIsSuccessWithEmptyList(result)
    }

    @Test
    fun `extract returns Success with empty list for voice entity`() = runTest {
        val entity = buildEntity(
            sourceType = "voice",
            eventTitle = "음성 메모",
            eventSnippet = "내일까지 계약서 검토하기로 했음.",
        )

        val result = extractor.extract(entity)

        assertIsSuccessWithEmptyList(result)
    }

    // ── SP-33-T2: BecalmResult.Success path (not Failure) ────────────────────

    @Test
    fun `extract result is BecalmResult Success not Failure`() = runTest {
        val entity = buildEntity()

        val result = extractor.extract(entity)

        assertTrue("Expected BecalmResult.Success but got $result", result is BecalmResult.Success)
    }

    @Test
    fun `extract Success value is non-null`() = runTest {
        val entity = buildEntity()

        val result = extractor.extract(entity) as BecalmResult.Success

        assertNotNull(result.value)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertIsSuccessWithEmptyList(result: BecalmResult<List<CommitmentDraft>>) {
        assertTrue("Expected BecalmResult.Success but was $result", result is BecalmResult.Success)
        val drafts = (result as BecalmResult.Success).value
        assertTrue("Expected empty list but had ${drafts.size} drafts", drafts.isEmpty())
    }

    private fun buildEntity(
        sourceType: String = "outlook_mail",
        eventTitle: String? = "Test event",
        eventSnippet: String? = "Test snippet",
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = UUID.randomUUID().toString(),
        userId = "user-test",
        clientEventId = UUID.randomUUID().toString(),
        sourceType = sourceType,
        sourceRef = null,
        personRef = null,
        eventTitle = eventTitle,
        eventSnippet = eventSnippet,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )
}
