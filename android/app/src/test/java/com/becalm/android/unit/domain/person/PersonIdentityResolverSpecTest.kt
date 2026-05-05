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
    fun `email and phone person ids match backend relation namespace`() {
        val email = requireNotNull(PersonIdentityResolver.resolve("user-1", "Alice <ALICE@Example.COM>"))
        val phone = requireNotNull(PersonIdentityResolver.resolve("user-1", "+82 10-1234-5678"))

        assertEquals("3f018abf-7e16-589a-a038-85eb55b60afb", email.personId)
        assertEquals("5bc62dec-5327-5fe8-a826-cbd9ff0b7419", phone.personId)
        assertEquals(
            "cae26e8a-ee05-51e5-991f-e2c3d21fd2b1",
            PersonIdentityResolver.stableIdentityId("user-1", email.identityKey),
        )
    }

    @Test
    fun `name only identity is low confidence and unverified`() {
        val result = requireNotNull(PersonIdentityResolver.resolve("user-1", "김민수"))

        assertEquals("name:김민수", result.identityKey)
        assertEquals("name", result.identityType)
        assertFalse(result.verified)
    }

    @Test
    fun `automated and role mailboxes are not treated as people`() {
        val blocked = listOf(
            "noreply@example.com",
            "no-reply@service.com",
            "webmaster@example.com",
            "return@mailer.example",
            "naverpay@naver.com",
            "billing@example.com",
            "support@example.com",
        )

        blocked.forEach { value ->
            assertTrue("$value should be automated", PersonIdentityResolver.isLikelyAutomated(value))
        }
        assertFalse(PersonIdentityResolver.isLikelyAutomated("alice@example.com"))
    }

    @Test
    fun `blocked refs normalize email anchors idempotently`() {
        val blocked = setOf("noreply@example.com", "강지훈")

        assertTrue(PersonIdentityResolver.isBlocked("No Reply <NOREPLY@example.com>", blocked))
        assertTrue(PersonIdentityResolver.isBlocked("강지훈", blocked))
        assertFalse(PersonIdentityResolver.isBlocked("jihun@example.com", blocked))
    }
}
