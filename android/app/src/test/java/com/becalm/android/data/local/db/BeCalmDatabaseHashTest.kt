package com.becalm.android.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for the per-user filename derivation introduced in S6-A.
 *
 * Covers the pure-hash contract only (no Room / Android context). The provider-level
 * integration is exercised in [BeCalmDatabaseProviderTest].
 */
public class BeCalmDatabaseHashTest {

    @Test
    public fun deriveUserIdHash_producesLowercaseHex16Chars() {
        val hash = BeCalmDatabase.deriveUserIdHash("user-abc-123")
        assertEquals("expected 16 hex chars = 8 bytes of SHA-256", 16, hash.length)
        assertEquals("hash must be lowercase hex only", hash, hash.lowercase())
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "hash contained non-hex chars: $hash"
        }
    }

    @Test
    public fun deriveUserIdHash_isDeterministic() {
        val a = BeCalmDatabase.deriveUserIdHash("fixed-user")
        val b = BeCalmDatabase.deriveUserIdHash("fixed-user")
        assertEquals("same input must yield same hash", a, b)
    }

    @Test
    public fun deriveUserIdHash_differsAcrossDistinctUsers() {
        val a = BeCalmDatabase.deriveUserIdHash("user-A")
        val b = BeCalmDatabase.deriveUserIdHash("user-B")
        assertNotEquals("distinct userIds must not collide at 16 hex chars", a, b)
    }

    @Test
    public fun deriveUserIdHash_rejectsBlankInput() {
        assertThrows(IllegalArgumentException::class.java) {
            BeCalmDatabase.deriveUserIdHash("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BeCalmDatabase.deriveUserIdHash("   ")
        }
    }

    @Test
    public fun databaseFilename_prefixesAndSuffixesHash() {
        val hash = BeCalmDatabase.deriveUserIdHash("user-1")
        val filename = BeCalmDatabase.databaseFilename(hash)
        assertEquals("becalm-$hash.db", filename)
    }

    @Test
    public fun databaseFilename_differsFromLegacyName() {
        val hash = BeCalmDatabase.deriveUserIdHash("anyone")
        assertNotEquals(
            "per-user filename must not collide with the pre-S6-A single-file name",
            BeCalmDatabase.LEGACY_DATABASE_NAME,
            BeCalmDatabase.databaseFilename(hash),
        )
    }
}
