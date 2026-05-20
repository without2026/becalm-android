package com.becalm.android.unit.source

import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypeContractTest {
    @Test
    // spec: MSG-005
    // spec: MAN-003
    fun `manual screenshot source is raw and product but text source is blocked`() {
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.ALL)
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.PRODUCT_SOURCES)
        assertFalse("manual_text" in SourceType.ALL)
        assertFalse("manual_text" in SourceType.PRODUCT_SOURCES)
    }

    @Test
    fun `recording folder sources are separate product rows`() {
        assertTrue(SourceType.VOICE in SourceType.PRODUCT_SOURCES)
        assertTrue(SourceType.CALL_RECORDING in SourceType.PRODUCT_SOURCES)
        assertTrue(SourceType.MEETING in SourceType.PRODUCT_SOURCES)
    }
}
