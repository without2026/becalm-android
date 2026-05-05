package com.becalm.android.unit.domain.commitment

import com.becalm.android.domain.commitment.CounterpartyRefNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterpartyRefNormalizerSpecTest {

    @Test
    fun `blank or null person refs normalise to null`() {
        assertEquals(null, CounterpartyRefNormalizer.normalize(null))
        assertEquals(null, CounterpartyRefNormalizer.normalize("   "))
    }

    @Test
    fun `emails and free-form names are trimmed and lowercased`() {
        assertEquals("lee@corp.com", CounterpartyRefNormalizer.normalize("  LEE@CORP.COM "))
        assertEquals("김철수 팀장", CounterpartyRefNormalizer.normalize("  김철수 팀장 "))
    }

    @Test
    fun `valid E164 phone-shaped refs remain acceptable after compaction`() {
        assertTrue(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("+821012345678"))
        assertTrue(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("+82 10-1234-5678"))
    }

    @Test
    fun `invalid phone-shaped refs are rejected`() {
        assertFalse(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("010-1234-5678"))
        assertFalse(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("+021012345678"))
    }

    @Test
    fun `non phone-shaped opaque ids stay valid as free-form`() {
        assertTrue(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("lee alias #1"))
        assertTrue(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("sales-team@corp.com"))
        assertTrue(CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm("+"))
    }
}
