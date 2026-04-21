package com.becalm.android.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for [PhoneNumberUtils] (ING-001).
 *
 * libphonenumber's `PhoneNumberUtil` ships bundled metadata and does not need Android
 * framework classes, so these tests run under plain JUnit — no Robolectric required.
 */
class PhoneNumberUtilsTest {

    // ── toE164OrNull ─────────────────────────────────────────────────────────

    @Test
    fun `toE164OrNull normalizes a KR mobile number with dashes`() {
        assertEquals("+821012345678", PhoneNumberUtils.toE164OrNull("010-1234-5678", "KR"))
    }

    @Test
    fun `toE164OrNull returns null for null input`() {
        assertNull(PhoneNumberUtils.toE164OrNull(null, "KR"))
    }

    @Test
    fun `toE164OrNull returns null for blank input`() {
        assertNull(PhoneNumberUtils.toE164OrNull("", "KR"))
        assertNull(PhoneNumberUtils.toE164OrNull("   ", "KR"))
    }

    @Test
    fun `toE164OrNull returns null for unparseable input`() {
        assertNull(PhoneNumberUtils.toE164OrNull("invalid", "KR"))
    }

    @Test
    fun `toE164OrNull returns null for parseable but invalid number`() {
        // "12345" parses as a short number but fails isValidNumber for KR.
        assertNull(PhoneNumberUtils.toE164OrNull("12345", "KR"))
    }

    // ── extractCounterpartyNumberFromDisplayName ─────────────────────────────

    @Test
    fun `extract pulls E164 from Samsung Call prefix filename with dashes`() {
        assertEquals(
            "+821012345678",
            PhoneNumberUtils.extractCounterpartyNumberFromDisplayName(
                "Call_010-1234-5678_20250415_0830.m4a",
            ),
        )
    }

    @Test
    fun `extract pulls E164 from Samsung call recording with contact name and digits`() {
        assertEquals(
            "+821012345678",
            PhoneNumberUtils.extractCounterpartyNumberFromDisplayName(
                "Call recording John Doe_01012345678_250415.m4a",
            ),
        )
    }

    @Test
    fun `extract returns null when display name has no recognizable phone number`() {
        assertNull(
            PhoneNumberUtils.extractCounterpartyNumberFromDisplayName("Voice memo.m4a"),
        )
    }

    @Test
    fun `extract returns null for blank display name`() {
        assertNull(PhoneNumberUtils.extractCounterpartyNumberFromDisplayName(""))
    }
}
