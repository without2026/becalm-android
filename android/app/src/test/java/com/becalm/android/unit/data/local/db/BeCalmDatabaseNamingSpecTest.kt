package com.becalm.android.unit.data.local.db

import com.becalm.android.data.local.db.BeCalmDatabase
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BeCalmDatabaseNamingSpecTest {

    @Test
    fun `AUTH-008 derives user hash as first 16 hex chars of SHA-256`() {
        val userId = "550e8400-e29b-41d4-a716-446655440000"

        assertEquals(expectedHash(userId), BeCalmDatabase.deriveUserIdHash(userId))
    }

    @Test
    fun `AUTH-008 builds database filename with becalm prefix and db suffix`() {
        val hash = "0123456789abcdef"

        assertEquals("becalm_0123456789abcdef.db", BeCalmDatabase.databaseFilename(hash))
    }

    @Test
    fun `AUTH-008 isolates different users into different filenames`() {
        val userAHash = BeCalmDatabase.deriveUserIdHash("user-a")
        val userBHash = BeCalmDatabase.deriveUserIdHash("user-b")

        assertNotEquals(userAHash, userBHash)
        assertNotEquals(
            BeCalmDatabase.databaseFilename(userAHash),
            BeCalmDatabase.databaseFilename(userBHash),
        )
    }

    private fun expectedHash(userId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
