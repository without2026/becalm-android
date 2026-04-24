package com.becalm.android.unit.domain.commitment

import com.becalm.android.domain.commitment.PersonRefNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonRefNormalizerSpecTest {

    @Test
    fun `blank or null person refs normalise to null`() {
        assertEquals(null, PersonRefNormalizer.normalize(null))
        assertEquals(null, PersonRefNormalizer.normalize("   "))
    }

    @Test
    fun `emails and free-form names are trimmed and lowercased`() {
        assertEquals("lee@corp.com", PersonRefNormalizer.normalize("  LEE@CORP.COM "))
        assertEquals("김철수 팀장", PersonRefNormalizer.normalize("  김철수 팀장 "))
    }

    @Test
    fun `valid E164 phone-shaped refs remain acceptable after compaction`() {
        assertTrue(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("+821012345678"))
        assertTrue(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("+82 10-1234-5678"))
    }

    @Test
    fun `invalid phone-shaped refs are rejected`() {
        assertFalse(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("010-1234-5678"))
        assertFalse(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("+021012345678"))
    }

    @Test
    fun `non phone-shaped opaque ids stay valid as free-form`() {
        assertTrue(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("lee alias #1"))
        assertTrue(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("sales-team@corp.com"))
        assertTrue(PersonRefNormalizer.isValidPhoneShapeOrFreeForm("+"))
    }
}
