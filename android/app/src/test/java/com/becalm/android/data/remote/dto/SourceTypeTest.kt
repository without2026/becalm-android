package com.becalm.android.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SourceType] constants.
 *
 * Spec ref: .spec/contracts/data-model.yml:28-32 — `source_type` enum for
 * `raw_ingestion_events` must include `call_recording`.
 */
class SourceTypeTest {

    @Test
    fun `CALL_RECORDING constant matches wire value`() {
        assertEquals("call_recording", SourceType.CALL_RECORDING)
    }

    @Test
    fun `ALL contains call_recording`() {
        assertTrue(SourceType.ALL.contains("call_recording"))
    }

    @Test
    fun `ALL size matches data-model enum count excluding manual`() {
        // voice, call_recording, gmail, outlook_mail, naver_imap, daum_imap,
        // google_calendar, outlook_calendar — 8 values (manual is commitments-only, out of scope).
        assertEquals(8, SourceType.ALL.size)
    }

    // ── PRODUCT_SOURCES — wave-0 carve-out (codex round 1) ─────────────────

    @Test
    fun `PRODUCT_SOURCES size is 6 external product sources`() {
        // gmail, outlook_mail, naver_imap, daum_imap, google_calendar, outlook_calendar.
        // Excludes voice (captured locally) and call_recording (schema-only carve-out).
        assertEquals(6, SourceType.PRODUCT_SOURCES.size)
    }

    @Test
    fun `PRODUCT_SOURCES excludes voice and call_recording`() {
        assertFalse(
            "PRODUCT_SOURCES must not include VOICE",
            SourceType.PRODUCT_SOURCES.contains(SourceType.VOICE),
        )
        assertFalse(
            "PRODUCT_SOURCES must not include CALL_RECORDING (wave-0 carve-out)",
            SourceType.PRODUCT_SOURCES.contains(SourceType.CALL_RECORDING),
        )
    }

    @Test
    fun `PRODUCT_SOURCES contains the six external connect-able sources`() {
        val expected = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
        assertEquals(expected, SourceType.PRODUCT_SOURCES)
    }

    @Test
    fun `PRODUCT_SOURCES is a strict subset of ALL`() {
        assertTrue(SourceType.ALL.containsAll(SourceType.PRODUCT_SOURCES))
        assertTrue(SourceType.ALL.size > SourceType.PRODUCT_SOURCES.size)
    }
}
