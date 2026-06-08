package com.becalm.android.unit.ui.actions

import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDetailDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceRefDto
import com.becalm.android.ui.actions.toPersonActionEvidenceDetailUi
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonActionEvidenceDetailUiSpecTest {

    @Test
    fun `local original replaces metadata body while preserving server metadata`() {
        val ui = evidenceOriginal(
            localOriginalTitle = "자료 확인 메일",
            localOriginalText = "긴 이메일 원문",
            localOriginalTruncated = true,
        ).toPersonActionEvidenceDetailUi()

        assertEquals("자료 확인 메일", ui.originalTitle)
        assertEquals("긴 이메일 원문", ui.originalText)
        assertEquals("Please send the proposal tomorrow.", ui.whyText)
        assertTrue(ui.metadataText.orEmpty().contains("bounded snippet"))
        assertTrue(ui.originalIsLocal)
        assertTrue(ui.originalTruncated)
    }

    @Test
    fun `missing local original keeps metadata-only evidence state`() {
        val ui = evidenceOriginal(
            localOriginalText = null,
            localOriginalTruncated = false,
        ).toPersonActionEvidenceDetailUi()

        assertEquals("Proposal thread", ui.originalTitle)
        assertEquals("bounded quote\n\nwhy this action exists\n\nbounded snippet\n\nProposal thread", ui.originalText)
        assertEquals(ui.originalText, ui.metadataText)
        assertFalse(ui.originalIsLocal)
        assertFalse(ui.originalTruncated)
    }

    private fun evidenceOriginal(
        localOriginalTitle: String? = null,
        localOriginalText: String? = null,
        localOriginalTruncated: Boolean = false,
    ): PersonActionEvidenceOriginalDto =
        PersonActionEvidenceOriginalDto(
            actionItemId = "pa-1",
            actionStatus = "active",
            evidence = PersonActionEvidenceRefDto(
                kind = "source_event",
                id = "source-event-1",
                sourceRef = "gmail-msg-1",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = "Gmail thread",
                quote = "Please send the proposal tomorrow.",
            ),
            original = PersonActionEvidenceOriginalDetailDto(
                kind = "source_event",
                id = "source-event-1",
                originalAvailable = true,
                status = "metadata_resolved",
                sourceType = "gmail",
                sourceRef = "gmail-msg-1",
                title = "Proposal thread",
                description = "why this action exists",
                snippet = "bounded snippet",
                quote = "bounded quote",
                rawBodyIncluded = false,
                localOriginalTitle = localOriginalTitle,
                localOriginalText = localOriginalText,
                localOriginalTruncated = localOriginalTruncated,
            ),
            resolvedAt = Instant.parse("2026-06-03T04:05:00Z"),
        )
}
