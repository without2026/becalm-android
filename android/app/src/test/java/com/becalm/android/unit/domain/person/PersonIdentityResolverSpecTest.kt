package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonIdentityResolverSpecTest {
    @Test
    fun `email identity is normalized and verified`() {
        val result = requireNotNull(PersonIdentityResolver.resolve("user-1", "Alice <ALICE@Example.COM>"))

        assertEquals("email:alice@example.com", result.identityKey)
        assertEquals("email", result.identityType)
        assertTrue(result.verified)
    }

    @Test
    fun `phone identity keeps phone anchor`() {
        val result = requireNotNull(PersonIdentityResolver.resolve("user-1", "+82 10-1234-5678"))

        assertEquals("phone:+821012345678", result.identityKey)
        assertEquals("phone", result.identityType)
        assertTrue(result.verified)
    }

    @Test
    fun `name only identity is low confidence and unverified`() {
        val result = requireNotNull(PersonIdentityResolver.resolve("user-1", "김민수"))

        assertEquals("name:김민수", result.identityKey)
        assertEquals("name", result.identityType)
        assertFalse(result.verified)
    }
}
