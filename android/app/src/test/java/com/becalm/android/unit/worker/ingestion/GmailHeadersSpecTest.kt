package com.becalm.android.unit.worker.ingestion

import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.worker.ingestion.canonicalizeEmail
import com.becalm.android.worker.ingestion.firstRecipientEmail
import com.becalm.android.worker.ingestion.gmailLabelsToFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmailHeadersSpecTest {

    @Test
    fun `EMAIL-002 canonicalizes display-name from header`() {
        assertEquals("john@example.com", canonicalizeEmail("John Doe <JOHN@EXAMPLE.COM>"))
        assertEquals("john@example.com", canonicalizeEmail(" JOHN@EXAMPLE.COM "))
        assertNull(canonicalizeEmail("   "))
    }

    @Test
    fun `EMAIL-002 first recipient parser ignores commas inside quoted display names`() {
        assertEquals(
            "jane@example.com",
            firstRecipientEmail("\"Doe, Jane\" <JANE@EXAMPLE.COM>, Bob <bob@example.com>"),
        )
    }

    @Test
    fun `EMAIL-001 gmail label mapper gives SENT precedence over INBOX`() {
        assertEquals(FOLDER_SENT, gmailLabelsToFolder(listOf("INBOX", "SENT")))
        assertEquals(FOLDER_INBOX, gmailLabelsToFolder(listOf("CATEGORY_PERSONAL", "INBOX")))
        assertNull(gmailLabelsToFolder(listOf("DRAFT")))
    }
}
