package com.becalm.android.unit.domain.email

import com.becalm.android.domain.email.EmailPersonRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailPersonRefSpecTest {

    @Test
    fun `EMAIL-002 inbox resolves sender email canonically`() {
        assertEquals("john@example.com", EmailPersonRef.forInbox("John Doe <JOHN@EXAMPLE.COM>"))
        assertNull(EmailPersonRef.forInbox("   "))
    }

    @Test
    fun `EMAIL-002 sent resolves first recipient until threshold`() {
        assertEquals("a@example.com", EmailPersonRef.forSent(listOf("A@EXAMPLE.COM", "b@example.com")))
        assertNull(EmailPersonRef.forSent(emptyList()))
    }

    @Test
    fun `EMAIL-002 sent quarantines group emails over threshold`() {
        val recipients = (1..11).map { "user$it@example.com" }

        assertNull(EmailPersonRef.forSent(recipients))
        assertTrue(EmailPersonRef.isGroupEmail(11))
        assertFalse(EmailPersonRef.isGroupEmail(10))
    }
}
